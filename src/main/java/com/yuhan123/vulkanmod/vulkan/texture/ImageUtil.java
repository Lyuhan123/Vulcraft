package com.yuhan123.vulkanmod.vulkan.texture;

import com.yuhan123.vulkanmod.render.texture.ImageUploadHelper;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.device.DeviceManager;
import com.yuhan123.vulkanmod.vulkan.memory.MemoryManager;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.Buffer;
import com.yuhan123.vulkanmod.vulkan.queue.CommandPool;
import com.yuhan123.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public abstract class ImageUtil {

    /**
     * Regions packed into one {@code vkCmdCopyBufferToImage}. The merged atlas
     * batch can carry thousands during the initial load, and an unbounded region
     * count means an equally large native struct array per call; capping keeps
     * both the allocation and the driver's call size bounded.
     */
    private static final int MAX_REGIONS_PER_COPY = 512;

    public static void copyBufferToImageCmd(MemoryStack stack, VkCommandBuffer commandBuffer, long buffer, long image,
                                            int mipLevel, int width, int height, int xOffset, int yOffset,
                                            int bufferOffset, int bufferRowLenght, int bufferImageHeight) {
        // Split (pass 11): `cpMs` read ~6.9 us per call for a 315-byte upload,
        // which is far too much for the copy. These two halves have opposite
        // fixes - struct churn is fixable by reusing the struct, a slow driver
        // entry point is not - so they are separated before anything is rewritten.
        final long __setup = com.yuhan123.vulkanmod.render.util.FrameProfiler.texStart();

        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.bufferOffset(bufferOffset);
        region.bufferRowLength(bufferRowLenght);
        region.bufferImageHeight(bufferImageHeight);
        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        region.imageSubresource().mipLevel(mipLevel);
        region.imageSubresource().baseArrayLayer(0);
        region.imageSubresource().layerCount(1);
        region.imageOffset().set(xOffset, yOffset, 0);
        region.imageExtent(VkExtent3D.calloc(stack).set(width, height, 1));

        com.yuhan123.vulkanmod.render.util.FrameProfiler.addTexStage(
                __setup, com.yuhan123.vulkanmod.render.util.FrameProfiler.TEX_CP_SETUP);

        final long __cmd = com.yuhan123.vulkanmod.render.util.FrameProfiler.texStart();
        vkCmdCopyBufferToImage(commandBuffer, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        com.yuhan123.vulkanmod.render.util.FrameProfiler.addTexStage(
                __cmd, com.yuhan123.vulkanmod.render.util.FrameProfiler.TEX_CP_CMD);
    }

    /**
     * Multi-region merge of the atlas sub-uploads: TRIED AND REVERTED, do not
     * re-enable without solving the staging lifetime first.
     *
     * <p>Deferring the copies to the end of the upload batch is not safe here:
     * the staging buffer's regions were sized for a batch that recorded each
     * copy immediately, so queueing them made the staging buffer grow mid-batch
     * and invalidate the offsets the earlier regions referenced. Measured with
     * it on: texUp went 0.392 -> 4.960 ms/frame, cpSetup 0.020 -> 8.354 ms
     * (a heap struct allocation per call), plus a 487 ms single-upload stall.
     * Left here only as a record; {@code ImageUploadHelper} no longer queues.
     */
    public static void copyBufferToImageCmdMulti(MemoryStack stack, VkCommandBuffer commandBuffer,
                                                 long buffer, long image, int[] regions, int regionCount) {
        final long __setup = com.yuhan123.vulkanmod.render.util.FrameProfiler.texStart();

        int done = 0;
        while (done < regionCount) {
            final int chunk = Math.min(regionCount - done, MAX_REGIONS_PER_COPY);

            try (VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(chunk);
                 VkExtent3D extent = VkExtent3D.calloc()) {
                for (int i = 0; i < chunk; ++i) {
                    final int b = (done + i) * 9;
                    VkBufferImageCopy r = region.get(i);
                    r.bufferOffset(regions[b + 1]);
                    r.bufferRowLength(regions[b + 2]);
                    r.bufferImageHeight(regions[b + 3]);
                    r.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                    r.imageSubresource().mipLevel(regions[b]);
                    r.imageSubresource().baseArrayLayer(0);
                    r.imageSubresource().layerCount(1);
                    r.imageOffset().set(regions[b + 4], regions[b + 5], 0);
                    extent.set(regions[b + 6], regions[b + 7], 1);
                    r.imageExtent(extent);
                }

                final long __cmd = com.yuhan123.vulkanmod.render.util.FrameProfiler.texStart();
                vkCmdCopyBufferToImage(commandBuffer, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
                com.yuhan123.vulkanmod.render.util.FrameProfiler.addTexStage(
                        __cmd, com.yuhan123.vulkanmod.render.util.FrameProfiler.TEX_CP_CMD);
            }

            done += chunk;
        }

        com.yuhan123.vulkanmod.render.util.FrameProfiler.addTexStage(
                __setup, com.yuhan123.vulkanmod.render.util.FrameProfiler.TEX_CP_SETUP);
    }

    public static void downloadTexture(VulkanImage image, long ptr) {
        // Texture uploads are batched; make sure any pending copy for this
        // image has been submitted before reading it back.
        ImageUploadHelper.INSTANCE.flushUploads();

        try (MemoryStack stack = stackPush()) {
            int prevLayout = image.getCurrentLayout();
            CommandPool.CommandBuffer commandBuffer = DeviceManager.getGraphicsQueue().beginCommands();
            image.transitionImageLayout(stack, commandBuffer.getHandle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            long imageSize = (long) image.width * image.height * image.formatSize;

            LongBuffer pStagingBuffer = stack.mallocLong(1);
            PointerBuffer pStagingAllocation = stack.pointers(0L);
            MemoryManager.getInstance().createBuffer(imageSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                                     VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                                                     pStagingBuffer, pStagingAllocation);

            copyImageToBufferCmd(stack, commandBuffer.getHandle(), pStagingBuffer.get(0), image.getId(), 0, image.width,
                              image.height, 0, 0, 0, 0, 0);
            image.transitionImageLayout(stack, commandBuffer.getHandle(), prevLayout);

            long fence = DeviceManager.getGraphicsQueue().submitCommands(commandBuffer);
            vkWaitForFences(DeviceManager.vkDevice, fence, true, VUtil.UINT64_MAX);

            MemoryManager.MapAndCopy(pStagingAllocation.get(0),
                                     (data) -> VUtil.memcpy(data.getByteBuffer(0, (int) imageSize), ptr));

            MemoryManager.freeBuffer(pStagingBuffer.get(0), pStagingAllocation.get(0));
        }
    }

    public static void copyImageToBuffer(VulkanImage image, Buffer buffer, int mipLevel,
                                         int width, int height, int xOffset, int yOffset,
                                         int bufferOffset, int bufferRowLength, int bufferImageHeight) {
        // Texture uploads are batched; make sure any pending copy for this
        // image has been submitted before reading it back.
        ImageUploadHelper.INSTANCE.flushUploads();

        try (MemoryStack stack = stackPush()) {
            int prevLayout = image.getCurrentLayout();
            CommandPool.CommandBuffer commandBuffer = DeviceManager.getGraphicsQueue().beginCommands();
            image.transitionImageLayout(stack, commandBuffer.getHandle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            copyImageToBufferCmd(stack, commandBuffer.getHandle(), buffer.getId(), image.getId(), mipLevel, width,
                                 height, xOffset, yOffset, bufferOffset, bufferRowLength, bufferImageHeight);
            image.transitionImageLayout(stack, commandBuffer.getHandle(), prevLayout);

            long fence = DeviceManager.getGraphicsQueue().submitCommands(commandBuffer);
            vkWaitForFences(DeviceManager.vkDevice, fence, true, VUtil.UINT64_MAX);
        }
    }

    public static void copyImageToBufferCmd(MemoryStack stack, VkCommandBuffer commandBuffer, long buffer, long image,
                                            int mipLevel, int width, int height, int xOffset, int yOffset, int bufferOffset,
                                            int bufferRowLength, int bufferImageHeight) {
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.bufferOffset(bufferOffset);
        region.bufferRowLength(bufferRowLength);
        region.bufferImageHeight(bufferImageHeight);
        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        region.imageSubresource().mipLevel(mipLevel);
        region.imageSubresource().baseArrayLayer(0);
        region.imageSubresource().layerCount(1);
        region.imageOffset().set(xOffset, yOffset, 0);
        region.imageExtent().set(width, height, 1);

        vkCmdCopyImageToBuffer(commandBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, region);
    }

    public static void blitFramebuffer(VulkanImage dstImage, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1) {
        try (MemoryStack stack = stackPush()) {

            VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

            Renderer.getInstance().endRenderPass(commandBuffer);

            dstImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            // TODO: hardcoded srcImage
            VulkanImage srcImage = Renderer.getInstance().getSwapChain().getColorAttachment();

            srcImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.srcOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
            blit.srcOffsets(1, VkOffset3D.calloc(stack).set(srcImage.width, srcImage.height, 1));
            blit.srcSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);

            blit.dstOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
            blit.dstOffsets(1, VkOffset3D.calloc(stack).set(dstImage.width, dstImage.height, 1));
            blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0)
                .layerCount(1);

            vkCmdBlitImage(commandBuffer, srcImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                           dstImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_LINEAR);

            dstImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            Renderer.getInstance().getMainPass().rebindMainTarget();

        }
    }

    /**
     * Builds the mip chain by successive {@code vkCmdBlitImage} halvings.
     *
     * <p><b>LAYOUT CORRECTNESS - driver-fatal on NVIDIA.</b> Each blit reads its
     * source level as {@code TRANSFER_SRC_OPTIMAL}; the final level stays
     * {@code TRANSFER_DST_OPTIMAL}. The whole-chain final transition MUST use the
     * matching per-group oldLayout, otherwise the barrier's oldLayout disagrees
     * with the level's real layout and {@code VUID-VkImageMemoryBarrier-oldLayout-01197}
     * fires. On an RTX 3080 this escalates to a driver access violation
     * (EXCEPTION_ACCESS_VIOLATION inside nvoglv64.dll) that kills the JVM at the
     * title screen. Intel Iris Xe tolerated the mismatch, which is why this was
     * previously mis-scoped as "visual-only" and the correct version was
     * reverted. Keep this correct: the NVIDIA crash depends on it.
     */
    public static void generateMipmaps(VulkanImage image) {
        // Base level must be uploaded before blitting mip levels.
        ImageUploadHelper.INSTANCE.flushUploads();

        try (MemoryStack stack = stackPush()) {

            CommandPool.CommandBuffer commandBuffer = DeviceManager.getGraphicsQueue().beginCommands();

            // The chain is rebuilt on a texture that has already been uploaded,
            // so by now it has been handed back to the sampler and sits in
            // SHADER_READ_ONLY. Move it to TRANSFER_DST first - the per-level
            // barriers below all assume that as their source layout.
            image.transitionImageLayout(stack, commandBuffer.getHandle(),
                                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            for (int level = 1; level < image.mipLevels; level++) {

                VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
                barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                barrier.newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                barrier.image(image.getId());
                barrier.subresourceRange().baseMipLevel(level - 1);
                barrier.subresourceRange().levelCount(1);
                barrier.subresourceRange().baseArrayLayer(0);
                barrier.subresourceRange().layerCount(VK_REMAINING_ARRAY_LAYERS);
                barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);

                vkCmdPipelineBarrier(commandBuffer.getHandle(),
                                     VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                     0, null, null, barrier);

                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.srcOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
                blit.srcOffsets(1, VkOffset3D.calloc(stack).set(
                        image.width >> (level - 1), image.height >> (level - 1), 1));
                blit.srcSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(level - 1)
                    .baseArrayLayer(0)
                    .layerCount(1);

                blit.dstOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
                blit.dstOffsets(1, VkOffset3D.calloc(stack).set(
                        image.width >> level, image.height >> level, 1));
                blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).baseArrayLayer(0)
                    .layerCount(1);

                vkCmdBlitImage(commandBuffer.getHandle(), image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                               image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_LINEAR);
            }

            // Final transition to SHADER_READ_ONLY. The blitted source levels
            // (0 .. mipLevels-2) are TRANSFER_SRC_OPTIMAL; the last level
            // (mipLevels-1) is TRANSFER_DST_OPTIMAL. Use one barrier per group
            // with the CORRECT oldLayout - a single whole-chain barrier with
            // oldLayout=TRANSFER_DST_OPTIMAL is a layout mismatch
            // (VUID-VkImageMemoryBarrier-oldLayout-01197) that crashes NVIDIA.
            if (image.mipLevels > 1) {
                VkImageMemoryBarrier.Buffer toReadSrc = VkImageMemoryBarrier.calloc(1, stack);
                toReadSrc.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
                toReadSrc.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                toReadSrc.newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                toReadSrc.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                toReadSrc.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                toReadSrc.image(image.getId());
                toReadSrc.subresourceRange().baseMipLevel(0);
                toReadSrc.subresourceRange().levelCount(image.mipLevels - 1);
                toReadSrc.subresourceRange().baseArrayLayer(0);
                toReadSrc.subresourceRange().layerCount(VK_REMAINING_ARRAY_LAYERS);
                toReadSrc.subresourceRange().aspectMask(image.aspect);
                toReadSrc.srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
                toReadSrc.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                vkCmdPipelineBarrier(commandBuffer.getHandle(),
                                     VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                                     0, null, null, toReadSrc);
            }

            // Last level: TRANSFER_DST_OPTIMAL -> SHADER_READ_ONLY_OPTIMAL.
            VkImageMemoryBarrier.Buffer toReadDst = VkImageMemoryBarrier.calloc(1, stack);
            toReadDst.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            toReadDst.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            toReadDst.newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            toReadDst.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            toReadDst.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            toReadDst.image(image.getId());
            toReadDst.subresourceRange().baseMipLevel(image.mipLevels - 1);
            toReadDst.subresourceRange().levelCount(1);
            toReadDst.subresourceRange().baseArrayLayer(0);
            toReadDst.subresourceRange().layerCount(VK_REMAINING_ARRAY_LAYERS);
            toReadDst.subresourceRange().aspectMask(image.aspect);
            toReadDst.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            toReadDst.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(commandBuffer.getHandle(),
                                 VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                                 0, null, null, toReadDst);

            // Update the whole-image layout tracker directly - do NOT call
            // readOnlyLayout() here: it emits a whole-chain barrier with the
            // stale TRANSFER_DST oldLayout (the very mismatch we just fixed)
            // on the renderer's command buffer.
            image.setCurrentLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            long fence = DeviceManager.getGraphicsQueue().submitCommands(commandBuffer);

            vkWaitForFences(DeviceManager.vkDevice, fence, true, VUtil.UINT64_MAX);
        }
    }
}
