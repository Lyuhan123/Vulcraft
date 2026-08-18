package com.yuhan123.vulkanmod.mixin.gl;

import com.yuhan123.vulkanmod.gl.VkGlBuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.ARBVertexBufferObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

// 1.12.2's OpenGlHelper dispatches VBO calls to the ARB extension when the
// GL_ARB_vertex_buffer_object flag is set (the common case on llvmpipe and many
// Windows GL drivers), so intercept the ARB entry points as well.
@Mixin(ARBVertexBufferObject.class)
public class ARBVertexBufferObjectMixin {

    @Overwrite(remap = false)
    public static int glGenBuffersARB() {
        return VkGlBuffer.glGenBuffers();
    }

    @Overwrite(remap = false)
    public static void glBindBufferARB(int target, int buffer) {
        VkGlBuffer.glBindBuffer(target, buffer);
    }

    @Overwrite(remap = false)
    public static void glBufferDataARB(int target, ByteBuffer data, int usage) {
        VkGlBuffer.glBufferData(target, data, usage);
    }

    @Overwrite(remap = false)
    public static void glBufferDataARB(int target, long size, int usage) {
        VkGlBuffer.glBufferData(target, size, usage);
    }

    @Overwrite(remap = false)
    public static void glDeleteBuffersARB(int buffer) {
        VkGlBuffer.glDeleteBuffers(buffer);
    }

    @Overwrite(remap = false)
    public static void glDeleteBuffersARB(@Nullable IntBuffer buffers) {
        VkGlBuffer.glDeleteBuffers(buffers);
    }
}
