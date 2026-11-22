// OpenXR cinema rendering. Include on the Android GLES path only.
#pragma once
#include <jni.h>
#include <EGL/egl.h>
#include <chrono>
#include <cmath>
#include <cstdio>
#include "anime4k_generated.h"
#include "cinema_input.h"
#include "cinema_pointer.h"

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

} // namespace Cinema

#include "vr_room.h"

namespace Cinema {
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
    else color = texture(frame, vec2(uv.x, 1.0-uv.y));
}
)glsl";
    static constexpr const char* externalImage = R"glsl(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES frame;
uniform sampler2D enhanced, depthMap;
uniform mat4 transform;
uniform vec4 crop;
uniform vec2 inset;
uniform int operation;
uniform float shift;
in vec2 uv;
out vec4 color;
vec3 source(vec2 p) {
    vec2 coord = clamp(p * crop.xy + crop.zw, crop.zw + inset, crop.xy + crop.zw - inset);
    return texture(frame, (transform * vec4(coord, 0.0, 1.0)).xy).rgb;
}
float light(vec3 rgb) {
    return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
}
void main() {
    if (operation == 3) {
        vec2 radius = vec2(.015, .024);
        float value = light(source(uv)) * .4;
        value += .15 * (light(source(uv + vec2(radius.x, 0))) + light(source(uv - vec2(radius.x, 0))) +
                        light(source(uv + vec2(0, radius.y))) + light(source(uv - vec2(0, radius.y))));
        float central = 1.0 - smoothstep(.12, .60, length(uv - vec2(.5, .52)));
        color = vec4(clamp(.36 + (value - .45) * .48 + central * .23, 0.0, 1.0), 0, 0, 1);
        return;
    }
    vec2 p = uv;
    if (shift != 0.0) {
        float mask = smoothstep(0.0, .06, min(uv.x, 1.0 - uv.x));
        float disparity = clamp((texture(depthMap, uv).r - .5) * 1.65, -.45, .45);
        p.x += shift * disparity * mask;
    }
    if (operation == 2 && abs(uv.x - .5) < .0016) {
        color = vec4(.87, .81, 1, 1);
    } else if (operation == 1 || (operation == 2 && uv.x > .5)) {
        color = vec4(texture(enhanced, p).rgb, 1);
    } else {
        color = vec4(source(p), 1);
    }
}
)glsl";
    static constexpr const char* beamVertex = R"glsl(#version 300 es
uniform mat4 mvp;
uniform vec3 fromPoint, toPoint, edge;
void main() {
    vec3 point = mix(fromPoint, toPoint, float(gl_VertexID >> 1));
    gl_Position = mvp * vec4(point + edge * float((gl_VertexID & 1) * 2 - 1), 1.0);
}
)glsl";

    Room room;
    jmethodID headYaw = nullptr;
    GLuint oes = 0, panel = 0, vao = 0, fbo = 0, video = 0, flat = 0, beam = 0;
    GLuint textures[7]{}, passes[5]{}, depth = 0;
    enum class FilterStatus { Off, Active, Waiting, TooLarge, Unavailable };
    bool depthReady = false;
    FilterStatus filterStatus = FilterStatus::Off;
    int filteredLayout = -1;
    int sourceW = 0, sourceH = 0;
    int allocatedW = 0, allocatedH = 0, allocatedEyes = 0;
    int activeHand = 1;
    Pointer pointer;
    int Layout() const {
        const int mode = (config >> 5) & 7;
        return mode == 1 || mode == 2 ? mode : 0;
    }
    jfloatArray transformArray = nullptr;
    jmethodID latch = nullptr, upload = nullptr, close = nullptr, readConfig = nullptr, click = nullptr;
    bool opened = false, filtered = false, canFilter = false;
    int width = 0, height = 0;
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

    bool Allocate(int w, int h, int eyes) {
        if (w > 1920 || h > 1080 || w < 2 || h < 2) return false;
        if (w == allocatedW && h == allocatedH && eyes == allocatedEyes) return true;
        GLint maxSize = 0;
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxSize);
        if (w * 2 > maxSize || h * 2 > maxSize) return false;
        glActiveTexture(GL_TEXTURE0);
        for (int i = 0; i < 7; ++i) {
            Texture(GL_TEXTURE_2D, textures[i], i == 0 || i >= 5 ? GL_LINEAR : GL_NEAREST);
            const int scale = i >= 5 ? 2 : 1;
            const bool intermediate = i > 0 && i < 5;
            const bool unused = i == 6 && eyes == 1;
            glTexImage2D(GL_TEXTURE_2D, 0, intermediate ? GL_RGBA16F : GL_RGBA8,
                         unused ? 1 : w * scale, unused ? 1 : h * scale, 0, GL_RGBA,
                         intermediate ? GL_HALF_FLOAT : GL_UNSIGNED_BYTE, nullptr);
        }
        allocatedW = w;
        allocatedH = h;
        allocatedEyes = eyes;
        return true;
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

    void UseVideo(const XrMatrix4x4f& mvp, int layout, int view, int operation, float shift) {
        glUseProgram(video);
        Matrix(video, "mvp", mvp.m);
        Matrix(video, "transform", transform);
        glUniform4f(glGetUniformLocation(video, "crop"),
                    layout == 1 ? .5f : 1, layout == 2 ? .5f : 1,
                    layout == 1 ? .5f * view : 0, layout == 2 ? .5f * (1 - view) : 0);
        glUniform2f(glGetUniformLocation(video, "inset"), .5f / sourceW, .5f / sourceH);
        glUniform1i(glGetUniformLocation(video, "operation"), operation);
        glUniform1f(glGetUniformLocation(video, "shift"), shift);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, shift != 0 ? depth : 0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, operation == 1 || operation == 2 ? textures[5 + view] : 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, oes);
    }

    void Process(bool enhance, bool mapDepth) {
        XrMatrix4x4f identity;
        XrMatrix4x4f_CreateIdentity(&identity);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glBindVertexArray(vao);
        if (mapDepth) {
            depthReady = Target(depth, 192, 108);
            if (depthReady) {
                UseVideo(identity, 0, 0, 3, 0);
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            }
        }
        if (!enhance) return;
        filtered = false;
        const int layout = Layout();
        const int w = sourceW / (layout == 1 ? 2 : 1);
        const int h = sourceH / (layout == 2 ? 2 : 1);
        const int eyes = layout ? 2 : 1;
        if (!Allocate(w, h, eyes)) return;
        for (int view = 0; view < eyes; ++view) {
            if (!Target(textures[0], w, h)) {
                canFilter = false;
                break;
            }
            UseVideo(identity, layout, view, 0, 0);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            for (int i = 0; i < 5; ++i) {
                const int scale = i == 4 ? 2 : 1;
                const GLuint output = textures[i == 4 ? 5 + view : i + 1];
                if (!Target(output, w * scale, h * scale)) {
                    canFilter = false;
                    break;
                }
                const GLuint program = passes[i];
                glUseProgram(program);
                Matrix(program, "mvp", identity.m);
                glUniform2f(glGetUniformLocation(program, "sourceSize"), float(w), float(h));
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, textures[i]);
                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_2D, i == 4 ? textures[0] : 0);
                glUniform1i(glGetUniformLocation(program, "original"), 1);
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            }
            if (!canFilter) break;
        }
        glActiveTexture(GL_TEXTURE0);
        filtered = canFilter;
        filteredLayout = layout;
    }

    static XrMatrix4x4f QuadMatrix(const XrMatrix4x4f& vp, XrVector3f center, float w, float h,
                                   XrQuaternionf rotation) {
        XrMatrix4x4f model, mvp;
        XrVector3f scale{w * .5f, h * .5f, 1};
        XrMatrix4x4f_CreateTranslationRotationScale(&model, &center, &rotation, &scale);
        XrMatrix4x4f_Multiply(&mvp, &vp, &model);
        return mvp;
    }

    void Quad(const XrMatrix4x4f& vp, XrVector3f center, float w, float h,
              XrQuaternionf rotation, GLuint texture, float r = 0, float g = 0, float b = 0) {
        const auto mvp = QuadMatrix(vp, center, w, h, rotation);
        glUseProgram(flat);
        Matrix(flat, "mvp", mvp.m);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glUniform1i(glGetUniformLocation(flat, "mode"), texture ? 2 : 0);
        glUniform4f(glGetUniformLocation(flat, "tint"), r, g, b, 1);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    static XrVector3f Cross(XrVector3f a, XrVector3f b) {
        return {a.y*b.z-a.z*b.y, a.z*b.x-a.x*b.z, a.x*b.y-a.y*b.x};
    }

    void UpdatePointer(int page) {
        pointer = {};
        if (!page) {
            if ((controllers[0].tracked && controllers[0].pressed) ||
                (controllers[1].tracked && controllers[1].pressed)) {
                Command(3);
                config = env->CallIntMethod(activity, readConfig);
            }
            return;
        }
        const auto geometry = PanelGeometry(page);
        const Pointer rays[] = {Aim(controllers[0].pose, controllers[0].tracked, geometry),
                                Aim(controllers[1].pose, controllers[1].tracked, geometry)};
        const int other = 1 - activeHand;
        if ((rays[other].hit && controllers[other].pressed) ||
            (!rays[activeHand].hit && rays[other].hit)) activeHand = other;
        else if (!rays[activeHand].visible && rays[other].visible) activeHand = other;
        pointer = rays[activeHand];
        if (pointer.hit && controllers[activeHand].pressed) {
            env->CallVoidMethod(activity, click, pointer.u, pointer.v);
            const int next = env->CallIntMethod(activity, readConfig);
            if ((next & 7) != page) pointer = {};
            config = next;
        }
    }

    void DrawPointer(const XrMatrix4x4f& vp, const XrVector3f& eyePosition) {
        if (!pointer.visible) return;
        const auto axis = Add(pointer.end, pointer.origin, -1);
        auto edge = Cross(axis, Add(eyePosition, pointer.origin, -1));
        float length = std::sqrt(edge.x*edge.x + edge.y*edge.y + edge.z*edge.z);
        if (length < .0001f) {
            edge = Cross(axis, {0, 1, 0});
            length = std::sqrt(edge.x*edge.x + edge.y*edge.y + edge.z*edge.z);
        }
        if (length >= .0001f) {
            glUseProgram(beam);
            Matrix(beam, "mvp", vp.m);
            glUniform3f(glGetUniformLocation(beam, "fromPoint"), pointer.origin.x, pointer.origin.y, pointer.origin.z);
            glUniform3f(glGetUniformLocation(beam, "toPoint"), pointer.end.x, pointer.end.y, pointer.end.z);
            glUniform3f(glGetUniformLocation(beam, "edge"), edge.x*.0014f/length, edge.y*.0014f/length, edge.z*.0014f/length);
            glUniform4f(glGetUniformLocation(beam, "tint"), .58f, .40f, .96f, 1);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        }
        if (pointer.hit) {
            auto marker = pointer.end;
            marker.z += .008f;
            Quad(vp, marker, .018f, .018f, {0, 0, 0, 1}, 0, .86f, .78f, 1);
        }
    }

public:
    void Open() {
        auto cls = env->GetObjectClass(activity);
        jmethodID open = env->GetMethodID(cls, "openTexture", "(I)V");
        close = env->GetMethodID(cls, "closeTexture", "()V");
        latch = env->GetMethodID(cls, "latch", "([F)J");
        upload = env->GetMethodID(cls, "uploadPanel", "(FFIIIZFF)V");
        readConfig = env->GetMethodID(cls, "renderConfig", "()I");
        click = env->GetMethodID(cls, "clickPanel", "(FF)V");
        headYaw = env->GetMethodID(cls, "setHeadYaw", "(F)V");
        commandMethod = env->GetMethodID(cls, "command", "(I)V");
        env->DeleteLocalRef(cls);
        auto array = env->NewFloatArray(16);
        transformArray = static_cast<jfloatArray>(env->NewGlobalRef(array));
        env->DeleteLocalRef(array);
        room.Open();
        glGenVertexArrays(1, &vao);
        glGenFramebuffers(1, &fbo);
        glGenTextures(1, &oes);
        glGenTextures(1, &panel);
        glGenTextures(7, textures);
        glGenTextures(1, &depth);
        Texture(GL_TEXTURE_EXTERNAL_OES, oes, GL_LINEAR);
        Texture(GL_TEXTURE_2D, panel, GL_LINEAR);
        Texture(GL_TEXTURE_2D, depth, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 192, 108, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        video = Program(vertex, externalImage);
        glUseProgram(video);
        glUniform1i(glGetUniformLocation(video, "enhanced"), 1);
        glUniform1i(glGetUniformLocation(video, "depthMap"), 2);
        flat = Program(vertex, image);
        beam = Program(beamVertex, "#version 300 es\nprecision mediump float; uniform vec4 tint; out vec4 color; void main() { color = tint; }");
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
    }

    void UpdatePanel() {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, panel);
        env->CallVoidMethod(activity, upload, pointer.hit ? pointer.u : -1.0f, pointer.hit ? pointer.v : -1.0f,
                            jint(filterStatus), jint(sourceW), jint(sourceH), jboolean(depthReady), fps,
                            filtered ? gpuMs : -1.0f);
    }

    void PrepareFrame() {
        const auto now = std::chrono::steady_clock::now();
        ++frames;
        const float elapsed = std::chrono::duration<float>(now - last).count();
        if (elapsed >= 0.5f) {
            fps = frames / elapsed;
            frames = 0;
            last = now;
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
        config = env->CallIntMethod(activity, readConfig);
        UpdatePointer(config & 7);
        const jlong format = env->CallLongMethod(activity, latch, transformArray);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return;
        }
        bool newFrame = format > 0;
        if (format < 0) {
            width = height = sourceW = sourceH = 0;
            filtered = depthReady = false;
        } else if (newFrame) {
            sourceW = int((uint64_t(format) >> 48) & 0x7fff);
            sourceH = int((uint64_t(format) >> 32) & 0xffff);
            width = int((uint64_t(format) >> 16) & 0xffff);
            height = int(uint64_t(format) & 0xffff);
            env->GetFloatArrayRegion(transformArray, 0, 16, transform);
        }
        const bool fast = config & 8;
        const bool parallax = ((config >> 5) & 7) == 3;
        const int layout = Layout();
        const bool stereo = layout != 0;
        const int w = sourceW / (layout == 1 ? 2 : 1);
        const int h = sourceH / (layout == 2 ? 2 : 1);
        const bool hasFrame = w > 1 && h > 1;
        const bool supported = hasFrame && w <= 1920 && h <= 1080 &&
                               (!stereo || w * h <= 1280 * 720);
        const bool enhance = fast && canFilter && supported &&
                             (!filtered || filteredLayout != layout || newFrame);
        const bool mapDepth = parallax && hasFrame && (newFrame || !depthReady);
        if (!fast || !supported || filteredLayout != layout) filtered = false;
        if (!parallax) depthReady = false;
        const bool measure = enhance && query && !queryPending;
        if (measure) beginQuery(GL_TIME_ELAPSED_EXT, query);
        if (enhance || mapDepth) Process(enhance, mapDepth);
        if (measure) {
            endQuery(GL_TIME_ELAPSED_EXT);
            queryPending = true;
        }
        filterStatus = !fast ? FilterStatus::Off
                     : !hasFrame ? FilterStatus::Waiting
                     : !supported ? FilterStatus::TooLarge
                     : filtered ? FilterStatus::Active : FilterStatus::Unavailable;
        if (config & 7) UpdatePanel();
    }

    void Draw(const XrMatrix4x4f& vp, const XrVector3f& eyePosition, const XrQuaternionf& orientation) {
        constexpr XrQuaternionf front{0, 0, 0, 1};
        if (eye == 0 && (config & (1 << 15))) {
            const auto& q = orientation;
            const float x = -2 * (q.x*q.z + q.w*q.y);
            const float z = -1 + 2 * (q.x*q.x + q.y*q.y);
            if (x*x + z*z > .01f) env->CallVoidMethod(activity, headYaw, std::atan2(x, -z));
        }
        glDisable(GL_CULL_FACE);
        room.Draw(vp);
        glBindVertexArray(vao);
        float aspect = width && height ? float(width) / height : 16.0f / 9.0f;
        const int layout = Layout();
        // Full-packed sources are very wide (SBS) or nearly square/tall (OU).
        // Half-packed sources preserve the display aspect but reduce per-eye resolution.
        if (layout == 1 && aspect > 2.65f) aspect *= .5f;
        if (layout == 2 && aspect < 1.22f) aspect *= 2;
        const int size = (config >> 13) & 3;
        const float w = size == 0 ? 3.55f : size == 1 ? 4.85f : 5.95f;
        const float h = std::min(w / aspect, 2.72f);
        const float actualWidth = h * aspect;
        const float screenLimit = 3.67f - actualWidth * .5f;
        const float x = std::clamp(screenX, -screenLimit, screenLimit);
        const float y = std::clamp(.38f + screenY, -1.23f + h*.5f, 2.37f - h*.5f);
        Quad(vp, {x, y, -5.12f}, actualWidth + .19f, h + .19f, front, 0, .013f, .010f, .020f);
        const XrVector3f screen{x, y, -5.10f};
        if (width && sourceW && sourceH) {
            const int view = (eye != 0) ^ ((config & 256) != 0);
            const int operation = filtered && filteredLayout == layout ? ((config & 16) ? 2 : 1) : 0;
            const float shift = depthReady ? (view ? 1 : -1) * ((config >> 9) & 15) * .003f : 0;
            UseVideo(QuadMatrix(vp, screen, actualWidth, h, front), layout, layout ? view : 0, operation, shift);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        } else {
            Quad(vp, screen, actualWidth, h, front, 0);
        }
        if (config & 7) {
            const auto geometry = PanelGeometry(config & 7);
            Quad(vp, geometry.center, geometry.w, geometry.h, front, panel);
            DrawPointer(vp, eyePosition);
        }
    }

    void Close() {
        room.Close();
        if (opened) env->CallVoidMethod(activity, close);
        if (transformArray) env->DeleteGlobalRef(transformArray);
        if (query) deleteQuery(1, &query);
        for (auto program : passes) glDeleteProgram(program);
        commandMethod = nullptr;
        glDeleteProgram(video);
        glDeleteProgram(beam);
        glDeleteProgram(flat);
        glDeleteTextures(1, &oes);
        glDeleteTextures(1, &panel);
        glDeleteTextures(7, textures);
        glDeleteTextures(1, &depth);
        glDeleteFramebuffers(1, &fbo);
        glDeleteVertexArrays(1, &vao);
    }
};
} // namespace Cinema
