package me.mehmetyl.vtp.client.gui;

import me.mehmetyl.vtp.compat.VillagerAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Display names and representative icons for villager professions.
 *
 * <p>Each profession is shown as its job site block, which is how players
 * already identify villagers in the world -- a lectern reads as "librarian"
 * faster than the word does.
 */
public final class ProfessionIcons {
    private static final Map<String, ItemStack> ICONS = new LinkedHashMap<>();

    /**
     * Stand-in for a profession with no job-site icon, typically a modded one.
     *
     * <p>Shared rather than built per call: {@link #icon} runs from the row
     * drawing path, so an unknown id used to allocate a stack every frame.
     */
    private static final ItemStack UNKNOWN = new ItemStack(Items.VILLAGER_SPAWN_EGG);

    /** Selection order for the profession picker. */
    public static final List<String> PROFESSIONS;

    static {
        put("minecraft:librarian", Items.LECTERN);
        put("minecraft:farmer", Items.COMPOSTER);
        put("minecraft:armorer", Items.BLAST_FURNACE);
        put("minecraft:toolsmith", Items.SMITHING_TABLE);
        put("minecraft:weaponsmith", Items.GRINDSTONE);
        put("minecraft:cleric", Items.BREWING_STAND);
        put("minecraft:fletcher", Items.FLETCHING_TABLE);
        put("minecraft:butcher", Items.SMOKER);
        put("minecraft:cartographer", Items.CARTOGRAPHY_TABLE);
        put("minecraft:fisherman", Items.BARREL);
        put("minecraft:leatherworker", Items.CAULDRON);
        put("minecraft:mason", Items.STONECUTTER);
        put("minecraft:shepherd", Items.LOOM);
        put(VillagerAccess.WANDERING_TRADER_ID, Items.WANDERING_TRADER_SPAWN_EGG);

        PROFESSIONS = List.copyOf(ICONS.keySet());
    }

    private ProfessionIcons() {
    }

    private static void put(String id, net.minecraft.world.item.Item item) {
        ICONS.put(id, new ItemStack(item));
    }

    /**
     * Professions offered by the picker: the ones with job-site icons, followed by
     * any others the server described.
     *
     * <p>The hardcoded list alone could never show a modded or datapack
     * profession, even though the trade catalogue arrives keyed by exactly those
     * ids. Falls back to the bare list on a vanilla server, which sends no
     * catalogue.
     */
    public static List<String> available() {
        java.util.Set<String> known = me.mehmetyl.vtp.client.VtpClientMod.catalog().professions();
        if (known.isEmpty()) {
            return PROFESSIONS;
        }

        List<String> out = new java.util.ArrayList<>(PROFESSIONS);
        for (String id : known) {
            if (!ICONS.containsKey(id)) {
                out.add(id);
            }
        }
        return out;
    }

    public static ItemStack icon(String professionId) {
        ItemStack stack = ICONS.get(professionId);
        return stack == null ? UNKNOWN : stack;
    }

    /**
     * Localised profession name.
     *
     * <p>Vanilla keys professions as {@code entity.<namespace>.villager.<path>};
     * the wandering trader is a separate entity type and keys differently.
     */
    public static Component name(String professionId) {
        if (professionId == null) {
            return Component.literal("?");
        }
        if (VillagerAccess.WANDERING_TRADER_ID.equals(professionId)) {
            return Component.translatable("entity.minecraft.wandering_trader");
        }

        int colon = professionId.indexOf(':');
        String namespace = colon >= 0 ? professionId.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? professionId.substring(colon + 1) : professionId;

        return Component.translatable("entity." + namespace + ".villager." + path);
    }
}
