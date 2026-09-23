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
import com.yuhan123.vulkanmod.vulkan.memory.buffer.Buffer;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.UniformBuffer;
import com.yuhan123.vulkanmod.vulkan.shader.SPIRVUtils.SPIRV;
import com.yuhan123.vulkanmod.vulkan.shader.SPIRVUtils.ShaderKind;
import com.yuhan123.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import com.yuhan123.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import com.yuhan123.vulkanmod.vulkan.shader.descriptor.StaticBuffer;
import com.yuhan123.vulkanmod.vulkan.shader.descriptor.UBO;
import com.yuhan123.vulkanmod.vulkan.shader.layout.PushConstants;
import com.yuhan123.vulkanmod.vulkan.shader.layout.Uniform;
import com.yuhan123.vulkanmod.vulkan.texture.VulkanImage;
import com.yuhan123.vulkanmod.vulkan.util.MappedBuffer;
import com.yuhan123.vulkanmod.vulkan.util.VUtil;
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
        // The blob goes on the HEAP, not on the thread-local MemoryStack.
        //
        // It can be as large as MAX_CACHE_SIZE (64 MiB) while a MemoryStack
        // defaults to 64 KiB, so `stack.malloc(size)` threw
        // "OutOfMemoryError: Out of stack space" on every shutdown. The catch
        // below swallowed it into a single warning line, so the failure was
        // invisible: the cache file was never rewritten, every session loaded a
        // stale blob (or none) and recompiled its pipelines from scratch - which
        // is exactly the first-seconds stutter the persistence exists to remove.
        //
        // Only the size_t* out-parameter belongs on the stack.
        ByteBuffer data = null;

        try (MemoryStack stack = stackPush()) {
            // size_t* is mapped to PointerBuffer by LWJGL, not LongBuffer.
            PointerBuffer pSize = stack.mallocPointer(1);

            int err = vkGetPipelineCacheData(DEVICE, PIPELINE_CACHE, pSize, null);
            if (err != VK_SUCCESS)
                return;

            long size = pSize.get(0);
            if (size <= 0 || size > MAX_CACHE_SIZE)
                return;

            data = MemoryUtil.memAlloc((int) size);

            if (vkGetPipelineCacheData(DEVICE, PIPELINE_CACHE, pSize, data) != VK_SUCCESS)
                return;

            // The second call may report a smaller size than the query did, and
            // only that many bytes are meaningful.
            final int written = (int) Math.min(size, pSize.get(0));

            byte[] out = new byte[UUID_LENGTH + written];
            ByteBuffer uuid = DeviceManager.deviceProperties.pipelineCacheUUID();
            for (int i = 0; i < UUID_LENGTH; ++i) {
                out[i] = uuid.get(i);
            }
            data.get(out, UUID_LENGTH, written);

            Files.write(PIPELINE_CACHE_FILE.toPath(), out);

            VulkanMod.LOGGER.info("Saved Vulkan pipeline cache ({} bytes)", written);
        } catch (Throwable t) {
            VulkanMod.LOGGER.warn("Failed to save Vulkan pipeline cache", t);
        } finally {
            if (data != null) {
                MemoryUtil.memFree(data);
            }
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
    /** Caller-owned buffer bindings (no per-draw upload). See {@link StaticBuffer}. */
    protected List<StaticBuffer> staticBuffers;
    protected PushConstants pushConstants;

    public Pipeline(String name) {
        this.name = name;
    }

    protected void createDescriptorSetLayout() {
        try (MemoryStack stack = stackPush()) {
            final int staticCount = this.staticBuffers == null ? 0 : this.staticBuffers.size();
            int bindingsSize = this.buffers.size() + imageDescriptors.size() + staticCount;

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(bindingsSize, stack);

            // Fill the array with a running index and set the real binding number
            // explicitly. Indexing by binding number (as this used to) only works
            // while the bindings happen to be dense from 0; the per-draw matrix
            // now lives in a push constant, so the first UBO starts at binding 1
            // and a binding-numbered array would overrun.
            int bindingIndex = 0;

            for (UBO ubo : this.buffers) {
                VkDescriptorSetLayoutBinding uboLayoutBinding = bindings.get(bindingIndex++);
                uboLayoutBinding.binding(ubo.getBinding());
                uboLayoutBinding.descriptorCount(1);
                uboLayoutBinding.descriptorType(ubo.getType());
                uboLayoutBinding.pImmutableSamplers(null);
                uboLayoutBinding.stageFlags(ubo.getStages());
            }

            for (ImageDescriptor imageDescriptor : this.imageDescriptors) {
                VkDescriptorSetLayoutBinding samplerLayoutBinding = bindings.get(bindingIndex++);
                samplerLayoutBinding.binding(imageDescriptor.getBinding());
                samplerLayoutBinding.descriptorCount(1);
                samplerLayoutBinding.descriptorType(imageDescriptor.getType());
                samplerLayoutBinding.pImmutableSamplers(null);
                samplerLayoutBinding.stageFlags(imageDescriptor.getStages());
            }

            for (int i = 0; i < staticCount; ++i) {
                StaticBuffer staticBuffer = this.staticBuffers.get(i);
                VkDescriptorSetLayoutBinding binding = bindings.get(bindingIndex++);
                binding.binding(staticBuffer.getBinding());
                binding.descriptorCount(1);
                binding.descriptorType(staticBuffer.getType());
                binding.pImmutableSamplers(null);
                binding.stageFlags(staticBuffer.getStages());
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

    /**
     * Publishes the buffer behind the {@code index}-th {@code StorageBuffers}
     * entry. The Drawer calls this once per frame with that frame's matrix
     * buffer; the descriptor set follows automatically because
     * {@code needsUpdate} compares buffer ids.
     */
    public void setStaticBuffer(int index, Buffer buffer) {
        if (this.staticBuffers != null && index < this.staticBuffers.size()) {
            this.staticBuffers.get(index).setBuffer(buffer);
        }
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

        /**
         * Caller-owned buffer bindings and the buffer id each was last written
         * with. The Drawer swaps the matrix buffer once per frame, so this is
         * what makes the descriptor set get rewritten exactly once per frame.
         */
        private final List<StaticBuffer> staticBuffers;
        private final long[] boundStaticIds;

        /**
         * The descriptor set handle, kept in a preallocated buffer so the bind
         * path does not have to open a MemoryStack frame for a single long.
         */
        private final LongBuffer setHandle;

        /**
         * Per-UBO staging used to decide whether a bind can be skipped.
         *
         * The UBO content is assembled into {@link #uboStaged} first, then
         * compared against {@link #uboLastWritten}. If the bytes are identical
         * AND this pipeline wrote the slice and nothing has written past it since
         * (see {@link #uboLastSliceOffset}), the slice already holds the right
         * values and neither the write nor the dynamic-offset bump has to happen.
         * That is what keeps the descriptor set (and its offset) stable across
         * the thousands of chunk draws that share one pipeline, which in turn
         * lets {@link #bindSets} skip the vkCmdBindDescriptorSets entirely.
         */
        private final ByteBuffer[] uboStaged;
        private final ByteBuffer[] uboLastWritten;
        private final int[] uboLastSliceOffset;
        private final long[] uboLastWriteBufferId;
        private final int[] uboLastWriteSeq;

        /**
         * {@link Renderer#getBindingEpoch()} at the last vkCmdBindDescriptorSets
         * issued by this instance. Descriptor bindings are command-buffer state,
         * so the cache is only valid inside the same recording session.
         */
        private int boundEpoch = -1;

        DescriptorSets(Pipeline pipeline) {
            this.pipeline = pipeline;
            this.boundTextures = new ImageDescriptor.State[pipeline.imageDescriptors.size()];
            this.dynamicOffsets = MemoryUtil.memAllocInt(pipeline.buffers.size());
            this.boundUBs = new long[pipeline.buffers.size()];
            this.setHandle = MemoryUtil.memAllocLong(1);

            this.staticBuffers = pipeline.staticBuffers == null ? java.util.List.of() : pipeline.staticBuffers;
            this.boundStaticIds = new long[this.staticBuffers.size()];

            int bufferCount = pipeline.buffers.size();
            this.uboStaged = new ByteBuffer[bufferCount];
            this.uboLastWritten = new ByteBuffer[bufferCount];
            this.uboLastSliceOffset = new int[bufferCount];
            this.uboLastWriteBufferId = new long[bufferCount];
            this.uboLastWriteSeq = new int[bufferCount];

            for (int i = 0; i < bufferCount; ++i) {
                int size = Math.max(16, pipeline.buffers.get(i).getSize());
                // memCalloc so the alignment padding inside the struct stays a
                // deterministic zero: the comparison below covers the whole
                // struct, padding included.
                this.uboStaged[i] = MemoryUtil.memCalloc(size);
                this.uboLastWritten[i] = MemoryUtil.memCalloc(size);
                this.uboLastSliceOffset[i] = -1;
            }

            Arrays.setAll(boundTextures, i -> new ImageDescriptor.State(0, 0));

            try (MemoryStack stack = stackPush()) {
                this.createDescriptorPool(stack);
                this.createDescriptorSets(stack);
            }
        }

        protected void bindSets(VkCommandBuffer commandBuffer, UniformBuffer uniformBuffer, int bindPoint) {
            // Evaluated before updateUniforms: it inspects the currently bound
            // set/UBO ids, not the slices updateUniforms is about to write.
            final boolean descriptorChanged = needsUpdate(uniformBuffer);
            final boolean uniformsChanged = updateUniforms(uniformBuffer);

            // TEMPORARY: descriptor-skip probe - see TextureProbe.
            com.yuhan123.vulkanmod.vulkan.texture.TextureProbe.onDescriptorDecision(
                    pipeline, descriptorChanged, uniformsChanged);

            // Nothing about this bind would differ from the one already issued in
            // this command buffer: same set, same dynamic offsets, byte-identical
            // UBO contents. Re-binding is pure overhead, and for terrain it is the
            // single largest per-chunk CPU cost.
            if (!descriptorChanged && !uniformsChanged && this.boundEpoch == Renderer.getBindingEpoch()) {
                FrameProfiler.onDescriptorBindSkipped();
                return;
            }

            final boolean timed = FrameProfiler.DETAILED_TIMING;

            if (descriptorChanged) {
                final long __u = timed ? FrameProfiler.start() : 0L;
                try (MemoryStack stack = stackPush()) {
                    this.updateDescriptorSet(stack, uniformBuffer);
                }
                if (timed) {
                    FrameProfiler.addFullSegment(__u, FrameProfiler.FULL_DESC_UPD);
                }
            }

            final long __d = timed ? FrameProfiler.start() : 0L;
            vkCmdBindDescriptorSets(commandBuffer, bindPoint, pipeline.pipelineLayout,
                    0, this.setHandle, dynamicOffsets);
            FrameProfiler.addCmd(FrameProfiler.CMD_BIND_DESCRIPTOR, __d);
            if (timed) {
                FrameProfiler.addFullSegment(__d, FrameProfiler.FULL_DESC_BIND);
            }
            this.boundEpoch = Renderer.getBindingEpoch();
            FrameProfiler.onDescriptorBind();
        }

        /**
         * Writes each UBO slice this pipeline needs and reports whether anything
         * actually had to change. Slices whose content is unchanged and still
         * intact are left alone and keep their previous offset.
         */
        private boolean updateUniforms(UniformBuffer globalUB) {
            boolean changed = false;
            int i = 0;
            for (UBO ubo : pipeline.buffers) {
                boolean useOwnUB = ubo.getUniformBuffer() != null;
                UniformBuffer ub = useOwnUB ? ubo.getUniformBuffer() : globalUB;

                int alignedSize = UniformBuffer.getAlignedSize(ubo.getSize());
                ub.checkCapacity(alignedSize);

                if (useOwnUB) {
                    // A dedicated buffer belongs to this UBO alone, so its content
                    // is always rewritten in place at the current offset.
                    this.dynamicOffsets.put(i, (int) ub.getUsedBytes());
                    ubo.update(ub.getPointer());
                    ub.updateOffset(alignedSize);
                    FrameProfiler.onUniformBytes(alignedSize);
                    changed = true;
                    ++i;
                    continue;
                }

                ByteBuffer staged = this.uboStaged[i];
                staged.clear();
                ubo.update(MemoryUtil.memAddress0(staged));

                final int offset = (int) ub.getUsedBytes();
                final int structSize = ubo.getSize();
                final int lastOffset = this.uboLastSliceOffset[i];

                // Reusable only if the slice is still exactly the one this
                // pipeline wrote and nothing has written into the buffer since.
                // Two independent guards, because a stale uniform would be a
                // silent visual bug:
                //  - the offset must sit one slice past ours (usedBytes is only
                //    ever rewound by the per-frame reset, which invalidates the
                //    recorded offset),
                //  - and the buffer's write sequence must be the one we left
                //    behind, which rules out another pipeline having written the
                //    same offset range in the meantime.
                boolean reusable = lastOffset >= 0
                        && offset == lastOffset + alignedSize
                        && this.uboLastWriteSeq[i] == ub.getWriteSeq()
                        && this.uboLastWriteBufferId[i] == ub.getId()
                        && contentEquals(staged, this.uboLastWritten[i], structSize);

                if (reusable) {
                    this.dynamicOffsets.put(i, lastOffset);
                } else {
                    MemoryUtil.memCopy(MemoryUtil.memAddress0(staged), ub.getPointer(), structSize);
                    MemoryUtil.memCopy(MemoryUtil.memAddress0(staged),
                            MemoryUtil.memAddress0(this.uboLastWritten[i]), structSize);

                    // TEMPORARY: dump the first float4 the shader will read as
                    // ColorModulator - see TextureProbe.
                    com.yuhan123.vulkanmod.vulkan.texture.TextureProbe.onUboStaged(pipeline, i, staged, structSize);

                    this.dynamicOffsets.put(i, offset);
                    ub.updateOffset(alignedSize);

                    this.uboLastSliceOffset[i] = offset;
                    this.uboLastWriteBufferId[i] = ub.getId();
                    this.uboLastWriteSeq[i] = ub.getWriteSeq();

                    FrameProfiler.onUniformBytes(alignedSize);
                    changed = true;
                }

                ++i;
            }

            return changed;
        }

        /**
         * Whole-word comparison of two native structs.
         *
         * This runs once per UBO per draw to decide whether the previously staged
         * slice is still usable, so it sits on the hot path. A byte-at-a-time
         * loop through ByteBuffer.get() pays a bounds check per byte; comparing
         * 8 bytes at a time through Unsafe cuts that by roughly 8x. Both buffers
         * are memCalloc'd at the same size, so no read goes past the struct.
         */
        private static boolean contentEquals(ByteBuffer a, ByteBuffer b, int size) {
            final long pa = MemoryUtil.memAddress0(a);
            final long pb = MemoryUtil.memAddress0(b);

            int off = 0;
            for (; off + 8 <= size; off += 8) {
                if (VUtil.UNSAFE.getLong(pa + off) != VUtil.UNSAFE.getLong(pb + off)) {
                    return false;
                }
            }
            for (; off < size; ++off) {
                if (VUtil.UNSAFE.getByte(pa + off) != VUtil.UNSAFE.getByte(pb + off)) {
                    return false;
                }
            }
            return true;
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

            // A static binding only has to be rewritten when the buffer object
            // behind it changes, which for the matrix array is once per frame.
            for (int j = 0; j < this.staticBuffers.size(); ++j) {
                Buffer staticBuffer = this.staticBuffers.get(j).getBuffer();

                if (staticBuffer == null || this.boundStaticIds[j] != staticBuffer.getId()) {
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
            this.setHandle.put(0, this.currentSet);

            VkWriteDescriptorSet.Buffer descriptorWrites = VkWriteDescriptorSet.calloc(
                    pipeline.buffers.size() + pipeline.imageDescriptors.size() + this.staticBuffers.size(), stack);

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

            // Caller-owned buffers: the whole buffer, no dynamic offset. Their
            // contents are written by the owner, not by updateUniforms.
            for (int j = 0; j < this.staticBuffers.size(); ++j) {
                StaticBuffer staticBuffer = this.staticBuffers.get(j);
                Buffer buffer = staticBuffer.getBuffer();

                if (buffer == null) {
                    // The owner has not published a buffer yet. The draw that
                    // would read it cannot be issued either, so skip the write;
                    // needsUpdate() keeps reporting true until one is set.
                    continue;
                }

                VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                bufferInfo.buffer(buffer.getId());
                bufferInfo.offset(0L);
                bufferInfo.range(Math.min(staticBuffer.getSize(), buffer.getBufferSize()));

                VkWriteDescriptorSet staticWrite = descriptorWrites.get(i);
                staticWrite.sType$Default();
                staticWrite.dstBinding(staticBuffer.getBinding());
                staticWrite.dstArrayElement(0);
                staticWrite.descriptorType(staticBuffer.getType());
                staticWrite.descriptorCount(1);
                staticWrite.pBufferInfo(bufferInfo);
                staticWrite.dstSet(currentSet);

                this.boundStaticIds[j] = buffer.getId();
                ++i;
            }

            // Submit only the entries that were actually filled. A
            // VkWriteDescriptorSet has no "empty" encoding: the array comes from
            // calloc, and a zeroed entry (sType = 0, descriptorCount = 0,
            // pBufferInfo = null) is invalid usage. The static-buffer loop can
            // `continue` before the owner has published its buffer, so the tail
            // is reachable - and the Intel driver faults on it rather than
            // rejecting it.
            descriptorWrites.limit(i);
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
            int size = pipeline.buffers.size() + pipeline.imageDescriptors.size() + this.staticBuffers.size();

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

            for (int j = 0; j < this.staticBuffers.size(); ++j) {
                VkDescriptorPoolSize staticPoolSize = poolSizes.get(i + j);
                staticPoolSize.type(this.staticBuffers.get(j).getType());
                staticPoolSize.descriptorCount(this.poolSize);
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
            this.boundEpoch = -1;

            // The frame's uniform buffer was rewound, so a slice that held this
            // pipeline's values may have been handed to somebody else since.
            // Force the next bind to write and re-issue.
            Arrays.fill(this.uboLastSliceOffset, -1);
        }

        private void cleanUp() {
            vkResetDescriptorPool(DEVICE, descriptorPool, 0);
            vkDestroyDescriptorPool(DEVICE, descriptorPool, null);

            MemoryUtil.memFree(this.dynamicOffsets);
            MemoryUtil.memFree(this.setHandle);

            for (int i = 0; i < this.uboStaged.length; ++i) {
                MemoryUtil.memFree(this.uboStaged[i]);
                MemoryUtil.memFree(this.uboLastWritten[i]);
            }

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
        List<StaticBuffer> staticBuffers;
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
            this.staticBuffers = new ArrayList<>();

            JsonArray jsonUbos = GsonHelper.getAsJsonArray(jsonObject, "UBOs", null);
            JsonArray jsonManualUbos = GsonHelper.getAsJsonArray(jsonObject, "ManualUBOs", null);
            JsonArray jsonSamplers = GsonHelper.getAsJsonArray(jsonObject, "samplers", null);
            JsonArray jsonPushConstants = GsonHelper.getAsJsonArray(jsonObject, "PushConstants", null);
            JsonArray jsonStorageBuffers = GsonHelper.getAsJsonArray(jsonObject, "StorageBuffers", null);

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

            if (jsonStorageBuffers != null) {
                for (JsonElement jsonelement : jsonStorageBuffers) {
                    this.parseStorageBufferNode(jsonelement);
                }
            }

            if (jsonPushConstants != null) {
                this.parsePushConstantNode(jsonPushConstants);
            }
        }

        /**
         * {@code {"binding": N, "size": bytes, "type": "vertex"}}.
         *
         * The buffer itself is supplied at runtime by the owner (the Drawer, for
         * the per-instance matrix array); the JSON only declares where it is
         * bound and how much of it the shader may index.
         */
        private void parseStorageBufferNode(JsonElement jsonelement) {
            JsonObject jsonobject = GsonHelper.convertToJsonObject(jsonelement, "StorageBuffer");
            int binding = GsonHelper.getAsInt(jsonobject, "binding");
            int size = GsonHelper.getAsInt(jsonobject, "size");
            int stages = jsonobject.has("type")
                    ? getStageFromString(GsonHelper.getAsString(jsonobject, "type"))
                    : VK_SHADER_STAGE_VERTEX_BIT;

            if (binding >= this.nextBinding)
                this.nextBinding = binding + 1;

            this.staticBuffers.add(new StaticBuffer(binding, stages, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, size));
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
