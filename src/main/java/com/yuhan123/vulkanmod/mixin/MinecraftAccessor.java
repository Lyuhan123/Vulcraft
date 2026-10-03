package com.yuhan123.vulkanmod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads {@code Minecraft.entityRenderer}.
 *
 * <p>An accessor mixin rather than Access Transformer / reflection: Unimined's
 * cleanroom AT route resolves {@code top.outlands:accesstransformers:8.3.0},
 * which is not published in any repository, and reflection here keyed off MCP
 * names and so threw NoSuchFieldException in the exported jar.
 *
 * <p>The accessor interface is remapped through
 * {@code vulcraft.mixin-refmap.json}, so it works in both dev and export, and
 * compiles down to a plain field read - no reflective lookup, no setAccessible.
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("entityRenderer")
    EntityRenderer getEntityRenderer();
}
