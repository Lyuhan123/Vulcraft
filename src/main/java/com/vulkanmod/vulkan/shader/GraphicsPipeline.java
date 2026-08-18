package com.yuhan123.vulkanmod.vulkan.shader;

import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
//import com.yuhan123.vulkanmod.interfaces.VertexFormatMixed;
import com.yuhan123.vulkanmod.vulkan.Renderer;
import com.yuhan123.vulkanmod.vulkan.Vulkan;
import com.yuhan123.vulkanmod.vulkan.device.DeviceManager;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class GraphicsPipeline extends Pipeline {
    private final Object2LongMap<PipelineState> graphicsPipelines = new Object2LongOpenHashMap<>();

    private final VertexFormat vertexFormat;
    private final VertexInputDescription vertexInputDescription;

    private long vertShaderModule = 0;
    private long fragShaderModule = 0;

    GraphicsPipeline(Builder builder) {
        super(builder.shaderPath);
        this.buffers = builder.UBOs;
        this.manualUBO = builder.manualUBO;
        this.imageDescriptors = builder.imageDescriptors;
        this.pushConstants = builder.pushConstants;
        this.vertexFormat = builder.vertexFormat;

        createDescriptorSetLayout();
        createPipelineLayout();
        createShaderModules(builder.vertShaderSPIRV, builder.fragShaderSPIRV);

        // Register only the vertex attributes the vertex shader actually consumes;
        // unused ones (e.g. the 1.12.2 lightmap UV2 slot) would otherwise trigger
        // "Vertex attribute at location N not consumed by vertex shader" warnings.
        boolean[] consumedVertexInputs = SPIRVUtils.getVertexInputLocations(builder.vertShaderSPIRV);
        this.vertexInputDescription = new VertexInputDescription(this.vertexFormat, consumedVertexInputs);

        if (builder.renderPass != null)
            graphicsPipelines.computeIfAbsent(PipelineState.DEFAULT,
                    this::createGraphicsPipeline);

        createDescriptorSets(Renderer.getFramesNum());

        PIPELINES.add(this);
    }

    public long getHandle(PipelineState state) {
        return graphicsPipelines.computeIfAbsent(state, this::createGraphicsPipeline);
    }

    private long createGraphicsPipeline(PipelineState state) {
        try (MemoryStack stack = stackPush()) {
            ByteBuffer entryPoint = stack.UTF8("main");

            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);

            VkPipelineShaderStageCreateInfo vertShaderStageInfo = shaderStages.get(0);

            vertShaderStageInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            vertShaderStageInfo.stage(VK_SHADER_STAGE_VERTEX_BIT);
            vertShaderStageInfo.module(vertShaderModule);
            vertShaderStageInfo.pName(entryPoint);

            VkPipelineShaderStageCreateInfo fragShaderStageInfo = shaderStages.get(1);

            fragShaderStageInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            fragShaderStageInfo.stage(VK_SHADER_STAGE_FRAGMENT_BIT);
            fragShaderStageInfo.module(fragShaderModule);
            fragShaderStageInfo.pName(entryPoint);

            // ===> VERTEX STAGE <===

            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack);
            vertexInputInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
            vertexInputInfo.pVertexBindingDescriptions(vertexInputDescription.bindingDescriptions);
            vertexInputInfo.pVertexAttributeDescriptions(vertexInputDescription.attributeDescriptions);

            // ===> ASSEMBLY STAGE <===

            final int topology = PipelineState.AssemblyRasterState.decodeTopology(state.assemblyRasterState);

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack);
            inputAssembly.sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO);
            inputAssembly.topology(topology);
            inputAssembly.primitiveRestartEnable(false);

            // ===> VIEWPORT & SCISSOR

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack);
            viewportState.sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO);

            viewportState.viewportCount(1);
            viewportState.scissorCount(1);

            // ===> RASTERIZATION STAGE <===

            final int polygonMode = PipelineState.AssemblyRasterState.decodePolygonMode(state.assemblyRasterState);
            int cullMode = PipelineState.AssemblyRasterState.decodeCullMode(state.assemblyRasterState);

            // 1.12.2 GUI items are drawn with a modelview Y-flip
            // (RenderItem.setupGuiTransform -> scale(1,-1,1)). Together with the
            // projection Y-flip and the inverted (negative-height) Vulkan viewport
            // this yields THREE winding flips, so icon quads come out back-facing
            // and BACK culling makes them invisible. World blocks only get two
            // flips (front-facing, correct) and GUI textures draw with culling
            // disabled, so the item pipeline is the only affected one.
            if ("item".equals(this.name)) {
                cullMode = VK_CULL_MODE_NONE;
            }

            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack);
            rasterizer.sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO);
            rasterizer.depthClampEnable(false);
            rasterizer.rasterizerDiscardEnable(false);
            rasterizer.polygonMode(polygonMode);
            rasterizer.lineWidth(1.0f);
            rasterizer.cullMode(cullMode);
            // The viewport is Y-inverted (negative height), which reverses triangle
            // winding in framebuffer space, so front faces are CW here.
            rasterizer.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
            boolean depthTestEnable = PipelineState.DepthState.depthTest(state.depthState_i);
            boolean depthWriteEnable = PipelineState.DepthState.depthMask(state.depthState_i);
            if ("item".equals(this.name)) {
                depthTestEnable = true;
                depthWriteEnable = true;
            }
            System.out.println("[PIPE] frontFace=CCW cullMode=" + cullMode + " topology=" + topology + " state=" + state.assemblyRasterState
                + " name=" + this.name + " depthTest=" + depthTestEnable + " depthMask=" + depthWriteEnable
                + " blend=" + PipelineState.BlendState.enable(state.blendState_i) + " cmask=" + Integer.toHexString(state.colorMask_i));
            rasterizer.depthBiasEnable(true);

            // ===> MULTISAMPLING <===

            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack);
            multisampling.sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO);
            multisampling.sampleShadingEnable(false);
            multisampling.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            // ===> DEPTH TEST <===

            // GUI items (inventory icons, hotbar) are depth-tested against the
            // panel/background depth written earlier in the frame and would fail
            // (their zLevel puts them behind the panel). Vanilla 1.12.2 relies on
            // the GUI clearing the depth buffer and mostly disabling depth; our
            // port tracks the GL state imperfectly, so the item pipeline draws
            // without depth testing/writing to guarantee icons are visible.
            // (depthTestEnable/depthWriteEnable computed above, before [PIPE].)

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack);
            depthStencil.sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO);
            depthStencil.depthTestEnable(depthTestEnable);
            depthStencil.depthWriteEnable(depthWriteEnable);
            depthStencil.depthCompareOp(PipelineState.DepthState.decodeDepthFun(state.depthState_i));
            depthStencil.depthBoundsTestEnable(false);
            depthStencil.minDepthBounds(0.0f); // Optional
            depthStencil.maxDepthBounds(1.0f); // Optional
            depthStencil.stencilTestEnable(false);

            // ===> COLOR BLENDING <===

            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            colorBlendAttachment.colorWriteMask(state.colorMask_i);

            if (PipelineState.BlendState.enable(state.blendState_i)) {
                colorBlendAttachment.blendEnable(true);
                colorBlendAttachment.srcColorBlendFactor(PipelineState.BlendState.getSrcRgbFactor(state.blendState_i));
                colorBlendAttachment.dstColorBlendFactor(PipelineState.BlendState.getDstRgbFactor(state.blendState_i));
                colorBlendAttachment.colorBlendOp(PipelineState.BlendState.blendOp(state.blendState_i));
                colorBlendAttachment.srcAlphaBlendFactor(PipelineState.BlendState.getSrcAlphaFactor(state.blendState_i));
                colorBlendAttachment.dstAlphaBlendFactor(PipelineState.BlendState.getDstAlphaFactor(state.blendState_i));
                colorBlendAttachment.alphaBlendOp(PipelineState.BlendState.blendOp(state.blendState_i));
            }
            else {
                colorBlendAttachment.blendEnable(false);
            }

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack);
            colorBlending.sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);
            colorBlending.logicOpEnable(PipelineState.LogicOpState.enable(state.logicOp_i));
            colorBlending.logicOp(PipelineState.LogicOpState.decodeFun(state.logicOp_i));
            colorBlending.pAttachments(colorBlendAttachment);
            colorBlending.blendConstants(stack.floats(0.0f, 0.0f, 0.0f, 0.0f));

            // ===> DYNAMIC STATES <===

            VkPipelineDynamicStateCreateInfo dynamicStates = VkPipelineDynamicStateCreateInfo.calloc(stack);
            dynamicStates.sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO);

            if (topology == VK_PRIMITIVE_TOPOLOGY_LINE_LIST || polygonMode == VK_POLYGON_MODE_LINE) {
                dynamicStates.pDynamicStates(
                        stack.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR,
                                   VK_DYNAMIC_STATE_LINE_WIDTH));
            }
            else {
                dynamicStates.pDynamicStates(
                        stack.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
            }

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            pipelineInfo.sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO);
            pipelineInfo.pStages(shaderStages);
            pipelineInfo.pVertexInputState(vertexInputInfo);
            pipelineInfo.pInputAssemblyState(inputAssembly);
            pipelineInfo.pViewportState(viewportState);
            pipelineInfo.pRasterizationState(rasterizer);
            pipelineInfo.pMultisampleState(multisampling);
            pipelineInfo.pDepthStencilState(depthStencil);
            pipelineInfo.pColorBlendState(colorBlending);
            pipelineInfo.pDynamicState(dynamicStates);
            pipelineInfo.layout(pipelineLayout);
            pipelineInfo.basePipelineHandle(VK_NULL_HANDLE);
            pipelineInfo.basePipelineIndex(-1);

            if (!Vulkan.DYNAMIC_RENDERING) {
                pipelineInfo.renderPass(state.renderPass.getId());
                pipelineInfo.subpass(0);
            }
            else {
                //dyn-rendering
                VkPipelineRenderingCreateInfoKHR renderingInfo = VkPipelineRenderingCreateInfoKHR.calloc(stack);
                renderingInfo.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR);
                renderingInfo.pColorAttachmentFormats(stack.ints(state.renderPass.getFramebuffer().getFormat()));
                renderingInfo.depthAttachmentFormat(state.renderPass.getFramebuffer().getDepthFormat());
                pipelineInfo.pNext(renderingInfo);
            }

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);

            Vulkan.checkResult(vkCreateGraphicsPipelines(DeviceManager.vkDevice, PIPELINE_CACHE, pipelineInfo, null, pGraphicsPipeline),
                               "Failed to create graphics pipeline " + this.name);

            return pGraphicsPipeline.get(0);
        }
    }

    private void createShaderModules(SPIRVUtils.SPIRV vertSpirv, SPIRVUtils.SPIRV fragSpirv) {
        this.vertShaderModule = createShaderModule(vertSpirv.bytecode());
        this.fragShaderModule = createShaderModule(fragSpirv.bytecode());
    }

    public void cleanUp() {
        vkDestroyShaderModule(DeviceManager.vkDevice, vertShaderModule, null);
        vkDestroyShaderModule(DeviceManager.vkDevice, fragShaderModule, null);

        vertexInputDescription.cleanUp();

        destroyDescriptorSets();

        graphicsPipelines.forEach((state, pipeline) -> {
            vkDestroyPipeline(DeviceManager.vkDevice, pipeline, null);
        });
        graphicsPipelines.clear();

        vkDestroyDescriptorSetLayout(DeviceManager.vkDevice, descriptorSetLayout, null);
        vkDestroyPipelineLayout(DeviceManager.vkDevice, pipelineLayout, null);

        PIPELINES.remove(this);
        Renderer.getInstance().removeUsedPipeline(this);
    }

    static class VertexInputDescription {
        final VkVertexInputAttributeDescription.Buffer attributeDescriptions;
        final VkVertexInputBindingDescription.Buffer bindingDescriptions;

        VertexInputDescription(VertexFormat vertexFormat, boolean[] consumedLocations) {
            this.bindingDescriptions = getBindingDescription(vertexFormat);
            this.attributeDescriptions = getAttributeDescriptions(vertexFormat, consumedLocations);
        }

        void cleanUp() {
            MemoryUtil.memFree(this.bindingDescriptions);
            MemoryUtil.memFree(this.attributeDescriptions);
        }
    }

    private static VkVertexInputBindingDescription.Buffer getBindingDescription(VertexFormat vertexFormat) {
        VkVertexInputBindingDescription.Buffer bindingDescription = VkVertexInputBindingDescription.calloc(1);

        bindingDescription.binding(0);
        bindingDescription.stride(vertexFormat.getSize());
        bindingDescription.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

        return bindingDescription;
    }

    private static VkVertexInputAttributeDescription.Buffer getAttributeDescriptions(VertexFormat vertexFormat, boolean[] consumedLocations) {
        List<VertexFormatElement> elements = vertexFormat.getElements();

        int size = elements.size();

        // First pass: compute the Vulkan format/offset for every element. The
        // offset must be tracked for ALL elements (even skipped ones) because it
        // reflects the packed Minecraft vertex layout.
        int[] formats = new int[size];
        int[] offsets = new int[size];
        boolean[] used = new boolean[size];

        int offset = 0;

        for (int i = 0; i < size; ++i) {
            VertexFormatElement formatElement = elements.get(i);
            VertexFormatElement.EnumUsage usage = formatElement.getUsage();
            VertexFormatElement.EnumType type = formatElement.getType();
            int elementCount = formatElement.getSize() / type.getSize();

            used[i] = consumedLocations != null && i < consumedLocations.length && consumedLocations[i];
            formats[i] = -1;
            offsets[i] = offset;

            switch (usage) {
                case POSITION -> {
                    switch (type) {
                        case FLOAT -> {
                            formats[i] = VK_FORMAT_R32G32B32_SFLOAT;
                            offset += 12;
                        }
                        case SHORT -> {
                            formats[i] = VK_FORMAT_R16G16B16A16_SINT;
                            offset += 8;
                        }
                        case BYTE -> {
                            formats[i] = VK_FORMAT_R8G8B8A8_SINT;
                            offset += 4;
                        }
                    }

                }

                case COLOR -> {
                    switch (type) {
                        case UBYTE -> {
                            formats[i] = VK_FORMAT_R8G8B8A8_UNORM;
                            offset += 4;
                        }
                        case UINT -> {
                            formats[i] = VK_FORMAT_R32_UINT;
                            offset += 4;
                        }
                    }
                }

                case UV -> {
                    switch (type) {
                        case FLOAT -> {
                            formats[i] = VK_FORMAT_R32G32_SFLOAT;
                            offset += 8;
                        }
                        case SHORT -> {
                            formats[i] = VK_FORMAT_R16G16_SINT;
                            offset += 4;
                        }
                        case USHORT -> {
                            formats[i] = VK_FORMAT_R16G16_UINT;
                            offset += 4;
                        }
                        case UINT -> {
                            formats[i] = VK_FORMAT_R32_UINT;
                            offset += 4;
                        }
                    }
                }

                case NORMAL -> {
                    formats[i] = VK_FORMAT_R8G8B8A8_SNORM;
                    offset += 4;
                }

                case GENERIC -> {
                    if (type == VertexFormatElement.EnumType.SHORT && elementCount == 1) {
                        formats[i] = VK_FORMAT_R16_SINT;
                        offset += 2;
                    }
                    else if (type == VertexFormatElement.EnumType.INT && elementCount == 1) {
                        formats[i] = VK_FORMAT_R32_SINT;
                        offset += 4;
                    }
                    else {
                        throw new RuntimeException(String.format("Unknown format: %s", usage));
                    }
                }
                case PADDING -> {
                    // 1.12.2 formats pad the vertex to a 4-byte boundary; give the
                    // slot a valid (unused by the shader) format so pipeline creation
                    // does not fail with an UNDEFINED attribute format.
                    formats[i] = VK_FORMAT_R8_UNORM;
                    offset += formatElement.getSize();
                }

                default -> throw new RuntimeException(String.format("Unknown format: %s", usage));
            }
        }

        // Second pass: only keep the attributes the vertex shader consumes
        int usedCount = 0;
        for (boolean b : used) {
            if (b) usedCount++;
        }

        VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(usedCount);

        int j = 0;
        for (int i = 0; i < size; ++i) {
            if (!used[i])
                continue;

            VkVertexInputAttributeDescription posDescription = attributeDescriptions.get(j);
            posDescription.binding(0);
            posDescription.location(i);
            posDescription.format(formats[i]);
            posDescription.offset(offsets[i]);
            j++;
        }

        return attributeDescriptions.rewind();
    }
}
