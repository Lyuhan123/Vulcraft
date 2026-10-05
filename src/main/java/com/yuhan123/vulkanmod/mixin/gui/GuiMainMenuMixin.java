package com.yuhan123.vulkanmod.mixin.gui;

import com.yuhan123.vulkanmod.VKProf;
import com.yuhan123.vulkanmod.config.VulkanModConfig;
import com.yuhan123.vulkanmod.VulkanMod;
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
        this.mc.launchIntegratedServer("New World", "New World", (net.minecraft.world.WorldSettings) null);
    }

    /**
     * Sharp main-menu panorama.
     *
     * <p>Vanilla renders the panorama through {@code viewport(0,0,256,256)}, then
     * pulls exactly that 256x256 square out of the framebuffer with
     * {@code glCopyTexSubImage2D(...,256,256)} into {@code backgroundTexture}
     * (a 256x256 DynamicTexture), blurs it 7 times, and finally stretches
     * {@code backgroundTexture} across the whole screen. That upscaled 256x256
     * block is what reads as "too blurry".
     *
     * <p>We replace {@code renderSkybox} entirely: paint {@code drawPanorama}
     * straight into the game framebuffer at native resolution (real window
     * viewport, real aspect), and skip the 256x256 copy / blur / blit. The GUI
     * gradient + title composite on top in {@code drawScreen} exactly as before,
     * so the result is a crisp, full-resolution background with no black corner.
     */
    /**
     * Exposes the private {@code drawPanorama} so this mixin can invoke it after
     * cancelling the original renderSkybox. Generated on the target class; the
     * call still runs drawPanorama with all of its own injections (the
     * gluPerspective redirect + the X-tilt) applied.
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
     * Per the user's request: roll the captured panorama -90 degrees about the
     * X axis so the background faces a different (sky-heavy) direction. Applied
     * once to the shared modelview, right after vanilla's own
     * {@code rotate(180,1,0,0)} + {@code rotate(90,0,0,1)} base orientation and
     * before the six cube faces are drawn.
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
     * <p>The mixin meant to hook that call ({@code gl.ProjectMixin}, on
     * {@code org.lwjgl.util.glu.Project}) never applies here: the mixin platform
     * cannot resolve that class in this Cleanroom/lwjglx setup, logs
     * "@Mixin target org.lwjgl.util.glu.Project was not found" once at startup,
     * and skips it permanently. The real gluPerspective therefore runs, writes
     * nothing into {@link MatrixState}, and the six panorama faces are drawn with
     * whatever projection was current - the GUI ortho. A unit cube under an ortho
     * that maps GUI pixels lands outside the frustum, so all six faces are
     * clipped: the panorama is drawn but never rasterized and the menu shows only
     * the two gradient strips. This has to be written at the call site, not at
     * drawPanorama HEAD: vanilla does {@code matrixMode(GL_PROJECTION)} +
     * {@code loadIdentity()} immediately before the gluPerspective call, so
     * anything written earlier is erased by that loadIdentity.
     *
     * <p>The aspect ratio passed is the REAL window aspect, not vanilla's
     * hard-coded 1.0: with a full-screen viewport a 1.0 aspect would horizontally
     * stretch the panorama on a wide monitor.
     */
//    @Redirect(method = "drawPanorama",
//            at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V"),
//            remap = false)
//    private static void vulkanmod$panoramaPerspective(float fovy, float aspect, float zNear, float zFar) {
//        MatrixState.perspective(fovy, (float) Display.getWidth() / (float) Display.getHeight(), zNear, zFar);
//    }
}
