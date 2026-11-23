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


def export_fp32(model, example, path):
    encoder = model.backbone.pretrained
    rotary_grid = encoder.position_getter(1, model.height // encoder.patch_size,
                                          model.width // encoder.patch_size, example.device)
    add_position = model.head._add_pos_embed
    positions = {}

    def add_cached_position(features, width, height, ratio=.1):
        key = (tuple(int(d) for d in features.shape), width, height, ratio)
        if key not in positions:
            positions[key] = add_position(torch.zeros_like(features), width, height, ratio)
        return features + positions[key]

    # Compute static coordinates eagerly. Tracing the grid builders can emit
    # unsupported Cartesian products and mixed FP32/FP64 ONNX coordinates.
    with (
        torch.no_grad(),
        patch.object(encoder, "position_getter", lambda *args, **kwargs: rotary_grid),
        patch.object(model.head, "_add_pos_embed", add_cached_position),
    ):
        model(example)
        torch.onnx.export(
            model, (example,), path, opset_version=17, dynamo=False,
            input_names=["image"], output_names=["depth"],
        )


def cpu_session(path, optimized_path=None):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.log_severity_level = 3
    if optimized_path is not None:
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
        options.optimized_model_filepath = str(optimized_path)
    return ort.InferenceSession(path, options, providers=["CPUExecutionProvider"])


def verify(path, model, cases):
    session = cpu_session(path)
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
            folded = Path(temp) / "folded.onnx"
            fp16 = Path(temp) / filename
            export_fp32(model, cases["pattern"], fp32)
            cpu_session(fp32, optimized_path=folded)
            graph = convert_float_to_float16(onnx.load(folded))
            OnnxModel(graph).topological_sort()
            del graph.graph.value_info[:]
            onnx.helper.set_model_props(graph, metadata | {"aniart_export": f"Fixed {width}x{height} FP16"})
            onnx.checker.check_model(graph, full_check=True)
            onnx.save(graph, fp16)
            verify(fp16, model, cases)
            fp16.replace(args.output_dir / filename)


if __name__ == "__main__":
    main()
