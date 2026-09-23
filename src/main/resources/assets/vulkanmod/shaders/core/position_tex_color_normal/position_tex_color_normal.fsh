#version 450
#include "fog.glsl"

layout(binding = 2) uniform sampler2D Sampler;
layout(binding = 3) uniform sampler2D Sampler2;

layout(binding = 1) uniform UBO{
    vec4 ColorModulator;
    vec4 LightmapCoord;
};

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in float vertexDistance;

layout(location = 0) out vec4 fragColor;
void main() {
    vec4 color = texture(Sampler, texCoord0) * vertexColor * ColorModulator;
    // 1.12.2 lightmap: a 16x16 texture sampled at the per-entity coordinate set
    // through OpenGlHelper.setLightmapTextureCoords, transformed as (coord + 8) /
    // 256 (the same transform block.vsh uses). Only rgb is modulated so the alpha
    // test below is unchanged. Without this the mob is drawn with no lightmap and
    // comes out unlit (full-bright).
    color.rgb *= texture(Sampler2, (LightmapCoord.xy + 8.0) / 256.0).rgb;
    // Alpha test: this is the entity/mob cutout shader. Vanilla 1.12.2 draws
    // mobs with GL_ALPHA_TEST on and discards anything below the 0.1 threshold
    // (RenderLivingBase leaves the default 0.1 func in place), which is what
    // carves out the transparent parts of a mob texture - eyes, the gaps
    // between legs, the inside of a hat layer.
    if (color.a < 0.1) {
        discard;
    }
    // Surviving fragments are opaque. Writing the texture's own alpha here
    // lets anti-aliased texel edges blend into whatever is behind them, and
    // because the entity pass runs with blending ON (the translucent layer is
    // still active from the terrain pass) the user sees straight through the
    // mob wherever the texture alpha is low but above the test threshold.
    // Forcing alpha to 1 matches the "alpha test = cutout" intent and is what
    // the sibling position_tex_normal shader does for the same reason.
    fragColor = vec4(color.rgb, 1.0);
}
