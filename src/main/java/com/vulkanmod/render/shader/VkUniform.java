package com.yuhan123.vulkanmod.render.shader;

import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.gl.VkGlProgram;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.shader.Pipeline;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.shader.ShaderManager;
import net.minecraft.client.util.JsonException;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public class VkUniform {
    public static final int UT_INT1 = 0;
    public static final int UT_INT2 = 1;
    public static final int UT_INT3 = 2;
    public static final int UT_INT4 = 3;
    public static final int UT_FLOAT1 = 4;
    public static final int UT_FLOAT2 = 5;
    public static final int UT_FLOAT3 = 6;
    public static final int UT_FLOAT4 = 7;
    public static final int UT_MAT2 = 8;
    public static final int UT_MAT3 = 9;
    public static final int UT_MAT4 = 10;
    private static final boolean TRANSPOSE_MATRICIES = false;
    private int location;
    private final int count;
    private final int type;
    private final IntBuffer intValues;
    private final FloatBuffer floatValues;
    private final String name;
    private boolean dirty;
//    private final Shader parent;

    public VkUniform(String string, int i, int j) {
        this.name = string;
        this.count = j;
        this.type = i;
//        this.parent = shader;
        if (i <= 3) {
            this.intValues = MemoryUtil.memAllocInt(j);
            this.floatValues = null;
        } else {
            this.intValues = null;
            this.floatValues = MemoryUtil.memAllocFloat(j);
        }

        this.location = -1;
        this.markDirty();
    }

    public void close() {
        if (this.intValues != null) {
            MemoryUtil.memFree(this.intValues);
        }

        if (this.floatValues != null) {
            MemoryUtil.memFree(this.floatValues);
        }

    }

    public void markDirty() {
        this.dirty = true;
    }

    public static int getTypeFromString(String string) {
        int i = -1;
        if ("int".equals(string)) {
            i = 0;
        } else if ("float".equals(string)) {
            i = 4;
        } else if (string.startsWith("matrix")) {
            if (string.endsWith("2x2")) {
                i = 8;
            } else if (string.endsWith("3x3")) {
                i = 9;
            } else if (string.endsWith("4x4")) {
                i = 10;
            }
        }

        return i;
    }

    public void setLocation(int i) {
        this.location = i;
    }

    public String getName() {
        return this.name;
    }

    public final void set(float f) {
        this.floatValues.position(0);
        this.floatValues.put(0, f);
        this.markDirty();
    }

    public final void set(float f, float g) {
        this.floatValues.position(0);
        this.floatValues.put(0, f);
        this.floatValues.put(1, g);
        this.markDirty();
    }

    public final void set(int i, float f) {
        this.floatValues.position(0);
        this.floatValues.put(i, f);
        this.markDirty();
    }

    public final void set(float f, float g, float h) {
        this.floatValues.position(0);
        this.floatValues.put(0, f);
        this.floatValues.put(1, g);
        this.floatValues.put(2, h);
        this.markDirty();
    }

    public final void set(Vector3f vector3f) {
        this.floatValues.position(0);
        vector3f.get(this.floatValues);
        this.markDirty();
    }

    public final void set(float f, float g, float h, float i) {
        this.floatValues.position(0);
        this.floatValues.put(f);
        this.floatValues.put(g);
        this.floatValues.put(h);
        this.floatValues.put(i);
        this.floatValues.flip();
        this.markDirty();
    }

    public final void set(Vector4f vector4f) {
        this.floatValues.position(0);
        vector4f.get(this.floatValues);
        this.markDirty();
    }

    public final void setSafe(float f, float g, float h, float i) {
        this.floatValues.position(0);
        if (this.type >= 4) {
            this.floatValues.put(0, f);
        }

        if (this.type >= 5) {
            this.floatValues.put(1, g);
        }

        if (this.type >= 6) {
            this.floatValues.put(2, h);
        }

        if (this.type >= 7) {
            this.floatValues.put(3, i);
        }

        this.markDirty();
    }

    public final void setSafe(int i, int j, int k, int l) {
        this.intValues.position(0);
        if (this.type >= 0) {
            this.intValues.put(0, i);
        }

        if (this.type >= 1) {
            this.intValues.put(1, j);
        }

        if (this.type >= 2) {
            this.intValues.put(2, k);
        }

        if (this.type >= 3) {
            this.intValues.put(3, l);
        }

        this.markDirty();
    }

    public final void set(int i) {
        this.intValues.position(0);
        this.intValues.put(0, i);
        this.markDirty();
    }

    public final void set(int i, int j) {
        this.intValues.position(0);
        this.intValues.put(0, i);
        this.intValues.put(1, j);
        this.markDirty();
    }

    public final void set(int i, int j, int k) {
        this.intValues.position(0);
        this.intValues.put(0, i);
        this.intValues.put(1, j);
        this.intValues.put(2, k);
        this.markDirty();
    }

    public final void set(int i, int j, int k, int l) {
        this.intValues.position(0);
        this.intValues.put(0, i);
        this.intValues.put(1, j);
        this.intValues.put(2, k);
        this.intValues.put(3, l);
        this.markDirty();
    }

    public final void set(float[] fs) {
        if (fs.length < this.count) {
            VulkanMod.LOGGER.warn("Uniform.set called with a too-small value array (expected {}, got {}). Ignoring.", this.count, fs.length);
        } else {
            this.floatValues.position(0);
            this.floatValues.put(fs);
            this.floatValues.position(0);
            this.markDirty();
        }
    }

    public final void setMat2x2(float f, float g, float h, float i) {
        this.floatValues.position(0);
        this.floatValues.put(0, f);
        this.floatValues.put(1, g);
        this.floatValues.put(2, h);
        this.floatValues.put(3, i);
        this.markDirty();
    }

    public final void setMat2x3(float f, float g, float h, float i, float j, float k) {
        this.floatValues.position(0);
        this.floatValues.put(0, f);
        this.floatValues.put(1, g);
        this.floatValues.put(2, h);
        this.floatValues.put(3, i);
        this.floatValues.put(4, j);
        this.floatValues.put(5, k);
        this.markDirty();
    }

    public final void setMat2x4(float f, float g, float h, float i, float j, float k, float l, float m) {
        this.floatValues.position(0);
        this.floatValues.put(0, f);
        this.floatValues.put(1, g);
        this.floatValues.put(2, h);
        this.floatValues.put(3, i);
        this.floatValues.put(4, j);
        this.floatValues.put(5, k);
        this.floatValues.put(6, l);
        this.floatValues.put(7, m);
        this.markDirty();
    }

    public final void setMat3x2(float f, float g, float h, float i, float j, float k) {
        this.floatValues.position(0);
        this.floatValues.put(0, f);
        this.floatValues.put(1, g);
        this.floatValues.put(2, h);
        this.floatValues.put(3, i);
        this.floatValues.put(4, j);
        this.floatValues.put(5, k);
        this.markDirty();
    }

    public final void setMat3x3(float f, float g, float h, float i, float j, float k, float l, float m, float n) {
        this.floatValues.position(0);
        this.floatValues.put(0, f);
        this.floatValues.put(1, g);
        this.floatValues.put(2, h);
        this.floatValues.put(3, i);
        this.floatValues.put(4, j);
        this.floatValues.put(5, k);
        this.floatValues.put(6, l);
        this.floatValues.put(7, m);
        this.floatValues.put(8, n);
        this.markDirty();
    }

    public final void setMat3x4(float f, float g, float h, float i, float j, float k, float l, float m, float n, float o, float p, float q) {
        this.floatValues.position(0);
        this.floatValues.put(0, f);
        this.floatValues.put(1, g);
        this.floatValues.put(2, h);
        this.floatValues.put(3, i);
        this.floatValues.put(4, j);
        this.floatValues.put(5, k);
        this.floatValues.put(6, l);
        this.floatValues.put(7, m);
        this.floatValues.put(8, n);
        this.floatValues.put(9, o);
        this.floatValues.put(10, p);
        this.floatValues.put(11, q);
        this.markDirty();
    }

    public final void setMat4x2(float f, float g, float h, float i, float j, float k, float l, float m) {
        this.floatValues.position(0);
        this.floatValues.put(0, f);
        this.floatValues.put(1, g);
        this.floatValues.put(2, h);
        this.floatValues.put(3, i);
        this.floatValues.put(4, j);
        this.floatValues.put(5, k);
        this.floatValues.put(6, l);
        this.floatValues.put(7, m);
        this.markDirty();
    }

    public final void setMat4x3(float f, float g, float h, float i, float j, float k, float l, float m, float n, float o, float p, float q) {
        this.floatValues.position(0);
        this.floatValues.put(0, f);
        this.floatValues.put(1, g);
        this.floatValues.put(2, h);
        this.floatValues.put(3, i);
        this.floatValues.put(4, j);
        this.floatValues.put(5, k);
        this.floatValues.put(6, l);
        this.floatValues.put(7, m);
        this.floatValues.put(8, n);
        this.floatValues.put(9, o);
        this.floatValues.put(10, p);
        this.floatValues.put(11, q);
        this.markDirty();
    }

    public final void setMat4x4(float f, float g, float h, float i, float j, float k, float l, float m, float n, float o, float p, float q, float r, float s, float t, float u) {
        this.floatValues.position(0);
        this.floatValues.put(0, f);
        this.floatValues.put(1, g);
        this.floatValues.put(2, h);
        this.floatValues.put(3, i);
        this.floatValues.put(4, j);
        this.floatValues.put(5, k);
        this.floatValues.put(6, l);
        this.floatValues.put(7, m);
        this.floatValues.put(8, n);
        this.floatValues.put(9, o);
        this.floatValues.put(10, p);
        this.floatValues.put(11, q);
        this.floatValues.put(12, r);
        this.floatValues.put(13, s);
        this.floatValues.put(14, t);
        this.floatValues.put(15, u);
        this.markDirty();
    }

    public final void set(Matrix4f matrix4f) {
        this.floatValues.position(0);
        matrix4f.get(this.floatValues);
        this.markDirty();
    }

    public final void set(Matrix3f matrix3f) {
        this.floatValues.position(0);
        matrix3f.get(this.floatValues);
        this.markDirty();
    }

    public void upload() {
        Renderer renderer = Renderer.getInstance();
        Pipeline boundPipeline = renderer.getBoundPipeline();

//        ci.cancel();

        VkGlProgram program = VkGlProgram.getBoundProgram();

        if (program == null) {
            return;
        }

        // Update the descriptor sets of the pipeline being applied. The old
        // boundPipeline comparison was evaluated before bindPipeline() had run,
        // so the FIRST draw of a pipeline (e.g. the item pipeline after the GUI
        // panel) skipped the UBO update and drew with the identity MVP.
        Pipeline pipeline = program.getPipeline();
        renderer.uploadAndBindUBOs(pipeline);
        // $FF: Couldn't be decompiled
    }

    private void uploadAsInteger() {
        // $FF: Couldn't be decompiled
    }

    private void uploadAsFloat() {
        // $FF: Couldn't be decompiled
    }

    private void uploadAsMatrix() {
        // $FF: Couldn't be decompiled
    }

    public int getLocation() {
        return this.location;
    }

    public int getCount() {
        return this.count;
    }

    public int getType() {
        return this.type;
    }

    public IntBuffer getIntBuffer() {
        return this.intValues;
    }

    public FloatBuffer getFloatBuffer() {
        return this.floatValues;
    }
}
