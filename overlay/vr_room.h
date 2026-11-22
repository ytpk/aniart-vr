#pragma once
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

namespace Cinema {

class Room {
    struct Vertex { XrVector3f position, color, normal; float material; };
    GLuint program = 0, vao = 0, buffer = 0;
    GLsizei count = 0;

    static constexpr const char* vertex = R"glsl(#version 300 es
layout(location=0) in vec3 position;
layout(location=1) in vec3 tint;
layout(location=2) in float material;
layout(location=3) in vec3 normal;
uniform int darkMode, hasLight;
uniform sampler2D screenLight;
uniform vec4 screenRect;
uniform mat4 vp;
out vec3 world;
out vec3 color;
flat out float surfaceType;
vec3 spill(vec2 uv, vec3 normal) {
    vec3 point = vec3(screenRect.xy + (uv-.5)*screenRect.zw, -5.10);
    vec3 offset = position - point;
    float distance2 = dot(offset,offset) + .4;
    vec3 ray = offset * inversesqrt(distance2);
    float weight = max(0.0,ray.z) * abs(dot(normal,ray)) / distance2;
    vec3 rgb = textureLod(screenLight, uv, 0.0).rgb;
    return rgb*rgb * weight;
}
void main() {
    world = position;
    color = tint;
    if (darkMode != 0) {
        color *= material > 2.5 ? .023 : .11;
        if (hasLight != 0 && material < 2.5) {
            vec3 reflected = vec3(0.0);
            for (int y = 0; y < 2; ++y)
                for (int x = 0; x < 3; ++x)
                    reflected += spill((vec2(x,y)+.5)/vec2(3,2), normal);
            color += .26 * sqrt(reflected) * (.8 + 2.0 * tint);
        }
    }
    surfaceType = material;
    gl_Position = vp * vec4(position, 1.0);
}
)glsl";
    static constexpr const char* fragment = R"glsl(#version 300 es
precision highp float;
in vec3 world;
in vec3 color;
flat in float surfaceType;
out vec4 outColor;
uniform int darkMode;
float grain(vec3 p) {
    vec3 cell = fract(floor(p) * vec3(.1031, .1137, .1379));
    return fract(dot(cell, cell.yzx + 19.19));
}
void main() {
    if (darkMode != 0) {
        outColor = vec4(color, 1);
        return;
    }
    if (surfaceType > 2.5) {
        outColor = vec4(color, 1);
        return;
    }
    float side = max(0.0, 3.85 - abs(world.x));
    float front = abs(world.z + 5.40);
    float rear = abs(world.z - 3.20);
    float edge = min(min(side, front), rear);
    float low = abs(world.y + 1.245);
    float high = abs(world.y - 2.55);
    float falloff = 1.0 / (1.0 + 140.0 * (edge*edge + low*low));
    float cove = 1.0 / (1.0 + 165.0 * (edge*edge + high*high));
    float violet = 1.0 / (2.1 + .32 * dot(world - vec3(0,.65,-5.2), world - vec3(0,.65,-5.2)));
    float fibers = grain(world * (surfaceType > 1.5 ? 245.0 : 65.0));
    vec3 shaded = color * (.87 + fibers*.24) + violet*vec3(.073,.053,.140);
    shaded += (falloff*.63+cove*.37)*vec3(.34,.054,.079);
    outColor = vec4(shaded, 1);
}
)glsl";

    struct Mesh {
        std::vector<Vertex> vertices;

        static XrVector3f mix(XrVector3f a, XrVector3f b, float t) {
            return {a.x+(b.x-a.x)*t, a.y+(b.y-a.y)*t, a.z+(b.z-a.z)*t};
        }

        static float length(XrVector3f p) { return std::sqrt(p.x*p.x + p.y*p.y + p.z*p.z); }

        void face(XrVector3f a, XrVector3f b, XrVector3f c, XrVector3f d,
                  XrVector3f color, float material = 0, float light = 1) {
            const XrVector3f shade{color.x * light, color.y * light, color.z * light};
            const XrVector3f u{b.x-a.x,b.y-a.y,b.z-a.z}, v{c.x-a.x,c.y-a.y,c.z-a.z};
            XrVector3f normal{u.y*v.z-u.z*v.y, u.z*v.x-u.x*v.z, u.x*v.y-u.y*v.x};
            float n = length(normal);
            if (n > .00001f) normal = {normal.x/n, normal.y/n, normal.z/n};
            const int cols = material > 2.5 ? 1 : std::max(1, int(std::ceil(length(u) / .9f)));
            const int rows = material > 2.5 ? 1 : std::max(1, int(std::ceil(length({d.x-a.x,d.y-a.y,d.z-a.z}) / .9f)));
            const auto point = [&](int x, int y) {
                return mix(mix(a,b,float(x)/cols), mix(d,c,float(x)/cols), float(y)/rows);
            };
            for (int y = 0; y < rows; ++y)
                for (int x = 0; x < cols; ++x) {
                    const auto p = point(x,y), q = point(x+1,y);
                    const auto r = point(x+1,y+1), t = point(x,y+1);
                    for (auto vertex : {p,q,r,p,r,t}) vertices.push_back({vertex, shade, normal, material});
                }
        }

        void box(float x, float y, float z, float w, float h, float d, XrVector3f color, float material = 0) {
            float l = x - w / 2, r = x + w / 2;
            float b = y - h / 2, t = y + h / 2;
            float f = z - d / 2, a = z + d / 2;
            face({l,b,a}, {r,b,a}, {r,t,a}, {l,t,a}, color, material, .86f);
            face({r,b,f}, {l,b,f}, {l,t,f}, {r,t,f}, color, material, .92f);
            face({l,b,f}, {l,b,a}, {l,t,a}, {l,t,f}, color, material, .77f);
            face({r,b,a}, {r,b,f}, {r,t,f}, {r,t,a}, color, material, .77f);
            face({l,t,a}, {r,t,a}, {r,t,f}, {l,t,f}, color, material, 1.12f);
            face({l,b,f}, {r,b,f}, {r,b,a}, {l,b,a}, color, material, .64f);
        }

        void cushion(float x, float y, float z, float w, float h, float d, XrVector3f color) {
            const float bevel = .04f;
            const float l = x - w/2, r = x + w/2, b = y - h/2, t = y + h/2;
            const XrVector2f edge[] = {{l+bevel,b},{r-bevel,b},{r,b+bevel},{r,t-bevel},
                                     {r-bevel,t},{l+bevel,t},{l,t-bevel},{l,b+bevel}};
            float near = z-d/2, far = z+d/2;
            const float lights[] = {.65f, .77f, .83f, 1.f, 1.18f, .96f, .80f, .73f};
            for (int i = 0; i < 8; ++i) {
                auto a = edge[i], p = edge[(i+1)%8];
                face({a.x,a.y,near},{p.x,p.y,near},{p.x,p.y,far},{a.x,a.y,far},color,2,lights[i]);
                face({x,y,near},{a.x,a.y,near},{p.x,p.y,near},{x,y,near},color,2,.93f);
                face({x,y,far},{p.x,p.y,far},{a.x,a.y,far},{x,y,far},color,2,.83f);
            }
        }
    };

public:
    void Open() {
        Mesh mesh;
        constexpr float left=-3.85f, right=3.85f, floor=-1.35f, top=2.65f, front=-5.40f, rear=3.20f;
        const XrVector3f carpet{.044f,.034f,.068f}, wall{.047f,.036f,.061f}, seam{.016f,.012f,.025f};
        const XrVector3f red{.89f,.17f,.22f}, shade{.26f,.029f,.057f};
        mesh.face({left,floor,front},{right,floor,front},{right,floor,rear},{left,floor,rear},carpet,2);
        mesh.face({left,top,rear},{right,top,rear},{right,top,front},{left,top,front},{.025f,.022f,.035f});
        mesh.face({left,floor,front},{right,floor,front},{right,top,front},{left,top,front},seam);
        mesh.face({right,floor,rear},{left,floor,rear},{left,top,rear},{right,top,rear},seam);
        for (float x : {left, right}) {
            mesh.face({x,floor,rear},{x,floor,front},{x,top,front},{x,top,rear},seam);
            for (int i = 0; i < 8; ++i) {
                float z = front + (i+.5f)*(rear-front)/8;
                auto tint = wall;
                if (i % 2) tint.z += .006f;
                mesh.box(x*.998f, .59f, z, .086f, 3.62f, (rear-front)/8-.016f, tint);
            }
            mesh.box(x*.980f, -1.247f, -1.10f, .034f, .038f, rear-front-.07f, shade, 3);
            mesh.box(x*.976f, -1.247f, -1.10f, .011f, .014f, rear-front-.07f, red, 3);
            mesh.box(x*.925f, 2.578f, -1.10f, .033f, .025f, rear-front-.68f, shade, 3);
            mesh.box(x*.920f, 2.560f, -1.10f, .011f, .008f, rear-front-.72f, red, 3);
            mesh.box(x*.970f, .61f, -3.20f, .012f, 3.69f, .008f, shade, 3);
        }
        for (float z : {front+.037f, rear-.037f}) {
            mesh.box(0, -1.247f, z, 7.63f, .031f, .034f, shade, 3);
            mesh.box(0, -1.247f, z + (z < 0 ? .022f : -.022f), 7.63f, .014f, .012f, red, 3);
            for (int i=0; i<8; ++i) {
                mesh.box(-3.36f+i*.96f,.58f,z,.944f,3.62f,.040f,wall);
            }
        }
        mesh.box(0, .50f, -5.29f, 6.65f, 3.33f, .12f, {.012f,.010f,.017f});
        mesh.box(0, 2.44f, -4.98f, 7.28f, .25f, .55f, {.041f,.033f,.057f});
        for (float x : {-2.42f, 0.0f, 2.42f}) {
            for (float z : {-4.73f, -1.63f, 1.45f}) {
                mesh.box(x, 2.518f, z, .19f, .02f, .13f, {.18f,.073f,.066f}, 3);
                mesh.box(x, 2.502f, z, .115f, .011f, .075f, {.93f,.71f,.63f}, 3);
            }
        }
        const XrVector3f leather{.067f,.045f,.085f}, pillows{.087f,.062f,.109f};
        mesh.box(0,-1.148f,.36f,3.57f,.32f,1.49f,leather,2);
        for (float x : {-1.09f, 0.0f, 1.09f}) {
            mesh.cushion(x,-.927f,.26f,1.06f,.20f,1.13f,pillows);
            mesh.cushion(x,-.67f,1.03f,1.06f,.59f,.29f,pillows);
        }
        for (float x : {-1.72f, 1.72f}) mesh.cushion(x,-.963f,.27f,.31f,.56f,1.46f,leather);
        for (float x : {-3.22f, 3.22f}) {
            mesh.box(x,-.89f,-5.14f,.46f,.63f,.37f,{.015f,.013f,.021f},2);
        }
        program = Program(vertex, fragment);
        glGenVertexArrays(1, &vao);
        glGenBuffers(1, &buffer);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, buffer);
        glBufferData(GL_ARRAY_BUFFER, mesh.vertices.size() * sizeof(Vertex), mesh.vertices.data(), GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Vertex), nullptr);
        glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, sizeof(Vertex), reinterpret_cast<void*>(offsetof(Vertex,color)));
        glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, sizeof(Vertex), reinterpret_cast<void*>(offsetof(Vertex,material)));
        glVertexAttribPointer(3, 3, GL_FLOAT, GL_FALSE, sizeof(Vertex), reinterpret_cast<const GLfloat*>(offsetof(Vertex,normal)));
        for (GLuint i = 0; i < 4; ++i) glEnableVertexAttribArray(i);
        count = static_cast<GLsizei>(mesh.vertices.size());
    }

    void Draw(const XrMatrix4x4f& vp, bool dark, GLuint light, float x, float y, float w, float h) {
        glUseProgram(program);
        glUniformMatrix4fv(glGetUniformLocation(program, "vp"), 1, GL_FALSE, vp.m);
        glUniform1i(glGetUniformLocation(program, "darkMode"), dark);
        glUniform1i(glGetUniformLocation(program, "hasLight"), light != 0);
        glUniform4f(glGetUniformLocation(program, "screenRect"), x, y, w, h);
        glUniform1i(glGetUniformLocation(program, "screenLight"), 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, light);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, count);
    }

    void Close() {
        glDeleteBuffers(1, &buffer);
        glDeleteVertexArrays(1, &vao);
        glDeleteProgram(program);
    }
};
} // namespace Cinema
