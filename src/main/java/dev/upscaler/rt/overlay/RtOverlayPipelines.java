package dev.upscaler.rt.overlay;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import dev.upscaler.rt.RtContext;
import dev.upscaler.rt.RtDebugLabels;

import static dev.upscaler.rt.RtContext.check;

/**
 * Shared creation-time boilerplate for the world-overlay raster passes ({@link RtWorldOverlay}). Overlay
 * features describe a pass as a {@link Spec} (shaders + vertex format + blend + attachment format) instead
 * of hand-rolling {@code VkGraphicsPipelineCreateInfo}. Pipelines are keyed by GPU state, not by feature —
 * a new feature should reuse an existing vertex-format/blend combination where one fits, so the pipeline
 * count grows with distinct state, which saturates at a handful.
 *
 * <p>All pipelines target {@code VK_KHR_dynamic_rendering} (already required + enabled by vanilla's own
 * Blaze3D device bring-up) with one colour attachment, no depth, dynamic viewport/scissor.
 */
public final class RtOverlayPipelines {
    private static final String SHADER_DIR = "/upscaler/rt/";

    private RtOverlayPipelines() {
    }

    /** Vertex layouts available to overlay passes (one interleaved binding at binding 0). */
    public enum VertexFormat {
        /** No vertex input — fullscreen triangle via {@code gl_VertexIndex}. */
        NONE(0),
        /** vec3 position (12B). */
        POSITION(3 * Float.BYTES),
        /** vec3 position + RGBA8-unorm colour (16B). */
        POSITION_COLOR(3 * Float.BYTES + 4),
        /** vec3 position + vec2 uv + RGBA8-unorm colour (24B). */
        POSITION_TEX_COLOR(5 * Float.BYTES + 4);

        public final int stride;

        VertexFormat(int stride) {
            this.stride = stride;
        }
    }

    /** Colour-attachment blend state. */
    public enum Blend {
        /** Straight replace (mask writes). */
        NONE,
        /** Classic alpha blend: SRC_ALPHA / ONE_MINUS_SRC_ALPHA, destination alpha preserved. */
        ALPHA
    }

    /** A pipeline plus its layout, destroyed together. */
    public static final class Pipeline {
        public final long layout;
        public final long handle;

        private Pipeline(long layout, long handle) {
            this.layout = layout;
            this.handle = handle;
        }

        public void destroy(VkDevice vk) {
            VK10.vkDestroyPipeline(vk, handle, null);
            VK10.vkDestroyPipelineLayout(vk, layout, null);
        }
    }

    /** Everything that varies between overlay pipelines; defaults cover the common case. */
    public static final class Spec {
        private final String vertSpv;
        private final String fragSpv;
        private VertexFormat vertexFormat = VertexFormat.NONE;
        private int topology = VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        private Blend blend = Blend.NONE;
        private int attachmentFormat;
        private int pushBytes;
        private int pushStages;
        private long descriptorSetLayout;

        public Spec(String vertSpv, String fragSpv) {
            this.vertSpv = vertSpv;
            this.fragSpv = fragSpv;
        }

        public Spec vertex(VertexFormat format) {
            this.vertexFormat = format;
            return this;
        }

        public Spec topology(int vkTopology) {
            this.topology = vkTopology;
            return this;
        }

        public Spec blend(Blend blend) {
            this.blend = blend;
            return this;
        }

        /** The colour attachment's VkFormat — {@link RtWorldOverlay#TARGET_FORMAT} for composite passes. */
        public Spec attachment(int vkFormat) {
            this.attachmentFormat = vkFormat;
            return this;
        }

        public Spec push(int bytes, int stageFlags) {
            this.pushBytes = bytes;
            this.pushStages = stageFlags;
            return this;
        }

        public Spec descriptorSetLayout(long dsl) {
            this.descriptorSetLayout = dsl;
            return this;
        }

        public Pipeline build(RtContext ctx, String label) {
            if (attachmentFormat == 0) {
                throw new IllegalStateException("overlay pipeline '" + label + "' has no attachment format");
            }
            return createGraphics(ctx, this, label);
        }
    }

    private static Pipeline createGraphics(RtContext ctx, Spec spec, String label) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);

            VkPipelineLayoutCreateInfo layoutCi = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            if (spec.pushBytes > 0) {
                VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
                push.get(0).stageFlags(spec.pushStages).offset(0).size(spec.pushBytes);
                layoutCi.pPushConstantRanges(push);
            }
            if (spec.descriptorSetLayout != 0L) {
                layoutCi.pSetLayouts(stack.longs(spec.descriptorSetLayout));
            }
            check(VK10.vkCreatePipelineLayout(vk, layoutCi, null, p), "vkCreatePipelineLayout(" + label + ")");
            long layout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout, label + " pipeline layout");

            long vertModule = loadModule(vk, stack, spec.vertSpv);
            long fragModule = loadModule(vk, stack, spec.fragSpv);
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK10.VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(stack.UTF8("main"));

            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
            if (spec.vertexFormat != VertexFormat.NONE) {
                VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
                binding.get(0).binding(0).stride(spec.vertexFormat.stride).inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);
                vertexInput.pVertexBindingDescriptions(binding)
                        .pVertexAttributeDescriptions(vertexAttributes(stack, spec.vertexFormat));
            }

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType$Default().topology(spec.topology);

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                    .viewportCount(1).scissorCount(1);

            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .polygonMode(VK10.VK_POLYGON_MODE_FILL).cullMode(VK10.VK_CULL_MODE_NONE)
                    .frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1.0f);

            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default().rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);

            VkPipelineColorBlendAttachmentState.Buffer blendAttach = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blendAttach.get(0).colorWriteMask(
                    VK10.VK_COLOR_COMPONENT_R_BIT | VK10.VK_COLOR_COMPONENT_G_BIT
                            | VK10.VK_COLOR_COMPONENT_B_BIT | VK10.VK_COLOR_COMPONENT_A_BIT);
            if (spec.blend == Blend.ALPHA) {
                blendAttach.get(0).blendEnable(true)
                        .srcColorBlendFactor(VK10.VK_BLEND_FACTOR_SRC_ALPHA)
                        .dstColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .colorBlendOp(VK10.VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ZERO)
                        .dstAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE)
                        .alphaBlendOp(VK10.VK_BLEND_OP_ADD);
            } else {
                blendAttach.get(0).blendEnable(false);
            }
            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default().pAttachments(blendAttach);

            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                    .pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR));

            VkPipelineRenderingCreateInfo renderingInfo = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                    .colorAttachmentCount(1).pColorAttachmentFormats(stack.ints(spec.attachmentFormat));

            VkGraphicsPipelineCreateInfo.Buffer gpci = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            gpci.get(0).sType$Default().pNext(renderingInfo.address())
                    .pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState).pRasterizationState(raster).pMultisampleState(multisample)
                    .pColorBlendState(colorBlend).pDynamicState(dynamicState).layout(layout)
                    .renderPass(VK10.VK_NULL_HANDLE).subpass(0);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(VK10.vkCreateGraphicsPipelines(vk, VK10.VK_NULL_HANDLE, gpci, null, pPipeline),
                    "vkCreateGraphicsPipelines(" + label + ")");
            long handle = pPipeline.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, handle, label + " pipeline");
            VK10.vkDestroyShaderModule(vk, vertModule, null);
            VK10.vkDestroyShaderModule(vk, fragModule, null);
            return new Pipeline(layout, handle);
        }
    }

    private static VkVertexInputAttributeDescription.Buffer vertexAttributes(MemoryStack stack, VertexFormat format) {
        return switch (format) {
            case NONE -> throw new IllegalArgumentException("NONE has no attributes");
            case POSITION -> {
                VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(1, stack);
                attrs.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                yield attrs;
            }
            case POSITION_COLOR -> {
                VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(2, stack);
                attrs.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                attrs.get(1).location(1).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(12);
                yield attrs;
            }
            case POSITION_TEX_COLOR -> {
                VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(3, stack);
                attrs.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                attrs.get(1).location(1).binding(0).format(VK10.VK_FORMAT_R32G32_SFLOAT).offset(12);
                attrs.get(2).location(2).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(20);
                yield attrs;
            }
        };
    }

    /**
     * A single descriptor set of {@code count} storage images (bindings 0..count-1), with its layout and
     * pool — enough for overlay composite passes that read a mod-owned mask/scratch image. (Vanilla-owned
     * textures can never be bound here: Blaze3D never sets VK_IMAGE_USAGE_STORAGE_BIT — they are reachable
     * only as colour attachments.)
     */
    public static final class StorageImageSet {
        public final long layout;
        private final long pool;
        public final long set;
        private final long[] boundViews;

        private StorageImageSet(long layout, long pool, long set, int count) {
            this.layout = layout;
            this.pool = pool;
            this.set = set;
            this.boundViews = new long[count];
        }

        /** Point binding {@code binding} at {@code view} (GENERAL layout); no-op when already bound. */
        public void bind(RtContext ctx, int binding, long view) {
            if (boundViews[binding] == view) {
                return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
                writes.get(0).sType$Default().dstSet(set).dstBinding(binding)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
                VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
            }
            boundViews[binding] = view;
        }

        public void destroy(VkDevice vk) {
            VK10.vkDestroyDescriptorPool(vk, pool, null);
            VK10.vkDestroyDescriptorSetLayout(vk, layout, null);
        }
    }

    public static StorageImageSet storageImageSet(RtContext ctx, int count, int stageFlags, String label) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(count, stack);
            for (int i = 0; i < count; i++) {
                binds.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(stageFlags);
            }
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout(" + label + ")");
            long dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, label + " descriptor set layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(count);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(" + label + ")");
            long pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, label + " descriptor pool");

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets(" + label + ")");
            long set = pSet.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, set, label + " descriptor set");
            return new StorageImageSet(dsl, pool, set, count);
        }
    }

    static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtOverlayPipelines.class.getResourceAsStream(SHADER_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + name);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER_DIR + name, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, pModule), "vkCreateShaderModule(" + name + ")");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
