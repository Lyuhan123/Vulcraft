package com.yuhan123.vulkanmod.mixin.chunk;

import com.yuhan123.vulkanmod.VKProf;
import com.yuhan123.vulkanmod.config.VulkanModConfig;
import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.gl.VkGlBuffer;
import com.yuhan123.vulkanmod.render.PipelineManager;
import com.yuhan123.vulkanmod.render.shader.ShaderInstance;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.device.DeviceManager;
import com.yuhan123.vulkanmod.vulkan.memory.MemoryTypes;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.Buffer;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.index.AutoIndexBuffer;
import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.VboRenderList;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMultiDrawIndexedInfoEXT;
import org.lwjgl.vulkan.EXTMultiDraw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

/**
 * Batched terrain drawing (experiment, TERRAIN_BATCH=1): replaces the
 * vanilla per-section bind + translate + push + draw sequence (~600 of each per
 * frame, per layer) with ONE shared vertex buffer and ONE
 * {@code vkCmdDrawIndexed} per layer.
 *
 * <p>How a single draw can cover every section:
 *
 * <ul>
 *   <li>Section vertices are chunk-local (RenderChunk rebuilds with
 *       setTranslation(-pos)). At upload time the section's world offset is
 *       baked into the vertex positions (one-time, cached), so the stored data
 *       is in world coordinates.</li>
 *   <li>Each section's area-buffer offset is aligned to 4 vertices (one quad,
 *       112 bytes). The shared quad index pattern maps quad q to vertices
 *       q*4..q*4+3, so with every section quad-aligned the whole layer's
 *       sections form one contiguous quad sequence in the shared pattern -
 *       everything else in the buffer stays zero-filled and rasterizes as
 *       degenerate (zero-area) triangles that produce no fragments.</li>
 *   <li>One {@code vkCmdDrawIndexed(firstIndex=0, vertexOffset=0,
 *       indexCount=lastQuad*6)} then draws the entire layer. The MVP push and
 *       all pipeline/textures/uniforms come from the standard block shader via
 *       {@code PipelineManager.chooseShader(BLOCK).apply()} - the exact shader
 *       state the vanilla per-section path uses, so lighting, fog and
 *       fixed-function state are byte-identical.</li>
 * </ul>
 *
 * <p>Each BlockRenderLayer gets its own area buffer, so a layer's draw never
 * touches another layer's geometry. Steady state (static camera/world) records
 * zero uploads: sections are cached by their persistent VBO id and only
 * uploaded when first seen or when their vertex count changes (the old range
 * is zeroed so no ghost geometry survives). TRANSLUCENT layers keep the
 * vanilla path (their back-to-front section order must be preserved exactly).
 * A failure resets the failing layer's caches and retries on the next frame
 * (self-heal); only a persistent failure - three resets inside a short window -
 * disables the batch for the rest of the session and falls back to vanilla.
 *
 * <p>NOTE: this project's Mixin runtime runs at JAVA_8 compatibility level, so
 * this class must not contain nested classes or lambdas. vkCmdDraw*Indirect is
 * deliberately NOT used: this machine's Windows Intel driver emulates it on
 * the CPU and a 600-command indirect batch costs ~60ms of GPU time.
 */
@Mixin(VboRenderList.class)
public class VboRenderListMixin {

    private static final int STRIDE = 28; // DefaultVertexFormats.BLOCK
    private static final int QUAD_ALIGNMENT_BYTES = 4 * STRIDE;
    private static final int AREA_MAX_BYTES = 48 * 1024 * 1024;
    private static final int LAYER_COUNT = 4; // BlockRenderLayer.values().length

    private static final boolean BATCH_WANTED = VulkanModConfig.getBool("TERRAIN_BATCH", false);
    private static boolean batchActive = BATCH_WANTED;
    private static boolean initTried;
    // ARM B toggle (paired A/B, TB_TRANS_VANILLA=1): force TRANSLUCENT
    // back onto the vanilla per-section path, reproducing the pre-DrawBuffers
    // behaviour so the single-MVP translucent path can be measured against it on
    // the SAME scene. Default OFF (translucent goes through the shared-area path).
    private static final boolean TRANS_VANILLA =
            VulkanModConfig.getBool("TB_TRANS_VANILLA", false);

    // Second-knife toggle (TERRAIN_REGION=1): a persistent per-section
    // registry lets the steady-state per-frame collection loop skip the native
    // bindBuffer() + reflected vertex-count lookup that the vanilla per-section
    // path performs for EVERY visible section (~337 x 4 / frame). The registry
    // maps each RenderChunk to its stable persistent-VBO id + vertex count, so
    // the area offset is recovered from secCache with two HashMap.gets instead
    // of a native GL call + reflection. Draws/geometry are unchanged, so the
    // only observable effect is lower camWorld. Default OFF (current proven path).
    private static final boolean REGION_WANTED =
            VulkanModConfig.getBool("TERRAIN_REGION", false);
    // Second-knife registry: packed (chunkX,chunkY,chunkZ,layer) -> {pid, vc, _, x, y, z, _}.
    // Keyed by POSITION (not RenderChunk) so it survives ChunkRenderContainer
    // recreation between frames - the original RenderChunk-keyed map never hit
    // because those instances are reallocated every frame. Cleared on a stride
    // re-scan (see drawBatched) so a chunk rebuild (new persistent id) is caught.
    private static final Map[] secReg = new HashMap[LAYER_COUNT];
    private static final long[] regLastFull = new long[LAYER_COUNT];
    // set when an area-buffer growth reset this layer's caches; next frame re-derives
    private static final boolean[] regionDirty = new boolean[LAYER_COUNT];
    // pooled section arrays (reused across frames) to kill the per-frame
    // new long[6] allocation that dominates the GC cost of the collection loop.
    private static final List<long[]> secPool = new ArrayList<>();
    private static final long[] regHitMiss = new long[2]; // [0]=hit [1]=miss per log window

    // ---- Third-knife: area-grouped stable visible table + per-area multi-draw ----
    // (TERRAIN_AREA=1). Mirrors upstream VulkanMod's ChunkArea grouping:
    // sections are bucketed into spatial areas; the per-frame cost is a DELTA (only
    // new / removed / rebuilt sections touch the GPU or a HashMap), and each area is
    // emitted as ONE vkCmdDrawMultiIndexedEXT instead of a per-section draw. The
    // per-frame Java traversal of every visible section is skipped in steady state
    // (cached sections are flagged alive with a single HashMap.get). Default OFF.
    private static final boolean AREA_WANTED =
            VulkanModConfig.getBool("TERRAIN_AREA", false);
    private static final int AREA_SHIFT = 5; // 32-block (2-chunk) areas -> a few dozen areas
    // stable per-layer: posKey(x,y,z,layer) -> {offset, vc, x, y, z, areaId, lastSeen, pid}
    private static final Map[] areaSec = new HashMap[LAYER_COUNT];
    private static final Map[] areaToPids = new HashMap[LAYER_COUNT]; // areaId -> List<posKey>
    private static final boolean[] areaDirty = new boolean[LAYER_COUNT];
    private static final long[] areaLastFull = new long[LAYER_COUNT];

    private static Buffer[] areaVtx = new Buffer[LAYER_COUNT]; // per layer, zero-filled
    private static AutoIndexBuffer autoIdx;
    private static long tbatchFrameCounter;

    // Thrash guard for the self-healing catch in vulkanmod$batchChunkLayer: a
    // one-off reset (e.g. area-buffer exhaustion right after a death-respawn
    // teleport loads a burst of new sections) must recover by itself, but a
    // persistent failure must neither spam the log nor flip-flop every frame -
    // three resets inside a 600-counter-tick window (~150 layer frames)
    // re-disables the batch for the rest of the session.
    private static long lastBatchResetTick = -1L;
    private static int batchResets;

    // persistent section cache, per layer: srcVkBufferId -> {dstByte, vc}
    private static final Map[] secCache = new HashMap[LAYER_COUNT];
    private static final int[] areaUsed = new int[LAYER_COUNT];

    // --- per-call scratch ---
    private static final List<long[]> secList = new ArrayList<>(); // {srcVkBufferId, dstByte, vc, x, y, z}
    private static final List<ByteBuffer> secData = new ArrayList<>(); // parallel: client bytes or null
    private static final List<com.yuhan123.vulkanmod.vulkan.memory.buffer.VertexBuffer> secVb = new ArrayList<>(); // parallel: persistent (for readback)

    private static ByteBuffer gatherBB = alloc(4 * 1024 * 1024);
    private static ByteBuffer verifyBB = alloc(4 * 1024 * 1024);
    private static ByteBuffer zeroBB = alloc(1024 * 1024); // zero-fill scratch

    private static ByteBuffer alloc(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    /**
     * Indirect / multi-draw POC switch (INDIRECT), isolated from the
     * default batched path so it can never change production behaviour:
     *   0 = coalesced runs (default): one vkCmdDrawIndexed per adjacent run,
     *       i.e. ~1 draw per layer in steady state. Byte-identical to before.
     *   1 = per-section: one vkCmdDrawIndexed PER visible section, all into the
     *       same area buffer (no per-section bind/push). ~705 draws -> isolates
     *       GPU per-draw EXECUTION overhead from CPU command encoding.
     *   2 = grouped multi-draw: vkCmdDrawMultiIndexedEXT, ~40 calls each covering
     *       ~18 sections (705 sub-draws). The "indirect/minimal" the review asked
     *       for; it reduces command-buffer entries but still executes 705 GPU
     *       draws, so it separates command-count from draw-execution-count.
     * The decision line: if gpuPass(0) << gpuPass(1) ~= gpuPass(2), GPU per-draw
     * execution overhead is real -> coalescing (draw COUNT reduction) is the lever
     * and the 3080 win is real; multi-draw alone does not beat coalescing.
     */
    private static final int INDIRECT_GROUP = 18; // 705/18 ~= 39 -> ~40 multi-draw calls

    private static int indirectMode() {
        final String v = VulkanModConfig.get("INDIRECT");
        if ("1".equals(v)) return 1;
        if ("2".equals(v)) return 2;
        return 0;
    }

    /**
     * Emits the visible sections as a small number of vkCmdDrawMultiIndexedEXT
     * calls (INDIRECT=2). Geometry is unchanged: every section is already baked
     * into the single area buffer at world coordinates, so each sub-draw is just
     * {firstIndex=0, indexCount=quads*6, vertexOffset=sectionStartVtx} - the same
     * parameters a per-section vkCmdDrawIndexed would use.
     *
     * @return number of vkCmdDrawMultiIndexedEXT calls issued.
     */
    private static int emitMultiDraw(org.lwjgl.system.MemoryStack stack, VkCommandBuffer cb,
                                     int[] order, int n, boolean noDraw) {
        if (noDraw) return 0;
        if (!DeviceManager.MULTI_DRAW_AVAILABLE) return 0; // caller falls back to per-section
        int calls = 0;
        for (int base = 0; base < n; base += INDIRECT_GROUP) {
            final int cnt = Math.min(INDIRECT_GROUP, n - base);
            final VkMultiDrawIndexedInfoEXT.Buffer info =
                    VkMultiDrawIndexedInfoEXT.calloc(cnt, stack);
            for (int t = 0; t < cnt; t++) {
                final long[] s = secList.get(order[base + t]);
                final int startVtx = (int) (s[1] / STRIDE);
                final int vc = (int) s[2];
                final int idxCount = (int) ((long) vc / 4L) * 6;
                info.get(t).firstIndex(0).indexCount(idxCount).vertexOffset(startVtx);
            }
            // info position stays 0, limit = cnt: LWJGL reads cnt sub-draws.
            // pVertexBuffer = null: per-draw vertex offset comes from the struct's
            // vertexOffset field (the area buffer is bound once at 0).
            org.lwjgl.vulkan.EXTMultiDraw.vkCmdDrawMultiIndexedEXT(
                    cb, info, 1, 0, VkMultiDrawIndexedInfoEXT.SIZEOF, (java.nio.IntBuffer) null);
            FrameProfiler.addCmdCount(FrameProfiler.CMD_DRAW_MULTI_INDEXED);
            calls++;
        }
        return calls;
    }

    // vanilla inherited fields (ChunkRenderContainer) via reflection: @Shadow on
    // superclass members is not guaranteed across Mixin versions, this is.
    private static Field fRenderChunks;
    private static Field fViewEntityX;
    private static Field fViewEntityY;
    private static Field fViewEntityZ;
    private static Field fVboCount;

    @SuppressWarnings("unchecked")
    private static List<RenderChunk> renderChunks(Object self) throws Exception {
        if (fRenderChunks == null) {
            fRenderChunks = Class.forName("net.minecraft.client.renderer.ChunkRenderContainer")
                    .getDeclaredField("renderChunks");
            fRenderChunks.setAccessible(true);
        }
        return (List<RenderChunk>) fRenderChunks.get(self);
    }

    private static long packKey(int x, int y, int z, int layer) {
        // stable per-section key; signed coords are recomputed identically so
        // distinct positions never collide within the +/-2M block range.
        return ((long) layer << 62) | (((long) x) << 42) | (((long) y) << 21) | ((long) z);
    }

    @Inject(method = "renderChunkLayer", at = @At("HEAD"), cancellable = true)
    private void vulkanmod$batchChunkLayer(BlockRenderLayer layer, CallbackInfo ci) {
        if (!batchActive) {
            return; // vanilla path untouched
        }
        if (TRANS_VANILLA && layer == BlockRenderLayer.TRANSLUCENT) {
            return; // ARM B: pre-DrawBuffers behaviour (vanilla per-section translucent)
        }
        // TRANSLUCENT no longer excluded: it now shares the same single area
        // buffer + single-MVP path as the opaque layers. It is emitted
        // per-section in back-to-front distance order (sorted in drawBatched)
        // so alpha blending stays correct, instead of keeping a per-section
        // matrix like the old vanilla path did (the camWorld wall).

        try {
            if (!initTried) {
                initTried = true;
                initBatch();
                VKProf.info("[VKPROF] INDIRECT mode={} (0=coalesced,1=per-section,2=multi-draw); VK_EXT_multi_draw available={}; TERRAIN_REGION={} (second-knife); TERRAIN_AREA={} (third-knife area-grouped)",
                        indirectMode(), com.yuhan123.vulkanmod.vulkan.device.DeviceManager.MULTI_DRAW_AVAILABLE, REGION_WANTED, AREA_WANTED);
            }

            if (AREA_WANTED) {
                if (!drawBatchedArea(layer)) {
                    return; // nothing to draw: let vanilla run (it no-ops on an empty list)
                }
            } else if (!drawBatched(layer)) {
                return; // nothing to draw: let vanilla run (it no-ops on an empty list)
            }

            if (++tbatchFrameCounter % 600L == 1L) {
                VKProf.info("[VKPROF] TBATCH active layer={} batchedLayersDrawn={} cachedSections={} (compare fps/gpuPass/cpu vs VULKANMOD_TERRAIN_BATCH unset)",
                        layer, tbatchFrameCounter, secCache[layer.ordinal()].size());
                if (REGION_WANTED) {
                    VKProf.info("[VKPROF] TBATCH region hit={} miss={} regSize={}",
                            regHitMiss[0], regHitMiss[1], secReg[layer.ordinal()].size());
                    regHitMiss[0] = 0; regHitMiss[1] = 0;
                }
            }
            ci.cancel();
        } catch (Throwable t) {
            // Self-heal instead of the old permanent-disable. The exhaustion
            // throw fires when the per-layer bump allocator has filled up
            // (rebuilt sections never free their old offsets), typically right
            // after a death-respawn teleport loads a burst of new sections.
            // Clearing ONLY the failed layer's caches makes the next frame
            // re-derive every visible section from fresh vertex sources at
            // offset 0 - which also compacts away the leaked holes; vanilla
            // finishes this frame so nothing is lost. The other layers' caches
            // stay valid because their area buffers are untouched. Null checks
            // cover a catch that fires while initBatch() itself is still
            // failing (cache maps not yet assigned).
            final int layerIdx = layer.ordinal();
            if (secCache[layerIdx] != null) {
                secCache[layerIdx].clear();
                areaUsed[layerIdx] = 0;
                if (REGION_WANTED && secReg[layerIdx] != null) {
                    secReg[layerIdx].clear();
                    regLastFull[layerIdx] = 0L;
                    regionDirty[layerIdx] = true;
                }
                if (AREA_WANTED && areaSec[layerIdx] != null) {
                    areaSec[layerIdx].clear();
                    areaToPids[layerIdx].clear();
                    areaDirty[layerIdx] = true;
                    areaLastFull[layerIdx] = 0L;
                }
            }
            if (lastBatchResetTick >= 0L && tbatchFrameCounter - lastBatchResetTick < 600L) {
                batchResets++;
            } else {
                batchResets = 1;
            }
            lastBatchResetTick = tbatchFrameCounter;
            if (batchResets >= 3) {
                batchActive = false;
                VulkanMod.LOGGER.error("[VKPROF] TBATCH failed {} times inside a short window, disabling terrain batch for this session", Integer.valueOf(batchResets), t);
            } else {
                VulkanMod.LOGGER.error("[VKPROF] TBATCH layer {} failed, resetting its caches and retrying next frame (self-heal {}/3)", layer, Integer.valueOf(batchResets), t);
            }
            // Do NOT cancel: let vanilla render this layer so the frame stays whole.
        }
    }

    /**
     * Vanilla per-section path: the fog translation is re-published per section
     * by {@code ChunkRenderContainerMixin} on {@code preRenderChunk}. Clear it
     * once the layer is done so it cannot leak into the next non-terrain draw
     * that shares block.vsh.
     */
    @Inject(method = "renderChunkLayer", at = @At("TAIL"))
    private void vulkanmod$clearChunkOffset(BlockRenderLayer layer, CallbackInfo ci) {
        com.yuhan123.vulkanmod.vulkan.VRenderSystem.setChunkOffset(0.0f, 0.0f, 0.0f);
    }

    private static void initBatch() throws Exception {
        for (int i = 0; i < LAYER_COUNT; i++) {
            areaVtx[i] = areaBuffer(16 * 1024 * 1024);
            secCache[i] = new HashMap();
            secReg[i] = new HashMap();
            regLastFull[i] = 0L;
            regionDirty[i] = false;
            areaSec[i] = new HashMap();
            areaToPids[i] = new HashMap();
            areaDirty[i] = false;
            areaLastFull[i] = 0L;
        }
        autoIdx = new AutoIndexBuffer(4096, 7 /* GL_QUADS */);
        initViewEntityFields();
        VKProf.info("[VKPROF] TBATCH initialized: single-draw mode, per-layer zeroed DEVICE-LOCAL area (VULKANMOD_TERRAIN_BATCH=1)");
    }

    private static void initViewEntityFields() throws Exception {
        if (fViewEntityX == null) {
            final Class<?> cc = Class.forName("net.minecraft.client.renderer.ChunkRenderContainer");
            fViewEntityX = cc.getDeclaredField("viewEntityX");
            fViewEntityY = cc.getDeclaredField("viewEntityY");
            fViewEntityZ = cc.getDeclaredField("viewEntityZ");
            fViewEntityX.setAccessible(true);
            fViewEntityY.setAccessible(true);
            fViewEntityZ.setAccessible(true);
        }
    }

    /**
     * Device-local (VRAM) vertex buffer. Terrain geometry is static per section
     * (cached), so it is uploaded ONCE per section via the staging buffer and
     * then stays resident in VRAM - the GPU never re-fetches it from system
     * memory every frame. A HOST_MEM area would cost a per-frame PCIe vertex
     * transfer on a discrete GPU (the real "copied every frame" cost). Vulkan
     * does not guarantee device memory to be zeroed, so zero-fill the
     * allocation: any unwritten vertex the single big draw might touch must
     * rasterize as degenerate, not garbage.
     */
    private static Buffer areaBuffer(int size) throws Exception {
        final Buffer b = new Buffer(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, MemoryTypes.GPU_MEM);
        b.createBuffer(size);
        zeroBB.position(0).limit(zeroBB.capacity());
        for (int off = 0; off < size; off += zeroBB.capacity()) {
            final int chunk = Math.min(zeroBB.capacity(), size - off);
            b.type.copyToBuffer(b, zeroBB, chunk, 0, off);
        }
        return b;
    }

    // ===================== Third-knife: area-grouped path =====================

    private static long areaIdOf(int x, int y, int z, int layer) {
        final long ax = ((long) x) >> AREA_SHIFT;
        final long ay = ((long) y) >> AREA_SHIFT;
        final long az = ((long) z) >> AREA_SHIFT;
        return ((long) layer << 62)
                | ((ax & 0x1FFFFFL) << 42)
                | ((ay & 0x1FFFFFL) << 21)
                | (az & 0x1FFFFFL);
    }

    private static int safeCount(VertexBuffer vbo) {
        try {
            if (fVboCount == null) {
                fVboCount = VertexBuffer.class.getDeclaredField("count");
                fVboCount.setAccessible(true);
            }
            return fVboCount.getInt(vbo);
        } catch (Exception ex) {
            return 0;
        }
    }

    private static void bakeUpload(Buffer layerArea, int dst, int bytes, ByteBuffer data,
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

    private static void zeroRange(Buffer layerArea, int dst, int bytes) {
        if (bytes <= 0) return;
        zeroBB.position(0).limit(zeroBB.capacity());
        for (int off = 0; off < bytes; off += zeroBB.capacity()) {
            final int chunk = Math.min(zeroBB.capacity(), bytes - off);
            layerArea.type.copyToBuffer(layerArea, zeroBB, chunk, 0, dst + off);
        }
    }

    /**
     * Emits one area: gathers the sections visible THIS frame, builds draw params,
     * and issues a single vkCmdDrawMultiIndexedEXT (or a per-section fallback when
     * VK_EXT_multi_draw is unavailable). For translucent, sections are ordered
     * back-to-front within the area so alpha blending stays correct.
     */
    @SuppressWarnings("unchecked")
    private static int emitArea(org.lwjgl.system.MemoryStack stack, VkCommandBuffer cb, Buffer layerArea,
                                Map<Long, long[]> cache, List<Long> keys, int frame,
                                boolean translucent, double vx, double vy, double vz) {
        int cnt = 0;
        for (int k = 0; k < keys.size(); k++) {
            final long[] e = cache.get(keys.get(k));
            if (e != null && e[6] == frame) cnt++;
        }
        if (cnt == 0) return 0;
        final int[] startVtx = new int[cnt];
        final int[] idxCount = new int[cnt];
        final int[] sxArr = new int[cnt];
        final int[] syArr = new int[cnt];
        final int[] szArr = new int[cnt];
        final int[] idx = new int[cnt];
        int t = 0;
        for (int k = 0; k < keys.size(); k++) {
            final long[] e = cache.get(keys.get(k));
            if (e != null && e[6] == frame) {
                final int vc = (int) e[1];
                startVtx[t] = (int) (e[0] / STRIDE);
                idxCount[t] = (int) ((long) vc / 4L) * 6;
                sxArr[t] = (int) e[2];
                syArr[t] = (int) e[3];
                szArr[t] = (int) e[4];
                idx[t] = t;
                t++;
            }
        }
        if (translucent) {
            final double[] d = new double[cnt];
            for (int k = 0; k < cnt; k++) {
                final double dx = sxArr[idx[k]] - vx, dy = syArr[idx[k]] - vy, dz = szArr[idx[k]] - vz;
                d[k] = dx * dx + dy * dy + dz * dz;
            }
            for (int i = 1; i < cnt; i++) {
                final int curIdx = idx[i];
                final double curD = d[i];
                int j = i - 1;
                while (j >= 0 && d[j] < curD) {
                    idx[j + 1] = idx[j];
                    d[j + 1] = d[j];
                    j--;
                }
                idx[j + 1] = curIdx;
                d[j + 1] = curD;
            }
        }
        if (com.yuhan123.vulkanmod.vulkan.device.DeviceManager.MULTI_DRAW_AVAILABLE) {
            final VkMultiDrawIndexedInfoEXT.Buffer info = VkMultiDrawIndexedInfoEXT.calloc(cnt, stack);
            for (int k = 0; k < cnt; k++) {
                final int s = idx[k];
                info.get(k).firstIndex(0).indexCount(idxCount[s]).vertexOffset(startVtx[s]);
            }
            EXTMultiDraw.vkCmdDrawMultiIndexedEXT(cb, info, 1, 0, VkMultiDrawIndexedInfoEXT.SIZEOF, (java.nio.IntBuffer) null);
            FrameProfiler.addCmdCount(FrameProfiler.CMD_DRAW_MULTI_INDEXED);
            return 1;
        }
        for (int k = 0; k < cnt; k++) {
            final int s = idx[k];
            org.lwjgl.vulkan.VK10.vkCmdDrawIndexed(cb, idxCount[s], 1, 0, startVtx[s], 0);
            FrameProfiler.addCmdCount(FrameProfiler.CMD_DRAW_INDEXED);
        }
        return cnt;
    }

    /**
     * Third-knife terrain path (TERRAIN_AREA=1). Replicates upstream
     * VulkanMod's ChunkArea grouping: sections are bucketed into spatial areas and
     * only NEW / REMOVED / REBUILT sections do work each frame. Cached sections
     * are flagged alive with a single HashMap.get, so the per-frame Java
     * traversal of every visible section is skipped in steady state. Each area is
     * emitted as one vkCmdDrawMultiIndexedEXT instead of a per-section draw.
     *
     * @return true if the layer was rendered; false if nothing to draw.
     */
    @SuppressWarnings("unchecked")
    private boolean drawBatchedArea(BlockRenderLayer layer) throws Exception {
        final List<RenderChunk> chunks = renderChunks(this);
        if (chunks.isEmpty()) {
            return false;
        }
        final int layerIdx = layer.ordinal();
        final Buffer layerArea = areaVtx[layerIdx];
        final Map<Long, long[]> cache = areaSec[layerIdx];
        final Map<Long, List<Long>> a2p = areaToPids[layerIdx];
        final int frame = (int) (tbatchFrameCounter & 0x7FFFFFFF);
        final boolean translucent = layer == BlockRenderLayer.TRANSLUCENT;

        if (areaDirty[layerIdx]) {
            areaDirty[layerIdx] = false;
            areaUsed[layerIdx] = 0;
            cache.clear();
            a2p.clear();
        }

        final boolean forceRescan = (tbatchFrameCounter - areaLastFull[layerIdx] >= 256L);
        int added = 0;
        int maxEndVtx = 0;

        // ---- delta collection: cached sections are flagged alive with ONE HashMap.get ----
        for (int i = 0, n = chunks.size(); i < n; i++) {
            final RenderChunk rc = chunks.get(i);
            final VertexBuffer vbo = rc.getVertexBufferByLayer(layerIdx);
            if (vbo == null) continue;
            final net.minecraft.util.math.BlockPos bp = rc.getPosition();
            final long key = packKey(bp.getX(), bp.getY(), bp.getZ(), layerIdx);
            long[] e = cache.get(key);
            boolean needUpload = (e == null);
            if (e != null) {
                e[6] = frame; // mark alive (cheap; no bindBuffer / reflection)
                if (forceRescan) {
                    // detect a rebuild at the same position (pid and/or vc changed)
                    vbo.bindBuffer();
                    final VkGlBuffer glb = VkGlBuffer.getArrayBufferBound();
                    if (glb != null) {
                        final com.yuhan123.vulkanmod.vulkan.memory.buffer.VertexBuffer p = glb.getPersistentVertexBuffer();
                        final ByteBuffer db = glb.getData();
                        long pid = -1L; int vc = 0;
                        if (p != null) {
                            pid = p.getId();
                            vc = (db != null) ? db.limit() / STRIDE : safeCount(vbo);
                        } else if (db != null) {
                            vc = safeCount(vbo);
                            pid = -(long) i - 1;
                        }
                        if (pid != e[7] || vc != (int) e[1]) {
                            needUpload = true;
                        }
                    }
                }
            }

            if (needUpload) {
                // first sighting or rebuild: read source, upload, assign offset
                vbo.bindBuffer();
                final VkGlBuffer glb = VkGlBuffer.getArrayBufferBound();
                if (glb == null) continue;
                final com.yuhan123.vulkanmod.vulkan.memory.buffer.VertexBuffer persistent = glb.getPersistentVertexBuffer();
                final ByteBuffer data = glb.getData();
                long pid; int vc;
                if (persistent != null) {
                    pid = persistent.getId();
                    vc = (data != null) ? data.limit() / STRIDE : safeCount(vbo);
                } else if (data != null) {
                    vc = safeCount(vbo);
                    pid = -(long) i - 1;
                } else {
                    continue;
                }
                if (vc <= 0) continue;
                final int bytes = vc * STRIDE;
                int dst = (areaUsed[layerIdx] + QUAD_ALIGNMENT_BYTES - 1) / QUAD_ALIGNMENT_BYTES * QUAD_ALIGNMENT_BYTES;
                if (dst + bytes > layerArea.getBufferSize()) {
                    final int newSize = (int) Math.min(AREA_MAX_BYTES,
                            Math.max(dst + bytes, (long) layerArea.getBufferSize() * 2L));
                    areaVtx[layerIdx] = areaBuffer(newSize);
                    cache.clear();
                    a2p.clear();
                    areaUsed[layerIdx] = 0;
                    areaDirty[layerIdx] = true;
                    VKProf.info("[VKPROF] TBAREA layer={} area buffer grown to {} bytes; fallback this frame", layerIdx, newSize);
                    return false;
                }
                if (e != null) zeroRange(layerArea, (int) e[0], (int) e[1] * STRIDE); // rebuild: kill old range
                bakeUpload(layerArea, dst, bytes, data, persistent, bp.getX(), bp.getY(), bp.getZ());
                areaUsed[layerIdx] = dst + bytes;
                final long areaId = areaIdOf(bp.getX(), bp.getY(), bp.getZ(), layerIdx);
                e = new long[]{dst, vc, bp.getX(), bp.getY(), bp.getZ(), areaId, frame, pid};
                cache.put(key, e);
                List<Long> lst = a2p.get(areaId);
                if (lst == null) {
                    lst = new ArrayList<>();
                    a2p.put(areaId, lst);
                }
                lst.add(key);
                added++;
            }
            if (e != null) {
                final int end = (int) (e[0] / STRIDE) + (int) e[1];
                if (end > maxEndVtx) maxEndVtx = end;
            }
        }
        if (forceRescan) areaLastFull[layerIdx] = tbatchFrameCounter;

        // periodic sweep of fully-expired sections to reclaim area-buffer space
        if ((tbatchFrameCounter & 511) == 0 && !cache.isEmpty()) {
            final int expiry = frame - 512;
            final java.util.Iterator<Map.Entry<Long, long[]>> it = cache.entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<Long, long[]> me = it.next();
                final long[] e = me.getValue();
                if (e[6] < expiry) {
                    zeroRange(layerArea, (int) e[0], (int) e[1] * STRIDE);
                    final List<Long> lst = a2p.get(e[5]);
                    if (lst != null) lst.remove(me.getKey());
                    it.remove();
                }
            }
        }

        if (cache.isEmpty()) return false;

        // ---- apply block pipeline state + single MVP (identical to drawBatched) ----
        final ShaderInstance shader = PipelineManager.chooseShader(DefaultVertexFormats.BLOCK);
        if (shader == null) {
            throw new IllegalStateException("no block shader for area terrain");
        }
        if (layer == BlockRenderLayer.SOLID) {
            GlStateManager.disableAlpha();
            GlStateManager.depthMask(true);
        } else {
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(516, 0.1F);
            GlStateManager.depthMask(!translucent);
        }
        if (translucent) {
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(770, 771);
        } else {
            GlStateManager.disableBlend();
        }
        com.yuhan123.vulkanmod.vulkan.VRenderSystem.alphaTest = layer != BlockRenderLayer.SOLID;
        com.yuhan123.vulkanmod.vulkan.VRenderSystem.depthMask = !translucent;
        final double vx = fViewEntityX.getDouble(this);
        final double vy = fViewEntityY.getDouble(this);
        final double vz = fViewEntityZ.getDouble(this);
        com.yuhan123.vulkanmod.vulkan.VRenderSystem.setChunkOffset((float) -vx, (float) -vy, (float) -vz);
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) -vx, (float) -vy, (float) -vz);
        shader.apply();
        Renderer.getInstance().flushPipelineBind();

        // ---- emit: per area, ONE multi-draw (or per-section fallback) ----
        final VkCommandBuffer cb = Renderer.getCommandBuffer();
        final boolean noDraw = VulkanModConfig.getBool("TB_NODRAW", false);
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VK10.vkCmdBindVertexBuffers(cb, 0, stack.longs(layerArea.getId()), stack.longs(0L));
            autoIdx.checkCapacity(maxEndVtx);
            final IndexBuffer ib = autoIdx.getIndexBuffer();
            Renderer.getDrawer().bindIndexBuffer(cb, ib, ib.indexType.value);
            if (!noDraw) {
                for (java.util.Map.Entry<Long, List<Long>> en : a2p.entrySet()) {
                    emitArea(stack, cb, layerArea, cache, en.getValue(), frame, translucent, vx, vy, vz);
                }
            }
        }
        if (translucent) {
            GlStateManager.depthMask(true);
            GlStateManager.disableBlend();
        }
        GlStateManager.popMatrix();
        com.yuhan123.vulkanmod.vulkan.VRenderSystem.setChunkOffset(0.0f, 0.0f, 0.0f);

        if (tbatchFrameCounter % 600L == 1L) {
            VKProf.info("[VKPROF] TBAREA layer={} areas={} cachedSections={} added={} maxEndVtx={}",
                    layerIdx, a2p.size(), cache.size(), added, maxEndVtx);
        }

        chunks.clear();
        GlStateManager.resetColor();
        return true;
    }

    /**
     * @return true if the layer was rendered batched; false if there was nothing
     *         to draw (vanilla then no-ops too).
     */
    private boolean drawBatched(BlockRenderLayer layer) throws Exception {
        final List<RenderChunk> chunks = renderChunks(this);
        if (chunks.isEmpty()) {
            return false;
        }

        final int layerIdx = layer.ordinal();
        // a growth in a previous frame reset this layer's caches; start clean
        if (regionDirty[layerIdx]) {
            regionDirty[layerIdx] = false;
            areaUsed[layerIdx] = 0;
            if (REGION_WANTED) {
                secReg[layerIdx].clear();
            }
        }
        final Buffer layerArea = areaVtx[layerIdx];
        final Map<Long, int[]> cache = secCache[layerIdx];

        // ghost expiry: when a section rebuilds, vanilla may allocate a NEW GL
        // buffer, orphaning the old cache entry - its stale geometry would be
        // drawn forever (accumulating "garbage geometry" and slow drift). Every
        // 256 layer calls, zero and drop entries not seen for ~512 calls.
        if ((tbatchFrameCounter & 255) == 0 && !cache.isEmpty()) {
            final int now = (int) (tbatchFrameCounter & 0x7FFFFFFF);
            final java.util.Iterator<Map.Entry<Long, int[]>> it = cache.entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<Long, int[]> e = it.next();
                final int[] en = e.getValue();
                final int age = now - en[2];
                if (age > 512 || age < -256) { // negative = wrapped; drop to be safe
                    final int oldBytes = en[1] * STRIDE;
                    zeroBB.position(0).limit(zeroBB.capacity());
                    for (int off = 0; off < oldBytes; off += zeroBB.capacity()) {
                        final int chunk = Math.min(zeroBB.capacity(), oldBytes - off);
                        layerArea.type.copyToBuffer(layerArea, zeroBB, chunk, 0, en[0] + off);
                    }
                    it.remove();
                }
            }
        }

        // ---- collect sections (order preserved) ----
        secList.clear();
        secData.clear();
        secVb.clear();
        int secCount = 0;
        // Second-knife: every ~256 calls force a full re-scan (native bindBuffer +
        // reflected count) so a chunk rebuild - which allocates a NEW persistent
        // VBO id - is detected even though we normally skip the per-frame bind.
        // Bounds any stale-geometry window to <=256 calls (~4s at 60fps).
        final boolean forceRescan = REGION_WANTED && (tbatchFrameCounter - regLastFull[layerIdx] >= 256L);
        for (int i = 0, n = chunks.size(); i < n; i++) {
            final RenderChunk rc = chunks.get(i);
            final VertexBuffer vbo = rc.getVertexBufferByLayer(layerIdx);
            if (vbo == null) {
                continue;
            }

            long pid = -1L;
            int vc = 0;
            int sx = 0, sy = 0, sz = 0;
            com.yuhan123.vulkanmod.vulkan.memory.buffer.VertexBuffer persistent = null;
            ByteBuffer data = null;
            boolean fromReg = false;
            if (REGION_WANTED && !forceRescan) {
                final net.minecraft.util.math.BlockPos bp = rc.getPosition();
                final long key = packKey(bp.getX(), bp.getY(), bp.getZ(), layerIdx);
                final long[] reg = (long[]) secReg[layerIdx].get(Long.valueOf(key));
                if (reg != null) {
                final int[] c = cache.get(reg[0]);
                // validate the cached entry's baked POSITION too: a freed
                // VkBuffer handle can be re-created for a section at a different
                // position, and the registry would otherwise happily serve the
                // old section's geometry for the new one.
                if (c != null && c[1] == (int) reg[1]
                        && c[3] == (int) reg[3] && c[4] == (int) reg[4] && c[5] == (int) reg[5]) {
                        pid = reg[0];
                        vc = (int) reg[1];
                        sx = (int) reg[3]; sy = (int) reg[4]; sz = (int) reg[5];
                        fromReg = true;
                        regHitMiss[0]++;
                    } else {
                        secReg[layerIdx].remove(Long.valueOf(key));
                        regHitMiss[1]++;
                    }
                } else {
                    regHitMiss[1]++;
                }
            }
            if (!fromReg) {
                vbo.bindBuffer(); // vanilla GL bookkeeping; also sets the bound array buffer
                final VkGlBuffer glb = VkGlBuffer.getArrayBufferBound();
                if (glb == null) {
                    continue; // vanilla skips unbindable sections too (draw path returns)
                }
                persistent = glb.getPersistentVertexBuffer();
                data = glb.getData();
                final net.minecraft.util.math.BlockPos bp = rc.getPosition();
                sx = bp.getX(); sy = bp.getY(); sz = bp.getZ();
                if (persistent != null) {
                    pid = persistent.getId();
                    if (data != null) {
                        vc = data.limit() / STRIDE;
                    } else {
                        if (fVboCount == null) {
                            fVboCount = VertexBuffer.class.getDeclaredField("count");
                            fVboCount.setAccessible(true);
                        }
                        vc = fVboCount.getInt(vbo);
                    }
                } else if (data != null) {
                    if (fVboCount == null) {
                        fVboCount = VertexBuffer.class.getDeclaredField("count");
                        fVboCount.setAccessible(true);
                    }
                    vc = fVboCount.getInt(vbo);
                    pid = -(long) i - 1; // non-cacheable marker (matches original)
                } else {
                    continue; // vanilla has nothing to draw for this section either
                }
                if (vc <= 0) {
                    continue;
                }
                if (REGION_WANTED) {
                    // cap registry to avoid unbounded growth across chunk unloads
                    if (secReg[layerIdx].size() > 32768) {
                        secReg[layerIdx].clear();
                    }
                    secReg[layerIdx].put(Long.valueOf(packKey(sx, sy, sz, layerIdx)),
                            new long[]{pid, vc, 0L, sx, sy, sz, 0L});
                }
            }
            if (vc <= 0) {
                continue;
            }
            // reuse a pooled array instead of allocating new long[6] per section
            // (the dominant per-frame GC cost at ~1600 sections x 4 layers).
            long[] e = (secCount < secPool.size()) ? secPool.get(secCount) : null;
            if (e == null) {
                e = new long[6];
                secPool.add(e);
            }
            secCount++;
            e[0] = pid; e[1] = 0L; e[2] = vc; e[3] = sx; e[4] = sy; e[5] = sz;
            secList.add(e);
            secData.add(data);
            secVb.add(persistent);
        }
        if (forceRescan) {
            regLastFull[layerIdx] = tbatchFrameCounter;
        }
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);

        if (secList.isEmpty()) {
            return false;
        }

        // ---- assign quad-aligned area offsets; upload sections never seen ----
        final boolean verify = tbatchFrameCounter < 2;
        for (int i = 0, n = secList.size(); i < n; i++) {
            final long[] s = secList.get(i);
            final long srcId = s[0];
            final int vc = (int) s[2];
            final int bytes = vc * STRIDE;
            final boolean cacheable = srcId > 0L;
            int[] entry = cacheable ? (int[]) cache.get(srcId) : null;
            if (entry != null && (entry[3] != (int) s[3] || entry[4] != (int) s[4] || entry[5] != (int) s[5])) {
                // Vulkan handle reuse: this VkBuffer id was freed (chunk unload,
                // or a rebuild's deferred scheduleFree) and later re-created for
                // a DIFFERENT section. The cached vertices are baked for the OLD
                // world offset, so drawing them here renders water/terrain at
                // the wrong position (the checkerboard ocean bug). Zero the
                // stale range so it cannot ghost, then force a full re-upload.
                final int staleBytes = entry[1] * STRIDE;
                zeroBB.position(0).limit(zeroBB.capacity());
                for (int off = 0; off < staleBytes; off += zeroBB.capacity()) {
                    final int zc = Math.min(zeroBB.capacity(), staleBytes - off);
                    layerArea.type.copyToBuffer(layerArea, zeroBB, zc, 0, entry[0] + off);
                }
                entry = null;
            }
            final boolean isNew = !cacheable || entry == null || entry[1] != vc;
            if (isNew) {
                if (bytes > AREA_MAX_BYTES - areaUsed[layerIdx]) {
                    throw new IllegalStateException("area buffer exhausted: " + areaUsed[layerIdx] + " + " + bytes);
                }
                if (entry != null && entry[1] != vc) {
                    // the section was rebuilt with a different vertex count: zero
                    // its old (now stale) range so no ghost geometry survives
                    final int oldBytes = entry[1] * STRIDE;
                    zeroBB.position(0).limit(zeroBB.capacity());
                    for (int off = 0; off < oldBytes; off += zeroBB.capacity()) {
                        final int chunk = Math.min(zeroBB.capacity(), oldBytes - off);
                        layerArea.type.copyToBuffer(layerArea, zeroBB, chunk, 0, entry[0] + off);
                    }
                }
                // quad-align the offset: the shared pattern maps quad q to
                // vertices q*4..q*4+3, so each section's quads must start on a
                // pattern quad boundary or one draw would tear across sections.
                int dst = (areaUsed[layerIdx] + QUAD_ALIGNMENT_BYTES - 1) / QUAD_ALIGNMENT_BYTES * QUAD_ALIGNMENT_BYTES;
                if (dst + bytes > layerArea.getBufferSize()) {
                    final int newSize = (int) Math.min(AREA_MAX_BYTES,
                            Math.max(dst + bytes, (long) layerArea.getBufferSize() * 2L));
                    areaVtx[layerIdx] = areaBuffer(newSize);
                    // reallocation invalidates every cached offset of this layer.
                    // Clear BOTH caches (secCache holds area offsets; secReg holds
                    // the per-section vertex source) - otherwise a previously
                    // cached section (null vertex source in secVb/secData) would be
                    // re-marked "new" and hit copyFromBuffer(null). Fall back to
                    // vanilla for THIS frame; next frame re-derives everything via
                    // the expensive path with valid vertex sources.
                    cache.clear();
                    if (REGION_WANTED) {
                        secReg[layerIdx].clear();
                    }
                    areaUsed[layerIdx] = 0;
                    regionDirty[layerIdx] = true;
                    VKProf.info("[VKPROF] TBATCH area buffer[{}] grown to {} bytes; caches reset, fallback this frame", layerIdx, newSize);
                    return false; // let vanilla render this layer for one frame
                }
                entry = new int[]{dst, vc, (int) (tbatchFrameCounter & 0x7FFFFFFF), (int) s[3], (int) s[4], (int) s[5]};
                areaUsed[layerIdx] = dst + bytes;
                if (cacheable) {
                    cache.put(srcId, entry);
                }
            } else {
                entry[2] = (int) (tbatchFrameCounter & 0x7FFFFFFF);
            }
            s[1] = entry[0];

            if (!isNew) {
                continue;
            }

            // ---- upload: bake the section's world offset into the vertex
            // positions (one-time), then write directly into the host-visible
            // area buffer ----
            final float sx = (int) s[3], sy = (int) s[4], sz = (int) s[5];
            if (gatherBB.capacity() < bytes) {
                gatherBB = alloc(Math.max(bytes, gatherBB.capacity() * 2));
            }
            gatherBB.position(0).limit(bytes);
            final ByteBuffer data = secData.get(i);
            if (data != null) {
                data.position(0).limit(bytes);
                gatherBB.put(data);
                gatherBB.position(0);
            } else {
                final com.yuhan123.vulkanmod.vulkan.memory.buffer.VertexBuffer vb = secVb.get(i);
                MemoryTypes.HOST_MEM.copyFromBuffer(vb, bytes, gatherBB);
                gatherBB.position(0);
            }
            for (int v = 0; v < vc; v++) {
                final int o = v * STRIDE;
                gatherBB.putFloat(o, gatherBB.getFloat(o) + sx);
                gatherBB.putFloat(o + 4, gatherBB.getFloat(o + 4) + sy);
                gatherBB.putFloat(o + 8, gatherBB.getFloat(o + 8) + sz);
            }
            gatherBB.position(0);
            layerArea.type.copyToBuffer(layerArea, gatherBB, bytes, 0, entry[0]);
            if (verify && entry[0] == 0 && layerArea.type.mappable()) {
                if (verifyBB.capacity() < bytes) {
                    verifyBB = alloc(Math.max(bytes, verifyBB.capacity() * 2));
                }
                verifyBB.position(0).limit(bytes);
                MemoryTypes.HOST_MEM.copyFromBuffer(layerArea, bytes, verifyBB);
                verifyBB.position(0);
                gatherBB.position(0);
                boolean ok = true;
                for (int b2 = 0; b2 < bytes; b2++) {
                    if (gatherBB.get(b2) != verifyBB.get(b2)) {
                        ok = false;
                        break;
                    }
                }
                final float px = gatherBB.getFloat(0), py = gatherBB.getFloat(4), pz = gatherBB.getFloat(8);
                VKProf.info("[VKPROF] TBATCH verify first section ({} bytes): {} firstBakedPos=({},{},{})",
                        bytes, ok ? "OK" : "MISMATCH", px, py, pz);
            }
            // DIAG (steady state, first section of each layer at key frames):
            // print the raw local coord, the section origin we added, and the
            // resulting baked world coord, so a wrong offset is visible directly.
            if (tbatchFrameCounter == 40 || tbatchFrameCounter == 401 || tbatchFrameCounter == 4001) {
                VKProf.info("[VKPROF] TBATCH bake layer={} secOrigin=({},{},{}) rawLocal=({},{},{}) bakedWorld=({},{},{})",
                        layer, (int) s[3], (int) s[4], (int) s[5],
                        gatherBB.getFloat(0), gatherBB.getFloat(4), gatherBB.getFloat(8),
                        gatherBB.getFloat(0) + sx, gatherBB.getFloat(4) + sy, gatherBB.getFloat(8) + sz);
            }
        }

        // ---- build a draw order ----
        // For opaque/cutout we sort by area offset and coalesce adjacent runs
        // into one vkCmdDrawIndexed (gaps are simply not drawn). For
        // TRANSLUCENT, blending order is sacred: sort back-to-front by camera
        // distance and emit one draw per section (no coalescing), so the
        // transparent pass reads correctly.
        final int n = secList.size();
        final int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        if (layer == BlockRenderLayer.TRANSLUCENT) {
            final double vx = fViewEntityX.getDouble(this);
            final double vy = fViewEntityY.getDouble(this);
            final double vz = fViewEntityZ.getDouble(this);
            // insertion sort by squared distance DESCENDING (far first)
            for (int i = 1; i < n; i++) {
                final int cur = order[i];
                final long[] sc = secList.get(cur);
                final double dx = sc[3] - vx, dy = sc[4] - vy, dz = sc[5] - vz;
                final double d = dx * dx + dy * dy + dz * dz;
                int j = i - 1;
                while (j >= 0) {
                    final long[] sj = secList.get(order[j]);
                    final double dxj = sj[3] - vx, dyj = sj[4] - vy, dzj = sj[5] - vz;
                    final double dj = dxj * dxj + dyj * dyj + dzj * dzj;
                    if (dj < d) {
                        order[j + 1] = order[j];
                        j--;
                    } else {
                        break;
                    }
                }
                order[j + 1] = cur;
            }
        } else {
            // insertion sort by dstByte (n <= ~700) for run coalescing
            for (int i = 1; i < n; i++) {
                final int cur = order[i];
                final long dst = secList.get(cur)[1];
                int j = i - 1;
                while (j >= 0 && secList.get(order[j])[1] > dst) {
                    order[j + 1] = order[j];
                    j--;
                }
                order[j + 1] = cur;
            }
        }

        final ShaderInstance shader = PipelineManager.chooseShader(DefaultVertexFormats.BLOCK);
        if (shader == null) {
            throw new IllegalStateException("no block shader for batched terrain");
        }
        // Force the per-layer fixed-function state exactly as the vanilla
        // per-section path sees it at draw time, so the resolved block pipeline
        // variant matches. Vanilla's per-layer variants:
        //   SOLID      = alpha test OFF + depth write ON
        //   CUTOUT     = alpha test ON  + depth write ON
        //   TRANSLUCENT = alpha test ON + depth write OFF + blending
        final boolean translucent = layer == BlockRenderLayer.TRANSLUCENT;
        if (layer == BlockRenderLayer.SOLID) {
            GlStateManager.disableAlpha();
            GlStateManager.depthMask(true);
        } else {
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(516 /* GL_GREATER */, 0.1F);
            GlStateManager.depthMask(!translucent);
        }
        if (translucent) {
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(770 /* GL_SRC_ALPHA */, 771 /* GL_ONE_MINUS_SRC_ALPHA */);
        } else {
            GlStateManager.disableBlend();
        }
        // belt and suspenders: write the flags the pipeline-state snapshot reads
        // directly, in case the GlStateManager hooks lag the snapshot in any way
        com.yuhan123.vulkanmod.vulkan.VRenderSystem.alphaTest = layer != BlockRenderLayer.SOLID;
        com.yuhan123.vulkanmod.vulkan.VRenderSystem.depthMask = !translucent;
        if (tbatchFrameCounter < 4) {
            VKProf.info("[VKPROF] TBATCH pre-apply state layer={} aTest={} dMask={}",
                    layer, com.yuhan123.vulkanmod.vulkan.VRenderSystem.alphaTest,
                    com.yuhan123.vulkanmod.vulkan.VRenderSystem.depthMask);
        }
        // Vanilla's per-section path does preRenderChunk() = translate(pos -
        // viewEntity) on the ENTRY (camera-orientation-only) modelview, then
        // draws chunk-local vertices. Our single draw bakes WORLD coords
        // (v_local + pos) into the cached vertices, so the camera position must
        // be supplied by the MVP instead. Inject -viewEntity onto the modelview
        // stack before apply() so the recorded MVP = projection * viewRotation *
        // translate(-viewEntity) - exactly vanilla's per-section transform with
        // the per-chunk constant folded into the vertices. popMatrix() restores
        // the entry modelview for the rest of the frame (entities, etc.).
        final double vx = fViewEntityX.getDouble(this);
        final double vy = fViewEntityY.getDouble(this);
        final double vz = fViewEntityZ.getDouble(this);
        // Publish the WORLD-SPACE model translation this draw applies (see
        // VRenderSystem.chunkOffset). The batched vertices already carry WORLD
        // coordinates (each section's origin is baked in), so the only thing
        // left to fold in is -viewEntity. block.vsh then measures the fog on
        // (Position + ChunkOffset) = (worldPos - eye). Without it the shader
        // measured length(Position) from the world ORIGIN and the distance fog
        // silently disappeared (a 72-high eye against a 144 fog start).
        com.yuhan123.vulkanmod.vulkan.VRenderSystem.setChunkOffset((float) -vx, (float) -vy, (float) -vz);
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) -vx, (float) -vy, (float) -vz);
        if (tbatchFrameCounter < 4) {
            VKProf.info("[VKPROF] TBATCH viewEntity=({},{},{}) (injecting -viewEntity into MVP)", vx, vy, vz);
        }
        // apply() binds the block pipeline variant for the CURRENT layer state and
        // pushes the current-stack MVP (now with -viewEntity baked in).
        shader.apply();
        // CRITICAL: apply() only RECORDS the pipeline bind (pendingPipeline);
        // the variant is resolved inside flushPipelineBind() with the state in
        // effect AT THE FLUSH. My raw draws below never trigger the vanilla
        // draw paths that flush, so without this the terrain draws ran on
        // whatever stale variant the next entity draw happened to resolve -
        // wrong alpha test (holes in the ground) and no depth write (overdraw
        // explosion).
        Renderer.getInstance().flushPipelineBind();
        if (tbatchFrameCounter == 401) {
            VKProf.info("[VKPROF] TBATCH fogparams layer={} fogStart={} fogEnd={} viewEntity=({},{},{})",
                    layer, com.yuhan123.vulkanmod.vulkan.VRenderSystem.getShaderFogStart(),
                    com.yuhan123.vulkanmod.vulkan.VRenderSystem.getShaderFogEnd(), vx, vy, vz);
        }

        // Fog uses VRenderSystem.chunkOffset (the eye position, published by
        // ChunkRenderContainerMixin), which block.vsh subtracts from the
        // world-space Position before measuring the fog distance.

        int runs = 0;
        final VkCommandBuffer cb = Renderer.getCommandBuffer();
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VK10.vkCmdBindVertexBuffers(cb, 0, stack.longs(layerArea.getId()), stack.longs(0L));
            int maxEndVtx = 0;
            for (int i = 0; i < n; i++) {
                final long[] s = secList.get(i);
                final int end = (int) (s[1] / STRIDE) + (int) s[2];
                if (end > maxEndVtx) {
                    maxEndVtx = end;
                }
            }
            autoIdx.checkCapacity(maxEndVtx);
            final IndexBuffer ib = autoIdx.getIndexBuffer();
            Renderer.getDrawer().bindIndexBuffer(cb, ib, ib.indexType.value);

            int i = 0;
            final String nd = VulkanModConfig.get("TB_NODRAW");
            final boolean noDraw = "1".equals(nd);
            final boolean oneQuadOnly = "2".equals(nd);
            // DIAG: scan every visible section's stored positions for NaN or
            // absurd magnitudes (huge triangles => GPU clip explosion). Only
            // possible when the area buffer is CPU-readable (device-local
            // buffers cannot be read back on this path).
            if (tbatchFrameCounter < 4 && layerArea.type.mappable()) {
                int maxEnd = 0;
                for (int k = 0; k < n; k++) {
                    final long[] s = secList.get(k);
                    final int end = (int) (s[1] / STRIDE) + (int) s[2];
                    if (end > maxEnd) {
                        maxEnd = end;
                    }
                }
                final int scanBytes = Math.min(maxEnd * STRIDE, 4 * 1024 * 1024);
                if (verifyBB.capacity() < scanBytes) {
                    verifyBB = alloc(Math.max(scanBytes, verifyBB.capacity() * 2));
                }
                verifyBB.position(0).limit(scanBytes);
                MemoryTypes.HOST_MEM.copyFromBuffer(layerArea, scanBytes, verifyBB);
                int bad = 0;
                long firstBad = 0;
                int firstBadV = -1;
                float sample = 0.0f;
                for (int k = 0; k < n; k++) {
                    final long[] s = secList.get(k);
                    final int dstV = (int) (s[1] / STRIDE);
                    final int vc = (int) s[2];
                    if ((dstV + vc) * STRIDE > scanBytes) {
                        continue; // beyond the snapshot
                    }
                    for (int v = 0; v < vc; v++) {
                        for (int c = 0; c < 3; c++) {
                            final float f = verifyBB.getFloat((dstV + v) * STRIDE + c * 4);
                            if (Float.isNaN(f) || Float.isInfinite(f) || Math.abs(f) > 1.0e6f) {
                                bad++;
                                if (firstBadV < 0) {
                                    firstBad = s[0];
                                    firstBadV = v;
                                    sample = f;
                                }
                            }
                        }
                    }
                }
                VKProf.info("[VKPROF] TBATCH scan: sections={} scanBytes={} badVerts={} firstBadSrc={} firstBadV={} sample={}",
                        n, scanBytes, bad, firstBad, firstBadV, sample);
            }
            if (layer == BlockRenderLayer.TRANSLUCENT) {
                // Back-to-front per-section draws against the single shared area
                // buffer with ONE MVP (set above). No coalescing: each section is
                // its own draw so the sorted order (and thus alpha blending) is
                // preserved. Positions are baked world coordinates, so the only
                // per-draw difference is the vertexOffset into the area buffer.
                for (int k = 0; k < n && !noDraw; k++) {
                    final long[] s = secList.get(order[k]);
                    final int startVtx = (int) (s[1] / STRIDE);
                    final int vc = (int) s[2];
                    final int idxCount = oneQuadOnly ? 6 : (int) ((long) vc / 4L) * 6;
                    if (idxCount <= 0) continue;
                    com.yuhan123.vulkanmod.vulkan.Renderer.getInstance().writeGpuDrawTimestamp(cb);
                    org.lwjgl.vulkan.VK10.vkCmdDrawIndexed(cb, idxCount, 1, 0, startVtx, 0);
                    FrameProfiler.addCmdCount(FrameProfiler.CMD_DRAW_INDEXED);
                    runs++;
                }
            } else {
                final int indirect = indirectMode();
                if (indirect == 0) {
                    // --- default: coalesced adjacent runs (unchanged from before) ---
                    while (i < n && !noDraw) {
                        final long[] first = secList.get(order[i]);
                        int runStartVtx = (int) (first[1] / STRIDE);
                        int runEndVtx = runStartVtx + (int) first[2];
                        int j = i + 1;
                        while (j < n) {
                            final long[] next = secList.get(order[j]);
                            final int nextStart = (int) (next[1] / STRIDE);
                            if (nextStart != runEndVtx) {
                                break;
                            }
                            runEndVtx = nextStart + (int) next[2];
                            j++;
                        }
                        // quads [runStartVtx/4, runEndVtx/4) are contiguous: one draw.
                        // The pattern holds ABSOLUTE vertex ids (quad q -> q*4+r), so the
                        // base goes in vertexOffset and firstIndex MUST stay 0 - passing
                        // the quad base in BOTH doubled every position (exploded
                        // geometry + GPU clip explosion).
                        final int runQuads = (runEndVtx - runStartVtx) / 4;
                        final int idxCount = oneQuadOnly ? 6 : runQuads * 6;
                        com.yuhan123.vulkanmod.vulkan.Renderer.getInstance().writeGpuDrawTimestamp(cb);
                        org.lwjgl.vulkan.VK10.vkCmdDrawIndexed(cb, idxCount, 1,
                                0, runStartVtx, 0);
                        FrameProfiler.addCmdCount(FrameProfiler.CMD_DRAW_INDEXED);
                        runs++;
                        i = j;
                    }
                } else if (indirect == 2 && DeviceManager.MULTI_DRAW_AVAILABLE) {
                    // --- POC mode 2: grouped vkCmdDrawMultiIndexedEXT (~40 calls / 705 sub-draws) ---
                    runs += emitMultiDraw(stack, cb, order, n, noDraw);
                } else {
                    // --- POC mode 1: per-section vkCmdDrawIndexed (~705 draws, single area buffer) ---
                    // No per-section bind/push (buffer already bound once), so this
                    // isolates GPU per-draw EXECUTION overhead from CPU command encoding.
                    for (int k = 0; k < n && !noDraw; k++) {
                        final long[] s = secList.get(order[k]);
                        final int startVtx = (int) (s[1] / STRIDE);
                        final int vc = (int) s[2];
                        final int idxCount = oneQuadOnly ? 6 : (int) ((long) vc / 4L) * 6;
                        if (idxCount <= 0) continue;
                        com.yuhan123.vulkanmod.vulkan.Renderer.getInstance().writeGpuDrawTimestamp(cb);
                        org.lwjgl.vulkan.VK10.vkCmdDrawIndexed(cb, idxCount, 1, 0, startVtx, 0);
                        FrameProfiler.addCmdCount(FrameProfiler.CMD_DRAW_INDEXED);
                        runs++;
                    }
                }
            }
        }
        if (layer == BlockRenderLayer.TRANSLUCENT) {
            // Undo the translucent depth/blend state so later draws (entities,
            // GUI) are not affected - the cancelled vanilla epilogue would have.
            GlStateManager.depthMask(true);
            GlStateManager.disableBlend();
        }
        // restore the entry modelview (pop the -viewEntity we pushed before apply)
        GlStateManager.popMatrix();
        // Terrain is done: drop the fog translation so any later draw that shares
        // block.vsh (tile entities, block breaking, ...) is not fogged as if it
        // were sitting at the last section's origin.
        com.yuhan123.vulkanmod.vulkan.VRenderSystem.setChunkOffset(0.0f, 0.0f, 0.0f);
        if (tbatchFrameCounter < 3) {
            VKProf.info("[VKPROF] TBATCH layer={} sections={} drawRuns={}", layer, n, runs);
        }

        // vanilla epilogue state: the vanilla body clears the section list at the
        // end of every renderChunkLayer call. Skipping that made every later
        // layer call re-render all previous layers' sections with the wrong
        // layer's vertex buffers (ground vanishing, foliage stacked).
        chunks.clear();
        GlStateManager.resetColor();
        return true;
    }
}
