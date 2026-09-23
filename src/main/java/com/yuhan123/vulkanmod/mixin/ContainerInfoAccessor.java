package com.yuhan123.vulkanmod.mixin;

import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Read access to one entry of the visible-chunk BFS queue.
 *
 * <p>{@code RenderGlobal.ContainerLocalRenderInformation} is a
 * <b>package-private inner class</b> of {@code RenderGlobal}, so neither the type
 * nor its fields can be named from this mod's package. An accessor interface is
 * the supported way in, and it is deliberately limited to the two members the
 * term-A hoist needs:
 *
 * <ul>
 *   <li>{@code setFacing} — the six-bit mask of directions this entry has already
 *       been entered from. {@code hasDirection(f)} is
 *       {@code (setFacing & 1 << f.ordinal()) > 0}, so reading the byte once per
 *       poll replaces six calls to {@code hasDirection} and lets the whole
 *       term-A decision be computed with bit arithmetic.</li>
 *   <li>{@code hasDirection} — here as an {@code @Invoker} so
 *       {@code VULKANMOD_BFS_MASK_VERIFY=1} can check the bit arithmetic against
 *       the <b>real</b> method rather than against a transcription of it. That is
 *       the only form of reference this project accepts, and it is what caught the
 *       window-local chunk-position bug in pass 13.</li>
 * </ul>
 *
 * <p>Nothing here changes behaviour: one field read and one passthrough.
 */
@Mixin(targets = "net.minecraft.client.renderer.RenderGlobal$ContainerLocalRenderInformation")
public interface ContainerInfoAccessor {

    /**
     * The entry's accumulated direction mask. Bits are {@code EnumFacing}
     * ordinals; bit {@code i} set means the BFS has already entered this entry
     * from direction {@code i}.
     */
    @Accessor("setFacing")
    byte vulkanmod$setFacing();

    /** The real predicate, for verify mode. */
    @Invoker("hasDirection")
    boolean vulkanmod$hasDirection(EnumFacing facing);

    /**
     * The chunk this entry expands, i.e. the {@code rc3} of the loop body.
     *
     * <p>Pass 22 reads it once per poll so the term-2 predicate
     * ({@code rc3.getCompiledChunk().isVisible(facing.getOpposite(), f)}) can be
     * folded into the same keep mask pass 15 builds from term 1 — which is what
     * lets the loop stop paying the neighbour probe for the facings term 2
     * rejects. Term 2 is a property of the <b>polled</b> entry, not of the facing
     * alone, so it has to be evaluated where the entry is available.
     */
    @Accessor("renderChunk")
    RenderChunk vulkanmod$renderChunk();

    /**
     * The direction this entry was entered from — {@code null} for the root
     * entry, and {@code null} makes term 2 vacuously true, which is why the
     * term-2 fold must test for it before doing anything else.
     */
    @Accessor("facing")
    EnumFacing vulkanmod$facing();
}
