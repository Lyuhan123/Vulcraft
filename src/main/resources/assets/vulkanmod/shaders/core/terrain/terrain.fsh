#version 450

// Block atlas (binding 0: Sampler0 in terrain.json). The lightmap is folded
// into vertexColor by terrain.vsh, so this stage only samples the atlas.
layout(binding = 0) uniform sampler2D Sampler0;

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a < 0.1) {
        discard;
    }
    fragColor = color;
}
