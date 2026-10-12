package me.mehmetyl.vtp.client.filter;

import me.mehmetyl.vtp.client.ItemNames;
import me.mehmetyl.vtp.client.data.VillagerSnapshot;
import me.mehmetyl.vtp.compat.OfferView;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates the player's rules against a villager.
 *
 * <p>Rules are compiled once per config generation. Matching itself is only ever
 * invoked on a cache miss -- see {@code MatchCache} -- so the per-rule string
 * comparisons here run when a villager's offers actually change, not per tick.
 */
public final class FilterEngine {
    private static final CompiledRule[] NONE = new CompiledRule[0];
    private static final Match[] NO_MATCHES = new Match[0];

    private CompiledRule[] compiled = NONE;
    private int compiledGeneration = -1;

    /**
     * One row of a villager's label: what the player hands over, and what they get.
     *
     * <p>Drawn as {@code <costCount> (costIcon) - <resultText> (resultIcon)}. When
     * {@code baseCostCount} differs from {@code costCount} the base is struck
     * through and the real price follows it, which is how a discounted or
     * demand-inflated trade shows as one row instead of looking like two.
     *
     * @param label        the rule's name, used when the rule names no trade at all
     * @param costCount    what the player actually pays, after adjustments
     * @param baseCostCount the unadjusted price; equal to {@code costCount} normally
     * @param costItemId   registry id of the item paid, for the leading icon
     * @param costItemBId  registry id of the second item paid, or {@code null}
     * @param costBCount   quantity of that second item, or 0
     * @param resultText   right-hand text, or {@code null} for a profession-only rule
     * @param resultItemId registry id of the item received, for the right icon
     */
    public record Match(String label,
                        int costCount,
                        int baseCostCount,
                        String costItemId,
                        String costItemBId,
                        int costBCount,
                        String resultText,
                        String resultItemId) {

        /** A rule that names only a profession, so there is no trade to describe. */
        static Match professionOnly(String label) {
            return new Match(label, 0, 0, null, null, 0, null, null);
        }

        /** True when the trade charges a second, different item alongside the first. */
        public boolean hasSecondCost() {
            return costItemBId != null && costBCount > 0;
        }

        public boolean priceAdjusted() {
            return baseCostCount != costCount;
        }
    }

    /** Recompiles if the player edited the filters since the last scan. */
    public void refresh(List<FilterRule> rules, int generation) {
        if (generation == compiledGeneration) {
            return;
        }
        compiledGeneration = generation;

        int enabled = 0;
        for (FilterRule rule : rules) {
            if (rule.enabled) {
                enabled++;
            }
        }

        CompiledRule[] next = enabled == 0 ? NONE : new CompiledRule[enabled];
        int i = 0;
        for (FilterRule rule : rules) {
            if (rule.enabled) {
                next[i++] = new CompiledRule(rule);
            }
        }
        compiled = next;
    }

    public boolean hasRules() {
        return compiled.length > 0;
    }

    /**
     * Every trade on this villager that any rule matches, or {@code null}.
     *
     * <p>Rules are evaluated in list order, so the player's ordering is priority:
     * the first rule to claim a trade owns it, and the resulting rows are drawn
     * with the top rule nearest the villager's head. A trade already claimed is
     * skipped, so two overlapping rules cannot produce the same row twice.
     */
    public Match[] match(VillagerSnapshot snapshot) {
        OfferView[] offers = snapshot.offers();
        boolean offersKnown = snapshot.offerState() != VillagerSnapshot.OfferState.UNKNOWN;
        boolean[] claimed = offers.length == 0 ? null : new boolean[offers.length];

        List<Match> found = null;

        for (CompiledRule rule : compiled) {
            if (!rule.professionMatches(snapshot.groupId())) {
                continue;
            }

            if (!rule.hasOfferCriteria) {
                // Profession-only rule: matches even when offers are unknown.
                found = add(found, Match.professionOnly(rule.label));
                continue;
            }

            if (!offersKnown || claimed == null) {
                continue;
            }

            for (int i = 0; i < offers.length; i++) {
                if (claimed[i] || !rule.offerMatches(offers[i])) {
                    continue;
                }
                claimed[i] = true;
                found = add(found, describe(rule.label, offers[i]));
            }
        }

        return found == null ? null : found.toArray(NO_MATCHES);
    }

    private static List<Match> add(List<Match> found, Match match) {
        if (found == null) {
            found = new ArrayList<>(2);
        }
        found.add(match);
        return found;
    }

    /** Turns one matched trade into the row the renderer will draw. */
    private static Match describe(String label, OfferView offer) {
        return new Match(label,
                offer.costACount(),
                offer.baseCostACount(),
                offer.costItemAId(),
                offer.costItemBId(),
                offer.costBCount(),
                resultTextOf(offer),
                offer.resultItemId());
    }

    /**
     * The right-hand side of a row.
     *
     * <p>An enchanted book is named by its enchantment, because that is the whole
     * reason anyone filters for one. A plain emerald payout is just a count -- its
     * icon already says what it is. Everything else gets its real item name, taken
     * from the item rather than assembled from the registry id, prefixed by the
     * stack size when the trade hands over more than one. A count of one stays
     * implicit: writing it would lengthen every row to say nothing.
     */
    private static String resultTextOf(OfferView offer) {
        if (offer.enchantmentId() != null) {
            Component enchantment = Component.translatable(
                    Identifier.parse(offer.enchantmentId()).toLanguageKey("enchantment"));

            if (offer.enchantLevel() > 1) {
                return Component.translatable("vtp.summary.enchantment_level",
                        enchantment,
                        Component.translatable("enchantment.level." + offer.enchantLevel()))
                        .getString();
            }
            return enchantment.getString();
        }

        if (ItemNames.isEmerald(offer.resultItemId())) {
            return Integer.toString(offer.resultCount());
        }

        String name = ItemNames.displayName(offer.resultItemId());
        return offer.resultCount() > 1 ? offer.resultCount() + " " + name : name;
    }
}
