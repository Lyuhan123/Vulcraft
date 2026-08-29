package com.yuhan123.vulkanmod.mixin.gl;

import org.lwjgl.opengl.GL;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Intentionally left empty: we let the real {@link GL#createCapabilities()} run
 * on a hidden GL context (see {@link com.yuhan123.vulkanmod.mixin.MinecraftMixin}).
 * The mod's mixins redirect the actual GL rendering calls to Vulkan.
 */
@Mixin(GL.class)
public class GLMixin {
}
