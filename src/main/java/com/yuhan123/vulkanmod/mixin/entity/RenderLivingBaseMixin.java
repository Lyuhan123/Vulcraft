package com.yuhan123.vulkanmod.mixin.entity;

import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Robust mob hurt/burn flash disarm.
 *
 * <p>1.12.2 draws the flash through the fixed-function texture combiner
 * (RenderLivingBase.setBrightness uploads GL_TEXTURE_ENV_COLOR and arms the
 * flash; GlStateManagerMixin.glTexEnv captures that into VRenderSystem.flashColor).
 * The flash is GLOBAL state, so it must be cleared the instant the hurt entity is
 * done drawing or it leaks into every later entity for the rest of the frame.
 *
 * <p>unsetBrightness is called exactly once per living entity, immediately after
 * its model is drawn, regardless of whether that entity was flashing
 * (RenderLivingBase.doRender always pairs setBrightness(... , hurt) with
 * unsetBrightness()). Disarming here - at the precise semantic boundary - is
 * guaranteed to fire and removes all dependence on guessing the GL constant
 * sequence that restores the texture env mode (the fragile path in
 * GlStateManagerMixin.glTexEnvi, which is kept only as a belt-and-suspenders
 * backstop).
 */
@Mixin(RenderLivingBase.class)
public class RenderLivingBaseMixin {

    @Inject(method = "unsetBrightness", at = @At("HEAD"))
    private void vulkanmod$clearFlash(CallbackInfo ci) {
        VRenderSystem.setFlashColor(0.0f, 0.0f, 0.0f, 0.0f);
    }
}
