package com.yuhan123.vulkanmod.render.texture;

import com.yuhan123.vulkanmod.vulkan.Synchronization;
import com.yuhan123.vulkanmod.vulkan.device.DeviceManager;
import com.yuhan123.vulkanmod.vulkan.queue.CommandPool;
import com.yuhan123.vulkanmod.vulkan.queue.Queue;
import com.yuhan123.vulkanmod.vulkan.texture.VulkanImage;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.system.MemoryStack.stackPush;

public class ImageUploadHelper {

    public static final ImageUploadHelper INSTANCE = new ImageUploadHelper();

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
        if (this.currentCmdBuffer == null) {
            return;
        }

        this.flushPendingTransitions();

        long fence = queue.submitCommands(this.currentCmdBuffer);
        Synchronization.INSTANCE.addCommandBuffer(this.currentCmdBuffer);

        this.currentCmdBuffer = null;
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
        this.pendingReadOnly.add(image);
    }

    /**
     * True when the image will be transitioned by the upload batch, so the draw
     * path must not try to transition it (which would emit an in-pass barrier).
     */
    public boolean isPendingReadOnly(VulkanImage image) {
        return this.pendingReadOnly.contains(image);
    }

    /** Submit any pending batched uploads and wait for them to complete. */
    public void flushUploads() {
        submitCommands();
        Synchronization.INSTANCE.waitFences();
    }

    public CommandPool.CommandBuffer getOrStartCommandBuffer() {
        if (this.currentCmdBuffer == null) {
            this.currentCmdBuffer = this.queue.beginCommands();
        }

        return this.currentCmdBuffer;
    }

    public CommandPool.CommandBuffer getCommandBuffer() {
        return this.currentCmdBuffer;
    }

    /**
     * True while a batched upload command buffer is still open. Its recorded
     * copies reference regions of the current frame's staging buffer, so the
     * staging buffer must not be rewound while this is true.
     */
    public boolean hasPendingCommands() {
        return this.currentCmdBuffer != null;
    }
}
