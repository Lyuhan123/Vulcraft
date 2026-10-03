package com.yuhan123.vulkanmod.mixin.texture;

import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads {@code DynamicTexture.dynamicTextureData}, the int[] holding the
 * texture's CPU-side pixels (used here for the entity lightmap).
 *
 * <p>Accessor mixin rather than reflection: the reflective read keyed off the
 * MCP name and therefore threw NoSuchFieldException once the jar was
 * reobfuscated, leaving the FBDBG dump silently empty instead of reporting the
 * lightmap. The accessor is remapped through {@code vulcraft.mixin-refmap.json}
 * and compiles to a direct field read.
 */
@Mixin(DynamicTexture.class)
public interface DynamicTextureAccessor {
    @Accessor("dynamicTextureData")
    int[] getDynamicTextureData();
}
