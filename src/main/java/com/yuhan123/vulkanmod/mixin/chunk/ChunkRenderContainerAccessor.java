package com.yuhan123.vulkanmod.mixin.chunk;

import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.client.renderer.chunk.RenderChunk;

/**
 * Exposes inherited fields of net.minecraft.client.renderer.ChunkRenderContainer
 * (VboRenderList's superclass) without reflection. Resolved through the Mixin
 * refmap (MCP name -> SRG), so it works in both dev and the remapped export -
 * unlike a raw getDeclaredField, which threw NoSuchFieldException in the SRG
 * export and disabled the whole terrain batch.
 */
@Mixin(targets = "net.minecraft.client.renderer.ChunkRenderContainer")
public interface ChunkRenderContainerAccessor {
    @Accessor("viewEntityX")
    double getViewEntityX();

    @Accessor("viewEntityY")
    double getViewEntityY();

    @Accessor("viewEntityZ")
    double getViewEntityZ();

    @Accessor("renderChunks")
    List<RenderChunk> getRenderChunks();
}
