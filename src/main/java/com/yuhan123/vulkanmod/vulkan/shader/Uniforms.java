package com.yuhan123.vulkanmod.vulkan.shader;

import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
import com.yuhan123.vulkanmod.vulkan.util.MappedBuffer;

import java.util.function.Supplier;

public class Uniforms {

    public static Object2ReferenceOpenHashMap<String, Supplier<Integer>> vec1i_uniformMap = new Object2ReferenceOpenHashMap<>();

    public static Object2ReferenceOpenHashMap<String, Supplier<Float>> vec1f_uniformMap = new Object2ReferenceOpenHashMap<>();
    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> vec2f_uniformMap = new Object2ReferenceOpenHashMap<>();
    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> vec3f_uniformMap = new Object2ReferenceOpenHashMap<>();
    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> vec4f_uniformMap = new Object2ReferenceOpenHashMap<>();

    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> mat4f_uniformMap = new Object2ReferenceOpenHashMap<>();

    public static void setupDefaultUniforms() {

        VRenderSystem.calculateMVP();
        //Mat4
        mat4f_uniformMap.put("ModelViewMat", VRenderSystem::getModelViewMatrix);
        mat4f_uniformMap.put("ProjMat", VRenderSystem::getProjectionMatrix);
        mat4f_uniformMap.put("MVP", VRenderSystem::getMVP);
        mat4f_uniformMap.put("TextureMat", VRenderSystem::getTextureMatrix);

        //Vec1i
        vec1i_uniformMap.put("EndPortalLayers", () -> 15);
//        vec1i_uniformMap.put("FogShape", VRenderSystem::getShaderFogShape);

        //Vec1
        vec1f_uniformMap.put("FogStart", VRenderSystem::getShaderFogStart);
        vec1f_uniformMap.put("FogEnd", VRenderSystem::getShaderFogEnd);
        vec1f_uniformMap.put("LineWidth", VRenderSystem::getShaderLineWidth);
//        vec1f_uniformMap.put("GameTime", VRenderSystem::getShaderGameTime);
//        vec1f_uniformMap.put("GlintAlpha", VRenderSystem::getShaderGlintAlpha);
        vec1f_uniformMap.put("AlphaCutout", () -> VRenderSystem.alphaCutout);
        // Mirrors GL_LIGHTING (see VRenderSystem.lightingEnabled). The item
        // pipeline reads it to shade 3D block icons the way vanilla does while
        // leaving flat item icons full-bright. Published globally like the
        // lightmap below, or the UBO field would bind to the shader-local
        // VkUniform that nothing writes and stay at its JSON default.
        vec1f_uniformMap.put("LightingEnabled", () -> VRenderSystem.lightingEnabled ? 1.0f : 0.0f);
        // Selects fog for the cloud layer, which shares its pipeline with the
        // unfogged HUD. Published globally for the same reason as LightingEnabled
        // above - the shader-local VkUniform has no writer and would stay at the
        // JSON default. See VRenderSystem.fogEnabled.
        vec1f_uniformMap.put("FogEnabled", () -> VRenderSystem.fogEnabled ? 1.0f : 0.0f);

        //Vec2
        vec2f_uniformMap.put("ScreenSize", VRenderSystem::getScreenSize);

        //Vec3
        vec3f_uniformMap.put("Light0_Direction", () -> VRenderSystem.lightDirection0);
        vec3f_uniformMap.put("Light1_Direction", () -> VRenderSystem.lightDirection1);
        vec3f_uniformMap.put("ModelOffset", () -> VRenderSystem.modelOffset);
        // Camera position for the terrain fog distance. The batched and vanilla
        // terrain paths both feed WORLD-space vertices to block.vsh; fog is a
        // function of the vertex-to-camera distance, so the shader subtracts this.
        // Without it the fog measured from the world origin and disappeared.
        vec4f_uniformMap.put("ChunkOffset", () -> VRenderSystem.chunkOffset);

        //Vec4
        vec4f_uniformMap.put("ColorModulator", VRenderSystem::getShaderColor);
        vec4f_uniformMap.put("FogColor", VRenderSystem::getShaderFogColor);
        // Per-entity lightmap coordinate, written by OpenGlHelper.setLightmapTextureCoords
        // (RenderLivingBase.setBrightness) -> VRenderSystem.setLightmapCoord.
        //
        // Without a global supplier here the UBO field falls back to the shader's
        // own VkUniform buffer (Pipeline.Builder.parseUboNode -> ShaderInstance
        // .getUniformSupplier), and the only writer of those buffers,
        // setDefaultUniforms(), has no callers - so LightmapCoord stayed at the
        // JSON default (240,240), which samples the full-bright corner of the
        // lightmap: every entity rendered unlit.
        // When vanilla has switched the lightmap unit off (EntityRenderer
        // .disableLightmap - the state every GUI/HUD draw inherits), the
        // shader must sample the full-bright corner, not the stale world
        // coordinate. See VRenderSystem.lightmapEnabled.
        vec4f_uniformMap.put("LightmapCoord",
                () -> VRenderSystem.lightmapEnabled ? VRenderSystem.lightmapCoord : VRenderSystem.unlitLightmapCoord);
        // Mob hurt/burn flash colour (see VRenderSystem.setFlashColor). Without a
        // global supplier the UBO field would bind to the shader-local VkUniform,
        // which nothing writes, and the flash would stay invisible.
        vec4f_uniformMap.put("EntityFlash", () -> VRenderSystem.flashColor);

    }

    public static Supplier<MappedBuffer> getUniformSupplier(String type, String name) {
        return switch (type) {
            case "mat4" -> Uniforms.mat4f_uniformMap.get(name);
            case "vec4" -> Uniforms.vec4f_uniformMap.get(name);
            case "vec3" -> Uniforms.vec3f_uniformMap.get(name);
            case "vec2" -> Uniforms.vec2f_uniformMap.get(name);

            default -> null;
        };
    }
}
