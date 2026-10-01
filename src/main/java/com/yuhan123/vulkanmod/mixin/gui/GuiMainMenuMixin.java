package com.yuhan123.vulkanmod.mixin.gui;

import com.yuhan123.vulkanmod.VKProf;
import com.yuhan123.vulkanmod.config.VulkanModConfig;
import com.yuhan123.vulkanmod.VulkanMod;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.world.WorldSettings;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(GuiMainMenu.class)
public abstract class GuiMainMenuMixin extends GuiScreen {

    /**
     * Headless-bench hook: with AUTOJOIN=1, load the "New World" save
     * 4 seconds after the main menu appears so automated runClient measurements
     * reach an in-world scene without manual interaction. No-op otherwise.
     */
    @Unique
    private static boolean vulkanmod$autoJoinFired = false;
    @Unique
    private long vulkanmod$shownAt;

    @Inject(method = "initGui", at = @At("TAIL"))
    private void vulkanmod$recordShown(CallbackInfo ci) {
        this.vulkanmod$shownAt = System.currentTimeMillis();
    }

    @Inject(method = "drawScreen", at = @At("TAIL"))
    private void vulkanmod$autoJoin(CallbackInfo ci) {
        if (vulkanmod$autoJoinFired || !VulkanModConfig.getBool("AUTOJOIN", false)) {
            return;
        }

        if (this.vulkanmod$shownAt == 0) {
            this.vulkanmod$shownAt = System.currentTimeMillis();
        }

        if (System.currentTimeMillis() - this.vulkanmod$shownAt < 4_000) {
            return;
        }

        vulkanmod$autoJoinFired = true;
        VKProf.info("[VKPROF] auto-joining save 'New World'");
        this.mc.launchIntegratedServer("New World", "New World", (WorldSettings) null);
    }

    @ModifyArgs(method = "renderSkybox", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;viewport(IIII)V"))
    public void setViewport(Args args) {
        args.set(2, Display.getWidth());
        args.set(3, Display.getHeight());
    }

    @Inject(method = "drawPanorama", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;enableBlend()V"))
    public void correctRotation(CallbackInfo ci) {
        GlStateManager.rotate(-90.0F, 0.0F, 0.0F, 1.0F);
    }
}
