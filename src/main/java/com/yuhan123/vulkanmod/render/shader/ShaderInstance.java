package com.yuhan123.vulkanmod.render.shader;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.gl.VkGlProgram;
import com.yuhan123.vulkanmod.gl.VkGlTexture;
import com.yuhan123.vulkanmod.render.PipelineManager;
import com.yuhan123.vulkanmod.render.shader.ShaderLoadUtil;
import com.yuhan123.vulkanmod.render.shader.VkUniform;
import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import com.yuhan123.vulkanmod.render.util.PairedAB;
import com.yuhan123.vulkanmod.vulkan.Drawer;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
import com.yuhan123.vulkanmod.vulkan.framebuffer.RenderPass;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.index.AutoIndexBuffer;
import com.yuhan123.vulkanmod.vulkan.shader.GraphicsPipeline;
import com.yuhan123.vulkanmod.vulkan.shader.Pipeline;
import com.yuhan123.vulkanmod.vulkan.shader.PipelineState;
import com.yuhan123.vulkanmod.vulkan.shader.converter.GlslConverter;
import com.yuhan123.vulkanmod.vulkan.shader.descriptor.UBO;
import com.yuhan123.vulkanmod.vulkan.shader.layout.Uniform;
import com.yuhan123.vulkanmod.vulkan.texture.VTextureSelector;
import com.yuhan123.vulkanmod.vulkan.util.MappedBuffer;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.shader.ShaderLoader;
import net.minecraft.client.util.JsonException;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.JsonUtils;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.Display;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Unique;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.util.*;
import java.util.function.Supplier;

import static com.yuhan123.vulkanmod.gl.VkGlTexture.activeTexture;
import static com.yuhan123.vulkanmod.render.shader.ShaderLoadUtil.getResource;

public class ShaderInstance {
    private final String name;
    private final VertexFormat vertexFormat;
    private final List<VkUniform> uniforms = Lists.newArrayList();
    //    private final List<Integer> attribLocations;
    private List<String> attributes = new ArrayList<>();
    private final Map<String, Object> samplerMap = Maps.newHashMap();
    private final List<String> samplerNames = Lists.newArrayList();
    /** Pre-resolved sampler values, parallel to {@link #samplerNames}. The map is
     *  only touched during construction so the hot {@link #apply()} loop never hashes
     *  a String key. A null entry means "this slot has no sampler"; the loop skips it. */
    private Object[] samplerValues;
    private GraphicsPipeline pipeline;
    private static int lastProgramId = -1;

    /**
     * Whether {@link #pipeline} declares any push constants at all.
     *
     * <p>Cached because the per-draw publish has to run <em>before</em> the reuse
     * test (the MVP rides in a push constant for every shader), and the cheap
     * version of that is a boolean test rather than a call into {@code Pipeline}
     * on every section.
     *
     * <p>Refreshed wherever {@code pipeline} is assigned - see
     * {@link #updatePushConstantFlag()}.
     */
    private boolean hasPushConstants;

    /**
     * Serial of the most recent {@link #apply()} that actually published state,
     * across every ShaderInstance. A shader can only reuse the state it published
     * if nothing else has published since - see {@link #canReuseTerrainState()}.
     */
    private static int applySerial;

    /**
     * Benchmark switch for the terrain apply-reuse fast path. Default ON; set
     * {@code VULKANMOD_APPLYREUSE=0} to force every chunk section through a full
     * apply(), which is what the A/B harness compares against.
     */
    private static final boolean REUSE_TERRAIN_STATE =
            !"0".equals(System.getenv("VULKANMOD_APPLYREUSE"));

    /**
     * Whether the reuse fast path is allowed beyond the terrain pipeline.
     * Default ON; {@code VULKANMOD_REUSEALL=0} restores the historical gate
     * (terrain pipeline only, and only while the chunk arena is active), which
     * is what the A/B harness compares against.
     *
     * <p>Every shader here is eligible on the merits - see {@link #reuseEligible}
     * - so the historical gate was leaving the whole entity/display-list path
     * (187 applies/frame, ~0.4 ms) on the full path for no reason.
     */
    private static final boolean REUSE_ALL =
            !"0".equals(System.getenv("VULKANMOD_REUSEALL"));

    /**
     * Uniform names whose values are covered by
     * {@link VRenderSystem#getUniformVersion()}. A shader whose uniform set is a
     * subset of these has no per-draw state that the reuse conditions do not
     * already check: the only other per-draw input is the MVP, which is a push
     * constant (or the arena's per-instance storage buffer) and is published by
     * {@code apply()} <em>outside</em> the reuse test.
     *
     * <p>Derived from the shader JSON rather than hardcoded, so a future shader
     * declaring anything else is excluded automatically instead of silently
     * drawing with stale uniforms.
     */
    private static final java.util.Set<String> VERSIONED_UNIFORMS =
            java.util.Set.of("ColorModulator", "FogColor", "FogStart", "FogEnd");
    /** True when every uniform this shader declares is version-checked. */
    private boolean reuseEligible;

    /** Serial this instance last published its state under, or -1 if never. */
    private int publishedSerial = -1;
    private int publishedEpoch = -1;
    /** {@link Renderer#getBindingEpoch()} at the last publish. See canReuseTerrainState. */
    private int publishedBindingEpoch = -1;
    private int publishedBindVersion = -1;
    private int publishedUniformVersion = -1;
    private PipelineState publishedState;

    private final List<Integer> samplerLocations = Lists.newArrayList();
    private final List<Integer> uniformLocations = Lists.newArrayList();
    public final Map<String, VkUniform> uniformMap = Maps.newHashMap();

    private String fsName;
    private String vsPath;
    boolean doUniformUpdate = false;

//    private GraphicsPipeline pipeline;

    @Nullable
    public final VkUniform MODEL_VIEW_MATRIX;
    @Nullable
    public final VkUniform PROJECTION_MATRIX;
    @Nullable
    public final VkUniform TEXTURE_MATRIX;
    @Nullable
    public final VkUniform SCREEN_SIZE;
    @Nullable
    public final VkUniform COLOR_MODULATOR;
    @Nullable
    public final VkUniform LIGHT0_DIRECTION;
    @Nullable
    public final VkUniform LIGHT1_DIRECTION;
    @Nullable
    public final VkUniform GLINT_ALPHA;
    @Nullable
    public final VkUniform FOG_START;
    /** Per-draw lightmap coordinate the entity shaders sample Sampler2 at. */
    public final VkUniform LIGHTMAP_COORD;
    @Nullable
    public final VkUniform FOG_END;
    @Nullable
    public final VkUniform FOG_COLOR;
    @Nullable
    public final VkUniform FOG_SHAPE;
    @Nullable
    public final VkUniform LINE_WIDTH;
    @Nullable
    public final VkUniform GAME_TIME;
    @Nullable
    public final VkUniform CHUNK_OFFSET;
    private boolean dirty;

//    private boolean doUniformUpdate = false;
    private int programId;

    public GraphicsPipeline getPipeline() {
        return pipeline;
    }

    /** TEMPORARY: diagnostics only - see TextureProbe. */
    public String getName() {
        return this.name;
    }

    public ShaderInstance(String programName, VertexFormat vertexFormat) throws IOException {
        this.name = programName;
        this.vertexFormat = vertexFormat;
        JsonParser jsonparser = new JsonParser();
        ResourceLocation resourceLocation = new ResourceLocation("vulkanmod:shaders/core/" + programName + "/" + programName + ".json");
        InputStream iresource = null;

        try {
            iresource = getResource(resourceLocation);
            JsonObject jsonObject = jsonparser.parse(IOUtils.toString(iresource, StandardCharsets.UTF_8)).getAsJsonObject();
            String s = JsonUtils.getString(jsonObject, "vertex");
            String s1 = JsonUtils.getString(jsonObject, "fragment");
            JsonArray jsonArray = JsonUtils.getJsonArray(jsonObject, "samplers", null);
            if (jsonArray != null) {
                int i = 0;

                for (JsonElement jsonElement : jsonArray) {
                    try {
                        this.parseSampler(jsonElement);
                    } catch (Exception exception) {
                        JsonException jsonexception1 = JsonException.forException(exception);
                        jsonexception1.prependJsonKey("samplers[" + i + "]");
                        throw jsonexception1;
                    }

                    ++i;
                }
            }
            JsonArray jsonarray1 = JsonUtils.getJsonArray(jsonObject, "attributes", null);

            if (jsonarray1 != null) {
                int j = 0;
//            this.attribLocations = Lists.newArrayListWithCapacity(jsonarray1.size());
                this.attributes = Lists.newArrayListWithCapacity(jsonarray1.size());

                for (JsonElement jsonelement1 : jsonarray1) {
                    try {
                        this.attributes.add(JsonUtils.getString(jsonelement1, "attribute"));
                    } catch (Exception exception1) {
                        JsonException jsonexception2 = JsonException.forException(exception1);
                        jsonexception2.prependJsonKey("attributes[" + j + "]");
                        throw jsonexception2;
                    }

                    j++;
                }
            } else {
//            this.attribLocations = null;
                this.attributes = null;
            }

            JsonArray jsonArray2 = GsonHelper.getAsJsonArray(jsonObject, "uniforms", (JsonArray) null);
            if (jsonArray2 != null) {
                int j = 0;

                for (JsonElement jsonElement2 : jsonArray2) {
                    try {
                        this.parseUniform(jsonElement2);
                    } catch (Exception exception2) {
                        throw new RuntimeException(exception2);
                    }

                    ++j;
                }
            }

            getOrCreate(ShaderLoader.ShaderType.VERTEX, s);
            getOrCreate(ShaderLoader.ShaderType.FRAGMENT, s1);
            this.programId = OpenGlHelper.glCreateProgram();
            int j = 0;

//        for(String string4 : vertexFormat.getElementAttributeNames()) {
//            Uniform.glBindAttribLocation(this.programId, j, string4);
//            ++j;
//        }

//        ProgramManager.linkShader(this);
            this.updateLocations();
        } catch (Exception exception3) {
            throw new RuntimeException(exception3);
        }

        this.markDirty();
        this.MODEL_VIEW_MATRIX = this.getUniform("ModelViewMat");
        this.PROJECTION_MATRIX = this.getUniform("ProjMat");
        this.TEXTURE_MATRIX = this.getUniform("TextureMat");
        this.SCREEN_SIZE = this.getUniform("ScreenSize");
        this.COLOR_MODULATOR = this.getUniform("ColorModulator");
        this.LIGHT0_DIRECTION = this.getUniform("Light0_Direction");
        this.LIGHT1_DIRECTION = this.getUniform("Light1_Direction");
        this.GLINT_ALPHA = this.getUniform("GlintAlpha");
        this.FOG_START = this.getUniform("FogStart");
        this.LIGHTMAP_COORD = this.getUniform("LightmapCoord");
        this.FOG_END = this.getUniform("FogEnd");
        this.FOG_COLOR = this.getUniform("FogColor");
        this.FOG_SHAPE = this.getUniform("FogShape");
        this.LINE_WIDTH = this.getUniform("LineWidth");
        this.GAME_TIME = this.getUniform("GameTime");
        this.CHUNK_OFFSET = this.getUniform("ChunkOffset");

        create(programName, vertexFormat);
    }


    private void create(String name, VertexFormat format) {
        String configName = name;
        JsonObject config = ShaderLoadUtil.getJsonConfig("core", configName);

        if (config == null) {
            createLegacyShader(format);
        } else {
            createPipeline(configName, format, config);
        }

        VkGlProgram program = VkGlProgram.getProgram(this.programId);
        program.bindPipeline(this.pipeline);
    }


    public void markDirty() {
        this.dirty = true;
    }

    public VkUniform getUniform(String string) {
        return this.uniformMap.get(string);
    }

    private void parseSampler(JsonElement element) throws JsonException {
        JsonObject jsonobject = JsonUtils.getJsonObject(element, "sampler");
        String s = JsonUtils.getString(jsonobject, "name");

        if (!JsonUtils.isString(jsonobject, "file")) {
            this.samplerMap.put(s, null);
            this.samplerNames.add(s);
        } else {
            this.samplerNames.add(s);
        }
    }


    private void parseUniform(JsonElement jsonElement) {
        JsonObject jsonObject = GsonHelper.convertToJsonObject(jsonElement, "uniform");
        String string = GsonHelper.getAsString(jsonObject, "name");
        int i = VkUniform.getTypeFromString(GsonHelper.getAsString(jsonObject, "type"));
        int j = GsonHelper.getAsInt(jsonObject, "count");
        float[] fs = new float[Math.max(j, 16)];
        JsonArray jsonArray = GsonHelper.getAsJsonArray(jsonObject, "values");
        if (jsonArray.size() != j && jsonArray.size() > 1) {
//            throw new ChainedJsonException("Invalid amount of values specified (expected " + j + ", found " + jsonArray.size() + ")");
        } else {
            int k = 0;

            for (JsonElement jsonElement2 : jsonArray) {
                try {
                    fs[k] = GsonHelper.convertToFloat(jsonElement2, "value");
                } catch (Exception exception) {
//                    ChainedJsonException chainedJsonException = ChainedJsonException.forException(exception);
//                    chainedJsonException.prependJsonKey("values[" + k + "]");
                    throw new RuntimeException(exception);
                }

                ++k;
            }

            if (j > 1 && jsonArray.size() == 1) {
                while (k < j) {
                    fs[k] = fs[0];
                    ++k;
                }
            }

            int l = j > 1 && j <= 4 && i < 8 ? j - 1 : 0;
            VkUniform uniform = new VkUniform(string, i + l, j);
            if (i <= 3) {
                uniform.setSafe((int) fs[0], (int) fs[1], (int) fs[2], (int) fs[3]);
            } else if (i <= 7) {
                uniform.setSafe(fs[0], fs[1], fs[2], fs[3]);
            } else {
                uniform.set(Arrays.copyOfRange(fs, 0, j));
            }

            this.uniforms.add(uniform);
        }
    }

    private void updateLocations() {
//        RenderSystem.assertOnRenderThread();
        IntList intList = new IntArrayList();

        for (int i = 0; i < this.samplerNames.size(); ++i) {
            String string = (String) this.samplerNames.get(i);
            int j = 1;
            if (j == -1) {
                VulkanMod.LOGGER.warn("Shader {} could not find sampler named {} in the specified shader program.", this.name, string);
                this.samplerMap.remove(string);
                intList.add(i);
            } else {
                this.samplerLocations.add(j);
            }
        }

        for (int i = intList.size() - 1; i >= 0; --i) {
            int k = intList.getInt(i);
            this.samplerNames.remove(k);
        }

        for (VkUniform uniform : uniforms) {
            String string2 = uniform.getName();
            int l = 1;
            if (l == -1) {
                VulkanMod.LOGGER.warn("Shader {} could not find uniform named {} in the specified shader program.", this.name, string2);
            } else {
                this.uniformLocations.add(l);
                uniform.setLocation(l);
                this.uniformMap.put(string2, uniform);

            }
        }

        // Freeze the sampler bindings so the hot apply() loop never has to hash
        // a String key. After updateLocations() has trimmed samplerNames for any
        // location we could not find, samplerValues is in one-to-one slot order.
        this.samplerValues = new Object[this.samplerNames.size()];
        for (int i = 0; i < this.samplerNames.size(); ++i) {
            this.samplerValues[i] = this.samplerMap.get(this.samplerNames.get(i));
        }

        // Eligibility for the apply-reuse fast path: every uniform this shader
        // declares must be one whose value changes bump the uniform version.
        // Everything else the skipped code publishes - the pipeline, the
        // descriptor sets, the bound textures - is covered by the other five
        // conditions in canReuseTerrainState(), and the per-draw MVP is pushed
        // by apply() before the test.
        boolean eligible = true;
        for (String uniformName : this.uniformMap.keySet()) {
            if (!VERSIONED_UNIFORMS.contains(uniformName)) {
                eligible = false;
                break;
            }
        }
        this.reuseEligible = eligible;
    }

    public void close() {
        if (this.pipeline != null)
            this.pipeline.cleanUp();
    }

    private void getOrCreate(ShaderLoader.ShaderType shaderType, String name) {
        String path = "shaders/core/%s".formatted(name);


        switch (shaderType) {
            case VERTEX -> vsPath = path;
            case FRAGMENT -> fsName = path;
        }
    }

    public void apply() {
        // One hoisted read of the timing switch instead of eight per-draw calls
        // into FrameProfiler. `DETAILED_TIMING` is a static final, so the JIT
        // folds it and every `timed` block below disappears in normal play; the
        // counters the A/B harnesses depend on (aSkip, rej, ...) are deliberately
        // NOT behind this flag.
        final boolean timed = FrameProfiler.DETAILED_TIMING;

        // The INNER windows (flush / push / guard / book) are a second, finer gate
        // than `timed`. Outside VULKANMOD_AB=APPTIME `seg == timed` exactly, so this
        // is inert; under APPTIME one arm closes them, which is what measures the
        // apparatus. The OUTER window (`__t`) and the counters the harnesses read by
        // name stay open in both arms, or the row would measure nothing.
        final boolean seg = timed && appSegmentsOn();
        final long __t = timed ? FrameProfiler.start() : 0L;

        // Paired A/B arm for this call (VULKANMOD_AB=<flag>).
        //
        // Taken FIRST, before anything consults a flag, so the whole call - the
        // MVP flush, the guard, the publish - is measured under one arm.
        final boolean __abArm = PairedAB.AT_APPLY && PairedAB.nextArm();

        // APPLYSPLIT, OFF arm: identical work, guard and publish behind ONE call.
        if (PairedAB.TARGET_APPLYSPLIT && !__abArm) {
            applyFolded(__t, __abArm);
            return;
        }

        // The MVP is only marked dirty by the matrix ops; resolve it once per
        // draw instead of once per GlStateManager.translate/rotate/scale.
        //
        // Deliberately outside the reuse test below: the MVP is the one thing
        // that really is per-section (each chunk sits at its own translation),
        // and it is what the arena writes into its per-instance matrix array.
        //
        // Timed as a whole call: `mvpMs` is opened inside calculateMVP(), so it
        // excludes the two call boundaries and the interface dispatch into the
        // section-MVP provider. `flushMs - mvpMs` is exactly that boundary cost,
        // which `miscMs` has been absorbing without attribution.
        final long __f = seg ? FrameProfiler.start() : 0L;
        // PASS 23 — TRIED AND REVERTED: hoisting the dirty test out of flushMVP().
        //
        // Pass 11 priced this chain at `flushMs - mvpMs` = ~180 ns/call over ~950
        // calls a frame and read it as the cost of the two call boundaries
        // `apply -> flushMVP -> calculateMVP`. It then tried to remove them by
        // splitting the TAIL of calculateMVP, which moved `flushNs` by nothing, and
        // left the note "do not re-attempt it without a measurement that shows the
        // inlining actually happening".
        //
        // Pass 23 took that measurement the other way: it removed the call
        // altogether — `if (VRenderSystem.mvpDirty) calculateMVP();`, i.e. one
        // public field read instead of one call, semantically identical — and
        // `flushMs`/`flushNs` read **0.337/351 and 0.338/352 before against
        // 0.335/354 and 0.342/362 after**, while `mvpMs` stayed at 0.18/0.19 and
        // `apply` stayed at 1.51/1.54 ms. The call boundary is therefore not a cost
        // at all: `flushMVP()` was already being inlined into apply(), and
        // `flushMs - mvpMs` is the OUTER `nanoTime` pair's own price (~165 ns
        // against this project's measured 150–226 ns per pair), not a boundary.
        // **The residual pass 11 attributed to the call structure is the meter.**
        //
        // Reverted rather than kept: the change measured neutral, and keeping it
        // would have meant making `mvpDirty` public — a correctness-critical flag —
        // in exchange for nothing measurable. "Remove the flushMVP boundary" is off
        // the ranked list; do not re-open it.
        VRenderSystem.flushMVP();
        if (seg) {
            FrameProfiler.addFlushMvp(__f);
        }

        // Publish the per-draw push constants BEFORE the reuse test.
        //
        // Every shader carries its matrix in the MVP push constant, and
        // the push normally happens inside bindPipeline() - which the reuse path
        // skips. Leaving it there would make a reused draw go out with the
        // previous draw's MVP, i.e. an entity drawn at the wrong place.
        //
        // `hasPushConstants` is the precomputed form of "this pipeline declares
        // any push constants", so the terrain path pays a boolean test instead of
        // a call into Pipeline on every one of its ~890 sections a frame. The
        // later call inside bindPipeline() stays a no-op because pushConstants()
        // already skips a byte-identical re-push.
        if (this.hasPushConstants) {
            final long __p = seg ? FrameProfiler.start() : 0L;
            Renderer.getInstance().pushConstants(this.pipeline);
            if (seg) {
                FrameProfiler.addApplyPush(__p);
            }
        }

        // Paired A/B arm for the memo (VULKANMOD_MEMOAB=1), a separate, older
        // instrument with its own flip point inside PipelineState.
        final boolean __memoArm = PipelineState.memoAbEnabled() && PipelineState.nextMemoArm();

        // Historical note: this used to materialise a pending CHUNKXF deferred
        // window before the reuse guard, and its return value gated the guard.
        // That mechanism is gone (the matrix stack is always real now), so this
        // is always false and the guard is no longer held back by it.
        final boolean materialised = VRenderSystem.pullPendingTransform();

        final long __g = seg ? FrameProfiler.start() : 0L;
        final boolean reuse = !materialised && canReuseTerrainState();
        if (seg) {
            FrameProfiler.addReuseGuardNanos(__g);
        }

        if (reuse) {
            final long __b = seg ? FrameProfiler.start() : 0L;
            FrameProfiler.onShaderApplyReused();
            if (this.pipeline != PipelineManager.getBlockPipeline()) {
                FrameProfiler.onShaderApplyReusedNonBlock();
            }
            if (seg) {
                FrameProfiler.addApplyBook(__b);
            }
            if (timed) {
                FrameProfiler.addApplyFast(__t, __memoArm);
                FrameProfiler.addShaderApply(__t);
            }
            if (PairedAB.ACTIVE) {
                FrameProfiler.addAbApply(__t, __abArm);
            }
            return;
        }

        applyFull();

        if (timed) {
            FrameProfiler.addApplyFull(__t);
            FrameProfiler.addShaderApply(__t);
        }
        if (PairedAB.ACTIVE) {
            FrameProfiler.addAbApply(__t, __abArm);
        }
    }

    /**
     * The full publish: sampler resolution, program switch, pipeline bind, uniform
     * and descriptor staging, and the reuse record.
     *
     * <p><b>The split from {@link #apply()} is load-bearing, not cosmetic.</b> A
     * `[VKPROF] applypath` measurement at rd 12 put the reuse path at 1122 ns per
     * call and the full path at 18.5 us per call, while mvp + guard + push + book
     * together account for only ~490 ns of the fast path. The remainder is the
     * price of <em>entering</em> the method: a body this size cannot be inlined
     * into the draw site, so each of the ~890 calls a frame paid a full frame
     * setup and register spill to reach, 99% of the time, six integer compares.
     * Keeping the fast path in a small method is what makes it inlinable. Do not
     * fold this body back into {@code apply()}.
     */
    private void applyFull() {
        // Segment split for the pass-11 `fullsplit` report. `applypath` shows this
        // path at ~11 us/call against ~0.9 us for the reuse path, and there are
        // only ~24 calls a frame - so ~0.27 ms of the frame sits in two dozen
        // calls. Which of the four segments owns it decides the fix, and they need
        // different fixes, so it is measured rather than guessed.
        //
        // `appSegmentsOn()` is the APPTIME gate (see apply()); outside that A/B it
        // is the constant true and this is exactly `DETAILED_TIMING`.
        final boolean timed = FrameProfiler.DETAILED_TIMING && appSegmentsOn();

        if (this.doUniformUpdate) {
            final long __s = timed ? FrameProfiler.start() : 0L;

            // NOTE: this loop used to call VkUniform.upload() for every uniform,
            // and each of those calls ran a full uploadAndBindUBOs() - a whole-UBO
            // rewrite, a dynamic-offset bump and a vkCmdBindDescriptorSets. For a
            // shader declaring four uniforms that meant four redundant descriptor
            // binds per draw, and they bound the *previously* used program's
            // pipeline rather than this one (the loop runs before glUseProgram).
            // The single bindPipeline() below uploads and binds the correct
            // descriptor sets for all uniforms in one go, because each UBO field
            // pulls its value through its supplier at bind time.
            //
            // Sampler values are pre-resolved at construction time (samplerValues)
            // so the hot loop is a plain array access - no String-keyed HashMap.get,
            // no List.get, just a null check. With two samplers per terrain draw
            // and ~1000 draws/frame, the saved String hashes alone are ~0.05 ms.
            final Object[] samplers = this.samplerValues;
            if (samplers != null) {
                for (int j = 0; j < samplers.length; ++j) {
                    final Object object = samplers[j];
                    if (object == null) {
                        continue;
                    }
                    activeTexture(33984 + j);
                    int texId = -1;
                    if (object instanceof AbstractTexture) {
                        texId = ((AbstractTexture) object).getGlTextureId();
                    } else if (object instanceof Integer) {
                        texId = (Integer) object;
                    }

                    if (texId != -1) {
                        VkGlTexture.bindTexture(texId);
                    }
                }
            }

            if (timed) {
                FrameProfiler.addFullSegment(__s, FrameProfiler.FULL_SAMPLERS);
            }
        }

        if (this.programId != lastProgramId) {
            final long __s = timed ? FrameProfiler.start() : 0L;
            OpenGlHelper.glUseProgram(this.programId);
            lastProgramId = this.programId;
            if (timed) {
                FrameProfiler.addFullSegment(__s, FrameProfiler.FULL_USE_PROGRAM);
            }
        }

        final long __b = timed ? FrameProfiler.start() : 0L;
        bindPipeline();
        if (timed) {
            FrameProfiler.addFullSegment(__b, FrameProfiler.FULL_BIND_PIPELINE);
        }

        final long __p = timed ? FrameProfiler.start() : 0L;

        // Every real apply() invalidates every other shader's reuse record: it may
        // have published a different pipeline, different textures, a different
        // descriptor set or different uniform values. An int increment is the
        // whole cost of that.
        ++applySerial;

        // Only shaders the fast path may serve maintain a record - and only when
        // it is enabled. With VULKANMOD_APPLYREUSE=0 this block never runs, which
        // keeps the A/B honest: the reference case is then byte-for-byte the
        // pre-change apply().
        if (reuseTerrainStateOn() && reuseGateOk()) {
            this.publishedSerial = applySerial;
            this.publishedEpoch = Renderer.getBindingEpoch();
            this.publishedBindingEpoch = Renderer.getBindingEpoch();
            this.publishedBindVersion = VTextureSelector.getBindVersion();
            this.publishedUniformVersion = VRenderSystem.getUniformVersion();

            final RenderPass renderPass = Renderer.getInstance().getBoundRenderPass();
            this.publishedState = renderPass == null ? null : PipelineState.getCurrentPipelineState(renderPass);
        }

        if (timed) {
            FrameProfiler.addFullSegment(__p, FrameProfiler.FULL_PUBLISH);
        }
    }

    /**
     * True when the state {@link #apply()} would publish is provably still the
     * state in effect, so re-publishing it is pure overhead.
     *
     * <p>Scope: the chunk terrain path only, and only while the arena is driving
     * it. A chunk layer draws a thousand sections that share one pipeline, one
     * descriptor set, one pair of textures and one set of uniform values - the
     * only per-section quantity is the MVP, which {@link VRenderSystem#flushMVP()}
     * already handled above and which the arena writes into its per-instance
     * matrix array rather than into a uniform. That makes apply()'s remaining
     * body (pipeline bind, sampler resolution, descriptor-set re-evaluation,
     * uniform staging) repeated ~1000 times a frame for one distinct outcome.
     *
     * <p>Restricting the fast path to the terrain pipeline keeps the set of
     * things that can invalidate it closed and small. Every one of them is
     * checked:
     * <ul>
     *   <li>{@code publishedSerial} - no other shader has applied since, so no
     *       other pipeline/texture/uniform state has been published either;</li>
     *   <li>the bound pipeline - nothing bound a different one behind our back;</li>
     *   <li>the binding epoch - the command buffer did not restart, which would
     *       have dropped the descriptor sets and the bind (see
     *       {@link Renderer#getBindingEpoch()});</li>
     *   <li>the texture bind version - the sampler images are unchanged, which
     *       includes the lightmap written directly by VTextureSelector;</li>
     *   <li>the uniform version - ColorModulator / FogColor / FogStart / FogEnd
     *       are unchanged, i.e. the UBO the descriptor set points at still holds
     *       the right values;</li>
     *   <li>the {@link PipelineState} - identical raster/depth/blend/color-mask
     *       state against the same render pass. If it
     *       ever differs, the pending pipeline bind is needed to re-resolve the
     *       handle, so the reuse must not happen.</li>
     * </ul>
     *
     * <p>A false negative only costs a redundant apply(); a false positive would
     * draw with stale state, so each condition is a hard requirement.
     */
    /**
     * The static half of the reuse test: is this shader one the fast path may
     * serve at all? Independent of the current frame, so it is the same answer
     * on the publish side and on the reuse side.
     *
     * <p>With {@code VULKANMOD_REUSEALL=0} this reproduces the original gate
     * exactly (terrain pipeline, arena live). Otherwise eligibility is derived
     * from what the shader declares: a shader whose uniform set is fully
     * version-checked has nothing per-draw left for the skipped code to publish.
     */
    private boolean reuseGateOk() {
        if (this.pipeline == null) {
            return false;
        }

        if (reuseAllOn()) {
            return this.reuseEligible;
        }

        return this.pipeline == PipelineManager.getBlockPipeline();
    }

    /**
     * Whether the reuse fast path may run at all this call.
     *
     * <p>The paired A/B ({@code VULKANMOD_AB=APPLYREUSE}) makes this the arm's
     * answer instead of the constant; with the instrument off the ternary folds
     * back to the original field read and costs nothing.
     */
    private static boolean reuseTerrainStateOn() {
        return PairedAB.TARGET_APPLYREUSE ? PairedAB.arm() : REUSE_TERRAIN_STATE;
    }

    /** Whether the gate is widened past the terrain pipeline. {@code VULKANMOD_AB=REUSEALL}. */
    private static boolean reuseAllOn() {
        return PairedAB.TARGET_REUSEALL ? PairedAB.arm() : REUSE_ALL;
    }

    /**
     * Whether this call opens the <em>inner</em> timing windows inside
     * {@code apply()} / {@code applyFull()} / {@code bindPipeline()}.
     *
     * <p>Outside {@code VULKANMOD_AB=APPTIME} this is the constant {@code true}, so
     * {@code timed && appSegmentsOn()} folds to {@code timed} and the shipping build
     * is unchanged. Under {@code APPTIME} the two arms differ only in whether those
     * windows open, which makes the paired row the cost of the instrumentation
     * itself - the reads that {@code applySplitMiscMs} has been absorbing without
     * attribution. See {@link PairedAB#TARGET_APPTIME}.
     */
    private static boolean appSegmentsOn() {
        if (PairedAB.TARGET_APPLYSPLIT) {
            // The split A/B is about the call boundary, so the two arms must differ
            // in nothing else. Close the sub-windows in BOTH arms.
            return false;
        }
        return !PairedAB.TARGET_APPTIME || PairedAB.arm();
    }

    /**
     * The pre-split shape of the fast path: the guard and the whole publish behind
     * <b>one</b> non-inlinable call instead of the current {@code apply()} +
     * {@code applyFull()} pair.
     *
     * <p>Used only as the OFF arm of {@code VULKANMOD_AB=APPLYSPLIT}, to measure what
     * the split is actually worth. It deliberately does <b>not</b> duplicate the
     * publish body - it calls the same {@link #applyFull()} the split path does - so
     * the only thing it adds is the call boundary the split removed.
     *
     * <p>It reproduces the fast path faithfully, which is the path the split's claim
     * concerns (99% of calls). The full path is one call deeper here than it is on the
     * split arm, which biases this arm slightly worse and so makes the result
     * conservative. See {@link PairedAB#TARGET_APPLYSPLIT}.
     *
     * <p>Never reached outside an {@code APPLYSPLIT} run: the caller guards it with a
     * {@code static final}, so it is dead code that the JIT removes in a normal build.
     */
    private void applyFolded(long __t, boolean __abArm) {
        final boolean timed = FrameProfiler.DETAILED_TIMING;

        VRenderSystem.flushMVP();

        if (this.hasPushConstants) {
            Renderer.getInstance().pushConstants(this.pipeline);
        }

        final boolean __memoArm = PipelineState.memoAbEnabled() && PipelineState.nextMemoArm();
        final boolean reuse = canReuseTerrainState();

        if (reuse) {
            FrameProfiler.onShaderApplyReused();
            if (this.pipeline != PipelineManager.getBlockPipeline()) {
                FrameProfiler.onShaderApplyReusedNonBlock();
            }
            if (timed) {
                FrameProfiler.addApplyFast(__t, __memoArm);
                FrameProfiler.addShaderApply(__t);
            }
            if (PairedAB.ACTIVE) {
                FrameProfiler.addAbApply(__t, __abArm);
            }
            return;
        }

        applyFull();

        if (timed) {
            FrameProfiler.addApplyFull(__t);
            FrameProfiler.addShaderApply(__t);
        }
        if (PairedAB.ACTIVE) {
            FrameProfiler.addAbApply(__t, __abArm);
        }
    }

    private boolean canReuseTerrainState() {
        if (!reuseTerrainStateOn() || !reuseGateOk()) {
            return false;
        }
        // Past this point this shader could have reused and did not. Report
        // which condition fired - see FrameProfiler.reuseRejects.
        final int reason;

        if (this.publishedSerial != applySerial) {
            reason = com.yuhan123.vulkanmod.render.util.FrameProfiler.REJ_SERIAL;
        } else if (this.publishedEpoch != Renderer.getBindingEpoch()
                || this.publishedBindingEpoch != Renderer.getBindingEpoch()) {
            reason = com.yuhan123.vulkanmod.render.util.FrameProfiler.REJ_EPOCH;
        } else if (this.publishedBindVersion != VTextureSelector.getBindVersion()) {
            reason = com.yuhan123.vulkanmod.render.util.FrameProfiler.REJ_BIND;
        } else if (this.publishedUniformVersion != VRenderSystem.getUniformVersion()) {
            reason = com.yuhan123.vulkanmod.render.util.FrameProfiler.REJ_UNIFORM;
        } else {
            final Renderer renderer = Renderer.getInstance();

            if (renderer.getBoundPipeline() != this.pipeline || renderer.getBoundRenderPass() == null) {
                reason = com.yuhan123.vulkanmod.render.util.FrameProfiler.REJ_BOUNDPIPE;
            } else if (this.publishedState == null
                    || this.publishedState != PipelineState.getCurrentPipelineState(renderer.getBoundRenderPass())) {
                reason = com.yuhan123.vulkanmod.render.util.FrameProfiler.REJ_STATE;
            } else {
                return true;
            }
        }

        com.yuhan123.vulkanmod.render.util.FrameProfiler.onReuseReject(reason);
        return false;
    }

    public void setDefaultUniforms(int mode, Matrix4f modelView, Matrix4f projection) {
        if (!this.doUniformUpdate)
            return;

        if (this.MODEL_VIEW_MATRIX != null) {
            this.MODEL_VIEW_MATRIX.set(modelView);
        }

        if (this.PROJECTION_MATRIX != null) {
            this.PROJECTION_MATRIX.set(projection);
        }

        if (this.COLOR_MODULATOR != null) {
            this.COLOR_MODULATOR.set(VRenderSystem.getColor());
        }

        if (this.GLINT_ALPHA != null) {
            this.GLINT_ALPHA.set(VRenderSystem.getShaderGlintAlpha());
        }

        if (this.FOG_START != null) {
            this.FOG_START.set(VRenderSystem.getShaderFogStart());
        }

        if (this.LIGHTMAP_COORD != null) {
            this.LIGHTMAP_COORD.set(VRenderSystem.getLightmapU(), VRenderSystem.getLightmapV(), 0.0f, 1.0f);
        }

        if (this.FOG_END != null) {
            this.FOG_END.set(VRenderSystem.getShaderFogEnd());
        }

        if (this.FOG_COLOR != null) {
            this.FOG_COLOR.set(VRenderSystem.getFogColor());
        }

//        if (this.FOG_SHAPE != null) {
//            this.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
//        }

//        if (this.TEXTURE_MATRIX != null) {
//            this.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
//        }

//        if (this.GAME_TIME != null) {
//            this.GAME_TIME.set(RenderSystem.getShaderGameTime());
//        }

        if (this.SCREEN_SIZE != null) {
            this.SCREEN_SIZE.set((float) Display.getWidth(), (float) Display.getHeight());
        }

        if (this.LINE_WIDTH != null && (mode == AutoIndexBuffer.DrawType.LINES || mode == AutoIndexBuffer.DrawType.DEBUG_LINE_STRIP)) {
            this.LINE_WIDTH.set(VRenderSystem.getShaderLineWidth());
        }

//        RenderSystem.setupShaderLights((ShaderInstance) (Object) this);
    }

    /**
     * Re-evaluates {@link PipelineState} and binds the matching pipeline handle.
     * Public so the depth-prepass flow can re-bind between its two passes
     * (colorMask=0 depth write, then EQUAL + depthMask off colour) without
     * going through a full uniform apply.
     */
    public void bindPipeline() {
        if (this.pipeline == null) {
            throw new NullPointerException("Shader %s has no initialized pipeline".formatted(this.name));
        }

        // `appSegmentsOn()` is the APPTIME gate (see apply()) - inert outside it.
        final boolean timed = FrameProfiler.DETAILED_TIMING && appSegmentsOn();
        Renderer renderer = Renderer.getInstance();

        long __s = timed ? FrameProfiler.start() : 0L;
        renderer.bindGraphicsPipeline(pipeline);
        if (timed) {
            FrameProfiler.addFullSegment(__s, FrameProfiler.FULL_BIND_GFX);
        }

        __s = timed ? FrameProfiler.start() : 0L;
        VTextureSelector.bindShaderTextures(pipeline);
        if (timed) {
            FrameProfiler.addFullSegment(__s, FrameProfiler.FULL_BIND_TEX);
        }

        __s = timed ? FrameProfiler.start() : 0L;
        renderer.uploadAndBindUBOs(pipeline);
        if (timed) {
            FrameProfiler.addFullSegment(__s, FrameProfiler.FULL_BIND_UBO);
        }
    }

    /**
     * Re-binds ONLY the vkCmdBindPipeline for this shader's pipeline, without
     * re-uploading uniforms or re-binding descriptor sets / textures. Used by
     * the depth-prepass flow: apply() has just bound the descriptor sets and
     * the pipeline layout is unchanged, so a state-keyed pipeline switch needs
     * nothing else.
     */
    public void rebindPipelineOnly() {
        if (this.pipeline == null) {
            throw new NullPointerException("Shader %s has no initialized pipeline".formatted(this.name));
        }

        Renderer.getInstance().bindGraphicsPipeline(this.pipeline);
    }

    public void setupUniformSuppliers(UBO ubo) {
        for (Uniform vUniform : ubo.getUniforms()) {
            VkUniform uniform = this.uniformMap.get(vUniform.getName());

            Supplier<MappedBuffer> supplier;
            ByteBuffer byteBuffer;

            if (uniform == null) {
                VulkanMod.LOGGER.error(String.format("Error: field %s not present in uniform map", vUniform.getName()));

                int size = vUniform.getSize();
                byteBuffer = MemoryUtil.memAlloc(size * 4);
            } else if (uniform.getType() <= 3) {
                byteBuffer = MemoryUtil.memByteBuffer(uniform.getIntBuffer());
            } else if (uniform.getType() <= 10) {
                byteBuffer = MemoryUtil.memByteBuffer(uniform.getFloatBuffer());
            } else {
                throw new RuntimeException("out of bounds value for uniform " + uniform);
            }


            MappedBuffer mappedBuffer = MappedBuffer.createFromBuffer(byteBuffer);
            supplier = () -> mappedBuffer;

            vUniform.setSupplier(supplier);
        }
    }


    public Supplier<MappedBuffer> getUniformSupplier(String name) {
        VkUniform uniform1 = uniformMap.get(name);

        if (uniform1 == null) {
            VulkanMod.LOGGER.error(String.format("Error: field %s not present in uniform map", name));
            return null;
        }

        Supplier<MappedBuffer> supplier;
        ByteBuffer byteBuffer;

        if (uniform1.getType() <= 3) {
            byteBuffer = MemoryUtil.memByteBuffer(uniform1.getIntBuffer());
        } else if (uniform1.getType() <= 10) {
            byteBuffer = MemoryUtil.memByteBuffer(uniform1.getFloatBuffer());
        } else {
            throw new RuntimeException("out of bounds value for uniform " + uniform1);
        }

        MappedBuffer mappedBuffer = MappedBuffer.createFromBuffer(byteBuffer);
        supplier = () -> mappedBuffer;

        return supplier;
    }

    public void setDoUniformsUpdate() {
        this.doUniformUpdate = true;
    }

    public void setPipeline(GraphicsPipeline graphicsPipeline) {
        this.pipeline = graphicsPipeline;
        updatePushConstantFlag();
    }

    /**
     * Refreshes the cached "does this pipeline declare push constants" test used
     * by the per-draw publish. Must be called from every site that assigns
     * {@link #pipeline}.
     */
    private void updatePushConstantFlag() {
        this.hasPushConstants = this.pipeline != null && this.pipeline.getPushConstants() != null;
    }

    private void createPipeline(String configName, VertexFormat format, JsonObject config) {
        Pipeline.Builder builder = new Pipeline.Builder(format, configName);
        builder.setUniformSupplierGetter(info -> this.getUniformSupplier(info.name));

        builder.parseBindings(config);

        ShaderLoadUtil.loadShaders(builder, config, configName, "core");

        GraphicsPipeline pipeline = builder.createGraphicsPipeline();
        this.pipeline = pipeline;
        updatePushConstantFlag();
    }

    private void createLegacyShader(VertexFormat format) {

        InputStream iresource = null;

        try {
            String vertPath = vsPath + ".vsh";
            ResourceLocation shaderResource = new ResourceLocation("vulkanmod:" + vertPath);
            iresource = getResource(shaderResource);

            String vshSrc = IOUtils.toString(iresource, StandardCharsets.UTF_8);

            String fragPath = fsName + ".fsh";
            shaderResource = new ResourceLocation("vulkanmod:" + fragPath);
            iresource = getResource(shaderResource);
//            inputStream = resource.open();
            String fshSrc = IOUtils.toString(iresource, StandardCharsets.UTF_8);

            GlslConverter converter = new GlslConverter();
            Pipeline.Builder builder = new Pipeline.Builder(format, this.name);

            converter.process(vshSrc, fshSrc);
            UBO ubo = converter.createUBO();
            this.setupUniformSuppliers(ubo);
            this.setDoUniformsUpdate();

            builder.setUniforms(Collections.singletonList(ubo), converter.getSamplerList());
            builder.compileShaders(this.name, converter.getVshConverted(), converter.getFshConverted());

            this.pipeline = builder.createGraphicsPipeline();
            updatePushConstantFlag();
            this.doUniformUpdate = true;
        } catch (Exception e) {
            VulkanMod.LOGGER.error("Error on shader {} conversion/compilation", this.name, e);
        }
    }


}