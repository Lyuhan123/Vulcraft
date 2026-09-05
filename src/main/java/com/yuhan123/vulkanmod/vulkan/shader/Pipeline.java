package com.yuhan123.vulkanmod.vulkan.shader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import com.yuhan123.vulkanmod.vulkan.shader.layout.AlignedStruct;
import com.yuhan123.vulkanmod.vulkan.texture.VTextureSelector;
import net.minecraft.client.renderer.vertex.VertexFormat;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.Vulkan;
import com.yuhan123.vulkanmod.vulkan.device.DeviceManager;
import com.yuhan123.vulkanmod.vulkan.framebuffer.RenderPass;
import com.yuhan123.vulkanmod.vulkan.memory.MemoryManager;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.UniformBuffer;
import com.yuhan123.vulkanmod.vulkan.shader.SPIRVUtils.SPIRV;
import com.yuhan123.vulkanmod.vulkan.shader.SPIRVUtils.ShaderKind;
import com.yuhan123.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import com.yuhan123.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import com.yuhan123.vulkanmod.vulkan.shader.descriptor.UBO;
import com.yuhan123.vulkanmod.vulkan.shader.layout.PushConstants;
import com.yuhan123.vulkanmod.vulkan.shader.layout.Uniform;
import com.yuhan123.vulkanmod.vulkan.texture.VulkanImage;
import com.yuhan123.vulkanmod.vulkan.util.MappedBuffer;
import net.minecraft.util.GsonHelper;
import org.apache.commons.lang3.Validate;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public abstract class Pipeline {

    private static final VkDevice DEVICE = Vulkan.getVkDevice();

    /**
     * Vulkan pipeline compilation is expensive and happens lazily, the first
     * time a given state combination (blend/depth/cull/topology/...) is drawn.
     * Without persistence every game restart recompiles every pipeline from
     * scratch, which shows up as stutter for the first seconds of play and
     * whenever a new combination appears mid-game.
     * <p>
     * The cache is stored in the working directory as
     * [16-byte pipelineCacheUUID][raw VkPipelineCache blob]. The UUID guards
     * against feeding one driver's cache to a different one.
     * <p>
     * These MUST stay above PIPELINE_CACHE: static initialisers run in textual
     * order, and createPipelineCache() reads PIPELINE_CACHE_FILE. Declaring it
     * below left the field null during class init, so the cache silently never
     * loaded (the NPE was swallowed by the load's catch).
     */
    private static final File PIPELINE_CACHE_FILE =
            new File(System.getProperty("user.dir", "."), "vulkanmod_pipeline_cache.bin");
    private static final int UUID_LENGTH = 16;
    private static final long MAX_CACHE_SIZE = 64L * 1024 * 1024;

    protected static final long PIPELINE_CACHE = createPipelineCache();
    protected static final List<Pipeline> PIPELINES = new LinkedList<>();

    private static long createPipelineCache() {
        ByteBuffer initialData = loadPipelineCacheData();

        try (MemoryStack stack = stackPush()) {
            VkPipelineCacheCreateInfo cacheCreateInfo = VkPipelineCacheCreateInfo.calloc(stack);
            cacheCreateInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO);

            if (initialData != null) {
                cacheCreateInfo.pInitialData(initialData);
            }

            LongBuffer pPipelineCache = stack.mallocLong(1);

            if (vkCreatePipelineCache(DEVICE, cacheCreateInfo, null, pPipelineCache) == VK_SUCCESS) {
                return pPipelineCache.get(0);
            }

            if (initialData == null) {
                throw new RuntimeException("Failed to create graphics pipeline");
            }

            // A stale/corrupt cache file must never stop the game from starting:
            // retry once with an empty cache.
            VulkanMod.LOGGER.warn("Discarding unusable Vulkan pipeline cache");
            deletePipelineCacheFile();

            cacheCreateInfo.pInitialData(null);

            if (vkCreatePipelineCache(DEVICE, cacheCreateInfo, null, pPipelineCache) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create graphics pipeline");
            }

            return pPipelineCache.get(0);
        } finally {
            if (initialData != null) {
                MemoryUtil.memFree(initialData);
            }
        }
    }

    private static void deletePipelineCacheFile() {
        try {
            Files.deleteIfExists(PIPELINE_CACHE_FILE.toPath());
        } catch (Throwable ignored) {
        }
    }

    private static ByteBuffer loadPipelineCacheData() {
        try {
            if (!PIPELINE_CACHE_FILE.isFile())
                return null;

            byte[] bytes = Files.readAllBytes(PIPELINE_CACHE_FILE.toPath());
            if (bytes.length <= UUID_LENGTH)
                return null;

            if (!isCurrentDriver(bytes))
                return null;

            int size = bytes.length - UUID_LENGTH;
            ByteBuffer data = MemoryUtil.memAlloc(size);
            data.put(bytes, UUID_LENGTH, size);
            data.flip();

            VulkanMod.LOGGER.info("Loaded Vulkan pipeline cache ({} bytes)", size);
            return data;
        } catch (Throwable t) {
            VulkanMod.LOGGER.warn("Failed to load Vulkan pipeline cache", t);
            return null;
        }
    }

    private static boolean isCurrentDriver(byte[] bytes) {
        ByteBuffer uuid = DeviceManager.deviceProperties.pipelineCacheUUID();

        for (int i = 0; i < UUID_LENGTH; ++i) {
            if (bytes[i] != uuid.get(i))
                return false;
        }

        return true;
    }

    private static void savePipelineCacheData() {
        try (MemoryStack stack = stackPush()) {
            // size_t* is mapped to PointerBuffer by LWJGL, not LongBuffer.
            PointerBuffer pSize = stack.mallocPointer(1);

            int err = vkGetPipelineCacheData(DEVICE, PIPELINE_CACHE, pSize, null);
            if (err != VK_SUCCESS)
                return;

            long size = pSize.get(0);
            if (size <= 0 || size > MAX_CACHE_SIZE)
                return;

            ByteBuffer data = stack.malloc((int) size);

            if (vkGetPipelineCacheData(DEVICE, PIPELINE_CACHE, pSize, data) != VK_SUCCESS)
                return;

            byte[] out = new byte[UUID_LENGTH + (int) size];
            ByteBuffer uuid = DeviceManager.deviceProperties.pipelineCacheUUID();
            for (int i = 0; i < UUID_LENGTH; ++i) {
                out[i] = uuid.get(i);
            }
            data.get(out, UUID_LENGTH, (int) size);

            Files.write(PIPELINE_CACHE_FILE.toPath(), out);
        } catch (Throwable t) {
            VulkanMod.LOGGER.warn("Failed to save Vulkan pipeline cache", t);
        }
    }

    public static void destroyPipelineCache() {
        savePipelineCacheData();
        vkDestroyPipelineCache(DEVICE, PIPELINE_CACHE, null);
    }

    public static void recreateDescriptorSets(int frames) {
        PIPELINES.forEach(pipeline -> {
            pipeline.destroyDescriptorSets();
            pipeline.createDescriptorSets(frames);
        });
    }

    public final String name;

    protected long descriptorSetLayout;
    protected long pipelineLayout;

    protected DescriptorSets[] descriptorSets;
    protected List<UBO> buffers;
    protected ManualUBO manualUBO;
    protected List<ImageDescriptor> imageDescriptors;
    protected PushConstants pushConstants;

    public Pipeline(String name) {
        this.name = name;
    }

    protected void createDescriptorSetLayout() {
        try (MemoryStack stack = stackPush()) {
            int bindingsSize = this.buffers.size() + imageDescriptors.size();

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(bindingsSize, stack);

            for (UBO ubo : this.buffers) {
                VkDescriptorSetLayoutBinding uboLayoutBinding = bindings.get(ubo.getBinding());
                uboLayoutBinding.binding(ubo.getBinding());
                uboLayoutBinding.descriptorCount(1);
                uboLayoutBinding.descriptorType(ubo.getType());
                uboLayoutBinding.pImmutableSamplers(null);
                uboLayoutBinding.stageFlags(ubo.getStages());
            }

            for (ImageDescriptor imageDescriptor : this.imageDescriptors) {
                VkDescriptorSetLayoutBinding samplerLayoutBinding = bindings.get(imageDescriptor.getBinding());
                samplerLayoutBinding.binding(imageDescriptor.getBinding());
                samplerLayoutBinding.descriptorCount(1);
                samplerLayoutBinding.descriptorType(imageDescriptor.getType());
                samplerLayoutBinding.pImmutableSamplers(null);
                samplerLayoutBinding.stageFlags(imageDescriptor.getStages());
            }

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack);
            layoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            layoutInfo.pBindings(bindings);

            LongBuffer pDescriptorSetLayout = stack.mallocLong(1);

            if (vkCreateDescriptorSetLayout(DeviceManager.vkDevice, layoutInfo, null, pDescriptorSetLayout) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create descriptor set layout");
            }

            this.descriptorSetLayout = pDescriptorSetLayout.get(0);
        }
    }

    protected void createPipelineLayout() {
        try (MemoryStack stack = stackPush()) {
            // ===> PIPELINE LAYOUT CREATION <===

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack);
            pipelineLayoutInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            pipelineLayoutInfo.pSetLayouts(stack.longs(this.descriptorSetLayout));

            if (this.pushConstants != null) {
                VkPushConstantRange.Buffer pushConstantRange = VkPushConstantRange.calloc(1, stack);
                pushConstantRange.size(this.pushConstants.getSize());
                pushConstantRange.offset(0);
                pushConstantRange.stageFlags(VK_SHADER_STAGE_VERTEX_BIT);

                pipelineLayoutInfo.pPushConstantRanges(pushConstantRange);
            }

            LongBuffer pPipelineLayout = stack.longs(VK_NULL_HANDLE);

            if (vkCreatePipelineLayout(DEVICE, pipelineLayoutInfo, null, pPipelineLayout) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create pipeline layout");
            }

            pipelineLayout = pPipelineLayout.get(0);
        }
    }

    protected void createDescriptorSets(int frames) {
        descriptorSets = new DescriptorSets[frames];
        for (int i = 0; i < frames; ++i) {
            descriptorSets[i] = new DescriptorSets(this);
        }
    }

    public void scheduleCleanUp() {
        MemoryManager.getInstance().addFrameOp(this::cleanUp);
    }

    public abstract void cleanUp();

    void destroyDescriptorSets() {
        for (DescriptorSets descriptorSets : this.descriptorSets) {
            descriptorSets.cleanUp();
        }

        this.descriptorSets = null;
    }

    public ManualUBO getManualUBO() {
        return this.manualUBO;
    }

    public void resetDescriptorPool(int i) {
        if (this.descriptorSets != null)
            this.descriptorSets[i].resetIdx();

    }

    public PushConstants getPushConstants() {
        return this.pushConstants;
    }

    public long getLayout() {
        return pipelineLayout;
    }

    public List<ImageDescriptor> getImageDescriptors() {
        return imageDescriptors;
    }

    public void bindDescriptorSets(VkCommandBuffer commandBuffer, int frame) {
        UniformBuffer uniformBuffer = Renderer.getDrawer().getUniformBuffer();
        this.descriptorSets[frame].bindSets(commandBuffer, uniformBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS);
    }

    public void bindDescriptorSets(VkCommandBuffer commandBuffer, UniformBuffer uniformBuffer, int frame) {
        this.descriptorSets[frame].bindSets(commandBuffer, uniformBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS);
    }

    static long createShaderModule(ByteBuffer spirvCode) {

        try (MemoryStack stack = stackPush()) {

            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack);

            createInfo.sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
            createInfo.pCode(spirvCode);

            LongBuffer pShaderModule = stack.mallocLong(1);

            if (vkCreateShaderModule(DEVICE, createInfo, null, pShaderModule) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create shader module");
            }

            return pShaderModule.get(0);
        }
    }

    protected static class DescriptorSets {
        private final Pipeline pipeline;
        private int poolSize = 10;
        private long descriptorPool;
        private LongBuffer sets;
        private long currentSet;
        private int currentIdx = -1;

        private final long[] boundUBs;
        private final ImageDescriptor.State[] boundTextures;
        private final IntBuffer dynamicOffsets;

        DescriptorSets(Pipeline pipeline) {
            this.pipeline = pipeline;
            this.boundTextures = new ImageDescriptor.State[pipeline.imageDescriptors.size()];
            this.dynamicOffsets = MemoryUtil.memAllocInt(pipeline.buffers.size());
            this.boundUBs = new long[pipeline.buffers.size()];

            Arrays.setAll(boundTextures, i -> new ImageDescriptor.State(0, 0));

            try (MemoryStack stack = stackPush()) {
                this.createDescriptorPool(stack);
                this.createDescriptorSets(stack);
            }
        }

        protected void bindSets(VkCommandBuffer commandBuffer, UniformBuffer uniformBuffer, int bindPoint) {
            try (MemoryStack stack = stackPush()) {

                this.updateUniforms(uniformBuffer);
                this.updateDescriptorSet(stack, uniformBuffer);

                vkCmdBindDescriptorSets(commandBuffer, bindPoint, pipeline.pipelineLayout,
                        0, stack.longs(currentSet), dynamicOffsets);
            }
        }

        private void updateUniforms(UniformBuffer globalUB) {
            int i = 0;
            for (UBO ubo : pipeline.buffers) {
                boolean useOwnUB = ubo.getUniformBuffer() != null;
                UniformBuffer ub = useOwnUB ? ubo.getUniformBuffer() : globalUB;

                int currentOffset = (int) ub.getUsedBytes();
                this.dynamicOffsets.put(i, currentOffset);

                // TODO: non mappable memory

                int alignedSize = UniformBuffer.getAlignedSize(ubo.getSize());
                ub.checkCapacity(alignedSize);

                if (!useOwnUB) {
                    ubo.update(ub.getPointer());
                    ub.updateOffset(alignedSize);
                FrameProfiler.onUniformBytes(alignedSize);
                }

                ++i;
            }
        }

        private boolean needsUpdate(UniformBuffer uniformBuffer) {
            if (currentIdx == -1)
                return true;

            for (int j = 0; j < pipeline.imageDescriptors.size(); ++j) {
                ImageDescriptor imageDescriptor = pipeline.imageDescriptors.get(j);
                VulkanImage image = imageDescriptor.getImage();
                long view = imageDescriptor.getImageView(image);
                long sampler = image.getSampler();

                if (imageDescriptor.isReadOnlyLayout)
                    image.readOnlyLayout();

                if (!this.boundTextures[j].isCurrentState(view, sampler)) {
                    return true;
                }
            }

            for (int j = 0; j < pipeline.buffers.size(); ++j) {
                UBO ubo = pipeline.buffers.get(j);
                UniformBuffer uniformBufferI = ubo.getUniformBuffer();

                if (uniformBufferI == null)
                    uniformBufferI = uniformBuffer;

                if (this.boundUBs[j] != uniformBufferI.getId()) {
                    return true;
                }
            }

            return false;
        }

        private void checkPoolSize(MemoryStack stack) {
            if (this.currentIdx >= this.poolSize) {
                this.poolSize *= 2;

                this.createDescriptorPool(stack);
                this.createDescriptorSets(stack);
                this.currentIdx = 0;
            }
        }

        private void updateDescriptorSet(MemoryStack stack, UniformBuffer uniformBuffer) {

            //Check if update is needed
            if (!needsUpdate(uniformBuffer))
                return;

            this.currentIdx++;

            //Check pool size
            checkPoolSize(stack);

            this.currentSet = this.sets.get(this.currentIdx);

            VkWriteDescriptorSet.Buffer descriptorWrites = VkWriteDescriptorSet.calloc(pipeline.buffers.size() + pipeline.imageDescriptors.size(), stack);

            //TODO maybe ubo update is not needed everytime
            int i = 0;
            for (UBO ubo : pipeline.buffers) {
                UniformBuffer ub = ubo.getUniformBuffer();
                if (ub == null)
                    ub = uniformBuffer;
                boundUBs[i] = ub.getId();

                // Structs come from the stack (pointer bump); only the enclosing
                // Java array was heap garbage, so keep them as locals.
                VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                bufferInfo.buffer(boundUBs[i]);
                bufferInfo.range(ubo.getSize());

                VkWriteDescriptorSet uboDescriptorWrite = descriptorWrites.get(i);
                uboDescriptorWrite.sType$Default();
                uboDescriptorWrite.dstBinding(ubo.getBinding());
                uboDescriptorWrite.dstArrayElement(0);
                uboDescriptorWrite.descriptorType(ubo.getType());
                uboDescriptorWrite.descriptorCount(1);
                uboDescriptorWrite.pBufferInfo(bufferInfo);
                uboDescriptorWrite.dstSet(currentSet);

                ++i;
            }

            for (int j = 0; j < pipeline.imageDescriptors.size(); ++j) {
                ImageDescriptor imageDescriptor = pipeline.imageDescriptors.get(j);
                VulkanImage image = imageDescriptor.getImage();
                long view = imageDescriptor.getImageView(image);
                long sampler = image.getSampler();
                int layout = imageDescriptor.getLayout();

                if (imageDescriptor.isReadOnlyLayout)
                    image.readOnlyLayout();

                VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
                imageInfo.imageLayout(layout);
                imageInfo.imageView(view);

                if (imageDescriptor.useSampler)
                    imageInfo.sampler(sampler);

                VkWriteDescriptorSet samplerDescriptorWrite = descriptorWrites.get(i);
                samplerDescriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                samplerDescriptorWrite.dstBinding(imageDescriptor.getBinding());
                samplerDescriptorWrite.dstArrayElement(0);
                samplerDescriptorWrite.descriptorType(imageDescriptor.getType());
                samplerDescriptorWrite.descriptorCount(1);
                samplerDescriptorWrite.pImageInfo(imageInfo);
                samplerDescriptorWrite.dstSet(currentSet);

                this.boundTextures[j].set(view, sampler);
                ++i;
            }

            vkUpdateDescriptorSets(DEVICE, descriptorWrites, null);
            FrameProfiler.onDescriptorUpdate();
        }

        private void createDescriptorSets(MemoryStack stack) {
            LongBuffer layout = stack.mallocLong(this.poolSize);
//            layout.put(0, descriptorSetLayout);

            for (int i = 0; i < this.poolSize; ++i) {
                layout.put(i, pipeline.descriptorSetLayout);
            }

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack);
            allocInfo.sType$Default();
            allocInfo.descriptorPool(descriptorPool);
            allocInfo.pSetLayouts(layout);

            // Free the previous handle array: the pool it belonged to is already
            // scheduled for destruction, so its handles are no longer needed.
            if (this.sets != null) {
                MemoryUtil.memFree(this.sets);
            }

            this.sets = MemoryUtil.memAllocLong(this.poolSize);

            int result = vkAllocateDescriptorSets(DEVICE, allocInfo, this.sets);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate descriptor sets. Result:" + result);
            }
        }

        private void createDescriptorPool(MemoryStack stack) {
            int size = pipeline.buffers.size() + pipeline.imageDescriptors.size();

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(size, stack);

            int i;
            for (i = 0; i < pipeline.buffers.size(); ++i) {
                VkDescriptorPoolSize uniformBufferPoolSize = poolSizes.get(i);
//                uniformBufferPoolSize.type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
                uniformBufferPoolSize.type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC);
                uniformBufferPoolSize.descriptorCount(this.poolSize);
            }

            for (; i < pipeline.buffers.size() + pipeline.imageDescriptors.size(); ++i) {
                VkDescriptorPoolSize textureSamplerPoolSize = poolSizes.get(i);
                textureSamplerPoolSize.type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                textureSamplerPoolSize.descriptorCount(this.poolSize);
            }

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
            poolInfo.pPoolSizes(poolSizes);
            poolInfo.maxSets(this.poolSize);

            LongBuffer pDescriptorPool = stack.mallocLong(1);

            if (vkCreateDescriptorPool(DEVICE, poolInfo, null, pDescriptorPool) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create descriptor pool");
            }

            if (this.descriptorPool != VK_NULL_HANDLE) {
                final long oldDescriptorPool = this.descriptorPool;
                MemoryManager.getInstance().addFrameOp(() -> {
                    vkDestroyDescriptorPool(DEVICE, oldDescriptorPool, null);
                });
            }

            this.descriptorPool = pDescriptorPool.get(0);
        }

        public void resetIdx() {
            this.currentIdx = -1;
        }

        private void cleanUp() {
            vkResetDescriptorPool(DEVICE, descriptorPool, 0);
            vkDestroyDescriptorPool(DEVICE, descriptorPool, null);

            MemoryUtil.memFree(this.dynamicOffsets);

            if (this.sets != null) {
                MemoryUtil.memFree(this.sets);
                this.sets = null;
            }
        }

    }

    public static class Builder {
        final VertexFormat vertexFormat;
        final String shaderPath;
        List<UBO> UBOs;
        ManualUBO manualUBO;
        PushConstants pushConstants;
        List<ImageDescriptor> imageDescriptors;
        int nextBinding;

        SPIRV vertShaderSPIRV;
        SPIRV fragShaderSPIRV;

        /**
         * Optional discard-free fragment variant. Vanilla 1.12.2 emulates alpha
         * test in fixed function, which does not affect early-Z; our {@code discard}
         * emulation forces late fragment tests per the Vulkan spec, disabling
         * early-Z overdraw rejection for every draw using the shader. Pipelines
         * compiled from a fragment shader containing {@code discard} get this
         * variant and GraphicsPipeline picks it whenever the tracked GL alpha-test
         * state is disabled (SOLID/TRANSLUCENT terrain layers, most GUI).
         */
        SPIRV fragNoDiscardSPIRV;

        /**
         * Variant with {@code layout(early_fragment_tests) in;} injected (discard
         * retained). Used for the cutout colour pass of the depth-prepass flow:
         * depth was already written by the prepass, the colour draw runs
         * EQUAL + depthWrite=off, so early tests reject overdrawn fragments
         * before the shader runs and discard cannot corrupt depth.
         */
        SPIRV fragEarlyTestSPIRV;

        /**
         * Minimal depth-only fragment shader for the depth-prepass pass: one
         * texture fetch + the alpha discard, no lightmap/fog/colour math. Only
         * compiled when the sampler binding and texcoord input can be parsed
         * out of the fragment source.
         */
        SPIRV fragDepthOnlySPIRV;

        RenderPass renderPass;

        Function<Uniform.Info, Supplier<MappedBuffer>> uniformSupplierGetter;

        public Builder(VertexFormat vertexFormat, String path) {
            this.vertexFormat = vertexFormat;
            this.shaderPath = path;
        }

        public Builder(VertexFormat vertexFormat) {
            this(vertexFormat, null);
        }

        public Builder() {
            this(null, null);
        }

        public GraphicsPipeline createGraphicsPipeline() {
            Validate.isTrue(this.imageDescriptors != null && this.UBOs != null
                            && this.vertShaderSPIRV != null && this.fragShaderSPIRV != null,
                    "Cannot create Pipeline: resources missing");

            if (this.manualUBO != null)
                this.UBOs.add(this.manualUBO);

            return new GraphicsPipeline(this);
        }

        public void setUniforms(List<UBO> UBOs, List<ImageDescriptor> imageDescriptors) {
            this.UBOs = UBOs;
            this.imageDescriptors = imageDescriptors;
        }

        public void setSPIRVs(SPIRV vertShaderSPIRV, SPIRV fragShaderSPIRV) {
            this.vertShaderSPIRV = vertShaderSPIRV;
            this.fragShaderSPIRV = fragShaderSPIRV;
        }

        public void compileShaders(String name, String vsh, String fsh) {
            this.vertShaderSPIRV = SPIRVUtils.compileShader(String.format("%s.vsh", name), vsh, ShaderKind.VERTEX_SHADER);
            this.fragShaderSPIRV = SPIRVUtils.compileShader(String.format("%s.fsh", name), fsh, ShaderKind.FRAGMENT_SHADER);
            this.compileFragNoDiscardVariant(name, fsh);
            this.compileFragEarlyTestVariant(name, fsh);
            this.compileFragDepthOnlyVariant(name, fsh);
        }

        /**
         * Compiles a discard-free clone of the fragment shader when the source
         * actually discards (commented-out occurrences are ignored). See the
         * {@link #fragNoDiscardSPIRV} field comment for why this variant exists.
         */
        public void compileFragNoDiscardVariant(String name, String fsh) {
            if (fsh == null) {
                return;
            }

            String code = fsh.replaceAll("(?m)^\\s*//.*$", "");
            if (!code.contains("discard")) {
                return;
            }

            this.fragNoDiscardSPIRV = SPIRVUtils.compileShader(
                    String.format("%s.fsh_nodiscard", name),
                    fsh.replace("discard;", ""),
                    ShaderKind.FRAGMENT_SHADER);
        }

        /**
         * Compiles an early_fragment_tests clone of the fragment shader when the
         * source actually discards. See the {@link #fragEarlyTestSPIRV} field
         * comment for how this variant is used by the depth-prepass flow.
         */
        public void compileFragEarlyTestVariant(String name, String fsh) {
            if (fsh == null || this.fragNoDiscardSPIRV == null) {
                return;
            }

            String injected = fsh.replaceFirst("(?m)^(#version.*)$",
                    "$1\nlayout(early_fragment_tests) in;");

            this.fragEarlyTestSPIRV = SPIRVUtils.compileShader(
                    String.format("%s.fsh_earlytest", name),
                    injected,
                    ShaderKind.FRAGMENT_SHADER);
        }

        /**
         * Compiles a minimal depth-only fragment shader for the depth-prepass
         * pass: one texture fetch + the alpha discard, no lightmap/fog/colour
         * math. Generated by parsing the sampler binding and the vec2 texcoord
         * input location out of the fragment source; when a vec4 vertex-colour
         * input exists it is multiplied in so the discard stays at least as
         * strict as the full shader's (the prepass must never write depth for a
         * texel the colour pass would discard, or invisible occluders appear).
         */
        public void compileFragDepthOnlyVariant(String name, String fsh) {
            if (fsh == null || this.fragNoDiscardSPIRV == null) {
                VulkanMod.LOGGER.info("[VKPROF] depth-only variant skipped for '{}': no fsh or no noDiscard", name);
                return;
            }

            java.util.regex.Matcher sampler = java.util.regex.Pattern
                    .compile("layout\\s*\\(\\s*binding\\s*=\\s*(\\d+)\\s*\\)\\s*uniform\\s+sampler2D\\s+(\\w+)\\s*;")
                    .matcher(fsh);
            if (!sampler.find()) {
                VulkanMod.LOGGER.info("[VKPROF] depth-only variant skipped for '{}': no sampler2D", name);
                return;
            }

            java.util.regex.Matcher uv = java.util.regex.Pattern
                    .compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*in\\s+vec2\\s+(\\w+)\\s*;")
                    .matcher(fsh);
            if (!uv.find()) {
                VulkanMod.LOGGER.info("[VKPROF] depth-only variant skipped for '{}': no vec2 input", name);
                return;
            }

            java.util.regex.Matcher vcolor = java.util.regex.Pattern
                    .compile("layout\\s*\\(\\s*location\\s*=\\s*0\\s*\\)\\s*in\\s+vec4\\s+(\\w+)\\s*;")
                    .matcher(fsh);
            boolean hasVColor = vcolor.find();
            String vColorName = hasVColor ? vcolor.group(1) : null;

            String alphaExpr = "texture(" + sampler.group(2) + ", " + uv.group(2) + ").a";
            if (hasVColor) {
                alphaExpr += " * " + vColorName + ".a";
            }

            String src = "#version 450\n"
                    + "layout(binding = " + sampler.group(1) + ") uniform sampler2D " + sampler.group(2) + ";\n"
                    + "layout(location = " + uv.group(1) + ") in vec2 " + uv.group(2) + ";\n"
                    + (hasVColor ? "layout(location = 0) in vec4 " + vColorName + ";\n" : "")
                    + "void main() {\n"
                    + "    if (" + alphaExpr + " < 0.1) { discard; }\n"
                    + "}\n";

            this.fragDepthOnlySPIRV = SPIRVUtils.compileShader(
                    String.format("%s.fsh_depthonly", name),
                    src,
                    ShaderKind.FRAGMENT_SHADER);
        }

        public void setVertShaderSPIRV(SPIRV vertShaderSPIRV) {
            this.vertShaderSPIRV = vertShaderSPIRV;
        }

        public void setFragShaderSPIRV(SPIRV fragShaderSPIRV) {
            this.fragShaderSPIRV = fragShaderSPIRV;
        }

        public void parseBindings(JsonObject jsonObject) {
            this.UBOs = new ArrayList<>();
            this.imageDescriptors = new ArrayList<>();

            JsonArray jsonUbos = GsonHelper.getAsJsonArray(jsonObject, "UBOs", null);
            JsonArray jsonManualUbos = GsonHelper.getAsJsonArray(jsonObject, "ManualUBOs", null);
            JsonArray jsonSamplers = GsonHelper.getAsJsonArray(jsonObject, "samplers", null);
            JsonArray jsonPushConstants = GsonHelper.getAsJsonArray(jsonObject, "PushConstants", null);

            if (jsonUbos != null) {
                for (JsonElement jsonelement : jsonUbos) {
                    this.parseUboNode(jsonelement);
                }
            }

            if (jsonManualUbos != null) {
                this.parseManualUboNode(jsonManualUbos.get(0));
            }

            if (jsonSamplers != null) {
                for (JsonElement jsonelement : jsonSamplers) {
                    this.parseSamplerNode(jsonelement);
                }
            }

            if (jsonPushConstants != null) {
                this.parsePushConstantNode(jsonPushConstants);
            }
        }

        public void setUniformSupplierGetter(Function<Uniform.Info, Supplier<MappedBuffer>> uniformSupplierGetter) {
            this.uniformSupplierGetter = uniformSupplierGetter;
        }

        private void parseUboNode(JsonElement jsonelement) {
            JsonObject jsonobject = GsonHelper.convertToJsonObject(jsonelement, "UBO");
            int binding = GsonHelper.getAsInt(jsonobject, "binding");
            int type = getStageFromString(GsonHelper.getAsString(jsonobject, "type"));
            JsonArray fields = GsonHelper.getAsJsonArray(jsonobject, "fields");

            AlignedStruct.Builder builder = new AlignedStruct.Builder();

            for (JsonElement jsonelement2 : fields) {
                JsonObject jsonobject2 = GsonHelper.convertToJsonObject(jsonelement2, "uniform");
                //need to store some infos
                String name = GsonHelper.getAsString(jsonobject2, "name");
                String type2 = GsonHelper.getAsString(jsonobject2, "type");
                int count = GsonHelper.getAsInt(jsonobject2, "count");

                Uniform.Info uniformInfo = Uniform.createUniformInfo(type2, name, count);
                uniformInfo.setupSupplier();

                if (!uniformInfo.hasSupplier()) {
                    if (this.uniformSupplierGetter != null) {
                        var uniformSupplier = this.uniformSupplierGetter.apply(uniformInfo);

                        if (uniformSupplier == null) {
                            throw new IllegalStateException("No uniform supplier found for uniform: (%s:%s)".formatted(type2, name));
                        }

                        uniformInfo.setBufferSupplier(uniformSupplier);
                    }
                    else {
                        throw new IllegalStateException("No uniform supplier found for uniform: (%s:%s)".formatted(type2, name));
                    }
                }

                builder.addUniformInfo(uniformInfo);
            }

            UBO ubo = builder.buildUBO(binding, type);

            if (binding >= this.nextBinding)
                this.nextBinding = binding + 1;

            this.UBOs.add(ubo);
        }

        private void parseManualUboNode(JsonElement jsonelement) {
            JsonObject jsonobject = GsonHelper.convertToJsonObject(jsonelement, "ManualUBO");
            int binding = GsonHelper.getAsInt(jsonobject, "binding");
            int stage = getStageFromString(GsonHelper.getAsString(jsonobject, "type"));
            int size = GsonHelper.getAsInt(jsonobject, "size");

            if (binding >= this.nextBinding)
                this.nextBinding = binding + 1;

            this.manualUBO = new ManualUBO(binding, stage, size);
        }

        private void parseSamplerNode(JsonElement jsonelement) {
            JsonObject jsonobject = GsonHelper.convertToJsonObject(jsonelement, "Sampler");
            String name = GsonHelper.getAsString(jsonobject, "name");

            int imageIdx = VTextureSelector.getTextureIdx(name);
            this.imageDescriptors.add(new ImageDescriptor(this.nextBinding, "sampler2D", name, imageIdx));
            this.nextBinding++;
        }

        private void parsePushConstantNode(JsonArray jsonArray) {
            AlignedStruct.Builder builder = new AlignedStruct.Builder();

            for (JsonElement jsonelement : jsonArray) {
                JsonObject jsonobject2 = GsonHelper.convertToJsonObject(jsonelement, "PushConstants");

                String name = GsonHelper.getAsString(jsonobject2, "name");
                String type2 = GsonHelper.getAsString(jsonobject2, "type");
                int count = GsonHelper.getAsInt(jsonobject2, "count");

                Uniform.Info uniformInfo = Uniform.createUniformInfo(type2, name, count);
                uniformInfo.setupSupplier();

                builder.addUniformInfo(uniformInfo);
            }

            this.pushConstants = builder.buildPushConstant();
        }

        public static int getStageFromString(String s) {
            return switch (s) {
                case "vertex" -> VK_SHADER_STAGE_VERTEX_BIT;
                case "fragment" -> VK_SHADER_STAGE_FRAGMENT_BIT;
                case "all" -> VK_SHADER_STAGE_ALL_GRAPHICS;
                case "compute" -> VK_SHADER_STAGE_COMPUTE_BIT;

                default -> throw new RuntimeException("cannot identify type..");
            };
        }
    }
}
