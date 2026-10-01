package com.yuhan123.vulkanmod.mixin;

import com.yuhan123.vulkanmod.VKProf;
import com.yuhan123.vulkanmod.config.VulkanModConfig;
import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.gl.VkGlFramebuffer;
import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.Vulkan;
import net.minecraft.client.Minecraft;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;

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
        if (BENCH_MODE) {
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
                        server.addScheduledTask(() -> server.getCommandManager()
                                .executeCommand(server, "time set day"));
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
                // See PIN_CAMERA: hold the viewpoint still so the shot is a
                // function of the render path and nothing else.
                if (PIN_CAMERA && !screenshotFired && pinnedViewpoint != null && mc.player != null) {
                    mc.player.setLocationAndAngles(pinnedViewpoint[0], pinnedViewpoint[1],
                            pinnedViewpoint[2], (float) pinnedViewpoint[3], (float) pinnedViewpoint[4]);
                    mc.player.setPositionAndUpdate(pinnedViewpoint[0], pinnedViewpoint[1],
                            pinnedViewpoint[2]);
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
                    VKProf.info("[VKPROF] auto-quit after {}s in world", BENCH_MILLIS / 1000L);
                    mc.shutdown();
                }
            }
        }

        // Reset the per-frame vertex/index/UBO buffers BEFORE recording the
        // frame's draws. Without this the Drawer's usedBytes accumulates across
        // frames, so the buffers double in size every frame until the GPU runs
        // out of memory (VK_ERROR_OUT_OF_DEVICE_MEMORY) and the game stalls.
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
}
