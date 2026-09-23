package com.yuhan123.vulkanmod.vulkan.texture;

import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.gl.VkGlTexture;
import com.yuhan123.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import com.yuhan123.vulkanmod.vulkan.VRenderSystem;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * TEMPORARY diagnostic: dumps what the sampler slots actually hold.
 *
 * <p>Enabled only with {@code -Dvulkanmod.texprobe=1}. Answers two questions
 * that the entity-renders-black symptom turns on, neither of which can be read
 * off the source:
 *
 * <ol>
 *   <li>When a GL texture is bound ({@code glBindTexture}), which slot does it
 *       land in ({@code activeTexture}) and which image is it? If the entity
 *       texture never reaches slot 0, the shader samples whatever is left there
 *       - the block atlas, the swap-chain attachment, or a freed image.</li>
 *   <li>When a pipeline binds its descriptor sets, what does slot 0 hold, and is
 *       it the image the pipeline's descriptor expects?</li>
 * </ol>
 *
 * <p>Logs at most once per second, and only when the slot actually changed, so a
 * 60 s bench stays readable.
 */
public final class TextureProbe {

    public static final boolean ENABLED = Boolean.getBoolean("vulkanmod.texprobe");

    private static final long INTERVAL_NS = 1_000_000_000L;

    private static long lastGlBindLog;
    private static long lastPipelineLog;
    private static String lastGlBindKey = "";
    private static String lastPipelineKey = "";

    private TextureProbe() {
    }

    private static String describe(VulkanImage image) {
        if (image == null) {
            return "null";
        }
        return String.format("%s[%dx%d fmt=%d mip=%d]",
                image.name, image.width, image.height, image.format, image.mipLevels);
    }

    /** Called from the GL texture-bind path, after the slot write. */
    public static void onGlBind(int glId, int slot, VulkanImage image) {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        String key = glId + "@" + slot + "=" + describe(image);
        if (key.equals(lastGlBindKey) || now - lastGlBindLog < INTERVAL_NS) {
            return;
        }
        lastGlBindKey = key;
        lastGlBindLog = now;
        VulkanMod.LOGGER.info("[VKTEX] glBind id={} slot={} -> {}", glId, slot, describe(image));
    }

    /** Called from {@code VTextureSelector.bindShaderTextures}, after the backfill. */
    public static void onPipelineBind(Object pipeline, List<ImageDescriptor> descriptors) {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();

        StringBuilder sb = new StringBuilder();
        for (ImageDescriptor state : descriptors) {
            VulkanImage bound = VTextureSelector.getImage(state.imageIdx);
            sb.append(String.format("%s(idx=%d,b=%d)=%s ", state.name, state.imageIdx,
                    state.getBinding(), describe(bound)));
        }
        String key = sb.toString();
        if (key.equals(lastPipelineKey) || now - lastPipelineLog < INTERVAL_NS) {
            return;
        }
        lastPipelineKey = key;
        lastPipelineLog = now;
        VulkanMod.LOGGER.info("[VKTEX] pipe={} active={} {}", pipeline, VkGlTexture.getActiveTextureIndex(), sb);
    }

    /**
     * Called from {@code Pipeline.DescriptorSets.bindSets}. Reports the slot-0
     * image alongside the skip decision, so a "the draw sampled the wrong image"
     * symptom can be attributed to either the slot write or the descriptor memo.
     */
    public static void onDescriptorDecision(Object pipeline, boolean descriptorChanged, boolean uniformsChanged) {
        if (!ENABLED) {
            return;
        }
        VulkanImage slot0 = VTextureSelector.getImage(0);
        long now = System.nanoTime();

        String key = describe(slot0) + "|" + descriptorChanged + "|" + uniformsChanged;
        if (key.equals(lastDescKey) || now - lastDescLog < INTERVAL_NS) {
            return;
        }
        lastDescKey = key;
        lastDescLog = now;
        VulkanMod.LOGGER.info("[VKTEX] desc name={} slot0={} descChanged={} uniChanged={}",
                pipeline instanceof com.yuhan123.vulkanmod.vulkan.shader.Pipeline
                        ? ((com.yuhan123.vulkanmod.vulkan.shader.Pipeline) pipeline).name : "?",
                describe(slot0), descriptorChanged, uniformsChanged);
    }

    /**
     * Called from {@code Pipeline.DescriptorSets.updateUniforms}. Dumps the first
     * few floats of the staged UBO, i.e. literally what the shader reads as
     * {@code ColorModulator}. A black mob with a correct silhouette is a
     * texture x ColorModulator product, so this is the remaining unmeasured
     * input once the sampler is proven correct.
     */
    public static void onUboStaged(Object pipeline, int index, ByteBuffer staged, int structSize) {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        String key = System.identityHashCode(pipeline) + "|" + index + "|" + structSize;
        if (key.equals(lastUboKey) || now - lastUboLog < INTERVAL_NS) {
            return;
        }
        lastUboKey = key;
        lastUboLog = now;

        StringBuilder sb = new StringBuilder();
        int floats = Math.min(structSize / 4, 10);
        for (int i = 0; i < floats; ++i) {
            sb.append(String.format("%.3f ", staged.getFloat(i * 4)));
        }
        VulkanMod.LOGGER.info("[VKTEX] ubo name={} size={} floats=[{}]",
                pipeline instanceof com.yuhan123.vulkanmod.vulkan.shader.Pipeline
                        ? ((com.yuhan123.vulkanmod.vulkan.shader.Pipeline) pipeline).name : "?",
                structSize, sb.toString().trim());
    }

    private static long lastUboLog;
    private static String lastUboKey = "";

    /**
     * Called from {@code VkGlTexture.uploadSubImage} with the bytes actually
     * handed to the GPU.
     *
     * <p>This is the measurement that was missing. The entity pipeline provably
     * binds the right image (a 64x32 / 64x64 mob skin in slot 0) and provably
     * reads a white {@code ColorModulator}, so if a mob still renders black the
     * only remaining input is the texture's own content: either the upload wrote
     * zeroes, or it wrote a format/size the shader's sampler cannot decode.
     * Reporting the source bytes' mean and max separates those two from "the
     * sampling coordinates are wrong".
     *
     * <p>Logged only for small (<= 512x512) images, which is every mob skin and
     * icon but not the block atlas, at most once per second per size.
     */
    public static void onUpload(int glId, int width, int height, int format, ByteBuffer src) {
        // Deliberately not gated on a per-pixel scan of the big atlas: the cost
        // would land in the frame the probe is trying to observe.
        if (src == null || width * height > 512 * 512) {
            return;
        }
        long now = System.nanoTime();
        String key = width + "x" + height + "f" + format;
        if (key.equals(lastUploadKey) || now - lastUploadLog < INTERVAL_NS) {
            return;
        }
        lastUploadKey = key;
        lastUploadLog = now;

        int n = Math.min(src.remaining(), 4 * 4096);
        long sum = 0;
        int max = 0;
        int zero = 0;
        for (int i = 0; i < n; ++i) {
            int v = src.get(i) & 0xFF;
            sum += v;
            if (v > max) {
                max = v;
            }
            if (v == 0) {
                ++zero;
            }
        }
        VulkanMod.LOGGER.info(
                "[VKTEX] upload {}x{} fmt={} bytes={} mean={} max={} zeroPct={}%",
                width, height, format, src.remaining(),
                n == 0 ? -1 : (int) (sum / n), max,
                n == 0 ? -1 : (100 * zero / n));
    }

    private static long lastUploadLog;
    private static String lastUploadKey = "";

    /**
     * Called from {@code VkGlTexture.texImage2D}, i.e. the ALLOCATE step, with
     * whether pixel data came with it.
     *
     * <p>This is the other half of {@link #onUpload}. {@code uploadSubImage} only
     * runs when bytes are actually written, so "the mob skin is allocated but its
     * fill never lands" and "the mob skin is never allocated" look identical from
     * {@code onUpload} alone - both simply never appear. Reporting the allocation
     * separates them, and a mob skin is a 64x32 or 64x64 image.
     */
    public static void onAllocate(int glId, int width, int height, int level, boolean hasPixels) {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        String key = "a" + glId + "|" + width + "x" + height + "|" + level + "|" + hasPixels;
        if (key.equals(lastAllocKey) || now - lastAllocLog < INTERVAL_NS) {
            return;
        }
        lastAllocKey = key;
        lastAllocLog = now;
        VulkanMod.LOGGER.info("[VKTEX] alloc gl={} {}x{} level={} pixels={}",
                glId, width, height, level, hasPixels);
    }

    private static long lastAllocLog;
    private static String lastAllocKey = "";

    /**
     * Called from every {@code VkGlTexture.texSubImage2D} overload BEFORE the
     * "is there a source buffer" test, with the outcome of that test.
     *
     * <p>This is the missing link between {@link #onAllocate} and
     * {@link #onUpload}. The allocate probe shows a 64x32 mob skin image is
     * created; the upload probe shows no 64x32 bytes ever reach the GPU. Those
     * two facts together are consistent with three very different causes:
     * (a) GL never calls texSubImage2D for that image at all, (b) it calls it but
     * the source pointer is null / the IntBuffer is empty, or (c) the buffer is
     * bound but {@code uploadSubImage} throws early. Reporting the call and the
     * skip reason picks between them.
     */
    public static void onSubImageAttempt(int glId, int level, int xOffset, int yOffset,
                                         int width, int height, int format, String srcState) {
        if (!ENABLED) {
            return;
        }
        // Only the SUCCESSFUL uploads are interesting: the allocation path
        // (allocateTextureImpl -> glTexImage2D(null) -> texSubImage2D with a null
        // source) fires for every texture and would drown the log. What matters
        // is which images are then FILLED, and from where.
        if (!"ok".equals(srcState)) {
            return;
        }
        if (++subImageCalls > 400) {
            return;
        }
        String at = caller();
        // The block atlas (gl=9, 512x512) dominates the trace with thousands of
        // per-sprite sub-uploads. Mob skins go through
        // TextureUtil.uploadTextureImageSubImpl, so filter on that: it is the
        // SimpleTexture load path every entity skin, item sheet and mob texture
        // uses, and it is the one that must be seen to be believed.
        if (!at.contains("uploadTextureImageSubImpl")) {
            return;
        }
        VulkanMod.LOGGER.info("[VKTEX] subImage OK gl={} {}x{} off={},{} fmt={} lvl={} at={}",
                glId, width, height, xOffset, yOffset, format, level, at);
    }

    /**
     * First non-probe stack frame, so a texture that is allocated but never
     * filled can be attributed to the exact vanilla call site instead of being
     * guessed at.
     */
    private static String caller() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (cn.startsWith("com.yuhan123.vulkanmod.vulkan.texture.TextureProbe")
                    || cn.startsWith("com.yuhan123.vulkanmod.mixin")
                    || cn.startsWith("com.yuhan123.vulkanmod.gl.VkGlTexture")) {
                continue;
            }
            if (shown > 0) {
                sb.append(" <- ");
            }
            sb.append(cn).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber());
            if (++shown >= 3) {
                break;
            }
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    // ------------------------------------------------------------------
    // Entity-pass draw sequence probe.
    //
    // The two live on-screen defects are (a) the player's torso draws as a flat
    // untextured white box while the head right above it is textured, and (b) thin
    // black needle-shaped slivers lying on the grass. Both look like "the draw we
    // want was not the draw that happened", so this probe prints the raw sequence
    // the entity region actually issues: for every draw that uses the entity
    // shader, the GL id bound at that instant, the image slot 0 resolves to, the
    // vertex count and the MVP translation.
    //
    // Order matters here, so this is deliberately NOT deduped the way the other
    // probes are: a repeated (texture, draw) pair is itself the signal.
    // ------------------------------------------------------------------

    private static final java.util.Set<String> entSeen = new java.util.LinkedHashSet<>();
    private static int entDraws;
    private static int entBatches;

    // ------------------------------------------------------------------
    // Depth-state probe.
    //
    // Entities are now positioned correctly but are reported to ignore depth:
    // they draw over terrain that should occlude them. The position comes from
    // the outer modelview, so a correct position says nothing about whether the
    // depth compare is even enabled, which function it uses, or whether the part
    // writes depth. Those are four independent bits of state and guessing
    // between them is what the last several rounds wasted effort on.
    //
    // This records the ACTUAL state the entity pipeline is built from, per
    // distinct combination, so the answer is a number.
    // ------------------------------------------------------------------
    private static final java.util.Set<String> entDepthSeen = new java.util.LinkedHashSet<>();
    private static int entDepthDraws;

    /** Called alongside {@link #onEntityDraw} so the depth state is paired with the draw. */
    public static void onEntityDepthState(String pipelineName) {
        if (!ENABLED) {
            return;
        }
        ++entDepthDraws;
        if (entDepthSeen.size() >= 64) {
            return;
        }
        entDepthSeen.add(String.format(
                "name=%s depthTest=%s depthMask=%s depthFun=0x%x state=0x%x alphaTest=%s",
                pipelineName,
                com.yuhan123.vulkanmod.vulkan.VRenderSystem.depthTest,
                com.yuhan123.vulkanmod.vulkan.VRenderSystem.depthMask,
                com.yuhan123.vulkanmod.vulkan.VRenderSystem.depthFun,
                com.yuhan123.vulkanmod.vulkan.shader.PipelineState.getDepthState(),
                com.yuhan123.vulkanmod.vulkan.VRenderSystem.alphaTest));
    }

    /** Emits the collected depth states once, after the entity pass has run. */
    public static void reportEntityDepthStates() {
        if (!ENABLED || entDepthSeen.isEmpty()) {
            return;
        }
        VulkanMod.LOGGER.info("[VKDEPTH] entityDepthDraws={} distinct={}",
                entDepthDraws, entDepthSeen.size());
        for (String s : entDepthSeen) {
            VulkanMod.LOGGER.info("[VKDEPTH] {}", s);
        }
        entDepthSeen.clear();
        entDepthDraws = 0;
    }

    public static void onEntityDraw(String pipelineName, int boundGlId, int vertexCount,
                                    int mode, org.joml.Matrix4f mvp) {
        if (!ENABLED) {
            return;
        }
        VulkanImage slot0 = VTextureSelector.getImage(0);
        String key = String.format("name=%s gl=%d v=%d mode=%d tex=%s t=%.2f,%.2f,%.2f",
                pipelineName, boundGlId, vertexCount, mode, describe(slot0),
                mvp == null ? 0f : mvp.m30(), mvp == null ? 0f : mvp.m31(), mvp == null ? 0f : mvp.m32());

        if (entSeen.size() < 4000) {
            // Deliberately NOT deduped: a repeated (texture, draw) pair is itself
            // the signal, and the ordering is the whole point.
            entSeen.add(entDraws + " " + key);
        }
        ++entDraws;

        // One frame of entities is ~50-70 draws. Emit after a couple of frames,
        // once, so the log is a readable snapshot instead of a stream.
        if (entDraws >= 120 && entBatches < 1) {
            ++entBatches;
            for (String s : entSeen) {
                VulkanMod.LOGGER.info("[VKENT] {}", s);
            }
            entSeen.clear();
        }
    }

    private static int subImageCalls;

    // ------------------------------------------------------------------
    // Lightmap slot probe.
    //
    // block.fsh does `color.rgb *= texture(Sampler2, lightmapCoord).rgb`, so slot 2
    // is what shades every terrain face. Pit side walls and the stepped grass rims
    // render pitch black, which is what a slot-2 lookup resolves to when the real
    // lightmap never arrived and a dark or stale image is left there instead of the
    // intended white fallback. This records whether the lightmap ever reaches slot 2
    // and which image the pipeline actually binds for it.
    // ------------------------------------------------------------------

    private static final java.util.Set<String> lmSeen = new java.util.HashSet<>();

    /** Called from {@code VTextureSelector.bindShaderTextures} for imageIdx==2. */
    public static void onLightmapBind(VulkanImage image, boolean wasNull) {
        if (!ENABLED) {
            return;
        }
        String key = describe(image) + " wasNull=" + wasNull;
        if (lmSeen.size() < 40 && lmSeen.add(key)) {
            VulkanMod.LOGGER.info("[VKLM] {} wasNull={}", describe(image), wasNull);
        }
    }

    /** Called from {@code VkGlTexture.bindTexture} when the lightmap slot is written. */
    public static void onLightmapSet(int glId, VulkanImage image) {
        if (!ENABLED) {
            return;
        }
        String key = "SET " + glId + " " + describe(image);
        if (lmSeen.size() < 80 && lmSeen.add(key)) {
            VulkanMod.LOGGER.info("[VKLM] set id={} -> {}", glId, describe(image));
        }
    }

    /** One-shot: what does slot 2 hold at the end of a frame. */
    private static boolean lmReported;

    public static void reportLightmapSlot() {
        if (!ENABLED || lmReported) {
            return;
        }
        lmReported = true;
        VulkanMod.LOGGER.info("[VKLM] end-of-run slot2={} slot1={}",
                describe(VTextureSelector.getImage(2)), describe(VTextureSelector.getImage(1)));
    }

    /** Called from {@code VkGlTexture.uploadSubImage} entry, before the damage is done. */
    public static void onUploadEnter(int glId, int level, int width, int height, int format, int capacity) {
        if (!ENABLED) {
            return;
        }
        // The block atlas floods this with thousands of per-sprite sub-uploads,
        // so an absolute call cap silently hides exactly the interesting ids.
        // Report ONLY the entity-skin shapes instead: 64x32 and 64x64 are the
        // two sizes vanilla uses for mob and player skins, and no atlas sprite
        // ever has them as a full image.
        if (!((width == 64 && height == 32) || (width == 64 && height == 64))) {
            return;
        }
        VulkanMod.LOGGER.info("[VKTEX] upEnter gl={} {}x{} lvl={} fmt={} cap={}",
                glId, width, height, level, format, capacity);
    }

    private static int uploadEnterCalls;

    /**
     * Called from {@code VkGlTexture.bindTexture} immediately before it throws
     * {@code NullPointerException("bound texture is null"+id)}.
     *
     * <p>This is the one exception in the GL texture path that can silently kill
     * a texture load. Vanilla's {@code SimpleTexture.loadTexture} wraps the whole
     * load in {@code try/finally}, so a throw out of
     * {@code TextureUtil.uploadTextureImageSub -> bindTexture} aborts before
     * {@code uploadTextureImageSubImpl} ever runs - which is exactly the
     * "allocated (glTexImage2D with a null source) but never filled" fingerprint
     * a 64x32 entity skin shows. Always logged, never rate-limited: if this
     * fires for a mob-skin id, the root cause is proven.
     */
    public static void onBindMissing(int glId, int activeTexture) {
        if (!ENABLED) {
            return;
        }
        VulkanMod.LOGGER.warn("[VKTEX] BIND MISSING id={} slot={} at={}",
                glId, activeTexture, caller());
    }

    /**
     * Called from {@code TextureUtil.allocateTextureImpl} (the allocator) and
     * from {@code TextureUtil.uploadTextureImageSub} HEAD/RETURN (the filler).
     *
     * <p>Vanilla's {@code uploadTextureImageAllocate} is literally those two
     * calls back to back, so pairing them per id says whether a given texture
     * completed its load. A mob skin that shows ALLOC but never FILL is
     * allocated-and-empty - and since the two are adjacent in the same method,
     * the only way that happens is an exception in between.
     */
    public static void onAllocateImpl(int glId, int width, int height) {
        if (!ENABLED) {
            return;
        }
        if (!allocImplSeen.add(glId)) {
            return;
        }
        VulkanMod.LOGGER.info("[VKTEX] ALLOC id={} {}x{}", glId, width, height);
    }

    public static void onUploadImageSub(int glId, String phase) {
        if (!ENABLED) {
            return;
        }
        if (!uploadImageSubSeen.add(glId + phase)) {
            return;
        }
        VulkanMod.LOGGER.info("[VKTEX] FILL {} id={} at={}", phase, glId, caller());
    }

    /**
     * TEMPORARY: one line per {@code uploadSubImage} entry, so the gap between
     * "TextureUtil.uploadTextureImageSub returned" and "no bytes reached the GPU"
     * can be closed. If FILL RETURN fires for an id but this never does, the
     * loop inside uploadTextureImageSubImpl aborted before its glTexSubImage2D -
     * i.e. an exception in getRGB/copyToBuffer - which is the last unmeasured
     * step of the entity-skin load.
     */
    public static void onFillEnter(int glId, int level, int xOffset, int yOffset,
                                   int width, int height, int capacity) {
        if (!ENABLED) {
            return;
        }
        String key = glId + "|" + width + "x" + height + "|" + capacity;
        if (!fillEnterSeen.add(key)) {
            return;
        }
        VulkanMod.LOGGER.info("[VKTEX] FILLIN id={} {}x{} off={},{} lvl={} cap={}",
                glId, width, height, xOffset, yOffset, level, capacity);
    }

    private static final java.util.Set<String> fillEnterSeen = new java.util.HashSet<>();

    /**
     * Dumps the actual bytes handed to the GPU for a 64x32 / 64x64 entity skin
     * into a PNG, once per size.
     *
     * <p>Everything else about the entity path has now been measured and found
     * correct: the image is allocated at the right size, the bytes reach
     * {@code uploadSubImage}, the right image is bound to slot 0, and the
     * pipeline's {@code ColorModulator} is a sane 0.9-1.0. If a mob still draws
     * dark, the only unmeasured input left is the CONTENT of that byte block -
     * a wrongly-strided or wrongly-ordered buffer would still be non-null and
     * still 8192 bytes long, and would only show up as a garbled image.
     * Writing it out makes that visible instead of inferable.
     */
    public static void dumpSkin(int glId, int width, int height, java.nio.ByteBuffer src) {
        if (!ENABLED || src == null) {
            return;
        }
        if (!((width == 64 && height == 32) || (width == 64 && height == 64))) {
            return;
        }
        // Dedupe per glId, NOT per size: the defect being chased is "one mob's
        // parts draw against several distinct 64x32 ids", so every id has to be
        // dumped for the contents to be comparable.
        String key = glId + ":" + width + "x" + height;
        if (!skinDumped.add(key)) {
            return;
        }
        try {
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; ++y) {
                for (int x = 0; x < width; ++x) {
                    int o = (y * width + x) * 4;
                    if (o + 3 >= src.capacity()) {
                        continue;
                    }
                    int r = src.get(o) & 0xFF;
                    int g = src.get(o + 1) & 0xFF;
                    int b = src.get(o + 2) & 0xFF;
                    int a = src.get(o + 3) & 0xFF;
                    img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            java.io.File out = new java.io.File("tools/_skin_gl" + glId + "_" + width + "x" + height + ".png");
            out.getParentFile().mkdirs();
            javax.imageio.ImageIO.write(img, "png", out);
            VulkanMod.LOGGER.info("[VKTEX] skin dumped {} -> {}", key, out.getAbsolutePath());
        } catch (Exception e) {
            VulkanMod.LOGGER.warn("[VKTEX] skin dump failed", e);
        }
    }

    private static final java.util.Set<String> skinDumped = new java.util.HashSet<>();

    /**
     * Dumps the block atlas (the texture the terrain cutout layer samples).
     *
     * <p>The two symptoms under investigation - grass blades drawing as dark
     * brown spikes and 28.7% of the leaf canopy showing sky through it - are both
     * in the alpha-tested (cutout) terrain layer, and both would produce exactly
     * this appearance if the atlas's own alpha values were wrong: vanilla encodes
     * leaf/biome colour in RGB and the cutout shape in A, so an atlas whose alpha
     * arrived as 255 everywhere renders every texel of a leaf block's quad solid,
     * and an atlas whose alpha arrived as 0 everywhere renders nothing.
     *
     * <p>The atlas is 512x512 or larger with hundreds of mip levels of sub-uploads;
     * this dumps the first upload at full size only, once.
     */
    public static void dumpAtlas(int glId, int width, int height, java.nio.ByteBuffer src) {
        if (!ENABLED || src == null) {
            return;
        }
        if (width < 256 || height < 256) {
            return;
        }
        if (!atlasDumped.add(glId)) {
            return;
        }
        try {
            int sx = Math.min(4, Math.max(1, 512 / width));
            int outW = width;
            int outH = height;
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(outW, outH, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < outH; ++y) {
                for (int x = 0; x < outW; ++x) {
                    int o = (y * width + x) * 4;
                    if (o + 3 >= src.capacity()) {
                        continue;
                    }
                    int r = src.get(o) & 0xFF;
                    int g = src.get(o + 1) & 0xFF;
                    int b = src.get(o + 2) & 0xFF;
                    int a = src.get(o + 3) & 0xFF;
                    img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            java.io.File out = new java.io.File("tools/_atlas_gl" + glId + "_" + width + "x" + height + ".png");
            out.getParentFile().mkdirs();
            javax.imageio.ImageIO.write(img, "png", out);

            // Histogram the alpha channel: the verdict is "is it all 255, all 0,
            // or a real distribution".
            int n255 = 0, n0 = 0, other = 0, total = 0;
            for (int i = 3; i < src.capacity(); i += 4) {
                int a = src.get(i) & 0xFF;
                ++total;
                if (a == 255) {
                    ++n255;
                } else if (a == 0) {
                    ++n0;
                } else {
                    ++other;
                }
            }
            VulkanMod.LOGGER.info("[VKATLAS] gl={} {}x{} dumped -> {} | alpha total={} n255={} ({}) n0={} ({}) mid={} ({})",
                    glId, width, height, out.getAbsolutePath(), total,
                    n255, pct(n255, total), n0, pct(n0, total), other, pct(other, total));
        } catch (Exception e) {
            VulkanMod.LOGGER.warn("[VKATLAS] atlas dump failed", e);
        }
    }

    private static String pct(long n, long total) {
        return total == 0 ? "n/a" : String.format("%.1f%%", 100.0 * n / total);
    }

    private static final java.util.Set<Integer> atlasDumped = new java.util.HashSet<>();

    /**
     * Dumps the 16x16 lightmap once.
     *
     * <p>{@code block_arena.fsh} multiplies colour by {@code texture(Sampler2)},
     * so a lightmap that arrived white would make every terrain face fully lit
     * regardless of UV2 - which is the other way the cutout layer can look wrong.
     * Dumping it settles whether the terrain is being lit at all.
     */
    public static void dumpLightmap(int glId, int width, int height, java.nio.ByteBuffer src) {
        if (!ENABLED || src == null) {
            return;
        }
        if (width != 16 || height != 16) {
            return;
        }
        if (!lightmapDumped.add(glId)) {
            return;
        }
        try {
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            StringBuilder sb = new StringBuilder();
            for (int y = 0; y < 16; ++y) {
                for (int x = 0; x < 16; ++x) {
                    int o = (y * 16 + x) * 4;
                    if (o + 3 >= src.capacity()) {
                        continue;
                    }
                    int r = src.get(o) & 0xFF;
                    int g = src.get(o + 1) & 0xFF;
                    int b = src.get(o + 2) & 0xFF;
                    int a = src.get(o + 3) & 0xFF;
                    img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            java.io.File out = new java.io.File("tools/_lightmap_gl" + glId + ".png");
            out.getParentFile().mkdirs();
            javax.imageio.ImageIO.write(img, "png", out);

            // Row min/max so the log carries the actual range without a screenshot.
            for (int y = 0; y < 16; ++y) {
                int lo = 255, hi = 0;
                for (int x = 0; x < 16; ++x) {
                    int o = (y * 16 + x) * 4;
                    if (o >= src.capacity()) {
                        continue;
                    }
                    int r = src.get(o) & 0xFF;
                    lo = Math.min(lo, r);
                    hi = Math.max(hi, r);
                }
                sb.append(y == 0 ? "" : ",").append(lo).append('-').append(hi);
            }
            VulkanMod.LOGGER.info("[VKLMAP] gl={} dumped -> {} rMinMaxPerRow(block-light axis)={}",
                    glId, out.getAbsolutePath(), sb);
        } catch (Exception e) {
            VulkanMod.LOGGER.warn("[VKLMAP] lightmap dump failed", e);
        }
    }

    private static final java.util.Set<Integer> lightmapDumped = new java.util.HashSet<>();

    /**
     * Stitches a large atlas out of its per-sprite {@code glTexSubImage2D} calls.
     *
     * <p>1.12.2 uploads the block atlas one sprite at a time - every animation
     * frame and every mip level is a separate sub-image into the same image - so
     * the whole-atlas dump never fires. This keeps a CPU-side RGBA copy keyed by
     * the image's own capacity and blits each sub-image into place, then writes
     * it out once at the end.
     */
    private static final java.util.Map<Integer, byte[]> atlasPixels = new java.util.HashMap<>();
    private static final java.util.Set<Integer> atlasWritten = new java.util.HashSet<>();

    public static void stitchAtlas(int glId, int xOffset, int yOffset, int width, int height,
                                   java.nio.ByteBuffer src) {
        if (!ENABLED || src == null || width <= 0 || height <= 0) {
            return;
        }

        // The sub-image is a SPRITE, not the atlas, so its own size says nothing
        // about whether this is an atlas: the block atlas receives 16x16 sprites
        // and animation frames into a 512x512 image. The discriminator is the
        // IMAGE's size, which is only known once the image exists - so ask the
        // texture for it rather than guessing from the upload.
        int imageSide = imageSide(glId);
        if (imageSide < 128) {
            return;
        }

        if (atlasWritten.size() > 8) {
            return;
        }

        // Recompute the buffer whenever the image turns out to be larger than the
        // copy we are holding; animation frames for big atlases arrive first and
        // would otherwise be clipped.
        byte[] buf = atlasPixels.get(glId);
        int bufSide = buf == null ? 0 : (int) Math.round(Math.sqrt(buf.length / 4.0));
        if (buf == null || imageSide > bufSide) {
            byte[] grown = new byte[imageSide * imageSide * 4];
            if (buf != null && bufSide > 0) {
                for (int y = 0; y < Math.min(bufSide, imageSide); ++y) {
                    System.arraycopy(buf, y * bufSide * 4,
                                     grown, y * imageSide * 4,
                                     Math.min(bufSide, imageSide) * 4);
                }
            }
            buf = grown;
            bufSide = imageSide;
            atlasPixels.put(glId, buf);
        }

        if (xOffset + width > bufSide || yOffset + height > bufSide) {
            return;
        }

        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int so = (y * width + x) * 4;
                if (so + 3 >= src.capacity()) {
                    continue;
                }
                int d = ((yOffset + y) * bufSide + (xOffset + x)) * 4;
                if (d + 3 >= buf.length) {
                    continue;
                }
                buf[d] = src.get(so);
                buf[d + 1] = src.get(so + 1);
                buf[d + 2] = src.get(so + 2);
                buf[d + 3] = src.get(so + 3);
            }
        }
    }

    /** The size of the GL image a sub-image upload targets, 0 if unknown. */
    private static int imageSide(int glId) {
        try {
            com.yuhan123.vulkanmod.gl.VkGlTexture t = com.yuhan123.vulkanmod.gl.VkGlTexture.getTexture(glId);
            if (t == null || t.getVulkanImage() == null) {
                return 0;
            }
            return t.getVulkanImage().width;
        } catch (Throwable th) {
            return 0;
        }
    }

    /** Writes out every stitched atlas. Called once at auto-quit. */
    public static void writeAtlases() {
        if (!ENABLED) {
            return;
        }
        for (java.util.Map.Entry<Integer, byte[]> e : atlasPixels.entrySet()) {
            int glId = e.getKey();
            if (!atlasWritten.add(glId)) {
                continue;
            }
            byte[] buf = e.getValue();
            int side = (int) Math.round(Math.sqrt(buf.length / 4.0));
            if (side < 128) {
                continue;
            }
            try {
                java.awt.image.BufferedImage img =
                        new java.awt.image.BufferedImage(side, side, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                int n0 = 0, n255 = 0, mid = 0;
                for (int y = 0; y < side; ++y) {
                    for (int x = 0; x < side; ++x) {
                        int o = (y * side + x) * 4;
                        int r = buf[o] & 0xFF;
                        int g = buf[o + 1] & 0xFF;
                        int b = buf[o + 2] & 0xFF;
                        int a = buf[o + 3] & 0xFF;
                        if (a == 0) {
                            ++n0;
                        } else if (a == 255) {
                            ++n255;
                        } else {
                            ++mid;
                        }
                        img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                    }
                }
                java.io.File out = new java.io.File("tools/_st_atlas_gl" + glId + "_" + side + ".png");
                out.getParentFile().mkdirs();
                javax.imageio.ImageIO.write(img, "png", out);
                int total = side * side;
                VulkanMod.LOGGER.info("[VKSTITCH] gl={} side={} -> {} | alpha n0={} ({}) n255={} ({}) mid={} ({})",
                        glId, side, out.getAbsolutePath(),
                        n0, pct(n0, total), n255, pct(n255, total), mid, pct(mid, total));
            } catch (Exception ex) {
                VulkanMod.LOGGER.warn("[VKSTITCH] write failed for gl={}", glId, ex);
            }
        }
    }

    private static final java.util.Set<Integer> allocImplSeen = new java.util.HashSet<>();
    private static final java.util.Set<String> uploadImageSubSeen = new java.util.HashSet<>();

    /**
     * Called from {@code DisplayListManager.DisplayList.mergeQuadRuns} for every
     * surviving QUADS draw. A vertex count that is not a multiple of four makes
     * the QUADS index pattern expand past the last whole quad, pulling the next
     * part's vertices into this draw - which is what scrambled entity geometry
     * looks like. Counted, reported once per second if non-zero.
     */
    public static void onQuadRun(int vertexCount) {
        if (!ENABLED) {
            return;
        }
        if ((vertexCount & 3) == 0) {
            return;
        }
        ++quadRunBad;
        long now = System.nanoTime();
        if (now - lastQuadRunLog < INTERVAL_NS) {
            return;
        }
        lastQuadRunLog = now;
        VulkanMod.LOGGER.warn("[VKTEX] QUADS run with vertexCount={} not a multiple of 4 (bad total={})",
                vertexCount, quadRunBad);
    }

    private static int quadRunBad;
    private static long lastQuadRunLog;

    // ------------------------------------------------------------------
    // Chunk-draw pipeline census.
    //
    // Every terrain symptom chased so far (black slivers on thin edge-on
    // geometry, pits shading to black, the see-through vase) is explained by ONE
    // hypothesis: the chunk draw resolves to a shader that never samples the
    // lightmap. block_arena.vsh/fsh are correct (UV2 -> Sampler2); the entity
    // cutout shader position_tex_color_normal declares only locations 0/1/2 and
    // has no Sampler2 at all.
    //
    // Which of the two a chunk draw reaches is decided by the VertexFormat the
    // chunk pointer state resolves to, because PipelineManager.shaderMap is keyed
    // on the format object:
    //   GlStateManagerMixin.computeFormatFromPointers() returns
    //   DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL for chunk draws, while
    //   PipelineManager.createCorePipelines() registered block_arena under the
    //   BLOCK constant.
    //
    // So this counts, per frame, which shader the terrain path actually binds.
    // It is the one link in the chain that has never been measured directly -
    // the [VKENT] probe only saw it fire twice, because it is gated on the
    // entity loop and terrain is not drawn inside it.
    // ------------------------------------------------------------------

    private static final java.util.Map<String, Integer> chunkShaders = new java.util.LinkedHashMap<>();
    private static long chunkDraws;
    private static int chunkBatches;
    private static long lastChunkLog;

    /** Called from the chunk draw sites in GlStateManagerMixin, per draw. */
    public static void onChunkDraw(String shaderName, int vertexFormatSize, int elementCount, int vertexCount) {
        if (!ENABLED) {
            return;
        }
        ++chunkDraws;
        // Bucket by the render state as well as the shader, because the reported
        // defect is that water (translucent, depth-write off, blended) is landing
        // over opaque terrain - so the two must be told apart in the census. A
        // shader/format key alone lumps them together when they share a shader.
        String key = shaderName + " fmt=" + vertexFormatSize + "/" + elementCount
                + " blend=" + (com.yuhan123.vulkanmod.vulkan.shader.PipelineState.blendInfo.enabled ? 1 : 0)
                + " aTest=" + (com.yuhan123.vulkanmod.vulkan.VRenderSystem.alphaTest ? 1 : 0)
                + " dMask=" + (com.yuhan123.vulkanmod.vulkan.VRenderSystem.depthMask ? 1 : 0)
                + " cMask=0x" + Integer.toHexString(com.yuhan123.vulkanmod.vulkan.VRenderSystem.colorMask);
        chunkShaders.merge(key, 1, Integer::sum);

        long now = System.nanoTime();
        if (chunkBatches < 3 && now - lastChunkLog > INTERVAL_NS) {
            lastChunkLog = now;
            ++chunkBatches;
            VulkanMod.LOGGER.info("[VKCHUNK] draws={} {}", chunkDraws, chunkShaders);
            chunkShaders.clear();
        }
    }

    /**
     * Called from {@code DisplayListManager.replayList} once per list. Reports how
     * many distinct relative matrices the list's draws carry. A model whose parts
     * all share one relative matrix replays as a rigid body; many distinct ones
     * are the model's own part transforms and must be correct for the model to
     * assemble. Reporting the count distinguishes "one part is misplaced" from
     * "every part is scattered".
     */
    public static void onListReplay(int internalId, int draws, int distinctRelatives) {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        String key = internalId + "|" + draws + "|" + distinctRelatives;
        if (key.equals(lastListKey) || now - lastListLog < INTERVAL_NS) {
            return;
        }
        lastListKey = key;
        lastListLog = now;
        VulkanMod.LOGGER.info("[VKTEX] list id={} draws={} distinctRelative={}",
                internalId, draws, distinctRelatives);
    }

    /**
     * Called once per display-list replay that happens inside the entity loop.
     *
     * <p>The user reports that <b>every entity's texture lands on top of the
     * player</b>. If that is true, the modelview applied at replay time is the
     * same for every entity, so this records the transformation each replay
     * actually uses and counts how many distinct ones a frame contains. One
     * distinct transform for N rendered entities is the report confirmed; N
     * distinct transforms means the problem is elsewhere.
     */
    private static final java.util.List<String> entTransforms = new java.util.ArrayList<>();
    private static int entReplays;

    /**
     * Entity attribution, filled by {@code RenderManagerMixin} around the one
     * {@code Render.doRender} call per rendered entity.
     *
     * <p>{@code entWant*} is where the entity <b>should</b> end up in view space:
     * the base modelview (whatever the camera left before this entity's own
     * {@code translate}) applied to the (x,y,z) Minecraft handed the renderer.
     * {@code onEntityReplay} records where its display lists <b>actually</b> put
     * it. If the two agree for every entity but the user still sees one heap, the
     * models are correct and something else stacks them; if several entities all
     * report the same "got", that is the report reproduced in one number.
     */
    private static String entName = "?";
    private static float entWantX, entWantY, entWantZ;
    /** Replay count for the entity currently being attributed; distinct from {@code entDraws}. */
    private static int entXfDraws;
    private static double entMaxDev;
    private static float entGotX, entGotY, entGotZ;
    /** The entity's raw camera-relative offset, and the base modelview's own translation column. */
    private static float entPx, entPy, entPz;
    private static float entBaseX, entBaseY, entBaseZ;
    private static final java.util.List<String> entRows = new java.util.ArrayList<>();
    private static int entFrames;

    public static void onEntityBegin(String name, float wantX, float wantY, float wantZ) {
        if (!ENABLED) {
            return;
        }
        entName = name;
        entWantX = wantX;
        entWantY = wantY;
        entWantZ = wantZ;
        entXfDraws = 0;
        entMaxDev = 0.0;
        entGotX = entGotY = entGotZ = 0.0f;
    }

    /** Preferred entry for {@code RenderManagerMixin}: derives the expected view-space position itself. */
    public static void beginEntity(Object entity, double x, double y, double z) {
        if (!ENABLED) {
            return;
        }
        String name = entity == null ? "?" : entity.getClass().getSimpleName();
        // Base modelview = whatever the camera left before this entity plugged
        // its own translate in. Its translation column applied to (x,y,z) is the
        // view-space point the entity's model must land on.
        final java.nio.FloatBuffer fb = VRenderSystem.pullModelViewFloatBuffer();
        final float m00 = fb.get(0), m01 = fb.get(1), m02 = fb.get(2), m03 = fb.get(3);
        final float m10 = fb.get(4), m11 = fb.get(5), m12 = fb.get(6), m13 = fb.get(7);
        final float m20 = fb.get(8), m21 = fb.get(9), m22 = fb.get(10), m23 = fb.get(11);
        final float m30 = fb.get(12), m31 = fb.get(13), m32 = fb.get(14), m33 = fb.get(15);
        final float fx = (float) x, fy = (float) y, fz = (float) z;
        entPx = fx;
        entPy = fy;
        entPz = fz;
        entBaseX = m30;
        entBaseY = m31;
        entBaseZ = m32;
        onEntityBegin(name,
                m00 * fx + m10 * fy + m20 * fz + m30,
                m01 * fx + m11 * fy + m21 * fz + m31,
                m02 * fx + m12 * fy + m22 * fz + m32);
    }

    /** Closes one entity's attribution window and queues its summary row. */
    public static void onEntityEnd() {
        if (!ENABLED) {
            return;
        }
        entRows.add(String.format("%s draws=%d p=(%.2f,%.2f,%.2f) base=(%.2f,%.2f,%.2f)"
                        + " want=(%.2f,%.2f,%.2f) got=(%.2f,%.2f,%.2f) maxDev=%.2f",
                entName, entXfDraws, entPx, entPy, entPz, entBaseX, entBaseY, entBaseZ,
                entWantX, entWantY, entWantZ,
                entGotX, entGotY, entGotZ, entMaxDev));
        entName = "?";
    }

    public static void onEntityReplay(int internalId, int draws, double x, double y, double z) {
        if (!ENABLED) {
            return;
        }
        ++entReplays;
        entTransforms.add(String.format("%s@(%.2f,%.2f,%.2f)", internalId, x, y, z));

        if (entXfDraws == 0) {
            entGotX = (float) x;
            entGotY = (float) y;
            entGotZ = (float) z;
        }
        entXfDraws++;
        // Limb transforms legitimately move a part off the entity origin, but
        // only by the size of the model - a couple of blocks at most. A whole
        // entity drawn at another entity's position is an order of magnitude out.
        double dev = Math.sqrt((x - entWantX) * (x - entWantX)
                             + (y - entWantY) * (y - entWantY)
                             + (z - entWantZ) * (z - entWantZ));
        if (dev > entMaxDev) {
            entMaxDev = dev;
        }
    }

    /** Called once per frame, after the entity loop closes. */
    public static void reportEntityTransforms() {
        if (!ENABLED) {
            return;
        }
        java.util.Set<String> distinct = new java.util.LinkedHashSet<>(entTransforms);
        // The per-DRAW set cannot detect the bug: limbs legitimately differ, so
        // it reads a healthy 60+ even when every entity is drawn at one place.
        // What collapses when the modelview is stale is the set of distinct
        // whole-block positions - an entity's OWN position, with the sub-block
        // limb offsets rounded away. That is the number to read.
        java.util.Set<String> blocks = new java.util.LinkedHashSet<>();
        for (String s : entTransforms) {
            int at = s.indexOf('@');
            if (at < 0) {
                continue;
            }
            String[] p = s.substring(at + 2, s.length() - 1).split(",");
            if (p.length != 3) {
                continue;
            }
            try {
                blocks.add(Math.round(Float.parseFloat(p[0])) + ","
                         + Math.round(Float.parseFloat(p[1])) + ","
                         + Math.round(Float.parseFloat(p[2])));
            } catch (NumberFormatException ignored) {
                // A malformed row must not take the frame's report down.
            }
        }
        VulkanMod.LOGGER.info("[VKENTXF] replays={} distinctTransforms={} distinctBlocks={}",
                entReplays, distinct.size(), blocks.size());
        entTransforms.clear();
        entReplays = 0;

        // Rows are capped by frame, not by count: every entity needs to be in
        // the same frame's dump or the comparison between them is meaningless.
        if (entFrames < 40 && !entRows.isEmpty()) {
            for (String row : entRows) {
                VulkanMod.LOGGER.info("[VKENTROW] {}", row);
            }
            entFrames++;
        }
        entRows.clear();
    }

    /**
     * Called once per display list at {@code endList}, after the quad merge.
     * Dumps the merged draw shape: how many draws survived, their vertex counts,
     * and the translation of each distinct relative matrix. A model that renders
     * as a scattered heap of parts is a list whose draws disagree about the
     * transform the replay reconstructs.
     */
    public static void onListBuilt(int internalId, int draws, String shape) {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        String key = internalId + "|" + shape;
        if (key.equals(lastBuiltKey) || now - lastBuiltLog < INTERVAL_NS) {
            return;
        }
        lastBuiltKey = key;
        lastBuiltLog = now;
        VulkanMod.LOGGER.info("[VKTEX] built id={} draws={} shape={}", internalId, draws, shape);
    }

    private static String lastBuiltKey = "";
    private static long lastBuiltLog;

    /** GL id -> internal id, as handed out. A collision makes two models share geometry. */
    private static final java.util.Map<Integer, Integer> glToInternalSeen = new java.util.HashMap<>();

    private static int genListsCalls;
    private static int startListCalls;
    private static int glIdRemapBad;

    /** Called from {@code DisplayListManager.genLists}: the id vanilla is told to use. */
    public static void onGenLists(int count, int id, int nextInternalId) {
        if (!ENABLED) {
            return;
        }
        ++genListsCalls;
        if (genListsCalls <= 10 || genListsCalls % 50 == 0) {
            VulkanMod.LOGGER.info("[VKTEX] genLists n={} id={} next={} (call #{})",
                    count, id, nextInternalId, genListsCalls);
        }
    }

    /**
     * Called from {@code DisplayListManager.startList}. A GL id mapped to a SECOND
     * internal list means vanilla reused the id for a different ModelRenderer, so
     * two models share geometry.
     */
    public static void onStartList(int glId, int internalId, Integer prevInternal) {
        if (!ENABLED) {
            return;
        }
        ++startListCalls;

        Integer prev = glToInternalSeen.put(glId, internalId);
        if (prev != null) {
            ++glIdRemapBad;
            if (glIdRemapBad <= 20) {
                VulkanMod.LOGGER.warn("[VKTEX] gl id={} RE-MAPPED internal {} -> {} (bad total={})",
                        glId, prev, internalId, glIdRemapBad);
            }
        }

        if (startListCalls <= 12) {
            VulkanMod.LOGGER.info("[VKTEX] startList gl={} internal={} prev={} (call #{})",
                    glId, internalId, prevInternal, startListCalls);
        }
    }

    private static String lastListKey = "";
    private static long lastListLog;

    private static long lastDescLog;
    private static String lastDescKey = "";

    /**
     * Called from {@code VkGlTexture.glDeleteTextures}. Reports whether the image
     * being freed is still referenced by any sampler slot - if it is, the next
     * draw through that slot samples a destroyed image.
     */
    public static void onDelete(int glId, VulkanImage image) {
        if (!ENABLED) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < VTextureSelector.SIZE; ++i) {
            if (VTextureSelector.getImage(i) == image) {
                sb.append(i).append(' ');
            }
        }
        if (sb.length() > 0) {
            VulkanMod.LOGGER.warn("[VKTEX] DELETE id={} img={} still referenced by slots: {}",
                    glId, describe(image), sb);
        }
    }
}
