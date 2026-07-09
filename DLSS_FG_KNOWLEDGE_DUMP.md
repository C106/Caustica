# DLSS Frame Generation Knowledge Dump

This is a context map for integrating NVIDIA DLSS Frame Generation (DLSS-FG / DLSSG)
with this mod. It is intentionally not an implementation checklist. It records what is
already present, what DLSS-FG expects, what is missing, and which local files are related.

## Local Context

The project is already a Vulkan/Fabric Minecraft renderer with:

- A hardware ray tracing composite path.
- DLSS Ray Reconstruction support through NGX.
- A native C ABI shim over NVIDIA NGX because Java FFM wants flat C symbols.
- Java FFM bindings for the shim.
- Vulkan device creation hooks that enable extensions/features needed by FSR, NGX, and RT.

The current DLSS path is DLSS-RR, not DLSS-FG. DLSS-RR runs inside the RT composite command
buffer and writes a reconstructed display-resolution image. DLSS-FG is different: it needs
to operate on final presented frames and it needs presentation/pacing control.

## Existing DLSS / NGX Pieces

Native shim:

- `native/ngx_shim/ngx_shim.cpp`
- Already includes:
  - `nvsdk_ngx.h`
  - `nvsdk_ngx_vk.h`
  - `nvsdk_ngx_helpers.h`
  - `nvsdk_ngx_helpers_vk.h`
  - `nvsdk_ngx_helpers_dlssd.h`
  - `nvsdk_ngx_helpers_dlssd_vk.h`
- Already exports:
  - `ngxshim_required_extensions`
  - `ngxshim_init`
  - `ngxshim_dlss_available`
  - `ngxshim_query_optimal`
  - `ngxshim_create_dlss`
  - `ngxshim_evaluate`
  - `ngxshim_dlssd_available`
  - `ngxshim_create_dlssd`
  - `ngxshim_evaluate_dlssd`
  - `ngxshim_release`
  - `ngxshim_shutdown`
  - `ngxshim_last_result`

Java FFM binding:

- `src/main/java/dev/comfyfluffy/caustica/ngx/NgxLibrary.java`
- Mirrors the shim exports with `MethodHandle`s.
- Already has bindings for DLSS-SR-ish create/evaluate and DLSS-RR create/evaluate.
- Does not have DLSSG bindings.

DLSS-RR runtime:

- `src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssRr.java`
- Locates/extracts `ngxshim.dll`.
- Extracts `nvngx_dlssd.dll`.
- Initializes NGX.
- Checks `dlssdAvailable()`.
- Creates/recreates a DLSS-RR feature by render/display resolution, quality, and preset.
- Evaluates RR over RT color and guide buffers.
- Shuts down NGX in `destroy()`.

Important ownership note:

- `RtDlssRr` currently owns NGX initialization and shutdown.
- DLSS-FG would also need NGX.
- If both features coexist, NGX lifetime probably needs to move to a shared runtime object so one feature does not shut down NGX while the other still owns a handle.

## Existing Vulkan / RT Pieces Related To FG

Device extension hook:

- `src/main/java/dev/comfyfluffy/caustica/mixin/VulkanBackendMixin.java`
- Adds NGX-related device extensions when present:
  - `VK_NVX_binary_import`
  - `VK_NVX_image_view_handle`
  - `VK_KHR_push_descriptor`
- Adds SDK shader-related features:
  - `shaderStorageImageWriteWithoutFormat`
  - `shaderInt16`
  - `shaderFloat16`
- Delegates RT extension/feature setup to `RtDeviceBringup`.

RT composite:

- `src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java`
- Captures camera/projection state through `captureFrame(...)`.
- Creates RT color and guide images:
  - `output`: HDR trace color, `R16G16B16A16_SFLOAT`.
  - `gNormal`: normal/roughness.
  - `gAlbedo`: diffuse albedo.
  - `gDepth`: linear view depth, `R32_SFLOAT`.
  - `gMotion`: screen-space motion vectors, `R16G16_SFLOAT`.
  - `gSpecAlbedo`: specular albedo.
  - `gSpecHitDistance`: currently debug/disabled for RR input.
  - `gSpecMotion`: reflection motion vectors.
  - `rrOutput`: display-resolution HDR output from RR or fallback blit.
  - `displayImage`: LDR image copied back to Minecraft's main target.
- Runs RT trace, exposure, optional DLSS-RR, display mapping, then copies into the main target.

Camera capture:

- `src/main/java/dev/comfyfluffy/caustica/mixin/GameRendererMixin.java`
- Captures projection/view/camera position at the level-render projection point.
- Ends the RT composite at the before-hand seam.

Important FG mismatch:

- DLSS-FG wants the final backbuffer in most integrations: post-processing, UI/HUD, etc.
- The current RT composite seam is before hand/HUD/some screen-fixed content.
- The swapchain blit/present path is a more relevant DLSS-FG hook than the current RT composite seam.

## Minecraft Vulkan Present Path

Mapped Minecraft class:

- `com.mojang.blaze3d.vulkan.VulkanGpuSurface`

Relevant methods from the mapped jar:

- `acquireNextTexture()`
- `blitFromTexture(CommandEncoderBackend, GpuTextureView)`
- `present()`

Important fields in `VulkanGpuSurface`:

- `device`
- `presentQueue`
- `surface`
- `swapchainImageFormat`
- `swapchain`
- `swapchainWidth`
- `swapchainHeight`
- `swapchainImages`
- `acquireSemaphores`
- `presentSemaphores`
- `currentImageIndex`
- `swapchainSuboptimal`
- `swapchainOutOfDate`

Current behavior:

- Minecraft acquires one swapchain image.
- It blits/copies the rendered texture into that swapchain image.
- `present()` builds `VkPresentInfoKHR`, waits on the current present semaphore, presents the current image, and resets `currentImageIndex` to `-1`.

DLSS-FG impact:

- FG needs at least one generated/interpolated output and one retained real output per rendered frame.
- Presenting both likely needs custom control over acquire/copy/present sequencing.
- The current one-current-image/one-present-per-frame surface flow is not enough by itself.
- Generated and real frames can be written to intermediate images, then copied/blitted into swapchain images for presentation.

## DLSS-FG SDK Pieces Present Locally

SDK headers:

- `../dlss-sdk/include/nvsdk_ngx_defs_dlssg.h`
- `../dlss-sdk/include/nvsdk_ngx_params_dlssg.h`
- `../dlss-sdk/include/nvsdk_ngx_helpers_dlssg.h`
- `../dlss-sdk/include/nvsdk_ngx_helpers_dlssg_vk.h`

Windows DLSSG DLLs:

- Dev: `../dlss-sdk/lib/Windows_x86_64/dev/nvngx_dlssg.dll`
- Rel: `../dlss-sdk/lib/Windows_x86_64/rel/nvngx_dlssg.dll`

Current bundled native list:

- `native/ngx_shim/out/Release/ngxshim.dll`
- `native/ngx_shim/out/Release/nvngx_dlssd.dll`

Missing from bundle:

- `native/ngx_shim/out/Release/nvngx_dlssg.dll`

## DLSS-FG Feature Expectations

Feature availability:

- Capability parameter:
  - `NVSDK_NGX_Parameter_FrameGeneration_Available`
- MFG capability parameter:
  - `NVSDK_NGX_DLSSG_Parameter_MultiFrameCountMax`
- Hardware support is not universal. The guide describes FG as RTX 40-series or higher. MFG should be gated by `MultiFrameCountMax`.

Feature creation uses `NVSDK_NGX_DLSSG_Create_Params`:

- `Width`
- `Height`
- `NativeBackbufferFormat`
- `RenderWidth`
- `RenderHeight`
- `DynamicResolutionScaling`

Vulkan helper:

- `NGX_VK_CREATE_DLSSG(...)`

Feature evaluation uses:

- `NVSDK_NGX_VK_DLSSG_Eval_Params`
- `NVSDK_NGX_DLSSG_Opt_Eval_Params`

Vulkan helper:

- `NGX_VK_EVALUATE_DLSSG(...)`

Each evaluate call generates one interpolated frame.

Single-frame generation:

- `multiFrameCount = 1`
- `multiFrameIndex = 1`

Multi-frame generation:

- Multiple evaluates per rendered frame.
- `multiFrameCount` must not exceed `MultiFrameCountMax`.
- `multiFrameIndex` runs from `1` to `multiFrameCount`.

## Required DLSS-FG Resources

Required input resources:

- `pBackbuffer`
  - Final color resource.
  - Usually the same content that would be presented without FG.
  - Should include final post-processing and UI unless using HUDless/UI recomposition inputs.
  - Must match the feature create width/height.

- `pDepth`
  - Hardware/non-linear depth.
  - Can be inverted or non-inverted, but `depthInverted` must match.
  - May be lower resolution than the backbuffer.
  - If reconstruction or upscaling is used, this is usually the same depth input used by that pass.

- `pMVecs`
  - Motion vectors from current frame to previous frame.
  - Pixel units at the motion-vector resource resolution.
  - Can be lower resolution than the backbuffer.
  - `mvecScale` can correct scale/sign.

Required output resources:

- `pOutputInterpFrame`
  - The generated/interpolated frame.
  - Same size and format as `pBackbuffer`.

Optional output resources:

- `pOutputRealFrame`
  - Retained rendered frame.
  - Same size and format as `pBackbuffer`.
  - Useful because FG/debug overlays can affect the real frame too.

- `pOutputDisableInterpolation`
  - Small buffer allowing the snippet to signal that interpolation should not be shown.

Recommended optional inputs:

- `pHudless`
  - Scene color without UI/HUD.
  - Must match `pBackbuffer` exactly for non-UI pixels.

- `pUI` or `pUIAlpha`
  - UI color/alpha for UI recomposition.
  - UI color should be premultiplied by alpha.

- `pBidirectionalDistortionField`
  - Needed if final color has distortion effects not aligned with depth/MVs/camera data.

## Current Inputs That Are Close But Not Sufficient

Motion vectors:

- Current RT motion buffer is `gMotion`.
- It is render resolution and in screen-space pixel units for the RT path.
- This is close to what DLSS-FG wants.
- It only represents RT-rendered scene content. It does not naturally cover hand/HUD/UI unless those are incorporated or separated through HUDless/UI inputs.

Depth:

- Current `gDepth` is linear view depth for DLSS-RR.
- DLSS-FG expects hardware/non-linear depth.
- Existing `gDepth` should not be assumed correct for FG.
- A separate FG depth buffer or access to Minecraft's main hardware depth is likely needed.

Color:

- Current `displayImage` is LDR RT display output copied into the main target before hand/HUD.
- DLSS-FG wants final presented color.
- The final surface texture passed to `VulkanGpuSurface.blitFromTexture(...)` is more relevant than `displayImage`.

Camera data:

- `RtComposite.captureFrame(...)` already stores projection, view rotation, and camera position.
- DLSS-FG needs more complete eval data:
  - current view-to-clip
  - clip-to-view
  - clip-to-prev-clip
  - prev-clip-to-clip
  - jitter offset
  - camera position
  - camera up/right/forward
  - near/far/fov/aspect
- Matrices must be jitter-free according to the SDK guide.
- Existing RR helper `putNgxLeftMultiplyMatrix(...)` is related but not enough by itself.

## Missing Native Pieces

The native shim does not currently include DLSSG headers.

Missing likely exports:

- `ngxshim_dlssg_available`
- `ngxshim_dlssg_multi_frame_count_max`
- `ngxshim_create_dlssg`
- `ngxshim_evaluate_dlssg`

Missing likely helper usage:

- `NGX_VK_CREATE_DLSSG`
- `NGX_VK_EVALUATE_DLSSG`

Missing/native resource handling:

- Vulkan `NVSDK_NGX_Resource_VK` creation for FG resources:
  - backbuffer
  - hardware depth
  - motion vectors
  - optional hudless
  - optional UI/UI alpha
  - optional distortion field
  - output interpolated
  - output real
  - output disable interpolation buffer

## Missing Java Pieces

Missing FFM bindings in `NgxLibrary`:

- `dlssgAvailable()`
- `dlssgMultiFrameCountMax()`
- `createDlssg(...)`
- `evaluateDlssg(...)`

Missing runtime wrapper:

- A DLSS-FG runtime class equivalent to `RtDlssRr`.
- A shared NGX runtime/lifetime object if RR and FG are enabled together.

Missing configuration:

- Enable/disable FG.
- Requested multiplier / generated frame count.
- Debug/availability logging.
- Possibly "present FG only when final-frame data is valid".
- Possibly "disable FG in menus/loading/screens".

## Missing Render Resources

Likely needed images/buffers:

- Final color input, or a copy of the final color, in a format DLSSG accepts.
- Interpolated output image, same size/format as final color.
- Real output image, same size/format as final color.
- Hardware/non-linear depth image, or access to an existing valid depth image.
- Motion-vector image aligned to the rendered scene.
- Optional disable-interpolation buffer.
- Optional HUDless/UI/UIAlpha images for UI recomposition.

Current `RtContext.createStorageImage(...)` creates sampled/storage/transfer images in `GENERAL` layout. That pattern is relevant for intermediate FG output images, but swapchain images are owned by `VulkanGpuSurface`.

## Missing Presentation / Pacing Control

DLSS-FG does not present frames for the application.

The application is responsible for:

- Evaluating FG after the real frame is available.
- Presenting the generated frame first.
- Presenting the retained real frame after that.
- Keeping roughly even spacing between generated and real frames.
- Handling MFG by presenting multiple generated frames in generated order, then the real frame.
- Avoiding presenting generated frames when the disable-interpolation output says not to.

Current Minecraft surface flow:

- One acquired swapchain image.
- One blit into that image.
- One `vkQueuePresentKHR`.

DLSS-FG needs a broader flow:

- Render real frame.
- Evaluate FG into intermediate generated/real images.
- Acquire/present enough swapchain images to display generated and real outputs.
- Copy/blit intermediate images into swapchain images.
- Submit synchronization so present waits on the right semaphores.

This is why `VulkanGpuSurface.present()` and `VulkanGpuSurface.blitFromTexture(...)` are central.

## Missing Synchronization Model

DLSS-FG evaluate calls do not provide presentation synchronization by themselves.

The mod would need explicit handling for:

- Command buffer submission containing `NGX_VK_EVALUATE_DLSSG`.
- Resource barriers before NGX reads inputs.
- Resource barriers after NGX writes outputs.
- Copy/blit from FG output images to swapchain images.
- Present semaphore waits/signals per output frame.
- In-flight ownership/lifetime of generated/real images.
- Swapchain resize/out-of-date/suboptimal handling.

Existing useful patterns:

- `RtComposite.recordFrame(...)` for recording one-shot per-frame command buffers.
- `VulkanCommandEncoder.waitSemaphore(...)`
- `VulkanCommandEncoder.signalSemaphore(...)`
- `VulkanGpuSurface.blitFromTexture(...)` bytecode for swapchain image layout/copy/present semaphore flow.

## UI / HUD Complications

FG quality is sensitive to UI.

Possible UI modes:

- Treat final frame as one backbuffer with UI included.
  - Simplest input model.
  - Generated UI can smear/warp because UI has no meaningful depth/MVs.

- Provide HUDless + UI/UIAlpha inputs.
  - Better quality.
  - Requires a way to capture final scene without UI and UI separately.
  - Current RT composite already happens before hand/HUD, which may be useful as a HUDless-ish source, but it does not include all post-processing and may not exactly equal final non-UI pixels.

Current project does not appear to have:

- A UI-only color/alpha capture.
- A final HUDless color matching final backbuffer non-UI pixels.
- A final frame motion/depth treatment for hand/HUD.

## Depth Complications

DLSS-RR and DLSS-FG want different depth conventions.

Current RR path:

- Uses linear view depth in `gDepth`.
- Shim creates DLSSD with linear depth mode.

FG expected path:

- Uses hardware/non-linear depth.
- Uses `depthInverted` to describe whether near is high or low.
- Internal FG linearization depends on the depth convention.

Missing:

- A confirmed valid hardware/non-linear depth image at the same point as final color.
- A known `depthInverted` value for the chosen depth source.
- A resize/lifetime path for that depth source.

## Motion Vector Complications

The RT path already writes good scene motion vectors for RR.

FG still needs:

- Motion vectors aligned with the chosen backbuffer and depth.
- Correct sign and scale.
- Reset behavior on scene cuts/resizes/world changes.
- Treatment for pixels not covered by RT scene vectors.
- Treatment for UI/hand/screen-space effects if included in final color.

Current `gMotion` is likely useful for RT-only FG experiments, but it is not automatically a complete final-frame MV input.

## Format / Color Space Complications

Current RT display path:

- Traces HDR into `R16G16B16A16_SFLOAT`.
- Maps to `R8G8B8A8_UNORM` display image.
- Copies into Minecraft's main target.

FG resource constraints:

- `pBackbuffer`, `pOutputInterpFrame`, and `pOutputRealFrame` must match size/format.
- `NativeBackbufferFormat` must describe the actual native backbuffer format.
- HDR handling has separate SDK guidance.

Unknowns to confirm locally:

- Exact final surface source texture format at `VulkanGpuSurface.blitFromTexture(...)`.
- Whether final color is SDR UNORM, scRGB, HDR, or another swapchain-compatible format.
- Whether output images can be made in that exact format with storage/write usage accepted by NGX.

## Runtime Support / Availability Gating

FG should be gated by:

- Vulkan backend active.
- NVIDIA NGX initialized.
- `FrameGeneration.Available == 1`.
- `nvngx_dlssg.dll` present and loadable.
- Supported driver/GPU.
- Valid final color/depth/motion inputs.
- Valid swapchain/present hook state.
- Not in swapchain resize/out-of-date state.

MFG should be gated by:

- `DLSSG.MultiFrameCountMax`.
- Present pacing support for more than one generated frame.

## Reflex / Latency Note

No NVIDIA Reflex or latency marker integration is visible in this repo.

DLSS-FG usually has latency implications because presented FPS increases without the game simulation producing new real frames at the same rate. The local DLSS-FG guide section inspected here focuses on FG evaluation and presentation pacing, but a production-quality user-facing integration may need latency handling beyond just NGX DLSSG calls.

## Related Local Files

Core NGX / DLSS:

- `native/ngx_shim/ngx_shim.cpp`
- `native/ngx_shim/CMakeLists.txt`
- `src/main/java/dev/comfyfluffy/caustica/ngx/NgxLibrary.java`
- `src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssRr.java`

Build/bundling:

- `build.gradle`
- `native/ngx_shim/out/Release/ngxshim.dll`
- `native/ngx_shim/out/Release/nvngx_dlssd.dll`
- `../dlss-sdk/lib/Windows_x86_64/dev/nvngx_dlssg.dll`
- `../dlss-sdk/lib/Windows_x86_64/rel/nvngx_dlssg.dll`

RT/frame data:

- `src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java`
- `shaders/rt/world.rgen`
- `src/main/java/dev/comfyfluffy/caustica/client/CausticaJitter.java`
- `src/main/java/dev/comfyfluffy/caustica/mixin/GameRendererMixin.java`

Vulkan/device/present:

- `src/main/java/dev/comfyfluffy/caustica/mixin/VulkanBackendMixin.java`
- `src/main/java/dev/comfyfluffy/caustica/rt/RtDeviceBringup.java`
- `src/main/java/dev/comfyfluffy/caustica/rt/RtContext.java`
- Minecraft mapped class: `com.mojang.blaze3d.vulkan.VulkanGpuSurface`
- Minecraft mapped class: `com.mojang.blaze3d.vulkan.VulkanCommandEncoder`
- Minecraft mapped class: `com.mojang.blaze3d.vulkan.VulkanDevice`

Config/UI:

- `src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java`
- `src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java`
- `src/main/resources/assets/caustica/lang/en_us.json`

## Open Unknowns

- Exact final texture format passed into `VulkanGpuSurface.blitFromTexture(...)`.
- Whether the final texture has usage/layout suitable for NGX input or needs a copy.
- Best source for hardware/non-linear depth at final-frame time.
- Whether a separate RT hardware-depth guide should be generated.
- Whether FG is intended for RT output only, vanilla output only, or both.
- How to represent hand/HUD/UI:
  - included in final color only
  - separated into HUDless/UI inputs
  - disabled for UI-heavy frames
- How many swapchain images are practically available for generated + real presentation.
- Whether Minecraft's current frame submission model can support async/even pacing without a larger surface rewrite.
- How resize/out-of-date swapchain recovery should interact with FG feature recreation.
- Whether debug overlay / disable interpolation output should be wired from the start.
- Whether release or dev DLSSG DLL should be bundled for local testing.

## Useful Sanity Signals

Availability signals:

- NGX init succeeds.
- `FrameGeneration.Available` reads as true on supported hardware.
- `DLSSG.MultiFrameCountMax` is readable.
- Missing/old driver path reports useful init result data instead of crashing.

Resource signals:

- `pBackbuffer`, `pOutputInterpFrame`, and `pOutputRealFrame` have identical size/format.
- Motion vector scale/sign can be checked with camera pans.
- Depth convention can be checked with near/far geometry and `depthInverted`.
- Reset path produces copied real frames rather than unstable interpolation.

Presentation signals:

- Generated frame can be copied to swapchain and presented.
- Real frame can be retained/copied/presented after the generated frame.
- Resize does not leave stale NGX handles or stale swapchain image references.
- Disabling FG returns to the vanilla one-present flow.
