package com.yuhan123.vulkanmod.mixin.shader;

import net.minecraft.client.shader.ShaderManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderManager.class)
public class ShaderManagerMixin {

    @Shadow @Final private String programFilename;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void loadShader(CallbackInfo ci) {
        System.out.println(programFilename);
    }
}
