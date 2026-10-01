package com.yuhan123.vulkanmod.vulkan;

import com.yuhan123.vulkanmod.VKProf;
import com.yuhan123.vulkanmod.config.VulkanModConfig;
import net.minecraft.client.Minecraft;
import com.yuhan123.vulkanmod.vulkan.device.DeviceManager;
import com.yuhan123.vulkanmod.vulkan.shader.PipelineState;
import com.yuhan123.vulkanmod.vulkan.util.ColorUtil;
import com.yuhan123.vulkanmod.vulkan.util.MappedBuffer;
import com.yuhan123.vulkanmod.vulkan.util.VUtil;
import org.joml.Matrix4f;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.vulkan.VK10.*;

public abstract class VRenderSystem {
    private static final float DEFAULT_DEPTH_VALUE = 1.0f;

    private static long window;



    public static boolean depthTest = true;
    public static boolean depthMask = true;
    public static int depthFun = 515;
    public static int topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    public static int polygonMode = VK_POLYGON_MODE_FILL;
    public static boolean canSetLineWidth = false;

    public static int colorMask = PipelineState.ColorMask.getColorMask(true, true, true, true);

    public static boolean cull = false;

    public static boolean logicOp = false;
    public static int logicOpFun = 0;

    public static float clearDepthValue = DEFAULT_DEPTH_VALUE;
    public static FloatBuffer clearColor = MemoryUtil.memCallocFloat(4);

    public static MappedBuffer modelViewMatrix = new MappedBuffer(16 * 4);
    public static MappedBuffer projectionMatrix = new MappedBuffer(16 * 4);
    public static MappedBuffer TextureMatrix = new MappedBuffer(16 * 4);
    public static MappedBuffer MVP = new MappedBuffer(16 * 4);

    /**
     * Cached FloatBuffer views and scratch matrices.
     *
     * 1.12.2 calls GlStateManager.translate/rotate/scale/pushMatrix/popMatrix a
     * few tens of thousands of times per frame (every entity limb, every block
     * face, every GUI element). Each one used to go through
     * MatrixState.apply() -> calculateMVP(), which allocated two Matrix4f and
     * four FloatBuffer views and ran a full 4x4 multiply. That was millions of
     * short-lived objects per second and the dominant source of GC pressure.
     */
    private static final FloatBuffer MODEL_VIEW_FB = modelViewMatrix.buffer.asFloatBuffer();
    private static final FloatBuffer PROJECTION_FB = projectionMatrix.buffer.asFloatBuffer();
    private static final FloatBuffer TEXTURE_FB = TextureMatrix.buffer.asFloatBuffer();
    private static final FloatBuffer MVP_FB = MVP.buffer.asFloatBuffer();

    private static final Matrix4f MV_SCRATCH = new Matrix4f();
    private static final Matrix4f P_SCRATCH = new Matrix4f();

    private static boolean mvpDirty = true;

    public static MappedBuffer modelOffset = new MappedBuffer(3 * 4);
    /**
     * The WORLD-SPACE model translation the current terrain draw applies,
     * published per section (vanilla path) or per layer (batched path) and fed
     * to block.vsh as a push constant alongside the MVP.
     *
     * <p>The shader ADDS it to the vertex position before measuring the fog
     * distance, so the value must be whatever turns the stored position into
     * (world - eye):
     * <ul>
     *   <li>vanilla per-section: vertices are chunk-local and the CPU
     *       translates by {@code pos - viewEntity} -> publish that;</li>
     *   <li>batched: the section origin is baked into the vertices and the CPU
     *       translates by {@code -viewEntity} -> publish that.</li>
     * </ul>
     * Leaving it at zero makes the vertex stage measure {@code length(Position)}
     * from the world ORIGIN, which is what made the distance fog disappear (a
     * 72-high eye against a 144-block fog start put nearly every terrain vertex
     * below the fog start). Zero is also the correct value for every
     * non-terrain draw that shares block.vsh, so the terrain paths reset it
     * when they finish.
     */
    public static MappedBuffer chunkOffset = new MappedBuffer(4 * 4);
    public static MappedBuffer lightDirection0 = new MappedBuffer(3 * 4);
    public static MappedBuffer lightDirection1 = new MappedBuffer(3 * 4);

    public static MappedBuffer shaderColor = new MappedBuffer(4 * 4);
    public static MappedBuffer shaderFogColor = new MappedBuffer(4 * 4);

    public static MappedBuffer screenSize = new MappedBuffer(2 * 4);

    /**
     * Current lightmap texture coordinate, captured from
     * {@code OpenGlHelper.setLightmapTextureCoords}. 1.12.2 publishes the
     * per-entity lightmap coordinate through that GL call and folds it into the
     * lightmap with a texture matrix this port does not emulate, so the entity
     * shaders sample Sampler2 at this coordinate themselves (see
     * position_tex_normal). Seeded to the full-bright corner in the static block
     * so a draw that never sets it is left unchanged instead of being multiplied
     * by the dark corner.
     */
    public static MappedBuffer lightmapCoord = new MappedBuffer(4 * 4);

    public static float alphaCutout = 0.0f;

    /**
     * Mirrors the GL fixed-function alpha test enable bit (GL_ALPHA_TEST).
     * Vanilla 1.12.2 toggles it per terrain layer (EntityRenderer.renderWorldPass:
     * disableAlpha() before SOLID and TRANSLUCENT, enableAlpha() around the two
     * CUTOUT layers). A fragment shader containing {@code discard} forces late
     * fragment tests per the Vulkan spec, which kills early-Z overdraw rejection,
     * so pipelines with a discard-free variant must key on this flag.
     */
    public static boolean alphaTest = false;

    private static boolean depthBiasEnabled = false;
    private static float depthBiasConstant = 0.0f;
    private static float depthBiasSlope = 0.0f;
    private static final int shaderFogShape = 0;
    private static float shaderFogEnd;
    private static float shaderFogStart;
    private static float shaderGlintAlpha;
    private static float shaderLineWidth;

    private static float[] color = new float[4];
    private static float[] fogColor = new float[4];

    /**
     * GL's default current colour is opaque WHITE, and vanilla relies on that:
     * {@code GlStateManager.color} only issues glColor4f when a caller explicitly
     * sets one, so any draw that sets no colour is meant to modulate by
     * (1,1,1,1) - a no-op in the product {@code tex * ColorModulator}.
     *
     * <p>Both mirrors used to start zeroed: {@code MappedBuffer} is fresh
     * off-heap memory (all zero) and {@code color[]} is a bare {@code new float[4]}
     * of four zeros. A draw that never calls setShaderColor therefore multiplied
     * the sampled texel by (0,0,0,0) instead of white. {@code tex * 0} is black,
     * and the zero alpha makes the fragment invisible wherever blending is on -
     * which is exactly the intended overbright/tinted layer that vanilla draws
     * through the fixed-function combiner this port does not emulate. That is why
     * the particle/overlay shaders published [0,0,0,1] and [0,0,0,0] and the
     * terrain behind them showed through untinted.
     *
     * <p>Seeded here, after both mirrors exist, so the static initialiser order is
     * valid. FogColor's GL default is transparent black, which is already what
     * the JSON declares, so it needs no seeding.
     */
    static {
        setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        // Full-bright lightmap corner: a draw that never receives a lightmap
        // coordinate must look exactly as before, not be multiplied by (0,0).
        setLightmapCoord(240.0f, 240.0f);
    }

    /**
     * Bumped whenever a value that feeds a shader UBO actually changes.
     *
     * <p>The terrain batching path reuses the shader state it published for the
     * previous chunk section instead of re-running {@code ShaderInstance.apply()},
     * because a batch of sections shares one pipeline, one descriptor set and one
     * set of uniform values. This counter is how the fast path knows that the
     * uniform values it published are still current - it must only move on a real
     * change, or a value that is re-assigned identically (1.12.2 does that
     * constantly) would defeat the fast path for no reason.
     *
     * <p>Only the setters that reach a UBO field bump it: {@code ColorModulator}
     * and {@code FogColor} both come from setShaderColor (see getColor /
     * getFogColor), the fog range from setShaderFogStart / setShaderFogEnd, and
     * {@code ScreenSize} from updateScreenSize.
     */
    private static int uniformVersion;

    public static int getUniformVersion() {
        return uniformVersion;
    }

    public static void initRenderer() {
        Vulkan.initVulkan(window);
    }

    public static MappedBuffer getScreenSize() {
        updateScreenSize();
        return screenSize;
    }

    public static void updateScreenSize() {
        final float w = (float) Display.getWidth();
        final float h = (float) Display.getHeight();

        if (screenSize.getFloat(0) != w || screenSize.getFloat(4) != h) {
            ++uniformVersion;
        }

        screenSize.putFloat(0, w);
        screenSize.putFloat(4, h);
    }

    public static float getLightmapU() {
        return lightmapCoord.getFloat(0);
    }

    public static float getLightmapV() {
        return lightmapCoord.getFloat(4);
    }

    /**
     * Records the current lightmap texture coordinate. The 1.12.2 lightmap is a
     * 16x16 texture the shaders sample with {@code (coord + 8) / 256}, so the raw
     * value is stored here and that transform is applied shader-side. The uniform
     * version is bumped on a real change because the terrain fast path otherwise
     * reuses the previous draw's uniform block.
     */
    public static void setLightmapCoord(float u, float v) {
        if (lightmapCoord.getFloat(0) != u || lightmapCoord.getFloat(4) != v) {
            ++uniformVersion;
        }
        lightmapCoord.putFloat(0, u);
        lightmapCoord.putFloat(4, v);
        lightmapCoord.putFloat(8, 0.0f);
        lightmapCoord.putFloat(12, 1.0f);
    }

    /** TEMP DIAGNOSTIC: records setLightmapTextureCoords calls issued after GUI item rendering begins. */
    private static int guiSetLmLogs;

    public static void logGuiSetLm(float u, float v) {
        if (guiSetLmLogs < 16) {
            ++guiSetLmLogs;
            com.yuhan123.vulkanmod.VKProf.info(String.format("[VKPROF] SETLM (%.1f,%.1f)", u, v));
        }
    }

    /**
     * Entity hurt/burn flash colour, published to the entity shaders as the
     * {@code EntityFlash} UBO field.
     *
     * <p>1.12.2 draws the mob hurt flash through the fixed-function texture
     * combiner, not the current colour: RenderLivingBase.setBrightness uploads
     * {@code glTexEnv(GL_TEXTURE_ENV, GL_TEXTURE_ENV_COLOR, (1,0,0,0.3))} and the
     * lightmap unit's GL_INTERPOLATE combine mixes that constant into the shaded
     * texture by the constant's alpha. The vanilla GlStateManager body only
     * reached glColor/glTexEnv on a GL context that does not exist under Vulkan,
     * so the state was silently dropped and mobs never flashed.
     *
     * <p>Written by the glTexEnv / glTexEnvi hooks in GlStateManagerMixin: the
     * flash is armed by GL_TEXTURE_ENV_COLOR and cleared when the texture env
     * mode is restored to GL_MODULATE (RenderLivingBase.unsetBrightness). Zero
     * alpha means "no flash", so a fresh MappedBuffer (all zero) is inert.
     */
    public static final MappedBuffer flashColor = new MappedBuffer(4 * 4);

    /** TEMP DIAGNOSTIC: bounded log of flash arm/clear transitions (see setFlashColor). */
    private static int flashLogs;

    /** TEMP DIAGNOSTIC: when true, setLightmapTextureCoords calls are logged (see OpenGlHelperMixin). */
    public static boolean guiLmWatch;

    public static void setFlashColor(float r, float g, float b, float a) {
        final float oldA = flashColor.getFloat(12);
        if (flashColor.getFloat(0) != r || flashColor.getFloat(4) != g
                || flashColor.getFloat(8) != b || flashColor.getFloat(12) != a) {
            ++uniformVersion;
        }
        flashColor.putFloat(0, r);
        flashColor.putFloat(4, g);
        flashColor.putFloat(8, b);
        flashColor.putFloat(12, a);

        // TEMP DIAGNOSTIC: every arm and every clear-of-an-armed flash, with the
        // caller. Expected healthy sequence per flashing mob: ARM (glTexEnv from
        // RenderLivingBase.setBrightness) ... CLEAR (unsetBrightness) - and
        // nothing in between on other entities. A missing CLEAR, or an ARM whose
        // caller is not setBrightness, is the persistent-red bug.
        if (flashLogs < 24 && (a > 0.0f || oldA > 0.0f)) {
            ++flashLogs;
            final StackTraceElement[] st = Thread.currentThread().getStackTrace();
            final StringBuilder s = new StringBuilder(String.format(
                    "[VKPROF] FLASH %s rgb=(%.2f,%.2f,%.2f) a=%.2f", a > 0.0f ? "ARM  " : "CLEAR", r, g, b, a));
            for (int i = 2; i < Math.min(7, st.length); i++) {
                s.append(" <- ").append(st[i].getClassName(), st[i].getClassName().lastIndexOf('.') + 1,
                        st[i].getClassName().length()).append('.').append(st[i].getMethodName());
            }
            com.yuhan123.vulkanmod.VKProf.info(s.toString());
        }
    }

    public static void setWindow(long window) {
        VRenderSystem.window = window;
    }

    public static ByteBuffer getModelOffset() {
        return modelOffset.buffer;
    }

    public static int maxSupportedTextureSize() {
        return DeviceManager.deviceProperties.limits().maxImageDimension2D();
    }

    /**
     * Supplies the current GL matrices to {@link #calculateMVP()}.
     *
     * The matrix stacks live in {@code MatrixState}; copying them into the
     * MappedBuffers is deferred to the point where an MVP is actually needed
     * (once per draw) rather than done on every matrix operation. 1.12.2 issues
     * pushMatrix + translate + multMatrix + popMatrix per chunk section, so
     * copying eagerly meant ~384 redundant float stores per chunk, several MB of
     * pointless copying per frame in a many-chunk scene.
     */
    public interface MatrixSource {
        void applyCurrentMatrices();
    }

    private static MatrixSource matrixSource;

    public static void setMatrixSource(MatrixSource source) {
        matrixSource = source;
    }

    public static void applyMVP(Matrix4f MV, Matrix4f P) {
        // An external override of both matrices: the caller's values replace
        // whatever the stack last published.
        applyModelViewMatrix(MV);
        applyProjectionMatrix(P);
        calculateMVP();
    }

    public static void applyModelViewMatrix(Matrix4f mat) {
        MODEL_VIEW_FB.clear();
        mat.get(MODEL_VIEW_FB);
        mvpDirty = true;
    }

    public static void applyProjectionMatrix(Matrix4f mat) {
        PROJECTION_FB.clear();
        mat.get(PROJECTION_FB);
        mvpDirty = true;
    }

    /**
     * Recomputes MVP = P * MV into the MVP MappedBuffer. Allocation-free.
     * <p>
     * Called lazily: MatrixState only marks the MVP dirty when a matrix
     * changes, so this runs once per draw instead of once per matrix op.
     *
     * <p>NOTE (pass 11): the body was split into a small fused-path method plus a
     * cold {@code calculateMVPGeneral()} on the theory that the two call
     * boundaries measured by `flushMs - mvpMs` (~180 ns/call over ~950 calls a
     * frame) would then inline away. **They did not** — `flushNs` read 342/348/355
     * before and 355/334/358 after, i.e. identical. The boundaries are
     * {@code apply -> flushMVP -> calculateMVP}, and shrinking the tail of
     * {@code calculateMVP} does not remove either of them. The split was reverted;
     * do not re-attempt it without a measurement that shows the inlining actually
     * happening.
     */
    public static void calculateMVP() {
        final long __t = com.yuhan123.vulkanmod.render.util.FrameProfiler.start();

        // Pull the current GL matrices in first - this is the one place that
        // copies them, so the buffers are up to date for every consumer
        // (ModelViewMat/ProjMat uniforms included) by the time a draw reads them.
        if (matrixSource != null) {
            matrixSource.applyCurrentMatrices();
        }

        MODEL_VIEW_FB.clear();
        PROJECTION_FB.clear();

        MV_SCRATCH.set(MODEL_VIEW_FB);
        P_SCRATCH.set(PROJECTION_FB);

        P_SCRATCH.mul(MV_SCRATCH);

        MVP_FB.clear();
        P_SCRATCH.get(MVP_FB);

        com.yuhan123.vulkanmod.render.util.FrameProfiler.onMvpRecalc();
        com.yuhan123.vulkanmod.render.util.FrameProfiler.addMvpRecalcNanos(__t);

        mvpDirty = false;
    }

    /**
     * Recompute the MVP only if a matrix changed since the last flush.
     * Called at the start of every draw (ShaderInstance.apply).
     *
     * <p><b>Pass 23 removed this call from the {@code apply()} hot path.</b> It is
     * still correct and still used by {@link #getMVP()} and by the APPLYSPLIT
     * reference arm, but {@code ShaderInstance.apply()} now reads
     * {@link #mvpDirty} directly and only calls {@link #calculateMVP()} when it is
     * set. The reason is pass 11's own measurement: the boundary cost it reported
     * as {@code flushMs - mvpMs} is the price of <em>entering</em> a non-inlinable
     * callee, not of the work inside it, so removing the tail of
     * {@code calculateMVP} (which pass 11 tried and reverted) could not remove it.
     * Only removing the call can — and the flag makes the call unnecessary for the
     * ~97% of draws whose MVP is already clean.
     */
    public static void flushMVP() {
        if (mvpDirty) {
            calculateMVP();
        }
    }

    /**
     * Marks the MVP as up to date without recomputing it. Used by callers that
     * write their own matrix straight into the MVP buffer (display-list replay).
     */
    public static void markMvpClean() {
        mvpDirty = false;
    }

    public static void markMvpDirty() {
        mvpDirty = true;
    }

    public static void setTextureMatrix(Matrix4f mat) {
        TEXTURE_FB.clear();
        mat.get(TEXTURE_FB);
    }

    public static MappedBuffer getTextureMatrix() {
        return TextureMatrix;
    }

    public static MappedBuffer getModelViewMatrix() {
        return modelViewMatrix;
    }

    public static MappedBuffer getProjectionMatrix() {
        return projectionMatrix;
    }

    public static MappedBuffer getMVP() {
        flushMVP();
        return MVP;
    }

    private static final Matrix4f MVP_MATRIX = new Matrix4f();

    /**
     * Current MVP (projection * modelview, with any per-section translate baked
     * in) as a reusable {@link Matrix4f}, column-major like the MVP buffer. The
     * batch path reads it synchronously, so returning one shared instance is
     * safe.
     */
    public static Matrix4f getMVPMatrix() {
        flushMVP();
        MVP_FB.clear();
        MVP_MATRIX.set(MVP_FB);
        return MVP_MATRIX;
    }

    /**
     * Rewound views over the backing buffers. Callers reuse them instead of
     * calling ByteBuffer.asFloatBuffer(), which allocates a new FloatBuffer
     * object on every single call.
     */
    public static FloatBuffer modelViewFloatBuffer() {
        MODEL_VIEW_FB.clear();
        return MODEL_VIEW_FB;
    }

    /**
     * Reads the modelview with the buffers guaranteed to hold the current GL
     * state, refreshing them from the matrix source first.
     *
     * <p><b>Why the refresh is unconditional.</b> The buffers are only written
     * by {@code applyCurrentMatrices()}, which runs from the general branch of
     * {@link #calculateMVP()} - once per draw, not per matrix op. The
     * display-list replay is the exception: it does not go through
     * {@code calculateMVP()} at all - {@code DisplayListManager.replayDraw}
     * computes {@code P * MV * relative} itself, stores it in the MVP buffer
     * and marks that clean - so nothing recomputes anything and nothing pulls
     * the matrices for it.
     *
     * <p>When this pull was gated on the old CHUNKXF deferred-window state, it
     * never fired for replays: by the time the model's {@code callList} ran,
     * nothing was pending, the gated pull read the stale buffer and changed
     * nothing. Measured on the entity pass: entity N's own (x,y,z) had no
     * effect on its modelview at all - {@code got == base} for every entity
     * after the first - so every mob in the frame was drawn at one position
     * (the first one's, which in third person is the player). That is the
     * reported symptom "all entity textures are rendered on top of the player".
     *
     * <p>The cost is one copy of two matrices per replay, against 53-67 replays
     * a frame. Returns the same buffer as {@link #modelViewFloatBuffer()}.
     */
    public static FloatBuffer pullModelViewFloatBuffer() {
        if (PULL_ON_REPLAY) {
            // Refresh from the stack UNCONDITIONALLY. The FBs are only written
            // by applyCurrentMatrices(), which runs from calculateMVP() - i.e.
            // once per draw, not per matrix op - so a replay that arrives
            // without an intervening draw would otherwise read the previous
            // draw's modelview.
            //
            // Measured on the entity pass before the unconditional refresh
            // (when the read was gated on the old CHUNKXF deferred-window
            // state): entity N's own (x,y,z) had no effect on its modelview at
            // all - every mob in the frame was drawn at one position (the
            // first one's, which in third person is the player).
            if (matrixSource != null) {
                // The one call that puts the stack's current content into
                // MODEL_VIEW_FB. Costs two 4x4 copies per replay (53-67 a frame).
                matrixSource.applyCurrentMatrices();
            }
        }
        // else: DLPULL=0 restores the lazy read, for A/B runs only.
        MODEL_VIEW_FB.clear();
        return MODEL_VIEW_FB;
    }

    /** {@code DLPULL=0} restores the lazy read, for A/B runs only. */
    private static final boolean PULL_ON_REPLAY = VulkanModConfig.getBool("DLPULL", true);

    /**
     * Always false: the deferred chunk transform (CHUNKXF) was
     * removed and the matrix stack is always real, so there is never anything
     * pending to materialise. Kept for {@code ShaderInstance.apply}, whose
     * reuse guard consumes the return value.
     */
    public static boolean pullPendingTransform() {
        return false;
    }


    public static FloatBuffer projectionFloatBuffer() {
        PROJECTION_FB.clear();
        return PROJECTION_FB;
    }

    public static FloatBuffer textureFloatBuffer() {
        TEXTURE_FB.clear();
        return TEXTURE_FB;
    }

    public static FloatBuffer mvpFloatBuffer() {
        MVP_FB.clear();
        return MVP_FB;
    }

    public static void setModelOffset(float x, float y, float z) {
        long ptr = modelOffset.ptr;
        VUtil.UNSAFE.putFloat(ptr, x);
        VUtil.UNSAFE.putFloat(ptr + 4, y);
        VUtil.UNSAFE.putFloat(ptr + 8, z);
    }

    /**
     * Publishes the world-space model translation for the next terrain draw.
     * See {@link #chunkOffset}: this is the vector block.vsh ADDS to Position,
     * i.e. {@code pos - eye} on the vanilla path and {@code -eye} on the batched
     * one, and 0 for anything that is not terrain.
     */
    public static void setChunkOffset(float x, float y, float z) {
        long ptr = chunkOffset.ptr;
        VUtil.UNSAFE.putFloat(ptr, x);
        VUtil.UNSAFE.putFloat(ptr + 4, y);
        VUtil.UNSAFE.putFloat(ptr + 8, z);
        VUtil.UNSAFE.putFloat(ptr + 12, 0.0f);
    }

    public static void setShaderColor(float f1, float f2, float f3, float f4) {
        if (color[0] != f1 || color[1] != f2 || color[2] != f3 || color[3] != f4) {
            ++uniformVersion;
        }

        color[0] = f1;
        color[1] = f2;
        color[2] = f3;
        color[3] = f4;
        ColorUtil.setRGBA_Buffer(shaderColor, f1, f2, f3, f4);
    }

    public static void setShaderFogColor(float f1, float f2, float f3, float f4) {
        if (fogColor[0] != f1 || fogColor[1] != f2 || fogColor[2] != f3 || fogColor[3] != f4) {
            ++uniformVersion;
        }

        fogColor[0] = f1;
        fogColor[1] = f2;
        fogColor[2] = f3;
        fogColor[3] = f4;
        ColorUtil.setRGBA_Buffer(shaderFogColor, f1, f2, f3, f4);
    }

    public static MappedBuffer getShaderColor() {
        return shaderColor;
    }

    public static MappedBuffer getShaderFogColor() {
        return shaderFogColor;
    }

    public static void setClearColor(float f1, float f2, float f3, float f4) {
        ColorUtil.setRGBA_Buffer(clearColor, f1, f2, f3, f4);
        // 1.12.2 sets the fog color via GlStateManager.clearColor (EntityRenderer.updateFogColor),
        // so the shader's FogColor must follow the clear color.
        setShaderFogColor(f1, f2, f3, f4);
    }

    public static void clear(int mask) {
        Renderer.clearAttachments(mask);
    }

    public static void clearDepth(double depth) {
        clearDepthValue = (float) depth;
    }

    // Pipeline state

    public static void disableDepthTest() {
        depthTest = false;
    }

    public static void depthMask(boolean b) {
        depthMask = b;
    }

    public static void setPrimitiveTopologyGL(final int mode) {
        VRenderSystem.topology = switch (mode) {
            case GL11.GL_LINES, GL11.GL_LINE_STRIP  -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case GL11.GL_TRIANGLE_FAN, GL11.GL_TRIANGLES, GL11.GL_TRIANGLE_STRIP -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            default -> throw new RuntimeException(String.format("Unknown GL primitive topology: %s", mode));
        };
    }

    public static void setPolygonModeGL(final int mode) {
        VRenderSystem.polygonMode = switch (mode) {
            case GL11.GL_POINT -> VK_POLYGON_MODE_POINT;
            case GL11.GL_LINE -> VK_POLYGON_MODE_LINE;
            case GL11.GL_FILL -> VK_POLYGON_MODE_FILL;
            default -> throw new RuntimeException(String.format("Unknown GL polygon mode: %s", mode));
        };
    }

    public static void setLineWidth(final float width) {
        if (canSetLineWidth) {
            Renderer.setLineWidth(width);
        }
    }

    public static void colorMask(boolean b, boolean b1, boolean b2, boolean b3) {
        colorMask = PipelineState.ColorMask.getColorMask(b, b1, b2, b3);
    }

    public static int getColorMask() {
        return colorMask;
    }

    public static void enableDepthTest() {
        depthTest = true;
    }

    public static void enableCull() {
        cull = true;
    }

    public static void disableCull() {
        cull = false;
    }

    public static void depthFunc(int depthFun) {
        VRenderSystem.depthFun = depthFun;
    }

    public static void enableBlend() {
        PipelineState.blendInfo.enabled = true;
    }

    public static void disableBlend() {
        PipelineState.blendInfo.enabled = false;
    }

    public static void blendFunc(int srcFactor, int dstFactor) {
        PipelineState.blendInfo.setBlendFunction(srcFactor, dstFactor);
    }

    public static void blendFuncSeparate(int srcFactorRGB, int dstFactorRGB, int srcFactorAlpha, int dstFactorAlpha) {
        PipelineState.blendInfo.setBlendFuncSeparate(srcFactorRGB, dstFactorRGB, srcFactorAlpha, dstFactorAlpha);
    }

    public static void blendOp(int op) {
        PipelineState.blendInfo.setBlendOp(op);
    }

    public static void enableColorLogicOp() {
        logicOp = true;
    }

    public static void disableColorLogicOp() {
        logicOp = false;
    }

    public static void logicOp(int glLogicOp) {
        logicOpFun = glLogicOp;
    }

    public static void polygonOffset(float slope, float biasConstant) {
        if (depthBiasConstant != biasConstant || depthBiasSlope != slope) {
            depthBiasConstant = biasConstant;
            depthBiasSlope = slope;

            Renderer.setDepthBias(depthBiasConstant, depthBiasSlope);
        }
    }

    public static void enablePolygonOffset() {
        if (!depthBiasEnabled) {
            Renderer.setDepthBias(depthBiasConstant, depthBiasSlope);
            depthBiasEnabled = true;
        }
    }

    public static void disablePolygonOffset() {
        if (depthBiasEnabled) {
            Renderer.setDepthBias(0.0F, 0.0F);
            depthBiasEnabled = false;
        }
    }

    public static int getShaderFogShape() {
        return shaderFogShape;
    }

    public static void setShaderFogStart(float fogStart) {
        if (shaderFogStart != fogStart) {
            ++uniformVersion;
        }

        shaderFogStart = fogStart;
    }

    public static float getShaderFogStart() {
        return shaderFogStart;
    }

    public static float getShaderFogEnd() {
        return shaderFogEnd;
    }

    public static void setShaderFogEnd(float fogEnd) {
        if (shaderFogEnd != fogEnd) {
            ++uniformVersion;
        }

        shaderFogEnd = fogEnd;
    }

    public static float getShaderLineWidth() {
        return shaderLineWidth;
    }

    public static float getShaderGlintAlpha() {
        return shaderGlintAlpha;
    }

    public static void setShaderLineWidth(float lineWidth) {
        shaderLineWidth = lineWidth;
    }

    public static void setShaderGameTime() {

    }

    public static float[] getColor() {
        return color;
    }

    public static float[] getFogColor() {
        return fogColor;
    }

//    public static void setShaderGlintAlpha() {
//        shaderGlintAlpha
//    }
}
