package com.yuhan123.vulkanmod.mixin.gui;

import com.yuhan123.vulkanmod.VKProf;
import com.yuhan123.vulkanmod.config.VulkanModConfig;
import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.gl.MatrixState;
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

    // DISABLED (was: @ModifyArgs forcing viewport(0,0,256,256) -> (0,0,Display.w,h)).
    //
    // renderSkybox draws the panorama through viewport(0,0,256,256) and then pulls
    // exactly that region back out of the framebuffer with glCopyTexSubImage2D
    // (0,0,256,256). The two only line up when the viewport really is the bottom
    // left 256x256: Renderer.setViewport maps GL's bottom-left origin with
    // `viewport.y = targetHeight - y` (480 -> rows 224..480) and
    // copyTexSubImage2D maps it with `srcY = height - sh - y` (224) - the same
    // rows. Forcing the viewport to full screen therefore does not "fix" the
    // alignment, it destroys it: the panorama is painted across the whole screen
    // while the copy still only takes the bottom-left 256x256, so backgroundTexture
    // receives a mostly-black corner instead of the panorama and the menu loses its
    // background (only the gradient survives). The override was written back when
    // setViewport still used the buggy `height + y` mapping, which put the panorama
    // in the TOP 256 rows; now that the mapping is correct it is redundant.
//
//    @ModifyArgs(method = "renderSkybox", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;viewport(IIII)V"))
//    public void setViewport(Args args) {
//        args.set(2, Display.getWidth());
//        args.set(3, Display.getHeight());
//    }

    // TEMP DIAGNOSTIC (remove): trace the panorama pipeline for the first few
    // menu frames so "panorama not drawn" and "drawn but not composited" can be
    // told apart from a dump instead of guessed.
    @Unique private static int vulkanmod$skyboxFrames = 0;
    @Unique private static boolean vulkanmod$panoDumped = false;

    // TEMP DIAGNOSTIC (remove): run only ONE of the seven blur iterations so the
    // single-copy panorama can be looked at directly. Seven accumulating passes
    // over a wrong copy region is what produces a uniform wash, and that makes it
    // impossible to tell "the copy is wrong" from "the accumulation saturates".
    @Unique private static int vulkanmod$blurCount = 0;

    @Inject(method = "renderSkybox", at = @At("HEAD"))
    private void vulkanmod$resetBlur(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        vulkanmod$blurCount = 0;
    }

    @Inject(method = "rotateAndBlurSkybox", at = @At("HEAD"), cancellable = true)
    private void vulkanmod$limitBlur(CallbackInfo ci) {
        if (++vulkanmod$blurCount > 1) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSkybox", at = @At("HEAD"))
    private void vulkanmod$traceSkyboxHead(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (vulkanmod$skyboxFrames > 4) {
            return;
        }
        VulkanMod.LOGGER.info("[VKPROF] renderSkybox#{} head: Display={}x{}  mcDisplay={}x{}  scaled={}x{}",
                vulkanmod$skyboxFrames, Display.getWidth(), Display.getHeight(),
                this.mc.displayWidth, this.mc.displayHeight, this.width, this.height);
    }

    @Inject(method = "drawPanorama", at = @At("RETURN"))
    private void vulkanmod$tracePanoReturn(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (vulkanmod$panoDumped) {
            return;
        }
        vulkanmod$panoDumped = true;
        VulkanMod.LOGGER.info("[VKPROF] drawPanorama returned");
        com.yuhan123.vulkanmod.vulkan.texture.ImageUtil.dumpMainTargetPng("menu_pano_after_draw.png");
    }

    @Inject(method = "renderSkybox", at = @At("TAIL"))
    private void vulkanmod$traceSkyboxTail(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (vulkanmod$skyboxFrames > 4) {
            return;
        }
        VulkanMod.LOGGER.info("[VKPROF] renderSkybox#{} tail", vulkanmod$skyboxFrames);
        if (vulkanmod$skyboxFrames == 4) {
            com.yuhan123.vulkanmod.vulkan.texture.ImageUtil.dumpMainTargetPng("menu_pano_after_skybox.png");
        }
        vulkanmod$skyboxFrames++;
    }

    // DISABLED: vanilla drawPanorama already applies rotate(90,0,0,1) while
    // building the cube's orientation; adding -90 about the same axis cancels it
    // and rolls the captured view onto a different (sky-heavy) face. Kept here
    // because it was part of the revision believed to render the background.
//    @Inject(method = "drawPanorama", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;enableBlend()V"))
//    public void correctRotation(CallbackInfo ci) {
//        GlStateManager.rotate(-90.0F, 0.0F, 0.0F, 1.0F);
//    }

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
     * clipped: the panorama is drawn but never rasterized, the backbuffer stays
     * black, and the menu shows only the two gradient strips. That is the
     * "只剩渐变 / no background" report.
     *
     * <p>This has to be written at the call site, not at drawPanorama HEAD:
     * vanilla does {@code matrixMode(GL_PROJECTION)} + {@code loadIdentity()}
     * immediately before the gluPerspective call, so anything written earlier is
     * erased by that loadIdentity.
     */
    @Redirect(method = "drawPanorama",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V"),
            remap = false)
    private static void vulkanmod$panoramaPerspective(float fovy, float aspect, float zNear, float zFar) {
        VulkanMod.LOGGER.info("[VKPROF] panoramaPerspective fired fovy={} aspect={} near={} far={}",
                fovy, aspect, zNear, zFar);
        MatrixState.perspective(fovy, aspect, zNear, zFar);
        MatrixState.traceNextProjection = true;
    }
}
