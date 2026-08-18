package com.yuhan123.vulkanmod.gl;

import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import com.yuhan123.vulkanmod.vulkan.shader.Pipeline;

public class VkGlProgram {
    private static int ID_COUNTER = 1;
    private static final Int2ReferenceOpenHashMap<VkGlProgram> map = new Int2ReferenceOpenHashMap<>();
    private static int boundProgramId = 0;
    private static VkGlProgram boundProgram;

    public static VkGlProgram getBoundProgram() {
        return boundProgram;
    }

    public static VkGlProgram getProgram(int id) {
        return map.get(id);
    }

    public static int genProgramId() {
        int id = ID_COUNTER;
        map.put(id, new VkGlProgram(id));
        ID_COUNTER++;
        return id;
    }

    public static void glUseProgram(int id) {
        boundProgramId = id;
        boundProgram = map.get(id);

        if (id <= 0) {
            return;
        }

        if (boundProgram == null) {
            throw new NullPointerException("bound texture is null");
        }

    }

    int id;
    Pipeline pipeline;

    VkGlProgram(int i) {
        this.id = i;
    }

    public void bindPipeline(Pipeline pipeline) {
        this.pipeline = pipeline;
    }

    public static void glDeleteProgram(int id) {
        map.remove(id);
    }

    public Pipeline getPipeline() {
        return this.pipeline;
    }
}
