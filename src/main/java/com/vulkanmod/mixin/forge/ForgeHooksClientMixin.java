package com.yuhan123.vulkanmod.mixin.forge;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.model.pipeline.LightUtil;
import net.minecraftforge.common.ForgeModContainer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

@Mixin(ForgeHooksClient.class)
public class ForgeHooksClientMixin {
    /**
     * Item rendering entry (emissive items path). Renders the item model quads
     * with the ITEM vertex format through the Tessellator, which the mod's
     * TessellatorMixin redirects to the Vulkan drawer.
     */
    @Overwrite(remap = false)
    public static void renderLitItem(RenderItem ri, net.minecraft.client.renderer.block.model.IBakedModel model, int color, ItemStack stack) {
        if (ForgeModContainer.allowEmissiveItems) {
            List<BakedQuad> allquads = new ArrayList<>();
            for (EnumFacing enumfacing : EnumFacing.values()) {
                allquads.addAll(model.getQuads(null, enumfacing, 0L));
            }
            allquads.addAll(model.getQuads(null, null, 0L));
            if (allquads.isEmpty()) return;
            drawSegment(ri, color, stack, allquads, 0, 0, true, false, false);
        }
    }

    @Overwrite(remap = false)
    private static void drawSegment(RenderItem ri, int baseColor, ItemStack stack, List<BakedQuad> segment,
                                    int bl, int sl, boolean shade, boolean updateLighting, boolean updateShading) {
        BufferBuilder bufferbuilder = Tessellator.getInstance().getBuffer();
        bufferbuilder.begin(7, DefaultVertexFormats.ITEM);

        // The fixed-function lighting state (enableLighting / lightmap coords) is
        // handled by the mod's GlStateManager/OpenGlHelper mixins as no-ops, so
        // only the quad geometry needs to be recorded here.
        boolean flag = baseColor == -1 && !stack.isEmpty();
        for (BakedQuad quad : segment) {
            int k = baseColor;
            if (flag && quad.hasTintIndex()) {
                k = net.minecraft.client.Minecraft.getMinecraft().getItemColors().colorMultiplier(stack, quad.getTintIndex());
                k |= -16777216;
            }
            LightUtil.renderQuadColor(bufferbuilder, quad, k);
        }
        Tessellator.getInstance().draw();
        segment.clear();
    }

    @Overwrite(remap = false)
    public static void preDraw(VertexFormatElement.EnumUsage attrType, VertexFormat format, int element, int stride, ByteBuffer buffer) {}

    @Overwrite(remap = false)
    public static void postDraw(VertexFormatElement.EnumUsage attrType, VertexFormat format, int element, int stride, ByteBuffer buffer) {}
}
