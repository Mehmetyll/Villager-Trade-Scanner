package me.mehmetyl.vtp.compat;

import me.mehmetyl.vtp.mixin.AbstractVillagerAccessor;

import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * Every read of villager internals goes through here.
 *
 * <p>This is the version-sensitive blast radius described in the Stage 1
 * proposal: if a mapping name changes, it changes in this file and not in the
 * scanner, the tracker or the renderer.
 */
public final class VillagerAccess {
    /** Registry id used to group wandering traders in the filter UI. */
    public static final String WANDERING_TRADER_ID = "minecraft:wandering_trader";

    private VillagerAccess() {
    }

    /**
     * Returns the villager's already-generated offers, or {@code null} if it has
     * none yet. Never triggers generation.
     *
     * <p>Must only be called on the server thread: the field is a plain
     * {@code ArrayList} mutated by restock, trading and level-up.
     */
    public static MerchantOffers peekOffers(AbstractVillager villager) {
        return ((AbstractVillagerAccessor) villager).vtp$offers();
    }

    /**
     * True when this villager's profession can still be re-rolled by breaking
     * and replacing its job site.
     *
     * <p>This mirrors the vanilla condition in {@code ResetProfession}, which
     * requires {@code getVillagerXp() == 0 && level() <= 1} (on top of having no
     * job site and a profession that is neither NONE nor NITWIT). Note this is
     * deliberately <em>not</em> {@code getVillagerDataFinalized()} -- despite the
     * promising name, that flag only records that the villager's biome type was
     * assigned explicitly, and is true for freshly bred babies that are fully
     * re-rollable.
     */
    public static boolean isRerollable(Villager villager) {
        return villager.getVillagerXp() == 0
                && villager.getVillagerData().level() <= VillagerData.MIN_VILLAGER_LEVEL;
    }

    /** Registry id of the villager's profession, e.g. {@code minecraft:librarian}. */
    public static String professionId(Villager villager) {
        return villager.getVillagerData().profession().getRegisteredName();
    }

    /** Trade level (1..5). Synced to vanilla clients, unlike villager xp. */
    public static int level(Villager villager) {
        return villager.getVillagerData().level();
    }

    /**
     * True when this villager cannot have trades at all.
     *
     * <p>A jobless villager has none, and a nitwit never gets any. Breaking a job
     * site puts a villager back in the first state, and vanilla nulls its offers
     * when that happens -- so anything still held for it is stale by definition.
     */
    public static boolean hasNoTrades(Villager villager) {
        var profession = villager.getVillagerData().profession();
        return profession.is(VillagerProfession.NONE) || profession.is(VillagerProfession.NITWIT);
    }

    /**
     * Profession group id for any merchant: the villager's profession, or the
     * wandering-trader pseudo-profession.
     */
    public static String groupId(AbstractVillager villager) {
        if (villager instanceof Villager v) {
            return professionId(v);
        }
        return WANDERING_TRADER_ID;
    }
}
