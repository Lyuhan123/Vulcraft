package com.yuhan123.vulkanmod.mixin;

import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Pass 28, second level: split {@code RenderLivingBase.doRender} at the model draw.
 *
 * <p>The first level of the pass-28 instrument says {@code Render.doRender} is ~89%
 * of a {@code renderEntityStatic} and that only a third of <em>that</em> is
 * display-list replay, leaving ~0.36 ms/frame (5% of the frame) inside
 * {@code doRender} and outside {@code replayList}. This redirect names the boundary
 * between the two candidates for that residual:
 *
 * <ul>
 *   <li>{@code renderModel} &mdash; the model draw itself: {@code bindEntityTexture}
 *       plus {@code ModelBase.render}'s loop of {@code ModelRenderer.render} calls
 *       (which is where the display lists are replayed).</li>
 *   <li>the {@code doRender} wrapper around it &mdash; {@code pushMatrix} /
 *       {@code disableCull}, {@code setLivingAnimations} / {@code setRotationAngles},
 *       {@code setDoRenderBrightness}, {@code renderLayers}, {@code depthMask},
 *       {@code popMatrix}.</li>
 * </ul>
 *
 * <p>{@code renderModel} is called from <b>two</b> call sites in {@code doRender} -
 * the {@code renderOutlines} branch and the normal branch, both with descriptor
 * {@code (Lnet/minecraft/entity/EntityLivingBase;FFFFFF)V} - and exactly one of them
 * runs per call, so a single {@code @Redirect} covers both without double counting.
 * Verified with {@code javap -c}: offsets 471 and 551.
 *
 * <p>Gated on {@link FrameProfiler#entLoopOpen()} like the other pass-28 hooks, so
 * the layer renderers' own {@code renderModel} calls (sheep wool, armour, held
 * items - different owners, so not matched here anyway) stay out. {@code mdlN} must
 * track the {@code entattr} row's {@code n}; a hook that failed to attach reads 0,
 * which looks exactly like "the model is free".
 */
@Mixin(RenderLivingBase.class)
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class RenderLivingBaseMixin {

    /**
     * The target method, shadowed so the redirect handler can re-issue the call it
     * intercepted. {@code renderModel} is {@code protected} on the target, so a
     * plain call from this class does not compile; a {@code @Shadow} is resolved by
     * name + descriptor and is the supported way to reach it.
     *
     * <p>Declared with the <b>erased</b> parameter type ({@code EntityLivingBase},
     * not the class type variable {@code T}) because that is the descriptor in the
     * bytecode - verified with {@code javap}: {@code (Lnet/minecraft/entity/
     * EntityLivingBase;FFFFFF)V}.
     */
    @Shadow
    protected abstract void renderModel(EntityLivingBase entity, float limbSwing, float limbSwingAmount,
                                        float ageInTicks, float netHeadYaw, float headPitch, float scaleFactor);

    @Redirect(
            method = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/entity/RenderLivingBase;"
                             + "renderModel(Lnet/minecraft/entity/EntityLivingBase;FFFFFF)V"))
    private void vulkanmod$timeEntityModelRender(RenderLivingBase self, EntityLivingBase entity,
                                                 float limbSwing, float limbSwingAmount, float ageInTicks,
                                                 float netHeadYaw, float headPitch, float scaleFactor) {
        if (!FrameProfiler.entLoopOpen()) {
            this.renderModel(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, scaleFactor);
            return;
        }
        final long t0 = System.nanoTime();
        this.renderModel(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, scaleFactor);
        FrameProfiler.onEntityModelRender(System.nanoTime() - t0);
    }
}
