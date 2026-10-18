package com.yuhan123.vulkanmod.gl;

import com.yuhan123.vulkanmod.vulkan.Vulkan;
import com.yuhan123.vulkanmod.vulkan.shader.SPIRVUtils;
import org.apache.commons.lang3.Validate;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.vulkan.VK10.*;

public abstract class GlUtil {

    public static SPIRVUtils.ShaderKind extToShaderKind(String ext) {
        return switch (ext) {
            case ".vsh" -> SPIRVUtils.ShaderKind.VERTEX_SHADER;
            case ".fsh" -> SPIRVUtils.ShaderKind.FRAGMENT_SHADER;
            default -> throw new RuntimeException("unknown shader type: " + ext);
        };
    }

    //Created buffer will need to be freed
    public static ByteBuffer RGBtoRGBA_buffer(ByteBuffer in) {
        Validate.isTrue(in.remaining() % 3 == 0, "Unexpected buffer stride");

        int outSize = in.remaining() * 4 / 3;
        ByteBuffer out = MemoryUtil.memAlloc(outSize);

        int j = 0;
        for (int i = 0; i < outSize; i+=4, j+=3) {
            out.put(i, in.get(j));
            out.put(i + 1, in.get(j + 1));
            out.put(i + 2, in.get(j + 2));
            out.put(i + 3, (byte) 0xFF);
        }

        return out;
    }

    public static ByteBuffer BGRAtoRGBA_buffer(ByteBuffer in) {
        Validate.isTrue(in.remaining() % 4 == 0, "Unexpected buffer stride");

        int outSize = in.remaining();
        ByteBuffer out = MemoryUtil.memAlloc(outSize);

        long ptr = MemoryUtil.memAddress0(out);

        long srcPtr = MemoryUtil.memAddress0(in);

        // TODO write in place (don't free the returned buffer in that case)
        for (int i = 0; i < outSize ; i += 4) {
            int color = MemoryUtil.memGetInt(srcPtr + i);

            // BGRA in memory (bytes [B,G,R,A]) -> RGBA (bytes [R,G,B,A]): swap byte0<->byte2, keep G and A.
            color = (color & 0xFF00FF00) | ((color >> 16) & 0xFF) | ((color & 0xFF) << 16);

            MemoryUtil.memPutInt(ptr + i, color);
        }

        return out;
    }

//    public static int vulkanFormat(int glFormat, int type) {
//        return switch (glFormat) {
//            case GL11.GL_RGBA ->
//                    switch (type) {
//                        case GL11.GL_UNSIGNED_BYTE -> VK_FORMAT_R8G8B8A8_UNORM;
//                        case GL11.GL_BYTE -> VK_FORMAT_R8G8B8A8_UNORM;
//                        case GL30.GL_UNSIGNED_INT_8_8_8_8, GL30.GL_UNSIGNED_INT_8_8_8_8_REV -> VK_FORMAT_R8G8B8A8_UNORM;
//                        default -> throw new IllegalStateException("Unexpected type: " + type);
//                    };
//            case GL30.GL_BGRA ->
//                    switch (type) {
//                        case GL11.GL_UNSIGNED_BYTE -> VK_FORMAT_B8G8R8A8_UNORM;
//                        case GL11.GL_BYTE -> VK_FORMAT_B8G8R8A8_UNORM;
//                        case GL30.GL_UNSIGNED_INT_8_8_8_8, GL30.GL_UNSIGNED_INT_8_8_8_8_REV -> VK_FORMAT_B8G8R8A8_UNORM;
//                        default -> throw new IllegalStateException("Unexpected type: " + type);
//                    };
//            case GL30.GL_UNSIGNED_INT_8_8_8_8_REV ->
//                    switch (type) {
//                        case GL11.GL_UNSIGNED_BYTE -> VK_FORMAT_R8G8B8A8_UINT;
//                        case GL11.GL_BYTE -> VK_FORMAT_R8G8B8A8_UNORM;
//                        default -> throw new IllegalStateException("Unexpected type: " + type);
//                    };
//            case GL11.GL_RED ->
//                    switch (type) {
//                        case GL11.GL_UNSIGNED_BYTE -> VK_FORMAT_R8_UNORM;
//                        default -> throw new IllegalStateException("Unexpected type: " + type);
//                    };
//            case GL11.GL_DEPTH_COMPONENT, GL30.GL_DEPTH_COMPONENT32,
//                 GL30.GL_DEPTH_COMPONENT32F, GL30.GL_DEPTH_COMPONENT24 ->
//                    Vulkan.getDefaultDepthFormat();
//
//            default -> throw new IllegalStateException("Unexpected format: " + glFormat);
//        };
//    }

    public static int vulkanFormat(int glFormat, int type) {
        return switch (glFormat) {
            case GL11.GL_RGBA -> {
                if (type == GL11.GL_UNSIGNED_BYTE ||
                        type == GL_UNSIGNED_INT_8_8_8_8 ||
                        type == GL_UNSIGNED_INT_8_8_8_8_REV) {
                    yield VK_FORMAT_R8G8B8A8_UNORM;
                }
                throw new IllegalStateException("Unsupported RGBA type: " + type);
            }

            // Sized internal formats. Mods pass these instead of the unsized
            // GL_RGBA/GL_RGB - JourneyMap's map region textures use GL_RGBA8,
            // which used to throw "Unsupported glFormat: 32856" and left the
            // texture unbound (map rendered blank). Every case keeps the same
            // channel count and byte size as its base format, so the upload
            // path needs no conversion (GL_RGB8's 3-channel pixels are
            // expanded by uploadSubImage's RGBtoRGBA_buffer once the image is
            // R8G8B8A8_UNORM).
            case GL11.GL_RGBA8 -> VK_FORMAT_R8G8B8A8_UNORM;
            case GL21.GL_SRGB8_ALPHA8 -> VK_FORMAT_R8G8B8A8_SRGB;
            case GL11.GL_RGB8 -> VK_FORMAT_R8G8B8A8_UNORM;
            case GL11.GL_RGBA16 -> VK_FORMAT_R16G16B16A16_UNORM;
            case GL30.GL_RGBA16F -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case GL30.GL_RGBA32F -> VK_FORMAT_R32G32B32A32_SFLOAT;
            case GL30.GL_R8 -> VK_FORMAT_R8_UNORM;
            case GL30.GL_RG8 -> VK_FORMAT_R8G8_UNORM;

            case GL11.GL_RED -> {
                if (type == GL11.GL_UNSIGNED_BYTE)
                    yield VK_FORMAT_R8_UNORM;
                throw new IllegalStateException("Unsupported RED type: " + type);
            }

            // Sized depth variants -> default depth format (D24S8), same as
            // the unsized GL_DEPTH_COMPONENT case.
            case GL11.GL_DEPTH_COMPONENT, GL14.GL_DEPTH_COMPONENT16,
                 GL14.GL_DEPTH_COMPONENT24, GL14.GL_DEPTH_COMPONENT32,
                 GL30.GL_DEPTH_COMPONENT32F -> {
                yield Vulkan.getDefaultDepthFormat(); // D24S8
            }

            default ->
                    throw new IllegalStateException("Unsupported glFormat: " + glFormat);
        };
    }

    // TODO: refactor
//    public static int vulkanFormat(int glInternalFormat) {
//        return switch (glInternalFormat) {
//            case GL30.GL_UNSIGNED_INT_8_8_8_8_REV -> VK_FORMAT_R8G8B8A8_UINT;
//            case GL11.GL_DEPTH_COMPONENT, GL30.GL_DEPTH_COMPONENT32F ->
//                    Vulkan.getDefaultDepthFormat();
//
//            default -> throw new IllegalStateException("Unexpected value: " + glInternalFormat);
//        };
//    }

    public static int getGlFormat(int vFormat) {
        return switch (vFormat) {
            case VK_FORMAT_R8G8B8A8_UNORM -> GL11.GL_RGBA;
            case VK_FORMAT_R8G8B8A8_SRGB -> GL21.GL_SRGB8_ALPHA8;
            case VK_FORMAT_R16G16B16A16_UNORM -> GL11.GL_RGBA16;
            case VK_FORMAT_R16G16B16A16_SFLOAT -> GL30.GL_RGBA16F;
            case VK_FORMAT_R32G32B32A32_SFLOAT -> GL30.GL_RGBA32F;
            case VK_FORMAT_R8G8_UNORM -> GL30.GL_RG8;
//            case VK_FORMAT_B8G8R8A8_UNORM -> GL_BGRA;
//            case VK_FORMAT_R8G8_UNORM -> GL30.GL_RG;
            case VK_FORMAT_R8_UNORM -> GL11.GL_RED;
            default -> throw new IllegalStateException("Unexpected value: " + vFormat);
        };
    }
}
