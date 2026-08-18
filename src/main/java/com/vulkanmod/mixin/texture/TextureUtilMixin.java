package com.yuhan123.vulkanmod.mixin.texture;

import com.yuhan123.vulkanmod.gl.VkGlTexture;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureUtil;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.nio.IntBuffer;

import static net.minecraft.client.renderer.GlStateManager.bindTexture;

@Mixin(TextureUtil.class)
public class TextureUtilMixin {
    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int glGenTextures() {
        //RenderSystem.assertOnRenderThread();
        return VkGlTexture.genTextureId();
    }

    /**
     * @author
     */

    @Overwrite
    public static void allocateTextureImpl(int glTextureId, int mipmapLevels, int width, int height)
    {
        synchronized (net.minecraftforge.fml.client.SplashProgress.class)
        {
            bindTexture(glTextureId);
        }
        if (mipmapLevels >= 0)
        {
            GlStateManager.glTexParameteri(3553, 33085, mipmapLevels);
            GlStateManager.glTexParameteri(3553, 33082, 0);
            GlStateManager.glTexParameteri(3553, 33083, mipmapLevels);
            GlStateManager.glTexParameterf(3553, 34049, 0.0F);
        }

        for (int i = 0; i <= mipmapLevels; i++)
        {
            GlStateManager.glTexImage2D(3553, i, 6408, width >> i, height >> i, 0, 32993, 33639, null);
        }
    }

}
