package me.mehmetyl.vtp.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/**
 * Resolves an item registry id to something a player recognises.
 *
 * <p>The display name must come from {@link Item#getName}, never from a language
 * key assembled out of the registry id. Block items key as
 * {@code block.minecraft.bookshelf}, so building {@code item.<namespace>.<path>}
 * by hand produces a key that does not exist and Minecraft renders it raw --
 * which is exactly how {@code item.minecraft.bookshelf} ended up in the label.
 */
public final class ItemNames {
    private ItemNames() {
    }

    /** The item for this id, or {@code null} when it is unknown or unset. */
    public static Item item(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        } catch (Exception e) {
            // A hand-edited or modded-and-since-removed id simply has no item.
            return null;
        }
    }

    /** Localised display name, falling back to the raw id when unresolvable. */
    public static String displayName(String id) {
        Item item = item(id);
        if (item == null) {
            return id == null ? "" : id;
        }
        return item.getName(item.getDefaultInstance()).getString();
    }

    public static boolean isEmerald(String id) {
        return "minecraft:emerald".equals(id);
    }
}
