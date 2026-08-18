package com.yuhan123.vulkanmod.mixin.gui;

import net.minecraft.client.gui.GuiMainMenu;
import org.lwjgl.opengl.ContextCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GuiMainMenu.class)
public class GuiMainMenuMixin {
//
//    @Redirect(method = "<init>", at = @At(value = "FIELD", target = "Lorg/lwjgl/opengl/ContextCapabilities;OpenGL20:Z"))
//    public boolean gl20(ContextCapabilities instance) {
//        return false;
//    }
//
//    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GLContext;getCapabilities()Lorg/lwjgl/opengl/ContextCapabilities;"))
//    public ContextCapabilities context() {
//        return null;
//    }
//
//    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/OpenGlHelper;areShadersSupported()Z"))
//    public boolean areShadersSupported() {
//        return false;
//    }
//
//    /**
//     * The Vulkan port does not support the panorama blur FBO path yet;
//     * skip the skybox so the menu still renders (gradient + logo + buttons).
//     */
//    @Overwrite(remap = false)
//    private void renderSkybox(int p_73971_1_, int p_73971_2_, float p_73971_3_) {
//    }
}
