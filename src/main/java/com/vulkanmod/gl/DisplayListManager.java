package com.yuhan123.vulkanmod.gl;

import com.yuhan123.vulkanmod.render.PipelineManager;
import com.yuhan123.vulkanmod.render.shader.ShaderInstance;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GL display-list emulation for the Vulkan renderer.
 *
 * 1.12.2 compiles entity models and other geometry into display lists
 * (glNewList..glEndList) and replays them with glCallList. The mod captures the
 * vertex data during compilation and replays it as Vulkan draws. Captures must
 * remember their shader so the replay binds the right pipeline.
 *
 * The modelview at the replay differs from the compile-time one: at compile time
 * the stack holds the OUTER transform times the model's internal (pose/box)
 * transforms, while at replay time only the OUTER transform is current. Each
 * captured draw therefore stores its full compile-time modelview, and the replay
 * rebuilds the draw matrix as
 *
 *     replayMV = currentMV * startMV^-1 * capturedMV
 *
 * (startMV = the modelview at glNewList, i.e. the outer transform). Without this
 * the model's internal transforms are lost and entities render as a collapsed
 * blob of boxes.
 */
public class DisplayListManager {
    // The real GL returns -1/0 for glGenLists (no display-list support on the
    // hidden context), so each glNewList gets a fresh INTERNAL id and the GL id
    // is mapped to it. Different models then never share a list.
    private static final Map<Integer, List<Runnable>> displayLists = new HashMap<>();
    private static final Map<Integer, Integer> glToInternal = new HashMap<>();
    private static int recordingInternal = -1;
    private static boolean recording = false;
    private static int nextInternalId = 1;
    private static Matrix4f recordingStartMV = new Matrix4f();

    public static boolean isRecordingList() {
        return recording;
    }

    private static int dlLogs = 0;

    public static void startList(int glId) {
        int internalId = nextInternalId++;
        glToInternal.put(glId, internalId);
        displayLists.put(internalId, new ArrayList<>());
        recordingInternal = internalId;
        recording = true;
        recordingStartMV = copyCurrentMV();
        if (dlLogs < 8) {
            com.yuhan123.vulkanmod.VulkanMod.LOGGER.info("[DLDBG] startList glId={} internal={}", glId, internalId);
            dlLogs++;
        }
    }

    public static void endList() {
        recording = false;
        recordingInternal = -1;
    }

    public static void replayList(int glId) {
        Integer internalId = glToInternal.get(glId);
        if (internalId == null) {
            if (dlLogs < 8) {
                com.yuhan123.vulkanmod.VulkanMod.LOGGER.info("[DLDBG] replayList glId={} -> NOT FOUND", glId);
                dlLogs++;
            }
            return;
        }
        List<Runnable> draws = displayLists.get(internalId);
        if (dlLogs < 8) {
            com.yuhan123.vulkanmod.VulkanMod.LOGGER.info("[DLDBG] replayList glId={} internal={} draws={}", glId, internalId, draws != null ? draws.size() : -1);
            dlLogs++;
        }
        if (draws != null) {
            for (Runnable r : draws) {
                r.run();
            }
        }
    }

    public static void deleteLists(int glId) {
        Integer internalId = glToInternal.remove(glId);
        if (internalId != null) {
            displayLists.remove(internalId);
        }
    }

    public static int genLists(int count) {
        // Keep returning a positive id so vanilla's 0-check in
        // GLAllocation.generateDisplayLists does not throw.
        int id = nextInternalId;
        nextInternalId += count;
        return id;
    }

    public static void captureDisplayListDraw(ByteBuffer data, int mode, VertexFormat vertexFormat, int count) {
        if (!recording || recordingInternal < 0)
            return;

        int size = count * vertexFormat.getSize();
        ByteBuffer copy = MemoryUtil.memAlloc(size);
        ByteBuffer src = data.duplicate();
        src.position(0);
        src.limit(size);
        copy.put(src);
        copy.flip();
        ByteBuffer frozen = copy;

        ShaderInstance shader = PipelineManager.chooseShader(vertexFormat);
        Matrix4f capturedMV = copyCurrentMV();
        Matrix4f startMV = new Matrix4f(recordingStartMV);

        displayLists.get(recordingInternal).add(() -> {
            replayDraw(frozen, mode, vertexFormat, count, capturedMV, startMV, shader);
        });
    }

    private static int replayLogs = 0;

    private static void replayDraw(ByteBuffer data, int mode, VertexFormat vertexFormat, int count,
                                   Matrix4f capturedMV, Matrix4f startMV, ShaderInstance shader) {
        if (shader == null)
            return;

        // replayMV = currentMV * startMV^-1 * capturedMV
        Matrix4f startInv = new Matrix4f(startMV).invert();
        Matrix4f mv = new Matrix4f(copyCurrentMV()).mul(startInv).mul(capturedMV);
        Matrix4f p = new Matrix4f(VRenderSystem.getProjectionMatrix().buffer.asFloatBuffer());
        Matrix4f mvp = p.mul(mv);

        if (replayLogs < 4) {
            com.yuhan123.vulkanmod.VulkanMod.LOGGER.info("[RPYDBG] count={} mode={} fmt={} curMV[3]={} capMV[3]={} startMV[3]={} mvp[3]={}",
                    count, mode, vertexFormat,
                    copyCurrentMV().m30(), capturedMV.m30(), startMV.m30(), mvp.m30());
            replayLogs++;
        }

        // Temporarily override the MVP uniform source for this draw.
        float[] saved = new float[16];
        VRenderSystem.MVP.buffer.asFloatBuffer().get(saved);
        mvp.get(VRenderSystem.MVP.buffer.asFloatBuffer());
        try {
            shader.apply();
            Renderer.getDrawer().draw(data, mode, vertexFormat, count);
        } finally {
            VRenderSystem.MVP.buffer.asFloatBuffer().put(saved);
        }
    }

    private static Matrix4f copyCurrentMV() {
        return new Matrix4f(VRenderSystem.getModelViewMatrix().buffer.asFloatBuffer());
    }
}
