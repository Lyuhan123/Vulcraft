package com.yuhan123.vulkanmod.mixin;

import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Splits EntityRenderer.updateCameraAndRender - the whole `gameRenderer`
 * profiler section - into three timed pieces, and periodically logs the frame
 * state.
 *
 * Minecraft's own profiler leaves ~17% of `gameRenderer` in its "unspecified"
 * bucket: real time spent in this method but not in any of the sections it
 * opens (`mouse`, `level`, `gui`). The three timers say which of the gaps
 * around those sections it lands in - before `renderWorld` (Display.isActive,
 * ScaledResolution, Mouse.getX/getY), inside it, or after it (the screen
 * render, which runs outside any section).
 *
 * The state log covers the same question from the other side: if the benchmark
 * window is not "active" with pauseOnLostFocus on, vanilla calls
 * displayInGameMenu() every single frame, which would both explain a large
 * unattributed block and mean the benchmark is measuring a paused game.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {

    private static long lastStateLog;

    @Inject(method = "updateCameraAndRender(FJ)V", at = @At("HEAD"))
    private void vulkanmod$beginCameraAndRender(float partialTicks, long nanoTime, CallbackInfo ci) {
        FrameProfiler.beginCameraAndRender();

        if (!FrameProfiler.PROFILE_DUMP) {
            return;
        }

        final long now = System.currentTimeMillis();
        if (now - lastStateLog < 3000L) {
            return;
        }
        lastStateLog = now;

        Minecraft mc = Minecraft.getMinecraft();
        VulkanMod.LOGGER.info("[VKSTATE] active={} screen={} skipRenderWorld={} pauseOnLostFocus={} shaders={}",
                Display.isActive(),
                mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName(),
                mc.skipRenderWorld,
                mc.gameSettings.pauseOnLostFocus,
                OpenGlHelper.shadersSupported);
    }

    @Inject(method = "updateCameraAndRender(FJ)V", at = @At("RETURN"))
    private void vulkanmod$endCameraAndRender(float partialTicks, long nanoTime, CallbackInfo ci) {
        FrameProfiler.endCameraAndRender();
    }

    @Inject(method = "renderWorld(FJ)V", at = @At("HEAD"))
    private void vulkanmod$beginRenderWorld(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        FrameProfiler.beginRenderWorld();
    }

    @Inject(method = "renderWorld(FJ)V", at = @At("RETURN"))
    private void vulkanmod$endRenderWorld(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        FrameProfiler.endRenderWorld();
    }

    @Inject(method = "updateLightmap(F)V", at = @At("HEAD"))
    private void vulkanmod$beginLightmap(float partialTicks, CallbackInfo ci) {
        FrameProfiler.beginLightmap();
    }

    @Inject(method = "updateLightmap(F)V", at = @At("RETURN"))
    private void vulkanmod$endLightmap(float partialTicks, CallbackInfo ci) {
        FrameProfiler.endLightmap();
    }

    @Inject(method = "getMouseOver(F)V", at = @At("HEAD"))
    private void vulkanmod$beginMouseOver(float partialTicks, CallbackInfo ci) {
        FrameProfiler.beginMouseOver();
    }

    @Inject(method = "getMouseOver(F)V", at = @At("RETURN"))
    private void vulkanmod$endMouseOver(float partialTicks, CallbackInfo ci) {
        FrameProfiler.endMouseOver();
    }

    @Inject(method = "renderWorldPass(IFJ)V", at = @At("HEAD"))
    private void vulkanmod$beginWorldPass(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        FrameProfiler.beginWorldPass();
    }

    @Inject(method = "renderWorldPass(IFJ)V", at = @At("RETURN"))
    private void vulkanmod$endWorldPass(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        FrameProfiler.endWorldPass();
    }
}
