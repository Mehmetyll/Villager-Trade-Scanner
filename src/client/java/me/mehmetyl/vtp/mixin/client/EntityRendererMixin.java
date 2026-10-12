package me.mehmetyl.vtp.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import me.mehmetyl.vtp.client.render.VtpLabelRenderer;
import me.mehmetyl.vtp.client.render.VtpRenderData;
import net.minecraft.client.renderer.entity.EntityRenderer;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

//? if >=1.21.9 {
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
//?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;
*///?}
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the glow colour and the floating label into the entity render pipeline.
 *
 * <p>From 1.21.9 that pipeline is extract/submit; below it, a single render
 * call. The glow hook is the same on both, because extractRenderState exists
 * either way.
 *
 * <p>Both injections are TAIL and purely additive. Vanilla assigns
 * {@code state.outlineColor} inside {@code extractRenderState} itself, so
 * injecting at its tail is what lets us override the colour without touching the
 * entity or its glowing flag -- nothing is written back to the world, so there is
 * no possibility of desync with the server.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void vtp$applyHighlight(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        VtpLabelRenderer.extract(entity, state);
    }

    // 1.21.9 split entity drawing into extract and submit. Before that a single
    // render() call does both, taking a MultiBufferSource rather than a
    // collector, so the label goes out from there instead.
    //? if >=1.21.9 {
    @Inject(method = "submit", at = @At("TAIL"))
    private void vtp$submitLabel(EntityRenderState state, PoseStack poseStack,
                                 SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        VtpRenderData data = state.getData(VtpRenderData.KEY);
        if (data != null) {
            VtpLabelRenderer.submit(state, data, poseStack, collector, camera);
        }
    }
    //?} else {
    /*@Inject(method = "render", at = @At("TAIL"))
    private void vtp$renderLabel(EntityRenderState state, PoseStack poseStack,
                                 MultiBufferSource buffers, int packedLight, CallbackInfo ci) {
        VtpRenderData data = state.getData(VtpRenderData.KEY);
        if (data != null) {
            VtpLabelRenderer.render(state, data, poseStack, buffers, packedLight);
        }
    }
    *///?}
}
