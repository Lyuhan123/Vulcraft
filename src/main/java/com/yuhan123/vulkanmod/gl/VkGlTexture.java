package com.yuhan123.vulkanmod.gl;

import com.yuhan123.vulkanmod.VKProf;
import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.config.VulkanModConfig;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import com.yuhan123.vulkanmod.vulkan.memory.MemoryManager;
import com.yuhan123.vulkanmod.vulkan.texture.ImageUtil;
import com.yuhan123.vulkanmod.vulkan.texture.SamplerManager;
import com.yuhan123.vulkanmod.vulkan.texture.VTextureSelector;
import com.yuhan123.vulkanmod.vulkan.texture.VulkanImage;
import net.minecraft.util.IntHashMap;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.vulkan.VK10.*;

public class VkGlTexture {
    private static int ID_COUNTER = 1;
    private static final Int2ReferenceOpenHashMap<VkGlTexture> map = new Int2ReferenceOpenHashMap<>();
    private static int boundTextureId = 0;



    private static VkGlTexture boundTexture;
    private static int activeTexture = 0;

    public static int getBoundTextureId() {
        return boundTextureId;
    }

    private static int unpackRowLength;
    private static int unpackSkipRows;
    private static int unpackSkipPixels;

    /**
     * Mipmap chain for GL textures. MC asks for its configured mipmap level
     * through GL_TEXTURE_MAX_LEVEL / MAX_LOD and then glGenerateMipmap; honouring
     * those is what makes distant (minified) terrain sample from a small mip
     * instead of thrashing the texture cache at mip 0.
     *
     * Set MIP=0 to restore the previous single-level behaviour.
     */
    private static final boolean MIP_ENABLED = VulkanModConfig.getBool("MIP", true);

    private static boolean MIP_LOGGED;

    /**
     * Grow each sprite's colour into its own fully transparent texels, leaving
     * alpha at 0. ALPHADILATE=0 disables it.
     *
     * <p>Minecraft's cutout textures (grass, leaves) store black in the
     * transparent area. Building a mip chain box-filters that black into the
     * edges, so the first mip levels put a dark outline around every blade -
     * the "grass with black edges" artifact. Copying the neighbouring opaque
     * colour into the transparent texels first means the downsample only ever
     * averages the sprite's own colour. Alpha is left untouched, so the alpha
     * test still cuts exactly the same silhouette; only the (invisible) RGB of
     * discarded texels changes.
     */
    private static final boolean ALPHA_DILATE = VulkanModConfig.getBool("ALPHADILATE", true);

    /**
     * Textures whose mip chain must be rebuilt from the (dilated) level 0.
     *
     * <p>Dilating level 0 is not enough on its own: Minecraft builds its own
     * mip levels on the CPU from the <em>undilated</em> level 0 and uploads
     * them, so the levels the GPU actually samples for distant terrain still
     * carry the black outline. Regenerating the chain from the fixed level 0
     * replaces them.
     *
     * <p>Rebuilt once per texture: animated textures re-upload level 0 every
     * frame, and rebuilding on each of those would add a GPU blit and a fence
     * wait to every frame.
     */
    private static final java.util.Set<VulkanImage> MIP_REBUILD_PENDING =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static final java.util.Set<VulkanImage> MIP_REBUILD_DONE =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * Rebuilds the mip chains queued by texture uploads. Called once per frame
     * from {@code Renderer.beginFrame}, i.e. after the previous frame's upload
     * batch has been submitted, so the level-0 data being read is on the GPU.
     */
    public static void rebuildPendingMipmaps() {
        if (MIP_REBUILD_PENDING.isEmpty()) {
            return;
        }

        for (VulkanImage image : MIP_REBUILD_PENDING) {
            try {
                com.yuhan123.vulkanmod.vulkan.texture.ImageUtil.generateMipmaps(image);
                MIP_REBUILD_DONE.add(image);
            } catch (Throwable t) {
                com.yuhan123.vulkanmod.VKProf.warn("[VKPROF] mip rebuild failed for {}x{}: {}",
                        image.width, image.height, t.toString());
            }
        }
        MIP_REBUILD_PENDING.clear();
    }

    private static void dilateTransparentRGB(ByteBuffer buf, int width, int height) {
        final int n = width * height;
        final int base = buf.position();

        if (buf.capacity() - base < n * 4) {
            return;
        }

        boolean[] cur = new boolean[n];
        int opaque = 0;
        for (int i = 0; i < n; i++) {
            if ((buf.get(base + i * 4 + 3) & 0xFF) != 0) {
                cur[i] = true;
                opaque++;
            }
        }
        // Fully opaque or fully transparent: nothing bleeds, nothing to do.
        if (opaque == 0 || opaque == n) {
            return;
        }

        boolean[] next = new boolean[n];

        // A handful of passes is enough: sprites are small and the artifact
        // only comes from texels immediately adjacent to the silhouette.
        for (int pass = 0; pass < 8; pass++) {
            boolean changed = false;
            System.arraycopy(cur, 0, next, 0, n);

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    final int idx = y * width + x;
                    if (cur[idx]) {
                        continue;
                    }

                    int srcIdx = -1;
                    if (x > 0 && cur[idx - 1]) {
                        srcIdx = idx - 1;
                    } else if (x < width - 1 && cur[idx + 1]) {
                        srcIdx = idx + 1;
                    } else if (y > 0 && cur[idx - width]) {
                        srcIdx = idx - width;
                    } else if (y < height - 1 && cur[idx + width]) {
                        srcIdx = idx + width;
                    }

                    if (srcIdx >= 0) {
                        final int s = base + srcIdx * 4;
                        final int d = base + idx * 4;
                        buf.put(d, buf.get(s));
                        buf.put(d + 1, buf.get(s + 1));
                        buf.put(d + 2, buf.get(s + 2));
                        // alpha stays 0 - the silhouette must not change.
                        next[idx] = true;
                        changed = true;
                    }
                }
            }

            if (!changed) {
                break;
            }
            boolean[] t = cur;
            cur = next;
            next = t;
        }
    }

    static {
    }

    public static void bindIdToImage(int id, VulkanImage vulkanImage) {
        VkGlTexture texture = map.get(id);
        texture.vulkanImage = vulkanImage;
    }

    public static int genTextureId() {
        int id = ID_COUNTER;
        map.put(id, new VkGlTexture(id));
        ID_COUNTER++;
        return id;
    }

    public static void bindTexture(int id) {
        boundTextureId = id;
        boundTexture = map.get(id);

        if (id <= 0)
            return;

        if (boundTexture == null) {
            // TEMPORARY: this throw is the prime suspect for "the mob skin is
            // allocated and then never filled". TextureUtil.allocateTextureImpl
            // deletes and re-binds the id, then uploadTextureImageSub binds it
            // again; if the second bind cannot find the entry, the exception
            // propagates out of loadTexture and the image stays empty forever.
            throw new NullPointerException("bound texture is null" + id);
        }

        VulkanImage vulkanImage = boundTexture.vulkanImage;
        if (vulkanImage != null) {
            // The ender-crystal healing beam texture is 16x256 and scrolls its V
            // coordinate far negative over time (RenderDragon.renderCrystalBeams
            // uses tex(f9, -(ticks + partialTicks) * 0.01)). Vanilla relies on GL's
            // default REPEAT wrap for that scroll; this port defaults every texture
            // to CLAMP_TO_EDGE, which would pin the beam to V=0 (alpha 0) and make
            // it invisible. Force REPEAT on it so the soft alpha gradient wraps and
            // shows. 16x256 is the beam's signature size; no other MC texture uses it.
            if (boundTexture.width == 16 && boundTexture.height == 256 && boundTexture.clamp) {
                boundTexture.clamp = false;
                boundTexture.updateSampler();
            }
            // 1.12.2 binds the lightmap at GL_TEXTURE1 (OpenGlHelper.lightmapTexUnit = 33985),
            // but the block/item/entity shaders all sample it from slot 2 (Sampler2 ->
            // imageIdx 2). Publish it there so those shaders get real lighting.
            if (activeTexture == 1) {
                VTextureSelector.setLightTexture(vulkanImage);
            }

            // Only publish the image into the selector's slot array for units that are
            // real shader samplers. GL_TEXTURE2 (33986) is the vanilla brightness / FX
            // overlay driven by RenderLivingBase.setBrightness / unsetBrightness; this
            // port reproduces that effect through the EntityFlash UBO + ColorModulator
            // rather than a shader sampler, so binding it would write straight into
            // slot 2 - the lightmap sampler - and clobber world lighting for every
            // later entity in the frame. That was the "attack one mob -> all mobs go
            // full bright" defect: an attacked mob's setBrightness() binds the white
            // TEXTURE_BRIGHTNESS onto unit 2, overwriting the lightmap, and
            // unsetBrightness() never restores it.
            if (activeTexture != 2) {
                VTextureSelector.bindTexture(activeTexture, vulkanImage);
            }
        } else {
        }
    }

    /**
     * TEMPORARY (pass 32): called when {@code textures/misc/shadow.png} is
     * bound. Reports the shadow texture's real sampler state once (so the
     * clamp flag that vanilla actually left it in is on the record), then
     * forces {@code CLAMP_TO_EDGE} on it.
     *
     * <p>Why. Vanilla draws the entity shadow as one quad per block column under
     * the entity, with UVs derived from the entity's world position - so every
     * column but the entity's own maps outside {@code [0,1]}. If the sampler
     * wraps, each of those columns re-shows the whole {@code shadow.png} circle
     * and a single entity throws a grid of them (the reported artifact). Clamped,
     * the far columns land on the transparent border and vanish, leaving one soft
     * circle. Scoped to this one texture on purpose: a global clamp changed the
     * sky and terrain too, which makes the comparison unreadable.
     */
    private static boolean shadowClampReported = false;

    public static void vulkanmod$clampShadowTexture() {
        if (boundTexture == null) {
            return;
        }
        if (!shadowClampReported) {
            shadowClampReported = true;
            com.yuhan123.vulkanmod.VKProf.info("[VKPROF] shadow texture id={} {}x{} clamp={} minFilter={} magFilter={} maxLevel={} mipLevels={}",
                    boundTexture.id, boundTexture.width, boundTexture.height, boundTexture.clamp,
                    boundTexture.minFilter, boundTexture.magFilter, boundTexture.maxLevel,
                    boundTexture.vulkanImage == null ? -1 : boundTexture.vulkanImage.mipLevels);
        }
        texParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        texParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    public static void glDeleteTextures(IntBuffer intBuffer) {
        for (int i = intBuffer.position(); i < intBuffer.limit(); i++) {
            glDeleteTextures(intBuffer.get(i));
        }
    }

    public static void glDeleteTextures(int i) {
        VkGlTexture glTexture = map.remove(i);
        VulkanImage image = glTexture != null ? glTexture.vulkanImage : null;
        if (image != null) {
            // TEMPORARY: the delete path frees the image but leaves any sampler
            // slot that still references it pointing at freed memory. Log it so
            // the probe can tell whether a black draw is sampling a dead image.
            MemoryManager.getInstance().addToFreeable(image);
        }
    }

    public static VkGlTexture getTexture(int id) {
        if (id == 0)
            return null;

        return map.get(id);
    }

    public static void activeTexture(int i) {
        activeTexture = i - GL_TEXTURE0;
        VTextureSelector.setActiveTexture(activeTexture);
    }

    /** TEMPORARY diagnostic accessor for the texture probe. */
    public static int getActiveTextureIndex() {
        return activeTexture;
    }

    public static void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, long pixels) {
        if (checkParams(level, width, height))
            return;

        // TEMPORARY: report the ALLOCATE step. uploadSubImage only fires when
        // bytes are written, so an image that is allocated and then never filled

        boundTexture.updateParams(level, width, height, internalFormat, type);
        boundTexture.allocateIfNeeded();

        VTextureSelector.bindTexture(activeTexture, boundTexture.vulkanImage);

        texSubImage2D(target, level, 0, 0, width, height, format, type, pixels);
    }

    public static void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, @Nullable ByteBuffer pixels) {
        if (checkParams(level, width, height))
            return;


        boundTexture.updateParams(level, width, height, internalFormat, type);
        boundTexture.allocateIfNeeded();

        VTextureSelector.bindTexture(activeTexture, boundTexture.vulkanImage);

        texSubImage2D(target, level, 0, 0, width, height, format, type, pixels);
    }

    private static boolean checkParams(int level, int width, int height) {
        if (width == 0 || height == 0)
            return true;

        return false;
    }

    public static void texSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, long pixels) {
        if (width == 0 || height == 0)
            return;

        ByteBuffer src;

        VkGlBuffer glBuffer = VkGlBuffer.getPixelUnpackBufferBound();
        if (glBuffer != null) {

            glBuffer.data.position((int) pixels);
            src = glBuffer.data;
        } else {
            if (pixels != 0L) {
                src = getByteBuffer(width, height, pixels);
            } else {
                src = null;
            }
        }

        if (src != null) {
            boundTexture.uploadSubImage(level, xOffset, yOffset, width, height, format, src);
        } else {
        }
    }

    public static void texSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, IntBuffer pixels) {

        if (width == 0 || height == 0)
            return;

        ByteBuffer src;

        VkGlBuffer glBuffer = VkGlBuffer.getPixelUnpackBufferBound();
        if (glBuffer != null) {

            glBuffer.data.position((int) pixels.position() * 4);
            src = glBuffer.data;
        } else {
            if (pixels != null && pixels.capacity() > 0) {
                long ptr = MemoryUtil.memAddress(pixels);//IntBuffer to long
                src = getByteBuffer(width, height, ptr);
            } else {
                src = null;
            }
        }

        if (src != null) {
            boundTexture.uploadSubImage(level, xOffset, yOffset, width, height, format, src);
        } else {
        }
    }

    private static ByteBuffer getByteBuffer(int width, int height, long pixels) {
        ByteBuffer src;
        // TODO: hardcoded format size
        int formatSize = 4;
        int rowLength = unpackRowLength != 0 ? unpackRowLength : width;
        int offset = (unpackSkipRows * rowLength + unpackSkipPixels) * formatSize;
        src = MemoryUtil.memByteBuffer(pixels + offset, (rowLength * height - unpackSkipPixels) * formatSize);
        return src;
    }

    public static void texSubImage2D(int target, int level, int xOffset, int yOffset, int width , int height, int format, int type, @Nullable ByteBuffer pixels) {
        if (width == 0 || height == 0)
            return;

        ByteBuffer src;

        VkGlBuffer glBuffer = VkGlBuffer.getPixelUnpackBufferBound();
        if (glBuffer != null) {
            if (pixels != null) {
                throw new IllegalStateException("Trying to use pixel buffer when there is a Pixel Unpack Buffer bound.");
            }

            glBuffer.data.position(0);
            src = glBuffer.data;
        } else {
            src = pixels;
        }

        if (src != null) {
            boundTexture.uploadSubImage(level, xOffset, yOffset, width, height, format, src);
        } else {
        }
    }

    public static void texParameteri(int target, int pName, int param) {
        if (target != GL11.GL_TEXTURE_2D)
            throw new UnsupportedOperationException("target != GL_TEXTURE_2D not supported");

        if (boundTexture == null)
            return;

        switch (pName) {
            // GL_TEXTURE_MAX_LEVEL / MAX_LOD / MIN_LOD are GL 1.2 enums (MC 1.12
            // does not use GL30 at all), so the numeric values are used directly
            // - TextureUtilMixin does the same. MC sets MAX_LEVEL and MAX_LOD to
            // its configured mipmap level, and without them every texture stays
            // single-level: distant terrain is then sampled from mip 0 with no
            // minification, which both moires and thrashes the texture cache.
            case 33085 -> { if (MIP_ENABLED) boundTexture.setMaxLevel(param); }  // GL_TEXTURE_MAX_LEVEL
            case 33083 -> { if (MIP_ENABLED) boundTexture.setMaxLod(param); }    // GL_TEXTURE_MAX_LOD
            case 33082 -> {}                                                     // GL_TEXTURE_MIN_LOD
//            case GL30.GL_TEXTURE_LOD_BIAS -> {}

            case GL11.GL_TEXTURE_MAG_FILTER -> boundTexture.setMagFilter(param);
            case GL11.GL_TEXTURE_MIN_FILTER -> boundTexture.setMinFilter(param);

            case GL11.GL_TEXTURE_WRAP_S, GL11.GL_TEXTURE_WRAP_T -> boundTexture.setClamp(param);

            default -> {}
        }

        //TODO
    }

    public static int getTexParameteri(int target, int pName) {
        if (target != GL11.GL_TEXTURE_2D)
            throw new UnsupportedOperationException("target != GL_TEXTURE_2D not supported");

        if (boundTexture == null)
            return -1;

        return switch (pName) {
            case GL11.GL_TEXTURE_INTERNAL_FORMAT -> GlUtil.getGlFormat(boundTexture.vulkanImage.format);
            case GL11.GL_TEXTURE_WIDTH -> boundTexture.vulkanImage.width;
            case GL11.GL_TEXTURE_HEIGHT -> boundTexture.vulkanImage.height;

//            case GL30.GL_TEXTURE_MAX_LEVEL -> boundTexture.maxLevel;
//            case GL30.GL_TEXTURE_MAX_LOD -> boundTexture.maxLod;

            case GL11.GL_TEXTURE_MAG_FILTER -> boundTexture.magFilter;
            case GL11.GL_TEXTURE_MIN_FILTER -> boundTexture.minFilter;

            default -> -1;
        };
    }

    public static int getTexLevelParameter(int target, int level, int pName) {
        if (target != GL11.GL_TEXTURE_2D)
            throw new UnsupportedOperationException("target != GL_TEXTURE_2D not supported");

        if (boundTexture == null)
            return -1;

        return switch (pName) {
            case GL11.GL_TEXTURE_INTERNAL_FORMAT -> GlUtil.getGlFormat(boundTexture.vulkanImage.format);
            case GL11.GL_TEXTURE_WIDTH -> boundTexture.vulkanImage.width;
            case GL11.GL_TEXTURE_HEIGHT -> boundTexture.vulkanImage.height;

            default -> -1;
        };
    }

    public static void pixelStoreI(int pName, int value) {
        switch (pName) {
            case GL11.GL_UNPACK_ROW_LENGTH -> unpackRowLength = value;
            case GL11.GL_UNPACK_SKIP_ROWS -> unpackSkipRows = value;
            case GL11.GL_UNPACK_SKIP_PIXELS -> unpackSkipPixels = value;
        }
    }

    public static void generateMipmap(int target) {
        if (target != GL11.GL_TEXTURE_2D)
            throw new UnsupportedOperationException("target != GL_TEXTURE_2D not supported");

        // NOTE: glGenerateMipmap is a GL30 entry point and MC 1.12 does not use
        // GL30, so this path is not normally reached - 1.12 uploads its mip
        // levels from the CPU side (TextureUtil.uploadTextureMipmap) after
        // allocateTextureImpl has allocated them. It is kept working in case a
        // mod does call it. Without mips being sampled at all - which was the
        // real bug, see updateSampler() - distant terrain moires and thrashes
        // the texture cache.
        if (MIP_ENABLED && boundTexture != null && boundTexture.vulkanImage != null
                && boundTexture.vulkanImage.mipLevels > 1) {
            if (!MIP_LOGGED) {
                MIP_LOGGED = true;
                com.yuhan123.vulkanmod.VKProf.info("[VKPROF] mipmap: generating {} levels for {}x{}",
                        boundTexture.vulkanImage.mipLevels,
                        boundTexture.vulkanImage.width, boundTexture.vulkanImage.height);
            }
            boundTexture.generateMipmaps();
        }
    }

    public static void getTexImage(int tex, int level, int format, int type, long pixels) {
        VulkanImage image = boundTexture.vulkanImage;

        VkGlBuffer buffer = VkGlBuffer.getPixelPackBufferBound();
        long ptr;
        if (buffer != null) {
            buffer.data.position((int) pixels);

            ptr = MemoryUtil.memAddress(buffer.data);
        } else {
            ptr = pixels;
        }


        ImageUtil.downloadTexture(image, ptr);
    }

    public static void setVulkanImage(int id, VulkanImage vulkanImage) {
        VkGlTexture texture = map.get(id);

        texture.vulkanImage = vulkanImage;
    }

    public static VkGlTexture getBoundTexture() {
        return boundTexture;
    }

    public final int id;
    VulkanImage vulkanImage;

    int width, height;
    int vkFormat;

    boolean needsUpdate = false;
    int maxLevel = 0;
    int maxLod = 0;
    int minFilter, magFilter = GL11.GL_LINEAR;

    boolean clamp = true;

    public VkGlTexture(int id) {
        this.id = id;
    }

    void updateParams(int level, int width, int height, int internalFormat, int type) {
        if (level > this.maxLevel) {
            this.maxLevel = level;

            this.needsUpdate = true;
        }

        if (level == 0) {
            int vkFormat = GlUtil.vulkanFormat(internalFormat, type);

            if (this.vulkanImage == null || this.width != width || this.height != height || vkFormat != vulkanImage.format) {
                this.width = width;
                this.height = height;
                this.vkFormat = vkFormat;

                this.needsUpdate = true;
            }
        }
    }

    void allocateIfNeeded() {
        if (needsUpdate) {
            allocateImage(width, height, vkFormat);
            updateSampler();

            needsUpdate = false;
        }
    }

    void allocateImage(int width, int height, int vkFormat) {
        if (this.vulkanImage != null)
            this.vulkanImage.free();

        if (VulkanImage.isDepthFormat(vkFormat)) {
            this.vulkanImage = VulkanImage.createDepthImage(
                    vkFormat, width, height,
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                    false, true);
        }
        else {
            this.vulkanImage = new VulkanImage.Builder(width, height)
                    .setName(String.format("GlTexture %d", this.id))
                    .setMipLevels(maxLevel + 1)
                    .setFormat(vkFormat)
                    .addUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .createVulkanImage();
        }
    }

    void updateSampler() {
        if (vulkanImage == null)
            return;

        byte samplerFlags;
        samplerFlags = clamp ? SamplerManager.CLAMP_BIT : 0;
        samplerFlags |= magFilter == GL11.GL_LINEAR ? SamplerManager.LINEAR_FILTERING_BIT : 0;

        // All four mipmap min-filters have to be recognised. MC sets
        // GL_NEAREST_MIPMAP_LINEAR (9986) on its atlases, which was missing
        // here: it fell through to `default -> 0`, so USE_MIPMAPS_BIT was
        // never set and every texture was sampled from mip 0 no matter how
        // far away it was - the source of the moire on water and distant
        // terrain, and a texture-cache cost as well.
        samplerFlags |= switch (minFilter) {
            case GL11.GL_LINEAR_MIPMAP_LINEAR, GL11.GL_NEAREST_MIPMAP_LINEAR ->
                    SamplerManager.USE_MIPMAPS_BIT | SamplerManager.MIPMAP_LINEAR_FILTERING_BIT;
            case GL11.GL_NEAREST_MIPMAP_NEAREST, GL11.GL_LINEAR_MIPMAP_NEAREST ->
                    SamplerManager.USE_MIPMAPS_BIT;
            default -> 0;
        };

        vulkanImage.updateTextureSampler(maxLod, samplerFlags);
    }

    private void uploadSubImage(int level, int xOffset, int yOffset, int width, int height, int format, ByteBuffer pixels) {
        if (level == 0 && xOffset == 0 && yOffset == 0) {
        }
        if (level == 0 && pixels != null) {
            // TEMPORARY: stitch the terrain atlas from its per-sprite sub-uploads.
        }
        ByteBuffer src;
        final long swzT = FrameProfiler.texStart();
        if (format == GL11.GL_RGB && vulkanImage.format == VK_FORMAT_R8G8B8A8_UNORM) {
            src = GlUtil.RGBtoRGBA_buffer(pixels);
        } else if (format == GL_BGRA && vulkanImage.format == VK_FORMAT_R8G8B8A8_UNORM) {
            src = GlUtil.BGRAtoRGBA_buffer(pixels);
        } else {
            src = pixels;
        }
        if (src != pixels) {
            FrameProfiler.addTextureSwizzle(swzT, src.capacity());
        }

        // Grass/leaves pick up a black outline once the mip chain is sampled,
        // because their transparent texels are black and downsampling averages
        // that black into the silhouette. Growing the sprite's own colour into
        // those texels first (alpha untouched) removes the artifact at the
        // source - see dilateTransparentRGB.
        if (ALPHA_DILATE && level == 0 && src != null
                && vulkanImage.format == VK_FORMAT_R8G8B8A8_UNORM) {
            dilateTransparentRGB(src, width, height);

            // Queue a chain rebuild: MC's own mip levels were built from the
            // pre-dilation level 0 and still carry the black outline.
            if (vulkanImage.mipLevels > 1 && !MIP_REBUILD_DONE.contains(vulkanImage)) {
                MIP_REBUILD_PENDING.add(vulkanImage);
            }
        }

        // TEMPORARY: report what the upload actually contains. A mob that binds
        // the right image and a white ColorModulator but still draws black can
        // only be explained by the bytes: zeroes, or a layout the sampler cannot

        final long upT = FrameProfiler.texStart();
        this.vulkanImage.uploadSubTextureAsync(level, width, height, xOffset, yOffset, 0, 0, unpackRowLength, src);
        FrameProfiler.addTextureUpload(upT);
        FrameProfiler.onTextureUpload(src.remaining());

        if (src != pixels) {
            MemoryUtil.memFree(src);
        }
    }

    void generateMipmaps() {
        // TEMPORARY: NOMIP=1 skips mip generation entirely. The mip
        // chain is the leading hypothesis for the per-texel canopy holes, and a
        // code-level fix is only worth writing once a measurement shows the mips
        // are actually implicated. Skipping generation altogher is the cleanest
        // possible test of that: if the holes vanish, the mips are guilty; if
        // they persist, the mips are exonerated and the cause is elsewhere.
        if (VulkanModConfig.getBool("NOMIP", false)) {
            return;
        }
        ImageUtil.generateMipmaps(vulkanImage);
    }

    void setMaxLevel(int l) {
        if (l < 0)
            throw new IllegalStateException("max level cannot be < 0.");

        if (maxLevel != l) {
            maxLevel = l;
            needsUpdate = true;
        }
    }

    void setMaxLod(int l) {
        if (l < 0)
            throw new IllegalStateException("max level cannot be < 0.");

        if (maxLod != l) {
            maxLod = l;
            updateSampler();
        }
    }

    void setMagFilter(int v) {
        switch (v) {
            case GL11.GL_LINEAR, GL11.GL_NEAREST -> {
            }

            default -> throw new IllegalArgumentException("illegal mag filter value: " + v);
        }

        this.magFilter = v;
        updateSampler();
    }

    void setMinFilter(int v) {
        switch (v) {
            case GL11.GL_LINEAR, GL11.GL_NEAREST,
                 GL11.GL_LINEAR_MIPMAP_LINEAR, GL11.GL_NEAREST_MIPMAP_LINEAR,
                 GL11.GL_LINEAR_MIPMAP_NEAREST, GL11.GL_NEAREST_MIPMAP_NEAREST -> {
            }

            default -> throw new IllegalArgumentException("illegal min filter value: " + v);
        }

        this.minFilter = v;
        updateSampler();
    }

    void setClamp(int v) {
        if (v == GL_CLAMP_TO_EDGE) {
            this.clamp = true;
        } else {
            this.clamp = false;
        }

        updateSampler();
    }

    public VulkanImage getVulkanImage() {
        return vulkanImage;
    }

    public void setVulkanImage(VulkanImage vulkanImage) {
        this.vulkanImage = vulkanImage;
    }

}