package dev.upscaler.rt;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;

import java.util.List;

/**
 * P5.1b-2 capture infrastructure: a {@link SubmitNodeCollector} that intercepts entity model
 * submissions and renders them straight into an {@link RtEntityCapture}, reusing all of vanilla's
 * posing/animation. {@code submitModel} is the hook (it is where {@code LivingEntityRenderer.submit}
 * sends the body + each feature layer); we pose the model from its render state and render it into the
 * capture. Every other submit* path (name tags, leashes, shadows, flames, held items, block models,
 * custom geometry, particles, gizmos) is a no-op — the entity *body geometry* is what we want for RT;
 * held items / custom geometry are not captured yet (acceptable, noted for P5.1b-2b).
 *
 * <p>Driven once per entity per probe/frame: {@link #begin} sets the capture, then {@code
 * EntityRenderDispatcher.submit} fans out into {@code submitModel} here. Reused across entities.
 */
public final class RtEntityCollector implements SubmitNodeCollector {
    private RtEntityCapture capture;
    private RenderType firstRenderType; // P5.1b-2b: the entity's primary (body) render type → texture

    /** Point the collector at the capture buffer for the next {@code dispatcher.submit}. */
    public void begin(RtEntityCapture capture) {
        this.capture = capture;
        this.firstRenderType = null;
    }

    /** The first render type submitted this capture (the body), used to resolve the entity texture. */
    public RenderType firstRenderType() {
        return firstRenderType;
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                int lightCoords, int overlayCoords, int tintedColor, TextureAtlasSprite sprite,
                                int outlineColor, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        if (capture == null) {
            return;
        }
        if (firstRenderType == null) {
            firstRenderType = renderType;
        }
        // Resolve this submission's texture to a bindless slot; the capture stamps it on every prim it
        // emits, so the body and each feature layer (armor, eyes, …) keep their own texture.
        capture.currentTexSlot = RtEntityTextures.INSTANCE.slotFor(renderType);
        // Pose the model from its render state (idempotent re-pose; mirrors what the renderer does for
        // its feature layers), then render the posed parts into the capture. renderToBuffer applies the
        // PoseStack to every vertex/normal, so the capture receives world-/camera-relative geometry.
        model.setupAnim(state);
        int color = tintedColor == 0 ? -1 : tintedColor; // 0 would be fully transparent black; treat as white
        model.renderToBuffer(poseStack, capture, lightCoords, overlayCoords, color);
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return this; // single un-ordered capture sink — ordering is irrelevant for geometry extraction
    }

    // --- Everything below is intentionally a no-op: not entity body geometry. ---

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    }

    @Override
    public void submitNameTag(PoseStack poseStack, Vec3 nameTagAttachment, int offset, Component name,
                              boolean seeThrough, int lightCoords, CameraRenderState camera) {
    }

    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow,
                           Font.DisplayMode displayMode, int lightCoords, int color, int backgroundColor, int outlineColor) {
    }

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int outlineColor) {
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
                                 int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {
    }

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color,
                                   float width, boolean afterTerrain) {
    }

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords, int overlayCoords,
                           int outlineColor, int[] tintLayers, List<net.minecraft.client.resources.model.geometry.BakedQuad> quads,
                           ItemStackRenderState.FoilType foilType) {
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                     SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
    }
}
