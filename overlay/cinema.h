// OpenXR cinema rendering. Include on the Android GLES path only.
#pragma once
#include <jni.h>
#include <EGL/egl.h>
#include <chrono>
#include <cmath>
#include <cstdio>
#include "anime4k_generated.h"
#include "cinema_input.h"

namespace Cinema {
inline GLuint Program(const char* vertex, const char* fragment) {
    auto compile = [](GLenum type, const char* source) {
        GLuint shader = glCreateShader(type);
        glShaderSource(shader, 1, &source, nullptr);
        glCompileShader(shader);
        GLint ok = 0;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
        if (!ok) {
            char log[2048]{};
            glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
            glDeleteShader(shader);
            THROW(std::string(log));
        }
        return shader;
    };
    GLuint vs = compile(GL_VERTEX_SHADER, vertex);
    GLuint fs = compile(GL_FRAGMENT_SHADER, fragment);
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[2048]{};
        glGetProgramInfoLog(program, sizeof(log), nullptr, log);
        glDeleteProgram(program);
        THROW(std::string(log));
    }
    return program;
}

class Screen {
    static constexpr const char* vertex = R"glsl(#version 300 es
uniform mat4 mvp;
out vec2 uv;
void main() {
    uv = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    gl_Position = mvp * vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
)glsl";
    static constexpr const char* image = R"glsl(#version 300 es
precision highp float;
uniform sampler2D frame;
uniform vec4 tint;
uniform int mode;
in vec2 uv;
out vec4 color;
void main() {
    if (mode == 0) color = tint;
    else if (mode == 1) color = texture(frame, uv);
    else color = texture(frame, vec2(uv.x, 1.0-uv.y));
}
)glsl";
    static constexpr const char* externalImage = R"glsl(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES frame;
uniform mat4 transform;
in vec2 uv;
out vec4 color;
void main() {
    color = texture(frame, (transform * vec4(uv, 0.0, 1.0)).xy);
}
)glsl";

    GLuint oes = 0, panel = 0, vao = 0, fbo = 0, copy = 0, flat = 0;
    GLuint textures[6]{}, passes[5]{};
    jfloatArray transformArray = nullptr;
    jmethodID latch = nullptr, upload = nullptr, close = nullptr;
    bool opened = false, filtered = false, canFilter = false;
    int width = 0, height = 0;
    bool targetsAllocated = false;
    float transform[16]{};
    float fps = 0, gpuMs = -1;
    GLuint query = 0;
    bool queryPending = false;
    PFNGLGENQUERIESEXTPROC genQuery = nullptr;
    PFNGLDELETEQUERIESEXTPROC deleteQuery = nullptr;
    PFNGLBEGINQUERYEXTPROC beginQuery = nullptr;
    PFNGLENDQUERYEXTPROC endQuery = nullptr;
    PFNGLGETQUERYOBJECTUIVEXTPROC queryStatus = nullptr;
    PFNGLGETQUERYOBJECTUI64VEXTPROC queryTime = nullptr;
    int frames = 0;
    std::chrono::steady_clock::time_point last = std::chrono::steady_clock::now();

    static void Texture(GLenum target, GLuint id, GLenum filter) {
        glBindTexture(target, id);
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(target, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    void Allocate() {
        targetsAllocated = false;
        if (!canFilter) return;
        GLint maxSize = 0;
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxSize);
        // Limit bandwidth and memory in the initial Quest preset.
        if (width > 1920 || height > 1080 || width * 2 > maxSize || height * 2 > maxSize) return;
        for (int i = 0; i < 6; ++i) {
            Texture(GL_TEXTURE_2D, textures[i], i == 0 || i == 5 ? GL_LINEAR : GL_NEAREST);
            int scale = i == 5 ? 2 : 1;
            glTexImage2D(GL_TEXTURE_2D, 0, i == 0 || i == 5 ? GL_RGBA8 : GL_RGBA16F,
                         width * scale, height * scale, 0, GL_RGBA,
                         i == 0 || i == 5 ? GL_UNSIGNED_BYTE : GL_HALF_FLOAT, nullptr);
        }
        targetsAllocated = true;
    }

    bool Target(GLuint texture, int w, int h) {
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) return false;
        glViewport(0, 0, w, h);
        return true;
    }

    static void Matrix(GLuint program, const char* name, const float* matrix) {
        glUniformMatrix4fv(glGetUniformLocation(program, name), 1, GL_FALSE, matrix);
    }

    void Process() {
        filtered = false;
        if (!canFilter || width <= 0 || height <= 0 || width > 1920 || height > 1080) return;
        XrMatrix4x4f identity;
        XrMatrix4x4f_CreateIdentity(&identity);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glBindVertexArray(vao);
        if (!Target(textures[0], width, height)) {
            canFilter = false;
            return;
        }
        glUseProgram(copy);
        Matrix(copy, "mvp", identity.m);
        Matrix(copy, "transform", transform);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, oes);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        for (int i = 0; i < 5; ++i) {
            int scale = i == 4 ? 2 : 1;
            if (!Target(textures[i + 1], width * scale, height * scale)) {
                canFilter = false;
                glActiveTexture(GL_TEXTURE0);
                return;
            }
            GLuint program = passes[i];
            glUseProgram(program);
            Matrix(program, "mvp", identity.m);
            glUniform2f(glGetUniformLocation(program, "sourceSize"), float(width), float(height));
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, textures[i]);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, textures[0]);
            glUniform1i(glGetUniformLocation(program, "original"), 1);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        }
        glActiveTexture(GL_TEXTURE0);
        filtered = true;
    }

    void Quad(const XrMatrix4x4f& vp, XrVector3f center, float w, float h,
              XrQuaternionf rotation, GLuint texture, int mode, float r = 0, float g = 0, float b = 0) {
        XrMatrix4x4f model, mvp;
        XrVector3f scale{w * 0.5f, h * 0.5f, 1};
        XrMatrix4x4f_CreateTranslationRotationScale(&model, &center, &rotation, &scale);
        XrMatrix4x4f_Multiply(&mvp, &vp, &model);
        GLuint program = mode == 3 ? copy : flat;
        glUseProgram(program);
        Matrix(program, "mvp", mvp.m);
        glActiveTexture(GL_TEXTURE0);
        if (mode == 3) {
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture);
            Matrix(program, "transform", transform);
        } else {
            glBindTexture(GL_TEXTURE_2D, texture);
            glUniform1i(glGetUniformLocation(program, "mode"), mode);
            glUniform4f(glGetUniformLocation(program, "tint"), r, g, b, 1);
        }
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

public:
    void Open() {
        auto cls = env->GetObjectClass(activity);
        jmethodID open = env->GetMethodID(cls, "openTexture", "(I)V");
        close = env->GetMethodID(cls, "closeTexture", "()V");
        latch = env->GetMethodID(cls, "latch", "([F)J");
        upload = env->GetMethodID(cls, "uploadPanel", "(ZFF)V");
        env->DeleteLocalRef(cls);
        auto array = env->NewFloatArray(16);
        transformArray = static_cast<jfloatArray>(env->NewGlobalRef(array));
        env->DeleteLocalRef(array);
        glGenVertexArrays(1, &vao);
        glGenFramebuffers(1, &fbo);
        glGenTextures(1, &oes);
        glGenTextures(1, &panel);
        glGenTextures(6, textures);
        Texture(GL_TEXTURE_EXTERNAL_OES, oes, GL_LINEAR);
        Texture(GL_TEXTURE_2D, panel, GL_LINEAR);
        copy = Program(vertex, externalImage);
        flat = Program(vertex, image);
        try {
            for (int i = 0; i < 5; ++i) passes[i] = Program(vertex, Anime4KPasses[i]);
            canFilter = true;
        } catch (const std::exception& error) {
            Log::Write(Log::Level::Error, std::string("Anime4K: ") + error.what());
        }
        GLint extensions = 0;
        glGetIntegerv(GL_NUM_EXTENSIONS, &extensions);
        for (GLint i = 0; i < extensions; ++i) {
            auto extension = reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, i));
            if (std::string(extension) != "GL_EXT_disjoint_timer_query") continue;
            genQuery = reinterpret_cast<PFNGLGENQUERIESEXTPROC>(eglGetProcAddress("glGenQueriesEXT"));
            deleteQuery = reinterpret_cast<PFNGLDELETEQUERIESEXTPROC>(eglGetProcAddress("glDeleteQueriesEXT"));
            beginQuery = reinterpret_cast<PFNGLBEGINQUERYEXTPROC>(eglGetProcAddress("glBeginQueryEXT"));
            endQuery = reinterpret_cast<PFNGLENDQUERYEXTPROC>(eglGetProcAddress("glEndQueryEXT"));
            queryStatus = reinterpret_cast<PFNGLGETQUERYOBJECTUIVEXTPROC>(eglGetProcAddress("glGetQueryObjectuivEXT"));
            queryTime = reinterpret_cast<PFNGLGETQUERYOBJECTUI64VEXTPROC>(eglGetProcAddress("glGetQueryObjectui64vEXT"));
            if (genQuery && deleteQuery && beginQuery && endQuery && queryStatus && queryTime) genQuery(1, &query);
            break;
        }
        env->CallVoidMethod(activity, open, oes);
        opened = !env->ExceptionCheck();
        if (!opened) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            THROW("Cannot create decoder surface");
        }
        UpdatePanel();
    }

    void UpdatePanel() {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, panel);
        env->CallVoidMethod(activity, upload, jboolean(fast && filtered), fps, fast && filtered ? gpuMs : -1.0f);
    }

    void PrepareFrame() {
        const auto now = std::chrono::steady_clock::now();
        ++frames;
        const float elapsed = std::chrono::duration<float>(now - last).count();
        if (elapsed >= 0.5f) {
            fps = frames / elapsed;
            frames = 0;
            last = now;
            if (catalog) UpdatePanel();
        }
        if (queryPending) {
            GLuint available = 0;
            queryStatus(query, GL_QUERY_RESULT_AVAILABLE_EXT, &available);
            if (available) {
                GLuint64 nanos = 0;
                GLint disjoint = 0;
                queryTime(query, GL_QUERY_RESULT_EXT, &nanos);
                glGetIntegerv(GL_GPU_DISJOINT_EXT, &disjoint);
                gpuMs = disjoint ? -1.0f : float(nanos) / 1000000.0f;
                queryPending = false;
            }
        }
        jlong format = env->CallLongMethod(activity, latch, transformArray);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return;
        }
        if (format) {
            int w = int(format >> 32), h = int(format & 0xffffffff);
            env->GetFloatArrayRegion(transformArray, 0, 16, transform);
            if (w != width || h != height) {
                width = w;
                height = h;
                targetsAllocated = false;
            }
        }
        if (fast && (format || !filtered) && width > 0 && canFilter && width <= 1920 && height <= 1080) {
            if (!targetsAllocated) Allocate();
            if (!targetsAllocated) return;
            bool measure = query && !queryPending;
            if (measure) beginQuery(GL_TIME_ELAPSED_EXT, query);
            Process();
            if (measure) {
                endQuery(GL_TIME_ELAPSED_EXT);
                queryPending = true;
            }
        } else if (!fast) {
            filtered = false;
        }
    }

    void Draw(const XrMatrix4x4f& vp) {
        constexpr XrQuaternionf front{0, 0, 0, 1};
        constexpr XrQuaternionf floor{-0.70710678f, 0, 0, 0.70710678f};
        constexpr XrQuaternionf side{0, 0.70710678f, 0, 0.70710678f};
        glDisable(GL_CULL_FACE);
        glBindVertexArray(vao);
        Quad(vp, {0, -1.35f, -2.4f}, 7, 9, floor, 0, 0, .045f, .038f, .065f);
        Quad(vp, {0, 0.5f, -5.85f}, 7, 3.7f, front, 0, 0, .075f, .065f, .10f);
        Quad(vp, {-3.5f, 0.5f, -2.4f}, 7, 3.7f, side, 0, 0, .055f, .045f, .08f);
        Quad(vp, {3.5f, 0.5f, -2.4f}, 7, 3.7f, side, 0, 0, .055f, .045f, .08f);
        for (float x : {-2.05f, 2.05f}) {
            Quad(vp, {x, -1.33f, -2.4f}, .045f, 7, floor, 0, 0, .24f, .14f, .38f);
        }
        const float aspect = width && height ? float(width) / height : 16.0f / 9.0f;
        const float w = size == 0 ? 2.8f : size == 1 ? 3.8f : 4.8f;
        const float h = std::min(w / aspect, 2.45f);
        const float actualWidth = h * aspect;
        Quad(vp, {screenX, .38f + screenY, -4.25f}, actualWidth + .14f, h + .14f, front, 0, 0, .19f, .13f, .28f);
        Quad(vp, {screenX, .38f + screenY, -4.23f}, actualWidth, h, front,
             fast && filtered ? textures[5] : oes, width ? (fast && filtered ? 1 : 3) : 0);
        if (catalog) Quad(vp, {0, -.56f, -2.15f}, 1.95f, .609375f, front, panel, 2);
    }

    void Close() {
        if (opened) env->CallVoidMethod(activity, close);
        if (transformArray) env->DeleteGlobalRef(transformArray);
        if (query) deleteQuery(1, &query);
        for (auto program : passes) glDeleteProgram(program);
        glDeleteProgram(copy);
        glDeleteProgram(flat);
        glDeleteTextures(1, &oes);
        glDeleteTextures(1, &panel);
        glDeleteTextures(6, textures);
        glDeleteFramebuffers(1, &fbo);
        glDeleteVertexArrays(1, &vao);
    }
};
} // namespace Cinema
