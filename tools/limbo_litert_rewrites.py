"""Bake the trained Limbo decoder's positional features for a fixed input shape."""

import types

import torch

from depth_anything_3.model import dpt, dualdpt
from depth_anything_3.model.utils import head_utils


def freeze_decoder(model, example):
    positions = {}
    original_position = dualdpt.DualDPT._add_pos_embed
    original_resize = head_utils.custom_interpolate

    def position_key(x, w, h, ratio):
        return (*x.shape, w, h, ratio)

    def capture_position(self, x, w, h, ratio=.1):
        key = position_key(x, w, h, ratio)
        if key not in positions:
            positions[key] = original_position(self, torch.zeros_like(x), w, h, ratio)
        return x + positions[key]

    head = model.h
    head._add_pos_embed = types.MethodType(capture_position, head)
    with torch.no_grad():
        model(example)

    def position(self, x, w, h, ratio=.1):
        return x + positions[position_key(x, w, h, ratio)]

    def resize(x, size=None, scale_factor=None, mode="bilinear", align_corners=True):
        return original_resize(x, size, scale_factor, mode, align_corners=False)

    head._add_pos_embed = types.MethodType(position, head)
    head_utils.custom_interpolate = dualdpt.custom_interpolate = dpt.custom_interpolate = resize
