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
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import com.yuhan123.vulkanmod.gl.VkGlFramebuffer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.GlStateManager.CullFace;

@Mixin(GL11.class)
public class GL11Mixin {

    @Overwrite
    public static void glMultMatrix(FloatBuffer m) {
        MatrixState.multMatrix(m);
    }

    @Overwrite
    public static void glLoadMatrix(FloatBuffer m) {
        MatrixState.loadMatrix(m);
    }

    /**
     * Legacy fixed-function matrix entry points. MC and Forge route these
     * through GlStateManager (overwritten in GlStateManagerMixin), but mods
     * that call the raw LWJGL GL11 functions directly - JourneyMap's
     * MapChat.drawScreen calls GL11.glTranslatef - used to reach the native
     * LWJGL binding, which has no current context under Vulkan and ABORTS THE
     * JVM ("FATAL ERROR in native method: No context is current"). Route them
     * into the same MatrixState stack the GlStateManager overwrites use.
     */
    @Overwrite(remap = false)
    public static void glMatrixMode(@NativeType("GLenum") int mode) {
        MatrixState.matrixMode(mode);
    }

    @Overwrite(remap = false)
    public static void glLoadIdentity() {
        MatrixState.loadIdentity();
    }

    @Overwrite(remap = false)
    public static void glPushMatrix() {
        MatrixState.pushMatrix();
    }

    @Overwrite(remap = false)
    public static void glPopMatrix() {
        MatrixState.popMatrix();
    }

    @Overwrite(remap = false)
    public static void glTranslatef(@NativeType("GLfloat") float x, @NativeType("GLfloat") float y, @NativeType("GLfloat") float z) {
        MatrixState.translate(x, y, z);
    }

    @Overwrite(remap = false)
    public static void glTranslated(@NativeType("GLdouble") double x, @NativeType("GLdouble") double y, @NativeType("GLdouble") double z) {
        MatrixState.translate(x, y, z);
    }

    @Overwrite(remap = false)
    public static void glRotatef(@NativeType("GLfloat") float angle, @NativeType("GLfloat") float x, @NativeType("GLfloat") float y, @NativeType("GLfloat") float z) {
        MatrixState.rotate(angle, x, y, z);
    }

    @Overwrite(remap = false)
    public static void glScalef(@NativeType("GLfloat") float x, @NativeType("GLfloat") float y, @NativeType("GLfloat") float z) {
        MatrixState.scale(x, y, z);
    }

    @Overwrite(remap = false)
    public static void glScaled(@NativeType("GLdouble") double x, @NativeType("GLdouble") double y, @NativeType("GLdouble") double z) {
        MatrixState.scale(x, y, z);
    }
    @Overwrite(remap = false)
    public static void glOrtho(@NativeType("GLdouble") double left, @NativeType("GLdouble") double right, @NativeType("GLdouble") double bottom, @NativeType("GLdouble") double top, @NativeType("GLdouble") double zNear, @NativeType("GLdouble") double zFar) {
        MatrixState.ortho(left, right, bottom, top, zNear, zFar);
    }

    @Overwrite(remap = false)
    public static void glMultMatrixf(@NativeType("GLfloat const *") FloatBuffer m) {
        MatrixState.multMatrix(m);
    }

    @Overwrite(remap = false)
    public static void glMultMatrixf(@NativeType("GLfloat const *") float[] m) {
        // Same routing as the FloatBuffer overload; MatrixState reads the
        // column-major 16 floats.
        MatrixState.multMatrix(FloatBuffer.wrap(m));
    }

    @Overwrite
    public static String glGetString(int name) {
        return "2.1";
    }
    /**
     * @author
     * @reason ideally Scissor should be used. but using vkCmdSetScissor() caused glitches with invisible menus with replay mod, so disabled for now as temp fix
     */
    /**
     * Maps a raw GL11 enable/disable cap to the GlStateManager VK state so mods
     * that bypass GlStateManager and call {@code GL11.glEnable/glDisable} directly
     * (a) no longer hit native LWJGL ("No context is current" abort) and
     * (b) actually flip the Vulkan state. Previously only GL_SCISSOR_TEST was
     * handled and every other cap was silently ignored, so e.g. a direct
     * {@code GL11.glEnable(GL_BLEND)} left blending off.
     */
    @Overwrite(remap = false)
    public static void glEnable(@NativeType("GLenum") int target) {
        switch (target) {
            case GL11.GL_BLEND:
                GlStateManager.enableBlend();
                break;
            case GL11.GL_DEPTH_TEST:
                GlStateManager.enableDepth();
                break;
            case GL11.GL_CULL_FACE:
                GlStateManager.enableCull();
                break;
            case GL11.GL_ALPHA_TEST:
                GlStateManager.enableAlpha();
                break;
            case GL11.GL_LIGHTING:
                GlStateManager.enableLighting();
                break;
            case GL11.GL_FOG:
                GlStateManager.enableFog();
                break;
            case GL11.GL_TEXTURE_2D:
                GlStateManager.enableTexture2D();
                break;
            case 3058: /* GL_COLOR_LOGIC */
                GlStateManager.enableColorLogic();
                break;
            case GL11.GL_POLYGON_OFFSET_FILL:
                GlStateManager.enablePolygonOffset();
                break;
            case GL11.GL_NORMALIZE:
                GlStateManager.enableNormalize();
                break;
            case 32826: /* GL_RESCALE_NORMAL */
                GlStateManager.enableRescaleNormal();
                break;
            case GL11.GL_SCISSOR_TEST:
                // The Vulkan scissor test is always active; the box set by glScissor applies
                break;
            default:
                break;
        }
    }

    @Overwrite(remap = false)
    public static void glDisable(@NativeType("GLenum") int target) {
        switch (target) {
            case GL11.GL_BLEND:
                GlStateManager.disableBlend();
                break;
            case GL11.GL_DEPTH_TEST:
                GlStateManager.disableDepth();
                break;
            case GL11.GL_CULL_FACE:
                GlStateManager.disableCull();
                break;
            case GL11.GL_ALPHA_TEST:
                GlStateManager.disableAlpha();
                break;
            case GL11.GL_LIGHTING:
                GlStateManager.disableLighting();
                break;
            case GL11.GL_FOG:
                GlStateManager.disableFog();
                break;
            case GL11.GL_TEXTURE_2D:
                GlStateManager.disableTexture2D();
                break;
            case 3058: /* GL_COLOR_LOGIC */
                GlStateManager.disableColorLogic();
                break;
            case GL11.GL_POLYGON_OFFSET_FILL:
                GlStateManager.disablePolygonOffset();
                break;
            case GL11.GL_NORMALIZE:
                GlStateManager.disableNormalize();
                break;
            case 32826: /* GL_RESCALE_NORMAL */
                GlStateManager.disableRescaleNormal();
                break;
            case GL11.GL_SCISSOR_TEST:
                Renderer.resetScissor();
                break;
            default:
                break;
        }
    }

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
     * Remaining glColor* overloads. Botania's HUD (mana bar) packs colors into
     * bytes and calls GL11.glColor4ub directly; un-intercepted it aborts the JVM
     * ("No context is current"). Byte colors map through the unsigned-normalized
     * conversion (b &amp; 0xFF) / 255; doubles are clamped to [0,1] like GL does.
     */
    @Overwrite(remap = false)
    public static void glColor3b(@NativeType("GLbyte") byte red, @NativeType("GLbyte") byte green, @NativeType("GLbyte") byte blue) {
        VRenderSystem.setShaderColor((red & 0xFF) / 255.0F, (green & 0xFF) / 255.0F, (blue & 0xFF) / 255.0F, 1.0F);
    }

    @Overwrite(remap = false)
    public static void glColor3ub(@NativeType("GLubyte") byte red, @NativeType("GLubyte") byte green, @NativeType("GLubyte") byte blue) {
        VRenderSystem.setShaderColor((red & 0xFF) / 255.0F, (green & 0xFF) / 255.0F, (blue & 0xFF) / 255.0F, 1.0F);
    }

    @Overwrite(remap = false)
    public static void glColor3d(double red, double green, double blue) {
        VRenderSystem.setShaderColor(
                (float) (red < 0.0 ? 0.0 : (red > 1.0 ? 1.0 : red)),
                (float) (green < 0.0 ? 0.0 : (green > 1.0 ? 1.0 : green)),
                (float) (blue < 0.0 ? 0.0 : (blue > 1.0 ? 1.0 : blue)),
                1.0F);
    }

    @Overwrite(remap = false)
    public static void glColor4b(@NativeType("GLbyte") byte red, @NativeType("GLbyte") byte green, @NativeType("GLbyte") byte blue, @NativeType("GLbyte") byte alpha) {
        VRenderSystem.setShaderColor((red & 0xFF) / 255.0F, (green & 0xFF) / 255.0F, (blue & 0xFF) / 255.0F, (alpha & 0xFF) / 255.0F);
    }

    @Overwrite(remap = false)
    public static void glColor4ub(@NativeType("GLubyte") byte red, @NativeType("GLubyte") byte green, @NativeType("GLubyte") byte blue, @NativeType("GLubyte") byte alpha) {
        VRenderSystem.setShaderColor((red & 0xFF) / 255.0F, (green & 0xFF) / 255.0F, (blue & 0xFF) / 255.0F, (alpha & 0xFF) / 255.0F);
    }

    @Overwrite(remap = false)
    public static void glColor4d(double red, double green, double blue, double alpha) {
        VRenderSystem.setShaderColor(
                (float) (red < 0.0 ? 0.0 : (red > 1.0 ? 1.0 : red)),
                (float) (green < 0.0 ? 0.0 : (green > 1.0 ? 1.0 : green)),
                (float) (blue < 0.0 ? 0.0 : (blue > 1.0 ? 1.0 : blue)),
                (float) (alpha < 0.0 ? 0.0 : (alpha > 1.0 ? 1.0 : alpha)));
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

    // The typed-buffer overloads are NOT covered by the two above: lwjglx's
    // Short/Int/Float/Double variants call the native entry points directly,
    // and the Forge loading splash uploads its textures through the IntBuffer
    // overload - from a thread with no GL context, which aborts the JVM
    // ("FATAL ERROR in native method: No context is current").

    @Overwrite(remap = false)
    public static void glTexImage2D(@NativeType("GLenum") int target, @NativeType("GLint") int level, @NativeType("GLint") int internalformat, @NativeType("GLsizei") int width, @NativeType("GLsizei") int height, @NativeType("GLint") int border, @NativeType("GLenum") int format, @NativeType("GLenum") int type, @Nullable IntBuffer pixels) {
        // memByteBuffer() NPEs on null in LWJGL 3.4 (unlike the old versions),
        // and glTexImage2D(NULL) is a legal "allocate only" call - e.g. Forge's
        // SplashProgress$Texture allocates with null and fills via glTexSubImage2D.
        VkGlTexture.texImage2D(target, level, internalformat, width, height, border, format, type, pixels == null ? null : MemoryUtil.memByteBuffer(pixels));
    }

    @Overwrite(remap = false)
    public static void glTexImage2D(@NativeType("GLenum") int target, @NativeType("GLint") int level, @NativeType("GLint") int internalformat, @NativeType("GLsizei") int width, @NativeType("GLsizei") int height, @NativeType("GLint") int border, @NativeType("GLenum") int format, @NativeType("GLenum") int type, @Nullable ShortBuffer pixels) {
        VkGlTexture.texImage2D(target, level, internalformat, width, height, border, format, type, pixels == null ? null : MemoryUtil.memByteBuffer(pixels));
    }

    @Overwrite(remap = false)
    public static void glTexImage2D(@NativeType("GLenum") int target, @NativeType("GLint") int level, @NativeType("GLint") int internalformat, @NativeType("GLsizei") int width, @NativeType("GLsizei") int height, @NativeType("GLint") int border, @NativeType("GLenum") int format, @NativeType("GLenum") int type, @Nullable FloatBuffer pixels) {
        VkGlTexture.texImage2D(target, level, internalformat, width, height, border, format, type, pixels == null ? null : MemoryUtil.memByteBuffer(pixels));
    }

    @Overwrite(remap = false)
    public static void glTexImage2D(@NativeType("GLenum") int target, @NativeType("GLint") int level, @NativeType("GLint") int internalformat, @NativeType("GLsizei") int width, @NativeType("GLsizei") int height, @NativeType("GLint") int border, @NativeType("GLenum") int format, @NativeType("GLenum") int type, @Nullable DoubleBuffer pixels) {
        VkGlTexture.texImage2D(target, level, internalformat, width, height, border, format, type, pixels == null ? null : MemoryUtil.memByteBuffer(pixels));
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
        VkGlTexture.texSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels == null ? null : MemoryUtil.memByteBuffer(pixels));
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
     * Legacy attribute-stack calls. vulcraft has no attribute stack (per-draw
     * state is emulated via push constants / pipeline state), so these are
     * no-ops - but they MUST be intercepted: botania/psi (Vazkii's
     * ParticleRenderDispatcher) call GL11.glPushAttrib/glPopAttrib directly
     * during RenderWorldLastEvent, and an un-intercepted call falls through
     * to native LWJGL, which aborts the JVM with
     * "FATAL ERROR in native method: No context is current"
     * (the visible window is Vulkan-only; no GL context is current).
     */
    @Overwrite(remap = false)
    public static void glPushAttrib(@NativeType("GLbitfield") int mask) {
    }

    @Overwrite(remap = false)
    public static void glPopAttrib() {
    }

    /**
     * {@code glFlush} has no Vulkan counterpart at the GL11 level: vulcraft
     * submits a frame when the frame ends (Renderer.submitFrame), so there is
     * nothing to flush mid-draw. It MUST be intercepted for the same reason as
     * glPushAttrib above - CustomLoadingScreen's MainSplashRenderer.run() ends
     * with GL11.glFlush() on the splash thread, which has no GL context (the
     * only window is GLFW_NO_API), so an un-intercepted call aborts the JVM.
     */
    @Overwrite(remap = false)
    public static void glFlush() {
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
        GlStateManager.blendFunc(sfactor, dfactor);
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

    // ------------------------------------------------------------------
    // Bridge to GlStateManagerMixin (the canonical VK implementation).
    // Raw org.lwjgl.opengl.GL11 calls that have a GlStateManager equivalent are
    // forwarded here so mods bypassing GlStateManager no longer fall through to
    // native LWJGL ("No context is current" abort). GSM's @Overwrite bodies call
    // VRenderSystem/MatrixState/Drawer directly and never call back into GL11,
    // so this bridge is acyclic.
    // ------------------------------------------------------------------

    // --- Immediate-mode capture (reuses GlStateManager's vertex capture) ---
    @Overwrite(remap = false)
    public static void glBegin(@NativeType("GLenum") int mode) {
        GlStateManager.glBegin(mode);
    }

    @Overwrite(remap = false)
    public static void glEnd() {
        GlStateManager.glEnd();
    }

    @Overwrite(remap = false)
    public static void glVertex3f(float x, float y, float z) {
        GlStateManager.glVertex3f(x, y, z);
    }

    @Overwrite(remap = false)
    public static void glVertex2f(float x, float y) {
        GlStateManager.glVertex3f(x, y, 0.0F);
    }

    @Overwrite(remap = false)
    public static void glVertex4f(float x, float y, float z, float w) {
        GlStateManager.glVertex3f(x, y, z);
    }

    @Overwrite(remap = false)
    public static void glTexCoord2f(float u, float v) {
        GlStateManager.glTexCoord2f(u, v);
    }

    @Overwrite(remap = false)
    public static void glTexCoord1f(float s) {
        GlStateManager.glTexCoord2f(s, 0.0F);
    }

    @Overwrite(remap = false)
    public static void glTexCoord3f(float s, float t, float r) {
        GlStateManager.glTexCoord2f(s, t);
    }

    @Overwrite(remap = false)
    public static void glTexCoord4f(float s, float t, float r, float q) {
        GlStateManager.glTexCoord2f(s, t);
    }

    @Overwrite(remap = false)
    public static void glNormal3f(float nx, float ny, float nz) {
        GlStateManager.glNormal3f(nx, ny, nz);
    }

    // --- Color (the common float overload not already covered) ---
    @Overwrite(remap = false)
    public static void glColor3f(float r, float g, float b) {
        VRenderSystem.setShaderColor(r, g, b, 1.0F);
    }

    // --- Alpha test / depth / cull / shade ---
    @Overwrite(remap = false)
    public static void glAlphaFunc(@NativeType("GLenum") int func, float ref) {
        GlStateManager.alphaFunc(func, ref);
    }

    // --- Fixed-function texture environment ---
    //
    // GL_TEXTURE_ENV_MODE / _COLOR and the combine pipeline are fixed-function
    // state the Vulkan shaders do not implement (they sample the bound texture
    // directly, which matches REPLACE and MODULATE-with-white). Leaving these
    // un-intercepted lets any mod that still calls them - Modern Splash's
    // drawFadeOverlay does - reach native LWJGL and abort the JVM with
    // "No context is current". No-ops, like glFlush/glFinish.
    @Overwrite(remap = false)
    public static void glTexEnvi(@NativeType("GLenum") int target, @NativeType("GLenum") int pname, @NativeType("GLint") int param) {
    }

    @Overwrite(remap = false)
    public static void glTexEnvf(@NativeType("GLenum") int target, @NativeType("GLenum") int pname, @NativeType("GLfloat") float param) {
    }

    @Overwrite(remap = false)
    public static void glTexEnviv(@NativeType("GLenum") int target, @NativeType("GLenum") int pname, @NativeType("GLint const *") IntBuffer params) {
    }

    @Overwrite(remap = false)
    public static void glTexEnvfv(@NativeType("GLenum") int target, @NativeType("GLenum") int pname, @NativeType("GLfloat const *") FloatBuffer params) {
    }

    @Overwrite(remap = false)
    public static void glCullFace(@NativeType("GLenum") int mode) {
        GlStateManager.cullFace(mode == GL11.GL_FRONT ? CullFace.FRONT
                : mode == GL11.GL_FRONT_AND_BACK ? CullFace.FRONT_AND_BACK
                : CullFace.BACK);
    }

    @Overwrite(remap = false)
    public static void glDepthFunc(@NativeType("GLenum") int func) {
        GlStateManager.depthFunc(func);
    }

    @Overwrite(remap = false)
    public static void glShadeModel(@NativeType("GLenum") int mode) {
        GlStateManager.shadeModel(mode);
    }

    @Overwrite(remap = false)
    public static void glPolygonMode(@NativeType("GLenum") int face, @NativeType("GLenum") int mode) {
        GlStateManager.glPolygonMode(face, mode);
    }

    // --- Fog ---
    @Overwrite(remap = false)
    public static void glFogi(@NativeType("GLenum") int pname, int param) {
        // mode-only fog state is ignored; start/end/color flow through glFogf(GL_FOG_START/END)
    }

    @Overwrite(remap = false)
    public static void glFogf(@NativeType("GLenum") int pname, float param) {
        // glFog(int, FloatBuffer) does not exist in LWJGL 3.x GL11; the scalar
        // form is used for start/end, which is what vanilla fog actually sets.
        if (pname == GL11.GL_FOG_START) {
            VRenderSystem.setShaderFogStart(param);
        } else if (pname == GL11.GL_FOG_END) {
            VRenderSystem.setShaderFogEnd(param);
        }
    }

    // --- Color mask / logic op / depth range / clear depth / clear stencil ---
    @Overwrite(remap = false)
    public static void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        GlStateManager.colorMask(red, green, blue, alpha);
    }

    @Overwrite(remap = false)
    public static void glLogicOp(@NativeType("GLenum") int opcode) {
        GlStateManager.colorLogicOp(opcode);
    }

    @Overwrite(remap = false)
    public static void glClearDepth(double depth) {
        GlStateManager.clearDepth(depth);
    }

    @Overwrite(remap = false)
    public static void glDepthRange(double near, double far) {
        // ignored: Vulkan depth range is fixed [0,1]
    }

    @Overwrite(remap = false)
    public static void glClearStencil(int s) {
        // ignored: no stencil buffer in vulcraft
    }

    // --- Legacy material (glMaterial / glColorMaterial) ---
    // The GL fixed-function material state. 1.12.2 reaches these only through
    // RenderHelper.enableStandardItemLighting (which pairs enableColorMaterial
    // with colorMaterial and never calls glMaterial itself), so the interesting
    // callers are mods that tint their models with glMaterial(GL_FRONT,
    // GL_DIFFUSE, ...). The state is tracked in VRenderSystem and consumed by
    // the item pipeline; see the block comment there.
    //
    // All four overloads MUST be overwritten: an un-overwritten one still calls
    // into LWJGL's native GL, and with no GL context that aborts the JVM. That
    // is why glMaterialfv/glMaterialiv are here even though they were missing.
    //
    // NOTE: GL11 has no glMaterial(int,int,FloatBuffer); only glMaterialf /
    // glMateriali / glMaterialfv / glMaterialiv exist.
    @Overwrite(remap = false)
    public static void glMaterialf(int face, int pname, float param) {
        VRenderSystem.setMaterial(face, pname, param, 0.0F, 0.0F, 1.0F);
    }

    @Overwrite(remap = false)
    public static void glMateriali(int face, int pname, int param) {
        VRenderSystem.setMaterial(face, pname, param, 0.0F, 0.0F, 1.0F);
    }

    @Overwrite(remap = false)
    public static void glMaterialfv(int face, int pname, FloatBuffer params) {
        if (params == null) {
            return;
        }
        final int pos = params.position();
        // A scalar pname reads only the first component; GL leaves the rest of
        // the vector untouched, which is why the vec4 defaults are filled here.
        VRenderSystem.setMaterial(face, pname,
                params.get(pos),
                params.remaining() > 1 ? params.get(pos + 1) : 0.0F,
                params.remaining() > 2 ? params.get(pos + 2) : 0.0F,
                params.remaining() > 3 ? params.get(pos + 3) : 1.0F);
    }

    @Overwrite(remap = false)
    public static void glMaterialiv(int face, int pname, IntBuffer params) {
        if (params == null) {
            return;
        }
        final int pos = params.position();
        VRenderSystem.setMaterial(face, pname,
                params.get(pos),
                params.remaining() > 1 ? params.get(pos + 1) : 0.0F,
                params.remaining() > 2 ? params.get(pos + 2) : 0.0F,
                params.remaining() > 3 ? params.get(pos + 3) : 1.0F);
    }

    // glColorMaterial(face, mode) is the GL_COLOR_MATERIAL equivalent of
    // GlStateManager.colorMaterial, which is where Minecraft itself calls it.
    // Forwarded so a direct GL11 caller lands in the same state.
    @Overwrite(remap = false)
    public static void glColorMaterial(int face, int mode) {
        VRenderSystem.colorMaterialFace = face;
        VRenderSystem.colorMaterialMode = mode;
        VRenderSystem.bumpUniformVersion();
    }

    // --- Client vertex arrays (reuses GlStateManager's pointer capture) ---
    // NOTE: GL11 in LWJGL 3.x has NO int-offset glVertexPointer(int,int,int,int)
    // overload (only ByteBuffer/long/ShortBuffer/IntBuffer/FloatBuffer). The int
    // form would fail Mixin apply ("target not located in org.lwjgl.opengl.GL11").
    @Overwrite(remap = false)
    public static void glVertexPointer(int size, int type, int stride, @Nullable ByteBuffer pointer) {
        GlStateManager.glVertexPointer(size, type, stride, pointer);
    }

    // NOTE: GL11 has no int-offset glColorPointer(int,int,int,int) overload
    // (only ByteBuffer/long/ShortBuffer/IntBuffer/FloatBuffer).
    @Overwrite(remap = false)
    public static void glColorPointer(int size, int type, int stride, @Nullable ByteBuffer pointer) {
        GlStateManager.glColorPointer(size, type, stride, pointer);
    }

    // NOTE: GL11 has no int-offset glTexCoordPointer(int,int,int,int) overload
    // (only ByteBuffer/long/ShortBuffer/IntBuffer/FloatBuffer).
    @Overwrite(remap = false)
    public static void glTexCoordPointer(int size, int type, int stride, @Nullable ByteBuffer pointer) {
        GlStateManager.glTexCoordPointer(size, type, stride, pointer);
    }

    @Overwrite(remap = false)
    public static void glNormalPointer(int type, int stride, @Nullable ByteBuffer pointer) {
        GlStateManager.glNormalPointer(type, stride, pointer);
    }

    @Overwrite(remap = false)
    public static void glEnableClientState(int array) {
        GlStateManager.glEnableClientState(array);
    }

    @Overwrite(remap = false)
    public static void glDisableClientState(int array) {
        GlStateManager.glDisableClientState(array);
    }

    // --- Draw ---
    @Overwrite(remap = false)
    public static void glDrawArrays(@NativeType("GLenum") int mode, int first, int count) {
        GlStateManager.glDrawArrays(mode, first, count);
    }

    // NOTE: GL11 has no int-offset glDrawElements(int,int,int,int); the native
    // signature uses a long pointer. Keep the long form as a no-op intercept.
    @Overwrite(remap = false)
    public static void glDrawElements(@NativeType("GLenum") int mode, int count, int type, long indices) {
        // indexed draws from raw GL11 are not captured; intercept to avoid abort
    }

    // --- Display lists (reuses GlStateManager's DisplayListManager) ---
    @Overwrite(remap = false)
    public static void glNewList(int list, int mode) {
        GlStateManager.glNewList(list, mode);
    }

    @Overwrite(remap = false)
    public static void glEndList() {
        GlStateManager.glEndList();
    }

    @Overwrite(remap = false)
    public static int glGenLists(int range) {
        return GlStateManager.glGenLists(range);
    }

    @Overwrite(remap = false)
    public static void glCallList(int list) {
        GlStateManager.callList(list);
    }

    @Overwrite(remap = false)
    public static void glCallLists(@Nullable IntBuffer lists) {
        // intercepted to avoid abort; per-list replay is not captured from raw GL11
    }

    @Overwrite(remap = false)
    public static void glDeleteLists(int list, int range) {
        GlStateManager.glDeleteLists(list, range);
    }

    @Overwrite(remap = false)
    public static void glListBase(int base) {
    }

    @Overwrite(remap = false)
    public static boolean glIsList(int list) {
        return false;
    }
}
