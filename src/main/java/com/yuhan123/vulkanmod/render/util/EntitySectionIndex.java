package com.yuhan123.vulkanmod.render.util;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;
import java.util.List;

/**
 * The "which chunk sections hold entities" index for pass 0 of
 * {@code RenderGlobal.renderEntities} (pass 19).
 *
 * <h2>The problem this exists for</h2>
 *
 * Pass 18 split the entity phase and found the two halves are not the same size:
 * the <b>pass-0</b> loop is 0.99 ms/frame (1.11 µs an iteration) while the pass-1
 * loop it spent two passes optimising is 0.14–0.26 ms (160 ns an iteration). The
 * pass-0 body is a five-deep dependent load chain —
 * {@code world.getChunk(pos)} ({@code Long2ObjectOpenHashMap} probe) →
 * {@code Chunk.entityLists} → {@code ClassInheritanceMultiMap} → {@code isEmpty()}
 * — over ~892 render infos whose sections are almost all empty, and the answer
 * changes for ~10 of them. Nothing else in the frame reads that data, so every
 * iteration is a cold cache miss. It is a memory-latency walk, not an instruction
 * count: the block-entity loop over the <em>same</em> 892 render infos costs
 * 0.16 µs an iteration because its {@code CompiledChunk} is warmed elsewhere.
 *
 * <h2>Why this is safe where an incrementally maintained index is not</h2>
 *
 * Pass 18 explicitly declined to build an index maintained by hooks on
 * {@code Chunk.addEntity}/{@code removeEntity}/{@code onLoad}/{@code onUnload},
 * because the failure mode of a missing hook is <em>entities silently not
 * rendered</em> — invisible to every harness in this repo.
 *
 * <p>This index is not maintained by hooks. It is <b>rebuilt from scratch every
 * pass-0 call</b> out of {@code world.getLoadedEntityList()}, the very list vanilla
 * iterates one line earlier to compute {@code countEntitiesTotal}. On the client
 * every route into a {@code Chunk}'s {@code entityLists} also puts the entity in
 * that list ({@code World.spawnEntity}, {@code World.updateEntity}'s chunk-change
 * branch, {@code Chunk.onLoad} via {@code World.loadEntities}) and every route out
 * removes it from both, so the marked set is a <b>superset</b> of the sections the
 * vanilla walk would visit. A superset means the worst case is visiting a section
 * that turns out to be empty — wasted work, not a missing entity.
 *
 * <p>The design carries a second, independent safety net: the object returned in
 * place of the real chunk ({@link FrameProfiler}'s dummy) is a real, permanently
 * empty {@code Chunk}, so even a total wiring failure of the skip path renders the
 * correct scene at the vanilla cost. There is no configuration in which this class
 * can make an entity disappear; the only thing it can get wrong is how much it
 * saves.
 *
 * <h2>Key space</h2>
 *
 * One 64-bit key per chunk <b>section</b>: 26 bits of chunk X, 26 of chunk Z and 4
 * of section Y, all zero-extended, so every key is non-negative and {@code -1L} is
 * a usable empty marker. Chunk X/Z stay injective over ±2^25 chunks (±33.5 M
 * blocks) against a ±30 M world border.
 */
public final class EntitySectionIndex {

    /** Empty-slot marker. Real keys occupy bits 0..55, so they are never negative. */
    private static final long EMPTY = -1L;

    private static final int INITIAL = 4096;

    private long[] keys;
    private int size;
    private int limit;

    public EntitySectionIndex() {
        this.keys = new long[INITIAL];
        Arrays.fill(this.keys, EMPTY);
        this.limit = INITIAL >> 1;
    }

    /** Packs a chunk-section coordinate triple. */
    public static long key(int chunkX, int sectionY, int chunkZ) {
        return ((long) (chunkX & 0x3FFFFFF) << 26)
                | (long) (chunkZ & 0x3FFFFFF)
                | ((long) (sectionY & 0x0F) << 52);
    }

    /** Packs the section that contains a block position. */
    public static long keyOf(BlockPos pos) {
        return key(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    /**
     * Clears the index and marks every entity's own section.
     *
     * <p>Marked unconditionally, including entities whose {@code addedToChunk} is
     * false and whose {@code chunkCoord*} may therefore be stale: a stale mark is an
     * extra (harmless) entry, while skipping a live one would be a dropped entity.
     * The cheap direction is the safe direction.
     */
    public void rebuild(List<Entity> loadedEntities) {
        begin();
        for (int i = 0, n = loadedEntities.size(); i < n; i++) {
            final Entity e = loadedEntities.get(i);
            add(key(e.chunkCoordX, e.chunkCoordY, e.chunkCoordZ));
        }
    }

    /** Clears the table. Called once per pass-0 entity walk. */
    public void begin() {
        Arrays.fill(keys, EMPTY);
        size = 0;
    }

    public int size() {
        return size;
    }

    public void add(long k) {
        if (size >= limit) {
            grow();
        }
        insert(k);
    }

    public boolean contains(long k) {
        final long[] a = keys;
        final int mask = a.length - 1;
        int i = hash(k) & mask;
        for (; ; ) {
            final long cur = a[i];
            if (cur == k) {
                return true;
            }
            if (cur == EMPTY) {
                return false;
            }
            i = (i + 1) & mask;
        }
    }

    private void insert(long k) {
        final long[] a = keys;
        final int mask = a.length - 1;
        int i = hash(k) & mask;
        for (; ; ) {
            final long cur = a[i];
            if (cur == EMPTY) {
                a[i] = k;
                size++;
                return;
            }
            if (cur == k) {
                return;
            }
            i = (i + 1) & mask;
        }
    }

    private void grow() {
        final long[] old = keys;
        final int n = old.length << 1;
        keys = new long[n];
        Arrays.fill(keys, EMPTY);
        size = 0;
        limit = n >> 1;
        for (int i = 0; i < old.length; i++) {
            final long k = old[i];
            if (k != EMPTY) {
                insert(k);
            }
        }
    }

    private static int hash(long k) {
        int h = (int) (k ^ (k >>> 32));
        h *= 0x9E3779B1;
        h ^= h >>> 15;
        return h;
    }
}
