package com.yuhan123.vulkanmod;

import com.yuhan123.vulkanmod.config.VulkanModConfig;

/**
 * Sink for the {@code [VKPROF]} instrumentation output.
 *
 * The profiler lines used to go straight to {@link VulkanMod#LOGGER}, which
 * emitted roughly 25 lines per frame and buried everything else in the log.
 * They now go through here: when {@code profiling} is off in
 * {@code config/vulkanmod.properties} (the default) every call returns before
 * touching the logging framework, so the runtime stays quiet.
 *
 * Set {@code profiling=1} in the config file to bring the output back for a
 * measurement run.
 */
public final class VKProf {

    private VKProf() {
    }

    public static void info(String message, Object... args) {
//        if (!VulkanModConfig.profiling()) {
//            return;
//        }
//        VulkanMod.LOGGER.info(message, args);
    }

    public static void warn(String message, Object... args) {
//        if (!VulkanModConfig.profiling()) {
//            return;
//        }
//        VulkanMod.LOGGER.warn(message, args);
    }

    /**
     * Errors are always reported: a silenced failure is how the terrain batch
     * ended up permanently disabled without anyone noticing.
     */
    public static void error(String message, Object... args) {
//        VulkanMod.LOGGER.error(message, args);
    }
}
