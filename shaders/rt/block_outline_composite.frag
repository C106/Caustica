#version 460

// Composites the MSAA-resolved block-outline mask onto the main render target via fixed-function blending
// (SRC_ALPHA, ONE_MINUS_SRC_ALPHA), same reasoning as entity_glow_composite.frag: vanilla's main render
// target is never VK_IMAGE_USAGE_STORAGE_BIT, so it can only be written as a colour attachment. Unlike the
// glow mask (a binary silhouette that still needs a Sobel edge extracted), the resolved outline mask IS the
// final antialiased colour+coverage already — RtBlockOutlineFeature's mask pass draws the line list at 4x
// MSAA into a scratch attachment that dynamic rendering resolve-averages into this mask, turning per-sample
// rasterizer coverage into a fractional alpha. Straight passthrough.
layout(binding = 0, set = 0, rgba8) uniform readonly image2D maskImage;

layout(location = 0) out vec4 outColor;

void main() {
    outColor = imageLoad(maskImage, ivec2(gl_FragCoord.xy));
}
