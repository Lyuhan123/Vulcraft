package com.yuhan123.vulkanmod.mixin.gl;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GLFW.class)
public class GLFWMixin {
    @Inject(method = "glfwDefaultWindowHints", at = @At("RETURN"))
    private static void setWindow(CallbackInfo ci) {
        // The visible window is presented through Vulkan only
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
    }

    /**
     * The window is presented through Vulkan (vkQueuePresentKHR), so the GL buffer
     * swap performed by lwjglx's Display.update() would overwrite the presented
     * image with an empty GL framebuffer (black screen). Skip it.
     */
    @Inject(method = "glfwSwapBuffers", at = @At("HEAD"), cancellable = true)
    private static void skipSwapBuffers(CallbackInfo ci) {
        ci.cancel();
    }

    /**
     * Only the hidden GL context window carries a GL context. Making any other
     * (Vulkan-only) window current would clear the thread's GL context and make
     * lwjglx's GL calls abort with "No context is current".
     */
    @Inject(method = "glfwMakeContextCurrent", at = @At("HEAD"), cancellable = true)
    private static void keepHiddenContextCurrent(long window, CallbackInfo ci) {
        long hidden = com.yuhan123.vulkanmod.vulkan.Vulkan.getGlContextWindow();
        if (window != hidden) {
            ci.cancel();
        }
    }
}
