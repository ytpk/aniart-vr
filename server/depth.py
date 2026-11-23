"""Limbo V3 monocular inference and side-by-side synthesis."""

import numpy as np


class Limbo:
    def __init__(self, checkpoint, device):
        import torch
        from depth_anything_3.cfg import create_object
        from huggingface_hub import hf_hub_download
        from safetensors.torch import load_file
        import json

        self.torch = torch
        self.device = device
        if device == "cuda" and not torch.cuda.is_available():
            raise RuntimeError("AMD ROCm PyTorch GPU unavailable")
        with open(hf_hub_download("depth-anything/DA3-SMALL", "config.json")) as config:
            net = create_object(json.load(config)["config"]).eval()
        state = {key.removeprefix("model."): value for key, value in load_file(checkpoint).items()}
        missing, _ = net.load_state_dict(state, strict=False)
        if any(key.startswith(("backbone.", "head.")) for key in missing):
            raise ValueError("Checkpoint is missing Limbo depth weights")
        self.net = net.to(device)
        self.mean = torch.tensor([.485, .456, .406], device=device)[None, :, None, None]
        self.std = torch.tensor([.229, .224, .225], device=device)[None, :, None, None]

    def __call__(self, frame, width, height):
        torch = self.torch
        image = torch.from_numpy(frame).to(self.device).permute(2, 0, 1)[None].float() / 255
        image = torch.nn.functional.interpolate(image, (height, width), mode="bilinear", align_corners=False)
        image = (image - self.mean) / self.std
        with torch.inference_mode(), torch.autocast(self.device, dtype=torch.float16, enabled=self.device == "cuda"):
            features, _ = self.net.backbone(image.unsqueeze(1), cam_token=None)
            depth = self.net.head(features, height, width, patch_start_idx=0)["depth"]
            return depth.float().reshape(height, width).cpu().numpy()


class Stereo:
    def __init__(self, width, height, strength, half_sbs):
        import cv2

        self.cv = cv2
        self.width = width
        self.height = height
        self.strength = strength
        self.half_sbs = half_sbs
        self.x, self.y = np.meshgrid(np.arange(width, dtype=np.float32), np.arange(height, dtype=np.float32))
        edge = np.minimum(self.x, width - 1 - self.x)
        self.fade = np.clip(edge / max(1, width * .05), 0, 1)
        self.last = None

    def update(self, distance, cut):
        cv = self.cv
        valid = np.isfinite(distance) & (distance > 0)
        disparity = np.divide(1, np.maximum(distance, 1e-5), out=np.zeros_like(distance), where=valid)
        shift = np.zeros_like(disparity)
        if valid.any():
            lo, hi = np.percentile(disparity[valid], [5, 95])
            if hi > lo:
                shift = np.clip((disparity - lo) / (hi - lo) - .5, -.5, .5).astype(np.float32)
        if self.last is not None and not cut:
            shift = .65 * self.last + .35 * shift
        self.last = shift
        gradient = np.abs(cv.Sobel(shift, cv.CV_32F, 1, 0, ksize=3)) * .25
        shift *= 1 - .8 * np.clip(gradient / .18, 0, 1)
        self.shift = cv.resize(shift, (self.width, self.height)) * self.fade * self.width * self.strength
        # The subtitle region gradually converges on the screen plane.
        self.shift *= np.clip((.93 - self.y / self.height) / .14, 0, 1)
        self.shift = self.shift.astype(np.float32, copy=False)

    def render(self, frame):
        left = self.cv.remap(frame, self.x + self.shift, self.y, self.cv.INTER_LINEAR,
                             borderMode=self.cv.BORDER_REPLICATE)
        right = self.cv.remap(frame, self.x - self.shift, self.y, self.cv.INTER_LINEAR,
                              borderMode=self.cv.BORDER_REPLICATE)
        if self.half_sbs:
            size = (self.width // 2, self.height)
            left = self.cv.resize(left, size, interpolation=self.cv.INTER_AREA)
            right = self.cv.resize(right, size, interpolation=self.cv.INTER_AREA)
        return np.concatenate((left, right), axis=1)
