package com.yuhan123.vulkanmod.mixin;

import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads {@code EntityRenderer.lightmapTexture}.
 *
 * <p>See {@link MinecraftAccessor} for why this is an accessor mixin rather than
 * an Access Transformer entry or a reflective field lookup: the Access
 * Transformer route cannot be built (Unimined resolves an unpublished artifact),
 * and reflection broke in the exported jar where the member is renamed.
 */
@Mixin(EntityRenderer.class)
public interface EntityRendererAccessor {
    @Accessor("lightmapTexture")
    DynamicTexture getLightmapTexture();
}
