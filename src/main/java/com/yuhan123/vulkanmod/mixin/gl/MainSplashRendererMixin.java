package com.yuhan123.vulkanmod.mixin.gl;

import org.lwjgl.opengl.Drawable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * CustomLoadingScreen's splash loop has its own {@code setGL}/{@code clearGL}
 * pair (mirroring Forge's {@code SplashProgress$2}), and calls them whenever
 * FML pauses the splash. Under Vulkan the splash thread must never make a GL
 * context current - the only context is the hidden one, main-thread only, and
 * {@code makeCurrent()} would run {@code GL.createCapabilities()} on a thread
 * without a context, throwing "There is no OpenGL context current in the
 * current thread" and killing the splash thread. The splash renders through
 * Vulkan anyway (its GL11 calls are routed to the Drawer), so it never needs
 * one. Same redirects as {@code SplashProgressMixin}, different target class.
 *
 * <p>Applies only when CustomLoadingScreen is installed: the target class does
 * not exist otherwise and the mixin is simply never applied.
 */
@Mixin(targets = "alexiil.mc.mod.load.render.MainSplashRenderer")
public class MainSplashRendererMixin {

    @Redirect(
            method = "setGL",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/Drawable;makeCurrent()V"),
            remap = false)
    private static void vulcraft$skipSetGLMakeCurrent(Drawable drawable) {
    }

    /** Same reason as {@link #vulcraft$skipSetGLMakeCurrent}: nothing to release. */
    @Redirect(
            method = "clearGL",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/Drawable;releaseContext()V"),
            remap = false)
    private static void vulcraft$skipClearGLRelease(Drawable drawable) {
    }
}
