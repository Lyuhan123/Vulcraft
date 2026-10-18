package com.yuhan123.vulkanmod.mixin.vertex;

import com.yuhan123.vulkanmod.config.VulkanModConfig;
import com.yuhan123.vulkanmod.render.PipelineManager;
import com.yuhan123.vulkanmod.render.shader.ShaderInstance;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.shader.Pipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.texture.DynamicTexture;
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

        // Instrumentation placed BEFORE the isRecording gate: it has to
        // distinguish "the cloud batch never reaches vkDraw" from "it arrives
        // but isRecording() is false so it is silently dropped". Fancy clouds
        // are POSITION_TEX_COLOR_NORMAL batches, fast clouds one 1024-vertex
        // POSITION_TEX_COLOR batch; neither ever appeared in DRAWTRACE.
        // Full draw inventory (no format filter): the cloud batch has to show up
        // here under SOME format if the cloud layer renders through Tessellator
        // at all. Logging every draw with its element list, vertex count and
        // whether the Vulkan renderer is accepting draws.
        final VertexFormat cfFmt = buffer.getVertexFormat();
        if (cloudSeenLogs < 80 && Minecraft.getMinecraft().world != null && vertexCount >= 64) {
            cloudSeenLogs++;
            com.yuhan123.vulkanmod.VulkanMod.LOGGER.info(
                    "[VKPROF] CLOUD-DRAW-SEEN #{} count={} isRecording={} fmt={}",
                    cloudSeenLogs, vertexCount, Renderer.isRecording(), cfFmt);
        }

        if (!Renderer.isRecording())
            return;

        // Recording from the splash thread has to exclude the main thread's
        // frame transitions: apply() uploads into the per-frame uniform buffer
        // and the draw binds it, both of which Renderer.beginFrame() rewinds
        // (see Drawer.splashPresenting). Reentrant with the vanilla splash's
        // begin/end and with Drawer's own entry-point locks; a no-op during
        // normal play. The try opened here extends to the end of this method.
        final boolean splashLock = com.yuhan123.vulkanmod.vulkan.Drawer.splashPresenting();
        if (splashLock) {
            com.yuhan123.vulkanmod.render.texture.ImageUploadHelper.LOCK.lock();
        }
        try {
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
        // TEMP TRACER: which vertex formats actually reach the draw path, and at
        // what size? Large batches only (GUI/text/panorama are 4-vertex draws),
        // capped, so this cannot flood. Fast clouds are a 1024-vertex
        // POSITION_TEX_COLOR draw; if nothing near that size shows up, the cloud
        // pass is not reaching Tessellator.draw at all and the problem is upstream
        // of the vertex data.
        // Cloud colour diagnostic. Fancy clouds are POSITION_TEX_COLOR_NORMAL
        // drawn one 4-vertex tile at a time, and fast clouds are a single
        // POSITION_TEX_COLOR grid; the old "vertexCount >= 256" filter threw
        // away EVERY fancy-cloud tile, which is why no vColor ever showed up in
        // the log. Match on the cloud formats instead, with no size filter.
        // Only in-world draws. The main menu / loading screen draws plenty of
        // POSITION_TEX_COLOR quads too (panorama, GUI), and with a 24-dump cap
        // they exhausted the budget before the world ever loaded - every
        // earlier run logged menu draws and no clouds at all.
        // renderCloudsFast emits ONE 1024-vertex POSITION_TEX_COLOR draw, so a
        // small POSITION_TEX_COLOR batch is not the cloud layer (rain/particles
        // use it too) - requiring >= 512 keeps the 24-dump budget on real clouds.
        if (cloudDiagDumps < 40 && Minecraft.getMinecraft().world != null
                && (vertexFormat == net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL
                    || vertexFormat == net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_TEX_COLOR)) {
            cloudDiagDumps++;
            final java.nio.FloatBuffer mvp = com.yuhan123.vulkanmod.vulkan.VRenderSystem.mvpFloatBuffer();
            double camX = Double.NaN, camZ = Double.NaN;
            final net.minecraft.entity.Entity cam = Minecraft.getMinecraft().getRenderViewEntity();
            if (cam != null) {
                camX = cam.posX;
                camZ = cam.posZ;
            }
            final int stride = vertexFormat.getSize();
            final float u0 = vertexData.getFloat(12);
            final float v0 = vertexData.getFloat(16);
            vertexData.position(0);
            // Baked cloud colour sits at byte 20 for both cloud formats
            // (Position 12 + UV0 8 precedes it). Vanilla writes
            // world.getCloudColour there, scaled 0.7-0.9 per face: at night that
            // is ~(18..38), at day ~(230). ~255 at night means the cloud colour
            // never made it into the vertex - the real "clouds full-bright"
            // cause - as opposed to a lightmap problem.
            final int cr = vertexData.get(20) & 0xff, cg = vertexData.get(21) & 0xff,
                      cb = vertexData.get(22) & 0xff, ca = vertexData.get(23) & 0xff;
            // Global colour = ColorModulator (set by GlStateManager.color).
            // renderCloudsFancy never calls it, so this must read (1,1,1,1);
            // a bright value here means a stale global tint is washing the
            // clouds out. lightmapEnabled must be false at the cloud window.
            final float[] gcol = com.yuhan123.vulkanmod.vulkan.VRenderSystem.getColor();
            final boolean lmOn = com.yuhan123.vulkanmod.vulkan.VRenderSystem.lightmapEnabled;
            // Ground truth: what the world thinks the time is, and what the CPU
            // computes the cloud colour to be right now. If worldTime is a night
            // tick and cloudColour is dark but vColor above is 255, the cloud
            // colour is being lost between CPU and vertex buffer - that is the
            // "clouds full-bright" bug. If worldTime is a day tick, the harness
            // simply never switched to night and there is nothing to fix.
            final net.minecraft.world.World w = Minecraft.getMinecraft().world;
            final long wtime = (w != null) ? w.getWorldTime() : -1L;
            String ccol = "n/a";
            if (w != null) {
                final net.minecraft.util.math.Vec3d cc = w.getCloudColour(1.0F);
                ccol = String.format("(%.3f,%.3f,%.3f)", cc.x, cc.y, cc.z);
            }
            // Identify WHO is drawing. The 16-vertex POSITION_TEX_COLOR draws
            // captured here are not the cloud layer (renderCloudsFast emits one
            // 1024-vertex draw), so without a caller the trace is ambiguous.
            if (cloudDiagDumps <= 14) {
                final StringBuilder st = new StringBuilder("[VKPROF] DRAWTRACE #").append(cloudDiagDumps).append(" caller:");
                int frames = 0;
                for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                    final String cn = e.getClassName();
                    if (cn.startsWith("com.yuhan123.vulkanmod.mixin.vertex.TessellatorMixin")) continue;
                    if (cn.startsWith("java.lang.Thread")) continue;
                    st.append(' ').append(cn).append('.').append(e.getMethodName())
                      .append(':').append(e.getLineNumber());
                    if (++frames >= 5) break;
                }
                com.yuhan123.vulkanmod.VulkanMod.LOGGER.info(st.toString());
            }
            com.yuhan123.vulkanmod.VulkanMod.LOGGER.info(String.format(
                    "[VKPROF] DRAWTRACE #%d fmt=%s count=%d stride=%d v0pos=(%.1f,%.1f,%.1f) v0uv=(%.6f,%.6f) vColor=(%d,%d,%d,%d) GLOBAL=(%.2f,%.2f,%.2f,%.2f) lmEnabled=%b worldTime=%d cloudColour=%s mvpT=(%.2f,%.2f,%.2f) cam=(%.1f,%.1f)",
                    cloudDiagDumps, vertexFormat, vertexCount, stride,
                    vertexData.getFloat(0), vertexData.getFloat(4), vertexData.getFloat(8), u0, v0,
                    cr, cg, cb, ca,
                    gcol[0], gcol[1], gcol[2], gcol[3], lmOn, wtime, ccol,
                    mvp.get(12), mvp.get(13), mvp.get(14), camX, camZ));
        }

        Renderer.getDrawer().draw(vertexData, buffer.getDrawMode(), vertexFormat, vertexCount);
        } finally {
            if (splashLock) {
                com.yuhan123.vulkanmod.render.texture.ImageUploadHelper.LOCK.unlock();
            }
        }
    }

    private static int fbdbgDumps = 0;

    /** CLOUD-DRAW-SEEN instrumentation budget. */
    private static int cloudSeenLogs = 0;

    /**
     * CLOUD DIAGNOSTIC (CLOUDDIAG=1): confirm whether the cloud draw's
     * modelview carries a camera-position translate and whether the captured UV
     * still carries Vanilla's camera offset. Catches both fast clouds
     * (POSITION_TEX_COLOR, 1024 verts) and fancy clouds (POSITION_TEX_COLOR_NORMAL).
     */
    private static int cloudDiagDumps = 0;

    /** CPU-side lightmap pixels (EntityRenderer.lightmapTexture's int[]), fetched once. */
    private static int[] lightmapTexels;

    /**
     * The three fields this walks are private/final in vanilla; they are read
     * through the accessor mixins {@code MinecraftAccessor},
     * {@code EntityRendererAccessor} and {@code DynamicTextureAccessor}, whose
     * targets are remapped via {@code vulcraft.mixin-refmap.json}.
     *
     * <p>Access Transformer would be the natural tool here, but Unimined's
     * cleanroom AT route resolves {@code top.outlands:accesstransformers:8.3.0},
     * which is not published in any repository, so it cannot be built.
     *
     * <p>This used to walk the field chain with {@code getDeclaredField}. That
     * cost three reflective lookups plus {@code setAccessible} and, worse, keyed
     * off MCP names: reobfuscation renames those members, so in the exported jar
     * every lookup threw NoSuchFieldException and this silently handed back an
     * empty array instead of the lightmap.
     */
    private static int[] lightmapTexels() {
        if (lightmapTexels != null) {
            return lightmapTexels;
        }
        try {
            // Field access through accessor mixins (remapped by the refmap), not
            // reflection: no getDeclaredField lookup, no setAccessible, and no
            // MCP-name dependency to break once the jar is reobfuscated.
            EntityRenderer er = ((com.yuhan123.vulkanmod.mixin.MinecraftAccessor)
                    Minecraft.getMinecraft()).getEntityRenderer();
            DynamicTexture tex = ((com.yuhan123.vulkanmod.mixin.EntityRendererAccessor) er).getLightmapTexture();
            lightmapTexels = ((com.yuhan123.vulkanmod.mixin.texture.DynamicTextureAccessor) tex)
                    .getDynamicTextureData();
        } catch (Throwable t) {
            com.yuhan123.vulkanmod.VKProf.info("[VKPROF] FBDBG lightmap fetch failed: {}", t.toString());
            lightmapTexels = new int[0];
        }
        return lightmapTexels;
    }
}
