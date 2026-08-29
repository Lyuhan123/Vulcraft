package com.yuhan123.vulkanmod.vulkan.shader;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.NativeResource;
import org.lwjgl.util.shaderc.ShadercIncludeResolveI;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultReleaseI;
import org.lwjgl.vulkan.VK12;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memASCII;
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
        URL url = SPIRVUtils.class.getResource(path);

        if (url != null)
            includePaths.add(url.toExternalForm());
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

        private static final int MAX_PATH_LENGTH = 4096; //Maximum Linux/Unix Path Length

        @Override
        public long invoke(long user_data, long requested_source, int type, long requesting_source, long include_depth) {
            var requesting = memASCII(requesting_source);
            var requested = memASCII(requested_source);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                Path path;

                for (String includePath : includePaths) {
                    path = Paths.get(new URI(String.format("%s%s", includePath, requested)));

                    if (Files.exists(path)) {
                        byte[] bytes = Files.readAllBytes(path);

                        return ShadercIncludeResult.malloc(stack)
                                                   .source_name(stack.ASCII(requested))
                                                   .content(stack.bytes(bytes))
                                                   .user_data(user_data).address();
                    }
                }
            } catch (IOException | URISyntaxException e) {
                throw new RuntimeException(e);
            }

            throw new RuntimeException(String.format("%s: Unable to find %s in include paths", requesting, requested));
        }
    }

    //TODO: Don't actually need the Releaser at all, (MemoryStack frees this for us)
    //But ShaderC won't let us create the Includer without a corresponding Releaser, (so we need it anyway)
    private static class ShaderReleaser implements ShadercIncludeResultReleaseI {

        @Override
        public void invoke(long user_data, long include_result) {
            //TODO:Maybe dump Shader Compiled Binaries here to a .Misc Diretcory to allow easy caching.recompilation...
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