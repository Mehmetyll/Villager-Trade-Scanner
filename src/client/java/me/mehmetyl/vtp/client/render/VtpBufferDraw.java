package me.mehmetyl.vtp.client.render;

// Writes the label's quads through a MultiBufferSource, for every version below
// 1.21.9.
//
// It lives apart from VtpLabelRenderer because the two entry points that use it
// -- one taking a render state, one taking the entity, depending on whether
// render states exist -- sit in different Stonecutter branches, and a shared
// helper could not be nested inside either: Java block comments do not nest, so
// a condition inside a branch that is itself commented out on other versions
// would not survive. A separate file needs only one condition.
//
// From 1.21.9 none of this applies: MultiBufferSource is gone and the submit
// pipeline takes its place, so the class does not exist there.
//? if <1.21.9 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;

public final class VtpBufferDraw {
    // Kept in step with VtpLabelRenderer; the geometry is the same on both sides
    // of the 1.21.9 boundary.
    private static final float ICON_SIZE = 9.0f;
    private static final float ICON_GAP = 2.0f;

    private static final int SHADE_TOP = 0xFFFFFFFF;
    private static final int SHADE_LEFT = 0xFFCCCCCC;
    private static final int SHADE_RIGHT = 0xFF999999;

    private VtpBufferDraw() {
    }

    public static void background(PoseStack poseStack, MultiBufferSource buffers,
                                         float start, float y, float width, int argb, int light) {
        float left = start - 1.0f;
        float right = start + width + 1.0f;
        float top = y - 1.0f;
        float bottom = y + 9.0f;

        var pose = poseStack.last();
        VertexConsumer vertices = buffers.getBuffer(RenderTypes.textBackgroundSeeThrough());
        vertices.addVertex(pose, left, bottom, 0.0f).setColor(argb).setLight(light);
        vertices.addVertex(pose, right, bottom, 0.0f).setColor(argb).setLight(light);
        vertices.addVertex(pose, right, top, 0.0f).setColor(argb).setLight(light);
        vertices.addVertex(pose, left, top, 0.0f).setColor(argb).setLight(light);
    }

    public static float icon(VtpRenderData.Icon sprite, PoseStack poseStack,
                                    MultiBufferSource buffers, float x, float y, int light) {
        if (sprite == null) {
            return x;
        }

        var pose = poseStack.last();
        VertexConsumer vertices = buffers.getBuffer(RenderTypes.textSeeThrough(sprite.atlas()));

        if (sprite.cube()) {
            float half = ICON_SIZE / 2.0f;
            float quarter = ICON_SIZE / 4.0f;
            float cx = x + half;
            float cy = y - 1.0f + half;

            float topX = cx, topY = cy - half;
            float ulX = cx - half, ulY = cy - quarter;
            float urX = cx + half, urY = cy - quarter;
            float midX = cx, midY = cy;
            float llX = cx - half, llY = cy + quarter;
            float lrX = cx + half, lrY = cy + quarter;
            float botX = cx, botY = cy + half;

            // Top face.
            vertices.addVertex(pose, ulX, ulY, 0.0f).setColor(SHADE_TOP).setUv(sprite.u0(), sprite.v1()).setLight(light);
            vertices.addVertex(pose, midX, midY, 0.0f).setColor(SHADE_TOP).setUv(sprite.u1(), sprite.v1()).setLight(light);
            vertices.addVertex(pose, urX, urY, 0.0f).setColor(SHADE_TOP).setUv(sprite.u1(), sprite.v0()).setLight(light);
            vertices.addVertex(pose, topX, topY, 0.0f).setColor(SHADE_TOP).setUv(sprite.u0(), sprite.v0()).setLight(light);

            // Left face.
            vertices.addVertex(pose, llX, llY, 0.0f).setColor(SHADE_LEFT).setUv(sprite.u0(), sprite.v1()).setLight(light);
            vertices.addVertex(pose, botX, botY, 0.0f).setColor(SHADE_LEFT).setUv(sprite.u1(), sprite.v1()).setLight(light);
            vertices.addVertex(pose, midX, midY, 0.0f).setColor(SHADE_LEFT).setUv(sprite.u1(), sprite.v0()).setLight(light);
            vertices.addVertex(pose, ulX, ulY, 0.0f).setColor(SHADE_LEFT).setUv(sprite.u0(), sprite.v0()).setLight(light);

            // Right face.
            vertices.addVertex(pose, botX, botY, 0.0f).setColor(SHADE_RIGHT).setUv(sprite.u0(), sprite.v1()).setLight(light);
            vertices.addVertex(pose, lrX, lrY, 0.0f).setColor(SHADE_RIGHT).setUv(sprite.u1(), sprite.v1()).setLight(light);
            vertices.addVertex(pose, urX, urY, 0.0f).setColor(SHADE_RIGHT).setUv(sprite.u1(), sprite.v0()).setLight(light);
            vertices.addVertex(pose, midX, midY, 0.0f).setColor(SHADE_RIGHT).setUv(sprite.u0(), sprite.v0()).setLight(light);

            return x + ICON_SIZE + ICON_GAP;
        }

        float left = x;
        float right = x + ICON_SIZE;
        float top = y - 1.0f;
        float bottom = y + ICON_SIZE - 1.0f;

        vertices.addVertex(pose, left, bottom, 0.0f).setColor(SHADE_TOP).setUv(sprite.u0(), sprite.v1()).setLight(light);
        vertices.addVertex(pose, right, bottom, 0.0f).setColor(SHADE_TOP).setUv(sprite.u1(), sprite.v1()).setLight(light);
        vertices.addVertex(pose, right, top, 0.0f).setColor(SHADE_TOP).setUv(sprite.u1(), sprite.v0()).setLight(light);
        vertices.addVertex(pose, left, top, 0.0f).setColor(SHADE_TOP).setUv(sprite.u0(), sprite.v0()).setLight(light);

        return x + ICON_SIZE + ICON_GAP;
    }
}
*///?}
