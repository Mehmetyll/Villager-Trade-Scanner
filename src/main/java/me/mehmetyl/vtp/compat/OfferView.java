package me.mehmetyl.vtp.compat;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * An immutable, flattened read model of a single trade.
 *
 * <p>Built once when offers arrive and never again, so the scan and render paths
 * can compare plain ints and interned strings instead of walking item stacks and
 * data components every tick.
 *
 * @param resultItemId  registry id of the result item
 * @param resultCount   result stack size
 * @param enchantmentId registry id of the stored enchantment, or {@code null}
 * @param enchantLevel  level of that enchantment, or 0
 * @param costItemAId   registry id of the first input
 * @param costACount    quantity of the first input, after price adjustments
 * @param baseCostACount quantity before demand and special-price adjustments
 * @param costItemBId   registry id of the second input, or {@code null}
 * @param costBCount    quantity of the second input, or 0
 * @param emeraldPrice  emerald cost if either input is emeralds, else -1
 * @param uses          trades already made
 * @param maxUses       trades allowed before restock
 */
public record OfferView(
        String resultItemId,
        int resultCount,
        String enchantmentId,
        int enchantLevel,
        String costItemAId,
        int costACount,
        int baseCostACount,
        String costItemBId,
        int costBCount,
        int emeraldPrice,
        int uses,
        int maxUses) {

    private static final OfferView[] EMPTY = new OfferView[0];

    public static OfferView[] of(MerchantOffers offers) {
        if (offers == null || offers.isEmpty()) {
            return EMPTY;
        }
        OfferView[] views = new OfferView[offers.size()];
        for (int i = 0; i < offers.size(); i++) {
            views[i] = of(offers.get(i));
        }
        return views;
    }

    public static OfferView of(MerchantOffer offer) {
        ItemStack result = offer.getResult();

        // getCostA() is the *adjusted* price -- it already includes the demand
        // and special-price modifiers, which is what the player actually pays and
        // therefore what a "<= 20 emeralds" filter has to compare against.
        ItemStack costA = offer.getCostA();
        ItemStack costB = offer.getCostB();

        String enchantmentId = null;
        int enchantLevel = 0;

        // Enchanted books carry STORED_ENCHANTMENTS, not ENCHANTMENTS. Librarian
        // book trades are the headline use case, so this is worth flattening.
        ItemEnchantments stored = result.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored != null && !stored.isEmpty()) {
            for (Holder<Enchantment> holder : stored.keySet()) {
                int level = stored.getLevel(holder);
                // Books from librarians only ever carry one enchantment, but if a
                // datapack produces several, keep the highest-level one so the
                // "minimum level" filter behaves sensibly.
                if (level > enchantLevel) {
                    enchantLevel = level;
                    enchantmentId = holder.getRegisteredName();
                }
            }
        }

        int emeraldPrice = -1;
        if (costA.getItem() == Items.EMERALD) {
            emeraldPrice = costA.getCount();
        } else if (!costB.isEmpty() && costB.getItem() == Items.EMERALD) {
            emeraldPrice = costB.getCount();
        }

        return new OfferView(
                itemId(result),
                result.getCount(),
                enchantmentId,
                enchantLevel,
                itemId(costA),
                costA.getCount(),
                offer.getBaseCostA().getCount(),
                costB.isEmpty() ? null : itemId(costB),
                costB.isEmpty() ? 0 : costB.getCount(),
                emeraldPrice,
                offer.getUses(),
                offer.getMaxUses());
    }

    /**
     * What the player hands over, as a single number.
     *
     * <p>Emeralds when the trade is bought with them, otherwise the count of the
     * main input item -- a cleric buying 32 rotten flesh for an emerald has a
     * price of 32. Without this, a price filter silently rejected every trade
     * that was not paid for in emeralds.
     */
    /** True when demand or a discount has moved this trade off its base price. */
    public boolean priceAdjusted() {
        return baseCostACount != costACount;
    }

    public int priceAmount() {
        return emeraldPrice >= 0 ? emeraldPrice : costACount;
    }

    /** Remaining trades before this offer locks until the next restock. */
    public int remainingStock() {
        return Math.max(0, maxUses - uses);
    }

    public boolean outOfStock() {
        return uses >= maxUses;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /**
     * Folds the offers into a 64-bit value used to detect change.
     *
     * <p>The tracker sends an update only when this differs from what the player
     * was last sent, which is what keeps a 200-villager trading hall from
     * producing per-tick traffic.
     */
    public static long digest(OfferView[] views) {
        long hash = 0xcbf29ce484222325L;
        for (OfferView view : views) {
            hash = mix(hash, view.hashCode());
        }
        return hash;
    }

    private static long mix(long hash, int value) {
        hash ^= value;
        hash *= 0x100000001b3L;
        return hash;
    }
}
