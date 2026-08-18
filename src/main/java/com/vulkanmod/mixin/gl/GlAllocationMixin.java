package com.yuhan123.vulkanmod.mixin.gl;

import net.minecraft.client.renderer.GLAllocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GLAllocation.class)
public class GlAllocationMixin {
    @Redirect(method = "generateDisplayLists", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;glGenLists(I)I"))
    private static int genLists(int p_74527_0_) {
        // Return a unique display-list id so each ModelRenderer gets its own
        // list; -1 would make every glNewList overwrite the same entry.
        return com.yuhan123.vulkanmod.gl.DisplayListManager.genLists(p_74527_0_);
    }
}
