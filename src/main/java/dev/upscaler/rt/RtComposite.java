package dev.upscaler.rt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.upscaler.UpscalerMod;
import dev.upscaler.client.SodiumCompat;
import dev.upscaler.mixin.CommandEncoderAccessor;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;

import java.nio.ByteBuffer;

/**
 * On-screen composite. Each frame, ray-trace into a screen-sized storage image, blend it 50/50
 * with a storage-capable copy of the upscaled world color, and copy the result back to the world
 * target at the end-of-world seam. Gated by {@code -Dupscaler.rt.composite=true}.
 *
 * <p>When {@link RtTerrain} has been extracted (P1), traces real terrain with perspective camera
 * rays (camera matrices captured each frame via {@link #captureFrame}); otherwise falls back to
 * the P0 triangle. Pipelines/SBT/descriptors are built once; screen-sized images rebuilt on resize.
 */
public final class RtComposite {
    public static final RtComposite INSTANCE = new RtComposite();
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("upscaler.rt.composite", "false"));
    /** Blend weight of RT over vanilla: 0 = vanilla only, 1 = RT only. {@code -Dupscaler.rt.blend}. */
    public static final float BLEND = parseBlend();

    private static final int WORLD_PUSH_SIZE = 88; // mat4 invViewProj (64) + vec3 camOffset (@64) + uint64 normalAddr (@80)

    private static float parseBlend() {
        try {
            return Math.clamp(Float.parseFloat(System.getProperty("upscaler.rt.blend", "0.5")), 0f, 1f);
        } catch (NumberFormatException e) {
            return 0.5f;
        }
    }

    private RtPipeline trianglePipeline;
    private RtPipeline worldPipeline;
    private RtBlendPipeline blendPipeline;
    private RtImage output;
    private RtImage baseCopy;
    private long boundTriangleTlas;
    private long boundWorldTlas;
    private boolean failed;
    private boolean loggedActive;

    // Camera captured each frame from GameRenderer (unjittered level projection + camera rotation + pos).
    private final Matrix4f frameProjection = new Matrix4f();
    private final Matrix4f frameViewRotation = new Matrix4f();
    private double camX;
    private double camY;
    private double camZ;
    private boolean frameCaptured;

    private RtComposite() {
    }

    /** Capture the frame's camera for the next composite. Called from GameRendererMixin. */
    public void captureFrame(Matrix4f projection, Matrix4fc viewRotation, double cameraX, double cameraY, double cameraZ) {
        frameProjection.set(projection);
        frameViewRotation.set(viewRotation);
        camX = cameraX;
        camY = cameraY;
        camZ = cameraZ;
        frameCaptured = true;
    }

    public boolean composite(GpuTexture nativeColor, int width, int height) {
        if (failed) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return false;
        }
        boolean useWorld = RtTerrain.currentOrNull() != null && frameCaptured;
        if (!useWorld && RtTriangleScene.currentOrNull() == null) {
            return false; // nothing to trace yet
        }
        try {
            if (blendPipeline == null) {
                blendPipeline = RtBlendPipeline.create(ctx);
            }
            ensureOutput(ctx, width, height);
            RtPipeline active = useWorld ? ensureWorld(ctx) : ensureTriangle(ctx);
            recordFrame(active, useWorld, nativeColor, width, height);
            if (!loggedActive) {
                loggedActive = true;
                UpscalerMod.LOGGER.info("RT composite active ({}): {}x{}, RT blended at {} over the world target",
                        useWorld ? "terrain" : "triangle", width, height, BLEND);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            UpscalerMod.LOGGER.error("RT composite failed; reverting to vanilla/upscaler path", t);
            return false;
        }
    }

    private RtPipeline ensureWorld(RtContext ctx) {
        if (worldPipeline == null) {
            worldPipeline = RtPipeline.create(ctx, "world.rgen.spv", "world.rmiss.spv", "world.rchit.spv", WORLD_PUSH_SIZE);
            if (output != null) {
                worldPipeline.setStorageImage(output.view);
            }
        }
        long tlas = RtTerrain.currentOrNull().tlas();
        if (boundWorldTlas != tlas) {
            worldPipeline.setTlas(tlas);
            boundWorldTlas = tlas;
        }
        return worldPipeline;
    }

    private RtPipeline ensureTriangle(RtContext ctx) {
        if (trianglePipeline == null) {
            trianglePipeline = RtPipeline.create(ctx, "triangle.rgen.spv", "triangle.rmiss.spv", "triangle.rchit.spv");
            if (output != null) {
                trianglePipeline.setStorageImage(output.view);
            }
        }
        long tlas = RtTriangleScene.currentOrNull().tlas();
        if (boundTriangleTlas != tlas) {
            trianglePipeline.setTlas(tlas);
            boundTriangleTlas = tlas;
        }
        return trianglePipeline;
    }

    private void ensureOutput(RtContext ctx, int width, int height) {
        if (output != null && baseCopy != null && output.width == width && output.height == height
                && baseCopy.width == width && baseCopy.height == height) {
            return;
        }
        ctx.waitIdle(); // resize is rare; no in-flight frame may use the old image/descriptor
        if (baseCopy != null) {
            baseCopy.destroy();
        }
        if (output != null) {
            output.destroy();
        }
        output = ctx.createStorageImage(width, height);
        baseCopy = ctx.createStorageImage(width, height);
        if (trianglePipeline != null) {
            trianglePipeline.setStorageImage(output.view);
        }
        if (worldPipeline != null) {
            worldPipeline.setStorageImage(output.view);
        }
        blendPipeline.setImages(baseCopy.view, output.view);
    }

    private void recordFrame(RtPipeline active, boolean useWorld, GpuTexture nativeColor, int width, int height) {
        long dstImage = vkImage(nativeColor);
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).upscaler$getBackend();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (useWorld) {
                RtTerrain terrain = RtTerrain.currentOrNull();
                ByteBuffer push = stack.malloc(WORLD_PUSH_SIZE);
                new Matrix4f(frameProjection).mul(frameViewRotation).invert().get(0, push);
                push.putFloat(64, (float) (camX - terrain.blockX));
                push.putFloat(68, (float) (camY - terrain.blockY));
                push.putFloat(72, (float) (camZ - terrain.blockZ));
                push.putLong(80, terrain.primAddress());
                active.trace(cmd, width, height, push);
            } else {
                active.trace(cmd, width, height);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            VK10.vkCmdCopyImage(cmd, dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    baseCopy.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, width, height));
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            blendPipeline.dispatch(cmd, width, height, BLEND);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            VK10.vkCmdCopyImage(cmd, baseCopy.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, width, height));
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(rt composite) failed");
        }
        encoder.execute(cmd); // deferred into the frame's submission — correct for per-frame work
    }

    public void destroy() {
        if (baseCopy != null) {
            baseCopy.destroy();
            baseCopy = null;
        }
        if (output != null) {
            output.destroy();
            output = null;
        }
        if (blendPipeline != null) {
            blendPipeline.destroy();
            blendPipeline = null;
        }
        if (worldPipeline != null) {
            worldPipeline.destroy();
            worldPipeline = null;
        }
        if (trianglePipeline != null) {
            trianglePipeline.destroy();
            trianglePipeline = null;
        }
        boundTriangleTlas = 0L;
        boundWorldTlas = 0L;
    }

    private static long vkImage(GpuTexture texture) {
        Long sodiumHandle = SodiumCompat.vkImage(texture);
        if (sodiumHandle != null) {
            return sodiumHandle;
        }
        if (texture instanceof VulkanGpuTexture vulkanTexture) {
            return vulkanTexture.vkImage();
        }
        throw new IllegalStateException("cannot resolve VkImage for " + texture);
    }

    private static VkImageCopy.Buffer copyRegion(MemoryStack stack, int width, int height) {
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).extent().set(width, height, 1);
        return region;
    }
}
