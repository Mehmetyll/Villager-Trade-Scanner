package me.mehmetyl.vtp.client.data;

import me.mehmetyl.vtp.client.ItemNames;
import me.mehmetyl.vtp.client.filter.FilterEngine;
import me.mehmetyl.vtp.client.render.VtpRenderData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.core.BlockPos;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One villager the renderer should draw, fully precomputed.
 *
 * <p>Deliberately a mutable POJO owned by a reuse pool: the render path reads
 * {@link #renderData} and does nothing else -- no formatting, no font
 * measurement, no model lookup, no allocation. All of that happens here, during
 * the scan, and only when something actually changed.
 */
public final class HighlightEntry {
    /** Icon edge length in label text units; must match the renderer's. */
    private static final float ICON_SIZE = 9.0f;
    private static final float ICON_GAP = 2.0f;
    private static final float TEXT_GAP = 2.0f;

    private static final VtpRenderData.Row[] NO_ROWS = new VtpRenderData.Row[0];

    /**
     * Bumped when Minecraft invalidates render state, so cached icon models are
     * rebuilt after a resource reload instead of drawing stale quads.
     */
    private static int renderEpoch;

    public static void invalidateIcons() {
        renderEpoch++;
    }

    /** Resolved against the client level to find the entity. */
    public int entityId;

    /** Colour for this villager state, already resolved from the config. */
    public int argb;

    /** Every rule match on this villager; index 0 is drawn nearest the head. */
    public FilterEngine.Match[] matches;

    /** Squared distance to the player; used for sorting and culling. */
    public double distanceSq;

    /** World position, used to stack labels of villagers standing together. */
    public double x;
    public double y;
    public double z;

    /** Lines already claimed at this spot by other villagers; offsets this one up. */
    public int stackIndex;

    /**
     * False when this villager is past the label cap. It still glows -- only the
     * text is dropped, so a dense trading hall stays readable.
     */
    public boolean labelVisible;

    /**
     * True when terrain stands between the player and this villager.
     *
     * <p>Occluded villagers are not drawn at all. The outline pass renders
     * through walls by design, so without this check the mod would let players
     * find villagers, and therefore bases, through solid blocks.
     */
    public boolean occluded;

    /** The immutable payload handed to the render state. Rebuilt only on change. */
    public VtpRenderData renderData;

    // Previous inputs, so an unchanged entry reuses its VtpRenderData instead of
    // re-measuring text and re-resolving models every scan.
    private FilterEngine.Match[] builtMatches;
    private int builtArgb;
    private int builtStackIndex;
    private int builtEpoch = -1;

    public void set(int entityId, int argb, FilterEngine.Match[] matches,
                    double distanceSq, double x, double y, double z, int stackIndex) {
        this.entityId = entityId;
        this.argb = argb;
        this.matches = matches;
        this.distanceSq = distanceSq;
        this.x = x;
        this.y = y;
        this.z = z;
        this.stackIndex = stackIndex;
        this.labelVisible = false;
    }

    /** How many lines this villager's label occupies. */
    public int rowCount() {
        return matches == null ? 0 : matches.length;
    }

    /**
     * Rebuilds {@link #renderData} if any of its inputs changed. Called once per
     * scan per matched villager, never per frame.
     */
    public void refreshRenderData(Font font) {
        if (renderData != null
                && stackIndex == builtStackIndex
                && argb == builtArgb
                && builtEpoch == renderEpoch
                && Arrays.equals(matches, builtMatches)) {
            return;
        }

        VtpRenderData.Row[] rows = matches == null || matches.length == 0
                ? NO_ROWS
                : new VtpRenderData.Row[matches.length];

        for (int i = 0; i < rows.length; i++) {
            rows[i] = buildRow(font, matches[i]);
        }

        renderData = new VtpRenderData(rows, argb, stackIndex);

        builtMatches = matches;
        builtArgb = argb;
        builtStackIndex = stackIndex;
        builtEpoch = renderEpoch;
    }

    /**
     * Builds one row as a sequence of segments.
     *
     * <p>A trade charging two different items shows both, each with its own
     * icon, so a fletcher's offer reads {@code (gravel) 10 + (emerald) 5 - 10
     * Flint (flint)} instead of naming only half of what it costs.
     *
     * <p>A rule that names only a profession has no trade to describe, so it
     * falls back to the rule's own name and draws nothing else.
     */
    private static VtpRenderData.Row buildRow(Font font, FilterEngine.Match match) {
        List<VtpRenderData.Segment> segments = new ArrayList<>(5);

        if (match.resultText() == null) {
            addText(segments, font, Component.literal(match.label() == null ? "" : match.label()));
            return finish(segments);
        }

        addIcon(segments, resolveIcon(match.costItemId()));

        // A discounted or demand-inflated trade shows the price it started at,
        // struck through, immediately before the price actually charged.
        MutableComponent price = Component.empty();
        if (match.priceAdjusted()) {
            price.append(Component.literal(Integer.toString(match.baseCostCount()))
                    .withStyle(Style.EMPTY.withStrikethrough(true)));
            price.append(" " + match.costCount());
        } else {
            price.append(Integer.toString(match.costCount()));
        }

        if (match.hasSecondCost()) {
            // The plus trails the first amount so the second icon lands between
            // the two numbers and the row reads as one sum.
            price.append(" +");
            addText(segments, font, price);
            addIcon(segments, resolveIcon(match.costItemBId()));
            addText(segments, font,
                    Component.literal(match.costBCount() + " - " + match.resultText()));
        } else {
            addText(segments, font, price.append(" - " + match.resultText()));
        }

        addIcon(segments, resolveIcon(match.resultItemId()));
        return finish(segments);
    }

    private static void addIcon(List<VtpRenderData.Segment> segments, VtpRenderData.Icon icon) {
        if (icon != null) {
            segments.add(VtpRenderData.Segment.of(icon));
        }
    }

    private static void addText(List<VtpRenderData.Segment> segments, Font font, Component text) {
        FormattedCharSequence visual = text.getVisualOrderText();
        segments.add(VtpRenderData.Segment.of(visual, font.width(visual)));
    }

    /** Sums the segment advances, dropping the trailing gap of the last one. */
    private static VtpRenderData.Row finish(List<VtpRenderData.Segment> segments) {
        float width = 0.0f;
        for (VtpRenderData.Segment segment : segments) {
            width += segment.icon() != null ? ICON_SIZE + ICON_GAP : segment.textWidth() + TEXT_GAP;
        }
        if (!segments.isEmpty()) {
            VtpRenderData.Segment last = segments.get(segments.size() - 1);
            width -= last.icon() != null ? ICON_GAP : TEXT_GAP;
        }
        return new VtpRenderData.Row(segments.toArray(new VtpRenderData.Segment[0]), width);
    }

    /**
     * Resolves an item id to an icon.
     *
     * <p>The particle material is the item's own texture for a normal item and
     * the block's face for a block item, which is what an icon this small can
     * show. Building the state first is still needed to reach it.
     */
    private static VtpRenderData.Icon resolveIcon(String itemId) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return null;
            }

            Item item = ItemNames.item(itemId);
            if (item == null) {
                return null;
            }

            // An ItemStackRenderState is built and asked for its particle
            // sprite. 26 wraps it in a Material; before that the sprite comes
            // straight back.
            //? if >=26.1 {
            ItemStackRenderState state = new ItemStackRenderState();
            minecraft.getItemModelResolver().updateForTopItem(
                    state, new ItemStack(item), ItemDisplayContext.GUI, minecraft.level, null, 0);
            if (state.isEmpty()) {
                return null;
            }
            var material = state.pickParticleMaterial(RandomSource.create(0L));
            if (material == null) {
                return null;
            }
            TextureAtlasSprite sprite = material.sprite();
            //?} else {
            /*ItemStackRenderState state = new ItemStackRenderState();
            minecraft.getItemModelResolver().updateForTopItem(
                    state, new ItemStack(item), ItemDisplayContext.GUI, minecraft.level, null, 0);
            if (state.isEmpty()) {
                return null;
            }
            TextureAtlasSprite sprite = state.pickParticleIcon(RandomSource.create(0L));
            *///?}

            if (sprite == null) {
                return null;
            }

            return new VtpRenderData.Icon(sprite.atlasLocation(),
                    sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(),
                    isFullCube(item));
        } catch (Exception e) {
            // An icon is cosmetic; never let one break the scan.
            return null;
        }
    }

    /**
     * Whether this item should be drawn as a cube rather than a flat sprite.
     *
     * <p>Full-cube collision is the test rather than "is it a block item":
     * saplings, torches and slabs are block items too, and drawing any of them
     * as a cube would look worse than the flat sprite it replaced.
     */
    private static boolean isFullCube(Item item) {
        if (!(item instanceof BlockItem blockItem)) {
            return false;
        }
        try {
            return blockItem.getBlock().defaultBlockState()
                    .isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (Exception e) {
            return false;
        }
    }
}
