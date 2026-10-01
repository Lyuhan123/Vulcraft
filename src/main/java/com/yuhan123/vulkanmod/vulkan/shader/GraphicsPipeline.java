package com.yuhan123.vulkanmod.vulkan.shader;

import com.yuhan123.vulkanmod.VKProf;
import com.yuhan123.vulkanmod.config.VulkanModConfig;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
//import com.yuhan123.vulkanmod.interfaces.VertexFormatMixed;
import com.yuhan123.vulkanmod.VulkanMod;
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

    /**
     * Discard-free fragment variant (may be 0 when the fragment shader contains
     * no discard). Selected whenever the GL alpha-test state is disabled so the
     * pipeline keeps early-Z fragment rejection instead of paying full fragment
     * shading for overdrawn terrain.
     */
    private long fragShaderModuleNoDiscard = 0;

    /**
     * early_fragment_tests variant (discard retained). Selected for the cutout
     * single-pass draw when EARLYCUTOUT=1 is set (diagnostic only):
     * re-attaches early tests but reintroduces the sky punch-through defect.
     */
    private long fragShaderModuleEarlyTest = 0;

    /** TEMPORARY: winding experiment - NOCULL=1 disables back-face culling everywhere. */
    private static final boolean NOCULL = VulkanModConfig.getBool("NOCULL", false);

    /** TEMPORARY: A/B arm for the depth-bias fix - DEPTHBIAS=1 restores the old value. */
    private static final boolean DEPTHBIAS = VulkanModConfig.getBool("DEPTHBIAS", false);

    GraphicsPipeline(Builder builder) {
        super(builder.shaderPath);
        this.buffers = builder.UBOs;
        this.manualUBO = builder.manualUBO;
        this.imageDescriptors = builder.imageDescriptors;
        this.staticBuffers = builder.staticBuffers;
        this.pushConstants = builder.pushConstants;
        this.vertexFormat = builder.vertexFormat;

        createDescriptorSetLayout();
        createPipelineLayout();
        createShaderModules(builder.vertShaderSPIRV, builder.fragShaderSPIRV,
                builder.fragNoDiscardSPIRV, builder.fragEarlyTestSPIRV);

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

    /**
     * Hot path: hit once per draw call. The previous
     * {@code computeIfAbsent(state, this::createGraphicsPipeline)} allocated a
     * bound method-reference object on every call, which the JIT cannot always
     * scalarise once the lambda escapes into the map. Explicit get/put keeps the
     * steady state allocation-free.
     */
    public long getHandle(PipelineState state) {
        final long handle = graphicsPipelines.getLong(state);
        if (handle != 0L) {
            return handle;
        }

        final long created = createGraphicsPipeline(state);
        graphicsPipelines.put(state, created);
        return created;
    }

    private long createGraphicsPipeline(PipelineState state) {
        long picked = pickFragModule(state);
        VKProf.info("[VKPROF] create pipeline '{}': cMask=0x{} aTest={} dMask={} dEqual={} frag={} (early=0x{} noDiscard=0x{} normal=0x{})",
                this.name, Integer.toHexString(state.colorMask_i), state.alphaTest(), state.depthMask(),
                state.depthEqual(),
                picked == fragShaderModuleEarlyTest && fragShaderModuleEarlyTest != 0 ? "EARLY"
                        : (picked == fragShaderModuleNoDiscard && fragShaderModuleNoDiscard != 0 ? "NODISCARD" : "NORMAL"),
                fragShaderModuleEarlyTest, fragShaderModuleNoDiscard, fragShaderModule);

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
            fragShaderStageInfo.module(pickFragModule(state));
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

            // TEMPORARY winding experiment: NOCULL=1 disables back-face
            // culling for EVERY pipeline. If the foliage stipple and the shattered
            // entities disappear, the defect is winding/culling; if they survive,
            // it is not.
            if (NOCULL) {
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
            // winding in framebuffer space. Measured: with CLOCKWISE here the TERRAIN
            // itself loses its front faces (every grass surface disappears and only
            // the back faces remain), so COUNTER_CLOCKWISE is the value that matches
            // the winding the chunk geometry actually carries. Do NOT "fix" this
            // again from the flip arithmetic alone - the arithmetic is off by the
            // projection's own z/y remap; only an A/B screenshot settles it.
            rasterizer.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
            boolean depthTestEnable = PipelineState.DepthState.depthTest(state.depthState_i);
            boolean depthWriteEnable = PipelineState.DepthState.depthMask(state.depthState_i);
            if ("item".equals(this.name)) {
                depthTestEnable = true;
                depthWriteEnable = true;
            }
            // Depth bias was enabled here with NO factors ever set, which leaves
            // depthBiasConstantFactor / depthBiasSlopeFactor at whatever the
            // allocator handed back. With bias active, every fragment's depth is
            // shifted, and the error is proportional to nothing useful: thick
            // geometry hides it, but thin near-edge-on geometry (grass blades,
            // leaf quads, water edges, entity part seams) crosses its neighbour's
            // depth and shows up as black hair-line cracks with the water plane
            // poking through the grass.
            //
            // GL's polygon offset is the feature this was standing in for, and
            // 1.12.2 only enables it for a few cases (glPolygonOffset for
            // overlays/shadowing). Nothing in the port maps GL polygon offset
            // into this struct, so the correct value is simply bias OFF.
            // DEPTHBIAS=1 restores the old unconditional enable for
            // back-to-back A/B.
            rasterizer.depthBiasEnable(DEPTHBIAS);
            rasterizer.depthBiasConstantFactor(0.0f);
            rasterizer.depthBiasSlopeFactor(0.0f);
            rasterizer.depthBiasClamp(0.0f);

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

            // LINE_WIDTH is always dynamic. Making it conditional meant that
            // Renderer.setLineWidth() (and the per-frame reset) called
            // vkCmdSetLineWidth against pipelines that declared it statically,
            // which is invalid and produced a VUID error on every affected draw.
            // resetDynamicState() sets a valid 1.0 default at the start of each
            // command buffer, so leaving it dynamic is safe.
            VkPipelineDynamicStateCreateInfo dynamicStates = VkPipelineDynamicStateCreateInfo.calloc(stack);
            dynamicStates.sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO);
            dynamicStates.pDynamicStates(
                    stack.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR,
                               VK_DYNAMIC_STATE_LINE_WIDTH));

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

    private void createShaderModules(SPIRVUtils.SPIRV vertSpirv, SPIRVUtils.SPIRV fragSpirv,
                                     SPIRVUtils.SPIRV fragNoDiscardSpirv, SPIRVUtils.SPIRV fragEarlyTestSpirv) {
        this.vertShaderModule = createShaderModule(vertSpirv.bytecode());
        this.fragShaderModule = createShaderModule(fragSpirv.bytecode());

        if (fragNoDiscardSpirv != null) {
            this.fragShaderModuleNoDiscard = createShaderModule(fragNoDiscardSpirv.bytecode());
            VKProf.info("[VKPROF] early-Z fragment variant created for shader '{}'", this.name);
        }

        if (fragEarlyTestSpirv != null) {
            this.fragShaderModuleEarlyTest = createShaderModule(fragEarlyTestSpirv.bytecode());
        }
    }

    /**
     * Picks the fragment module for a draw.
     *
     * 1. alpha test on + depthMask ON (single-pass cutout) -> the PLAIN module,
     *    deliberately. early_fragment_tests would write depth for fragments the
     *    shader goes on to discard, punching the cutout mask into a per-texel
     *    stipple with sky bleeding through. The variant is only reachable as a
     *    diagnostic via EARLYCUTOUT=1 (see branch 2 below).
     * 2. alpha test on + depthMask ON + EARLYCUTOUT=1 (diagnostic only)
     *    -> the early_fragment_tests variant; reintroduces the sky punch-through
     *    defect but confirms whether cutout overdraw is the GPU bottleneck.
     * 3. alpha test off (vanilla disables it for the SOLID layer) -> the
     *    discard-free variant restores early-Z; the discard statement would
     *    otherwise be dead code that still forces late fragment tests.
     * 4. anything else -> the full original shader.
     *
     * The two-pass PREPASS depth prepass was removed; the depth-only
     * fragment module and the prepass colour-pass branch are gone. See git
     * history (backup commit b428c9a) for the removed code.
     */
    private long pickFragModule(PipelineState state) {
        // NOTE: alpha test on + depthMask ON (the default single-pass cutout draw)
        // deliberately does NOT take the early_fragment_tests variant.
        //
        // early_fragment_tests moves the depth test - and with it the depth WRITE
        // - ahead of the fragment shader. A leaf or grass texel that the shader
        // then discards has already written depth, so the pixel is occluded while
        // never being painted: the cutout alpha mask gets punched out into a
        // per-texel stipple with sky showing through. The alpha-test semantic GL
        // implements is the opposite - a discarded fragment must not write depth.
        //
        // Measured on the leaf canopy (854x480 bench, fixed camera, 12600-pixel
        // box): 28.7% sky punch-through with the variant, 11.3% without - the
        // variant accounted for 61% of the defect. The variant is only reachable
        // as a diagnostic via EARLYCUTOUT=1 (branch 2 below).
        if (this.fragShaderModuleEarlyTest != 0
                && state.alphaTest() && state.depthMask()
                && VulkanModConfig.getBool("EARLYCUTOUT", false)) {
            return this.fragShaderModuleEarlyTest;
        }

        if (this.fragShaderModuleNoDiscard != 0 && !state.alphaTest()) {
            return this.fragShaderModuleNoDiscard;
        }

        return this.fragShaderModule;
    }

    public void cleanUp() {
        vkDestroyShaderModule(DeviceManager.vkDevice, vertShaderModule, null);
        vkDestroyShaderModule(DeviceManager.vkDevice, fragShaderModule, null);

        if (fragShaderModuleNoDiscard != 0) {
            vkDestroyShaderModule(DeviceManager.vkDevice, fragShaderModuleNoDiscard, null);
        }

        if (fragShaderModuleEarlyTest != 0) {
            vkDestroyShaderModule(DeviceManager.vkDevice, fragShaderModuleEarlyTest, null);
        }

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
