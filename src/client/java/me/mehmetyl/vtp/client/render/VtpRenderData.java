package me.mehmetyl.vtp.client.render;

import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.util.FormattedCharSequence;

/**
 * What the renderer needs about one matched villager, attached to its render
 * state during extraction and read back during submission.
 *
 * <p>Every row is pre-split into segments, pre-measured, and has its icon
 * sprites pre-resolved, so the submit path does no formatting, no font
 * measurement and no model lookup -- it only positions and submits.
 *
 * @param rows       one row per matching trade, {@code rows[0]} nearest the head
 * @param argb       colour for this villager state
 * @param stackIndex lines already occupied by other villagers on the same block
 */
public record VtpRenderData(Row[] rows, int argb, int stackIndex) {

    /**
     * An item icon, drawn from a patch of an atlas.
     *
     * <p>Never the item's real model. A model is 3D geometry that depth-tests
     * against the world, so icons vanished behind the very blocks the see-through
     * label text was drawn over. Everything here goes through the same
     * see-through render type as the text, so icons and text behave alike.
     *
     * <p>{@code cube} is how a block still reads as a block. A single flat quad
     * of a block's texture is just a coloured square -- gravel became an
     * indistinguishable grey patch. With it set, the same sprite is drawn as the
     * three visible faces of an isometric cube, shaded like Minecraft shades its
     * own block faces, which is the silhouette players already read as "block".
     *
     * @param atlas the atlas the sprite lives on, bound as the quad's texture
     */
    public record Icon(Identifier atlas, float u0, float u1, float v0, float v1, boolean cube) {
    }

    /**
     * One piece of a row: exactly one of {@code icon} or {@code text} is set.
     *
     * <p>Rows became a sequence rather than {@code icon-text-icon} so a trade
     * charging two different items can show both, each with its own icon --
     * a fletcher's {@code gravel + emerald -> flint} needs three icons, not two.
     */
    public record Segment(Icon icon, FormattedCharSequence text, int textWidth) {

        public static Segment of(Icon icon) {
            return new Segment(icon, null, 0);
        }

        public static Segment of(FormattedCharSequence text, int textWidth) {
            return new Segment(null, text, textWidth);
        }
    }

    /**
     * One line of a label, as laid-out segments.
     *
     * @param width total advance of the whole row, used to centre it
     */
    public record Row(Segment[] segments, float width) {
    }

    /**
     * Carries this record from extraction to drawing.
     *
     * <p>Fabric clears attached data when a render state is recycled from 1.21.9
     * on, but not before, so {@code VtpLabelRenderer.extract} clears it by hand
     * for every entity rather than depending on that.
     */
    public static final RenderStateDataKey<VtpRenderData> KEY = RenderStateDataKey.create();
}
