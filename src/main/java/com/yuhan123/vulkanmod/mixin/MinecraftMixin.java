package com.yuhan123.vulkanmod.mixin;

import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.Vulkan;
import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;

/**
 * Vanilla mixin example
 * Refmap will be handled by Unimined automatically
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
    /**
     * Headless-bench hooks: set VULKANMOD_AUTOJOIN=1 to load the "New World"
     * save from the main menu and VULKANMOD_AUTOQUIT=1 to exit 30s after the
     * integrated server is up. Both are no-ops unless the env vars are set,
     * so normal gameplay is unaffected.
     */
    @Unique private static boolean autoQuitFired = false;
    @Unique private static long autoQuitStart = 0;
    @Unique private static boolean autoTeleported = false;
    @Unique private static final boolean BENCH_MODE = "1".equals(System.getenv("VULKANMOD_AUTOJOIN"));

    /**
     * Bench mode: never pause. In an unattended runClient the window has no
     * focus, so the "Loading terrain"/pause flow can pause the integrated
     * server, which then never sends the spawn chunks, which never closes the
     * loading screen -- a deadlock that freezes the benchmark at 25 menu draws.
     * With this override the server always ticks and the world always loads.
     */
    @Inject(method = "isGamePaused", at = @At("HEAD"), cancellable = true)
    public void benchNoPause(CallbackInfoReturnable<Boolean> cir) {
        if (BENCH_MODE) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "createDisplay", at = @At("HEAD"))
    public void preCreateDisplay(CallbackInfo ci){
        // Create the hidden GL context BEFORE lwjglx's Display.create() runs, so
        // the real GL.createCapabilities() finds a current context.
        Vulkan.ensureHiddenGLContext();
    }

    @Inject(method = "createDisplay", at = @At(value = "RETURN"))
    public void inject(CallbackInfo ci){

        // lwjglx's Display.create() may clear the current GL context (the visible
        // window is Vulkan-only); restore the hidden GL context on this thread.
        Vulkan.ensureHiddenGLContext();

        Vulkan.initVulkan(Display.getWindow());
        VulkanMod.LOGGER.info("Mixin succeed!" + Display.getWindow());
        Renderer.getInstance().beginFrame();
    }

    @Inject(method = "init", at = @At("RETURN"))
    public void endInitCmd(CallbackInfo callbackInfo) {
        Renderer.getInstance().endFrame();
    }

    @Inject(method = "shutdownMinecraftApplet", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;destroy()V", shift = At.Shift.BEFORE))
    public void cleanup(CallbackInfo ci) {

        Vulkan.cleanUp();
    }

    @Inject(method = "runGameLoop", at = @At("HEAD"))
    public void beginRendering(CallbackInfo ci) {
        Vulkan.ensureHiddenGLContext();

        Minecraft mc = Minecraft.getMinecraft();
        if (!autoQuitFired && "1".equals(System.getenv("VULKANMOD_AUTOQUIT"))
                && mc.getIntegratedServer() != null) {
            // Teleport to the fixed benchmark viewpoint (the forest scene from
            // the user's day/day2 RenderDoc captures) so A/B runs compare the
            // same camera. Wait until the "Loading terrain" screen is gone --
            // teleporting during it races the server's initial spawn placement
            // and can hang the loading screen forever. Bench mode only.
            if (!autoTeleported && mc.player != null && mc.currentScreen == null) {
                autoTeleported = true;
                // Fixed position AND view angles: the rotation otherwise comes
                // from whatever the save captured at exit, which made every
                // benchmark run measure a different scene. Also hide the HUD:
                // the ~1300 GUI draw calls then drop out of the measurement,
                // which splits GPU/CPU cost into terrain vs GUI shares.
                mc.player.setLocationAndAngles(320.5, 67.0, -327.5, 180.0f, 15.0f);
                mc.player.setPositionAndUpdate(320.5, 67.0, -327.5);
                VulkanMod.LOGGER.info("[VKPROF] teleported to benchmark viewpoint");
            }

            if (autoQuitStart == 0) {
                autoQuitStart = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - autoQuitStart > 30_000) {
                autoQuitFired = true;
                VulkanMod.LOGGER.info("[VKPROF] auto-quit after 30s in world");
                mc.shutdown();
            }
        }

        // Reset the per-frame vertex/index/UBO buffers BEFORE recording the
        // frame's draws. Without this the Drawer's usedBytes accumulates across
        // frames, so the buffers double in size every frame until the GPU runs
        // out of memory (VK_ERROR_OUT_OF_DEVICE_MEMORY) and the game stalls.
        Renderer.getInstance().preInitFrame();

        Renderer.getInstance().beginFrame();
    }

    @Inject(method = "runGameLoop", at = @At("RETURN"))
    public void endRendering(CallbackInfo ci) {
        Renderer.getInstance().endFrame();
    }

    @Inject(method = "runTick", at = @At("HEAD"))
    public void benchTickStart(CallbackInfo ci) {
        FrameProfiler.beginGameTick();
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    public void benchTickEnd(CallbackInfo ci) {
        FrameProfiler.endGameTick();
    }
}
