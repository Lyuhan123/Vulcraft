package com.yuhan123.vulkanmod.render.util;

import com.yuhan123.vulkanmod.VulkanMod;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;

/**
 * Cheap per-frame instrumentation.
 *
 * The point is to answer "is this CPU-bound or GPU-bound?" before spending
 * effort on the wrong side:
 *
 *  - fenceWait is the CPU sitting in vkWaitForFences / vkAcquireNextImageKHR,
 *    i.e. blocked because the GPU (or the presentation engine) is behind. A
 *    large share of the frame there means the CPU work is not the limit.
 *  - cpu is everything else: record time, uniform uploads, draw submission.
 *
 * Counters are plain longs incremented on the hot paths; the only per-frame
 * costs are a few nanoTime() calls and the periodic report.
 */
public final class FrameProfiler {

    /**
     * Compile-time master switch for the counters. Never written at runtime, so
     * it is {@code final} on purpose.
     *
     * <p>This used to be {@code public static volatile boolean}. Nothing ever
     * assigned it, but {@code volatile} meant the JIT could never treat it as a
     * constant: every one of the ~11 000 counter calls a frame re-read it, and
     * each counter method stayed a real call instead of folding to the single
     * increment behind it. As a {@code final true} every {@code if (ENABLED)}
     * guard disappears at compile time and the counter bodies inline to a plain
     * static field increment at the call site.
     *
     * <p>Turning the profiler off therefore means editing this line and
     * recompiling, which is what the field always documented anyway - there is no
     * runtime toggle anywhere in the mod.
     */
    public static final boolean ENABLED = true;

    /**
     * Per-draw timing (shader apply / draw record) is sampled with
     * System.nanoTime() on both ends of every draw. At the draw counts a
     * many-chunk scene produces (10k+) that is ~40k clock reads per frame, worth
     * around a millisecond - enough to distort the very measurement it reports.
     *
     * The counters (draws, binds, descriptor updates, bytes) stay on either way;
     * only these two timings are gated. Set VULKANMOD_DRAWTIMING=1 to re-enable
     * them when investigating where the per-draw CPU time goes.
     */
    public static final boolean DETAILED_TIMING = "1".equals(System.getenv("VULKANMOD_DRAWTIMING"));
    public static final boolean GPU_DRAW_TIMING = "1".equals(System.getenv("VULKANMOD_GPUDRAWTIMING"));

    /**
     * VULKANMOD_PROFILE_DUMP=1 forces Minecraft's own Profiler on for the whole
     * run and dumps its section tree at auto-quit, which attributes the parts of
     * the frame the mod's own report cannot see (terrain vs entities vs
     * updatechunks vs clouds). Read by ProfilerMixin, which is why the flag lives
     * here rather than in the mixin.
     */
    public static final boolean PROFILE_DUMP = "1".equals(System.getenv("VULKANMOD_PROFILE_DUMP"));

    private static final int REPORT_INTERVAL = 120;

    private static long frames;
    private static long totalNanos;
    private static long fenceWaitNanos;
    private static long submitNanos;

    /**
     * Time blocked in vkQueuePresentKHR.
     *
     * Kept out of the reported "cpu" figure. The frame is bracketed by
     * runGameLoop, and the present call happens inside endFrame(), so a
     * presentation-engine stall (vsync, driver frame pacing, no free swapchain
     * image) used to be indistinguishable from real CPU work - which makes a
     * present-bound frame look CPU-bound and sends optimisation in the wrong
     * direction.
     */
    private static long presentNanos;
    private static long presentStart;
    private static long shaderApplyNanos;
    private static long drawRecordNanos;

    /**
     * Chunk sections whose {@code ShaderInstance.apply()} was satisfied by the
     * state the previous section published (see ShaderInstance.canReuseTerrainState).
     * Terrain has ~1000 sections a frame and they share one shader state, so this
     * should approach the persistent-draw count; a value near zero means the fast
     * path is not firing and the guard is rejecting it.
     */
    private static long shaderApplyReuses;

    private static long draws;
    private static long pipelineBinds;
    private static long descriptorUpdates;
    private static long descriptorBinds;
    private static long descriptorBindSkips;
    private static long vertexBindSkips;

    /** Real vkCmdBindVertexBuffers calls and their cost (not gated: the point is the count). */
    private static long vertexBinds;
    private static long vertexBindNanos;
    private static long indexBindSkips;
    private static long vertexBytesCopied;
    private static long uniformBytes;

    /**
     * Cheap always-on counters that split the per-draw CPU cost by path.
     *
     * The frame being CPU-bound at a constant ~2-4us per draw says the cost
     * scales with draw count but not which part of the draw dominates. These
     * four answer that: mvpRecalcs counts full 4x4 MVP recomputes, pushConstants
     * counts per-draw push-constant uploads, and the two draw counters split
     * draws into the persistent-VBO path (chunks, display lists) and the
     * copy-into-per-frame-buffer path. Plain increments, no clock reads.
     */
    private static long mvpRecalcs;
    private static long pushConstantCalls;
    private static long pushConstantSkips;
    private static long persistentDraws;
    // DRAW-SOURCE split (rigorous draw attribution):
    //   pDraw = persistentDraws = terrain (chunk VBOs) + entity (display-list replays).
    //   onPersistentDraw routes each into terrainPersistentDraws vs entityPersistentDraws
    //   via the inEntityReplay flag set around DisplayListManager.replayDraw.
    private static long terrainPersistentDraws;
    private static long entityPersistentDraws;
    private static long copiedDraws;
    // cDraw = copiedDraws = immediate (per-frame buffer) draws, split by render phase:
    //   WORLD  = inside renderWorld (tile entities, particles, held item, debug, world border)
    //   OVERLAY= after renderWorld returns, inside updateCameraAndRender (pure GUI/HUD)
    private static final int PHASE_WORLD = 0;
    private static final int PHASE_OVERLAY = 1;
    private static int drawPhase = PHASE_WORLD;
    private static long immWorld;
    private static long immOverlay;
    // Immediate draws further classified by vertex format as a source hint:
    //   immLmap  = POSITION_TEX_LMAP / POSITION_TEX_COLOR_LMAP  -> tile-entity / lit world geometry
    //   immTex   = POSITION_TEX / POSITION_TEX_COLOR            -> GUI panels, particles, overlays
    //   immLines = POSITION / POSITION_COLOR                    -> debug lines, selection box, wireframe
    //   immBlock = BLOCK format                                -> chunk VBO drawn without a persistent buffer (~0)
    //   immOther = anything else
    private static long immLmap;
    private static long immTex;
    private static long immLines;
    private static long immBlock;
    private static long immOther;
    private static boolean inEntityReplay;

    /**
     * Entity / display-list attribution.
     *
     * The vanilla profiler says "entities" is the largest section of the frame,
     * but not what inside it costs: 1.12.2 draws entity models through GL display
     * lists, and every ModelBox quad is its own Tessellator.draw, so a single mob
     * becomes dozens of replayed draws. These counters separate the three
     * candidates - matrix traffic (MatrixState), display-list replay, and the
     * per-replayed-draw shader apply - so the next optimisation is aimed at the
     * part that actually dominates instead of at a plausible-looking guess.
     */
    private static long matrixOps;
    private static long matrixNanos;
    private static long displayListReplays;
    private static long displayListDraws;
    private static long displayListNanos;
    private static long displayListApplyNanos;

    /**
     * Split of EntityRenderer.updateCameraAndRender - the whole `gameRenderer`
     * profiler section - into its three pieces: the code before `renderWorld`
     * (ScaledResolution, mouse, Display.isActive), `renderWorld` itself (the
     * `level` section), and everything after it (the `gui` section plus the
     * screen render, which is outside any section).
     *
     * The vanilla profiler leaves ~17% of `gameRenderer` in its "unspecified"
     * bucket, i.e. in one of those three pieces but not in any named section.
     * These three timers say which one, instead of guessing.
     */
    private static long camBeforeWorldNanos;
    private static long camWorldNanos;
    private static long camAfterWorldNanos;
    private static long camAtStart;
    private static long camWorldStart;
    private static long camWorldEnd;

    /**
     * EntityRenderer.renderWorld calls updateLightmap() and getMouseOver()
     * BEFORE it opens its first profiler section, so neither shows up in the
     * profiler tree at all - they are invisible to every section-based
     * measurement. Timed directly instead, along with renderWorldPass, so the
     * three of them can be checked against renderWorld's own total.
     */
    private static long lightmapNanos;
    private static long lightmapStart;
    private static long mouseOverNanos;
    private static long mouseOverStart;
    private static long worldPassNanos;
    private static long worldPassStart;

    /** GPU timestamp delta across the main render pass, fed by Renderer's query pool. */
    private static long gpuPassNanos;

    /** Experiment B: GPU time inside the main swapchain color pass(es). */
    private static long gpuMainNanos;
    /** Experiment B: GPU time inside offscreen framebuffer passes. */
    private static long gpuOffNanos;
    /** Experiment B: total render-pass count (segments) accumulated over the window. */
    private static int gpuPassCount;
    /** Experiment B: offscreen render-pass count accumulated over the window. */
    private static int gpuOffCount;
    // Per-draw GPU timing (vkCmdWriteTimestamp interval method, gated by GPU_DRAW_TIMING).
    private static long gpuDrawTotalNanos;
    private static long gpuDrawCount;
    private static long gpuDrawMaxNanos;
    private static final long[] GPU_DRAW_BUCKETS = {100, 250, 500, 1000, 2000, 5000, 10000, 20000, 50000, Long.MAX_VALUE};
    private static long[] gpuDrawHist = new long[GPU_DRAW_BUCKETS.length];

    /** Client game-logic tick time (Minecraft.runTick), split out of the cpu total. */
    private static long gameTickNanos;

    /**
     * Split of the terrain apply-reuse fast path (ShaderInstance.apply with
     * aSkip high). `apply` on its own says how much the whole path costs but not
     * which half of it: the MVP flush is a real per-section recompute, while the
     * reuse guard is pure bookkeeping and should be nearly free. Without this
     * split the two are indistinguishable, and the guard - six conditions, two
     * of them object-identity compares - is the part that looks suspicious.
     */
    private static long mvpRecalcNanos;
    private static long reuseGuardNanos;

    /**
     * Matrix ops split by kind.
     *
     * `matOps` (4657/frame at rd 12) says the shim spends ~0.9 ms in the matrix
     * stack but not which operation it is. That distinction decides whether the
     * per-section cost can be collapsed at all: a push/translate/pop idiom around
     * each chunk draw is replaceable by a single offset, whereas a general
     * multMatrix per section is not. Plain increments.
     */
    private static long matPush;
    private static long matPop;
    private static long matTranslate;
    private static long matMultMatrix;
    private static long matRotate;
    private static long matScale;
    private static long matLoadIdentity;

    /**
     * Deferred per-chunk transform (VULKANMOD_CHUNKXF).
     *
     * `matOps` shows ~4 matrix ops per chunk section (push / translate /
     * multMatrix / pop around each section's single draw). `xfFused` counts the
     * sections whose MVP was produced by the fused path instead of by mutating
     * the matrix stack, so the A/B can prove the fast path actually fired:
     * xfFused ~0 in the "on" case means the guard rejected everything and the
     * two runs are identical.
     *
     * `xfMiss` counts A = P*parent cache rebuilds - it should be about one per
     * chunk layer (the layer base modelview and projection are constant for a
     * whole layer), not one per section.
     */
    private static long chunkXfFused;
    private static long chunkXfCacheMiss;
    private static long chunkXfStackRead;
    private static long chunkXfEpochStale;
    private static long chunkXfMismatch;
    private static double chunkXfMaxDiff;

    /**
     * Texture upload path. Vanilla's profiler puts `textures` at 5.5-8.5% of the
     * frame but cannot say what is inside it, so these count the pieces the shim
     * owns: how many `glTexSubImage2D` calls arrive (the animated atlas walks
     * every mip level of every animated sprite), how many bytes they carry, and
     * how the time splits between the CPU-side BGRA->RGBA swizzle and the staging
     * copy + `vkCmdCopyBufferToImage` recording.
     *
     * Kept out of `DETAILED_TIMING`: the upload count is tens per tick, not tens
     * of thousands per frame, so the clock reads cost nothing measurable.
     */
    private static long texUploads;
    private static long texUploadBytes;
    private static long texUploadNanos;
    private static long texSwizzleNanos;
    private static long texSwizzleBytes;

    /** Stage split inside {@code uploadSubTextureAsync}: staging copy, barrier, image copy. */
    public static final int TEX_STG = 0;
    public static final int TEX_BARRIER = 1;
    public static final int TEX_COPY = 2;
    /**
     * Sub-split of TEX_COPY (pass 11). The copy stage read ~6.9 us per call for a
     * 315-byte upload, which is far too much for the copy itself - so it is split
     * into the two halves that could explain it: building the {@code VkBufferImageCopy}
     * + {@code VkExtent3D} structs on the MemoryStack, and the driver call. Only one
     * of those is fixable, and they need opposite fixes, so guessing is not an option.
     */
    public static final int TEX_CP_SETUP = 3;
    public static final int TEX_CP_CMD = 4;
    private static final long[] texStageNanos = new long[5];
    private static long texUploadMaxNanos;

    /**
     * Why the terrain apply-reuse guard rejected, counted only for the terrain
     * pipeline with the arena active - i.e. only for the sections that could
     * have reused. With `aSkip` around 880 of ~1040 sections, ~160 still take
     * the full path every frame and the reason has never been identified.
     * Splitting the rejection by condition says which one fires; a counter that
     * dominates means the guard is correct but its input churns, which is a
     * different (and fixable) problem from the guard being too strict.
     */
    public static final int REJ_SERIAL = 0;
    public static final int REJ_EPOCH = 1;
    public static final int REJ_BIND = 2;
    public static final int REJ_UNIFORM = 3;
    public static final int REJ_BOUNDPIPE = 4;
    public static final int REJ_STATE = 5;
    private static final long[] reuseRejects = new long[6];

    /**
     * Reuses that happened on a shader other than the terrain pipeline, i.e. the
     * ones the generalised gate (VULKANMOD_REUSEALL) unlocked. Must be ~0 with
     * the flag off, and should track the entity/display-list applies with it on -
     * a frame-time win with this at 0 would mean the gate did not widen.
     */
    private static long reuseNonBlock;

    /**
     * Per-section terrain breakdown.
     *
     * <p>`apply` is the largest single shim-owned row in the frame report, but the
     * report only splits it into `mvp` and `guard`; the remainder (~0.5 ms/frame at
     * rd 12, ~600 ns per call) was never attributed to anything. These counters
     * close that gap from both ends:
     *
     * <ul>
     *   <li>`aPush` - the `Renderer.pushConstants()` call that the generalised
     *       reuse gate hoisted above the test (a null check for block_arena, a
     *       real push for every other shader);</li>
     *   <li>`aBook` - the bookkeeping the reuse path performs before returning
     *       (the profiler increments plus the `getBlockPipeline()` compare);</li>
     *   <li>`misc` - computed at report time as `apply - mvp - guard - push - book`,
     *       i.e. everything left: the two `System.nanoTime()` reads, the method
     *       prologue, and the two static dispatches. If this dominates, the cost
     *       is call overhead rather than work, and the fix is to move the test to
     *       the draw site instead of shaving the body.</li>
     * </ul>
     *
     * <p>`qInd` and `pre` do the same for the other two per-section segments: the
     * `queueIndirect()` call that records the section, and the `glDrawArrays`
     * preamble (pointer-state memo + shader lookup) that precedes `apply()`.
     * `psHit`/`psDerive` count the `PipelineState` cache - a derivation is five
     * state encodes plus seven field compares, so the hit/derive ratio says
     * whether memoising the whole lookup is worth it.
     */
    private static long aPushNanos;
    private static long aBookNanos;
    private static long queueIndirectNanos;
    private static long queueIndirectCalls;
    private static long drawPreambleNanos;
    private static long drawPreambleCalls;
    private static long psHits;
    private static long psDerives;
    private static long psMemoHits;
    private static long psMemoMismatches;

    /**
     * Whole-call split of {@code apply()}: the reuse path (guard passes) against
     * the full path (pipeline bind, sampler resolution, uniform staging,
     * descriptor bind). `applysplit` shows the fast path costing ~1.26 us/call
     * with only ~490 ns of it attributable to mvp+guard+push+book, so the
     * remainder is either method-entry overhead or the handful of full applies -
     * and those two have opposite fixes. Splitting by call answers it.
     */
    private static long applyFastNanos;
    private static long applyFastCalls;
    private static long applyFullNanos;
    private static long applyFullCalls;

    /**
     * Segment split of {@code applyFull()} (pass 11).
     *
     * <p>`applypath` shows the full path at ~11 us/call against ~0.9 us for the
     * reuse path, and there are only ~24 full calls a frame - so this is ~0.27 ms
     * of the frame in two dozen calls, the largest clearly-attributable non-terrain
     * shim cost. It has been on the ranked list for three passes without ever
     * being attributed to a segment: the four candidates (sampler rebind loop,
     * program switch, {@code bindPipeline()} -> descriptor set + push constants,
     * and the reuse-record publish) have different fixes, so it has to be split
     * before anything is rewritten.
     */
    public static final int FULL_SAMPLERS = 0;
    public static final int FULL_USE_PROGRAM = 1;
    public static final int FULL_BIND_PIPELINE = 2;
    public static final int FULL_PUBLISH = 3;
    /**
     * Sub-split of FULL_BIND_PIPELINE (pass 11). `bindMs` read 9.3 us per call,
     * which is the whole of the full path's cost and far more than any of the
     * three pieces look like they should cost. The three have different fixes
     * (nothing / pre-flatten the descriptor list / reduce descriptor-set churn),
     * so the segment has to be attributed before it is touched.
     */
    public static final int FULL_BIND_GFX = 4;
    public static final int FULL_BIND_TEX = 5;
    public static final int FULL_BIND_UBO = 6;
    /**
     * Sub-split of FULL_BIND_UBO (pass 11). `bindUboMs` read ~7.9 us per call and
     * owns 85% of the full path's total. It is two calls - `pushConstants` and
     * `bindDescriptorSets` - and the descriptor half is itself three: the
     * needsUpdate/updateUniforms decision, the descriptor-set rewrite, and the
     * vkCmdBindDescriptorSets. Only the rewrite is Java-side and therefore
     * fixable; if the driver entry points dominate, this item is closed rather
     * than optimised.
     */
    public static final int FULL_PUSH = 7;
    public static final int FULL_DESC = 8;
    public static final int FULL_DESC_UPD = 9;
    public static final int FULL_DESC_BIND = 10;
    public static final String[] FULL_SEGMENTS =
            {"samplers", "useProgram", "bindPipeline", "publish", "bindGfx", "bindTex", "bindUbo",
             "push", "desc", "descUpd", "descBind"};
    private static final long[] fullSegmentNanos = new long[FULL_SEGMENTS.length];

    /**
     * Whole {@code VRenderSystem.flushMVP()} call as seen from {@code apply()}
     * (pass 11).
     *
     * <p>`mvpMs` only covers the body of {@code calculateMVP}; it is opened
     * <em>inside</em> that method, so it excludes the two call boundaries
     * (`apply` -> `flushMVP` -> `calculateMVP`) and the interface dispatch into
     * {@code SectionMvpProvider.tryWriteSectionMVP}. Subtracting `mvpMs` from this
     * gives exactly that boundary cost, which is the part `applysplit`'s `miscMs`
     * has been hiding - and which is only fixable by changing the call structure.
     */
    private static long flushMvpNanos;

    /**
     * Paired within-frame A/B (pass 11) - see {@link PairedAB}.
     *
     * <p>The pass-10 `MEMOAB` instrument proved the technique: a two-launch A/B
     * cannot resolve a sub-100 ns/call change on this bench, but alternating the
     * arm per hot call puts both arms in one frame against one scene. This
     * generalises it to any boolean-gated hot path, accumulating the timed window
     * of {@code apply()} (and the matrix ops, for the transform flag) under
     * whichever arm was in effect for that call.
     */
    private static long abOnNanos;
    private static long abOnCalls;
    private static long abOffNanos;
    private static long abOffCalls;
    private static long abMatOnNanos;
    private static long abMatOffNanos;
    private static long abMatOnCalls;
    private static long abMatOffCalls;

    /** One paired sample for the whole {@code apply()} window. */
    public static void addAbApply(long start, boolean onArm) {
        if (!ENABLED || start == 0L)
            return;
        final long dt = System.nanoTime() - start;
        if (onArm) {
            abOnNanos += dt;
            abOnCalls++;
        } else {
            abOffNanos += dt;
            abOffCalls++;
        }
    }

    /** One paired sample for a single matrix-stack operation. */
    public static void addAbMatrixOp(long start, boolean onArm) {
        if (!ENABLED || start == 0L)
            return;
        final long dt = System.nanoTime() - start;
        if (onArm) {
            abMatOnNanos += dt;
            abMatOnCalls++;
        } else {
            abMatOffNanos += dt;
            abMatOffCalls++;
        }
    }

    /**
     * Paired within-frame A/B for the {@code PipelineState} memo
     * ({@code VULKANMOD_MEMOAB=1}).
     *
     * <p>A two-launch A/B could not resolve this change: the memo measured
     * −69 ns/call in one order and +23 ns/call in the other, and in both orders
     * the second run was the faster one - the run-order effect is bigger than the
     * treatment. Alternating the arm per {@code apply()} call puts both arms in
     * the same frame against the same scene, so the difference is paired and the
     * scene cancels.
     *
     * <p>{@code armA} is the memo arm, {@code armB} the full-encode arm.
     */
    public static final boolean MEMO_AB = "1".equals(System.getenv("VULKANMOD_MEMOAB"));

    private static long abMemoNanos;
    private static long abMemoCalls;
    private static long abPlainNanos;
    private static long abPlainCalls;

    public static void addApplyFast(long start, boolean memoArm) {
        if (!ENABLED)
            return;
        applyFastCalls++;
        if (start == 0L)
            return;

        final long dt = System.nanoTime() - start;
        applyFastNanos += dt;

        if (MEMO_AB) {
            if (memoArm) {
                abMemoNanos += dt;
                abMemoCalls++;
            } else {
                abPlainNanos += dt;
                abPlainCalls++;
            }
        }
    }

    public static void addApplyFull(long start) {
        if (!ENABLED)
            return;
        applyFullCalls++;
        if (start != 0L)
            applyFullNanos += System.nanoTime() - start;
    }

    public static void addApplyPush(long start) {
        if (ENABLED && start != 0L)
            aPushNanos += System.nanoTime() - start;
    }

    public static void addApplyBook(long start) {
        if (ENABLED && start != 0L)
            aBookNanos += System.nanoTime() - start;
    }

    /** Closes the timer opened around {@code VRenderSystem.flushMVP()} in apply(). */
    public static void addFlushMvp(long start) {
        if (ENABLED && start != 0L)
            flushMvpNanos += System.nanoTime() - start;
    }

    /** One segment of {@code applyFull()}; see FULL_SAMPLERS .. FULL_PUBLISH. */
    public static void addFullSegment(long start, int segment) {
        if (ENABLED && start != 0L && segment >= 0 && segment < fullSegmentNanos.length)
            fullSegmentNanos[segment] += System.nanoTime() - start;
    }

    public static void addQueueIndirect(long start) {
        if (!ENABLED)
            return;
        queueIndirectCalls++;
        if (start != 0L)
            queueIndirectNanos += System.nanoTime() - start;
    }

    public static void addDrawPreamble(long start) {
        if (!ENABLED)
            return;
        drawPreambleCalls++;
        if (start != 0L)
            drawPreambleNanos += System.nanoTime() - start;
    }

    public static void onPipelineStateHit() {
        if (ENABLED)
            psHits++;
    }

    public static void onPipelineStateDerive() {
        if (ENABLED)
            psDerives++;
    }

    /**
     * Memo hits: {@code getCurrentPipelineState} returned without spending the
     * five state encodes because every raw input still held its previous value.
     * Must track `psHit + psDerive` closely or the memo is not firing.
     */
    public static void onPipelineStateMemoHit() {
        if (ENABLED)
            psMemoHits++;
    }

    /**
     * {@code VULKANMOD_PIPESTATE_VERIFY=1} only: the memo said "unchanged" but a
     * full re-derive disagreed. **Any non-zero value here is a real bug** - the
     * snapshot is missing an input - and the memo must not ship until it is 0.
     */
    public static void onPipelineStateMemoMismatch() {
        if (ENABLED)
            psMemoMismatches++;
    }

    private static long frameStart;
    private static long markStart;
    private static long tickStart;

    /**
     * {@code RenderGlobal.setupTerrain} and its {@code getVisibleFacings} helper
     * (pass 12).
     *
     * <p>The steady-state vanilla dump ranks {@code terrain_setup} second only to
     * {@code terrain} itself (12.2% of the frame), and 11.0 points of that is the
     * {@code update} section - the visible-chunk selection. That is surprising
     * for a stationary camera, where {@code displayListEntitiesDirty} is supposed
     * to be false and the whole block skipped, so the first thing to establish is
     * <b>how often it actually runs and what it costs</b>, measured directly
     * rather than inferred from a profiler tree the project already knows is
     * unbalanced.
     *
     * <p>{@code update}'s own children split it in two: {@code iteration} is the
     * BFS over visible chunks, and the {@code unspecified} remainder is dominated
     * by {@code getVisibleFacings} - a scan of all 4096 blocks of the eye's chunk
     * section plus a flood fill. These four counters separate the two so a fix
     * can be aimed at the half that actually costs.
     *
     * <p>Pass 12's measurement then retired the cache idea it was built for.
     * {@code vfCalls} tracks {@code bfs} exactly (0.15/frame), which proves the
     * fast path is always taken and {@code getVisibleFacings} is already throttled
     * by vanilla's own dirty flag; and {@code vfMs} of 0.20 ms/frame against 0.15
     * calls/frame puts the cost at <b>1.33 ms per call</b> - a periodic spike
     * rather than a steady drain. A memo could therefore save at most 2.5% and
     * would risk stale culling, so the effort went to
     * {@code VisGraphMixin}, which removes the allocations inside the flood fill
     * instead and cannot return a stale answer.
     *
     * <p>{@code vfHit} is now "answered by the allocation-free flood fill rather
     * than vanilla's", and {@code vfBad} is that rewrite's verify mode - a
     * non-zero value means it disagreed with the real vanilla traversal and must
     * not ship.
     */
    private static long setupTerrainNanos;
    private static long setupTerrainCalls;
    private static long visibleFacingsNanos;
    private static long visibleFacingsCalls;
    private static long visibleFacingsHits;
    private static long visibleFacingsBad;

    /**
     * How often the expensive half of {@code setupTerrain} actually runs, and how
     * big the list it produces is.
     *
     * <p>This exists because the timing rows alone could not answer the question.
     * {@code setupTerrain} runs once per frame, but the block that does the work
     * sits behind {@code if (!flag && displayListEntitiesDirty)}, and there are
     * two mutually exclusive shapes inside it: the fast path, taken when
     * {@code viewFrustum.getRenderChunk(eye) != null}, which calls
     * {@code getVisibleFacings} once; and the fallback, taken when it is null,
     * which probes every chunk in a {@code (2*renderDistance+1)^2} square - 625
     * {@code getRenderChunk} + frustum tests at rd 12 - and never calls
     * {@code getVisibleFacings} at all.
     *
     * <p>So {@code vfCalls} near zero has two completely different explanations -
     * "the block never ran" and "the block ran on the fallback path" - and the
     * cost of the two is not comparable. {@code bfsRuns} counts entries into the
     * block (via the {@code iteration} profiler section it always opens) and
     * {@code ri} is the size of the render-info list it builds, which is what the
     * {@code iteration} BFS and the later {@code filterempty} pass both scale
     * with.
     */
    private static long setupTerrainBfsRuns;
    private static long renderInfosLast;
    private static long renderInfosMax;

    /** Calls answered from the {@link VisibleFacingsCache} memo (pass 12). */
    private static long visibleFacingsMemoHits;

    /** Verify mode only: the memo disagreed with a fresh computation. Must be 0. */
    private static long visibleFacingsMemoBad;

    private static long setupTerrainStart;
    private static long visibleFacingsStart;

    /**
     * The visible-chunk breadth-first pass itself (pass 13).
     *
     * <p>{@code setupTerrain} has been timed as a whole since pass 12, and
     * {@code getVisibleFacings} inside it since pass 12b, but the BFS - the
     * {@code iteration} section, which the vanilla tree ranks at ~15% of the
     * frame - has only ever been measured through the vanilla profiler, whose
     * absolute values this project already knows are ~1.4&times; too large. The
     * arithmetic is suspicious too: at rd 12 the loop polls ~890 chunks and
     * probes each of their six neighbours, so ~5350 probes at ~150 ns each is
     * the whole of {@code terrain_setup}'s non-{@code getVisibleFacings} cost.
     * Nothing in the loop body looks like 150 ns, so it needs a direct number
     * before anything is rewritten on the strength of a guess.
     *
     * <p>{@code bfsNanos} is one clock pair per frame (the {@code iteration}
     * section opens exactly once per entry and its {@code endSection} is the
     * next one in the method), so the measurement tax is nil.
     * {@code bfsProbes} is the number of {@code getRenderChunkOffset} calls, i.e.
     * six per poll - a <em>count</em>, which is what settled the
     * {@code vfCalls} vs {@code bfs} question in pass 12 and cannot be
     * mis-attributed by a flip.
     */
    private static long bfsNanos;
    private static long bfsProbes;
    private static long bfsFastCalls;
    private static long bfsBad;
    private static long bfsStart;

    /** Paired A/B of the neighbour probe ({@code VULKANMOD_AB=BFS}). */
    private static long abBfsOnNanos;
    private static long abBfsOnCalls;
    private static long abBfsOffNanos;
    private static long abBfsOffCalls;

    /**
     * The BFS <b>loop body</b>, split by term (pass 14).
     *
     * <p>Pass 13 established two things and neither of them answers the question
     * this block exists for. It established that the {@code iteration} BFS is
     * ~93% of {@code setupTerrain} (1.03 ms of 1.11 ms, ~12% of the frame), and
     * it established that the <em>arithmetic</em> of the neighbour probe is
     * worth exactly zero ({@code dBfsNs = +0}) because {@code intFloorDiv} is
     * {@code inline (hot)} and the literal divisor strength-reduces. What it did
     * <b>not</b> establish is where the remaining 192 ns per probe goes, and its
     * own javadoc says so: "Nothing in the loop body looks like 150 ns, so it
     * needs a direct number before anything is rewritten on the strength of a
     * guess." The guess was then made anyway, and it was worth nothing.
     *
     * <p>So these counters exist to split the 192 ns. Per polled chunk the loop
     * probes six neighbours and, for each, evaluates a five-term condition whose
     * every term is a method call out of a 1556-byte {@code setupTerrain} (the
     * JIT will not inline into a caller that size, so each is a real call):
     *
     * <pre>
     *   getRenderChunkOffset(...)              -> redirect -> fastProbe   (2 calls)
     *   info.hasDirection(f.getOpposite())     (2 calls, one of them getOpposite)
     *   chunk.getCompiledChunk().isVisible(..) (3 calls: getCompiledChunk,
     *                                           getOpposite, isVisible)
     *   chunk2.setFrameIndex(frameCount)       (1 call)
     *   camera.isBoundingBoxInFrustum(bbox)    (1 interface dispatch + 1 virtual
     *                                           call + up to 8 dot() per plane)
     * </pre>
     *
     * <p>At rd 12 that is ~5350 neighbours a frame, so ~40 000 calls, and the
     * question is whether the 1.03 ms is those calls or the frustum arithmetic
     * they end in. The two have opposite fixes - a call count can only be reduced
     * by flattening the loop, whereas the arithmetic can be reduced in place - so
     * the split has to be measured before either is built.
     *
     * <p><b>Timing here is sampled, not complete.</b> A {@code nanoTime} pair
     * costs ~226 ns on this machine, which is larger than the per-call costs
     * being separated; timing every call would measure the clock and, worse,
     * would add ~40 000 clock pairs a frame to the very window under test. One
     * call in 32 is timed instead and the mean is reported, which is exact in
     * expectation and costs 1/32 of the tax. The <em>counts</em> are complete and
     * are not sampled - a count cannot be mis-attributed, which is what settled
     * the {@code vfCalls} vs {@code bfs} question in pass 12.
     *
     * <p><b>How much of this line is populated in a shipped build.</b>
     * {@code polls} and {@code frN} are always on (both come from a redirect
     * that does real work). {@code pNs}, {@code frNs} and {@code frBad} need
     * {@code VULKANMOD_BFS_BODY=1}. {@code sfN}, {@code sfTrue}, {@code viN} and
     * {@code oppN} came from three diagnostic redirects - on
     * {@code setFrameIndex}, {@code CompiledChunk.isVisible} and
     * {@code getOpposite} - which were <b>removed</b> after pass 14 because a
     * redirect is not free: it replaces an inlinable call with a call the JIT
     * will not inline into a 1556-byte {@code setupTerrain}, and those three
     * sites together are ~13 200 calls a frame. Their pass-14 readings are
     * recorded in {@code REFERENCE.md}; re-adding them is a ten-line change and
     * the source is in {@code tools/ab-results/optimization-report-pass14.html}.
     * A zero in those four columns therefore means "not wired", not "never
     * called".
     */
    private static long bfsPolls;
    private static long bfsSetFrameCalls;
    private static long bfsSetFrameTrue;
    private static long bfsFrustumCalls;
    private static long bfsVisibleCalls;
    private static long bfsOppositeCalls;

    private static long bfsProbeNanos;
    private static long bfsProbeSamples;
    private static long bfsSetFrameNanos;
    private static long bfsSetFrameSamples;
    private static long bfsFrustumNanos;
    private static long bfsFrustumSamples;
    private static long bfsVisibleNanos;
    private static long bfsVisibleSamples;

    /** Verify mode only: the positive-vertex frustum test disagreed. Must be 0. */
    private static long bfsFrustumBad;

    /** Stride counter for the sampled timings above. */
    private static int bfsSampleTick;

    /** Paired A/B of the frustum test ({@code VULKANMOD_AB=FRUSTUM}). */
    private static long abFrOnNanos;
    private static long abFrOnCalls;
    private static long abFrOffNanos;
    private static long abFrOffCalls;

    /**
     * Paired A/B of the BFS term-1 hoist ({@code VULKANMOD_AB=BFSMASK}). The
     * block unit is a <b>poll</b> (892 a frame at rd 12), not a probe, so
     * {@code dMkNs} is ns per poll and the frame effect is that times the poll
     * rate. Kept separate from the {@code bfs*} pair on purpose: the two numbers
     * have different units and averaging one into the other would produce a
     * plausible wrong answer.
     */
    private static long abMkOnNanos;
    private static long abMkOnCalls;
    private static long abMkOffNanos;
    private static long abMkOffCalls;

    /** Polls whose keep mask was not "all six facings". */
    private static long bfsMaskPolls;

    /** Facings removed from the loop by the keep mask, summed over polls. */
    private static long bfsMaskSkipped;

    /** {@code BFS_MASK_VERIFY} disagreements between the table and {@code hasDirection}. */
    private static long bfsMaskBad;

    /**
     * Flag self-reports for the pass-25 {@code bfsrow} row.
     *
     * <p>Read here rather than from the mixin so the row reports what the client
     * process actually received: a flag that fails to reach the JVM produces a
     * perfectly plausible run (pass 18 lost a whole correctness gate to exactly
     * that), and the only defence is a field on the row that says so.
     */
    private static final int BFS_ROW_ON =
            "0".equals(System.getenv("VULKANMOD_BFS_ROW")) ? 0 : 1;

    private static final int BFS_ROW_VERIFY_ON =
            "1".equals(System.getenv("VULKANMOD_BFS_ROW_VERIFY")) ? 1 : 0;

    // ------------------------------------------------------------------
    // Pass 16: the two sections the shim has never directly instrumented
    // ------------------------------------------------------------------

    /**
     * Direct wall-clock timing of {@code RenderGlobal.renderEntities}, the
     * section the vanilla tree puts at ~23% of the frame but that no direct
     * instrument in this project has ever measured. It is bracketed by an
     * {@code @Inject} at HEAD and RETURN on the public
     * {@code renderEntities(Entity, ICamera, float)} overload - two clock reads
     * a frame, so the apparatus is free.
     *
     * <p>Why it matters: the vanilla profiler tree is known to be ~1.5&times;
     * inflated on {@code terrain_setup} and to be built on an <b>unbalanced
     * section stack</b>, so a 23% reading there is a hint, not a budget. This is
     * the number that decides whether the entity path is worth any work at all.
     */
    private static long renderEntitiesStart;
    private static long renderEntitiesNanos;
    private static long renderEntitiesCalls;

    /** {@code countEntitiesTotal} / {@code countEntitiesRendered} at RETURN. */
    private static long entitiesTotal;
    private static long entitiesRendered;

    /**
     * Pass-0 {@code renderEntities} calls in this window - the denominator for the
     * two counts above. Pass 1 returns the same {@code countEntitiesTotal} (it is
     * not reset there) but a different {@code countEntitiesRendered}, so mixing
     * the two calls into one mean would double-count the rendered entities.
     */
    private static long entPass0Calls;

    /**
     * The {@code filterempty} window of {@code renderBlockLayer}.
     *
     * <p>Bracketed at the two profiler calls that delimit it in vanilla:
     * {@code startSection("filterempty")} opens it and {@code func_194339_b}
     * <b>closes it and opens "render_&lt;layer&gt;"</b> - so the window is exactly
     * the 892-per-layer scan and excludes the draws. That is the same boundary
     * the vanilla tree uses, which is what makes the two comparable.
     */
    private static long filterEmptyStart;
    private static long filterEmptyNanos;
    private static long filterEmptyCalls;
    private static long filterEmptyScanned;
    private static long filterEmptyAdded;

    /** {@code BufferBuilder.endVertex} calls, i.e. vertices submitted per frame. */
    private static long vertices;

    private FrameProfiler() {
    }

    public static void beginRenderEntities(int pass) {
        if (ENABLED) {
            renderEntitiesStart = System.nanoTime();
            // Drop the previous call's trailing interval rather than let it be
            // closed by this call's first iteration (pass 13's inter-frame bug).
            entIterStart = 0L;
        }
        // Outside the ENABLED guard: the record has to be maintained whenever
        // ENT_PASS1 is on, and the two flags are independent.
        entPass1Begin(pass);
    }

    /**
     * The entity loop and the block-entity loop are two very different costs
     * inside one 1.8 ms section, and only one of them scales with the scene. The
     * phases are opened by the two {@code endStartSection} calls that delimit them
     * in vanilla ({@code "entities"} and {@code "blockentities"}), so the split is
     * vanilla's own boundary, not a guess.
     */
    private static long entPhaseStart;
    private static int entPhase;
    private static long entityLoopNanos;
    private static long blockEntityLoopNanos;

    public static void enterEntityPhase(int phase) {
        // Pass 20: the `blockentities` boundary is where the tile-entity loop
        // starts, so it is where its record cursor resets. `flBLoops` must read
        // 2.00 a frame - it is the attach check for a cursor whose desync would
        // silently answer a section with another section's tile bit.
        if (phase == 2 && FLMASK) {
            flBIdx = 0;
            flBLoops++;
        }
        // Pass 21: the tile loop's own cursor. Reset by the same boundary the
        // record's pass-0 walk is opened by, so the cursor can never carry an index
        // across a call - and `tileBuilt` must read ri, which is what says it did.
        if (phase == 2 && TILE_TRACK) {
            tileCursor = 0;
        }
        if (!ENABLED)
            return;
        final long now = System.nanoTime();
        if (entPhase == 1) {
            entityLoopNanos += now - entPhaseStart;
        } else if (entPhase == 2) {
            blockEntityLoopNanos += now - entPhaseStart;
        }
        entPhase = phase;
        entPhaseStart = now;
    }

    public static void endRenderEntities(int total, int rendered) {
        // Pass 20: close the tile-loop A/B block at the end of the method that owns
        // the loop (pass 13's rule). The next hook after the loop is this RETURN;
        // what sits between them is the `setTileEntities` iteration and the
        // damaged-block pair, ~microseconds and empty on this bench. Pass 0's tile
        // loop has now recorded every entry, so pass 1 may read the record.
        if (FLMASK) {
            ftAbFlush();
            if (entP1Pass == 0)
                flTilesValid = true;
        }
        // Pass 21: close the tile-loop A/B block at the RETURN of the method that
        // owns the loop (pass 13's rule), and publish or retire the record.
        //
        // `tileRecorded` is keyed off the cursor rather than off the count: the
        // cursor is reset where the `blockentities` section opens, so a pass-0 call
        // that returned at the startup counter leaves it 0 and pass 1 correctly
        // declines to consume a record that was never built.
        if (TILE_TRACK) {
            tileAbClose();
            if (entP1Pass == 0) {
                tileCount = tileCount0;
                tileCountSum += tileCount0;
                tileRecorded = tileCursor > 0;
            } else {
                // The record is SINGLE USE, for the same reason ENTPASS2's is: a
                // second pass-1 entry would otherwise consume a stale record and
                // silently drop a tile entity.
                tileConsuming = false;
            }
        }
        if (!ENABLED)
            return;
        final long now = System.nanoTime();
        renderEntitiesNanos += now - renderEntitiesStart;
        if (entPhase == 2) {
            blockEntityLoopNanos += now - entPhaseStart;
        }
        entPhase = 0;
        renderEntitiesCalls++;
        // Pass 0 only. `countEntitiesTotal` is reset in pass 0 and
        // `countEntitiesRendered` is incremented in BOTH passes, so only pass 0's
        // pair is a per-frame figure - accumulating both calls would double-count
        // the rendered entities.
        if (entP1Pass == 0) {
            // Accumulated, not assigned. This was assigned for four passes while
            // the comment above the report line claimed it was accumulated, so the
            // row divided the LAST call's count by the window's call count and
            // printed a stable, plausible-looking `total=0.3`. Fourth occurrence of
            // this shape in the project; the tell is a row stable at a value
            // nothing else corroborates.
            entitiesTotal += total;
            entitiesRendered += rendered;
            entPass0Calls++;
        }
    }

    public static void beginFilterEmpty(int scanned) {
        if (!ENABLED)
            return;
        filterEmptyStart = System.nanoTime();
        filterEmptyScanned += scanned;
    }

    public static void endFilterEmpty() {
        if (!ENABLED)
            return;
        filterEmptyNanos += System.nanoTime() - filterEmptyStart;
        filterEmptyCalls++;
    }

    public static void onFilterEmptyAdded(int added) {
        if (ENABLED)
            filterEmptyAdded += added;
    }

    // ------------------------------------------------------------------
    // Pass 20: one record per frame, three walks that used to rediscover it.
    //
    // Two independent loops walk the SAME `renderInfos` list every frame and each
    // entry pays two or three dependent cold loads to read state that is a
    // property of the chunk's compiled data:
    //
    //   filterempty    4 x renderInfos   (`renderBlockLayer`, once per layer)
    //   blockentities  2 x renderInfos   (both `renderEntities` calls)
    //
    // Measured in the shipped configuration at rd 12: `fempty ms` 0.30-0.34
    // ms/frame over 4 x 824 entries (92-105 ns an entry) and `blocke` 0.25-0.30
    // ms over 2 x 824 (150-180 ns an entry). The instruction work in both bodies
    // is three to eight nanoseconds - a `RenderChunk` field read, a
    // `CompiledChunk` field read and a boolean test. The whole cost is the load
    // chain `renderInfo -> RenderChunk -> CompiledChunk -> layersUsed[]` /
    // `tileEntities` over 824 objects scattered across the heap, i.e. two cache
    // misses an entry that nothing else in the frame removes.
    //
    // The fix is one record per frame, built during the frame's FIRST filterempty
    // scan - which reads the real CompiledChunk for every entry anyway, so the
    // build adds three virtual calls and no cache miss - and read by the other
    // three scans and both block-entity loops out of two contiguous arrays.
    //
    //   `flMask[i]`   4 bits: layer L present. Bit = BlockRenderLayer.ordinal()
    //   `flTiles[i]`  1 bit:  the CompiledChunk has at least one tile entity
    //
    // Correctness rests on the same invariant ENTPASS1/ENTPASS2 rest on: within
    // one `renderWorldPass` the answer cannot change. `renderInfos` is rebuilt
    // only by `setupTerrain`, which runs once, before everything here; the
    // `CompiledChunk`s are installed only by `updateChunks`, which runs once, at
    // EntityRenderer line 1381 - after `setupTerrain` and before the first
    // `renderBlockLayer`. The record is dropped at the head of every frame, so it
    // is never carried across the one boundary where both inputs can move.
    //
    // Two failure modes are invisible to every numeric harness in this repo - a
    // wrong layer mask draws holes in the terrain, a wrong tile bit silently drops
    // a tile entity - so both consumers have their own verify mode that re-derives
    // the real answer and counts disagreements, and both report attach checks
    // that read a known non-zero.
    // ------------------------------------------------------------------

    /**
     * {@code VULKANMOD_FLTMASK} - the per-frame render-info record. <b>Default OFF,
     * and that is the result of pass 20 rather than an oversight.</b>
     *
     * <p>It works and it is verified, but pass 20 measured it as not paying. Both
     * consumers are loops whose entire body costs less than the redirect harness
     * needed to feed them: the filterempty scan is 80 ns an entry at rd 12 and the
     * tile loop 127 ns, against a harness pass 20 put at ~88 ns an entry.
     *
     * <p><b>That harness figure is RETRACTED (pass 23).</b> It was derived as the
     * paired OFF arm minus the same loop's section timer in a {@code FLTMASK=0}
     * build - but the paired OFF arm carries the A/B's own block timer and arm
     * bookkeeping, so what that subtraction measured was the <em>paired</em>
     * harness, not the redirect harness. It is measurement rule 16's failure mode,
     * and pass 20 committed it in the same document that states rule 16.
     *
     * <p>Direct measurement now bounds the real harness from the other side. At
     * identical work ({@code scanned=3568 added=887}, settled frames) the whole
     * four-scan section - <em>including</em> all the real work - reads 0.236-0.315 ms
     * in four hook-present runs, and 0.221-0.263 ms with the hooks removed. An
     * 88 ns/entry harness would need 0.314 ms of harness <em>alone</em>, and two of
     * those four runs read below that in total.
     *
     * <p>So the harness is a few ns, not 88, and this record was rejected on a
     * number that does not survive. Re-staging it - the layer-mask half only, which
     * won both of pass 20's end-to-end pairs - is the top-ranked next step in
     * {@code REFERENCE.md}, which carries the restore recipe.
     *
     * <p>Kept as unreachable code: a working instrument, and the evidence for the
     * pass-20 findings. Contrast pass 19's {@code ENTIDX}, which removed a
     * ~2.5 microsecond dependent-load chain and so cleared the harness by 28x.
     */
    // ------------------------------------------------------------------
    // PASS 23 — the three `renderBlockLayer` entry points of this record are
    // REMOVED. `flGet`, `flChunk` and `flLayerEmpty` lived here; their only call
    // sites were the three `@Redirect`s in `RenderGlobalMixin` on `List.get`,
    // `RenderChunk.getCompiledChunk()` and `CompiledChunk.isLayerEmpty()`, and
    // those are gone (see the note where they were).
    //
    // Pass 20 kept them on the argument "with FLTMASK off the redirects fold
    // away". They do not: `FLMASK` is consulted *inside* the handler, so the call
    // is emitted either way - 3 x 3568 = 10 704 invocations a frame for a feature
    // that was permanently off.
    //
    // They were nevertheless free. Measured at identical work (scanned=3568
    // added=887, settled frames): `fempty ms` 0.221 / 0.263 with the hooks removed
    // against 0.236 / 0.264 / 0.292 / 0.315 with them present - and the two runs of
    // the same post-removal build differ by more than the two groups do. **No
    // resolvable effect.** The handlers are three instructions and get inlined.
    //
    // So this is dead-code hygiene, not a win, and it is not claimed as one. It is
    // worth having only because a permanently-false flag feeding an unreachable
    // record is a liability, and because the note above needed correcting.
    //
    // Everything below — the record, its accumulators, the A/B and the
    // `[VKPROF] flmask` row — is kept as unreachable code so the pass-20 findings
    // survive in place. `FLMASK` is now the constant `false`, so no environment
    // variable can re-arm a record that nothing can build; restoring the three
    // redirects is what re-stages the experiment (recipe in REFERENCE.md).
    // ------------------------------------------------------------------

    /**
     * The pass-20 record's enable flag. <b>Permanently false since pass 24</b>, and
     * now for two reasons that do not depend on any timing.
     *
     * <p>Pass 23 retired it as dead code after measuring the removal as neutral.
     * Pass 24 re-staged the layer-mask half — the half that won <em>both</em> of
     * pass 20's end-to-end pairs — and closed the item on argument rather than on
     * another noisy pair of runs.
     *
     * <p><b>1. It is count-neutral.</b> {@code isLayerEmpty} is
     * {@code !this.layersUsed[layer.ordinal()]}. The build scan must evaluate all
     * four layers on every entry to fill its 4-bit mask, so it makes 892 &times; 4 =
     * 3568 calls a frame &mdash; <em>exactly</em> the 4 &times; 892 = 3568 the four
     * scans make without it. The record removes no touch of the per-chunk compiled
     * state at all; it relocates 2676 of them into the build scan. Making the build
     * free (a per-chunk memo, say) still removes no touches.
     *
     * <p><b>2. There is nothing left to remove.</b> Measurement rule 16 puts this
     * section's marginal per-entry cost at 5-20 ns; the 92-105 ns an entry pass 20
     * quoted is mostly the 0.26-0.32 ms frame-fixed intercept that same rule
     * describes. At 5-20 ns an entry the loop is at the floor, and the reason is
     * the working set: ~892 {@code CompiledChunk}s plus their {@code layersUsed[]}
     * arrays are ~30 KB and stay in L2 across all four scans, so the "two cache
     * misses an entry" the record was designed to remove are not there to remove.
     *
     * <p>End-to-end at identical work ({@code scanned=3568 added=887}, settled
     * frames): {@code fempty ms} 0.2288 with the flag off against 0.2251 with it on,
     * ranges overlapping. No resolvable effect — which is what both arguments above
     * predict, and the reason this is a closure rather than a failed measurement.
     *
     * <p>See REFERENCE.md, "pass 24", for the count argument, the working-set
     * argument, and the two instrument bugs the gate caught.
     */
    private static final boolean FLMASK = false;

    /** See {@link #FLMASK}. Kept for the record's javadoc; no longer readable from the environment. */
    private static final boolean FLMASK_VERIFY = false;

    private static int[] flMask = new int[2048];
    private static int[] flTiles = new int[2048];

    /**
     * The record is complete for the current frame. False from {@code beginFrame}
     * until the frame's first filterempty scan has finished, which is what makes
     * that scan the build scan.
     */
    private static boolean flFrameValid;
    /** Whether the CURRENT filterempty entry is answered from the record (false on the build scan and in the A/B's OFF arm). */
    private static boolean flUseMask;
    /** Index of the render-info entry the filterempty scan is on, from the {@code List.get} redirect. */
    private static int flIdx = -1;
    private static int flMaxIdx = -1;
    /** Cursor for the block-entity loop, reset at each {@code blockentities} section. */
    private static int flBIdx;
    /**
     * Pass 0's tile loop has recorded every entry, so pass 1 may read the record.
     * Cleared at the head of every frame, like {@link #flFrameValid}.
     */
    private static boolean flTilesValid;

    private static long flGets;
    private static long flChunks;
    private static long flIsl;
    private static long flBuilds;
    private static long flHits;
    private static long flOff;
    private static long flOffT;
    private static long flBad;
    /**
     * Pass 24: how many layer answers verify mode actually re-derived. Without it
     * {@code bad=0} cannot be told from "verify never ran" — rule 9's failure mode
     * exactly, and the layer half had no such counter while the tile half did.
     */
    private static long flChecked;
    private static long flIdxBad;
    private static long flBChunks;
    private static long flBSkip;
    private static long flTilesBuilt;
    private static long flBChecked;
    private static long flBBad;
    private static long flBLoops;
    /** Sections the record says are NOT empty - the handful whose list is returned for real. */
    private static long flBNonEmpty;

    /** Handed back instead of the real list when the record says the section has no tile entities. */
    private static final List<TileEntity> FL_EMPTY_TILES = Collections.emptyList();

    private static void flGrow(int need) {
        int n = flMask.length;
        while (n <= need) {
            n <<= 1;
        }
        flMask = Arrays.copyOf(flMask, n);
        flTiles = Arrays.copyOf(flTiles, n);
    }

    // ------------------------------------------------------------------
    // PASS 24 — the LAYER-MASK half was RE-STAGED HERE and is REMOVED AGAIN.
    //
    // Pass 23 removed the three entry points on an invocation count, then found
    // that the number pass 20 REJECTED the record with was impossible: "~83
    // ns/iteration of redirect harness" would need 0.296 ms of harness ALONE at
    // 3 x 3568 iterations, while four hook-present runs measure the whole four-scan
    // section, work included, at 0.236-0.315 ms. That figure was the paired OFF arm
    // minus a flag-off section timer, and the paired OFF arm carries the paired
    // harness - rule 16, violated by the pass that wrote rule 16 down.
    //
    // So pass 24 put the half back - the half that won BOTH of pass 20's end-to-end
    // pairs (-0.037 / -0.053 ms/frame, paired row unanimous in 70 of 70 reports) -
    // and measured it properly. The answer is in the javadoc on FLMASK above: it is
    // count-neutral, and the loop it would feed is already at its floor because the
    // ~30 KB working set stays in L2. Two arguments, both pointing the same way.
    //
    // The paired row did read dFlNs -59 ns/call over scans 2-4, and that reading is
    // not wrong - it is just about the wrong half. The paired row ticks the READ and
    // cannot see the BUILD, which is where the record's cost is and which is exactly
    // what the four scans' own count pays for. This is the trap pass 20 fell into
    // from the other side, and it is why the shipping question has to be answered by
    // `fempty ms` at identical work (same `scanned` AND `added`), never by dFlNs.
    //
    // Re-staging it was worth the run even so: the gate caught two instrument bugs
    // that would each have made a broken record look verified - a backwards
    // implication (on=0 v=1) and an inverted bit polarity (bad = every call). Both
    // were caught only by a counter reading a known non-zero next to `bad=0`, which
    // is rule 9 for the third time in this file. See REFERENCE.md "pass 24".
    //
    // This is the end state. Re-staging it a third time needs a reason that is not
    // "the last number was wrong".
    // ------------------------------------------------------------------

    // `flGet`, `flChunk`, `flLayerEmpty` and `flmScanEnd` were re-staged here by
    // pass 24 and removed again at the end of it. Their only call sites were the
    // three `@Redirect`s in RenderGlobalMixin on `List.get`,
    // `RenderChunk.getCompiledChunk()` and `CompiledChunk.isLayerEmpty()`, plus the
    // `flmScanEnd()` call in the `func_194339_b` redirect - and all four are gone.
    // See the note above for why, and REFERENCE.md "pass 24" for the recipe if the
    // record is ever re-staged a third time.

    /**
     * {@code RenderChunk.getCompiledChunk()} in the block-entity loop of
     * {@code renderEntities}.
     *
     * <p>{@code RenderGlobal} contains exactly one {@code getCompiledChunk()} call
     * in that method (the tile-entity loop), so counting it counts the loop's
     * iterations - and the count <b>is</b> the render-info index, because the loop
     * is a straight for-each over {@code renderInfos} from element 0 with no early
     * exit. The cursor is reset where the {@code blockentities} section opens.
     */
    public static CompiledChunk entBlockChunk(RenderChunk rc) {
        // Pass 21: this is the tile loop's iteration index, and TILEIDX is the only
        // consumer of it left. One increment, no branch (TILE_TRACK is a
        // compile-time constant).
        if (TILE_TRACK) {
            tileCursor++;
        }
        if (!FLMASK || !flFrameValid)
            return rc.getCompiledChunk();
        // Pass 24 removed `flBChunks++` and `flBIdx++` again: with FLMASK a constant
        // false this whole tail is unreachable, and a cursor nothing advances is
        // worse than no cursor - it is what makes `flBIdx - 1` below read -1.
        // Ticked once per pass-1 iteration whatever the arm, and timed from here to
        // the close at the method's RETURN - so the block spans the whole loop body,
        // `entBlockTiles` included, which is where the per-iteration difference
        // actually sits. Pass 0 is not ticked: it does the recording and its work is
        // identical in both arms.
        //
        // The real CompiledChunk is returned unconditionally, and that is a measured
        // decision rather than an oversight. Skipping the `rc.getCompiledChunk()`
        // call on pass 1 - handing back CompiledChunk.DUMMY, which is safe because
        // the only method `renderEntities` invokes on the value is the redirected
        // getTileEntities - was implemented and measured: dFtNs did not move
        // (-27 ns/call with and without it). The RenderChunk was just dereferenced to
        // reach this very call, so its compiledChunk field is an L1 hit. What is cold
        // is the CompiledChunk's own tileEntities field and the ArrayList behind it,
        // and that is the deref entBlockTiles removes.
        if (flTilesValid && PairedAB.TARGET_FLTMASK)
            ftAbTick(flmAbArm);
        return rc.getCompiledChunk();
    }

    /**
     * {@code CompiledChunk.getTileEntities()} in the block-entity loop.
     *
     * <p>The tile bit is <b>built by pass 0 and read by pass 1</b>, not by the
     * filterempty build scan. The pass-0 loop calls {@code getTileEntities()} on
     * every entry anyway, so recording the answer there costs one store and no
     * load at all; putting it in the filterempty build instead cost an extra
     * {@code tileEntities} field read and an extra ArrayList deref on all 824
     * entries, which measured 168 ns/scan against a 92 ns baseline - the build
     * scan was more expensive than the three scans it was feeding.
     *
     * <p>The list is empty for all but a handful of sections, and reaching it costs
     * two dependent loads - the CompiledChunk's {@code tileEntities} field and the
     * ArrayList object itself. When the record says empty, hand back a shared empty
     * list instead; the loop's only uses of the value are {@code isEmpty()} and,
     * when it is not empty, the iteration.
     */
    public static List<TileEntity> entBlockTiles(CompiledChunk cc) {
        // Pass 21 first, and it returns: TILEIDX and FLMASK are two answers to the
        // same question and running both would look the tile bit up with a cursor
        // that the compacted iterator has already re-pointed. `ab_flag.sh` disables
        // TILEIDX for `AB=FLTMASK` so pass 20's instrument stays exactly as it was
        // measured; FLMASK itself is default OFF, so a shipped run never sees this
        // branch.
        if (TILE_TRACK) {
            final List<TileEntity> real = cc.getTileEntities();
            final int i = tileCursor - 1;
            if (entP1Pass == 0) {
                // The build. `real` was going to be read anyway, so the record costs
                // one byte store and (for the handful of non-empty sections) one int
                // store - no extra load, which is why this build is free and the
                // filterempty-scan build pass 20 tried was not.
                if (i >= 0) {
                    if (i >= tileBits.length)
                        tileGrow(i);
                    final boolean nonEmpty = !real.isEmpty();
                    tileBits[i] = (byte) (nonEmpty ? 1 : 0);
                    tileBuilt++;
                    if (nonEmpty) {
                        if (tileCount0 >= tileIdx.length)
                            tileGrow(tileCount0);
                        tileIdx[tileCount0++] = i;
                    }
                }
                return real;
            }
            // Pass 1. Exactly one of the three configurations is active, and each
            // one is decided by the flag, not by the data.
            if (TILE_IDX_VERIFY) {
                // The full walk, so `i` really is the render-info index and the
                // record is on trial. `tmiss` is the direction that loses a tile
                // entity; it must be 0.
                if (i >= 0 && i < tileBits.length) {
                    tileChecked++;
                    final boolean rec = tileBits[i] != 0;
                    final boolean now = !real.isEmpty();
                    if (now && !rec) {
                        tileMiss++;
                    } else if (!now && rec) {
                        tileExtra++;
                    }
                }
                return real;
            }
            if (tileConsuming) {
                // The iterator only hands out entries pass 0 recorded as non-empty,
                // so this is the compaction's own attach check: `tvisited` must
                // track `tcount` and `tbad` must be 0. A non-zero `tbad` means the
                // record is answering "non-empty" for something that is not, i.e.
                // the iterator is handing out the wrong indices.
                tileVisited++;
                if (real.isEmpty()) {
                    tileBad++;
                }
            }
            return real;
        }
        if (!FLMASK || !flFrameValid)
            return cc.getTileEntities();
        final int i = flBIdx - 1;
        if (i < 0 || i >= flMask.length) {
            flIdxBad++;
            return cc.getTileEntities();
        }
        if (entP1Pass == 0) {
            final List<TileEntity> real = cc.getTileEntities();
            flTiles[i] = real.isEmpty() ? 0 : 1;
            flTilesBuilt++;
            return real;
        }
        if (!flTilesValid)
            return cc.getTileEntities();
        final boolean recEmpty = flTiles[i] == 0;
        if (FLMASK_VERIFY) {
            final boolean realEmpty = cc.getTileEntities().isEmpty();
            flBChecked++;
            if (realEmpty != recEmpty)
                flBBad++;
            return cc.getTileEntities();
        }
        if (PairedAB.TARGET_FLTMASK && !flmAbArm) {
            flOffT++;
            return cc.getTileEntities();
        }
        if (recEmpty) {
            flBSkip++;
            return FL_EMPTY_TILES;
        }
        // The record says this section really has tile entities, so the list has to
        // be the real one. This branch runs for the handful of non-empty sections,
        // and counting it is how the attach check knows the record is not simply
        // answering "empty" for everything: bskip + bnon + offT must be ri.
        flBNonEmpty++;
        return cc.getTileEntities();
    }

    // Paired A/B of the record (VULKANMOD_AB=FLTMASK).
    //
    // Two consumers, two column pairs, two units - and each one's decision point is
    // its own sample point:
    //
    //   flOn/flOff/dFlNs   one isLayerEmpty call on scans 2-4   (~3 x ri a frame)
    //   ftOn/ftOff/dFtNs   one getTileEntities call in the tile loop (2 x ri a frame)
    //
    // The arm is shared, because both consumers must be configured identically at
    // any instant; it is ticked by both, so a block is ~32 entries of each stream
    // rather than 64 of one. That is still ~20x the ~150-226 ns clock pair the
    // block form exists to amortise.
    private static boolean flmAbArm = true;
    private static boolean flmAbOpen;
    private static int flmAbCount;
    private static long flmAbStart;
    private static long abFlOnNanos;
    private static long abFlOnCalls;
    private static long abFlOffNanos;
    private static long abFlOffCalls;

    private static boolean ftAbArm = true;
    private static boolean ftAbOpen;
    private static int ftAbCount;
    private static long ftAbStart;
    private static long abFtOnNanos;
    private static long abFtOnCalls;
    private static long abFtOffNanos;
    private static long abFtOffCalls;

    private static void flmAbTick(boolean arm) {
        if (arm != flmAbArm || !flmAbOpen) {
            final long now = System.nanoTime();
            if (flmAbOpen)
                addAbFlMask(flmAbArm, now - flmAbStart, flmAbCount);
            flmAbArm = arm;
            flmAbStart = now;
            flmAbOpen = true;
            flmAbCount = 0;
        }
        flmAbCount++;
    }

    private static void flmAbFlush() {
        if (flmAbOpen) {
            flmAbOpen = false;
            addAbFlMask(flmAbArm, System.nanoTime() - flmAbStart, flmAbCount);
        }
        ftAbFlush();
    }

    public static void addAbFlMask(boolean onArm, long nanos, int calls) {
        if (onArm) {
            abFlOnNanos += nanos;
            abFlOnCalls += calls;
        } else {
            abFlOffNanos += nanos;
            abFlOffCalls += calls;
        }
    }

    /**
     * The tile-loop arm. Chosen at the tile loop's own decision point from the
     * shared {@code flmAbArm} value that the record's {@code flChunk} entry point
     * last published (that method is removed since pass 24; see the note on
     * {@link #FLMASK}), so no entry is ever measured half under each arm.
     */
    private static void ftAbTick(boolean arm) {
        if (arm != ftAbArm || !ftAbOpen) {
            final long now = System.nanoTime();
            if (ftAbOpen)
                addAbFtMask(ftAbArm, now - ftAbStart, ftAbCount);
            ftAbArm = arm;
            ftAbStart = now;
            ftAbOpen = true;
            ftAbCount = 0;
        }
        ftAbCount++;
    }

    private static void ftAbFlush() {
        if (ftAbOpen) {
            ftAbOpen = false;
            addAbFtMask(ftAbArm, System.nanoTime() - ftAbStart, ftAbCount);
        }
    }

    public static void addAbFtMask(boolean onArm, long nanos, int calls) {
        if (onArm) {
            abFtOnNanos += nanos;
            abFtOnCalls += calls;
        } else {
            abFtOffNanos += nanos;
            abFtOffCalls += calls;
        }
    }

    /** One vertex submitted through {@code BufferBuilder.endVertex}. */
    public static void onEndVertex() {
        if (ENABLED)
            vertices++;
    }

    // ------------------------------------------------------------------
    // Pass 17: the second entity walk.
    //
    // `renderEntities` runs twice a frame and that is Forge, not a bug. The
    // patched EntityRenderer calls it once with setRenderPass(0) - solid
    // entities, before translucent terrain - and once with setRenderPass(1) -
    // translucent entity layers, after it. Both calls walk the same 892 render
    // infos in the same order and pay the same five-deep dependent load chain to
    // rediscover something pass 0 already knew: which chunks hold no entities.
    //
    // Within one renderWorldPass nothing can change that answer. No tick runs, no
    // chunk loads (updateChunks is called before pass 0), and renderInfos is
    // rebuilt only by setupTerrain, which also runs before pass 0. So pass 0
    // records the answer and pass 1 reuses it - and the record is rebuilt from
    // scratch on every pass-0 call, so it is never carried across a frame.
    //
    // What is NOT assumed is that the answer stays true. ENTPASS1_VERIFY
    // re-derives it in pass 1 and counts disagreements; it must read 0/0.
    // ------------------------------------------------------------------

    /**
     * Pass-1 reuse of pass 0's empty-chunk findings ({@code VULKANMOD_ENTPASS1},
     * default ON; {@code =0} opts out).
     *
     * <p>The saving is the <b>probe</b>, not the entity loop: 892 iterations of
     * {@code world.getChunk(pos)} - a {@code Long2ObjectOpenHashMap} probe into an
     * 8192-entry map, through {@code provideChunk}'s {@code firstNonNull} - plus a
     * {@code Chunk} deref, an {@code entityLists[]} index and a {@code values}
     * load. Pass 16 measured that chain at ~1.11 µs an iteration, which is why a
     * second walk of an all-but-empty scene costs as much as the first.
     */
    private static final boolean ENT_PASS1 = !"0".equals(System.getenv("VULKANMOD_ENTPASS1"));

    /**
     * Pass-1 <b>loop compaction</b> ({@code VULKANMOD_ENTPASS2}, default ON;
     * {@code =0} opts out).
     *
     * <p>Pass 17 made an iteration pass 0 had found empty cheap, but still paid
     * 892 iterator advances, 892 {@code renderChunk.getPosition()} reads and 892
     * {@code isEmpty()} calls a frame for the privilege of discovering that ~880
     * of them have nothing to draw. At ~0.79 µs each that is the bulk of what is
     * left of the entity phase.
     *
     * <p>Pass 0 already knows which entries matter - it computes exactly that
     * answer - so it now appends their indices to a compact {@code int[]} and
     * pass 1's {@code for} loop iterates only those. The entries visited, their
     * order and the body they run are unchanged, so the render result is
     * bit-identical; the removed iterations are the ones whose body was
     * {@code if (!isEmpty())} and nothing else.
     *
     * <p>This rests on exactly the invariant ENTPASS1 already rests on and that
     * {@code ENTPASS1_VERIFY} already checks: within one {@code renderWorldPass}
     * the emptiness of each render info is stable between pass 0 and pass 1.
     */
    private static final boolean ENT_PASS2 = !"0".equals(System.getenv("VULKANMOD_ENTPASS2"));

    /**
     * Bench-only correctness gate for the compaction. Default OFF.
     *
     * <p>Takes the <b>full</b> walk in pass 1 and compares each iteration's real
     * emptiness against the pass-0 record - i.e. it re-derives the very set the
     * compaction iterates - and reports the disagreements as {@code miss} (pass 0
     * said empty, pass 1 found entities: the compaction would have dropped a
     * chunk's entities) and {@code extra}. It must read {@code miss=0 extra=0
     * bad=0} in every window.
     *
     * <p>This is the same comparison {@code VULKANMOD_ENTPASS1_VERIFY} makes, so
     * setting either one enables it.
     */
    private static final boolean ENT_PASS2_VERIFY =
            "1".equals(System.getenv("VULKANMOD_ENTPASS2_VERIFY"));

    /**
     * Bench-only. Takes the <b>unmodified</b> path in pass 1 and compares each
     * iteration's real emptiness against the pass-0 record, so a disagreement
     * shows up as a count instead of as a silently missing entity. Default OFF.
     */
    private static final boolean ENT_PASS1_VERIFY =
            ENT_PASS2_VERIFY || "1".equals(System.getenv("VULKANMOD_ENTPASS1_VERIFY"));

    /**
     * True when either pass-1 change is enabled. The index/record bookkeeping is
     * shared, so the {@code ENTPASS1=0} kill switch must not silently disarm the
     * {@code ENTPASS2} record as well.
     */
    private static final boolean ENT_TRACK = ENT_PASS1 || ENT_PASS2;

    /**
     * Pass-0 entity-walk index gate ({@code VULKANMOD_ENTIDX}, default ON;
     * {@code =0} opts out).
     *
     * <p>Pass 18 split the entity phase and found that every pass since 16 had been
     * optimising the smaller half: the pass-1 loop is 0.14–0.26 ms/frame and the
     * <b>pass-0</b> loop is 0.99 ms (1.11 µs an iteration, 892 of them). Pass 0's
     * body is a five-deep dependent load chain — {@code Long2ObjectOpenHashMap}
     * probe → {@code Chunk} → {@code entityLists} → {@code ClassInheritanceMultiMap}
     * → {@code isEmpty()} — over sections that are almost all empty and that nothing
     * else in the frame reads, i.e. a cold cache miss per link. It is memory
     * latency, not instructions: the block-entity loop over the <em>same</em> 892
     * render infos costs 0.16 µs an iteration because its {@code CompiledChunk} is
     * warmed by {@code filterempty}.
     *
     * <p>The gate answers an unmarked iteration without touching any of that. The
     * index is rebuilt from {@code world.getLoadedEntityList()} on every pass-0 call
     * — not maintained by hooks — so it is a <b>superset</b> of the sections that
     * hold entities (see {@link EntitySectionIndex}): the failure mode is wasted
     * work, never a dropped entity. And the stand-in chunk returned for a skipped
     * iteration is a real, permanently empty {@code Chunk}, so even a wiring failure
     * renders the correct scene at vanilla cost.
     *
     * <p>Requires {@code ENTPASS1}: the skip rides on the same pass-0 record, and
     * the {@code getEntityLists} redirect that answers it is guarded by that flag.
     */
    private static final boolean ENT_IDX = !"0".equals(System.getenv("VULKANMOD_ENTIDX"));

    /**
     * Correctness gate for {@link #ENT_IDX} ({@code VULKANMOD_ENTIDX_VERIFY=1}).
     *
     * <p>Takes the vanilla probe on every iteration and compares, at the
     * {@code isEmpty()} call site, the real answer against the index's verdict for
     * that section. {@code idxMiss} counts sections the index did <em>not</em> mark
     * that turned out to hold entities — i.e. entities this change would have
     * dropped — and <b>must be 0</b>. {@code idxExtra} counts the harmless
     * direction. Makes the timing meaningless, so it is a correctness run only.
     */
    private static final boolean ENT_IDX_VERIFY =
            "1".equals(System.getenv("VULKANMOD_ENTIDX_VERIFY"));

    /**
     * Pass-1 <b>tile-loop compaction</b> ({@code VULKANMOD_TILEIDX}, default ON;
     * {@code =0} opts out).
     *
     * <p>{@code renderEntities} runs twice a frame (Forge's two render passes) and
     * each call walks all of {@code renderInfos} a second time in the tile-entity
     * loop:
     *
     * <pre>
     *   for (info : this.renderInfos) {
     *       List&lt;TileEntity&gt; list3 = info.renderChunk.getCompiledChunk().getTileEntities();
     *       if (!list3.isEmpty()) { ... }
     *   }
     * </pre>
     *
     * <p>That is 1784 iterations a frame at ~157 ns each (pass 20's {@code blocke}
     * row), and on any real scene all but a handful of them end in
     * {@code isEmpty() == true}. Pass 18 removed exactly this shape from the
     * <em>entity</em> loop by recording the non-empty indices in pass 0 and handing
     * pass 1 an iterator over just those. This is the same change one loop down,
     * and it is the first candidate since pass 20 that is <b>not</b> subject to the
     * harness limit: it costs one redirect call per loop, not one per iteration.
     *
     * <p>The record is built during <b>pass 0's</b> tile loop, which reads
     * {@code getTileEntities()} on every entry anyway — so the build is one
     * {@code byte} store and one {@code int} store per entry and no extra load at
     * all. (Doing it in the {@code filterempty} build scan instead was implemented
     * in pass 20 and measured as a regression: that scan does not otherwise touch
     * the {@code tileEntities} field, so it paid a second cold object per entry.)
     *
     * <p>What is <b>not</b> assumed is that the answer stays true between the two
     * calls. {@code TILEIDX_VERIFY} takes the full walk in pass 1 and re-derives it.
     */
    private static final boolean TILE_IDX = !"0".equals(System.getenv("VULKANMOD_TILEIDX"));

    /**
     * Bench-only correctness gate for the tile-loop compaction. Default OFF.
     *
     * <p>Takes the <b>full</b> walk in pass 1 and compares each iteration's real
     * tile-entity emptiness against the pass-0 record. {@code tmiss} counts a
     * section that really has tile entities but was not recorded — i.e. tile
     * entities the compaction would have dropped — and <b>must be 0</b>;
     * {@code textra} is the harmless direction. Enabling it also maintains the
     * record even with {@code TILEIDX=0}, so a verify run can never come back
     * silently off.
     */
    private static final boolean TILE_IDX_VERIFY =
            "1".equals(System.getenv("VULKANMOD_TILEIDX_VERIFY"));

    /** True when the tile record has to be maintained at all. */
    private static final boolean TILE_TRACK = TILE_IDX || TILE_IDX_VERIFY;

    /** Forge's render pass for the current {@code renderEntities} call. */
    private static int entP1Pass;

    /** Iterations seen so far in this call - exactly one per loop iteration. */
    private static int entP1Idx;

    /** The iteration the last probe belonged to; read by the other two hooks. */
    private static int entP1Cur;

    /** Iterations recorded by the last pass-0 call. */
    private static int entP1Count0;

    /** Pass 1 reuses the record only when pass 0 produced one this frame. */
    private static boolean entP1Recorded;

    /** True while the current pass-1 call is reusing a record. */
    private static boolean entP1Consuming;

    /** 1 = that iteration's entity list was empty in pass 0. */
    private static byte[] entP1Empty = new byte[1024];

    /** The chunk pass 0 saw, so pass 1 can skip the map probe entirely. */
    private static Chunk[] entP1Chunk = new Chunk[1024];

    /** Set by the probe, read by the {@code getEntityLists} hook. */
    private static boolean entP1Skip;

    private static long entP1Probes;
    private static long entP1Short;
    private static long entP1Miss;
    private static long entP1Extra;
    private static long entP1Bad;

    /**
     * Attach checks for the other two hooks, and the pass classifier's own tally.
     *
     * <p>Added after a run read {@code short=0 miss=0 extra=0} and looked clean:
     * every one of those zeros is also what you see when a hook never attached.
     * {@code probes} had a counter and read 1784, so {@code getChunk} was fine -
     * which says nothing about {@code isEmpty} or {@code release()}. Three
     * quantities, three possible culprits, one run to separate them:
     * {@code ecalls} must equal {@code iter} (1784), {@code loopends} must equal
     * {@code calls} (2.00), and {@code forge0}/{@code forge1} must be 1.00 each.
     */
    private static long entP1EmptyCalls;
    private static long entP1LoopEnds;
    private static long entP1Pass0Calls;
    private static long entP1Pass1Calls;
    private static long entP1Forge0;
    private static long entP1Forge1;
    private static long entP1ForgeOther;

    /**
     * A 16-entry array of permanently empty entity lists.
     *
     * <p>Sixteen because {@code Chunk.getEntityLists()} is sixteen long and
     * {@code RenderChunk.getPosition().getY() / 16} is therefore in 0..15 - a real
     * chunk would throw on any other index too. Nothing ever adds to these, so
     * they stay empty for the life of the process, and because every skipped
     * iteration is handed the <b>same</b> array its {@code [y / 16]} index and
     * {@code isEmpty()} are L1 hits no matter which chunk asked.
     */
    private static ClassInheritanceMultiMap<Entity>[] entP1HotEmpty;

    @SuppressWarnings("unchecked")
    private static ClassInheritanceMultiMap<Entity>[] entP1HotEmptyLists() {
        ClassInheritanceMultiMap<Entity>[] a = entP1HotEmpty;
        if (a == null) {
            a = (ClassInheritanceMultiMap<Entity>[]) new ClassInheritanceMultiMap<?>[16];
            for (int i = 0; i < 16; i++) {
                a[i] = new ClassInheritanceMultiMap<Entity>(Entity.class);
            }
            entP1HotEmpty = a;
        }
        return a;
    }

    // ------------------------------------------------------------------
    // Pass 19: the pass-0 entity walk.
    //
    // `ents` has always been the SUM of both entity loops and was never separable
    // until pass 18 put two clock reads around each loop. Split, the picture is
    // unambiguous: pass 0 is 0.99 ms/frame at 1.11 us an iteration and pass 1 is
    // 0.14-0.26 ms at 160 ns - a ratio that survives a 1.6x machine-speed spread,
    // so it is a property of the code. Passes 16-18 all optimised pass 1.
    //
    // What makes pass 0 cost 1.11 us an iteration is not the instructions (~30 ns
    // of them) but the four cold cache misses per iteration: nothing else in the
    // frame reads a Chunk's entity lists, so every link of the chain is a DRAM
    // round trip. The only fix is to not make the trip, which needs an index of
    // "which sections hold entities".
    //
    // The index is rebuilt from world.getLoadedEntityList() every pass-0 call,
    // which makes it a SUPERSET of the sections the vanilla walk visits - so the
    // failure mode is wasted work rather than a dropped entity. That distinction
    // is the whole reason this is shippable and pass 18's hook-maintained index
    // was not.
    // ------------------------------------------------------------------

    /** Section index, reused across frames (one Arrays.fill a frame). */
    private static final EntitySectionIndex entIdx = new EntitySectionIndex();

    /** Rebuilt at the first pass-0 iteration of each renderEntities call. */
    private static boolean entIdxReady;

    /**
     * The stand-in returned for a skipped iteration.
     *
     * <p>A real {@code Chunk} with sixteen permanently empty
     * {@code ClassInheritanceMultiMap}s, so the loop's {@code [y / 16]} index and
     * {@code isEmpty()} answer correctly even if the {@code getEntityLists} redirect
     * were somehow not to fire. The skip then degrades to "slower", never to
     * "wrong". It is one object for the whole session and holds a world reference,
     * so {@link #onWorldChanged()} drops it.
     */
    private static Chunk entIdxDummyChunk;

    /** Per-iteration: the index's verdict for the section being probed. */
    private static boolean entIdxCurMarked;

    private static long entIdxBuilds;
    private static long entIdxMarks;
    private static long entIdxSkipped;
    private static long entIdxChecked;
    private static long entIdxMiss;
    private static long entIdxExtra;
    private static long entIdxDummies;

    private static void entIdxBuild(WorldClient world) {
        entIdx.rebuild(world.getLoadedEntityList());
        entIdxBuilds++;
        entIdxMarks += entIdx.size();
    }

    private static Chunk entIdxDummy(WorldClient world) {
        Chunk d = entIdxDummyChunk;
        if (d == null) {
            d = new Chunk(world, 0, 0);
            entIdxDummyChunk = d;
            entIdxDummies++;
        }
        return d;
    }

    /**
     * Drops the dummy and the index's readiness. Called when the world is replaced
     * or the renderers are reloaded, so the dummy cannot pin a dead world.
     */
    public static void onWorldChanged() {
        entIdxDummyChunk = null;
        entIdxReady = false;
    }

    // ------------------------------------------------------------------
    // Pass 18: pass 1 iterates only the entries that matter.
    //
    // Pass 0's `isEmpty()` answer is not just a reuse opportunity for the probe
    // (pass 17) - it is the membership test for the set of render infos pass 1
    // needs to visit at all. Recording the indices of the non-empty entries costs
    // one int store per non-empty entry (~15 a frame) and removes ~877 of pass 1's
    // 892 iterations, each of which costs ~0.79 us of pure overhead.
    // ------------------------------------------------------------------

    /** Indices of the pass-0 entries whose section had entities. */
    private static int[] entP2Idx = new int[1024];

    /** Entries appended to {@link #entP2Idx} by the call in progress. */
    private static int entP2Count0;

    /** Entries the last completed pass-0 call recorded. */
    private static int entP2Count;

    /**
     * The two entity-loop windows, split by pass.
     *
     * <p>The {@code ent} line reports {@code ents}, which is the sum of both
     * passes' loops (plus the multipass replay and the outline block) and has
     * never been separable. That mattered: the A/B's pass-1 window read ~110 µs
     * against a pass-0 loop of ~990 µs, which is a 9:1 split no prior pass had
     * seen and which the combined row cannot confirm or refute. Two clock reads
     * per loop (4 a frame) make the split free.
     *
     * <p>{@code p2 opens} is the attach check that matters here: the window is
     * opened by the {@code List.iterator()} redirect, and if that fires more than
     * once per loop the window is silently truncated and every number derived from
     * it is short. It must read 1.00.
     */
    private static long entP0LoopStart;
    private static long entP0LoopNanos;
    private static long entP0Opens;
    private static long entP1LoopStart;
    private static long entP1LoopNanos;
    private static long entP1Opens;

    // ------------------------------------------------------------------
    // Pass 27: is the entity phase the walk or the render?
    //
    // `p0` has been the largest single shim-visible item since pass 18 (1.34 ms
    // at rd 12, ~15% of the frame) and it has never been split. Pass 19 answered
    // one half of the question - the five-deep chunk-probe chain was ~31% of the
    // vanilla loop and ENTIDX removed it - and then attributed the residual to
    // "the inner entity-rendering body for the ~10 marked sections plus loop
    // machinery", which is two very different things in one sentence. The two
    // imply opposite decisions: a walk means 892 iterations of addressable
    // overhead, a render means a handful of vanilla `RenderEntityStatic` calls
    // that no index can make cheaper.
    //
    // The split costs one clock pair per *rendered* entity - `rendered` reads
    // 7.4 a frame at rd 12, so ~15 clock reads, against 892 iterations. That is
    // an unsampled instrument, unlike pass 26's, and it is deliberately NOT a
    // removal: removing the render body changes the scene, so a removal arm
    // could not be compared against this one.
    //
    // The gate is `entLoopOpen`, which only `entLoopIterator` sets. It exists
    // because `RenderManager.renderEntityStatic` has three call sites inside
    // `renderEntities` - the weather-effects loop above the entity loop and the
    // `list2` multipass replay below it - and both must stay out of the window
    // (the replay runs after `release()`, so the flag is already down).
    // ------------------------------------------------------------------

    /** True between the entity loop's iterator and `onEntityLoopEnd`. */
    private static boolean entLoopOpen;

    /** Wall time inside `RenderManager.renderEntityStatic`, entity loops only. */
    private static long entRenderNanos;

    /** Attach check: must track `rendered`, i.e. be non-zero every settled frame. */
    private static long entRenderCalls;

    /** Frames in which at least one render was timed. Must equal `frames` at rd 12. */
    private static long entRenderFrames;

    /** Per-frame latch for {@link #entRenderFrames}. */
    private static boolean entRenderFrameSeen;

    /** True while a timed `renderEntityStatic` should be paid for. */
    public static boolean entLoopOpen() {
        return ENT_TRACK && entLoopOpen;
    }

    /** One timed `renderEntityStatic`. */
    public static void onEntityRender(long nanos) {
        if (!entRenderFrameSeen) {
            entRenderFrameSeen = true;
            entRenderFrames++;
        }
        entRenderCalls++;
        entRenderNanos += nanos;
    }

    // ------------------------------------------------------------------
    // Pass 28: split the render itself.
    //
    // Pass 27 priced `renderEntityStatic` at 129-175 us a call, 7-9 calls a frame -
    // the largest per-call cost in the frame and, at ~1.0 ms, 11-14% of it. It left
    // the obvious next question open: the entity path is *supposed* to be display
    // lists, but `dlMs` (frame-global) is only 0.31-0.50 ms against `dlReplay`
    // 53-67 a frame, so at most about half the window can be replay. "The model /
    // Java work around it" was the other half by elimination, and elimination is
    // not an attribution.
    //
    // This splits the window on the vanilla call structure, which is stable and
    // gives disjoint boundaries:
    //
    //   render = the whole `renderEntityStatic`   (pass 27)
    //   pre    = render - do - sh                 (interpolation, getBrightnessForRender,
    //                                              setLightmapTextureCoords, color)
    //   do     = Render.doRender                  (the model)
    //   dl     = DisplayListManager.replayList    (nested inside `do`)
    //   sh     = Render.doRenderShadowAndFire
    //
    // `do - dl` is the model's non-replay work: pushMatrix/translate/rotate/popMatrix,
    // bindEntityTexture, setLivingAnimations/setRotationAngles, the two Forge events.
    // The `dl` accumulator is gated on `entLoopOpen`, so it is a subset of `do` by
    // construction, and `dlN` must never exceed the frame-global `dlReplay` - that is
    // the attach check which separates "the replay is cheap" from "the accumulator
    // never ran" (a detached hook reads 0, and 0 reads exactly like free).
    //
    // Note that `renderEntities` runs twice a frame (Forge render pass 0 and 1) and
    // `renderEntityStatic` is called in both, but `shouldRenderInPass` filters almost
    // everything into pass 0 - `n` = 7.0 a frame against `rendered` = 7.0 says the
    // window catches one render per entity, not two.
    // ------------------------------------------------------------------

    /** Wall time in `Render.doRender` for the entity loop. */
    private static long entDoNanos;

    /** Attach check for {@link #entDoNanos}: must track `n` (7-9 at rd 12). */
    private static long entDoCalls;

    /** Wall time in `Render.doRenderShadowAndFire` for the entity loop. */
    private static long entShNanos;

    /** Attach check for {@link #entShNanos}: must track `n` (7-9 at rd 12). */
    private static long entShCalls;

    /** Wall time in `DisplayListManager.replayList` while the entity loop is open. */
    private static long entDlNanos;

    /** Attach check for {@link #entDlNanos}: must be > 0 and <= the frame's `dlReplay`. */
    private static long entDlCalls;

    /** One timed `Render.doRender`. */
    public static void onEntityDo(long nanos) {
        entDoCalls++;
        entDoNanos += nanos;
    }

    /** One timed `Render.doRenderShadowAndFire`. */
    public static void onEntityShadow(long nanos) {
        entShCalls++;
        entShNanos += nanos;
    }

    /** One display-list replay inside the entity loop. */
    public static void onEntityDlReplay(long nanos) {
        entDlCalls++;
        entDlNanos += nanos;
    }

    // ------------------------------------------------------------------
    // Pass 28, second level: split `oth = do - dl`.
    //
    // The first level says `doRender` is 89% of the entity render and that only a
    // third of it is display-list replay, which leaves ~0.36 ms/frame (5% of the
    // frame) inside `doRender` and outside `replayList`. That residual is either
    // the shim's own matrix path or vanilla Java, and those imply opposite
    // decisions - one is ours to fix, the other is not.
    //
    // Two accumulators, both off the `entLoopOpen` gate:
    //   mat - the `MatrixState` op time, piggybacking on the clock read that
    //         `addMatrixOp` already performs, so it costs one extra `nanoTime`
    //         per entity matrix op (~90 a frame) and nothing at all when
    //         DETAILED_TIMING is off.
    //   mdl - `RenderLivingBase.renderModel`, the model draw itself, nested
    //         inside `do`. `oth - mdl` is then the `doRender` wrapper:
    //         pushMatrix/disableCull, setLivingAnimations/setRotationAngles,
    //         setDoRenderBrightness, renderLayers, popMatrix.
    //
    // `mat` reads 0.0000 when DETAILED_TIMING is off because `FrameProfiler.start()`
    // returns 0 and `addMatrixOp` has no delta to attribute. That is the pass-16
    // trap, so the row carries `dt=` - the state of the timing flag as the client
    // saw it - and a `mat` reading of 0 with `dt=0` means "not measured", never
    // "free". `matN` is the attach check for the same reason.
    // ------------------------------------------------------------------

    /** Entity-loop share of {@link #matrixNanos}; zero unless DETAILED_TIMING. */
    private static long entMatNanos;

    /** Attach check for {@link #entMatNanos}: must be ~90 a frame in a DRAWTIMING run. */
    private static long entMatOps;

    /** Wall time in `RenderLivingBase.renderModel` for the entity loop. */
    private static long entMdlNanos;

    /** Attach check for {@link #entMdlNanos}: must track `doN` (7-9 at rd 12). */
    private static long entMdlCalls;

    /** One entity-loop matrix op. */
    public static void addEntityMatrixNanos(long nanos) {
        entMatOps++;
        entMatNanos += nanos;
    }

    /** One timed `RenderLivingBase.renderModel`. */
    public static void onEntityModelRender(long nanos) {
        entMdlCalls++;
        entMdlNanos += nanos;
    }

    /** Pass 1 compacts only when pass 0 produced a record this frame. */
    private static boolean entP2Recorded;

    /** True while the current pass-1 call is iterating the compacted set. */
    private static boolean entP2Consuming;

    /** Iterations the compacted iterator actually handed out, and the bad ones. */
    private static long entP2Visited;
    private static long entP2Bad;

    /**
     * Recorded-entry count summed over the window's pass-0 calls.
     *
     * <p>Accumulated rather than reported from {@link #entP2Count}, which is a
     * snapshot of the last call - the shape that printed a plausible {@code total=0}
     * for four passes in pass 16.
     */
    private static long entP2CountSum;

    /**
     * The frame's arm for the compaction A/B, chosen once at pass-0 HEAD.
     *
     * <p>Deliberately a frame-level arm rather than a per-loop one: the record is
     * built by pass 0 and consumed by pass 1, so the two calls of one frame must
     * agree or the OFF arm would consume an ON-built record. Flipping at pass-0
     * HEAD (64 frames a block) also makes the timed window - the pass-1 loop -
     * a complete observation under one arm, with no boundary inside it.
     */
    private static boolean entP2AbArm = true;

    /** Iterator handed to pass 1's for-each when the compaction is active. */
    private static final CompactIterator<Object> entP2Iter = new CompactIterator<>();

    /**
     * Iterates a list by index over a compact {@code int[]}, so the pass-1 loop
     * walks ~15 entries instead of 892 without allocating a new list.
     */
    private static final class CompactIterator<T> implements Iterator<T> {
        private List<T> src;
        private int[] idx;
        private int n;
        private int k;

        void reset(List<T> src, int[] idx, int n) {
            this.src = src;
            this.idx = idx;
            this.n = n;
            this.k = 0;
        }

        @Override
        public boolean hasNext() {
            return k < n;
        }

        @Override
        public T next() {
            return src.get(idx[k++]);
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * The iterator pass 1's for-each is handed.
     *
     * <p>Called once per loop entry - for both passes and both arms - so it is
     * simultaneously the decision point, the window opener for the paired A/B, and
     * the pass-0 "nothing to do" path. Returning {@code list.iterator()} unchanged
     * is what the OFF arm and every non-A/B run do.
     */
    public static <T> Iterator<T> entLoopIterator(List<T> list) {
        if (!ENT_TRACK) {
            return list.iterator();
        }
        entLoopOpen = true;
        if (entP1Pass != 0) {
            entP1Opens++;
            entP1LoopStart = System.nanoTime();
            if (PairedAB.TARGET_ENTPASS2) {
                entP2AbOpen();
            }
            if (entP2Consuming) {
                @SuppressWarnings("unchecked")
                final CompactIterator<T> it = (CompactIterator<T>) (CompactIterator<?>) entP2Iter;
                it.reset(list, entP2Idx, entP2Count);
                return it;
            }
        } else {
            entP0Opens++;
            entP0LoopStart = System.nanoTime();
        }
        return list.iterator();
    }

    // ------------------------------------------------------------------
    // Pass 21: pass 1's tile-entity loop iterates only the sections that have
    // tile entities (VULKANMOD_TILEIDX).
    //
    // Pass 0's tile loop reads `getTileEntities()` on all 892 entries anyway, so
    // it records the non-empty indices for free; pass 1's for-each is handed an
    // iterator over just those. Same shape as ENTPASS2 (pass 18), one loop down.
    //
    // The cursor is the loop's own index: `RenderChunk.getCompiledChunk()` is
    // called exactly once in `renderEntities` and only from the tile loop, so
    // counting it counts the iterations - and the loop is a straight for-each
    // from element 0 with no early exit, so the count IS the render-info index.
    // The cursor is reset where the `blockentities` profiler section opens.
    // ------------------------------------------------------------------

    /** Indices of the pass-0 entries whose section had tile entities. */
    private static int[] tileIdx = new int[1024];

    /** Entries appended to {@link #tileIdx} by the call in progress. */
    private static int tileCount0;

    /** Entries the last completed pass-0 call recorded. */
    private static int tileCount;

    /**
     * Recorded-entry count summed over the window's pass-0 calls.
     *
     * <p>Reported instead of {@link #tileCount}, which is a snapshot of the last
     * call. Dividing a snapshot by the window's frame count prints a per-frame
     * rate that is 100x too small - the exact shape that made pass 16 report
     * {@code total=0} for four passes and that {@code entP2CountSum} exists to
     * avoid. It cost this pass one run: {@code count=0.0} looked like "the bench
     * scene has no tile entities", and it was read that way before the arithmetic
     * was checked.
     */
    private static long tileCountSum;

    /** 1 = that entry's tile list was non-empty in pass 0. Backs the verify gate. */
    private static byte[] tileBits = new byte[1024];

    /** The tile loop's iteration index; reset where the `blockentities` section opens. */
    private static int tileCursor;

    /** Pass 0 actually ran its tile loop this frame, so the record is complete. */
    private static boolean tileRecorded;

    /** True while the current pass-1 call is iterating the compacted set. */
    private static boolean tileConsuming;

    /**
     * The frame's arm for the tile-loop A/B, chosen once at pass-0 HEAD.
     *
     * <p>Frame-level rather than per-loop, for the same reason {@code ENTPASS2}'s
     * is: the record is built by pass 0 and consumed by pass 1, so the two calls of
     * one frame must agree or the OFF arm would consume an ON-built record.
     */
    private static boolean tileAbArm = true;

    /** Pass-1 tile-loop entries; must read 1.00 a frame (the loop's own attach check). */
    private static long tileLoops;

    /** Entries pass 0 recorded; must equal {@code ri} once a frame. */
    private static long tileBuilt;

    /** Entries the compacted iterator handed out; must track {@code count}. */
    private static long tileVisited;

    private static long tileChecked;
    private static long tileMiss;
    private static long tileExtra;
    private static long tileBad;

    /** Iterator handed to pass 1's tile for-each when the compaction is active. */
    private static final CompactIterator<Object> tileIter = new CompactIterator<>();

    private static void tileGrow(int need) {
        int n = tileIdx.length;
        while (n <= need) {
            n <<= 1;
        }
        // Grow each array to the size it needs and never shrink one to satisfy the
        // other. The first version shared one target size and `Arrays.copyOf`'d both
        // to it, so growing the 256-entry index past 256 would have TRUNCATED the
        // 1024-entry bit array - an index-keyed record silently shortened by the
        // branch that only runs on a scene with tile entities. That is the branch
        // this bench does not otherwise reach, which is exactly why it is the one
        // worth writing carefully.
        if (n > tileIdx.length)
            tileIdx = Arrays.copyOf(tileIdx, n);
        if (n > tileBits.length)
            tileBits = Arrays.copyOf(tileBits, n);
    }

    /**
     * The iterator the <b>tile-entity loop's</b> for-each is handed.
     *
     * <p>Called once per tile loop — twice a frame — so it is the decision point
     * for the pass-1 compaction and the opener of the A/B window that prices it.
     * Pass 0 gets the vanilla iterator unchanged: it is the build walk, and it does
     * the same work in both arms.
     *
     * <p>Reached through a {@code @Redirect} on the <b>third</b> {@code
     * List.iterator()} in {@code renderEntities} (ordinal 2: the entity loop over
     * {@code renderInfos} is 0, the {@code list2} multipass replay is 1, this is 2,
     * and the inner {@code list3} iteration is 3). The receiver is checked against
     * {@code renderInfos} as well, so a wrong ordinal is a fail-safe no-op rather
     * than a loop that iterates the wrong list.
     */
    public static <T> Iterator<T> tileLoopIterator(List<T> list) {
        if (!TILE_TRACK) {
            return list.iterator();
        }
        if (entP1Pass == 0) {
            tileCount0 = 0;
            return list.iterator();
        }
        tileLoops++;
        if (PairedAB.TARGET_TILEIDX) {
            tileAbOpen();
        }
        if (tileConsuming) {
            @SuppressWarnings("unchecked")
            final CompactIterator<T> it = (CompactIterator<T>) (CompactIterator<?>) tileIter;
            it.reset(list, tileIdx, tileCount);
            return it;
        }
        return list.iterator();
    }

    // Paired A/B of the tile-loop compaction (VULKANMOD_AB=TILEIDX).
    //
    // The arm is chosen once per frame at pass-0 HEAD so both calls of a frame
    // agree; the window is the whole pass-1 tile loop, opened where its iterator is
    // built and closed at the `renderEntities` RETURN that owns the loop. The unit
    // is therefore ns per pass-1 LOOP, not per iteration - the two arms do not
    // iterate the same number of times, so a per-iteration mean would be
    // meaningless (the BFSMASK lesson about units, applied again). It is also
    // already the per-frame figure, because only pass 1's half is compacted.
    private static boolean tileAbOpen;
    private static long tileAbStart;
    private static long abTileOnNanos;
    private static long abTileOnCalls;
    private static long abTileOffNanos;
    private static long abTileOffCalls;

    private static void tileAbOpen() {
        tileAbStart = System.nanoTime();
        tileAbOpen = true;
    }

    private static void tileAbClose() {
        if (!tileAbOpen) {
            return;
        }
        tileAbOpen = false;
        final long dt = System.nanoTime() - tileAbStart;
        if (tileAbArm) {
            abTileOnNanos += dt;
            abTileOnCalls++;
        } else {
            abTileOffNanos += dt;
            abTileOffCalls++;
        }
    }

    /** HEAD of {@code renderEntities}; {@code pass} is Forge's render pass. */
    private static void entPass1Begin(int pass) {
        // Assigned unconditionally: the entity-count row keys off it too, and a
        // diagnostic that goes stale when a flag is switched off is worse than no
        // diagnostic.
        entP1Pass = pass;
        // The pass classifier's own tally, ABOVE the flag guard on purpose. A
        // diagnostic that reads 0 when the flag is off is the pass-16 trap in its
        // purest form: `forge0=0 forge1=0` in an `ENTPASS1=0` control run is
        // indistinguishable from a classifier that never attached, and the control
        // run is exactly where the classifier's correctness has to be checked.
        // `forge0`/`forge1` must each read 1.00 and `forgeOther` 0.00, or the two
        // calls are not the two passes this record assumes they are.
        if (pass == 0) {
            entP1Forge0++;
            entP1Pass0Calls++;
        } else if (pass == 1) {
            entP1Forge1++;
            entP1Pass1Calls++;
        } else {
            entP1ForgeOther++;
        }
        if (!ENT_TRACK) {
            return;
        }
        entP1Idx = 0;
        entP1Cur = -1;
        entP1Skip = false;
        if (pass == 0) {
            // Pass 0 rebuilds the record. It stays false until the loop has
            // actually run, so a call that returns at the startup counter cannot
            // leave a stale record for pass 1 to trust.
            entP1Recorded = false;
            entP1Consuming = false;
            entP2Count0 = 0;
            entP2Recorded = false;
            entP2Consuming = false;
            // Pass 19: the section index is rebuilt once per pass-0 call, at the
            // first iteration - i.e. inside the pass-0 window the A/B prices, so
            // the build cost is charged to the arm that needs it.
            entIdxReady = false;
            // The frame's arm is chosen HERE, once, so both calls of the frame run
            // the same arm and the timed pass-1 window is never split.
            entP2AbArm = !PairedAB.TARGET_ENTPASS2 || PairedAB.nextArm();
            // Pass 21: the tile record has the same shape and the same requirement.
            tileCount0 = 0;
            tileRecorded = false;
            tileConsuming = false;
            tileAbArm = !PairedAB.TARGET_TILEIDX || PairedAB.nextArm();
        } else {
            entP1Consuming = entP1Recorded;
            // Verify mode takes the full walk, which is the point: it is the only
            // configuration in which every iteration can be compared against the
            // record that the compaction would have iterated.
            entP2Consuming = ENT_PASS2 && !ENT_PASS2_VERIFY && entP2Recorded && entP2AbArm;
            // Pass 21. Same three-way split: verify takes the full walk and
            // compares, the OFF arm takes the full walk and does not, and only the
            // ON arm iterates the compacted set.
            tileConsuming = TILE_IDX && !TILE_IDX_VERIFY && tileRecorded && tileAbArm;
        }
    }

    private static void entP2Grow(int need) {
        int n = entP2Idx.length;
        while (n <= need) {
            n <<= 1;
        }
        entP2Idx = Arrays.copyOf(entP2Idx, n);
    }

    private static void entP1Grow(int need) {
        int n = entP1Empty.length;
        while (n <= need) {
            n <<= 1;
        }
        entP1Empty = Arrays.copyOf(entP1Empty, n);
        entP1Chunk = Arrays.copyOf(entP1Chunk, n);
    }

    /**
     * {@code world.getChunk(pos)} inside {@code renderEntities} - the first call
     * of the loop body, so this is where the iteration index advances.
     *
     * <p>Pass 0 records the chunk and returns it. Pass 1 returns the recorded
     * chunk (no map probe) and, when pass 0 found that iteration's entity list
     * empty, flags it so the {@code getEntityLists} hook can answer with the hot
     * array instead of dereferencing a cold {@code Chunk}.
     */
    public static Chunk entPass1Chunk(WorldClient world, BlockPos pos) {
        final int i = entP1Idx++;
        entP1Cur = i;
        entP1Probes++;

        if (!ENT_PASS1) {
            return world.getChunk(pos);
        }

        if (entP1Pass == 0) {
            entP1Skip = false;
            // Pass 19. The index is rebuilt once per pass-0 call, on the first
            // iteration, so its ~74-entry scan is inside the window under test.
            if (ENT_IDX) {
                if (!entIdxReady) {
                    entIdxReady = true;
                    entIdxBuild(world);
                }
                // The A/B's decision point. Arm ON is the gate; arm OFF is the
                // vanilla probe with the index not even consulted, so the row is the
                // full "gate vs no gate" difference rather than "skip vs no skip".
                // The flip must happen BEFORE the decision and the block is timed at
                // the same point, so the sample point agrees with the decision point.
                boolean useIndex = true;
                if (PairedAB.TARGET_ENTIDX) {
                    useIndex = PairedAB.nextArm();
                    entIdxAbTick(useIndex);
                }
                final boolean marked = useIndex
                        && entIdx.contains(EntitySectionIndex.keyOf(pos));
                entIdxCurMarked = marked;
                if (useIndex && !marked) {
                    // Counted in verify mode too: there it is "would have been
                    // skipped", which is what says the gate is live.
                    entIdxSkipped++;
                    if (!ENT_IDX_VERIFY) {
                        if (i >= entP1Chunk.length) {
                            entP1Grow(i);
                        }
                        final Chunk dummy = entIdxDummy(world);
                        entP1Chunk[i] = dummy;
                        entP1Empty[i] = 1;
                        return dummy;
                    }
                }
            }
            final Chunk chunk = world.getChunk(pos);
            if (i >= entP1Chunk.length) {
                entP1Grow(i);
            }
            entP1Chunk[i] = chunk;
            return chunk;
        }

        if (entP2Consuming) {
            // The compaction means pass 1 only visits entries pass 0 found
            // non-empty, so the index-keyed record below no longer lines up and
            // there is nothing to skip - the probe is the real one, and it is paid
            // ~15 times instead of 892.
            entP1Skip = false;
            return world.getChunk(pos);
        }

        if (!entP1Consuming || ENT_PASS1_VERIFY) {
            // Verify mode takes the vanilla path, so the emptiness the isEmpty
            // hook compares against is the real one.
            entP1Skip = false;
            return world.getChunk(pos);
        }

        boolean skip = i < entP1Count0 && entP1Empty[i] != 0;
        if (PairedAB.TARGET_ENTPASS1) {
            // Flip first, then use the returned arm (the pass-10 lesson). The
            // decision point is here, and the block is timed here too, so the
            // sample point agrees with the decision point.
            final boolean arm = PairedAB.nextArm();
            entPass1Tick(arm);
            skip = arm && skip;
        }
        entP1Skip = skip;
        if (skip) {
            entP1Short++;
        }

        final Chunk recorded = i < entP1Count0 ? entP1Chunk[i] : null;
        return recorded != null ? recorded : world.getChunk(pos);
    }

    /**
     * {@code chunk.getEntityLists()} inside {@code renderEntities}. Counts the
     * iterations (pass 16) and, in pass 1, answers a skipped iteration with the
     * hot empty array.
     */
    public static ClassInheritanceMultiMap<Entity>[] entEntityLists(Chunk chunk) {
        onEntIteration();
        entP1EmptyCalls++;
        if (ENT_PASS1 && entP1Skip) {
            return entP1HotEmptyLists();
        }
        return chunk.getEntityLists();
    }

    /**
     * {@code ClassInheritanceMultiMap.isEmpty()} inside {@code renderEntities}.
     *
     * <p>Exactly one call per loop iteration - {@code RenderGlobal} contains one
     * {@code isEmpty} call site on that class in the whole file - which is what
     * makes it the record point for "did this iteration have anything to draw".
     */
    public static boolean entListIsEmpty(ClassInheritanceMultiMap<Entity> self) {
        if (!ENT_TRACK) {
            return self.isEmpty();
        }
        final boolean empty = self.isEmpty();
        final int i = entP1Cur;
        if (entP1Pass == 0) {
            if (i >= 0) {
                if (i >= entP1Empty.length) {
                    entP1Grow(i);
                }
                entP1Empty[i] = (byte) (empty ? 1 : 0);
                // Pass 19's gate. In verify mode the probe and the deref chain were
                // the real ones, so `empty` is the truth and the index is on trial:
                // a section the index did not mark that turns out to hold entities
                // is an entity the shipped build would have dropped.
                if (ENT_IDX && ENT_IDX_VERIFY) {
                    entIdxChecked++;
                    if (empty != !entIdxCurMarked) {
                        if (empty) {
                            entIdxExtra++;
                        } else {
                            entIdxMiss++;
                        }
                    }
                }
                if (!empty) {
                    // The membership test for pass 1's compacted iteration. This is
                    // the ONLY place in the frame where "does this render info have
                    // anything to draw" is known, which is why the record is built
                    // here rather than from a separate scan.
                    if (entP2Count0 >= entP2Idx.length) {
                        entP2Grow(entP2Count0);
                    }
                    entP2Idx[entP2Count0++] = i;
                }
            }
        } else {
            if (ENT_PASS1_VERIFY && entP1Consuming && i >= 0 && i < entP1Count0) {
                final boolean wasEmpty = entP1Empty[i] != 0;
                if (wasEmpty != empty) {
                    if (empty) {
                        entP1Extra++;
                    } else {
                        entP1Miss++;
                    }
                }
            }
            if (entP2Consuming) {
                // Attach check for the compaction: an iteration the record said was
                // non-empty must really be non-empty. `visited` must equal
                // `count`, and `bad` must be 0.
                entP2Visited++;
                if (empty) {
                    entP2Bad++;
                }
            }
        }
        return empty;
    }

    /**
     * Closes the record and the A/B block at the end of the entity loop.
     *
     * <p>{@code BlockPos.PooledMutableBlockPos.release()} is called exactly once
     * in {@code renderEntities}, immediately after the loop and before the
     * block-entity loop, so this is the loop closing its own window - pass 13's
     * rule. Leaving the flush to RETURN would time the last block across the
     * block-entity loop, ~0.25 ms of work the entity loop did not do.
     */
    public static void onEntityLoopEnd() {
        if (!ENT_TRACK) {
            return;
        }
        entLoopOpen = false;
        // TEMPORARY: the depth state the entity pass actually ran with - the
        // reported remaining defect is depth, and it is not visible from any
        // TEMPORARY (d10): section-transform extremes, for the blue ribbon that
        // is water geometry drawn in the wrong place. See onSectionXf above.
        reportSectionXf();
        entP1LoopEnds++;
        if (entP1Pass == 0) {
            entP0LoopNanos += System.nanoTime() - entP0LoopStart;
            entP1Count0 = entP1Idx;
            entP1Recorded = entP1Idx > 0;
            entP2Count = entP2Count0;
            entP2CountSum += entP2Count0;
            entP2Recorded = entP1Idx > 0;
        } else if (entP1Consuming && !entP2Consuming && entP1Idx != entP1Count0) {
            // The two calls must walk the same list in the same order, or an
            // index-keyed record is meaningless. Must be 0. Skipped while the
            // compaction is active: pass 1 visits ~15 entries by design, so the
            // counts are not supposed to match.
            entP1Bad++;
        }
        if (entP1Pass != 0) {
            entP1LoopNanos += System.nanoTime() - entP1LoopStart;
            entP2AbClose();
            // The record is SINGLE USE. Pass 1 always follows pass 0 within one
            // renderWorldPass, so the record is always fresh - but if pass 1 were
            // ever entered twice (a mod calling renderEntities, a frame where pass
            // 0 returned at the startup counter), the second entry would consume
            // the previous frame's record and silently cull a chunk that now has
            // entities. Retiring it here makes that impossible for one branch that
            // is taken twice a frame.
            entP1Recorded = false;
            entP1Consuming = false;
            entP2Recorded = false;
            entP2Consuming = false;
        }
        entPass1Flush();
    }

    // Paired A/B of the pass-1 reuse (VULKANMOD_AB=ENTPASS1). Blocks of 64
    // iterations - the same length every other paired target uses - so the
    // reported mean is ns per iteration and the frame effect is that times 892
    // (only pass 1 is eligible).
    private static boolean entP1AbArm = true;
    private static boolean entP1AbOpen;
    private static int entP1AbCount;
    private static long entP1AbStart;
    private static long abE1OnNanos;
    private static long abE1OnCalls;
    private static long abE1OffNanos;
    private static long abE1OffCalls;

    private static void entPass1Tick(boolean arm) {
        if (arm != entP1AbArm || !entP1AbOpen) {
            final long now = System.nanoTime();
            if (entP1AbOpen) {
                addAbEntPass1(entP1AbArm, now - entP1AbStart, entP1AbCount);
            }
            entP1AbArm = arm;
            entP1AbStart = now;
            entP1AbOpen = true;
            entP1AbCount = 0;
        }
        entP1AbCount++;
    }

    private static void entPass1Flush() {
        if (entP1AbOpen) {
            entP1AbOpen = false;
            addAbEntPass1(entP1AbArm, System.nanoTime() - entP1AbStart, entP1AbCount);
        }
        entIdxAbFlush();
    }

    /** One timed block of the pass-1-reuse A/B; {@code calls} is in iterations. */
    public static void addAbEntPass1(boolean onArm, long nanos, int calls) {
        if (onArm) {
            abE1OnNanos += nanos;
            abE1OnCalls += calls;
        } else {
            abE1OffNanos += nanos;
            abE1OffCalls += calls;
        }
    }

    // Paired A/B of the pass-1 loop compaction (VULKANMOD_AB=ENTPASS2).
    //
    // The arm is chosen once per frame at pass-0 HEAD, so both calls of a frame
    // agree; the window is the whole pass-1 loop, opened where its iterator is
    // built and closed where the loop releases its pooled BlockPos. The unit is
    // therefore ns per pass-1 LOOP, not per iteration - the two arms do not
    // iterate the same number of times, so a per-iteration mean would be
    // meaningless (this is the BFSMASK lesson about units, applied again).
    private static boolean entP2AbOpen;
    private static long entP2AbStart;
    private static long abE2OnNanos;
    private static long abE2OnCalls;
    private static long abE2OffNanos;
    private static long abE2OffCalls;

    private static void entP2AbOpen() {
        entP2AbStart = System.nanoTime();
        entP2AbOpen = true;
    }

    private static void entP2AbClose() {
        if (!entP2AbOpen) {
            return;
        }
        entP2AbOpen = false;
        final long dt = System.nanoTime() - entP2AbStart;
        if (entP2AbArm) {
            abE2OnNanos += dt;
            abE2OnCalls++;
        } else {
            abE2OffNanos += dt;
            abE2OffCalls++;
        }
    }

    // Paired A/B of the pass-0 index gate (VULKANMOD_AB=ENTIDX).
    //
    // The flip point is the decision point - inside the world.getChunk redirect,
    // once per iteration - and the block is timed there too, so a block spans 64
    // whole loop iterations of one arm. A per-call nanoTime pair would be ~150-226
    // ns against an ON arm of ~1100 ns and an OFF arm of ~60 ns, i.e. it would
    // dominate the very thing being measured; the block form makes both arms carry
    // the same one-pair-per-64-iterations overhead, which cancels in dEiNs. The
    // block is closed by onEntityLoopEnd (the loop closing its own window, pass
    // 13's rule) so it can never span the inter-frame gap.
    //
    // The unit is ns per ITERATION of the pass-0 walk (892/frame at rd 12).
    private static boolean entIdxAbArm = true;
    private static boolean entIdxAbOpen;
    private static int entIdxAbCount;
    private static long entIdxAbStart;
    private static long abEiOnNanos;
    private static long abEiOnCalls;
    private static long abEiOffNanos;
    private static long abEiOffCalls;

    /**
     * Meter checks for the block timer. A block is opened and closed inside the
     * pass-0 loop, so the sum of all block times can never exceed the loop window
     * `p0`; if it does, a block is spanning something that is not in the window and
     * every number derived from the row is void. `eiBig` counts blocks longer than
     * 1 ms, which is the signature of a block straddling the inter-frame gap.
     */
    private static long abEiBlocks;
    private static long abEiBig;

    private static void entIdxAbTick(boolean arm) {
        if (arm != entIdxAbArm || !entIdxAbOpen) {
            final long now = System.nanoTime();
            if (entIdxAbOpen) {
                addAbEntIdx(entIdxAbArm, now - entIdxAbStart, entIdxAbCount);
            }
            entIdxAbArm = arm;
            entIdxAbStart = now;
            entIdxAbOpen = true;
            entIdxAbCount = 0;
        }
        entIdxAbCount++;
    }

    private static void entIdxAbFlush() {
        if (entIdxAbOpen) {
            entIdxAbOpen = false;
            addAbEntIdx(entIdxAbArm, System.nanoTime() - entIdxAbStart, entIdxAbCount);
        }
    }

    /** One timed block of the pass-0 index gate; {@code calls} is in iterations. */
    public static void addAbEntIdx(boolean onArm, long nanos, int calls) {
        abEiBlocks++;
        if (nanos > 1_000_000L) {
            abEiBig++;
        }
        if (onArm) {
            abEiOnNanos += nanos;
            abEiOnCalls += calls;
        } else {
            abEiOffNanos += nanos;
            abEiOffCalls += calls;
        }
    }

    // ------------------------------------------------------------------
    // Pass 16b: localising the entity phase.
    //
    // The entity phase is 1.4 ms and the block-entity phase is 0.2 ms, yet they
    // walk the SAME 892 render infos. That 7x gap is the whole question, and the
    // body of the entity loop is three field reads and a hash lookup, which cannot
    // be 1.4 ms. So the window is instrumented from the inside: how many iterations
    // it really does, what the chunk lookup costs, and whether the entity-outline
    // framebuffer block (a full-screen clear plus two framebuffer binds, which on a
    // Vulkan backend is two render-pass restarts) is running inside it.
    // ------------------------------------------------------------------
    private static long entIterStart;
    private static long entIterNanos;
    private static long entIterCalls;
    private static long entOutlineCalls;

    /**
     * Bench-only: time the entity loop's per-iteration interval. Default OFF -
     * see {@link #onEntIteration} for why the meter has to be optional here.
     */
    private static final boolean ENT_ITER_TIME =
            "1".equals(System.getenv("VULKANMOD_ENT_ITER_TIME"));

    public static void onEntIteration() {
        if (!ENABLED)
            return;
        // Interval between successive iterations, not a window around one call:
        // the only call site in the loop body that a redirect actually matched was
        // `Chunk.getEntityLists()`, and it is the loop body's LAST call, so the gap
        // to the next iteration covers the whole body - the iterator advance, the
        // two `getPosition()` reads, `world.getChunk` and the array index.
        //
        // `entIterStart` is cleared at the start of every renderEntities call
        // (see beginRenderEntities) so that the final iteration's interval, which
        // would otherwise be closed by the NEXT frame's first iteration and span
        // the whole inter-frame gap, is dropped. That is pass 13's bug, avoided
        // here by construction rather than by a flush-and-reopen dance.
        //
        // The clock pair costs ~50 ns against an iteration of ~1100 ns, so it is
        // ~5% - but that is 5% of a 1.8 ms item, i.e. ~0.09 ms/frame added to the
        // very window under test. Bench-only, default OFF, so the shipped build
        // pays one predictable branch per iteration and nothing else.
        if (ENT_ITER_TIME) {
            final long now = System.nanoTime();
            if (entIterStart != 0L)
                entIterNanos += now - entIterStart;
            entIterStart = now;
        }
        entIterCalls++;
    }

    public static void onEntOutline() {
        if (ENABLED)
            entOutlineCalls++;
    }

    // ------------------------------------------------------------------
    // Pass 16c: settling the 2:1.
    //
    // Two counters said renderEntities runs 2.00x a frame; a third said
    // renderBlockLayer - three lines earlier in the same renderWorldPass - runs
    // once. HEAD and RETURN are counted separately here, because the two candidate
    // explanations are very different:
    //   head=1.00 calls=2.00  -> the RETURN inject double-fires (an instrument bug)
    //   head=2.00 calls=2.00  -> the method really is entered twice (a real
    //                            duplicate world pass worth ~1 ms/frame)
    // A bounded stack trace on the second entry names the second caller outright.
    // `Throwable.getStackTrace` costs microseconds, so it is capped at 3 dumps in
    // the whole run and can never show up in a window.
    // ------------------------------------------------------------------
    private static int entHeadInPass;
    private static long entHeadCalls;
    private static long worldPassCalls;
    private static int entTraceDumps;

    public static void onRenderEntitiesHead() {
        if (!ENABLED)
            return;
        entHeadCalls++;
        entHeadInPass++;
        // Dump the FIRST and SECOND entry of each of the first two world passes.
        // Entry #2's stack proved both entries come from one renderWorldPass; the
        // question that separates "one call site executed twice" from "two blocks
        // that both call it" is the *line number* of the caller frame, so entry #1
        // needs its own dump to compare against.
        if (entHeadInPass <= 2 && entTraceDumps < 4) {
            entTraceDumps++;
            final StackTraceElement[] st = new Throwable().getStackTrace();
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < st.length && i < 10; i++) {
                sb.append("\n    at ").append(st[i]);
            }
            VulkanMod.LOGGER.info(
                    "[VKENT] renderEntities entry #{} in worldPassCalls={} (dump {}/4){}",
                    entHeadInPass, worldPassCalls, entTraceDumps, sb);
        }
    }

    /**
     * The {@code iteration} section of {@code setupTerrain} was opened. One clock
     * read per frame; the matching close is the first {@code Profiler.endSection()}
     * after it, which the method only reaches from that section.
     */
    public static void beginSetupTerrainBfs() {
        if (ENABLED)
            bfsStart = System.nanoTime();
    }

    public static void endSetupTerrainBfs() {
        if (!ENABLED)
            return;
        bfsNanos += System.nanoTime() - bfsStart;
    }

    /** One {@code getRenderChunkOffset} call, i.e. one neighbour probe. */
    public static void onSetupTerrainProbe() {
        bfsProbes++;
    }

    /** One probe answered by the arithmetic rewrite rather than the lookup chain. */
    public static void onBfsFastCall() {
        bfsFastCalls++;
    }

    /** Verify mode only: the rewrite disagreed with the real lookup. Must be 0. */
    public static void onBfsVerifyMismatch() {
        bfsBad++;
    }

    /**
     * One timed block of the paired neighbour-probe A/B. {@code calls} is the
     * block length (64), not 1 - the block is timed from its first probe to the
     * first probe of the next one, so the per-call mean is the real mean.
     */
    public static void addAbBfs(boolean onArm, long nanos, int calls) {
        if (onArm) {
            abBfsOnNanos += nanos;
            abBfsOnCalls += calls;
        } else {
            abBfsOffNanos += nanos;
            abBfsOffCalls += calls;
        }
    }

    // ------------------------------------------------------------------
    // The BFS loop body (pass 14)
    // ------------------------------------------------------------------

    /**
     * True on one call in 32. The caller reads the clock only when this returns
     * true, so the timing tax is 1/32 of a full per-call timing pass.
     */
    public static boolean bfsSample() {
        return (++bfsSampleTick & 31) == 0;
    }

    /** One {@code EnumFacing.values()} evaluation, i.e. one polled chunk. */
    public static void onBfsPoll() {
        bfsPolls++;
    }

    /** One {@code EnumFacing.getOpposite()} on the BFS path. */
    public static void onBfsOpposite() {
        bfsOppositeCalls++;
    }

    /** One neighbour probe, with the sampled cost of the probe call chain. */
    public static void onBfsProbeSampled(long start, boolean sampled) {
        if (!sampled) {
            return;
        }
        bfsProbeNanos += System.nanoTime() - start;
        bfsProbeSamples++;
    }

    /** One {@code RenderChunk.setFrameIndex} call; {@code result} is its return value. */
    public static void onBfsSetFrame(boolean result, long start, boolean sampled) {
        bfsSetFrameCalls++;
        if (result) {
            bfsSetFrameTrue++;
        }
        if (sampled) {
            bfsSetFrameNanos += System.nanoTime() - start;
            bfsSetFrameSamples++;
        }
    }

    /** One {@code ICamera.isBoundingBoxInFrustum} call on the BFS path. Always on. */
    public static void onBfsFrustumCall() {
        bfsFrustumCalls++;
    }

    /** Sampled cost of the frustum call. Only accumulated with {@code BFS_BODY=1}. */
    public static void onBfsFrustum(long start, boolean sampled) {
        if (sampled) {
            bfsFrustumNanos += System.nanoTime() - start;
            bfsFrustumSamples++;
        }
    }

    /** One {@code CompiledChunk.isVisible} call on the BFS path. */
    public static void onBfsVisible(long start, boolean sampled) {
        bfsVisibleCalls++;
        if (sampled) {
            bfsVisibleNanos += System.nanoTime() - start;
            bfsVisibleSamples++;
        }
    }

    /**
     * Verify mode only: the positive-vertex frustum test disagreed with the real
     * {@code ClippingHelper.isBoxInFrustum}. **Any non-zero value means a chunk
     * is being culled that vanilla would draw - holes in the world - and must
     * not ship.** The two are algebraically identical (the maximum of a linear
     * form over a box is attained at the positive vertex, and the term order of
     * each dot product is preserved), so this is expected to be 0 in every
     * window; it exists because "algebraically identical" is exactly the class of
     * claim this project has been wrong about before.
     */
    public static void onBfsFrustumBad() {
        bfsFrustumBad++;
    }

    /** One timed block of the paired frustum A/B. {@code calls} is the block length. */
    public static void addAbFrustum(boolean onArm, long nanos, int calls) {
        if (onArm) {
            abFrOnNanos += nanos;
            abFrOnCalls += calls;
        } else {
            abFrOffNanos += nanos;
            abFrOffCalls += calls;
        }
    }

    /**
     * One poll whose keep mask removed at least one facing from the loop
     * (pass 15). {@code removed} is how many of the six facings term 1 rejected;
     * summing it gives the loop iterations the reorder actually deletes, which is
     * the number the paired A/B's per-poll figure multiplies against.
     */
    public static void onBfsMaskPoll(int removed) {
        bfsMaskPolls++;
        bfsMaskSkipped += removed;
    }

    /** {@code BFS_MASK_VERIFY} only: the table disagreed with {@code hasDirection}. Must be 0. */
    public static void onBfsMaskBad() {
        bfsMaskBad++;
    }

    // ---- pass 25: the BFS term-2 visibility row ---------------------------
    //
    // `applyTerm2` evaluates the conjunction's second term for every facing term 1
    // kept, i.e. ~2916 `CompiledChunk.isVisible` calls a frame from 892 polls, each
    // of them a three-level chain (CompiledChunk -> SetVisibility -> BitSet.get).
    // Pass 25 replaces them with one six-bit row read per poll, backed by a mirror
    // of SetVisibility's 36-bit store.
    //
    // These are counts, not timings, because the item is small enough that a
    // nanoTime pair would be a large fraction of it (rule 5) and because a count
    // settles a branch question a timing row cannot (rule 4). `vis` is the attach
    // check that matters: it must reproduce pass 14's independently measured
    // `bfsbody viN` (~2910) in the per-facing path.

    /** The mirror was written: the two SetVisibility mutators both call this. */
    private static long bfsRowMirrorWrites;    /** {@code applyTerm2} reached the term-2 decision (all three guards passed). */
    private static long bfsRowCalls;

    /** Of those, how many were answered from the row rather than per-facing. */
    private static long bfsRowHits;

    /** {@code isVisible} calls made by the per-facing path - pass 14's {@code viN}. */
    private static long bfsRowVisCalls;

    /** Rows that came back all-six, i.e. term 2 was vacuously true for that poll. */
    private static long bfsRowAllVisible;

    /** {@code BFS_ROW_VERIFY} disagreements. Must be 0: a mismatch is holes in the world. */
    private static long bfsRowBad;

    /** One mirror update. Called from the two SetVisibility mutator hooks. */
    public static void onBfsRowMirrorWrite() {
        bfsRowMirrorWrites++;
    }

    /** One term-2 decision, and whether the row answered it. */
    public static void onBfsRowCall(boolean fromRow) {
        bfsRowCalls++;
        if (fromRow) {
            bfsRowHits++;
        }
    }

    /** One {@code isVisible} call on the per-facing path. */
    public static void onBfsRowVisCall() {
        bfsRowVisCalls++;
    }

    /** A row that was all-six, so {@code term1Mask & row == term1Mask}. */
    public static void onBfsRowAllVisible() {
        bfsRowAllVisible++;
    }

    /** {@code BFS_ROW_VERIFY} only: the mirror row disagreed with the real BitSet. Must be 0. */
    public static void onBfsRowBad() {
        bfsRowBad++;
    }

    // ------------------------------------------------------------------
    // Pass 26: where the BFS loop's ~1.0 ms actually is
    // ------------------------------------------------------------------

    /**
     * Flag self-report for the pass-26 {@code bfsphase} row. Read here, not in
     * the mixin, so the row states what the client process received.
     */
    private static final int BFS_PHASE_ON =
            "1".equals(System.getenv("VULKANMOD_BFS_PHASE")) ? 1 : 0;

    /**
     * The pass-26 split of one BFS poll into its two halves, sampled 1-in-8.
     *
     * <p>The open question this answers: {@code bfs} is ~1.0 ms/frame over 892
     * polls and 2028 loop iterations, pass 13 measured the neighbour probe's
     * marginal cost at <b>~15 ns</b> (so the probe is ~30 us/frame, 3% of the
     * loop), and passes 15+22 removed 3324 of 5352 iterations for only ~330 us.
     * Those three facts cannot all describe the same 1.0 ms, so the loop's cost
     * is <em>somewhere nobody has looked</em>. This splits it:
     *
     * <ul>
     *   <li><b>prologue</b> = {@code queue.poll()} + the mask + term 2 + the two
     *       field reads + {@code renderInfos.add} - everything between the poll
     *       handler's entry and the {@code EnumFacing.values()} that opens the
     *       facing loop. This is the <em>per-poll</em> work.</li>
     *   <li><b>loop</b> = the facing loop + the enqueue + {@code queue.isEmpty()}
     *       - everything between that {@code values()} and the next poll
     *       handler's entry. This is the <em>per-iteration</em> work, 2028
     *       iterations over 892 polls.</li>
     * </ul>
     *
     * <p>Both stamps sit on redirects that already exist, so the split needs no
     * new hook and no new failure mode. Two clock reads per <em>sampled</em>
     * poll: at 1-in-8 that is ~220 reads a frame, ~17 us, 0.2% of the frame.
     *
     * <p><b>The closure is the check.</b> {@code pro + loop} is one whole poll,
     * so it must reproduce the independently measured whole-poll figure
     * (1090 ns/poll, the pass-25 row path, from a paired row whose block runs
     * poll to poll). If it does not, the instrument is broken, not the loop.
     */
    private static long bfsPhaseSamples;

    /** Summed deque-poll interval ({@code queue.poll()}) over the samples. */
    private static long bfsPhasePollNs;

    /** Summed mask + term-2 + {@code renderInfos.add} interval over the samples. */
    private static long bfsPhasePrologueNs;

    /** Summed facing-loop interval over the samples. */
    private static long bfsPhaseLoopNs;

    /**
     * Samples whose interval was negative or longer than a poll could plausibly
     * be (100 us). A non-zero value means a stamp was missed - a window that
     * spans a frame boundary, which is pass 13's 45x-high failure mode. Must be
     * 0 before any number on this row is quoted.
     */
    private static long bfsPhaseBad;

    /** One complete sampled poll: the deque-poll interval closed. */
    public static void onBfsPhasePoll(long ns) {
        bfsPhaseSamples++;
        bfsPhasePollNs += ns;
        if (ns < 0 || ns > 100_000L) {
            bfsPhaseBad++;
        }
    }

    /** The same sampled poll: the mask + term-2 + renderInfos.add interval closed. */
    public static void onBfsPhasePrologue(long ns) {
        bfsPhasePrologueNs += ns;
        if (ns < 0 || ns > 100_000L) {
            bfsPhaseBad++;
        }
    }

    /** The same sampled poll's facing loop, closed at the next poll's entry. */
    public static void onBfsPhaseLoop(long ns) {
        bfsPhaseLoopNs += ns;
        if (ns < 0 || ns > 100_000L) {
            bfsPhaseBad++;
        }
    }

    /**
     * One timed block of the paired term-1-hoist A/B. {@code calls} is the block
     * length in <b>polls</b> - see {@link PairedAB#TARGET_BFSMASK}.
     */
    public static void addAbBfsMask(boolean onArm, long nanos, int calls) {
        if (onArm) {
            abMkOnNanos += nanos;
            abMkOnCalls += calls;
        } else {
            abMkOffNanos += nanos;
            abMkOffCalls += calls;
        }
    }

    /**
     * The {@code update} block of {@code setupTerrain} was entered. Called from a
     * redirect on the {@code iteration} profiler section, which is opened exactly
     * once per entry into that block and nowhere else.
     */
    public static void onSetupTerrainBfs() {
        if (ENABLED)
            setupTerrainBfsRuns++;
    }

    /** Size of {@code renderInfos} as left by the last {@code setupTerrain}. */
    public static void observeRenderInfos(int size) {
        if (!ENABLED)
            return;
        renderInfosLast = size;
        if (size > renderInfosMax)
            renderInfosMax = size;
    }

    public static void beginSetupTerrain() {
        if (ENABLED)
            setupTerrainStart = System.nanoTime();
    }

    public static void endSetupTerrain() {
        if (!ENABLED)
            return;
        setupTerrainNanos += System.nanoTime() - setupTerrainStart;
        setupTerrainCalls++;
    }

    public static void beginVisibleFacings() {
        if (ENABLED)
            visibleFacingsStart = System.nanoTime();
    }

    public static void endVisibleFacings() {
        if (!ENABLED)
            return;
        visibleFacingsNanos += System.nanoTime() - visibleFacingsStart;
        visibleFacingsCalls++;
    }

    /** One {@code getVisibleFacings} call answered from the memo. */
    public static void onVisibleFacingsHit() {
        if (ENABLED)
            visibleFacingsHits++;
    }

    /** Verify mode only: the memo's answer disagreed with a fresh computation. */
    public static void onVisibleFacingsMismatch() {
        if (ENABLED)
            visibleFacingsBad++;
    }

    /** One {@code getVisibleFacings} call answered from the memo. */
    public static void onVisibleFacingsMemoHit() {
        if (ENABLED)
            visibleFacingsMemoHits++;
    }

    /** Verify mode only: the memo returned a stale answer and must not ship. */
    public static void onVisibleFacingsMemoMismatch() {
        if (ENABLED)
            visibleFacingsMemoBad++;
    }

    /** Timestamp helper for nestable segments (no shared state). */
    public static long start() {
        return (ENABLED && DETAILED_TIMING) ? System.nanoTime() : 0L;
    }

    public static void addShaderApply(long start) {
        if (ENABLED && DETAILED_TIMING)
            shaderApplyNanos += System.nanoTime() - start;
    }

    public static void onShaderApplyReused() {
        if (ENABLED)
            shaderApplyReuses++;
    }

    public static void addDrawRecord(long start) {
        if (ENABLED && DETAILED_TIMING)
            drawRecordNanos += System.nanoTime() - start;
    }

    // ---- Per-vkCmd-family CPU recording time -------------------------------
    // Every vkCmd* call site wraps itself with start()/addCmd(family, start).
    // Gated on DETAILED_TIMING (VULKANMOD_DRAWTIMING=1). These counters are
    // intentionally SEPARATE from the dedicated count hooks (onPipelineBind,
    // onPushConstants, draws, vertexBinds, descriptorBinds) so the existing
    // [VKPROF] positional report is unchanged and nothing is double-counted.
    public static final int CMD_DRAW_INDEXED = 0;
    public static final int CMD_DRAW = 1;
    public static final int CMD_BIND_PIPELINE = 2;
    public static final int CMD_BIND_VERTEX = 3;
    public static final int CMD_BIND_INDEX = 4;
    public static final int CMD_BIND_DESCRIPTOR = 5;
    public static final int CMD_PUSH_CONSTANTS = 6;
    public static final int CMD_PIPELINE_BARRIER = 7;
    public static final int CMD_COPY_BUFFER = 8;
    public static final int CMD_CLEAR_ATTACHMENTS = 9;
    public static final int CMD_BEGIN_RENDERING = 10;
    public static final int CMD_END_RENDERING = 11;
    public static final int CMD_SET_VIEWPORT = 12;
    public static final int CMD_SET_SCISSOR = 13;
    public static final int CMD_OTHER = 14;
    public static final int CMD_DRAW_MULTI_INDEXED = 15;
    private static final int CMD_COUNT = 16;
    private static final String[] CMD_NAMES = {
            "DrawIndexed", "Draw", "BindPipeline", "BindVertex", "BindIndex", "BindDescriptor",
            "PushConstants", "PipelineBarrier", "CopyBuffer", "ClearAttachments", "BeginRendering",
            "EndRendering", "SetViewport", "SetScissor", "Other", "DrawMultiIndexed"
    };
    private static final long[] cmdNanos = new long[CMD_COUNT];
    private static final long[] cmdCount = new long[CMD_COUNT];

    public static void addCmd(int family, long start) {
        if (!ENABLED || !DETAILED_TIMING || start == 0L)
            return;
        cmdCount[family]++;
        cmdNanos[family] += System.nanoTime() - start;
    }

    /**
     * Count-only command increment (no timing, no DETAILED_TIMING gate). Used by
     * the terrain batch path, which issues vkCmdDrawIndexed / vkCmdDrawMultiIndexedEXT
     * directly (bypassing Drawer) and therefore never reaches addCmd - without this
     * the [VKPROF] cmd line would under-count total DrawIndexed by the terrain count.
     */
    public static void addCmdCount(int family) {
        if (!ENABLED)
            return;
        cmdCount[family]++;
    }

    /** Timestamp helper for the texture-upload path (not gated on DETAILED_TIMING). */
    public static long texStart() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void onTextureUpload(int bytes) {
        if (!ENABLED)
            return;
        texUploads++;
        texUploadBytes += bytes;
    }

    /** Whole {@code uploadSubTextureAsync} body, minus the swizzle it was handed. */
    public static void addTextureUpload(long start) {
        if (ENABLED && start != 0L) {
            final long dt = System.nanoTime() - start;
            texUploadNanos += dt;
            if (dt > texUploadMaxNanos)
                texUploadMaxNanos = dt;
        }
    }

    /** One stage of the upload; see TEX_STG / TEX_BARRIER / TEX_COPY. */
    public static void addTexStage(long start, int stage) {
        if (ENABLED && start != 0L)
            texStageNanos[stage] += System.nanoTime() - start;
    }

    public static void addTextureSwizzle(long start, int bytes) {
        if (!ENABLED || start == 0L)
            return;
        texSwizzleNanos += System.nanoTime() - start;
        texSwizzleBytes += bytes;
    }

    /** Records which condition sent a terrain section down the full apply() path. */
    public static void onReuseReject(int reason) {
        if (ENABLED && reason >= 0 && reason < reuseRejects.length)
            reuseRejects[reason]++;
    }

    /** A reuse that happened on a shader other than the terrain pipeline. */
    public static void onShaderApplyReusedNonBlock() {
        if (ENABLED)
            reuseNonBlock++;
    }

    public static void beginFrame() {
        // Pass 20: the render-info record is only valid within one frame. Dropping
        // it here is what makes the first filterempty scan of each frame the build
        // scan; `setupTerrain` and `updateChunks` both run after this point and
        // before that scan, so the record can never be built from a stale list.
        flFrameValid = false;
        flTilesValid = false;
        if (!ENABLED)
            return;
        frameStart = System.nanoTime();
    }

    public static void endFrame() {
        if (!ENABLED)
            return;

        totalNanos += System.nanoTime() - frameStart;

        entRenderFrameSeen = false;

        if (++frames >= REPORT_INTERVAL) {
            report();
        }
    }

    /** Marks the start of a blocking GPU sync (fence wait / image acquire). */
    public static void beginFenceWait() {
        if (ENABLED)
            markStart = System.nanoTime();
    }

    public static void endFenceWait() {
        if (ENABLED)
            fenceWaitNanos += System.nanoTime() - markStart;
    }

    public static void beginSubmit() {
        if (ENABLED)
            markStart = System.nanoTime();
    }

    public static void endSubmit() {
        if (ENABLED)
            submitNanos += System.nanoTime() - markStart;
    }

    /**
     * Marks the start of vkQueuePresentKHR. Uses its own marker slot: this call
     * happens inside the beginSubmit/endSubmit window, so sharing markStart would
     * clobber the submit measurement.
     */
    public static void beginPresent() {
        if (ENABLED)
            presentStart = System.nanoTime();
    }

    public static void endPresent() {
        if (ENABLED)
            presentNanos += System.nanoTime() - presentStart;
    }

    public static void onDraw() {
        if (ENABLED)
            draws++;
    }

    public static void onPipelineBind() {
        if (ENABLED)
            pipelineBinds++;
    }

    public static void onDescriptorUpdate() {
        if (ENABLED)
            descriptorUpdates++;
    }

    /** A vkCmdBindDescriptorSets was actually issued. */
    public static void onDescriptorBind() {
        if (ENABLED)
            descriptorBinds++;
    }

    /** A vkCmdBindDescriptorSets was elided because the binding was unchanged. */
    public static void onDescriptorBindSkipped() {
        if (ENABLED)
            descriptorBindSkips++;
    }

    /** A vkCmdBindVertexBuffers was elided because the binding was unchanged. */
    public static void onVertexBindSkipped() {
        if (ENABLED)
            vertexBindSkips++;
    }

    /**
     * A real vkCmdBindVertexBuffers was issued. Timed because the per-draw
     * vertex bind is the one bind the terrain path cannot dedup (each chunk
     * section owns its buffer), so its cost is per section, not per batch.
     */
    public static void addVertexBind(long start) {
        if (ENABLED) {
            vertexBinds++;
            vertexBindNanos += System.nanoTime() - start;
        }
    }

    /** A vkCmdBindIndexBuffer was elided because the binding was unchanged. */
    public static void onIndexBindSkipped() {
        if (ENABLED)
            indexBindSkips++;
    }

    public static void onVertexCopy(int bytes) {
        if (ENABLED)
            vertexBytesCopied += bytes;
    }

    /** A full P*MV recompute (VRenderSystem.calculateMVP) was performed. */
    public static void onMvpRecalc() {
        if (ENABLED)
            mvpRecalcs++;
    }

    /** Closes the timer opened around a full P*MV recompute. */
    public static void addMvpRecalcNanos(long start) {
        if (ENABLED && start != 0L)
            mvpRecalcNanos += System.nanoTime() - start;
    }

    /** Closes the timer opened around ShaderInstance.canReuseTerrainState. */
    public static void addReuseGuardNanos(long start) {
        if (ENABLED && start != 0L)
            reuseGuardNanos += System.nanoTime() - start;
    }

    /** One matrix op, split by kind. Bit 0..2 is the kind index (see {@link #MAT_KINDS}). */
    public static final int MAT_PUSH = 0;
    public static final int MAT_POP = 1;
    public static final int MAT_TRANSLATE = 2;
    public static final int MAT_MULT = 3;
    public static final int MAT_ROTATE = 4;
    public static final int MAT_SCALE = 5;
    public static final int MAT_LOAD_IDENTITY = 6;

    /**
     * A matrix op with no kind bucket - {@code ortho} and {@code setPerspective},
     * which count toward {@code matOps} but were never split by kind. Passing this
     * keeps those two sites on the same single call as everything else.
     */
    public static final int MAT_NONE = -1;

    public static final String[] MAT_KINDS =
            {"push", "pop", "translate", "multMatrix", "rotate", "scale", "loadIdentity"};

    private static final long[] matByKind = new long[MAT_KINDS.length];

    /** A chunk section's MVP was produced by the fused transform path. */
    public static void onChunkXfFused() {
        if (ENABLED)
            chunkXfFused++;
    }

    // ------------------------------------------------------------------
    // TEMPORARY (d10): section-transform outlier probe.
    //
    // The user reports a flat saturated-blue ribbon lying ACROSS the grass and
    // over the mobs, at a near-constant screen row, present only in frames that
    // contain an actual water body. That is water geometry that has been drawn
    // somewhere it does not belong, so the question is which section's transform
    // is wrong - not whether the MVP maths is wrong (CHUNKXF_VERIFY says
    // xfMis=0 xfStale=0, so the fusion itself is exact).
    //
    // A section is a 16x16x16 cube of a chunk at integer chunk coordinates, so a
    // sane transform is a whole-number translation in a few thousand blocks and
    // a scale of exactly 1. Accumulate the extremes per frame; a translation far
    // outside the world, a fractional one, or a scale that is not 1 is the
    // fingerprint of the draw that stretches a section across the view.
    // ------------------------------------------------------------------
    private static final boolean XFPROBE = "1".equals(System.getenv("VULKANMOD_XFPROBE"));

    private static float xfMinX = Float.MAX_VALUE, xfMaxX = -Float.MAX_VALUE;
    private static float xfMinY = Float.MAX_VALUE, xfMaxY = -Float.MAX_VALUE;
    private static float xfMinZ = Float.MAX_VALUE, xfMaxZ = -Float.MAX_VALUE;
    private static float xfMinS = Float.MAX_VALUE, xfMaxS = -Float.MAX_VALUE;
    private static long xfN;
    private static long xfBadScale, xfFrac;      // s != 1 ; translation not whole
    private static long xfBig;                   // |t| > 3e7 (outside any world)

    /** One fused section transform, as written to sectionTransform. */
    public static void onSectionXf(float ux, float uy, float uz, float s) {
        if (!XFPROBE)
            return;
        xfN++;
        if (ux < xfMinX) xfMinX = ux;
        if (ux > xfMaxX) xfMaxX = ux;
        if (uy < xfMinY) xfMinY = uy;
        if (uy > xfMaxY) xfMaxY = uy;
        if (uz < xfMinZ) xfMinZ = uz;
        if (uz > xfMaxZ) xfMaxZ = uz;
        if (s < xfMinS) xfMinS = s;
        if (s > xfMaxS) xfMaxS = s;
        if (s != 1.0f) xfBadScale++;
        if (ux != Math.round(ux) || uy != Math.round(uy) || uz != Math.round(uz)) xfFrac++;
        if (Math.abs(ux) > 3.0e7f || Math.abs(uy) > 3.0e7f || Math.abs(uz) > 3.0e7f) xfBig++;
    }

    private static void reportSectionXf() {
        if (!XFPROBE || xfN == 0)
            return;
        VulkanMod.LOGGER.info(
                "[VKXF] n={} x={}..{} y={}..{} z={}..{} s={}..{} badScale={} frac={} big={}",
                xfN, xfMinX, xfMaxX, xfMinY, xfMaxY, xfMinZ, xfMaxZ,
                xfMinS, xfMaxS, xfBadScale, xfFrac, xfBig);
        xfN = 0;
        xfMinX = xfMinY = xfMinZ = Float.MAX_VALUE;
        xfMaxX = xfMaxY = xfMaxZ = -Float.MAX_VALUE;
        xfMinS = Float.MAX_VALUE;
        xfMaxS = -Float.MAX_VALUE;
        xfBadScale = xfFrac = xfBig = 0;
    }

    /** The cached A = P*parent had to be rebuilt (expected ~once per layer). */
    public static void onChunkXfCacheMiss() {
        if (ENABLED)
            chunkXfCacheMiss++;
    }

    /**
     * A reader pulled the matrix stack in while a chunk window was still open,
     * forcing the deferred transform to be materialised. Expected 0 on the
     * terrain path (the arena shader reads no matrix uniform); a non-zero value
     * means the fused path is being undone and the win is smaller than it looks.
     */
    public static void onChunkXfStackRead() {
        if (ENABLED)
            chunkXfStackRead++;
    }

    /**
     * Verify mode only: the epoch said the cached A = P*parent was still valid
     * but the factors had actually changed, i.e. a missed epoch bump. Must
     * never fire - a non-zero value means the fused path can draw a stale
     * transform and the whole approach has to be reconsidered.
     */
    public static void onChunkXfEpochStale() {
        if (ENABLED)
            chunkXfEpochStale++;
    }

    /**
     * Verify mode only. Records how far the fused MVP is from the general one;
     * {@code mismatch} is true when that difference is large enough to be a bug
     * rather than the rounding of a reassociated product. Must never fire.
     */
    public static void onChunkXfVerify(double maxAbsDiff, boolean mismatch) {
        if (!ENABLED)
            return;
        if (maxAbsDiff > chunkXfMaxDiff)
            chunkXfMaxDiff = maxAbsDiff;
        if (mismatch)
            chunkXfMismatch++;
    }

    /** A vkCmdPushConstants was issued. */
    public static void onPushConstants() {
        if (ENABLED)
            pushConstantCalls++;
    }

    /** A vkCmdPushConstants was elided because the payload was unchanged. */
    public static void onPushConstantSkipped() {
        if (ENABLED)
            pushConstantSkips++;
    }

    /** A draw was issued straight out of a persistent vertex buffer. */
    public static void onPersistentDraw() {
        if (!ENABLED)
            return;
        persistentDraws++;
        if (inEntityReplay)
            entityPersistentDraws++;
        else
            terrainPersistentDraws++;
    }

    /** Set by DisplayListManager around the entity-model replay loop. */
    public static void setEntityReplay(boolean on) {
        inEntityReplay = on;
    }

    /** A draw was issued after copying vertex data into the per-frame buffer. */
    public static void onCopiedDraw(VertexFormat vertexFormat) {
        if (!ENABLED)
            return;
        copiedDraws++;
        if (drawPhase == PHASE_OVERLAY)
            immOverlay++;
        else
            immWorld++;
        classifyImmediateFormat(vertexFormat);
    }

    /**
     * Source hint for an immediate draw: classify its vertex format. A draw that
     * carries a lightmap UV (UV index 1) is tile-entity / lit-world geometry; one
     * with a texture UV (UV index 0) but no lightmap is GUI / particle / overlay;
     * one with neither is debug lines / selection box. BLOCK is the terrain format
     * (chunk VBOs drawn without a persistent buffer, normally ~0). Classification
     * is by element inspection so it works for both the {@code DefaultVertexFormats}
     * singletons (used by vanilla Tessellator draws) and the fresh formats built
     * by the glDrawArrays client-array path.
     */
    private static void classifyImmediateFormat(VertexFormat vf) {
        if (vf == null) {
            immOther++;
            return;
        }
        if (vf == DefaultVertexFormats.BLOCK) {
            immBlock++;
            return;
        }
        boolean hasLightmap = false;
        boolean hasTex = false;
        for (VertexFormatElement e : vf.getElements()) {
            if (e.getUsage() == VertexFormatElement.EnumUsage.UV) {
                if (e.getIndex() == 1) {
                    hasLightmap = true;
                } else if (e.getIndex() == 0) {
                    hasTex = true;
                }
            }
        }
        if (hasLightmap) {
            immLmap++;
        } else if (hasTex) {
            immTex++;
        } else {
            immLines++;
        }
    }

    /**
     * One matrix-stack operation (translate/rotate/scale/push/pop/multMatrix).
     *
     * <p>Always counted, timed only with DETAILED_TIMING. The kind bucket and the
     * total used to be two separate calls at every one of the ~4000 matrix ops a
     * frame; they are one call now, which removes ~4000 static calls per frame
     * from a path that already runs ~4000 times. `kind` is {@link #MAT_NONE} for
     * the ops that have no bucket.
     */
    public static void addMatrixOp(long start, int kind) {
        matrixOps++;

        if (kind >= 0) {
            matByKind[kind]++;
        }

        if (start != 0L) {
            matrixNanos += System.nanoTime() - start;
        }

        // Pass 28: the entity loop's share of the matrix time. `start` is already
        // non-zero only in a DETAILED_TIMING build, so this is one extra clock read
        // per entity matrix op and nothing when the timing flag is off - and the
        // row's `dt=` field is what tells a reader which of those two it is.
        if (start != 0L && entLoopOpen) {
            addEntityMatrixNanos(System.nanoTime() - start);
        }

        // Paired A/B attribution for the transform flag. Constant-folded away
        // when VULKANMOD_AB is not naming a flag, so this costs nothing in play.
        if (PairedAB.ACTIVE) {
            addAbMatrixOp(start, PairedAB.arm());
        }
    }

    /** One GlStateManager.callList replay. */
    public static void onDisplayListReplay() {
        if (ENABLED)
            displayListReplays++;
    }

    /** One draw replayed out of a captured display list. */
    public static void onDisplayListDraw() {
        if (ENABLED)
            displayListDraws++;
    }

    public static void addDisplayListNanos(long start) {
        if (ENABLED && start != 0L)
            displayListNanos += System.nanoTime() - start;
    }

    public static void addDisplayListApplyNanos(long start) {
        if (ENABLED && start != 0L)
            displayListApplyNanos += System.nanoTime() - start;
    }

    /** HEAD of EntityRenderer.updateCameraAndRender. */
    public static void beginCameraAndRender() {
        if (ENABLED) {
            camAtStart = System.nanoTime();
            // If renderWorld never runs this frame (or throws before the HEAD
            // injector on renderWorld fires), camWorldEnd stays at its previous
            // value and endCameraAndRender would compute the entire
            // updateCameraAndRender time as "after world". Initialise it here so
            // that case adds exactly zero - the wall time is still captured by
            // camBeforeWorldNanos.
            camWorldEnd = camAtStart;
        }
    }

    /** HEAD of EntityRenderer.renderWorld: closes the "before" segment. */
    public static void beginRenderWorld() {
        if (!ENABLED)
            return;

        drawPhase = PHASE_WORLD;
        final long now = System.nanoTime();
        camBeforeWorldNanos += now - camAtStart;
        camWorldStart = now;
    }

    /** RETURN of EntityRenderer.renderWorld: closes the "world" segment. */
    public static void endRenderWorld() {
        if (ENABLED) {
            drawPhase = PHASE_OVERLAY;
            final long now = System.nanoTime();
            camWorldNanos += now - camWorldStart;
            camWorldEnd = now;
        }
    }

    /** RETURN of EntityRenderer.updateCameraAndRender: closes the "after" segment. */
    public static void endCameraAndRender() {
        if (ENABLED)
            camAfterWorldNanos += System.nanoTime() - camWorldEnd;
    }

    public static void beginLightmap() {
        if (ENABLED)
            lightmapStart = System.nanoTime();
    }

    public static void endLightmap() {
        if (ENABLED)
            lightmapNanos += System.nanoTime() - lightmapStart;
    }

    public static void beginMouseOver() {
        if (ENABLED)
            mouseOverStart = System.nanoTime();
    }

    public static void endMouseOver() {
        if (ENABLED)
            mouseOverNanos += System.nanoTime() - mouseOverStart;
    }

    public static void beginWorldPass() {
        if (ENABLED) {
            worldPassStart = System.nanoTime();
            // pass 16c: a world pass is the only frame boundary the shim can see
            // that both renderEntities callers would sit inside.
            worldPassCalls++;
            entHeadInPass = 0;
        }
    }

    public static void endWorldPass() {
        if (ENABLED)
            worldPassNanos += System.nanoTime() - worldPassStart;
    }

    /**
     * Dumps the GL state a draw actually sees, at most once a second.
     *
     * "You can see through entities / cutout blocks" is an alpha question: the
     * fragment shaders discard below alpha 0.1 and write whatever alpha is left,
     * so the answer depends on the alpha test flag, the blend flag, the colour
     * modulator and the colour mask at draw time - none of which is visible in
     * any existing counter. This prints all of them, tagged by draw kind, so
     * entity and block draws can be compared directly.
     */
    private static long lastDrawStateLog;

    public static void logDrawState(String tag) {
        if (!PROFILE_DUMP) {
            return;
        }

        final long now = System.currentTimeMillis();
        if (now - lastDrawStateLog < 1000L) {
            return;
        }
        lastDrawStateLog = now;

        final float[] c = com.yuhan123.vulkanmod.vulkan.VRenderSystem.getColor();
        VulkanMod.LOGGER.info(
                "[VKDRAW] {} color=({} {} {} {}) aTest={} blend={} cMask=0x{} cull={} dMask={} dTest={}",
                tag, c[0], c[1], c[2], c[3],
                com.yuhan123.vulkanmod.vulkan.VRenderSystem.alphaTest,
                com.yuhan123.vulkanmod.vulkan.shader.PipelineState.blendInfo.enabled,
                Integer.toHexString(com.yuhan123.vulkanmod.vulkan.VRenderSystem.getColorMask()),
                com.yuhan123.vulkanmod.vulkan.VRenderSystem.cull,
                com.yuhan123.vulkanmod.vulkan.VRenderSystem.depthMask,
                com.yuhan123.vulkanmod.vulkan.VRenderSystem.depthTest);
    }

    public static void onUniformBytes(int bytes) {
        if (ENABLED)
            uniformBytes += bytes;
    }

    /** Adds one GPU timestamp delta (nanoseconds) to the per-report accumulator. */
    public static void onGpuDrawNanos(long seg) {
        if (seg <= 0)
            return;
        gpuDrawTotalNanos += seg;
        gpuDrawCount++;
        if (seg > gpuDrawMaxNanos)
            gpuDrawMaxNanos = seg;
        for (int b = 0; b < GPU_DRAW_BUCKETS.length; b++) {
            if (seg <= GPU_DRAW_BUCKETS[b]) {
                gpuDrawHist[b]++;
                break;
            }
        }
    }

    public static void onGpuPassNanos(long nanos) {
        if (ENABLED)
            gpuPassNanos += nanos;
    }

    /**
     * Experiment B: accumulate one frame's per-render-pass GPU segment times.
     * {@code mainNanos}/{@code offNanos} are the GPU durations of the main
     * swapchain color pass(es) and the offscreen passes respectively;
     * {@code passCount} is the total number of render-pass segments and
     * {@code offCount} how many of those were offscreen. A large {@code offCount}
     * with a small average per pass is the signature of fixed per-pass overhead.
     */
    public static void onGpuPassBreakdown(long mainNanos, long offNanos, int passCount, int offCount) {
        if (ENABLED) {
            gpuMainNanos += mainNanos;
            gpuOffNanos += offNanos;
            gpuPassCount += passCount;
            gpuOffCount += offCount;
        }
    }

    public static void beginGameTick() {
        if (ENABLED)
            tickStart = System.nanoTime();
    }

    public static void endGameTick() {
        if (ENABLED)
            gameTickNanos += System.nanoTime() - tickStart;
    }

    private static void report() {
        final double frameMs = totalNanos / 1e6 / frames;
        final double fenceMs = fenceWaitNanos / 1e6 / frames;
        final double submitMs = submitNanos / 1e6 / frames;
        final double presentMs = presentNanos / 1e6 / frames;

        // Real CPU work: everything in the frame that is neither blocked on a
        // fence/image acquire nor blocked in vkQueuePresentKHR. submit is a
        // subset of this (it is the CPU cost of recording the submission).
        final double cpuMs = frameMs - fenceMs - presentMs;
        final double applyMs = shaderApplyNanos / 1e6 / frames;
        final double drawMs = drawRecordNanos / 1e6 / frames;
        final double gpuMs = gpuPassNanos / 1e6 / frames;
        final double tickMs = gameTickNanos / 1e6 / frames;

        // Experiment B: per-render-pass GPU split. gpuPass should equal
        // gpuMain + gpuOff; gpuPassN is the average number of render passes per
        // frame and gpuOffN how many of those were offscreen.
        final double gpuMainMs = gpuMainNanos / 1e6 / frames;
        final double gpuOffMs = gpuOffNanos / 1e6 / frames;
        final double gpuPassN = gpuPassCount / (double) frames;
        final double gpuOffN = gpuOffCount / (double) frames;

        VulkanMod.LOGGER.info(
                "[VKPROF] fps={} frame={}ms (cpu={} fence={} present={} submit={} gpuPass={}ms tick={}ms) apply={} aSkip={} draw={} draws={} binds={} descBind={} descSkip={} vbSkip={} vbB={} vbMs={} ibSkip={} descUpd={} mvp={} push={} pushSkip={} pDraw={} cDraw={} vbCopy={}MB ubo={}MB matOps={} mat={}ms dlReplay={} dlDraw={} dlMs={} dlApply={}ms camPre={} camWorld={} camPost={}ms lightmap={} pick={} pass={}ms mvpMs={} guard={}ms gpuMain={}ms gpuOff={}ms gpuPassN={} gpuOffN={} eDraw={} tDraw={} iWorld={} iOverlay={} iLmap={} iTex={} iLines={} iBlock={} iOther={}",
                format(1000.0 / frameMs, 1), format(frameMs, 2), format(cpuMs, 2), format(fenceMs, 2),
                format(presentMs, 2), format(submitMs, 2), format(gpuMs, 2), format(tickMs, 2),
                DETAILED_TIMING ? format(applyMs, 2) : "n/a", format(shaderApplyReuses / (double) frames, 0),
                DETAILED_TIMING ? format(drawMs, 2) : "n/a",
                format(draws / (double) frames, 0), format(pipelineBinds / (double) frames, 0),
                format(descriptorBinds / (double) frames, 0), format(descriptorBindSkips / (double) frames, 0),
                format(vertexBindSkips / (double) frames, 0), format(vertexBinds / (double) frames, 0),
                format(vertexBindNanos / 1e6 / frames, 3),
                format(indexBindSkips / (double) frames, 0),
                format(descriptorUpdates / (double) frames, 0),
                format(mvpRecalcs / (double) frames, 0), format(pushConstantCalls / (double) frames, 0),
                format(pushConstantSkips / (double) frames, 0),
                format(persistentDraws / (double) frames, 0), format(copiedDraws / (double) frames, 0),
                format(vertexBytesCopied / 1048576.0 / frames, 2),
                format(uniformBytes / 1048576.0 / frames, 2),
                format(matrixOps / (double) frames, 0),
                DETAILED_TIMING ? format(matrixNanos / 1e6 / frames, 2) : "n/a",
                format(displayListReplays / (double) frames, 0), format(displayListDraws / (double) frames, 0),
                DETAILED_TIMING ? format(displayListNanos / 1e6 / frames, 2) : "n/a",
                DETAILED_TIMING ? format(displayListApplyNanos / 1e6 / frames, 2) : "n/a",
                format(camBeforeWorldNanos / 1e6 / frames, 2), format(camWorldNanos / 1e6 / frames, 2),
                format(camAfterWorldNanos / 1e6 / frames, 2),
                format(lightmapNanos / 1e6 / frames, 2), format(mouseOverNanos / 1e6 / frames, 2),
                format(worldPassNanos / 1e6 / frames, 2),
                DETAILED_TIMING ? format(mvpRecalcNanos / 1e6 / frames, 2) : "n/a",
                DETAILED_TIMING ? format(reuseGuardNanos / 1e6 / frames, 2) : "n/a",
                format(gpuMainMs, 2), format(gpuOffMs, 2), format(gpuPassN, 1), format(gpuOffN, 1),
                format(entityPersistentDraws / (double) frames, 0), format(terrainPersistentDraws / (double) frames, 0),
                format(immWorld / (double) frames, 0), format(immOverlay / (double) frames, 0),
                format(immLmap / (double) frames, 0), format(immTex / (double) frames, 0),
                format(immLines / (double) frames, 0), format(immBlock / (double) frames, 0),
                format(immOther / (double) frames, 0));

        // Per-vkCmd-family CPU recording cost, on its own line so the positional
        // main report above is untouched. Format: Family=count/frameMs.
        final StringBuilder cmdLine = new StringBuilder(220);
        for (int i = 0; i < CMD_COUNT; i++) {
            if (cmdCount[i] == 0)
                continue;
            if (cmdLine.length() > 0)
                cmdLine.append(' ');
            cmdLine.append(CMD_NAMES[i]).append('=')
                   .append(format(cmdCount[i] / (double) frames, 0))
                   .append('/').append(format(cmdNanos[i] / 1e6 / frames, 4)).append("ms");
        }
        VulkanMod.LOGGER.info("[VKPROF] cmd {}", cmdLine.length() == 0 ? "none" : cmdLine);

        // Per-draw GPU timing histogram: how many draws fall in each GPU-time
        // bucket (microseconds). Answers whether the GPU pass is many tiny draws
        // or a few heavy ones. Only emitted when VULKANMOD_GPUDRAWTIMING=1.
        if (GPU_DRAW_TIMING && gpuDrawCount > 0) {
            final StringBuilder h = new StringBuilder(160);
            for (int b = 0; b < gpuDrawHist.length; b++) {
                if (b > 0)
                    h.append(' ');
                h.append(format(gpuDrawHist[b] / (double) frames, 0));
            }
            VulkanMod.LOGGER.info(
                "[VKPROF] gpudraw total={}ms avg={}ms max={}ms n={} | hist(us)<0.1/0.25/0.5/1/2/5/10/20/50/inf={}",
                format(gpuDrawTotalNanos / 1e6 / frames, 3),
                format(gpuDrawTotalNanos / 1e6 / gpuDrawCount, 4),
                format(gpuDrawMaxNanos / 1e6, 4),
                format(gpuDrawCount / (double) frames, 0),
                h);
        }



        // Matrix ops split by kind, on their own line so the main report's field
        // list (which the A/B harnesses parse positionally by name) is unchanged.
        final StringBuilder kinds = new StringBuilder(96);
        for (int i = 0; i < MAT_KINDS.length; i++) {
            if (i > 0)
                kinds.append(' ');
            kinds.append(MAT_KINDS[i]).append('=').append(format(matByKind[i] / (double) frames, 0));
        }
        VulkanMod.LOGGER.info(
                "[VKPROF] matByKind {} | xfFused={} xfMiss={} xfRead={} xfMis={} xfDiff={} xfStale={}",
                kinds, format(chunkXfFused / (double) frames, 0), format(chunkXfCacheMiss / (double) frames, 0),
                format(chunkXfStackRead / (double) frames, 0),
                format(chunkXfMismatch / (double) frames, 0), String.format("%.2e", chunkXfMaxDiff),
                format(chunkXfEpochStale / (double) frames, 0));

        // Texture uploads, on their own line for the same reason: the main
        // report is parsed positionally by the A/B harnesses.
        VulkanMod.LOGGER.info(
                "[VKPROF] texUp n={} kB={} ms={} swzN={} swzKB={} swzMs={} maxUs={} stgMs={} barMs={} cpMs={} "
                        + "cpSetupMs={} cpCmdMs={}",
                format(texUploads / (double) frames, 1),
                format(texUploadBytes / 1024.0 / frames, 1),
                format(texUploadNanos / 1e6 / frames, 3),
                format(texSwizzleBytes > 0 ? texUploads / (double) frames : 0, 1),
                format(texSwizzleBytes / 1024.0 / frames, 1),
                format(texSwizzleNanos / 1e6 / frames, 3),
                format(texUploadMaxNanos / 1000.0, 1),
                format(texStageNanos[TEX_STG] / 1e6 / frames, 3),
                format(texStageNanos[TEX_BARRIER] / 1e6 / frames, 3),
                format(texStageNanos[TEX_COPY] / 1e6 / frames, 3),
                format(texStageNanos[TEX_CP_SETUP] / 1e6 / frames, 3),
                format(texStageNanos[TEX_CP_CMD] / 1e6 / frames, 3));

        // Rejection split for the apply-reuse guard, plus the count of reuses the
        // generalised gate unlocked.
        VulkanMod.LOGGER.info(
                "[VKPROF] rej serial={} epoch={} bind={} uniform={} boundPipe={} state={} nonBlock={}",
                format(reuseRejects[REJ_SERIAL] / (double) frames, 0),
                format(reuseRejects[REJ_EPOCH] / (double) frames, 0),
                format(reuseRejects[REJ_BIND] / (double) frames, 0),
                format(reuseRejects[REJ_UNIFORM] / (double) frames, 0),
                format(reuseRejects[REJ_BOUNDPIPE] / (double) frames, 0),
                format(reuseRejects[REJ_STATE] / (double) frames, 0),
                format(reuseNonBlock / (double) frames, 0));

        // Per-section terrain breakdown, on its own line so the main report's
        // positional field list is unchanged. `misc` is the part of apply() that
        // neither mvp, guard, push nor the reuse bookkeeping accounts for - i.e.
        // the cost of entering the method rather than of doing anything in it.
        final long applyCalls = applyFastCalls + applyFullCalls;
        final double applySplitMiscMs = (shaderApplyNanos - mvpRecalcNanos - reuseGuardNanos - aPushNanos - aBookNanos)
                                        / 1e6 / frames;
        VulkanMod.LOGGER.info(
                "[VKPROF] applysplit calls={} pushMs={} bookMs={} miscMs={} qIndMs={} qIndN={} preMs={} preN={} "
                        + "psHit={} psDerive={} ns/apply={} ns/mat={} ns/guard={}",
                format(applyCalls / (double) frames, 0),
                format(aPushNanos / 1e6 / frames, 3),
                format(aBookNanos / 1e6 / frames, 3),
                format(applySplitMiscMs, 3),
                format(queueIndirectNanos / 1e6 / frames, 3),
                format(queueIndirectCalls / (double) frames, 0),
                format(drawPreambleNanos / 1e6 / frames, 3),
                format(drawPreambleCalls / (double) frames, 0),
                format(psHits / (double) frames, 0),
                format(psDerives / (double) frames, 0),
                format(applyCalls > 0 ? shaderApplyNanos / (double) applyCalls : 0, 0),
                format(matrixOps > 0 ? matrixNanos / (double) matrixOps : 0, 0),
                format(applyCalls > 0 ? reuseGuardNanos / (double) applyCalls : 0, 0));

        // PipelineState input-snapshot memo (pass 10), on its own line so the
        // applysplit field list the harnesses read by name is untouched.
        // `psMemo` must track `psHit + psDerive`; `psBad` is verify-mode only and
        // must be 0.
        VulkanMod.LOGGER.info(
                "[VKPROF] psmemo psMemo={} psBad={} psHit={} psDerive={}",
                format(psMemoHits / (double) frames, 0),
                format(psMemoMismatches / (double) frames, 0),
                format(psHits / (double) frames, 0),
                format(psDerives / (double) frames, 0));

        // Paired within-frame A/B of the memo (VULKANMOD_MEMOAB=1). `nsA` is the
        // memo arm, `nsB` the full-encode arm; both are per-call means over the
        // same frames, so `dNs` is the memo's cost delta with the scene cancelled.
        // Meaningless unless MEMO_AB is on - armA/armB are then 0.
        VulkanMod.LOGGER.info(
                "[VKPROF] memoab armA={} nsA={} armB={} nsB={} dNs={}",
                format(abMemoCalls / (double) frames, 0),
                format(abMemoCalls > 0 ? abMemoNanos / (double) abMemoCalls : 0, 0),
                format(abPlainCalls / (double) frames, 0),
                format(abPlainCalls > 0 ? abPlainNanos / (double) abPlainCalls : 0, 0),
                format(abMemoCalls > 0 && abPlainCalls > 0
                       ? abMemoNanos / (double) abMemoCalls - abPlainNanos / (double) abPlainCalls : 0, 0));

        // Reuse path vs full path, per call. The two have opposite fixes, so the
        // per-call figures matter more than the per-frame totals.
        VulkanMod.LOGGER.info(
                "[VKPROF] applypath fastN={} fastNs={} fullN={} fullNs={} fastMs={} fullMs={}",
                format(applyFastCalls / (double) frames, 0),
                format(applyFastCalls > 0 ? applyFastNanos / (double) applyFastCalls : 0, 0),
                format(applyFullCalls / (double) frames, 0),
                format(applyFullCalls > 0 ? applyFullNanos / (double) applyFullCalls : 0, 0),
                format(applyFastNanos / 1e6 / frames, 3),
                format(applyFullNanos / 1e6 / frames, 3));

        // applyFull() segment split (pass 11), plus the flushMVP call-boundary
        // cost that `miscMs` has been absorbing. `flushMs` minus the main line's
        // `mvpMs` is the entry cost of apply -> flushMVP -> calculateMVP plus the
        // interface dispatch into the section-MVP provider.
        VulkanMod.LOGGER.info(
                "[VKPROF] fullsplit n={} samplersMs={} useProgramMs={} bindMs={} publishMs={} otherMs={} "
                        + "flushMs={} flushNs={} bindGfxMs={} bindTexMs={} bindUboMs={} "
                        + "pushMs={} descMs={} descUpdMs={} descBindMs={}",
                format(applyFullCalls / (double) frames, 0),
                format(fullSegmentNanos[FULL_SAMPLERS] / 1e6 / frames, 3),
                format(fullSegmentNanos[FULL_USE_PROGRAM] / 1e6 / frames, 3),
                format(fullSegmentNanos[FULL_BIND_PIPELINE] / 1e6 / frames, 3),
                format(fullSegmentNanos[FULL_PUBLISH] / 1e6 / frames, 3),
                format((applyFullNanos - fullSegmentNanos[FULL_SAMPLERS] - fullSegmentNanos[FULL_USE_PROGRAM]
                        - fullSegmentNanos[FULL_BIND_PIPELINE] - fullSegmentNanos[FULL_PUBLISH]) / 1e6 / frames, 3),
                format(flushMvpNanos / 1e6 / frames, 3),
                format(applyCalls > 0 ? flushMvpNanos / (double) applyCalls : 0, 0),
                format(fullSegmentNanos[FULL_BIND_GFX] / 1e6 / frames, 3),
                format(fullSegmentNanos[FULL_BIND_TEX] / 1e6 / frames, 3),
                format(fullSegmentNanos[FULL_BIND_UBO] / 1e6 / frames, 3),
                format(fullSegmentNanos[FULL_PUSH] / 1e6 / frames, 3),
                format(fullSegmentNanos[FULL_DESC] / 1e6 / frames, 3),
                format(fullSegmentNanos[FULL_DESC_UPD] / 1e6 / frames, 3),
                format(fullSegmentNanos[FULL_DESC_BIND] / 1e6 / frames, 3));

        // Paired within-frame A/B (pass 11). armOn is the arm on which the flag
        // behaves as it does by default; both per-call means are taken over the
        // SAME frames, so dNs has the scene cancelled out. All zero unless
        // VULKANMOD_AB names a flag.
        VulkanMod.LOGGER.info(
                "[VKPROF] pab flag={} armOn={} nsOn={} armOff={} nsOff={} dNs={} "
                        + "matOn={} nsMatOn={} matOff={} nsMatOff={} dMatNs={} "
                        + "bfsOn={} nsBfsOn={} bfsOff={} nsBfsOff={} dBfsNs={} "
                        + "frOn={} nsFrOn={} frOff={} nsFrOff={} dFrNs={} "
                        + "mkOn={} nsMkOn={} mkOff={} nsMkOff={} dMkNs={} "
                        + "e1On={} nsE1On={} e1Off={} nsE1Off={} dE1Ns={} "
                        + "e2On={} nsE2On={} e2Off={} nsE2Off={} dE2Ns={} "
                        + "eiOn={} nsEiOn={} eiOff={} nsEiOff={} dEiNs={} "
                        + "eiMs={} eiP0={} eiN={} eiBlk={} eiBig={} "
                        + "flOn={} nsFlOn={} flOff={} nsFlOff={} dFlNs={} "
                        + "ftOn={} nsFtOn={} ftOff={} nsFtOff={} dFtNs={} "
                        + "tileOn={} nsTileOn={} tileOff={} nsTileOff={} dTileNs={}",
                PairedAB.FLAG == null ? "-" : PairedAB.FLAG,
                format(abOnCalls / (double) frames, 0),
                format(abOnCalls > 0 ? abOnNanos / (double) abOnCalls : 0, 0),
                format(abOffCalls / (double) frames, 0),
                format(abOffCalls > 0 ? abOffNanos / (double) abOffCalls : 0, 0),
                format(abOnCalls > 0 && abOffCalls > 0
                       ? abOnNanos / (double) abOnCalls - abOffNanos / (double) abOffCalls : 0, 0),
                format(abMatOnCalls / (double) frames, 0),
                format(abMatOnCalls > 0 ? abMatOnNanos / (double) abMatOnCalls : 0, 0),
                format(abMatOffCalls / (double) frames, 0),
                format(abMatOffCalls > 0 ? abMatOffNanos / (double) abMatOffCalls : 0, 0),
                format(abMatOnCalls > 0 && abMatOffCalls > 0
                       ? abMatOnNanos / (double) abMatOnCalls - abMatOffNanos / (double) abMatOffCalls : 0, 0),
                format(abBfsOnCalls / (double) frames, 0),
                format(abBfsOnCalls > 0 ? abBfsOnNanos / (double) abBfsOnCalls : 0, 0),
                format(abBfsOffCalls / (double) frames, 0),
                format(abBfsOffCalls > 0 ? abBfsOffNanos / (double) abBfsOffCalls : 0, 0),
                format(abBfsOnCalls > 0 && abBfsOffCalls > 0
                       ? abBfsOnNanos / (double) abBfsOnCalls - abBfsOffNanos / (double) abBfsOffCalls : 0, 0),
                format(abFrOnCalls / (double) frames, 0),
                format(abFrOnCalls > 0 ? abFrOnNanos / (double) abFrOnCalls : 0, 0),
                format(abFrOffCalls / (double) frames, 0),
                format(abFrOffCalls > 0 ? abFrOffNanos / (double) abFrOffCalls : 0, 0),
                format(abFrOnCalls > 0 && abFrOffCalls > 0
                       ? abFrOnNanos / (double) abFrOnCalls - abFrOffNanos / (double) abFrOffCalls : 0, 0),
                format(abMkOnCalls / (double) frames, 0),
                format(abMkOnCalls > 0 ? abMkOnNanos / (double) abMkOnCalls : 0, 0),
                format(abMkOffCalls / (double) frames, 0),
                format(abMkOffCalls > 0 ? abMkOffNanos / (double) abMkOffCalls : 0, 0),
                format(abMkOnCalls > 0 && abMkOffCalls > 0
                       ? abMkOnNanos / (double) abMkOnCalls - abMkOffNanos / (double) abMkOffCalls : 0, 0),
                format(abE1OnCalls / (double) frames, 0),
                format(abE1OnCalls > 0 ? abE1OnNanos / (double) abE1OnCalls : 0, 0),
                format(abE1OffCalls / (double) frames, 0),
                format(abE1OffCalls > 0 ? abE1OffNanos / (double) abE1OffCalls : 0, 0),
                format(abE1OnCalls > 0 && abE1OffCalls > 0
                       ? abE1OnNanos / (double) abE1OnCalls - abE1OffNanos / (double) abE1OffCalls : 0, 0),
                format(abE2OnCalls / (double) frames, 2),
                format(abE2OnCalls > 0 ? abE2OnNanos / (double) abE2OnCalls : 0, 0),
                format(abE2OffCalls / (double) frames, 2),
                format(abE2OffCalls > 0 ? abE2OffNanos / (double) abE2OffCalls : 0, 0),
                format(abE2OnCalls > 0 && abE2OffCalls > 0
                       ? abE2OnNanos / (double) abE2OnCalls - abE2OffNanos / (double) abE2OffCalls : 0, 0),
                format(abEiOnCalls / (double) frames, 0),
                format(abEiOnCalls > 0 ? abEiOnNanos / (double) abEiOnCalls : 0, 0),
                format(abEiOffCalls / (double) frames, 0),
                format(abEiOffCalls > 0 ? abEiOffNanos / (double) abEiOffCalls : 0, 0),
                format(abEiOnCalls > 0 && abEiOffCalls > 0
                       ? abEiOnNanos / (double) abEiOnCalls - abEiOffNanos / (double) abEiOffCalls : 0, 0),
                format((abEiOnNanos + abEiOffNanos) / 1e6 / frames, 4),
                format(entP0LoopNanos / 1e6 / frames, 4),
                format((abEiOnCalls + abEiOffCalls) / (double) frames, 1),
                format(abEiBlocks / (double) frames, 1),
                format(abEiBig, 0),
                format(abFlOnCalls / (double) frames, 0),
                format(abFlOnCalls > 0 ? abFlOnNanos / (double) abFlOnCalls : 0, 0),
                format(abFlOffCalls / (double) frames, 0),
                format(abFlOffCalls > 0 ? abFlOffNanos / (double) abFlOffCalls : 0, 0),
                format(abFlOnCalls > 0 && abFlOffCalls > 0
                       ? abFlOnNanos / (double) abFlOnCalls - abFlOffNanos / (double) abFlOffCalls : 0, 0),
                format(abFtOnCalls / (double) frames, 0),
                format(abFtOnCalls > 0 ? abFtOnNanos / (double) abFtOnCalls : 0, 0),
                format(abFtOffCalls / (double) frames, 0),
                format(abFtOffCalls > 0 ? abFtOffNanos / (double) abFtOffCalls : 0, 0),
                format(abFtOnCalls > 0 && abFtOffCalls > 0
                       ? abFtOnNanos / (double) abFtOnCalls - abFtOffNanos / (double) abFtOffCalls : 0, 0),
                format(abTileOnCalls / (double) frames, 2),
                format(abTileOnCalls > 0 ? abTileOnNanos / (double) abTileOnCalls : 0, 0),
                format(abTileOffCalls / (double) frames, 2),
                format(abTileOffCalls > 0 ? abTileOffNanos / (double) abTileOffCalls : 0, 0),
                format(abTileOnCalls > 0 && abTileOffCalls > 0
                       ? abTileOnNanos / (double) abTileOnCalls - abTileOffNanos / (double) abTileOffCalls : 0, 0));

        // The visible-chunk BFS on its own line (pass 13). `ms` is the whole
        // `iteration` section; `probes` is the neighbour-probe count, six per
        // polled chunk. Together they give the per-probe cost, which is the
        // number the rewrite has to beat. `fastN` is how many probes took the
        // arithmetic path and `bad` is the verify-mode disagreement count
        // (must be 0).
        VulkanMod.LOGGER.info(
                "[VKPROF] bfs ms={} probes={} fastN={} bad={}",
                format(bfsNanos / 1e6 / frames, 3),
                format(bfsProbes / (double) frames, 2),
                format(bfsFastCalls / (double) frames, 2),
                format(bfsBad / (double) frames, 2));

        // The BFS loop body, split by term (pass 14). Counts are complete;
        // the `Ns` columns are means over the 1-in-32 sampled calls, so they
        // are per-call costs and multiply out against the counts to a per-frame
        // total that can be checked against the `bfs` line above. `sfTrue` is
        // how many setFrameIndex calls were the first touch of that chunk this
        // frame, i.e. how many reached the frustum test - which is why `frN`
        // and `sfTrue` are expected to be equal, and a disagreement between
        // them means the loop's short-circuit order is not what the reading
        // assumes.
        VulkanMod.LOGGER.info(
                "[VKPROF] bfsbody polls={} sfN={} sfTrue={} frN={} viN={} oppN={} "
                        + "pNs={} sfNs={} frNs={} viNs={} s={}/{}/{}/{} frBad={}",
                format(bfsPolls / (double) frames, 2),
                format(bfsSetFrameCalls / (double) frames, 2),
                format(bfsSetFrameTrue / (double) frames, 2),
                format(bfsFrustumCalls / (double) frames, 2),
                format(bfsVisibleCalls / (double) frames, 2),
                format(bfsOppositeCalls / (double) frames, 2),
                format(bfsProbeSamples > 0 ? bfsProbeNanos / (double) bfsProbeSamples : 0, 0),
                format(bfsSetFrameSamples > 0 ? bfsSetFrameNanos / (double) bfsSetFrameSamples : 0, 0),
                format(bfsFrustumSamples > 0 ? bfsFrustumNanos / (double) bfsFrustumSamples : 0, 0),
                format(bfsVisibleSamples > 0 ? bfsVisibleNanos / (double) bfsVisibleSamples : 0, 0),
                bfsProbeSamples, bfsSetFrameSamples, bfsFrustumSamples, bfsVisibleSamples,
                format(bfsFrustumBad / (double) frames, 2));

        // The term-1 hoist (pass 15). `polls` is the same quantity as the bfsbody
        // line's; `masked` is how many of those polls had at least one facing
        // removed from the loop, and `skipN` is the total number of loop
        // iterations deleted per frame - the number the paired per-poll figure
        // multiplies against. `bad` is the verify-mode disagreement count and must
        // be 0; a non-zero value means the mask removed a facing term 1 would have
        // kept, which is holes in the world.
        VulkanMod.LOGGER.info(
                "[VKPROF] bfsmask polls={} masked={} skipN={} bad={}",
                format(bfsPolls / (double) frames, 2),
                format(bfsMaskPolls / (double) frames, 2),
                format(bfsMaskSkipped / (double) frames, 2),
                format(bfsMaskBad / (double) frames, 2));

        // The BFS term-2 visibility row (pass 25). `calls` is how often applyTerm2
        // reached its decision; `row`/`loop` split those by which path answered
        // them, and they must sum to `calls`. Attach checks, all of which read a
        // known non-zero on a healthy run:
        //   * `bits`  - the SetVisibility mirror has ever been maintained. A LATCH,
        //               not a per-frame rate: rebuilds stop once the world settles,
        //               so a healthy run reads 0/frame in every settled window. As a
        //               per-frame number it is a plausible zero that reads as "the
        //               hooks did not attach"; as a latch it reads >= 1 whenever they
        //               did.
        //   * `vis`   - isVisible calls on the per-facing path; must reproduce pass
        //               14's independently measured bfsbody `viN` (~2910/frame). It
        //               is 0 by construction in verify mode, where the row answers
        //               every call.
        //   * `allVis`- rows that came back all-six. A 0 would mean no section is
        //               ever "fully visible", which contradicts computeVisibility's
        //               setAllVisible(true) branch for solid sections.
        // `bad` is the BFS_ROW_VERIFY disagreement count and must be 0; a non-zero
        // value means the mirror is stale and chunks are missing from the world.
        VulkanMod.LOGGER.info(
                "[VKPROF] bfsrow on={} v={} calls={} row={} loop={} vis={} allVis={} bits={} bad={}",
                BFS_ROW_ON,
                BFS_ROW_VERIFY_ON,
                format(bfsRowCalls / (double) frames, 1),
                format(bfsRowHits / (double) frames, 1),
                format((bfsRowCalls - bfsRowHits) / (double) frames, 1),
                format(bfsRowVisCalls / (double) frames, 1),
                format(bfsRowAllVisible / (double) frames, 1),
                bfsRowMirrorWrites,
                format(bfsRowBad / (double) frames, 2));

        // Pass 26. Three intervals per sampled poll, in ns, means over samples:
        //   q    = queue.poll()                       (the deque, and nothing else)
        //   pro  = mask + term 2 + renderInfos.add    (the per-poll half)
        //   loop = the facing loop + enqueue          (the per-iteration half)
        //
        // `s` is the attach check: sampling is 1-in-8, so `s` must be ~polls/8
        // (111 at 892 polls). A row reading s=0 is not "no effect", it is "no
        // treatment". `sum` is ONE WHOLE POLL: it must close against the section
        // timer's own per-poll share (bfs_ms * 1000 / polls), which is the
        // cleanest absolute instrument in the project - two clock reads a frame
        // around the exact loop. If sum overshoots that, the sampling is
        // perturbing what it measures and only the RATIOS may be quoted.
        // `bad` counts intervals that were negative or longer than any poll
        // could be; must be 0.00, or a stamp was missed and the split is fiction.
        final double s = bfsPhaseSamples / (double) frames;
        final double tot = bfsPhasePollNs + bfsPhasePrologueNs + bfsPhaseLoopNs;
        VulkanMod.LOGGER.info(
                "[VKPROF] bfsphase on={} s={} q={} pro={} loop={} sum={} qPct={} proPct={} bad={}",
                BFS_PHASE_ON,
                format(s, 1),
                format(bfsPhaseSamples == 0 ? 0.0 : bfsPhasePollNs / (double) bfsPhaseSamples, 1),
                format(bfsPhaseSamples == 0 ? 0.0 : bfsPhasePrologueNs / (double) bfsPhaseSamples, 1),
                format(bfsPhaseSamples == 0 ? 0.0 : bfsPhaseLoopNs / (double) bfsPhaseSamples, 1),
                format(bfsPhaseSamples == 0 ? 0.0 : tot / (double) bfsPhaseSamples, 1),
                format(tot == 0 ? 0.0 : 100.0 * bfsPhasePollNs / tot, 1),
                format(tot == 0 ? 0.0 : 100.0 * bfsPhasePrologueNs / tot, 1),
                format(bfsPhaseBad / (double) frames, 2));

        // setupTerrain / getVisibleFacings (pass 12), on its own line for the
        // usual reason: the main report is parsed positionally by the harnesses.
        // vfCalls/bfs are per-frame rates, so they are printed to 2 decimals: at
        // 0 decimals a call rate of 0.4/frame printed as "0", which reads as
        // "never called" and sent the first reading of this line down the wrong
        // branch entirely. bfs is the number of entries into the update block.
        VulkanMod.LOGGER.info(
                "[VKPROF] tsetup calls={} ms={} bfs={} vfCalls={} vfMs={} vfHit={} vfBad={} "
                        + "vfMemo={} vfMemoBad={} ri={}/{}",
                format(setupTerrainCalls / (double) frames, 2),
                format(setupTerrainNanos / 1e6 / frames, 3),
                format(setupTerrainBfsRuns / (double) frames, 2),
                format(visibleFacingsCalls / (double) frames, 2),
                format(visibleFacingsNanos / 1e6 / frames, 3),
                format(visibleFacingsHits / (double) frames, 2),
                format(visibleFacingsBad / (double) frames, 2),
                format(visibleFacingsMemoHits / (double) frames, 2),
                format(visibleFacingsMemoBad / (double) frames, 2),
                renderInfosLast,
                renderInfosMax);

        // The two sections the shim had never directly priced (pass 16), on their
        // own line for the usual reason. `ent ms` is the whole of
        // RenderGlobal.renderEntities, `total`/`rendered` are the entity counts
        // vanilla itself maintains (mean over calls, not over frames - the first
        // version divided the last call's value by the frame count), so the cost
        // can be told from the scene drift that has repeatedly been mistaken for a
        // mod cost. `ents`/`blocke` are vanilla's own two section boundaries inside
        // it. `fempty ms` is the per-frame scan inside renderBlockLayer; `ns/scan`
        // is its per-iteration cost and is the number a memo would have to beat.
        // `vtx` is the vertex count, which prices the BufferBuilder per-vertex path.
        // `p1` is the pass-1 reuse (pass 17). `short` is the iterations pass 1
        // answered from pass 0's record instead of probing a chunk, so it should
        // equal `iter`/2; `probes` must equal `iter` or the getChunk redirect did
        // not attach - a redirect that reads 0 is not evidence that the call is
        // free. `miss`/`extra`/`bad` are the ENTPASS1_VERIFY disagreement counts
        // and must all be 0.
        //
        // `ecalls`/`loopends`/`forge0`/`forge1` are the ATTACH CHECKS, and they
        // exist because every quantity above reads 0 when a hook silently fails
        // to attach - the pass-16 `chnkCalls=0` trap. Three hooks, three possible
        // culprits, so: `ecalls` must equal `iter` (the getEntityLists redirect),
        // `loopends` must equal `calls` (the release() redirect), and
        // `forge0`/`forge1` must be 1.00 each with `forgeOther` 0.00 (the pass
        // classifier). A run where `short=0` and `ecalls=0` is "not wired"; a run
        // where `short=0` and `ecalls=1784` is "wired and skipping nothing".
        final double pass0Calls = entPass0Calls > 0 ? entPass0Calls : 1;
        VulkanMod.LOGGER.info(
                "[VKPROF] ent ms={} ents={} blocke={} calls={} head={} wpass={} total={} rendered={} "
                        + "| fempty calls={} scanned={} added={} ms={} ns/scan={} | vtx={} "
                        + "| iter={} ns/iter={} outline={} "
                        + "| p1 short={} probes={} miss={} extra={} bad={} "
                        + "| p2 visited={} count={} bad={} "
                        + "| p2ms p0={} p1={} opens0={} opens1={} e2on={} e2v={} "
                        + "| p1x ecalls={} loopends={} forge0={} forge1={} forgeOther={}",
                format(renderEntitiesNanos / 1e6 / frames, 3),
                format(entityLoopNanos / 1e6 / frames, 3),
                format(blockEntityLoopNanos / 1e6 / frames, 3),
                format(renderEntitiesCalls / (double) frames, 2),
                format(entHeadCalls / (double) frames, 2),
                format(worldPassCalls / (double) frames, 2),
                format(entitiesTotal / pass0Calls, 1),
                format(entitiesRendered / pass0Calls, 1),
                format(filterEmptyCalls / (double) frames, 2),
                format(filterEmptyScanned / (double) frames, 0),
                format(filterEmptyAdded / (double) frames, 0),
                format(filterEmptyNanos / 1e6 / frames, 4),
                format(filterEmptyScanned > 0 ? filterEmptyNanos / (double) filterEmptyScanned : 0, 1),
                format(vertices / (double) frames, 0),
                format(entIterCalls / (double) frames, 0),
                format(entIterCalls > 0 ? entIterNanos / (double) entIterCalls : 0, 1),
                format(entOutlineCalls / (double) frames, 2),
                format(entP1Short / (double) frames, 0),
                format(entP1Probes / (double) frames, 0),
                format(entP1Miss, 0),
                format(entP1Extra, 0),
                format(entP1Bad, 0),
                format(entP2Visited / (double) frames, 1),
                format(entP2CountSum / (double) frames, 1),
                format(entP2Bad, 0),
                format(entP0LoopNanos / 1e6 / frames, 4),
                format(entP1LoopNanos / 1e6 / frames, 4),
                format(entP0Opens / (double) frames, 2),
                format(entP1Opens / (double) frames, 2),
                format(ENT_PASS2 ? 1 : 0, 0),
                format(ENT_PASS2_VERIFY ? 1 : 0, 0),
                format(entP1EmptyCalls / (double) frames, 0),
                format(entP1LoopEnds / (double) frames, 2),
                format(entP1Forge0 / (double) frames, 2),
                format(entP1Forge1 / (double) frames, 2),
                format(entP1ForgeOther / (double) frames, 2));

        // Pass 27, on its own line so the `ent` line's positional field list never
        // changes. `walk = loop - render` is the whole point: it is the addressable
        // half. `n` is the attach check - it must track `rendered` (7.4 at rd 12) and
        // must not read 0, because a redirect that fails to attach reads exactly like
        // a render that costs nothing. `open` counts the frames in which at least one
        // render was timed, so a run where the flag never went up is distinguishable
        // from a run where it went up and the renders were cheap.
        VulkanMod.LOGGER.info(
                "[VKPROF] entattr loop={} render={} walk={} n={} rendered={} ns/render={} open={}",
                format(entP0LoopNanos / 1e6 / frames, 4),
                format(entRenderNanos / 1e6 / frames, 4),
                format((entP0LoopNanos - entRenderNanos) / 1e6 / frames, 4),
                format(entRenderCalls / (double) frames, 2),
                format(entitiesRendered / pass0Calls, 1),
                format(entRenderCalls > 0 ? entRenderNanos / (double) entRenderCalls : 0, 0),
                format(entRenderFrames / (double) frames, 2));

        // Pass 28, on its own line so no earlier row's positional field list moves.
        //
        // `pre`/`do`/`sh` partition `render`; `dl` is nested inside `do` and `oth`
        // is the remainder `do - dl`. The three counts are the attach checks: `doN`
        // and `shN` must both track `n` (7-9 at rd 12), and `dlN` must be non-zero
        // and no larger than the frame-global `dlReplay` on the fps line. `ns/do`
        // and `ns/sh` are per-call means over the same window, so they carry the
        // scene with them and are the only figures here worth comparing across
        // passes.
        VulkanMod.LOGGER.info(
                "[VKPROF] entsplit pre={} do={} dl={} oth={} sh={} n={} doN={} dlN={} shN={} ns/do={} ns/sh={}",
                format((entRenderNanos - entDoNanos - entShNanos) / 1e6 / frames, 4),
                format(entDoNanos / 1e6 / frames, 4),
                format(entDlNanos / 1e6 / frames, 4),
                format((entDoNanos - entDlNanos) / 1e6 / frames, 4),
                format(entShNanos / 1e6 / frames, 4),
                format(entRenderCalls / (double) frames, 2),
                format(entDoCalls / (double) frames, 2),
                format(entDlCalls / (double) frames, 2),
                format(entShCalls / (double) frames, 2),
                format(entDoCalls > 0 ? entDoNanos / (double) entDoCalls : 0, 0),
                format(entShCalls > 0 ? entShNanos / (double) entShCalls : 0, 0));

        // Pass 28's second level, on its own line for the same reason. `mdl` is
        // nested inside `do`, so `oth2` is the `doRender` wrapper (`do - mdl`).
        //
        // `oth2` was first written as `do - dl - mdl` and that was wrong: `dl` is
        // *inside* `mdl`, so subtracting it twice made the field read negative
        // (-0.2642..-0.0863, med -0.1714) in `p28split4.log`. The wrapper is the
        // useful quantity, so the field is now `do - mdl`. **In `p28split4.log` the
        // `oth2` column predates this fix** - read it as `do - mdl` (0.19-0.37 ms),
        // recomputed from the two rows, not as printed.
        //
        // `mat` is the shim's own matrix path and reads 0.0000 when `dt=0` - which
        // means "not measured", not "free"; see the field block above.
        VulkanMod.LOGGER.info(
                "[VKPROF] entsplit2 mdl={} oth2={} mat={} n={} mdlN={} matN={} dt={}",
                format(entMdlNanos / 1e6 / frames, 4),
                format((entDoNanos - entMdlNanos) / 1e6 / frames, 4),
                format(entMatNanos / 1e6 / frames, 4),
                format(entRenderCalls / (double) frames, 2),
                format(entMdlCalls / (double) frames, 2),
                format(entMatOps / (double) frames, 1),
                format(DETAILED_TIMING ? 1 : 0, 0));

        // The pass-0 index gate on its own line (pass 19), so the `ent` line's
        // positional field list never changes.
        //
        // `on`/`v` self-report the configuration for the same reason `e2on`/`e2v`
        // do: a flag that fails to reach the JVM produces a perfectly plausible run,
        // and pass 18 shipped a correctness gate that never ran because of exactly
        // that. `builds` must be 1.00 - the index is rebuilt once per pass-0 call -
        // and `marks` is the marked-section count, which must be far below `iter`/2
        // for the gate to be worth anything. `skipped` is iterations answered from
        // the index (in verify mode, "would have been"); the attach check is that it
        // tracks `iter`/2 rather than reading 0, and that `miss` is 0.
        VulkanMod.LOGGER.info(
                "[VKPROF] entidx on={} v={} builds={} marks={} skipped={} checked={} "
                        + "miss={} extra={} dummy={}",
                format(ENT_IDX ? 1 : 0, 0),
                format(ENT_IDX_VERIFY ? 1 : 0, 0),
                format(entIdxBuilds / (double) frames, 2),
                format(entIdxMarks / (double) frames, 1),
                format(entIdxSkipped / (double) frames, 0),
                format(entIdxChecked, 0),
                format(entIdxMiss, 0),
                format(entIdxExtra, 0),
                format(entIdxDummies, 0));

        // The render-info record (pass 20), on its own line so the `ent` line's
        // field list never changes.
        //
        // THIS ROW IS INERT IN THE SHIPPED BUILD, and that is by design rather than
        // a fault. The record's three entry points are removed (pass 23, re-confirmed
        // pass 24 - see the javadoc on FLMASK), so `on=0` and every field below reads
        // 0. An all-zero row here means "the instrument is unplugged", NOT "the
        // instrument ran and found nothing" - which is the distinction rule 9 exists
        // for, and the reason this paragraph is here rather than in REFERENCE.md.
        // The checks below therefore apply to a RE-STAGED gate run, not to a
        // benchmark log. Recipe: REFERENCE.md, "pass 24".
        //
        // Attach checks, in the order they would fail:
        //   `gets` must equal 4 x ri              - the List.get redirect attached
        //   `isl`  must equal `gets`               - the isLayerEmpty redirect attached
        //   `chunks` must equal `gets`             - the getCompiledChunk redirect attached
        //   `builds` must equal ri (once a frame)  - the build scan ran exactly once
        //   `hits` + `off` must equal 3 x ri       - scans 2-4 each consulted the record
        //   `hits`/`(hits+off)` must be ~0.5       - the A/B arms are balanced, not stuck
        //   `max` must be < ri                     - the index never ran away
        //   `bchunks`/`loops` must equal ri        - the tile cursor advanced once per entry
        //   `tbuild` must equal ri                 - pass 0 recorded every entry
        //   `bskip` + `bnon` + `offT` = ri         - every pass-1 entry was answered
        //   `bad`/`bbad` must be 0                 - verify-mode disagreements
        //   `chk` must be non-zero                 - and verify mode ACTUALLY RAN.
        //        Pass 24 added `chk` for the layer half because `bad=0` alone cannot
        //        be told from "verify never ran" - the tile half had `bchk` and the
        //        layer half did not. It read 3 x ri (2676) on the re-staged gate run,
        //        and the gate needed it twice: a backwards implication gave on=0 v=1,
        //        and an inverted bit polarity gave bad = 321120 (every call). It is 0
        //        outside verify mode, which is correct, not a failure.
        //   `bskip`/`bnon`/`tbuild` read 0 whenever TILEIDX is on (the default),
        //        because `entBlockTiles` returns from its TILE_TRACK block before
        //        reaching this record's tile branches. The tile half of the record
        //        is unreachable in the shipped configuration by design - pass 21's
        //        TILEIDX superseded it. Those zeroes are NOT an attach failure.
        // A run with `gets=0` is inert, not clean; a run with `hits=0` has a record
        // that is built and never read. `hits` counts the ON arm only - it is NOT
        // the 3 x ri of post-build scans, because the OFF arm's share is in `off`.
        VulkanMod.LOGGER.info(
                "[VKPROF] flmask on={} v={} gets={} isl={} chunks={} builds={} hits={} off={} "
                        + "offT={} idxBad={} max={} bad={} bchk={} bbad={} bchunks={} bskip={} "
                        + "tbuild={} bloops={} bnon={} chk={}",
                format(FLMASK ? 1 : 0, 0),
                format(FLMASK_VERIFY ? 1 : 0, 0),
                format(flGets / (double) frames, 0),
                format(flIsl / (double) frames, 0),
                format(flChunks / (double) frames, 0),
                format(flBuilds / (double) frames, 0),
                format(flHits / (double) frames, 0),
                format(flOff / (double) frames, 0),
                format(flOffT / (double) frames, 0),
                format(flIdxBad, 0),
                format(flMaxIdx, 0),
                format(flBad, 0),
                format(flBChecked, 0),
                format(flBBad, 0),
                format(flBChunks / (double) frames, 0),
                format(flBSkip / (double) frames, 0),
                format(flTilesBuilt / (double) frames, 0),
                format(flBLoops / (double) frames, 2),
                format(flBNonEmpty / (double) frames, 0),
                format(flChecked / (double) frames, 0));

        // The pass-1 tile-loop compaction (pass 21), on its own line for the same
        // reason the two above are: the `ent` and `flmask` lines' positional field
        // lists never change.
        //
        // Attach checks, in the order they would fail:
        //   `on`/`v` self-report the configuration - pass 18 shipped a correctness
        //        gate that never ran because the flag never reached the JVM
        //   `built` must equal ri (892)      - pass 0's tile loop ran and recorded
        //   `loops` must equal 1.00          - pass 1's tile iterator fired once
        //   `count` must be far below ri/2   - the record is selective, not a no-op
        //   `visited` must track `count`     - the iterator handed out what was
        //                                      recorded, and `bad` must be 0
        //   `chk`/`miss`/`extra` are verify-only; `miss` must be 0
        // A run with `built=0` is inert, not clean; a run with `count=0` is a record
        // that is built and never used.
        VulkanMod.LOGGER.info(
                "[VKPROF] tileidx on={} v={} built={} loops={} count={} visited={} bad={} "
                        + "chk={} miss={} extra={}",
                format(TILE_IDX ? 1 : 0, 0),
                format(TILE_IDX_VERIFY ? 1 : 0, 0),
                format(tileBuilt / (double) frames, 0),
                format(tileLoops / (double) frames, 2),
                format(tileCountSum / (double) frames, 1),
                format(tileVisited / (double) frames, 1),
                format(tileBad, 0),
                format(tileChecked, 0),
                format(tileMiss, 0),
                format(tileExtra, 0));

        reset();
    }

    private static String format(double v, int decimals) {
        return String.format("%." + decimals + "f", v);
    }

    private static void reset() {
        frames = 0;
        totalNanos = 0;
        fenceWaitNanos = 0;
        submitNanos = 0;
        presentNanos = 0;
        shaderApplyNanos = 0;
        shaderApplyReuses = 0;
        drawRecordNanos = 0;
        draws = 0;
        pipelineBinds = 0;
        descriptorUpdates = 0;
        descriptorBinds = 0;
        descriptorBindSkips = 0;
        vertexBindSkips = 0;
        vertexBinds = 0;
        vertexBindNanos = 0;
        indexBindSkips = 0;
        vertexBytesCopied = 0;
        uniformBytes = 0;
        mvpRecalcs = 0;
        pushConstantCalls = 0;
        pushConstantSkips = 0;
        persistentDraws = 0;
        terrainPersistentDraws = 0;
        entityPersistentDraws = 0;
        copiedDraws = 0;
        immWorld = 0;
        immOverlay = 0;
        immLmap = 0;
        immTex = 0;
        immLines = 0;
        immBlock = 0;
        immOther = 0;
        inEntityReplay = false;
        drawPhase = PHASE_WORLD;
        gpuPassNanos = 0;
        gpuMainNanos = 0;
        gpuOffNanos = 0;
        gpuPassCount = 0;
        gpuOffCount = 0;
        gpuDrawTotalNanos = 0;
        gpuDrawCount = 0;
        gpuDrawMaxNanos = 0;
        for (int b = 0; b < gpuDrawHist.length; b++)
            gpuDrawHist[b] = 0;
        gameTickNanos = 0;
        matrixOps = 0;
        matrixNanos = 0;
        displayListReplays = 0;
        displayListDraws = 0;
        displayListNanos = 0;
        displayListApplyNanos = 0;
        camBeforeWorldNanos = 0;
        camWorldNanos = 0;
        camAfterWorldNanos = 0;
        lightmapNanos = 0;
        mouseOverNanos = 0;
        worldPassNanos = 0;
        java.util.Arrays.fill(cmdNanos, 0);
        java.util.Arrays.fill(cmdCount, 0);
        mvpRecalcNanos = 0;
        reuseGuardNanos = 0;
        for (int i = 0; i < matByKind.length; i++)
            matByKind[i] = 0;
        chunkXfFused = 0;
        chunkXfCacheMiss = 0;
        chunkXfStackRead = 0;
        chunkXfEpochStale = 0;
        chunkXfMismatch = 0;
        chunkXfMaxDiff = 0;
        texUploads = 0;
        texUploadBytes = 0;
        texUploadNanos = 0;
        texSwizzleNanos = 0;
        texSwizzleBytes = 0;
        texUploadMaxNanos = 0;
        reuseNonBlock = 0;
        aPushNanos = 0;
        aBookNanos = 0;
        queueIndirectNanos = 0;
        queueIndirectCalls = 0;
        drawPreambleNanos = 0;
        drawPreambleCalls = 0;
        psHits = 0;
        psDerives = 0;
        psMemoHits = 0;
        psMemoMismatches = 0;
        abMemoNanos = 0;
        abMemoCalls = 0;
        abPlainNanos = 0;
        abPlainCalls = 0;
        applyFastNanos = 0;
        applyFastCalls = 0;
        applyFullNanos = 0;
        applyFullCalls = 0;
        flushMvpNanos = 0;
        abOnNanos = 0;
        abOnCalls = 0;
        abOffNanos = 0;
        abOffCalls = 0;
        abMatOnNanos = 0;
        abMatOffNanos = 0;
        abMatOnCalls = 0;
        abMatOffCalls = 0;
        // pass 12: the terrain-setup counters. These were added to the fields and
        // to the report but not to reset(), so the tsetup line divided a
        // run-cumulative total by a single window's frame count and reported
        // 52 ms of setupTerrain inside a 9.8 ms frame. The impossible number is
        // what gave it away; the lesson is that a new counter belongs in reset()
        // in the same edit that adds the report line.
        setupTerrainNanos = 0;
        setupTerrainCalls = 0;
        visibleFacingsNanos = 0;
        visibleFacingsCalls = 0;
        visibleFacingsHits = 0;
        visibleFacingsBad = 0;
        setupTerrainBfsRuns = 0;
        renderInfosMax = 0;
        visibleFacingsMemoHits = 0;
        visibleFacingsMemoBad = 0;
        // pass 13: the BFS counters, in reset() in the same edit that added their
        // report line - the pass-12 rule.
        bfsNanos = 0;
        bfsProbes = 0;
        bfsFastCalls = 0;
        bfsBad = 0;
        abBfsOnNanos = 0;
        abBfsOnCalls = 0;
        abBfsOffNanos = 0;
        abBfsOffCalls = 0;
        // pass 14: the BFS loop-body split, in reset() in the same edit that
        // added its report line - the pass-12 rule.
        bfsPolls = 0;
        bfsSetFrameCalls = 0;
        bfsSetFrameTrue = 0;
        bfsFrustumCalls = 0;
        bfsVisibleCalls = 0;
        bfsOppositeCalls = 0;
        bfsProbeNanos = 0;
        bfsProbeSamples = 0;
        bfsSetFrameNanos = 0;
        bfsSetFrameSamples = 0;
        bfsFrustumNanos = 0;
        bfsFrustumSamples = 0;
        bfsVisibleNanos = 0;
        bfsVisibleSamples = 0;
        bfsFrustumBad = 0;
        abFrOnNanos = 0;
        abFrOnCalls = 0;
        abFrOffNanos = 0;
        abFrOffCalls = 0;
        // pass 15: the term-1 hoist, in reset() in the same edit that added its
        // report line - the pass-12 rule.
        abMkOnNanos = 0;
        abMkOnCalls = 0;
        abMkOffNanos = 0;
        abMkOffCalls = 0;
        bfsMaskPolls = 0;
        bfsMaskSkipped = 0;
        bfsMaskBad = 0;
        // bfsRowMirrorWrites is intentionally NOT reset - it is a latch, see
        // onBfsRowMirrorWrite().
        bfsRowCalls = 0;
        bfsRowHits = 0;
        bfsRowVisCalls = 0;
        bfsRowAllVisible = 0;
        bfsRowBad = 0;
        // pass 26, in reset() in the same edit that added their report line.
        bfsPhaseSamples = 0;
        bfsPhasePollNs = 0;
        bfsPhasePrologueNs = 0;
        bfsPhaseLoopNs = 0;
        bfsPhaseBad = 0;
        // pass 16, in reset() in the same edit that added their report line.
        renderEntitiesNanos = 0;
        renderEntitiesCalls = 0;
        entitiesTotal = 0;
        entitiesRendered = 0;
        filterEmptyStart = 0;
        filterEmptyNanos = 0;
        filterEmptyCalls = 0;
        filterEmptyScanned = 0;
        filterEmptyAdded = 0;
        vertices = 0;
        entityLoopNanos = 0;
        blockEntityLoopNanos = 0;
        entIterStart = 0;
        entIterNanos = 0;
        entIterCalls = 0;
        entOutlineCalls = 0;
        entHeadCalls = 0;
        worldPassCalls = 0;
        // pass 17, in reset() in the same edit that added their report line. The
        // pass-1 record itself (entP1Empty/entP1Chunk/entP1Recorded) is NOT reset:
        // it is rebuilt from scratch by every pass-0 call, and clearing it here
        // would break a window boundary that happened to fall between the two
        // entity passes.
        entPass0Calls = 0;
        entP1Probes = 0;
        entP1Short = 0;
        entP1EmptyCalls = 0;
        entP1LoopEnds = 0;
        entP1Pass0Calls = 0;
        entP1Pass1Calls = 0;
        entP1Forge0 = 0;
        entP1Forge1 = 0;
        entP1ForgeOther = 0;
        entP1Miss = 0;
        entP1Extra = 0;
        entP1Bad = 0;
        abE1OnNanos = 0;
        abE1OnCalls = 0;
        abE1OffNanos = 0;
        abE1OffCalls = 0;
        entP2Visited = 0;
        entP2Bad = 0;
        entP2CountSum = 0;
        entP0LoopNanos = 0;
        entP0Opens = 0;
        entP1LoopNanos = 0;
        entP1Opens = 0;
        // pass 27, in reset() in the same edit that added their report line.
        entRenderNanos = 0;
        entRenderCalls = 0;
        entRenderFrames = 0;
        // pass 28, same rule.
        entDoNanos = 0;
        entDoCalls = 0;
        entShNanos = 0;
        entShCalls = 0;
        entDlNanos = 0;
        entDlCalls = 0;
        entMatNanos = 0;
        entMatOps = 0;
        entMdlNanos = 0;
        entMdlCalls = 0;
        abE2OnNanos = 0;
        abE2OnCalls = 0;
        abE2OffNanos = 0;
        abE2OffCalls = 0;
        // pass 19, in reset() in the same edit that added their report line.
        // `entIdxDummies` is deliberately NOT reset: it is a session counter whose
        // only job is to say the stand-in chunk exists, and resetting it would make
        // a healthy run report 0 after the first window.
        entIdxBuilds = 0;
        entIdxMarks = 0;
        entIdxSkipped = 0;
        entIdxChecked = 0;
        entIdxMiss = 0;
        entIdxExtra = 0;
        abEiOnNanos = 0;
        abEiOnCalls = 0;
        abEiOffNanos = 0;
        abEiOffCalls = 0;
        abEiBlocks = 0;
        abEiBig = 0;
        // Pass 20: the render-info record's counters. `flMaxIdx` is a high-water
        // mark, so it is reset with the rest rather than left to grow across the
        // run - a stale maximum would hide an index that only runs away once.
        flGets = 0;
        flChunks = 0;
        flIsl = 0;
        flBuilds = 0;
        flHits = 0;
        flOff = 0;
        flOffT = 0;
        flBad = 0;
        flChecked = 0;
        flIdxBad = 0;
        flMaxIdx = -1;
        flBChunks = 0;
        flBSkip = 0;
        flTilesBuilt = 0;
        flBChecked = 0;
        flBBad = 0;
        flBLoops = 0;
        flBNonEmpty = 0;
        abFlOnNanos = 0;
        abFlOnCalls = 0;
        abFlOffNanos = 0;
        abFlOffCalls = 0;
        abFtOnNanos = 0;
        abFtOnCalls = 0;
        abFtOffNanos = 0;
        abFtOffCalls = 0;
        // Pass 21: the tile-loop compaction's counters, in reset() in the same edit
        // that added their report line - the pass-12 rule. The record itself
        // (tileIdx/tileBits/tileCount/tileRecorded) is NOT reset: it is rebuilt from
        // scratch by every pass-0 call, and clearing it here would break a window
        // boundary that fell between the two entity passes.
        tileBuilt = 0;
        tileLoops = 0;
        tileCountSum = 0;
        tileVisited = 0;
        tileChecked = 0;
        tileMiss = 0;
        tileExtra = 0;
        tileBad = 0;
        abTileOnNanos = 0;
        abTileOnCalls = 0;
        abTileOffNanos = 0;
        abTileOffCalls = 0;
        java.util.Arrays.fill(fullSegmentNanos, 0L);
        java.util.Arrays.fill(texStageNanos, 0L);
        java.util.Arrays.fill(reuseRejects, 0L);
    }
}
