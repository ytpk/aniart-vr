from pathlib import Path
import re
import sys

source = Path(sys.argv[1]).read_text()
license_header, *hooks = re.split(r"^//!DESC .*$", source, flags=re.M)
assert len(hooks) == 5
result = []
for hook in hooks:
    binds = re.findall(r"^//!BIND (\w+)$", hook, re.M)
    body = re.sub(r"^//!.*$", "", hook, flags=re.M).strip()
    declarations = []
    for name in binds:
        sampler = "original" if len(binds) == 2 and name == "MAIN" else "previous"
        declarations += [
            f"#define {name}_pos uv",
            f"#define {name}_size sourceSize",
            f"#define {name}_pt (1.0 / sourceSize)",
            f"#define {name}_tex(p) texture({sampler}, (p))",
            f"#define {name}_texOff(o) texture({sampler}, uv + (o) / sourceSize)",
        ]
    result.append("\n".join([
        "#version 300 es",
        "precision highp float;",
        "uniform sampler2D previous, original;",
        "uniform vec2 sourceSize;",
        "in vec2 uv;",
        "out vec4 color;",
        *declarations,
        body,
        "void main() { color = hook(); }",
    ]))
Path(sys.argv[2]).write_text(
    license_header + "\n#pragma once\nstatic constexpr const char* Anime4KPasses[] = {\n"
    + ",\n".join('R"shader(' + shader + ')shader"' for shader in result)
    + "\n};\n"
)
