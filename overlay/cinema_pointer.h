#pragma once
#include <algorithm>
#include <openxr/openxr.h>

namespace Cinema {
struct Pointer {
    XrVector3f origin{}, end{};
    float u = -1, v = -1;
    bool visible = false;
    bool hit = false;
};
struct Panel { XrVector3f center; float w, h; };
inline Panel PanelGeometry(int page) {
    return page == 2 ? Panel{{0, -.80f, -2.24f}, 2.35f, 2.35f * 300 / 1280}
                     : Panel{{0, .10f, -2.42f}, 2.60f, 2.60f * 760 / 1280};
}

inline XrVector3f Add(XrVector3f a, XrVector3f b, float scale = 1) {
    return {a.x+b.x*scale, a.y+b.y*scale, a.z+b.z*scale};
}

inline Pointer Aim(const XrPosef& pose, bool tracked, const Panel& geometry) {
    if (!tracked) return {};
    const auto& q = pose.orientation;
    const XrVector3f direction{-2*(q.x*q.z+q.w*q.y), -2*(q.y*q.z-q.w*q.x),
                               -1+2*(q.x*q.x+q.y*q.y)};
    Pointer p;
    p.origin = pose.position;
    p.end = Add(p.origin, direction, 1.8f);
    p.visible = true;
    if (direction.z >= -.015f) return p;
    const float t = (geometry.center.z - p.origin.z) / direction.z;
    if (t < .025f || t > 5.0f) return p;
    const XrVector3f point = Add(p.origin, direction, t);
    p.u = (point.x - geometry.center.x) / geometry.w + .5f;
    p.v = .5f - (point.y - geometry.center.y) / geometry.h;
    p.hit = p.u >= 0 && p.u < 1 && p.v >= 0 && p.v < 1;
    p.end = p.hit ? Add(p.origin, direction, t - .004f) : Add(p.origin, direction, std::min(t, 1.8f));
    return p;
}

} // namespace Cinema
