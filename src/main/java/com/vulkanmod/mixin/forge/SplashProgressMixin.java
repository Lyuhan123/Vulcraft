package com.yuhan123.vulkanmod.mixin.forge;

import net.minecraftforge.fml.client.SplashProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(SplashProgress.class)
public class SplashProgressMixin {
    @Overwrite(remap = false)
    public static void start() {}

}
