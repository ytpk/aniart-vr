"""Static-shape replacements for Limbo decoder operations that affect map fidelity."""

import types

import torch

from depth_anything_3.model import dpt, dualdpt
from depth_anything_3.model.utils import head_utils


def freeze_decoder(model, example):
    """Record the exact positional constants and bilinear weights before export."""
    positions = {}
    weights = {}
    original_position = dualdpt.DualDPT._add_pos_embed
    original_resize = head_utils.custom_interpolate

    def position_key(x, w, h, ratio):
        return (*x.shape, w, h, ratio)

    def freeze_position(self, x, w, h, ratio=.1):
        key = position_key(x, w, h, ratio)
        if key not in positions:
            positions[key] = original_position(self, torch.zeros_like(x), w, h, ratio)
        return x + positions[key]

    def resize_shape(x, size, scale):
        return tuple(size) if size is not None else (int(x.shape[-2] * scale), int(x.shape[-1] * scale))

    def axis_weights(src, dst):
        coord = torch.linspace(0, src - 1, dst)
        base = coord.floor().long()
        alpha = coord - base
        result = torch.zeros(src, dst)
        out = torch.arange(dst)
        result[base, out] = 1 - alpha
        result[(base + 1).clamp(max=src - 1), out] += alpha
        return result

    def freeze_resize(x, size=None, scale_factor=None, mode="bilinear", align_corners=True):
        if mode == "bilinear" and align_corners:
            h, w = x.shape[-2:]
            dh, dw = resize_shape(x, size, scale_factor)
            weights.setdefault((h, w, dh, dw), (axis_weights(h, dh), axis_weights(w, dw)))
        return original_resize(x, size, scale_factor, mode, align_corners)

    def set_resize(fn):
        head_utils.custom_interpolate = dualdpt.custom_interpolate = dpt.custom_interpolate = fn

    head = model.h
    head._add_pos_embed = types.MethodType(freeze_position, head)
    set_resize(freeze_resize)
    with torch.no_grad():
        model(example)

    def position(self, x, w, h, ratio=.1):
        return x + positions[position_key(x, w, h, ratio)]

    def resize(x, size=None, scale_factor=None, mode="bilinear", align_corners=True):
        if mode != "bilinear" or not align_corners:
            return original_resize(x, size, scale_factor, mode, align_corners)
        h, w = x.shape[-2:]
        dh, dw = resize_shape(x, size, scale_factor)
        wy, wx = weights[h, w, dh, dw]
        if w != dw:
            x = x @ wx
        if h != dh:
            x = (x.transpose(-2, -1) @ wy).transpose(-2, -1)
        return x

    head._add_pos_embed = types.MethodType(position, head)
    set_resize(resize)
