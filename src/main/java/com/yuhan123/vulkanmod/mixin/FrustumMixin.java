package com.yuhan123.vulkanmod.mixin;

import com.yuhan123.vulkanmod.render.util.FrustumFast;
import net.minecraft.client.renderer.culling.ClippingHelper;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.math.AxisAlignedBB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Attaches {@link FrustumFast#vulkanmod$fastBoxInFrustum} to vanilla's
 * {@code Frustum}.
 *
 * <p>Implemented as a method on {@code Frustum} rather than as a helper taking
 * the frustum as an argument, because the two things the test needs -
 * the wrapped {@code ClippingHelper} and the camera offset - are <b>private
 * fields</b>. Reaching them through generated accessors would cost four calls
 * per test, which is most of what the rewrite is removing; as a method on the
 * target the field reads are direct.
 *
 * <p>Nothing is overwritten: {@code Frustum.isBoundingBoxInFrustum} and
 * {@code isBoxInFrustum} are untouched, so the reference arm of any A/B is the
 * real vanilla chain and not a transcription of it.
 */
@Mixin(Frustum.class)
public abstract class FrustumMixin implements FrustumFast {

    @Shadow
    private ClippingHelper clippingHelper;

    @Shadow
    private double x;

    @Shadow
    private double y;

    @Shadow
    private double z;

    @Override
    public boolean vulkanmod$fastBoxInFrustum(AxisAlignedBB box) {
        // The box is translated into camera-relative space exactly as
        // Frustum.isBoxInFrustum does before it calls the clipping helper.
        final double minX = box.minX - this.x;
        final double minY = box.minY - this.y;
        final double minZ = box.minZ - this.z;
        final double maxX = box.maxX - this.x;
        final double maxY = box.maxY - this.y;
        final double maxZ = box.maxZ - this.z;

        return vulkanmod$positiveVertex(this.clippingHelper.frustum,
                minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * One dot product per plane, taken at the corner that maximises it. The
     * term order is the one {@code ClippingHelper.dot} uses, so the value is
     * bit-identical to vanilla's.
     */
    @Unique
    private static boolean vulkanmod$positiveVertex(float[][] planes,
                                                    double minX, double minY, double minZ,
                                                    double maxX, double maxY, double maxZ) {        for (int i = 0; i < 6; i++) {
            final float[] p = planes[i];

            final double x = p[0] > 0.0f ? maxX : minX;
            final double y = p[1] > 0.0f ? maxY : minY;
            final double z = p[2] > 0.0f ? maxZ : minZ;

            if (p[0] * x + p[1] * y + p[2] * z + p[3] <= 0.0) {
                return false;
            }
        }

        return true;
    }
}
