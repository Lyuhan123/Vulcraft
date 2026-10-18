package com.yuhan123.vulkanmod.mixin.gui;

import com.cleanroommc.client.modlist.ImageInfo;
import com.cleanroommc.client.modlist.RenderUtils;
import com.cleanroommc.client.modlist.data.IModData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Make the mod-list background a custom size ONLY for vulcraft.
 *
 * Cleanroom's default {@code drawBackground} (ModListScreen.java:1171) draws every selected mod's
 * background with the same geometry: height = clamp(height/3, 128, 180) -> effectively 180px,
 * faded fully to transparent at the bottom (alpha levels 1,1,0.65,0.25,0). This mixin intercepts
 * that method and, when the selected mod is vulcraft, draws a taller / more-visible background and
 * cancels the original draw. For every other mod it does nothing, so the global behaviour is
 * completely untouched.
 *
 * We extend GuiScreen so the inherited members (mc, height, zLevel) are visible at compile time,
 * matching the existing GuiMainMenuMixin convention. The three ModListScreen-specific fields are
 * pulled in with @Shadow.
 */
@Mixin(targets = "com.cleanroommc.client.modlist.screen.ModListScreen")
public abstract class ModListScreenBackgroundMixin extends GuiScreen {

    // Only this mod id gets the custom treatment; every other mod keeps Cleanroom's default.
    private static final String VULCRAFT_MOD_ID = "vulcraft";

    // ---- Custom geometry for vulcraft (tweak these) -------------------------
    // Background height as a fraction of the screen height, clamped to [MIN, MAX].
    // Cleanroom's default caps at 180, so these make vulcraft's strip clearly taller.
    private static final float VULCRAFT_BG_HEIGHT_FRACTION = 0.6F;
    private static final int   VULCRAFT_BG_MIN_HEIGHT = 240;
    private static final int   VULCRAFT_BG_MAX_HEIGHT = 480;

    // Vertical alpha fade, top -> bottom (5 bands). Higher bottom values keep more of the
    // image visible lower down. Cleanroom default is {1,1,0.65,0.25,0}.
    private static final float[] VULCRAFT_BG_FADE = {1.0F, 1.0F, 0.9F, 0.7F, 0.45F};
    // ------------------------------------------------------------------------

    @Shadow private IModData selectedModData;
    @Shadow private static ImageInfo cachedBackground;
    @Shadow private static ImageInfo MISSING_BACKGROUND_INFO;

    @Inject(method = "drawBackground", at = @At("HEAD"), cancellable = true)
    private void vulcraftDrawBackground(int contentWidth, int contentLeft, int contentTop, CallbackInfo ci) {
        // Matches the null guard the original method does at its start.
        if (this.selectedModData == null) {
            return;
        }
        // Not our mod: leave Cleanroom's global rendering completely alone.
        if (!VULCRAFT_MOD_ID.equals(this.selectedModData.getModId())) {
            return;
        }

        ImageInfo background = cachedBackground != null ? cachedBackground : MISSING_BACKGROUND_INFO;

        int customHeight = MathHelper.clamp(
                (int) (this.height * VULCRAFT_BG_HEIGHT_FRACTION),
                VULCRAFT_BG_MIN_HEIGHT,
                VULCRAFT_BG_MAX_HEIGHT);

        // x / y / width stay exactly where Cleanroom positioned the panel; only the height
        // (size) and fade change. Pass the 5 fade levels positionally to drawCoverFade.
        RenderUtils.drawCoverFade(
                this.mc, background,
                contentLeft, contentTop, contentWidth, customHeight, this.zLevel,
                VULCRAFT_BG_FADE[0], VULCRAFT_BG_FADE[1], VULCRAFT_BG_FADE[2],
                VULCRAFT_BG_FADE[3], VULCRAFT_BG_FADE[4]);

        // We drew it ourselves; suppress the original (default-sized) draw.
        ci.cancel();
    }
}
