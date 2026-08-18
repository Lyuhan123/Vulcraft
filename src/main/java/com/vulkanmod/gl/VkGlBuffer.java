package com.yuhan123.vulkanmod.gl;

import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL21.*;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER;

// TODO: This class is only used to emulate a CPU buffer for texture copying purposes
//  any other use is not supported
public class VkGlBuffer {
    private static int ID_COUNTER = 1;
    private static final Int2ReferenceOpenHashMap<VkGlBuffer> map = new Int2ReferenceOpenHashMap<>();
    private static int boundId = 0;
    private static VkGlBuffer boundBuffer;

    private static VkGlBuffer pixelPackBufferBound;
    private static VkGlBuffer pixelUnpackBufferBound;
    private static VkGlBuffer arrayBufferBound;
    // The last VBO that received glBufferData; used as a fallback by the draw
    // path when the bind state could not be tracked (e.g. chunk VBO rendering).
    private static VkGlBuffer lastUploadedArrayBuffer;

    public static final int GL_ARRAY_BUFFER = 34962; // GL_ARRAY_BUFFER

    public static int glGenBuffers() {
        int id = ID_COUNTER;
        map.put(id, new VkGlBuffer(id));
        ID_COUNTER++;
        return id;
    }

    private static int bindLogs = 0;

    public static void glBindBuffer(int target, int buffer) {
        if (bindLogs < 12) {

            bindLogs++;
        }

        boundId = buffer;
        VkGlBuffer glBuffer = map.get(buffer);

        if (buffer > 0 && glBuffer == null)
            throw new NullPointerException("bound texture is null");

        if (glBuffer != null) {
            glBuffer.target = target;
        }

        switch (target) {
            case GL_ARRAY_BUFFER -> arrayBufferBound = glBuffer;
            case GL_PIXEL_PACK_BUFFER -> pixelPackBufferBound = glBuffer;
            case GL_PIXEL_UNPACK_BUFFER -> pixelUnpackBufferBound = glBuffer;
            default -> throw new IllegalStateException("Unexpected value: " + target);
        }
    }

    public static void glBufferData(int target, ByteBuffer byteBuffer, int usage) {
        if (target == GL_ARRAY_BUFFER) {
            VkGlBuffer glBuffer = arrayBufferBound;
            if (glBuffer != null) {
                glBuffer.copyData(byteBuffer);
                lastUploadedArrayBuffer = glBuffer;
            }
            return;
        }

        checkTarget(target);

        // TODO

        pixelUnpackBufferBound = boundBuffer;
    }

    public static void glBufferData(int target, long size, int usage) {
        VkGlBuffer buffer = switch (target) {
            case GL_PIXEL_PACK_BUFFER -> pixelPackBufferBound;
            case GL_PIXEL_UNPACK_BUFFER -> pixelUnpackBufferBound;
            default -> throw new IllegalStateException("Unexpected value: " + target);
        };

        buffer.allocate((int) size);
    }

    public static ByteBuffer glMapBuffer(int target, int access) {
        VkGlBuffer buffer = switch (target) {
            case GL_PIXEL_PACK_BUFFER -> pixelPackBufferBound;
            case GL_PIXEL_UNPACK_BUFFER -> pixelUnpackBufferBound;
            default -> throw new IllegalStateException("Unexpected value: " + target);
        };

        ByteBuffer mappedBuffer = buffer.data;
        mappedBuffer.position(0);
        return mappedBuffer;
    }

    public static boolean glUnmapBuffer(int i) {
        return true;
    }

    public static void glDeleteBuffers(IntBuffer intBuffer) {
        for (int i = intBuffer.position(); i < intBuffer.limit(); i++) {
            glDeleteBuffers(intBuffer.get(i));
        }
    }

    public static void glDeleteBuffers(int id) {
        var buffer = map.remove(id);

        if (buffer != null)
            buffer.freeData();

        if (arrayBufferBound == buffer)
            arrayBufferBound = null;
        if (lastUploadedArrayBuffer == buffer)
            lastUploadedArrayBuffer = null;
    }

    public static VkGlBuffer getPixelUnpackBufferBound() {
        return pixelUnpackBufferBound;
    }

    public static VkGlBuffer getPixelPackBufferBound() {
        return pixelPackBufferBound;
    }

    public static VkGlBuffer getArrayBufferBound() {
        return arrayBufferBound;
    }

    public static VkGlBuffer getLastUploadedArrayBuffer() {
        return lastUploadedArrayBuffer;
    }

    private void copyData(ByteBuffer src) {
        if (this.data == null || this.data.capacity() < src.remaining()) {
            if (this.data != null)
                freeData();
            this.data = MemoryUtil.memAlloc(Math.max(16, src.remaining()));
        }
        int pos = src.position();
        src.position(0);
        this.data.position(0);
        MemoryUtil.memCopy(MemoryUtil.memAddress(src), MemoryUtil.memAddress(this.data), src.remaining());
        this.data.limit(src.remaining());
        src.position(pos);
    }

    private static void checkTarget(int target) {
        if (target != GL_PIXEL_UNPACK_BUFFER && target != GL_PIXEL_PACK_BUFFER)
            throw new IllegalArgumentException("target %d not supported".formatted(target));
    }

    int id;
    int target;

    ByteBuffer data;

    public VkGlBuffer(int id) {
        this.id = id;
    }

    private void allocate(int size) {
        if (this.data != null)
            this.freeData();

        this.data = MemoryUtil.memAlloc(size);
    }

    public ByteBuffer getData() {
        return this.data;
    }

    private void freeData() {
        MemoryUtil.memFree(data);
    }

}
