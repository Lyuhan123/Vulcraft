package com.yuhan123.vulkanmod.vulkan.memory.buffer;

import com.yuhan123.vulkanmod.vulkan.memory.MemoryType;
import com.yuhan123.vulkanmod.vulkan.memory.MemoryTypes;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

public class VertexBuffer extends Buffer {

    public VertexBuffer(int size) {
        this(size, MemoryTypes.HOST_MEM);
    }

    public VertexBuffer(int size, MemoryType type) {
        super(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, type);
        this.createBuffer(size);
    }

}
