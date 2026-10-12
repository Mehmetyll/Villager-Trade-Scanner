package me.mehmetyl.vtp.mixin;

import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the raw {@code offers} field of {@link AbstractVillager}.
 *
 * <p>This exists specifically so we never call {@code getOffers()}. That method
 * lazily calls {@code updateTrades(ServerLevel)} when the field is still null,
 * which would generate and permanently fix a villager's random trade roll just
 * because a player walked past it. Reading the field directly has no side
 * effects, and a null result correctly means "trades not generated yet".
 */
@Mixin(AbstractVillager.class)
public interface AbstractVillagerAccessor {
    @Accessor("offers")
    MerchantOffers vtp$offers();
}
