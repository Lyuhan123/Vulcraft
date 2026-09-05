package com.yuhan123.vulkanmod.render.util;

import com.yuhan123.vulkanmod.VulkanMod;

/**
 * Cheap per-frame instrumentation.
 *
 * The point is to answer "is this CPU-bound or GPU-bound?" before spending
 * effort on the wrong side:
 *
 *  - fenceWait is the CPU sitting in vkWaitForFences / vkAcquireNextImageKHR,
 *    i.e. blocked because the GPU (or the presentation engine) is behind. A
 *    large share of the frame there means the CPU work is not the limit.
 *  - cpu is everything else: record time, uniform uploads, draw submission.
 *
 * Counters are plain longs incremented on the hot paths; the only per-frame
 * costs are a few nanoTime() calls and the periodic report.
 */
public final class FrameProfiler {

    /** Set to false to remove all overhead (checks are still branches). */
    public static volatile boolean ENABLED = true;

    private static final int REPORT_INTERVAL = 120;

    private static long frames;
    private static long totalNanos;
    private static long fenceWaitNanos;
    private static long submitNanos;
    private static long shaderApplyNanos;
    private static long drawRecordNanos;

    private static long draws;
    private static long pipelineBinds;
    private static long descriptorUpdates;
    private static long vertexBytesCopied;
    private static long uniformBytes;

    /** GPU timestamp delta across the main render pass, fed by Renderer's query pool. */
    private static long gpuPassNanos;

    /** Client game-logic tick time (Minecraft.runTick), split out of the cpu total. */
    private static long gameTickNanos;

    private static long frameStart;
    private static long markStart;
    private static long tickStart;

    private FrameProfiler() {
    }

    /** Timestamp helper for nestable segments (no shared state). */
    public static long start() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void addShaderApply(long start) {
        if (ENABLED)
            shaderApplyNanos += System.nanoTime() - start;
    }

    public static void addDrawRecord(long start) {
        if (ENABLED)
            drawRecordNanos += System.nanoTime() - start;
    }

    public static void beginFrame() {
        if (!ENABLED)
            return;
        frameStart = System.nanoTime();
    }

    public static void endFrame() {
        if (!ENABLED)
            return;

        totalNanos += System.nanoTime() - frameStart;

        if (++frames >= REPORT_INTERVAL) {
            report();
        }
    }

    /** Marks the start of a blocking GPU sync (fence wait / image acquire). */
    public static void beginFenceWait() {
        if (ENABLED)
            markStart = System.nanoTime();
    }

    public static void endFenceWait() {
        if (ENABLED)
            fenceWaitNanos += System.nanoTime() - markStart;
    }

    public static void beginSubmit() {
        if (ENABLED)
            markStart = System.nanoTime();
    }

    public static void endSubmit() {
        if (ENABLED)
            submitNanos += System.nanoTime() - markStart;
    }

    public static void onDraw() {
        if (ENABLED)
            draws++;
    }

    public static void onPipelineBind() {
        if (ENABLED)
            pipelineBinds++;
    }

    public static void onDescriptorUpdate() {
        if (ENABLED)
            descriptorUpdates++;
    }

    public static void onVertexCopy(int bytes) {
        if (ENABLED)
            vertexBytesCopied += bytes;
    }

    public static void onUniformBytes(int bytes) {
        if (ENABLED)
            uniformBytes += bytes;
    }

    /** Adds one GPU timestamp delta (nanoseconds) to the per-report accumulator. */
    public static void onGpuPassNanos(long nanos) {
        if (ENABLED)
            gpuPassNanos += nanos;
    }

    public static void beginGameTick() {
        if (ENABLED)
            tickStart = System.nanoTime();
    }

    public static void endGameTick() {
        if (ENABLED)
            gameTickNanos += System.nanoTime() - tickStart;
    }

    private static void report() {
        final double frameMs = totalNanos / 1e6 / frames;
        final double fenceMs = fenceWaitNanos / 1e6 / frames;
        final double submitMs = submitNanos / 1e6 / frames;
        final double cpuMs = frameMs - fenceMs;
        final double applyMs = shaderApplyNanos / 1e6 / frames;
        final double drawMs = drawRecordNanos / 1e6 / frames;
        final double gpuMs = gpuPassNanos / 1e6 / frames;
        final double tickMs = gameTickNanos / 1e6 / frames;

        VulkanMod.LOGGER.info(
                "[VKPROF] fps={} frame={}ms (cpu={} fence={} submit={} gpuPass={}ms tick={}ms) apply={} draw={} draws={} binds={} descUpd={} vbCopy={}MB ubo={}MB",
                format(1000.0 / frameMs, 1), format(frameMs, 2), format(cpuMs, 2), format(fenceMs, 2),
                format(submitMs, 2), format(gpuMs, 2), format(tickMs, 2), format(applyMs, 2), format(drawMs, 2),
                format(draws / (double) frames, 0), format(pipelineBinds / (double) frames, 0),
                format(descriptorUpdates / (double) frames, 0),
                format(vertexBytesCopied / 1048576.0 / frames, 2),
                format(uniformBytes / 1048576.0 / frames, 2));

        reset();
    }

    private static String format(double v, int decimals) {
        return String.format("%." + decimals + "f", v);
    }

    private static void reset() {
        frames = 0;
        totalNanos = 0;
        fenceWaitNanos = 0;
        submitNanos = 0;
        shaderApplyNanos = 0;
        drawRecordNanos = 0;
        draws = 0;
        pipelineBinds = 0;
        descriptorUpdates = 0;
        vertexBytesCopied = 0;
        uniformBytes = 0;
        gpuPassNanos = 0;
        gameTickNanos = 0;
    }
}
