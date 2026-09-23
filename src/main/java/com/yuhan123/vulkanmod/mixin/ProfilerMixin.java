package com.yuhan123.vulkanmod.mixin;

import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import net.minecraft.profiler.Profiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps {@link Profiler#profilingEnabled} stuck on while a frame-budget run is
 * in progress (VULKANMOD_PROFILE_DUMP=1).
 *
 * Why this is needed: {@code Minecraft.runGameLoop} assigns
 * {@code profiler.profilingEnabled = false} part-way through every frame unless
 * the F3 profiler chart is on screen. Re-arming the flag from the outside at the
 * start of the next frame is not enough, because the sections opened before the
 * disable are never closed while it is off - {@code endSection()} returns
 * immediately - so the profiler's section stack drifts and the recorded section
 * paths degrade into {@code root.root.root.render...}. {@code getProfilingData}
 * then finds nothing at the paths we want to read.
 *
 * Forcing the flag at the head of startSection/endSection keeps the profiler in
 * exactly the state it is in when the F3 chart is displayed, which is the state
 * its path bookkeeping is written for.
 *
 * Only active when the env var is set, so normal runs are unaffected.
 */
@Mixin(Profiler.class)
public abstract class ProfilerMixin {

    @Inject(method = "startSection(Ljava/lang/String;)V", at = @At("HEAD"))
    private void vulkanmod$keepProfilingOnStart(CallbackInfo ci) {
        if (FrameProfiler.PROFILE_DUMP) {
            ((Profiler) (Object) this).profilingEnabled = true;
        }
    }

    @Inject(method = "endSection()V", at = @At("HEAD"))
    private void vulkanmod$keepProfilingOnEnd(CallbackInfo ci) {
        if (FrameProfiler.PROFILE_DUMP) {
            ((Profiler) (Object) this).profilingEnabled = true;
        }
    }
}
