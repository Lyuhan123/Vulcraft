package com.yuhan123.vulkanmod.vulkan.memory.buffer;

import com.yuhan123.vulkanmod.vulkan.device.DeviceManager;
import com.yuhan123.vulkanmod.vulkan.memory.MemoryType;

import static com.yuhan123.vulkanmod.vulkan.util.VUtil.align;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;

public class UniformBuffer extends Buffer {
    private final static int MIN_OFFSET_ALIGNMENT = (int) DeviceManager.deviceProperties.limits().minUniformBufferOffsetAlignment();

    /**
     * Incremented on every slice handed out. Consumers that want to know whether
     * a slice they wrote is still the last thing written into this buffer compare
     * the value they saw against this one - an exact signal that no other
     * pipeline has written since, which offset arithmetic alone cannot give.
     */
    private int writeSeq;

    public static int getAlignedSize(int uploadSize) {
        return align(uploadSize, MIN_OFFSET_ALIGNMENT);
    }

    public UniformBuffer(int size, MemoryType memoryType) {
        // STORAGE_BUFFER_BIT lets the chunk draw path bind this same buffer as a
        // read-only SectionData SSBO (gl_InstanceIndex-indexed per-section
        // offsets) instead of a UBO; the bit is a harmless superset for the
        // normal UBO consumers.
        super(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, memoryType);
        this.createBuffer(size);
    }

    public void checkCapacity(int size) {
        if (size > this.bufferSize - this.usedBytes) {
            resizeBuffer((this.bufferSize + size) * 2);
        }
    }

    public int getWriteSeq() {
        return this.writeSeq;
    }

    public void updateOffset(int alignedSize) {
        usedBytes += alignedSize;
        this.writeSeq++;
    }

    public long getPointer() {
        return this.dataPtr + usedBytes;
    }
}
