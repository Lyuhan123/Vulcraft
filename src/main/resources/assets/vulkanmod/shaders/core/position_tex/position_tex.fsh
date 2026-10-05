#version 450
#include "fog.glsl"

layout(binding = 2) uniform sampler2D Sampler;
layout(binding = 3) uniform sampler2D Sampler2;

layout(binding = 1) uniform UBO{
    vec4 ColorModulator;
    vec4 LightmapCoord;
};

layout(location = 0) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler, texCoord0) * ColorModulator;
    // 1.12.2 draws arrows (RenderArrow) and most projectile models through
    // POSITION_TEX. Vanilla lights them via the fixed-function lightmap on
    // texture unit 1, but this port samples the lightmap in the shader instead
    // (same transform item.fsh / block.vsh use: a 16x16 texture sampled at
    // (coord + 8) / 256). Without this arrows and ender pearls were drawn
    // with no lightmap at all and came out full-bright.
    //
    // The default LightmapCoord is (240,240) - the full-bright corner - so a
    // draw that never received a coordinate (any GUI text/icon using this
    // format, the main menu before a world exists) keeps its old look instead
    // of going black.
    color.rgb *= texture(Sampler2, (LightmapCoord.xy + 8.0) / 256.0).rgb;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color;
}
