package com.yuhan123.vulkanmod.mixin;

import com.yuhan123.vulkanmod.VKProf;
import com.yuhan123.vulkanmod.config.VulkanModConfig;
import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.gl.VkGlFramebuffer;
import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.Vulkan;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.profiler.Profiler;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import org.lwjgl.opengl.Display;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;

/**
 * Vanilla mixin example
 * Refmap will be handled by Unimined automatically
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
    /**
     * Headless-bench hooks: set AUTOJOIN=1 to load the "New World"
     * save from the main menu and AUTOQUIT=1 to exit 30s after the
     * integrated server is up. Both are no-ops unless the env vars are set,
     * so normal gameplay is unaffected.
     */
    @Unique private static boolean autoQuitFired = false;
    @Unique private static long autoQuitStart = 0;
    @Unique private static boolean autoTeleported = false;
    @Unique private static boolean sceneFrozen = false;
    @Unique private static boolean profileWindowStarted = false;
    @Unique private static final boolean BENCH_MODE = VulkanModConfig.getBool("AUTOJOIN", false);

    /**
     * Compatibility-test mode (COMPAT=1): a headless smoke test that drives
     * vulcraft alongside other mods and captures a sequence of screenshots:
     * world + JEI overlay, given items, open inventory, and a different
     * dimension (the End, to prove vulcraft re-renders on a dimension switch).
     * Reuses AUTOJOIN (loads "New World") and never pauses, exactly like bench
     * mode, so the game reaches an in-world scene without manual input.
     */
    @Unique private static final boolean COMPAT_MODE = VulkanModConfig.getBool("COMPAT", false);

    /** When the compatibility run entered the world (ms); drives the step sequence. */
    @Unique private static long compatStart = 0;
    @Unique private static boolean compatWorldShot = false;
    @Unique private static boolean compatGive = false;
    @Unique private static boolean compatItemsShot = false;
    @Unique private static boolean compatInvOpen = false;
    @Unique private static boolean compatInvShot = false;
    @Unique private static boolean compatDim = false;
    @Unique private static boolean compatQuit = false;

    // Twilight Forest portal step (replaces the old End switch): build a real TF
    // portal at the player's feet, drop a diamond in it so TF's own
    // checkForPortalCreation (fired every player tick) forms the portal and
    // teleports the player, and fall back to a direct changeDimension(7) if the
    // portal has not formed within the poll window.
    @Unique private static long compatTfBuildMs = 0;
    @Unique private static boolean compatTfShot = false;
    @Unique private static boolean compatTfFallback = false;
    @Unique private static long compatTfFallbackMs = 0;
    @Unique private static long compatTfArriveMs = 0;

    // Whether Twilight Forest is actually loaded this run (gates the portal step,
    // so the harness works as a generic smoke test for any mod set, not just TF).
    @Unique private static boolean compatTfResolved = false;
    @Unique private static boolean compatTfEnabled = false;
    @Unique private static boolean compatModsLogged = false;
    @Unique private static boolean compatJmEnabled = false;
    @Unique private static boolean compatJmInvClosed = false;
    @Unique private static boolean compatJmOpen = false;
    @Unique private static boolean compatJmShot = false;
    @Unique private static boolean compatJmClosed = false;

    // Botania item-effect test (BOTANIA_TEST=1 + botania loaded): replaces the
    // vanilla step sequence with give-items -> fire Mana Blaster -> place
    // functional flowers -> hold Mana Tablet (HUD) + screenshots at each step.
    @Unique private static final boolean BOTANIA_TEST =
            VulkanModConfig.getBool("BOTANIA_TEST", false);
    @Unique private static boolean compatBotaniaEnabled = false;
    @Unique private static boolean botAimed = false;
    @Unique private static boolean botArenaBuilt = false;
    @Unique private static boolean botServerTp = false;
    @Unique private static boolean botRefreshed = false;
    @Unique private static boolean botRefreshed2 = false;
    @Unique private static boolean botDesyncRecheck = false;
    @Unique private static boolean botEarlyDump = false;
    @Unique private static boolean botEntLog1 = false;
    @Unique private static boolean botEntLog2 = false;
    @Unique private static boolean botEntLog3 = false;
    @Unique private static boolean botEntLog4 = false;
    @Unique private static boolean botGave = false;
    @Unique private static boolean botItemsShot = false;
    @Unique private static boolean botGunHeld = false;
    @Unique private static boolean botFired1 = false;
    @Unique private static boolean botFired2 = false;
    @Unique private static boolean botFired3 = false;
    @Unique private static boolean botBurstShot = false;
    @Unique private static boolean botBurstShot2 = false;
    @Unique private static boolean botAimDown = false;
    @Unique private static boolean botPlaced1 = false;
    @Unique private static boolean botPlaced2 = false;
    @Unique private static boolean botAimBack = false;
    @Unique private static int botFx = 0;
    @Unique private static int botFy = 0;
    @Unique private static int botFz = 0;
    @Unique private static boolean botFlowersShot = false;
    @Unique private static boolean botTabletHeld = false;
    @Unique private static boolean botHudShot = false;

    // Tech-mod machine-GUI test (TECHGUI_TEST=1): for the first loaded tech mod,
    // build a small platform, teleport the player next to a placed machine
    // block, open its GUI on the SERVER thread (the real right-click path),
    // capture techgui_<label>.png, then close the GUI and quit. Driven one mod
    // per run (the harness selects it via -Pcompat_mods); the bot opens
    // whichever machine block maps to the first loaded tech mod.
    @Unique private static final boolean TECHGUI_TEST =
            VulkanModConfig.getBool("TECHGUI_TEST", false);

    // Render-fix regression test (FIXTEST=1 + AUTOJOIN=1): captures the F3
    // direction axes (OpenGlHelper.renderDirections - the red/green/blue rays
    // that used to render as wide filled triangles) and an end-portal block
    // (TileEntityEndPortalRenderer - the texGen starfield that used to render
    // untextured). One screenshot per target, then quit.
    @Unique private static final boolean FIXTEST =
            VulkanModConfig.getBool("FIXTEST", false);
    @Unique private static boolean fixF3On = false;
    @Unique private static boolean fixF3Shot = false;
    @Unique private static boolean fixStageBuilt = false;
    @Unique private static boolean fixPortalPlaced = false;
    @Unique private static boolean fixPortalShot = false;
    @Unique private static boolean fixQuit = false;

    // Window-minimise regression test (MINITEST=1 + AUTOJOIN=1): iconify the
    // GLFW window, keep the game loop running while it is minimised, restore
    // it, and capture a screenshot before and after. Iconifying drops the
    // framebuffer size to 0x0 on every platform, so the surface's
    // currentExtent does too - this exercises the swapchain rebuild path that
    // the "minimising sometimes crashes / leaves a black screen" report hits.
    @Unique private static final boolean MINITEST =
            VulkanModConfig.getBool("MINITEST", false);
    @Unique private static boolean miniBeforeShot = false;
    @Unique private static boolean miniIconified = false;
    @Unique private static boolean miniRestored = false;
    @Unique private static boolean miniAfterShot = false;
    @Unique private static boolean miniQuit = false;
    @Unique private static boolean compatTechGuiEnabled = false;
    @Unique private static boolean techPlatformBuilt = false;
    @Unique private static boolean techServerTp = false;
    @Unique private static boolean techBlockPlaced = false;
    @Unique private static boolean techGuiOpenRequested = false;
    @Unique private static boolean techGuiOpened = false;
    @Unique private static boolean techGuiShot = false;
    @Unique private static boolean techGuiClosed = false;
    @Unique private static String techTargetMod = "";
    @Unique private static String techTargetBlock = "";
    @Unique private static int techTargetMeta = 0;

    /**
     * How long after the bench window opens the profiler's accumulators are
     * cleared, so the dump covers the <b>steady state only</b>.
     *
     * <p>This exists because the dump was a <i>run</i> average, and the run
     * includes the world-load phase - where {@code setupTerrain} is called with
     * 124&ndash;318 ms per call (the profiler's own "Something's taking too
     * long!" warnings) and chunk building dominates everything. That phase
     * contributed roughly a third of the samples, which is why the dump has
     * ranked {@code terrain_setup} as the largest section of the frame (11.8%)
     * for several passes while the steady state is a stationary camera whose
     * {@code displayListEntitiesDirty} is false, i.e. {@code setupTerrain} skips
     * the entire visible-chunk BFS. A run average that is a third load phase is
     * not a ranking of the steady-state frame, and treating it as one has sent
     * optimisation at load-time work more than once.
     *
     * <p>Clearing at the top of {@code runGameLoop} is safe: the profiler's
     * section stack is empty there (it opens {@code root} immediately after),
     * and {@code clearProfiling()} resets exactly the accumulators
     * {@code getProfilingData} reads.
     *
     * <p>{@code PROFILE_WARMUP} sets it in seconds (default 10). A
     * value at or above the bench window leaves the old run-average behaviour.
     */
    @Unique private static final long PROFILE_WARMUP_MILLIS = vulkanmod$profileWarmupMillis();

    @Unique
    private static long vulkanmod$profileWarmupMillis() {
        String seconds = VulkanModConfig.get("PROFILE_WARMUP");
        if (seconds == null) {
            return 10_000L;
        }
        try {
            return Math.max(0L, Long.parseLong(seconds.trim())) * 1000L;
        } catch (NumberFormatException e) {
            return 10_000L;
        }
    }

    /**
     * The bench viewpoint, overridable so a run can be aimed at a scene the
     * default one does not contain.
     *
     * <p>The default - {@code 320.5, 67, -327.5}, yaw 180, pitch 15 - is the
     * forest scene the benchmark has always used, and <b>no environment variable
     * changes it unless one is set</b>, so every earlier pass stays comparable.
     *
     * <p>Why it exists (pass 21). The default camera faces {@code -Z}, and the
     * whole bench save's tile entities - 21 chunks hold chests, spawners and a
     * furnace - happen to lie to the south of it. So the tile-entity loop's
     * non-empty branch never ran: {@code count=0} over 2 280 frames, and the
     * compaction under test would have iterated <b>zero</b> entries. A correctness
     * gate cannot test a branch the scene never reaches, and the failure mode of
     * getting that branch wrong (a tile entity silently not drawn) is invisible to
     * every harness in this repo. {@code BENCH_POS="x,y,z"} plus
     * {@code BENCH_YAW}/{@code BENCH_PITCH} aim the camera at a
     * scene that does contain tile entities, which turns that branch from untested
     * into tested.
     *
     * <p>Returns {@code {x, y, z, yaw, pitch}} and is logged on use, so a run
     * self-reports where it stood rather than leaving the scene to be inferred.
     */
    @Unique
    private static double[] vulkanmod$benchViewpoint(double dx, double dy, double dz,
                                                     double dyaw, double dpitch) {
        double x = dx, y = dy, z = dz, yaw = dyaw, pitch = dpitch;
        final String pos = VulkanModConfig.get("BENCH_POS");
        if (pos != null) {
            final String[] parts = pos.split(",");
            if (parts.length == 3) {
                try {
                    x = Double.parseDouble(parts[0].trim());
                    y = Double.parseDouble(parts[1].trim());
                    z = Double.parseDouble(parts[2].trim());
                } catch (NumberFormatException e) {
                    VKProf.warn("[VKPROF] VULKANMOD_BENCH_POS unparseable: {}", pos);
                }
            } else {
                VKProf.warn("[VKPROF] VULKANMOD_BENCH_POS wants x,y,z - got {}", pos);
            }
        }
        yaw = vulkanmod$benchAngle("VULKANMOD_BENCH_YAW", yaw);
        pitch = vulkanmod$benchAngle("VULKANMOD_BENCH_PITCH", pitch);
        return new double[]{x, y, z, yaw, pitch};
    }

    @Unique
    private static double vulkanmod$benchAngle(String key, double fallback) {
        final String v = VulkanModConfig.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            VKProf.warn("[VKPROF] {} unparseable: {}", key, v);
            return fallback;
        }
    }

    /**
     * Bench-only scene injection: {@code BENCH_CHEST="x,y,z[;x,y,z]"}.
     *
     * <p>Why it exists (pass 21). The tile-entity loop's non-empty branch could
     * not be reached on this bench. The save holds 21 chunks with tile entities -
     * chests, mob spawners and one furnace - but every one of them is either
     * behind the default camera, beyond the render distance, or underground and
     * culled by the visibility graph. A 45 s run at rd 16 aimed straight at the
     * nearest surface chests still read {@code count=0} over 2 300 frames.
     *
     * <p>That matters because a correctness gate cannot test a branch the scene
     * never reaches, and the failure mode of getting this branch wrong - a tile
     * entity silently not drawn - is invisible to every harness in this repo. One
     * chest placed in front of the camera turns the branch from untested into
     * tested, and placing it away from the default viewpoint leaves the default
     * scene unchanged.
     *
     * <p>Default OFF. Runs on the server thread, once, after the teleport has
     * landed - the same plumbing the scene freeze uses.
     */
    @Unique
    private static boolean benchChestPlaced = false;

    /**
     * Bench-only probe block wall: {@code BENCH_BLOCK="<blockId>:<dz>"}.
     *
     * <p>Fills a solid wall one layer thick at {@code z = floor(pz) + dz},
     * spanning {@code x = px +/- 6} and {@code y = feet-2 .. feet+5}, with the
     * named block. The point is to put an arbitrary translucent / cutout surface
     * between the pinned camera and the fixed mob set, so a transparency defect
     * (a surface that blocks what is behind it instead of blending with it) can
     * be reproduced and photographed in a deterministic scene.
     *
     * <p>Deliberately comma-free: the harness forwards switches through
     * {@code -Pvulkanmod.env=NAME=VALUE,NAME=VALUE}, which splits on {@code ,},
     * so any value containing a comma would be silently truncated. The block id
     * carries no comma and the {@code dz} is the trailing integer after the last
     * {@code :}, which also tolerates namespaced ids like
     * {@code minecraft:stained_glass:3}.
     */
    @Unique
    private static void vulkanmod$injectBenchBlockWall(Minecraft mc) {
        final String spec = VulkanModConfig.get("BENCH_BLOCK");
        if (spec == null || mc.player == null || mc.getIntegratedServer() == null) {
            return;
        }
        // Several walls in one run, ';'-separated: "id:dz;id:dz". Filling with
        // minecraft:air clears an earlier run's probe (a bench run leaves its
        // blocks in the save - run_once.sh restores level.dat only, not terrain).
        final MinecraftServer server = mc.getIntegratedServer();
        final int px = net.minecraft.util.math.MathHelper.floor(mc.player.posX);
        final int py = net.minecraft.util.math.MathHelper.floor(mc.player.posY);
        final int pz = net.minecraft.util.math.MathHelper.floor(mc.player.posZ);

        for (String one : spec.split(";")) {
            final String entry = one.trim();
            final int lastColon = entry.lastIndexOf(':');
            if (lastColon < 0) {
                VKProf.warn("[VKPROF] VULKANMOD_BENCH_BLOCK wants <blockId>:<dz> - got {}", entry);
                continue;
            }
            final String blockId = entry.substring(0, lastColon);
            final int dz;
            try {
                dz = Integer.parseInt(entry.substring(lastColon + 1).trim());
            } catch (NumberFormatException e) {
                VKProf.warn("[VKPROF] VULKANMOD_BENCH_BLOCK dz unparseable: {}", entry);
                continue;
            }
            final String command = String.format(java.util.Locale.ROOT,
                    "fill %d %d %d %d %d %d %s", px - 6, py - 2, pz + dz, px + 6, py + 5, pz + dz, blockId);
            server.addScheduledTask(() -> {
                try {
                    server.getCommandManager().executeCommand(server, command);
                    VKProf.info("[VKPROF] bench block wall: {}", command);
                } catch (Throwable t) {
                    VKProf.warn("[VKPROF] bench block wall failed: {}", command, t);
                }
            });
        }
    }

    @Unique
    private static void vulkanmod$injectBenchScene(Minecraft mc) {
        vulkanmod$injectBenchBlockWall(mc);

        final String spec = VulkanModConfig.get("BENCH_CHEST");
        if (spec == null || mc.getIntegratedServer() == null) {
            return;
        }
        final MinecraftServer server = mc.getIntegratedServer();
        for (String one : spec.split(";")) {
            final String[] xyz = one.trim().split(",");
            if (xyz.length != 3) {
                VKProf.warn("[VKPROF] VULKANMOD_BENCH_CHEST wants x,y,z - got {}", one);
                continue;
            }
            final String command = "setblock " + xyz[0].trim() + " " + xyz[1].trim()
                    + " " + xyz[2].trim() + " minecraft:chest";
            server.addScheduledTask(() -> {
                try {
                    server.getCommandManager().executeCommand(server, command);
                    VKProf.info("[VKPROF] bench scene injection: {}", command);
                } catch (Throwable t) {
                    VKProf.warn("[VKPROF] bench scene injection failed: {}", command, t);
                }
            });
        }
    }

    /**
     * FREEZE=1 (bench mode only) pins the world into a deterministic
     * state right after the teleport, so an A/B compares the same scene instead
     * of two samples of a drifting one.
     *
     * <p>This exists because scene drift turned out to be the binding constraint
     * on measurement, not sample count. The last pass ran 28&ndash;37 settled
     * reports per case - enough by the earlier standard - and still could not
     * resolve the frame row, because the two cases differed by 8&ndash;25% in
     * {@code draws}/{@code dlReplay} (visible entity model parts), worth up to
     * ~0.4 ms on its own. That is more than the changes being measured.
     *
     * <p>What is pinned: no weather (rain is both a particle load and a
     * display-list load), no daylight cycle (a moving sun changes the sky and
     * the lightmap), no mob spawning and no random ticks (no new entities, no
     * grass spread), a fixed time of day, and static entities.
     *
     * <p>Two entity modes, because they are the right bench for different
     * changes:
     * <ul>
     *   <li><b>{@code FREEZE=1} (default)</b> clears the world's
     *       entities and summons a fixed set of mobs at fixed coordinates with
     *       {@code NoAI/NoGravity/Invulnerable/PersistenceRequired}. The
     *       display-list load that entity drawing represents is
     *       <em>preserved</em> (which is what changes on the entity apply path
     *       are measured against) while the set in the frustum stops changing,
     *       so {@code dlReplay} goes constant. Clearing first matters: the
     *       natural mobs wander, so their count in view is whatever the save
     *       happened to hold.</li>
     *   <li><b>{@code FREEZE=kill}</b> only kills, and summons
     *       nothing. A smaller, even more stable scene - but it deletes the
     *       entity draw load entirely, so it is only valid for terrain and
     *       chunk-path changes. Measuring a display-list change here understates
     *       it to near zero, which is exactly what happened the first time.</li>
     *   <li><b>{@code FREEZE=restore}</b> changes no scene state; it
     *       only puts the four gamerules back to their vanilla defaults and
     *       exits. Used to repair the bench save after a run that persisted
     *       them - {@code gamerule} writes to {@code level.dat}, so a bench that
     *       freezes them freezes them <em>for good</em> unless something puts
     *       them back. The harnesses now snapshot the save instead.</li>
     * </ul>
     */
    @Unique private static final String FREEZE_MODE =
            VulkanModConfig.get("FREEZE");

    @Unique private static final boolean FREEZE_SCENE =
            FREEZE_MODE != null && !"0".equals(FREEZE_MODE);

    /** {@code FREEZE=kill} removes entities instead of replacing them. */
    @Unique private static final boolean FREEZE_KILL = "kill".equals(FREEZE_MODE);

    /** {@code FREEZE=restore} only puts the gamerules back. */
    @Unique private static final boolean FREEZE_RESTORE = "restore".equals(FREEZE_MODE);

    /**
     * {@code FREEZE=ground} summons the same fixed mob set but
     * <b>without</b> {@code NoGravity}, so the mobs fall onto the terrain and
     * rest on it instead of hovering at spawn height.
     *
     * <p>Why (pass 32). The default freeze pins the set with {@code NoGravity}
     * at {@code player.y + 0.5}; after the player lands that is ~1.2 blocks
     * above the terrain surface, so every shadow vertex computes a negative
     * alpha and the hard {@code color.a < 0.1} discard in {@code
     * position_tex_color.fsh} deletes the shadow entirely. The bench could
     * therefore never reproduce the on-ground "grid of circular shadows" the
     * user reported - it saw nothing. Dropping gravity lets the mobs settle on
     * the ground, where the real shadow lives, so the artifact is actually
     * visible and fixable from here.
     */
    @Unique private static final boolean FREEZE_GROUND = "ground".equals(FREEZE_MODE);

    /**
     * PROFILE_DUMP=1 (bench mode only) forces vanilla's own Profiler
     * on for the whole run and dumps its section tree at auto-quit, which
     * attributes the parts of the frame the mod's own report cannot see
     * (terrain vs entities vs updatechunks vs clouds). The dump is a
     * <i>run</i> average, which is exactly what one wants for ranking the
     * sections but not for absolute timing.
     */
    @Unique private static final boolean PROFILE_DUMP =
            VulkanModConfig.getBool("PROFILE_DUMP", false);

    /**
     * Chunk streaming is not instant: at a high render distance a fresh launch
     * can still be uploading sections when a 30 s window closes, so the
     * "steady state" average includes world-loading work (tick time inflated,
     * draw count still climbing). A longer window lets the scene settle.
     */
    @Unique private static final long BENCH_MILLIS = vulkanmod$benchMillis();

    @Unique
    private static long vulkanmod$benchMillis() {
        String seconds = VulkanModConfig.get("BENCH_SECONDS");
        if (seconds == null) {
            return 30_000L;
        }
        try {
            return Math.max(5L, Long.parseLong(seconds.trim())) * 1000L;
        } catch (NumberFormatException e) {
            return 30_000L;
        }
    }

    /** Sections below this share of the frame are omitted from the dump. */
    @Unique private static final double PROFILE_MIN_PERCENT = 0.3;

    /**
     * Runs the freeze command set through the integrated server's command
     * manager. Singleplayer only, which is all the bench uses: in singleplayer
     * the server owns world time and the entity list, so a client-side
     * {@code setWorldTime} would be overridden on the next tick.
     *
     * <p>The sender is the <b>server</b>, not the player. An entity selector
     * resolves its dimensions through {@code sender.getServer()}, and the client
     * player's is null - so {@code kill @e[type=!player]} throws
     * {@code NullPointerException: ... getServer() is null} and silently leaves
     * every entity in the world. The server is its own server, so selectors work.
     *
     * <p>Each command is issued on its own so one unsupported selector cannot
     * take the rest of the freeze down with it - a partially frozen scene is
     * still better than a drifting one, and the log says which failed.
     */
    @Unique
    private static void vulkanmod$freezeScene(Minecraft mc) {
        final MinecraftServer server = mc.getIntegratedServer();

        if (FREEZE_RESTORE) {
            // Repair mode: nothing about the scene is touched, only the four
            // gamerules a freeze run persists into level.dat.
            server.addScheduledTask(() -> {
                int failed = 0;
                for (String command : vulkanmod$vanillaGamerules()) {
                    try {
                        server.getCommandManager().executeCommand(server, command);
                    } catch (Throwable t) {
                        failed++;
                        VKProf.warn("[VKPROF] restore command failed: {}", command, t);
                    }
                }
                VKProf.info("[VKPROF] gamerules restored to vanilla ({} failed)", failed);
            });
            return;
        }

        final String[] sceneCommands = {
                "weather clear 1000000",
                "gamerule doWeatherCycle false",
                "gamerule doDaylightCycle false",
                "gamerule doMobSpawning false",
                "gamerule randomTickSpeed 0",
                "time set 6000",
        };

        // Entity handling. A wandering mob walks in and out of the frustum, and
        // that is what makes dlReplay drift between A/B cases - but its *draw*
        // is the display-list load that some changes are meant to reduce, so
        // deleting it hides the effect. Hence: clear, then repopulate with a
        // fixed set at fixed coordinates, frozen in place. Both the count and
        // the positions are then identical in every run, and the load is real.
        final String[] entityCommands;
        if (FREEZE_KILL) {
            entityCommands = new String[] { "kill @e[type=item]", "kill @e[type=!player]" };
        } else {
            entityCommands = vulkanmod$fixedMobSet(mc);
        }

        // These MUST be queued onto the server thread, not run here.
        //
        // `kill @e` removes entities from the entity tracker's map, and the
        // server thread iterates that same map every tick in
        // EntityTracker.tick. Running the command from the client thread - which
        // is where this method is called from - mutates the map mid-iteration
        // and the server dies with ConcurrentModificationException. (The other
        // commands only touch scalars and would survive, but queuing the whole
        // set freezes the scene atomically inside a single server tick.)
        server.addScheduledTask(() -> {
            int failed = 0;
            for (String[] group : new String[][] { sceneCommands, entityCommands }) {
                for (String command : group) {
                    try {
                        server.getCommandManager().executeCommand(server, command);
                    } catch (Throwable t) {
                        failed++;
                        VKProf.warn("[VKPROF] freeze command failed: {}", command, t);
                    }
                }
            }
            VKProf.info("[VKPROF] scene frozen at ({}, {}, {}): clear weather, fixed noon, no mob spawning / random ticks, entities {} ({} failed)",
                    String.format(java.util.Locale.ROOT, "%.1f", mc.player.posX),
                    String.format(java.util.Locale.ROOT, "%.1f", mc.player.posY),
                    String.format(java.util.Locale.ROOT, "%.1f", mc.player.posZ),
                    FREEZE_KILL ? "removed" : "replaced with a fixed set",
                    failed);
        });
    }

    /** The four gamerules a freeze run persists, back at their vanilla values. */
    @Unique
    private static String[] vulkanmod$vanillaGamerules() {
        return new String[] {
                "gamerule doWeatherCycle true",
                "gamerule doDaylightCycle true",
                "gamerule doMobSpawning true",
                "gamerule randomTickSpeed 3",
        };
    }

    /**
     * Clears the world's entities and summons a fixed mob set in their place.
     *
     * <p>Positions are offsets from the camera, not absolute world
     * coordinates. The benchmark viewpoint sits on a beach, and the terrain
     * surface there is only a block or so below the player's feet - an absolute
     * Y puts the mobs *inside* the sand, where they are frustum/occlusion
     * culled and add exactly nothing, so the entity workload the bench exists
     * to reproduce silently disappears. Anchoring to {@code mc.player} puts
     * them in the near foreground wherever the camera happens to be: +Z is the
     * direction the viewpoint faces.
     *
     * <p>They carry {@code NoAI} (no wandering, so the set in the frustum never
     * changes), {@code NoGravity} (they hold the spawn height instead of
     * falling to whatever the terrain is), {@code Invulnerable} (no suffocation
     * or fall damage killing them at different times) and
     * {@code PersistenceRequired} (no despawn). The point is not that they look
     * right - it is that the entity draw load is real, and that its size and
     * positions are identical in every run.
     */
    @Unique
    private static String[] vulkanmod$fixedMobSet(Minecraft mc) {
        final String[] types = { "Cow", "Sheep", "Pig", "Chicken" };
        // { dx, dy, dz } from the player's feet.
        //
        // FREEZE_GROUND uses its own layout (dy = +6, so each mob is dropped
        // from above and lands ON the terrain surface rather than hovering at
        // player height), and spaces them out along the centre of the view.
        // Spacing matters for the shadow investigation: a tight cluster of mobs
        // makes a cluster of overlapping shadows, and the point is to see
        // whether ONE mob throws ONE circle or a 2x2 patch of them. Keeping the
        // near ones within the horizontal FOV (~+/-3.5 blocks at z=5) means the
        // shadows are actually on screen rather than past the frame edge.
        final double[][] spots = FREEZE_GROUND
                ? new double[][] {
                        { -2.5, 6.0, 5.0 }, { 2.5, 6.0, 5.0 },
                        { -2.5, 6.0, 8.5 }, { 2.5, 6.0, 8.5 },
                        {  0.0, 6.0, 6.7 },
                }
                : new double[][] {
                        { -3.5, 0.5, 4.5 }, { 0.5, 0.5, 5.5 }, { 3.5, 0.5, 5.5 },
                        { -1.5, 0.5, 7.5 }, { 2.5, 0.5, 8.5 }, { -4.5, 0.5, 9.5 },
                        { 0.5, 0.5, 10.5 }, { 4.5, 0.5, 11.5 }, { -2.5, 0.5, 12.5 },
                        { 1.5, 0.5, 13.5 }, { -5.5, 0.5, 14.5 }, { 5.5, 0.5, 15.5 },
                };
        final double px = mc.player.posX, py = mc.player.posY, pz = mc.player.posZ;

        final java.util.List<String> cmds = new java.util.ArrayList<>();
        // Clear first: the natural mobs wander, so leaving them in means the
        // count in view is whatever the save happened to hold.
        cmds.add("kill @e[type=!player]");

        if (FREEZE_GROUND) {
            // Flatten a platform so the shadow lands on LEVEL ground and can
            // actually be read. Without it the mobs land on a slope and every
            // shadow smears down the hill - a band, not the artifact under
            // investigation. Fill stone from three below the surface up to the
            // surface block, then clear the terrain above it, so the top is
            // exactly flat (the mobs' feet then sit at the player's own y).
            final int x0 = (int) Math.floor(px) - 8, x1 = (int) Math.floor(px) + 8;
            // z0 reaches BACK behind the player (not just in front). The
            // screenshot viewpoint is (px, py, pz) itself, so if the platform
            // started ahead of the player the player would have nothing to
            // stand on after the teleport, fall to the natural terrain, and the
            // pinned camera would then stare at the platform's 4-block side
            // wall - a frame of solid stone with no mobs in it (measured, one
            // run of pass 32). Covering z=floor(pz)-6 keeps the player on the
            // slab at py, which is the height the shadow geometry assumes.
            final int z0 = (int) Math.floor(pz) - 6, z1 = (int) Math.floor(pz) + 14;
            final int top = (int) Math.floor(py) - 1;
            cmds.add(String.format(java.util.Locale.ROOT,
                    "fill %d %d %d %d %d %d minecraft:stone", x0, top - 3, z0, x1, top, z1));
            cmds.add(String.format(java.util.Locale.ROOT,
                    "fill %d %d %d %d %d %d minecraft:air", x0, top + 1, z0, x1, top + 9, z1));
        }

        for (int i = 0; i < spots.length; i++) {
            // Locale.ROOT: a comma decimal separator would produce an
            // unparseable coordinate in a comma-separated command.
            // FREEZE_GROUND drops NoGravity so the mob falls onto the platform
            // (see the flag's javadoc) - the others stay frozen in place.
            final String gravity = FREEZE_GROUND ? "0b" : "1b";
            cmds.add(String.format(
                    java.util.Locale.ROOT,
                    "summon %s %.1f %.1f %.1f {NoAI:1b,NoGravity:" + gravity + ",Invulnerable:1b,PersistenceRequired:1b,Silent:1b}",
                    types[i % types.length],
                    px + spots[i][0], py + spots[i][1], pz + spots[i][2]));
        }
        return cmds.toArray(new String[0]);
    }

    /**
     * Dumps vanilla's Profiler tree at auto-quit. Sections come out as
     * siblings (endStartSection closes the current and opens the new as a
     * sibling), so the tree printed by getProfilingData is read as-is instead
     * of being collapsed into a guessed path.
     */
    @Unique
    private static void dumpProfiler(Minecraft mc) {
        try {
            VKProf.info("[VKPROF] === vanilla profiler, {} average "
                    + "(values are percent of frame) ===",
                    profileWindowStarted ? "STEADY-STATE (post-warm-up)" : "run");
            dumpTree(mc.profiler, "root", 0);
        } catch (Throwable t) {
            VulkanMod.LOGGER.error("[VKPROF] profiler dump failed", t);
        }
    }

    @Unique
    private static void dumpTree(Profiler profiler, String path, int depth) {
        if (depth > 5) {
            return;
        }
        List<Profiler.Result> results = profiler.getProfilingData(path);
        if (results == null || results.size() <= 1) {
            return;
        }
        for (int i = 1; i < results.size(); ++i) {
            Profiler.Result r = results.get(i);
            if (r.totalUsePercentage < PROFILE_MIN_PERCENT) {
                continue;
            }
            // Built with String.format, not slf4j placeholders: slf4j only
            // understands "{}", so width specifiers would be logged literally.
            VKProf.info("[VKPROF] {}",
                    String.format("%-" + (depth * 2 + 24) + "s %5.1f%%  of parent %5.1f%%",
                            "  ".repeat(depth) + r.profilerName,
                            r.totalUsePercentage, r.usePercentage));
            dumpTree(profiler, path + "." + r.profilerName, depth + 1);
        }
    }

    /**
     * SHOT=N captures one screenshot N seconds after joining.
     *
     * glReadPixels was never implemented, so Minecraft's F2 (and every
     * diagnostic that needs pixels) silently produced nothing. With it now
     * implemented (VkGlFramebuffer.readPixels, downloads via
     * ImageUtil.downloadTexture), the benchmark can capture a frame by itself
     * - so a visual question can be answered from the PNG instead of by
     * guessing. Without this, "you can see through the mob" is unfixable
     * without someone looking at the screen.
     */
    @Unique private static final long SHOT_SECONDS = vulkanmod$shotSeconds();
    @Unique private static final String SHOT_TAG = vulkanmod$shotTag();
    @Unique private static boolean screenshotFired = false;

    /**
     * PIN_CAMERA=1 re-applies the bench viewpoint every frame until
     * the shot fires, instead of only once at the teleport.
     *
     * <p>Why (pass 24). The one-shot teleport is not enough to make two runs
     * comparable. The player is dropped at y=72 over ground that is not there
     * yet (the chunks around the viewpoint are still streaming in), so the
     * player <em>falls</em> until the terrain arrives, and the third-person
     * camera rides the fall. How far the fall got by shot time therefore
     * depends on how fast the run reached the shot - i.e. on the very thing
     * under test. Two runs of the same build differed only in a render flag,
     * and the images came back 28/255 apart with visibly different horizons:
     * the artifact under investigation was drowned in a camera difference.
     *
     * <p>Pinning makes the camera an input rather than an outcome. Default OFF
     * so every earlier capture keeps its meaning.
     */
    @Unique private static final boolean PIN_CAMERA = VulkanModConfig.getBool("PIN_CAMERA", false);

    /** Viewpoint last applied, so PIN_CAMERA can re-apply the same one. */
    @Unique private static double[] pinnedViewpoint = null;

    /**
     * Force the CLIENT world time to a fixed tick every frame until the shot
     * fires. Off (<0) by default.
     *
     * <p>Why this exists: /time set night through the integrated server does
     * reach the server world (logged serverWorldTime=13000) but the client's
     * own WorldClient stayed at 6000 (noon) for the entire run, so every
     * "night" capture was actually rendered at noon with cloudColour
     * (1,1,1) - i.e. it never tested the night path at all.
     */
    @Unique private static final long FORCE_CLIENT_TIME = vulkanmod$forceClientTime();

    /** Render frames counted inside the bench window, for the FPS report. */
    @Unique private static long benchFrames = 0;

    @Unique
    private static long vulkanmod$forceClientTime() {
        final String v = VulkanModConfig.get("BENCH_CLIENT_TIME");
        if (v == null || v.isBlank()) {
            return -1L;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    @Unique
    private static long vulkanmod$shotSeconds() {
        String seconds = VulkanModConfig.get("SHOT");
        if (seconds == null) {
            return 0L;
        }
        try {
            return Math.max(1L, Long.parseLong(seconds.trim()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * BENCH_TIME: the argument handed to {@code /time set} at the bench
     * viewpoint. Vanilla accepts either a literal ("day", "night") or a tick
     * count, so both work here. Defaults to "day".
     */
    @Unique
    private static String vulkanmod$benchTimeCommand() {
        final String t = VulkanModConfig.get("BENCH_TIME");
        if (t == null || t.isBlank()) {
            return "time set day";
        }
        return "time set " + t.trim();
    }

    @Unique
    private static String vulkanmod$shotTag() {
        String tag = VulkanModConfig.get("SHOT_TAG");
        if (tag == null || tag.isBlank()) {
            return "shot";
        }
        return tag.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Captures the previous frame's swapchain image into screenshots/.
     *
     * Runs at frame start, outside any render pass. VkGlFramebuffer falls back
     * to the swapchain image the previous frame rendered into. Vanilla's
     * ScreenShotHelper would do the same job, but in 1.12.2 the field it
     * wants (mcDataDir) does not exist and its filename is timestamped, so a
     * harness cannot predict the path.
     */
    @Unique
    private static void takeScreenshot(Minecraft mc) {
        final int width = mc.displayWidth;
        final int height = mc.displayHeight;

        if (width <= 0 || height <= 0) {
            VKProf.warn("[VKPROF] screenshot skipped, zero-sized framebuffer");
            return;
        }

        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);

        try {
            VkGlFramebuffer.readPixels(0, 0, width, height, pixels);

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

            long sum = 0L;
            for (int y = 0; y < height; ++y) {
                // glReadPixels returns rows bottom-up; a PNG is top-down.
                final int srcRow = (height - 1 - y) * width * 4;

                for (int x = 0; x < width; ++x) {
                    final int i = srcRow + x * 4;
                    final int r = pixels.get(i) & 0xFF;
                    final int g = pixels.get(i + 1) & 0xFF;
                    final int b = pixels.get(i + 2) & 0xFF;
                    image.setRGB(x, y, (r << 16) | (g << 8) | b);
                    sum += r + g + b;
                }
            }

            File dir = new File(System.getProperty("user.dir", "."), "screenshots");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }

            File out = new File(dir, "vulkanmod-" + SHOT_TAG + ".png");
            ImageIO.write(image, "PNG", out);

            VKProf.info("[VKPROF] screenshot {}x{} meanRGB={} -> {}",
                    width, height, String.format("%.1f", sum / (double) (width * height * 3)),
                    out.getAbsolutePath());
        } catch (Throwable t) {
            VulkanMod.LOGGER.error("[VKPROF] screenshot failed", t);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    /**
     * Bench mode: never pause. In an unattended runClient the window has no
     * focus, so the "Loading terrain"/pause flow can pause the integrated
     * server, which then never sends the spawn chunks, which never closes the
     * loading screen -- a deadlock that freezes the benchmark at 25 menu draws.
     * With this override the server always ticks and the world always loads.
     */
    @Inject(method = "isGamePaused", at = @At("HEAD"), cancellable = true)
    public void benchNoPause(CallbackInfoReturnable<Boolean> cir) {
        if (BENCH_MODE || COMPAT_MODE || FIXTEST || MINITEST) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "createDisplay", at = @At("HEAD"))
    public void preCreateDisplay(CallbackInfo ci){
        // Create the hidden GL context BEFORE lwjglx's Display.create() runs, so
        // the real GL.createCapabilities() finds a current context.
        Vulkan.ensureHiddenGLContext();
    }

    @Inject(method = "createDisplay", at = @At(value = "RETURN"))
    public void inject(CallbackInfo ci){

        // lwjglx's Display.create() may clear the current GL context (the visible
        // window is Vulkan-only); restore the hidden GL context on this thread.
        Vulkan.ensureHiddenGLContext();

        // lwjglx's Display.create() ran GL.createCapabilities() against the
        // hidden context (see preCreateDisplay), so the main thread now owns
        // the only GLCapabilities instance that will ever exist. Stash them:
        // the splash thread has no GL context, and loading-screen mods
        // (CustomLoadingScreen) call GLContext.getCapabilities() from there -
        // lwjglx would otherwise build its ContextCapabilities singleton on
        // that thread and die with an ExceptionInInitializerError.
        Vulkan.stashHiddenContextCaps();

        Vulkan.initVulkan(Display.getWindow());
        VulkanMod.LOGGER.info("Mixin succeed!" + Display.getWindow());
        Renderer.getInstance().beginFrame();
    }

    @Inject(method = "init", at = @At("RETURN"))
    public void endInitCmd(CallbackInfo callbackInfo) {
        Renderer.getInstance().endFrame();
    }

    @Inject(method = "shutdownMinecraftApplet", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;destroy()V", shift = At.Shift.BEFORE))
    public void cleanup(CallbackInfo ci) {

        Vulkan.cleanUp();
    }

    @Inject(method = "runGameLoop", at = @At("HEAD"))
    public void beginRendering(CallbackInfo ci) {
        Vulkan.ensureHiddenGLContext();

        Minecraft mc = Minecraft.getMinecraft();

        // runGameLoop sets profilingEnabled back to false near its end on every
        // frame unless the F3 profiler chart is on screen, so it has to be
        // re-armed here - the one point in the frame guaranteed to run before
        // the renderer.
        if (PROFILE_DUMP) {
            mc.profiler.profilingEnabled = true;
        }

        // SHOT without AUTOJOIN: stay on the main menu and capture it.
        //
        // A world screenshot has no text on screen (no chat, no F3, the hotbar
        // is icon-only), so the GUI font path - the one that renders glyph quads
        // - is never exercised by the bench. The main menu is wall-to-wall text:
        // every button label and the splash text go through the same
        // FontRenderer -> Tessellator -> Drawer path, so one frame of it answers
        // "is the text broken, and how" without anyone having to look at a
        // screen. AUTOQUIT makes it exit on its own; without it the shot is
        // still written and the process is left running for the harness to kill.
        if (SHOT_SECONDS > 0 && !BENCH_MODE) {
            if (autoQuitStart == 0) {
                autoQuitStart = System.currentTimeMillis();
            } else if (!screenshotFired
                    && System.currentTimeMillis() - autoQuitStart > SHOT_SECONDS * 1000L) {
                screenshotFired = true;
                takeScreenshot(mc);
                if (VulkanModConfig.getBool("AUTOQUIT", false)) {
                    VKProf.info("[VKPROF] menu shot taken, quitting");
                    mc.shutdown();
                }
            }
        }

        if (!autoQuitFired && VulkanModConfig.getBool("AUTOQUIT", false)
                && mc.getIntegratedServer() != null) {
            // Teleport to the fixed benchmark viewpoint (the forest scene from
            // the user's day/day2 RenderDoc captures) so A/B runs compare the
            // same camera. Wait until the "Loading terrain" screen is gone --
            // teleporting during it races the server's initial spawn placement
            // and can hang the loading screen forever. Bench mode only.
            if (!autoTeleported && mc.player != null && mc.currentScreen == null) {
                autoTeleported = true;
                final boolean shotMode = SHOT_SECONDS > 0;
                if (shotMode) {
                    // Near world spawn where passive mobs are, midday, looking
                    // down a touch so the captured frame contains cows/sheep
                    // close to the camera plus lit grass/stone.
                    //
                    // Pass 21: this is the branch EVERY bench run takes, because
                    // run_once.sh/ab_flag.sh always set SHOT. The
                    // "benchmark viewpoint" below is the fallback, not the
                    // default - a fact worth knowing before reasoning about
                    // "the bench scene" from the wrong coordinates. The override
                    // applies here too, so a run can be aimed at a scene this
                    // one does not contain.
                    final double[] vp = vulkanmod$benchViewpoint(0.5, 72.0, 0.5, 0.0f, 25.0f);
                    mc.player.setLocationAndAngles(vp[0], vp[1], vp[2], (float) vp[3], (float) vp[4]);
                    mc.player.setPositionAndUpdate(vp[0], vp[1], vp[2]);
                    pinnedViewpoint = vp;
                    // TEMPORARY: THIRDPERSON=1 switches to the back view
                    // so the player model itself is on screen. The user reports
                    // that every entity's texture lands on the player, which is
                    // only visible from outside the player.
                    if (VulkanModConfig.getBool("THIRDPERSON", false)) {
                        mc.gameSettings.thirdPersonView = 1;
                        VKProf.info("[VKPROF] third-person view enabled");
                    }
                    // In singleplayer the integrated server owns world time
                    // and overrides any client-side setWorldTime. Run /time
                    // set day through the server command manager so the sky,
                    // the lightmap and the entity lighting all pick up
                    // daytime.
                    if (mc.getIntegratedServer() != null) {
                        // Sender is the server, not the player: an EntityPlayer's
                        // getServer() is null on the client side, so
                        // sendCommandFeedback throws and the command is dropped
                        // ("Couldn't process command: time set day"). Queued onto
                        // the server thread for the same reason as freezeScene.
                        final MinecraftServer server = mc.getIntegratedServer();
                        // BENCH_TIME overrides the hardcoded daytime so a run
                        // can be aimed at night (cloud/sky/lightmap bugs are
                        // only visible after dark). Defaults to "day" so every
                        // earlier capture keeps its meaning.
                        final String timeCmd = vulkanmod$benchTimeCommand();
                        server.addScheduledTask(() -> {
                            try {
                                final int tr = server.getCommandManager().executeCommand(server, timeCmd);
                                com.yuhan123.vulkanmod.VulkanMod.LOGGER.info(
                                        "[VKPROF] BENCHTIME cmd='{}' result={} serverWorldTime={}",
                                        timeCmd, tr, server.worlds[0].getWorldTime());
                            } catch (Throwable t) {
                                com.yuhan123.vulkanmod.VulkanMod.LOGGER.error(
                                        "[VKPROF] BENCHTIME cmd='{}' FAILED", timeCmd, t);
                            }
                        });
                    } else {
                        mc.world.setWorldTime(6000L);
                    }
                    VKProf.info("[VKPROF] teleported to screenshot viewpoint ({}, {}, {} yaw={} pitch={})",
                            vp[0], vp[1], vp[2], vp[3], vp[4]);
                } else {
                    // Fixed position AND view angles: the rotation otherwise
                    // comes from whatever the save captured at exit, which
                    // made every benchmark run measure a different scene.
                    // Pass 21: overridable, and logged, so a run that aims at a
                    // different scene says so instead of leaving it to be inferred.
                    final double[] vp = vulkanmod$benchViewpoint(320.5, 67.0, -327.5, 180.0, 15.0);
                    mc.player.setLocationAndAngles(vp[0], vp[1], vp[2], (float) vp[3], (float) vp[4]);
                    mc.player.setPositionAndUpdate(vp[0], vp[1], vp[2]);
                    pinnedViewpoint = vp;
                    VKProf.info("[VKPROF] teleported to benchmark viewpoint ({}, {}, {} yaw={} pitch={})",
                            vp[0], vp[1], vp[2], vp[3], vp[4]);
                }
            }

            // Pin the scene once, right after the teleport, so both cases of an
            // A/B measure the same world state. See FREEZE_SCENE.
            //
            // Wait for the player to be back on the ground first. The teleport
            // drops the player in from above, and the freeze anchors the mob set
            // to the player's position - so freezing on the teleport frame
            // summons every mob at the teleport height, several blocks above the
            // terrain and entirely out of frame. That failure is silent: every
            // command reports success and the entity draw load the bench exists
            // to reproduce is simply absent, which reads as "the change does
            // nothing" rather than "the bench is empty".
            if (FREEZE_SCENE && autoTeleported && !sceneFrozen && mc.player != null
                    && mc.player.onGround && mc.getIntegratedServer() != null) {
                sceneFrozen = true;
                vulkanmod$freezeScene(mc);
            }

            // Pass 21: optional scene injection, once the player has landed.
            if (!benchChestPlaced && autoTeleported && mc.player != null
                    && mc.player.onGround && mc.getIntegratedServer() != null) {
                benchChestPlaced = true;
                vulkanmod$injectBenchScene(mc);
            }

            // Scope the vanilla profiler dump to the steady state - see
            // PROFILE_WARMUP_MILLIS for why the old run average mis-ranked the
            // sections. Clearing at a frame boundary with an empty section stack
            // resets exactly the accumulators getProfilingData reads.
            if (PROFILE_DUMP && !profileWindowStarted && autoQuitStart != 0
                    && System.currentTimeMillis() - autoQuitStart >= PROFILE_WARMUP_MILLIS) {
                profileWindowStarted = true;
                mc.profiler.clearProfiling();
                VKProf.info("[VKPROF] profiler accumulators cleared after {}s warm-up - the dump below covers the steady state only",
                        PROFILE_WARMUP_MILLIS / 1000L);
            }

            if (autoQuitStart == 0) {
                autoQuitStart = System.currentTimeMillis();
            } else {
                // Bench frame counter. FPS is render frames over wall time and
                // nothing in the harness reports it - VKProf.info is a no-op
                // body, so the number has to go through VulkanMod.LOGGER.
                benchFrames++;
                // See PIN_CAMERA: hold the viewpoint still so the shot is a
                // function of the render path and nothing else.
                if (PIN_CAMERA && !screenshotFired && pinnedViewpoint != null && mc.player != null) {
                    mc.player.setLocationAndAngles(pinnedViewpoint[0], pinnedViewpoint[1],
                            pinnedViewpoint[2], (float) pinnedViewpoint[3], (float) pinnedViewpoint[4]);
                    mc.player.setPositionAndUpdate(pinnedViewpoint[0], pinnedViewpoint[1],
                            pinnedViewpoint[2]);
                }

                // Hold the CLIENT world time too - see FORCE_CLIENT_TIME. The
                // server-side /time set alone leaves the client at noon.
                if (FORCE_CLIENT_TIME >= 0 && mc.world != null && !screenshotFired) {
                    mc.world.setWorldTime(FORCE_CLIENT_TIME);
                }

                if (SHOT_SECONDS > 0 && !screenshotFired
                        && System.currentTimeMillis() - autoQuitStart > SHOT_SECONDS * 1000L) {
                    screenshotFired = true;
                    if (mc.player != null) {
                        // Self-report the exact camera the pixels were taken
                        // from: two runs whose lines differ are not comparable,
                        // and that is otherwise invisible in the PNGs.
                        VKProf.info("[VKPROF] shot camera pos=({}, {}, {}) yaw={} pitch={} onGround={}",
                                String.format("%.3f", mc.player.posX),
                                String.format("%.3f", mc.player.posY),
                                String.format("%.3f", mc.player.posZ),
                                String.format("%.3f", mc.player.rotationYaw),
                                String.format("%.3f", mc.player.rotationPitch),
                                mc.player.onGround);
                    }
                    takeScreenshot(mc);
                }

                if (System.currentTimeMillis() - autoQuitStart > BENCH_MILLIS) {
                    autoQuitFired = true;
                    if (PROFILE_DUMP) {
                        dumpProfiler(mc);
                    }
                    final long benchMs = System.currentTimeMillis() - autoQuitStart;
                    final double fps = benchFrames * 1000.0 / Math.max(1L, benchMs);
                    com.yuhan123.vulkanmod.VulkanMod.LOGGER.info(
                            "[VKPROF] BENCH fps={} frames={} ms={} (TERRAIN_BATCH={} REGION={} AREA={})",
                            String.format("%.1f", fps), Long.valueOf(benchFrames), Long.valueOf(benchMs),
                            Boolean.valueOf(VulkanModConfig.getBool("TERRAIN_BATCH", false)),
                            Boolean.valueOf(VulkanModConfig.getBool("TERRAIN_REGION", false)),
                            Boolean.valueOf(VulkanModConfig.getBool("TERRAIN_AREA", false)));
                    VKProf.info("[VKPROF] auto-quit after {}s in world", BENCH_MILLIS / 1000L);
                    mc.shutdown();
                }
            }
        }

        // Reset the per-frame vertex/index/UBO buffers BEFORE recording the
        // frame's draws. Without this the Drawer's usedBytes accumulates across
        // frames, so the buffers double in size every frame until the GPU runs
        // out of memory (VK_ERROR_OUT_OF_DEVICE_MEMORY) and the game stalls.
        // Compatibility-test sequence: drive the game alongside other mods and
        // capture a series of screenshots proving vulcraft renders them.
        if (COMPAT_MODE || FIXTEST || MINITEST) {
            vulkanmod$compatTick(mc);
        }

        Renderer.getInstance().preInitFrame();

        Renderer.getInstance().beginFrame();
    }

    @Inject(method = "runGameLoop", at = @At("RETURN"))
    public void endRendering(CallbackInfo ci) {
        Renderer.getInstance().endFrame();
    }

    @Inject(method = "runTick", at = @At("HEAD"))
    public void benchTickStart(CallbackInfo ci) {
        FrameProfiler.beginGameTick();
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    public void benchTickEnd(CallbackInfo ci) {
        FrameProfiler.endGameTick();
    }

    /**
     * Compatibility-test step sequence. Each step fires once, timed from when the
     * player first enters the world. The screenshots are written to
     * screenshots/vulkanmod-<tag>.png and analysed off-line (the model cannot
     * view them) for: JEI overlay present, inventory GUI rendered, items visible
     * in the hotbar, and the End dimension rendered after a dimension switch.
     */
    @Unique
    private static void vulkanmod$compatTick(Minecraft mc) {
        // Only block while we are NOT in a world yet (player/server null). Do NOT
        // block on currentScreen != null: step 3 deliberately opens the inventory
        // GUI, and returning early there would freeze the whole sequence (the
        // inventory-open flag is set but the later timed steps never fire).
        if (mc.player == null || mc.getIntegratedServer() == null) {
            return;
        }
        if (compatStart == 0) {
            compatStart = System.currentTimeMillis();
            VulkanMod.LOGGER.info("[VKPROF] compat: entered world, starting sequence");
        }
        if (!compatTfResolved) {
            compatTfResolved = true;
            compatTfEnabled = Loader.isModLoaded("twilightforest");
            VulkanMod.LOGGER.info("[VKPROF] compat: twilightforest loaded = {}", compatTfEnabled);
            compatJmEnabled = Loader.isModLoaded("journeymap");
            VulkanMod.LOGGER.info("[VKPROF] compat: journeymap loaded = {}", compatJmEnabled);
            compatBotaniaEnabled = BOTANIA_TEST && Loader.isModLoaded("botania");
            VulkanMod.LOGGER.info("[VKPROF] compat: botania item-test mode = {}", compatBotaniaEnabled);
        }
        if (!compatModsLogged) {
            compatModsLogged = true;
            try {
                final StringBuilder sb = new StringBuilder();
                for (ModContainer m : Loader.instance().getActiveModList()) {
                    sb.append(m.getModId()).append(':').append(m.getVersion()).append(' ');
                }
                VulkanMod.LOGGER.info("[VKPROF] compat: loaded mods ({}): {}",
                        Loader.instance().getActiveModList().size(), sb.toString().trim());
            } catch (Throwable t) {
                VulkanMod.LOGGER.warn("[VKPROF] compat: could not enumerate mods", t);
            }
        }
        final long dt = System.currentTimeMillis() - compatStart;

        // Botania item-effect test takes over the whole sequence when enabled.
        if (compatBotaniaEnabled) {
            vulkanmod$botaniaTick(mc, dt);
            return;
        }

        // Tech-mod machine-GUI test takes over when enabled (and not botania).
        if (TECHGUI_TEST) {
            vulkanmod$techGuiTick(mc, dt);
            return;
        }

        // Render-fix regression test (FIXTEST=1, needs AUTOJOIN=1): F3 direction
        // axes + an end portal block on a stage, one screenshot each.
        if (FIXTEST) {
            vulcraft$fixTick(mc, dt);
            return;
        }

        // Window-minimise regression test (MINITEST=1, needs AUTOJOIN=1).
        if (MINITEST) {
            vulcraft$miniTick(mc, dt);
            return;
        }

        // Step 1: world + JEI overlay (JEI draws its item list at the screen edge).
        if (!compatWorldShot && dt > 4000) {
            compatWorldShot = true;
            vulkanmod$captureTo(mc, "compat_world");
        }

        // Step 2: give a spread of items, then screenshot the hotbar (item rendering).
        if (!compatGive && dt > 8000) {
            compatGive = true;
            vulkanmod$giveCompatItems(mc);
        }
        if (!compatItemsShot && compatGive && dt > 10000) {
            compatItemsShot = true;
            vulkanmod$captureTo(mc, "compat_items");
        }

        // Step 3: force-open the inventory (JEI overlay + inventory GUI rendering).
        if (!compatInvOpen && dt > 12000) {
            compatInvOpen = true;
            mc.displayGuiScreen(new GuiInventory(mc.player));
        }
        if (!compatInvShot && compatInvOpen && dt > 14000) {
            compatInvShot = true;
            vulkanmod$captureTo(mc, "compat_inv");
        }

        // Step 3b: JourneyMap fullscreen map (only when journeymap is loaded).
        // JM 6.x dispatches keybinds via KeybindingHandler.consume() ->
        // while (keyBinding.isPressed()) wrapper.action().run(); invoke the
        // fullscreen-toggle action directly. JM is runtime-only here
        // (modRuntimeOnly), so this must be pure reflection.
        if (compatJmEnabled) {
            if (!compatJmInvClosed && dt > 15000) {
                compatJmInvClosed = true;
                if (mc.currentScreen != null) {
                    mc.displayGuiScreen(null);
                }
            }
            if (!compatJmOpen && compatJmInvClosed && dt > 15600) {
                compatJmOpen = true;
                boolean opened = vulkanmod$openJourneyMapFullscreen(mc);
                VulkanMod.LOGGER.info("[VKPROF] compat: journeymap fullscreen map opened={} screen={}",
                        opened, mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getName());
            }
            if (!compatJmShot && compatJmOpen && dt > 21000) {
                compatJmShot = true;
                vulkanmod$captureTo(mc, "compat_jm");
            }
            if (!compatJmClosed && compatJmShot && dt > 21600) {
                compatJmClosed = true;
                if (mc.currentScreen != null) {
                    mc.displayGuiScreen(null);
                }
            }
        }

        // Step 4: enter the Twilight Forest dimension through a real TF portal.
        // Build a 2x2 water pool with a grass rim + flowers at the player's
        // feet, stand the player in it, drop a diamond so TF's own
        // checkForPortalCreation (fired every player tick) forms the portal and
        // teleports the player. If the portal has not formed within the poll
        // window, fall back to a direct changeDimension(7) (TF's default id).
        // Only when Twilight Forest is actually loaded (generic smoke test otherwise).
        if (compatTfEnabled && !compatDim && dt > 16000) {
            compatDim = true;
            if (mc.currentScreen != null) {
                mc.displayGuiScreen(null);
            }
            compatTfBuildMs = System.currentTimeMillis();
            vulkanmod$buildTFPortal(mc);
        }
        if (compatDim && !compatTfShot) {
            final long ttf = System.currentTimeMillis() - compatTfBuildMs;
            final boolean inTf = vulkanmod$isInTwilightForest(mc);
            if (inTf) {
                if (compatTfArriveMs == 0) {
                    compatTfArriveMs = System.currentTimeMillis();
                    VulkanMod.LOGGER.info("[VKPROF] compat: entered Twilight Forest via {}",
                            compatTfFallback ? "direct changeDimension fallback" : "auto portal");
                }
                if (System.currentTimeMillis() - compatTfArriveMs > 3000L) {
                    compatTfShot = true;
                    vulkanmod$captureTo(mc, "compat_tf");
                }
            } else if (ttf > 12000L && !compatTfFallback) {
                compatTfFallback = true;
                compatTfFallbackMs = System.currentTimeMillis();
                VulkanMod.LOGGER.warn("[VKPROF] compat: auto TF portal did not form in 12s, falling back to changeDimension(7)");
                vulkanmod$changeDimensionTf(mc);
            }
        }

        // Step 5: done. Quit earlier when there is no TF portal step to wait for.
        final long compatQuitMs = compatBotaniaEnabled ? 22000L
                : (compatTfEnabled ? 34000L : (compatJmEnabled ? 24000L : 18000L));
        if (!compatQuit && dt > compatQuitMs) {
            compatQuit = true;
            VulkanMod.LOGGER.info("[VKPROF] compat: sequence complete, quitting");
            mc.shutdown();
        }
    }

    /**
     * Opens JourneyMap's fullscreen map directly. JM classes are runtime-only
     * (modRuntimeOnly), so this is pure reflection. Verified against
     * journeymap 1.12.2-6.0.10: UIManager.open(Fullscreen.class) goes through
     * Fullscreen.reset(), which pops the About splash dialog on top of the
     * map; constructing Fullscreen and displaying it directly skips the splash.
     */
    @Unique
    private static boolean vulkanmod$openJourneyMapFullscreen(Minecraft mc) {
        try {
            Class<?> fsClass = Class.forName("journeymap.client.ui.fullscreen.Fullscreen");
            Object screen = fsClass.getDeclaredConstructor().newInstance();
            if (!(screen instanceof net.minecraft.client.gui.GuiScreen)) {
                return false;
            }
            mc.displayGuiScreen((net.minecraft.client.gui.GuiScreen) screen);
            return true;
        } catch (Throwable t) {
            VulkanMod.LOGGER.warn("[VKPROF] compat: journeymap fullscreen map open failed", t);
            return false;
        }
    }

    /** Writes screenshots/vulkanmod-<tag>.png (same mechanism as takeScreenshot). */
    @Unique
    private static void vulkanmod$captureTo(Minecraft mc, String tag) {
        final int width = mc.displayWidth;
        final int height = mc.displayHeight;
        if (width <= 0 || height <= 0) {
            VKProf.warn("[VKPROF] compat capture '{}' skipped, zero-sized framebuffer", tag);
            return;
        }
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            VkGlFramebuffer.readPixels(0, 0, width, height, pixels);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            long sum = 0L;
            for (int y = 0; y < height; ++y) {
                final int srcRow = (height - 1 - y) * width * 4;
                for (int x = 0; x < width; ++x) {
                    final int i = srcRow + x * 4;
                    final int r = pixels.get(i) & 0xFF;
                    final int g = pixels.get(i + 1) & 0xFF;
                    final int b = pixels.get(i + 2) & 0xFF;
                    image.setRGB(x, y, (r << 16) | (g << 8) | b);
                    sum += r + g + b;
                }
            }
            File dir = new File(System.getProperty("user.dir", "."), "screenshots");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            File out = new File(dir, "vulkanmod-" + tag + ".png");
            ImageIO.write(image, "PNG", out);
            VKProf.info("[VKPROF] compat capture '{}' {}x{} meanRGB={} -> {}",
                    tag, width, height,
                    String.format("%.1f", sum / (double) (width * height * 3)),
                    out.getAbsolutePath());
        } catch (Throwable t) {
            VulkanMod.LOGGER.error("[VKPROF] compat capture '{}' failed", tag, t);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    /**
     * Botania item-effect test sequence (BOTANIA_TEST=1). Timeline from world
     * entry: build a floating arena -> give items -> hotbar shot -> fire the
     * Mana Blaster x3 through the vanilla input path (KeyBinding.onTick ->
     * processKeyBinds -> rightClickMouse) -> burst shots -> /setblock functional
     * flowers + mana spreader on the arena -> flowers shot -> hold the Mana
     * Tablet -> mana-bar HUD shot. Quit at 22s (see compatQuitMs).
     *
     * <p>Why the arena: the AUTOJOIN teleport target is (0.5, 72, 0.5), which
     * sits INSIDE a stone structure in the fixed-seed world - the wall fills
     * the right half of the frame and the unlit interior the rest, so every
     * capture from there is a dark stone wall with no terrain and no
     * difference between shots. The arena is a grass platform 28 blocks above
     * the structure: open sky above, the generated spawn terrain (already
     * loaded, renderDistance 12) below and around it, and the flowers have
     * valid grass support (BlockBush.canSustainBush accepts only
     * grass/dirt/farmland - a stone pad makes them pop).
     */
    @Unique
    private static void vulkanmod$botaniaTick(Minecraft mc, long dt) {
        // Aim at the horizon facing +Z (yaw 0) so the burst and the flowers
        // placed in front of the player are actually inside the camera frustum.
        if (!botAimed) {
            botAimed = true;
            mc.player.rotationYaw = 0.0F;
            mc.player.rotationPitch = 0.0F;
        }

        // Arena: carve air y=100..112 and lay grass at y=99 over +-14 blocks
        // around the spawn column. Queued on the server thread - the teleport
        // below runs a full second later so the platform exists before the
        // player is moved onto it (falling 28 blocks into the structure would
        // kill them).
        if (!botArenaBuilt && dt > 500) {
            botArenaBuilt = true;
            vulkanmod$runServerCommands(mc, new String[]{
                    "fill -14 100 -14 14 112 14 minecraft:air",
                    "fill -14 99 -14 14 99 14 minecraft:grass",
            });
            VulkanMod.LOGGER.info("[VKPROF] botania: arena commands queued (grass at y=99)");
        }
        // Teleport. THE SERVER PLAYER, not just the client: a client-only
        // setPositionAndUpdate leaves the server-side EntityPlayerMP at the
        // world spawn (-192, 64, 96 in this seed - an ocean), 215 blocks from
        // the arena. Everything server-authoritative then happens at the spawn:
        // the Mana Blaster's EntityManaBurst spawns at the SERVER player's eye
        // (EntityManaBurst(EntityPlayer, EnumHand) ctor -> player.posY +
        // getEyeHeight), so the projectile flew over the ocean 215 blocks away
        // and never entered a single capture, and block updates for the arena
        // were queued into PlayerChunkMap entries at the very edge of the
        // server view distance (12 chunks) and partly dropped - which is why
        // the client was missing the whole east half of the arena floor
        // (x>=0) and both functional flowers.
        //
        // setPlayerLocation is the server-authoritative teleport: it moves the
        // EntityPlayerMP AND sends SPacketPlayerPosLook, which drags the client
        // player along with it. On the arena the server player stands on solid
        // grass, so the client's fall-position packets can no longer push it
        // off the edge and kill it (fall damage -> respawn at the spawn point
        // was the loop that kept re-scattering the server player).
        if (botArenaBuilt && !botServerTp && dt > 1200) {
            botServerTp = true;
            final net.minecraft.server.MinecraftServer server0 = mc.getIntegratedServer();
            if (server0 != null) {
                server0.addScheduledTask(() -> {
                    try {
                        final java.util.List<net.minecraft.entity.player.EntityPlayerMP> players =
                                server0.getPlayerList().getPlayers();
                        if (!players.isEmpty()) {
                            final net.minecraft.entity.player.EntityPlayerMP mp = players.get(0);
                            VulkanMod.LOGGER.info(
                                    "[VKPROF] botania: server player BEFORE tp = ({}, {}, {})",
                                    String.format(java.util.Locale.ROOT, "%.1f", mp.posX),
                                    String.format(java.util.Locale.ROOT, "%.1f", mp.posY),
                                    String.format(java.util.Locale.ROOT, "%.1f", mp.posZ));
                            mp.connection.setPlayerLocation(0.5, 100.0, 0.5, 0.0F, 0.0F);
                            VulkanMod.LOGGER.info(
                                    "[VKPROF] botania: server player AFTER tp = ({}, {}, {})",
                                    String.format(java.util.Locale.ROOT, "%.1f", mp.posX),
                                    String.format(java.util.Locale.ROOT, "%.1f", mp.posY),
                                    String.format(java.util.Locale.ROOT, "%.1f", mp.posZ));
                        }
                    } catch (Throwable t) {
                        VulkanMod.LOGGER.warn("[VKPROF] botania: server teleport FAILED", t);
                    }
                });
            }
            // Client-side too, so the camera moves on this very frame.
            mc.player.setLocationAndAngles(0.5, 100.0, 0.5, 0.0F, 0.0F);
            mc.player.setPositionAndUpdate(0.5, 100.0, 0.5);
            VulkanMod.LOGGER.info("[VKPROF] botania: server-authoritative teleport queued (0.5,100,0.5)");
        }
        // Force the arena floor back onto the client. The original fill's block
        // updates for the chunks at the edge of the server view distance never
        // all arrived, so the client is missing x>=0 of the grass layer (135+
        // blocks, exactly where the player stands and where the flowers go).
        // Re-setting the SAME block is a no-op (setblock/fill return 0 and send
        // no packet), so the floor is toggled grass -> dirt -> grass: every
        // position then differs from the server state and a real
        // SPacketBlockChange goes out. The flowers are not placed yet, and
        // dirt supports bushes, so nothing pops.
        if (botServerTp && !botRefreshed && dt > 1800) {
            botRefreshed = true;
            vulkanmod$runServerCommands(mc, new String[]{
                    "fill -14 99 -14 14 99 14 minecraft:dirt",
                    "fill -14 99 -14 14 99 14 minecraft:grass",
            });
            VulkanMod.LOGGER.info("[VKPROF] botania: arena floor force-refresh queued (dirt->grass)");
        }

        if (!botGave && dt > 2000) {
            botGave = true;
            vulkanmod$runServerCommands(mc, new String[]{
                    "give @p botania:manaGun",
                    "give @p botania:manaTablet 1 0 {mana:1000000}",
                    "give @p botania:lens 1 0",
                    "give @p botania:manasteelSword",
                    "give @p botania:manasteelPick",
                    "give @p botania:manaResource 8 0",
                    "give @p botania:manaRing",
                    "give @p botania:dirtRod",
                    "give @p botania:terraformRod",
                    "give @p botania:petal 16 0",
                    // functional flowers are ONE block (specialFlower) with the
                    // type in NBT - placing them as items goes through the real
                    // onItemUse path (setblock cannot write the NBT type).
                    "give @p botania:specialFlower 1 0 {type:\"puredaisy\"}",
                    "give @p botania:specialFlower 1 0 {type:\"endoflame\"}",
                    "give @p botania:spreader",
            });
            VulkanMod.LOGGER.info("[VKPROF] botania: test items given");
        }
        if (!botItemsShot && dt > 4500) {
            botItemsShot = true;
            vulkanmod$captureTo(mc, "compat_bot_items");
        }

        // Hold the Mana Blaster (pulls mana from the full tablet in the
        // inventory) and right-click three times, 500 ms apart.
        if (!botGunHeld && dt > 5000) {
            botGunHeld = true;
            final boolean held = vulkanmod$holdItem(mc, "botania:manaGun");
            VulkanMod.LOGGER.info("[VKPROF] botania: mana blaster held={}", held);
        }
        if (botGunHeld && !botFired1 && dt > 5200) {
            botFired1 = true;
            KeyBinding.onTick(mc.gameSettings.keyBindUseItem.getKeyCode());
        }
        if (botFired1 && !botFired2 && dt > 5700) {
            botFired2 = true;
            KeyBinding.onTick(mc.gameSettings.keyBindUseItem.getKeyCode());
        }
        if (botFired2 && !botFired3 && dt > 6200) {
            botFired3 = true;
            KeyBinding.onTick(mc.gameSettings.keyBindUseItem.getKeyCode());
            // Proof of firing: the gun drains the inventory tablet's mana.
            for (int i = 0; i < mc.player.inventory.mainInventory.size(); i++) {
                final net.minecraft.item.ItemStack st = mc.player.inventory.mainInventory.get(i);
                if (!st.isEmpty() && "botania:manatablet".equalsIgnoreCase(
                        net.minecraft.item.Item.REGISTRY.getNameForObject(st.getItem()).toString())) {
                    VulkanMod.LOGGER.info("[VKPROF] botania: tablet mana after 3 shots = {}",
                            st.hasTagCompound() ? st.getTagCompound().getInteger("mana") : -1);
                }
            }
        }
        // Track the mana-burst entity itself: the projectile never shows up in
        // the captures, so log the client entity list at several delays to see
        // whether it exists client-side (render issue) or is already gone.
        if (botGunHeld && !botEntLog1 && dt > 5250) {
            botEntLog1 = true;
            vulkanmod$logEntities(mc, "t+50ms");
        }
        if (botEntLog1 && !botEntLog2 && dt > 5350) {
            botEntLog2 = true;
            vulkanmod$logEntities(mc, "t+150ms");
        }
        if (botEntLog2 && !botEntLog3 && dt > 5500) {
            botEntLog3 = true;
            vulkanmod$logEntities(mc, "t+300ms");
        }
        if (botEntLog3 && !botEntLog4 && dt > 6200) {
            botEntLog4 = true;
            vulkanmod$logEntities(mc, "t+1000ms");
        }
        // Burst shot ~400 ms after the first shot: at t+100 ms the burst is
        // still ~0.2 blocks from the eye (inside the near plane); at t+400 ms
        // it is a couple of blocks out, big and unmistakable, still over the
        // platform (it only moves ~0.2 blocks/tick).
        if (!botBurstShot && dt > 5600) {
            botBurstShot = true;
            vulkanmod$captureTo(mc, "compat_bot_burst");
        }
        if (!botBurstShot2 && dt > 7000) {
            botBurstShot2 = true;
            vulkanmod$captureTo(mc, "compat_bot_burst2");
        }

        // Early desync dump: BEFORE any flower is placed. If the grass hole is
        // already here it came from the arena fill/teleport, not the flowers.
        if (!botEarlyDump && dt > 7000) {
            botEarlyDump = true;
            vulkanmod$desyncDump(mc, "pre-flower");
        }

        // Functional flowers + a mana spreader on the arena, 4 blocks in front
        // of the player (far enough that the camera at pitch 20 sees them
        // against the terrain beyond the platform edge). The functional
        // flowers are ONE block (specialFlower) with the subtile name in the TE
        // NBT - setblock creates the block, blockdata writes subTileName
        // (readPacketNBT -> provideSubTile instantiates it). The arena floor is
        // grass, so canSustainBush is satisfied and the flowers survive; the
        // blockdata runs in a separate tick from the setblock (the TE is not
        // always visible to the command in the same tick). Tests block models,
        // the spreader's animated rotation, the Pure Daisy's sparkle particles,
        // Endoflame's flame.
        if (!botAimDown && dt > 7500) {
            botAimDown = true;
            final int px = MathHelper.floor(mc.player.posX);
            final int py = MathHelper.floor(mc.player.posY);
            final int pz = MathHelper.floor(mc.player.posZ);
            botFx = px;
            botFy = py;
            botFz = pz + 4;
            VulkanMod.LOGGER.info("[VKPROF] botania: player at {},{},{} -> flower row at {},{},{}",
                    px, py, pz, botFx, botFy, botFz);
            vulkanmod$runServerCommands(mc, new String[]{
                    "setblock " + (botFx - 1) + " " + botFy + " " + botFz + " botania:spreader",
            });
            mc.player.rotationPitch = 20.0F;
            mc.player.rotationYaw = 0.0F;
        }
        if (botAimDown && !botPlaced1 && dt > 8200) {
            botPlaced1 = true;
            vulkanmod$runServerCommands(mc, new String[]{
                    "setblock " + botFx + " " + botFy + " " + botFz + " botania:specialFlower",
                    "setblock " + (botFx + 1) + " " + botFy + " " + botFz + " botania:specialFlower",
            });
        }
        if (botPlaced1 && !botPlaced2 && dt > 8800) {
            botPlaced2 = true;
            vulkanmod$runServerCommands(mc, new String[]{
                    "blockdata " + botFx + " " + botFy + " " + botFz + " {subTileName:\"puredaisy\"}",
                    "blockdata " + (botFx + 1) + " " + botFy + " " + botFz + " {subTileName:\"endoflame\"}",
            });
        }
        if (!botAimBack && dt > 10000) {
            botAimBack = true;
            vulkanmod$desyncDump(mc, "post-flower");
        }
        // Second force-refresh after the flowers are down: this one is the
        // proof run - if the client still shows air at the flower row here,
        // the updates are being dropped somewhere else (a second toggle sends
        // 1682 real block-change packets through the same path).
        if (botPlaced2 && !botRefreshed2 && dt > 10500) {
            botRefreshed2 = true;
            vulkanmod$runServerCommands(mc, new String[]{
                    "fill -14 99 -14 14 99 14 minecraft:dirt",
                    "fill -14 99 -14 14 99 14 minecraft:grass",
            });
        }
        if (botAimBack && !botDesyncRecheck && dt > 16000) {
            botDesyncRecheck = true;
            vulkanmod$desyncDump(mc, "final");
        }
        if (!botFlowersShot && dt > 11000) {
            botFlowersShot = true;
            vulkanmod$captureTo(mc, "compat_bot_flowers");
        }

        // Hold the Mana Tablet -> Botania's mana bar HUD overlay.
        if (!botTabletHeld && dt > 12000) {
            botTabletHeld = true;
            final boolean held = vulkanmod$holdItem(mc, "botania:manaTablet");
            VulkanMod.LOGGER.info("[VKPROF] botania: mana tablet held={}", held);
        }
        if (!botHudShot && dt > 14000) {
            botHudShot = true;
            vulkanmod$captureTo(mc, "compat_bot_hud");
        }
    }

    /**
     * Logs every loaded client entity (class + position) except the player.
     * Used to track the mana-burst projectile, which never appears in the
     * captures: if the entity is absent here it never existed client-side,
     * if it is present the problem is in the render path. Also logs the
     * SERVER player's position - the burst spawns at the server player's eye,
     * so if that is 200 blocks from the camera the projectile is simply
     * somewhere else entirely.
     */
    @Unique
    private static void vulkanmod$logEntities(Minecraft mc, String tag) {
        if (mc.world == null) {
            return;
        }
        String srvPos = "no-server";
        if (mc.getIntegratedServer() != null) {
            final java.util.List<net.minecraft.entity.player.EntityPlayerMP> players =
                    mc.getIntegratedServer().getPlayerList().getPlayers();
            if (!players.isEmpty()) {
                final net.minecraft.entity.Entity mp = players.get(0);
                srvPos = String.format(java.util.Locale.ROOT, "(%.1f,%.1f,%.1f)", mp.posX, mp.posY, mp.posZ);
            }
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mc.world.loadedEntityList.size(); i++) {
            final net.minecraft.entity.Entity ent = mc.world.loadedEntityList.get(i);
            if (ent == mc.player) {
                continue;
            }
            sb.append(ent.getClass().getSimpleName())
              .append(String.format(java.util.Locale.ROOT, "(%.1f,%.1f,%.1f)",
                      ent.posX, ent.posY, ent.posZ));
            // Class-NAME check, not a type reference: Botania is injected at
            // runtime only and is not on the compile classpath.
            if ("vazkii.botania.common.entity.EntityManaBurst".equals(ent.getClass().getName())) {
                sb.append(String.format(java.util.Locale.ROOT,
                        "[motion=(%.3f,%.3f,%.3f) age=%d dead=%s]",
                        ent.motionX, ent.motionY, ent.motionZ, ent.ticksExisted, ent.isDead));
            }
            sb.append(' ');
        }
        VulkanMod.LOGGER.info("[VKPROF] botania: entities@{} n={} clientPlayer=({},{},{}) serverPlayer={}: {}",
                tag, mc.world.loadedEntityList.size() - 1,
                String.format(java.util.Locale.ROOT, "%.1f", mc.player.posX),
                String.format(java.util.Locale.ROOT, "%.1f", mc.player.posY),
                String.format(java.util.Locale.ROOT, "%.1f", mc.player.posZ),
                srvPos, sb.toString().trim());
    }

    /**
     * Client-vs-server block-state diff over the flower neighbourhood. The
     * server keeps every block while the client is missing a 4x5 grass slab
     * plus the two flowers, so map the exact shape of the desync at several
     * points in the timeline.
     */
    @Unique
    private static void vulkanmod$desyncDump(Minecraft mc, String tag) {
        if (mc.world == null || mc.getIntegratedServer() == null) {
            return;
        }
        final net.minecraft.world.WorldServer srv = mc.getIntegratedServer().worlds[0];
        int diffs = 0;
        final StringBuilder sb = new StringBuilder();
        for (int y = 98; y <= 100; y++) {
            for (int x = -6; x <= 8; x++) {
                for (int z = -2; z <= 12; z++) {
                    final net.minecraft.util.math.BlockPos pos =
                            new net.minecraft.util.math.BlockPos(x, y, z);
                    final Object cli = net.minecraft.block.Block.REGISTRY.getNameForObject(
                            mc.world.getBlockState(pos).getBlock());
                    final Object srvB = net.minecraft.block.Block.REGISTRY.getNameForObject(
                            srv.getBlockState(pos).getBlock());
                    if (!String.valueOf(cli).equals(String.valueOf(srvB))) {
                        diffs++;
                        if (diffs <= 40) {
                            sb.append('[').append(x).append(',').append(y).append(',').append(z)
                              .append(" c=").append(cli).append(" s=").append(srvB).append("] ");
                        }
                    }
                }
            }
        }
        // Client-side TE state at the flower positions.
        final StringBuilder te = new StringBuilder();
        for (int dx = -1; dx <= 1; dx++) {
            final net.minecraft.tileentity.TileEntity t = mc.world.getTileEntity(
                    new net.minecraft.util.math.BlockPos(botFx + dx, botFy, botFz));
            te.append('(').append(dx).append(')').append(t == null ? "no-te" : t.getClass().getSimpleName())
              .append(' ');
        }
        VulkanMod.LOGGER.info("[VKPROF] botania: desync[{}] diffs={} {} TE:{} (clientTime={})",
                tag, diffs, sb.toString().trim(), te.toString().trim(),
                mc.world.getWorldTime());
    }

    /** Runs server commands on the integrated server thread with logging. */
    @Unique
    private static void vulkanmod$runServerCommands(Minecraft mc, String[] cmds) {        final MinecraftServer server = mc.getIntegratedServer();
        if (server == null) {
            return;
        }
        server.addScheduledTask(() -> {
            for (String c : cmds) {
                try {
                    final int r = server.getCommandManager().executeCommand(server, c);
                    VulkanMod.LOGGER.info("[VKPROF] botania: cmd '{}' -> {}", c, r);
                } catch (Throwable t) {
                    VulkanMod.LOGGER.warn("[VKPROF] botania: cmd '{}' failed", c, t);
                }
            }
        });
    }

    /**
     * Resolves a tech-mod machine block + metadata into an IBlockState to place.
     * Mods register machines inconsistently: some as clean separate blocks
     * (appliedenergistics2:inscriber, enderio:block_alloy_smelter,
     * thermalexpansion:machine, ic2:te, immersiveengineering:wooden_device0),
     * others as metadata variants of a shared block (Mekanism's crusher is
     * meta 3 of mekanism:machineblock; the lang key is
     * tile.MachineBlock.Crusher.name). So we look the hint up as an exact
     * registry name and apply the supplied meta (the map carries the correct
     * meta per mod). A "domain:token" fallback exists for safety. The chosen
     * block + meta + display key is always logged.
     */
    @Unique
    private static net.minecraft.block.state.IBlockState vulkanmod$resolveTechState(String hint, int meta) {
        // "domain:block#enumValue" selects a metadata/property value BY NAME
        // instead of a hardcoded meta. Needed for IC2's ic2:te, whose machine
        // type is an enum property (TeBlock), not a plain meta. meta is still
        // used as a fallback when no "#name" is supplied.
        String blockHint = hint;
        String namedValue = null;
        final int hash = hint.indexOf('#');
        if (hash >= 0) {
            blockHint = hint.substring(0, hash);
            namedValue = hint.substring(hash + 1).toLowerCase(java.util.Locale.ROOT);
        }
        final net.minecraft.util.ResourceLocation rl = new net.minecraft.util.ResourceLocation(blockHint);
        net.minecraft.block.Block exact = net.minecraft.block.Block.REGISTRY.getObject(rl);
        if (exact == null || exact == net.minecraft.init.Blocks.AIR) {
            // Fallback: registry name "domain:token".
            final String full = rl.toString();
            final int i = full.indexOf(':');
            final String domain = (i >= 0 ? full.substring(0, i) : "minecraft").toLowerCase(java.util.Locale.ROOT);
            final String token = (i >= 0 ? full.substring(i + 1) : full).toLowerCase(java.util.Locale.ROOT);
            for (net.minecraft.block.Block b : net.minecraft.block.Block.REGISTRY) {
                final net.minecraft.util.ResourceLocation rn = b.getRegistryName();
                if (rn == null) {
                    continue;
                }
                if (rn.toString().equalsIgnoreCase(domain + ":" + token)) {
                    exact = b;
                    break;
                }
            }
        }
        if (exact == null || exact == net.minecraft.init.Blocks.AIR) {
            VulkanMod.LOGGER.warn("[VKPROF] techgui: resolve '{}' FAILED (no registry name matched)", hint);
            return null;
        }
        // IC2 special-case: ic2:te is one block whose machine type is an ITeBlock
        // selected via BlockTileEntity.getState(ITeBlock). The generic named-value
        // matcher only sees MetaTeBlock.toString() (not the ITeBlock identifier or
        // enum name), so resolve it directly here. ic2 is not on the compile
        // classpath, hence the reflection.
        if ("ic2:te".equals(blockHint)) {
            try {
                Class<?> bteCls = Class.forName("ic2.core.block.BlockTileEntity");
                if (bteCls.isInstance(exact)) {
                    Class<?> iteCls = Class.forName("ic2.core.block.ITeBlock");
                    final java.util.Set<?> allTypes =
                            (java.util.Set<?>) bteCls.getMethod("getAllTypes").invoke(exact);
                    final String want = (namedValue != null) ? namedValue : "generator";
                    for (Object ite : allTypes) {
                        Object id = ite.getClass().getMethod("getIdentifier").invoke(ite);
                        final String path = (String) id.getClass().getMethod("getPath").invoke(id);
                        final String ename = (ite instanceof java.lang.Enum)
                                ? ((java.lang.Enum<?>) ite).name() : "";
                        if (want.equalsIgnoreCase(path) || want.equalsIgnoreCase(ename)) {
                            final Object st = bteCls.getMethod("getState", iteCls).invoke(exact, ite);
                            if (st instanceof net.minecraft.block.state.IBlockState) {
                                VulkanMod.LOGGER.info("[VKPROF] techgui: resolve '{}' -> ic2:te ITeBlock '{}'",
                                        hint, path);
                                return (net.minecraft.block.state.IBlockState) st;
                            }
                        }
                    }
                    VulkanMod.LOGGER.warn("[VKPROF] techgui: resolve '{}' ITeBlock '{}' not found", hint, want);
                }
            } catch (Throwable t) {
                VulkanMod.LOGGER.warn("[VKPROF] techgui: resolve ic2 FAILED", t);
            }
        }
        final net.minecraft.block.state.IBlockState base = exact.getStateFromMeta(meta);
        if (namedValue != null) {
            for (net.minecraft.block.properties.IProperty<?> p : base.getPropertyKeys()) {
                for (Object v : p.getAllowedValues()) {
                    final String vname = (v instanceof java.lang.Enum)
                            ? ((java.lang.Enum<?>) v).name() : String.valueOf(v);
                    if (vname.equalsIgnoreCase(namedValue)) {
                        @SuppressWarnings("unchecked")
                        final net.minecraft.block.state.IBlockState s =
                                base.withProperty((net.minecraft.block.properties.IProperty) p, (Comparable) v);
                        VulkanMod.LOGGER.info("[VKPROF] techgui: resolve '{}' -> {} (prop {} = {})",
                                hint, exact.getRegistryName(), p.getName(), vname);
                        return s;
                    }
                }
            }
            VulkanMod.LOGGER.warn("[VKPROF] techgui: resolve '{}' named value '{}' not found on {}, using meta {}",
                    hint, namedValue, exact.getRegistryName(), meta);
        }
        VulkanMod.LOGGER.info("[VKPROF] techgui: resolve '{}' -> {} meta={} (key={})",
                hint, exact.getRegistryName(), meta, base.getBlock().getTranslationKey());
        return base;
    }

    /**
     * Tech-mod machine-GUI test sequence (TECHGUI_TEST=1). One mod per run:
     * build a 7x7 grass platform at y=99 -> teleport the SERVER player onto it
     * facing a machine placed ~1.5 blocks ahead -> open the machine GUI on the
     * server thread (the real right-click path) -> wait for the open-window
     * packet to reach the client -> capture techgui_<label>.png (and log the
     * screen class so the harness can confirm a GUI actually opened) -> close
     * the GUI -> quit.
     *
     * <p>Block/meta table per mod (best-known for 1.12.2):
     *   enderio             -&gt; enderio:alloy_smelter            (meta 0)
     *   thermalexpansion    -&gt; thermalexpansion:machine         (meta 1 = Pulverizer)
     *   mekanism            -&gt; mekanism:crusher                (meta 0)
     *   appliedenergistics2 -&gt; appliedenergistics2:inscriber   (meta 0)
     *   immersiveengineering-&gt; immersiveengineering:wooden_device0 (meta 0 = Crafting Table)
     *   ic2                 -&gt; ic2:te#generator              (TeBlock enum "generator")
     */
    @Unique
    private static void vulkanmod$techGuiTick(Minecraft mc, long dt) {
        // Resolve which machine to open from loaded mods (first match wins).
        if (!compatTechGuiEnabled) {
            compatTechGuiEnabled = true;
            final Object[][] map = {
                {"enderio", "enderio:block_alloy_smelter", 0},
                {"thermalexpansion", "thermalexpansion:machine", 1},
                {"mekanism", "mekanism:machineblock", 3},
                {"appliedenergistics2", "appliedenergistics2:inscriber", 0},
                {"immersiveengineering", "immersiveengineering:wooden_device0", 0},
                {"ic2", "ic2:te#generator", 0},
            };
            for (Object[] e : map) {
                if (Loader.isModLoaded((String) e[0])) {
                    techTargetMod = (String) e[0];
                    techTargetBlock = (String) e[1];
                    techTargetMeta = (Integer) e[2];
                    break;
                }
            }
            VulkanMod.LOGGER.info("[VKPROF] techgui: target mod={} block={} meta={}",
                    techTargetMod, techTargetBlock, techTargetMeta);
            if (techTargetMod.isEmpty()) {
                VulkanMod.LOGGER.warn("[VKPROF] techgui: no known tech mod loaded, quitting");
                mc.shutdown();
                return;
            }
        }

        // Platform + clear air above so the camera has a clean frame.
        if (!techPlatformBuilt && dt > 500) {
            techPlatformBuilt = true;
            vulkanmod$runServerCommands(mc, new String[]{
                    "fill -3 99 -3 3 99 3 minecraft:grass",
                    "fill -3 100 -3 3 110 3 minecraft:air",
            });
            VulkanMod.LOGGER.info("[VKPROF] techgui: platform queued (grass y=99, 7x7)");
        }

        // Server-authoritative teleport onto the platform facing +Z (yaw 0), so
        // the machine placed at z=+1 is ahead of the camera.
        if (techPlatformBuilt && !techServerTp && dt > 1200) {
            techServerTp = true;
            final net.minecraft.server.MinecraftServer server0 = mc.getIntegratedServer();
            if (server0 != null) {
                server0.addScheduledTask(() -> {
                    try {
                        final java.util.List<net.minecraft.entity.player.EntityPlayerMP> players =
                                server0.getPlayerList().getPlayers();
                        if (!players.isEmpty()) {
                            players.get(0).connection.setPlayerLocation(
                                    0.5, 100.0, -0.5, 0.0F, 20.0F);
                        }
                    } catch (Throwable t) {
                        VulkanMod.LOGGER.warn("[VKPROF] techgui: server teleport FAILED", t);
                    }
                });
            }
            mc.player.setLocationAndAngles(0.5, 100.0, -0.5, 0.0F, 20.0F);
            mc.player.setPositionAndUpdate(0.5, 100.0, -0.5);
            VulkanMod.LOGGER.info("[VKPROF] techgui: server teleport queued (0.5,100,-0.5) yaw=0");
        }

        // Place the machine 1.5 blocks ahead (z=+1) DIRECTLY in the server
        // world. We deliberately do NOT use the /setblock command: its block
        // parser fails to resolve many mod block names and silently returns 0
        // (the machine is never actually placed, so the GUI never opens). Going
        // through Block.REGISTRY + World.setBlockState on the real server world
        // is the same reliable path the TF-portal harness uses. We also keep a
        // dirt->grass toggle on the platform so the floor is visible.
        if (techServerTp && !techBlockPlaced && dt > 1800) {
            techBlockPlaced = true;
            final String blk = techTargetBlock;
            final int meta = techTargetMeta;
            final net.minecraft.server.MinecraftServer server0 = mc.getIntegratedServer();
            if (server0 != null) {
                server0.addScheduledTask(() -> {
                    try {
                        final net.minecraft.world.WorldServer w = server0.getWorld(0);
                        final net.minecraft.block.state.IBlockState state = vulkanmod$resolveTechState(blk, meta);
                        if (state == null || state.getBlock() == net.minecraft.init.Blocks.AIR) {
                            VulkanMod.LOGGER.warn("[VKPROF] techgui: PLACE FAILED, block not resolved: {}", blk);
                            return;
                        }
                        final net.minecraft.util.math.BlockPos pos =
                                new net.minecraft.util.math.BlockPos(0, 100, 1);
                        w.setBlockState(pos, state, 3);
                        w.getPlayerChunkMap().markBlockForUpdate(pos);
                        // Guarantee the tile entity exists on BOTH sides. Some mods
                        // (IC2) do not create their TE through Forge's normal
                        // setBlockState path, and most mod GUIs (IE crates) require
                        // the CLIENT TE to exist before the open-window packet so
                        // their client GUI handler can resolve it. We force-create
                        // the server TE if missing and push a FULL TE packet
                        // (writeToNBT) -- getUpdatePacket() is null for a freshly
                        // placed, not-yet-dirty TE and would never create the client TE.
                        net.minecraft.tileentity.TileEntity teSync = w.getTileEntity(pos);
                        if (teSync == null) {
                            final net.minecraft.block.Block tb = state.getBlock();
                            teSync = tb.createTileEntity(w, state);
                            if (teSync == null) {
                                // IC2 registers TEs via its own ITeBlock system, not
                                // Forge's standard registry, so the default
                                // createTileEntity returns null and no TE is created.
                                // Build it through IC2's ITeBlock.getTeClass() so the
                                // machine (and its GUI) are fully functional. Pure
                                // reflection so the harness compiles without IC2 on the
                                // classpath; Class.forName throws (caught) when IC2 is
                                // absent, leaving other mods untouched.
                                try {
                                    // BlockTileEntity implements IMultiBlock<ITeBlock> and
                                    // does NOT override createTileEntity, so Forge's default
                                    // returns null and no TE is created. The placed state
                                    // carries its machine type via the IProperty whose value
                                    // class is MetaTeBlock; that wrapper exposes the ITeBlock
                                    // through its public `teBlock` field. From the ITeBlock we
                                    // obtain the TE class and instantiate it ourselves.
                                    Class<?> metaTeCls = Class.forName("ic2.core.ref.MetaTeBlock");
                                    net.minecraft.block.properties.IProperty<?> typeProp = null;
                                    for (net.minecraft.block.properties.IProperty<?> p :
                                            state.getPropertyKeys()) {
                                        if (metaTeCls.isAssignableFrom(p.getValueClass())) {
                                            typeProp = p;
                                            break;
                                        }
                                    }
                                    if (typeProp != null) {
                                        Object metaTe = state.getValue(typeProp);
                                        if (metaTe != null) {
                                            Object ite = metaTeCls.getField("teBlock").get(metaTe);
                                            if (ite != null) {
                                                Class<?> teCls = (Class<?>) ite.getClass()
                                                        .getMethod("getTeClass").invoke(ite);
                                                if (teCls != null) {
                                                    Object teObj = teCls.getDeclaredConstructor().newInstance();
                                                    if (teObj instanceof net.minecraft.tileentity.TileEntity) {
                                                        net.minecraft.tileentity.TileEntity te2 =
                                                                (net.minecraft.tileentity.TileEntity) teObj;
                                                        te2.setWorld(w);
                                                        te2.setPos(pos);
                                                        w.setTileEntity(pos, te2);
                                                        teSync = te2;
                                                    }
                                                } else {
                                                    VulkanMod.LOGGER.warn("[VKPROF] techgui: ic2 ITeBlock.getTeClass() returned null");
                                                }
                                            }
                                        }
                                    }
                                    if (teSync == null) {
                                        VulkanMod.LOGGER.warn("[VKPROF] techgui: ic2 TE not built "
                                                + "(no MetaTeBlock type prop / teBlock)");
                                    }
                                } catch (Throwable t) {
                                    VulkanMod.LOGGER.warn("[VKPROF] techgui: ic2 TE create failed", t);
                                }
                            }
                            if (teSync != null) w.setTileEntity(pos, teSync);
                        }
                        if (teSync != null) {
                            final net.minecraft.nbt.NBTTagCompound teNbt =
                                    new net.minecraft.nbt.NBTTagCompound();
                            teSync.writeToNBT(teNbt);
                            final net.minecraft.network.play.server.SPacketUpdateTileEntity syncPkt =
                                    new net.minecraft.network.play.server.SPacketUpdateTileEntity(pos, 0, teNbt);
                            for (net.minecraft.entity.player.EntityPlayerMP sp :
                                    server0.getPlayerList().getPlayers())
                                sp.connection.sendPacket(syncPkt);
                        }
                        final net.minecraft.block.state.IBlockState after = w.getBlockState(pos);
                        VulkanMod.LOGGER.info("[VKPROF] techgui: PLACED '{}' meta={} -> server sees {} (class {})",
                                blk, meta,
                                net.minecraft.block.Block.REGISTRY.getNameForObject(after.getBlock()),
                                after.getBlock().getClass().getName());
                    } catch (Throwable t) {
                        VulkanMod.LOGGER.warn("[VKPROF] techgui: place block FAILED", t);
                    }
                });
            }
            // Refresh the platform floor (command path works fine for vanilla blocks).
            vulkanmod$runServerCommands(mc, new String[]{
                    "fill -3 99 -3 3 99 3 minecraft:dirt",
                    "fill -3 99 -3 3 99 3 minecraft:grass",
            });
            VulkanMod.LOGGER.info("[VKPROF] techgui: machine placement '{}' meta={} queued (direct)", blk, meta);
        }

        // Open the machine GUI by invoking block.onBlockActivated directly on the
        // SERVER thread with the server player. This bypasses the reach/ray-trace
        // path and the server definitely has the placed block, so it is the most
        // reliable way to open a mod GUI headlessly. openGui() then flows the
        // open-window packet back to the client. We also force a client
        // block-update so the machine is actually visible in the screenshot.
        if (techBlockPlaced && !techGuiOpenRequested && dt > 3200) {
            techGuiOpenRequested = true;
            final net.minecraft.server.MinecraftServer server0 = mc.getIntegratedServer();
            if (server0 != null) {
                server0.addScheduledTask(() -> {
                    try {
                        final net.minecraft.world.WorldServer w = server0.getWorld(0);
                        final net.minecraft.util.math.BlockPos pos =
                                new net.minecraft.util.math.BlockPos(0, 100, 1);
                        final net.minecraft.block.state.IBlockState st = w.getBlockState(pos);
                        final net.minecraft.block.Block b = st.getBlock();
                        VulkanMod.LOGGER.info("[VKPROF] techgui: SERVER block at machine pos = {} (class {})",
                                net.minecraft.block.Block.REGISTRY.getNameForObject(b),
                                b.getClass().getName());
                        final java.util.List<net.minecraft.entity.player.EntityPlayerMP> players =
                                server0.getPlayerList().getPlayers();
                        if (players.isEmpty()) {
                            VulkanMod.LOGGER.warn("[VKPROF] techgui: no server player to open GUI");
                            return;
                        }
                        final net.minecraft.entity.player.EntityPlayerMP mp = players.get(0);
                        // Re-send the tile-entity data to the client so mods whose
                        // client GUI handler looks up the TE (e.g. IE crates) can
                        // actually open their screen.
                        final net.minecraft.tileentity.TileEntity teAt = w.getTileEntity(pos);
                        VulkanMod.LOGGER.info("[VKPROF] techgui: SERVER te at machine pos = {}",
                                teAt == null ? "null" : teAt.getClass().getName());
                        if (teAt != null) {
                            final net.minecraft.nbt.NBTTagCompound teNbt2 =
                                    new net.minecraft.nbt.NBTTagCompound();
                            teAt.writeToNBT(teNbt2);
                            final net.minecraft.network.play.server.SPacketUpdateTileEntity tePkt2 =
                                    new net.minecraft.network.play.server.SPacketUpdateTileEntity(pos, 0, teNbt2);
                            mp.connection.sendPacket(tePkt2);
                        }
                        // Force the client to receive the machine block update.
                        w.getPlayerChunkMap().markBlockForUpdate(pos);
                        final boolean opened = b.onBlockActivated(w, pos, st, mp,
                                net.minecraft.util.EnumHand.MAIN_HAND,
                                net.minecraft.util.EnumFacing.UP, 0.5F, 0.5F, 0.5F);
                        VulkanMod.LOGGER.info("[VKPROF] techgui: SERVER onBlockActivated('{}') -> {} (distSq={} container={})",
                                techTargetBlock, opened,
                                String.format(java.util.Locale.ROOT, "%.2f", mp.getDistanceSqToCenter(pos)),
                                mp.openContainer != null ? mp.openContainer.getClass().getSimpleName() : "null");
                    } catch (Throwable t) {
                        VulkanMod.LOGGER.warn("[VKPROF] techgui: open GUI FAILED", t);
                    }
                });
            }
            VulkanMod.LOGGER.info("[VKPROF] techgui: open GUI requested (server thread)");
        }

        // After the open-window packet reaches the client, log the screen class
        // (proves a mod GUI actually opened) and the client block state (proves
        // the machine is visible), then capture.
        if (techGuiOpenRequested && !techGuiShot && dt > 4400) {
            techGuiShot = true;
            final net.minecraft.util.math.BlockPos pos =
                    new net.minecraft.util.math.BlockPos(0, 100, 1);
            final net.minecraft.block.Block b = mc.world.getBlockState(pos).getBlock();
            final String screen = (mc.currentScreen == null)
                    ? "null" : mc.currentScreen.getClass().getName();
            techGuiOpened = (mc.currentScreen != null);
            VulkanMod.LOGGER.info("[VKPROF] techgui: CLIENT block at machine pos = {} ; screen after open = {}",
                    net.minecraft.block.Block.REGISTRY.getNameForObject(b), screen);
            vulkanmod$captureTo(mc, "techgui_" + techTargetMod);
        }

        // Close the GUI (client-initiated; sends CPacketCloseWindow to server).
        if (techGuiShot && !techGuiClosed && dt > 5200) {
            techGuiClosed = true;
            if (mc.currentScreen != null) {
                mc.player.closeScreen();
            }
            VulkanMod.LOGGER.info("[VKPROF] techgui: GUI closed");
        }

        // Done.
        if (!compatQuit && dt > 6400) {
            compatQuit = true;
            VulkanMod.LOGGER.info("[VKPROF] techgui: sequence complete, quitting (opened={})",
                    techGuiOpened);
            mc.shutdown();
        }
    }

    /**
     * Render-fix regression sequence (FIXTEST=1, needs AUTOJOIN=1).
     * <ul>
     *   <li>F3 axes: force the debug overlay on and capture
     *       {@code fixtest_f3.png}. The axes are
     *       {@code OpenGlHelper.renderDirections} - a GL_LINES batch whose
     *       index buffer used to be the QUADS generator, so the rays rendered
     *       as wide filled triangles. reducedDebugInfo is forced off because
     *       GuiIngame only draws the axes when it is false.</li>
     *   <li>End portal: build a stage, place an {@code end_portal} block, aim
     *       the camera at its top face and capture {@code fixtest_endportal.png}.
     *       The starfield is texGen-driven POSITION_COLOR geometry (no UV
     *       attribute); before the fix the pipeline had no sampler and the
     *       portal drew as flat untextured quads.</li>
     * </ul>
     */
    @Unique
    private static void vulcraft$fixTick(Minecraft mc, long dt) {
        // 1. F3 direction axes.
        if (!fixF3On && dt > 2000) {
            fixF3On = true;
            mc.gameSettings.showDebugInfo = true;
            mc.gameSettings.reducedDebugInfo = false;
            VulkanMod.LOGGER.info("[VKPROF] fixtest: debug overlay on");
        }
        if (fixF3On && !fixF3Shot && dt > 4000) {
            fixF3Shot = true;
            vulkanmod$captureTo(mc, "fixtest_f3");
        }

        // 2. Stage: grass floor at y=99 (top surface = y=100), air above.
        if (!fixStageBuilt && dt > 5000) {
            fixStageBuilt = true;
            vulkanmod$runServerCommands(mc, new String[]{
                    "fill -6 99 -6 6 99 6 minecraft:grass",
                    "fill -6 100 -6 6 104 6 minecraft:air",
            });
            VulkanMod.LOGGER.info("[VKPROF] fixtest: stage queued (grass y=99, air y=100..104)");
        }

        // 3. Teleport to (-4.5, 100, 0.5) facing +X (yaw -90, pitch 10: the
        //    portal's UP-face quad at y+0.75 sits just below the eye line), then
        //    place the end portal at (0, 100, 0) on the server world.
        if (fixStageBuilt && !fixPortalPlaced && dt > 6000) {
            fixPortalPlaced = true;
            final net.minecraft.server.MinecraftServer server0 = mc.getIntegratedServer();
            if (server0 != null) {
                server0.addScheduledTask(() -> {
                    try {
                        final java.util.List<net.minecraft.entity.player.EntityPlayerMP> players =
                                server0.getPlayerList().getPlayers();
                        if (!players.isEmpty()) {
                            players.get(0).connection.setPlayerLocation(
                                    -4.5, 100.0, 0.5, -90.0F, 10.0F);
                        }
                    } catch (Throwable t) {
                        VulkanMod.LOGGER.warn("[VKPROF] fixtest: server teleport FAILED", t);
                    }
                });
            }
            mc.player.setLocationAndAngles(-4.5, 100.0, 0.5, -90.0F, 10.0F);
            mc.player.setPositionAndUpdate(-4.5, 100.0, 0.5);
            if (server0 != null) {
                server0.addScheduledTask(() -> {
                    try {
                        final net.minecraft.world.WorldServer w = server0.getWorld(0);
                        final net.minecraft.util.math.BlockPos pos =
                                new net.minecraft.util.math.BlockPos(0, 100, 0);
                        w.setBlockState(pos, net.minecraft.init.Blocks.END_PORTAL.getDefaultState(), 3);
                        w.getPlayerChunkMap().markBlockForUpdate(pos);
                        final net.minecraft.block.state.IBlockState after = w.getBlockState(pos);
                        VulkanMod.LOGGER.info("[VKPROF] fixtest: end portal placed -> {} (TE {})",
                                after.getBlock(),
                                w.getTileEntity(pos) != null ? w.getTileEntity(pos).getClass().getSimpleName() : "null");
                    } catch (Throwable t) {
                        VulkanMod.LOGGER.warn("[VKPROF] fixtest: end portal placement FAILED", t);
                    }
                });
            }
            VulkanMod.LOGGER.info("[VKPROF] fixtest: teleported + portal queued");
        }

        // 4. Capture the portal.
        if (fixPortalPlaced && !fixPortalShot && dt > 9000) {
            fixPortalShot = true;
            vulkanmod$captureTo(mc, "fixtest_endportal");
        }

        // Done.
        if (!fixQuit && dt > 11000) {
            fixQuit = true;
            VulkanMod.LOGGER.info("[VKPROF] fixtest: sequence complete, quitting (f3={} portal={})",
                    fixF3Shot, fixPortalShot);
            mc.shutdown();
        }
    }

    /**
     * Window-minimise regression sequence (MINITEST=1, needs AUTOJOIN=1).
     *
     * <p>Iconifying a GLFW window drives the framebuffer size to 0x0, which
     * makes the surface's {@code currentExtent} 0x0 as well - the swapchain
     * cannot exist in that state. The game loop keeps running while minimised,
     * so every frame has to survive a swapchain that is either gone or
     * out of date, and restoring the window has to rebuild it. This sequence
     * captures {@code minitest_before} (normal), stays iconified for several
     * seconds of live frames, then captures {@code minitest_after} (restored).
     */
    @Unique
    private static void vulcraft$miniTick(Minecraft mc, long dt) {
        // 1. Baseline frame while the window is normal.
        if (!miniBeforeShot && dt > 5000) {
            miniBeforeShot = true;
            vulkanmod$captureTo(mc, "minitest_before");
        }

        // 2. Minimise. glfwIconifyWindow is a request - the actual iconify
        //    (and the resulting 0x0 framebuffer) is delivered by the next
        //    glfwPollEvents, i.e. inside Display.update().
        if (miniBeforeShot && !miniIconified && dt > 6000) {
            miniIconified = true;
            final long w = Display.getWindow();
            VulkanMod.LOGGER.info("[VKPROF] minitest: iconifying window 0x{}", Long.toHexString(w));
            org.lwjgl.glfw.GLFW.glfwIconifyWindow(w);
        }

        // 3. Stay minimised long enough for a few dozen frames to run through
        //    whatever the 0x0-extent path does.
        if (miniIconified && !miniRestored && dt > 11000) {
            miniRestored = true;
            VulkanMod.LOGGER.info("[VKPROF] minitest: restoring window 0x{}",
                    Long.toHexString(Display.getWindow()));
            org.lwjgl.glfw.GLFW.glfwRestoreWindow(Display.getWindow());
        }

        // 4. Frame after the restore - the swapchain has to be back.
        if (miniRestored && !miniAfterShot && dt > 14000) {
            miniAfterShot = true;
            vulkanmod$captureTo(mc, "minitest_after");
        }

        if (!miniQuit && dt > 16000) {
            miniQuit = true;
            VulkanMod.LOGGER.info("[VKPROF] minitest: sequence complete, quitting (before={} after={})",
                    miniBeforeShot, miniAfterShot);
            mc.shutdown();
        }
    }

    /**
     * Selects the hotbar slot holding the item with the given registry name.
     * Registry names in this environment are stored LOWERCASE (e.g.
     * "botania:managun"), so the comparison is case-insensitive. If the item
     * sits in the main inventory (slots 9-35) it is swapped into the current
     * hotbar slot first.
     */
    @Unique
    private static boolean vulkanmod$holdItem(Minecraft mc, String registryName) {
        try {
            int found = -1;
            for (int i = 0; i < mc.player.inventory.mainInventory.size(); i++) {
                final net.minecraft.item.ItemStack st = mc.player.inventory.mainInventory.get(i);
                if (st == null || st.isEmpty()) {
                    continue;
                }
                final net.minecraft.util.ResourceLocation id =
                        net.minecraft.item.Item.REGISTRY.getNameForObject(st.getItem());
                if (id != null && id.toString().equalsIgnoreCase(registryName)) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < mc.player.inventory.mainInventory.size(); i++) {
                    final net.minecraft.item.ItemStack st = mc.player.inventory.mainInventory.get(i);
                    if (st == null || st.isEmpty()) {
                        continue;
                    }
                    final net.minecraft.util.ResourceLocation id =
                            net.minecraft.item.Item.REGISTRY.getNameForObject(st.getItem());
                    sb.append(i).append('=').append(id).append(' ');
                }
                VulkanMod.LOGGER.info("[VKPROF] botania: holdItem('{}') not found; inventory: {}",
                        registryName, sb.toString().trim());
                return false;
            }
            if (found >= 9) {
                final int cur = mc.player.inventory.currentItem;
                final net.minecraft.item.ItemStack a = mc.player.inventory.mainInventory.get(found);
                final net.minecraft.item.ItemStack b = mc.player.inventory.mainInventory.get(cur);
                mc.player.inventory.mainInventory.set(found, b);
                mc.player.inventory.mainInventory.set(cur, a);
            } else {
                mc.player.inventory.currentItem = found;
            }
            return true;
        } catch (Throwable t) {
            VulkanMod.LOGGER.warn("[VKPROF] botania: holdItem('{}') failed", registryName, t);
        }
        return false;
    }

    /** Gives a spread of items through the integrated server's command manager. */
    @Unique
    private static void vulkanmod$giveCompatItems(Minecraft mc) {
        final MinecraftServer server = mc.getIntegratedServer();
        if (server == null) {
            return;
        }
        final String[] items = {
                "minecraft:diamond 64",
                "minecraft:golden_apple 16",
                "minecraft:diamond_sword",
                "minecraft:torch 64",
                "minecraft:emerald 32",
                "minecraft:book",
        };
        server.addScheduledTask(() -> {
            for (String it : items) {
                try {
                    final int r = server.getCommandManager().executeCommand(server, "give @p " + it);
                    VKProf.info("[VKPROF] compat give '{}' result={}", it, r);
                } catch (Throwable t) {
                    VKProf.warn("[VKPROF] compat give '{}' failed", it, t);
                }
            }
        });
    }

    /** True when the client world is the Twilight Forest dimension. */
    @Unique
    private static boolean vulkanmod$isInTwilightForest(Minecraft mc) {
        if (mc.world == null || mc.world.provider == null) {
            return false;
        }
        return mc.world.provider.getClass().getName().toLowerCase(java.util.Locale.ROOT)
                .contains("twilightforest");
    }

    /**
     * Builds a 2x2 TF portal (water + grass rim + flowers) under the player and
     * drops a diamond into it so TF forms the portal on its own.
     */
    @Unique
    private static void vulkanmod$buildTFPortal(Minecraft mc) {
        final MinecraftServer server = mc.getIntegratedServer();
        if (server == null || mc.player == null) {
            return;
        }
        final int px = net.minecraft.util.math.MathHelper.floor(mc.player.posX);
        final int py = net.minecraft.util.math.MathHelper.floor(mc.player.posY);
        final int pz = net.minecraft.util.math.MathHelper.floor(mc.player.posZ);
        final int poolY = py;                 // water surface at the player's feet
        final int x0 = px - 1, z0 = pz - 1;   // 2x2 water spans [x0,x0+1] x [z0,z0+1]
        server.addScheduledTask(() -> {
            try {
                final WorldServer world = server.getWorld(0);
                final IBlockState water = Blocks.WATER.getDefaultState();
                final IBlockState grass = Blocks.GRASS.getDefaultState();
                final IBlockState dirt = Blocks.DIRT.getDefaultState();
                final IBlockState flower = Blocks.RED_FLOWER.getDefaultState();
                final IBlockState air = Blocks.AIR.getDefaultState();
                // 4x4 envelope around the 2x2 pool.
                for (int x = x0 - 1; x <= x0 + 2; x++) {
                    for (int z = z0 - 1; z <= z0 + 2; z++) {
                        final boolean inPool = (x == x0 || x == x0 + 1) && (z == z0 || z == z0 + 1);
                        world.setBlockState(new BlockPos(x, poolY - 1, z), dirt, 2);   // floor
                        if (inPool) {
                            world.setBlockState(new BlockPos(x, poolY, z), water, 2);    // water
                            world.setBlockState(new BlockPos(x, poolY + 1, z), air, 2);  // open above water
                        } else {
                            world.setBlockState(new BlockPos(x, poolY, z), grass, 2);    // grass rim
                            world.setBlockState(new BlockPos(x, poolY + 1, z), flower, 2); // flower on rim
                        }
                        world.setBlockState(new BlockPos(x, poolY + 2, z), air, 2);
                    }
                }
                // Stand the player in the centre of the pool so they collide with
                // the portal the instant it forms.
                final double cx = x0 + 1.0 + 0.5, cy = poolY + 0.5, cz = z0 + 1.0 + 0.5;
                mc.player.setLocationAndAngles(cx, cy, cz, 0.0f, 30.0f);
                mc.player.setPositionAndUpdate(cx, cy, cz);
                // Drop a diamond into a CORNER of the pool, INSIDE the water block
                // (not above it): TF's checkForPortalCreation only forms the portal
                // when the item's own block position is water (canFormPortal check).
                // Infinite pickup delay so the player does not grab it first.
                final EntityItem diamond = new EntityItem(world, x0 + 0.5, poolY + 0.8, z0 + 0.5,
                        new net.minecraft.item.ItemStack(Items.DIAMOND));
                diamond.setInfinitePickupDelay();
                world.spawnEntity(diamond);
                VulkanMod.LOGGER.info("[VKPROF] compat: TF portal built at ({},{},{}), diamond in water @({},{},{})",
                        x0, poolY, z0, x0, poolY, z0);
            } catch (Throwable t) {
                VulkanMod.LOGGER.error("[VKPROF] compat TF portal build failed", t);
            }
        });
    }

    /** Teleports the host player to the Twilight Forest dimension (id 7 by default). */
    @Unique
    private static void vulkanmod$changeDimensionTf(Minecraft mc) {
        final MinecraftServer server = mc.getIntegratedServer();
        if (server == null) {
            return;
        }
        server.addScheduledTask(() -> {
            try {
                final EntityPlayerMP mp = server.getPlayerList().getPlayers().get(0);
                if (mp != null) {
                    // TFConfig.dimension.dimensionID defaults to 7. changeDimension
                    // creates the TF world on demand for the integrated server.
                    mp.changeDimension(7);
                    VKProf.info("[VKPROF] compat: changed dimension -> TWILIGHT FOREST (7)");
                } else {
                    VKProf.warn("[VKPROF] compat: no player to dimension-switch");
                }
            } catch (Throwable t) {
                VulkanMod.LOGGER.error("[VKPROF] compat TF dimension change failed", t);
            }
        });
    }
}
