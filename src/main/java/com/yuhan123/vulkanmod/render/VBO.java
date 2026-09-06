package com.yuhan123.vulkanmod.render;

import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.memory.*;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.VertexBuffer;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.index.AutoIndexBuffer;
import com.yuhan123.vulkanmod.vulkan.shader.GraphicsPipeline;
import com.yuhan123.vulkanmod.vulkan.shader.Pipeline;
import com.yuhan123.vulkanmod.vulkan.texture.VTextureSelector;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexFormat;

import java.nio.ByteBuffer;

import static com.yuhan123.vulkanmod.vulkan.memory.buffer.index.AutoIndexBuffer.DrawType.*;

public class VBO {
    private final MemoryType memoryType;
    private VertexBuffer vertexBuffer;
    private IndexBuffer indexBuffer;

    private int mode;
    private boolean autoIndexed = false;
    private int indexCount;
    private int vertexCount;

    public VBO(boolean useGpuMem) {
        this.memoryType = useGpuMem ? MemoryTypes.GPU_MEM : MemoryTypes.HOST_MEM;
    }

    public void upload(BufferBuilder buffer) {
//        BufferBuilder.State parameters = buffer.getVertexState();

        this.mode = buffer.getDrawMode();
        this.vertexCount = buffer.getVertexState().getVertexCount();


        AutoIndexBuffer index = Renderer.getDrawer().getAutoIndexBuffer(mode, vertexCount);

        this.indexCount = index.getIndexCount(vertexCount);



        this.uploadVertexBuffer(buffer, buffer.getByteBuffer());

        //set a index buffer
       // this.uploadIndexBuffer(index.);

        buffer.reset();
    }

    private void uploadVertexBuffer(BufferBuilder parameters, ByteBuffer data) {
        if (data != null) {
            if (this.vertexBuffer != null)
                this.vertexBuffer.scheduleFree();

            int size = parameters.getVertexFormat().getSize() * parameters.getVertexState().getVertexCount();
            this.vertexBuffer = new VertexBuffer(size, this.memoryType);
            this.vertexBuffer.copyBuffer(data, size);
        }
    }

    public void uploadIndexBuffer(ByteBuffer data) {
        if (data == null) {
            if (this.indexBuffer != null && !this.autoIndexed) {
                this.indexBuffer.scheduleFree();
            }

            this.autoIndexed = true;
        }
        else {
            if (this.indexBuffer != null && !this.autoIndexed) {
                this.indexBuffer.scheduleFree();
            }

            this.indexBuffer = new IndexBuffer(data.remaining(), MemoryTypes.GPU_MEM);
            this.indexBuffer.copyBuffer(data, data.remaining());
        }
    }

    private IndexBuffer getAutoIndexBuffer() {
        AutoIndexBuffer autoIndexBuffer;
        switch (this.mode) {
            case TRIANGLE_FAN -> {
                autoIndexBuffer = Renderer.getDrawer().getTriangleFanIndexBuffer();
                this.indexCount = AutoIndexBuffer.DrawType.getTriangleStripIndexCount(this.vertexCount);
            }
            case TRIANGLE_STRIP -> {
                autoIndexBuffer = Renderer.getDrawer().getTriangleStripIndexBuffer();
                this.indexCount = AutoIndexBuffer.DrawType.getTriangleStripIndexCount(this.vertexCount);
            }
            case QUADS -> {
                autoIndexBuffer = Renderer.getDrawer().getQuadsIndexBuffer();
            }
            case LINES -> {
                autoIndexBuffer = Renderer.getDrawer().getLinesIndexBuffer();
            }
            case DEBUG_LINE_STRIP -> {
                autoIndexBuffer = Renderer.getDrawer().getDebugLineStripIndexBuffer();
            }
//            case DEBUG_LINES -> {
//                autoIndexBuffer = null;
//            }
            default -> throw new IllegalStateException("Unexpected draw mode: %s".formatted(this.mode));
        }

        if (autoIndexBuffer != null) {
            autoIndexBuffer.checkCapacity(this.vertexCount);
            return autoIndexBuffer.getIndexBuffer();
        }

        return null;
    }

    public void bind(GraphicsPipeline pipeline) {
        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline(pipeline);
        VTextureSelector.bindShaderTextures(pipeline);
        renderer.uploadAndBindUBOs(pipeline);
    }

    public void draw() {
        if (this.indexCount != 0) {
            Renderer renderer = Renderer.getInstance();
            Pipeline pipeline = renderer.getBoundPipeline();
            renderer.uploadAndBindUBOs(pipeline);

            if (this.autoIndexed) {
                this.indexBuffer = getAutoIndexBuffer();
            }

            if (this.indexBuffer != null) {
                Renderer.getDrawer().drawIndexed(this.vertexBuffer, this.indexBuffer, this.indexCount);
            }
            else {
                Renderer.getDrawer().draw(this.vertexBuffer, this.vertexCount);
            }
        }
    }

    public void close() {
        if (this.vertexCount <= 0)
            return;

        this.vertexBuffer.scheduleFree();
        this.vertexBuffer = null;

        if (!this.autoIndexed) {
            this.indexBuffer.scheduleFree();
            this.indexBuffer = null;
        }

        this.vertexCount = 0;
        this.indexCount = 0;
    }

    public VertexBuffer getVertexBuffer() {
        return vertexBuffer;
    }

    public int getVertexCount() {
        return vertexCount;
    }

    public int getIndexCount() {
        return indexCount;
    }

    public boolean isAutoIndexed() {
        return autoIndexed;
    }

    public int getMode() {
        return mode;
    }

    public IndexBuffer getIndexBuffer() {
        return indexBuffer;
    }
}
