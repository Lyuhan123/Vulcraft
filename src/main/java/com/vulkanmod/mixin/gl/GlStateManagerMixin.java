package com.yuhan123.vulkanmod.mixin.gl;

import com.yuhan123.vulkanmod.VulkanMod;
import com.yuhan123.vulkanmod.gl.MatrixState;
import com.yuhan123.vulkanmod.gl.VkGlBuffer;
import com.yuhan123.vulkanmod.gl.VkGlTexture;
import com.yuhan123.vulkanmod.render.PipelineManager;
import com.yuhan123.vulkanmod.render.shader.ShaderInstance;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.VRenderSystem;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.GlStateManager.CullFace;
import net.minecraft.client.renderer.GlStateManager.DestFactor;
import net.minecraft.client.renderer.GlStateManager.FogMode;
import net.minecraft.client.renderer.GlStateManager.LogicOp;
import net.minecraft.client.renderer.GlStateManager.SourceFactor;
import net.minecraft.client.renderer.GlStateManager.TexGen;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.client.renderer.vertex.VertexFormatElement.EnumType;
import net.minecraft.client.renderer.vertex.VertexFormatElement.EnumUsage;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vector.Quaternion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GlStateManager.class)
public class GlStateManagerMixin {
   private static int ptrStride = 0;
   private static int ptrPosOffset = -1;
   private static int ptrColorOffset = -1;
   private static int ptrUv0Offset = -1;
   private static int ptrUv1Offset = -1;
   private static int ptrNormalOffset = -1;
   private static ByteBuffer clientPosBuffer = null;
   private static ByteBuffer clientColorBuffer = null;
   private static ByteBuffer clientUv0Buffer = null;
   private static ByteBuffer clientUv1Buffer = null;
   private static boolean clientArrays = false;
   private static int captureMode = 0;
   private static final List<float[]> captureList = new ArrayList<>();
   private static float captureU;
   private static float captureV;



   @Overwrite(remap = true)
   public static void bindTexture(int i) {
      VkGlTexture.bindTexture(i);
   }

   @Overwrite(remap = true)
   public static void disableBlend() {
      VRenderSystem.disableBlend();
   }

   @Overwrite(remap = true)
   public static void enableBlend() {
      VRenderSystem.enableBlend();
   }

   @Overwrite(remap = true)
   public static void blendFunc(int i, int j) {
      VRenderSystem.blendFunc(i, j);
   }

   @Overwrite(remap = true)
   public static void glBlendEquation(int i) {
      VRenderSystem.blendOp(i);
   }

   @Overwrite(remap = true)
   public static void enableCull() {
      VRenderSystem.enableCull();
   }

   @Overwrite(remap = true)
   public static void disableCull() {
      VRenderSystem.disableCull();
   }

   @Redirect(method = "viewport", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glViewport(IIII)V"), remap = true)
   private static void viewport(int x, int y, int width, int height) {
      Renderer.setViewport(x, y, width, height);
   }

   @Overwrite(remap = true)
   public static int glGetError() {
      return 0;
   }

   @Overwrite(remap = true)
   public static void glTexImage2D(
      int target, int level, int internalFormat, int width, int height, int border, int format, int type, @Nullable IntBuffer pixels
   ) {
      VkGlTexture.texImage2D(target, level, internalFormat, width, height, border, format, type, pixels != null ? MemoryUtil.memByteBuffer(pixels) : null);
   }

   @Overwrite(remap = true)
   public static void glTexSubImage2D(int target, int level, int offsetX, int offsetY, int width, int height, int format, int type, IntBuffer pixels) {
      VkGlTexture.texSubImage2D(target, level, offsetX, offsetY, width, height, format, type, pixels);
   }

   @Overwrite(remap = true)
   public static void glTexParameteri(int i, int j, int k) {
      VkGlTexture.texParameteri(i, j, k);
   }

   @Overwrite(remap = true)
   public static void glTexParameterf(int i, int j, float k) {
   }

   @Overwrite(remap = true)
   public static int glGetTexLevelParameteri(int i, int j, int k) {
      return VkGlTexture.getTexLevelParameter(i, j, k);
   }

   @Overwrite(remap = true)
   public static void glPixelStorei(int pname, int param) {
      VkGlTexture.pixelStoreI(pname, param);
   }

   @Overwrite(remap = true)
   public static void deleteTexture(int i) {
      VkGlTexture.glDeleteTextures(i);
   }

   @Overwrite(remap = true)
   public static void colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
      VRenderSystem.colorMask(red, green, blue, alpha);
   }

   @Overwrite(remap = true)
   public static void glPolygonMode(int face, int mode) {
      VRenderSystem.setPolygonModeGL(mode);
   }

   @Overwrite(remap = true)
   public static void enablePolygonOffset() {
      VRenderSystem.enablePolygonOffset();
   }

   @Overwrite(remap = true)
   public static void disablePolygonOffset() {
      VRenderSystem.disablePolygonOffset();
   }

   @Overwrite(remap = true)
   public static void doPolygonOffset(float f, float g) {
      VRenderSystem.polygonOffset(g, f);
   }

   @Overwrite(remap = true)
   public static void enableColorLogic() {
      VRenderSystem.enableColorLogicOp();
   }

   @Overwrite(remap = true)
   public static void disableColorLogic() {
      VRenderSystem.disableColorLogicOp();
   }

   @Overwrite(remap = true)
   public static void colorLogicOp(int i) {
      VRenderSystem.logicOp(i);
   }

   @Overwrite(remap = true)
   public static void clearColor(float f, float g, float h, float i) {
      VRenderSystem.setClearColor(f, g, h, i);
   }

   @Overwrite(remap = true)
   public static void clearDepth(double d) {
      VRenderSystem.clearDepth(d);
   }

   @Overwrite(remap = true)
   public static void clear(int mask) {
      VRenderSystem.clear(mask);
   }

   @Overwrite(remap = true)
   public static void disableDepth() {
      VRenderSystem.disableDepthTest();
   }

   @Overwrite(remap = true)
   public static void enableDepth() {
      VRenderSystem.enableDepthTest();
   }

   @Overwrite(remap = true)
   public static void depthFunc(int i) {
      VRenderSystem.depthFunc(i);
   }

   @Overwrite(remap = true)
   public static void depthMask(boolean bl) {
      VRenderSystem.depthMask(bl);
   }

   @Overwrite
   public static void pushAttrib() {
   }

   @Overwrite
   public static void popAttrib() {
   }

   @Overwrite
   public static void disableAlpha() {
   }

   @Overwrite
   public static void enableAlpha() {
   }

   @Overwrite
   public static void alphaFunc(int p_179092_0_, float p_179092_1_) {
   }

   @Overwrite
   public static void enableLighting() {
   }

   @Overwrite
   public static void disableLighting() {
   }

   @Overwrite
   public static void enableLight(int p_179085_0_) {
   }

   @Overwrite
   public static void disableLight(int p_179122_0_) {
   }

   @Overwrite
   public static void enableColorMaterial() {
   }

   @Overwrite
   public static void disableColorMaterial() {
   }

   @Overwrite
   public static void colorMaterial(int p_179104_0_, int p_179104_1_) {
   }

   @Overwrite
   public static void glLight(int p_187438_0_, int p_187438_1_, FloatBuffer p_187438_2_) {
      if (p_187438_1_ == 4611 && p_187438_2_ != null && p_187438_2_.remaining() >= 3) {
         int pos = p_187438_2_.position();
         float x = p_187438_2_.get(pos);
         float y = p_187438_2_.get(pos + 1);
         float z = p_187438_2_.get(pos + 2);
         if (p_187438_0_ == 16384) {
            VRenderSystem.lightDirection0.putFloat(0, x);
            VRenderSystem.lightDirection0.putFloat(4, y);
            VRenderSystem.lightDirection0.putFloat(8, z);
         } else if (p_187438_0_ == 16385) {
            VRenderSystem.lightDirection1.putFloat(0, x);
            VRenderSystem.lightDirection1.putFloat(4, y);
            VRenderSystem.lightDirection1.putFloat(8, z);
         }
      }
   }

   @Overwrite
   public static void glLightModel(int p_187424_0_, FloatBuffer p_187424_1_) {
   }

   @Overwrite
   public static void glNormal3f(float p_187432_0_, float p_187432_1_, float p_187432_2_) {
   }

   @Overwrite
   public static void blendFunc(SourceFactor p_187401_0_, DestFactor p_187401_1_) {
      VRenderSystem.blendFunc(p_187401_0_.factor, p_187401_1_.factor);
   }

   @Overwrite
   public static void tryBlendFuncSeparate(SourceFactor p_187428_0_, DestFactor p_187428_1_, SourceFactor p_187428_2_, DestFactor p_187428_3_) {
      VRenderSystem.blendFuncSeparate(p_187428_0_.factor, p_187428_1_.factor, p_187428_2_.factor, p_187428_3_.factor);
   }

   @Overwrite
   public static void tryBlendFuncSeparate(int p_179120_0_, int p_179120_1_, int p_179120_2_, int p_179120_3_) {
      VRenderSystem.blendFuncSeparate(p_179120_0_, p_179120_1_, p_179120_2_, p_179120_3_);
   }

   @Overwrite
   public static void enableOutlineMode(int p_187431_0_) {
   }

   @Overwrite
   public static void disableOutlineMode() {
   }

   @Overwrite
   public static void enableFog() {
   }

   @Overwrite
   public static void disableFog() {
   }

   @Overwrite
   public static void setFog(FogMode p_187430_0_) {
   }

   @Overwrite
   private static void setFog(int p_179093_0_) {
   }

   @Overwrite
   public static void setFogDensity(float p_179095_0_) {
   }

   @Overwrite
   public static void setFogStart(float p_179102_0_) {
      VRenderSystem.setShaderFogStart(p_179102_0_);
   }

   @Overwrite
   public static void setFogEnd(float p_179153_0_) {
      VRenderSystem.setShaderFogEnd(p_179153_0_);
   }

   @Overwrite
   public static void glFog(int p_187402_0_, FloatBuffer p_187402_1_) {
      if (p_187402_1_ != null && p_187402_1_.remaining() >= 1) {
         int pos = p_187402_1_.position();
         if (p_187402_0_ == 2915) {
            VRenderSystem.setShaderFogStart(p_187402_1_.get(pos));
         } else if (p_187402_0_ == 2914) {
            VRenderSystem.setShaderFogEnd(p_187402_1_.get(pos));
         } else if (p_187402_0_ == 2918 && p_187402_1_.remaining() >= 4) {
            VRenderSystem.setShaderFogColor(p_187402_1_.get(pos), p_187402_1_.get(pos + 1), p_187402_1_.get(pos + 2), p_187402_1_.get(pos + 3));
         }
      }
   }

   @Overwrite
   public static void glFogi(int p_187412_0_, int p_187412_1_) {
   }

   @Overwrite
   public static void cullFace(CullFace p_187407_0_) {
      cullFace(p_187407_0_.mode);
   }

   @Overwrite
   private static void cullFace(int p_179107_0_) {
   }

   @Overwrite
   public static void colorLogicOp(LogicOp p_187422_0_) {
   }

   @Overwrite
   public static void enableTexGenCoord(TexGen p_179087_0_) {
   }

   @Overwrite
   public static void disableTexGenCoord(TexGen p_179100_0_) {
   }

   @Overwrite
   public static void texGen(TexGen p_179149_0_, int p_179149_1_) {
   }

   @Overwrite
   public static void texGen(TexGen p_179105_0_, int p_179105_1_, FloatBuffer p_179105_2_) {
   }

   @Overwrite
   public static void matrixMode(int p_179128_0_) {
      MatrixState.matrixMode(p_179128_0_);
   }

   @Overwrite
   public static void loadIdentity() {
      MatrixState.loadIdentity();
   }

   @Overwrite
   public static void pushMatrix() {
      MatrixState.pushMatrix();
   }

   @Overwrite
   public static void popMatrix() {
      MatrixState.popMatrix();
   }

   @Overwrite
   public static void getFloat(int p_179111_0_, FloatBuffer p_179111_1_) {
      MatrixState.getMatrix(p_179111_0_, p_179111_1_);
   }

   @Overwrite
   public static void ortho(double p_179130_0_, double p_179130_2_, double p_179130_4_, double p_179130_6_, double p_179130_8_, double p_179130_10_) {
      MatrixState.ortho(p_179130_0_, p_179130_2_, p_179130_4_, p_179130_6_, p_179130_8_, p_179130_10_);
   }

   @Overwrite
   public static void rotate(float p_179114_0_, float p_179114_1_, float p_179114_2_, float p_179114_3_) {
      MatrixState.rotate(p_179114_0_, p_179114_1_, p_179114_2_, p_179114_3_);
   }

   @Overwrite
   public static void scale(float p_179152_0_, float p_179152_1_, float p_179152_2_) {
      MatrixState.scale(p_179152_0_, p_179152_1_, p_179152_2_);
   }

   @Overwrite
   public static void scale(double p_179139_0_, double p_179139_2_, double p_179139_4_) {
      MatrixState.scale(p_179139_0_, p_179139_2_, p_179139_4_);
   }

   @Overwrite
   public static void translate(float p_179109_0_, float p_179109_1_, float p_179109_2_) {
      MatrixState.translate(p_179109_0_, p_179109_1_, p_179109_2_);
   }

   @Overwrite
   public static void translate(double p_179137_0_, double p_179137_2_, double p_179137_4_) {
      MatrixState.translate(p_179137_0_, p_179137_2_, p_179137_4_);
   }

   @Overwrite
   public static void multMatrix(FloatBuffer p_179110_0_) {
      MatrixState.multMatrix(p_179110_0_);
   }

   @Overwrite
   public static void rotate(Quaternion p_187444_0_) {
      MatrixState.rotateQuat(p_187444_0_.x, p_187444_0_.y, p_187444_0_.z, p_187444_0_.w);
   }

   @Overwrite
   public static FloatBuffer quatToGlMatrix(FloatBuffer p_187418_0_, Quaternion p_187418_1_) {
      float x = p_187418_1_.x;
      float y = p_187418_1_.y;
      float z = p_187418_1_.z;
      float w = p_187418_1_.w;
      float xx = x * x;
      float yy = y * y;
      float zz = z * z;
      float xy = x * y;
      float xz = x * z;
      float yz = y * z;
      float xw = x * w;
      float yw = y * w;
      float zw = z * w;
      float[] m = new float[]{
         1.0F - 2.0F * (yy + zz),
         2.0F * (xy + zw),
         2.0F * (xz - yw),
         0.0F,
         2.0F * (xy - zw),
         1.0F - 2.0F * (xx + zz),
         2.0F * (yz + xw),
         0.0F,
         2.0F * (xz + yw),
         2.0F * (yz - xw),
         1.0F - 2.0F * (xx + yy),
         0.0F,
         0.0F,
         0.0F,
         0.0F,
         1.0F
      };
      p_187418_0_.put(m);
      p_187418_0_.flip();
      return p_187418_0_;
   }

   @Overwrite
   public static void resetColor() {
   }

   private static void resetPointerState() {
      ptrStride = 0;
      ptrPosOffset = -1;
      ptrColorOffset = -1;
      ptrUv0Offset = -1;
      ptrUv1Offset = -1;
      ptrNormalOffset = -1;
      clientPosBuffer = null;
      clientColorBuffer = null;
      clientUv0Buffer = null;
      clientUv1Buffer = null;
      clientArrays = false;
   }

   private static VertexFormat buildFormatFromPointers() {
      if (ptrPosOffset >= 0 && ptrStride > 0) {
         List<VertexFormatElement> elements = new ArrayList<>();
         List<Integer> offsets = new ArrayList<>();

         elements.add(new VertexFormatElement(0, EnumType.FLOAT, EnumUsage.POSITION, 3));
         offsets.add(ptrPosOffset);

         if (ptrUv0Offset >= 0) {
            elements.add(new VertexFormatElement(0, EnumType.FLOAT, EnumUsage.UV, 2));
            offsets.add(ptrUv0Offset);
         }

         if (ptrColorOffset >= 0) {
            elements.add(new VertexFormatElement(0, EnumType.UBYTE, EnumUsage.COLOR, 4));
            offsets.add(ptrColorOffset);
         }

         if (ptrNormalOffset >= 0) {
            elements.add(new VertexFormatElement(0, EnumType.BYTE, EnumUsage.NORMAL, 3));
            offsets.add(ptrNormalOffset);
         }

         if (ptrUv1Offset >= 0) {
            elements.add(new VertexFormatElement(1, EnumType.SHORT, EnumUsage.UV, 2));
            offsets.add(ptrUv1Offset);
         }

         // Sort elements by ascending byte offset so the reconstructed
         // VertexFormat's auto-assigned offsets match the GL pointer offsets.
         for (int i = 0; i < elements.size(); i++) {
            for (int j = i + 1; j < elements.size(); j++) {
               if (offsets.get(j) >= 0 && (offsets.get(i) < 0 || offsets.get(j) < offsets.get(i))) {
                  VertexFormatElement tmp = elements.get(i);
                  elements.set(i, elements.get(j));
                  elements.set(j, tmp);
                  int to = offsets.get(i);
                  offsets.set(i, offsets.get(j));
                  offsets.set(j, to);
               }
            }
         }

         // Sort by ascending byte offset so the auto-assigned offsets match the
         // vanilla format offsets (Position@0, Color@12, UV0@16, UV1@24 for BLOCK).
         VertexFormat format = new VertexFormat();

         for (VertexFormatElement e : elements) {
            format.addElement(e);
         }

         // POSITION_TEX_COLOR_NORMAL carries a 1-byte PADDING after NORMAL_3B that
         // the GL pointer API cannot see; return the vanilla constant so the shader
         // map key matches (12+8+4+4 = 28 vs the reconstructed 27).
         if (ptrNormalOffset >= 0 && ptrUv0Offset >= 0 && ptrColorOffset >= 0) {
            return DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL;
         }

         return format;
      } else {
         return null;
      }
   }

   @Overwrite
   public static void glNormalPointer(int p_187446_0_, int p_187446_1_, ByteBuffer p_187446_2_) {
      clientArrays = true;
      ptrNormalOffset = p_187446_2_ != null ? p_187446_2_.position() : 0;
   }

   @Overwrite
   public static void glTexCoordPointer(int p_187405_0_, int p_187405_1_, int p_187405_2_, int p_187405_3_) {
      if (ptrUv0Offset == -1) {
         ptrUv0Offset = p_187405_3_;
      } else {
         ptrUv1Offset = p_187405_3_;
      }
   }

   @Overwrite
   public static void glTexCoordPointer(int p_187404_0_, int p_187404_1_, int p_187404_2_, ByteBuffer p_187404_3_) {
      clientArrays = true;
      if (ptrUv0Offset == -1) {
         ptrUv0Offset = p_187404_3_ != null ? p_187404_3_.position() : 0;
         clientUv0Buffer = p_187404_3_;
      } else {
         ptrUv1Offset = p_187404_3_ != null ? p_187404_3_.position() : 0;
         clientUv1Buffer = p_187404_3_;
      }
   }

   @Overwrite
   public static void glVertexPointer(int p_187420_0_, int p_187420_1_, int p_187420_2_, int p_187420_3_) {
      resetPointerState();
      ptrStride = p_187420_2_;
      ptrPosOffset = p_187420_3_;
   }

   @Overwrite
   public static void glVertexPointer(int p_187427_0_, int p_187427_1_, int p_187427_2_, ByteBuffer p_187427_3_) {
      resetPointerState();
      clientArrays = true;
      ptrStride = p_187427_2_;
      ptrPosOffset = p_187427_3_ != null ? p_187427_3_.position() : 0;
      clientPosBuffer = p_187427_3_;
   }

   @Overwrite
   public static void glColorPointer(int p_187406_0_, int p_187406_1_, int p_187406_2_, int p_187406_3_) {
      ptrColorOffset = p_187406_3_;
   }

   @Overwrite
   public static void glColorPointer(int p_187400_0_, int p_187400_1_, int p_187400_2_, ByteBuffer p_187400_3_) {
      clientArrays = true;
      ptrColorOffset = p_187400_3_ != null ? p_187400_3_.position() : 0;
      clientColorBuffer = p_187400_3_;
   }

   @Overwrite
   public static void glDisableClientState(int p_187429_0_) {
   }

   @Overwrite
   public static void glEnableClientState(int p_187410_0_) {
   }

   @Overwrite
   public static void glBegin(int p_187447_0_) {
      captureMode = p_187447_0_;
      captureList.clear();
      captureU = 0.0F;
      captureV = 0.0F;
   }

   @Overwrite
   public static void glTexCoord2f(float u, float v) {
      captureU = u;
      captureV = v;
   }

   @Overwrite
   public static void glVertex3f(float x, float y, float z) {
      if (captureMode != 0) {
         captureList.add(new float[]{x, y, z, captureU, captureV});
      }
   }

   @Overwrite
   public static void glEnd() {
      int mode = captureMode;
      captureMode = 0;
      if (mode == 5 || mode == 6 || mode == 7) {
         if (captureList.size() >= 4) {
            if (Renderer.isRecording()) {
               ShaderInstance shader = PipelineManager.chooseShader(DefaultVertexFormats.POSITION_TEX);
               if (shader != null && shader.getPipeline() != null) {
                  int vertexCount = captureList.size();
                  ByteBuffer data = MemoryUtil.memAlloc(vertexCount * 20);

                  for (float[] v : captureList) {
                     data.putFloat(v[0]).putFloat(v[1]).putFloat(v[2]);
                     data.putFloat(v[3]).putFloat(v[4]);
                  }

                  data.flip();
                  shader.apply();
                  Renderer renderer = Renderer.getInstance();
                  if (renderer.getBoundPipeline() != null) {
                     Renderer.getDrawer().draw(data, mode, DefaultVertexFormats.POSITION_TEX, vertexCount);
                  }

                  MemoryUtil.memFree(data);
               }
            }
         }
      }
   }

   @Overwrite
   public static void glDrawArrays(int p_187439_0_, int p_187439_1_, int p_187439_2_) {
      if (Renderer.isRecording() && p_187439_2_ > 0) {
         VertexFormat vertexFormat = buildFormatFromPointers();
         if (vertexFormat != null) {
            ShaderInstance shader = PipelineManager.chooseShader(vertexFormat);
            if (shader != null) {
               shader.apply();
               Renderer renderer = Renderer.getInstance();
               if (renderer.getBoundPipeline() != null) {
                  ByteBuffer data;
                  if (clientArrays && clientPosBuffer != null) {
                     data = clientPosBuffer;
                     data.position(0);
                     data.limit(p_187439_2_ * vertexFormat.getSize());
                  } else {
                     VkGlBuffer glBuffer = VkGlBuffer.getArrayBufferBound();
                     if (glBuffer == null || glBuffer.getData() == null) {
                        glBuffer = VkGlBuffer.getLastUploadedArrayBuffer();
                     }

                     if (glBuffer == null || glBuffer.getData() == null) {
                        return;
                     }

                     data = glBuffer.getData();
                     long bytesNeeded = (long)p_187439_1_ * vertexFormat.getSize() + (long)p_187439_2_ * vertexFormat.getSize();
                     if (data.limit() < bytesNeeded) {
                        return;
                     }

                     data.position(p_187439_1_ * vertexFormat.getSize());
                  }

                  if (com.yuhan123.vulkanmod.gl.DisplayListManager.isRecordingList()) {
                     // Display-list compilation: capture a copy of the vertex data.
                     com.yuhan123.vulkanmod.gl.DisplayListManager.captureDisplayListDraw(data, p_187439_0_, vertexFormat, p_187439_2_);
                     return;
                  }

                  Renderer.getDrawer().draw(data, p_187439_0_, vertexFormat, p_187439_2_);
               }
            }
         }
      }
   }

   @Overwrite
   public static void glLineWidth(float p_187441_0_) {
   }

   @Overwrite
   public static void callList(int p_179148_0_) {
      com.yuhan123.vulkanmod.gl.DisplayListManager.replayList(p_179148_0_);
   }

   @Overwrite
   public static void glDeleteLists(int p_187449_0_, int p_187449_1_) {
      com.yuhan123.vulkanmod.gl.DisplayListManager.deleteLists(p_187449_0_);
   }

   @Overwrite
   public static void glNewList(int p_187423_0_, int p_187423_1_) {
      com.yuhan123.vulkanmod.gl.DisplayListManager.startList(p_187423_0_);
   }

   @Overwrite
   public static void glEndList() {
      com.yuhan123.vulkanmod.gl.DisplayListManager.endList();
   }

   @Overwrite
   public static int glGenLists(int p_187442_0_) {
      return com.yuhan123.vulkanmod.gl.DisplayListManager.genLists(p_187442_0_);
   }


   @Overwrite
   public static void enableTexture2D() {
   }

   @Overwrite
   public static void disableTexture2D() {
   }

   @Overwrite
   public static void glTexEnv(int p_187448_0_, int p_187448_1_, FloatBuffer p_187448_2_) {
   }

   @Overwrite
   public static void glTexEnvi(int p_187399_0_, int p_187399_1_, int p_187399_2_) {
   }

   @Overwrite
   public static void glTexEnvf(int p_187436_0_, int p_187436_1_, float p_187436_2_) {
   }

   @Overwrite
   public static int generateTexture() {
      return VkGlTexture.genTextureId();
   }

   @Overwrite
   public static void glCopyTexSubImage2D(
      int p_187443_0_, int p_187443_1_, int p_187443_2_, int p_187443_3_, int p_187443_4_, int p_187443_5_, int p_187443_6_, int p_187443_7_
   ) {
   }

   @Overwrite
   public static void glGetTexImage(int p_187433_0_, int p_187433_1_, int p_187433_2_, int p_187433_3_, IntBuffer p_187433_4_) {
   }

   @Overwrite
   public static void enableNormalize() {
   }

   @Overwrite
   public static void disableNormalize() {
   }

   @Overwrite
   public static void shadeModel(int p_179103_0_) {
   }

   @Overwrite
   public static void enableRescaleNormal() {
   }

   @Overwrite
   public static void disableRescaleNormal() {
   }
}