package me.mehmetyl.vtp.client.filter;

import java.util.ArrayList;
import java.util.List;

/**
 * One user-authored filter rule.
 *
 * <p>This is a plain mutable data class on purpose: it is what Gson reads and
 * writes, and what the Stage 6 editor screen mutates in place. The matching
 * logic deliberately does <em>not</em> live here -- it belongs to the
 * {@code FilterEngine} added in Stage 4, which compiles these rules into a form
 * that can be evaluated without allocating or doing string lookups.
 *
 * <p>Every field has an initialiser. Gson uses the no-arg constructor and only
 * overwrites keys actually present in the JSON, so a config written by an older
 * build silently picks up defaults for anything added later.
 */
public final class FilterRule {
    /** Sentinel meaning "do not constrain this field". */
    public static final String ANY = "any";

    /** Sentinel for numeric fields meaning "do not constrain". */
    public static final int UNSET = -1;

    /** {@link #minEnchantmentLevel} value meaning "any level of this enchantment". */
    public static final int ANY_LEVEL = 0;

    public boolean enabled = true;

    /** Label drawn above a matching villager's head, e.g. {@code MENDING CHEAP}. */
    public String label = "";

    /**
     * Profession registry id (e.g. {@code minecraft:librarian}), {@link #ANY},
     * or {@code minecraft:wandering_trader} for the wandering-trader group.
     * Stored as a string so an unknown/modded profession round-trips through the
     * config untouched instead of being dropped on load.
     */
    public String profession = ANY;

    /** Item registry id of the offer's result, or {@link #ANY}. */
    public String resultItem = ANY;

    /** Enchantment registry id for enchanted-book rules; {@code null} if unused. */
    public String enchantment = null;

    /** Minimum enchantment level, or {@link #ANY_LEVEL}. Any is the default. */
    public int minEnchantmentLevel = ANY_LEVEL;

    /** Maximum emerald cost, or {@link #UNSET}. */
    public int maxEmeraldPrice = UNSET;

    /** Item registry ids required as inputs; empty means unconstrained. */
    public List<String> inputItems = new ArrayList<>();

    /** Hide the villager when the matching trade is exhausted until restock. */
    public boolean onlyInStock = false;

    /**
     * Schema 2's minimum-stock number, kept only so an older config can be read.
     *
     * <p>Boxed so "absent" is distinguishable from "zero". {@link #normalize()}
     * folds it into {@link #onlyInStock} and clears it; Gson omits nulls, so it
     * disappears from the file on the next save.
     */
    public Integer minStock = null;

    public FilterRule() {
    }

    /** Deep copy, used for edit-in-a-draft so nothing applies until saved. */
    public FilterRule copy() {
        FilterRule copy = new FilterRule();
        copy.copyFrom(this);
        return copy;
    }

    /** Overwrites this rule in place, keeping the identity the config list holds. */
    public void copyFrom(FilterRule other) {
        this.enabled = other.enabled;
        this.label = other.label;
        this.profession = other.profession;
        this.resultItem = other.resultItem;
        this.enchantment = other.enchantment;
        this.minEnchantmentLevel = other.minEnchantmentLevel;
        this.maxEmeraldPrice = other.maxEmeraldPrice;
        this.inputItems = new ArrayList<>(other.inputItems);
        this.onlyInStock = other.onlyInStock;
    }

    /** Clamps values that a hand-edited config could have put out of range. */
    public void normalize() {
        if (label == null) {
            label = "";
        }
        if (profession == null || profession.isBlank()) {
            profession = ANY;
        }
        if (resultItem == null || resultItem.isBlank()) {
            resultItem = ANY;
        }
        if (enchantment != null && enchantment.isBlank()) {
            enchantment = null;
        }
        if (inputItems == null) {
            inputItems = new ArrayList<>();
        } else {
            inputItems.removeIf(s -> s == null || s.isBlank());
            // An offer has at most two cost slots and every listed input has to
            // occupy one, so a longer list could never match anything at all.
            while (inputItems.size() > 2) {
                inputItems.remove(inputItems.size() - 1);
            }
        }
        // ANY_LEVEL is the floor, not 1: "any level" is a real choice here.
        minEnchantmentLevel = clamp(minEnchantmentLevel, ANY_LEVEL, 255);
        if (maxEmeraldPrice != UNSET) {
            maxEmeraldPrice = clamp(maxEmeraldPrice, 1, 64);
        }

        // Schema 2 -> 3: any positive minimum meant "must have stock left".
        if (minStock != null) {
            if (minStock >= 1) {
                onlyInStock = true;
            }
            minStock = null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}
