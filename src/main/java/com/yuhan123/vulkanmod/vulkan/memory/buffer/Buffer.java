package com.yuhan123.vulkanmod.vulkan.memory.buffer;

import com.yuhan123.vulkanmod.vulkan.memory.MemoryManager;
import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import com.yuhan123.vulkanmod.vulkan.memory.MemoryType;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.system.libc.LibCString.nmemcpy;

import java.nio.ByteBuffer;

public class Buffer {
    public final MemoryType type;
    public final int usage;

    protected long id;
    protected long allocation;

    protected long bufferSize;
    protected long usedBytes;
    protected long offset;

    protected long dataPtr;

    public Buffer(int usage, MemoryType type) {
        this.usage = usage;
        this.type = type;
    }

    public void createBuffer(long bufferSize) {
        this.type.createBuffer(this, bufferSize);

        if (this.type.mappable()) {
            this.dataPtr = MemoryManager.getInstance().mapPointer(this.allocation);
        }
    }

    public void resizeBuffer(long newSize) {
        // A resize used to silently drop everything already written:
        // createBuffer() allocates a brand-new VkBuffer, so slices recorded
        // before the resize survived only as offsets into a buffer full of
        // zeroes - which showed up as black/white draws rather than as an
        // error. The old allocation is still alive at this point
        // (addToFreeable only queues a BufferInfo snapshot for a later
        // freeBuffers()), so carry the used range across before the fields
        // are replaced.
        final long oldPtr = this.dataPtr;
        final long oldUsed = this.usedBytes;

        MemoryManager.getInstance().addToFreeable(this);
        this.createBuffer(newSize);

        if (oldPtr != 0L && oldUsed > 0L && this.dataPtr != 0L) {
            nmemcpy(this.dataPtr, oldPtr, oldUsed);
        }
    }

    public void copyBuffer(ByteBuffer byteBuffer, int size) {
        if (size > this.bufferSize - this.usedBytes) {
            resizeBuffer((this.bufferSize + size) * 2);
        }

        this.type.copyToBuffer(this, byteBuffer, size, 0, this.usedBytes);
        FrameProfiler.onVertexCopy(size);
        this.offset = this.usedBytes;
        this.usedBytes += size;
    }

    public void copyBuffer(ByteBuffer byteBuffer, int size, int dstOffset) {
        if (size > this.bufferSize - dstOffset) {
            resizeBuffer((this.bufferSize + size) * 2);
        }

        this.type.copyToBuffer(this, byteBuffer, size, 0, dstOffset);
        FrameProfiler.onVertexCopy(size);
        this.offset = dstOffset;
        this.usedBytes = dstOffset + size;
    }

    public void scheduleFree() {
        MemoryManager.getInstance().addToFreeable(this);
    }

    public void reset() {
        usedBytes = 0;
    }

    public long getAllocation() {
        return allocation;
    }

    public long getUsedBytes() {
        return usedBytes;
    }

    public long getOffset() {
        return offset;
    }

    public long getId() {
        return id;
    }

    public long getBufferSize() {
        return bufferSize;
    }

    public long getDataPtr() {
        return dataPtr;
    }

    public void setBufferSize(long size) {
        this.bufferSize = size;
    }

    public void setId(long id) {
        this.id = id;
    }

    public void setAllocation(long allocation) {
        this.allocation = allocation;
    }

    public BufferInfo getBufferInfo() {
        return new BufferInfo(this.id, this.allocation, this.bufferSize, this.type.getType());
    }

    // Plain class instead of a record: the Cleanroom dev classloader failed to load
    // the record's nested class at runtime (ClassNotFoundException on first resize).
    public static class BufferInfo {
        public final long id;
        public final long allocation;
        public final long bufferSize;
        public final MemoryType.Type type;

        public BufferInfo(long id, long allocation, long bufferSize, MemoryType.Type type) {
            this.id = id;
            this.allocation = allocation;
            this.bufferSize = bufferSize;
            this.type = type;
        }

        public long id() { return id; }
        public long allocation() { return allocation; }
        public long bufferSize() { return bufferSize; }
        public MemoryType.Type type() { return type; }
    }
}
