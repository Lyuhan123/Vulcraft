package com.yuhan123.vulkanmod.mixin;

import com.yuhan123.vulkanmod.render.util.VisibilityRow;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.SetVisibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Pass 25: exposes a whole face-visibility <b>row</b> from a
 * {@code CompiledChunk}, so the visible-chunk BFS can answer its term-2
 * predicate with one call instead of up to six.
 *
 * <p>{@code CompiledChunk.isVisible(from, to)} is a one-line delegate to
 * {@code setVisibility.isVisible(from, to)}, and that field is private. The
 * actual bit work lives in {@link SetVisibilityMixin}; this class exists only to
 * get from the {@code CompiledChunk} the BFS already holds to the
 * {@code SetVisibility} that owns the bits, which is why it is a two-line
 * passthrough rather than an accessor interface plus a cast at the call site.
 *
 * <p>The row is exactly {@code CompiledChunk.isVisible}'s answer for all six
 * target faces, so {@code term1Mask & row} is the same set of facings vanilla's
 * conjunction would keep, in the same order. Nothing here changes behaviour.
 */
@Mixin(CompiledChunk.class)
public abstract class CompiledChunkMixin implements VisibilityRow {

    @Shadow private SetVisibility setVisibility;

    @Override
    public int vulkanmod$visibilityRow(int fromOrdinal) {
        return ((VisibilityRow) this.setVisibility).vulkanmod$visibilityRow(fromOrdinal);
    }

    @Override
    public int vulkanmod$visibilityRowSlow(int fromOrdinal) {
        return ((VisibilityRow) this.setVisibility).vulkanmod$visibilityRowSlow(fromOrdinal);
    }
}
