package com.yuhan123.vulkanmod.mixin.forge;

import com.yuhan123.vulkanmod.vulkan.SplashPresenter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes the Forge loading splash visible under Vulkan.
 *
 * <p>{@code SplashProgress$2} is the anonymous render thread that draws the
 * progress bar every iteration with legacy GL11 immediate mode (already routed
 * to the Vulkan Drawer by {@code GL11Mixin}). Without this, those draws are
 * recorded into a command buffer that is never submitted/presented (the main
 * render loop - the only thing that presents - does not run during loading).
 *
 * <p>We bracket each loop iteration with {@link SplashPresenter#begin()} (loop
 * head, before the first draw) and {@link SplashPresenter#end()} (loop tail,
 * right before {@code Display.update()}), so every splash frame gets submitted
 * and presented. All targets are Forge/Cleanroom/library classes (not
 * obfuscated), so {@code remap = false}.
 */
@Mixin(targets = "net.minecraftforge.fml.client.SplashProgress$2")
public class SplashProgressMixin {

    @Inject(
            method = "run()V",
            at = @At(value = "INVOKE",
                    target = "Lcom/cleanroommc/client/LoadingTracker;tick()V",
                    shift = At.Shift.BEFORE),
            remap = false,
            // Modern Splash replaces SplashProgress$2 with its own fork, whose
            // run() never calls LoadingTracker.tick(). Skipped there: the
            // Drawer-level splash lock (Drawer.splashPresenting) covers the
            // recording race on its own, begin/end only make it coarser.
            require = 0)
    private void vulcraft$splashBegin(CallbackInfo ci) {
        SplashPresenter.begin();
    }

    @Inject(
            method = "run()V",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/Display;update()V",
                    shift = At.Shift.BEFORE),
            remap = false,
            require = 0)
    private void vulcraft$splashEnd(CallbackInfo ci) {
        SplashPresenter.end();
    }

    /**
     * The splash thread renders through Vulkan (its GL11 calls are routed to
     * the Drawer by GL11Mixin), so it never needs a current GL context.
     * lwjglx's {@code makeCurrent()} would call {@code GL.createCapabilities()},
     * which throws "There is no OpenGL context current in the current thread"
     * here (GLFWMixin keeps the hidden context on the main thread only) and
     * kills the splash thread via setGL()'s RuntimeException rethrow.
     */
    @Redirect(
            method = "setGL",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/Drawable;makeCurrent()V"),
            remap = false)
    private static void vulcraft$skipSetGLMakeCurrent(org.lwjgl.opengl.Drawable drawable) {
    }

    /** Same reason as {@link #vulcraft$skipSetGLMakeCurrent}: nothing to release. */
    @Redirect(
            method = "clearGL",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/Drawable;releaseContext()V"),
            remap = false)
    private static void vulcraft$skipClearGLRelease(org.lwjgl.opengl.Drawable drawable) {
    }
}
