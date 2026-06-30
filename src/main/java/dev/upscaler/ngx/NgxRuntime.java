package dev.upscaler.ngx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;

import dev.upscaler.UpscalerConfig;
import dev.upscaler.UpscalerMod;
import dev.upscaler.mixin.GpuDeviceAccessor;

import net.fabricmc.loader.api.FabricLoader;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkInstance;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Shared NVIDIA NGX lifetime for the mod. Loads the native shim, extracts the bundled NGX feature DLLs,
 * and runs {@code ngxshim_init} / {@code ngxshim_shutdown} exactly once per Vulkan device. Multiple NGX
 * features (DLSS Ray Reconstruction, and later Frame Generation) share this single initialized
 * {@link NgxLibrary}; each feature owns only its own create/evaluate/release. NGX is shut down only at
 * device teardown (so releasing one feature can't tear NGX down while another still holds a handle).
 */
public final class NgxRuntime {
    public static final NgxRuntime INSTANCE = new NgxRuntime();

    private static final String SHIM_DLL = "ngxshim.dll";
    private static final String BUNDLED_NATIVE_DIR = "/upscaler/natives/windows-x64/";
    // Feature DLLs extracted next to the shim so NGX can load them at init. Add nvngx_dlssg.dll here when
    // Frame Generation is bundled. Missing ones are skipped (the corresponding feature just reports
    // unavailable).
    private static final String[] FEATURE_DLLS = {"nvngx_dlssd.dll"};

    private NgxLibrary lib;
    private boolean initialized;
    private boolean failed;

    private NgxRuntime() {
    }

    /**
     * Ensure NGX is loaded and initialized for {@code device}, returning the shared {@link NgxLibrary}, or
     * {@code null} if it is unavailable. Idempotent; latches failure so it isn't retried every frame
     * (cleared by {@link #shutdown()} so a fresh device can re-init).
     */
    public synchronized NgxLibrary acquire(VulkanDevice device) {
        if (initialized) {
            return lib;
        }
        if (failed) {
            return null;
        }
        try {
            init(device);
            initialized = true;
            return lib;
        } catch (Throwable t) {
            failed = true;
            lib = null;
            UpscalerMod.LOGGER.error("NGX init failed; DLSS features disabled", t);
            return null;
        }
    }

    public synchronized boolean isInitialized() {
        return initialized;
    }

    /** The shared library once {@link #acquire} has succeeded, else {@code null}. */
    public NgxLibrary library() {
        return lib;
    }

    /**
     * Shut down NGX. Call only at device teardown, after every feature has been released. Resolves the
     * device from the current render backend; no-op if NGX was never initialized.
     */
    public synchronized void shutdown() {
        if (lib != null && initialized
                && ((GpuDeviceAccessor) RenderSystem.getDevice()).upscaler$getBackend() instanceof VulkanDevice device) {
            try {
                lib.shutdown(device.vkDevice().address());
            } catch (Throwable t) {
                UpscalerMod.LOGGER.warn("NGX shutdown failed", t);
            }
        }
        initialized = false;
        failed = false;
        lib = null;
    }

    /** NVSDK_NGX_Result: failure when the top 12 bits == 0xBAD. Shared by all NGX feature wrappers. */
    public static boolean ngxFailed(int result) {
        return (result & 0xFFF00000) == 0xBAD00000;
    }

    private void init(VulkanDevice device) {
        Path shim = locateShim();
        if (shim == null) {
            throw new IllegalStateException(SHIM_DLL + " not found (bundled natives or -Dupscaler.ngx.path)");
        }
        Path nativesDir = shim.getParent();

        lib = NgxLibrary.load(shim);

        Path dataPath = FabricLoader.getInstance().getGameDir().resolve("upscaler-ngx");
        try {
            Files.createDirectories(dataPath);
        } catch (Exception e) {
            UpscalerMod.LOGGER.warn("Could not create NGX data path {}", dataPath, e);
        }

        VkInstance instance = device.vkDevice().getPhysicalDevice().getInstance();
        try (Arena arena = Arena.ofConfined()) {
            long gdpa;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                gdpa = VK10.vkGetInstanceProcAddr(instance, stack.ASCII("vkGetDeviceProcAddr"));
            }
            int rc = lib.init(0L, wideString(arena, dataPath.toString()),
                    instance.address(), device.vkDevice().getPhysicalDevice().address(), device.vkDevice().address(),
                    0L, gdpa, wideString(arena, nativesDir == null ? "" : nativesDir.toString()));
            if (ngxFailed(rc)) {
                throw new IllegalStateException("ngxshim_init failed: 0x" + Integer.toHexString(rc)
                        + " last=0x" + Integer.toHexString(lib.lastResult()));
            }
        }
        UpscalerMod.LOGGER.info("NGX initialized (shim {})", shim);
    }

    private static Path locateShim() {
        String override = UpscalerConfig.Ngx.PATH.get();
        if (override != null) {
            Path p = Path.of(override);
            return Files.isRegularFile(p) ? p : null;
        }
        return extractBundledNatives();
    }

    private static Path extractBundledNatives() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("upscaler-ngx").resolve("natives");
        try {
            Files.createDirectories(dir);
            boolean hasShim = extractBundledNative(SHIM_DLL, dir.resolve(SHIM_DLL));
            for (String featureDll : FEATURE_DLLS) {
                if (!extractBundledNative(featureDll, dir.resolve(featureDll))) {
                    UpscalerMod.LOGGER.warn("bundled NGX feature DLL {} not present; its feature will be unavailable", featureDll);
                }
            }
            return hasShim && Files.isRegularFile(dir.resolve(SHIM_DLL)) ? dir.resolve(SHIM_DLL) : null;
        } catch (IOException e) {
            UpscalerMod.LOGGER.warn("Could not extract bundled NGX natives to {}", dir, e);
            return null;
        }
    }

    private static boolean extractBundledNative(String name, Path dst) throws IOException {
        String resource = BUNDLED_NATIVE_DIR + name;
        try (InputStream in = NgxRuntime.class.getResourceAsStream(resource)) {
            if (in == null) {
                return false;
            }
            byte[] bytes = in.readAllBytes();
            if (!sameBytes(dst, bytes)) {
                Files.write(dst, bytes);
            }
            return true;
        }
    }

    private static boolean sameBytes(Path path, byte[] bytes) throws IOException {
        try {
            return Files.size(path) == bytes.length && Arrays.equals(Files.readAllBytes(path), bytes);
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    private static MemorySegment wideString(Arena arena, String s) {
        byte[] utf16 = s.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment seg = arena.allocate(utf16.length + 2L);
        MemorySegment.copy(utf16, 0, seg, ValueLayout.JAVA_BYTE, 0, utf16.length);
        seg.set(ValueLayout.JAVA_BYTE, utf16.length, (byte) 0);
        seg.set(ValueLayout.JAVA_BYTE, utf16.length + 1, (byte) 0);
        return seg;
    }
}
