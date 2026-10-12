package me.mehmetyl.vtp.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import me.mehmetyl.vtp.VtpMod;
import me.mehmetyl.vtp.client.VtpClientMod;
import me.mehmetyl.vtp.client.config.VtpConfig;
import me.mehmetyl.vtp.client.data.HighlightEntry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.rendertype.RenderTypes;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

//? if >=1.21.9 {
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.VertexConsumer;
*///?}
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;

/**
 * Draws the glow colour and the floating label for matched villagers.
 *
 * <p>Called from {@code EntityRendererMixin}. Everything drawn here was decided
 * during the scan: this code positions and submits, it never formats text,
 * measures the font, or evaluates a filter.
 */
public final class VtpLabelRenderer {
    /** Vanilla name-tag world scale; our label scale multiplies this. */
    private static final float BASE_SCALE = 0.025f;

    /** Icon edge length in label text units (font height is 8). */
    private static final float ICON_SIZE = 9.0f;

    /** Gap between an icon and the text, in the same units. */
    private static final float ICON_GAP = 2.0f;

    /** Vertical gap between label lines, in font units. */
    private static final int LINE_HEIGHT = 10;

    /** Gap after a run of text, in the same units. */
    private static final float TEXT_GAP = 2.0f;

    /** Height above the villager where the label sits. */
    private static final double LABEL_OFFSET_Y = 0.6;

    /** Face shading, matching how Minecraft shades its own block faces. */
    private static final int SHADE_TOP = 0xFFFFFFFF;
    private static final int SHADE_LEFT = 0xFFCCCCCC;
    private static final int SHADE_RIGHT = 0xFF999999;

    private static boolean errorLogged;

    private VtpLabelRenderer() {
    }

    /**
     * Attaches this villager highlight to its render state, and overrides the
     * outline colour vanilla just assigned.
     */
    public static void extract(Entity entity, EntityRenderState state) {
        try {
            // Detach first, for every entity, before any early return.
            //
            // Render states are pooled and handed back out for whatever entity
            // comes next. Fabric clears attached data on recycle from 1.21.9,
            // but not on 1.21.6-1.21.8, where one matched villager's label rode
            // the recycled state onto everything else on screen and never left.
            // Clearing here rather than relying on the loader means the answer
            // does not depend on which Fabric version is installed.
            state.setData(VtpRenderData.KEY, null);

            if (!(entity instanceof AbstractVillager)) {
                return;
            }

            VtpConfig config = VtpClientMod.config();
            if (!config.previewEnabled) {
                return;
            }

            HighlightEntry entry = VtpClientMod.scanner().lookup(entity.getId());
            if (entry == null || entry.occluded) {
                // Nothing is drawn for a villager behind terrain. The outline pass
                // renders through walls, so highlighting an occluded villager
                // would turn the mod into an X-ray for finding hidden bases.
                return;
            }

            // Glow. Writing the render state only affects this client's frame --
            // the entity itself is never marked glowing, so nothing desyncs.
            // This is deliberately independent of the label: every match glows,
            // while only the nearest few carry text, so render data is built for
            // the labelled subset alone.
            // Up to 1.21.8 the render state carries no outline colour; the glow
            // is driven by EntityGlowMixin off the same scan result instead.
            //? if >=1.21.9 {
            if (config.appearance.glowEnabled) {
                state.outlineColor = entry.argb;
            }
            //?}

            if (config.appearance.labelEnabled && entry.labelVisible && entry.renderData != null) {
                state.setData(VtpRenderData.KEY, entry.renderData);
            }
        } catch (Exception e) {
            logOnce(e);
        }
    }

    //? if >=1.21.9 {
    // Submits the label text above the villager.
    public static void submit(EntityRenderState state, VtpRenderData data, PoseStack poseStack,
                              SubmitNodeCollector collector, CameraRenderState camera) {
        try {
            VtpConfig config = VtpClientMod.config();

            double maxDistance = config.appearance.maxRenderDistance;
            if (state.distanceToCameraSq > maxDistance * maxDistance) {
                return;
            }

            // Fixed world scale, exactly like a vanilla name tag. Scaling with
            // distance made every label resize continuously as the player moved,
            // which reads as jitter -- far worse than simply being smaller.
            float scale = BASE_SCALE * config.appearance.labelScale;

            double offsetY = state.boundingBoxHeight + LABEL_OFFSET_Y;

            // Full bright so a label stays readable at night, and SEE_THROUGH so
            // the block directly above a penned-in villager cannot clip the text.
            // This is not an X-ray: nothing reaches here unless the occlusion test
            // during the scan already decided this villager is visible, so the gate
            // on drawing at all is unchanged -- only the clipping of a label whose
            // villager the player can already see.
            int light = LightCoordsUtil.FULL_BRIGHT;
            Font.DisplayMode displayMode = Font.DisplayMode.SEE_THROUGH;

            int background = config.appearance.labelBackground ? 0x40000000 : 0;

            poseStack.pushPose();
            poseStack.translate(0.0, offsetY, 0.0);
            // Billboard: cancel the world rotation by adopting the camera's.
            poseStack.mulPose(camera.orientation);
            // Y is negated because text coordinates grow downward.
            poseStack.scale(scale, -scale, scale);

            // Lift this label clear of any others on the same block. Negative Y is
            // upward on screen here, because of the flip just applied.
            if (data.stackIndex() > 0) {
                poseStack.translate(0.0f, -(float) (data.stackIndex() * LINE_HEIGHT), 0.0f);
            }

            // Rows climb away from the head, rows[0] lowest. stackIndex is how
            // many lines other villagers on this same block already took, so two
            // penned-in villagers never write over each other.
            VtpRenderData.Row[] rows = data.rows();

            for (int i = 0; i < rows.length; i++) {
                VtpRenderData.Row row = rows[i];
                float lineY = -(float) ((data.stackIndex() + i) * LINE_HEIGHT);
                float start = -row.width() / 2.0f;

                // One backdrop for the whole row, drawn here rather than passed to
                // submitText. A row is several text runs with icons between them,
                // and per-run backgrounds would show a seam at every gap instead of
                // one continuous bar.
                if (background != 0) {
                    submitBackground(poseStack, collector, start, lineY, row.width(), background);
                }

                float cursor = start;
                for (VtpRenderData.Segment segment : row.segments()) {
                    if (segment.icon() != null) {
                        cursor = submitIcon(segment.icon(), poseStack, collector, cursor, lineY, light);
                    } else {
                        collector.submitText(poseStack, cursor, lineY, segment.text(), false,
                                displayMode, light, data.argb(), 0, 0);
                        cursor += segment.textWidth() + TEXT_GAP;
                    }
                }
            }

            poseStack.popPose();
        } catch (Exception e) {
            logOnce(e);
        }
    }

    // The row's backdrop, one quad spanning every segment.
    private static void submitBackground(PoseStack poseStack, SubmitNodeCollector collector,
                                         float start, float y, float width, int argb) {
        float left = start - 1.0f;
        float right = start + width + 1.0f;
        float top = y - 1.0f;
        float bottom = y + 9.0f;

        collector.submitCustomGeometry(poseStack, RenderTypes.textBackgroundSeeThrough(),
                (pose, vertices) -> {
                    vertices.addVertex(pose, left, bottom, 0.0f).setColor(argb).setLight(LightCoordsUtil.FULL_BRIGHT);
                    vertices.addVertex(pose, right, bottom, 0.0f).setColor(argb).setLight(LightCoordsUtil.FULL_BRIGHT);
                    vertices.addVertex(pose, right, top, 0.0f).setColor(argb).setLight(LightCoordsUtil.FULL_BRIGHT);
                    vertices.addVertex(pose, left, top, 0.0f).setColor(argb).setLight(LightCoordsUtil.FULL_BRIGHT);
                });
    }

     // Draws one icon, returning the advanced cursor.
     //
     // <p>Submitted through {@code textSeeThrough} -- the same render type the
     // label's text uses -- so an icon ignores depth exactly as the text does.
     // Drawing the item's real model instead left icons depth-testing against
     // nearby terrain, so a villager in a trench kept its text and lost its icons.
    private static float submitIcon(VtpRenderData.Icon icon, PoseStack poseStack,
                                    SubmitNodeCollector collector, float x, float y, int light) {
        if (icon == null) {
            return x;
        }

        if (icon.cube()) {
            submitCube(icon, poseStack, collector, x, y, light);
            return x + ICON_SIZE + ICON_GAP;
        }

        // Text space grows downward, so "top" is the smaller Y.
        float left = x;
        float right = x + ICON_SIZE;
        float top = y - 1.0f;
        float bottom = y + ICON_SIZE - 1.0f;

        collector.submitCustomGeometry(poseStack, RenderTypes.textSeeThrough(icon.atlas()),
                (pose, vertices) -> {
                    vertices.addVertex(pose, left, bottom, 0.0f)
                            .setColor(SHADE_TOP).setUv(icon.u0(), icon.v1()).setLight(light);
                    vertices.addVertex(pose, right, bottom, 0.0f)
                            .setColor(SHADE_TOP).setUv(icon.u1(), icon.v1()).setLight(light);
                    vertices.addVertex(pose, right, top, 0.0f)
                            .setColor(SHADE_TOP).setUv(icon.u1(), icon.v0()).setLight(light);
                    vertices.addVertex(pose, left, top, 0.0f)
                            .setColor(SHADE_TOP).setUv(icon.u0(), icon.v0()).setLight(light);
                });

        return x + ICON_SIZE + ICON_GAP;
    }

     // Draws a block as the three visible faces of an isometric cube.
     //
     // <p>A block drawn as one flat quad is just a coloured square: gravel was an
     // anonymous grey patch with nothing to tell it from stone. The real item
     // model would solve that but depth-tests against the world, which is exactly
     // why icons are quads here. Three sheared quads give the silhouette players
     // read as "block" while still going through the see-through render type.
     //
     // <p>The same sprite is used on all three faces. Blocks that trade hands in a
     // villager's offers are overwhelmingly uniform ones -- gravel, glass, stone --
     // and a block with a distinct top still reads correctly as a block, which is
     // the point of the shape.
    private static void submitCube(VtpRenderData.Icon icon, PoseStack poseStack,
                                   SubmitNodeCollector collector, float x, float y, int light) {
        float half = ICON_SIZE / 2.0f;
        float quarter = ICON_SIZE / 4.0f;
        float cx = x + half;
        float cy = y - 1.0f + half;

        // The six points of the cube's hexagonal silhouette, plus the centre
        // where the three visible faces meet.
        float topX = cx,            topY = cy - half;
        float ulX = cx - half,      ulY = cy - quarter;
        float urX = cx + half,      urY = cy - quarter;
        float midX = cx,            midY = cy;
        float llX = cx - half,      llY = cy + quarter;
        float lrX = cx + half,      lrY = cy + quarter;
        float botX = cx,            botY = cy + half;

        collector.submitCustomGeometry(poseStack, RenderTypes.textSeeThrough(icon.atlas()),
                (pose, vertices) -> {
                    // Top face.
                    vertices.addVertex(pose, ulX, ulY, 0.0f)
                            .setColor(SHADE_TOP).setUv(icon.u0(), icon.v1()).setLight(light);
                    vertices.addVertex(pose, midX, midY, 0.0f)
                            .setColor(SHADE_TOP).setUv(icon.u1(), icon.v1()).setLight(light);
                    vertices.addVertex(pose, urX, urY, 0.0f)
                            .setColor(SHADE_TOP).setUv(icon.u1(), icon.v0()).setLight(light);
                    vertices.addVertex(pose, topX, topY, 0.0f)
                            .setColor(SHADE_TOP).setUv(icon.u0(), icon.v0()).setLight(light);

                    // Left face.
                    vertices.addVertex(pose, llX, llY, 0.0f)
                            .setColor(SHADE_LEFT).setUv(icon.u0(), icon.v1()).setLight(light);
                    vertices.addVertex(pose, botX, botY, 0.0f)
                            .setColor(SHADE_LEFT).setUv(icon.u1(), icon.v1()).setLight(light);
                    vertices.addVertex(pose, midX, midY, 0.0f)
                            .setColor(SHADE_LEFT).setUv(icon.u1(), icon.v0()).setLight(light);
                    vertices.addVertex(pose, ulX, ulY, 0.0f)
                            .setColor(SHADE_LEFT).setUv(icon.u0(), icon.v0()).setLight(light);

                    // Right face.
                    vertices.addVertex(pose, botX, botY, 0.0f)
                            .setColor(SHADE_RIGHT).setUv(icon.u0(), icon.v1()).setLight(light);
                    vertices.addVertex(pose, lrX, lrY, 0.0f)
                            .setColor(SHADE_RIGHT).setUv(icon.u1(), icon.v1()).setLight(light);
                    vertices.addVertex(pose, urX, urY, 0.0f)
                            .setColor(SHADE_RIGHT).setUv(icon.u1(), icon.v0()).setLight(light);
                    vertices.addVertex(pose, midX, midY, 0.0f)
                            .setColor(SHADE_RIGHT).setUv(icon.u0(), icon.v0()).setLight(light);
                });
    }
    //?} else {
    /*// ---- 1.21.6 - 1.21.8 --------------------------------------------------
    //
    // Same geometry and the same render types as the submit path above; only the
    // way vertices and text reach the pipeline differs. MultiBufferSource hands
    // out a VertexConsumer per render type, and the font draws straight into it.

    public static void render(EntityRenderState state, VtpRenderData data, PoseStack poseStack,
                              MultiBufferSource buffers, int packedLight) {
        try {
            VtpConfig config = VtpClientMod.config();

            // Re-read rather than trusting what extract attached: this makes the
            // label option take effect on the next frame instead of the next scan.
            if (!config.appearance.labelEnabled) {
                return;
            }

            double maxDistance = config.appearance.maxRenderDistance;
            if (state.distanceToCameraSq > maxDistance * maxDistance) {
                return;
            }

            Minecraft minecraft = Minecraft.getInstance();
            float scale = BASE_SCALE * config.appearance.labelScale;
            double offsetY = state.boundingBoxHeight + LABEL_OFFSET_Y;

            int light = LightCoordsUtil.FULL_BRIGHT;
            Font.DisplayMode displayMode = Font.DisplayMode.SEE_THROUGH;
            int background = config.appearance.labelBackground ? 0x40000000 : 0;

            poseStack.pushPose();
            poseStack.translate(0.0, offsetY, 0.0);
            // Billboard. The render state carries no camera here, so the
            // orientation comes from the dispatcher that is drawing this entity.
            poseStack.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
            poseStack.scale(scale, -scale, scale);

            if (data.stackIndex() > 0) {
                poseStack.translate(0.0f, -(float) (data.stackIndex() * LINE_HEIGHT), 0.0f);
            }

            VtpRenderData.Row[] rows = data.rows();

            for (int i = 0; i < rows.length; i++) {
                VtpRenderData.Row row = rows[i];
                float lineY = -(float) ((data.stackIndex() + i) * LINE_HEIGHT);
                float start = -row.width() / 2.0f;

                if (background != 0) {
                    VtpBufferDraw.background(poseStack, buffers, start, lineY, row.width(), background, light);
                }

                float cursor = start;
                for (VtpRenderData.Segment segment : row.segments()) {
                    if (segment.icon() != null) {
                        cursor = VtpBufferDraw.icon(segment.icon(), poseStack, buffers, cursor, lineY, light);
                    } else {
                        minecraft.font.drawInBatch(segment.text(), cursor, lineY, data.argb(), false,
                                poseStack.last().pose(), buffers, displayMode, 0, light);
                        cursor += segment.textWidth() + TEXT_GAP;
                    }
                }
            }

            poseStack.popPose();
        } catch (Exception e) {
            logOnce(e);
        }
    }
    *///?}

    private static void logOnce(Exception e) {
        if (!errorLogged) {
            errorLogged = true;
            VtpMod.LOGGER.error("Villager highlight rendering failed; further errors suppressed", e);
        }
    }
}
