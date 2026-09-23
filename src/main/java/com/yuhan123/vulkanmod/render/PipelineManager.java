package com.yuhan123.vulkanmod.render;

import com.google.gson.JsonObject;
import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.render.shader.ShaderInstance;
import com.yuhan123.vulkanmod.render.shader.ShaderLoadUtil;

import com.yuhan123.vulkanmod.render.shader.VkUniform;
import com.yuhan123.vulkanmod.vulkan.shader.GraphicsPipeline;
import com.yuhan123.vulkanmod.vulkan.shader.Pipeline;
import com.yuhan123.vulkanmod.vulkan.util.MappedBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.resources.FileResourcePack;
import net.minecraft.client.resources.FolderResourcePack;
import net.minecraft.client.resources.IResourcePack;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.asm.FMLSanityChecker;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.CodeSource;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static net.minecraft.client.renderer.vertex.DefaultVertexFormats.*;
import static org.apache.commons.compress.harmony.archive.internal.nls.Messages.getString;

//import net.minecraft.client.renderer.RenderType;
//import com.yuhan123.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
//import com.yuhan123.vulkanmod.render.vertex.CustomVertexFormat;
//import com.yuhan123.vulkanmod.render.vertex.TerrainRenderType;

public abstract class PipelineManager {


    //    public static VertexFormat terrainVertexFormat;
    static GraphicsPipeline blockPipeline, itemPipeline, particlePipeline, positionPipeline,
            positionColorPipeline, positionNormalPipeline, positionTexPipeline, positionTexColorPipeline,
            positionTexColorNormalPipeline, positionTexLightmapColorPipeline, positionTexNormalPipeline,
            blitPipeline;
    private static Function<VertexFormat, GraphicsPipeline> shaderGetter;
    private final static Map<VertexFormat, ShaderInstance> shaderMap = new HashMap<>();
    public final static VertexFormat blitFormat = new VertexFormat();

//    public static void setTerrainVertexFormat(VertexFormat format) {
//        terrainVertexFormat = format;
//    }

    public static void init() {
//        setTerrainVertexFormat(CustomVertexFormat.COMPRESSED_TERRAIN);
        createCorePipelines();
//        setDefaultShader();
//        ThreadBuilderPack.defaultTerrainBuilderConstructor();
    }

    public static void setDefaultShader() {
//        setShaderGetter(POSITION_TEX_COLOR);
//                vertexFormat -> vertexFormat == TerrainRenderType.TRANSLUCENT ? terrainShaderEarlyZ : terrainShader);
    }

    private static IResourcePack createResourcePack(File file)
    {
        if(file.isDirectory())
        {
            return new FolderResourcePack(file);
        }
        else
        {
            return new FileResourcePack(file);
        }
    }

    private static File getModRoot(Class<?> anchor) {
        try {
            CodeSource cs = anchor.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return null;
            return new File(cs.getLocation().toURI());
        } catch (Exception e) {
            return null;
        }
    }

    private static void createCorePipelines() {
        blockPipeline = createPipeline("block", BLOCK);
        itemPipeline = createPipeline("item", ITEM);
        particlePipeline = createPipeline("particle", PARTICLE_POSITION_TEX_COLOR_LMAP);
        positionPipeline = createPipeline("position", POSITION);
        positionColorPipeline = createPipeline("position_color", POSITION_COLOR);
        positionNormalPipeline = createPipeline("position_normal", POSITION_NORMAL);
        positionTexPipeline = createPipeline("position_tex", POSITION_TEX);
        positionTexColorPipeline = createPipeline("position_tex_color", POSITION_TEX_COLOR);
        positionTexColorNormalPipeline = createPipeline("position_tex_color_normal", POSITION_TEX_COLOR_NORMAL);
        positionTexLightmapColorPipeline = createPipeline("position_tex_lightmap_color", POSITION_TEX_LMAP_COLOR);
        positionTexNormalPipeline = createPipeline("position_tex_normal", POSITION_TEX_NORMAL);
        blitPipeline = createPipeline("blit", blitFormat);
    }


    public static Supplier<MappedBuffer> getUniformSupplier(String name, ShaderInstance shader) {
        VkUniform uniform1 = shader.uniformMap.get(name);

        if (uniform1 == null) {
            VulkanMod.LOGGER.error(String.format("Error: field %s not present in uniform map", name));
            return null;
        }

        Supplier<MappedBuffer> supplier;
        ByteBuffer byteBuffer;

        if (uniform1.getType() <= 3) {
            byteBuffer = MemoryUtil.memByteBuffer(uniform1.getIntBuffer());
        } else if (uniform1.getType() <= 10) {
            byteBuffer = MemoryUtil.memByteBuffer(uniform1.getFloatBuffer());
        } else {
            throw new RuntimeException("out of bounds value for uniform " + uniform1);
        }

        MappedBuffer mappedBuffer = MappedBuffer.createFromBuffer(byteBuffer);
        supplier = () -> mappedBuffer;

        return supplier;
    }

    /**
     * Last format resolved, and its shader. The lookup runs once per draw call and
     * a frame issues thousands of them, virtually all with the same format object
     * (the terrain pointer state resolves to the shared
     * DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL instance). A HashMap lookup
     * there costs a VertexFormat.hashCode() - which walks two ArrayLists, because
     * 1.12.2's VertexFormat does not cache its hash - plus the equality check on
     * collision. A reference comparison short-circuits all of it.
     *
     * Only hits are memoised: a miss has to reach the map every time, otherwise a
     * format looked up before its pipeline was registered would stay null.
     */
    private static VertexFormat lastChosenFormat;
    private static ShaderInstance lastChosenShader;

    public static ShaderInstance chooseShader(VertexFormat vertexFormat) {
        if (vertexFormat == lastChosenFormat) {
            return lastChosenShader;
        }

        ShaderInstance shader = shaderMap.get(vertexFormat);

        if (shader != null) {
            lastChosenFormat = vertexFormat;
            lastChosenShader = shader;
        }

        return shader;
    }

    /**
     * Builds the shader for {@code configName} and returns the pipeline draws
     * will actually use.
     *
     * <p>{@link ShaderInstance}'s constructor already reads the same JSON through
     * the same {@link Pipeline.Builder} and assigns the result to its own
     * {@code pipeline} field - which is the object every draw reaches via
     * {@code ShaderInstance.bindPipeline() -> Renderer.bindGraphicsPipeline()}.
     * Building a second {@code GraphicsPipeline} here used to be pure dead
     * weight, and worse than dead: it was the object stored in the
     * {@code blockPipeline} field, so anything that configured "the terrain
     * pipeline" configured one that never recorded a single draw. That is
     * exactly why the arena's per-instance matrix array
     * ({@code Drawer.setCurrentFrame -> Pipeline.setStaticBuffer}) was written
     * into a descriptor set nobody bound, while the drawn pipeline's
     * {@code Matrices} binding stayed uninitialised.
     */
    private static GraphicsPipeline createPipeline(String configName, VertexFormat vertexFormat) {
        try {
            ShaderInstance shader = new ShaderInstance(configName, vertexFormat);
            shaderMap.put(vertexFormat, shader);

            GraphicsPipeline pipeline = shader.getPipeline();

            if (pipeline == null)
                throw new IllegalStateException("Shader '" + configName + "' produced no pipeline");

            return pipeline;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static GraphicsPipeline getTerrainShader(VertexFormat vertexFormat) {
        return shaderGetter.apply(vertexFormat);
    }

    /** The terrain (chunk) pipeline. */
    public static GraphicsPipeline getBlockPipeline() {
        return blockPipeline;
    }

    public static void setShaderGetter(Function<VertexFormat, GraphicsPipeline> consumer) {
        shaderGetter = consumer;
    }

//
//    public static GraphicsPipeline getTerrainDirectShader(RenderType renderType) {
//        return terrainShader;
//    }
//
//    public static GraphicsPipeline getTerrainIndirectShader(RenderType renderType) {
//        return terrainShaderEarlyZ;
//    }
//
//    public static GraphicsPipeline getFastBlitPipeline() {
//        return fastBlitPipeline;
//    }
//
//    public static GraphicsPipeline getCloudsPipeline() {
//        return cloudsPipeline;
//    }

    public static void destroyPipelines() {
        blockPipeline.cleanUp();
        itemPipeline.cleanUp();
        particlePipeline.cleanUp();
        positionPipeline.cleanUp();
        positionColorPipeline.cleanUp();
        positionNormalPipeline.cleanUp();
        positionTexPipeline.cleanUp();
        positionTexColorPipeline.cleanUp();
        positionTexColorNormalPipeline.cleanUp();
        positionTexLightmapColorPipeline.cleanUp();
        positionTexNormalPipeline.cleanUp();
    }
}
