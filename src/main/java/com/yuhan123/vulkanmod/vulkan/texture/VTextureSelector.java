package com.yuhan123.vulkanmod.vulkan.texture;

import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.gl.VkGlTexture;
import com.yuhan123.vulkanmod.vulkan.shader.Pipeline;
import com.yuhan123.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.minecraft.client.renderer.texture.TextureUtil;

import java.nio.ByteBuffer;

public abstract class VTextureSelector {
    public static final int SIZE = 12;

    private static final VulkanImage[] boundTextures = new VulkanImage[SIZE];

    private static final int[] levels = new int[SIZE];

    /**
     * Bumped every time a sampler slot actually changes image.
     *
     * <p>The terrain batching path can fold a chunk section into an open
     * {@code vkCmdDrawIndexedIndirect} batch without re-running
     * {@code ShaderInstance.apply()} - the batch is one draw, so everything
     * apply() would have established (pipeline, descriptor sets, textures) has
     * to still be in effect. This counter is how it knows the sampler bindings
     * have not moved since the batch was opened; without it a texture change
     * between two sections would be silently ignored for the rest of the batch.
     *
     * <p>Only a real change counts. 1.12.2 re-binds the atlas texture constantly
     * and a counter that bumped on every call would defeat the fast path for no
     * reason.
     */
    private static int bindVersion;

    public static int getBindVersion() {
        return bindVersion;
    }

    private static final VulkanImage whiteTexture = VulkanImage.createWhiteTexture();

    private static int activeTexture = 0;

    public static void bindTexture(VulkanImage texture) {
        if (boundTextures[0] != texture) {
            boundTextures[0] = texture;
            ++bindVersion;
        }
    }

    public static void bindTexture(int i, VulkanImage texture) {
        if (i < 0 || i >= SIZE) {
            VulkanMod.LOGGER.error(String.format("On Texture binding: index %d out of range [0, %d]", i, SIZE - 1));
            return;
        }

        if (boundTextures[i] != texture || levels[i] != -1) {
            boundTextures[i] = texture;
            levels[i] = -1;
            ++bindVersion;
        }
    }

    public static void bindImage(int i, VulkanImage texture, int level) {
        if (i < 0 || i > 7) {
            VulkanMod.LOGGER.error(String.format("On Texture binding: index %d out of range [0, %d]", i, SIZE - 1));
            return;
        }

        if (boundTextures[i] != texture || levels[i] != level) {
            boundTextures[i] = texture;
            levels[i] = level;
            ++bindVersion;
        }
    }

    public static void uploadSubTexture(int mipLevel, int width, int height, int xOffset, int yOffset, int unpackSkipRows, int unpackSkipPixels, int unpackRowLength, ByteBuffer buffer) {
        VulkanImage texture = boundTextures[activeTexture];

        if(texture == null)
            throw new NullPointerException("Texture is null at index: " + activeTexture);

        texture.uploadSubTextureAsync(mipLevel, width, height, xOffset, yOffset, unpackSkipRows, unpackSkipPixels, unpackRowLength, buffer);
    }

    public static int getTextureIdx(String name) {
        return switch (name) {
            case "Sampler0", "DiffuseSampler" -> 0;
            case "Sampler1" -> 1;
            case "Sampler2" -> 2;
            case "Sampler3" -> 3;
            case "Sampler4" -> 4;
            case "Sampler5" -> 5;
            case "Sampler6" -> 6;
            case "Sampler7" -> 7;
            default -> throw new IllegalStateException("Unknown sampler name: " + name);
        };
    }

    // bindShaderTextures runs on every draw, and a missing bound texture used to
    // resolve through a map lookup plus getGlTextureId() each time. There is
    // effectively one missing-texture id, so a single-entry cache is enough.
    private static int cachedMissingTextureId = -1;
    private static VulkanImage cachedMissingImage;

    private static VulkanImage resolveMissingImage() {
        int id = TextureUtil.MISSING_TEXTURE.getGlTextureId();

        if (id != cachedMissingTextureId) {
            cachedMissingTextureId = id;
            cachedMissingImage = null;

            VkGlTexture texture = VkGlTexture.getTexture(id);
            if (texture != null) {
                cachedMissingImage = texture.getVulkanImage();
            }
        }

        return cachedMissingImage;
    }

    public static void bindShaderTextures(Pipeline pipeline) {
        var imageDescriptors = pipeline.getImageDescriptors();

        for (ImageDescriptor state : imageDescriptors) {
            VulkanImage image = getBoundTexture(state.imageIdx);

            if (image == null) {
                // The lightmap (slot 2) may not be bound yet; fall back to a white
                // texture so Color * white = Color (full daylight) instead of black.
                image = state.imageIdx == 2 ? getWhiteTexture() : resolveMissingImage();
            }

            // TEMPORARY: lightmap-slot probe - see TextureProbe.
            if (state.imageIdx == 2) {
                TextureProbe.onLightmapBind(image, image == getWhiteTexture());
            }

            if (image != null) {
                VTextureSelector.bindTexture(state.imageIdx, image);
            }
        }

        TextureProbe.onPipelineBind(pipeline, imageDescriptors);
    }

    public static VulkanImage getImage(int i) {
        return boundTextures[i];
    }

    public static void setLightTexture(VulkanImage texture) {
        if (boundTextures[2] != texture) {
            boundTextures[2] = texture;
            ++bindVersion;
        }
    }

    public static void setOverlayTexture(VulkanImage texture) {
        if (boundTextures[1] != texture) {
            boundTextures[1] = texture;
            ++bindVersion;
        }
    }

    public static void setActiveTexture(int activeTexture) {
        if (activeTexture < 0 || activeTexture >= SIZE) {
            VulkanMod.LOGGER.error(
                    String.format("On Texture binding: index %d out of range [0, %d]", activeTexture, SIZE - 1));
        }

        VTextureSelector.activeTexture = activeTexture;
    }

    public static VulkanImage getBoundTexture() {
        return boundTextures[activeTexture];
    }

    public static VulkanImage getBoundTexture(int i) {
        return boundTextures[i];
    }

    public static VulkanImage getWhiteTexture() {
        return whiteTexture;
    }
}
