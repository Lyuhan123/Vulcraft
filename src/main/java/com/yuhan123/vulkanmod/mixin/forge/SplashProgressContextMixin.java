package com.yuhan123.vulkanmod.mixin.forge;

import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.vulkan.SplashPresenter;
import net.minecraftforge.fml.client.SplashProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the Forge loading splash alive under Vulkan.
 *
 * <p>{@code SplashProgress.start()} wants a shared GL context for its render
 * thread: {@code new SharedDrawable(...)}. Under Vulkan that hidden window
 * cannot be created (the only window is {@code GLFW_NO_API}), so the
 * constructor throws {@code LWJGLException} - which is caught, but the catch
 * handler funnels it into {@code disableSplash()}, and that method
 * <b>re-throws</b> ({@code SplashProgress$4}), killing the game before the
 * splash thread is even created.
 *
 * <p>The shared drawable is never actually needed here: the splash thread's
 * GL11 calls are all routed to the Vulkan Drawer by {@code GL11Mixin}, and
 * {@code SplashProgress$2.run()} never touches {@code SplashProgress.d}
 * (its {@code setGL()} context switch is a no-op because GLFWMixin cancels
 * {@code glfwMakeContextCurrent} for every window except the hidden one).
 * {@code finish()} tolerates {@code d == null} (NPE caught by its own
 * catch-all). So swallowing the fatal "disable" lets {@code start()} fall
 * through to creating the thread, and the splash renders - presented frame by
 * frame by {@code SplashProgressMixin} via {@code Renderer.beginFrame()/endFrame()}.
 */
@Mixin(SplashProgress.class)
public class SplashProgressContextMixin {

    private static boolean vulcraft$logged = false;

    /**
     * Arm splash presenting just before the splash thread is started: from then
     * on the main thread presents from FML's progress callbacks (the splash
     * thread only records draws), and {@code finish()} disarms it again.
     * Injecting before {@code Thread.start()} also guarantees no splash draw can
     * happen before the flag is up.
     */
    @Inject(method = "start", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;start()V",
            shift = At.Shift.BEFORE), remap = false)
    private static void vulcraft$armMainPresent(CallbackInfo ci) {
        SplashPresenter.setMainPresentEnabled(true);
        VulkanMod.LOGGER.info("[Splash] main-thread presenting armed");
    }

    @Inject(method = "finish", at = @At("HEAD"), remap = false)
    private static void vulcraft$disarmMainPresent(CallbackInfo ci) {
        SplashPresenter.setMainPresentEnabled(false);
    }

    @Inject(method = "disableSplash(Ljava/lang/Exception;)Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void vulcraft$swallowDisable(Exception cause, CallbackInfoReturnable<Boolean> cir) {
        vulcraft$logSwallow(cause);
        // Report "not disabled" and - crucially - do NOT throw: start()/finish()
        // both ignore the return value, they only care that no exception escapes.
        cir.setReturnValue(false);
    }

    /**
     * Modern Splash replaces {@code SplashProgress} wholesale with its own fork
     * ({@code CustomSplash}), whose {@code disableSplash} returns <b>void</b>
     * and always throws ({@code SplashProgress$4}/{@code $5}). Same swallow,
     * other descriptor. Both variants are {@code require = 0} because
     * exactly one of them exists in any given class: vanilla Forge has the
     * boolean one, the Modern Splash fork the void one.
     */
    @Inject(method = "disableSplash(Ljava/lang/Exception;)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void vulcraft$swallowDisableVoid(Exception cause, CallbackInfo ci) {
        vulcraft$logSwallow(cause);
        ci.cancel();
    }

    private static void vulcraft$logSwallow(Exception cause) {
        if (!vulcraft$logged) {
            vulcraft$logged = true;
            VulkanMod.LOGGER.info("[Splash] swallowing disableSplash (shared-context hidden window is"
                    + " impossible under Vulkan; the splash thread does not need it): {}", String.valueOf(cause));
        }
    }

    // ---- GL context juggling in finish()/pause()/resume() -----------------
    //
    // With d == null (SharedDrawable stubbed out above) these would NPE on the
    // null drawable, and the Display.getDrawable() makeCurrent() calls would
    // hit the same "no OpenGL context current" guard as setGL() did. None of
    // it is meaningful under Vulkan: the splash thread renders through the
    // Drawer and the main thread's context is the hidden one, which stays put.
    // The pause/resume lock choreography (which synchronises the two threads)
    // is untouched - only the context calls are stubbed.

    @Redirect(method = "finish", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/Drawable;releaseContext()V"), remap = false)
    private static void vulcraft$skipFinishRelease(org.lwjgl.opengl.Drawable drawable) {
    }

    @Redirect(method = "finish", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/Drawable;makeCurrent()V"), remap = false)
    private static void vulcraft$skipFinishMakeCurrent(org.lwjgl.opengl.Drawable drawable) {
    }

    @Redirect(method = "pause", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/Drawable;releaseContext()V"), remap = false)
    private static void vulcraft$skipPauseRelease(org.lwjgl.opengl.Drawable drawable) {
    }

    @Redirect(method = "pause", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/Drawable;makeCurrent()V"), remap = false)
    private static void vulcraft$skipPauseMakeCurrent(org.lwjgl.opengl.Drawable drawable) {
    }

    @Redirect(method = "resume", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/Drawable;releaseContext()V"), remap = false)
    private static void vulcraft$skipResumeRelease(org.lwjgl.opengl.Drawable drawable) {
    }

    @Redirect(method = "resume", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/Drawable;makeCurrent()V"), remap = false)
    private static void vulcraft$skipResumeMakeCurrent(org.lwjgl.opengl.Drawable drawable) {
    }
}
