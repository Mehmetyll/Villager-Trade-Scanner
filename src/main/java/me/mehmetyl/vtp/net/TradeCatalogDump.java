package me.mehmetyl.vtp.net;

// Generates the trade catalogue that TradeCatalogBuilder reads below 26.
//
// Run with -Dvtp.dumpTradeCatalog=true on a dev server; it writes
// vtp-trade-catalog.json into the run directory, which is then committed as the
// bundled resource. Never runs otherwise, and does not exist outside the range
// where it is meaningful: from 26 the server reads the real registry instead.
// The catalogue is generated once from the newest pre-26 target and shared by
// every version below it.
//? if <26.1 {
/*import me.mehmetyl.vtp.VtpMod;
import me.mehmetyl.vtp.compat.VillagerAccess;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TradeCatalogDump {

    private TradeCatalogDump() {
    }

    public static boolean dumpRequested() {
        return Boolean.getBoolean("vtp.dumpTradeCatalog");
    }

    // Rolled enough times to see every result a randomised listing can pick --
    // enchanted books, dyed armour, biome-specific goods draw from a pool.
    private static final int SAMPLES_PER_LISTING = 64;

    public static void dump(MinecraftServer server) {
        try {
            ServerLevel level = server.overworld();

            // A throwaway villager. Several listings read the trader's villager
            // type to decide what they offer and return nothing without one.
            // It is never added to the world.
            Villager trader = EntityType.VILLAGER.create(level, EntitySpawnReason.COMMAND);

            // Fixed seed so regenerating produces the same file.
            RandomSource random = RandomSource.create(0L);
            JsonObject root = new JsonObject();

            for (var profession : VillagerTrades.TRADES.entrySet()) {
                Map<String, VtpPayloads.TradeEntry> trades = new LinkedHashMap<>();
                for (var byLevel : profession.getValue().int2ObjectEntrySet()) {
                    sample(byLevel.getValue(), level, trader, random, trades);
                }
                addRows(root, profession.getKey().identifier().toString(), trades);
            }

            Map<String, VtpPayloads.TradeEntry> wandering = new LinkedHashMap<>();
            for (var pool : VillagerTrades.WANDERING_TRADER_TRADES) {
                sample(pool.getLeft(), level, trader, random, wandering);
            }
            addRows(root, VillagerAccess.WANDERING_TRADER_ID, wandering);

            Path out = Path.of("vtp-trade-catalog.json");
            Files.writeString(out, root.toString(), StandardCharsets.UTF_8);
            VtpMod.LOGGER.info("Wrote {} professions to {}", root.size(), out.toAbsolutePath());
        } catch (Exception e) {
            VtpMod.LOGGER.error("Trade catalogue dump failed", e);
        }
    }

    private static void addRows(JsonObject root, String profession,
                                Map<String, VtpPayloads.TradeEntry> trades) {
        if (trades.isEmpty()) {
            return;
        }
        JsonArray rows = new JsonArray();
        for (VtpPayloads.TradeEntry trade : trades.values()) {
            JsonArray row = new JsonArray();
            row.add(trade.result());
            row.add(trade.cost());
            row.add(trade.costB());
            rows.add(row);
        }
        root.add(profession, rows);
    }

    private static void sample(VillagerTrades.ItemListing[] listings,
                               ServerLevel level,
                               Villager trader,
                               RandomSource random,
                               Map<String, VtpPayloads.TradeEntry> out) {
        for (VillagerTrades.ItemListing listing : listings) {
            // Explorer maps run a structure search that can take a minute of
            // wall clock and generate chunks. Skipped here; opening a
            // cartographer once fills the trade in from the observed offer.
            if (listing == null || listing.getClass().getSimpleName().contains("TreasureMap")) {
                continue;
            }
            for (int i = 0; i < SAMPLES_PER_LISTING; i++) {
                try {
                    MerchantOffer offer = listing.getOffer(level, trader, random);
                    if (offer == null) {
                        continue;
                    }
                    ItemStack gives = offer.getResult();
                    if (gives.isEmpty()) {
                        continue;
                    }
                    String resultId = BuiltInRegistries.ITEM.getKey(gives.getItem()).toString();
                    String costA = itemId(offer.getCostA());
                    String costB = itemId(offer.getCostB());
                    out.putIfAbsent(resultId + ' ' + costA + ' ' + costB,
                            VtpPayloads.TradeEntry.of(resultId, costA, costB));
                } catch (Exception ignored) {
                    // One listing needing more world than we have must not lose
                    // the rest of the profession.
                }
            }
        }
    }

    private static String itemId(ItemStack stack) {
        return stack == null || stack.isEmpty()
                ? ""
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
*///?}
