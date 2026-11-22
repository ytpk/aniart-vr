"""Adapt the pinned DA3 conversion to Limbo V3 and verify it against the ONNX export."""

from pathlib import Path
import sys

import numpy as np
import onnxruntime as ort
from PIL import Image

# The sharp boundaries and smooth areas exercise different parts of the decoder.
y, x = np.indices((280, 504))
foreground = ((x - 220) / 103) ** 2 + ((y - 126) / 93) ** 2 < 1
rgb = np.stack([np.where(foreground, 212, 35 + x / 4),
                np.where(foreground, 50 + y / 3, 30 + y / 4),
                np.where(foreground, 105, 157 + x / 10)], axis=-1).astype(np.uint8)
Image.fromarray(rgb).save("frame.png")

source = Path("convert_da3.py").read_text()
# Use the runner's real SciPy. The upstream script's local PROPACK shim
# breaks the sparse max-flow routine used during converter layout passes.
source = source[:source.index("class _Dummy:")] + source[source.index("sys.path.insert"):]
original = '''sd = load_file(hf_hub_download("depth-anything/DA3-SMALL", "model.safetensors"))
net.load_state_dict({(k[6:] if k.startswith("model.") else k): v for k, v in sd.items()}, strict=False)'''
replacement = '''sd = load_file("Limbo.safetensors")
state = {k.removeprefix("model."): v for k, v in sd.items()}
missing, unexpected = net.load_state_dict(state, strict=False)
required = [k for k in missing if k.startswith(("backbone.", "head."))]
if required:
    raise ValueError(f"Limbo checkpoint is missing inference weights: {required[:12]}")'''
if source.count(original) != 1:
    raise ValueError("Pinned DA3 converter has an unexpected checkpoint loader")
# Preserve the trained backbone's actual positional interpolation. Its offset
# and antialias settings are part of the original forward pass.
start = source.index("GH, GW =")
end = source.index("# ---- SELECT_V2 fix", start)
source = source[:start] + """
for mod in net.modules():
    if hasattr(mod, "interpolate_pos_encoding") and hasattr(mod, "pos_embed"):
        with torch.no_grad():
            tokens = torch.empty(1, H_IN // 14 * (W_IN // 14) + 1, mod.pos_embed.shape[-1])
            baked = mod.interpolate_pos_encoding(tokens, H_IN, W_IN)
        mod.register_buffer("_baked_pos", baked)
        mod.interpolate_pos_encoding = _ty.MethodType(lambda self, x, w, h: self._baked_pos, mod)
""" + source[end:]
start = source.index("# ---- DPT head:")
end = source.index("with torch.no_grad():\n    d_clean", start)
source = source[:start] + """
from limbo_litert_rewrites import freeze_decoder
freeze_decoder(m, x_img)
""" + source[end:]
sys.argv = ["convert_da3.py", "frame.png", "280", "504"]
exec(compile(source.replace(original, replacement), "convert_da3.py", "exec"), namespace := {})

options = ort.SessionOptions()
options.intra_op_num_threads = 2
reference = ort.InferenceSession("Limbo.onnx", options, providers=["CPUExecutionProvider"])
frame = namespace["x_img"].numpy()
expected = reference.run(None, {reference.get_inputs()[0].name: frame})[0].squeeze()
interpreter = namespace["it"]
interpreter.set_tensor(interpreter.get_input_details()[0]["index"], frame)
interpreter.invoke()
actual = interpreter.get_tensor(interpreter.get_output_details()[0]["index"]).squeeze()
if expected.shape != (280, 504) or actual.shape != expected.shape or not np.isfinite(actual).all():
    raise ValueError(f"Unexpected depth output: ONNX {expected.shape}, LiteRT {actual.shape}")
correlation = np.corrcoef(expected.flat, actual.flat)[0, 1]
print(f"Limbo LiteRT vs ONNX: Pearson={correlation:.6f}")
if not np.isfinite(correlation) or correlation < .95:
    raise ValueError("LiteRT depth differs from the ONNX reference")
