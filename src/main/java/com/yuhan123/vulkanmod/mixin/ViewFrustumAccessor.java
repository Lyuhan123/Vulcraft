package com.yuhan123.vulkanmod.mixin;

import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Read access to {@code ViewFrustum}'s internals, for the arithmetic neighbour
 * lookup in {@code RenderGlobalMixin}.
 *
 * <p>{@code ViewFrustum.renderChunks} is public, but the three chunk counts are
 * {@code protected} in {@code net.minecraft.client.renderer} and this mod lives
 * in another package, so they cannot be read directly. An accessor interface is
 * the supported way to get at them, and it is deliberately limited to the four
 * members the lookup needs - {@code getRenderChunk} is here as an
 * {@code @Invoker} so the paired A/B's OFF arm can call the <b>real</b> vanilla
 * lookup rather than a transcription of it, which is the only form of reference
 * this project accepts.
 *
 * <p>Nothing here changes behaviour: these are getters and one passthrough.
 */
@Mixin(ViewFrustum.class)
public interface ViewFrustumAccessor {

    @Accessor("countChunksX")
    int vulkanmod$countChunksX();

    @Accessor("countChunksY")
    int vulkanmod$countChunksY();

    @Accessor("countChunksZ")
    int vulkanmod$countChunksZ();

    @Invoker("getRenderChunk")
    RenderChunk vulkanmod$getRenderChunk(BlockPos pos);
}
