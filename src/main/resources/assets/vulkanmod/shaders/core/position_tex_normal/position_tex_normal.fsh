#version 450
#include "fog.glsl"
#include "light.glsl"

layout(binding = 2) uniform sampler2D Sampler;
layout(binding = 3) uniform sampler2D Sampler2;

layout(binding = 1) uniform UBO{
    vec4 ColorModulator;
    vec4 LightmapCoord;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    vec4 EntityFlash;
    // Vanilla's two directional lights (RenderHelper.enableStandardItemLighting
    // -> GlStateManager.glLight -> VRenderSystem.lightDirection0/1). The glLight
    // overwrite already runs them through the CURRENT modelview, and vanilla
    // calls that before it applies each entity's own transform, so these carry
    // the CAMERA rotation. Shading them against the eye-space normal - which
    // carries that same camera rotation - makes the two cancel:
    //   dot(R_cam*R_entity*N, R_cam*L_world) == dot(R_entity*N, L_world)
    // leaving ONLY the entity's own facing. Moving the camera therefore cannot
    // change which face is lit, turning the entity can. This is exactly what
    // vanilla GL_LIGHTING does, and it is why the light directions - not a
    // fixed model-space table - are the right input here.
    vec3 Light0_Direction;
    vec3 Light1_Direction;
};

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in float vertexDistance;
// Eye-space normal from the vertex stage.
layout(location = 3) in vec3 eyeNormal;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler, texCoord0) * ColorModulator;
    // 1.12.2 lightmap: a 16x16 texture sampled at the per-entity coordinate set
    // through OpenGlHelper.setLightmapTextureCoords, transformed as (coord + 8) /
    // 256 (the same transform block.vsh uses). Only rgb is modulated, so the
    // alpha test below is unchanged. Without this the entity is drawn with no
    // lightmap at all and comes out full-bright.
    // Directional face shading. Evaluated per fragment so mobs with smooth
    // normals shade smoothly. minecraft_mix_light is 0.4 ambient + 0.6 per
    // light, clamped to 1.0 (see light.glsl), matching vanilla's standard item
    // lighting.
    //
    // Fallback: if nothing ever called glLight (so the directions are still
    // zero-length) fall back to vanilla's untransformed reference directions
    // rather than collapsing everything to bare ambient.
    vec3 light0 = Light0_Direction;
    vec3 light1 = Light1_Direction;
    if (dot(light0, light0) < 0.25 || dot(light1, light1) < 0.25) {
        light0 = normalize(vec3(0.2, 1.0, -0.7));
        light1 = normalize(vec3(-0.2, 1.0, 0.7));
    }
    float faceShade = minecraft_mix_light(light0, light1, normalize(eyeNormal), vec4(1.0)).r;
    color.rgb *= faceShade;
    // 1.12.2 mob hurt/burn flash: RenderLivingBase.setBrightness uploads the
    // combine constant (1,0,0,0.3) through GL_TEXTURE_ENV_COLOR and the lightmap
    // unit's GL_INTERPOLATE combine mixes it into the shaded texel by that
    // alpha. Reproduce exactly that mix here; alpha 0 leaves the texel alone.
    color.rgb = mix(color.rgb, EntityFlash.rgb, EntityFlash.a);

    color.rgb *= texture(Sampler2, (LightmapCoord.xy + 8.0) / 256.0).rgb;

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
