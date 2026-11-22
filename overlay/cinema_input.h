#pragma once
#include <jni.h>
#include <openxr/openxr.h>
#include <algorithm>
#include <cstdint>

namespace Cinema {
inline JNIEnv* env = nullptr;
inline jobject activity = nullptr;
inline uint64_t renderFrame = 0;
inline uint32_t eye = 0;
inline int config = 1 | (1 << 21);
inline float displayHz = 0;
inline bool eyeMsaa = false;
inline float screenX = 0;
inline float screenY = 0;
inline jmethodID commandMethod = nullptr;

struct Controller {
    XrPosef pose{};
    bool tracked = false;
    bool pressed = false;
};
inline Controller controllers[2]{};

inline void Command(int command) {
    if (commandMethod) env->CallVoidMethod(activity, commandMethod, command);
}

inline void MoveScreen(float x, float y, float seconds) {
    const float step = std::clamp(seconds, 0.0f, 0.05f) * 1.5f;
    if (x < -0.19f || x > 0.19f) screenX = std::clamp(screenX + x * step, -1.0f, 1.0f);
    if (y < -0.19f || y > 0.19f) screenY = std::clamp(screenY + y * step, -0.5f, 0.7f);
}
} // namespace Cinema
