package com.yuhan123.vulkanmod.mixin.gl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * No-ops the KHR_debug marker entry points.
 *
 * <p>They are GL context calls, and under Vulkan only the main thread ever has
 * a context (the hidden one), so an un-intercepted call from any other thread
 * aborts the JVM with "No context is current". CustomLoadingScreen brackets
 * every splash frame with these markers when it sees
 * {@code GLContext.getCapabilities().GL_KHR_debug} - and with
 * {@link GlContextMixin} the capabilities it sees are the hidden context's
 * real ones, where that flag is true. The markers themselves have no effect
 * on the rendered image; vulcraft's own frame markers go through
 * {@code Renderer.pushDebugSection}.
 */
@Mixin(org.lwjgl.opengl.KHRDebug.class)
public class KHRDebugMixin {

    @Overwrite(remap = false)
    public static void glPushDebugGroup(int source, int id, CharSequence message) {
    }

    @Overwrite(remap = false)
    public static void glPushDebugGroup(int source, int id, java.nio.ByteBuffer message) {
    }

    @Overwrite(remap = false)
    public static void glPopDebugGroup() {
    }

    @Overwrite(remap = false)
    public static void glObjectLabel(int identifier, int name, CharSequence label) {
    }
}
