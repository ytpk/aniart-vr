#!/usr/bin/env python3
"""Export fixed-size Limbo V3 FP16 models for the PC benchmark."""

import argparse
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

import numpy as np
import onnx
import onnxruntime as ort
import torch
from onnxruntime.transformers.float16 import convert_float_to_float16
from onnxruntime.transformers.onnx_model import OnnxModel
from safetensors import safe_open

from depth import Limbo


class MonoDepth(torch.nn.Module):
    def __init__(self, net, width, height):
        super().__init__()
        self.backbone = net.backbone
        self.head = net.head
        self.width, self.height = width, height

    def forward(self, image):
        features, _ = self.backbone(image.unsqueeze(1), cam_token=None)
        depth = self.head(features, self.height, self.width, patch_start_idx=0)["depth"]
        return depth.reshape(1, self.height, self.width)


def verify(path, model, cases):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.log_severity_level = 3
    session = ort.InferenceSession(path, options, providers=["CPUExecutionProvider"])
    with torch.no_grad():
        for name, image in cases.items():
            expected = model(image).numpy()
            actual, = session.run(["depth"], {"image": image.numpy().astype(np.float16)})
            if actual.shape != expected.shape or not np.isfinite(actual).all() or not (actual > 0).all():
                raise ValueError(f"{name}: invalid FP16 depth shape or values")
            correlation = np.corrcoef(expected.ravel(), actual.ravel())[0, 1]
            print(f"{name}: {actual.shape}, FP32 vs FP16 Pearson {correlation:.5f}", flush=True)
            if not np.isfinite(correlation) or correlation < .98:
                raise ValueError(f"{name}: FP16 depth differs from the original model")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    torch.set_num_threads(4)
    net = Limbo(args.checkpoint, "cpu").net
    with safe_open(args.checkpoint, framework="pt", device="cpu") as weights:
        metadata = weights.metadata() or {}
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for width, height in ((700, 392), (1274, 714)):
        print(f"Exporting Limbo V3 {width}x{height}", flush=True)
        model = MonoDepth(net, width, height).eval()
        # Exercise the blank frame used to initialize the player and a patterned frame.
        black = (-torch.tensor([.485, .456, .406]) / torch.tensor([.229, .224, .225]))[None, :, None, None]
        cases = {
            "black": black.expand(1, 3, height, width).contiguous(),
            "pattern": torch.rand((1, 3, height, width), generator=torch.Generator().manual_seed(40)) * 4 - 2,
        }
        filename = f"Limbo-{width}x{height}-fp16.onnx"
        with TemporaryDirectory(dir=args.output_dir) as temp:
            fp32 = Path(temp) / "fp32.onnx"
            fp16 = Path(temp) / filename
            # Fixed inputs allow baking the exact rotary grid before tracing.
            # The legacy ONNX exporter cannot translate torch.cartesian_prod.
            encoder = net.backbone.pretrained
            positions = encoder.position_getter(1, height // encoder.patch_size,
                                                width // encoder.patch_size, cases["pattern"].device)
            with torch.no_grad(), patch.object(encoder, "position_getter", lambda *args, **kwargs: positions):
                torch.onnx.export(
                    model, (cases["pattern"],), fp32, opset_version=17, dynamo=False,
                    input_names=["image"], output_names=["depth"],
                )
            graph = convert_float_to_float16(onnx.load(fp32))
            OnnxModel(graph).topological_sort()
            del graph.graph.value_info[:]
            onnx.helper.set_model_props(graph, metadata | {"aniart_export": f"Fixed {width}x{height} FP16"})
            onnx.checker.check_model(graph, full_check=True)
            onnx.save(graph, fp16)
            verify(fp16, model, cases)
            fp16.replace(args.output_dir / filename)


if __name__ == "__main__":
    main()
