


#version 450

#include "fog.glsl"

layout(binding = 2) uniform sampler2D Sampler0;

layout(binding = 1) uniform UBO{
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    float FogEnabled;
};

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in float vertexDistance;
layout(location = 2) in vec2 texCoord0;
layout(location = 0) out vec4 fragColor;

void main() {
    // GL's default texture environment is MODULATE: texel * primary colour.
    // The end-portal renderer relies on exactly this - its per-pass vertex
    // colours are the random dark tints, and the starfield is the texture.
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a == 0.0) {
        discard;
    }
    // Same fog gating as position_color: RGB-only mix, alpha preserved.
    float fogAmount = smoothstep(FogStart, FogEnd, vertexDistance) * FogColor.a;
    fragColor = (FogEnabled > 0.5)
            ? vec4(mix(color.rgb, FogColor.rgb, fogAmount), color.a)
            : color;
}
