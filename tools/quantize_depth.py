"""Quantize constant MatMul weights for the CPU benchmark; keep other layers in FP32."""

import sys

import numpy as np
import onnxruntime as ort
from onnxruntime.quantization import QuantType, quantize_dynamic

source, target = sys.argv[1:]
quantize_dynamic(source, target, per_channel=True, weight_type=QuantType.QInt8,
                 op_types_to_quantize=["MatMul"], extra_options={"MatMulConstBOnly": True})

options = ort.SessionOptions()
options.intra_op_num_threads = 2
session = ort.InferenceSession(target, options, providers=["CPUExecutionProvider"])
image = session.get_inputs()[0]
frame = np.random.default_rng(42).random(image.shape, dtype=np.float32)
for depth in session.run(None, {image.name: frame}):
    if not np.isfinite(depth).all():
        raise ValueError("Quantized model produced non-finite values")
