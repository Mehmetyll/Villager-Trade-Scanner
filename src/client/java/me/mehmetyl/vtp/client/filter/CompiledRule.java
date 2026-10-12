package me.mehmetyl.vtp.client.filter;

import me.mehmetyl.vtp.compat.OfferView;

/**
 * A {@link FilterRule} reduced to the form the scan actually evaluates.
 *
 * <p>Compiled once per config generation so the hot path never parses strings,
 * reads {@code null}-able boxed fields, or walks the rule's list allocations.
 * Unconstrained criteria become {@code null} / {@code -1} and are skipped with a
 * single branch.
 */
public final class CompiledRule {
    final String label;
    final String professionId;   // null = any profession
    final String resultItemId;   // null = any result
    final String enchantmentId;  // null = not an enchantment rule
    final int minEnchantmentLevel;
    final int maxPrice;          // -1 = unconstrained
    final String[] inputItemIds; // null = unconstrained
    final boolean onlyInStock;

    /**
     * True when the rule constrains anything about the offers themselves. A rule
     * that only names a profession can still match a villager whose offers we
     * cannot see, which is what keeps profession filtering useful on a vanilla
     * server.
     */
    final boolean hasOfferCriteria;

    CompiledRule(FilterRule rule) {
        this.label = rule.label == null || rule.label.isBlank()
                ? rule.profession
                : rule.label;
        this.professionId = isAny(rule.profession) ? null : rule.profession;
        this.resultItemId = isAny(rule.resultItem) ? null : rule.resultItem;
        this.enchantmentId = rule.enchantment;
        this.minEnchantmentLevel = rule.minEnchantmentLevel;
        this.maxPrice = rule.maxEmeraldPrice;
        this.onlyInStock = rule.onlyInStock;

        this.inputItemIds = rule.inputItems == null || rule.inputItems.isEmpty()
                ? null
                : rule.inputItems.toArray(new String[0]);

        this.hasOfferCriteria = resultItemId != null
                || enchantmentId != null
                || maxPrice != FilterRule.UNSET
                || inputItemIds != null
                || onlyInStock;
    }

    private static boolean isAny(String value) {
        return value == null || value.isBlank() || FilterRule.ANY.equals(value);
    }

    boolean professionMatches(String groupId) {
        return professionId == null || professionId.equals(groupId);
    }

    /** Evaluates the offer-level criteria against a single trade. */
    boolean offerMatches(OfferView offer) {
        if (resultItemId != null && !resultItemId.equals(offer.resultItemId())) {
            return false;
        }

        if (enchantmentId != null) {
            if (!enchantmentId.equals(offer.enchantmentId())) {
                return false;
            }
            // ANY_LEVEL accepts every level, so the comparison is skipped rather
            // than run against zero.
            if (minEnchantmentLevel > FilterRule.ANY_LEVEL
                    && offer.enchantLevel() < minEnchantmentLevel) {
                return false;
            }
        }

        // Compares against what the player actually hands over, which is not
        // always emeralds -- a cleric buying 32 rotten flesh has a price of 32.
        if (maxPrice != FilterRule.UNSET && offer.priceAmount() > maxPrice) {
            return false;
        }

        if (onlyInStock && offer.outOfStock()) {
            return false;
        }

        if (inputItemIds != null) {
            for (String required : inputItemIds) {
                if (!required.equals(offer.costItemAId()) && !required.equals(offer.costItemBId())) {
                    return false;
                }
            }
        }

        return true;
    }
}
