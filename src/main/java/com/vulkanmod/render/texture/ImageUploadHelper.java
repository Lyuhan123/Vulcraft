package com.yuhan123.vulkanmod.render.texture;

import com.yuhan123.vulkanmod.vulkan.Synchronization;
import com.yuhan123.vulkanmod.vulkan.device.DeviceManager;
import com.yuhan123.vulkanmod.vulkan.queue.CommandPool;
import com.yuhan123.vulkanmod.vulkan.queue.Queue;

public class ImageUploadHelper {

    public static final ImageUploadHelper INSTANCE = new ImageUploadHelper();

    final Queue queue;
    private CommandPool.CommandBuffer currentCmdBuffer;

    public ImageUploadHelper() {
        queue = DeviceManager.getGraphicsQueue();
    }

    public void submitCommands() {
        if (this.currentCmdBuffer == null) {
            return;
        }

        long fence = queue.submitCommands(this.currentCmdBuffer);
        Synchronization.INSTANCE.addCommandBuffer(this.currentCmdBuffer);

        this.currentCmdBuffer = null;
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
}
