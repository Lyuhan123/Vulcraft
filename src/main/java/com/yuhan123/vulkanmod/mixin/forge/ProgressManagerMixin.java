package com.yuhan123.vulkanmod.mixin.forge;

import com.yuhan123.vulkanmod.vulkan.SplashPresenter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Presents the loading splash from FML's progress callbacks.
 *
 * <p>Progress steps are the only points during FML loading where the main
 * thread is between two GPU operations, which makes them the safe place to
 * submit/present a frame (see {@link SplashPresenter#presentFromMainThread()}).
 * The splash's own thread only records draw calls into the currently open
 * frame; it never touches frame state.
 */
@Mixin(targets = "net.minecraftforge.fml.common.ProgressManager$ProgressBar")
public class ProgressManagerMixin {

    @Inject(method = "step(Ljava/lang/String;)V", at = @At("RETURN"), remap = false)
    private void vulcraft$presentAfterStep(CallbackInfo ci) {
        SplashPresenter.presentFromMainThread();
    }

    @Inject(method = "step(Ljava/lang/Class;[Ljava/lang/String;)V", at = @At("RETURN"), remap = false)
    private void vulcraft$presentAfterStepNames(CallbackInfo ci) {
        SplashPresenter.presentFromMainThread();
    }
}
