package com.yuhan123.vulkanmod.render.util;

/**
 * TEMPORARY (d15): scratch state shared between the layer-census probe mixins.
 * Mixins may not hold non-private static fields, so the "current render layer"
 * published by RenderGlobalMixin for the draw-time probe in GlStateManagerMixin
 * lives here instead. Removed once the see-through-blocks investigation closes.
 */
public class XrayState {
    public static volatile String currentLayer = null;
}
