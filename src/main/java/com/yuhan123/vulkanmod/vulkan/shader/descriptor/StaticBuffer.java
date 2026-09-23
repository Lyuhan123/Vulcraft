package com.yuhan123.vulkanmod.vulkan.shader.descriptor;

import com.yuhan123.vulkanmod.vulkan.memory.buffer.Buffer;

/**
 * A descriptor binding backed by a whole buffer the caller owns and writes.
 *
 * <p>Unlike {@link UBO} this has no field layout and no per-draw upload: the
 * contents are written directly by whoever owns the buffer. That is what the
 * per-instance matrix array needs - {@code vkCmdPushConstants} costs one native
 * call per chunk section, so the matrices instead live in a buffer the Drawer
 * writes with a plain memcpy and the shader indexes by {@code gl_InstanceIndex}.
 *
 * <p>The descriptor set is only rewritten when the bound buffer object changes,
 * which happens once per frame (each frame has its own matrix buffer), so
 * {@code needsUpdate} compares buffer ids exactly like it does for UBOs.
 */
public class StaticBuffer implements Descriptor {

    private final int binding;
    private final int stages;
    private final int type;
    private final int size;

    private Buffer buffer;

    public StaticBuffer(int binding, int stages, int type, int size) {
        this.binding = binding;
        this.stages = stages;
        this.type = type;
        this.size = size;
    }

    @Override
    public int getBinding() {
        return binding;
    }

    @Override
    public int getType() {
        return type;
    }

    @Override
    public int getStages() {
        return stages;
    }

    /** Bytes of the buffer the shader can index; used as the descriptor range. */
    public int getSize() {
        return size;
    }

    public Buffer getBuffer() {
        return buffer;
    }

    public void setBuffer(Buffer buffer) {
        this.buffer = buffer;
    }
}
