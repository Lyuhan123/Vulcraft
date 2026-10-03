package com.yuhan123.vulkanmod.mixin.vertex;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.client.renderer.vertex.VertexBuffer;

/**
 * Exposes VertexBuffer's private {@code count} field (vertex count of the VBO)
 * without reflection. The @Accessor is resolved through the Mixin refmap, which
 * maps the MCP name "count" to its SRG name at runtime, so it works in both the
 * dev (deobfuscated) and the remapped export builds - unlike a raw
 * getDeclaredField("count") which fails in the SRG export environment.
 */
@Mixin(VertexBuffer.class)
public interface VertexBufferAccessor {
    @Accessor("count")
    int getCount();
}
