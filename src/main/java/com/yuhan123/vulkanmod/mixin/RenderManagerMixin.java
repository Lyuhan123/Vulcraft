package com.yuhan123.vulkanmod.mixin;

import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Pass 28: split {@code renderEntityStatic} on the vanilla call structure.
 *
 * <p><b>Why this is a separate mixin.</b> The two redirects below name
 * {@code renderEntity}, which is a {@link RenderManager} method. They were written
 * first inside {@code RenderGlobalMixin}, whose target is {@link
 * net.minecraft.client.renderer.RenderGlobal} &mdash; and an {@code @Redirect}'s
 * {@code method} string is resolved <b>against the mixin's own target class</b>, so
 * a name that belongs to a different class matches nothing and is dropped
 * <em>silently</em>: no error, no warning, and the run comes back with
 * {@code doN=0.00 shN=0.00} while every other row looks healthy. That is the pass-16
 * trap ("a plausible zero reads as free") in a new place, and it cost this pass one
 * 60 s launch. The rule: an injection that names a method must live in a mixin whose
 * target owns that method; the {@code at.target} is the only place a foreign class
 * may be named.
 *
 * <p><b>The instrument.</b> Pass 27 priced the whole {@code renderEntityStatic} call
 * at 129&ndash;175 us, 7&ndash;9 times a frame &mdash; the largest per-call cost in
 * the frame &mdash; and left the attribution at "display lists vs the model/Java
 * work around it", which was an elimination and not a measurement. These two
 * redirects time the two calls {@code renderEntity} makes into the {@code Render}
 * object: {@code doRender} (the model) and {@code doRenderShadowAndFire} (the shadow
 * quad plus fire). {@code pre} is then {@code render - do - sh} by subtraction,
 * which is sound because those two calls are the whole of {@code renderEntity} apart
 * from a render-object map lookup and two trivial branches.
 *
 * <p>The bytecode is unambiguous: {@code renderEntity} has exactly one
 * {@code INVOKEVIRTUAL Render.doRender(Entity,DDDFF)V} and exactly one
 * {@code INVOKEVIRTUAL Render.doRenderShadowAndFire(Entity,DDDFF)V}, verified with
 * {@code javap -c}. They share a descriptor but not a name, so neither redirect can
 * drift onto the other.
 *
 * <p><b>The gate.</b> Both are gated on {@link FrameProfiler#entLoopOpen()}, the
 * same flag pass 27 uses and for the same reason: {@code renderEntity} is called
 * from {@code renderEntityStatic} (the entity loop, inside the window) and from
 * {@code renderMultipass} (the {@code list2} replay below the loop, after
 * {@code PooledMutableBlockPos.release()} has dropped the flag). The gate is the
 * loop's own iterator, never an ordinal.
 *
 * <p>Neither redirect needs an attach flag of its own: {@code doN} and {@code shN}
 * must each track the {@code entattr} row's {@code n} (7&ndash;9 at rd 12), and a
 * hook that failed to attach reads 0 &mdash; which is exactly the reading that looks
 * like "this call is free". The nested display-list half is accumulated in
 * {@code DisplayListManager.replayList} off the same gate.
 */
@Mixin(RenderManager.class)
public class RenderManagerMixin {

    @Redirect(
            method = "renderEntity(Lnet/minecraft/entity/Entity;DDDFFZ)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/entity/Render;"
                             + "doRender(Lnet/minecraft/entity/Entity;DDDFF)V"))
    private void vulkanmod$timeEntityDoRender(Render<Entity> render, Entity entity, double x, double y,
                                              double z, float yaw, float partialTicks) {
        if (!FrameProfiler.entLoopOpen()) {
            render.doRender(entity, x, y, z, yaw, partialTicks);
            return;
        }
        final long t0 = System.nanoTime();
        // TEMPORARY: name the entity whose display-list replays follow, and where
        // the base modelview says it belongs. See TextureProbe.beginEntity.
        com.yuhan123.vulkanmod.vulkan.texture.TextureProbe.beginEntity(entity, x, y, z);
        render.doRender(entity, x, y, z, yaw, partialTicks);
        com.yuhan123.vulkanmod.vulkan.texture.TextureProbe.onEntityEnd();
        FrameProfiler.onEntityDo(System.nanoTime() - t0);
    }

    @Redirect(
            method = "renderEntity(Lnet/minecraft/entity/Entity;DDDFFZ)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/entity/Render;"
                             + "doRenderShadowAndFire(Lnet/minecraft/entity/Entity;DDDFF)V"))
    private void vulkanmod$timeEntityShadowAndFire(Render<Entity> render, Entity entity, double x, double y,
                                                   double z, float yaw, float partialTicks) {
        if (!FrameProfiler.entLoopOpen()) {
            render.doRenderShadowAndFire(entity, x, y, z, yaw, partialTicks);
            return;
        }
        final long t0 = System.nanoTime();
        render.doRenderShadowAndFire(entity, x, y, z, yaw, partialTicks);
        FrameProfiler.onEntityShadow(System.nanoTime() - t0);
    }
}
