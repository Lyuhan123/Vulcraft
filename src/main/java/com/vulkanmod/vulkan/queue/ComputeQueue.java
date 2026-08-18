package com.yuhan123.vulkanmod.vulkan.queue;

import org.lwjgl.system.MemoryStack;

public class ComputeQueue extends Queue {

    public ComputeQueue(MemoryStack stack, int familyIndex) {
        super(stack, familyIndex);
    }
}
