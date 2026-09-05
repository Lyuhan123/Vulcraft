package com.yuhan123.vulkanmod.gl;

import com.yuhan123.vulkanmod.render.PipelineManager;
import com.yuhan123.vulkanmod.render.shader.ShaderInstance;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
import com.yuhan123.vulkanmod.vulkan.memory.MemoryTypes;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.VertexBuffer;
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
 *
 * `startMV^-1 * capturedMV` is constant for a given captured draw, so it is
 * folded into a single "relative" matrix once at capture time. The replay then
 * only needs `P * (currentMV * relative)`: no matrix inversion and no allocation
 * on a path that runs once per replayed draw (i.e. thousands of times per frame).
 *
 * Geometry is packed once per list into a single persistent vertex buffer at
 * glEndList and replayed by byte offset, so a replayed draw copies no vertex
 * data at all - previously every replay re-copied the model into the per-frame
 * vertex buffer, which is the same static data every frame.
 */
public class DisplayListManager {
    // The real GL returns -1/0 for glGenLists (no display-list support on the
    // hidden context), so each glNewList gets a fresh INTERNAL id and the GL id
    // is mapped to it. Different models then never share a list.
    private static final Map<Integer, DisplayList> displayLists = new HashMap<>();
    private static final Map<Integer, Integer> glToInternal = new HashMap<>();
    private static int recordingInternal = -1;
    private static boolean recording = false;
    private static int nextInternalId = 1;
    private static final Matrix4f recordingStartMV = new Matrix4f();

    // Scratch matrices reused by every replay: replayDraw runs once per replayed
    // draw (thousands of times per frame), so it must not allocate.
    private static final Matrix4f SCRATCH_MV = new Matrix4f();
    private static final Matrix4f SCRATCH_PROJ = new Matrix4f();
    private static final Matrix4f SCRATCH_MVP = new Matrix4f();

    // Vertex data gathered while a list is being compiled. Reused and grown
    // geometrically so compiling a model allocates nothing per draw.
    private static final ByteBuilder recordingData = new ByteBuilder(4096);

    public static boolean isRecordingList() {
        return recording;
    }

//    private static int dlLogs = 0;

    public static void startList(int glId) {
        int internalId = nextInternalId++;
        glToInternal.put(glId, internalId);
        // Overwriting an id replaces whatever list was there; release its buffer.
        DisplayList prev = displayLists.put(internalId, new DisplayList());
        if (prev != null) {
            prev.free();
        }
        recordingInternal = internalId;
        recording = true;
        recordingStartMV.set(VRenderSystem.modelViewFloatBuffer());
        recordingData.clear();
//        if (dlLogs < 8) {
//            com.yuhan123.vulkanmod.VulkanMod.LOGGER.info("[DLDBG] startList glId={} internal={}", glId, internalId);
//            dlLogs++;
//        }
    }

    public static void endList() {
        DisplayList list = recordingInternal >= 0 ? displayLists.get(recordingInternal) : null;

        if (list != null && recordingData.length() > 0) {
            list.upload(recordingData.buffer(), recordingData.length());
        }

        recording = false;
        recordingInternal = -1;
        recordingData.clear();
    }

    public static void replayList(int glId) {
        Integer internalId = glToInternal.get(glId);
        if (internalId == null) {
//            if (dlLogs < 8) {
//                com.yuhan123.vulkanmod.VulkanMod.LOGGER.info("[DLDBG] replayList glId={} -> NOT FOUND", glId);
//                dlLogs++;
//            }
            return;
        }
        DisplayList list = displayLists.get(internalId);
//        if (dlLogs < 8) {
//            com.yuhan123.vulkanmod.VulkanMod.LOGGER.info("[DLDBG] replayList glId={} internal={} draws={}",
//                    glId, internalId, list != null ? list.draws.size() : -1);
//            dlLogs++;
//        }
        if (list != null) {
            VertexBuffer vb = list.vertexBuffer;
            for (CapturedDraw draw : list.draws) {
                replayDraw(vb, draw);
            }
        }
    }

    public static void deleteLists(int glId) {
        Integer internalId = glToInternal.remove(glId);
        if (internalId != null) {
            DisplayList list = displayLists.remove(internalId);
            if (list != null) {
                list.free();
            }
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

        DisplayList list = displayLists.get(recordingInternal);
        if (list == null)
            return;

        int size = count * vertexFormat.getSize();
        if (data.remaining() < size)
            return;

        // Geometry is copied into the list's staging area now (the caller's
        // buffer is reused immediately afterwards) and uploaded to one
        // persistent vertex buffer when the list is closed.
        int byteOffset = recordingData.append(data, size);

        ShaderInstance shader = PipelineManager.chooseShader(vertexFormat);

        // Fold startMV^-1 * capturedMV into one matrix now, so the replay does
        // not have to invert startMV for every single draw.
        SCRATCH_MV.set(VRenderSystem.modelViewFloatBuffer());
        Matrix4f relative = new Matrix4f(recordingStartMV).invert().mul(SCRATCH_MV);

        list.draws.add(new CapturedDraw(byteOffset, count, mode, vertexFormat, relative, shader));
    }

//    private static int replayLogs = 0;

    private static void replayDraw(VertexBuffer vertexBuffer, CapturedDraw draw) {
        ShaderInstance shader = draw.shader;
        if (shader == null)
            return;

        Matrix4f relative = draw.relative;

        // mvp = P * (currentMV * relative)
        SCRATCH_MV.set(VRenderSystem.modelViewFloatBuffer());
        SCRATCH_PROJ.set(VRenderSystem.projectionFloatBuffer());
        SCRATCH_MVP.set(SCRATCH_MV).mul(relative);
        SCRATCH_PROJ.mul(SCRATCH_MVP);

//        if (repla;yLogs < 4) {
//                com.yuhan123.vulkanmod.VulkanMod.LOGGER.info("[RPYDBG] count={} mode={} fmt={} curMV[3]={} rel[3]={} mvp[3]={}",
//                        draw.vertexCount, draw.mode, draw.vertexFormat,
//                        SCRATCH_MV.m30(), relative.m30(), SCRATCH_PROJ.m30());
//                replayLogs++
//        }

        // Temporarily override the MVP uniform source for this draw. The MVP is
        // computed lazily, so writing it here and marking it clean is enough -
        // no save/restore copy needed.
        SCRATCH_PROJ.get(VRenderSystem.mvpFloatBuffer());
        VRenderSystem.markMvpClean();
        try {
            shader.apply();

            if (vertexBuffer != null) {
                // Persistent geometry: bind it, copy nothing this frame.
                Renderer.getDrawer().drawPersistent(vertexBuffer, draw.byteOffset, draw.mode,
                                                    draw.vertexFormat, draw.vertexCount);
            } else {
                draw.fallback.rewind();
                Renderer.getDrawer().draw(draw.fallback, draw.mode, draw.vertexFormat, draw.vertexCount);
            }
        } finally {
            VRenderSystem.markMvpDirty();
        }
    }

    /** One draw captured while a display list was being compiled. */
    private static final class CapturedDraw {
        final int byteOffset;
        final int vertexCount;
        final int mode;
        final VertexFormat vertexFormat;
        final Matrix4f relative;
        final ShaderInstance shader;
        /** Only used when the persistent upload failed. */
        ByteBuffer fallback;

        CapturedDraw(int byteOffset, int vertexCount, int mode, VertexFormat vertexFormat,
                     Matrix4f relative, ShaderInstance shader) {
            this.byteOffset = byteOffset;
            this.vertexCount = vertexCount;
            this.mode = mode;
            this.vertexFormat = vertexFormat;
            this.relative = relative;
            this.shader = shader;
        }
    }

    private static final class DisplayList {
        final List<CapturedDraw> draws = new ArrayList<>();
        VertexBuffer vertexBuffer;

        void upload(ByteBuffer src, int length) {
            try {
                VertexBuffer buffer = new VertexBuffer(length, MemoryTypes.HOST_MEM);
                buffer.copyBuffer(src, length);
                this.vertexBuffer = buffer;
            } catch (Throwable t) {
                // Out of memory: fall back to copying from a CPU mirror at replay.
                this.vertexBuffer = null;
                for (CapturedDraw draw : this.draws) {
                    if (draw.fallback == null) {
                        ByteBuffer copy = MemoryUtil.memAlloc(draw.vertexCount * draw.vertexFormat.getSize());
                        src.position(draw.byteOffset);
                        src.limit(draw.byteOffset + copy.capacity());
                        copy.put(src);
                        copy.flip();
                        draw.fallback = copy;
                    }
                }
                src.clear();
            }
        }

        void free() {
            if (this.vertexBuffer != null) {
                this.vertexBuffer.scheduleFree();
                this.vertexBuffer = null;
            }
            for (CapturedDraw draw : this.draws) {
                if (draw.fallback != null) {
                    MemoryUtil.memFree(draw.fallback);
                    draw.fallback = null;
                }
            }
            this.draws.clear();
        }
    }

    /** Reusable, growable byte sink used while compiling a display list. */
    private static final class ByteBuilder {
        private ByteBuffer buffer;
        private int length;

        ByteBuilder(int initial) {
            this.buffer = MemoryUtil.memAlloc(initial);
        }

        void clear() {
            this.length = 0;
        }

        int length() {
            return this.length;
        }

        /** Rewound view over the gathered bytes (position 0, limit = capacity). */
        ByteBuffer buffer() {
            this.buffer.clear();
            return this.buffer;
        }

        int append(ByteBuffer src, int size) {
            ensureCapacity(this.length + size);
            int offset = this.length;

            // src may be a view with a non-zero position; copy that range.
            int pos = src.position();
            src.position(pos);
            ByteBuffer slice = src.slice(pos, size);
            this.buffer.position(offset);
            this.buffer.put(slice);
            src.position(pos);
            this.length += size;

            return offset;
        }

        private void ensureCapacity(int needed) {
            if (needed <= this.buffer.capacity())
                return;

            int newCapacity = this.buffer.capacity();
            while (newCapacity < needed) {
                newCapacity *= 2;
            }

            ByteBuffer bigger = MemoryUtil.memAlloc(newCapacity);
            this.buffer.position(0);
            this.buffer.limit(this.length);
            bigger.put(this.buffer);
            MemoryUtil.memFree(this.buffer);
            this.buffer = bigger;
        }
    }
}
