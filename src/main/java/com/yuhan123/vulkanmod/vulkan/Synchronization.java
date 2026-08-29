package com.yuhan123.vulkanmod.vulkan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import com.yuhan123.vulkanmod.vulkan.queue.CommandPool;
import com.yuhan123.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class Synchronization {
    private static final int ALLOCATION_SIZE = 50;

    public static final Synchronization INSTANCE = new Synchronization(ALLOCATION_SIZE);

    private final LongBuffer fences;
    private int idx = 0;

    private ObjectArrayList<CommandPool.CommandBuffer> commandBuffers = new ObjectArrayList<>();

    Synchronization(int allocSize) {
        this.fences = MemoryUtil.memAllocLong(allocSize);
    }

    public synchronized void addCommandBuffer(CommandPool.CommandBuffer commandBuffer) {
        this.addFence(commandBuffer.getFence());
        this.commandBuffers.add(commandBuffer);
    }

    public synchronized void addFence(long fence) {
        if (idx == ALLOCATION_SIZE)
            reapSignaled();

        // Still full: nothing has completed yet, so we have to stall.
        if (idx == ALLOCATION_SIZE)
            waitFences();

        fences.put(idx, fence);
        idx++;
    }

    public synchronized void waitFences() {
        if (idx == 0)
            return;

        VkDevice device = Vulkan.getVkDevice();

        fences.limit(idx);

        vkWaitForFences(device, fences, true, VUtil.UINT64_MAX);

        this.commandBuffers.forEach(CommandPool.CommandBuffer::reset);
        this.commandBuffers.clear();

        fences.limit(ALLOCATION_SIZE);
        idx = 0;
    }

    /**
     * Recycle every upload command buffer whose fence the GPU has already
     * signaled, without ever blocking.
     * <p>
     * Uploads are submitted once per frame and their command buffers stay
     * checked out until the GPU is done. Blocking on them would serialize CPU
     * and GPU, so instead we poll the fences and only reclaim the ones that are
     * already complete; the rest are kept and retried on the next frame.
     */
    public synchronized void reapSignaled() {
        if (idx == 0)
            return;

        VkDevice device = Vulkan.getVkDevice();

        final int oldIdx = idx;
        int n = 0;

        for (int i = 0; i < oldIdx; ++i) {
            final long fence = fences.get(i);
            final CommandPool.CommandBuffer commandBuffer = commandBuffers.get(i);

            if (vkGetFenceStatus(device, fence) == VK_SUCCESS) {
                commandBuffer.reset();
                continue;
            }

            fences.put(n, fence);
            commandBuffers.set(n, commandBuffer);
            ++n;
        }

        if (n < oldIdx)
            commandBuffers.subList(n, oldIdx).clear();

        idx = n;
    }

    public static void waitFence(long fence) {
        VkDevice device = Vulkan.getVkDevice();

        vkWaitForFences(device, fence, true, VUtil.UINT64_MAX);
    }

    public static boolean checkFenceStatus(long fence) {
        VkDevice device = Vulkan.getVkDevice();
        return vkGetFenceStatus(device, fence) == VK_SUCCESS;
    }

}
