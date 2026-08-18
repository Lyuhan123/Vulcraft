package com.yuhan123.vulkanmod.vulkan.util;

import com.yuhan123.vulkanmod.gl.VkGlFramebuffer;
import com.yuhan123.vulkanmod.gl.VkGlTexture;
import com.yuhan123.vulkanmod.render.PipelineManager;
import com.yuhan123.vulkanmod.render.shader.ShaderInstance;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
import com.yuhan123.vulkanmod.vulkan.framebuffer.Framebuffer;
import com.yuhan123.vulkanmod.vulkan.shader.GraphicsPipeline;
import com.yuhan123.vulkanmod.vulkan.shader.Pipeline;
import com.yuhan123.vulkanmod.vulkan.texture.VTextureSelector;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

//
//import net.minecraft.client.renderer.BufferBuilder;
//import net.minecraft.client.renderer.vertex.*;
//import net.minecraft.client.Minecraft;
//import com.yuhan123.vulkanmod.render.PipelineManager;
//import com.yuhan123.vulkanmod.vulkan.Renderer;
//import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
//import com.yuhan123.vulkanmod.vulkan.shader.GraphicsPipeline;
//import net.minecraft.client.renderer.Tessellator;
//import org.joml.Matrix4f;
//import org.joml.Matrix4fStack;
//import org.lwjgl.opengl.GL11;
//import org.lwjgl.vulkan.VK11;
//import org.lwjgl.vulkan.VkCommandBuffer;
//
//import static com.yuhan123.vulkanmod.vulkan.memory.buffer.index.AutoIndexBuffer.DrawType.QUADS;
//
public class DrawUtil {
//
//    public static void blitToScreen() {
////        defualtBlit();
//        fastBlit();
//    }
//
    public static void fastBlit() {
        GraphicsPipeline blitPipeline = PipelineManager.chooseShader(PipelineManager.blitFormat).getPipeline();

        VRenderSystem.disableCull();
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);

        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline(blitPipeline);
        renderer.uploadAndBindUBOs(blitPipeline);

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        VK11.vkCmdDraw(commandBuffer, 3, 1, 0, 0);

        VRenderSystem.enableCull();
    }

    /** DEBUG: test the Drawer path with identity MVP (red) and ortho MVP (green) */
    public static void testDrawerQuad() {
        VRenderSystem.disableCull();
        VRenderSystem.disableDepthTest();
        VRenderSystem.disableBlend();

        Renderer renderer = Renderer.getInstance();
        com.yuhan123.vulkanmod.render.shader.ShaderInstance shader =
                com.yuhan123.vulkanmod.render.PipelineManager.chooseShader(net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_TEX);
        if (shader == null)
            return;

        org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackGet();
        ByteBuffer vb = stack.malloc(4 * 20);

        // 1) Identity MVP + NDC quad (POSITION_TEX format) -> red
        com.yuhan123.vulkanmod.gl.MatrixState.matrixMode(com.yuhan123.vulkanmod.gl.MatrixState.GL_PROJECTION);
        com.yuhan123.vulkanmod.gl.MatrixState.loadIdentity();
        com.yuhan123.vulkanmod.gl.MatrixState.matrixMode(com.yuhan123.vulkanmod.gl.MatrixState.GL_MODELVIEW);
        com.yuhan123.vulkanmod.gl.MatrixState.loadIdentity();
        VRenderSystem.setShaderColor(1.0f, 0.0f, 0.0f, 1.0f);
        shader.apply();
        Pipeline pipeline = renderer.getBoundPipeline();
        if (pipeline == null)
            return;
        renderer.uploadAndBindUBOs(pipeline);
        vb.clear();
        vb.putFloat(-1.0f).putFloat(-1.0f).putFloat(0.0f); vb.putFloat(0.0f).putFloat(0.0f);
        vb.putFloat(1.0f).putFloat(-1.0f).putFloat(0.0f); vb.putFloat(1.0f).putFloat(0.0f);
        vb.putFloat(1.0f).putFloat(1.0f).putFloat(0.0f); vb.putFloat(1.0f).putFloat(1.0f);
        vb.putFloat(-1.0f).putFloat(1.0f).putFloat(0.0f); vb.putFloat(0.0f).putFloat(1.0f);
        vb.flip();
        Renderer.getDrawer().draw(vb, GL11.GL_QUADS, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_TEX, 4);

        // 2) Ortho MVP + GUI coords quad at z=-2000 -> green
        com.yuhan123.vulkanmod.gl.MatrixState.matrixMode(com.yuhan123.vulkanmod.gl.MatrixState.GL_PROJECTION);
        com.yuhan123.vulkanmod.gl.MatrixState.ortho(0.0, 427.0, 240.0, 0.0, 1000.0, 3000.0);
        com.yuhan123.vulkanmod.gl.MatrixState.matrixMode(com.yuhan123.vulkanmod.gl.MatrixState.GL_MODELVIEW);
        com.yuhan123.vulkanmod.gl.MatrixState.loadIdentity();
        VRenderSystem.setShaderColor(0.0f, 1.0f, 0.0f, 1.0f);
        shader.apply();
        renderer.uploadAndBindUBOs(renderer.getBoundPipeline());
        vb.clear();
        vb.putFloat(0.0f).putFloat(240.0f).putFloat(-2000.0f); vb.putFloat(0.0f).putFloat(1.0f);
        vb.putFloat(427.0f).putFloat(240.0f).putFloat(-2000.0f); vb.putFloat(1.0f).putFloat(1.0f);
        vb.putFloat(427.0f).putFloat(0.0f).putFloat(-2000.0f); vb.putFloat(1.0f).putFloat(0.0f);
        vb.putFloat(0.0f).putFloat(0.0f).putFloat(-2000.0f); vb.putFloat(0.0f).putFloat(0.0f);
        vb.flip();
        Renderer.getDrawer().draw(vb, GL11.GL_QUADS, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_TEX, 4);
    }
//
//    public static void defualtBlit() {
//        Matrix4f matrix4f = new Matrix4f().setOrtho(0.0F, 1.0F, 1.0F, 0.0F, 0.0F, 1.0F);
//        RenderSystem.setProjectionMatrix(matrix4f, VertexSorting.ORTHOGRAPHIC_Z);
//        Matrix4fStack posestack = RenderSystem.getModelViewStack();
//        posestack.pushMatrix();
//        posestack.identity();
//        RenderSystem.applyModelViewMatrix();
//        posestack.popMatrix();
//
////        ShaderInstance shaderInstance = Minecraft.getInstance().gameRenderer.blitShader;
////        RenderSystem.setShader(() -> shaderInstance);
//
//        Tessellator tessellator = Tessellator.getInstance();
//        BufferBuilder bufferBuilder = tessellator.getBuffer();
//        bufferBuilder.begin(QUADS, DefaultVertexFormats.POSITION_TEX);
//        bufferBuilder.pos(-1.0f, -1.0f, 0.0f).tex(0.0F, 1.0F).endVertex();
//        bufferBuilder.pos(1.0f, -1.0f, 0.0f).tex(1.0F, 1.0F).endVertex();
//        bufferBuilder.pos(1.0f, 1.0f, 0.0f).tex(1.0F, 0.0F).endVertex();
//        bufferBuilder.pos(-1.0f, 1.0f, 0.0f).tex(0.0F, 0.0F).endVertex();
////        var meshData = bufferBuilder.buildOrThrow();
////
////        MeshData.DrawState parameters = meshData.drawState();
//
//        Renderer renderer = Renderer.getInstance();
//
//        GraphicsPipeline pipeline = ((ShaderMixed)(shaderInstance)).getPipeline();
//        renderer.bindGraphicsPipeline(pipeline);
//        renderer.uploadAndBindUBOs(pipeline);
//        Renderer.getDrawer().draw(bufferBuilder.getByteBuffer(), bufferBuilder.getDrawMode(), bufferBuilder.getVertexFormat(), bufferBuilder.getVertexCount());
//    }
}
