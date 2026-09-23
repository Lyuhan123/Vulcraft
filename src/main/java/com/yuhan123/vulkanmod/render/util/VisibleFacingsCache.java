package com.yuhan123.vulkanmod.render.util;

import net.minecraft.util.EnumFacing;

import java.util.EnumSet;
import java.util.Set;

/**
 * One-entry memo for {@code RenderGlobal.getVisibleFacings(BlockPos)}.
 *
 * <p>This was designed in pass 12, <b>retired on the strength of a static-camera
 * benchmark</b>, and reinstated when {@code VULKANMOD_FORCE_DIRTY} showed that
 * benchmark was pricing the wrong thing. {@code setupTerrain} only runs its
 * visible-chunk selection when {@code displayListEntitiesDirty} is set, and the
 * benchmark camera stands still, so the flag tripped on 0.17 of frames and
 * {@code getVisibleFacings} looked like a 0.13 ms/frame item with a 2.5% ceiling.
 * In real play the flag trips on <b>every</b> frame - it is a strict compare
 * against the previous frame's position <i>and rotation</i>, and a player walking
 * at 4.3 blocks/s moves 0.033 blocks per frame. Measured with the flag forced:
 * {@code setupTerrain} 1.79 ms/frame (20.5% of the frame) and
 * {@code getVisibleFacings} 0.59 ms/frame.
 *
 * <p>Why the hit rate is high even though the flag trips every frame: the flag
 * tracks the view entity's <i>position</i>, but this key tracks the eye's
 * <b>block</b> position. A walking player crosses a block boundary about every
 * 30 frames, so the key is unchanged on ~97% of frames and the expensive
 * recompute happens on the rest. Standing still and looking around, the hit rate
 * is 100%.
 *
 * <p><b>Two correctness traps, both handled.</b>
 * <ol>
 *   <li>{@code setupTerrain} <i>mutates</i> the set it is handed - it removes a
 *       facing when {@code size() == 1} - so a cached instance can never be
 *       returned directly, and the value vanilla computed cannot be cached
 *       directly either, since it goes straight back to that caller. Both
 *       directions copy.</li>
 *   <li>A stale entry culls chunks that should be drawn, which shows up as holes
 *       in the world and <b>not</b> as a wrong number in any harness here. So the
 *       key carries a generation that every content-changing event bumps, and
 *       {@code VULKANMOD_VF_MEMO_VERIFY=1} recomputes the truth on every hit and
 *       counts disagreements in {@code vfMemoBad} - a non-zero value means this
 *       must not ship.</li>
 * </ol>
 *
 * <p>{@code VULKANMOD_VF_MEMO=0} disables the memo.
 */
public final class VisibleFacingsCache {

    public static final boolean ENABLED = !"0".equals(System.getenv("VULKANMOD_VF_MEMO"));
    public static final boolean VERIFY = "1".equals(System.getenv("VULKANMOD_VF_MEMO_VERIFY"));

    /**
     * Bumped by every event that can change a chunk section's contents. Volatile
     * because chunk loading and population are not guaranteed to be on the client
     * thread, while the read is once per {@code getVisibleFacings} call, not once
     * per block.
     */
    private static volatile long generation;

    private static long cachedGeneration = Long.MIN_VALUE;

    private static int sectionX;
    private static int sectionY;
    private static int sectionZ;

    private static int eyeX;
    private static int eyeY;
    private static int eyeZ;

    private static EnumSet<EnumFacing> cached;

    private VisibleFacingsCache() {
    }

    /**
     * Any block change, chunk load, chunk unload or world swap.
     *
     * <p>Deliberately unconditional rather than testing the changed range against
     * the cached section: block changes are rare relative to frames, and a range
     * test would be a second place to get the geometry wrong for no measurable
     * gain. The cheap-but-slightly-over-eager direction is the safe one.
     */
    public static void invalidate() {
        generation++;
    }

    public static boolean matches(int sx, int sy, int sz, int px, int py, int pz) {
        return ENABLED
                && cached != null
                && cachedGeneration == generation
                && sectionX == sx && sectionY == sy && sectionZ == sz
                && eyeX == px && eyeY == py && eyeZ == pz;
    }

    /** A copy, because the caller mutates what it is handed. */
    public static Set<EnumFacing> copy() {
        return copyOf(cached);
    }

    /** Verify mode only: the cached value, for comparison against the truth. */
    public static Set<EnumFacing> peek() {
        return cached;
    }

    public static void store(int sx, int sy, int sz, int px, int py, int pz, Set<EnumFacing> value) {
        if (!ENABLED || value == null) {
            return;
        }

        sectionX = sx;
        sectionY = sy;
        sectionZ = sz;
        eyeX = px;
        eyeY = py;
        eyeZ = pz;
        cachedGeneration = generation;
        cached = copyOf(value);
    }

    /**
     * {@code EnumSet.copyOf} throws on an empty plain {@code Collection}; the
     * flood fill always returns an {@code EnumSet}, but this must not depend on
     * that.
     */
    @SuppressWarnings("unchecked")
    private static EnumSet<EnumFacing> copyOf(Set<EnumFacing> value) {
        if (value instanceof EnumSet) {
            return ((EnumSet<EnumFacing>) value).clone();
        }
        EnumSet<EnumFacing> copy = EnumSet.noneOf(EnumFacing.class);
        copy.addAll(value);
        return copy;
    }
}
