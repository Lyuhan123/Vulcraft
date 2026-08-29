package com.yuhan123.vulkanmod.gl;

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
    }

    public static void matrixMode(int m) {
        if (m != GL_MODELVIEW && m != GL_PROJECTION && m != GL_TEXTURE) {
            throw new IllegalStateException("Unknown matrix mode: " + m);
        }
        mode = m;
    }

    public static void loadIdentity() {
        current().identity();
        apply();
    }

    public static void pushMatrix() {
        stackOf(mode).push(obtainMatrix().set(current()));
    }

    public static void popMatrix() {
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
    }

    public static void ortho(double left, double right, double bottom, double top, double zNear, double zFar) {
        current().setOrtho((float) left, (float) right, (float) bottom, (float) top, (float) zNear, (float) zFar);
        remapZToVulkan();
        apply();
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
    }

    public static void translate(float x, float y, float z) {
        current().translate(x, y, z);
        apply();
    }

    public static void translate(double x, double y, double z) {
        current().translate((float) x, (float) y, (float) z);
        apply();
    }

    public static void rotate(float angleDegrees, float x, float y, float z) {
        current().rotate((float) Math.toRadians(angleDegrees), x, y, z);
        apply();
    }

    public static void scale(float x, float y, float z) {
        current().scale(x, y, z);
        apply();
    }

    public static void scale(double x, double y, double z) {
        current().scale((float) x, (float) y, (float) z);
        apply();
    }

    public static void multMatrix(FloatBuffer matrix) {
        // GL matrices are column-major; JOML's set(FloatBuffer) reads them directly
        Matrix4f m = obtainMatrix();
        m.set(matrix);
        current().mul(m);
        recycleMatrix(m);
        apply();
    }

    public static void rotateQuat(float x, float y, float z, float w) {
        current().rotate(new Quaternionf(x, y, z, w));
        apply();
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
     * Push the current matrices into VRenderSystem.
     * <p>
     * Both stacks are still copied out (32 float stores through cached,
     * reusable FloatBuffer views - no allocation), but the MVP is merely marked
     * dirty instead of being recomputed here. A single draw is preceded by
     * several matrix operations, so deferring the multiply to flush time (once
     * per draw, in ShaderInstance.apply) removes the vast majority of the work
     * that used to run on every translate/rotate/scale: two Matrix4f, four
     * FloatBuffer views and a full 4x4 multiply per call.
     */
    private static void apply() {
        // The texture matrix (lightmap) is not consumed by the Vulkan shaders
        VRenderSystem.applyModelViewMatrix(modelViewStack.peek());
        VRenderSystem.applyProjectionMatrix(projectionStack.peek());
    }
}
