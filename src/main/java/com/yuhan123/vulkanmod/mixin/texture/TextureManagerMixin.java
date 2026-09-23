package com.yuhan123.vulkanmod.mixin.texture;

import com.yuhan123.vulkanmod.gl.VkGlTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pass 32: clamp the entity-shadow texture, and only that one.
 *
 * <p>The shadow is drawn by vanilla {@code Render.renderShadow}: it walks every
 * block column under the entity and, for each, draws a {@code 2f x 2f} quad
 * whose UVs are <b>derived from the entity's world position</b>. Every column
 * except the entity's own therefore maps well outside {@code [0,1]} into
 * {@code shadow.png}. Whether that looks like one soft circle or a tiled grid of
 * them is decided entirely by the sampler's address mode - wrap repeats the
 * circle into every column (a grid), clamp sends the far columns to the
 * transparent border (a single circle).
 *
 * <p>Vanilla's own {@code TextureUtil.uploadTextureImageSub(..., clamp)} chooses
 * that mode, and the port was turning up REPEAT for this texture. Rather than
 * guess which upload path left it that way, the bind site is the one place that
 * unambiguously identifies <i>this</i> texture, so the clamp is asserted there.
 * Scoped to {@code textures/misc/shadow.png} deliberately: a global clamp also
 * changes the sky and terrain and makes any comparison unreadable (measured -
 * pass 32's first attempt).
 *
 * <p>Cheap enough to run every frame: {@code VkGlTexture.texParameteri} only
 * rebuilds the sampler when the value actually changes.
 */
@Mixin(TextureManager.class)
public class TextureManagerMixin {

    @Unique
    private static final ResourceLocation VULKANMOD_SHADOW_TEXTURE =
            new ResourceLocation("textures/misc/shadow.png");

    @Inject(method = "bindTexture(Lnet/minecraft/util/ResourceLocation;)V", at = @At("TAIL"))
    private void vulkanmod$clampShadowTexture(ResourceLocation location, CallbackInfo ci) {
        if (VULKANMOD_SHADOW_TEXTURE.equals(location)) {
            VkGlTexture.vulkanmod$clampShadowTexture();
        }
    }
}
