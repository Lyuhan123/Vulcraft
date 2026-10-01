package com.yuhan123.vulkanmod.vulkan.shader;

import com.yuhan123.vulkanmod.config.VulkanModConfig;
//import com.mojang.blaze3d.platform.GlStateManager;
import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
import com.yuhan123.vulkanmod.vulkan.framebuffer.RenderPass;
import net.minecraft.client.renderer.GlStateManager;

import static org.lwjgl.vulkan.VK10.*;

public class PipelineState {
    private static final int DEFAULT_DEPTH_OP = 515;
//    private static final int DEFAULT_DEPTH_OP = 518;

    public static BlendInfo blendInfo = PipelineState.defaultBlendInfo();

    public static final PipelineState DEFAULT = new PipelineState(getAssemblyRasterState(), getBlendState(), getDepthState(), getLogicOpState(), VRenderSystem.getColorMask(), VRenderSystem.alphaTest, null);

    public static PipelineState currentState = DEFAULT;

    /**
     * Every distinct state combination seen so far.
     *
     * The lookup runs once per draw, and the depth-prepass flow alternates
     * colorMask/depthMask between its two passes for every single chunk section -
     * so a cache-less implementation allocated two PipelineState objects per
     * chunk, i.e. thousands of objects per frame, purely to re-derive states it
     * had already built. These objects are immutable and their hash/equals are
     * value-based, so they double as stable keys for the per-pipeline handle map.
     */
    private static final java.util.List<PipelineState> STATE_CACHE = new java.util.ArrayList<>(32);

    private static final int MAX_CACHED_STATES = 64;

    /**
     * Opt-out for the input-snapshot memo (pass 10): {@code PIPESTATE=0}.
     *
     * <p>The lookup runs ~1800 times a frame - once per {@code apply()} through
     * the reuse guard and once per {@code flushPipelineBind()} - and each call
     * spent five state encodes (six blend fields, a depth switch, a logic-op
     * switch) to produce an answer that only changes ~12 times a frame.
     */
    private static final boolean MEMO_DEFAULT = VulkanModConfig.getBool("PIPESTATE", true);

    /**
     * Paired within-frame A/B ({@code MEMOAB=1}).
     *
     * <p>Exists because a two-launch A/B cannot resolve this change. Run
     * back-to-back in one session the memo measured −69 ns/call; run in the
     * reverse order it measured +23 ns/call. In both orders the <em>second</em>
     * run was the faster one, i.e. the effect is smaller than the run-order
     * effect, and the scene differs between launches (`iBatch`/`draws` are the
     * documented drifting counters).
     *
     * <p>With this on, {@link #nextMemoArm()} is called once per
     * {@code ShaderInstance.apply()} and the arm alternates per call, so the memo
     * and the full encode see the same frame, the same sections, the same
     * textures and the same cache state. The difference between the two arms'
     * per-call cost is then a paired statistic with the scene cancelled out.
     *
     * <p>Caveat: the snapshot is recorded on every call in both arms (the memo
     * arm needs it fresh), so this measures the encode+compare the memo removes,
     * not the 17 stores it also skips in a real {@code PIPESTATE=0} build. It is
     * therefore a conservative estimate.
     */
    private static final boolean MEMO_AB = VulkanModConfig.getBool("MEMOAB", false);

    /**
     * Whether the memo is consulted right now. Deliberately not {@code final}:
     * the paired A/B flips it per call. In normal play it is a constant, and a
     * non-final static boolean read is a single load either way.
     */
    private static boolean memoArmed = MEMO_DEFAULT;

    public static boolean memoAbEnabled() {
        return MEMO_AB;
    }

    /**
     * Arm for this call, flipping the arm for the next. A/B only.
     *
     * <p>Flips <em>first</em> and returns the value now in effect, because
     * {@link #getCurrentPipelineState} reads {@link #memoArmed} directly - the
     * returned arm has to be the one the guard will actually see. Returning the
     * previous value instead still alternates the two paths but silently swaps
     * the arms' labels, which reads as the memo being 46 ns <em>slower</em>.
     */
    public static boolean nextMemoArm() {
        memoArmed = !memoArmed;
        return memoArmed;
    }

    /** True when the raw-input snapshot is worth maintaining at all. */
    private static final boolean SNAPSHOT = MEMO_DEFAULT || MEMO_AB;

    /**
     * {@code PIPESTATE_VERIFY=1} re-derives the state on every memo hit
     * and counts disagreements. This is the correctness instrument for the memo,
     * and it is the only thing that can catch a missed input: run it once after
     * touching anything that writes a GL pipeline-state value.
     *
     * <p>Only meaningful on its own - it makes the hit/derive counters nonsense
     * because it drives both paths on every call.
     */
    private static final boolean MEMO_VERIFY = VulkanModConfig.getBool("PIPESTATE_VERIFY", false);

    /**
     * Snapshot of the raw GL inputs the last memoised result was derived from.
     *
     * <p>Why the raw inputs and not a version counter bumped from the setters:
     * the fields below are <em>the</em> source of truth, so comparing them cannot
     * miss an update however it was made. That matters here because
     * {@code GlStateManagerMixin} writes {@code VRenderSystem.alphaTest},
     * {@code .colorMask} and {@code .depthMask} <b>directly</b> (the cutout
     * depth-prepass toggles them per section), bypassing every setter. A counter
     * bumped only from {@code VRenderSystem} would have gone stale there and
     * silently bound the wrong pipeline - exactly the bug class this project has
     * hit before. The cost of the extra compares is ~10 ns/call, which is noise
     * next to the encoding work it removes.
     */
    private static boolean memoValid;
    private static RenderPass memoPass;
    private static boolean sCull, sBlendEnabled, sDepthTest, sDepthMask, sLogicOp, sAlphaTest;
    private static int sTopology, sPolygonMode, sBlendSrcRgb, sBlendDstRgb, sBlendSrcA, sBlendDstA,
            sBlendOp, sColorMask, sDepthFun, sLogicOpFun;

    public static PipelineState getCurrentPipelineState(RenderPass renderPass) {
        if (memoArmed && inputsUnchanged(renderPass)) {
            com.yuhan123.vulkanmod.render.util.FrameProfiler.onPipelineStateMemoHit();

            if (MEMO_VERIFY) {
                // Capture the memo's answer first: derive() reassigns
                // currentState, so comparing after the call would always agree
                // and the check would be vacuous.
                final PipelineState memoised = currentState;

                if (derive(renderPass) != memoised) {
                    com.yuhan123.vulkanmod.render.util.FrameProfiler.onPipelineStateMemoMismatch();
                }
            }

            return currentState;
        }

        return derive(renderPass);
    }

    /**
     * True when every input the memoised state was built from still holds the
     * value it had then, against the same render pass. A false positive would
     * draw with a stale pipeline, so every input is checked.
     */
    private static boolean inputsUnchanged(RenderPass renderPass) {
        if (!memoValid || memoPass != renderPass) {
            return false;
        }

        final BlendInfo b = blendInfo;

        return sCull == VRenderSystem.cull
               && sTopology == VRenderSystem.topology
               && sPolygonMode == VRenderSystem.polygonMode
               && sBlendEnabled == b.enabled
               && sBlendSrcRgb == b.srcRgbFactor
               && sBlendDstRgb == b.dstRgbFactor
               && sBlendSrcA == b.srcAlphaFactor
               && sBlendDstA == b.dstAlphaFactor
               && sBlendOp == b.blendOp
               && sColorMask == VRenderSystem.colorMask
               && sDepthTest == VRenderSystem.depthTest
               && sDepthMask == VRenderSystem.depthMask
               && sDepthFun == VRenderSystem.depthFun
               && sLogicOp == VRenderSystem.logicOp
               && sLogicOpFun == VRenderSystem.logicOpFun
               && sAlphaTest == VRenderSystem.alphaTest;
    }

    /** Records the inputs the result about to be produced is derived from. */
    private static void recordSnapshot(RenderPass renderPass) {
        final BlendInfo b = blendInfo;

        sCull = VRenderSystem.cull;
        sTopology = VRenderSystem.topology;
        sPolygonMode = VRenderSystem.polygonMode;
        sBlendEnabled = b.enabled;
        sBlendSrcRgb = b.srcRgbFactor;
        sBlendDstRgb = b.dstRgbFactor;
        sBlendSrcA = b.srcAlphaFactor;
        sBlendDstA = b.dstAlphaFactor;
        sBlendOp = b.blendOp;
        sColorMask = VRenderSystem.colorMask;
        sDepthTest = VRenderSystem.depthTest;
        sDepthMask = VRenderSystem.depthMask;
        sDepthFun = VRenderSystem.depthFun;
        sLogicOp = VRenderSystem.logicOp;
        sLogicOpFun = VRenderSystem.logicOpFun;
        sAlphaTest = VRenderSystem.alphaTest;

        memoPass = renderPass;
        memoValid = true;
    }

    /** The original lookup: five encodes, then the current-state and cache walks. */
    private static PipelineState derive(RenderPass renderPass) {
        int assemblyRasterState = getAssemblyRasterState();
        int blendState = getBlendState();
        int currentColorMask = VRenderSystem.getColorMask();
        int depthState = getDepthState();
        int logicOp = getLogicOpState();
        boolean alphaTest = VRenderSystem.alphaTest;

        // The raw inputs are still exactly what the encodes above were built
        // from, so this snapshot is consistent with the result below.
        if (SNAPSHOT) {
            recordSnapshot(renderPass);
        }

        if (currentState.checkEquals(assemblyRasterState, blendState, depthState, logicOp, currentColorMask, alphaTest, renderPass)) {
            com.yuhan123.vulkanmod.render.util.FrameProfiler.onPipelineStateHit();
            return currentState;
        }

        // The encodes above did not match the current state, so the cache is
        // walked. Reaching here ~12 times a frame (against ~1800 calls) is the
        // point of the memo above.
        com.yuhan123.vulkanmod.render.util.FrameProfiler.onPipelineStateDerive();

        for (int i = 0; i < STATE_CACHE.size(); ++i) {
            PipelineState cached = STATE_CACHE.get(i);
            if (cached.checkEquals(assemblyRasterState, blendState, depthState, logicOp, currentColorMask, alphaTest, renderPass)) {
                return currentState = cached;
            }
        }

        PipelineState created = new PipelineState(assemblyRasterState, blendState, depthState, logicOp, currentColorMask, alphaTest, renderPass);

        // Bounded: the set of states the game actually uses is tiny, but a
        // pathological sequence must not grow this without limit.
        if (STATE_CACHE.size() < MAX_CACHED_STATES) {
            STATE_CACHE.add(created);
        }

        return currentState = created;
    }

    /**
     * Drops the cache. The cached states hold a hard reference to their
     * RenderPass, which the swapchain recreation destroys, so the cache must not
     * outlive it.
     */
    public static void clearStateCache() {
        STATE_CACHE.clear();
        currentState = DEFAULT;

        // The memo's result references the destroyed pass, and its snapshot would
        // otherwise look current against the new one.
        memoValid = false;
    }

    public static int getBlendState() {
        return BlendState.getState(blendInfo);
    }

    public static int getAssemblyRasterState() {
        return AssemblyRasterState.encode(VRenderSystem.cull, VRenderSystem.topology, VRenderSystem.polygonMode);
    }

    public static int getDepthState() {
        int depthState = 0;

        depthState |= VRenderSystem.depthTest ? DepthState.DEPTH_TEST_BIT : 0;
        depthState |= VRenderSystem.depthMask ? DepthState.DEPTH_MASK_BIT : 0;

        depthState |= DepthState.encodeDepthFun(VRenderSystem.depthFun);

        return depthState;
    }

    public static int getLogicOpState() {
        int logicOpState = 0;

        logicOpState |= VRenderSystem.logicOp ? LogicOpState.ENABLE_BIT : 0;

        logicOpState |= LogicOpState.encodeLogicOpFun(VRenderSystem.logicOpFun);

        return logicOpState;
    }

    final RenderPass renderPass;

    int assemblyRasterState;
    int blendState_i;
    int depthState_i;
    int colorMask_i;
    int logicOp_i;
    boolean alphaTest_i;

    /**
     * Precomputed hash. The state fields are immutable, and the pipeline lookup
     * hits hashCode() on every draw call, so recomputing it (previously via
     * Objects.hash, which allocates an Object[] and boxes every int) is pure
     * per-draw garbage.
     */
    private final int hash;

    public PipelineState(int assemblyRasterState, int blendState, int depthState, int logicOp, int colorMask,
                         boolean alphaTest, RenderPass renderPass) {
        this.renderPass = renderPass;

        this.assemblyRasterState = assemblyRasterState;
        this.blendState_i = blendState;
        this.depthState_i = depthState;
        this.colorMask_i = colorMask;
        this.logicOp_i = logicOp;
        this.alphaTest_i = alphaTest;

        int h = 1;
        h = 31 * h + blendState;
        h = 31 * h + depthState;
        h = 31 * h + logicOp;
        h = 31 * h + assemblyRasterState;
        h = 31 * h + colorMask;
        h = 31 * h + (alphaTest ? 1 : 0);
        h = 31 * h + (renderPass != null ? renderPass.hashCode() : 0);
        this.hash = h;
    }

    /** GL fixed-function alpha test enable bit at draw time (see VRenderSystem.alphaTest). */
    public boolean alphaTest() {
        return this.alphaTest_i;
    }

    /** Packed VK color-component write mask at draw time. */
    public int colorMask() {
        return this.colorMask_i;
    }

    /** Depth-write enable at draw time (depthMask). */
    public boolean depthMask() {
        return DepthState.depthMask(this.depthState_i);
    }

    /** True when the depth compare op is VK_COMPARE_OP_EQUAL. */
    public boolean depthEqual() {
        return DepthState.decodeDepthFun(this.depthState_i) == VK_COMPARE_OP_EQUAL;
    }

    private boolean checkEquals(int assemblyRasterState, int blendState, int depthState, int logicOp, int colorMask,
                                boolean alphaTest, RenderPass renderPass) {
        return (blendState == this.blendState_i) && (depthState == this.depthState_i)
               && renderPass == this.renderPass && logicOp == this.logicOp_i
               && (assemblyRasterState == this.assemblyRasterState)
               && colorMask == this.colorMask_i
               && alphaTest == this.alphaTest_i;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        PipelineState that = (PipelineState) o;
        return (blendState_i == that.blendState_i) && (depthState_i == that.depthState_i)
               && this.renderPass == that.renderPass && logicOp_i == that.logicOp_i
               && this.assemblyRasterState == that.assemblyRasterState
               && this.colorMask_i == that.colorMask_i
               && this.alphaTest_i == that.alphaTest_i;
    }

    @Override
    public int hashCode() {
        return this.hash;
    }

    public static BlendInfo defaultBlendInfo() {
        return new BlendInfo(true, VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
                             VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD);
    }

    public static class BlendInfo {
        public boolean enabled;
        public int srcRgbFactor;
        public int dstRgbFactor;
        public int srcAlphaFactor;
        public int dstAlphaFactor;
        public int blendOp;

        public BlendInfo(boolean enabled, int srcRgbFactor, int dstRgbFactor, int srcAlphaFactor, int dstAlphaFactor,
                         int blendOp) {
            this.enabled = enabled;
            this.srcRgbFactor = srcRgbFactor;
            this.dstRgbFactor = dstRgbFactor;
            this.srcAlphaFactor = srcAlphaFactor;
            this.dstAlphaFactor = dstAlphaFactor;
            this.blendOp = blendOp;
        }

//        public void setBlendFunction(GlStateManager.SourceFactor sourceFactor, GlStateManager.DestFactor destFactor) {
//            this.srcRgbFactor = glToVulkanBlendFactor(sourceFactor.value);
//            this.srcAlphaFactor = glToVulkanBlendFactor(sourceFactor.value);
//            this.dstRgbFactor = glToVulkanBlendFactor(destFactor.value);
//            this.dstAlphaFactor = glToVulkanBlendFactor(destFactor.value);
//        }
//
//        public void setBlendFuncSeparate(GlStateManager.SourceFactor srcRgb, GlStateManager.DestFactor dstRgb,
//                                         GlStateManager.SourceFactor srcAlpha, GlStateManager.DestFactor dstAlpha) {
//            this.srcRgbFactor = glToVulkanBlendFactor(srcRgb.value);
//            this.srcAlphaFactor = glToVulkanBlendFactor(srcAlpha.value);
//            this.dstRgbFactor = glToVulkanBlendFactor(dstRgb.value);
//            this.dstAlphaFactor = glToVulkanBlendFactor(dstAlpha.value);
//        }

        /* gl to Vulkan conversion */
        public void setBlendFunction(int sourceFactor, int destFactor) {
            this.srcRgbFactor = glToVulkanBlendFactor(sourceFactor);
            this.srcAlphaFactor = glToVulkanBlendFactor(sourceFactor);
            this.dstRgbFactor = glToVulkanBlendFactor(destFactor);
            this.dstAlphaFactor = glToVulkanBlendFactor(destFactor);
        }

        /* gl to Vulkan conversion */
        public void setBlendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
            this.srcRgbFactor = glToVulkanBlendFactor(srcRgb);
            this.srcAlphaFactor = glToVulkanBlendFactor(srcAlpha);
            this.dstRgbFactor = glToVulkanBlendFactor(dstRgb);
            this.dstAlphaFactor = glToVulkanBlendFactor(dstAlpha);
        }

        public void setBlendOp(int i) {
            this.blendOp = glToVulkanBlendOp(i);
        }


        public int createBlendState() {
            return BlendState.getState(this);
        }

        private static int glToVulkanBlendOp(int value) {
            return switch (value) {
                case 0x8006 -> VK_BLEND_OP_ADD;
                case 0x8007 -> VK_BLEND_OP_MIN;
                case 0x8008 -> VK_BLEND_OP_MAX;
                case 0x800A -> VK_BLEND_OP_SUBTRACT;
                case 0x800B -> VK_BLEND_OP_REVERSE_SUBTRACT;
                default -> throw new RuntimeException("unknown blend factor: " + value);


//                GL_FUNC_ADD = 0x8006,
//                GL_MIN      = 0x8007,
//                GL_MAX      = 0x8008;
//                GL_FUNC_SUBTRACT         = 0x800A,
//                GL_FUNC_REVERSE_SUBTRACT = 0x800B;
            };
        }

        private static int glToVulkanBlendFactor(int value) {
            return switch (value) {
                case 1 -> VK_BLEND_FACTOR_ONE;
                case 0 -> VK_BLEND_FACTOR_ZERO;
                case 771 -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
                case 770 -> VK_BLEND_FACTOR_SRC_ALPHA;
                case 775 -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
                case 769 -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
                case 774 -> VK_BLEND_FACTOR_DST_COLOR;
                case 768, 772 -> VK_BLEND_FACTOR_SRC_COLOR;
                default -> throw new RuntimeException("unknown blend factor: " + value);


//                        CONSTANT_ALPHA(32771),
//                        CONSTANT_COLOR(32769),
//                        DST_ALPHA(772),
//                        DST_COLOR(774),
//                        ONE(1),
//                        ONE_MINUS_CONSTANT_ALPHA(32772),
//                        ONE_MINUS_CONSTANT_COLOR(32770),
//                        ONE_MINUS_DST_ALPHA(773),
//                        ONE_MINUS_DST_COLOR(775),
//                        ONE_MINUS_SRC_ALPHA(771),
//                        ONE_MINUS_SRC_COLOR(769),
//                        SRC_ALPHA(770),
//                        SRC_ALPHA_SATURATE(776),
//                        SRC_COLOR(768),
//                        ZERO(0);
            };
        }
    }

    public static class BlendState {
        public static final int SRC_RGB_OFFSET = 0;
        public static final int DST_RGB_OFFSET = 5;
        public static final int SRC_A_OFFSET = 10;
        public static final int DST_A_OFFSET = 15;
        public static final int FUN_OFFSET = 20;

        public static final int ENABLE_BIT = 1 << 24;

        public static final int OP_MASK = 0xF;
        public static final int FACTOR_MASK = 0x1F;

        public static int getState(BlendInfo blendInfo) {
            int s = 0;
            s |= blendInfo.enabled ? ENABLE_BIT : 0;
            s |= encode(blendInfo.srcRgbFactor, SRC_RGB_OFFSET, FACTOR_MASK);
            s |= encode(blendInfo.dstRgbFactor, DST_RGB_OFFSET, FACTOR_MASK);
            s |= encode(blendInfo.srcAlphaFactor, SRC_A_OFFSET, FACTOR_MASK);
            s |= encode(blendInfo.dstAlphaFactor, DST_A_OFFSET, FACTOR_MASK);
            s |= encode(blendInfo.blendOp, FUN_OFFSET, OP_MASK);

            return s;
        }

        public static boolean enable(int i) {
            return (i & ENABLE_BIT) != 0;
        }

        public static int encode(int i, int offset, int mask) {
            return (i & mask) << offset;
        }

        public static int decode(int i, int offset, int bits) {
            return (i >>> offset) & bits;
        }

        public static int getSrcRgbFactor(int s) {
            return decode(s, SRC_RGB_OFFSET, FACTOR_MASK);
        }

        public static int getDstRgbFactor(int s) {
            return decode(s, DST_RGB_OFFSET, FACTOR_MASK);
        }

        public static int getSrcAlphaFactor(int s) {
            return decode(s, SRC_A_OFFSET, FACTOR_MASK);
        }

        public static int getDstAlphaFactor(int s) {
            return decode(s, DST_A_OFFSET, FACTOR_MASK);
        }

        public static int blendOp(int state) {
            return decode(state, FUN_OFFSET, OP_MASK);
        }

    }

    public abstract static class LogicOpState {
        public static final int ENABLE_BIT = 1;

        public static final int FUN_OFFSET = 1;
        public static final int FUN_BITS = 5;

        public static boolean enable(int i) {
            return (i & ENABLE_BIT) != 0;
        }

        public static int encodeLogicOpFun(int glFun) {
            int fun = glToVulkan(glFun);

            return fun << FUN_OFFSET;
        }

        public static int decodeFun(int state) {
            return state >>> FUN_OFFSET;
        }

        public static int glToVulkan(int f) {
            return switch (f) {
                case 5387 -> VK_LOGIC_OP_OR_REVERSE;
                //TODO complete

                default -> VK_LOGIC_OP_AND;
            };
        }

    }

    public abstract static class AssemblyRasterState {
        public static final int POLYGON_MODE_MASK = 7;

        public static final int TOPOLOGY_OFFSET = 3;
        public static final int TOPOLOGY_BITS = 4;
        public static final int TOPOLOGY_MASK = 0b11111;

        public static final int CULL_MODE_OFFSET = TOPOLOGY_OFFSET + TOPOLOGY_BITS;
        public static final int CULL_MODE_BITS = 2;
        public static final int CULL_MODE_MASK = 0b11;

        public static int encode(boolean cull, int topology, int polygonMode) {
            int state = (polygonMode | (topology << TOPOLOGY_OFFSET));
            state |= ((cull ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE) << CULL_MODE_OFFSET);

            return state;
        }

        public static int decodeTopology(int state) {
            return (state >>> TOPOLOGY_OFFSET) & TOPOLOGY_MASK;
        }

        public static int decodePolygonMode(int state) {
            return state & POLYGON_MODE_MASK;
        }

        public static int decodeCullMode(int state) {
            return (state >>> CULL_MODE_OFFSET) & CULL_MODE_MASK;
        }
    }

    public static abstract class ColorMask {

        public static int getColorMask(boolean r, boolean g, boolean b, boolean a) {
            return (r ? VK_COLOR_COMPONENT_R_BIT : 0)
                   | (g ? VK_COLOR_COMPONENT_G_BIT : 0)
                   | (b ? VK_COLOR_COMPONENT_B_BIT : 0)
                   | (a ? VK_COLOR_COMPONENT_A_BIT : 0);
        }

    }

    public static abstract class DepthState {
        public static final int DEPTH_TEST_BIT = 1;
        public static final int DEPTH_MASK_BIT = 2;

        public static final int DEPTH_FUN_OFFSET = 2;
        public static final int DEPTH_FUN_BITS = 4;

        public static boolean depthTest(int i) {
            return (i & DEPTH_TEST_BIT) != 0;
        }

        public static boolean depthMask(int i) {
            return (i & DEPTH_MASK_BIT) != 0;
        }

        public static int encodeDepthFun(int glFun) {
            int fun = glToVulkan(glFun);

            return fun << DEPTH_FUN_OFFSET;
        }

        public static int decodeDepthFun(int state) {
            return state >>> DEPTH_FUN_OFFSET;
        }

        private static int glToVulkan(int value) {
            return switch (value) {
                case 515 -> VK_COMPARE_OP_LESS_OR_EQUAL;
                case 519 -> VK_COMPARE_OP_ALWAYS;
                case 516 -> VK_COMPARE_OP_GREATER;
                case 518 -> VK_COMPARE_OP_GREATER_OR_EQUAL;
                case 514 -> VK_COMPARE_OP_EQUAL;
                default -> throw new RuntimeException("unknown blend factor..");

//                case 515 -> VK_COMPARE_OP_GREATER_OR_EQUAL;
//                case 519 -> VK_COMPARE_OP_ALWAYS;
//                case 516 -> VK_COMPARE_OP_GREATER;
//                case 518 -> VK_COMPARE_OP_LESS_OR_EQUAL;
//                case 514 -> VK_COMPARE_OP_EQUAL;
//                default -> throw new RuntimeException("unknown blend factor..");

//                public static final int GL_NEVER = 512;
//                public static final int GL_LESS = 513;
//                public static final int GL_EQUAL = 514;
//                public static final int GL_LEQUAL = 515;
//                public static final int GL_GREATER = 516;
//                public static final int GL_NOTEQUAL = 517;
//                public static final int GL_GEQUAL = 518;
//                public static final int GL_ALWAYS = 519;
            };
        }

    }
}
