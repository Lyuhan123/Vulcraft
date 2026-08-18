package com.yuhan123.vulkanmod.mixin.gl;

import org.lwjgl.opengl.ContextCapabilities;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ContextCapabilities.class)
public class GlContextMixin {
    boolean OpenGL20 = false;

}
