package com.yuhan123.vulkanmod.render.util;

/**
 * Paired within-frame A/B for any boolean-gated hot path: {@code VULKANMOD_AB=<FLAG>}.
 *
 * <h2>Why this exists</h2>
 *
 * The project's standard two-launch A/B <b>cannot resolve a sub-100 ns/call change
 * on this bench</b>. Pass 10 measured the {@code PipelineState} memo at −69 ns/call
 * in one launch order and +23 ns/call in the other, and in <em>both</em> orders the
 * second launch was the faster one — the run-order effect is the same size as the
 * treatment, with 77 settled reports per case. That is not a sample-size problem:
 * the two launches share neither a scene ({@code iBatch}/{@code draws} are the
 * documented drifting counters) nor a machine state.
 *
 * The fix is to put both arms in <em>one</em> process, alternating them per hot
 * call so both see the same frame, the same sections and the same cache state. The
 * difference between the two arms' per-call cost is then a paired statistic with the
 * scene cancelled by construction. Pass 10 proved the technique on the memo
 * (−62 ns/call, stable at −43…−59 ns/frame); this class generalises it.
 *
 * <h2>How to use it</h2>
 *
 * A run names exactly one flag, e.g. {@code VULKANMOD_AB=APPLYREUSE}. The flag's
 * holder turns its {@code static final boolean} into a method:
 *
 * <pre>{@code
 * private static boolean reuseTerrainStateOn() {
 *     return PairedAB.TARGET_APPLYREUSE ? PairedAB.arm() : REUSE_TERRAIN_STATE;
 * }
 * }</pre>
 *
 * When {@code TARGET_APPLYREUSE} is false the ternary is a constant and the JIT
 * folds the whole call back to the original field read, so the instrument costs
 * nothing outside an A/B run. The arm is flipped once per hot event at the flip
 * point below, and {@link FrameProfiler#addAbApply} accumulates the {@code apply()}
 * window under whichever arm was in effect for that call.
 *
 * <h2>The flip point is part of the measurement</h2>
 *
 * The arm must flip <em>between</em> events, never inside one, or an event would
 * be measured half under each arm. The flags gate work that starts inside
 * {@code ShaderInstance.apply()}, so the flip happens at the top of
 * {@code apply()}: it covers the guard, the publish and the reuse bookkeeping.
 * (The CHUNKXF arm, which flipped in {@code MatrixState.pushMatrix()}, is gone
 * with that optimisation.)
 *
 * <h2>Reading the result</h2>
 *
 * {@code [VKPROF] pab} reports each arm's per-call mean over the same frames.
 * {@code dNs = nsOn − nsOff} is positive when the flag <em>costs</em> and negative
 * when it <em>saves</em>.
 */
public final class PairedAB {

    /** The flag named by {@code VULKANMOD_AB}, or null when the instrument is off. */
    public static final String FLAG = System.getenv("VULKANMOD_AB");

    public static final boolean ACTIVE = FLAG != null && !FLAG.isEmpty();

    public static final boolean TARGET_APPLYREUSE = targets("APPLYREUSE");
    public static final boolean TARGET_REUSEALL = targets("REUSEALL");

    /**
     * Measures <b>the timing apparatus itself</b>, not a feature. Arm ON opens
     * {@code apply()}'s inner sub-windows (the flush / push / guard / book
     * timers); arm OFF runs the identical code with those windows closed, so the
     * only difference between the arms is the clock reads and the accumulator
     * calls that produce the {@code applysplit} row.
     *
     * <p>The outer window that feeds this instrument stays open in both arms, so
     * the row still accumulates. {@code dNs} is therefore the per-call cost of the
     * inner instrumentation, and it answers the question the project has been
     * carrying for several passes: <b>how much of the measured {@code apply()}
     * window is measurement?</b> {@code applySplitMiscMs} is a residual that has
     * been absorbing exactly these reads without attribution, so this is the
     * number that says whether that residual is work or apparatus.
     *
     * <p>This is worth doing because the answer changes what is worth optimising:
     * if the residual is mostly apparatus then the shim's real per-draw cost is far
     * below its measured cost, and the ceiling on further shim work is much lower
     * than the measured rows imply. Nothing about a production build changes - the
     * apparatus is already behind {@code VULKANMOD_DRAWTIMING}.
     */
    public static final boolean TARGET_APPTIME = targets("APPTIME");

    /**
     * Validates the {@code apply()} / {@code applyFull()} split - the last shipped
     * change in this project that was never measured.
     *
     * <h3>What is being claimed, and how this tests it</h3>
     *
     * The split's claim is about <b>method entry</b>: before it, {@code apply()} was
     * one large method that the JIT could not inline into the draw site, so each of
     * the ~890 fast-path calls a frame paid a full frame setup and register spill to
     * reach, 99% of the time, six integer compares. After it, {@code apply()} is
     * small enough to inline, so the fast path pays no call at all.
     *
     * <p>The OFF arm therefore runs the <em>identical work</em> with the guard and the
     * publish behind one call ({@code applyFolded}) instead of two. That is a faithful
     * model of the pre-split fast path - one non-inlinable entry - which is the only
     * path the claim concerns. {@code dNs} is then the cost of that entry.
     *
     * <h3>Confounds removed by construction</h3>
     *
     * The full path is 26 calls a frame against ~890 fast ones, and in the OFF arm it
     * carries one extra call. That biases the OFF arm slightly <em>worse</em>, i.e. the
     * result is conservative. The sub-windows are also closed in <b>both</b> arms (see
     * {@code appSegmentsOn()}) so that the call boundary is the only difference - this
     * is a single-variable experiment or it is nothing.
     */
    public static final boolean TARGET_APPLYSPLIT = targets("APPLYSPLIT");

    /**
     * Prices the visible-chunk BFS's neighbour probe - the single most-called
     * thing in {@code setupTerrain}.
     *
     * <h3>Why this one is safe to flip per call, unlike the reuse flags</h3>
     *
     * {@code getRenderChunkOffset} is a <b>pure function</b> of
     * {@code (playerPos, chunkPosition, facing, renderDistanceChunks)}: it reads
     * the chunk's cached neighbour position, does three range checks and one
     * index lookup in the frustum's flat {@code RenderChunk[]}. It carries no
     * cross-call state, so both arms return the identical value and the frame
     * after the flip is bit-for-bit the frame that would have run. That is what
     * makes a per-call alternation valid here and invalid for
     * {@code APPLYREUSE} (see {@link #BLOCK}).
     *
     * <p>Arm ON is the arithmetic rewrite; arm OFF calls the real
     * {@code ViewFrustum.getRenderChunk}, i.e. the production reference, not a
     * transcription of it.
     *
     * <p>Timing is per <b>block</b> of 64 probes, never per call: a
     * {@code nanoTime()} pair costs ~226 ns here against a probe of ~150 ns, so
     * per-call timing would measure the clock. Both arms carry the same block
     * overhead, so it cancels in {@code dNs}.
     */
    public static final boolean TARGET_BFS = targets("BFS");

    /**
     * Prices the visible-chunk BFS's <b>frustum test</b> (pass 14) - the last
     * term of the five-term neighbour condition and the only one that is
     * arithmetic rather than a call.
     *
     * <h3>What the two arms are</h3>
     *
     * Arm ON is a positive-vertex test: one dot product per plane, evaluated at
     * the single corner that maximises it. Arm OFF is the real
     * {@code ClippingHelper.isBoxInFrustum} - the eight-corner, short-circuiting
     * loop, reached through the {@code ICamera} interface exactly as production
     * reaches it. That makes this a comparison against the production reference,
     * not a transcription of it.
     *
     * <h3>Why it is safe to flip per call</h3>
     *
     * The test is a pure function of {@code (frustum planes, box)}. It reads the
     * camera's six planes and the chunk's cached bounding box and writes nothing,
     * so both arms return the identical value and the frame after a flip is
     * bit-for-bit the frame that would have run. Same argument as
     * {@link #TARGET_BFS}.
     *
     * <h3>Why this needs its own accumulator, and blocks of 64</h3>
     *
     * A frustum test is ~40-100 ns, so a per-call {@code nanoTime} pair (~226 ns
     * here) would be larger than the thing being measured. The row is therefore
     * timed per <b>block</b> of 64 calls - from the first call of a block to the
     * first call of the next - and both arms carry the same block overhead, so it
     * cancels in {@code dFrNs}. The block is closed at the end of the
     * {@code iteration} window by the same flush the BFS row uses; without that
     * the trailing block of each frame would be timed across the ~11 ms
     * inter-frame gap and would swamp the row (pass 13 measured that failure at
     * 45&times; high).
     */
    public static final boolean TARGET_FRUSTUM = targets("FRUSTUM");

    /**
     * Prices the <b>term-1 hoist</b> of the BFS neighbour loop (pass 15).
     *
     * <h3>What the two arms are</h3>
     *
     * Arm ON iterates only the facings whose first conjunction term is not already
     * false, so the loop body — and with it the neighbour probe — is not entered
     * for them. Arm OFF iterates all six facings, i.e. the vanilla shape. Both
     * arms enqueue exactly the same entries in the same order, so the scene is
     * unchanged and the difference is pure removed work.
     *
     * <h3>Why the flip is at the {@code EnumFacing.values()} redirect</h3>
     *
     * That is where the decision is taken, and the block is timed there as well, so
     * the sample point agrees with the decision point — the rule two earlier
     * instruments in this project were caught breaking.
     *
     * <h3>The unit is a poll, not a probe</h3>
     *
     * A block is 64 <b>polls</b> (892 a frame at rd 12), so {@code dMkNs} is ns per
     * poll and the frame effect is {@code dMkNs} &times; polls/frame. This is
     * deliberately a different unit from {@link #TARGET_BFS}, whose blocks are 64
     * probes; the two targets are mutually exclusive and report in different
     * columns.
     */
    public static final boolean TARGET_BFSMASK = targets("BFSMASK");

    /**
     * Prices the <b>term-2 fold into the BFS keep mask</b> (pass 22).
     *
     * <h3>What the two arms are, and why this is not BFSMASK</h3>
     *
     * {@link #TARGET_BFSMASK} prices "mask versus vanilla iteration" — the pass-15
     * change. This one prices the pass-22 <em>increment</em> on top of it: arm ON
     * iterates the facings that survive term 1 <b>and</b> term 2, arm OFF iterates
     * the facings that survive term 1 alone (the pass-15 shape). Both arms are at
     * the same decision point — the {@code EnumFacing.values()} redirect, once per
     * poll — so the sample point still agrees with the decision point and the two
     * arms differ by exactly the term-2 predicate.
     *
     * <p>That makes the arm's own attach check available and cheap: with
     * {@code VULKANMOD_BFS_T2=0} both arms compute the term-1 mask, so the row
     * <b>must</b> read ~0. A non-zero {@code dMkNs} in that configuration means the
     * arm is not gating what it claims to.
     *
     * <h3>The unit is a poll, same as BFSMASK</h3>
     *
     * A block is 64 <b>polls</b> (892 a frame at rd 12), so the frame effect is
     * {@code dMkNs} &times; polls/frame. It reports in the same {@code mk*} columns
     * because only one target can be active per run and the decision point is
     * identical.
     */
    public static final boolean TARGET_BFSMASK2 = targets("BFSMASK2");

    /**
     * Prices the pass-25 <b>term-2 visibility row</b>.
     *
     * <h3>What the two arms are</h3>
     *
     * Both arms are the shipped pass-22 shape: the term-1 keep mask, intersected
     * with the conjunction's second term before the neighbour probe is paid. They
     * differ only in <em>how</em> term 2 is answered. Arm ON reads the polled
     * chunk's whole face-visibility row as one six-bit value out of a mirror of
     * {@code SetVisibility}'s 36-bit store ({@code term1Mask & row}); arm OFF
     * makes the production call per surviving facing —
     * {@code CompiledChunk.isVisible(opposite, f)} — which is a three-level chain
     * into {@code BitSet.get}.
     *
     * <p>Both arms return the identical mask, so they enqueue the identical
     * entries in the identical order and the scene is unchanged. The difference is
     * pure removed call work: ~2916 three-level calls a frame become 892 rows.
     *
     * <h3>Why the flip is at the {@code queue.poll} redirect</h3>
     *
     * That is where the term-2 decision is taken ({@code applyTerm2} runs inside
     * {@code vulkanmod$capturePolledInfo}). Flipping at the
     * {@code EnumFacing.values()} redirect instead would label every poll with the
     * <em>previous</em> poll's arm — the systematic one-event lag that pass 11
     * caught on {@code CHUNKXF} — so the flip and the block timer both live at the
     * poll.
     *
     * <h3>The unit is a poll, same as BFSMASK</h3>
     *
     * A block is 64 <b>polls</b> (892 a frame at rd 12), so the frame effect is
     * {@code dMkNs} &times; polls/frame. It reports in the {@code mk*} columns for
     * the same reason {@code BFSMASK2} does: only one target is active per run and
     * the sample point is identical.
     */
    public static final boolean TARGET_BFSVIS = targets("BFSVIS");

    /**
     * Prices the <b>pass-1 entity-walk reuse</b> (pass 17).
     *
     * <h3>What the two arms are</h3>
     *
     * {@code RenderGlobal.renderEntities} runs twice a frame because Forge's
     * patched {@code EntityRenderer} calls it once per render pass (0 = solid,
     * before translucent terrain; 1 = translucent entity layers, after it). Both
     * calls walk the same {@code renderInfos} in the same order and pay the same
     * five-deep dependent load chain to rediscover which chunks have no entities.
     *
     * <p>Arm ON reuses pass 0's answer and touches only a hot 16-element array for
     * the iterations pass 0 found empty. Arm OFF is the vanilla shape. Both arms
     * render exactly the same entities, so the scene is unchanged and the
     * difference is pure removed work.
     *
     * <h3>Why the flip is at the {@code world.getChunk} redirect</h3>
     *
     * That is where the decision is taken, and the block is timed there as well,
     * so the sample point agrees with the decision point.
     *
     * <h3>The unit is an iteration, not a probe</h3>
     *
     * A block is 64 <b>iterations</b>, so {@code dE1Ns} is ns per iteration and the
     * frame effect is {@code dE1Ns} &times; 892. Only pass 1 is eligible, so the
     * eligible calls are 892 a frame, not 1784.
     */
    public static final boolean TARGET_ENTPASS1 = targets("ENTPASS1");

    /**
     * Prices the <b>pass-1 loop compaction</b> (pass 18).
     *
     * <h3>What the two arms are</h3>
     *
     * Pass 17 made an iteration pass 0 had found empty cheap; it still
     * <em>iterated</em> all 892 entries. Arm ON does not visit them at all: pass 0
     * appends the index of every entry whose section had entities to a compact
     * {@code int[]}, and pass 1's {@code for} loop is handed an iterator over just
     * those entries. Arm OFF is the shipped pass-17 shape (full 892-iteration walk,
     * empty ones answered from the hot array).
     *
     * <p>Both arms visit the same non-empty entries in the same order and run the
     * same bodies, so the scene is unchanged and the difference is pure removed
     * iterations.
     *
     * <h3>Why the unit is a pass-1 loop and not an iteration</h3>
     *
     * The two arms do not iterate the same number of times (892 vs ~15), so a
     * per-iteration mean would be meaningless. The flip point is therefore
     * {@code renderEntities}' pass-0 HEAD - once a frame - and the timed window is
     * the whole pass-1 loop, opened where the loop's iterator is created and closed
     * where the loop releases its pooled {@code BlockPos}. {@code dE2Ns} is
     * <b>ns per pass-1 loop</b>, which is already the per-frame figure.
     *
     * <p>A block of 64 pass-0 HEADs is 64 frames, and both passes of a frame share
     * the arm chosen at that frame's pass 0, so a block boundary can never fall
     * between the two calls that make up one paired observation.
     */
    public static final boolean TARGET_ENTPASS2 = targets("ENTPASS2");

    /**
     * Prices the <b>pass-0 entity-walk index gate</b> (pass 19).
     *
     * <h3>What the two arms are</h3>
     *
     * Pass 18 priced the two entity loops and found the one every pass since 16 had
     * been optimising (pass 1) is the <em>smaller</em> half: 0.14–0.26 ms/frame
     * against 0.99 ms for the pass-0 walk. Arm ON answers an iteration whose section
     * the {@link EntitySectionIndex} did not mark from the index — no
     * {@code Long2ObjectOpenHashMap} probe, no cold {@code Chunk} deref, no
     * {@code entityLists} deref — and hands the loop a permanently empty chunk.
     * Arm OFF is the vanilla shape: the real probe and the real deref chain.
     *
     * <p>Both arms render exactly the same entities, because the index is a
     * superset of the sections that hold any, so the scene is unchanged and the
     * difference is pure removed work.
     *
     * <h3>Why the flip is at the {@code world.getChunk} redirect</h3>
     *
     * That is where the decision is taken, and the block is timed there too, so the
     * sample point agrees with the decision point — the rule two earlier
     * instruments in this project were caught breaking.
     *
     * <h3>The unit is an iteration of pass 0</h3>
     *
     * A block is 64 <b>iterations</b> (892 a frame at rd 12), so {@code dEiNs} is ns
     * per iteration and the frame effect is {@code dEiNs} &times; 892. Only pass 0 is
     * eligible — pass 1 already visits ~10 entries through the ENTPASS2 compaction.
     */
    public static final boolean TARGET_ENTIDX = targets("ENTIDX");

    /**
     * Prices the pass-20 <b>per-frame render-info record</b> - one answer for the
     * two families of loops that walk {@code renderInfos} to ask each chunk the
     * same question.
     *
     * <h3>What the two arms are</h3>
     *
     * Arm ON answers from a per-frame record built during the frame's first
     * filterempty scan; arm OFF dereferences the real {@code CompiledChunk} at
     * every call, which is what production did before this pass and what the kill
     * switch {@code VULKANMOD_FLTMASK=0} restores. The OFF arm is therefore the
     * production reference, not a transcription of it.
     *
     * <h3>Two rows, two units, two sample points</h3>
     *
     * This target covers two consumers, so the {@code pab} line carries two column
     * pairs and they are <b>not</b> interchangeable:
     *
     * <ul>
     *   <li>{@code flOn}/{@code flOff}/{@code dFlNs} — one
     *       {@code CompiledChunk.isLayerEmpty} call on filterempty scans 2-4, i.e.
     *       ~3 &times; {@code ri} a frame. The build scan (scan 1) does identical
     *       work in both arms, so its blocks are not ticked at all.</li>
     *   <li>{@code ftOn}/{@code ftOff}/{@code dFtNs} — one
     *       {@code CompiledChunk.getTileEntities} call in the tile-entity loop,
     *       i.e. 2 &times; {@code ri} a frame.</li>
     * </ul>
     *
     * The arm is shared and ticked by both streams, so both consumers are
     * configured identically at any instant. Each consumer's block is closed by the
     * loop that produced it (the {@code func_194339_b} close for the scan, the
     * {@code renderEntities} RETURN for the tile loop), so neither can span the
     * inter-frame gap.
     */
    public static final boolean TARGET_FLTMASK = targets("FLTMASK");

    /**
     * Prices the pass-21 <b>tile-entity loop compaction</b> — pass 18's
     * {@code ENTPASS2} trick applied to the other loop in {@code renderEntities}
     * that walks all 892 render infos to ask each one a question whose answer is
     * "no" for 882 of them.
     *
     * <h3>What the two arms are</h3>
     *
     * Arm ON hands pass 1's tile for-each a {@code CompactIterator} over the
     * indices pass 0 recorded as having tile entities; arm OFF hands it
     * {@code renderInfos.iterator()} unchanged, which is exactly what production
     * does with {@code VULKANMOD_TILEIDX=0}. The entries visited, their order and
     * their bodies are identical in both arms — the removed iterations are the
     * ones whose entire body was {@code if (!list3.isEmpty())} and nothing else.
     *
     * <h3>The unit is a whole pass-1 loop, not an iteration</h3>
     *
     * The two arms do not iterate the same number of times, so a per-iteration
     * mean would be meaningless — the BFSMASK lesson about units, applied again.
     * The frame-level arm is chosen once at pass-0 HEAD (so both calls of a frame
     * agree), the window opens where pass 1's tile iterator is built and closes at
     * the {@code renderEntities} RETURN that owns the loop, so no window can span
     * the inter-frame gap. {@code dTileNs} is therefore ns per <b>loop</b> and is
     * already the per-frame figure: the tile loop runs twice a frame and only the
     * pass-1 half is compacted.
     *
     * <h3>Why this is not subject to the pass-20 harness limit</h3>
     *
     * Pass 20's rule is that a redirect-mediated record only pays when the work it
     * removes is several times the ~36 ns of redirect harness it needs per
     * iteration. This change is <b>one</b> redirect call per loop (the iterator
     * construction), not per iteration: it removes ~882 whole iterations and pays
     * for one. The price of the harness is paid by the iterations that remain.
     */
    public static final boolean TARGET_TILEIDX = targets("TILEIDX");

    /** Flip once per {@code ShaderInstance.apply()} call. */
    public static final boolean AT_APPLY =
            TARGET_APPLYREUSE || TARGET_REUSEALL || TARGET_APPTIME || TARGET_APPLYSPLIT;

    /**
     * How many hot calls run on one arm before it flips. <b>Every</b> flag uses 64;
     * a per-call flip is wrong for all three, for two different reasons.
     *
     * <h3>Reason 1 - the reuse flags are destroyed by a per-call flip</h3>
     *
     * {@code APPLYREUSE} and {@code REUSEALL} only fire when the shader's
     * {@code publishedSerial} still equals the global {@code applySerial}, i.e. when
     * <b>no other apply() has happened since the one that published the state</b>.
     * That is cross-call state, so with a per-call flip the ON arm's guard always
     * sees a serial bumped by the intervening OFF-arm apply and never reuses:
     *
     * <pre>
     *   call 1 (ON)  guard fails on serial -> applyFull -> publishes serial 1
     *   call 2 (OFF) guard disabled        -> applyFull -> bumps to serial 2
     *   call 3 (ON)  publishedSerial 1 != 2 -> fails again, forever
     * </pre>
     *
     * Measured for real: with a per-call flip, {@code aSkip} read <b>0</b> across a
     * whole run and the paired row said the flag cost +100…160 ns/call. Both arms
     * were in fact running with reuse fully disabled, so the number described the
     * instrument, not the flag. (Third time in this project that a new instrument's
     * first result was an artefact of the instrument - see {@code nextMemoArm()}.)
     *
     * <h3>Reason 2 - {@code CHUNKXF}'s flip point runs one call ahead of its window</h3>
     *
     * {@code CHUNKXF} has no cross-call state, so a per-call flip looks safe, and
     * pass 11 shipped one. It is not safe, because the flip happens at
     * {@code MatrixState.pushMatrix()} while the paired accumulator samples the arm
     * at {@code ShaderInstance.apply()}, and for a terrain section {@code apply()}
     * is reached <b>before</b> the {@code pushMatrix} that opens that section's
     * window. A per-call flip therefore labels every {@code apply()} with the
     * <em>previous</em> section's arm - a systematic one-event lag, not noise.
     *
     * <p>The measured tell was unmistakable: {@code dMatNs} came out correctly
     * negative (−65 ns/op, the saving is real and lands in the matrix stack where it
     * belongs) while {@code dNs} on the {@code apply()} window came out
     * <b>+268 ns/call</b> - a flag that removes work apparently <em>adding</em> cost -
     * and the arm call counts split 473/445 instead of the 484/484 every other run
     * produced. Mis-attribution moves calls from one arm's bucket to the other and
     * biases both means; it cannot move {@code matOps}, which is why the matrix row
     * stayed clean.
     *
     * <h3>Why 64 works for all three</h3>
     *
     * A block of 64 is long enough that (a) the one full apply at a reuse block's
     * head is amortised over 63 reuses, so the arm behaves as it does in production,
     * and (b) a one-event lag is 1/64 of a block, i.e. below the resolution of the
     * row. It is short enough that a frame's ~890 terrain draws still contain ~14
     * blocks, so both arms keep sharing one frame and one scene - the property the
     * whole technique rests on. Blocks are only ever advanced at an eligible flip
     * point, which for {@code CHUNKXF} is a {@code pushMatrix} that is about to open
     * a new window, so a block boundary can never fall inside a window.
     */
    private static final int BLOCK = 64;

    /** True when this run is A/B'ing the named flag (case-insensitive). */
    public static boolean targets(String flag) {
        return ACTIVE && FLAG.equalsIgnoreCase(flag);
    }

    /**
     * True on the arm where the flag behaves as it does by default — i.e. the
     * "optimisation enabled" arm. Starts true so the first sample of a run is
     * already a valid ON sample.
     */
    private static boolean arm = true;

    private static int sinceFlip;

    private PairedAB() {
    }

    /** The arm in effect for the call currently being measured. */
    public static boolean arm() {
        return arm;
    }

    /**
     * Accounts one hot call and returns the arm it should run under.
     *
     * <p>Flips <em>before</em> returning, so the returned value always agrees with
     * {@link #arm()}. Pass 10's memo instrument returned the arm before flipping and
     * the two arms came out swapped, which read as the memo being 46 ns
     * <em>slower</em>. Same trap, called out here so it is not hit a fourth time.
     */
    public static boolean nextArm() {
        if (++sinceFlip >= BLOCK) {
            sinceFlip = 0;
            arm = !arm;
        }
        return arm;
    }
}
