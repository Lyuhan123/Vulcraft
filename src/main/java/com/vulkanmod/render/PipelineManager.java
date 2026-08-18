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

    public static ShaderInstance chooseShader(VertexFormat vertexFormat) {
        return shaderMap.get(vertexFormat);
    }

    private static GraphicsPipeline createPipeline(String configName, VertexFormat vertexFormat) {
        Pipeline.Builder pipelineBuilder = new Pipeline.Builder(vertexFormat, configName);

        try {
            ShaderInstance shader = new ShaderInstance(configName, vertexFormat);
            shaderMap.put(vertexFormat, shader);

            pipelineBuilder.setUniformSupplierGetter(info -> getUniformSupplier(info.name, shader));//uniform load
        } catch (IOException e) {
            throw new RuntimeException(e);
        }



        JsonObject config = ShaderLoadUtil.getJsonConfig("core", configName);
        pipelineBuilder.parseBindings(config);

        ShaderLoadUtil.loadShaders(pipelineBuilder, config, configName, "core");

        return pipelineBuilder.createGraphicsPipeline();
    }

    public static GraphicsPipeline getTerrainShader(VertexFormat vertexFormat) {
        return shaderGetter.apply(vertexFormat);
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
