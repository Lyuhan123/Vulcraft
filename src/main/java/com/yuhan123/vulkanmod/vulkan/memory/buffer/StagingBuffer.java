package com.yuhan123.vulkanmod.vulkan.memory.buffer;

//import com.yuhan123.vulkanmod.render.chunk.buffer.UploadManager;
//import com.yuhan123.vulkanmod.render.chunk.util.Util;
import com.yuhan123.vulkanmod.render.texture.ImageUploadHelper;
import com.yuhan123.vulkanmod.vulkan.Synchronization;
import com.yuhan123.vulkanmod.vulkan.memory.MemoryTypes;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.system.libc.LibCString.nmemcpy;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;

public class StagingBuffer extends Buffer {
    private static final long DEFAULT_SIZE = 64 * 1024 * 1024;

    public StagingBuffer() {
        this(DEFAULT_SIZE);
    }

    public StagingBuffer(long size) {
        super(VK_BUFFER_USAGE_TRANSFER_SRC_BIT, MemoryTypes.HOST_MEM);
        this.createBuffer(size);
    }

    public void copyBuffer(int size, ByteBuffer byteBuffer) {
        this.copyBuffer(size, MemoryUtil.memAddress(byteBuffer));
    }

    public void copyBuffer(int size, long scrPtr) {
        if (size > this.bufferSize) {
            throw new IllegalArgumentException("Upload size is greater than staging buffer size.");
        }

        if (size > this.bufferSize - this.usedBytes) {
            submitUploads();
        }

        nmemcpy(this.dataPtr + this.usedBytes, scrPtr, size);

        this.offset = this.usedBytes;
        this.usedBytes += size;
    }

    public void align(int alignment) {
        // Uploads are batched: accumulate in the staging buffer and flush only
        // when the aligned offset would overflow it. The actual submit + wait
        // happens once per frame in Renderer.waitFences().
        long alignedOffset = (this.usedBytes + alignment - 1) / alignment * alignment;

        if (alignedOffset > this.bufferSize) {
            submitUploads();
            alignedOffset = 0;
        }

        this.usedBytes = alignedOffset;
    }

    private void submitUploads() {
        // Submit and wait all recorded uploads before resetting the buffer
//        UploadManager.INSTANCE.submitUploads();
        ImageUploadHelper.INSTANCE.submitCommands();
        Synchronization.INSTANCE.waitFences();

        this.reset();
    }
}
