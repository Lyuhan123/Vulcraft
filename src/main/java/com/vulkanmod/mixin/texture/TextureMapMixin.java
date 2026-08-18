package com.yuhan123.vulkanmod.mixin.texture;

import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
import net.minecraft.client.renderer.texture.TextureMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(TextureMap.class)
public class TextureMapMixin {

    @Redirect(method = "loadTextureAtlas", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getGLMaximumTextureSize()I"))
    private int getTextureMapMaxSize() {

    return VRenderSystem.maxSupportedTextureSize();
}
}
