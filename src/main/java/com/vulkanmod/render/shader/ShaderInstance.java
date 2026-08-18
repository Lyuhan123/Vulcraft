package com.yuhan123.vulkanmod.render.shader;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.gl.VkGlProgram;
import com.yuhan123.vulkanmod.gl.VkGlTexture;
import com.yuhan123.vulkanmod.render.shader.ShaderLoadUtil;
import com.yuhan123.vulkanmod.render.shader.VkUniform;
import com.yuhan123.vulkanmod.vulkan.Drawer;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.index.AutoIndexBuffer;
import com.yuhan123.vulkanmod.vulkan.shader.GraphicsPipeline;
import com.yuhan123.vulkanmod.vulkan.shader.Pipeline;
import com.yuhan123.vulkanmod.vulkan.shader.converter.GlslConverter;
import com.yuhan123.vulkanmod.vulkan.shader.descriptor.UBO;
import com.yuhan123.vulkanmod.vulkan.shader.layout.Uniform;
import com.yuhan123.vulkanmod.vulkan.texture.VTextureSelector;
import com.yuhan123.vulkanmod.vulkan.util.MappedBuffer;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.shader.ShaderLoader;
import net.minecraft.client.util.JsonException;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.JsonUtils;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.Display;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Unique;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.util.*;
import java.util.function.Supplier;

import static com.yuhan123.vulkanmod.gl.VkGlTexture.activeTexture;
import static com.yuhan123.vulkanmod.render.shader.ShaderLoadUtil.getResource;

public class ShaderInstance {
    private final String name;
    private final VertexFormat vertexFormat;
    private final List<VkUniform> uniforms = Lists.newArrayList();
    //    private final List<Integer> attribLocations;
    private List<String> attributes = new ArrayList<>();
    private final Map<String, Object> samplerMap = Maps.newHashMap();
    private final List<String> samplerNames = Lists.newArrayList();
    private GraphicsPipeline pipeline;
    private static int lastProgramId = -1;
    private final List<Integer> samplerLocations = Lists.newArrayList();
    private final List<Integer> uniformLocations = Lists.newArrayList();
    public final Map<String, VkUniform> uniformMap = Maps.newHashMap();

    private String fsName;
    private String vsPath;
    boolean doUniformUpdate = false;

//    private GraphicsPipeline pipeline;

    @Nullable
    public final VkUniform MODEL_VIEW_MATRIX;
    @Nullable
    public final VkUniform PROJECTION_MATRIX;
    @Nullable
    public final VkUniform TEXTURE_MATRIX;
    @Nullable
    public final VkUniform SCREEN_SIZE;
    @Nullable
    public final VkUniform COLOR_MODULATOR;
    @Nullable
    public final VkUniform LIGHT0_DIRECTION;
    @Nullable
    public final VkUniform LIGHT1_DIRECTION;
    @Nullable
    public final VkUniform GLINT_ALPHA;
    @Nullable
    public final VkUniform FOG_START;
    @Nullable
    public final VkUniform FOG_END;
    @Nullable
    public final VkUniform FOG_COLOR;
    @Nullable
    public final VkUniform FOG_SHAPE;
    @Nullable
    public final VkUniform LINE_WIDTH;
    @Nullable
    public final VkUniform GAME_TIME;
    @Nullable
    public final VkUniform CHUNK_OFFSET;
    private boolean dirty;

//    private boolean doUniformUpdate = false;
    private int programId;

    public GraphicsPipeline getPipeline() {
        return pipeline;
    }

    public ShaderInstance(String programName, VertexFormat vertexFormat) throws IOException {
        this.name = programName;
        this.vertexFormat = vertexFormat;
        JsonParser jsonparser = new JsonParser();
        ResourceLocation resourceLocation = new ResourceLocation("vulkanmod:shaders/core/" + programName + "/" + programName + ".json");
        InputStream iresource = null;

        try {
            iresource = getResource(resourceLocation);
            JsonObject jsonObject = jsonparser.parse(IOUtils.toString(iresource, StandardCharsets.UTF_8)).getAsJsonObject();
            String s = JsonUtils.getString(jsonObject, "vertex");
            String s1 = JsonUtils.getString(jsonObject, "fragment");
            JsonArray jsonArray = JsonUtils.getJsonArray(jsonObject, "samplers", null);
            if (jsonArray != null) {
                int i = 0;

                for (JsonElement jsonElement : jsonArray) {
                    try {
                        this.parseSampler(jsonElement);
                    } catch (Exception exception) {
                        JsonException jsonexception1 = JsonException.forException(exception);
                        jsonexception1.prependJsonKey("samplers[" + i + "]");
                        throw jsonexception1;
                    }

                    ++i;
                }
            }
            JsonArray jsonarray1 = JsonUtils.getJsonArray(jsonObject, "attributes", null);

            if (jsonarray1 != null) {
                int j = 0;
//            this.attribLocations = Lists.newArrayListWithCapacity(jsonarray1.size());
                this.attributes = Lists.newArrayListWithCapacity(jsonarray1.size());

                for (JsonElement jsonelement1 : jsonarray1) {
                    try {
                        this.attributes.add(JsonUtils.getString(jsonelement1, "attribute"));
                    } catch (Exception exception1) {
                        JsonException jsonexception2 = JsonException.forException(exception1);
                        jsonexception2.prependJsonKey("attributes[" + j + "]");
                        throw jsonexception2;
                    }

                    j++;
                }
            } else {
//            this.attribLocations = null;
                this.attributes = null;
            }

            JsonArray jsonArray2 = GsonHelper.getAsJsonArray(jsonObject, "uniforms", (JsonArray) null);
            if (jsonArray2 != null) {
                int j = 0;

                for (JsonElement jsonElement2 : jsonArray2) {
                    try {
                        this.parseUniform(jsonElement2);
                    } catch (Exception exception2) {
                        throw new RuntimeException(exception2);
                    }

                    ++j;
                }
            }

            getOrCreate(ShaderLoader.ShaderType.VERTEX, s);
            getOrCreate(ShaderLoader.ShaderType.FRAGMENT, s1);
            this.programId = OpenGlHelper.glCreateProgram();
            int j = 0;

//        for(String string4 : vertexFormat.getElementAttributeNames()) {
//            Uniform.glBindAttribLocation(this.programId, j, string4);
//            ++j;
//        }

//        ProgramManager.linkShader(this);
            this.updateLocations();
        } catch (Exception exception3) {
            throw new RuntimeException(exception3);
        }

        this.markDirty();
        this.MODEL_VIEW_MATRIX = this.getUniform("ModelViewMat");
        this.PROJECTION_MATRIX = this.getUniform("ProjMat");
        this.TEXTURE_MATRIX = this.getUniform("TextureMat");
        this.SCREEN_SIZE = this.getUniform("ScreenSize");
        this.COLOR_MODULATOR = this.getUniform("ColorModulator");
        this.LIGHT0_DIRECTION = this.getUniform("Light0_Direction");
        this.LIGHT1_DIRECTION = this.getUniform("Light1_Direction");
        this.GLINT_ALPHA = this.getUniform("GlintAlpha");
        this.FOG_START = this.getUniform("FogStart");
        this.FOG_END = this.getUniform("FogEnd");
        this.FOG_COLOR = this.getUniform("FogColor");
        this.FOG_SHAPE = this.getUniform("FogShape");
        this.LINE_WIDTH = this.getUniform("LineWidth");
        this.GAME_TIME = this.getUniform("GameTime");
        this.CHUNK_OFFSET = this.getUniform("ChunkOffset");

        create(programName, vertexFormat);
    }


    private void create(String name, VertexFormat format) {
        String configName = name;
        JsonObject config = ShaderLoadUtil.getJsonConfig("core", configName);

        if (config == null) {
            createLegacyShader(format);
        } else {
            createPipeline(configName, format, config);
        }

        VkGlProgram program = VkGlProgram.getProgram(this.programId);
        program.bindPipeline(this.pipeline);
    }


    public void markDirty() {
        this.dirty = true;
    }

    public VkUniform getUniform(String string) {
        return this.uniformMap.get(string);
    }

    private void parseSampler(JsonElement element) throws JsonException {
        JsonObject jsonobject = JsonUtils.getJsonObject(element, "sampler");
        String s = JsonUtils.getString(jsonobject, "name");

        if (!JsonUtils.isString(jsonobject, "file")) {
            this.samplerMap.put(s, null);
            this.samplerNames.add(s);
        } else {
            this.samplerNames.add(s);
        }
    }


    private void parseUniform(JsonElement jsonElement) {
        JsonObject jsonObject = GsonHelper.convertToJsonObject(jsonElement, "uniform");
        String string = GsonHelper.getAsString(jsonObject, "name");
        int i = VkUniform.getTypeFromString(GsonHelper.getAsString(jsonObject, "type"));
        int j = GsonHelper.getAsInt(jsonObject, "count");
        float[] fs = new float[Math.max(j, 16)];
        JsonArray jsonArray = GsonHelper.getAsJsonArray(jsonObject, "values");
        if (jsonArray.size() != j && jsonArray.size() > 1) {
//            throw new ChainedJsonException("Invalid amount of values specified (expected " + j + ", found " + jsonArray.size() + ")");
        } else {
            int k = 0;

            for (JsonElement jsonElement2 : jsonArray) {
                try {
                    fs[k] = GsonHelper.convertToFloat(jsonElement2, "value");
                } catch (Exception exception) {
//                    ChainedJsonException chainedJsonException = ChainedJsonException.forException(exception);
//                    chainedJsonException.prependJsonKey("values[" + k + "]");
                    throw new RuntimeException(exception);
                }

                ++k;
            }

            if (j > 1 && jsonArray.size() == 1) {
                while (k < j) {
                    fs[k] = fs[0];
                    ++k;
                }
            }

            int l = j > 1 && j <= 4 && i < 8 ? j - 1 : 0;
            VkUniform uniform = new VkUniform(string, i + l, j);
            if (i <= 3) {
                uniform.setSafe((int) fs[0], (int) fs[1], (int) fs[2], (int) fs[3]);
            } else if (i <= 7) {
                uniform.setSafe(fs[0], fs[1], fs[2], fs[3]);
            } else {
                uniform.set(Arrays.copyOfRange(fs, 0, j));
            }

            this.uniforms.add(uniform);
        }
    }

    private void updateLocations() {
//        RenderSystem.assertOnRenderThread();
        IntList intList = new IntArrayList();

        for (int i = 0; i < this.samplerNames.size(); ++i) {
            String string = (String) this.samplerNames.get(i);
            int j = 1;
            if (j == -1) {
                VulkanMod.LOGGER.warn("Shader {} could not find sampler named {} in the specified shader program.", this.name, string);
                this.samplerMap.remove(string);
                intList.add(i);
            } else {
                this.samplerLocations.add(j);
            }
        }

        for (int i = intList.size() - 1; i >= 0; --i) {
            int k = intList.getInt(i);
            this.samplerNames.remove(k);
        }

        for (VkUniform uniform : uniforms) {
            String string2 = uniform.getName();
            int l = 1;
            if (l == -1) {
                VulkanMod.LOGGER.warn("Shader {} could not find uniform named {} in the specified shader program.", this.name, string2);
            } else {
                this.uniformLocations.add(l);
                uniform.setLocation(l);
                this.uniformMap.put(string2, uniform);

            }
        }
    }

    public void close() {
        if (this.pipeline != null)
            this.pipeline.cleanUp();
    }

    private void getOrCreate(ShaderLoader.ShaderType shaderType, String name) {
        String path = "shaders/core/%s".formatted(name);


        switch (shaderType) {
            case VERTEX -> vsPath = path;
            case FRAGMENT -> fsName = path;
        }
    }

    public void apply() {
        if (this.doUniformUpdate) {

            for (int j = 0; j < this.samplerLocations.size(); ++j) {
                String string = this.samplerNames.get(j);
                if (this.samplerMap.get(string) != null) {
                    activeTexture(33984 + j);
                    Object object = this.samplerMap.get(string);
                    int texId = -1;
                    if (object instanceof AbstractTexture) {
                        texId = ((AbstractTexture) object).getGlTextureId();
                    } else if (object instanceof Integer) {
                        texId = (Integer) object;
                    }

                    if (texId != -1) {
                        VkGlTexture.bindTexture(texId);
//                    setShaderTexture(j, texId);
                    }
                }
            }

            for (VkUniform uniform : this.uniforms) {
                uniform.upload();
            }

        }

        if (this.programId != lastProgramId) {
            OpenGlHelper.glUseProgram(this.programId);
            lastProgramId = this.programId;
        }

        bindPipeline();
    }

    public void setDefaultUniforms(int mode, Matrix4f modelView, Matrix4f projection) {
        if (!this.doUniformUpdate)
            return;

        if (this.MODEL_VIEW_MATRIX != null) {
            this.MODEL_VIEW_MATRIX.set(modelView);
        }

        if (this.PROJECTION_MATRIX != null) {
            this.PROJECTION_MATRIX.set(projection);
        }

        if (this.COLOR_MODULATOR != null) {
            this.COLOR_MODULATOR.set(VRenderSystem.getColor());
        }

        if (this.GLINT_ALPHA != null) {
            this.GLINT_ALPHA.set(VRenderSystem.getShaderGlintAlpha());
        }

        if (this.FOG_START != null) {
            this.FOG_START.set(VRenderSystem.getShaderFogStart());
        }

        if (this.FOG_END != null) {
            this.FOG_END.set(VRenderSystem.getShaderFogEnd());
        }

        if (this.FOG_COLOR != null) {
            this.FOG_COLOR.set(VRenderSystem.getFogColor());
        }

//        if (this.FOG_SHAPE != null) {
//            this.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
//        }

//        if (this.TEXTURE_MATRIX != null) {
//            this.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
//        }

//        if (this.GAME_TIME != null) {
//            this.GAME_TIME.set(RenderSystem.getShaderGameTime());
//        }

        if (this.SCREEN_SIZE != null) {
            this.SCREEN_SIZE.set((float) Display.getWidth(), (float) Display.getHeight());
        }

        if (this.LINE_WIDTH != null && (mode == AutoIndexBuffer.DrawType.LINES || mode == AutoIndexBuffer.DrawType.DEBUG_LINE_STRIP)) {
            this.LINE_WIDTH.set(VRenderSystem.getShaderLineWidth());
        }

//        RenderSystem.setupShaderLights((ShaderInstance) (Object) this);
    }

    private void bindPipeline() {
        if (this.pipeline == null) {
            throw new NullPointerException("Shader %s has no initialized pipeline".formatted(this.name));
        }

        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline(pipeline);
        VTextureSelector.bindShaderTextures(pipeline);
        renderer.uploadAndBindUBOs(pipeline);
    }

    public void setupUniformSuppliers(UBO ubo) {
        for (Uniform vUniform : ubo.getUniforms()) {
            VkUniform uniform = this.uniformMap.get(vUniform.getName());

            Supplier<MappedBuffer> supplier;
            ByteBuffer byteBuffer;

            if (uniform == null) {
                VulkanMod.LOGGER.error(String.format("Error: field %s not present in uniform map", vUniform.getName()));

                int size = vUniform.getSize();
                byteBuffer = MemoryUtil.memAlloc(size * 4);
            } else if (uniform.getType() <= 3) {
                byteBuffer = MemoryUtil.memByteBuffer(uniform.getIntBuffer());
            } else if (uniform.getType() <= 10) {
                byteBuffer = MemoryUtil.memByteBuffer(uniform.getFloatBuffer());
            } else {
                throw new RuntimeException("out of bounds value for uniform " + uniform);
            }


            MappedBuffer mappedBuffer = MappedBuffer.createFromBuffer(byteBuffer);
            supplier = () -> mappedBuffer;

            vUniform.setSupplier(supplier);
        }
    }


    public Supplier<MappedBuffer> getUniformSupplier(String name) {
        VkUniform uniform1 = uniformMap.get(name);

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

    public void setDoUniformsUpdate() {
        this.doUniformUpdate = true;
    }

    public void setPipeline(GraphicsPipeline graphicsPipeline) {
        this.pipeline = graphicsPipeline;
    }

    private void createPipeline(String configName, VertexFormat format, JsonObject config) {
        Pipeline.Builder builder = new Pipeline.Builder(format, configName);
        builder.setUniformSupplierGetter(info -> this.getUniformSupplier(info.name));

        builder.parseBindings(config);

        ShaderLoadUtil.loadShaders(builder, config, configName, "core");

        GraphicsPipeline pipeline = builder.createGraphicsPipeline();
        this.pipeline = pipeline;
    }

    private void createLegacyShader(VertexFormat format) {

        InputStream iresource = null;

        try {
            String vertPath = vsPath + ".vsh";
            ResourceLocation shaderResource = new ResourceLocation("vulkanmod:" + vertPath);
            iresource = getResource(shaderResource);

            String vshSrc = IOUtils.toString(iresource, StandardCharsets.UTF_8);

            String fragPath = fsName + ".fsh";
            shaderResource = new ResourceLocation("vulkanmod:" + fragPath);
            iresource = getResource(shaderResource);
//            inputStream = resource.open();
            String fshSrc = IOUtils.toString(iresource, StandardCharsets.UTF_8);

            GlslConverter converter = new GlslConverter();
            Pipeline.Builder builder = new Pipeline.Builder(format, this.name);

            converter.process(vshSrc, fshSrc);
            UBO ubo = converter.createUBO();
            this.setupUniformSuppliers(ubo);
            this.setDoUniformsUpdate();

            builder.setUniforms(Collections.singletonList(ubo), converter.getSamplerList());
            builder.compileShaders(this.name, converter.getVshConverted(), converter.getFshConverted());

            this.pipeline = builder.createGraphicsPipeline();
            this.doUniformUpdate = true;
        } catch (Exception e) {
            VulkanMod.LOGGER.error("Error on shader {} conversion/compilation", this.name);
            e.printStackTrace();
        }
    }


}