package com.yuhan123.vulkanmod.mixin.forge;

import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Restores the day/night cloud tint that Forge's {@code CloudRenderer} carries
 * outside the vertex data.
 *
 * <p>Why this is needed. {@code RenderGlobal.renderClouds} starts with
 * {@code FMLClientHandler.renderClouds(...)}; that answers true
 * (FMLClientHandler calls {@code WorldProvider.getCloudRenderer()} first, else
 * {@code CloudRenderer.render(int, float)}), so with Forge on the classpath the
 * vanilla {@code renderCloudsFancy} / inlined fast path never runs at all.
 * Forge draws the layer itself, and it does NOT bake the cloud colour into the
 * vertices:
 *
 * <ul>
 *   <li>{@code CloudRenderer.FORMAT} is POSITION_TEX_COLOR and
 *       {@code vertices()} writes a HARDCODED {@code color(0.7, 0.7, 0.7, 0.8)}
 *       - the same value day and night.</li>
 *   <li>The real per-frame tint goes into {@code COLOR_TEX}, a DynamicTexture
 *       filled from {@code WorldClient.getCloudColour(partialTicks)} and bound
 *       to {@code OpenGlHelper.lightmapTexUnit} (the "lightmap" texture unit),
 *       which vanilla/GL combines with the vertex colour.</li>
 *   <li>The geometry is built once into a display list / VBO and replayed with
 *       {@code GlStateManager.callList}, which is why no cloud batch ever
 *       reaches {@code Tessellator.draw()}.</li>
 * </ul>
 *
 * <p>vulcraft binds no second texture, and the cloud pipeline
 * (position_tex_color) has no Sampler2 at all - so COLOR_TEX was simply never
 * applied and the layer rendered at the constant 0.7 grey-white in every light:
 * the "clouds are full-bright at night" bug.
 *
 * <p>Fix: fold the same colour Forge puts in COLOR_TEX into the existing
 * {@code ColorModulator} uniform for the duration of the cloud render. The
 * shader already computes {@code texture * vertexColor * ColorModulator}, and
 * vertexColor is Forge's constant 0.7, so this reproduces
 * {@code texture * 0.7 * cloudColour} exactly - the same product GL's texture
 * combine produced - with no new descriptor binding and no change to any shared
 * pipeline.
 */
@Mixin(CloudRenderer.class)
public class CloudRendererMixin {

    /** ColorModulator as it was before the cloud layer hijacked it. */
    private static final float[] savedColor = new float[] {1f, 1f, 1f, 1f};
    private static boolean savedValid = false;

    @Inject(method = "render(IF)Z", at = @At("HEAD"))
    private void vulkanmod$cloudTintHead(int cloudTicks, float partialTicks,
                                         CallbackInfoReturnable<Boolean> cir) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.world == null) {
            return;
        }
        final float[] cur = VRenderSystem.getColor();
        savedColor[0] = cur[0];
        savedColor[1] = cur[1];
        savedColor[2] = cur[2];
        savedColor[3] = cur[3];
        savedValid = true;

        final Vec3d cc = mc.world.getCloudColour(partialTicks);
        // Alpha stays 1: the layer's 0.8 alpha already comes from Forge's vertex
        // colour, and forcing it here would double-attenuate the cloud.
        VRenderSystem.setShaderColor((float) cc.x, (float) cc.y, (float) cc.z, 1.0f);
    }

    @Inject(method = "render(IF)Z", at = @At("RETURN"))
    private void vulkanmod$cloudTintReturn(int cloudTicks, float partialTicks,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (!savedValid) {
            return;
        }
        savedValid = false;
        VRenderSystem.setShaderColor(savedColor[0], savedColor[1], savedColor[2], savedColor[3]);
    }
}
