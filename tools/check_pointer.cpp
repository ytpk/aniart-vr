#include "cinema_pointer.h"
#include <cassert>
#include <cmath>

int main() {
    using namespace Cinema;
    const auto library = PanelGeometry(1);
    const auto watch = PanelGeometry(2);
    XrPosef pose{{0, 0, 0, 1}, {0, library.center.y, 0}};
    auto center = Aim(pose, true, library);
    assert(center.hit && center.u == .5f && center.v == .5f);
    assert(center.end.z > library.center.z);
    pose.position.x = -library.w * .25f;
    pose.position.y += library.h * .25f;
    auto topLeft = Aim(pose, true, library);
    assert(topLeft.hit && std::abs(topLeft.u - .25f) < .001f && std::abs(topLeft.v - .25f) < .001f);
    assert(!Aim(pose, true, watch).hit);
    assert(!Aim(pose, false, library).visible);
    pose = {{0, 1, 0, 0}, {0, library.center.y, 0}};
    assert(!Aim(pose, true, library).hit);
    pose = {{0, .17364818f, 0, .98480775f}, {0, watch.center.y, 0}};
    auto rotated = Aim(pose, true, watch);
    assert(rotated.hit && rotated.u < .2f && rotated.v == .5f);
}
