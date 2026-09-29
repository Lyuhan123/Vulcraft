#version 450
#include "fog.glsl"

layout(binding = 2) uniform sampler2D Sampler;
layout(binding = 3) uniform sampler2D Sampler2;

layout(binding = 1) uniform UBO{
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    vec4 LightmapCoord;
};

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in float vertexDistance;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler, texCoord0) * vertexColor * ColorModulator;
    // 1.12.2 lights in-world items and falling blocks through the lightmap
    // texture on texture unit 1, whose per-entity coordinate arrives through
    // OpenGlHelper.setLightmapTextureCoords (same transform block.vsh uses:
    // a 16x16 texture sampled at (coord + 8) / 256). Without this the dropped
    // item / falling block is drawn unlit (full-bright). The default
    // LightmapCoord of (240,240) is the full-bright corner, so a draw that
    // never received a coordinate keeps its old look instead of going black.
    color.rgb *= texture(Sampler2, (LightmapCoord.xy + 8.0) / 256.0).rgb;
    if (color.a < 0.1) {
        discard;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
