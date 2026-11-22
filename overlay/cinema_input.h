#pragma once
#include <jni.h>
#include <cstdint>
#include <algorithm>

namespace Cinema {
inline JNIEnv* env = nullptr;
inline jobject activity = nullptr;
inline uint64_t renderFrame = 0;
inline bool fast = false;
inline bool catalog = true;
inline int size = 1;
inline float screenX = 0;
inline float screenY = 0;

inline void MoveScreen(float x, float y, float seconds) {
    constexpr float deadzone = 0.19f;
    const float step = std::clamp(seconds, 0.0f, 0.05f) * 1.5f;
    if (x < -deadzone || x > deadzone) screenX = std::clamp(screenX + x * step, -1.0f, 1.0f);
    if (y < -deadzone || y > deadzone) screenY = std::clamp(screenY + y * step, -0.5f, 0.7f);
}

inline void Command(int command) {
    if (command == 2) fast = !fast;
    else if (command == 3) catalog = !catalog;
    else if (command == 4) size = (size + 1) % 3;
    else {
        auto cls = env->GetObjectClass(activity);
        env->CallVoidMethod(activity, env->GetMethodID(cls, "command", "(I)V"), command);
        env->DeleteLocalRef(cls);
        if (command != 0) catalog = true;
    }
}
} // namespace Cinema
