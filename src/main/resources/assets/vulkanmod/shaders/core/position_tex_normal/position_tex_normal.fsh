#version 450
#include "fog.glsl"

layout(binding = 2) uniform sampler2D Sampler;
layout(binding = 3) uniform sampler2D Sampler2;

layout(binding = 1) uniform UBO{
    vec4 ColorModulator;
    vec4 LightmapCoord;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    vec4 EntityFlash;
};

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in float vertexDistance;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler, texCoord0) * ColorModulator;
    // 1.12.2 lightmap: a 16x16 texture sampled at the per-entity coordinate set
    // through OpenGlHelper.setLightmapTextureCoords, transformed as (coord + 8) /
    // 256 (the same transform block.vsh uses). Only rgb is modulated, so the
    // alpha test below is unchanged. Without this the entity is drawn with no
    // lightmap at all and comes out full-bright.
    color.rgb *= texture(Sampler2, (LightmapCoord.xy + 8.0) / 256.0).rgb;
    // 1.12.2 mob hurt/burn flash: RenderLivingBase.setBrightness uploads the
    // combine constant (1,0,0,0.3) through GL_TEXTURE_ENV_COLOR and the lightmap
    // unit's GL_INTERPOLATE combine mixes it into the shaded texel by that
    // alpha. Reproduce exactly that mix here; alpha 0 leaves the texel alone.
    color.rgb = mix(color.rgb, EntityFlash.rgb, EntityFlash.a);
    if (color.a < 0.1) {
        discard;
    }
    // Alpha-tested fragments are opaque. Writing the texture's alpha lets
    // anti-aliased edges blend into the background (which is what vanilla
    // 1.12.2 also does with GL_BLEND still enabled from the translucent
    // layer), and the user sees straight through the mob. Treating the
    // surviving fragment as opaque matches the "alpha test = cutout"
    // intent and fixes the see-through.
    //
    // Fog is applied AFTER the alpha test and after the alpha is forced to 1,
    // so a fogged fragment stays opaque: fogging a still-translucent fragment
    // would let the terrain behind the mob show through as the fog thickens.
    // linear_fog() only touches rgb, so the forced alpha survives it.
    color.a = 1.0;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
