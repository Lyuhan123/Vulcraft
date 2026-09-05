package com.yuhan123.vulkanmod.vulkan.shader;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.NativeResource;
import org.lwjgl.util.shaderc.ShadercIncludeResolveI;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultReleaseI;
import org.lwjgl.vulkan.VK12;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memASCII;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.system.MemoryUtil.memUTF8;
import static org.lwjgl.util.shaderc.Shaderc.*;

public class SPIRVUtils {
    private static final boolean DEBUG = false;
    private static final boolean OPTIMIZATIONS = true;

    private static long compiler;
    private static long options;

    //The dedicated Includer and Releaser Inner Classes used to Initialise #include Support for ShaderC
    private static final ShaderIncluder SHADER_INCLUDER = new ShaderIncluder();
    private static final ShaderReleaser SHADER_RELEASER = new ShaderReleaser();
    private static final long pUserData = 0;

    private static ObjectArrayList<String> includePaths;

    static {
        initCompiler();
    }

    private static void initCompiler() {
        compiler = shaderc_compiler_initialize();

        if (compiler == NULL) {
            throw new RuntimeException("Failed to create shader compiler");
        }

        options = shaderc_compile_options_initialize();

        if (options == NULL) {
            throw new RuntimeException("Failed to create compiler options");
        }

        if (OPTIMIZATIONS)
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);

        if (DEBUG)
            shaderc_compile_options_set_generate_debug_info(options);

        shaderc_compile_options_set_target_env(options, shaderc_env_version_vulkan_1_2, VK12.VK_API_VERSION_1_2);
        shaderc_compile_options_set_include_callbacks(options, SHADER_INCLUDER, SHADER_RELEASER, pUserData);

        includePaths = new ObjectArrayList<>();
        addIncludePath("/assets/vulkanmod/shaders/include/");
    }

    public static void addIncludePath(String path) {
        // Store the classpath root verbatim. Includes are resolved through the
        // classloader (getResourceAsStream), which works whether the mod is
        // exploded on disk (dev) or packed inside the mod jar (production) --
        // Paths.get(new URI(jar:...)) is unavailable there.
        includePaths.add(path);
    }

    public static SPIRV compileShader(String filename, String source, ShaderKind shaderKind) {
        if (source == null) {
            throw new NullPointerException("source for %s.%s is null".formatted(filename, shaderKind));
        }

        long result = shaderc_compile_into_spv(compiler, source, shaderKind.kind, filename, "main", options);

        if (result == NULL) {
            throw new RuntimeException("Failed to compile shader " + filename + " into SPIR-V");
        }

        if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
            throw new RuntimeException(
                    "Failed to compile shader " + filename + " into SPIR-V:\n" + shaderc_result_get_error_message(
                            result));
        }

        return new SPIRV(result, shaderc_result_get_bytes(result));
    }

    /**
     * Reflects which vertex input locations the compiled vertex shader actually
     * consumes, by scanning the SPIR-V for {@code OpVariable} (Input storage
     * class) declarations decorated with a {@code Location}.
     *
     * <p>The pipeline's vertex input state is built from the Minecraft vertex
     * format, which can declare more attributes than the shader uses (e.g. the
     * lightmap UV2 slot in 1.12.2). Registering only the consumed locations
     * avoids the Vulkan validation warning
     * "Vertex attribute at location N not consumed by vertex shader".</p>
     */
    public static boolean[] getVertexInputLocations(SPIRV spirv) {
        ByteBuffer code = spirv.bytecode().duplicate();
        code.position(0);
        code.order(ByteOrder.LITTLE_ENDIAN);
        IntBuffer words = code.asIntBuffer();

        boolean[] consumed = new boolean[16];
        java.util.HashMap<Integer, Integer> variableStorage = new java.util.HashMap<>();

        // Pass 1: collect OpVariable (59) result id -> storage class
        for (int i = 5; i < words.limit(); ) {
            int word = words.get(i);
            int wordCount = word >>> 16;
            int opcode = word & 0xFFFF;

            if (wordCount == 0)
                break;

            if (opcode == 59 && wordCount >= 4) { // OpVariable
                int resultId = words.get(i + 2);
                int storageClass = words.get(i + 3);
                variableStorage.put(resultId, storageClass);
            }

            i += wordCount;
        }

        // Pass 2: OpDecorate (71) with Decoration Location (30) on Input (1) variables
        for (int i = 5; i < words.limit(); ) {
            int word = words.get(i);
            int wordCount = word >>> 16;
            int opcode = word & 0xFFFF;

            if (wordCount == 0)
                break;

            if (opcode == 71 && wordCount >= 4) { // OpDecorate
                int target = words.get(i + 1);
                int decoration = words.get(i + 2);

                if (decoration == 30) { // Location
                    Integer storage = variableStorage.get(target);

                    if (storage != null && storage == 1) { // Input
                        int location = words.get(i + 3);

                        if (location >= consumed.length)
                            consumed = java.util.Arrays.copyOf(consumed, location + 8);

                        consumed[location] = true;
                    }
                }
            }

            i += wordCount;
        }

        return consumed;
    }

    public enum ShaderKind {
        VERTEX_SHADER(shaderc_glsl_vertex_shader),
        GEOMETRY_SHADER(shaderc_glsl_geometry_shader),
        FRAGMENT_SHADER(shaderc_glsl_fragment_shader),
        COMPUTE_SHADER(shaderc_glsl_compute_shader);

        private final int kind;

        ShaderKind(int kind) {
            this.kind = kind;
        }
    }

    private static class ShaderIncluder implements ShadercIncludeResolveI {

        @Override
        public long invoke(long user_data, long requested_source, int type, long requesting_source, long include_depth) {
            String requesting = memUTF8(requesting_source);
            String requested = memUTF8(requested_source);

            // Resolve the requested include against each registered classpath
            // root. getResourceAsStream works both exploded-on-disk (dev) and
            // inside the mod jar (production), unlike Paths.get(new URI(...)).
            for (String includePath : includePaths) {
                try (InputStream is = SPIRVUtils.class.getResourceAsStream(includePath + requested)) {
                    if (is == null) {
                        continue;
                    }

                    byte[] bytes = is.readAllBytes();

                    // IMPORTANT: allocate on the HEAP, not a MemoryStack. shaderc
                    // retains these pointers and only releases them via the
                    // ShaderReleaser callback -- which fires *after* this method
                    // returns and the compile that requested the include has
                    // finished. A stack-allocated result would be freed the
                    // instant we return, leaving shaderc with dangling pointers
                    // and an EXCEPTION_ACCESS_VIOLATION deep inside shaderc.dll.
                    ByteBuffer content = memAlloc(bytes.length);
                    content.put(bytes).flip();

                    ShadercIncludeResult result = ShadercIncludeResult.malloc()
                            .source_name(memASCII(requested))
                            .content(content)
                            .user_data(user_data);

                    return result.address();
                } catch (IOException ignored) {
                    // try the next include root
                }
            }

            throw new RuntimeException(requesting + ": Unable to find " + requested + " in include paths");
        }
    }

    // shaderc calls this once it no longer needs an include result, i.e. after
    // the compile that requested it has finished. Free the heap allocations we
    // made in ShaderIncluder so we don't leak native memory.
    private static class ShaderReleaser implements ShadercIncludeResultReleaseI {

        @Override
        public void invoke(long user_data, long include_result) {
            ShadercIncludeResult result = ShadercIncludeResult.create(include_result);
            memFree(result.content());
            memFree(result.source_name());
            result.free();
        }
    }

    public static final class SPIRV implements NativeResource {

        private final long handle;
        private ByteBuffer bytecode;

        public SPIRV(long handle, ByteBuffer bytecode) {
            this.handle = handle;
            this.bytecode = bytecode;
        }

        public ByteBuffer bytecode() {
            return bytecode;
        }

        @Override
        public void free() {
//            shaderc_result_release(handle);
            bytecode = null; // Help the GC
        }
    }

}