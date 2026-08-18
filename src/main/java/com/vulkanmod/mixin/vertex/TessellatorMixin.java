package com.yuhan123.vulkanmod.mixin.vertex;

import com.yuhan123.vulkanmod.render.PipelineManager;
import com.yuhan123.vulkanmod.render.shader.ShaderInstance;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.shader.Pipeline;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.ByteBuffer;

@Mixin(Tessellator.class)
public class TessellatorMixin {

    private static int vkDrawLogs = 0;

    @Redirect(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/WorldVertexBufferUploader;draw(Lnet/minecraft/client/renderer/BufferBuilder;)V"))
    public void vkDraw(WorldVertexBufferUploader uploader, BufferBuilder buffer) {
        int vertexCount = buffer.getVertexCount();
        if (vkDrawLogs < 16 && vertexCount > 0) {
            com.yuhan123.vulkanmod.VulkanMod.LOGGER.info("[VKDBG] vkDraw count={} mode={} fmt={}",
                    vertexCount, buffer.getDrawMode(), buffer.getVertexFormat());
            vkDrawLogs++;
        }
        if (vertexCount <= 0)
            return;

        if (!Renderer.isRecording())
            return;

        VertexFormat vertexFormat = buffer.getVertexFormat();
        ShaderInstance shader = PipelineManager.chooseShader(vertexFormat);

        if (shader == null || shader.getPipeline() == null)
            return;

        // Update uniforms and bind the graphics pipeline + descriptor sets
        // (ShaderInstance.apply() already uploads UBOs and binds descriptor sets)
        shader.apply();

        Renderer renderer = Renderer.getInstance();
        Pipeline pipeline = renderer.getBoundPipeline();
        if (pipeline == null)
            return;

        ByteBuffer vertexData = buffer.getByteBuffer();
        vertexData.position(0);

        // Entity models are compiled into GL display lists (glNewList..glEndList)
        // via Tessellator.draw. During compilation the vertices must be captured
        // into the list so glCallList can replay them; drawing them immediately
        // would both waste work and leave the list empty (invisible entities).
        if (com.yuhan123.vulkanmod.gl.DisplayListManager.isRecordingList()) {
            com.yuhan123.vulkanmod.gl.DisplayListManager.captureDisplayListDraw(
                    vertexData, buffer.getDrawMode(), vertexFormat, vertexCount);
            return;
        }

        Renderer.getDrawer().draw(vertexData, buffer.getDrawMode(), vertexFormat, vertexCount);
    }
}
