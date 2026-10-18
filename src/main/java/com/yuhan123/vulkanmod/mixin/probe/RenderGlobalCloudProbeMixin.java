package com.yuhan123.vulkanmod.mixin.probe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Temporary probe: does the cloud layer even get entered, and which branch
 * does it take?
 *
 * <p>Why this exists: the "clouds are full-bright at night" report could not
 * be confirmed from the draw path, because NO cloud-format batch ever reaches
 * {@code TessellatorMixin.vkDraw} - not POSITION_TEX_COLOR_NORMAL (fancy) and
 * not a 1024-vertex POSITION_TEX_COLOR (fast), even with the probe placed
 * before the {@code Renderer.isRecording()} gate. So either the cloud layer
 * never runs, or it draws through something else entirely.
 *
 * <p>Vanilla decides in order:
 * <ol>
 *   <li>{@code EntityRenderer.renderCloudsCheck} - guarded by
 *       {@code gameSettings.shouldRenderClouds() != 0} and
 *       {@code entity.posY + eyeHeight < 128}.</li>
 *   <li>{@code RenderGlobal.renderClouds} - guarded by
 *       {@code provider.isSurfaceWorld()}, then branches on
 *       {@code shouldRenderClouds() == 2} (fancy) vs anything else (fast,
 *       inlined here, no separate method).</li>
 * </ol>
 * There is no {@code renderCloudsFast} method - the fast path is inlined - so
 * only the two methods below can be hooked.
 */
@Mixin(RenderGlobal.class)
public class RenderGlobalCloudProbeMixin {

    private static int cloudHeadLogs = 0;
    private static int fancyHeadLogs = 0;

    @Inject(method = "renderClouds(FIDDD)V", at = @At("HEAD"))
    private void vulkanmod$renderCloudsHead(float partialTicks, int pass, double x, double y, double z,
                                            CallbackInfo ci) {
        if (cloudHeadLogs >= 8) {
            return;
        }
        cloudHeadLogs++;
        final Minecraft mc = Minecraft.getMinecraft();
        final int mode = (mc.gameSettings != null) ? mc.gameSettings.shouldRenderClouds() : -1;
        final boolean surface = (mc.world != null && mc.world.provider != null)
                && mc.world.provider.isSurfaceWorld();
        com.yuhan123.vulkanmod.VulkanMod.LOGGER.info(
                "[VKPROF] CLOUDPROBE renderClouds #{} pass={} shouldRenderClouds={} isSurfaceWorld={} "
                        + "y={} worldTime={}",
                cloudHeadLogs, pass, mode, surface, y,
                (mc.world != null) ? mc.world.getWorldTime() : -1L);
    }

    @Inject(method = "renderCloudsFancy(FIDDD)V", at = @At("HEAD"))
    private void vulkanmod$renderCloudsFancyHead(float partialTicks, int pass, double x, double y, double z,
                                                 CallbackInfo ci) {
        if (fancyHeadLogs >= 8) {
            return;
        }
        fancyHeadLogs++;
        com.yuhan123.vulkanmod.VulkanMod.LOGGER.info(
                "[VKPROF] CLOUDPROBE renderCloudsFancy #{} pass={}", fancyHeadLogs, pass);
    }
}
