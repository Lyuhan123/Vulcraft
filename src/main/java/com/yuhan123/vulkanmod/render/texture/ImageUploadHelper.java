package com.yuhan123.vulkanmod.render.texture;

import com.yuhan123.vulkanmod.vulkan.Synchronization;
import com.yuhan123.vulkanmod.vulkan.device.DeviceManager;
import com.yuhan123.vulkanmod.vulkan.queue.CommandPool;
import com.yuhan123.vulkanmod.vulkan.queue.Queue;
import com.yuhan123.vulkanmod.vulkan.texture.VulkanImage;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.lwjgl.system.MemoryStack;

import java.util.concurrent.locks.ReentrantLock;

import static org.lwjgl.system.MemoryStack.stackPush;

public class ImageUploadHelper {

    public static final ImageUploadHelper INSTANCE = new ImageUploadHelper();

    /**
     * Guards the upload batch: opening it, appending copies to it (callers hold
     * this for the whole recording operation, see {@code VulkanImage.uploadSubImage})
     * and submitting it.
     *
     * <p>Normally all of this happens on the loading/render thread, but the
     * Forge loading splash runs its own render loop on a second thread and
     * calls {@code Renderer.endFrame()} -&gt; {@code submitCommands()} while the
     * main thread is still uploading textures. Submitting a command buffer that
     * another thread is recording into is invalid Vulkan usage and loses the
     * device, so both sides take this lock.
     */
    public static final ReentrantLock LOCK = new ReentrantLock();

    final Queue queue;
    private CommandPool.CommandBuffer currentCmdBuffer;

    /**
     * Images whose layout still needs to be moved to SHADER_READ_ONLY_OPTIMAL.
     * <p>
     * The transition is appended to this batch's command buffer at submit time
     * instead of being issued lazily from the draw path: a barrier recorded
     * while a render pass is active is illegal (the render passes here have no
     * self-dependency) and forces the driver to do extra synchronisation work
     * mid-frame. Uploads are submitted before the frame's command buffer on the
     * same queue, so ordering is still guaranteed.
     */
    private final ObjectOpenHashSet<VulkanImage> pendingReadOnly = new ObjectOpenHashSet<>();

    public ImageUploadHelper() {
        queue = DeviceManager.getGraphicsQueue();
    }

    public void submitCommands() {
        LOCK.lock();
        try {
            // A deferred read-only transition must not be dropped just because
            // nothing was uploaded in the meantime: the draw path defers to
            // this batch (VulkanImage.readOnlyLayout()) precisely so it does not
            // have to barrier inside an open render pass, and if the batch were
            // skipped the image would be sampled in the wrong layout. Open one
            // for the barriers alone when that is all there is.
            if (this.currentCmdBuffer == null) {
                if (this.pendingReadOnly.isEmpty()) {
                    return;
                }
                this.currentCmdBuffer = this.queue.beginCommands();
            }

            this.flushPendingTransitions();

            queue.submitCommands(this.currentCmdBuffer);
            Synchronization.INSTANCE.addCommandBuffer(this.currentCmdBuffer);

            this.currentCmdBuffer = null;
        } finally {
            LOCK.unlock();
        }
    }

    private void flushPendingTransitions() {
        if (this.pendingReadOnly.isEmpty()) {
            return;
        }

        try (MemoryStack stack = stackPush()) {
            for (VulkanImage image : this.pendingReadOnly) {
                image.readOnlyLayout(stack, this.currentCmdBuffer.getHandle());
            }
        }

        this.pendingReadOnly.clear();
    }

    /** Queue an image for a read-only transition at the end of this batch. */
    public void addPendingReadOnly(VulkanImage image) {
        LOCK.lock();
        try {
            this.pendingReadOnly.add(image);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * True when the image will be transitioned by the upload batch, so the draw
     * path must not try to transition it (which would emit an in-pass barrier).
     */
    public boolean isPendingReadOnly(VulkanImage image) {
        LOCK.lock();
        try {
            return this.pendingReadOnly.contains(image);
        } finally {
            LOCK.unlock();
        }
    }

    /** Submit any pending batched uploads and wait for them to complete. */
    public void flushUploads() {
        submitCommands();
        Synchronization.INSTANCE.waitFences();
    }

    /**
     * Returns the batch's command buffer, creating it on first use.
     * <p>
     * Callers that only record into it must hold {@link #LOCK} for the whole
     * recording operation (see {@code VulkanImage.uploadSubImage}); this method
     * is safe to call on its own because it neither records nor submits.
     */
    public CommandPool.CommandBuffer getOrStartCommandBuffer() {
        LOCK.lock();
        try {
            if (this.currentCmdBuffer == null) {
                this.currentCmdBuffer = this.queue.beginCommands();
            }

            return this.currentCmdBuffer;
        } finally {
            LOCK.unlock();
        }
    }

    public CommandPool.CommandBuffer getCommandBuffer() {
        LOCK.lock();
        try {
            return this.currentCmdBuffer;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * True while a batched upload command buffer is still open. Its recorded
     * copies reference regions of the current frame's staging buffer, so the
     * staging buffer must not be rewound while this is true.
     */
    public boolean hasPendingCommands() {
        LOCK.lock();
        try {
            return this.currentCmdBuffer != null;
        } finally {
            LOCK.unlock();
        }
    }
}
