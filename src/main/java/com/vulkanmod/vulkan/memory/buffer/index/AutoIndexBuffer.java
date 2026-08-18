package com.yuhan123.vulkanmod.vulkan.memory.buffer.index;

import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.vulkan.memory.MemoryTypes;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

public class AutoIndexBuffer {
    public static final int U16_MAX_VERTEX_COUNT = 65536;
    public static final int QUAD_U16_MAX_INDEX_COUNT = U16_MAX_VERTEX_COUNT * 3 / 2;

    int vertexCount;
    int drawType;
    IndexBuffer indexBuffer;

    public AutoIndexBuffer(int vertexCount, int type) {
        this.drawType = type;

        createIndexBuffer(vertexCount);
    }

    private void createIndexBuffer(int vertexCount) {
        this.vertexCount = vertexCount;
        ByteBuffer buffer;

        IndexBuffer.IndexType indexType = IndexBuffer.IndexType.UINT16;

        if (vertexCount > U16_MAX_VERTEX_COUNT &&
            (this.drawType == DrawType.QUADS || this.drawType == DrawType.LINES))
        {
            indexType = IndexBuffer.IndexType.UINT32;
        }

        switch (this.drawType) {
            case DrawType.QUADS -> {
                if (indexType == IndexBuffer.IndexType.UINT16)
                    buffer = genQuadIndices(vertexCount);
                else {
                    buffer = genIntQuadIndices(vertexCount);
                }
            }
            case DrawType.TRIANGLE_FAN -> buffer = genTriangleFanIndices(vertexCount);
            case DrawType.TRIANGLE_STRIP -> buffer = genTriangleStripIndices(vertexCount);
            case DrawType.LINES -> buffer = genLinesIndices(vertexCount);
            case DrawType.DEBUG_LINE_STRIP -> buffer = genDebugLineStripIndices(vertexCount);
            default -> throw new IllegalArgumentException("Unsupported drawType: %s".formatted(this.drawType));
        }

        int size = buffer.capacity();
        this.indexBuffer = new IndexBuffer(size, MemoryTypes.GPU_MEM, indexType);
        this.indexBuffer.copyBuffer(buffer, buffer.remaining());

        MemoryUtil.memFree(buffer);
    }

    public void checkCapacity(int vertexCount) {
        if(vertexCount > this.vertexCount) {
            int newVertexCount = this.vertexCount * 2;
            VulkanMod.LOGGER.info("Reallocating AutoIndexBuffer from {} to {}", this.vertexCount, newVertexCount);

            this.indexBuffer.scheduleFree();
            createIndexBuffer(newVertexCount);
        }
    }

    public IndexBuffer getIndexBuffer() { return this.indexBuffer; }

    public void freeBuffer() {
        this.indexBuffer.scheduleFree();
    }
    public int getIndexCount(int vertexCount) {
        return getIndexCount(this.drawType, vertexCount);
    }

    public static int getIndexCount(int drawType, int vertexCount) {
        switch (drawType) {
            case DrawType.QUADS, DrawType.LINES -> {
                return vertexCount * 3 / 2;
            }
            case DrawType.TRIANGLE_FAN, DrawType.TRIANGLE_STRIP -> {
                return (vertexCount - 2) * 3;
            }
            case DrawType.DEBUG_LINE_STRIP -> {
                return (vertexCount - 1) * 2;
            }
            default -> throw new RuntimeException(String.format("unknown drawMode: %s", drawType));
        }
    }

    public static int maxVertexCount(int drawType, int maxIndexCount) {
        return switch (drawType) {
            case DrawType.QUADS, DrawType.LINES -> maxIndexCount * 3 / 2;

            default -> maxIndexCount;
        };
    }

    public static ByteBuffer genQuadIndices(int vertexCount) {
        int indexCount = vertexCount * 3 / 2;
        indexCount = roundUpToDivisible(indexCount, 6);

        ByteBuffer buffer = MemoryUtil.memAlloc(indexCount * Short.BYTES);
        ShortBuffer idxs = buffer.asShortBuffer();

        int j = 0;
        for(int i = 0; i < vertexCount; i += 4) {
            idxs.put(j + 0, (short) (i));
            idxs.put(j + 1, (short) (i + 1));
            idxs.put(j + 2, (short) (i + 2));
            idxs.put(j + 3, (short) (i));
            idxs.put(j + 4, (short) (i + 2));
            idxs.put(j + 5, (short) (i + 3));

            j += 6;
        }

        return buffer;
    }

    public static ByteBuffer genIntQuadIndices(int vertexCount) {
        int indexCount = vertexCount * 3 / 2;
        indexCount = roundUpToDivisible(indexCount, 6);

        ByteBuffer buffer = MemoryUtil.memAlloc(indexCount * Integer.BYTES);
        IntBuffer idxs = buffer.asIntBuffer();

        int j = 0;
        for(int i = 0; i < vertexCount; i += 4) {
            idxs.put(j + 0, (i));
            idxs.put(j + 1, (i + 1));
            idxs.put(j + 2, (i + 2));
            idxs.put(j + 3, (i));
            idxs.put(j + 4, (i + 2));
            idxs.put(j + 5, (i + 3));

            j += 6;
        }

        return buffer;
    }

    public static ByteBuffer genLinesIndices(int vertexCount) {
        int indexCount = vertexCount * 3 / 2;
        indexCount = roundUpToDivisible(indexCount, 6);

        ByteBuffer buffer = MemoryUtil.memAlloc(indexCount * Short.BYTES);
        ShortBuffer idxs = buffer.asShortBuffer();

        int j = 0;
        for(int i = 0; i < vertexCount; i += 4) {
            idxs.put(j + 0, (short) (i));
            idxs.put(j + 1, (short) (i + 1));
            idxs.put(j + 2, (short) (i + 2));
            idxs.put(j + 3, (short) (i + 3));
            idxs.put(j + 4, (short) (i + 2));
            idxs.put(j + 5, (short) (i + 1));

            j += 6;
        }

        return buffer;
    }

    public static ByteBuffer genTriangleFanIndices(int vertexCount) {
        int indexCount = (vertexCount - 2) * 3;
        ByteBuffer buffer = MemoryUtil.memAlloc(indexCount * Short.BYTES);
        ShortBuffer idxs = buffer.asShortBuffer();

        int j = 0;
        for (int i = 0; i < vertexCount - 2; ++i) {
            idxs.put(j + 0, (short) 0);
            idxs.put(j + 1, (short) (i + 1));
            idxs.put(j + 2, (short) (i + 2));

            j += 3;
        }

        return buffer;
    }

    public static ByteBuffer genTriangleStripIndices(int vertexCount) {
        int indexCount = (vertexCount - 2) * 3;

        ByteBuffer buffer = MemoryUtil.memAlloc(indexCount * Short.BYTES);
        ShortBuffer idxs = buffer.asShortBuffer();

        int j = 0;
        for (int i = 0; i < vertexCount - 2; ++i) {
            idxs.put(j + 0, (short) i);
            idxs.put(j + 1, (short) (i + 1));
            idxs.put(j + 2, (short) (i + 2));

            j += 3;
        }

        return buffer;
    }

    public static ByteBuffer genDebugLineStripIndices(int vertexCount) {
        int indexCount = (vertexCount - 1) * 2;

        ByteBuffer buffer = MemoryUtil.memAlloc(indexCount * Short.BYTES);
        ShortBuffer idxs = buffer.asShortBuffer();

        int j = 0;
        for (int i = 0; i < vertexCount - 1; ++i) {
            idxs.put(j + 0, (short) i);
            idxs.put(j + 1, (short) (i + 1));

            j += 2;
        }

        return buffer;
    }

    public static int roundUpToDivisible(int n, int d) {
        return ((n + d - 1) / d) * d;
    }

    public enum DrawType {;
        public static final int QUADS = 7;
        public static final int TRIANGLE_FAN = 6;
        public static final int TRIANGLE_STRIP = 5;
        public static final int DEBUG_LINE_STRIP = 3;
        public static final int DEBUG_LINES = 1;
        public static final int LINES = 1; // Emulates lines with quads

//        public final int n;

//        DrawType(int n) {
//            this.n = n;
//        }

        public static int getQuadIndexCount(int vertexCount) {
            return vertexCount * 3 / 2;
        }

        public static int getTriangleStripIndexCount(int vertexCount) {
            return (vertexCount - 2) * 3;
        }
    }
}
