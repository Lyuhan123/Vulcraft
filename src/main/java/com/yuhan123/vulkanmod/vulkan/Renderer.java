package com.yuhan123.vulkanmod.vulkan;

import com.yuhan123.vulkanmod.render.PipelineManager;
import com.yuhan123.vulkanmod.render.chunk.buffer.UploadManager;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.client.Minecraft;
//import com.yuhan123.vulkanmod.Initializer;
import com.yuhan123.vulkanmod.gl.VkGlFramebuffer;
//import com.yuhan123.vulkanmod.mixin.window.WindowAccessor;
//import com.yuhan123.vulkanmod.render.chunk.WorldRenderer;
//import com.yuhan123.vulkanmod.render.chunk.buffer.UploadManager;
//import com.yuhan123.vulkanmod.render.profiling.Profiler;
import com.yuhan123.vulkanmod.render.texture.ImageUploadHelper;
import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import com.yuhan123.vulkanmod.vulkan.device.DeviceManager;
import com.yuhan123.vulkanmod.vulkan.framebuffer.Framebuffer;
import com.yuhan123.vulkanmod.vulkan.framebuffer.RenderPass;
import com.yuhan123.vulkanmod.vulkan.framebuffer.SwapChain;
import com.yuhan123.vulkanmod.vulkan.memory.MemoryManager;
import com.yuhan123.vulkanmod.vulkan.pass.DefaultMainPass;
import com.yuhan123.vulkanmod.vulkan.pass.MainPass;
import com.yuhan123.vulkanmod.vulkan.shader.GraphicsPipeline;
import com.yuhan123.vulkanmod.vulkan.shader.Pipeline;
import com.yuhan123.vulkanmod.vulkan.shader.PipelineState;
import com.yuhan123.vulkanmod.vulkan.shader.Uniforms;
import com.yuhan123.vulkanmod.vulkan.shader.layout.PushConstants;
import com.yuhan123.vulkanmod.vulkan.texture.VTextureSelector;
import com.yuhan123.vulkanmod.vulkan.util.VUtil;
import com.yuhan123.vulkanmod.vulkan.util.VkResult;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.yuhan123.vulkanmod.vulkan.Vulkan.*;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class Renderer {
    private static Renderer INSTANCE;

    private static VkDevice device;

    private static boolean swapChainUpdate = false;
    public static boolean skipRendering = false;

    public static void initRenderer() {
        INSTANCE = new Renderer();
        INSTANCE.init();
    }

    public static Renderer getInstance() {
        return INSTANCE;
    }

    public static Drawer getDrawer() {
        return INSTANCE.drawer;
    }

    public static int getCurrentFrame() {
        return currentFrame;
    }

    public static int getCurrentImage() {
        return imageIndex;
    }

    private final Set<Pipeline> usedPipelines = new ObjectOpenHashSet<>();
    private Pipeline boundPipeline;
    private long boundPipelineHandle;

    /**
     * Pipeline whose vkCmdBindPipeline has been requested but not yet issued.
     *
     * The bind is deferred to the next draw (see {@link #flushPipelineBind()}) so
     * that a state change occurring between the request and the draw coalesces.
     * The depth-prepass flow calls apply() - which requests the full-colour
     * handle - and then immediately changes colorMask/depthMask and requests the
     * depth-only handle before drawing. Binding eagerly made every cutout chunk
     * pay for three vkCmdBindPipeline calls where two suffice.
     */
    private GraphicsPipeline pendingPipeline;
    private boolean pipelineBindPending;

    private Drawer drawer;

    private SwapChain swapChain;

    private int framesNum;
    private List<VkCommandBuffer> commandBuffers;
    private ArrayList<Long> imageAvailableSemaphores;
    private ArrayList<Long> renderFinishedSemaphores;
    private ArrayList<Long> inFlightFences;
    // The fence of the frame that last rendered each swapchain image
    // (per-image sync prevents semaphore/fence reuse validation warnings and stalls)
    private long[] imagesInFlight;

    private Framebuffer boundFramebuffer;
    private RenderPass boundRenderPass;

    private static int currentFrame = 0;
    private static int imageIndex;
    private static int lastReset = -1;
    private VkCommandBuffer currentCmdBuffer;
    private boolean recordingCmds = false;

    /**
     * Bumped whenever the recorded command buffer's binding state is no longer
     * the one the draw path cached: a command buffer (re)begins recording, or the
     * per-frame uniform buffer is rewound.
     *
     * Descriptor sets, vertex buffers and index buffers are command-buffer state,
     * so caches that skip redundant binds must be invalidated at exactly these
     * points. Consumers store the epoch they bound at and compare.
     */
    private static int bindingEpoch = 0;

    /** Scratch for the per-draw push constant block, reused across draws. */
    private ByteBuffer pushConstantScratch;

    /**
     * Shadow of the last push-constant payload, plus the identity of the pipeline
     * and command-buffer epoch it was pushed in. Lets pushConstants() skip a
     * re-push of identical bytes (see the method for why that is safe).
     */
    private ByteBuffer pushConstantShadow;
    private int pushConstantShadowSize = -1;
    private Pipeline pushConstantPipeline;
    private int pushConstantEpoch = -1;

    public static int getBindingEpoch() {
        return bindingEpoch;
    }

    private static void invalidateBindingState() {
        bindingEpoch++;
    }

    MainPass mainPass;

    private final List<Runnable> onResizeCallbacks = new ObjectArrayList<>();

    /**
     * GPU timestamp queries around the main render pass (one pool per frame
     * slot; the slot's fence guarantees the results are complete by the time
     * the slot is re-acquired). Feeds FrameProfiler's gpuPass metric so real
     * GPU pass time shows up in the [VKPROF] report without RenderDoc.
     */
    /**
     * GPU timestamp queries around the render passes. Bumped from 2 to 24 so the
     * Experiment-B breakdown can record a timestamp at every render-pass boundary
     * (frame start + one per pass switch + frame end). Each frame slot keeps its
     * own count/labels so the deferred read (3 frames later, after the fence)
     * stays coherent under triple-buffering.
     */
    private static final int GPU_QUERY_COUNT = 24;
    private long[] gpuQueryPools;
    private double timestampPeriodNanos;

    // Experiment B — per-render-pass GPU breakdown.
    public static final byte GPU_TS_MAIN = 0; // main swapchain color pass
    public static final byte GPU_TS_OFF = 1;  // offscreen framebuffer pass
    public static final byte GPU_TS_END = 2;  // frame-end sentinel (no segment)
    private int[] gpuTimestampCount;
    private byte[][] gpuTimestampLabels;

    // Per-draw GPU timestamp queries: one vkCmdWriteTimestamp before every
    // vkCmdDrawIndexed/vkCmdDraw so each draw's GPU execution time is recovered
    // from the interval to the next draw. Gated by VULKANMOD_GPUDRAWTIMING (off
    // by default; writing ~700+ timestamps/frame has measurable CPU overhead).
    // Pool sized for interval timing of up to GPU_DRAW_QUERY_COUNT-1 draws.
    private static final boolean GPU_DRAW_TIMING = "1".equals(System.getenv("VULKANMOD_GPUDRAWTIMING"));
    private static final int GPU_DRAW_QUERY_COUNT = 8192;
    private long[] gpuDrawQueryPools;
    private int[] gpuDrawQueryCount;

    public Renderer() {
        device = Vulkan.getVkDevice();
        framesNum = 3;
    }

    public static void setLineWidth(float width) {
        if (INSTANCE.boundFramebuffer == null) {
            return;
        }
        vkCmdSetLineWidth(INSTANCE.currentCmdBuffer, width);
    }

    private void init() {
        MemoryManager.createInstance(Renderer.getFramesNum());
        Vulkan.createStagingBuffers();

        swapChain = new SwapChain();
        imagesInFlight = new long[swapChain.getImages().size()];
        mainPass = DefaultMainPass.create();

        drawer = new Drawer();
        drawer.createResources(framesNum);

        Uniforms.setupDefaultUniforms();
        PipelineManager.init();
        UploadManager.createInstance();

        allocateCommandBuffers();
        createSyncObjects();
        createGpuQueryPools();
    }

    private void createGpuQueryPools() {
        this.timestampPeriodNanos = DeviceManager.deviceProperties.limits().timestampPeriod();

        this.gpuQueryPools = new long[framesNum];
        this.gpuTimestampCount = new int[framesNum];
        this.gpuTimestampLabels = new byte[framesNum][GPU_QUERY_COUNT];
        this.gpuDrawQueryPools = new long[framesNum];
        this.gpuDrawQueryCount = new int[framesNum];

        try (MemoryStack stack = stackPush()) {
            VkQueryPoolCreateInfo passInfo = VkQueryPoolCreateInfo.calloc(stack);
            passInfo.sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO);
            passInfo.queryType(VK_QUERY_TYPE_TIMESTAMP);
            passInfo.queryCount(GPU_QUERY_COUNT);

            VkQueryPoolCreateInfo drawInfo = VkQueryPoolCreateInfo.calloc(stack);
            drawInfo.sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO);
            drawInfo.queryType(VK_QUERY_TYPE_TIMESTAMP);
            drawInfo.queryCount(GPU_DRAW_QUERY_COUNT);

            LongBuffer pPool = stack.mallocLong(1);

            for (int i = 0; i < framesNum; ++i) {
                if (vkCreateQueryPool(device, passInfo, null, pPool) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create GPU timestamp query pool " + i);
                }
                this.gpuQueryPools[i] = pPool.get(0);

                if (vkCreateQueryPool(device, drawInfo, null, pPool) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create GPU draw-timestamp query pool " + i);
                }
                this.gpuDrawQueryPools[i] = pPool.get(0);
            }
        }
    }

    /**
     * Experiment B: record one GPU timestamp into the current frame slot's query
     * pool at a render-pass boundary, tagging it with a category so the per-frame
     * GPU time can later be split into pass segments. A cheap vkCmdWriteTimestamp;
     * writes are capped at {@link #GPU_QUERY_COUNT} per frame.
     */
    public void writeGpuTimestamp(VkCommandBuffer cb, byte label) {
        if (gpuQueryPools == null)
            return;
        int slot = currentFrame;
        int idx = gpuTimestampCount[slot];
        if (idx >= GPU_QUERY_COUNT)
            return;
        vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, gpuQueryPools[slot], idx);
        gpuTimestampLabels[slot][idx] = label;
        gpuTimestampCount[slot] = idx + 1;
    }

    /**
     * Per-draw GPU timing: records one timestamp right before a draw command so
     * the draw's GPU execution time is recovered (interval to the next draw) by
     * {@link #fetchGpuDrawNanos(int)}. Cheap vkCmdWriteTimestamp; capped at
     * {@link #GPU_DRAW_QUERY_COUNT} per frame to avoid pool overflow.
     */
    public void writeGpuDrawTimestamp(VkCommandBuffer cb) {
        if (!GPU_DRAW_TIMING || gpuDrawQueryPools == null)
            return;
        int slot = currentFrame;
        int idx = gpuDrawQueryCount[slot];
        if (idx >= GPU_DRAW_QUERY_COUNT)
            return;
        vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, gpuDrawQueryPools[slot], idx);
        gpuDrawQueryCount[slot] = idx + 1;
    }

    /** Reads the finished timestamp set of this frame slot (fence already signaled). */
    private void fetchGpuPassNanos(int frameSlot) {
        if (this.gpuQueryPools == null) {
            return;
        }

        int n = gpuTimestampCount[frameSlot];
        if (n < 2) {
            return;
        }

        try (MemoryStack stack = stackPush()) {
            LongBuffer results = stack.mallocLong(GPU_QUERY_COUNT);
            // No WAIT_BIT here: on the very first use of a slot (or after skipped
            // frames) the pool was never written and a blocking fetch would hang
            // the game before the first frame. The fence only guarantees results
            // when the slot's last submission actually recorded the queries, so
            // VK_NOT_READY is a normal "nothing to report" and just skips.
            // Query exactly the n slots that were written this frame, NOT the full
            // GPU_QUERY_COUNT pool. Unwritten (reset-but-empty) slots report
            // VK_NOT_READY, which would fail the entire read and zero gpuPass.
            int result = vkGetQueryPoolResults(device, this.gpuQueryPools[frameSlot], 0, n,
                    results, Long.BYTES, VK_QUERY_RESULT_64_BIT);
            if (result != VK_SUCCESS) {
                return;
            }

            long prev = -1;
            long total = 0;
            long mainNanos = 0;
            long offNanos = 0;
            int passCount = 0;
            int offCount = 0;
            for (int i = 0; i < n; i++) {
                long t = results.get(i);
                if (prev >= 0 && t > prev) {
                    long seg = (long) ((t - prev) * this.timestampPeriodNanos);
                    total += seg;
                    // The segment between t[i-1] and t[i] is the GPU work of the
                    // pass that began at t[i-1], so its label is labels[i-1].
                    byte lbl = gpuTimestampLabels[frameSlot][i - 1];
                    if (lbl == GPU_TS_OFF) {
                        offNanos += seg;
                        offCount++;
                    } else {
                        mainNanos += seg;
                    }
                    passCount++;
                }
                prev = t;
            }

            if (total <= 0) {
                return;
            }

            FrameProfiler.onGpuPassNanos(total);
            FrameProfiler.onGpuPassBreakdown(mainNanos, offNanos, passCount, offCount);
        }
    }

    /**
     * Reads the per-draw GPU timestamps of this frame slot (fence already
     * signaled) and feeds each draw's interval (to the next draw) to
     * FrameProfiler for histogram aggregation. The interval between two
     * consecutive draw-begin timestamps approximates the GPU time spent
     * executing the earlier draw including its queued setup.
     */
    private void fetchGpuDrawNanos(int frameSlot) {
        if (!GPU_DRAW_TIMING || this.gpuDrawQueryPools == null)
            return;
        int n = gpuDrawQueryCount[frameSlot];
        if (n < 2)
            return;
        try (MemoryStack stack = stackPush()) {
            LongBuffer results = stack.mallocLong(GPU_DRAW_QUERY_COUNT);
            int result = vkGetQueryPoolResults(device, this.gpuDrawQueryPools[frameSlot], 0, n,
                    results, Long.BYTES, VK_QUERY_RESULT_64_BIT);
            if (result != VK_SUCCESS)
                return;
            long prev = -1;
            for (int i = 0; i < n; i++) {
                long t = results.get(i);
                if (prev >= 0 && t > prev) {
                    long seg = (long) ((t - prev) * this.timestampPeriodNanos);
                    FrameProfiler.onGpuDrawNanos(seg);
                }
                prev = t;
            }
        }
    }

    private void allocateCommandBuffers() {
        if (commandBuffers != null) {
            commandBuffers.forEach(commandBuffer -> vkFreeCommandBuffers(device, Vulkan.getCommandPool(), commandBuffer));
        }

        commandBuffers = new ArrayList<>(framesNum);

        try (MemoryStack stack = stackPush()) {

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.commandPool(getCommandPool());
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandBufferCount(framesNum);

            PointerBuffer pCommandBuffers = stack.mallocPointer(framesNum);

            int vkResult = vkAllocateCommandBuffers(device, allocInfo, pCommandBuffers);
            if (vkResult != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate command buffers: %s".formatted(VkResult.decode(vkResult)));
            }

            for (int i = 0; i < framesNum; i++) {
                commandBuffers.add(new VkCommandBuffer(pCommandBuffers.get(i), device));
            }
        }
    }

    private void createSyncObjects() {
        // The render-finished semaphores are indexed by the swapchain IMAGE (not
        // the frame slot): re-signaling a render-finished semaphore while the
        // swapchain still uses it in a previous presentation violates
        // VUID-vkQueueSubmit-pSignalSemaphores-00067. With per-image semaphores
        // the semaphore for an image is only reused after that image has been
        // re-acquired, which is fenced by imagesInFlight.
        int imageCount = swapChain.getImages().size();
        imageAvailableSemaphores = new ArrayList<>(framesNum);
        renderFinishedSemaphores = new ArrayList<>(imageCount);
        inFlightFences = new ArrayList<>(framesNum);

        try (MemoryStack stack = stackPush()) {

            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack);
            semaphoreInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack);
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer pImageAvailableSemaphore = stack.mallocLong(1);
            LongBuffer pRenderFinishedSemaphore = stack.mallocLong(1);
            LongBuffer pFence = stack.mallocLong(1);

            for (int i = 0; i < framesNum; i++) {

                if (vkCreateSemaphore(device, semaphoreInfo, null, pImageAvailableSemaphore) != VK_SUCCESS
                    || vkCreateFence(device, fenceInfo, null, pFence) != VK_SUCCESS) {

                    throw new RuntimeException("Failed to create synchronization objects for the frame: " + i);
                }

                imageAvailableSemaphores.add(pImageAvailableSemaphore.get(0));
                inFlightFences.add(pFence.get(0));
            }

            for (int i = 0; i < imageCount; i++) {
                if (vkCreateSemaphore(device, semaphoreInfo, null, pRenderFinishedSemaphore) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create render-finished semaphore for image: " + i);
                }

                renderFinishedSemaphores.add(pRenderFinishedSemaphore.get(0));
            }
        }
    }

    public void preInitFrame() {
//        Profiler p = Profiler.getMainProfiler();
//        p.pop();
//        p.round();
//        p.push("Frame_ops");

        // runTick might be called recursively,
        // this check forces sync to avoid upload corruption
        if (lastReset == currentFrame) {
            waitFences();
        }
        lastReset = currentFrame;

        drawer.resetBuffers(currentFrame);

//        WorldRenderer.getInstance().uploadSections();
//        UploadManager.INSTANCE.submitUploads();
    }

    public void beginFrame() {
        FrameProfiler.beginFrame();

//        Profiler p = Profiler.getMainProfiler();
//        p.pop();
//        p.push("Frame_fence");

        if (swapChainUpdate) {
            recreateSwapChain();
            swapChainUpdate = false;

            if (getSwapChain().getWidth() == 0 && getSwapChain().getHeight() == 0) {
                skipRendering = true;
//                Minecraft.getInstance().noRender = true;
            } else {
                skipRendering = false;
//                Minecraft.getInstance().noRender = false;
            }
        }


        if (skipRendering || recordingCmds)
            return;

        FrameProfiler.beginFenceWait();
        vkWaitForFences(device, inFlightFences.get(currentFrame), true, VUtil.UINT64_MAX);
        FrameProfiler.endFenceWait();

        // The fence above guarantees this frame slot's previous command buffer
        // finished, so its timestamp pair is ready to be read.
        fetchGpuPassNanos(currentFrame);
        fetchGpuDrawNanos(currentFrame);

        // Rebuild the mip chains of any texture uploaded last frame. Doing it
        // here - rather than inside the upload - means the level-0 data it
        // reads has actually reached the GPU, and it keeps the blit out of any
        // render pass.
        com.yuhan123.vulkanmod.gl.VkGlTexture.rebuildPendingMipmaps();

        // This frame slot's staging buffer was filled the last time this slot
        // rendered (3 frames ago). Its upload batch was submitted BEFORE the
        // fence we just waited on, on the same queue, so the GPU is guaranteed
        // to be done with it and the buffer can be rewound here. Deferring the
        // reset to this point is what lets uploads run asynchronously instead
        // of stalling the pipeline at the end of every frame.
        //
        // If uploads were recorded while no frame was being rendered, that batch
        // is still open and still references this buffer - leave it alone and
        // let it be flushed normally (it grows until the staging buffer wraps,
        // which flushes it).
        if (!ImageUploadHelper.INSTANCE.hasPendingCommands()) {
            Vulkan.getStagingBuffer().reset();
        }

//        p.pop();
//        p.push("Begin_rendering");

        MemoryManager.getInstance().initFrame(currentFrame);
        drawer.setCurrentFrame(currentFrame);

        resetDescriptors();

        currentCmdBuffer = commandBuffers.get(currentFrame);
        vkResetCommandBuffer(currentCmdBuffer, 0);

        try (MemoryStack stack = stackPush()) {

            IntBuffer pImageIndex = stack.mallocInt(1);

            FrameProfiler.beginFenceWait();
            int vkResult = vkAcquireNextImageKHR(device, swapChain.getId(), VUtil.UINT64_MAX,
                                                 imageAvailableSemaphores.get(currentFrame), VK_NULL_HANDLE, pImageIndex);
            FrameProfiler.endFenceWait();

            if (vkResult == VK_SUBOPTIMAL_KHR || vkResult == VK_ERROR_OUT_OF_DATE_KHR || swapChainUpdate) {
                swapChainUpdate = true;
                skipRendering = true;
                beginFrame();

                return;
            } else if (vkResult != VK_SUCCESS) {
                throw new RuntimeException("Cannot acquire next swap chain image: %s".formatted(VkResult.decode(vkResult)));
            }

            imageIndex = pImageIndex.get(0);

            // Wait until the previous frame that rendered this swapchain image has
            // finished, so its semaphores/fences are safe to reuse (per-image sync).
            FrameProfiler.beginFenceWait();
            if (imagesInFlight != null && imagesInFlight[imageIndex] != VK_NULL_HANDLE
                    && imagesInFlight[imageIndex] != inFlightFences.get(currentFrame)) {
                vkWaitForFences(device, imagesInFlight[imageIndex], true, VUtil.UINT64_MAX);
            }
            FrameProfiler.endFenceWait();
            if (imagesInFlight != null)
                imagesInFlight[imageIndex] = inFlightFences.get(currentFrame);

            this.beginRenderPass(stack);
        }

//        p.pop();
    }

    private void beginRenderPass(MemoryStack stack) {
        VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
        beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
        beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

        VkCommandBuffer commandBuffer = currentCmdBuffer;

        int vkResult = vkBeginCommandBuffer(commandBuffer, beginInfo);
        if (vkResult != VK_SUCCESS) {
            throw new RuntimeException("Failed to begin recording command buffer: %s".formatted(VkResult.decode(vkResult)));
        }

        // A (re)begun command buffer has no bindings; every cached "already
        // bound" state from the previous recording session is void.
        invalidateBindingState();

        recordingCmds = true;
        mainPass.begin(commandBuffer, stack);

        long __c1 = FrameProfiler.start();
        vkCmdResetQueryPool(commandBuffer, gpuQueryPools[currentFrame], 0, GPU_QUERY_COUNT);
        FrameProfiler.addCmd(FrameProfiler.CMD_OTHER, __c1);
        gpuTimestampCount[currentFrame] = 0;
        if (GPU_DRAW_TIMING) {
            long __cd = FrameProfiler.start();
            vkCmdResetQueryPool(commandBuffer, gpuDrawQueryPools[currentFrame], 0, GPU_DRAW_QUERY_COUNT);
            FrameProfiler.addCmd(FrameProfiler.CMD_OTHER, __cd);
            gpuDrawQueryCount[currentFrame] = 0;
        }
        long __c2 = FrameProfiler.start();
        writeGpuTimestamp(commandBuffer, GPU_TS_MAIN);
        FrameProfiler.addCmd(FrameProfiler.CMD_OTHER, __c2);

        resetDynamicState(commandBuffer);
    }

    public void endFrame() {
        if (skipRendering || !recordingCmds) {
            FrameProfiler.endFrame();
            return;
        }

        // Anything still deferred has to be recorded before the pass is closed.
        if (currentCmdBuffer != null && gpuQueryPools != null) {
            long __c = FrameProfiler.start();
            writeGpuTimestamp(currentCmdBuffer, GPU_TS_END);
            FrameProfiler.addCmd(FrameProfiler.CMD_OTHER, __c);
        }

        mainPass.end(currentCmdBuffer);

        // Flush the batched texture uploads BEFORE the frame is submitted. Both
        // go to the graphics queue, so the copies land before any draw that
        // samples them. Deliberately no CPU-side wait here: the upload fences
        // are reaped later, once the GPU reports them signaled.
        ImageUploadHelper.INSTANCE.submitCommands();

        FrameProfiler.beginSubmit();
        submitFrame();
        FrameProfiler.endSubmit();
        recordingCmds = false;

        Synchronization.INSTANCE.reapSignaled();

//        p.pop();
//        p.push("Post_rendering");

        FrameProfiler.endFrame();
    }

    private void submitFrame() {
        if (swapChainUpdate)
            return;

        try (MemoryStack stack = stackPush()) {
            int vkResult;

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);

            submitInfo.waitSemaphoreCount(1);
            submitInfo.pWaitSemaphores(stack.longs(imageAvailableSemaphores.get(currentFrame)));
            submitInfo.pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT));
            submitInfo.pSignalSemaphores(stack.longs(renderFinishedSemaphores.get(imageIndex)));
            submitInfo.pCommandBuffers(stack.pointers(currentCmdBuffer));

            vkResetFences(device, inFlightFences.get(currentFrame));

            if ((vkResult = vkQueueSubmit(DeviceManager.getGraphicsQueue().queue(), submitInfo, inFlightFences.get(currentFrame))) != VK_SUCCESS) {
                vkResetFences(device, inFlightFences.get(currentFrame));
                throw new RuntimeException("Failed to submit draw command buffer: %s".formatted(VkResult.decode(vkResult)));
            }

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack);
            presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);

            presentInfo.pWaitSemaphores(stack.longs(renderFinishedSemaphores.get(imageIndex)));

            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(stack.longs(swapChain.getId()));

            presentInfo.pImageIndices(stack.ints(imageIndex));

            // Timed separately from vkQueueSubmit: this call can block on the
            // presentation engine (vsync, driver frame pacing, a full queue of
            // images), and that is not CPU work. Without splitting it out it lands
            // in the report's "cpu" figure, which is defined as frame - fence, and
            // makes a present-bound frame look CPU-bound.
            FrameProfiler.beginPresent();
            vkResult = vkQueuePresentKHR(DeviceManager.getPresentQueue().queue(), presentInfo);
            FrameProfiler.endPresent();

            if (vkResult == VK_ERROR_OUT_OF_DATE_KHR || vkResult == VK_SUBOPTIMAL_KHR || swapChainUpdate) {
                swapChainUpdate = true;
                return;
            } else if (vkResult != VK_SUCCESS) {
                throw new RuntimeException("Failed to present rendered frame: %s".formatted(VkResult.decode(vkResult)));
            }

            currentFrame = (currentFrame + 1) % framesNum;
        }
    }

    /**
     * Called in case draw results are needed before the end of the frame
     */
    public void flushCmds() {
        if (!this.recordingCmds)
            return;

        try (MemoryStack stack = stackPush()) {
            int vkResult;

            this.endRenderPass(currentCmdBuffer);
            vkEndCommandBuffer(currentCmdBuffer);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);

            submitInfo.pCommandBuffers(stack.pointers(currentCmdBuffer));

            vkResetFences(device, inFlightFences.get(currentFrame));

            waitFences();

            if ((vkResult = vkQueueSubmit(DeviceManager.getGraphicsQueue().queue(), submitInfo, inFlightFences.get(currentFrame))) != VK_SUCCESS) {
                vkResetFences(device, inFlightFences.get(currentFrame));
                throw new RuntimeException("Failed to submit draw command buffer: %s".formatted(VkResult.decode(vkResult)));
            }

            vkWaitForFences(device, inFlightFences.get(currentFrame), true, VUtil.UINT64_MAX);

            this.beginRenderPass(stack);
        }
    }

    public void endRenderPass() {
        endRenderPass(currentCmdBuffer);
    }

    public void endRenderPass(VkCommandBuffer commandBuffer) {
        if (skipRendering || !recordingCmds || this.boundFramebuffer == null)
            return;

        if (!DYNAMIC_RENDERING)
            this.boundRenderPass.endRenderPass(currentCmdBuffer);
        else
            KHRDynamicRendering.vkCmdEndRenderingKHR(commandBuffer);

        this.boundRenderPass = null;
        this.boundFramebuffer = null;

        // A pipeline bind requested before the pass ended must not be flushed
        // afterwards: flushPipelineBind() resolves the handle against
        // boundRenderPass, and that pass is gone. Every draw re-requests the bind
        // through apply() anyway, so dropping the pending request loses nothing.
        // boundPipelineHandle is deliberately left alone so a draw that arrives
        // without a fresh request behaves exactly as it did before deferral.
        this.pipelineBindPending = false;

        VkGlFramebuffer.resetBoundFramebuffer();
    }

    public boolean beginRendering(RenderPass renderPass, Framebuffer framebuffer) {
        if (skipRendering || !recordingCmds)
            return false;

        if (this.boundFramebuffer != framebuffer) {
            this.endRenderPass(currentCmdBuffer);

            // Experiment B: mark the boundary between the pass we just closed and
            // the pass about to begin. t0 (frame start) already captured the first
            // main pass; this tags every subsequent switch (offscreen FBO, or a
            // return to the main swapchain target via rebindMainTarget's sibling
            // path that still routes through here).
            writeGpuTimestamp(currentCmdBuffer,
                    (framebuffer == Renderer.getInstance().getSwapChain()) ? GPU_TS_MAIN : GPU_TS_OFF);

            try (MemoryStack stack = stackPush()) {
                framebuffer.beginRenderPass(currentCmdBuffer, renderPass, stack);
            }

            this.boundFramebuffer = framebuffer;
        }
        return true;
    }

    public void addUsedPipeline(Pipeline pipeline) {
        usedPipelines.add(pipeline);
    }

    public void removeUsedPipeline(Pipeline pipeline) {
        usedPipelines.remove(pipeline);
    }

    /**
     * Full blocking flush of every pending upload. Only for the rare paths that
     * genuinely cannot tolerate in-flight work (recursive runTick, swapchain
     * recreation) - never call it on the per-frame path.
     */
    private void waitFences() {
        // Make sure there are no uploads/transitions scheduled
        ImageUploadHelper.INSTANCE.submitCommands();
        Synchronization.INSTANCE.waitFences();
        Vulkan.getStagingBuffer().reset();
    }

    private void resetDescriptors() {
        invalidateBindingState();

        for (Pipeline pipeline : usedPipelines) {
            pipeline.resetDescriptorPool(currentFrame);
        }

        usedPipelines.clear();
        boundPipeline = null;
        boundPipelineHandle = 0;
    }

    void waitForSwapChain() {
        vkResetFences(device, inFlightFences.get(currentFrame));

//        constexpr VkPipelineStageFlags t=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            //Empty Submit
            VkSubmitInfo info = VkSubmitInfo.calloc(stack)
                                            .sType$Default()
                                            .pWaitSemaphores(stack.longs(imageAvailableSemaphores.get(currentFrame)))
                                            .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT));

            vkQueueSubmit(DeviceManager.getGraphicsQueue().queue(), info, inFlightFences.get(currentFrame));
            vkWaitForFences(device, inFlightFences.get(currentFrame), true, -1);
        }
    }

    @SuppressWarnings("UnreachableCode")
    private void recreateSwapChain() {
        waitFences();
        Vulkan.waitIdle();

        commandBuffers.forEach(commandBuffer -> vkResetCommandBuffer(commandBuffer, 0));
        recordingCmds = false;

        swapChain.recreate();

        // The swapchain image count can change after recreation; keep the
        // per-image tracking arrays in sync with the new swapchain.
        imagesInFlight = new long[swapChain.getImages().size()];

        //Semaphores need to be recreated in order to make them unsignaled
        destroySyncObjects();

        int newFramesNum = 3;

        if (framesNum != newFramesNum) {
//            UploadManager.INSTANCE.submitUploads();

            framesNum = newFramesNum;
            MemoryManager.getInstance().freeAllBuffers();
            MemoryManager.createInstance(newFramesNum);
            createStagingBuffers();
            allocateCommandBuffers();

            Pipeline.recreateDescriptorSets(framesNum);

            drawer.createResources(framesNum);
        }

        createSyncObjects();
        this.mainPass.onResize();

        // The cached PipelineStates reference the render pass that was just
        // destroyed with the old swapchain.
        PipelineState.clearStateCache();
        this.onResizeCallbacks.forEach(Runnable::run);
//        ((WindowAccessor) (Object) Minecraft.getInstance().getWindow()).getEventHandler().resizeDisplay();

        currentFrame = 0;
    }

    public void cleanUpResources() {
//        WorldRenderer.getInstance().cleanUp();
        destroySyncObjects();

        drawer.cleanUpResources();
        mainPass.cleanUp();
        swapChain.cleanUp();

//        PipelineManager.destroyPipelines();
        VTextureSelector.getWhiteTexture().free();
    }

    private void destroySyncObjects() {
        for (int i = 0; i < framesNum; ++i) {
            vkDestroyFence(device, inFlightFences.get(i), null);
            vkDestroySemaphore(device, imageAvailableSemaphores.get(i), null);
        }

        if (renderFinishedSemaphores != null) {
            for (int i = 0; i < renderFinishedSemaphores.size(); ++i) {
                vkDestroySemaphore(device, renderFinishedSemaphores.get(i), null);
            }
        }
    }

    public void addOnResizeCallback(Runnable runnable) {
        this.onResizeCallbacks.add(runnable);
    }

    public void bindGraphicsPipeline(GraphicsPipeline pipeline) {
        // Record the intent only; the actual vkCmdBindPipeline is issued by
        // flushPipelineBind() immediately before the next draw, so a state change
        // between here and the draw coalesces into a single bind.
        this.pendingPipeline = pipeline;
        this.pipelineBindPending = true;
        this.boundPipeline = pipeline;

        // Eager, and deliberately NOT part of the deferred bind: uploadAndBindUBOs
        // writes this pipeline's descriptor sets out of its descriptor pool, so
        // resetDescriptors() must reset that pool next frame even when the handle
        // bind below is coalesced away.
        addUsedPipeline(pipeline);
    }

    /**
     * Issues the pending vkCmdBindPipeline, if any, and only when the handle the
     * current render state resolves to is not already bound. Every draw path calls
     * this immediately before recording its draw.
     */
    public void flushPipelineBind() {
        if (!this.pipelineBindPending) {
            return;
        }
        this.pipelineBindPending = false;

        final GraphicsPipeline pipeline = this.pendingPipeline;
        if (pipeline == null) {
            return;
        }

        // Evaluated here rather than at request time so the state read is the one
        // in effect at the draw.
        //
        // No render pass means there is nothing to resolve the state against
        // (PipelineState carries the RenderPass, and a null one cannot produce a
        // pipeline handle). A draw outside a render pass is invalid anyway; leave
        // the previously bound handle alone rather than dereferencing null.
        if (boundRenderPass == null) {
            return;
        }

        final PipelineState currentState = PipelineState.getCurrentPipelineState(boundRenderPass);
        final long handle = pipeline.getHandle(currentState);

        if (boundPipelineHandle == handle) {
            return;
        }

        long __c = FrameProfiler.start();
        vkCmdBindPipeline(currentCmdBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, handle);
        FrameProfiler.addCmd(FrameProfiler.CMD_BIND_PIPELINE, __c);
        boundPipelineHandle = handle;
        FrameProfiler.onPipelineBind();
    }

    public void uploadAndBindUBOs(Pipeline pipeline) {
        if (pipeline == null)
            return;

        // The per-draw matrix is a push constant now (see the shader JSONs), so
        // it is re-pushed on every draw, while the descriptor sets below only
        // change when the bound textures or the uniform values really change.
        final boolean timed = com.yuhan123.vulkanmod.render.util.FrameProfiler.DETAILED_TIMING;

        long __s = timed ? com.yuhan123.vulkanmod.render.util.FrameProfiler.start() : 0L;
        pushConstants(pipeline);
        if (timed) {
            com.yuhan123.vulkanmod.render.util.FrameProfiler.addFullSegment(
                    __s, com.yuhan123.vulkanmod.render.util.FrameProfiler.FULL_PUSH);
        }

        __s = timed ? com.yuhan123.vulkanmod.render.util.FrameProfiler.start() : 0L;
        pipeline.bindDescriptorSets(currentCmdBuffer, currentFrame);
        if (timed) {
            com.yuhan123.vulkanmod.render.util.FrameProfiler.addFullSegment(
                    __s, com.yuhan123.vulkanmod.render.util.FrameProfiler.FULL_DESC);
        }
    }

    public void pushConstants(Pipeline pipeline) {
        PushConstants pushConstants = pipeline.getPushConstants();

        if (pushConstants == null)
            return;

        final int size = pushConstants.getSize();

        // Deliberately not a MemoryStack allocation: this runs once per draw call
        // and terrain alone is thousands of draws per frame.
        if (pushConstantScratch == null || pushConstantScratch.capacity() < size) {
            if (pushConstantScratch != null) {
                MemoryUtil.memFree(pushConstantScratch);
            }
            if (pushConstantShadow != null) {
                MemoryUtil.memFree(pushConstantShadow);
            }
            pushConstantScratch = MemoryUtil.memAlloc(size);
            // Shadow copy of the last pushed bytes, for the comparison below.
            pushConstantShadow = MemoryUtil.memAlloc(size);
            pushConstantShadowSize = -1;
        }

        long ptr = MemoryUtil.memAddress0(pushConstantScratch);
        pushConstants.update(ptr);

        // Re-pushing byte-identical values is a no-op: push constants are
        // command-buffer state, so the previous push is still in effect. Terrain
        // draws several layers of the same chunk section back to back and every
        // one of them carries the same MVP, so a large fraction of the per-frame
        // pushes are redundant.
        //
        // Two guards, because skipping a push that was actually needed would be a
        // silent transform bug:
        //  - the epoch, because a (re)begun command buffer has no push-constant
        //    state at all (see Renderer.getBindingEpoch), and
        //  - the pipeline, because a different layout means a different range.
        if (pushConstantEpoch == getBindingEpoch()
                && pushConstantPipeline == pipeline
                && pushConstantShadowSize == size
                && memEquals(pushConstantShadow, ptr, size)) {
            FrameProfiler.onPushConstantSkipped();
            return;
        }

        long __c = FrameProfiler.start();
        nvkCmdPushConstants(currentCmdBuffer, pipeline.getLayout(), VK_SHADER_STAGE_VERTEX_BIT, 0, size, ptr);
        FrameProfiler.addCmd(FrameProfiler.CMD_PUSH_CONSTANTS, __c);
        MemoryUtil.memCopy(ptr, MemoryUtil.memAddress0(pushConstantShadow), size);
        pushConstantShadowSize = size;
        pushConstantPipeline = pipeline;
        pushConstantEpoch = getBindingEpoch();
        FrameProfiler.onPushConstants();
    }

    /**
     * Whole-word comparison of a native block against a ByteBuffer. The struct is
     * a multiple of 4 bytes (mat4 here), but the tail loop keeps it correct for
     * any size.
     */
    private static boolean memEquals(ByteBuffer shadow, long otherPtr, int size) {
        final long shadowPtr = MemoryUtil.memAddress0(shadow);

        int off = 0;
        for (; off + 8 <= size; off += 8) {
            if (VUtil.UNSAFE.getLong(shadowPtr + off) != VUtil.UNSAFE.getLong(otherPtr + off)) {
                return false;
            }
        }
        for (; off < size; ++off) {
            if (VUtil.UNSAFE.getByte(shadowPtr + off) != VUtil.UNSAFE.getByte(otherPtr + off)) {
                return false;
            }
        }
        return true;
    }

    public Pipeline getBoundPipeline() {
        return boundPipeline;
    }

    public void setBoundFramebuffer(Framebuffer framebuffer) {
        this.boundFramebuffer = framebuffer;
    }

    public Framebuffer getBoundFramebuffer() {
        return boundFramebuffer;
    }

    public void setBoundRenderPass(RenderPass boundRenderPass) {
        this.boundRenderPass = boundRenderPass;
    }

    public RenderPass getBoundRenderPass() {
        return boundRenderPass;
    }

    public void setMainPass(MainPass mainPass) {
        this.mainPass = mainPass;
    }

    public MainPass getMainPass() {
        return this.mainPass;
    }

    public SwapChain getSwapChain() {
        return swapChain;
    }

    private static void resetDynamicState(VkCommandBuffer commandBuffer) {
        vkCmdSetDepthBias(commandBuffer, 0.0F, 0.0F, 0.0F);

        vkCmdSetLineWidth(commandBuffer, 1.0F);
    }

    public static void setDepthBias(float constant, float slope) {
        VkCommandBuffer commandBuffer = INSTANCE.currentCmdBuffer;

        vkCmdSetDepthBias(commandBuffer, constant, 0.0f, slope);
    }

    public static void clearAttachments(int v) {
        Framebuffer framebuffer = Renderer.getInstance().boundFramebuffer;
        if (framebuffer == null)
            return;

        clearAttachments(v, framebuffer.getWidth(), framebuffer.getHeight());
    }

    public static void clearAttachments(int v, int width, int height) {
        if (skipRendering)
            return;

        try (MemoryStack stack = stackPush()) {
            //ClearValues have to be different for each attachment to clear,
            //it seems it uses the same buffer: color and depth values override themselves
            VkClearValue colorValue = VkClearValue.calloc(stack);
            colorValue.color().float32(VRenderSystem.clearColor);

            VkClearValue depthValue = VkClearValue.calloc(stack);
            depthValue.depthStencil().set(VRenderSystem.clearDepthValue, 0); //Use fast depth clears if possible

            int attachmentsCount = v == (GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT) ? 2 : 1;
            final VkClearAttachment.Buffer pAttachments = VkClearAttachment.malloc(attachmentsCount, stack);
            switch (v) {
                case GL_DEPTH_BUFFER_BIT -> {

                    VkClearAttachment clearDepth = pAttachments.get(0);
                    clearDepth.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                    clearDepth.colorAttachment(0);
                    clearDepth.clearValue(depthValue);
                }
                case GL_COLOR_BUFFER_BIT -> {

                    VkClearAttachment clearColor = pAttachments.get(0);
                    clearColor.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                    clearColor.colorAttachment(0);
                    clearColor.clearValue(colorValue);
                }
                case GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT -> {

                    VkClearAttachment clearColor = pAttachments.get(0);
                    clearColor.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                    clearColor.colorAttachment(0);
                    clearColor.clearValue(colorValue);

                    VkClearAttachment clearDepth = pAttachments.get(1);
                    clearDepth.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                    clearDepth.colorAttachment(0);
                    clearDepth.clearValue(depthValue);
                }
                default -> throw new RuntimeException("unexpected value");
            }

            //Rect to clear
            VkRect2D renderArea = VkRect2D.malloc(stack);
            renderArea.offset().set(0, 0);
            renderArea.extent().set(width, height);

            VkClearRect.Buffer pRect = VkClearRect.malloc(1, stack);
            pRect.rect(renderArea);
            pRect.baseArrayLayer(0);
            pRect.layerCount(1);

            long __c = FrameProfiler.start();
            vkCmdClearAttachments(INSTANCE.currentCmdBuffer, pAttachments, pRect);
            FrameProfiler.addCmd(FrameProfiler.CMD_CLEAR_ATTACHMENTS, __c);
        }
    }

    public static void setInvertedViewport(int x, int y, int width, int height) {
        setViewportState(x, y + height, width, -height);
    }

    public static void resetViewport() {
        int width = INSTANCE.getSwapChain().getWidth();
        int height = INSTANCE.getSwapChain().getHeight();

        setViewportState(0, 0, width, height);
    }

    public static void setViewportState(int x, int y, int width, int height) {
        net.minecraft.client.renderer.GlStateManager.viewport(x, y, width, height);
    }

    public static void setViewport(int x, int y, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            setViewport(x, y, width, height, stack);
        }
    }

    public static void setViewport(int x, int y, int width, int height, MemoryStack stack) {
        if (!INSTANCE.recordingCmds)
            return;

        VkViewport.Buffer viewport = VkViewport.malloc(1, stack);
        viewport.x(x);
        viewport.y(height + y);
        viewport.width(width);
        viewport.height(-height);
        viewport.minDepth(0.0f);
        viewport.maxDepth(1.0f);

        long __c = FrameProfiler.start();
        vkCmdSetViewport(INSTANCE.currentCmdBuffer, 0, viewport);
        FrameProfiler.addCmd(FrameProfiler.CMD_SET_VIEWPORT, __c);
    }

    public static void setScissor(int x, int y, int width, int height) {
        if (INSTANCE.boundFramebuffer == null)
            return;

        try (MemoryStack stack = stackPush()) {
            int framebufferHeight = INSTANCE.boundFramebuffer.getHeight();

            x = Math.max(0, x);

            VkRect2D.Buffer scissor = VkRect2D.malloc(1, stack);
            scissor.offset().set(x, framebufferHeight - (y + height));
        scissor.extent().set(width, height);

        long __c = FrameProfiler.start();
        vkCmdSetScissor(INSTANCE.currentCmdBuffer, 0, scissor);
        FrameProfiler.addCmd(FrameProfiler.CMD_SET_SCISSOR, __c);
    }
    }

    public static void resetScissor() {
        if (INSTANCE.boundFramebuffer == null)
            return;

        try (MemoryStack stack = stackPush()) {
        VkRect2D.Buffer scissor = INSTANCE.boundFramebuffer.scissor(stack);
        long __c = FrameProfiler.start();
        vkCmdSetScissor(INSTANCE.currentCmdBuffer, 0, scissor);
        FrameProfiler.addCmd(FrameProfiler.CMD_SET_SCISSOR, __c);
    }
    }

    public static void pushDebugSection(String s) {
        if (Vulkan.ENABLE_VALIDATION_LAYERS) {
            VkCommandBuffer commandBuffer = INSTANCE.currentCmdBuffer;

            try (MemoryStack stack = stackPush()) {
                VkDebugUtilsLabelEXT markerInfo = VkDebugUtilsLabelEXT.calloc(stack);
                markerInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_LABEL_EXT);
                ByteBuffer string = stack.UTF8(s);
                markerInfo.pLabelName(string);
                vkCmdBeginDebugUtilsLabelEXT(commandBuffer, markerInfo);
            }
        }
    }

    public static void popDebugSection() {
        if (Vulkan.ENABLE_VALIDATION_LAYERS) {
            VkCommandBuffer commandBuffer = INSTANCE.currentCmdBuffer;

            vkCmdEndDebugUtilsLabelEXT(commandBuffer);
        }
    }

    public static void popPushDebugSection(String s) {
        popDebugSection();
        pushDebugSection(s);
    }

    public static int getFramesNum() {
        return INSTANCE.framesNum;
    }

    public static VkCommandBuffer getCommandBuffer() {
        return INSTANCE.currentCmdBuffer;
    }

    public static boolean isRecording() {
        return INSTANCE.recordingCmds;
    }

    public static void scheduleSwapChainUpdate() {
        swapChainUpdate = true;
    }
}