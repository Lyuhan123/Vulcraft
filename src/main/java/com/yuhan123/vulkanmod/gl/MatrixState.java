package com.yuhan123.vulkanmod.gl;

import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks the GL matrix state (modelview / projection) of 1.12.2's GlStateManager
 * and pushes the current values into {@link VRenderSystem} so the Vulkan shaders
 * always read the up-to-date MVP matrix.
 *
 * The current matrix of each mode IS the top of its stack: ortho/loadIdentity/
 * translate/... mutate the top element, pushMatrix duplicates it and popMatrix
 * restores the previous element.
 */
public class MatrixState {

    public static final int GL_MODELVIEW = 5888;   // GL_MODELVIEW
    public static final int GL_PROJECTION = 5889;  // GL_PROJECTION
    public static final int GL_TEXTURE = 5890;     // GL_TEXTURE (used for the lightmap; ignored by the shaders)
    public static final int GL_MODELVIEW_MATRIX = 2982;   // GL_MODELVIEW_MATRIX
    public static final int GL_PROJECTION_MATRIX = 2983;  // GL_PROJECTION_MATRIX

    private static int mode = GL_MODELVIEW;

    private static final Deque<Matrix4f> modelViewStack = new ArrayDeque<>();
    private static final Deque<Matrix4f> projectionStack = new ArrayDeque<>();
    private static final Deque<Matrix4f> textureStack = new ArrayDeque<>();

    /**
     * Recycled Matrix4f instances for the stacks. pushMatrix used to allocate a
     * fresh Matrix4f every time, which - at the push/pop rate of 1.12.2 entity
     * and GUI rendering - was a large share of the per-frame garbage.
     */
    private static final Deque<Matrix4f> matrixPool = new ArrayDeque<>();

    private static Matrix4f obtainMatrix() {
        Matrix4f m = matrixPool.poll();
        return m != null ? m : new Matrix4f();
    }

    private static void recycleMatrix(Matrix4f m) {
        if (matrixPool.size() < 256) {
            matrixPool.push(m);
        }
    }

    static {
        modelViewStack.push(new Matrix4f());
        projectionStack.push(new Matrix4f());
        textureStack.push(new Matrix4f());

        VRenderSystem.setMatrixSource(MatrixState::applyCurrentMatrices);
    }

    public static void matrixMode(int m) {
        if (m != GL_MODELVIEW && m != GL_PROJECTION && m != GL_TEXTURE) {
            throw new IllegalStateException("Unknown matrix mode: " + m);
        }
        mode = m;
    }

    /**
     * Resets the TEXTURE matrix to identity and publishes it.
     *
     * <p>In GL the texture matrix is per texture unit; {@code enableLightmap}
     * fills unit 1's copy with {@code scale(1/256) + translate(8)} and leaves it
     * there. VulkanMod keeps a single shared stack and now feeds it to the
     * shaders (position_tex_color.vsh, for the cloud layer), so the lightmap's
     * transform would otherwise leak into the next unit-0 draw and wreck its UVs
     * (GUI text/icons). Our shaders derive lightmap coordinates arithmetically
     * ({@code (UV2 + 8) / 256}) and never consume this matrix, so clearing it at
     * {@code disableLightmap} is both safe and necessary.
     */
    public static void resetTextureMatrix() {
        textureStack.peek().identity();
        VRenderSystem.setTextureMatrix(textureStack.peek());
    }

    public static void loadIdentity() {
        long __t = FrameProfiler.start();
        current().identity();
        apply();
        FrameProfiler.addMatrixOp(__t, FrameProfiler.MAT_LOAD_IDENTITY);
    }

    public static void pushMatrix() {
        long __t = FrameProfiler.start();

        final Deque<Matrix4f> stack = stackOf(mode);
        stack.push(obtainMatrix().set(current()));
        FrameProfiler.addMatrixOp(__t, FrameProfiler.MAT_PUSH);
    }

    public static void popMatrix() {
        long __t = FrameProfiler.start();

        Deque<Matrix4f> stack = stackOf(mode);
        if (stack.size() > 1) {
            recycleMatrix(stack.pop());
        } else {
            // Vanilla pops more than it pushes in some paths; reset to identity instead of failing
            while (stack.size() > 1) {
                recycleMatrix(stack.pop());
            }
            stack.peek().identity();
        }
        apply();
        FrameProfiler.addMatrixOp(__t, FrameProfiler.MAT_POP);
    }

    public static void ortho(double left, double right, double bottom, double top, double zNear, double zFar) {
        long __t = FrameProfiler.start();
        current().setOrtho((float) left, (float) right, (float) bottom, (float) top, (float) zNear, (float) zFar);
        remapZToVulkan();
        apply();
        FrameProfiler.addMatrixOp(__t, FrameProfiler.MAT_NONE);
    }

    /**
     * GL writes NDC z in [-1, 1]; Vulkan expects [0, 1]. The GUI ortho maps
     * GUI z=0 to NDC z=-0.5..0, so icon quads come out with a NEGATIVE NDC z
     * (depth < 0) and the rasterizer drops every fragment. Remap the projection's
     * z scale/offset so the whole GL z range lands in [0, 1].
     *
     * z_vk = 0.5 * z_gl + 0.5  <=>  z_clip' = 0.5 * z_clip + 0.5 * w_clip
     * w_clip is constant 1 for the ortho (m23()==0, m33()==1) but equals the
     * -z row for the perspective (m23()==-1, m33()==0), so the remap must take
     * the w row into account or the world's depth order is inverted and every
     * block becomes see-through.
     */
    private static void remapZToVulkan() {
        Matrix4f m = current();
        float wRowZ = m.m23();   // w-row z coefficient (perspective: -1, ortho: 0)
        float wRowW = m.m33();   // w-row w coefficient (ortho: 1, perspective: 0)
        m.m22(m.m22() * 0.5f + wRowZ * 0.5f);
        m.m32(m.m32() * 0.5f + wRowW * 0.5f);
    }

    public static void perspective(float fovyDegrees, float aspect, float zNear, float zFar) {
        long __t = FrameProfiler.start();
        // The Vulkan viewport is Y-inverted (y = height, height = -height), which maps
        // NDC y=+1 to the TOP of the window — the same convention as GL. The GL-style
        // Y-up perspective therefore renders the world upright as-is; no flip needed.
        // getFOVModifier can return 0 during the first frames (fovModifierHand not yet
        // initialized), which would make setPerspective produce an Infinity matrix and
        // clip every draw. Fall back to a sane FOV instead.
        if (!(fovyDegrees > 0.0f) || Float.isNaN(fovyDegrees) || Float.isInfinite(fovyDegrees)) {
            fovyDegrees = 70.0f;
        }
        current().setPerspective((float) Math.toRadians(fovyDegrees), aspect, zNear, zFar);
        remapZToVulkan();
        apply();
        FrameProfiler.addMatrixOp(__t, FrameProfiler.MAT_NONE);
    }

    public static void translate(float x, float y, float z) {
        long __t = FrameProfiler.start();

        current().translate(x, y, z);
        apply();
        FrameProfiler.addMatrixOp(__t, FrameProfiler.MAT_TRANSLATE);
    }

    public static void translate(double x, double y, double z) {
        translate((float) x, (float) y, (float) z);
    }

    public static void rotate(float angleDegrees, float x, float y, float z) {
        long __t = FrameProfiler.start();
        current().rotate((float) Math.toRadians(angleDegrees), x, y, z);
        apply();
        FrameProfiler.addMatrixOp(__t, FrameProfiler.MAT_ROTATE);
    }

    public static void scale(float x, float y, float z) {
        long __t = FrameProfiler.start();
        current().scale(x, y, z);
        apply();
        FrameProfiler.addMatrixOp(__t, FrameProfiler.MAT_SCALE);
    }

    public static void scale(double x, double y, double z) {
        scale((float) x, (float) y, (float) z);
    }

    public static void multMatrix(FloatBuffer matrix) {
        long __t = FrameProfiler.start();

        // GL matrices are column-major; JOML's set(FloatBuffer) reads them directly
        Matrix4f m = obtainMatrix();
        m.set(matrix);
        current().mul(m);
        recycleMatrix(m);
        apply();
        FrameProfiler.addMatrixOp(__t, FrameProfiler.MAT_MULT);
    }

    public static void rotateQuat(float x, float y, float z, float w) {
        long __t = FrameProfiler.start();
        current().rotate(new Quaternionf(x, y, z, w));
        apply();
        FrameProfiler.addMatrixOp(__t, FrameProfiler.MAT_ROTATE);
    }

    public static void getMatrix(int pname, FloatBuffer result) {
        Matrix4f m = pname == GL_PROJECTION_MATRIX ? projectionStack.peek() : modelViewStack.peek();
        m.get(result);
    }

    private static Matrix4f current() {
        return switch (mode) {
            case GL_PROJECTION -> projectionStack.peek();
            case GL_TEXTURE -> textureStack.peek();
            default -> modelViewStack.peek();
        };
    }

    private static Deque<Matrix4f> stackOf(int m) {
        return switch (m) {
            case GL_PROJECTION -> projectionStack;
            case GL_TEXTURE -> textureStack;
            default -> modelViewStack;
        };
    }

    /**
     * Copies the current modelview and projection into VRenderSystem.
     *
     * Invoked from {@link VRenderSystem#calculateMVP()} - i.e. once per draw,
     * when the MVP is actually needed - instead of from every matrix operation.
     * A chunk section is drawn after pushMatrix + translate + multMatrix +
     * popMatrix, so copying eagerly meant four full 4x4 copies per chunk where
     * one suffices.
     */
    private static void applyCurrentMatrices() {
        VRenderSystem.applyModelViewMatrix(modelViewStack.peek());
        VRenderSystem.applyProjectionMatrix(projectionStack.peek());

        // Publish the GL_TEXTURE matrix too. Forge's CloudRenderer makes the
        // cloud layer world-fixed by translating the TEXTURE matrix every frame
        // (the mesh UVs are static; the modelview translate only steps the layer
        // in whole-cloud increments and the texture translate supplies the
        // fractional remainder). Dropping it - as this method used to - left the
        // cloud layer glued to the camera, i.e. "clouds move with the player".
        // The value is consumed by position_tex_color.vsh via the "TextureMat"
        // push constant and is identity for every draw that does not set it.
        VRenderSystem.setTextureMatrix(textureStack.peek());

        // TEMP DIAGNOSTIC (remove): report the projection the FIRST draw after
        // the panorama's gluPerspective actually uses, so "the perspective never
        // landed" can be separated from "the faces are not being drawn".
        if (traceNextProjection) {
            traceNextProjection = false;
            Matrix4f m = projectionStack.peek();
            VulkanMod.LOGGER.info(
                    "[VKPROF] projection at first draw after panorama setup: "
                            + "m00={} m11={} m22={} m23={} m32={} m33={}",
                    m.m00(), m.m11(), m.m22(), m.m23(), m.m32(), m.m33());
        }
    }

    /** TEMP DIAGNOSTIC (remove). */
    public static boolean traceNextProjection = false;

    /**
     * Marks the MVP stale. The matrices themselves are copied by
     * {@link #applyCurrentMatrices()} at flush time, so a translate/rotate/scale
     * only has to flag that a recompute is pending.
     */
    private static void apply() {
        VRenderSystem.markMvpDirty();
    }
}
