package com.yuhan123.vulkanmod.mixin.gl;

import com.yuhan123.vulkanmod.gl.MatrixState;
import com.yuhan123.vulkanmod.gl.VkGlTexture;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.VRenderSystem;

import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import org.jetbrains.annotations.Nullable;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import com.yuhan123.vulkanmod.gl.VkGlFramebuffer;

@Mixin(GL11.class)
public class GL11Mixin {

    @Overwrite
    public static void glMultMatrix(FloatBuffer m) {
        MatrixState.multMatrix(m);
    }

    @Overwrite
    public static String glGetString(int name) {
        return "4.6";
    }
    /**
     * @author
     * @reason ideally Scissor should be used. but using vkCmdSetScissor() caused glitches with invisible menus with replay mod, so disabled for now as temp fix
     */
    @Overwrite(remap = false)
    public static void glScissor(@NativeType("GLint") int x, @NativeType("GLint") int y, @NativeType("GLsizei") int width, @NativeType("GLsizei") int height) {
        Renderer.setScissor(x, y, width, height);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glViewport(@NativeType("GLint") int x, @NativeType("GLint") int y, @NativeType("GLsizei") int w, @NativeType("GLsizei") int h) {
        Renderer.setViewport(x, y, w, h);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glBindTexture(@NativeType("GLenum") int target, @NativeType("GLuint") int texture) {
        VkGlTexture.bindTexture(texture);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glLineWidth(@NativeType("GLfloat") float width) {
        VRenderSystem.setLineWidth(width);
    }

    /**
     * @author
     * @reason
     */
    @NativeType("void")
    @Overwrite(remap = false)
    public static int glGenTextures() {
        return VkGlTexture.genTextureId();
    }

    /**
     * @author
     * @reason
     */
    @NativeType("GLboolean")
    @Overwrite(remap = false)
    public static boolean glIsEnabled(@NativeType("GLenum") int cap) {
        return true;
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glClear(@NativeType("GLbitfield") int mask) {
        VRenderSystem.clear(mask);
    }

    /**
     * @author
     * @reason
     */
    @NativeType("GLenum")
    @Overwrite(remap = false)
    public static int glGetError() {
        return 0;
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glClearColor(@NativeType("GLfloat") float red, @NativeType("GLfloat") float green, @NativeType("GLfloat") float blue, @NativeType("GLfloat") float alpha) {
        VRenderSystem.setClearColor(red, green, blue, alpha);
    }

    @Overwrite(remap = false)
    public static void glColor4f(@NativeType("GLfloat") float red, @NativeType("GLfloat") float green, @NativeType("GLfloat") float blue, @NativeType("GLfloat") float alpha) {
        VRenderSystem.setShaderColor(red, green, blue, alpha);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glDepthMask(@NativeType("GLboolean") boolean flag) {
        VRenderSystem.depthMask(flag);
    }

    /**
     * @author
     * @reason
     */
    @NativeType("void")
    @Overwrite(remap = false)
    public static int glGetInteger(@NativeType("GLenum") int pname) {
        return 0;
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, @Nullable ByteBuffer pixels) {
        VkGlTexture.texImage2D(target, level, internalformat, width, height, border, format, type, pixels);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glTexImage2D(@NativeType("GLenum") int target, @NativeType("GLint") int level, @NativeType("GLint") int internalformat, @NativeType("GLsizei") int width, @NativeType("GLsizei") int height, @NativeType("GLint") int border, @NativeType("GLenum") int format, @NativeType("GLenum") int type, @NativeType("void const *") long pixels) {
        VkGlTexture.texImage2D(target, level, internalformat, width, height, border, format, type, pixels);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, long pixels) {
        VkGlTexture.texSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, @Nullable ByteBuffer pixels) {
        VkGlTexture.texSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, @Nullable IntBuffer pixels) {
        VkGlTexture.texSubImage2D(target, level, xOffset, yOffset, width, height, format, type, MemoryUtil.memByteBuffer(pixels));
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glTexParameteri(@NativeType("GLenum") int target, @NativeType("GLenum") int pname, @NativeType("GLint") int param) {
        VkGlTexture.texParameteri(target, pname, param);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glTexParameterf(@NativeType("GLenum") int target, @NativeType("GLenum") int pname, @NativeType("GLfloat") float param) {

    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static int glGetTexParameteri(@NativeType("GLenum") int target, @NativeType("GLenum") int pname) {
        return VkGlTexture.getTexParameteri(target, pname);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static int glGetTexLevelParameteri(@NativeType("GLenum") int target, @NativeType("GLint") int level, @NativeType("GLenum") int pname) {
        return VkGlTexture.getTexLevelParameter(target, level, pname);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glPixelStorei(@NativeType("GLenum") int pname, @NativeType("GLint") int param) {
        VkGlTexture.pixelStoreI(pname, param);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glEnable(@NativeType("GLenum") int target) {
        if (target == GL_SCISSOR_TEST) {
            // The Vulkan scissor test is always active; the box set by glScissor applies
        }
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glDisable(@NativeType("GLenum") int target) {
        if (target == GL_SCISSOR_TEST) {
            Renderer.resetScissor();
        }
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glFinish() {
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glHint(@NativeType("GLenum") int target, @NativeType("GLenum") int hint) {
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glDeleteTextures(@NativeType("GLuint const *") int texture) {
        VkGlTexture.glDeleteTextures(texture);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glDeleteTextures(@NativeType("GLuint const *") IntBuffer textures) {
        VkGlTexture.glDeleteTextures(textures);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glGetTexImage(@NativeType("GLenum") int tex, @NativeType("GLint") int level, @NativeType("GLenum") int format, @NativeType("GLenum") int type, @NativeType("void *") long pixels) {
        VkGlTexture.getTexImage(tex, level, format, type, pixels);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glGetTexImage(@NativeType("GLenum") int tex, @NativeType("GLint") int level, @NativeType("GLenum") int format, @NativeType("GLenum") int type, @NativeType("void *") ByteBuffer pixels) {
        VkGlTexture.getTexImage(tex, level, format, type, MemoryUtil.memAddress(pixels));
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glGetTexImage(@NativeType("GLenum") int tex, @NativeType("GLint") int level, @NativeType("GLenum") int format, @NativeType("GLenum") int type, @NativeType("void *") IntBuffer pixels) {
        VkGlTexture.getTexImage(tex, level, format, type, MemoryUtil.memAddress(pixels));
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glCopyTexSubImage2D(@NativeType("GLenum") int target, @NativeType("GLint") int level, @NativeType("GLint") int xoffset, @NativeType("GLint") int yoffset, @NativeType("GLint") int x, @NativeType("GLint") int y, @NativeType("GLsizei") int width, @NativeType("GLsizei") int height) {
        com.yuhan123.vulkanmod.vulkan.texture.ImageUtil.copyTexSubImage2D(
                target, level, xoffset, yoffset, x, y, width, height);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glBlendFunc(@NativeType("GLenum") int sfactor, @NativeType("GLenum") int dfactor) {
        // TODO
    }

    /**
     * Reads the bound framebuffer back into CPU memory.
     *
     * Without this, Minecraft's screenshot (F2 / ScreenShotHelper) silently
     * produced nothing, which made every rendering bug undiagnosable without
     * asking someone to look at the screen.
     *
     * <p>{@link VkGlFramebuffer#readPixels} always hands back R,G,B,A bytes; the
     * GL {@code format} argument is honoured afterwards by
     * {@link #vulkanmod$applyReadPixelsFormat}, so a caller that asks for
     * GL_BGRA (vanilla's ScreenShotHelper does) gets B,G,R,A as it expects
     * instead of a red/blue-swapped image.
     */
    @Overwrite(remap = false)
    public static void glReadPixels(@NativeType("GLint") int x, @NativeType("GLint") int y,
                                    @NativeType("GLsizei") int width, @NativeType("GLsizei") int height,
                                    @NativeType("GLenum") int format, @NativeType("GLenum") int type,
                                    @NativeType("void *") ByteBuffer pixels) {
        final int start = pixels.position();
        VkGlFramebuffer.readPixels(x, y, width, height, pixels);
        vulkanmod$applyReadPixelsFormat(pixels, start, pixels.position(), format);
    }

    @Overwrite(remap = false)
    public static void glReadPixels(@NativeType("GLint") int x, @NativeType("GLint") int y,
                                    @NativeType("GLsizei") int width, @NativeType("GLsizei") int height,
                                    @NativeType("GLenum") int format, @NativeType("GLenum") int type,
                                    @NativeType("void *") IntBuffer pixels) {
        ByteBuffer bytes = MemoryUtil.memByteBuffer(pixels);
        final int start = bytes.position();
        VkGlFramebuffer.readPixels(x, y, width, height, bytes);
        vulkanmod$applyReadPixelsFormat(bytes, start, bytes.position(), format);
    }

    /**
     * Converts the R,G,B,A bytes {@code readPixels} produced into the component
     * order GL's {@code format} argument asked for.
     *
     * <p>Vanilla's screenshot path (ScreenShotHelper) requests
     * {@code GL_BGRA} + {@code GL_UNSIGNED_INT_8_8_8_8_REV}. On a little-endian
     * host that means the byte stream has to be B,G,R,A so the int the caller
     * assembles comes out as {@code 0xAARRGGBB}, which is exactly what
     * {@code BufferedImage.setRGB} wants. Ignoring the request left the bytes
     * R,G,B,A, so the assembled int was {@code 0xAABBGGRR} - the red/blue
     * inversion F2 was showing.
     *
     * <p>Only GL_BGRA is converted: it is the only order any caller in this
     * codebase (or vanilla) asks for, and both the BGRA types
     * (UNSIGNED_BYTE and UNSIGNED_INT_8_8_8_8_REV) describe the same byte
     * order.
     */
    @Unique
    private static void vulkanmod$applyReadPixelsFormat(ByteBuffer pixels, int start, int end, int format) {
        if (format != GL12.GL_BGRA)
            return;

        // readPixels emits four bytes per pixel.
        for (int p = start; p + 4 <= end; p += 4) {
            final byte r = pixels.get(p);
            pixels.put(p, pixels.get(p + 2));
            pixels.put(p + 2, r);
        }
    }



    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glPolygonOffset(@NativeType("GLfloat") float factor, @NativeType("GLfloat") float units) {
        VRenderSystem.polygonOffset(factor, units);
    }
}
