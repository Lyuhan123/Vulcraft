package com.yuhan123.vulkanmod.gl;

import com.yuhan123.vulkanmod.render.PipelineManager;
import com.yuhan123.vulkanmod.render.shader.ShaderInstance;
import com.yuhan123.vulkanmod.render.util.FrameProfiler;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
import com.yuhan123.vulkanmod.vulkan.memory.MemoryTypes;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.VertexBuffer;
import com.yuhan123.vulkanmod.vulkan.memory.buffer.index.AutoIndexBuffer;
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
    /**
     * Fold the per-quad draws of a display list into one draw.
     *
     * 1.12.2's TexturedQuad.draw calls Tessellator.draw() for every single quad,
     * so compiling one model box captures SIX draws. Replaying a mob then means
     * dozens of tiny 4-vertex draws per model part, each paying a full shader
     * apply (descriptor bind, push constant, texture rebind loop) for four
     * vertices - the dominant cost of the entity pass.
     *
     * Within one list every captured quad shares the same shader, vertex format,
     * draw mode and relative matrix (the model's own transforms are baked into
     * the vertices; no matrix op happens between two quads of a list), and the
     * captured bytes are already contiguous in the list's buffer. Consecutive
     * draws that agree on all of those therefore collapse into a single draw of
     * 4N vertices, which the QUADS index pattern expands identically.
     *
     * VULKANMOD_DLMERGE=0 disables it, for A/B runs.
     */
    private static final boolean MERGE_QUADS = !"0".equals(System.getenv("VULKANMOD_DLMERGE"));

    /** TEMPORARY: VULKANMOD_NOENTDRAW=1 skips entity display-list draws. See replayList. */
    private static final boolean NO_ENT_DRAW = System.getenv("VULKANMOD_NOENTDRAW") != null;

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
    private static final Matrix4f SCRATCH_PV = new Matrix4f();

    // Vertex data gathered while a list is being compiled. Reused and grown
    // geometrically so compiling a model allocates nothing per draw.
    private static final ByteBuilder recordingData = new ByteBuilder(4096);

    public static boolean isRecordingList() {
        return recording;
    }

//    private static int dlLogs = 0;

    public static void startList(int glId) {
        // Capture uses the current modelview as the list's outer transform - the
        // reference the replay divides out. It must therefore be the CURRENT GL
        // state, not whatever the last draw left in the shared buffer: same
        // failure mode as the replay's read, see VRenderSystem.pullModelViewFloatBuffer.
        // The vanilla call site is glNewList, which is not reached from a draw,
        // so nothing has refreshed the buffers for this moment either.
        recordingStartMV.set(VRenderSystem.pullModelViewFloatBuffer());
        // The GL id space and the internal id space used to be the SAME counter,
        // advanced from two places: genLists() returned nextInternalId and then
        // bumped it, and startList() allocated nextInternalId++ again. Vanilla
        // calls genLists once per ModelRenderer and glNewList once per list, so
        // the two interleaved: genLists handed out 1 while glNewList(1) consumed
        // internal 2, and the next genLists then returned 2 - an id already in
        // use as another model's internal list. Two different ModelRenderers
        // therefore ended up sharing one DisplayList, and the one compiled last
        // won: every mob part replayed the same geometry, drawn at each part's
        // transform, which is the scattered heap of torn quads.
        //
        // Now the id is allocated once, in genLists(), and recorded here. A
        // glNewList for an id we never handed out (GLAllocation was not the
        // source, e.g. a hardcoded id) still gets a fresh one.
        Integer internalId = glToInternal.get(glId);
        if (internalId == null) {
            internalId = nextInternalId++;
        }
        // TEMPORARY: report when one GL id is re-mapped to a second internal list,
        DisplayList prev = displayLists.put(internalId, new DisplayList());
        if (prev != null) {
            prev.free();
        }
        recordingInternal = internalId;
        recording = true;
        recordingData.clear();
    }

    public static void endList() {
        DisplayList list = recordingInternal >= 0 ? displayLists.get(recordingInternal) : null;

        if (list != null) {
            if (MERGE_QUADS) {
                list.mergeQuadRuns();
            }

            if (recordingData.length() > 0) {
                list.upload(recordingData.buffer(), recordingData.length());
            }

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
            FrameProfiler.onDisplayListReplay();


            // Pass 28: the replay time that belongs to the entity loop.
            //
            // The frame-global `dlMs` row is behind DRAWTIMING and covers every
            // replay in the frame, so it cannot say how much of the 129-175 us
            // `renderEntityStatic` window is replay. This pair is always on and is
            // paid only while the entity loop's own iterator has the window open
            // (53-67 replays a frame, ~7.6 per rendered entity). `entDlCalls` is the
            // attach check: it must be non-zero and no larger than the frame's
            // `dlReplay`, or the accumulator never ran and the row reads like free.
            final boolean entDl = FrameProfiler.entLoopOpen();
            final long __t0 = entDl ? System.nanoTime() : 0L;

            long __t = FrameProfiler.start();
            VertexBuffer vb = list.vertexBuffer;            // The modelview is constant across every draw of one list (the replay
            // runs under a single outer transform), so PV = P * MV is computed once
            // here and each draw only applies its own relative matrix. Previously
            // every draw did two 4x4 multiplies (MV*relative then P*that); by
            // associativity P*(MV*relative) == (P*MV)*relative, so one multiply per
            // draw suffices. Matches the old result exactly, halves the matmuls.
            // pullModelViewFloatBuffer(), not modelViewFloatBuffer(): the replay
            // is the one consumer that never triggers a refresh itself, so the
            // lazy read returns the previous draw's modelview. See VRenderSystem.
            SCRATCH_MV.set(VRenderSystem.pullModelViewFloatBuffer());
            SCRATCH_PROJ.set(VRenderSystem.projectionFloatBuffer());
            SCRATCH_PV.set(SCRATCH_PROJ).mul(SCRATCH_MV);
            // TEMPORARY: VULKANMOD_NOENTDRAW=1 suppresses entity display-list
            // draws. Rendering one fixed scene with and without them and diffing
            // isolates exactly which pixels entities contribute - which answers
            // whether they are scattered around the world or piled onto the
            // player, which is what the user reports seeing in third person.
            if (entDl && NO_ENT_DRAW) {
                return;
            }
            for (CapturedDraw draw : list.draws) {
                replayDraw(vb, draw);
            }
            FrameProfiler.addDisplayListNanos(__t);

            if (entDl) {
                FrameProfiler.onEntityDlReplay(System.nanoTime() - __t0);
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
        //
        // The id this returns IS the internal id: it is reserved in glToInternal
        // here, so the glNewList that follows reuses it instead of allocating a
        // second one. Handing out an id and allocating the internal id separately
        // made the two id spaces overlap (see startList). count is always 1 in
        // vanilla's ModelRenderer path; a larger count gets consecutive ids.
        int id = nextInternalId;
        nextInternalId += Math.max(1, count);
        for (int i = 0; i < Math.max(1, count); ++i) {
            glToInternal.put(id + i, id + i);
        }
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
        SCRATCH_MV.set(VRenderSystem.pullModelViewFloatBuffer());
        Matrix4f relative = new Matrix4f(recordingStartMV).invert().mul(SCRATCH_MV);

        list.draws.add(new CapturedDraw(byteOffset, size, count, mode, vertexFormat, relative, shader));
    }

//    private static int replayLogs = 0;

    private static void replayDraw(VertexBuffer vertexBuffer, CapturedDraw draw) {
        ShaderInstance shader = draw.shader;
        if (shader == null)
            return;

        FrameProfiler.onDisplayListDraw();
        FrameProfiler.logDrawState("entity");

        Matrix4f relative = draw.relative;

        // mvp = P * (currentMV * relative) == (P * currentMV) * relative == PV * relative
        // (PV was precomputed once in replayList; see there for why this is equivalent).
        SCRATCH_MVP.set(SCRATCH_PV).mul(relative);

//        if (repla;yLogs < 4) {
//                com.yuhan123.vulkanmod.VulkanMod.LOGGER.info("[RPYDBG] count={} mode={} fmt={} curMV[3]={} rel[3]={} mvp[3]={}",
//                        draw.vertexCount, draw.mode, draw.vertexFormat,
//                        SCRATCH_MV.m30(), relative.m30(), SCRATCH_MVP.m30());
//                replayLogs++
//        }

        // Temporarily override the MVP uniform source for this draw. The MVP is
        // computed lazily, so writing it here and marking it clean is enough -
        // no save/restore copy needed.
        SCRATCH_MVP.get(VRenderSystem.mvpFloatBuffer());
        VRenderSystem.markMvpClean();

        if (FrameProfiler.entLoopOpen()) {
            // Paired with the row above: position correctness says nothing about
        }

        try {
            long __t = FrameProfiler.start();
            shader.apply();
            FrameProfiler.addDisplayListApplyNanos(__t);

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
        /** Byte length of this draw's vertices, for contiguity checks when merging. */
        final int byteSize;
        int vertexCount;
        final int mode;
        final VertexFormat vertexFormat;
        final Matrix4f relative;
        final ShaderInstance shader;
        /** Only used when the persistent upload failed. */
        ByteBuffer fallback;

        CapturedDraw(int byteOffset, int byteSize, int vertexCount, int mode, VertexFormat vertexFormat,
                     Matrix4f relative, ShaderInstance shader) {
            this.byteOffset = byteOffset;
            this.byteSize = byteSize;
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

        /**
         * Collapses consecutive quads that share every piece of state into one
         * draw. The geometry of a merged run is contiguous in this list's vertex
         * buffer, so the run keeps the first draw's byteOffset and simply sums the
         * vertex counts - the QUADS index pattern then expands 4N vertices exactly
         * as it would have expanded N separate quads.
         *
         * Only mode QUADS is merged: a triangle fan or strip is defined by the
         * whole run, so two adjacent ones cannot be concatenated.
         */
        void mergeQuadRuns() {
            final int size = this.draws.size();
            if (size < 2) {
                return;
            }

            int write = 0;
            for (int read = 0; read < size; ) {
                CapturedDraw first = this.draws.get(read);

                if (first.mode != 7 || first.vertexCount <= 0) {
                    this.draws.set(write++, first);
                    read++;
                    continue;
                }

                int mergedVertices = first.vertexCount;
                int next = read + 1;

                while (next < size) {
                    CapturedDraw candidate = this.draws.get(next);

                    if (candidate.mode != 7
                            || candidate.shader != first.shader
                            || candidate.vertexFormat != first.vertexFormat
                            || !candidate.relative.equals(first.relative)
                            || first.byteOffset + mergedVertices * first.vertexFormat.getSize()
                               != candidate.byteOffset
                            || mergedVertices + candidate.vertexCount > AutoIndexBuffer.U16_MAX_VERTEX_COUNT) {
                        break;
                    }

                    mergedVertices += candidate.vertexCount;
                    next++;
                }

                if (next > read + 1) {
                    first.vertexCount = mergedVertices;
                }

                // TEMPORARY: a QUADS draw whose vertex count is not a multiple of
                // 4 makes genQuadIndices() read past the last whole quad and
                // expand the next part's vertices into this draw - scrambled

                this.draws.set(write++, first);
                read = next;
            }

            if (write < size) {
                this.draws.subList(write, size).clear();
            }
        }

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
