#version 450

layout(binding = 1) uniform UBO{
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    float FogEnabled;
};

layout(location = 0) in float vertexDistance;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = ColorModulator;
    // Vanilla fogs the sky dome (a POSITION-only mesh drawn flat in the sky
    // colour) so the zenith blends toward the fog/horizon colour and the
    // gradient appears. enableFog/disableFog drive FogEnabled - see
    // VRenderSystem.fogEnabled; before this the sky was one solid blue.
    // GL fogs RGB only and leaves alpha alone, so this is component-wise
    // rather than with linear_fog (which would also drive alpha toward
    // FogColor.a and turn the opaque dome into a blended sheet).
    float fogAmount = smoothstep(FogStart, FogEnd, vertexDistance) * FogColor.a;
    fragColor = (FogEnabled > 0.5)
            ? vec4(mix(color.rgb, FogColor.rgb, fogAmount), color.a)
            : color;
}
