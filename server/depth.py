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
        with open(hf_hub_download("depth-anything/DA3-SMALL", "config.json",
                                  revision="e08cab65ca0ec38e7826075418411ab90cab4da3")) as config:
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


class OnnxLimbo:
    def __init__(self, checkpoint, width, height, device="cpu", capture=False):
        import cv2
        import onnxruntime as ort

        options = ort.SessionOptions()
        options.intra_op_num_threads = 4
        if device == "webgpu":
            import onnxruntime_ep_webgpu as webgpu

            ort.register_execution_provider_library("aniart_webgpu", webgpu.get_library_path())
            gpu = next((d for d in ort.get_ep_devices() if d.ep_name == webgpu.get_ep_name()), None)
            if gpu is None:
                raise RuntimeError("ONNX WebGPU did not find a compatible Vulkan GPU")
            options.add_session_config_entry("session.disable_cpu_ep_fallback", "1")
            provider_options = {"powerPreference": "high-performance"}
            if capture:
                provider_options["enableGraphCapture"] = "1"
            options.add_provider_for_devices([gpu], provider_options)
            self.session = ort.InferenceSession(checkpoint, sess_options=options)
        else:
            self.session = ort.InferenceSession(checkpoint, options, providers=["CPUExecutionProvider"])
        inputs = self.session.get_inputs()
        outputs = self.session.get_outputs()
        input_types = {"tensor(float)": np.float32}
        if device == "webgpu":
            input_types["tensor(float16)"] = np.float16
        if (len(inputs) != 1 or inputs[0].shape != [1, 3, height, width]
                or inputs[0].type not in input_types or not any(out.name == "depth" for out in outputs)):
            raise ValueError(f"Unsupported Limbo ONNX input; expected 1×3×{height}×{width} and output depth")
        self.input_name = inputs[0].name
        self.input_dtype = input_types[inputs[0].type]
        self.cv = cv2
        self.mean = np.array([.485, .456, .406], dtype=np.float32)
        self.std = np.array([.229, .224, .225], dtype=np.float32)

    def __call__(self, frame, width, height):
        image = self.cv.resize(frame, (width, height)).astype(np.float32) / 255
        image = np.ascontiguousarray(((image - self.mean) / self.std).transpose(2, 0, 1)[None],
                                     dtype=self.input_dtype)
        outputs = self.session.run(["depth"], {self.input_name: image})
        if not outputs:
            raise RuntimeError("ONNX Runtime returned no depth output")
        return outputs[0].astype(np.float32, copy=False).reshape(height, width)


class DepthMap:
    def __init__(self, width, height, refine):
        import cv2

        self.cv = cv2
        self.width = width
        self.height = height
        self.refine = refine
        self.last = None
        self.last_luma = None
        if refine:
            self.guide_high = np.empty((height, width), dtype=np.uint8)
            self.guided_high = np.empty((height, width), dtype=np.float32)
            self.intercept_high = np.empty_like(self.guided_high)

    def update(self, distance, cut, frame):
        cv = self.cv
        valid = np.isfinite(distance) & (distance > 0)
        disparity = np.divide(1, np.maximum(distance, 1e-5), out=np.zeros_like(distance), where=valid)
        shift = np.zeros_like(disparity)
        if valid.any():
            lo, hi = np.percentile(disparity[valid], [5, 95])
            if hi > lo:
                shift = np.clip((disparity - lo) / (hi - lo) - .5, -.5, .5).astype(np.float32)
        h, w = distance.shape
        luma = cv.cvtColor(cv.resize(frame, (w, h)), cv.COLOR_RGB2GRAY)
        if self.last is not None and not cut:
            # Keep more history on still areas and follow changed contours faster.
            motion = cv.dilate(cv.absdiff(luma, self.last_luma), np.ones((3, 3), np.uint8))
            weight = .35 + .65 * np.clip((motion.astype(np.float32) - 7) / 32, 0, 1)
            shift = self.last + weight * (shift - self.last)
        self.last, self.last_luma = shift, luma
        return shift, luma

    def scale_map(self, depth, luma, frame):
        base = self.cv.resize(depth, (self.width, self.height))
        return self.refine_edges(base, depth, luma, frame) if self.refine else base

    def refine_edges(self, base, depth, luma, frame):
        cv = self.cv
        guide = luma.astype(np.float32) / 255
        mean_guide = cv.blur(guide, (5, 5))
        mean_depth = cv.blur(depth, (5, 5))
        variance = cv.blur(guide * guide, (5, 5)) - mean_guide * mean_guide
        covariance = cv.blur(guide * depth, (5, 5)) - mean_guide * mean_depth
        slope = covariance / (np.maximum(variance, 0) + .004)
        intercept = mean_depth - slope * mean_guide

        size = (self.width, self.height)
        cv.cvtColor(frame, cv.COLOR_RGB2GRAY, dst=self.guide_high)
        cv.resize(cv.blur(slope, (5, 5)), size, dst=self.guided_high)
        cv.resize(cv.blur(intercept, (5, 5)), size, dst=self.intercept_high)
        np.multiply(self.guided_high, self.guide_high, out=self.guided_high)
        self.guided_high *= 1 / 255
        self.guided_high += self.intercept_high
        # Limit copying texture and subtitles into depth.
        self.guided_high -= base
        base += np.clip(self.guided_high, -.08, .08, out=self.guided_high)
        return np.clip(base, -.5, .5, out=base)

    def preview(self, full=False):
        depth = self.full_map if full else self.last
        return np.rint((depth + .5) * 255).astype(np.uint8)


class Stereo(DepthMap):
    def __init__(self, width, height, strength, half_sbs, refine=False):
        super().__init__(width, height, refine)
        self.half_sbs = half_sbs
        self.x, self.y = np.meshgrid(np.arange(width, dtype=np.float32), np.arange(height, dtype=np.float32))
        edge = np.minimum(self.x[0], width - 1 - self.x[0])
        horizontal = np.clip(edge / max(1, width * .05), 0, 1)
        # The subtitle region gradually converges on the screen plane.
        vertical = np.clip((.93 - self.y[:, 0] / height) / .14, 0, 1)
        self.scale = (vertical[:, None] * horizontal * width * strength).astype(np.float32)
        self.left_x, self.right_x = np.empty_like(self.x), np.empty_like(self.x)
        self.sbs = np.empty((height, width if half_sbs else width * 2, 3), dtype=np.uint8)

    def update(self, distance, cut, frame):
        shift, luma = super().update(distance, cut, frame)
        gradient = np.abs(self.cv.Sobel(shift, self.cv.CV_32F, 1, 0, ksize=3)) * .25
        guarded = shift * (1 - .8 * np.clip(gradient / .18, 0, 1))
        self.full_map = self.scale_map(guarded, luma, frame)
        offset = self.full_map * self.scale
        np.add(self.x, offset, out=self.left_x)
        np.subtract(self.x, offset, out=self.right_x)

    def render(self, frame):
        half = self.sbs.shape[1] // 2
        left, right = self.sbs[:, :half], self.sbs[:, half:]
        if not self.half_sbs:
            self.cv.remap(frame, self.left_x, self.y, self.cv.INTER_LINEAR, dst=left,
                          borderMode=self.cv.BORDER_REPLICATE)
            self.cv.remap(frame, self.right_x, self.y, self.cv.INTER_LINEAR, dst=right,
                          borderMode=self.cv.BORDER_REPLICATE)
        else:
            size = (half, self.height)
            for coords, target in ((self.left_x, left), (self.right_x, right)):
                eye = self.cv.remap(frame, coords, self.y, self.cv.INTER_LINEAR,
                                    borderMode=self.cv.BORDER_REPLICATE)
                self.cv.resize(eye, size, dst=target, interpolation=self.cv.INTER_AREA)
        return self.sbs


class Layers(DepthMap):
    BAND_HEIGHT = 192

    def __init__(self, width, height, refine):
        super().__init__(width, height, refine)
        self.atlas = np.empty((height + self.BAND_HEIGHT, width, 3), dtype=np.uint8)

    def update(self, distance, cut, frame):
        shift, luma = super().update(distance, cut, frame)
        # Keep the real depth boundary for segmentation. Attenuation around
        # gradients is only needed when stretching pixels for SBS.
        self.full_map = self.scale_map(shift, luma, frame)
        band = self.cv.resize(self.full_map, (self.width, self.BAND_HEIGHT), interpolation=self.cv.INTER_AREA)
        gray = np.rint(255 * (band + .5)).astype(np.uint8)
        self.atlas[self.height:] = gray[:, :, None]

    def render(self, frame):
        self.atlas[:self.height] = frame
        return self.atlas
