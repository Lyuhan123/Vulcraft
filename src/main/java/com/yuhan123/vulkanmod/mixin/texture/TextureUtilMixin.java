package com.yuhan123.vulkanmod.mixin.texture;

import com.yuhan123.vulkanmod.gl.VkGlTexture;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureUtil;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

import java.nio.IntBuffer;

import static net.minecraft.client.renderer.GlStateManager.bindTexture;

@Mixin(TextureUtil.class)
public class TextureUtilMixin {
    /**
     * @author
     */
    @Overwrite
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

        // TEMPORARY: uploadTextureImageSub is what actually FILLS the image we
        // just allocated here. A 64x32 entity skin that reaches glTexImage2D but
        // never reaches uploadTextureImageSub is allocated-and-empty, which is
        // the "mob renders black" fingerprint. Trace the handoff.
        com.yuhan123.vulkanmod.vulkan.texture.TextureProbe.onAllocateImpl(glTextureId, width, height);
    }

    /**
     * TEMPORARY: the fill half of {@link #allocateTextureImpl}. If this fires for
     * a mob-skin id then the image really is filled and the bug is elsewhere; if
     * it never fires for one, the load aborted between the two calls.
     */
    @Inject(method = "uploadTextureImageSub", at = @At("HEAD"))
    private static void vulkanmod$onUploadSubHead(int textureId, java.awt.image.BufferedImage image, int x, int y,
                                                  boolean blur, boolean clamp, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
        com.yuhan123.vulkanmod.vulkan.texture.TextureProbe.onUploadImageSub(textureId, "HEAD");
    }

    @Inject(method = "uploadTextureImageSub", at = @At("RETURN"))
    private static void vulkanmod$onUploadSubReturn(int textureId, java.awt.image.BufferedImage image, int x, int y,
                                                    boolean blur, boolean clamp, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
        com.yuhan123.vulkanmod.vulkan.texture.TextureProbe.onUploadImageSub(textureId, "RETURN");
    }

}
