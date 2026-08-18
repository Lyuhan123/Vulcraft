
#version 450

#include "fog.glsl"

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;

layout(binding = 0) uniform UniformBufferObject {
    mat4 MVP;
};

layout(binding = 3) uniform sampler2D Sampler2;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out float vertexDistance;
layout(location = 3) out vec2 lightmapCoord;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);

    vertexDistance = fog_distance(Position.xyz, 0);
    texCoord0 = UV0;
    // 1.12.2 lightmap: UV2 is in [0, 240], lightmap is a 16x16 texture.
    // The vanilla texture matrix scales by 1/256 and translates by 8 pixels,
    // so the sample coordinate is (UV2 + 8) / 256.
    lightmapCoord = (vec2(UV2) + 8.0) / 256.0;
    vertexColor = Color;
}