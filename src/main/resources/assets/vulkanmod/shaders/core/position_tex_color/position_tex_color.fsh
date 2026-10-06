#version 450
#include "fog.glsl"

layout(binding = 2) uniform sampler2D Sampler;

layout(binding = 1) uniform UBO{
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    float FogEnabled;
};

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in float vertexDistance;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    // Vanilla fogs the cloud layer: distant clouds fade into the sky. This
    // pipeline is shared with the HUD, the chat and every item tooltip, which
    // vanilla draws with fog OFF, so the fade is gated instead of applied
    // unconditionally - see VRenderSystem.fogEnabled. Before this existed the
    // shader never called linear_fog at all, which is why far clouds stayed
    // flat and bright however dense the fog was.
    // GL fogs RGB only and leaves alpha alone, so this is done component-wise
    // rather than with linear_fog: that helper mixes the whole vec4, which would
    // also drive the alpha toward FogColor.a (1) and turn distant clouds into an
    // opaque sheet instead of a layer that fades into the sky.
    float fogAmount = smoothstep(FogStart, FogEnd, vertexDistance) * FogColor.a;
    fragColor = (FogEnabled > 0.5)
            ? vec4(mix(color.rgb, FogColor.rgb, fogAmount), color.a)
            : color;
}
