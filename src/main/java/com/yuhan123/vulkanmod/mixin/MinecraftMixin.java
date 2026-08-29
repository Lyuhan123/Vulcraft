package com.yuhan123.vulkanmod.mixin;

import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.Vulkan;
import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;

/**
 * Vanilla mixin example
 * Refmap will be handled by Unimined automatically
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
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
}
