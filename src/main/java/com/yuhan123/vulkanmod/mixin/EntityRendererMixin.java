package com.yuhan123.vulkanmod.mixin;

import com.yuhan123.vulkanmod.gl.MatrixState;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cloud-pass support.
 *
 * <p>Two independent things live here.
 *
 * <p><b>The cloud projection.</b> {@code renderCloudsCheck} builds its own
 * frustum with {@code Project.gluPerspective(fov, aspect, 0.05, farPlane * 4)}
 * and then restores the world frustum with a second call. Both are redirected to
 * {@link MatrixState#perspective} so the pass gets a real projection matrix on
 * the stack regardless of how {@code gluPerspective} itself is wired up. (This
 * is correct and necessary, but it is NOT what makes the clouds world-fixed -
 * see the texture-matrix note in {@link MatrixState#resetTextureMatrix()}.)
 *
 * <p><b>The lightmap guard.</b> Now that the GL_TEXTURE matrix is published to
 * the shaders for the cloud layer, the transform {@code enableLightmap} leaves
 * on the shared texture stack has to be cleared once the lightmap is done, or it
 * corrupts the next unit-0 draw's UVs.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {

    /**
     * The cloud pass's own projection. {@code farPlaneDistance * 4} keeps the
     * cloud plane (drawn at a fixed height relative to the camera) inside the
     * frustum regardless of the render distance.
     */
    @Redirect(method = "renderCloudsCheck",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V", ordinal = 0),
            remap = false)
    private static void vulkanmod$cloudPerspective(float fovy, float aspect, float zNear, float zFar) {
        MatrixState.perspective(fovy, aspect, zNear, zFar);
    }

    /**
     * The projection vanilla restores right after the cloud pass
     * ({@code farPlaneDistance * SQRT_2}). Redirecting only the first call would
     * leave the world drawing under the cloud frustum.
     */
    @Redirect(method = "renderCloudsCheck",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V", ordinal = 1),
            remap = false)
    private static void vulkanmod$restoreWorldPerspective(float fovy, float aspect, float zNear, float zFar) {
        MatrixState.perspective(fovy, aspect, zNear, zFar);
    }

    /**
     * Clears the shared TEXTURE matrix once the lightmap is done with it.
     *
     * <p>{@code enableLightmap} pushes {@code scale(1/256) + translate(8)} into
     * the texture matrix (in GL that is unit 1's private copy; here there is a
     * single shared stack). Now that the matrix is published to the shaders for
     * the cloud layer, leaving the lightmap transform in place would corrupt the
     * next unit-0 draw's UVs. Our shaders compute lightmap coordinates
     * arithmetically, never through this matrix, so an identity reset is safe.
     */
    @Inject(method = "disableLightmap", at = @At("HEAD"))
    private void vulkanmod$resetTextureAfterLightmap(CallbackInfo ci) {
        MatrixState.resetTextureMatrix();
    }

    /**
     * Clears the shared TEXTURE matrix the instant the lightmap transform is
     * installed, so it never leaks into the unit-0 draws that happen WHILE the
     * lightmap is still enabled.
     *
     * <p>This is what the ender-crystal healing beam needs. {@code
     * RenderDragon.renderCrystalBeams} runs from {@code doRender} -> {@code
     * super.doRender} while the lightmap is enabled, and it draws with {@code
     * position_tex_color}, which multiplies its UVs by this matrix. With the
     * lightmap transform present, {@code UV' = (u/256 + 8, V/256 + 8) ~ (8, 8)}
     * collapses the entire beam onto the texture's opaque corner pixel, so the
     * beam renders as a solid white line that {@code enableBlend} cannot fix
     * (the sampled alpha is 255). Resetting to identity here - before any entity
     * draw - restores the beam's true UVs.
     *
     * <p>Our shaders derive lightmap coordinates arithmetically and never
     * consume this matrix, so resetting it right after {@code enableLightmap}
     * applies the GL-equivalent transform is safe. Forge's cloud renderer
     * installs its own texture matrix during the cloud pass (which runs after
     * {@code disableLightmap}), so this does not affect cloud rendering.
     */
    @Inject(method = "enableLightmap", at = @At("RETURN"))
    private void vulkanmod$resetTextureBeforeLightmap(CallbackInfo ci) {
        MatrixState.resetTextureMatrix();
    }
}
