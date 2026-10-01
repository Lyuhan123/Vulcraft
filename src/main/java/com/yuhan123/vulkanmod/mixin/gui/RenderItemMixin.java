package com.yuhan123.vulkanmod.mixin.gui;

import com.yuhan123.vulkanmod.VKProf;
import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps GUI items (hotbar, inventory, most screens) at full brightness.
 *
 * <p>The item shader lights in-world items by sampling the lightmap at the global
 * {@code LightmapCoord} (published by OpenGlHelper.setLightmapTextureCoords from
 * world entity rendering). That global coordinate is left at whatever the last
 * world entity set it to, so when a GUI item is drawn afterwards it inherits that
 * stale world light and renders dark/tinted - which is wrong: inventory items are
 * shown under a fixed full-bright GUI light in vanilla, not the world lightmap.
 *
 * <p>Vanilla's GUI path renders items through {@code renderItemAndEffectIntoGUI}
 * -> {@code renderItemModelIntoGUI} -> {@code renderItem(ItemStack, IBakedModel)}
 * (the actual draw). {@code renderItemModelIntoGUI} is GUI-specific: world-dropped
 * items go through the separate {@code renderItemModel} (no "IntoGUI") method, so
 * hooking this entry scopes the fix to GUI items only and leaves world lighting
 * untouched. (Note: the 3-arg {@code renderItemIntoGUI} public method is NOT on
 * this path in 1.12.2-Cleanroom - hooking it does nothing.)
 */
@Mixin(RenderItem.class)
public class RenderItemMixin {

    private static int guiLmLogs;

    @Inject(method = "renderItemModelIntoGUI", at = @At("HEAD"))
    private void vulkanmod$guiItemFullBright(ItemStack stack, int xPosition, int yPosition,
                                            IBakedModel model, CallbackInfo ci) {
        final float u0 = VRenderSystem.getLightmapU();
        final float v0 = VRenderSystem.getLightmapV();
        VRenderSystem.setLightmapCoord(240.0f, 240.0f);
        com.yuhan123.vulkanmod.vulkan.VRenderSystem.guiLmWatch = true;
        if (guiLmLogs < 24) {
            ++guiLmLogs;
            com.yuhan123.vulkanmod.VKProf.info(String.format("[VKPROF] GUILM HEAD in=(%.1f,%.1f) -> set (240,240)", u0, v0));
        }
    }

    @Inject(method = "renderItemModelIntoGUI", at = @At("RETURN"))
    private void vulkanmod$guiItemFullBrightRet(ItemStack stack, int xPosition, int yPosition,
                                               IBakedModel model, CallbackInfo ci) {
        if (guiLmLogs >= 24 && guiLmLogs < 48) {
            ++guiLmLogs;
            final float u1 = VRenderSystem.getLightmapU();
            final float v1 = VRenderSystem.getLightmapV();
            com.yuhan123.vulkanmod.VKProf.info(String.format("[VKPROF] GUILM RET out=(%.1f,%.1f) %s", u1, v1,
                    (u1 == 240.0f && v1 == 240.0f) ? "OK-fullbright" : "OVERWRITTEN"));
        }
    }
}
