package com.yuhan123.vulkanmod.render.util;

/**
 * One <b>row</b> of a {@code SetVisibility}'s 6&times;6 face-visibility matrix,
 * as a six-bit mask (pass 25).
 *
 * <h2>What this is for</h2>
 *
 * The visible-chunk BFS's second conjunction term is
 * {@code rc3.getCompiledChunk().isVisible(facing.getOpposite(), f)}. Pass 22
 * folded that term into the pass-15 keep mask, which means the term is now
 * evaluated <b>once per facing per poll</b> - 2916 calls a frame at rd 12, from
 * 892 polls - and each of those calls is a three-level chain:
 * {@code CompiledChunk.isVisible} &rarr; {@code SetVisibility.isVisible} &rarr;
 * {@code BitSet.get}. Pass 22's own javadoc argues the calls "are not extra",
 * and that is true of their <em>number</em>: vanilla makes one per facing that
 * survives term 1, and so does the fold. What changed is <b>where</b> they are
 * paid: they used to sit after the neighbour probe, and 889 of the iterations
 * they now reject no longer pay that probe. That trade measured
 * {@code dMkNs} &minus;140 ns/poll &mdash; the fold pays &mdash; but it leaves
 * 2916 three-level calls a frame as the largest single item inside the BFS.
 *
 * <h2>Why the whole row is available in one read</h2>
 *
 * {@code SetVisibility} stores its answers in a {@code BitSet} of
 * {@code COUNT_FACES * COUNT_FACES = 36} bits, and
 * {@code isVisible(from, to)} is
 * {@code bitSet.get(from.ordinal() + to.ordinal() * COUNT_FACES)}. The maximum
 * index is {@code 5 + 5 * 6 = 35}, so the entire matrix lives in the single
 * {@code long} word {@code words[0]}, and the six answers for a fixed
 * {@code from} are the bits {@code o, o+6, o+12, o+18, o+24, o+30} of that word.
 *
 * <p>{@code vulkanmod$visibilityRow(o)} returns them gathered into bits 0..5:
 * bit {@code i} is set iff {@code isVisible(FACINGS[o], FACINGS[i])}. The caller
 * then has {@code term1Mask & row} &mdash; the exact set of facings the
 * two-term conjunction keeps, computed without touching {@code isVisible} at
 * all.
 *
 * <h2>How the row is produced, and why that is safe</h2>
 *
 * {@code SetVisibility} mutates its {@code BitSet} in exactly three places
 * (audited over the whole decompiled client tree, pass 25):
 * {@code setVisible(EnumFacing, EnumFacing, boolean)},
 * {@code setAllVisible(boolean)}, and {@code setManyVisible(Set)}, which routes
 * through {@code setVisible}. A new {@code SetVisibility} starts all-zero and is
 * handed to a {@code CompiledChunk} by {@code setVisibility(SetVisibility)},
 * which only assigns the reference. {@link com.yuhan123.vulkanmod.mixin.SetVisibilityMixin}
 * therefore keeps a 36-bit {@code long} mirror maintained from those two
 * mutators, which makes the row pure arithmetic: no {@code BitSet} access, no
 * call.
 *
 * <p>The mirror is a <b>cache</b>, so it carries the failure mode this project
 * treats as the dangerous one &mdash; a stale entry removes chunks from the world
 * and shows up as holes rather than as a wrong number in any harness here. It is
 * therefore never trusted on argument: {@code BFS_ROW_VERIFY=1}
 * recomputes the row from the real {@code BitSet} on every call, counts
 * disagreements in {@code [VKPROF] bfsrow bad=} (must be 0) and returns the
 * <b>real</b> answer, so a wrong mirror cannot draw a wrong scene even during a
 * correctness run. {@code BFS_ROW=0} restores the per-facing calls.
 */
public interface VisibilityRow {

    /**
     * The six visibility bits for {@code from = EnumFacing.values()[fromOrdinal]}.
     *
     * @param fromOrdinal {@code EnumFacing} ordinal of the source face, 0..5
     * @return bit {@code i} set iff {@code isVisible(from, EnumFacing.values()[i])}
     */
    int vulkanmod$visibilityRow(int fromOrdinal);

    /**
     * The same six bits, read out of the real {@code BitSet} through the real
     * {@code isVisible} index formula rather than out of the mirror.
     *
     * <p>Exists so {@code BFS_ROW_VERIFY=1} compares against the actual
     * store instead of against a transcription of it. That is the only form of
     * reference this project accepts, and it is what caught the window-local
     * chunk-position bug in pass 13.
     */
    int vulkanmod$visibilityRowSlow(int fromOrdinal);
}
