package com.yuhan123.vulkanmod.mixin.chunk;

import java.lang.reflect.Field;

import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
import net.minecraft.client.renderer.ChunkRenderContainer;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Publishes the world-space model translation for the VANILLA per-section
 * terrain path, so that {@code block.vsh} can measure the fog distance from the
 * camera instead of from the world origin.
 *
 * <p>Vanilla draws every section chunk-locally and shifts it with
 * {@code translate(pos - viewEntity)}; {@code preRenderChunk} is exactly where
 * that happens. The shader adds {@code ChunkOffset} to the vertex position, so
 * publishing {@code pos - viewEntity} here makes the vertex stage evaluate
 * {@code length(worldPos - eye)} - the distance fog is actually defined on.
 * Measuring {@code length(Position)} measured from the world origin: with the
 * eye ~72 blocks up against a 144-block fog start almost every terrain vertex
 * fell below the fog start and the distance fog silently vanished.
 *
 * <p>The batched path ({@code VboRenderListMixin}) never calls
 * {@code preRenderChunk}; it bakes the section origin into the vertices and
 * publishes {@code -viewEntity} itself. Both paths therefore feed the shader
 * the same quantity: the translation that turns the stored position into
 * (world - eye).
 *
 * <p>The private {@code viewEntity*} fields are read by reflection rather than
 * {@code @Shadow} for the same reason {@code VboRenderListMixin} does it:
 * {@code @Shadow} on these members is not reliable across Mixin versions here.
 * Any failure disables the hook instead of breaking rendering.
 */
@Mixin(ChunkRenderContainer.class)
public class ChunkRenderContainerMixin {

    @Shadow
    private double viewEntityX;
    @Shadow
    private double viewEntityY;
    @Shadow
    private double viewEntityZ;

    @Inject(method = "preRenderChunk", at = @At("HEAD"))
    private void vulkanmod$publishChunkOffset(RenderChunk renderChunkIn, CallbackInfo ci) {


        //        if (fieldsFailed) {
//            return;
//        }
//        try {
//            if (fViewEntityX == null) {
//                final Class<?> c = Class.forName("net.minecraft.client.renderer.ChunkRenderContainer");
//                fViewEntityX = c.getDeclaredField("viewEntityX");
//                fViewEntityY = c.getDeclaredField("viewEntityY");
//                fViewEntityZ = c.getDeclaredField("viewEntityZ");
//                fViewEntityX.setAccessible(true);
//                fViewEntityY.setAccessible(true);
//                fViewEntityZ.setAccessible(true);
//            }
            final BlockPos pos = renderChunkIn.getPosition();
            final double vx = viewEntityX;
            final double vy = viewEntityY;
            final double vz = viewEntityZ;
            VRenderSystem.setChunkOffset(
                    (float) (pos.getX() - vx),
                    (float) (pos.getY() - vy),
                    (float) (pos.getZ() - vz));
//        } catch (Throwable t) {
//            fieldsFailed = true;
//        }
    }
}
