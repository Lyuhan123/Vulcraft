package com.yuhan123.vulkanmod.mixin.probe;

import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.List;

/**
 * Restores the entity-walk hooks that the old (removed) RenderGlobalMixin
 * carried. These are the record/consume endpoints for FrameProfiler's
 * pass-1 walk-reuse machinery, which is already live on the profiler side and
 * defaults to ON when no A/B env is set:
 *
 * <ul>
 *   <li>{@code beginRenderEntities} - feeds the Forge render pass to
 *       {@code entP1Begin}, which is what makes the pass0/pass1 split (and
 *       therefore every reuse decision) correct. Without it both renderEntities
 *       calls of a frame count as pass 0 (opens0=2.00, opens1=0.00) and the
 *       record is rebuilt instead of consumed.</li>
 *   <li>{@code entPass1Chunk} (redirect of {@code WorldClient.getChunk}) -
 *       pass 0 records the chunk per iteration; pass 1 answers from the record,
 *       skipping the 8192-entry map probe (~1.11 us/iteration, pass 16).</li>
 *   <li>{@code entEntityLists} (redirect of {@code Chunk.getEntityLists}) - the
 *       iteration counter; pass 1 answers empty iterations with a hot empty
 *       array.</li>
 *   <li>{@code entListIsEmpty} (redirect of {@code ClassInheritanceMultiMap
 *       .isEmpty}) - pass 0 records emptiness here (exactly one call site in
 *       RenderGlobal); drives the pass-1 compaction record.</li>
 *   <li>pass-1 compaction - {@code entLoopIterator} (redirected in this mixin)
 *       hands pass 1 a {@code CompactIterator} over only the entries pass 0
 *       found non-empty.</li>
 *   <li>{@code tileLoopIterator} (iterator ordinal = 2) - same shape for the
 *       block-entity loop, one section down (pass 21).</li>
 *   <li>{@code entityPhase} ({@code endStartSection} redirect) - splits the
 *       entity/blockentity phases and resets the tile cursor.</li>
 * </ul>
 *
 * <p>Receiver checks on the iterator hooks guard against ordinal drift; all
 * other hooks are unique call sites in RenderGlobal.
 */
@Mixin(RenderGlobal.class)
@SuppressWarnings({"rawtypes", "unchecked"})
public class RenderGlobalEntityWalkMixin {

    @Shadow
    private List renderInfos;

    @Shadow
    private int countEntitiesTotal;

    @Shadow
    private int countEntitiesRendered;

    @Inject(method = "renderEntities(Lnet/minecraft/entity/Entity;"
                     + "Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            at = @At("HEAD"))
    private void vulkanmod$beginRenderEntities(Entity renderViewEntity, ICamera camera, float partialTicks,
                                               CallbackInfo ci) {
        FrameProfiler.beginRenderEntities(
                net.minecraftforge.client.MinecraftForgeClient.getRenderPass());
        FrameProfiler.onRenderEntitiesHead();
    }

    @Inject(method = "renderEntities(Lnet/minecraft/entity/Entity;"
                     + "Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            at = @At("RETURN"))
    private void vulkanmod$endRenderEntities(Entity renderViewEntity, ICamera camera, float partialTicks,
                                             CallbackInfo ci) {
        FrameProfiler.endRenderEntities(this.countEntitiesTotal, this.countEntitiesRendered);
    }

    /**
     * The four {@code endStartSection} calls of renderEntities; only the two
     * phase boundaries are consumed, the other names fall through untouched.
     */
    @Redirect(method = "renderEntities(Lnet/minecraft/entity/Entity;"
                      + "Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V"))
    private void vulkanmod$entityPhase(Profiler profiler, String name) {
        if ("entities".equals(name)) {
            FrameProfiler.enterEntityPhase(1);
        } else if ("blockentities".equals(name)) {
            FrameProfiler.enterEntityPhase(2);
        }
        profiler.endStartSection(name);
    }

    /**
     * The entity loop's iterator - bytecode ordinal 0 of four
     * {@code List.iterator()} calls. All iterator hooks of renderEntities MUST
     * live in this one mixin: each @Redirect rewrites an invoke, and a mixin
     * applied later rescans the rewritten bytecode, so its ordinals shift.
     * Receiver checks are the fail-safe against residual drift.
     */
    @Redirect(method = "renderEntities(Lnet/minecraft/entity/Entity;"
                      + "Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            at = @At(value = "INVOKE",
                     target = "Ljava/util/List;iterator()Ljava/util/Iterator;",
                     ordinal = 0))
    private Iterator<?> vulkanmod$entLoopOpen(List<?> list) {
        if (list != this.renderInfos) {
            return list.iterator();
        }
        return FrameProfiler.entLoopIterator((List) list);
    }

    /**
     * The block-entity loop's iterator (bytecode ordinal 2 of four
     * {@code List.iterator()} calls; the receiver check is the fail-safe).
     */
    @Redirect(method = "renderEntities(Lnet/minecraft/entity/Entity;"
                      + "Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            at = @At(value = "INVOKE",
                     target = "Ljava/util/List;iterator()Ljava/util/Iterator;",
                     ordinal = 2))
    private Iterator<?> vulkanmod$tileLoopIterator(List<?> list) {
        if (list != this.renderInfos) {
            return list.iterator();
        }
        return FrameProfiler.tileLoopIterator((List) list);
    }

    /** The entity loop's per-iteration {@code Chunk.getEntityLists()} (unique). */
    @Redirect(method = "renderEntities(Lnet/minecraft/entity/Entity;"
                      + "Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/chunk/Chunk;getEntityLists"))
    private ClassInheritanceMultiMap<Entity>[] vulkanmod$entIter(Chunk chunk) {
        return FrameProfiler.entEntityLists(chunk);
    }

    /**
     * {@code world.getChunk(pos)} - the FIRST call of the loop body. The owner
     * must be {@code WorldClient} (the declaring type of RenderGlobal.world); a
     * {@code World} owner matches nothing and reads as a plausible zero.
     */
    @Redirect(method = "renderEntities(Lnet/minecraft/entity/Entity;"
                      + "Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/multiplayer/WorldClient;getChunk("
                             + "Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/world/chunk/Chunk;"))
    private Chunk vulkanmod$entChunkProbe(WorldClient world, BlockPos pos) {
        return FrameProfiler.entPass1Chunk(world, pos);
    }

    /** {@code ClassInheritanceMultiMap.isEmpty()} - unique call site in RenderGlobal. */
    @Redirect(method = "renderEntities(Lnet/minecraft/entity/Entity;"
                      + "Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/util/ClassInheritanceMultiMap;isEmpty()Z"))
    private boolean vulkanmod$entListEmpty(ClassInheritanceMultiMap<Entity> self) {
        return FrameProfiler.entListIsEmpty(self);
    }
}
