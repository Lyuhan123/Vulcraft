package com.yuhan123.vulkanmod.mixin.gl;

import com.yuhan123.vulkanmod.vulkan.Vulkan;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes lwjglx's {@code ContextCapabilities} constructible from a thread that
 * has no current GL context.
 *
 * <p>lwjglx's constructor forwards to {@code GL.getCapabilities()}, which
 * throws unless {@code GL.createCapabilities()} ran on the calling thread.
 * Under Vulkan the only GL context is the hidden one and it is current on the
 * main thread only, so any other thread that touches {@code GLContext} (whose
 * static initialiser builds the singleton) dies with an
 * {@code ExceptionInInitializerError}. CustomLoadingScreen's
 * {@code MinecraftDisplayerRenderer.render()} does exactly that from the
 * splash thread, just to read {@code GL_KHR_debug}.
 *
 * <p>The redirect returns the current thread's capabilities when they exist
 * (main thread), otherwise the hidden context's real capabilities stashed by
 * {@link Vulkan#stashHiddenContextCaps()}. Returning null is also safe: the
 * constructor's reflective field-copy loop catches the resulting NPE (its own
 * outer try/catch) and leaves every compat flag false, which is the honest
 * answer for a thread without a context.
 */
@Mixin(targets = "org.lwjgl.opengl.ContextCapabilities")
public class GlContextMixin {

    @Redirect(method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/GL;getCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"))
    private GLCapabilities vulcraft$contextTolerantGetCapabilities() {
        try {
            return GL.getCapabilities();
        } catch (RuntimeException noContextInThisThread) {
            return Vulkan.getHiddenContextCaps();
        }
    }
}
