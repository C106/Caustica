package dev.upscaler.rt;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import dev.upscaler.UpscalerMod;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VKCapabilitiesDevice;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR;

/**
 * P0 — RT bring-up. Enables the hardware ray-tracing device extensions and their
 * feature structs on vanilla's Blaze3D device at {@code vkCreateDevice} time.
 *
 * <p>Vanilla assembles a {@code VkPhysicalDeviceFeatures2} pNext chain from the
 * {@code Set<VulkanFeature>} (arg2) via {@code VulkanFeature.set} →
 * {@code findOrCreateStructInPNextChain} (dedup by sType), so {@code bufferDeviceAddress}
 * merges into the existing {@code VkPhysicalDeviceVulkan12Features} struct and the two
 * KHR structs are created fresh. BDA / descriptor-indexing / SPIR-V 1.4 are core on the
 * 1.4 device, so only three extension <i>names</i> are needed; the rest are feature enables.
 *
 * <p><b>Extension names vs features, and Sodium:</b> Sodium's {@code VulkanBackendMixin}
 * rewrites arg0 from its {@code DeviceExtensionRegistry} (names only — no feature API) and
 * never touches arg2. So:
 * <ul>
 *   <li>Names: added to arg0 by us standalone; registered through Sodium's registry (via
 *       {@code SodiumCompat}) when Sodium is present — see {@link #sodiumExtensionsRegistered}.</li>
 *   <li>Features: always added to arg2 by us, but <i>only</i> when the extensions are
 *       guaranteed enabled (standalone, or Sodium registration succeeded), otherwise
 *       {@code vkCreateDevice} would fail on a feature without its extension.</li>
 * </ul>
 * Both are gated on the selected device actually supporting RT; if not, nothing is added
 * and the device comes up exactly as vanilla.
 */
public final class RtDeviceBringup {
    public static final boolean ENABLED_BY_PROPERTY =
            Boolean.parseBoolean(System.getProperty("upscaler.rt", "true"));

    /** The three device extensions RT needs (BDA/descriptor-indexing/SPIR-V 1.4 are core on 1.4). */
    public static final List<String> RT_EXTENSIONS = List.of(
            VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
            VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
            VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);

    /** Set by SodiumCompat once the RT extension names are registered in Sodium's registry. */
    public static volatile boolean sodiumExtensionsRegistered = false;

    private static volatile boolean rtRequested;
    private static boolean loggedUnavailable;

    private RtDeviceBringup() {
    }

    /** True once we have augmented a device creation to request RT (extensions + features). */
    public static boolean rtRequested() {
        return rtRequested;
    }

    private static String firstUnsupported(VulkanPhysicalDevice physicalDevice) {
        for (String ext : RT_EXTENSIONS) {
            if (!physicalDevice.hasDeviceExtension(ext)) {
                return ext;
            }
        }
        return null;
    }

    /** Standalone path: add RT extension names to the (mutable) arg0 list. */
    public static void addExtensions(List<String> augmentedExtensions, VulkanPhysicalDevice physicalDevice) {
        if (!ENABLED_BY_PROPERTY || firstUnsupported(physicalDevice) != null) {
            return;
        }
        for (String ext : RT_EXTENSIONS) {
            if (!augmentedExtensions.contains(ext)) {
                augmentedExtensions.add(ext);
            }
        }
    }

    /**
     * Both paths: add the RT VulkanFeatures to arg2. Caller guarantees the extensions are
     * being enabled (standalone, or Sodium registration succeeded).
     */
    @SuppressWarnings("unchecked")
    public static void addFeatures(Args args, VulkanPhysicalDevice physicalDevice) {
        if (!ENABLED_BY_PROPERTY) {
            return;
        }
        String missing = firstUnsupported(physicalDevice);
        if (missing != null) {
            if (!loggedUnavailable) {
                loggedUnavailable = true;
                UpscalerMod.LOGGER.warn("Ray tracing unavailable: device [{}] lacks {}", physicalDevice.deviceName(), missing);
            }
            return;
        }

        Set<VulkanFeature> features = new HashSet<>((Set<VulkanFeature>) args.get(2));
        VulkanPNextStruct asStruct = new VulkanPNextStruct(
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR,
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.SIZEOF);
        VulkanPNextStruct rtStruct = new VulkanPNextStruct(
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR,
                VkPhysicalDeviceRayTracingPipelineFeaturesKHR.SIZEOF);
        // bufferDeviceAddress merges into vanilla's existing Vulkan12Features struct.
        features.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "bufferDeviceAddress",
                VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS));
        // P5.1b-2b bindless entity textures: a runtime-sized sampler2D[] indexed non-uniformly in the
        // hit shader, with partially-bound + update-after-bind slots (a growing per-RenderType registry).
        // Core on the VK 1.4 device; just needs enabling alongside bufferDeviceAddress on the same struct.
        features.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "runtimeDescriptorArray",
                VkPhysicalDeviceVulkan12Features.RUNTIMEDESCRIPTORARRAY));
        features.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "shaderSampledImageArrayNonUniformIndexing",
                VkPhysicalDeviceVulkan12Features.SHADERSAMPLEDIMAGEARRAYNONUNIFORMINDEXING));
        features.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "descriptorBindingPartiallyBound",
                VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGPARTIALLYBOUND));
        features.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "descriptorBindingSampledImageUpdateAfterBind",
                VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGSAMPLEDIMAGEUPDATEAFTERBIND));
        // shaderInt64: the world hit shader uses uint64_t buffer-reference addresses (Int64 capability).
        features.add(new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "shaderInt64",
                VkPhysicalDeviceFeatures.SHADERINT64));
        features.add(new VulkanFeature(asStruct, "accelerationStructure",
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE));
        features.add(new VulkanFeature(rtStruct, "rayTracingPipeline",
                VkPhysicalDeviceRayTracingPipelineFeaturesKHR.RAYTRACINGPIPELINE));
        args.set(2, features);

        rtRequested = true;
        UpscalerMod.LOGGER.info(
                "Ray tracing: enabling {} + features [bufferDeviceAddress, accelerationStructure, rayTracingPipeline] on [{}]",
                RT_EXTENSIONS, physicalDevice.deviceName());
    }

    /**
     * Post-creation verification: confirm the RT entry points actually loaded on the new
     * device and log the RT pipeline / acceleration-structure limits. If this logs "OK",
     * the device truly came up RT-capable — the P0 device half is proven.
     */
    public static void probe(VkDevice device) {
        if (!rtRequested) {
            UpscalerMod.LOGGER.info("Ray tracing not requested; skipping RT probe");
            return;
        }
        try {
            VKCapabilitiesDevice caps = device.getCapabilities();
            boolean rtPipeline = caps.vkCreateRayTracingPipelinesKHR != 0L;
            boolean asBuild = caps.vkCmdBuildAccelerationStructuresKHR != 0L;
            boolean traceRays = caps.vkCmdTraceRaysKHR != 0L;
            if (!(rtPipeline && asBuild && traceRays)) {
                UpscalerMod.LOGGER.error(
                        "RT extensions enabled but entry points missing (rtPipeline={}, asBuild={}, traceRays={}) — RT bring-up FAILED",
                        rtPipeline, asBuild, traceRays);
                return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps =
                        VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
                VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps =
                        VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default();
                rtProps.pNext(asProps.address());
                VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
                props2.pNext(rtProps.address());
                VK12.vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), props2);

                UpscalerMod.LOGGER.info(
                        "RT bring-up OK — shaderGroupHandleSize={}, shaderGroupBaseAlignment={}, maxRayRecursionDepth={}; "
                                + "maxAS geometry/instance/primitive = {}/{}/{}",
                        rtProps.shaderGroupHandleSize(), rtProps.shaderGroupBaseAlignment(), rtProps.maxRayRecursionDepth(),
                        asProps.maxGeometryCount(), asProps.maxInstanceCount(), asProps.maxPrimitiveCount());
            }
        } catch (Throwable t) {
            // A probe must never break device creation.
            UpscalerMod.LOGGER.error("RT probe threw; continuing without RT", t);
        }
    }
}
