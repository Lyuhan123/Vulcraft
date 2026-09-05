package com.yuhan123.vulkanmod.mixin.gui;

import com.yuhan123.vulkanmod.VulkanMod;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.world.WorldSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiMainMenu.class)
public abstract class GuiMainMenuMixin extends GuiScreen {

    /**
     * Headless-bench hook: with VULKANMOD_AUTOJOIN=1, load the "New World" save
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
        if (vulkanmod$autoJoinFired || !"1".equals(System.getenv("VULKANMOD_AUTOJOIN"))) {
            return;
        }

        if (this.vulkanmod$shownAt == 0) {
            this.vulkanmod$shownAt = System.currentTimeMillis();
        }

        if (System.currentTimeMillis() - this.vulkanmod$shownAt < 4_000) {
            return;
        }

        vulkanmod$autoJoinFired = true;
        VulkanMod.LOGGER.info("[VKPROF] auto-joining save 'New World'");
        this.mc.launchIntegratedServer("New World", "New World", (WorldSettings) null);
    }
}
