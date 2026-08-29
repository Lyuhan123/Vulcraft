package com.yuhan123.vulkanmod.mixin.gl;

import com.yuhan123.vulkanmod.gl.*;
import net.minecraft.client.renderer.OpenGlHelper;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL20;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

@Mixin(OpenGlHelper.class)
public class OpenGlHelperMixin {
    // OpenGlHelper is a Forge class: its methods keep the MCP names in both the
    // dev (mcp-at) jar and the release jar, so the mixins must use MCP names.
    @Overwrite(remap = false)
    public static void initializeTextures() {
        // The game reads OpenGlHelper.GL_ARRAY_BUFFER for VBO binding; initialize it
        // here since the vanilla initializeTextures body was replaced.
        net.minecraft.client.renderer.OpenGlHelper.GL_ARRAY_BUFFER = 34962;
        net.minecraft.client.renderer.OpenGlHelper.GL_STATIC_DRAW = 35044;

        // 1.12.2 picks the chunk render path from OpenGlHelper.useVbo() (=
        // vboSupported && gameSettings.useVbo). The Vulkan renderer can only draw
        // chunks through the VBO path (VboRenderList), so force vboSupported on;
        // otherwise the game uses RenderList whose glDrawArrays has no bound VBO
        // and every chunk draw is skipped (invisible world).
        net.minecraft.client.renderer.OpenGlHelper.vboSupported = true;
        net.minecraft.client.renderer.OpenGlHelper.vboSupportedAti = true;

        // The vanilla initializeTextures body also assigns the texture unit
        // constants (defaultTexUnit = GL_TEXTURE0, lightmapTexUnit = GL_TEXTURE1).
        // Without them they stay 0, so enableLightmap() calls setActiveTexture(0)
        // and the lightmap bind overwrites the block atlas in texture slot 0,
        // making every block sample the lightmap (single-color blocks).
        net.minecraft.client.renderer.OpenGlHelper.defaultTexUnit = 33984;
        net.minecraft.client.renderer.OpenGlHelper.lightmapTexUnit = 33985;
        net.minecraft.client.renderer.OpenGlHelper.GL_TEXTURE2 = 33986;

        System.out.println("[FBO] framebufferSupported=" + net.minecraft.client.renderer.OpenGlHelper.framebufferSupported
            + " shadersSupported=" + net.minecraft.client.renderer.OpenGlHelper.shadersSupported
            + " fboEnable=" + net.minecraft.client.Minecraft.getMinecraft().gameSettings.fboEnable
            + " isFramebufferEnabled=" + net.minecraft.client.renderer.OpenGlHelper.isFramebufferEnabled());
    }

    /**
     * @author
     */
    @Overwrite()
    public static int glGenFramebuffers() {
        //RenderSystem.assertOnRenderThread();
        return VkGlFramebuffer.genFramebufferId();
    }

    /**
     * @author
     */
    @Overwrite()
    public static int glGenRenderbuffers() {
        //RenderSystem.assertOnRenderThreadOrInit();
        return VkGlRenderbuffer.genId();
    }

    /**
     * @author
     */
    @Overwrite()
    public static void glBindFramebuffer(int i, int j) {
        //RenderSystem.assertOnRenderThread();
        VkGlFramebuffer.bindFramebuffer(i, j);
    }

    /**
     * @author
     */
    @Overwrite()
    public static void glFramebufferTexture2D(int i, int j, int k, int l, int m) {
        //RenderSystem.assertOnRenderThread();
        VkGlFramebuffer.framebufferTexture2D(i, j, k, l, m);
    }

    /**
     * @author
     */
    @Overwrite()
    public static void glBindRenderbuffer(int i, int j) {
        //RenderSystem.assertOnRenderThreadOrInit();
        VkGlRenderbuffer.bindRenderbuffer(i, j);
    }

    /**
     * @author
     */
    @Overwrite()
    public static void glFramebufferRenderbuffer(int i, int j, int k, int l) {
        //RenderSystem.assertOnRenderThreadOrInit();
        VkGlFramebuffer.framebufferRenderbuffer(i, j, k, l);
    }

    /**
     * @author
     */
    @Overwrite()
    public static void glRenderbufferStorage(int i, int j, int k, int l) {
        //RenderSystem.assertOnRenderThreadOrInit();
        VkGlRenderbuffer.renderbufferStorage(i, j, k, l);
    }

    /**
     * @author
     */
    @Overwrite()
    public static int glCheckFramebufferStatus(int i) {
        //RenderSystem.assertOnRenderThreadOrInit();
        return VkGlFramebuffer.glCheckFramebufferStatus(i);
    }

    /**
     * @author
     */
    @Overwrite()
    public static void glDeleteFramebuffers(int i) {
        VkGlFramebuffer.deleteFramebuffer(i);
    }

    /**
     * @author
     */
    @Overwrite()
    public static void glDeleteRenderbuffers(int i) {
        VkGlRenderbuffer.deleteRenderbuffer(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int glGenBuffers() {
        //RenderSystem.assertOnRenderThreadOrInit();
        return VkGlBuffer.glGenBuffers();
    }

    /**
     * @author
     */

    @Overwrite(remap = false)
    public static void glBindBuffer(int i, int j) {
        VkGlBuffer.glBindBuffer(i, j);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void glBufferData(int i, ByteBuffer byteBuffer, int j) {
        //RenderSystem.assertOnRenderThread();
        VkGlBuffer.glBufferData(i, byteBuffer, j);
    }

    /**
     * @author
     */
//    @Overwrite()
//    public static void glBufferData(int i, long l, int j) {
//        //RenderSystem.assertOnRenderThread();
//        VkGlBuffer.glBufferData(i, l, j);
//    }

    @Overwrite(remap = false)
    public static void setActiveTexture(int i) {
        VkGlTexture.activeTexture(i);
    }

    /**
     * 1.12.2 chunk rendering calls OpenGlHelper.setClientActiveTexture() while
     * switching between the default and lightmap texture units. There is no
     * real GL context current (the window is Vulkan-only), so this must be a
     * no-op; the shader samplers are selected by the Vulkan descriptor sets.
     */
    @Overwrite(remap = false)
    public static void setClientActiveTexture(int i) {
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void setLightmapTextureCoords(int target, float p_77475_1_, float p_77475_2_) {
        // The lightmap coordinates are handled via the shader's lightmap texture
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void glDeleteBuffers(int i) {
        //RenderSystem.assertOnRenderThread();
        VkGlBuffer.glDeleteBuffers(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void glBlendFunc(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        com.yuhan123.vulkanmod.vulkan.VRenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
    }

    /**
     * @author
     */
//    @Overwrite()
//    public static void disableVertexAttribArray(int i) {}

    /**
     * @author
     */
    @Overwrite()
    public static void glUseProgram(int i) {
        //RenderSystem.assertOnRenderThread();
        VkGlProgram.glUseProgram(i);
    }

    // ---- Shader program uniform/attribute calls are handled via Vulkan UBOs ----
    @Overwrite()
    public static int glGetUniformLocation(int i, CharSequence charSequence) { return 0; }
    @Overwrite()
    public static int glGetAttribLocation(int i, CharSequence charSequence) { return 0; }
    @Overwrite()
    public static void glUniform1(int i, IntBuffer intBuffer) {}
    @Overwrite()
    public static void glUniform2(int i, IntBuffer intBuffer) {}
    @Overwrite()
    public static void glUniform3(int i, IntBuffer intBuffer) {}
    @Overwrite()
    public static void glUniform4(int i, IntBuffer intBuffer) {}
    @Overwrite()
    public static void glUniform1(int i, FloatBuffer floatBuffer) {}
    @Overwrite()
    public static void glUniform2(int i, FloatBuffer floatBuffer) {}
    @Overwrite()
    public static void glUniform3(int i, FloatBuffer floatBuffer) {}
    @Overwrite()
    public static void glUniform4(int i, FloatBuffer floatBuffer) {}
    @Overwrite()
    public static void glUniform1i(int i, int j) {}
    @Overwrite()
    public static void glUniformMatrix2(int i, boolean b, FloatBuffer floatBuffer) {}
    @Overwrite()
    public static void glUniformMatrix3(int i, boolean b, FloatBuffer floatBuffer) {}
    @Overwrite()
    public static void glUniformMatrix4(int i, boolean b, FloatBuffer floatBuffer) {}

    /**
     * @author
     */
    @Overwrite()
    public static int glCreateProgram() {
        //RenderSystem.assertOnRenderThread();
        return VkGlProgram.genProgramId();
    }

    /**
     * @author
     */
    @Overwrite()
    public static void glDeleteProgram(int i) {
        //RenderSystem.assertOnRenderThread();
        VkGlProgram.glDeleteProgram(i);
    }

    // ---- Vanilla shader-program API is replaced by the Vulkan pipeline system ----
    @Overwrite()
    public static int glCreateShader(int i) { return 0; }
    @Overwrite()
    public static void glDeleteShader(int i) {}
    @Overwrite()
    public static void glShaderSource(int i, ByteBuffer byteBuffer) {}
    @Overwrite()
    public static void glCompileShader(int i) {}
    @Overwrite()
    public static void glAttachShader(int i, int j) {}
    @Overwrite()
    public static void glLinkProgram(int i) {}
    @Overwrite()
    public static int glGetShaderi(int i, int j) { return 1; }
    @Overwrite()
    public static int glGetProgrami(int i, int j) { return 1; }
    @Overwrite()
    public static String glGetShaderInfoLog(int i, int j) { return ""; }
    @Overwrite()
    public static String glGetProgramInfoLog(int i, int j) { return ""; }

    /**
     * @author
     */
//    @Overwrite()
//    public static int glGenVertexArrays() {
        //RenderSystem.assertOnRenderThreadOrInit();
        // TODO
//        return 0;
//    }
}