"""Freeze Limbo decoder positions and align-corners resize for a fixed shape."""

import types

import torch
from torch.nn import functional as F

from depth_anything_3.model import dpt, dualdpt
from depth_anything_3.model.utils import head_utils


def freeze_decoder(model, example):
    positions = {}
    kernels = {}
    original_position = dualdpt.DualDPT._add_pos_embed
    original_resize = head_utils.custom_interpolate

    def position_key(x, w, h, ratio):
        return (*x.shape, w, h, ratio)

    def capture_position(self, x, w, h, ratio=.1):
        key = position_key(x, w, h, ratio)
        if key not in positions:
            positions[key] = original_position(self, torch.zeros_like(x), w, h, ratio)
        return x + positions[key]

    def resize_shape(x, size, scale):
        return tuple(size) if size is not None else (int(x.shape[-2] * scale), int(x.shape[-1] * scale))

    def axis_kernel(src, dst):
        # 1x1 convolution over the spatial axis moved into channels.
        # Each output channel is the weighted sum of its two aligned neighbors.
        coord = torch.linspace(0, src - 1, dst)
        base = coord.floor().long()
        alpha = coord - base
        kernel = torch.zeros(dst, src, 1, 1)
        out = torch.arange(dst)
        kernel[out, base, 0, 0] = 1 - alpha
        kernel[out, (base + 1).clamp(max=src - 1), 0, 0] += alpha
        return kernel

    def capture_resize(x, size=None, scale_factor=None, mode="bilinear", align_corners=True):
        if mode == "bilinear" and align_corners:
            h, w = x.shape[-2:]
            dh, dw = resize_shape(x, size, scale_factor)
            if h != dh and (h, dh) not in kernels:
                kernels[h, dh] = axis_kernel(h, dh)
            if w != dw and (w, dw) not in kernels:
                kernels[w, dw] = axis_kernel(w, dw)
        return original_resize(x, size, scale_factor, mode, align_corners)

    def set_resize(fn):
        head_utils.custom_interpolate = dualdpt.custom_interpolate = dpt.custom_interpolate = fn

    head = model.h
    head._add_pos_embed = types.MethodType(capture_position, head)
    set_resize(capture_resize)
    with torch.no_grad():
        model(example)

    def position(self, x, w, h, ratio=.1):
        return x + positions[position_key(x, w, h, ratio)]

    def resize(x, size=None, scale_factor=None, mode="bilinear", align_corners=True):
        if mode != "bilinear" or not align_corners:
            return original_resize(x, size, scale_factor, mode, align_corners)
        h, w = x.shape[-2:]
        dh, dw = resize_shape(x, size, scale_factor)
        if w != dw:
            x = F.conv2d(x.transpose(1, 3), kernels[w, dw]).transpose(1, 3)
        if h != dh:
            x = F.conv2d(x.transpose(1, 2), kernels[h, dh]).transpose(1, 2)
        return x

    head._add_pos_embed = types.MethodType(position, head)
    set_resize(resize)
