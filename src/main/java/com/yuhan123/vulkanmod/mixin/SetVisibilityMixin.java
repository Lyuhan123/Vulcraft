package com.yuhan123.vulkanmod.mixin;

import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import com.yuhan123.vulkanmod.render.util.VisibilityRow;
import net.minecraft.client.renderer.chunk.SetVisibility;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.BitSet;

/**
 * Pass 25: a 36-bit mirror of {@code SetVisibility}'s {@code BitSet}, so the
 * visible-chunk BFS can read a whole face-visibility <b>row</b> in one
 * arithmetic step instead of making up to six three-level calls.
 *
 * <h2>Why the mirror is possible at all &mdash; the mutation audit</h2>
 *
 * {@code isVisible(from, to)} is
 * {@code bitSet.get(from.ordinal() + to.ordinal() * COUNT_FACES)}, and the index
 * space is {@code 0..35}, so every answer the BFS can ask for lives in bit 0..35
 * of one {@code long}. The mirror is only sound if those bits cannot change
 * without this class noticing, so every route into the {@code BitSet} was
 * enumerated over the whole decompiled client tree:
 *
 * <ul>
 *   <li>{@code setVisible(EnumFacing, EnumFacing, boolean)} &mdash; sets the two
 *       symmetric bits. Hooked.</li>
 *   <li>{@code setAllVisible(boolean)} &mdash; sets bits {@code 0..size()-1},
 *       i.e. all 36 that matter. Hooked.</li>
 *   <li>{@code setManyVisible(Set)} &mdash; calls {@code setVisible} in a double
 *       loop and touches nothing else, so it is covered by the first hook.</li>
 * </ul>
 *
 * There is no other writer. A {@code SetVisibility} is constructed all-zero,
 * populated by {@code VisGraph.computeVisibility()} through those two methods,
 * and handed to a {@code CompiledChunk} by {@code setVisibility(SetVisibility)},
 * which only assigns the reference. That is a complete surface, not a sample of
 * one &mdash; the same shape of argument pass 19 used for {@code entityLists}.
 *
 * <p><b>This is still a cache</b>, so it is not trusted on that argument:
 * {@code VULKANMOD_BFS_ROW_VERIFY=1} recomputes the row from the real
 * {@code BitSet} on every call and counts disagreements in
 * {@code [VKPROF] bfsrow bad=}. A stale mirror would drop chunks from the world
 * and would look like nothing at all in any other harness here.
 */
@Mixin(SetVisibility.class)
public abstract class SetVisibilityMixin implements VisibilityRow {

    /** {@code SetVisibility.COUNT_FACES} &mdash; private, so restated. */
    @Unique private static final int COUNT_FACES = 6;

    /** Bits 0..35 &mdash; the whole index space {@code isVisible} can name. */
    @Unique private static final long ALL_ROWS = (1L << (COUNT_FACES * COUNT_FACES)) - 1L;

    /** The real store. Read only by the verify path; production never touches it. */
    @Shadow private BitSet bitSet;

    /**
     * The 36-bit mirror. Bit {@code a + b * 6} is {@code isVisible(a, b)}.
     * Deliberately not {@code final}: it is written by the two hooks below.
     */
    @Unique private long vulkanmod$rowBits;

    @Unique private static final boolean ROW =
            !"0".equals(System.getenv("VULKANMOD_BFS_ROW"));

    @Unique private static final boolean VERIFY =
            "1".equals(System.getenv("VULKANMOD_BFS_ROW_VERIFY"));

    /**
     * {@code setVisible} writes the two symmetric bits; the mirror has to move
     * with it or the row goes stale for the pair. {@code setManyVisible} reaches
     * this method, so it needs no hook of its own.
     */
    @Inject(method = "setVisible(Lnet/minecraft/util/EnumFacing;Lnet/minecraft/util/EnumFacing;Z)V",
            at = @At("TAIL"))
    private void vulkanmod$trackSetVisible(EnumFacing from, EnumFacing to, boolean visible, CallbackInfo ci) {
        final int a = from.ordinal();
        final int b = to.ordinal();
        final long bits = (1L << (a + b * COUNT_FACES)) | (1L << (b + a * COUNT_FACES));

        if (visible) {
            this.vulkanmod$rowBits |= bits;
        } else {
            this.vulkanmod$rowBits &= ~bits;
        }

        FrameProfiler.onBfsRowMirrorWrite();
    }

    /**
     * {@code setAllVisible} is the whole-matrix case, and it is the common one:
     * {@code computeVisibility} takes it whenever the section holds fewer than
     * 256 non-opaque cells, i.e. for every solid underground section.
     */
    @Inject(method = "setAllVisible(Z)V", at = @At("TAIL"))
    private void vulkanmod$trackSetAllVisible(boolean visible, CallbackInfo ci) {
        this.vulkanmod$rowBits = visible ? ALL_ROWS : 0L;
        FrameProfiler.onBfsRowMirrorWrite();
    }

    /**
     * The row, gathered from the mirror in six shifts.
     *
     * <p>Bit {@code i} of the answer is mirror bit {@code o + 6i}. Shifting the
     * word right by {@code 5i} brings that bit to position {@code i}, and no
     * other bit can land there: {@code 6j - 5i = i} has the unique solution
     * {@code j = i}. So the gather is exact rather than merely plausible.
     */
    @Override
    public int vulkanmod$visibilityRow(int fromOrdinal) {
        final long b = this.vulkanmod$rowBits >>> fromOrdinal;

        return (int) ((b & 1L)
                      | ((b >>> 5) & 2L)
                      | ((b >>> 10) & 4L)
                      | ((b >>> 15) & 8L)
                      | ((b >>> 20) & 16L)
                      | ((b >>> 25) & 32L));
    }

    /**
     * The same row read out of the <b>real</b> {@code BitSet} through the real
     * {@code isVisible} index formula. Used by
     * {@code VULKANMOD_BFS_ROW_VERIFY=1} so the comparison is against the actual
     * store and not against a transcription of it.
     */
    @Override
    public int vulkanmod$visibilityRowSlow(int fromOrdinal) {
        final BitSet bits = this.bitSet;
        int row = 0;

        for (int i = 0; i < COUNT_FACES; i++) {
            if (bits.get(fromOrdinal + i * COUNT_FACES)) {
                row |= 1 << i;
            }
        }

        return row;
    }
}
