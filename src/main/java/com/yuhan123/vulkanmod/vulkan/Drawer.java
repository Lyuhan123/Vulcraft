package com.yuhan123.vulkanmod.vulkan;

import net.minecraft.client.renderer.vertex.VertexFormat;
import com.yuhan123.vulkanmod.vulkan.memory.*;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.Buffer;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.UniformBuffer;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.VertexBuffer;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.index.AutoIndexBuffer;
import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
import com.yuhan123.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;

import static com.yuhan123.vulkanmod.vulkan.memory.buffer.index.AutoIndexBuffer.DrawType.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VkGeometryDataNV.TRIANGLES;

public class Drawer {
    private static final int INITIAL_VB_SIZE = 4000000;
    private static final int INITIAL_IB_SIZE = 1000000;
    private static final int INITIAL_UB_SIZE = 200000;

    private static final LongBuffer buffers = MemoryUtil.memAllocLong(1);
    private static final LongBuffer offsets = MemoryUtil.memAllocLong(1);
    private static final long pBuffers = MemoryUtil.memAddress0(buffers);
    private static final long pOffsets = MemoryUtil.memAddress0(offsets);

    private int framesNum;
    private VertexBuffer[] vertexBuffers;
    private IndexBuffer[] indexBuffers;

    private final AutoIndexBuffer quadsIndexBuffer;
    private final AutoIndexBuffer quadsIntIndexBuffer;
    private final AutoIndexBuffer linesIndexBuffer;
    private final AutoIndexBuffer debugLineStripIndexBuffer;
    private final AutoIndexBuffer triangleFanIndexBuffer;
    private final AutoIndexBuffer triangleStripIndexBuffer;

    private UniformBuffer[] uniformBuffers;

    private int currentFrame;

    // Last vertex/index buffer binding actually issued, with the binding epoch it
    // was issued in. Buffer bindings are command-buffer state, so an entry is
    // only trusted while the epoch still matches (see Renderer.getBindingEpoch).
    private int boundVertexEpoch = -1;
    private long boundVertexBufferId = 0;
    private long boundVertexBufferOffset = -1;

    private int boundIndexEpoch = -1;
    private long boundIndexBufferId = 0;
    private long boundIndexBufferOffset = -1;
    private int boundIndexBufferType = -1;

    public Drawer() {
        // Index buffers
        this.quadsIndexBuffer = new AutoIndexBuffer(AutoIndexBuffer.U16_MAX_VERTEX_COUNT, QUADS);
        this.quadsIntIndexBuffer = new AutoIndexBuffer(100000, QUADS);
        this.linesIndexBuffer = new AutoIndexBuffer(10000, LINES);
        this.debugLineStripIndexBuffer = new AutoIndexBuffer(10000, DEBUG_LINE_STRIP);
        this.triangleFanIndexBuffer = new AutoIndexBuffer(1000, TRIANGLE_FAN);
        this.triangleStripIndexBuffer = new AutoIndexBuffer(10000, TRIANGLE_STRIP);
    }

    public void setCurrentFrame(int currentFrame) {
        this.currentFrame = currentFrame;
    }

    public void createResources(int framesNum) {
        this.framesNum = framesNum;

        if (this.vertexBuffers != null) {
            Arrays.stream(this.vertexBuffers).iterator().forEachRemaining(
                    Buffer::scheduleFree
            );
        }
        this.vertexBuffers = new VertexBuffer[framesNum];
        Arrays.setAll(this.vertexBuffers, i -> new VertexBuffer(INITIAL_VB_SIZE, MemoryTypes.HOST_MEM));

        if (this.indexBuffers != null) {
            Arrays.stream(this.indexBuffers).iterator().forEachRemaining(
                    Buffer::scheduleFree
            );
        }
        this.indexBuffers = new IndexBuffer[framesNum];
        Arrays.setAll(this.indexBuffers, i -> new IndexBuffer(INITIAL_IB_SIZE, MemoryTypes.HOST_MEM));

        if (this.uniformBuffers != null) {
            Arrays.stream(this.uniformBuffers).iterator().forEachRemaining(
                    Buffer::scheduleFree
            );
        }
        this.uniformBuffers = new UniformBuffer[framesNum];
        Arrays.setAll(this.uniformBuffers, i -> new UniformBuffer(INITIAL_UB_SIZE, MemoryTypes.HOST_MEM));
    }

    public void resetBuffers(int currentFrame) {
        this.vertexBuffers[currentFrame].reset();
        this.indexBuffers[currentFrame].reset();
        this.uniformBuffers[currentFrame].reset();
    }

    public void draw(ByteBuffer vertexData, int mode, VertexFormat vertexFormat, int vertexCount) {
        draw(vertexData, null, mode, vertexFormat, vertexCount);
    }

    /**
     * Draw directly from a persistent vertex buffer (e.g. a 1.12.2 VBO that was
     * uploaded once). Avoids copying the CPU mirror into the per-frame vertex
     * buffer on every glDrawArrays - 1.12.2 re-issues the same VBO draws every
     * frame, which was the main CPU bottleneck.
     */
    public void draw(ByteBuffer vertexData, ByteBuffer indexData, int mode, VertexFormat vertexFormat, int vertexCount) {
        FrameProfiler.onCopiedDraw();
        VertexBuffer vertexBuffer = this.vertexBuffers[this.currentFrame];
        int size = vertexFormat.getSize() * vertexCount;
        vertexBuffer.copyBuffer(vertexData, size);

        if (indexData != null) {
            IndexBuffer indexBuffer = this.indexBuffers[this.currentFrame];
            indexBuffer.copyBuffer(indexData, indexData.remaining());

            int indexCount = vertexCount * 3 / 2;

            drawIndexed(vertexBuffer, indexBuffer, indexCount);
        }
        else {
            AutoIndexBuffer autoIndexBuffer = getAutoIndexBuffer(mode, vertexCount);

            if (autoIndexBuffer != null) {
                int indexCount = autoIndexBuffer.getIndexCount(vertexCount);
                // checkCapacity works in VERTEX counts, not index counts.
                autoIndexBuffer.checkCapacity(vertexCount);

                drawIndexed(vertexBuffer, autoIndexBuffer.getIndexBuffer(), indexCount);
            }
            else {
                draw(vertexBuffer, vertexCount);
            }
        }
    }

    public void drawIndexed(Buffer vertexBuffer, IndexBuffer indexBuffer, int indexCount) {
       drawIndexed(vertexBuffer, indexBuffer, indexCount, indexBuffer.indexType.value);
    }

    public void drawIndexed(Buffer vertexBuffer, Buffer indexBuffer, int indexCount, int indexType) {
        drawIndexed(vertexBuffer, vertexBuffer.getOffset(), indexBuffer, indexCount, indexType);
    }

    public void drawIndexed(Buffer vertexBuffer, long vertexOffset, Buffer indexBuffer, int indexCount, int indexType) {
        long __t = FrameProfiler.start();
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        // A vkCmdBindPipeline requested by apply()/bindPipeline() is issued here,
        // not at request time, so a state change between the request and this draw
        // coalesces into one bind instead of two (see Renderer.bindGraphicsPipeline).
        Renderer.getInstance().flushPipelineBind();

        bindVertexBuffer(commandBuffer, vertexBuffer, vertexOffset);
        bindIndexBuffer(commandBuffer, indexBuffer, indexType);

        long __c = FrameProfiler.start();
        vkCmdDrawIndexed(commandBuffer, indexCount, 1, 0, 0, 0);
        FrameProfiler.addCmd(FrameProfiler.CMD_DRAW_INDEXED, __c);
        FrameProfiler.onDraw();
        FrameProfiler.addDrawRecord(__t);
    }

    public void draw(VertexBuffer vertexBuffer, int vertexCount) {
        draw(vertexBuffer, vertexBuffer.getOffset(), vertexCount);
    }

    public void draw(VertexBuffer vertexBuffer, long vertexOffset, int vertexCount) {
        long __t = FrameProfiler.start();
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        Renderer.getInstance().flushPipelineBind();

        bindVertexBuffer(commandBuffer, vertexBuffer, vertexOffset);

        long __c = FrameProfiler.start();
        vkCmdDraw(commandBuffer, vertexCount, 1, 0, 0);
        FrameProfiler.addCmd(FrameProfiler.CMD_DRAW, __c);
        FrameProfiler.onDraw();
        FrameProfiler.addDrawRecord(__t);
    }

    /**
     * Binds a vertex buffer unless the same buffer at the same offset is already
     * bound in the current command buffer. The depth-prepass flow draws each
     * cutout chunk section twice from the same buffer, so this removes one bind
     * per chunk there.
     */
    private void bindVertexBuffer(VkCommandBuffer commandBuffer, Buffer vertexBuffer, long vertexOffset) {
        final long id = vertexBuffer.getId();

        if (boundVertexEpoch == Renderer.getBindingEpoch()
                && boundVertexBufferId == id && boundVertexBufferOffset == vertexOffset) {
            FrameProfiler.onVertexBindSkipped();
            return;
        }

        final long __vb = System.nanoTime();
        VUtil.UNSAFE.putLong(pBuffers, id);
        VUtil.UNSAFE.putLong(pOffsets, vertexOffset);
        long __c = FrameProfiler.start();
        nvkCmdBindVertexBuffers(commandBuffer, 0, 1, pBuffers, pOffsets);
        FrameProfiler.addCmd(FrameProfiler.CMD_BIND_VERTEX, __c);
        FrameProfiler.addVertexBind(__vb);

        boundVertexEpoch = Renderer.getBindingEpoch();
        boundVertexBufferId = id;
        boundVertexBufferOffset = vertexOffset;
    }

    /**
     * Draw straight out of a persistent vertex buffer (a 1.12.2 VBO uploaded
     * once) at the given byte offset. No vertex data is copied this frame.
     */
    public void drawPersistent(VertexBuffer vertexBuffer, int byteOffset, int mode, VertexFormat vertexFormat, int vertexCount) {
        FrameProfiler.onPersistentDraw();
        FrameProfiler.logDrawState("block");
        AutoIndexBuffer autoIndexBuffer = getAutoIndexBuffer(mode, vertexCount);

        if (autoIndexBuffer != null) {
            int indexCount = autoIndexBuffer.getIndexCount(vertexCount);
            // checkCapacity works in VERTEX counts, not index counts.
            autoIndexBuffer.checkCapacity(vertexCount);

            final int stride = vertexFormat.getSize();
            // The offset has to be a whole number of vertices or it cannot be
            // expressed as firstVertex; display-list captures and chunk VBOs are
            // always stride-aligned, so this only guards a mis-fetch.
            if (byteOffset % stride == 0) {
                // Bind the whole buffer at 0 and carry the offset in the draw's
                // vertexOffset. Previously every replayed draw bound the buffer
                // at its own byte offset, so the vertex-bind dedup below could
                // never hit and each of the ~3000 display-list draws a frame
                // issued its own vkCmdBindVertexBuffers. Binding at 0 turns
                // every draw after the first of a list into a dedup hit.
                drawIndexedAt(vertexBuffer, byteOffset, stride,
                              autoIndexBuffer.getIndexBuffer(), indexCount,
                              autoIndexBuffer.getIndexBuffer().indexType.value, 0);
            }
            else {
                drawIndexed(vertexBuffer, byteOffset, autoIndexBuffer.getIndexBuffer(), indexCount,
                            autoIndexBuffer.getIndexBuffer().indexType.value);
            }
        }
        else {
            draw(vertexBuffer, byteOffset, vertexCount);
        }
    }

    /**
     * Indexed draw with the buffer bound at offset 0 and the section's own
     * byte position carried as the draw command's {@code vertexOffset}, so
     * consecutive draws out of the same buffer turn into vertex-bind dedup
     * hits. {@code firstInstance} is always 0 on this path: the shader takes
     * the MVP as a push constant and never reads {@code gl_InstanceIndex}.
     */
    private void drawIndexedAt(VertexBuffer vertexBuffer, long byteOffset, int vertexStride,
                               Buffer indexBuffer, int indexCount, int indexType, int firstInstance) {
        long __t = FrameProfiler.start();
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        Renderer.getInstance().flushPipelineBind();
        bindVertexBuffer(commandBuffer, vertexBuffer, 0L);
        bindIndexBuffer(commandBuffer, indexBuffer, indexType);

        long __c = FrameProfiler.start();
        vkCmdDrawIndexed(commandBuffer, indexCount, 1, 0, (int) (byteOffset / vertexStride), firstInstance);
        FrameProfiler.addCmd(FrameProfiler.CMD_DRAW_INDEXED, __c);
        FrameProfiler.onDraw();
        FrameProfiler.addDrawRecord(__t);
    }

    /**
     * Binds an index buffer unless the identical binding is already in effect.
     *
     * Every chunk section of a layer draws quads, and quads are expanded through
     * one shared generated index buffer, so without this check each chunk issued
     * a vkCmdBindIndexBuffer that bound the exact same buffer, offset and type as
     * the previous draw.
     */
    public void bindIndexBuffer(VkCommandBuffer commandBuffer, Buffer indexBuffer, int indexType) {
        final long id = indexBuffer.getId();
        final long offset = indexBuffer.getOffset();

        if (boundIndexEpoch == Renderer.getBindingEpoch()
                && boundIndexBufferId == id && boundIndexBufferOffset == offset
                && boundIndexBufferType == indexType) {
            FrameProfiler.onIndexBindSkipped();
            return;
        }

        long __c = FrameProfiler.start();
        vkCmdBindIndexBuffer(commandBuffer, id, offset, indexType);
        FrameProfiler.addCmd(FrameProfiler.CMD_BIND_INDEX, __c);

        boundIndexEpoch = Renderer.getBindingEpoch();
        boundIndexBufferId = id;
        boundIndexBufferOffset = offset;
        boundIndexBufferType = indexType;
    }

    public void cleanUpResources() {
        Buffer buffer;
        for (int i = 0; i < this.framesNum; ++i) {
            buffer = this.vertexBuffers[i];
            MemoryManager.freeBuffer(buffer.getId(), buffer.getAllocation());

            buffer = this.indexBuffers[i];
            MemoryManager.freeBuffer(buffer.getId(), buffer.getAllocation());

            buffer = this.uniformBuffers[i];
            MemoryManager.freeBuffer(buffer.getId(), buffer.getAllocation());
        }

        this.quadsIndexBuffer.freeBuffer();
        this.quadsIntIndexBuffer.freeBuffer();
        this.linesIndexBuffer.freeBuffer();
        this.triangleFanIndexBuffer.freeBuffer();
        this.triangleStripIndexBuffer.freeBuffer();
        this.debugLineStripIndexBuffer.freeBuffer();
    }

    public AutoIndexBuffer getQuadsIndexBuffer() {
        return this.quadsIndexBuffer;
    }

    public AutoIndexBuffer getLinesIndexBuffer() {
        return this.linesIndexBuffer;
    }

    public AutoIndexBuffer getTriangleFanIndexBuffer() {
        return this.triangleFanIndexBuffer;
    }

    public AutoIndexBuffer getTriangleStripIndexBuffer() {
        return this.triangleStripIndexBuffer;
    }

    public AutoIndexBuffer getDebugLineStripIndexBuffer() {
        return this.debugLineStripIndexBuffer;
    }

    public UniformBuffer getUniformBuffer() {
        return this.uniformBuffers[this.currentFrame];
    }

    public AutoIndexBuffer getAutoIndexBuffer(int mode, int vertexCount) {
        return switch (mode) {
            case QUADS -> {
                int indexCount = vertexCount * 3 / 2;

                yield indexCount > AutoIndexBuffer.U16_MAX_VERTEX_COUNT
                        ? this.quadsIntIndexBuffer : this.quadsIndexBuffer;
            }
            case LINES -> this.linesIndexBuffer;
            case TRIANGLE_FAN -> this.triangleFanIndexBuffer;
            case TRIANGLE_STRIP -> this.triangleStripIndexBuffer;
            case DEBUG_LINE_STRIP -> this.debugLineStripIndexBuffer;
            case GL11.GL_TRIANGLES -> null;
            default -> throw new IllegalStateException("Unexpected value: " + mode);
        };
    }
}