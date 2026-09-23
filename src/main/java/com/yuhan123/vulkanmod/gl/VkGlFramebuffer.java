package com.yuhan123.vulkanmod.gl;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
import com.yuhan123.vulkanmod.vulkan.framebuffer.Framebuffer;
import com.yuhan123.vulkanmod.vulkan.framebuffer.RenderPass;
import com.yuhan123.vulkanmod.vulkan.framebuffer.SwapChain;
import com.yuhan123.vulkanmod.vulkan.texture.ImageUtil;
import com.yuhan123.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8_UNORM;
import static org.lwjgl.vulkan.VK11.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK11.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

public class VkGlFramebuffer {
    private static int idCounter = 1;

    private static final Int2ReferenceOpenHashMap<VkGlFramebuffer> map = new Int2ReferenceOpenHashMap<>();
    private static VkGlFramebuffer boundFramebuffer;
    private static VkGlFramebuffer readFramebuffer;

    public static void resetBoundFramebuffer() {
        boundFramebuffer = null;
    }

    public static void beginRendering(VkGlFramebuffer glFramebuffer) {
        boolean begunRendering = glFramebuffer.beginRendering();

        if (begunRendering) {
            Framebuffer framebuffer = glFramebuffer.framebuffer;
            int viewWidth = framebuffer.getWidth();
            int viewHeight = framebuffer.getHeight();

            Renderer.setInvertedViewport(0, 0, viewWidth, viewHeight);
            Renderer.setScissor(0, 0, viewWidth, viewHeight);

            // TODO: invert cull instead of disabling
            VRenderSystem.disableCull();
        }

        boundFramebuffer = glFramebuffer;
    }

    public static int genFramebufferId() {
        int id = idCounter;
        map.put(id, new VkGlFramebuffer(id));
        idCounter++;
        return id;
    }

    public static void bindFramebuffer(int target, int id) {
        if (id == 0) {
            Renderer.getInstance()
                    .endRenderPass();

            if (Renderer.isRecording()) {
                Renderer.getInstance()
                        .getMainPass()
                        .rebindMainTarget();
            }

            boundFramebuffer = null;
            return;
        }

        VkGlFramebuffer glFramebuffer = map.get(id);

        if (glFramebuffer == null)
            throw new NullPointerException("No Framebuffer with ID: %d ".formatted(id));

        switch (target) {
            case GL30.GL_FRAMEBUFFER -> {
                if (glFramebuffer.framebuffer != null) {
                    beginRendering(glFramebuffer);
                }

                boundFramebuffer = glFramebuffer;
            }
            case GL30.GL_READ_FRAMEBUFFER -> {
                readFramebuffer = glFramebuffer;
            }
        }

    }

    public static void deleteFramebuffer(int id) {
        if (id == 0) {
            return;
        }

        boundFramebuffer = map.remove(id);

        if (boundFramebuffer == null)
            throw new NullPointerException("bound framebuffer is null");

        boundFramebuffer.cleanUp(true);
        boundFramebuffer = null;
    }

    public static void framebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) {
        if (attachment != GL30.GL_COLOR_ATTACHMENT0 && attachment != GL30.GL_DEPTH_ATTACHMENT) {
            throw new UnsupportedOperationException();
        }
        if (texTarget != GL11.GL_TEXTURE_2D) {
            throw new UnsupportedOperationException();
        }
        if (level != 0) {
            throw new UnsupportedOperationException();
        }

        boundFramebuffer.setAttachmentTexture(attachment, texture);
    }

    public static void framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        if (boundFramebuffer == null)
            return;

        boundFramebuffer.setAttachmentRenderbuffer(attachment, renderbuffer);
    }

    public static void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1,
                                         int dstY1, int mask, int filter) {
        // TODO: add missing parameters
        ImageUtil.blitFramebuffer(boundFramebuffer.colorAttachment, srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1);
    }

    public static int glCheckFramebufferStatus(int target) {
        //TODO
        return GL30.GL_FRAMEBUFFER_COMPLETE;
    }

    public static VkGlFramebuffer getBoundFramebuffer() {
        return boundFramebuffer;
    }

    public static VkGlFramebuffer getFramebuffer(int id) {
        return map.get(id);
    }

    public final int id;
    Framebuffer framebuffer;
    RenderPass renderPass;

    VulkanImage colorAttachment;
    VulkanImage depthAttachment;

    VkGlFramebuffer(int i) {
        this.id = i;
    }

    boolean beginRendering() {
        return Renderer.getInstance().beginRendering(this.renderPass, this.framebuffer);
    }

    void setAttachmentTexture(int attachment, int texture) {
        VkGlTexture glTexture = VkGlTexture.getTexture(texture);

        if (glTexture == null)
            throw new NullPointerException(String.format("Texture %d is null", texture));

        if (glTexture.vulkanImage == null)
            return;

        switch (attachment) {
            case (GL30.GL_COLOR_ATTACHMENT0) -> this.setColorAttachment(glTexture.getVulkanImage());
            case (GL30.GL_DEPTH_ATTACHMENT) -> this.setDepthAttachment(glTexture.getVulkanImage());

            default -> throw new IllegalStateException("Unexpected value: " + attachment);
        }
    }

    void setAttachmentRenderbuffer(int attachment, int texture) {
        VkGlRenderbuffer renderbuffer = VkGlRenderbuffer.getRenderbuffer(texture);

        if (renderbuffer == null)
            throw new NullPointerException(String.format("Texture %d is null", texture));

        if (renderbuffer.vulkanImage == null)
            return;

        switch (attachment) {
            case (GL30.GL_COLOR_ATTACHMENT0) -> this.setColorAttachment(renderbuffer.getVulkanImage());
            case (GL30.GL_DEPTH_ATTACHMENT) -> this.setDepthAttachment(renderbuffer.getVulkanImage());

            default -> throw new IllegalStateException("Unexpected value: " + attachment);
        }
    }

    void setColorAttachment(VulkanImage image) {
        this.colorAttachment = image;
        createAndBind();
    }

    void setDepthAttachment(VulkanImage image) {
        //TODO check if texture is in depth format
        this.depthAttachment = image;
        createAndBind();
    }

    void createAndBind() {
        // Cannot create without color attachment
        if (this.colorAttachment == null)
            return;

        if (this.framebuffer != null) {
            this.cleanUp(false);
        }

        boolean hasDepthImage = this.depthAttachment != null;
        VulkanImage depthImage = this.depthAttachment;

        this.framebuffer = Framebuffer.builder(this.colorAttachment, depthImage)
                                      .build();
        RenderPass.Builder builder = RenderPass.builder(this.framebuffer);

        builder.getColorAttachmentInfo()
               .setLoadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
               .setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        if (hasDepthImage) {
            builder.getDepthAttachmentInfo()
                   .setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_LOAD_OP_LOAD);
        }

        this.renderPass = builder.build();

        VkGlFramebuffer.beginRendering(this);
    }

    public Framebuffer getFramebuffer() {
        return framebuffer;
    }

    public RenderPass getRenderPass() {
        return renderPass;
    }

    /**
     * Reads the bound framebuffer's colour attachment back into {@code dst}.
     *
     * glReadPixels was never implemented, so Minecraft's screenshot (F2) produced
     * nothing and every visual question had to be answered by guessing. This
     * downloads the colour attachment with vkCmdCopyImageToBuffer (hence the
     * TRANSFER_SRC usage on the attachment) and copies it out.
     *
     * Vulkan images are stored top-down, GL's glReadPixels returns rows
     * bottom-up, so the rows are flipped here to keep GL semantics; whatever the
     * caller does with the result (vanilla's ScreenShotHelper, which knows about
     * GL's order) then behaves as it expects.
     *
     * <p>The bytes come out in a fixed <b>R,G,B,A</b> contract regardless of the
     * attachment's own channel order: a B8G8R8A8 attachment stores B,G,R,A, and
     * handing that straight to a caller that expects RGBA is what made F2
     * screenshots come back with red and blue swapped. Callers that asked GL for
     * a different order (GL_BGRA, as vanilla's ScreenShotHelper does) convert
     * from this contract in GL11Mixin.
     *
     * A one-shot path - it flushes uploads and waits on a fence - so it is
     * deliberately not optimised. Never call it per frame.
     */
    public static void readPixels(int x, int y, int width, int height, ByteBuffer dst) {
        if (dst == null)
            return;

        Framebuffer fb = Renderer.getInstance().getBoundFramebuffer();
        VulkanImage image = fb != null ? fb.getColorAttachment() : null;

        if (image == null) {
            // No render pass is open. Vanilla's F2 path always runs with the main
            // framebuffer bound, but a benchmark screenshot taken at frame start
            // (see MinecraftMixin.takeScreenshot) does not - there the image the
            // last frame rendered into is the swapchain's. The download below
            // submits on the graphics queue and waits on its own fence, so it is
            // ordered after the previous frame's render submission.
            SwapChain swapChain = Renderer.getInstance().getSwapChain();
            image = swapChain != null ? swapChain.getColorAttachment() : null;
        }

        if (image == null) {
            while (dst.hasRemaining())
                dst.put((byte) 0);
            return;
        }

        final int imgW = image.width;
        final int imgH = image.height;
        final int bytes = image.formatSize;

        // The attachment's own channel order. A B8G8R8A8 image (the swapchain on
        // most Windows drivers) stores its bytes as B,G,R,A, so reading it back
        // without swapping would hand the caller RGBA with red and blue
        // exchanged. Normalise here so the contract above always holds.
        final boolean bgra = isBGRFormat(image.format);

        ByteBuffer tmp = MemoryUtil.memAlloc(imgW * imgH * bytes);

        try {
            ImageUtil.downloadTexture(image, MemoryUtil.memAddress0(tmp));

            for (int row = 0; row < height && dst.remaining() >= bytes; ++row) {
                final int srcRow = imgH - 1 - (y + row);

                for (int col = 0; col < width && dst.remaining() >= bytes; ++col) {
                    final int sx = x + col;

                    if (srcRow < 0 || srcRow >= imgH || sx < 0 || sx >= imgW) {
                        for (int b = 0; b < bytes; ++b)
                            dst.put((byte) 0);
                        continue;
                    }

                    final int si = (srcRow * imgW + sx) * bytes;
                    if (bgra && bytes == 4) {
                        // memory is B,G,R,A -> emit R,G,B,A
                        dst.put(tmp.get(si + 2));
                        dst.put(tmp.get(si + 1));
                        dst.put(tmp.get(si));
                        dst.put(tmp.get(si + 3));
                    } else {
                        for (int b = 0; b < bytes; ++b)
                            dst.put(tmp.get(si + b));
                    }
                }
            }
        } finally {
            MemoryUtil.memFree(tmp);
        }
    }

    /** True for the 8-bit BGR-family formats, whose byte order is B,G,R,(A). */
    private static boolean isBGRFormat(int format) {
        return format == VK_FORMAT_B8G8R8A8_UNORM
                || format == VK_FORMAT_B8G8R8A8_SRGB
                || format == VK_FORMAT_B8G8R8_UNORM
                || format == VK_FORMAT_B8G8R8_SRGB;
    }

    void cleanUp(boolean freeAttachments) {
        this.framebuffer.cleanUp(freeAttachments);
        this.renderPass.cleanUp();

        this.framebuffer = null;
        this.renderPass = null;
    }
}
