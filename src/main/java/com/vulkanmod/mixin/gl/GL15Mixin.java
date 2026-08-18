package com.yuhan123.vulkanmod.mixin.gl;

import com.yuhan123.vulkanmod.gl.VkGlBuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL15;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

@Mixin(GL15.class)
public class GL15Mixin {



    /**
     * @author
     */
    @Overwrite(remap = false)
    public static boolean glUnmapBuffer(int i) {
        //RenderSystem.assertOnRenderThread();
        return VkGlBuffer.glUnmapBuffer(i);
    }

    // 1.12.2 goes through OpenGlHelper -> GL15 for VBOs; intercept the GL15 calls
    // so the chunk vertex data lands in the CPU-side VkGlBuffer.
    @Overwrite(remap = false)
    public static int glGenBuffers() {
        return VkGlBuffer.glGenBuffers();
    }

    @Overwrite(remap = false)
    public static void glBindBuffer(int target, int buffer) {
        VkGlBuffer.glBindBuffer(target, buffer);
    }

    @Overwrite(remap = false)
    public static void glBufferData(int target, ByteBuffer data, int usage) {
        VkGlBuffer.glBufferData(target, data, usage);
    }

    @Overwrite(remap = false)
    public static void glBufferData(int target, long size, int usage) {
        VkGlBuffer.glBufferData(target, size, usage);
    }

    @Overwrite(remap = false)
    public static void glDeleteBuffers(int buffer) {
        VkGlBuffer.glDeleteBuffers(buffer);
    }

    @Overwrite(remap = false)
    public static void glDeleteBuffers(@Nullable IntBuffer buffers) {
        VkGlBuffer.glDeleteBuffers(buffers);
    }
}
