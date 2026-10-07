#version 450

layout(binding = 1) uniform UBO{
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    float FogEnabled;
};

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in float vertexDistance;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = vertexColor * ColorModulator;
    if (color.a == 0.0) {
        discard;
    }
    // Mirrors the cloud/sky fog gating: FogEnabled is driven by
    // GlStateManager.enableFog/disableFog (VRenderSystem.fogEnabled). The
    // sky's black horizon box is fogged exactly as vanilla fogs it; the
    // sunrise/sunset band is drawn with fog disabled so it stays crisp.
    // GL fogs RGB only, so the mix is component-wise (alpha is preserved).
    float fogAmount = smoothstep(FogStart, FogEnd, vertexDistance) * FogColor.a;
    fragColor = (FogEnabled > 0.5)
            ? vec4(mix(color.rgb, FogColor.rgb, fogAmount), color.a)
            : color;
}
