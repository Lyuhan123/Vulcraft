package com.yuhan123.vulkanmod.vulkan;

import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.gl.VkGlFramebuffer;
import com.yuhan123.vulkanmod.render.texture.ImageUploadHelper;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;

/**
 * Presents the Forge loading splash on the Vulkan surface.
 *
 * <p>Forge's {@code SplashProgress} draws its progress bar with legacy GL11
 * immediate mode on its own render thread. Those calls are already intercepted
 * by {@code GL11Mixin} and recorded into the Vulkan command buffer, but a
 * recorded frame only reaches the screen when it is submitted and presented -
 * which Minecraft's main loop does, and that loop is not running during FML
 * loading.
 *
 * <p><b>Who does what.</b> Frame management (begin/end, image acquire, submit,
 * present, frame-slot bookkeeping) stays entirely on the <b>main thread</b>,
 * which presents from FML's loading-progress callbacks
 * (see {@link #presentFromMainThread()}). The splash thread only records its
 * draw calls into the currently open frame, and holds
 * {@link ImageUploadHelper#LOCK} while doing so. Keeping every frame transition
 * on one thread is what makes this safe: the renderer's frame slot, its
 * MemoryManager resources, the staging buffer and the upload batch are all
 * single-owner state, and having the splash thread drive begin/endFrame itself
 * made the two threads reset/submit each other's command buffers
 * (VK_ERROR_DEVICE_LOST at the end of loading).
 *
 * <p>Frames are also dumped to {@code screenshots/} via
 * {@link VkGlFramebuffer#readPixels} (desktop capture cannot see a Vulkan
 * flip-model swapchain): {@code splash_N.png} while the splash is up, and - when
 * {@code -Dvulkanmod.menuDumps=N} is set - {@code menu_N.png} for the first
 * frames of the main menu that follow, so one runClient produces the whole
 * "loading -&gt; main menu" sequence.
 */
public final class SplashPresenter {

    /** Splash draw calls between two captures, and how many to keep in total. */
    private static final int DUMP_EVERY = Integer.getInteger("vulkanmod.splashDumpEvery", 40);
    private static final int MAX_DUMPS = Integer.getInteger("vulkanmod.splashDumpMax", 10);

    /**
     * How many main-menu frames to capture once the splash is over
     * ({@code -Dvulkanmod.menuDumps=N}, default 0 = off).
     *
     * <p>Off by default because it is a one-shot dev diagnostic, not something a
     * normal run should leave in {@code screenshots/}. It exists so a single
     * runClient can be turned into a "loading -&gt; main menu" sequence: the
     * splash frames above plus the first frames of the main menu, all read back
     * through the same spec-legal window.
     */
    private static final int MENU_DUMPS = Integer.getInteger("vulkanmod.menuDumps", 0);

    /** Main-menu frames to skip between two captures (~1 s at 60 fps). */
    private static final int MENU_EVERY = 60;

    /** True between SplashProgress.start() and finish(): main thread presents. */
    public static volatile boolean mainPresentEnabled = false;

    private static long calls = 0;
    private static int dumped = 0;
    private static int menuDumped = 0;
    private static long menuFrames = 0;
    private static boolean menuDone = false;
    private static int errors = 0;

    static {
        VulkanMod.LOGGER.info("[Splash] SplashPresenter class loaded (mixin referenced it)");
    }

    private SplashPresenter() {
    }

    /** Main thread: enable/disable splash presenting around the splash's lifetime. */
    public static void setMainPresentEnabled(boolean enabled) {
        mainPresentEnabled = enabled;
        // Install on enable; do NOT uninstall on disable - the hook stays so it
        // can capture the main menu that follows (see dumpFromHook), and takes
        // itself off once that is done (or immediately when no menu capture was
        // requested).
        if (enabled) {
            menuDumped = 0;
            menuDone = false;
            Renderer.preSubmitHook = SplashPresenter::dumpFromHook;
        } else if (MENU_DUMPS <= 0) {
            menuDone = true;
            Renderer.preSubmitHook = null;
        }
    }

    /**
     * Splash thread, loop head: take the GPU lock for this iteration's draw
     * calls so the main thread cannot submit/begin a frame or record an upload
     * while we are recording into the open frame.
     */
    public static void begin() {
        ImageUploadHelper.LOCK.lock();

        final long n = ++calls;
        if (n <= 3 || n % 200 == 0) {
            Renderer r0 = Renderer.getInstance();
            boolean ready = r0 != null && r0.getSwapChain() != null && r0.getSwapChain().getWidth() > 0;
            VulkanMod.LOGGER.info("[Splash] draw call#{} swapchainReady={} presenting={}", n, ready, mainPresentEnabled);
        }
    }

    /** Splash thread, loop tail: release the GPU lock. */
    public static void end() {
        // Defensive: only unlock if this thread holds it (an unbalanced end()
        // would otherwise throw and kill the splash thread).
        if (ImageUploadHelper.LOCK.isHeldByCurrentThread()) {
            ImageUploadHelper.LOCK.unlock();
        }
    }

    /**
     * Main thread: submit + present whatever the splash has drawn so far, then
     * open the next frame. Called from FML's progress callbacks, i.e. at points
     * where the loading thread is already between two GPU operations.
     *
     * <p>Uses {@code tryLock}: if the splash thread is in the middle of
     * recording its iteration we simply skip this step (the next progress
     * callback is only microseconds away), so the loading thread never blocks.
     */
    public static void presentFromMainThread() {
        if (!mainPresentEnabled) {
            return;
        }

        Renderer r = Renderer.getInstance();
        if (r == null || r.getSwapChain() == null
                || r.getSwapChain().getWidth() <= 0 || r.getSwapChain().getHeight() <= 0) {
            return;
        }

        if (!ImageUploadHelper.LOCK.tryLock()) {
            return;
        }

        try {
            if (!mainPresentEnabled) {
                return;
            }
            r.endFrame();
            r.beginFrame();
        } catch (Throwable t) {
            if (errors++ < 3) {
                VulkanMod.LOGGER.error("[Splash] present failed", t);
            }
        } finally {
            ImageUploadHelper.LOCK.unlock();
        }
    }

    /**
     * Installed on {@link Renderer#preSubmitHook} while the splash is up.
     *
     * <p>The dump runs inside {@code submitFrame()}, after the frame's command
     * buffer has been queued and before it is presented. That is the one window
     * where reading the swapchain image back is legal: the driver's layout for
     * the image has caught up with the CPU-side cache (both {@code PRESENT_SRC_KHR}
     * after {@code mainPass.end()}), and the image is still owned by this frame
     * because it has not been presented yet. Dumping with the pass open makes
     * the two sides disagree; dumping after the present transitions an image
     * that has already been released.
     */
    private static void dumpFromHook() {
        if (mainPresentEnabled) {
            final long n = calls;
            if (n % DUMP_EVERY == 0 && dumped < MAX_DUMPS) {
                dump();
            }
            return;
        }

        // Splash is over. Capture the requested number of frames that actually
        // show the main menu, then uninstall so normal play is untouched.
        if (menuDone) {
            return;
        }
        if (MENU_DUMPS <= 0 || menuDumped >= MENU_DUMPS) {
            menuDone = true;
            Renderer.preSubmitHook = null;
            return;
        }

        // Wait for the real main menu, not the resource-reload screen Forge
        // shows while startGame() is still finishing.
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc == null || !(mc.currentScreen instanceof net.minecraft.client.gui.GuiMainMenu)) {
            return;
        }

        // Spread the captures over time: at 60 fps, consecutive frames are the
        // same animation phase, so take one per MENU_EVERY frames instead.
        final long mf = ++menuFrames;
        if ((mf - 1) % MENU_EVERY != 0) {
            return;
        }

        dumpTo("menu_" + menuDumped + ".png", "main-menu");
        ++menuDumped;
        if (menuDumped >= MENU_DUMPS) {
            menuDone = true;
            Renderer.preSubmitHook = null;
        }
    }

    private static void dump() {
        dumpTo("splash_" + dumped + ".png", "splash");
        dumped++;
    }

    /** Shared body of {@link #dump()} and the main-menu capture. */
    private static void dumpTo(String fileName, String tag) {
        try {
            Renderer r = Renderer.getInstance();
            int width = r.getSwapChain().getWidth();
            int height = r.getSwapChain().getHeight();
            if (width <= 0 || height <= 0) {
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
                        final int rr = pixels.get(i) & 0xFF;
                        final int gg = pixels.get(i + 1) & 0xFF;
                        final int bb = pixels.get(i + 2) & 0xFF;
                        image.setRGB(x, y, (rr << 16) | (gg << 8) | bb);
                        sum += rr + gg + bb;
                    }
                }
                File dir = new File(System.getProperty("user.dir", "."), "screenshots");
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    return;
                }
                File out = new File(dir, fileName);
                ImageIO.write(image, "PNG", out);
                VulkanMod.LOGGER.info("[Splash] dumped {} frame {} {}x{} meanRGB={} -> {}",
                        tag, fileName, width, height, String.format("%.1f", sum / (double) (width * height * 3)),
                        out.getAbsolutePath());
            } finally {
                MemoryUtil.memFree(pixels);
            }
        } catch (Throwable t) {
            if (errors++ < 3) {
                VulkanMod.LOGGER.error("[Splash] dump failed", t);
            }
        }
    }
}
