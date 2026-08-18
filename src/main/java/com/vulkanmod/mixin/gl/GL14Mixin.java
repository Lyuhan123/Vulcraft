package com.yuhan123.vulkanmod.mixin.gl;

import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
import org.lwjgl.opengl.GL14;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(GL14.class)
public class GL14Mixin {
    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void glBlendFuncSeparate(int i, int j, int k, int l) {
        //RenderSystem.assertOnRenderThread();
        VRenderSystem.blendFuncSeparate(i, j, k, l);
    }
}
