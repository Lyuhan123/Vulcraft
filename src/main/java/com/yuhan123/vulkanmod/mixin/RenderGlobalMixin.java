package com.yuhan123.vulkanmod.mixin;

import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import com.yuhan123.vulkanmod.render.util.FrustumFast;
import com.yuhan123.vulkanmod.render.util.PairedAB;
import com.yuhan123.vulkanmod.render.util.VisibleFacingsCache;
import com.yuhan123.vulkanmod.render.util.VisibilityRow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Attributes the {@code terrain_setup} slice of the vanilla frame.
 *
 * <p>The steady-state vanilla profiler dump (pass 12, after the run-average was
 * fixed to exclude the world-load ramp) put {@code terrain_setup} at ~12% of a
 * 7.5 ms frame, ~11 points of it inside {@code update} - and that is with a
 * <b>stationary camera</b>. For a stationary camera the whole expensive block
 * should be skipped, because {@code RenderGlobal.setupTerrain} only descends
 * into it when {@code displayListEntitiesDirty} is set.
 *
 * <p>So there are two questions and this mixin answers both:
 * <ol>
 *   <li><b>Does the block run at all, every frame?</b> {@code displayListEntitiesDirty}
 *       is re-armed by the tail of {@code setupTerrain} itself - any render-info
 *       chunk with {@code needsUpdate()}, or any chunk still in {@code chunksToUpdate},
 *       sets it true again - so a single stuck chunk makes the full breadth-first
 *       pass re-run forever. The {@code [VKDIAG]} line logs the flag on entry
 *       (i.e. the value that decides this frame), the pending rebuild queue depth,
 *       and how far the view entity moved since the previous call.</li>
 *   <li><b>What inside it costs the most?</b> The {@code iteration} section is the
 *       breadth-first pass; the rest is {@code getVisibleFacings}, which scans all
 *       4096 blocks of the eye's chunk section with {@code isOpaqueCube()} and then
 *       flood-fills a {@code VisGraph}. {@code [VKPROF] tsetup} reports calls and
 *       milliseconds for both.</li>
 * </ol>
 *
 * <p>{@code vfCalls} doubles as a branch detector: {@code getVisibleFacings} is only
 * reached on the {@code renderchunk != null} fast path, so {@code vfCalls ~= calls}
 * means the cheap path and {@code vfCalls == 0} means the fallback loop - which
 * probes every chunk in a {@code (2*renderDistance+1)^2} square - was taken.
 *
 * <p>This is instrumentation only. It deliberately measures before proposing a
 * cache: a memo of {@code getVisibleFacings} is a correctness risk (a stale entry
 * culls chunks that should be drawn, which shows up as holes in the world and not
 * as a wrong number in any harness here), so it is only worth taking if the scan
 * is actually a measurable share of the frame.
 */
@Mixin(RenderGlobal.class)
public class RenderGlobalMixin {

    @Shadow
    private boolean displayListEntitiesDirty;

    @Shadow
    private Set<RenderChunk> chunksToUpdate;

    @Shadow
    private ViewFrustum viewFrustum;

    @Shadow
    private int renderDistanceChunks;

    /**
     * Read only for {@code renderChunksMany}, which is the loop's {@code flag1}.
     * See {@link #vulkanmod$computeFaceMask}.
     */
    @Shadow
    private Minecraft mc;

    /** Raw type on purpose: the descriptor is what Mixin matches on. */
    @Shadow
    private List renderInfos;

    /**
     * Vanilla's own entity counters, read at the end of {@code renderEntities}.
     *
     * <p>They are the difference between "the entity path is expensive" and "this
     * bench save has an abnormal number of entities", which the project has
     * repeatedly confused: {@code entities} grew from 9.1% to 23.6% of the frame
     * between passes purely because the save accumulates them. Printing the count
     * next to the time is what makes the reading interpretable.
     */
    @Shadow
    private int countEntitiesTotal;

    @Shadow
    private int countEntitiesRendered;

    /** One {@code [VKDIAG]} line per second; the accumulation window. */
    @Unique
    private static long vulkanmod$diagWindowStart;

    @Unique
    private static int vulkanmod$diagCalls;

    @Unique
    private static double vulkanmod$diagPosDelta;

    @Unique
    private static double vulkanmod$prevPosX;

    @Unique
    private static double vulkanmod$prevPosY;

    @Unique
    private static double vulkanmod$prevPosZ;

    @Unique
    private static boolean vulkanmod$havePrevPos;

    /**
     * Set at HEAD when the memo matched, so the RETURN handler knows whether it is
     * looking at a verified hit. Only ever read/written on the client thread.
     */
    @Unique
    private boolean vulkanmod$memoHit;

    /**
     * Force the visible-chunk selection to run every frame, without touching the
     * scene.
     *
     * <p>The benchmark camera stands still, so {@code displayListEntitiesDirty}
     * trips on only ~15% of frames and the bench prices {@code terrain_setup} at
     * roughly a sixth of what a player actually pays. In real play the flag trips
     * on <b>every</b> frame: it is set by a strict compare against the previous
     * frame's position <i>and rotation</i>, and a player walking at 4.3 blocks/s
     * moves 0.033 blocks per frame - about 5&times;10^11 double ULPs at x=320, so
     * the compare cannot miss.
     *
     * <p>Setting the flag at HEAD reproduces that condition exactly while leaving
     * position and rotation alone, so the scene stays pinned and the render-info
     * list stays at its steady size. The alternative - nudging the view entity -
     * would trip the same compare but also move the frustum, changing the very
     * work being measured. {@code VULKANMOD_FORCE_DIRTY=1}.
     */
    @Unique
    private static final boolean FORCE_DIRTY =
            "1".equals(System.getenv("VULKANMOD_FORCE_DIRTY"));

    // ------------------------------------------------------------------
    // The visible-chunk BFS neighbour probe (pass 13)
    // ------------------------------------------------------------------

    /**
     * Arithmetic replacement for {@code getRenderChunkOffset} + the
     * {@code ViewFrustum.getRenderChunk} chain. Default ON; {@code =0} restores
     * the vanilla chain.
     *
     * <h3>What the chain costs, and why it is worth replacing</h3>
     *
     * At rd 12 the BFS polls ~890 render infos and probes six neighbours of each,
     * so {@code getRenderChunkOffset} runs ~5350 times per frame. Every one of
     * those calls spends:
     *
     * <ul>
     *   <li>{@code RenderChunk.getBlockPosOffset16} — one call, one array read;</li>
     *   <li>two {@code MathHelper.abs} range checks and a Y bounds check;</li>
     *   <li>{@code ViewFrustum.getRenderChunk} — one call, then <b>three</b>
     *       {@code MathHelper.intFloorDiv} calls and <b>two</b> {@code %} by a
     *       field, i.e. two real integer divisions.</li>
     * </ul>
     *
     * <p>None of that is needed. Every position involved is a multiple of 16
     * ({@code RenderChunk.setPosition} is only ever called with a multiple of 16,
     * and {@code mapEnumFacing} is that position moved by one 16-block step), so
     * {@code intFloorDiv(p, 16) == p >> 4} exactly.
     *
     * <h3>Correctness argument</h3>
     *
     * The rewrite reproduces the vanilla chain's <b>order</b> of tests, so a probe
     * that returns {@code null} returns null for the same reason:
     * <ol>
     *   <li>{@code |playerX - px| > rd*16} → null</li>
     *   <li>{@code py < 0 || py >= 256} → null</li>
     *   <li>{@code |playerZ - pz| > rd*16} → null</li>
     *   <li>{@code j < 0 || j >= countChunksY} → null</li>
     * </ol>
     *
     * <h3>The one thing that is easy to get wrong: chunk positions are window-local</h3>
     *
     * {@code ViewFrustum.updateChunkPositions} recentres the render-chunk window on
     * the player by <b>moving every RenderChunk</b>, and {@code getBaseCoordinate}
     * returns a value in a {@code (count*16 - 16)}-wide band that can sit anywhere -
     * at x = 320.5 with count 25 the X positions run 304..688, i.e. array indices
     * 19..43. So a chunk's {@code position >> 4} is <b>not</b> in
     * {@code [0, count)} and needs a real modulo. The first version of this rewrite
     * assumed the index was already in range and wrapped a one-slot step with a
     * compare; that is wrong for every chunk whose raw index is out of range, and
     * the BFS then prunes most of the world - the verify run read
     * {@code bfsBad=128}/frame with {@code ri} 59 instead of 892, and the screenshot
     * showed the terrain missing in large black patches.
     *
     * <p>The fix is to reduce the base index <b>once per chunk</b> in the memo and
     * only then use the one-slot wrap, which is valid from an index already in
     * {@code [0, count)}. That keeps the two integer divisions off the per-probe
     * path: 892 a frame instead of 10 700.
     *
     * <p>{@code VULKANMOD_BFS_VERIFY=1} runs both and counts disagreements
     * ({@code bfsBad}, must be 0).
     */
    @Unique
    private static final boolean BFS_FAST =
            !"0".equals(System.getenv("VULKANMOD_BFS_FAST"));

    /** Run the vanilla lookup as well and count disagreements. Correctness only. */
    @Unique
    private static final boolean BFS_VERIFY =
            "1".equals(System.getenv("VULKANMOD_BFS_VERIFY"));

    // ------------------------------------------------------------------
    // The BFS loop body (pass 14)
    // ------------------------------------------------------------------

    /**
     * Diagnostic switch for the BFS loop-body split ({@code [VKPROF] bfsbody}).
     * Default OFF, because every counter in that line is fed from a
     * <b>redirect handler</b> and a redirect is not free: it replaces an
     * inlinable call with a call to a method in another class that the JIT will
     * not inline into a 1556-byte {@code setupTerrain}. The loop makes ~15 200
     * such calls a frame, so the counters cost a real fraction of a percent and
     * are only worth having while a question is open.
     *
     * <p>The counts this instrument produced (rd 12, {@code FORCE_DIRTY=1}, per
     * frame) are recorded in {@code REFERENCE.md} and in the pass-14 report, so
     * the next pass can start from them instead of paying to re-measure.
     */
    @Unique
    private static final boolean BFS_BODY =
            "1".equals(System.getenv("VULKANMOD_BFS_BODY"));

    /**
     * Positive-vertex replacement for the {@code ICamera.isBoundingBoxInFrustum}
     * call that ends the BFS's five-term neighbour condition.
     *
     * <p><b>Default OFF — this was built, made correct, and measured as a
     * regression.</b> See {@link #vulkanmod$frustumRedirect} for the measurement.
     * {@code =1} re-enables it so the paired A/B can be re-run.
     *
     * <h3>What the vanilla test costs, and why replacing it looked right</h3>
     *
     * {@code Frustum} does <b>not</b> extend {@code ClippingHelper} in 1.12.2 -
     * it wraps one - so the test a chunk pays for is four calls deep before any
     * arithmetic happens, and the last of them,
     * {@code ClippingHelper.isBoxInFrustum}, has <b>48</b> {@code dot()} call
     * sites: eight corners for each of six planes.
     *
     * <h3>The replacement, and why it is exactly equivalent</h3>
     *
     * For a plane {@code (n, d)} the eight corner dot products are maximised at
     * one specific corner - the one that takes {@code max} on every axis where
     * the corresponding normal component is positive, {@code min} elsewhere. That
     * maximum is ≤ 0 <b>iff</b> none of the eight corners is positive, which is
     * precisely the condition under which the vanilla loop returns {@code false}
     * for that plane. So the test reduces to one dot product per plane, six in
     * total, with no interface dispatch and no calls.
     *
     * <p>It is not merely equivalent in exact arithmetic. Each corner's dot
     * product is evaluated left-to-right in the same order and at the same
     * precision as {@code ClippingHelper.dot} (the {@code float} plane entries
     * promote to {@code double} exactly as they do there), so the maximum
     * computed here is <b>bit-identical</b> to the maximum vanilla's eight-term
     * loop would compute. {@code VULKANMOD_BFS_FRUSTUM_VERIFY=1} checks that on
     * every call and counts disagreements in {@code frBad} - 0 in every window of
     * every run, so the equivalence is established.
     *
     * <h3>The measurement (pass 14) - {@code VULKANMOD_AB=FRUSTUM}, 60 s</h3>
     *
     * Paired within-frame, both arms on the same frames, arms balanced
     * <b>556/556</b> tests per report: {@code dFrNs} was <b>positive in all 26
     * settled reports</b> (+5 … +37, mean ≈ +21 ns/test). At 1112 frustum tests a
     * frame that is <b>+0.02 ms/frame</b> — i.e. the rewrite that removes four
     * call levels and up to 42 dot products is very slightly <em>slower</em>.
     *
     * <p>The reason is the short-circuit. Vanilla's eight-corner loop exits a
     * plane as soon as one corner is positive, and for a box that is inside the
     * frustum that is the first or second corner tested - so the loop averages
     * one to two dots per plane, not eight. The positive-vertex form always pays
     * six conditional-select chains plus six dots, and the four calls it removes
     * are cheap (~5 ns each). This is the same shape as pass 13's probe result:
     * the arithmetic that "obviously" dominates was already near-optimal.
     */
    @Unique
    private static final boolean BFS_FRUSTUM =
            "1".equals(System.getenv("VULKANMOD_BFS_FRUSTUM"));

    /** Run both frustum tests and count disagreements. Correctness only. */
    @Unique
    private static final boolean BFS_FRUSTUM_VERIFY =
            "1".equals(System.getenv("VULKANMOD_BFS_FRUSTUM_VERIFY"));

    // ------------------------------------------------------------------
    // The five-term conjunction, reordered (pass 15)
    // ------------------------------------------------------------------

    /**
     * Skip the neighbour probe entirely for the facings whose <b>first</b> term of
     * the five-term conjunction already decides the answer.
     *
     * <h3>The loop, and where the waste is</h3>
     *
     * <pre>{@code
     * while (!queue.isEmpty()) {
     *     info = queue.poll();
     *     renderInfos.add(info);
     *     for (EnumFacing f : EnumFacing.values()) {
     *         RenderChunk rc2 = this.getRenderChunkOffset(blockpos, rc3, f);   // <-- paid first
     *         if ((!flag1 || !info.hasDirection(f.getOpposite()))
     *             && (!flag1 || facing == null || rc3.getCompiledChunk().isVisible(...))
     *             && rc2 != null
     *             && rc2.setFrameIndex(frameCount)
     *             && camera.isBoundingBoxInFrustum(rc2.boundingBox)) { ... }
     *     }
     * }
     * }</pre>
     *
     * <p>The probe is a <b>separate statement</b>, so it is paid before any term of
     * the conjunction is evaluated. Term 1 is a pure bit test on the polled entry
     * and term 2 is a pure bit test on the parent chunk's compiled visibility, so
     * for the facings they reject the probe's result cannot matter: {@code &&} is
     * value-commutative and short-circuiting, and the conjunction is false either
     * way. Pass 14 measured the rejection funnel exactly — of 5352 probes a frame
     * (892 polls &times; 6 facings at rd 12), term 1 rejects <b>2442 (45.6 %)</b>
     * and term 2 a further <b>882</b>, so <b>62 %</b> of the probes are thrown away
     * by a bit test evaluated after the probe is paid for.
     *
     * <h3>How the reorder is expressed without an {@code @Overwrite}</h3>
     *
     * The loop iterates {@code EnumFacing.values()}, and that call is already
     * redirected (pass 14, to hoist the six-element array clone). Returning a
     * <b>shorter array</b> from that redirect removes the facings whose term 1 is
     * false from the iteration altogether — no probe call, no term-1 re-evaluation,
     * no {@code getOpposite}. Only 64 distinct arrays are possible (one per six-bit
     * mask), so they are built once and cached: <b>no allocation per poll</b>.
     *
     * <p>The mask is computed once per <b>poll</b> rather than once per facing, from
     * the entry's {@code setFacing} byte: {@code hasDirection(f)} is
     * {@code (setFacing & 1 << f.ordinal()) > 0}, so a 64-entry table maps
     * {@code setFacing} straight to the set of facings whose opposite bit is set.
     * That is one field read and one array read per poll, replacing six
     * {@code hasDirection} calls.
     *
     * <h3>Why this is exact, and what it cannot do</h3>
     *
     * Term 1 is {@code !flag1 || !hasDirection(f.getOpposite())}. The mask keeps a
     * facing iff that is true, so a removed facing is exactly one the conjunction
     * would have rejected — with the same entries enqueued in the same order, so
     * {@code renderInfos} is identical, not merely equivalent. {@code flag1} is
     * {@code mc.renderChunksMany}, which {@code setupTerrain} clears to false in one
     * place: inside {@code if (playerSpectator && isOpaqueCube(eye))}. The mask is
     * therefore only computed when {@code !playerSpectator}, where {@code flag1}
     * cannot have been changed.
     *
     * <p><b>Term 2 is deliberately not hoisted.</b> It would skip 882 more probes,
     * but a skip there returns {@code null}, the conjunction then reaches term 2 and
     * evaluates {@code isVisible} a <em>second</em> time before short-circuiting —
     * so the extra call plus the extra {@code getOpposite} costs about what the
     * probe did. Measured thinking, not a guess: term 2 is a three-call chain into
     * {@code BitSet.get}, which is the same order as the probe.
     *
     * <p>{@code VULKANMOD_BFS_MASK=0} restores the vanilla iteration.
     */
    @Unique
    private static final boolean BFS_MASK =
            !"0".equals(System.getenv("VULKANMOD_BFS_MASK"));

    /**
     * Recompute the mask the long way, with six real {@code hasDirection} calls,
     * and count disagreements in {@code bfsMaskBad} (must be 0). Correctness only:
     * the extra calls make the timing meaningless.
     */
    @Unique
    private static final boolean BFS_MASK_VERIFY =
            "1".equals(System.getenv("VULKANMOD_BFS_MASK_VERIFY"));

    /**
     * Pass 22: fold the conjunction's <b>second</b> term into the same keep mask
     * pass 15 builds from the first.
     *
     * <p>Term 2 is
     * {@code !flag1 || facing == null || rc3.getCompiledChunk().isVisible(facing.getOpposite(), f)}.
     * Pass 15 deliberately left it alone, and the reason it gave is correct
     * <em>for the shape it was considering</em>: making {@code getRenderChunkOffset}
     * return {@code null} for a term-2 rejection leaves the conjunction to reach
     * term 2 and evaluate {@code isVisible} a second time, so the extra
     * {@code isVisible} plus the extra {@code getOpposite} costs about what the
     * probe did.
     *
     * <p><b>A mask does not have that problem.</b> Both terms are properties of
     * the <em>polled entry</em> — term 1 of its {@code setFacing} byte, term 2 of
     * its {@code facing} and its own {@code CompiledChunk} — and neither depends on
     * the neighbour. So both can be evaluated once per poll, in the same place, and
     * the facings they reject can simply be left out of the array the loop
     * iterates: no probe, no second {@code isVisible}, no second
     * {@code getOpposite}. Pass 14's funnel says that removes the 882 probes a
     * frame that term 2 was throwing away after they had been paid for.
     *
     * <p>The fold is exact for the same reason pass 15's was: {@code &&} is
     * value-commutative and short-circuiting, a removed facing is one the
     * conjunction would have rejected, the surviving facings keep their relative
     * order, and {@code getRenderChunkOffset}/{@code isVisible} have no side
     * effects. The one side-effecting call in the chain,
     * {@code rc2.setFrameIndex(frameCount)}, sits <em>after</em> both terms, so
     * vanilla's short-circuit already skipped it for every facing this removes.
     *
     * <p>Gated on the same {@code flag1} condition as pass 15 — see
     * {@link #vulkanmod$computeFaceMask} — and additionally skipped when the entry
     * has no {@code facing} (the root entry), where term 2 is vacuously true.
     *
     * <p>{@code VULKANMOD_BFS_T2=0} restores the pass-15 behaviour exactly.
     */
    @Unique
    private static final boolean BFS_T2 =
            !"0".equals(System.getenv("VULKANMOD_BFS_T2"));

    /**
     * Recompute the two-term predicate the long way — six real
     * {@code hasDirection} calls plus six real {@code isVisible} calls — and count
     * disagreements in {@code bfsMaskBad} (must be 0). Correctness only: the extra
     * calls make the timing meaningless.
     */
    @Unique
    private static final boolean BFS_T2_VERIFY =
            "1".equals(System.getenv("VULKANMOD_BFS_T2_VERIFY"));

    /** All six facings, i.e. "term 1 rejected nothing". */
    @Unique
    private static final int FACE_MASK_ALL = 0x3F;

    /**
     * Pass 25: answer the conjunction's <b>second</b> term from one six-bit
     * visibility row instead of one {@code CompiledChunk.isVisible} call per
     * surviving facing.
     *
     * <p>{@code applyTerm2} evaluates term 2 for every facing term 1 kept —
     * ~2916 calls a frame at rd 12, from 892 polls — and each of those calls is a
     * three-level chain ({@code CompiledChunk.isVisible} →
     * {@code SetVisibility.isVisible} → {@code BitSet.get}). The whole 6×6
     * answer matrix is 36 bits, so a whole row is available in one arithmetic
     * step; see {@link com.yuhan123.vulkanmod.render.util.VisibilityRow}.
     *
     * <p>{@code VULKANMOD_BFS_ROW=0} restores the per-facing calls exactly.
     * {@code VULKANMOD_BFS_ROW_VERIFY=1} <b>implies</b> the row and additionally
     * recomputes it from the real {@code BitSet} on every call, counting
     * disagreements in {@code [VKPROF] bfsrow bad=} (must be 0) and returning the
     * real answer, so a wrong mirror cannot draw a wrong scene even in a
     * correctness run. The implication direction matters: writing it the other way
     * round is what gave pass 24 a gate that read {@code on=0 v=1} with every
     * counter at zero.
     */
    @Unique
    private static final boolean BFS_ROW =
            !"0".equals(System.getenv("VULKANMOD_BFS_ROW"))
            || "1".equals(System.getenv("VULKANMOD_BFS_ROW_VERIFY"));

    @Unique
    private static final boolean BFS_ROW_VERIFY =
            "1".equals(System.getenv("VULKANMOD_BFS_ROW_VERIFY"));

    /**
     * The arm of the {@code BFSVIS} paired A/B, published by the flip at the
     * {@code queue.poll} redirect. Static because that redirect handler is static;
     * {@code setupTerrain} runs on the client thread only and the flip immediately
     * precedes the poll that reads it. Starts {@code true} so the first sample of a
     * run is already a valid ON sample.
     */
    @Unique
    private static boolean vulkanmod$visArm = true;

    // ------------------------------------------------------------------
    // Pass 26: splitting one BFS poll into prologue and facing loop
    // ------------------------------------------------------------------

    /**
     * Bench-only. Split one BFS poll into its per-<b>poll</b> half and its
     * per-<b>iteration</b> half, sampled 1-in-8, and report both means on the
     * {@code bfsphase} row.
     *
     * <h3>Why this exists</h3>
     *
     * Three measurements cannot all describe the same 1.0 ms, and this is the
     * cheap way to find out which one is wrong:
     *
     * <ul>
     *   <li>{@code bfs} is <b>~1.0 ms/frame</b> over 892 polls (section timer),
     *       and the pass-25 poll-to-poll block agrees: 892 &times; 1090 ns =
     *       0.97 ms. Two instruments, same answer.</li>
     *   <li>Pass 13 measured the neighbour probe's <b>marginal</b> cost at
     *       <b>~15 ns</b> (an extra vanilla probe on every probe moved the
     *       window 7.5%). 2028 probes is therefore ~30 us - <b>3%</b> of the
     *       loop.</li>
     *   <li>Passes 15 and 22 removed <b>3324 of 5352</b> iterations (62%) and
     *       were paid ~330 us in total.</li>
     * </ul>
     *
     * <p>If the probe is 3% and the predicates are gone, the loop's cost is
     * <b>per-poll</b> work that nobody has ever looked at. If it is not, the
     * loop is per-iteration and 2028 iterations cost ~400 ns each, which no
     * instruction count explains. Either answer is worth having and neither is
     * currently known.
     *
     * <h3>How, without a new hook</h3>
     *
     * Both stamps land on redirects that already exist:
     *
     * <pre>{@code
     * capturePolledInfo entry   <-- STAMP A (prologue opens)
     *   queue.poll()
     *   computeFaceMask / applyTerm2
     * setupTerrain: renderInfos.add(info)
     * valuesRedirect entry      <-- STAMP B (prologue closes, loop opens)
     *   for (f : facings) { probe; setFrameIndex; frustum; enqueue }
     * capturePolledInfo entry   <-- STAMP A' (loop closes)
     * }</pre>
     *
     * <p>So <b>prologue</b> = poll + mask + term 2 + two field reads +
     * {@code renderInfos.add} (per-poll), and <b>loop</b> = the facing loop +
     * the enqueue + {@code queue.isEmpty()} (per-iteration). Two clock reads per
     * <em>sampled</em> poll; at 1-in-8 that is ~220 a frame, ~17 us, 0.2% of the
     * frame.
     *
     * <h3>The closure, which is the whole point</h3>
     *
     * {@code pro + loop} is <b>one complete poll</b>, so it must reproduce the
     * whole-poll figure the pass-25 paired row measured independently
     * (1090 ns/poll on the row path). <b>If the sum closes, the split is
     * evidence; if it does not, the instrument is broken</b> - and the row says
     * which by reporting {@code bad}, the count of intervals that were negative
     * or longer than any poll could be (pass 13's window-spanning-the-frame-gap
     * failure mode, which read 45&times; high).
     *
     * <p>The last poll of a frame has no successor to close its loop interval,
     * so it is flushed at the {@code endSection} that closes {@code iteration} -
     * the same place the paired A/B blocks are closed, for the same reason.
     */
    @Unique
    private static final boolean BFS_PHASE =
            "1".equals(System.getenv("VULKANMOD_BFS_PHASE"));

    /** Samples between stamps. 8 keeps the apparatus under 0.3% of the frame. */
    @Unique
    private static final int BFS_PHASE_STRIDE = 8;

    /** Polls remaining before the next sample; 0 means "not currently sampling". */
    @Unique
    private static int vulkanmod$phaseCountdown;

    /** The open interval's start stamp. Only meaningful when {@code phaseState != 0}. */
    @Unique
    private static long vulkanmod$phaseT;

    /** 0 = idle, 1 = deque poll open, 2 = mask/term-2 open, 3 = facing loop open. */
    @Unique
    private static int vulkanmod$phaseState;

    /**
     * Closes the open interval into the matching accumulator and opens the next
     * one. Split out so the four stamp sites cannot drift apart.
     *
     * <p>{@code next} is the state to move to, or 0 to go idle.
     */
    @Unique
    private static void vulkanmod$phaseAdvance(final int open, final int next) {
        final long ns = System.nanoTime() - vulkanmod$phaseT;
        if (open == 1) {
            FrameProfiler.onBfsPhasePoll(ns);
        } else if (open == 2) {
            FrameProfiler.onBfsPhasePrologue(ns);
        } else {
            FrameProfiler.onBfsPhaseLoop(ns);
        }
        vulkanmod$phaseState = next;
        if (next != 0) {
            vulkanmod$phaseT = System.nanoTime();
        }
    }

    /**
     * The term-1 keep mask for the entry currently being polled. A static field
     * because the {@code EnumFacing.values()} redirect handler has to be static
     * (the target is a static call), while the mask is derived from instance state.
     * {@code setupTerrain} runs on the client thread only, and the poll that writes
     * it immediately precedes the loop that reads it. Initialised to "keep
     * everything" so that a missing poll redirect degrades to vanilla rather than to
     * an empty loop.
     */
    @Unique
    private static int vulkanmod$faceMask = FACE_MASK_ALL;

    /**
     * The term-1-only mask for the poll in progress (pass 22).
     *
     * <p>Kept so the {@code BFSMASK2} paired A/B has a real OFF arm: its OFF arm is
     * the <b>pass-15 shipped shape</b> (term 1 alone), not the vanilla iteration, so
     * the row prices the term-2 fold and nothing else. With {@code BFS_T2=0} both
     * arms compute this mask and the row must read ~0 — which is the arm's own
     * attach check.
     */
    @Unique
    private static int vulkanmod$faceMaskT1 = FACE_MASK_ALL;

    /**
     * True when {@link #vulkanmod$computeFaceMask} actually evaluated term 1 for
     * the poll in progress.
     *
     * <p>This cannot be inferred from the mask's value: a mask of
     * {@link #FACE_MASK_ALL} means both "term 1 was not evaluated, keep everything"
     * and "term 1 was evaluated and kept all six" (the root entry, whose
     * {@code setFacing} is 0). The second case is exactly where term 2 is
     * <em>not</em> vacuous, so the two have to be distinguished.
     */
    @Unique
    private boolean vulkanmod$term1Active;

    /** {@code setFacing} byte → the term-1 keep mask. Built once, 64 entries. */
    @Unique
    private static byte[] vulkanmod$maskTable;

    /** One entry per six-bit keep mask, built on demand. Never mutated after build. */
    @Unique
    private static final EnumFacing[][] vulkanmod$maskedFacings = new EnumFacing[64][];

    /** True while the player is a spectator, i.e. while {@code flag1} may be false. */
    @Unique
    private boolean vulkanmod$spectator;

    @Unique
    private static byte[] vulkanmod$maskTable() {
        byte[] t = vulkanmod$maskTable;
        if (t == null) {
            final EnumFacing[] all = vulkanmod$values();
            t = new byte[1 << all.length];
            for (int set = 0; set < t.length; set++) {
                int keep = 0;
                for (int i = 0; i < all.length; i++) {
                    // Keep facing all[i] iff bit (all[i].getOpposite().ordinal())
                    // of `set` is clear - that is exactly term 1 being true.
                    if ((set & (1 << all[i].getOpposite().ordinal())) == 0) {
                        keep |= 1 << i;
                    }
                }
                t[set] = (byte) keep;
            }
            vulkanmod$maskTable = t;
        }
        return t;
    }

    /**
     * The keep mask for one polled entry, or {@link #FACE_MASK_ALL} when the
     * reorder must not apply.
     *
     * <p>{@code flag1 = mc.renderChunksMany} is read once at the head of the update
     * block and cleared to false in exactly one place — inside
     * {@code if (playerSpectator && ...)}. So when the view entity is not a
     * spectator, {@code flag1} is {@code mc.renderChunksMany} for the whole pass and
     * re-reading the field is the same value.
     */
    @Unique
    private int vulkanmod$computeFaceMask(Object info) {
        if (this.vulkanmod$spectator || !this.mc.renderChunksMany) {
            this.vulkanmod$term1Active = false;
            return FACE_MASK_ALL;
        }
        if (!(info instanceof ContainerInfoAccessor)) {
            this.vulkanmod$term1Active = false;
            return FACE_MASK_ALL;
        }
        this.vulkanmod$term1Active = true;
        final ContainerInfoAccessor entry = (ContainerInfoAccessor) info;
        final byte setFacing = entry.vulkanmod$setFacing();
        final int mask = vulkanmod$maskTable()[setFacing & 0x3F] & 0x3F;

        if (BFS_MASK_VERIFY) {
            int truth = 0;
            final EnumFacing[] all = vulkanmod$values();
            for (int i = 0; i < all.length; i++) {
                if (!entry.vulkanmod$hasDirection(all[i].getOpposite())) {
                    truth |= 1 << i;
                }
            }
            if (truth != mask) {
                FrameProfiler.onBfsMaskBad();
                if (this.vulkanmod$maskBadLogged < 8) {
                    this.vulkanmod$maskBadLogged++;
                    VulkanMod.LOGGER.info(
                            "[VKBFS] MASK MISMATCH setFacing={} table={} hasDirection={}",
                            setFacing, mask, truth);
                }
            }
        }
        return mask;
    }

    /**
     * Pass 22: intersect the term-1 keep mask with the conjunction's second term.
     *
     * <p>{@code cc.isVisible(from, to)} is {@code setVisibility.isVisible(...)} — a
     * pure read of the polled chunk's compiled visibility, with no side effect — so
     * evaluating it here for the facings term 1 kept, and then not entering the loop
     * body for the ones it rejects, is the same decision vanilla makes one probe
     * later. The {@code isVisible} calls themselves are not extra: vanilla makes
     * exactly one per facing that survives term 1, and so does this.
     *
     * <p>Two cases where term 2 is vacuously true and the mask is returned unchanged:
     * the root entry ({@code facing == null}) and a {@code null}
     * {@code CompiledChunk}. The second cannot happen today — {@code getCompiledChunk}
     * is a field read — but a null check is cheaper than the argument for why it is
     * impossible.
     */
    @Unique
    private int vulkanmod$applyTerm2(ContainerInfoAccessor entry, int term1Mask) {
        final EnumFacing facing2 = entry.vulkanmod$facing();
        if (facing2 == null) {
            return term1Mask;
        }
        final RenderChunk rc3 = entry.vulkanmod$renderChunk();
        if (rc3 == null) {
            return term1Mask;
        }
        final CompiledChunk cc = rc3.getCompiledChunk();
        if (cc == null) {
            return term1Mask;
        }

        final EnumFacing opposite = facing2.getOpposite();

        // Pass 25. Both branches answer the identical predicate - bit i set means
        // "term 2 holds for EnumFacing.values()[i]" - so the mask, the entries
        // enqueued and their order are the same either way. The paired A/B
        // (VULKANMOD_AB=BFSVIS) flips between them at the queue.poll redirect,
        // i.e. at this decision point.
        final boolean useRow = PairedAB.TARGET_BFSVIS ? vulkanmod$visArm : BFS_ROW;

        if (useRow) {
            final int fromOrdinal = opposite.ordinal();
            final VisibilityRow vis = (VisibilityRow) cc;
            final int row = vis.vulkanmod$visibilityRow(fromOrdinal);

            FrameProfiler.onBfsRowCall(true);
            if (row == FACE_MASK_ALL) {
                FrameProfiler.onBfsRowAllVisible();
            }

            if (BFS_ROW_VERIFY) {
                // Compare against the real BitSet, and return the REAL answer so a
                // stale mirror cannot remove chunks from the world during a
                // correctness run (pass 24's rule: a gate that returns the thing it
                // is testing is not a gate).
                final int truth = vis.vulkanmod$visibilityRowSlow(fromOrdinal);
                if (truth != row) {
                    FrameProfiler.onBfsRowBad();
                }
                return term1Mask & truth;
            }

            return term1Mask & row;
        }

        FrameProfiler.onBfsRowCall(false);

        final EnumFacing[] all = vulkanmod$values();
        int out = 0;
        for (int i = 0; i < all.length; i++) {
            final int bit = 1 << i;
            if ((term1Mask & bit) == 0) {
                continue;
            }
            FrameProfiler.onBfsRowVisCall();
            if (cc.isVisible(opposite, all[i])) {
                out |= bit;
            }
        }

        if (BFS_T2_VERIFY) {
            // The whole two-term predicate, the long way, against the real methods:
            // six hasDirection calls and six isVisible calls, no bit table, no
            // reuse of term1Mask. A disagreement means a facing this removed would
            // have been enqueued - i.e. a chunk missing from the world.
            int truth = 0;
            for (int i = 0; i < all.length; i++) {
                if (!entry.vulkanmod$hasDirection(all[i].getOpposite())
                        && cc.isVisible(opposite, all[i])) {
                    truth |= 1 << i;
                }
            }
            if (truth != out) {
                FrameProfiler.onBfsMaskBad();
                if (this.vulkanmod$maskBadLogged < 8) {
                    this.vulkanmod$maskBadLogged++;
                    VulkanMod.LOGGER.info(
                            "[VKBFS] TERM2 MISMATCH facing={} mask={} truth={}",
                            facing2, out, truth);
                }
            }
        }

        return out;
    }

    @Unique
    private int vulkanmod$maskBadLogged;

    /** The cached sub-array of facings to iterate for a keep mask. */
    @Unique
    private static EnumFacing[] vulkanmod$masked(int mask) {
        final int m = mask & 0x3F;
        EnumFacing[] a = vulkanmod$maskedFacings[m];
        if (a == null) {
            final EnumFacing[] all = vulkanmod$values();
            a = new EnumFacing[Integer.bitCount(m)];
            int j = 0;
            for (int i = 0; i < all.length; i++) {
                if ((m & (1 << i)) != 0) {
                    a[j++] = all[i];
                }
            }
            vulkanmod$maskedFacings[m] = a;
        }
        return a;
    }

    /**
     * The {@code EnumFacing.values()} redirect: pass-14's cached array, plus the
     * pass-15 term-1 filter.
     */
    @Unique
    private static EnumFacing[] vulkanmod$valuesForLoop() {
        final int mask = vulkanmod$faceMask;
        return mask == FACE_MASK_ALL ? vulkanmod$values() : vulkanmod$masked(mask);
    }

    // ------------------------------------------------------------------
    // Paired A/B for the term-1 hoist ({@code VULKANMOD_AB=BFSMASK})
    // ------------------------------------------------------------------

    /**
     * Static because the {@code values()} redirect handler is. Blocks of 64
     * <b>polls</b> (the same block length every other paired target uses), so the
     * reported mean is ns per poll and the frame effect is that times the poll rate
     * (892 at rd 12).
     */
    @Unique
    private static boolean vulkanmod$mkArm = true;

    @Unique
    private static boolean vulkanmod$mkOpen;

    @Unique
    private static int vulkanmod$mkPolls;

    @Unique
    private static long vulkanmod$mkStart;

    @Unique
    private static void vulkanmod$mkTick(boolean arm) {
        if (arm != vulkanmod$mkArm || !vulkanmod$mkOpen) {
            final long now = System.nanoTime();
            if (vulkanmod$mkOpen) {
                FrameProfiler.addAbBfsMask(vulkanmod$mkArm, now - vulkanmod$mkStart,
                        vulkanmod$mkPolls);
            }
            vulkanmod$mkArm = arm;
            vulkanmod$mkStart = now;
            vulkanmod$mkOpen = true;
            vulkanmod$mkPolls = 0;
        }
        vulkanmod$mkPolls++;
    }

    /**
     * Closes the open poll block at the end of the {@code iteration} window.
     * Same reason as {@link #vulkanmod$flushAbBfs}: a block left open would be
     * closed by the <i>next</i> frame's polls and would be timed across the
     * inter-frame gap (pass 13 measured that failure at 45&times; high).
     */
    @Unique
    private static void vulkanmod$flushAbMask() {
        if (vulkanmod$mkOpen) {
            vulkanmod$mkOpen = false;
            FrameProfiler.addAbBfsMask(vulkanmod$mkArm, System.nanoTime() - vulkanmod$mkStart,
                    vulkanmod$mkPolls);
        }
    }

    /** The six {@code EnumFacing} constants, cached.
     *
     * <p>{@code EnumFacing.values()} clones a six-element array on every call,
     * and the BFS calls it once per polled chunk - ~890 clones a frame at rd 12.
     * The for-each only reads the array, so handing it the same instance every
     * time is indistinguishable from vanilla. Lazily initialised rather than a
     * static final so that nothing here depends on the order in which
     * {@code RenderGlobal}'s and {@code EnumFacing}'s class initialisers run.
     */
    @Unique
    private static EnumFacing[] vulkanmod$valuesArray;

    @Unique
    private static EnumFacing[] vulkanmod$values() {
        EnumFacing[] v = vulkanmod$valuesArray;
        if (v == null) {
            v = EnumFacing.values();
            vulkanmod$valuesArray = v;
        }
        return v;
    }

    /**
     * Paired A/B block state for the frustum test ({@code VULKANMOD_AB=FRUSTUM}).
     * Mirrors the BFS block machinery: blocks are opened at a flip and closed
     * either at the next flip or by {@link #vulkanmod$flushAbFrustum()} at the
     * end of the {@code iteration} window, whichever comes first.
     */
    @Unique
    private boolean vulkanmod$frArm = true;

    @Unique
    private boolean vulkanmod$frOpen;

    @Unique
    private int vulkanmod$frCalls;

    @Unique
    private long vulkanmod$frStart;

    @Unique
    private int vulkanmod$frBadLogged;

    @Unique
    private void vulkanmod$flushAbFrustum() {
        if (this.vulkanmod$frOpen) {
            this.vulkanmod$frOpen = false;
            FrameProfiler.addAbFrustum(this.vulkanmod$frArm,
                    System.nanoTime() - this.vulkanmod$frStart, this.vulkanmod$frCalls);
        }
    }

    /**
     * One-entry memo on the base chunk. The BFS probes one chunk's six
     * neighbours consecutively, so every field here is read once per chunk
     * (~890 times a frame) instead of once per probe (~5350).
     *
     * <p>These are instance fields on {@code RenderGlobal}: {@code setupTerrain}
     * runs on the client thread only.
     */
    @Unique
    private RenderChunk vulkanmod$probeBase;

    /** Identity of the {@code BlockPos} the memo's range checks were built from. */
    @Unique
    private BlockPos vulkanmod$probePlayer;

    @Unique
    private int vulkanmod$badLogged;

    @Unique
    private int vulkanmod$probeBaseX;

    @Unique
    private int vulkanmod$probeBaseY;

    @Unique
    private int vulkanmod$probeBaseZ;

    /** Base chunk's array index, already reduced into {@code [0, count)}. */
    @Unique
    private int vulkanmod$probeBaseIX;

    @Unique
    private int vulkanmod$probeBaseIZ;

    @Unique
    private int vulkanmod$probePlayerX;

    @Unique
    private int vulkanmod$probePlayerZ;

    @Unique
    private int vulkanmod$probeLimit;

    @Unique
    private int vulkanmod$probeCountX;

    @Unique
    private int vulkanmod$probeCountY;

    @Unique
    private int vulkanmod$probeCountZ;

    @Unique
    private RenderChunk[] vulkanmod$probeChunks;

    /** True between {@code startSection("iteration")} and the matching endSection. */
    @Unique
    private boolean vulkanmod$bfsOpen;

    /** Paired A/B block state ({@code VULKANMOD_AB=BFS}). */
    @Unique
    private boolean vulkanmod$abArm = true;

    @Unique
    private boolean vulkanmod$abOpen;

    @Unique
    private int vulkanmod$abCalls;

    @Unique
    private long vulkanmod$abStart;

    @Inject(
            method = "setupTerrain(Lnet/minecraft/entity/Entity;DLnet/minecraft/client/renderer/culling/ICamera;IZ)V",
            at = @At("HEAD"))
    private void vulkanmod$beginSetupTerrain(Entity viewEntity, double partialTicks, ICamera camera,
                                             int frameCount, boolean playerSpectator, CallbackInfo ci) {
        FrameProfiler.beginSetupTerrain();

        // The term-1 hoist needs to know whether `flag1` can have been cleared.
        // setupTerrain clears it in exactly one place, guarded by playerSpectator,
        // so `!spectator` is the precondition that makes `mc.renderChunksMany` a
        // faithful re-read of flag1 inside the loop.
        this.vulkanmod$spectator = playerSpectator;

        if (FORCE_DIRTY) {
            // Line 922 ORs this with the position/rotation compare and reads it at
            // 937, so setting it here is the same input a moving player provides.
            this.displayListEntitiesDirty = true;
        }

        if (!FrameProfiler.PROFILE_DUMP) {
            return;
        }

        vulkanmod$diagCalls++;

        if (vulkanmod$havePrevPos) {
            vulkanmod$diagPosDelta += Math.abs(viewEntity.posX - vulkanmod$prevPosX)
                    + Math.abs(viewEntity.posY - vulkanmod$prevPosY)
                    + Math.abs(viewEntity.posZ - vulkanmod$prevPosZ);
        }
        vulkanmod$prevPosX = viewEntity.posX;
        vulkanmod$prevPosY = viewEntity.posY;
        vulkanmod$prevPosZ = viewEntity.posZ;
        vulkanmod$havePrevPos = true;

        final long now = System.currentTimeMillis();
        if (vulkanmod$diagWindowStart == 0L) {
            vulkanmod$diagWindowStart = now;
            return;
        }
        if (now - vulkanmod$diagWindowStart < 1000L) {
            return;
        }

        // displayListEntitiesDirty is read on entry: it is the value left by the
        // previous frame's tail (rebuildNear re-arms it) ORed with the position
        // check, and it is what decides whether this frame pays for the pass.
        // dPos is the accumulated |dx|+|dy|+|dz| over the window: a non-zero value
        // is the whole point, because setupTerrain re-arms itself from a strict
        // float compare (viewEntity.posX != lastViewEntityX), so any sub-block
        // drift at all makes it re-run the breadth-first pass every frame. The
        // absolute position is printed so drift can be told from a fall.
        VulkanMod.LOGGER.info("[VKDIAG] setupTerrain calls={}/s dlIn={} toUpdate={} dPos={} at={},{},{}",
                vulkanmod$diagCalls,
                this.displayListEntitiesDirty,
                this.chunksToUpdate.size(),
                String.format(Locale.ROOT, "%.5f", vulkanmod$diagPosDelta),
                String.format(Locale.ROOT, "%.3f", vulkanmod$prevPosX),
                String.format(Locale.ROOT, "%.3f", vulkanmod$prevPosY),
                String.format(Locale.ROOT, "%.3f", vulkanmod$prevPosZ));

        vulkanmod$diagCalls = 0;
        vulkanmod$diagPosDelta = 0.0;
        vulkanmod$diagWindowStart = now;
    }

    @Inject(
            method = "setupTerrain(Lnet/minecraft/entity/Entity;DLnet/minecraft/client/renderer/culling/ICamera;IZ)V",
            at = @At("RETURN"))
    private void vulkanmod$endSetupTerrain(Entity viewEntity, double partialTicks, ICamera camera,
                                           int frameCount, boolean playerSpectator, CallbackInfo ci) {
        FrameProfiler.endSetupTerrain();
        if (FrameProfiler.PROFILE_DUMP) {
            FrameProfiler.observeRenderInfos(this.renderInfos == null ? 0 : this.renderInfos.size());
        }
    }

    /**
     * Counts entries into the expensive half of {@code setupTerrain}.
     *
     * <p>{@code startSection("iteration")} is opened exactly once per entry into
     * the {@code displayListEntitiesDirty} block and nowhere else in the method,
     * so filtering the redirect on the section name is a faithful entry count -
     * and, unlike injecting at the block itself, it needs no knowledge of the
     * method's internals. Every other {@code startSection} in the method is
     * forwarded unchanged.
     */
    @Redirect(
            method = "setupTerrain(Lnet/minecraft/entity/Entity;DLnet/minecraft/client/renderer/culling/ICamera;IZ)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/profiler/Profiler;startSection(Ljava/lang/String;)V"))
    private void vulkanmod$countIteration(Profiler profiler, String name) {
        if ("iteration".equals(name)) {
            FrameProfiler.onSetupTerrainBfs();
            // The `iteration` section opens exactly once per entry into the
            // update block and is the only profiler section in it, so the next
            // endSection() the method reaches closes it. One clock pair a frame.
            FrameProfiler.beginSetupTerrainBfs();
            this.vulkanmod$bfsOpen = true;
        }
        profiler.startSection(name);
    }

    /**
     * Closes the BFS timing window. {@code setupTerrain} calls
     * {@code Profiler.endSection()} exactly twice - once to close
     * {@code iteration} and once to close {@code rebuildNear} - and the flag is
     * only set by the {@code iteration} open, so this cannot close the wrong one.
     */
    @Redirect(
            method = "setupTerrain(Lnet/minecraft/entity/Entity;DLnet/minecraft/client/renderer/culling/ICamera;IZ)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/profiler/Profiler;endSection()V"))
    private void vulkanmod$endIteration(Profiler profiler) {
        if (this.vulkanmod$bfsOpen) {
            this.vulkanmod$bfsOpen = false;
            FrameProfiler.endSetupTerrainBfs();
            // Close the open A/B block HERE, at the end of the loop that produced
            // it. Without this the block opened by the last flip of a frame is not
            // closed until the next frame's probes arrive, so its window spans the
            // whole inter-frame gap - and since a probe is ~200 ns while that gap
            // is ~11 ms, the one polluted block per frame out of ~17 read
            // ~9200 ns/probe and swamped the row. (Measured, not theorised: the
            // first BFS A/B read nsBfsOn 9194 with dBfsNs +26.)
            this.vulkanmod$flushAbBfs();
            this.vulkanmod$flushAbFrustum();
            vulkanmod$flushAbMask();

            // Pass 26: the last poll of the frame has no successor to close its
            // facing-loop interval, so close it here. Without this the final
            // interval of every frame would span the inter-frame gap and read
            // ~11 ms - pass 13's 45x-high failure mode, which is why the paired
            // A/B blocks are flushed at this same point.
            if (BFS_PHASE && vulkanmod$phaseState == 3) {
                vulkanmod$phaseAdvance(3, 0);
            }
        }
        profiler.endSection();
    }

    /**
     * Closes the currently open A/B probe block, if any.
     *
     * <p>Called both on a flip (where the caller has already read the clock) and
     * at the end of the {@code iteration} window. The window close matters: the
     * loop is contiguous, so a block that is still open when the loop ends would
     * otherwise be closed by the <i>next</i> frame's probes and would therefore
     * be timed across the whole inter-frame gap.
     *
     * <p>Dropping the trailing partial block (up to {@code BLOCK - 1} probes,
     * ~1% of a frame's 5352) is the correct trade - a biased sample is worse than
     * a smaller one, and the alternative is a row that reads ~45× high.
     */
    @Unique
    private void vulkanmod$flushAbBfs() {
        if (this.vulkanmod$abOpen) {
            this.vulkanmod$abOpen = false;
            FrameProfiler.addAbBfs(this.vulkanmod$abArm,
                    System.nanoTime() - this.vulkanmod$abStart, this.vulkanmod$abCalls);
        }
    }

    /**
     * The neighbour probe of the visible-chunk BFS.
     *
     * <p>This replaces the call rather than wrapping it, so the reference arm has
     * to be reconstructed - and it is reconstructed by calling the <b>real</b>
     * {@code ViewFrustum.getRenderChunk} through {@link ViewFrustumAccessor},
     * with only the surrounding six lines transcribed. A transcription of the
     * whole thing would not be a reference; the project has been caught by that
     * distinction before.
     */
    @Redirect(
            method = "setupTerrain(Lnet/minecraft/entity/Entity;DLnet/minecraft/client/renderer/culling/ICamera;IZ)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/RenderGlobal;getRenderChunkOffset("
                             + "Lnet/minecraft/util/math/BlockPos;"
                             + "Lnet/minecraft/client/renderer/chunk/RenderChunk;"
                             + "Lnet/minecraft/util/EnumFacing;)"
                             + "Lnet/minecraft/client/renderer/chunk/RenderChunk;"))
    private RenderChunk vulkanmod$probe(RenderGlobal self, BlockPos playerPos, RenderChunk base,
                                        EnumFacing facing) {
        FrameProfiler.onSetupTerrainProbe();

        if (PairedAB.TARGET_BFS) {
            // Flip first, then use the returned arm - the pass-10 lesson. Blocks
            // are timed from one flip boundary to the next, and the exact call
            // count is passed to the accumulator because the first block is one
            // call shorter than the rest (PairedAB flips on the 64th call).
            final boolean arm = PairedAB.nextArm();
            // Reopen on a flip - and also when the window close flushed the open
            // block, because the arm only changes every BLOCK calls, so a flush
            // leaves abArm == arm and the block would otherwise never reopen
            // (its probes would be silently uncounted).
            if (arm != this.vulkanmod$abArm || !this.vulkanmod$abOpen) {
                final long now = System.nanoTime();
                if (this.vulkanmod$abOpen) {
                    FrameProfiler.addAbBfs(this.vulkanmod$abArm, now - this.vulkanmod$abStart,
                            this.vulkanmod$abCalls);
                }
                this.vulkanmod$abArm = arm;
                this.vulkanmod$abStart = now;
                this.vulkanmod$abOpen = true;
                this.vulkanmod$abCalls = 0;
            }
            this.vulkanmod$abCalls++;
            return arm ? this.vulkanmod$fastProbe(playerPos, base, facing)
                       : this.vulkanmod$vanillaProbe(playerPos, base, facing);
        }

        if (!BFS_FAST) {
            return this.vulkanmod$vanillaProbe(playerPos, base, facing);
        }

        if (BFS_BODY) {
            // Sampled 1 in 32: the clock pair is ~226 ns here against a probe of
            // ~150 ns, so timing every probe would measure the clock and would add
            // ~5350 clock pairs a frame to the very window under test.
            final boolean sampled = FrameProfiler.bfsSample();
            final long t0 = sampled ? System.nanoTime() : 0L;

            final RenderChunk fast = this.vulkanmod$fastProbe(playerPos, base, facing);

            if (BFS_VERIFY && fast != this.vulkanmod$vanillaProbe(playerPos, base, facing)) {
                FrameProfiler.onBfsVerifyMismatch();
                this.vulkanmod$logMismatch(playerPos, base, facing, fast);
            }

            FrameProfiler.onBfsProbeSampled(t0, sampled);
            FrameProfiler.onBfsFastCall();
            return fast;
        }

        final RenderChunk fast = this.vulkanmod$fastProbe(playerPos, base, facing);

        if (BFS_VERIFY && fast != this.vulkanmod$vanillaProbe(playerPos, base, facing)) {
            FrameProfiler.onBfsVerifyMismatch();
            this.vulkanmod$logMismatch(playerPos, base, facing, fast);
        }

        FrameProfiler.onBfsFastCall();
        return fast;
    }

    /** Bounded diagnostic dump: the first few disagreements, with the inputs. */
    @Unique
    private void vulkanmod$logMismatch(BlockPos playerPos, RenderChunk base, EnumFacing facing,
                                       RenderChunk fast) {
        if (this.vulkanmod$badLogged >= 12) {
            return;
        }
        this.vulkanmod$badLogged++;

        final int px = this.vulkanmod$probeBaseX + (facing.getXOffset() << 4);
        final int py = this.vulkanmod$probeBaseY + (facing.getYOffset() << 4);
        final int pz = this.vulkanmod$probeBaseZ + (facing.getZOffset() << 4);

        VulkanMod.LOGGER.info(
                "[VKBFS] MISMATCH facing={} player=({},{},{}) limit={} basePos=({},{},{}) "
                        + "baseIX/Z={}/{} memoObj={} px/py/pz=({},{},{}) counts={}/{}/{} len={} "
                        + "fast={} vanilla={}",
                facing, playerPos.getX(), playerPos.getY(), playerPos.getZ(),
                this.vulkanmod$probeLimit,
                this.vulkanmod$probeBaseX, this.vulkanmod$probeBaseY, this.vulkanmod$probeBaseZ,
                this.vulkanmod$probeBaseIX, this.vulkanmod$probeBaseIZ,
                System.identityHashCode(this.vulkanmod$probeBase),
                px, py, pz,
                this.vulkanmod$probeCountX, this.vulkanmod$probeCountY, this.vulkanmod$probeCountZ,
                this.vulkanmod$probeChunks == null ? -1 : this.vulkanmod$probeChunks.length,
                fast, this.vulkanmod$vanillaProbe(playerPos, base, facing));
    }

    /**
     * The vanilla chain, transcribed: {@code getBlockPosOffset16} → three range
     * checks → the real {@code ViewFrustum.getRenderChunk}. Used as the reference
     * arm and as the verify-mode oracle.
     */
    @Unique
    private RenderChunk vulkanmod$vanillaProbe(BlockPos playerPos, RenderChunk base, EnumFacing facing) {
        final BlockPos pos = base.getBlockPosOffset16(facing);
        final int limit = this.renderDistanceChunks * 16;

        if (Math.abs(playerPos.getX() - pos.getX()) > limit) {
            return null;
        }
        if (pos.getY() < 0 || pos.getY() >= 256) {
            return null;
        }
        if (Math.abs(playerPos.getZ() - pos.getZ()) > limit) {
            return null;
        }
        return ((ViewFrustumAccessor) (Object) this.viewFrustum).vulkanmod$getRenderChunk(pos);
    }

    /**
     * The same result without the call chain, the three {@code intFloorDiv} calls
     * or the two integer divisions.
     */
    @Unique
    private RenderChunk vulkanmod$fastProbe(BlockPos playerPos, RenderChunk base, EnumFacing facing) {
        // The memo is keyed on the base chunk AND on the playerPos instance. The
        // second half is not decoration: playerPos is a fresh BlockPos per
        // setupTerrain call, while `base` can repeat across frames (the BFS ends
        // on the same far chunk it started from often enough), so a base-only key
        // would leave the previous frame's range-check origin in place.
        if (base != this.vulkanmod$probeBase || playerPos != this.vulkanmod$probePlayer) {
            this.vulkanmod$probeBase = base;
            this.vulkanmod$probePlayer = playerPos;

            final BlockPos p = base.getPosition();
            this.vulkanmod$probeBaseX = p.getX();
            this.vulkanmod$probeBaseY = p.getY();
            this.vulkanmod$probeBaseZ = p.getZ();

            this.vulkanmod$probePlayerX = playerPos.getX();
            this.vulkanmod$probePlayerZ = playerPos.getZ();
            this.vulkanmod$probeLimit = this.renderDistanceChunks * 16;

            final ViewFrustum vf = this.viewFrustum;
            final ViewFrustumAccessor acc = (ViewFrustumAccessor) (Object) vf;
            this.vulkanmod$probeCountX = acc.vulkanmod$countChunksX();
            this.vulkanmod$probeCountY = acc.vulkanmod$countChunksY();
            this.vulkanmod$probeCountZ = acc.vulkanmod$countChunksZ();
            this.vulkanmod$probeChunks = vf.renderChunks;

            // Normalise the base index HERE, once per chunk, and not per probe.
            //
            // A chunk's `position` is NOT in [0, count*16). ViewFrustum.updateChunkPositions
            // recentres the window on the player by moving every RenderChunk, and
            // getBaseCoordinate returns a value in a 384-wide band that can sit
            // anywhere - at x=320.5 the X positions run 304..688 for count=25, i.e.
            // array indices 19..43. So the raw index needs a real modulo, and only
            // once it has been reduced to [0, count) is a one-slot step guaranteed
            // to leave the array by at most one slot.
            int ix = this.vulkanmod$probeBaseX >> 4;
            ix %= this.vulkanmod$probeCountX;
            if (ix < 0) {
                ix += this.vulkanmod$probeCountX;
            }
            this.vulkanmod$probeBaseIX = ix;

            int iz = this.vulkanmod$probeBaseZ >> 4;
            iz %= this.vulkanmod$probeCountZ;
            if (iz < 0) {
                iz += this.vulkanmod$probeCountZ;
            }
            this.vulkanmod$probeBaseIZ = iz;
        }

        final int px = this.vulkanmod$probeBaseX + (facing.getXOffset() << 4);
        final int py = this.vulkanmod$probeBaseY + (facing.getYOffset() << 4);
        final int pz = this.vulkanmod$probeBaseZ + (facing.getZOffset() << 4);

        if (Math.abs(this.vulkanmod$probePlayerX - px) > this.vulkanmod$probeLimit) {
            return null;
        }
        if (py < 0 || py >= 256) {
            return null;
        }
        if (Math.abs(this.vulkanmod$probePlayerZ - pz) > this.vulkanmod$probeLimit) {
            return null;
        }

        // Chunk positions are multiples of 16, so >> 4 is intFloorDiv(p, 16).
        final int j = py >> 4;
        if (j < 0 || j >= this.vulkanmod$probeCountY) {
            return null;
        }

        // A one-slot step from an already-normalised index can only leave the
        // array by one slot, so `% count` plus its negative fixup is a compare.
        int i = this.vulkanmod$probeBaseIX + facing.getXOffset();
        if (i < 0) {
            i = this.vulkanmod$probeCountX - 1;
        } else if (i >= this.vulkanmod$probeCountX) {
            i = 0;
        }
        int k = this.vulkanmod$probeBaseIZ + facing.getZOffset();
        if (k < 0) {
            k = this.vulkanmod$probeCountZ - 1;
        } else if (k >= this.vulkanmod$probeCountZ) {
            k = 0;
        }

        return this.vulkanmod$probeChunks[(k * this.vulkanmod$probeCountY + j) * this.vulkanmod$probeCountX + i];
    }

    // ------------------------------------------------------------------
    // The BFS loop body, term by term (pass 14)
    // ------------------------------------------------------------------

    /**
     * {@code EnumFacing.values()} — one call per polled chunk. Counts the polls
     * and returns the cached array instead of a fresh six-element clone, so this
     * redirect is <b>strictly less work</b> than vanilla: ~890 clones of a
     * six-element array a frame, gone. (The for-each only reads the array, so
     * handing it the same instance every time is indistinguishable.)
     *
     * <p>Pass 15: with {@link #BFS_MASK} the returned array is the sub-array of
     * facings whose first conjunction term is not already false, so the loop body
     * is not entered at all for them.
     */
    @Redirect(
            method = "setupTerrain(Lnet/minecraft/entity/Entity;DLnet/minecraft/client/renderer/culling/ICamera;IZ)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/util/EnumFacing;values()[Lnet/minecraft/util/EnumFacing;"))
    private static EnumFacing[] vulkanmod$valuesRedirect() {
        if (BFS_PHASE && vulkanmod$phaseState == 2) {
            // STAMP C. From the deque poll to here is the mask, term 2, the two
            // field reads and renderInfos.add - the per-poll half proper.
            // From here to the next handler entry is the facing loop.
            vulkanmod$phaseAdvance(2, 3);
        }

        // Always on: the poll rate is the denominator for every other number in
        // the split, and it is a plain static increment inside a handler that
        // already exists.
        FrameProfiler.onBfsPoll();

        if (!BFS_MASK) {
            return vulkanmod$values();
        }

        final int mask = vulkanmod$faceMask;
        if (mask != FACE_MASK_ALL) {
            FrameProfiler.onBfsMaskPoll(6 - Integer.bitCount(mask));
        }

        if (PairedAB.TARGET_BFSMASK || PairedAB.TARGET_BFSMASK2) {
            // Flip first, then use the returned arm (the pass-10 lesson). The
            // decision point is here, and the block is timed here too, so the
            // sample point agrees with the decision point.
            final boolean arm = PairedAB.nextArm();
            vulkanmod$mkTick(arm);

            if (PairedAB.TARGET_BFSMASK2) {
                // Pass 22. ON = term 1 + term 2 (the shipped shape), OFF = term 1
                // alone (the pass-15 shape), so this row prices the term-2 fold and
                // nothing else. Both arms iterate the same loop at the same decision
                // point, and with VULKANMOD_BFS_T2=0 both compute the term-1 mask, so
                // the row must read ~0 - which is the arm's own attach check.
                final int m = arm ? mask : vulkanmod$faceMaskT1;
                return m == FACE_MASK_ALL ? vulkanmod$values() : vulkanmod$masked(m);
            }

            return arm ? (mask == FACE_MASK_ALL ? vulkanmod$values() : vulkanmod$masked(mask))
                       : vulkanmod$values();
        }

        return vulkanmod$valuesForLoop();
    }

    /**
     * The polled entry, captured for {@link #vulkanmod$computeFaceMask}.
     *
     * <p>{@code queue.poll()} appears exactly once in {@code setupTerrain} — at the
     * head of the {@code while} loop — so this is unambiguous, and it is the only
     * point where the entry being expanded is available as a <em>return value</em>.
     * Injecting at the {@code renderInfos.add} that follows it would need a local
     * capture of every preceding local, which is the fragile kind of hook this
     * project has been bitten by before.
     */
    @Redirect(
            method = "setupTerrain(Lnet/minecraft/entity/Entity;DLnet/minecraft/client/renderer/culling/ICamera;IZ)V",
            at = @At(value = "INVOKE",
                     target = "Ljava/util/Queue;poll()Ljava/lang/Object;"))
    private Object vulkanmod$capturePolledInfo(Queue<?> queue) {
        if (BFS_PHASE) {
            // STAMP A. The facing loop that started at the previous
            // valuesRedirect ends here, so close it before opening anything new.
            if (vulkanmod$phaseState == 3) {
                vulkanmod$phaseAdvance(3, 0);
            }
            if (vulkanmod$phaseState == 0 && --vulkanmod$phaseCountdown <= 0) {
                vulkanmod$phaseCountdown = BFS_PHASE_STRIDE;
                vulkanmod$phaseT = System.nanoTime();
                vulkanmod$phaseState = 1;
            }
        }

        if (PairedAB.TARGET_BFSVIS) {
            // Flip first, then use the returned arm (the pass-10 lesson), and flip
            // HERE because this is the decision point: vulkanmod$applyTerm2 runs
            // inside this handler. Flipping at the EnumFacing.values() redirect
            // instead would label every poll with the previous poll's arm - the
            // one-event lag pass 11 caught on CHUNKXF.
            final boolean arm = PairedAB.nextArm();
            vulkanmod$visArm = arm;
            vulkanmod$mkTick(arm);
        }

        final Object info = queue.poll();

        if (BFS_PHASE && vulkanmod$phaseState == 1) {
            // STAMP B. Everything from the handler's entry to here is the deque
            // poll and nothing else - the one operation the prologue cannot be
            // blamed for. Splitting it out is what decides whether the per-poll
            // third of the loop is queue machinery or the visibility chain.
            vulkanmod$phaseAdvance(1, 2);
        }

        if (!BFS_MASK) {
            this.vulkanmod$term1Active = false;
            vulkanmod$faceMaskT1 = FACE_MASK_ALL;
            vulkanmod$faceMask = FACE_MASK_ALL;
            return info;
        }

        final int term1 = this.vulkanmod$computeFaceMask(info);
        vulkanmod$faceMaskT1 = term1;

        // Pass 22. Term 2 only has anything to say where term 1 was actually
        // evaluated and left at least one facing to test; a zero term-1 mask means
        // the loop body does not run at all, so there is nothing to fold into.
        int mask = term1;
        if (BFS_T2 && this.vulkanmod$term1Active && term1 != 0
                && info instanceof ContainerInfoAccessor) {
            mask = this.vulkanmod$applyTerm2((ContainerInfoAccessor) info, term1);
        }

        vulkanmod$faceMask = mask;
        return info;
    }

    /**
     * {@code ICamera.isBoundingBoxInFrustum(AxisAlignedBB)} — the fifth and last
     * term of the neighbour condition, and the only one that is arithmetic rather
     * than a call. Counts the call rate, and (with {@code VULKANMOD_BFS_FRUSTUM})
     * substitutes {@link FrustumFast#vulkanmod$fastBoxInFrustum}.
     *
     * <p>The redirect costs one non-inlinable call per frustum test - 1112 of
     * them a frame, ~0.01 ms, well under the frame row's resolution - and it is
     * kept because it is what makes the FRUSTUM A/B re-runnable. The measured
     * answer is in {@link #BFS_FRUSTUM}: the substitution is a small
     * <em>regression</em> and is off by default.
     */
    @Redirect(
            method = "setupTerrain(Lnet/minecraft/entity/Entity;DLnet/minecraft/client/renderer/culling/ICamera;IZ)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/culling/ICamera;isBoundingBoxInFrustum("
                             + "Lnet/minecraft/util/math/AxisAlignedBB;)Z"))
    private boolean vulkanmod$frustumRedirect(ICamera camera, AxisAlignedBB box) {
        if (PairedAB.TARGET_FRUSTUM) {
            final boolean arm = PairedAB.nextArm();
            // Only the BFS's calls are block-timed. The one other call site in
            // setupTerrain is the (2*rd+1)^2 fallback loop, which runs before
            // startSection("iteration") and therefore outside the window that
            // closes these blocks - counting it would time a block across the
            // inter-frame gap, the failure pass 13 measured at 45x high.
            if (this.vulkanmod$bfsOpen) {
                if (arm != this.vulkanmod$frArm || !this.vulkanmod$frOpen) {
                    final long now = System.nanoTime();
                    if (this.vulkanmod$frOpen) {
                        FrameProfiler.addAbFrustum(this.vulkanmod$frArm, now - this.vulkanmod$frStart,
                                this.vulkanmod$frCalls);
                    }
                    this.vulkanmod$frArm = arm;
                    this.vulkanmod$frStart = now;
                    this.vulkanmod$frOpen = true;
                    this.vulkanmod$frCalls = 0;
                }
                this.vulkanmod$frCalls++;
            }
            return arm ? this.vulkanmod$fastFrustum(camera, box)
                       : camera.isBoundingBoxInFrustum(box);
        }

        FrameProfiler.onBfsFrustumCall();

        if (!BFS_FRUSTUM || !(camera instanceof FrustumFast)) {
            return camera.isBoundingBoxInFrustum(box);
        }

        final FrustumFast fastCamera = (FrustumFast) camera;

        if (BFS_BODY) {
            final boolean sampled = FrameProfiler.bfsSample();
            final long t0 = sampled ? System.nanoTime() : 0L;
            final boolean fast = fastCamera.vulkanmod$fastBoxInFrustum(box);

            if (BFS_FRUSTUM_VERIFY && fast != camera.isBoundingBoxInFrustum(box)) {
                FrameProfiler.onBfsFrustumBad();
                this.vulkanmod$logFrustumBad(box, fast);
            }

            FrameProfiler.onBfsFrustum(t0, sampled);
            return fast;
        }

        final boolean fast = fastCamera.vulkanmod$fastBoxInFrustum(box);

        if (BFS_FRUSTUM_VERIFY && fast != camera.isBoundingBoxInFrustum(box)) {
            FrameProfiler.onBfsFrustumBad();
            this.vulkanmod$logFrustumBad(box, fast);
        }

        return fast;
    }

    /** The production arm of the frustum A/B, and the shipped implementation. */
    @Unique
    private boolean vulkanmod$fastFrustum(ICamera camera, AxisAlignedBB box) {
        if (!(camera instanceof FrustumFast)) {
            return camera.isBoundingBoxInFrustum(box);
        }
        return ((FrustumFast) camera).vulkanmod$fastBoxInFrustum(box);
    }

    /** Bounded dump of frustum disagreements, with the box that produced them. */
    @Unique
    private void vulkanmod$logFrustumBad(AxisAlignedBB box, boolean fast) {
        if (this.vulkanmod$frBadLogged >= 8) {
            return;
        }
        this.vulkanmod$frBadLogged++;

        VulkanMod.LOGGER.info(
                "[VKBFS] FRUSTUM MISMATCH box=({} {} {})..({} {} {}) fast={} vanilla={}",
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, fast, !fast);
    }

    // getVisibleFacings is non-void, so both callbacks must be
    // CallbackInfoReturnable - a plain CallbackInfo fails mixin apply with
    // "CallbackInfoReturnable is required!" and takes the whole game down at
    // class-load, before the renderer ever starts.
    //
    // One HEAD injection, not two: timing and the memo are the same decision
    // point, and Mixin does not guarantee the order of two injectors at the same
    // point, so a second one could sample before or after the memo cancelled.
    @Inject(method = "getVisibleFacings(Lnet/minecraft/util/math/BlockPos;)Ljava/util/Set;",
            at = @At("HEAD"), cancellable = true)
    private void vulkanmod$beginVisibleFacings(BlockPos pos, CallbackInfoReturnable<Set> cir) {
        FrameProfiler.beginVisibleFacings();

        if (!VisibleFacingsCache.ENABLED) {
            return;
        }

        // The key is the section origin plus the eye's *block* position, not the
        // eye's exact position: the result depends on both, but the block
        // position changes only on a block crossing (~every 30 frames walking),
        // while the raw position changes every frame.
        final int sx = pos.getX() >> 4 << 4;
        final int sy = pos.getY() >> 4 << 4;
        final int sz = pos.getZ() >> 4 << 4;

        if (!VisibleFacingsCache.matches(sx, sy, sz, pos.getX(), pos.getY(), pos.getZ())) {
            this.vulkanmod$memoHit = false;
            return;
        }

        if (VisibleFacingsCache.VERIFY) {
            // Do not cancel: the point of verify mode is to compare against the
            // real computation, which only happens if vanilla runs. Timing is
            // therefore not meaningful in this mode.
            this.vulkanmod$memoHit = true;
            return;
        }

        FrameProfiler.onVisibleFacingsMemoHit();
        cir.setReturnValue(VisibleFacingsCache.copy());
    }

    @Inject(method = "getVisibleFacings(Lnet/minecraft/util/math/BlockPos;)Ljava/util/Set;",
            at = @At("RETURN"))
    private void vulkanmod$endVisibleFacings(BlockPos pos, CallbackInfoReturnable<Set> cir) {
        FrameProfiler.endVisibleFacings();

        // Only reached on a memo miss: a HEAD cancel returns through the injected
        // early return and never passes the original RETURN opcodes.
        if (!VisibleFacingsCache.ENABLED) {
            return;
        }

        final Set<EnumFacing> value = cir.getReturnValue();

        if (this.vulkanmod$memoHit) {
            this.vulkanmod$memoHit = false;
            FrameProfiler.onVisibleFacingsMemoHit();
            if (!VisibleFacingsCache.peek().equals(value)) {
                FrameProfiler.onVisibleFacingsMemoMismatch();
            }
        }

        // Stored from a copy: this set goes straight back to setupTerrain, which
        // removes a facing from it when size() == 1.
        VisibleFacingsCache.store(pos.getX() >> 4 << 4, pos.getY() >> 4 << 4, pos.getZ() >> 4 << 4,
                pos.getX(), pos.getY(), pos.getZ(), value);
    }

    // ------------------------------------------------------------------
    // The entity path and the per-layer scan (pass 16)
    // ------------------------------------------------------------------

    /**
     * Direct wall-clock bracket around {@code renderEntities}.
     *
     * <p>The vanilla tree puts this section at ~23% of the frame, but that tree
     * is built on an <b>unbalanced profiler stack</b> and is known to be ~1.5x
     * inflated where it can be checked, so the reading has never been converted
     * into a budget. Two clock reads a frame make this instrument free, and the
     * entity counts printed beside it separate a real cost from bench drift.
     */
    @Inject(method = "renderEntities(Lnet/minecraft/entity/Entity;"
                     + "Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            at = @At("HEAD"))
    private void vulkanmod$beginRenderEntities(Entity renderViewEntity, ICamera camera, float partialTicks,
                                               CallbackInfo ci) {
        FrameProfiler.beginRenderEntities(
                net.minecraftforge.client.MinecraftForgeClient.getRenderPass());
        // Pass 16c: HEAD is counted separately from RETURN, and the second entry
        // inside one renderWorldPass dumps its caller (bounded to 3 dumps).
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
     * Splits that 1.8 ms into the two things inside it.
     *
     * <p>Vanilla already draws the boundary - {@code endStartSection("entities")}
     * opens the visible-chunk walk plus the entity render loop, and
     * {@code endStartSection("blockentities")} opens the tile-entity loop - so the
     * split is vanilla's own, not a guess about where the work is. The two halves
     * are completely different work: the first walks {@code renderInfos} and calls
     * {@code world.getChunk} per entry, the second walks the same list again and
     * asks each compiled chunk for its tile entities. Pricing them together is
     * what made this section unattributable for three passes.
     *
     * <p>This method contains four {@code endStartSection} calls
     * ({@code global}, {@code entities}, {@code entityOutlines},
     * {@code blockentities}); the name is filtered and the other two are ignored,
     * so {@code entityOutlines} is charged to the entity half.
     */
    @Redirect(
            method = "renderEntities(Lnet/minecraft/entity/Entity;"
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

    // ------------------------------------------------------------------
    // Pass 16b: localising the entity phase from the inside.
    //
    // Three diagnostics, all counts or sums rather than paired rows, because the
    // question here is "what is in the window", not "which of two variants is
    // faster". Each one is a `@Redirect`, which costs a non-inlinable call in the
    // loop it measures - acceptable because they answer a 1.4 ms question and the
    // loop is ~30 ns an iteration.
    // ------------------------------------------------------------------

    /**
     * The entity loop's <b>iterator</b> - pass 18.
     *
     * <p>{@code renderEntities} has three {@code List.iterator()} calls in
     * bytecode order: the entity loop over {@code renderInfos}, the block-entity
     * loop over the same list, and the {@code list2} multipass replay. {@code
     * ordinal = 0} is the first, and the receiver is checked against
     * {@code renderInfos} anyway so a future insertion above it cannot silently
     * re-point this hook.
     *
     * <p>Pass 17 made an iteration pass 0 had found empty cheap but still paid all
     * 892 of them. Here pass 1's for-each is handed an iterator over just the
     * entries pass 0 recorded as having entities, so the ~877 iterations whose
     * entire body is {@code if (!isEmpty())} are not entered at all. The entries
     * that <em>are</em> visited, their order and their bodies are unchanged, which
     * is why this is a pure work removal and not a behavioural change.
     *
     * <p>This is also the A/B's decision point and its window opener: the window is
     * the whole pass-1 loop, closed by the {@code PooledMutableBlockPos.release()}
     * hook below, so the sample point agrees with the decision point.
     */
    @Redirect(
            method = "renderEntities(Lnet/minecraft/entity/Entity;"
                     + "Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            at = @At(value = "INVOKE",
                     target = "Ljava/util/List;iterator()Ljava/util/Iterator;",
                     ordinal = 0))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Iterator vulkanmod$entLoopIterator(List list) {
        if (list != this.renderInfos) {
            return list.iterator();
        }
        return FrameProfiler.entLoopIterator(list);
    }

    /**
     * The <b>tile-entity loop's</b> iterator - pass 21.
     *
     * <p>{@code renderEntities} has four {@code List.iterator()} calls in bytecode
     * order, verified against {@code javap} on a source-shaped reproducer rather
     * than assumed:
     *
     * <ol>
     *   <li>{@code 0} - the entity loop over {@code renderInfos};</li>
     *   <li>{@code 1} - the {@code list2} multipass replay;</li>
     *   <li>{@code 2} - the block-entity loop over {@code renderInfos} (this one);</li>
     *   <li>{@code 3} - the inner iteration over that entry's {@code list3}.</li>
     * </ol>
     *
     * <p>The {@code ClassInheritanceMultiMap} iteration and the
     * {@code setTileEntities} iteration do not match this target at all: their
     * static types are not {@code java.util.List}, so the INVOKE owner differs.
     *
     * <p>Only ordinal 2 has {@code renderInfos} as its receiver, so the receiver
     * check is both a guard and a fail-safe: if a future edit inserts a
     * {@code List.iterator()} above it, this hook lands on a different list, the
     * check fails and the loop takes the vanilla path. The attach check on the
     * {@code tileidx} row ({@code built} must equal {@code ri}) is what would make
     * that visible rather than silent.
     *
     * <p>Pass 0 gets the vanilla iterator - it is the build walk. Pass 1 gets an
     * iterator over only the entries pass 0 recorded as having tile entities, so
     * the ~882 iterations whose whole body is {@code if (!list3.isEmpty())} are not
     * entered at all. The entries visited, their order and their bodies are
     * unchanged, which is why this is a pure work removal.
     *
     * <p>This is also the A/B's decision point and its window opener; the window is
     * the whole pass-1 tile loop and is closed by the {@code renderEntities} RETURN
     * inject, so the sample point agrees with the decision point.
     */
    @Redirect(
            method = "renderEntities(Lnet/minecraft/entity/Entity;"
                     + "Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            at = @At(value = "INVOKE",
                     target = "Ljava/util/List;iterator()Ljava/util/Iterator;",
                     ordinal = 2))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Iterator vulkanmod$tileLoopIterator(List list) {
        if (list != this.renderInfos) {
            return list.iterator();
        }
        return FrameProfiler.tileLoopIterator(list);
    }

    /**
     * {@code chunk.getEntityLists()} - the second of the loop body's two calls, so
     * counting it counts the loop's iterations exactly, and timing the gap between
     * successive calls times the whole body.
     *
     * <p>The obvious companion - a {@code @Redirect} on {@code world.getChunk} -
     * was written first and <b>never fired</b> ({@code chnkCalls=0} while this one
     * read 1784/frame). {@code RenderGlobal.world} is declared {@code WorldClient},
     * so the INVOKE's owner in the bytecode is not {@code World}; the redirect
     * matched nothing. Worth recording because it produced a plausible zero rather
     * than an error, and a plausible zero reads as "this call is free". Pass 17
     * targets {@code WorldClient} and reports its own probe count so the same
     * failure cannot pass unnoticed a second time.
     *
     * <p>Pass 17 also answers a pass-1 iteration that pass 0 found empty with a
     * hot, permanently empty array instead of this chunk's real one.
     */
    @Redirect(
            method = "renderEntities(Lnet/minecraft/entity/Entity;"
                     + "Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/chunk/Chunk;getEntityLists()"
                             + "[Lnet/minecraft/util/ClassInheritanceMultiMap;"))
    private ClassInheritanceMultiMap<Entity>[] vulkanmod$entIter(Chunk chunk) {
        return FrameProfiler.entEntityLists(chunk);
    }

    /**
     * {@code world.getChunk(pos)} inside {@code renderEntities} - the <b>first</b>
     * call of the loop body, so it is where the iteration index advances.
     *
     * <p>Pass 0 records the chunk and the iteration's emptiness. Pass 1 answers
     * from that record, skipping a {@code Long2ObjectOpenHashMap} probe into an
     * 8192-entry map plus a cold {@code Chunk} deref - the chain pass 16 measured
     * at ~1.11 µs an iteration, which is why a second walk of an almost empty
     * scene costs as much as the first.
     */
    @Redirect(
            method = "renderEntities(Lnet/minecraft/entity/Entity;"
                     + "Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/multiplayer/WorldClient;getChunk("
                             + "Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/world/chunk/Chunk;"))
    private Chunk vulkanmod$entChunkProbe(WorldClient world, BlockPos pos) {
        return FrameProfiler.entPass1Chunk(world, pos);
    }

    /**
     * {@code ClassInheritanceMultiMap.isEmpty()} inside the entity loop - exactly
     * one call site in the whole of {@code RenderGlobal}, which is what makes it
     * the place pass 0 records "this iteration had nothing to draw" and the place
     * {@code ENTPASS1_VERIFY} compares the record against reality.
     */
    @Redirect(
            method = "renderEntities(Lnet/minecraft/entity/Entity;"
                     + "Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/util/ClassInheritanceMultiMap;isEmpty()Z"))
    private boolean vulkanmod$entListEmpty(ClassInheritanceMultiMap<Entity> self) {
        return FrameProfiler.entListIsEmpty(self);
    }

    /**
     * Closes the pass-1 record and the A/B block at the end of the entity loop.
     *
     * <p>{@code BlockPos.PooledMutableBlockPos.release()} is called exactly once
     * in {@code renderEntities} - immediately after the loop and before the
     * block-entity loop - so this is the loop closing its own window, which is
     * pass 13's rule. Flushing at RETURN instead would time the last A/B block
     * across the block-entity loop, ~0.25 ms of work the entity loop did not do.
     */
    @Redirect(
            method = "renderEntities(Lnet/minecraft/entity/Entity;"
                     + "Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/util/math/BlockPos$PooledMutableBlockPos;release()V"))
    private void vulkanmod$entLoopEnd(BlockPos.PooledMutableBlockPos pos) {
        FrameProfiler.onEntityLoopEnd();
        pos.release();
    }

    /**
     * Pass 27: split the pass-0 entity loop into the walk and the render.
     *
     * <p>{@code p0} has been the largest shim-visible item since pass 18 (1.34 ms
     * at rd 12) and has never been split. Pass 19 priced the chunk-probe chain at
     * ~31% of the vanilla loop and ENTIDX removed it, then attributed the residual
     * to "the inner entity-rendering body for the ~10 marked sections plus loop
     * machinery" - two different things in one sentence, and they imply opposite
     * decisions. This redirect separates them by timing the only call in the loop
     * body that does real work: {@code RenderManager.renderEntityStatic}.
     *
     * <p>{@code renderEntityStatic} has three call sites inside
     * {@code renderEntities}: the weather-effects loop above the entity loop, the
     * entity loop itself, and the {@code list2} multipass replay below it. Only
     * the middle one is inside the window, and the gate is
     * {@link FrameProfiler#entLoopOpen()} - set by the entity loop's own iterator
     * redirect and cleared by {@code release()} - rather than an ordinal, because
     * an ordinal would silently re-point if Forge ever reorders the method.
     *
     * <p>The clock pair is paid once per <b>rendered</b> entity, not per iteration:
     * {@code rendered} reads 7.4 a frame at rd 12 against 892 iterations, so the
     * meter is ~15 reads a frame and the row is unsampled. That is deliberately the
     * opposite of pass 26's instrument, whose closure check failed by 19-25%
     * precisely because its stamps outnumbered the work they measured.
     */
    @Redirect(
            method = "renderEntities(Lnet/minecraft/entity/Entity;"
                     + "Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/entity/RenderManager;"
                             + "renderEntityStatic(Lnet/minecraft/entity/Entity;FZ)V"))
    private void vulkanmod$timeEntityRender(RenderManager renderManager, Entity entity,
                                            float partialTicks, boolean hideDebugBoxes) {
        final boolean timed = FrameProfiler.entLoopOpen();
        if (!timed) {
            renderManager.renderEntityStatic(entity, partialTicks, hideDebugBoxes);
            return;
        }
        final long t0 = System.nanoTime();
        renderManager.renderEntityStatic(entity, partialTicks, hideDebugBoxes);
        FrameProfiler.onEntityRender(System.nanoTime() - t0);
    }

    /**
     * The entity-outline block was counted for one run and never fired
     * ({@code outline=0.00}): its condition is
     * {@code isRenderEntityOutlines() && (!list1.isEmpty() || entityOutlinesRendered)},
     * {@code entityOutlinesRendered} starts false and is assigned
     * {@code !list1.isEmpty()} inside the block, so with no outlined entity it can
     * never become true. It is not a candidate and the redirect was removed rather
     * than left in the shipped diff. Recorded here because "a section that looks
     * expensive" and "a section that runs" are different questions and only a count
     * separates them.
     */

    /**
     * {@code renderBlockLayer} opens the {@code filterempty} section, scans
     * {@code renderInfos}, and then calls {@code func_194339_b} which
     * <b>closes that section and opens {@code render_&lt;layer&gt;}</b> before the
     * draws. So the pair of calls brackets exactly the scan and nothing else -
     * the same boundary the vanilla tree uses, which is what makes the two
     * comparable.
     *
     * <p>{@code startSection} is called twice in this method
     * ({@code translucent_sort} and {@code filterempty}), so the name is filtered.
     * {@code func_194339_b} appears once.
     */
    @Redirect(
            method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/profiler/Profiler;startSection(Ljava/lang/String;)V"))
    private void vulkanmod$beginFilterEmpty(Profiler profiler, String name) {
        if ("filterempty".equals(name)) {
            FrameProfiler.beginFilterEmpty(this.renderInfos == null ? 0 : this.renderInfos.size());
        }
        profiler.startSection(name);
    }

    @Redirect(
            method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/profiler/Profiler;func_194339_b(Ljava/util/function/Supplier;)V"))
    private void vulkanmod$endFilterEmpty(Profiler profiler, Supplier<String> name) {
        FrameProfiler.endFilterEmpty();
        // Pass 20 closed the per-frame render-info record here; pass 23 removed that
        // close, and pass 24 re-staged it and removed it again. The close belongs at
        // this boundary - it is where the scan ENDS, so the scan that just finished
        // is the one that closes its own A/B block - but there is nothing to close
        // while the record is retired. See FrameProfiler's note on FLMASK for why it
        // is retired, and REFERENCE.md "pass 24" for the restore recipe.
        profiler.func_194339_b(name);
    }

    // ------------------------------------------------------------------
    // Pass 20's per-frame render-info record, consumer 1 - the four
    // filterempty scans of renderBlockLayer.
    //
    // REMOVED in pass 23. MEASURED NEUTRAL there, RE-STAGED in pass 24 to be
    // measured properly, and REMOVED AGAIN at the end of pass 24 - this time on an
    // argument rather than on another noisy pair of runs.
    //
    // The three hooks used to intercept the filterempty loop body
    //
    //   RenderChunk rc = this.renderInfos.get(j).renderChunk;
    //   if (!rc.getCompiledChunk().isLayerEmpty(blockLayerIn)) { ... }
    //
    // and each of those three call sites occurs EXACTLY ONCE in renderBlockLayer -
    // the TRANSLUCENT pre-sort above it uses an iterator and `isLayerStarted`, not
    // these - so none of them would need an ordinal. `List.get` was intercepted
    // rather than advanced by a cursor because the TRANSLUCENT scan iterates
    // BACKWARDS.
    //
    // Why they are gone: the record is count-neutral (`isLayerEmpty` is
    // `!layersUsed[ordinal]`, and building the 4-bit mask costs exactly the four
    // calls the four scans make without it) and the loop it fed is already at its
    // floor (rule 16 puts the marginal per-entry cost at 5-20 ns, because the ~892
    // CompiledChunks plus their layersUsed arrays are ~30 KB and stay in L2). Both
    // arguments are written out in full on FrameProfiler.FLMASK.
    //
    // The lesson worth keeping is the instrumentation one. Re-staging it cost a
    // build and three gate runs and caught TWO bugs that would each have made a
    // broken record look verified - a backwards flag implication (on=0 v=1) and an
    // inverted bit polarity (bad = 321120 = every one of 2676 calls a frame). Both
    // were visible only because a counter next to `bad=0` read a known non-zero.
    // Rule 9, for the third time in this file.
    //
    // Pass 23's own correction stands: it removed these on an invocation count
    // ("10 704 handler calls a frame for a feature that is default OFF") and its
    // measurement then showed the count was not a cost - at identical work,
    // `fempty ms` reads 0.2208 / 0.2632 with the hooks removed against 0.2358 /
    // 0.2641 / 0.2916 / 0.3153 with them present, and the two runs of the same
    // post-removal build differ by more than the two groups do. The hooks were free.
    // They are gone now because the record they fed cannot pay, not because they
    // cost anything.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Pass 20, consumer 2 - the tile-entity loop.
    //
    //   for (info : this.renderInfos) {
    //       List<TileEntity> list3 = info.renderChunk.getCompiledChunk().getTileEntities();
    //       if (!list3.isEmpty()) { ... }
    //   }
    //
    // `renderEntities` contains exactly one getCompiledChunk() call, so counting it
    // counts the loop's iterations and the count IS the render-info index: the loop
    // is a straight for-each from element 0 with no early exit. That is what lets
    // the tile bit be looked up by index without a separate cursor.
    // ------------------------------------------------------------------

    @Redirect(
            method = "renderEntities(Lnet/minecraft/entity/Entity;"
                     + "Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/chunk/RenderChunk;getCompiledChunk()"
                             + "Lnet/minecraft/client/renderer/chunk/CompiledChunk;"))
    private CompiledChunk vulkanmod$entBlockChunk(RenderChunk rc) {
        return FrameProfiler.entBlockChunk(rc);
    }

    @Redirect(
            method = "renderEntities(Lnet/minecraft/entity/Entity;"
                     + "Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/chunk/CompiledChunk;getTileEntities()"
                             + "Ljava/util/List;"))
    private List vulkanmod$entBlockTiles(CompiledChunk cc) {
        return FrameProfiler.entBlockTiles(cc);
    }

    /**
     * The loop's own result: how many chunks of this layer were non-empty. It is
     * the return value of the method, so it costs nothing to observe.
     */
    @Inject(
            method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At("RETURN"))
    private void vulkanmod$observeFilterEmptyAdded(BlockRenderLayer layer, double partialTicks, int pass,
                                                   Entity entityIn, CallbackInfoReturnable<Integer> cir) {
        FrameProfiler.onFilterEmptyAdded(cir.getReturnValueI());
    }

    /**
     * TEMPORARY (d10): skip one block layer, to identify which layer draws a
     * reported artifact. {@code VULKANMOD_SKIPLAYER=TRANSLUCENT} (or SOLID /
     * CUTOUT / CUTOUT_MIPPED) makes that layer draw nothing.
     *
     * <p>The reported ribbon is a flat saturated-blue strip lying across the
     * grass and over the mobs, present only in frames that contain water - so
     * the first thing worth establishing is whether it really is the translucent
     * (water) layer, rather than assuming it from the colour. Removing the layer
     * and re-shooting settles that in one run; no timing consequence, so it is
     * gated on an env var that is null in every normal run.
     */
    @Inject(
            method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At("HEAD"), cancellable = true)
    private void vulkanmod$skipLayer(BlockRenderLayer layer, double partialTicks, int pass,
                                     Entity entityIn, CallbackInfoReturnable<Integer> cir) {
        // TEMPORARY (d15): depth-state census for the "water/caves see through
        // blocks" report. Logs, once per layer per run, the GL depth configuration
        // in effect when each render layer is drawn, plus the depth load/store op
        // of the currently bound render pass - so we can tell whether the
        // translucent (water) layer has depth testing disabled, or draws against a
        // CLEARED depth (which would let it, and everything after it, see through
        // terrain). Gated on VULKANMOD_DEPTHXRAY so it is inert on every normal run.
        if (VULKANMOD_DEPTHXRAY) {
            vulkanmod$logLayerDepth(layer);
        }
        // Published so the chunk-draw probe (GlStateManagerMixin) can attribute a
        // terrain draw to its render layer even though the draw call itself has no
        // layer parameter.
        com.yuhan123.vulkanmod.render.util.XrayState.currentLayer = layer.name();
        String skip = System.getenv("VULKANMOD_SKIPLAYER");
        if (skip != null && skip.equalsIgnoreCase(layer.name())) {
            cir.setReturnValue(0);
        }
    }

    private static final boolean VULKANMOD_DEPTHXRAY = System.getenv("VULKANMOD_DEPTHXRAY") != null;

    private static final java.util.Set<String> VULKANMOD_XRAY_LAYERS = new java.util.HashSet<>();
    private static void vulkanmod$logLayerDepth(BlockRenderLayer layer) {
        if (!VULKANMOD_XRAY_LAYERS.add(layer.name())) return;
        int dl = -1, ds = -1;
        try {
            com.yuhan123.vulkanmod.vulkan.Renderer r = com.yuhan123.vulkanmod.vulkan.Renderer.getInstance();
            com.yuhan123.vulkanmod.vulkan.framebuffer.RenderPass rp = r.getBoundRenderPass();
            if (rp != null) { dl = rp.getDepthLoadOp(); ds = rp.getDepthStoreOp(); }
        } catch (Throwable ignored) {}
        VulkanMod.LOGGER.info("[VKXRAY] layer={} depthTest={} depthMask={} alphaTest={} depthFunc=0x{} colorMask=0x{} boundDepthLoad={} boundDepthStore={}",
                layer.name(),
                com.yuhan123.vulkanmod.vulkan.VRenderSystem.depthTest,
                com.yuhan123.vulkanmod.vulkan.VRenderSystem.depthMask,
                com.yuhan123.vulkanmod.vulkan.VRenderSystem.alphaTest,
                Integer.toHexString(com.yuhan123.vulkanmod.vulkan.VRenderSystem.depthFun),
                Integer.toHexString(com.yuhan123.vulkanmod.vulkan.VRenderSystem.colorMask),
                dl, ds);
    }

    /**
     * Invalidation. Every route by which a chunk section's contents can change
     * ends in one of these.
     *
     * <p>{@code markBlockRangeForRenderUpdate} is the funnel for block changes -
     * {@code Chunk.setBlockState} and {@code RenderGlobal.notifyBlockUpdate} both
     * go through it - and {@code setWorldAndLoadRenderers}/{@code loadRenderers}
     * cover a world swap and a render-distance change. Chunk contents arriving
     * over the network do not route through any of them, hence the {@code Chunk}
     * hooks in {@link ChunkMixin}; that direction matters because a section that
     * looks empty caches an "everything is visible" answer.
     */
    @Inject(method = "markBlockRangeForRenderUpdate(IIIIII)V", at = @At("HEAD"))
    private void vulkanmod$invalidateOnBlockRange(int x1, int y1, int z1, int x2, int y2, int z2,
                                                  CallbackInfo ci) {
        VisibleFacingsCache.invalidate();
    }

    @Inject(method = "setWorldAndLoadRenderers(Lnet/minecraft/client/multiplayer/WorldClient;)V",
            at = @At("HEAD"))
    private void vulkanmod$invalidateOnWorld(WorldClient world, CallbackInfo ci) {
        VisibleFacingsCache.invalidate();
        // Pass 19: the entity-section index's stand-in chunk holds a world
        // reference, and this is the one place a world is replaced - including the
        // call with null when the player leaves one.
        FrameProfiler.onWorldChanged();
    }

    @Inject(method = "loadRenderers()V", at = @At("HEAD"))
    private void vulkanmod$invalidateOnLoadRenderers(CallbackInfo ci) {
        VisibleFacingsCache.invalidate();
    }
}
