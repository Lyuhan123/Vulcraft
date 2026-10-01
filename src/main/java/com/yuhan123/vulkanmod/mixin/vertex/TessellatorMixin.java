package com.yuhan123.vulkanmod.mixin.vertex;

import com.yuhan123.vulkanmod.VKProf;
import com.yuhan123.vulkanmod.config.VulkanModConfig;
import com.yuhan123.vulkanmod.render.PipelineManager;
import com.yuhan123.vulkanmod.render.shader.ShaderInstance;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.shader.Pipeline;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.ByteBuffer;

@Mixin(Tessellator.class)
public class TessellatorMixin {

//    private static int vkDrawLogs = 0;
//
    @Redirect(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/WorldVertexBufferUploader;draw(Lnet/minecraft/client/renderer/BufferBuilder;)V"))
    public void vkDraw(WorldVertexBufferUploader uploader, BufferBuilder buffer) {
        int vertexCount = buffer.getVertexCount();
//        if (vkDrawLogs < 16 && vertexCount > 0) {
//            com.yuhan123.vulkanmod.VulkanMod.LOGGER.info("[VKDBG] vkDraw count={} mode={} fmt={}",
//                    vertexCount, buffer.getDrawMode(), buffer.getVertexFormat());
//            vkDrawLogs++;
//        }
        if (vertexCount <= 0)
            return;

        if (!Renderer.isRecording())
            return;

        VertexFormat vertexFormat = buffer.getVertexFormat();
        ShaderInstance shader = PipelineManager.chooseShader(vertexFormat);

        if (shader == null || shader.getPipeline() == null)
            return;

        // Update uniforms and bind the graphics pipeline + descriptor sets
        // (ShaderInstance.apply() already uploads UBOs and binds descriptor sets)
        shader.apply();

        Renderer renderer = Renderer.getInstance();
        Pipeline pipeline = renderer.getBoundPipeline();
        if (pipeline == null)
            return;

        ByteBuffer vertexData = buffer.getByteBuffer();
        vertexData.position(0);

        // Entity models are compiled into GL display lists (glNewList..glEndList)
        // via Tessellator.draw. During compilation the vertices must be captured
        // into the list so glCallList can replay them; drawing them immediately
        // would both waste work and leave the list empty (invisible entities).
        if (com.yuhan123.vulkanmod.gl.DisplayListManager.isRecordingList()) {
            com.yuhan123.vulkanmod.gl.DisplayListManager.captureDisplayListDraw(
                    vertexData, buffer.getDrawMode(), vertexFormat, vertexCount);
            return;
        }

        if (com.yuhan123.vulkanmod.render.util.FrameProfiler.entLoopOpen()) {
        }

        // TEMP DIAGNOSTIC (FBDBG=1): the falling-block report describes
        // blocks that turn sky-coloured in some chunks, normal in others, with a
        // gradient between - the classic signature of a lightmap coordinate that
        // varies with world position. Falling blocks render through the BLOCK
        // format (the same "block" pipeline as terrain chunks), whose lightmap is
        // the per-vertex UV2 element folded into vertexColor by block.vsh via
        // texture(Sampler2, (UV2+8)/256). The first pass only captured ITEM
        // draws and so never saw the falling block at all. Capture BOTH ITEM and
        // BLOCK, and for BLOCK decode the raw UV2 (little-endian shorts at byte
        // 24) so we can see whether it carries a real packed-light value or some
        // stray world coordinate. Cap 64.
        if (VulkanModConfig.getBool("FBDBG", false) && fbdbgDumps < 64
                && (vertexFormat == net.minecraft.client.renderer.vertex.DefaultVertexFormats.ITEM
                    || vertexFormat == net.minecraft.client.renderer.vertex.DefaultVertexFormats.BLOCK)) {
            fbdbgDumps++;
            final int stride = vertexFormat.getSize();
            final boolean isBlock = vertexFormat == net.minecraft.client.renderer.vertex.DefaultVertexFormats.BLOCK;
            final java.nio.FloatBuffer mvp = com.yuhan123.vulkanmod.vulkan.VRenderSystem.mvpFloatBuffer();
            final StringBuilder sb = new StringBuilder("[VKPROF] FBDBG draw #").append(fbdbgDumps)
                    .append(isBlock ? " BLOCK" : " ITEM")
                    .append(" count=").append(vertexCount)
                    .append(" stride=").append(stride)
                    .append(String.format(" mvpT=(%.1f,%.1f,%.1f)", mvp.get(12), mvp.get(13), mvp.get(14)));
            if (isBlock) {
                // BLOCK layout: Position(12) Color(4) UV0(8) UV2(4, ivec2 shorts) Normal(4) -> 32
                // UV2 carries the lightmap coords (0..240); (UV2+8)/256 is what block.vsh samples.
                final int u = (vertexData.get(25) & 0xff) << 8 | (vertexData.get(24) & 0xff);
                final int v = (vertexData.get(27) & 0xff) << 8 | (vertexData.get(26) & 0xff);
                sb.append(String.format(" uv2=(%d,%d) lmSample=(%.3f,%.3f)", u, v, (u + 8) / 256.0, (v + 8) / 256.0));
                final int cr = vertexData.get(12) & 0xff, cg = vertexData.get(13) & 0xff,
                          cb = vertexData.get(14) & 0xff, ca = vertexData.get(15) & 0xff;
                sb.append(String.format(" vColor=(%d,%d,%d,%d)", cr, cg, cb, ca));
            } else {
                sb.append(String.format(" lm=(%.1f,%.1f)", com.yuhan123.vulkanmod.vulkan.VRenderSystem.getLightmapU(),
                        com.yuhan123.vulkanmod.vulkan.VRenderSystem.getLightmapV()));
            }
            sb.append(String.format(" fog=(%.1f..%.1f)", com.yuhan123.vulkanmod.vulkan.VRenderSystem.getShaderFogStart(),
                    com.yuhan123.vulkanmod.vulkan.VRenderSystem.getShaderFogEnd()));
            final float[] col = com.yuhan123.vulkanmod.vulkan.VRenderSystem.getColor();
            sb.append(String.format(" color=(%.2f,%.2f,%.2f,%.2f)", col[0], col[1], col[2], col[3]));
            com.yuhan123.vulkanmod.VKProf.info(sb.toString());
            // first two vertices, raw 32-bit words (decode helper: pos=w0..2,
            // vColor=w3, uv0=w4..5, [BLOCK] uv2=w6, normal=w7).
            for (int vv = 0; vv < 2 && vv < vertexCount; vv++) {
                final StringBuilder vb = new StringBuilder("[VKPROF] FBDBG v").append(vv).append(':');
                for (int i = 0; i < stride / 4; i++) {
                    vb.append(String.format(" %08x", vertexData.getInt(vv * stride + i * 4)));
                }
                com.yuhan123.vulkanmod.VKProf.info(vb.toString());
            }
        }

        // CLOUD DIAGNOSTIC (CLOUDDIAG=1): clouds render through this
        // immediate path as either POSITION_TEX_COLOR (fast: one 1024-vertex
        // 16x16 grid) or POSITION_TEX_COLOR_NORMAL (fancy: many small tiles with
        // a modelview scale(12,1,12)). Both bake Vanilla's camera XZ offset into
        // the per-vertex UV. Dump the MVP translation and the first vertex's
        // position+UV so we can see (a) whether the modelview carries a
        // camera-position translate (mvpT.x/y large instead of ~0) and (b)
        // whether the captured UV still has Vanilla's offset. Capped at 16 dumps.
        if (VulkanModConfig.getBool("CLOUDDIAG", false) && cloudDiagDumps < 16
                && (vertexFormat == net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_TEX_COLOR
                    || vertexFormat == net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL)) {
            cloudDiagDumps++;
            final boolean fancy = vertexFormat == net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL;
            final java.nio.FloatBuffer mvp = com.yuhan123.vulkanmod.vulkan.VRenderSystem.mvpFloatBuffer();
            com.yuhan123.vulkanmod.VKProf.info(String.format("[VKPROF] CLOUDDIAG #%d fmt=%s count=%d mvpT=(%.2f,%.2f,%.2f) proj?m=%.4f",
                    cloudDiagDumps, fancy ? "PTCN" : "PTC", vertexCount, mvp.get(12), mvp.get(13), mvp.get(14), mvp.get(0)));
            final int stride = vertexFormat.getSize();
            for (int vv = 0; vv < 2 && vv < vertexCount; vv++) {
                final int base = vv * stride;
                // POSITION_TEX_COLOR / _NORMAL: Position(12) then Tex(8) at offset 12.
                final float px = vertexData.getFloat(base);
                final float py = vertexData.getFloat(base + 4);
                final float pz = vertexData.getFloat(base + 8);
                final float u = vertexData.getFloat(base + 12);
                final float v = vertexData.getFloat(base + 16);
                vertexData.position(0);
                com.yuhan123.vulkanmod.VKProf.info(String.format("[VKPROF] CLOUDDIAG v%d pos=(%.1f,%.1f,%.1f) uv=(%.6f,%.6f) stride=%d",
                        vv, px, py, pz, u, v, stride));
            }
        }

        Renderer.getDrawer().draw(vertexData, buffer.getDrawMode(), vertexFormat, vertexCount);
    }

    private static int fbdbgDumps = 0;

    /**
     * CLOUD DIAGNOSTIC (CLOUDDIAG=1): confirm whether the cloud draw's
     * modelview carries a camera-position translate and whether the captured UV
     * still carries Vanilla's camera offset. Catches both fast clouds
     * (POSITION_TEX_COLOR, 1024 verts) and fancy clouds (POSITION_TEX_COLOR_NORMAL).
     */
    private static int cloudDiagDumps = 0;

    /** CPU-side lightmap pixels (EntityRenderer.lightmapTexture's int[]), fetched once. */
    private static int[] lightmapTexels;

    private static int[] lightmapTexels() {
        if (lightmapTexels != null) {
            return lightmapTexels;
        }
        try {
            java.lang.reflect.Field erF = net.minecraft.client.Minecraft.getMinecraft().getClass()
                    .getDeclaredField("entityRenderer");
            erF.setAccessible(true);
            Object er = erF.get(net.minecraft.client.Minecraft.getMinecraft());
            java.lang.reflect.Field lmF = er.getClass().getDeclaredField("lightmapTexture");
            lmF.setAccessible(true);
            Object tex = lmF.get(er);
            java.lang.reflect.Field dataF = tex.getClass().getDeclaredField("dynamicTextureData");
            dataF.setAccessible(true);
            lightmapTexels = (int[]) dataF.get(tex);
        } catch (Throwable t) {
            com.yuhan123.vulkanmod.VKProf.info("[VKPROF] FBDBG lightmap fetch failed: {}", t.toString());
            lightmapTexels = new int[0];
        }
        return lightmapTexels;
    }
}
