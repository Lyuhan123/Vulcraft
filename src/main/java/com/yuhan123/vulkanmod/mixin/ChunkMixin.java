package com.yuhan123.vulkanmod.mixin;

import com.yuhan123.vulkanmod.render.util.VisibleFacingsCache;
import net.minecraft.network.PacketBuffer;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chunk-content invalidation for {@link VisibleFacingsCache}.
 *
 * <p>Block changes already reach the memo: {@code Chunk.setBlockState} calls
 * {@code World.markBlockRangeForRenderUpdate}, which lands on
 * {@code RenderGlobal.markBlockRangeForRenderUpdate}. Chunk contents that
 * <b>arrive</b> do not - a section is filled wholesale from a packet and no
 * per-block update is emitted - so this is the one direction that would otherwise
 * go unnoticed.
 *
 * <p>That direction is the dangerous one in a specific way: a section whose data
 * has not arrived yet reads as empty, which caches an "everything is visible"
 * answer for the eye's section. Caching that is not unsafe (it over-draws rather
 * than hiding chunks), but it is wrong and would persist until the next block
 * change, so it is invalidated here rather than left to the verify mode to catch.
 *
 * <p>{@code read(PacketBuffer, int, boolean)} is the network fill;
 * {@code onLoad}/{@code onUnload} cover a chunk entering or leaving the world.
 */
@Mixin(Chunk.class)
public class ChunkMixin {

    @Inject(method = "read(Lnet/minecraft/network/PacketBuffer;IZ)V", at = @At("RETURN"))
    private void vulkanmod$invalidateOnChunkData(PacketBuffer buf, int availableSections,
                                                 boolean groundUpContinuous, CallbackInfo ci) {
        VisibleFacingsCache.invalidate();
    }

    @Inject(method = "onLoad()V", at = @At("HEAD"))
    private void vulkanmod$invalidateOnChunkLoad(CallbackInfo ci) {
        VisibleFacingsCache.invalidate();
    }

    @Inject(method = "onUnload()V", at = @At("HEAD"))
    private void vulkanmod$invalidateOnChunkUnload(CallbackInfo ci) {
        VisibleFacingsCache.invalidate();
    }
}
