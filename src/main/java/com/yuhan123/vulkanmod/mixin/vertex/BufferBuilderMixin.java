package com.yuhan123.vulkanmod.mixin.vertex;

import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import net.minecraft.client.renderer.BufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts submitted vertices, for pass 16.
 *
 * <p>The vanilla profiler's {@code entities} section reads ~23% of the frame and
 * no direct instrument in this project has ever priced it. The obvious suspect
 * is 1.12.2's immediate-mode vertex path: every vertex costs five calls into
 * {@code BufferBuilder} ({@code pos} 340 B, {@code tex} 207 B, {@code color}
 * 30/415 B, {@code lightmap} 189 B, {@code normal}) and the JIT reports all of
 * them as <b>"failed to inline: callee is too large"</b>. Whether that matters
 * depends entirely on how many vertices a frame actually submits - which is the
 * one number nobody has.
 *
 * <p>{@code endVertex()} is exactly one call per vertex, so it is the cheapest
 * honest place to count them. The counter is a 9-byte static increment and
 * inlines, so the apparatus is ~1 ns per vertex.
 *
 * <p>This is instrumentation only and changes no behaviour.
 */
@Mixin(BufferBuilder.class)
public class BufferBuilderMixin {

    @Inject(method = "endVertex()V", at = @At("HEAD"))
    private void vulkanmod$countVertex(CallbackInfo ci) {
        FrameProfiler.onEndVertex();
    }
}
