package me.mehmetyl.vtp.mixin;

// Everything below targets net.minecraft.world.item.trading.VillagerTrade, which
// does not exist before 26 -- trades were hardcoded VillagerTrades.ItemListing
// lambdas, not a readable registry. The mixin config drops the entry to match.
//? if >=26.1 {

import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.trading.TradeCost;
import net.minecraft.world.item.trading.VillagerTrade;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the result and cost of a trade definition.
 *
 * <p>Trade definitions live in {@code Registries.VILLAGER_TRADE}, which is a
 * worldgen registry and therefore never synced to clients. Reading it on the
 * server is the only way to know what a profession is able to sell, and what it
 * charges, before any villager has been seen doing it.
 */
@Mixin(VillagerTrade.class)
public interface VillagerTradeAccessor {
    @Accessor("gives")
    ItemStackTemplate vtp$gives();

    @Accessor("wants")
    TradeCost vtp$wants();

    /** The second item a trade charges, if it charges two. */
    @Accessor("additionalWants")
    java.util.Optional<TradeCost> vtp$additionalWants();
}
//?}
