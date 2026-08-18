package com.yuhan123.vulkanmod.mixin.gl;

import com.yuhan123.vulkanmod.gl.VkGlBuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.nio.ByteBuffer;

@Mixin(GL30C.class)
public class GL30Mixin {
    /**
     * @author
     */
    @Overwrite(remap = false)
    @Nullable
    public static ByteBuffer glMapBufferRange(int i, long offset, long length, int j) {
        //RenderSystem.assertOnRenderThreadOrInit();
        return VkGlBuffer.glMapBuffer(i, j);
        //TODO: offset
    }
}
