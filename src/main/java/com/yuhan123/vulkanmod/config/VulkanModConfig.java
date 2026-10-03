package com.yuhan123.vulkanmod.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import com.yuhan123.vulkanmod.VulkanMod;

/**
 * Central switch board.
 *
 * Every toggle that used to be a {@code VULKANMOD_*} environment variable now
 * lives in {@code config/vulkanmod.properties}. Values are read once, on first
 * use, and cached; nothing is re-read per frame.
 *
 * Accepted file locations, first match wins:
 *   <modConfigDir>/vulkanmod.properties   (set from FMLPreInitializationEvent)
 *   ./config/vulkanmod.properties         (game / working directory)
 *   ./vulkanmod.properties
 *   ./config/vulkanmod.cfg
 *
 * Semantics kept identical to the old environment variables:
 *   boolean flag defaulting to false:  key = 1        (was "1".equals(env))
 *   boolean flag defaulting to true :  key = 0        (was !"0".equals(env))
 *   "enabled when present"          :  key = 1
 *   free-form string                :  key = <text>   (was System.getenv)
 *
 * Any key that is absent falls back to its default, so a missing or partial
 * file is fine. A template listing the current values is written next to the
 * file on first load so the switches are discoverable.
 */
public final class VulkanModConfig {

    public static final String FILE_NAME = "vulcraft.properties";

    private static final Map<String, String> VALUES = new HashMap<String, String>();
    private static volatile boolean loaded = false;
    private static File resolvedFile = null;
    private static boolean legacyUsed = false;

    /**
     * Per-frame VKPROF output. Off by default: it is a debug aid that floods the
     * log with ~25 lines per frame. Set {@code profiling=1} to bring it back.
     *
     * Exposed as a method rather than a final field so that reading it always
     * triggers a load first: a final field could be observed before the file was
     * parsed and would then report the default forever.
     */
    private static volatile boolean profiling = false;

    public static boolean profiling() {
        ensureLoaded();
        return profiling;
    }

    private VulkanModConfig() {
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (VulkanModConfig.class) {
            if (loaded) {
                return;
            }
            loadFrom(null);
            loaded = true;
        }
    }

    /**
     * Point the config at FML's mod config directory. Called from
     * {@code VulkanMod.preInit}; safe to call more than once (it reloads).
     */
    public static void load(File modConfigDir) {
        synchronized (VulkanModConfig.class) {
            loadFrom(modConfigDir);
            loaded = true;
        }
    }

    private static void loadFrom(File modConfigDir) {
        VALUES.clear();
        resolvedFile = null;

        File f = null;
        legacyUsed = false;
        File cand;
        // Canonical name first, then the legacy "vulkanmod.properties" spelling so
        // configs carried over from before the mod was renamed still take effect.
        if (modConfigDir != null && (cand = new File(modConfigDir, FILE_NAME)).isFile()) {
            f = cand;
        } else if ((cand = new File("config" + File.separator + FILE_NAME)).isFile()) {
            f = cand;
        } else if ((cand = new File(FILE_NAME)).isFile()) {
            f = cand;
        } else if (modConfigDir != null && (cand = new File(modConfigDir, "vulkanmod.properties")).isFile()) {
            f = cand; legacyUsed = true;
        } else if ((cand = new File("config" + File.separator + "vulkanmod.properties")).isFile()) {
            f = cand; legacyUsed = true;
        } else if ((cand = new File("vulkanmod.properties")).isFile()) {
            f = cand; legacyUsed = true;
        } else {
            f = new File("config" + File.separator + "vulkanmod.cfg");
        }

        if (f != null && f.isFile()) {
            InputStream in = null;
            try {
                in = new FileInputStream(f);
                Properties p = new Properties();
                p.load(in);
                java.util.Enumeration<?> names = p.propertyNames();
                while (names.hasMoreElements()) {
                    Object k = names.nextElement();
                    if (k == null) {
                        continue;
                    }
                    String key = norm(String.valueOf(k));
                    String v = p.getProperty(String.valueOf(k).trim());
                    if (key != null && v != null) {
                        VALUES.put(key, v.trim());
                    }
                }
                resolvedFile = f;
                VulkanMod.LOGGER.info("[config] loaded switches from {} (legacyName={})",
                        f.getPath(), Boolean.valueOf(legacyUsed));
            } catch (Throwable t) {
                VulkanMod.LOGGER.warn("[config] could not read {}: {}", f.getPath(), t);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                        // nothing useful to do while closing a config file
                    }
                }
            }
        }

        profiling = getBoolRaw("profiling", false);

        if (resolvedFile == null) {
            // No file yet: remember where we looked and write a template so the
            // switches are discoverable without digging through the sources.
            File template = (modConfigDir != null)
                    ? new File(modConfigDir, FILE_NAME)
                    : new File("config" + File.separator + FILE_NAME);
            writeTemplate(template);
        }
    }

    private static void writeTemplate(File f) {
        OutputStream out = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            out = new FileOutputStream(f);
            StringBuilder sb = new StringBuilder();
            sb.append("# vulcraft (VulkanMod-Legacy) switches.\n");
            sb.append("# Every VULKANMOD_* environment variable moved here.\n");
            sb.append("# Boolean switches: 1 = on, 0 = off. Remove a line to use the default.\n");
            sb.append("# This template only lists the common ones; see VulkanModConfig call\n");
            sb.append("# sites for the full set. Restart the game after editing.\n");
            sb.append("\n");
            sb.append("# Per-frame VKPROF logging (debug aid, floods the log). 0 = silent.\n");
            sb.append("profiling=0\n");
            sb.append("\n");
            sb.append("# Terrain batching / grouping experiments.\n");
            sb.append("TERRAIN_BATCH=1\n");
            sb.append("TERRAIN_REGION=0\n");
            sb.append("TERRAIN_AREA=0\n");
            sb.append("TB_TRANS_VANILLA=0\n");
            sb.append("TB_NODRAW=0\n");
            sb.append("INDIRECT=0\n");
            sb.append("\n");
            sb.append("# Rendering fixes (all on by default).\n");
            sb.append("MIP=1\n");
            sb.append("ALPHADILATE=1\n");
            sb.append("NOMIP=0\n");
            sb.append("ARENA=1\n");
            sb.append("EARLYCUTOUT=0\n");
            sb.append("NOCULL=0\n");
            sb.append("DEPTHBIAS=0\n");
            sb.append("DLPULL=1\n");
            sb.append("DLMERGE=1\n");
            sb.append("NOENTDRAW=0\n");
            sb.append("APPLYREUSE=1\n");
            sb.append("REUSEALL=1\n");
            sb.append("PIPESTATE=1\n");
            sb.append("VF_MEMO=1\n");
            sb.append("\n");
            sb.append("# Benchmark harness (off unless you are measuring).\n");
            sb.append("AUTOJOIN=0\n");
            sb.append("AUTOQUIT=0\n");
            sb.append("PIN_CAMERA=0\n");
            sb.append("PROFILE_DUMP=0\n");
            sb.append("DRAWTIMING=0\n");
            sb.append("GPUDRAWTIMING=0\n");
            out.write(sb.toString().getBytes("UTF-8"));
            resolvedFile = f;
        } catch (Throwable t) {
            VulkanMod.LOGGER.warn("[config] could not write template {}: {}", f.getPath(), t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                    // nothing useful to do while closing a config file
                }
            }
        }
    }

    /**
     * Accepts both the short ({@code TERRAIN_BATCH}) and the legacy
     * ({@code TERRAIN_BATCH}) spelling, so call sites that still pass
     * the old full environment-variable name keep working.
     */
    private static String norm(String key) {
        if (key == null) {
            return null;
        }
        String k = key.trim();
        final String prefix = "VULKANMOD_";
        if (k.length() > prefix.length() && k.startsWith(prefix)) {
            k = k.substring(prefix.length()).trim();
        }
        return k;
    }

    /** Raw string value, or {@code null} when the key is not set. */
    public static String get(String key) {
        ensureLoaded();
        return VALUES.get(norm(key));
    }

    /** True when the key is present and not the empty string. */
    public static boolean isSet(String key) {
        String v = get(key);
        return v != null && v.length() > 0;
    }

    public static boolean getBool(String key, boolean def) {
        ensureLoaded();
        return getBoolRaw(key, def);
    }

    /** Reads the already-loaded map; does not trigger a load (avoids recursion). */
    private static boolean getBoolRaw(String key, boolean def) {
        String v = VALUES.get(norm(key));
        if (v == null || v.length() == 0) {
            return def;
        }
        v = v.trim();
        if ("1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v)
                || "on".equalsIgnoreCase(v)) {
            return true;
        }
        if ("0".equals(v) || "false".equalsIgnoreCase(v) || "no".equalsIgnoreCase(v)
                || "off".equalsIgnoreCase(v)) {
            return false;
        }
        return def;
    }

    public static int getInt(String key, int def) {
        String v = get(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    public static File file() {
        ensureLoaded();
        return resolvedFile;
    }
}
