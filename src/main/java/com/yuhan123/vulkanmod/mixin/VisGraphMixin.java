package com.yuhan123.vulkanmod.mixin;

import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.BitSet;
import java.util.EnumSet;
import java.util.Set;

/**
 * Replaces {@code VisGraph.floodFill} with an allocation-free traversal.
 *
 * <p>Why this one: pass 12 measured {@code RenderGlobal.getVisibleFacings} at
 * <b>1.33 ms per call</b> ({@code vfMs} 0.20 ms/frame at {@code vfCalls}
 * 0.15/frame, so the cost is a periodic ~1.3 ms spike, not a steady drain). It
 * scans the eye's 4096-block chunk section and then flood-fills a {@code VisGraph}
 * over it. Reading the vanilla implementation shows the scan is not the problem -
 * the flood fill is:
 *
 * <pre>
 *   while (!queue.isEmpty()) {
 *       int i = queue.poll();                 // ArrayDeque&lt;Integer&gt;, boxed
 *       this.addEdges(i, set);
 *       for (EnumFacing f : EnumFacing.values()) {   // &lt;-- clones a 6-element
 *           ...                                       //     array EVERY iteration
 *           queue.add(IntegerCache.getInteger(j));    // boxes index &gt; 255
 *       }
 *   }
 * </pre>
 *
 * <p>The traversal visits up to 4096 cells, so one call allocates an
 * {@code ArrayDeque}, ~4096 {@code Integer} boxes (the index space is 0..4095,
 * and {@code IntegerCache} only caches 0..255) and - the expensive part -
 * <b>~4096 clones of a 6-element array</b> for {@code EnumFacing.values()}, which
 * in Java returns a defensive copy. Roughly 30 KB of garbage per call, on the
 * client thread, ~17 times a second.
 *
 * <p>This is not a cache, and it cannot return a stale answer. The replacement is
 * a plain DFS over the same {@link BitSet} with the same mark-on-enqueue rule, so
 * it visits exactly the same connected component, and the returned set is the
 * union of the same per-cell edge contributions - traversal order does not enter
 * into it, because the result is a {@link Set}. {@code VisGraph.floodFill} is
 * called from both {@code getVisibleFacings} (client thread, once per terrain
 * pass) and {@code computeVisibility} (chunk-rebuild workers, up to 1352 times per
 * rebuild), so both callers benefit.
 *
 * <p>{@code VULKANMOD_VF_FAST=0} restores the vanilla traversal.
 * {@code VULKANMOD_VF_VERIFY=1} runs both and counts disagreements in
 * {@code [VKPROF] tsetup ... vfBad} - a non-zero value means the rewrite is wrong
 * and must not ship. The equivalence is checked against the real vanilla method
 * rather than a transcription of it: the redirect lets vanilla run, snapshots the
 * {@link BitSet} beforehand, and re-runs the fast path on that snapshot.
 */
@Mixin(VisGraph.class)
public class VisGraphMixin {

    /** {@code VisGraph.DX/DZ/DY} - the strides of the packed 12-bit index. */
    @Unique private static final int DX = 1;
    @Unique private static final int DZ = 16;
    @Unique private static final int DY = 256;

    /** {@code EnumFacing.values()} hoisted out of the loop; it is a fresh copy each call. */
    @Unique private static final EnumFacing[] FACINGS = EnumFacing.values();

    @Unique private static final boolean FAST =
            !"0".equals(System.getenv("VULKANMOD_VF_FAST"));

    @Unique private static final boolean VERIFY =
            "1".equals(System.getenv("VULKANMOD_VF_VERIFY"));

    @Shadow
    private BitSet bitSet;

    /** Verify mode only: the opaque set as it was before vanilla's traversal ran. */
    @Unique
    private BitSet vulkanmod$verifySnapshot;

    @Inject(method = "floodFill(I)Ljava/util/Set;", at = @At("HEAD"), cancellable = true)
    private void vulkanmod$fastFloodFill(int pos, CallbackInfoReturnable<Set<EnumFacing>> cir) {
        if (!FAST) {
            return;
        }

        if (VERIFY) {
            // Let vanilla run and keep a copy of the pre-traversal opaque set, so
            // the comparison at RETURN is against the real implementation.
            this.vulkanmod$verifySnapshot = (BitSet) this.bitSet.clone();
            return;
        }

        cir.setReturnValue(vulkanmod$floodFill(this.bitSet, pos));
        FrameProfiler.onVisibleFacingsHit();
    }

    @Inject(method = "floodFill(I)Ljava/util/Set;", at = @At("RETURN"))
    private void vulkanmod$verifyFloodFill(int pos, CallbackInfoReturnable<Set<EnumFacing>> cir) {
        if (!FAST || !VERIFY) {
            return;
        }

        BitSet snapshot = this.vulkanmod$verifySnapshot;
        this.vulkanmod$verifySnapshot = null;
        if (snapshot == null) {
            return;
        }

        Set<EnumFacing> fast = vulkanmod$floodFill(snapshot, pos);
        if (!fast.equals(cir.getReturnValue())) {
            FrameProfiler.onVisibleFacingsMismatch();
        }
    }

    /**
     * Same traversal as vanilla's {@code floodFill}, without the per-cell
     * allocations. Marking on enqueue (rather than on poll) is what bounds the
     * stack at 4096 entries: no cell is ever pushed twice.
     */
    @Unique
    private static Set<EnumFacing> vulkanmod$floodFill(BitSet bits, int start) {
        Set<EnumFacing> set = EnumSet.noneOf(EnumFacing.class);
        int[] stack = new int[4096];
        int top = 0;
        stack[top++] = start;
        bits.set(start, true);

        while (top > 0) {
            int i = stack[--top];

            // VisGraph.addEdges, inlined: index bits 0-3 are x, 4-7 are z, 8-11 are y.
            int x = i & 15;
            if (x == 0) {
                set.add(EnumFacing.WEST);
            } else if (x == 15) {
                set.add(EnumFacing.EAST);
            }
            int y = i >> 8 & 15;
            if (y == 0) {
                set.add(EnumFacing.DOWN);
            } else if (y == 15) {
                set.add(EnumFacing.UP);
            }
            int z = i >> 4 & 15;
            if (z == 0) {
                set.add(EnumFacing.NORTH);
            } else if (z == 15) {
                set.add(EnumFacing.SOUTH);
            }

            // VisGraph.getNeighborIndexAtFace, unrolled over the six faces.
            if (y != 0) {
                int j = i - DY;
                if (!bits.get(j)) {
                    bits.set(j);
                    stack[top++] = j;
                }
            }
            if (y != 15) {
                int j = i + DY;
                if (!bits.get(j)) {
                    bits.set(j);
                    stack[top++] = j;
                }
            }
            if (z != 0) {
                int j = i - DZ;
                if (!bits.get(j)) {
                    bits.set(j);
                    stack[top++] = j;
                }
            }
            if (z != 15) {
                int j = i + DZ;
                if (!bits.get(j)) {
                    bits.set(j);
                    stack[top++] = j;
                }
            }
            if (x != 0) {
                int j = i - DX;
                if (!bits.get(j)) {
                    bits.set(j);
                    stack[top++] = j;
                }
            }
            if (x != 15) {
                int j = i + DX;
                if (!bits.get(j)) {
                    bits.set(j);
                    stack[top++] = j;
                }
            }
        }

        return set;
    }
}
