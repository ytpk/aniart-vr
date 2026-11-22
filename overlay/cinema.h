// OpenXR cinema rendering. Include on the Android GLES path only.
#pragma once
#include <jni.h>
#include <EGL/egl.h>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include "anime4k_generated.h"
#include "cinema_input.h"
#include "cinema_pointer.h"
#include "qnn_htp_probe.h"

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
uniform sampler2D enhanced, depthMap, priorDepth;
uniform float depthBlend;
uniform mat4 transform;
uniform vec4 crop;
uniform vec2 inset;
uniform int operation, post;
uniform vec2 sourceTexel;
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
vec3 suppressHalo(vec2 p) {
    vec3 center = source(p);
    float luma = light(center);
    vec2 stepX = vec2(sourceTexel.x * 2.1, 0);
    vec2 stepY = vec2(0, sourceTexel.y * 2.1);
    float overX = luma - max(light(source(p - stepX)), light(source(p + stepX)));
    float overY = luma - max(light(source(p - stepY)), light(source(p + stepY)));
    float overshoot = max(overX, overY);
    float edge = smoothstep(.018, .05, overshoot) * (1.0 - smoothstep(.10, .17, overshoot));
    return max(vec3(0), center - vec3(.52 * max(0.0, overshoot) * edge));
}
vec3 picture(vec2 p, bool sharpened) {
    return sharpened ? texture(enhanced, p).rgb : source(p);
}
vec3 finishPicture(vec2 p, bool sharpened) {
    vec2 stepSize = sourceTexel * (sharpened ? .5 : 1.0);
    vec3 value = picture(p, sharpened);
    if ((post & 1) != 0) {
        vec2 footprint = min(fwidth(p), stepSize * 12.0);
        if (any(greaterThan(footprint, stepSize * 1.4))) {
            vec2 offset = footprint * .28;
            value = .25 * (picture(p + offset, sharpened) + picture(p - offset, sharpened)
                + picture(p + vec2(offset.x,-offset.y), sharpened)
                + picture(p + vec2(-offset.x,offset.y), sharpened));
        }
    }
    if ((post & 2) != 0) {
        vec2 radius = clamp(fwidth(p), stepSize * 2.5, stepSize * 7.0);
        vec3 a = picture(p + radius, sharpened), b = picture(p - radius, sharpened);
        vec3 c = picture(p + vec2(radius.x,-radius.y), sharpened);
        vec3 d = picture(p + vec2(-radius.x,radius.y), sharpened);
        vec3 delta = max(max(abs(a-value), abs(b-value)), max(abs(c-value), abs(d-value)));
        float edge = max(delta.r, max(delta.g, delta.b));
        value = mix(value, (a+b+c+d)*.25, .68 * (1.0-smoothstep(.018,.085,edge)));
        vec2 pixel = floor(clamp(p,0.0,1.0) / sourceTexel);
        float noise = fract(52.9829189 * fract(dot(pixel, vec2(.06711056,.00583715)))) - .5;
        value += noise / 255.0;
    }
    return value;
}
void main() {
    if (operation == 5) {
        color = vec4(suppressHalo(uv), 1);
        return;
    }
    if (operation == 4) {
        vec3 sum = vec3(0.0);
        for (int y=0;y<4;++y) for (int x=0;x<4;++x) {
            vec3 rgb = source(uv + (vec2(x,y)-1.5)*vec2(1.0/12.0,1.0/8.0));
            sum += rgb * rgb;
        }
        color = vec4(sqrt(sum/16.0), 1);
        return;
    }
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
        float disparity = clamp((mix(texture(priorDepth, uv).r, texture(depthMap, uv).r, depthBlend) - .5) * 1.65, -.45, .45);
        p.x += shift * disparity * mask;
    }
    if (operation == 2 && abs(uv.x - .5) < .0016) {
        color = vec4(.87, .81, 1, 1);
    } else {
        bool sharpened = operation == 1 || (operation == 2 && uv.x > .5);
        color = vec4(finishPicture(p, sharpened), 1);
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
    static constexpr int depthCaptureW = 256, depthCaptureH = 144;
    GLuint priorDepth = 0, depthReadback = 0;
    GLsync readbackFence = nullptr;
    bool hasPriorDepth = false;
    float depthBlend = 1, depthStrength = 1, targetDepthStrength = 1;
    std::chrono::steady_clock::time_point submittedDepth{}, deliveredDepth{}, freshDepth{}, lastMovieFrame{};
    GLuint depthCapture = 0, screenLight = 0, msaaDepth = 0;
    int msaaWidth = 0, msaaHeight = 0;
    PFNGLFRAMEBUFFERTEXTURE2DMULTISAMPLEEXTPROC bindMultisample = nullptr;
    PFNGLRENDERBUFFERSTORAGEMULTISAMPLEEXTPROC storageMultisample = nullptr;
    bool filteredDehalo = false;
    bool canMsaa = false, lightReady = false, panelMipsDirty = true;
    std::chrono::steady_clock::time_point lastLight{}, lastAim{};
    Pointer previousAim;
    int aimPage = -1, aimHand = -1;
    int depthModel = -1;
    jmethodID wantsDiagnosticDepth = nullptr, submitDiagnosticDepth = nullptr;
    bool diagnosticDepthReadback = false;
    jmethodID selectDepth = nullptr, wantsDepth = nullptr, submitDepth = nullptr, takeDepth = nullptr;
    jbyteArray depthInput = nullptr;
    static constexpr int captureBytes = depthCaptureW * depthCaptureH * 4;
    std::array<jbyte, 192 * 108> depthValues{};
    std::chrono::steady_clock::time_point lastDepth{};
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

    void UseVideo(const XrMatrix4x4f& mvp, int layout, int view, int operation, float shift, int post = 0) {
        glUseProgram(video);
        Matrix(video, "mvp", mvp.m);
        Matrix(video, "transform", transform);
        glUniform4f(glGetUniformLocation(video, "crop"),
                    layout == 1 ? .5f : 1, layout == 2 ? .5f : 1,
                    layout == 1 ? .5f * view : 0, layout == 2 ? .5f * (1 - view) : 0);
        glUniform2f(glGetUniformLocation(video, "inset"), .5f / sourceW, .5f / sourceH);
        glUniform1i(glGetUniformLocation(video, "operation"), operation);
        glUniform1i(glGetUniformLocation(video, "post"), post);
        glUniform2f(glGetUniformLocation(video, "sourceTexel"),
                    (layout == 1 ? 2.f : 1.f) / sourceW, (layout == 2 ? 2.f : 1.f) / sourceH);
        glUniform1f(glGetUniformLocation(video, "shift"), shift);
        glUniform1f(glGetUniformLocation(video, "depthBlend"), depthBlend);
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, shift != 0 ? (hasPriorDepth ? priorDepth : depth) : 0);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, shift != 0 ? depth : 0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, operation == 1 || operation == 2 ? textures[5 + view] : 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, oes);
    }

    void Process(bool enhance, bool mapDepth, bool captureDepth, bool updateLight) {
        XrMatrix4x4f identity;
        XrMatrix4x4f_CreateIdentity(&identity);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glBindVertexArray(vao);
        if (updateLight && Target(screenLight, 3, 2)) {
            UseVideo(identity, Layout(), 0, 4, 0);
            if (lightReady) {
                glEnable(GL_BLEND);
                glBlendColor(0, 0, 0, .24f);
                glBlendFunc(GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA);
            }
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            glDisable(GL_BLEND);
            lightReady = true;
        }
        if (mapDepth) {
            depthReady = Target(depth, 192, 108);
            if (depthReady) {
                UseVideo(identity, 0, 0, 3, 0);
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            }
        }
        if (captureDepth && Target(depthCapture, depthCaptureW, depthCaptureH)) {
            UseVideo(identity, 0, 0, 0, 0);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, depthReadback);
            glReadPixels(0, 0, depthCaptureW, depthCaptureH, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
            readbackFence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
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
            UseVideo(identity, layout, view, (config & (1 << 23)) ? 5 : 0, 0);
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

    void UpdatePointer(int page, std::chrono::steady_clock::time_point now) {
        pointer = {};
        if (!page) {
            previousAim = {};
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
        if ((config & (1 << 22)) && pointer.hit && previousAim.hit && aimPage == page && aimHand == activeHand) {
            const float dt = std::clamp(std::chrono::duration<float>(now - lastAim).count(), .001f, .10f);
            const float distance = std::hypot(pointer.u - previousAim.u, pointer.v - previousAim.v);
            const float response = 9.f + std::min(80.f, distance / dt * 32.f);
            const float factor = 1.f - std::exp(-dt * response);
            pointer.u = previousAim.u + (pointer.u - previousAim.u) * factor;
            pointer.v = previousAim.v + (pointer.v - previousAim.v) * factor;
            pointer.end = {geometry.center.x + (pointer.u - .5f) * geometry.w,
                           geometry.center.y + (.5f - pointer.v) * geometry.h, geometry.center.z + .004f};
        }
        previousAim = pointer;
        lastAim = now;
        aimPage = page;
        aimHand = activeHand;
        if (pointer.hit && controllers[activeHand].pressed) {
            env->CallVoidMethod(activity, click, pointer.u, pointer.v);
            const int next = env->CallIntMethod(activity, readConfig);
            if ((next & 7) != page) previousAim = pointer = {};
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
        upload = env->GetMethodID(cls, "uploadPanel", "(FFIIIZFFFZ)Z");
        readConfig = env->GetMethodID(cls, "renderConfig", "()I");
        click = env->GetMethodID(cls, "clickPanel", "(FF)V");
        headYaw = env->GetMethodID(cls, "setHeadYaw", "(F)V");
        commandMethod = env->GetMethodID(cls, "command", "(I)V");
        selectDepth = env->GetMethodID(cls, "selectDepthModel", "(I)V");
        wantsDepth = env->GetMethodID(cls, "wantsDepthFrame", "()Z");
        submitDepth = env->GetMethodID(cls, "submitDepthFrame", "([B)V");
        wantsDiagnosticDepth = env->GetMethodID(cls, "wantsDiagnosticDepthFrame", "()Z");
        submitDiagnosticDepth = env->GetMethodID(cls, "submitDiagnosticDepthFrame", "([B)V");
        takeDepth = env->GetMethodID(cls, "takeDepthMap", "()[B");
        env->DeleteLocalRef(cls);
        auto array = env->NewFloatArray(16);
        transformArray = static_cast<jfloatArray>(env->NewGlobalRef(array));
        env->DeleteLocalRef(array);
        auto input = env->NewByteArray(captureBytes);
        depthInput = static_cast<jbyteArray>(env->NewGlobalRef(input));
        env->DeleteLocalRef(input);
        room.Open();
        glGenVertexArrays(1, &vao);
        glGenFramebuffers(1, &fbo);
        glGenTextures(1, &oes);
        glGenTextures(1, &panel);
        glGenTextures(7, textures);
        glGenTextures(1, &depth);
        glGenTextures(1, &depthCapture);
        glGenTextures(1, &priorDepth);
        glGenBuffers(1, &depthReadback);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, depthReadback);
        glBufferData(GL_PIXEL_PACK_BUFFER, captureBytes, nullptr, GL_STREAM_READ);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        glGenTextures(1, &screenLight);
        Texture(GL_TEXTURE_EXTERNAL_OES, oes, GL_LINEAR);
        Texture(GL_TEXTURE_2D, panel, GL_LINEAR);
        Texture(GL_TEXTURE_2D, depth, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, 192, 108, 0, GL_RED, GL_UNSIGNED_BYTE, nullptr);
        Texture(GL_TEXTURE_2D, priorDepth, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, 192, 108, 0, GL_RED, GL_UNSIGNED_BYTE, nullptr);
        Texture(GL_TEXTURE_2D, depthCapture, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, depthCaptureW, depthCaptureH, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        Texture(GL_TEXTURE_2D, screenLight, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 3, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        video = Program(vertex, externalImage);
        glUseProgram(video);
        glUniform1i(glGetUniformLocation(video, "enhanced"), 1);
        glUniform1i(glGetUniformLocation(video, "depthMap"), 2);
        glUniform1i(glGetUniformLocation(video, "priorDepth"), 3);
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
            if (std::string(extension) == "GL_EXT_multisampled_render_to_texture" ||
                std::string(extension) == "GL_EXT_multisampled_render_to_texture2") {
                bindMultisample = reinterpret_cast<PFNGLFRAMEBUFFERTEXTURE2DMULTISAMPLEEXTPROC>(
                        eglGetProcAddress("glFramebufferTexture2DMultisampleEXT"));
                storageMultisample = reinterpret_cast<PFNGLRENDERBUFFERSTORAGEMULTISAMPLEEXTPROC>(
                        eglGetProcAddress("glRenderbufferStorageMultisampleEXT"));
                canMsaa = bindMultisample && storageMultisample;
            }
            if (std::string(extension) != "GL_EXT_disjoint_timer_query") continue;
            genQuery = reinterpret_cast<PFNGLGENQUERIESEXTPROC>(eglGetProcAddress("glGenQueriesEXT"));
            deleteQuery = reinterpret_cast<PFNGLDELETEQUERIESEXTPROC>(eglGetProcAddress("glDeleteQueriesEXT"));
            beginQuery = reinterpret_cast<PFNGLBEGINQUERYEXTPROC>(eglGetProcAddress("glBeginQueryEXT"));
            endQuery = reinterpret_cast<PFNGLENDQUERYEXTPROC>(eglGetProcAddress("glEndQueryEXT"));
            queryStatus = reinterpret_cast<PFNGLGETQUERYOBJECTUIVEXTPROC>(eglGetProcAddress("glGetQueryObjectuivEXT"));
            queryTime = reinterpret_cast<PFNGLGETQUERYOBJECTUI64VEXTPROC>(eglGetProcAddress("glGetQueryObjectui64vEXT"));
            if (genQuery && deleteQuery && beginQuery && endQuery && queryStatus && queryTime) genQuery(1, &query);
        }
        env->CallVoidMethod(activity, open, oes);
        opened = !env->ExceptionCheck();
        if (!opened) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            THROW("Cannot create decoder surface");
        }
    }

    bool BindAntialias(GLuint color, int w, int h) {
        eyeMsaa = false;
        if (!(config & (1 << 18)) || !canMsaa) return false;
        if (!msaaDepth) glGenRenderbuffers(1, &msaaDepth);
        glBindRenderbuffer(GL_RENDERBUFFER, msaaDepth);
        if (w != msaaWidth || h != msaaHeight) {
            storageMultisample(GL_RENDERBUFFER, 2, GL_DEPTH_COMPONENT24, w, h);
            msaaWidth = w;
            msaaHeight = h;
        }
        bindMultisample(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0, 2);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, msaaDepth);
        eyeMsaa = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
        if (!eyeMsaa) {
            canMsaa = false;
            Log::Write(Log::Level::Warning, "2x MSAA unavailable for this swapchain");
        }
        return eyeMsaa;
    }

    void UpdatePanel() {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, panel);
        const bool mipmaps = (config & (1 << 18)) != 0;
        const bool changed = env->CallBooleanMethod(activity, upload, pointer.hit ? pointer.u : -1.0f, pointer.hit ? pointer.v : -1.0f,
                            jint(filterStatus), jint(sourceW), jint(sourceH), jboolean(depthReady), fps,
                            filtered ? gpuMs : -1.0f, displayHz, jboolean(eyeMsaa));
        panelMipsDirty = panelMipsDirty || changed;
        if (mipmaps && panelMipsDirty) {
            glGenerateMipmap(GL_TEXTURE_2D);
            panelMipsDirty = false;
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, mipmaps ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
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
        UpdatePointer(config & 7, now);
        const jlong format = env->CallLongMethod(activity, latch, transformArray);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return;
        }
        bool newFrame = format > 0;
        if (format < 0) {
            width = height = sourceW = sourceH = 0;
            filtered = depthReady = lightReady = false;
        } else if (newFrame) {
            lastMovieFrame = now;
            sourceW = int((uint64_t(format) >> 48) & 0x7fff);
            sourceH = int((uint64_t(format) >> 32) & 0xffff);
            width = int((uint64_t(format) >> 16) & 0xffff);
            height = int(uint64_t(format) & 0xffff);
            env->GetFloatArrayRegion(transformArray, 0, 16, transform);
        }
        const bool fast = config & 8;
        const bool parallax = ((config >> 5) & 7) == 3;
        const int newModel = parallax ? (config >> 24) & 7 : 0;
        if (depthModel != newModel || format < 0) {
            depthModel = newModel;
            depthReady = false;
            lastDepth = submittedDepth = deliveredDepth = freshDepth = {};
            hasPriorDepth = false;
            depthBlend = 1;
            targetDepthStrength = depthStrength = depthModel == 0 ? 1 : 0;
            if (readbackFence) glDeleteSync(readbackFence);
            readbackFence = nullptr;
            env->CallVoidMethod(activity, selectDepth, depthModel);
        }
        const int layout = Layout();
        const bool stereo = layout != 0;
        const int w = sourceW / (layout == 1 ? 2 : 1);
        const int h = sourceH / (layout == 2 ? 2 : 1);
        const bool hasFrame = w > 1 && h > 1;
        const bool supported = hasFrame && w <= 1920 && h <= 1080 &&
                               (!stereo || w * h <= 1280 * 720);
        const bool enhance = fast && canFilter && supported &&
                             (!filtered || filteredLayout != layout || filteredDehalo != bool(config & (1 << 23)) || newFrame);
        const bool mapDepth = parallax && depthModel == 0 && hasFrame && (newFrame || !depthReady);
        bool captureDepth = false;
        if (depthModel != 0 && hasFrame) {
            auto map = static_cast<jbyteArray>(env->CallObjectMethod(activity, takeDepth));
            if (map) {
                const jsize size = env->GetArrayLength(map);
                if (size == 1 && depthReady) {
                    freshDepth = now;
                } else if (size == depthValues.size() + 1) {
                    jbyte cut = 0;
                    env->GetByteArrayRegion(map, depthValues.size(), 1, &cut);
                    hasPriorDepth = depthReady && !cut;
                    if (hasPriorDepth) std::swap(priorDepth, depth);
                    deliveredDepth = freshDepth = now;
                    const float age = std::chrono::duration<float>(now - submittedDepth).count();
                    targetDepthStrength = std::clamp((.70f - age) / .48f, 0.f, 1.f);
                    env->GetByteArrayRegion(map, 0, depthValues.size(), depthValues.data());
                    glActiveTexture(GL_TEXTURE0);
                    glBindTexture(GL_TEXTURE_2D, depth);
                    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 192, 108, GL_RED, GL_UNSIGNED_BYTE, depthValues.data());
                    depthReady = true;
                }
                env->DeleteLocalRef(map);
            }
            if (depthReady) {
                const float fade = std::chrono::duration<float>(now - deliveredDepth).count();
                depthBlend = hasPriorDepth ? std::clamp(fade / .115f, 0.f, 1.f) : 1.f;
                const bool advancing = now - lastMovieFrame < std::chrono::milliseconds(250);
                const float age = std::chrono::duration<float>(now - freshDepth).count();
                const float speed = targetDepthStrength * (advancing ? std::clamp((1.1f - age) / .5f, 0.f, 1.f) : 1.f);
                depthStrength += (speed - depthStrength) * .10f;
            }
        }
        const bool diagnostic = hasFrame && env->CallBooleanMethod(activity, wantsDiagnosticDepth);
        if (hasFrame) {
            if (readbackFence) {
                const GLenum ready = glClientWaitSync(readbackFence, 0, 0);
                if (ready != GL_TIMEOUT_EXPIRED) {
                    glDeleteSync(readbackFence);
                    readbackFence = nullptr;
                    if (ready != GL_WAIT_FAILED && (diagnosticDepthReadback ? diagnostic :
                            env->CallBooleanMethod(activity, wantsDepth))) {
                        glBindBuffer(GL_PIXEL_PACK_BUFFER, depthReadback);
                        const auto* data = static_cast<const jbyte*>(
                                glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, captureBytes, GL_MAP_READ_BIT));
                        if (data) {
                            env->SetByteArrayRegion(depthInput, 0, captureBytes, data);
                            glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
                            if (diagnosticDepthReadback) {
                                env->CallVoidMethod(activity, submitDiagnosticDepth, depthInput);
                            } else {
                                submittedDepth = lastDepth;
                                env->CallVoidMethod(activity, submitDepth, depthInput);
                            }
                        }
                        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
                    }
                }
            }
            const bool captureDiagnostic = !readbackFence && diagnostic
                    && env->CallBooleanMethod(activity, wantsDiagnosticDepth);
            const bool captureLive = !readbackFence && depthModel != 0
                    && (newFrame || lastDepth.time_since_epoch().count() == 0)
                    && now - lastDepth >= std::chrono::milliseconds(125)
                    && env->CallBooleanMethod(activity, wantsDepth);
            captureDepth = captureDiagnostic || captureLive;
            if (captureDepth) diagnosticDepthReadback = captureDiagnostic;
            if (captureLive) lastDepth = now;
        }
        if (!fast || !supported || filteredLayout != layout) filtered = false;
        if (!parallax) depthReady = false;
        const bool measure = enhance && query && !queryPending;
        if (measure) beginQuery(GL_TIME_ELAPSED_EXT, query);
        const bool dark = (config & (1 << 20)) != 0;
        if (!dark) lightReady = false;
        const bool updateLight = dark && hasFrame && (!lightReady ||
                (newFrame && now - lastLight >= std::chrono::milliseconds(67)));
        if (updateLight) lastLight = now;
        if (enhance || mapDepth || captureDepth || updateLight) Process(enhance, mapDepth, captureDepth, updateLight);
        if (enhance) filteredDehalo = (config & (1 << 23)) != 0;
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
        room.Draw(vp, (config & (1 << 20)) != 0, lightReady ? screenLight : 0, x, y, actualWidth, h);
        glBindVertexArray(vao);
        Quad(vp, {x, y, -5.12f}, actualWidth + .19f, h + .19f, front, 0, .013f, .010f, .020f);
        const XrVector3f screen{x, y, -5.10f};
        if (width && sourceW && sourceH) {
            const int view = (eye != 0) ^ ((config & 256) != 0);
            const int operation = filtered && filteredLayout == layout ? ((config & 16) ? 2 : 1) : 0;
            const float scale = depthModel == 0 ? .00125f : .003f * depthStrength;
            const float shift = depthReady ? (view ? 1 : -1) * ((config >> 9) & 15) * scale : 0;
            UseVideo(QuadMatrix(vp, screen, actualWidth, h, front), layout, layout ? view : 0, operation, shift, (config >> 18) & 3);
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
        if (depthInput) env->DeleteGlobalRef(depthInput);
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
        glDeleteTextures(1, &depthCapture);
        glDeleteTextures(1, &priorDepth);
        if (readbackFence) glDeleteSync(readbackFence);
        glDeleteBuffers(1, &depthReadback);
        glDeleteTextures(1, &screenLight);
        if (msaaDepth) glDeleteRenderbuffers(1, &msaaDepth);
        glDeleteFramebuffers(1, &fbo);
        glDeleteVertexArrays(1, &vao);
    }
};
} // namespace Cinema
