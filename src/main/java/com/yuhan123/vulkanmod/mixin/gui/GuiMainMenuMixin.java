package com.yuhan123.vulkanmod.mixin.gui;

import com.yuhan123.vulkanmod.VKProf;
import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.config.VulkanModConfig;
import com.yuhan123.vulkanmod.gl.MatrixState;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
        // NOTE: passing null WorldSettings makes vanilla launchIntegratedServer do
        // `settings = new WorldSettings(worldInfo)` with a null worldInfo for a
        // *fresh* save -> NPE in WorldSettings.<init>. Always pass a real settings.
        net.minecraft.world.WorldSettings vulkanmod$ws = new net.minecraft.world.WorldSettings(
                123456789L,
                net.minecraft.world.GameType.SURVIVAL,
                false,
                true,
                net.minecraft.world.WorldType.DEFAULT);
        this.mc.launchIntegratedServer("New World", "New World", vulkanmod$ws);
    }

    /**
     * Sharp main-menu panorama — the initial / clear state.
     *
     * <p>Vanilla bakes the panorama into a 256x256 DynamicTexture: it renders into a
     * 256x256 viewport, copies that square out of the framebuffer with
     * {@code glCopyTexSubImage2D}, blurs it 7 times, then stretches the 256x256
     * texture across the whole window. That upscaled block is what reads as a soft
     * blur (and, under Vulkan, as grain/mush when the copy is taken wrong).
     *
     * <p>We instead cancel {@code renderSkybox} and paint {@code drawPanorama}
     * straight into the game framebuffer at native resolution — a real window-sized
     * viewport with the real (window) aspect — so the background is crisp and
     * full-resolution with no upscaling and no black corner. {@code drawScreen}
     * still composites the GUI gradient + title on top exactly as before.
     */
    @Invoker("drawPanorama")
    public abstract void vulkanmod$drawPanorama(int mouseX, int mouseY, float partialTicks);

    @Inject(method = "renderSkybox", at = @At("HEAD"), cancellable = true)
    private void vulkanmod$renderSkyboxSharp(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        ci.cancel();
        this.mc.getFramebuffer().bindFramebuffer(true);
        GlStateManager.viewport(0, 0, Display.getWidth(), Display.getHeight());
        this.vulkanmod$drawPanorama(mouseX, mouseY, partialTicks);
        GlStateManager.viewport(0, 0, this.mc.displayWidth, this.mc.displayHeight);
    }

    /**
     * Depth test off for the panorama.
     *
     * <p>Under Vulkan there is only one swapchain depth buffer shared by the
     * panorama and the GUI; the previous frame's GUI has already written depths
     * around ndc z ~0 there, while a unit cube at the camera with
     * {@code gluPerspective(120, 1, 0.05, 10)} lands at ndc z ~0.91 and the enabled
     * {@code GL_LEQUAL} test rejects the entire panorama (the 256x256 copy then
     * picks up whatever was underneath — a flat colour). Disabling the test
     * reproduces vanilla's effective behaviour (vanilla draws the panorama into a
     * separate default framebuffer whose depth is clean). {@code drawPanorama}
     * re-enables depth at its own tail, so nothing leaks.
     */
    @Inject(method = "drawPanorama", at = @At("HEAD"))
    private void vulkanmod$panoramaNoDepth(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        GlStateManager.disableDepth();
    }

    /**
     * Roll the panorama -90 degrees about the Z axis so the background faces a
     * different (sky-heavy) direction. Applied once, right after vanilla's own
     * base orientation ({@code rotate(180,1,0,0)} then {@code rotate(90,0,0,1)})
     * and before the six cube faces are drawn. (User request: "reveal sky and
     * distant view".)
     */
    @Inject(method = "drawPanorama",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;rotate(FFFF)V",
                    ordinal = 1))
    private void vulkanmod$panoramaTilt(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        GlStateManager.rotate(-90.0F, 0.0F, 0.0F, 1.0F);
    }

    /**
     * Supplies the panorama's projection, replacing
     * {@code Project.gluPerspective(120, 1, 0.05, 10)}.
     *
     * <p>{@code gl.ProjectMixin} (an {@code @Overwrite} on
     * {@code org.lwjgl.util.glu.Project}) does not apply in this Cleanroom/lwjglx
     * setup — the mixin platform cannot resolve that class, logs the usual "was not
     * found" line once at startup and skips it permanently. The real
     * {@code gluPerspective} therefore writes nothing into {@link MatrixState}, and
     * the six cube faces would be drawn under the GUI ortho, so a unit cube lands
     * outside the frustum and every face is clipped (menu shows only the gradient
     * strips). Hence this {@code @Redirect} at the call site, the same technique
     * {@code EntityRendererMixin} uses for the cloud pass.
     *
     * <p>It cannot be injected at {@code drawPanorama} HEAD instead: vanilla calls
     * {@code matrixMode(GL_PROJECTION)} + {@code loadIdentity()} immediately before
     * the gluPerspective, which erases anything written earlier.
     *
     * <p>The aspect is the REAL window aspect, NOT vanilla's hard-coded 1.0: with a
     * full-screen viewport a 1.0 aspect would horizontally stretch the panorama on
     * a wide monitor.
     */

}
