package com.yuhan123.vulkanmod.render.chunk;

import com.yuhan123.vulkanmod.VKProf;
import com.yuhan123.vulkanmod.mixin.vertex.VertexBufferAccessor;
import com.yuhan123.vulkanmod.vulkan.device.DeviceManager;
import com.yuhan123.vulkanmod.vulkan.memory.MemoryTypes;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.Buffer;
import net.minecraft.client.renderer.vertex.VertexBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the terrain area buffers: one device-local vertex buffer per block
 * render layer, plus the range allocator that hands out and recycles byte
 * ranges inside them.
 *
 * <p>P1 of the WorldRenderer refactor. This state used to live as ~15
 * unrelated {@code private static} fields scattered through
 * {@code VboRenderListMixin}, mixed in with the draw-emission state. It is
 * moved here verbatim - same constants, same allocation policy, same grow
 * behaviour - so the extraction is behaviour-preserving by construction:
 *
 * <ul>
 *   <li>{@code freeRanges} / {@code areaUsed} / {@code areaVtx} - the bump
 *       allocator and its free list.</li>
 *   <li>{@code secCache} / {@code secReg} / {@code regionDirty} - the section
 *       offset caches that a grow has to invalidate (they are owned here
 *       because {@link #allocRange} is what invalidates them).</li>
 *   <li>{@code gatherBB} / {@code zeroBB} - host scratch for uploads.</li>
 * </ul>
 *
 * <p>The arrays themselves are never reassigned, only their elements are, so
 * the mixin can keep referring to them through the accessors below and both
 * sides always observe the same storage.
 */
public final class ChunkAreaManager {

    private ChunkAreaManager() {
    }

    // ---------------- constants (moved from VboRenderListMixin) ----------------

    public static final int STRIDE = 28; // DefaultVertexFormats.BLOCK
    public static final int QUAD_ALIGNMENT_BYTES = 4 * STRIDE;
    public static final int AREA_MAX_BYTES = 48 * 1024 * 1024;
    public static final int LAYER_COUNT = 4; // BlockRenderLayer.values().length
    public static final int AREA_SHIFT = 5;  // 32-block (2-chunk) areas

    // ---------------- state ----------------

    private static final List[] freeRanges = new ArrayList[LAYER_COUNT];
    private static final int[] areaUsed = new int[LAYER_COUNT];
    private static Buffer[] areaVtx = new Buffer[LAYER_COUNT];

    // section offset caches; invalidated by allocRange when a grow discards contents
    private static final Map[] secCache = new HashMap[LAYER_COUNT];
    private static final Map[] secReg = new HashMap[LAYER_COUNT];
    private static final boolean[] regionDirty = new boolean[LAYER_COUNT];

    private static ByteBuffer gatherBB = alloc(4 * 1024 * 1024);
    private static ByteBuffer zeroBB = alloc(1024 * 1024);

    // ---------------- accessors ----------------

    @SuppressWarnings("rawtypes")
    public static List[] freeRanges() {
        return freeRanges;
    }

    public static int[] areaUsed() {
        return areaUsed;
    }

    public static Buffer[] areaVtx() {
        return areaVtx;
    }

    @SuppressWarnings("rawtypes")
    public static Map[] secCache() {
        return secCache;
    }

    @SuppressWarnings("rawtypes")
    public static Map[] secReg() {
        return secReg;
    }

    public static boolean[] regionDirty() {
        return regionDirty;
    }

    public static ByteBuffer gatherBuffer() {
        return gatherBB;
    }

    public static ByteBuffer zeroBuffer() {
        return zeroBB;
    }

    /** Replaces the gather scratch buffer (it grows when a section exceeds it). */
    public static void setGatherBuffer(ByteBuffer b) {
        gatherBB = b;
    }

    // ---------------- allocation ----------------

    public static ByteBuffer alloc(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    /** Rounds a byte length up to a whole quad so range boundaries stay on the index pattern's quad grid. */
    public static int alignQuad(int bytes) {
        return (bytes + QUAD_ALIGNMENT_BYTES - 1) / QUAD_ALIGNMENT_BYTES * QUAD_ALIGNMENT_BYTES;
    }

    /**
     * Hands a range back to the layer's free pool: zeroes it, inserts it sorted
     * by offset, and merges it with an immediately adjacent range on either side
     * so the pool does not fragment into unusable slivers.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void releaseRange(int layerIdx, Buffer area, int offset, int size) {
        if (size <= 0) {
            return;
        }
        // Draws are bounded to each section's own [start, start+vertexCount)
        // range and runs only coalesce across EXACTLY contiguous sections, so a
        // released range is never rasterised. It is zeroed anyway: the padding a
        // recycled range leaves behind must not hold stale geometry.
        zeroRange(area, offset, size);

        final List<int[]> list = freeRanges[layerIdx];
        if (list == null) {
            return;
        }
        int at = 0;
        while (at < list.size() && ((int[]) list.get(at))[0] < offset) {
            at++;
        }
        list.add(at, new int[]{offset, size});

        if (at > 0) {
            final int[] prev = (int[]) list.get(at - 1);
            final int[] cur = (int[]) list.get(at);
            if (prev[0] + prev[1] == cur[0]) {
                prev[1] += cur[1];
                list.remove(at);
                at--;
            }
        }
        if (at + 1 < list.size()) {
            final int[] cur = (int[]) list.get(at);
            final int[] next = (int[]) list.get(at + 1);
            if (cur[0] + cur[1] == next[0]) {
                cur[1] += next[1];
                list.remove(at + 1);
            }
        }
    }

    /**
     * Reserves {@code bytes} in this layer's area buffer, recycling a freed
     * range when one fits and only otherwise advancing the bump pointer.
     *
     * @return the byte offset to write at, or -1 if the buffer had to be grown
     *         in the slow way (contents discarded, caches invalidated) and the
     *         caller must fall back to vanilla for this frame.
     */
    public static int allocRange(int layerIdx, Buffer areaIn, int bytes) throws Exception {
        // Carve whole quads: a free range starts on a quad boundary and the
        // remainder has to keep that invariant, or the next section allocated
        // out of it would tear across the shared index pattern.
        final int need = alignQuad(bytes);

        final List<int[]> list = freeRanges[layerIdx];
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                final int[] r = (int[]) list.get(i);
                if (r[1] >= need) {
                    final int dst = r[0];
                    r[0] = dst + need;
                    r[1] -= need;
                    if (r[1] <= 0) {
                        list.remove(i);
                    }
                    return dst;
                }
            }
        }

        final int dst = alignQuad(areaUsed[layerIdx]);
        if (dst + bytes > areaIn.getBufferSize()) {
            final long want = Math.max(dst + bytes, (long) areaIn.getBufferSize() * 2L);
            final int newSize = (int) Math.min(AREA_MAX_BYTES, want);
            if (newSize < dst + bytes) {
                throw new IllegalStateException("area buffer exhausted: " + areaUsed[layerIdx] + " + " + bytes);
            }
            // Grow by COPYING the old contents (see areaBufferGrow). Allocating a
            // fresh zeroed buffer instead invalidates every cached offset, and
            // the next frame then re-bakes and re-uploads every visible section -
            // the single most expensive thing this path can do.
            final Buffer grown = areaBufferGrow(areaIn, newSize);
            if (grown == null) {
                areaVtx[layerIdx] = areaBuffer(newSize);
                if (secCache[layerIdx] != null) {
                    secCache[layerIdx].clear();
                }
                if (secReg[layerIdx] != null) {
                    secReg[layerIdx].clear();
                }
                if (freeRanges[layerIdx] != null) {
                    freeRanges[layerIdx].clear();
                }
                areaUsed[layerIdx] = 0;
                regionDirty[layerIdx] = true;
                VKProf.info("[VKPROF] TBATCH area buffer[{}] grown to {} bytes (no device-side copy); caches reset, fallback this frame",
                        Integer.valueOf(layerIdx), Integer.valueOf(newSize));
                return -1;
            }
            areaVtx[layerIdx] = grown;
            VKProf.info("[VKPROF] TBATCH area buffer[{}] grown to {} bytes by copy; offsets preserved",
                    Integer.valueOf(layerIdx), Integer.valueOf(newSize));
        }
        areaUsed[layerIdx] = dst + bytes;
        return dst;
    }

    /**
     * Grows an area buffer to {@code newSize} while PRESERVING its contents.
     *
     * @return the new buffer, or null when no device-side copy is available
     *         (host-memory fallback), leaving the caller to rebuild from scratch.
     */
    public static Buffer areaBufferGrow(Buffer old, int newSize) throws Exception {
        if (!(old.type instanceof MemoryTypes.DeviceLocalMemory)) {
            return null;
        }
        final Buffer b = new Buffer(org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, MemoryTypes.GPU_MEM);
        b.createBuffer(newSize);
        // Synchronous on purpose. DeviceLocalMemory.copyBuffer() goes through
        // TransferQueue.copyBufferCmd(), which only SUBMITS - no wait, no
        // semaphore to the graphics queue. A grow copy still in flight when this
        // frame's draws are submitted would tear the whole layer, so pay the fence.
        DeviceManager.getTransferQueue().uploadBufferImmediate(
                old.getId(), 0L, b.getId(), 0L, old.getBufferSize());
        zeroRange(b, (int) old.getBufferSize(), newSize - (int) old.getBufferSize());
        return b;
    }

    /** Device-local (VRAM) vertex buffer, zero-filled so unwritten vertices rasterize degenerate. */
    public static Buffer areaBuffer(int size) throws Exception {
        final Buffer b = new Buffer(org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, MemoryTypes.GPU_MEM);
        b.createBuffer(size);
        zeroBB.position(0).limit(zeroBB.capacity());
        for (int off = 0; off < size; off += zeroBB.capacity()) {
            final int chunk = Math.min(zeroBB.capacity(), size - off);
            b.type.copyToBuffer(b, zeroBB, chunk, 0, off);
        }
        return b;
    }

    public static void zeroRange(Buffer layerArea, int dst, int bytes) {
        if (bytes <= 0) {
            return;
        }
        zeroBB.position(0).limit(zeroBB.capacity());
        for (int off = 0; off < bytes; off += zeroBB.capacity()) {
            final int chunk = Math.min(zeroBB.capacity(), bytes - off);
            layerArea.type.copyToBuffer(layerArea, zeroBB, chunk, 0, dst + off);
        }
    }

    /** Packs a section position + layer into the id of the 32-block area that contains it. */
    public static long areaIdOf(int x, int y, int z, int layer) {
        final long ax = ((long) x) >> AREA_SHIFT;
        final long ay = ((long) y) >> AREA_SHIFT;
        final long az = ((long) z) >> AREA_SHIFT;
        return ((long) layer << 62)
                | ((ax & 0x1FFFFFL) << 42)
                | ((ay & 0x1FFFFFL) << 21)
                | (az & 0x1FFFFFL);
    }

    public static int safeCount(VertexBuffer vbo) {
        try {
            return ((VertexBufferAccessor) vbo).getCount();
        } catch (Throwable ex) {
            return 0;
        }
    }

    /**
     * Copies one section's vertices into the area buffer at {@code dst}, baking
     * the section origin into the positions so the whole layer can be drawn with
     * a single model-view.
     */
    public static void bakeUpload(Buffer layerArea, int dst, int bytes, ByteBuffer data,
                                  com.yuhan123.vulkanmod.vulkan.memory.buffer.VertexBuffer persistent,
                                  int sx, int sy, int sz) {
        if (gatherBB.capacity() < bytes) {
            gatherBB = alloc(Math.max(bytes, gatherBB.capacity() * 2));
        }
        gatherBB.position(0).limit(bytes);
        if (data != null) {
            data.position(0).limit(bytes);
            gatherBB.put(data);
            gatherBB.position(0);
        } else {
            MemoryTypes.HOST_MEM.copyFromBuffer(persistent, bytes, gatherBB);
            gatherBB.position(0);
        }
        final int vcount = bytes / STRIDE;
        for (int v = 0; v < vcount; v++) {
            final int o = v * STRIDE;
            gatherBB.putFloat(o, gatherBB.getFloat(o) + sx);
            gatherBB.putFloat(o + 4, gatherBB.getFloat(o + 4) + sy);
            gatherBB.putFloat(o + 8, gatherBB.getFloat(o + 8) + sz);
        }
        gatherBB.position(0);
        layerArea.type.copyToBuffer(layerArea, gatherBB, bytes, 0, dst);
    }
}
