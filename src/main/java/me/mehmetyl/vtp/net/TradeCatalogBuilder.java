package me.mehmetyl.vtp.net;

import me.mehmetyl.vtp.VtpMod;
import me.mehmetyl.vtp.compat.VillagerAccess;
import net.minecraft.server.MinecraftServer;

import net.minecraft.core.registries.BuiltInRegistries;

//? if >=26.1 {
import me.mehmetyl.vtp.mixin.VillagerTradeAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.item.trading.TradeSets;
import net.minecraft.world.item.trading.VillagerTrade;
//?} else {
/*import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
*///?}

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the list of items each profession is capable of selling.
 *
 * <p>From 26 on, trade definitions are a worldgen registry, so only the server
 * has them. Sending this to the client once on join is what lets the filter UI
 * offer a short, accurate item list for a profession the player has never met --
 * and it stays correct for datapacks that add or change trades.
 *
 * <p>Before 26 there is no such registry: trades are {@code
 * VillagerTrades.ItemListing} lambdas that have to be <em>invoked</em> to yield
 * anything. So there they are invoked -- once per server, with a fixed seed --
 * and the offers they produce are read back. That gives the picker the same
 * short, accurate list, and it covers modded professions too, which a table
 * shipped inside the jar could not.
 *
 * <p>Built once per server and cached; it cannot change without a datapack
 * reload.
 */
public final class TradeCatalogBuilder {
    /**
     * The wandering trader's three trade sets, as read by its own
     * {@code updateTrades}. It is not in the profession registry, so without
     * these it gets no catalogue entry at all and its item picker falls back to
     * offering the entire item registry.
     */
    //? if >=26.1 {
    private static final List<ResourceKey<TradeSet>> WANDERING_TRADER_SETS = List.of(
            TradeSets.WANDERING_TRADER_BUYING,
            TradeSets.WANDERING_TRADER_COMMON,
            TradeSets.WANDERING_TRADER_UNCOMMON);
    //?}

    private static List<VtpPayloads.ProfessionItems> cached;

    private TradeCatalogBuilder() {
    }

    public static List<VtpPayloads.ProfessionItems> get(MinecraftServer server) {
        if (cached == null) {
            cached = build(server);
        }
        return cached;
    }

    /** Dropped on datapack reload so the next join rebuilds it. */
    public static void invalidate() {
        cached = null;
    }

    //? if >=26.1 {
    private static List<VtpPayloads.ProfessionItems> build(MinecraftServer server) {
        List<VtpPayloads.ProfessionItems> result = new ArrayList<>();

        try {
            RegistryAccess registries = server.registryAccess();
            Registry<VillagerProfession> professions = registries.lookupOrThrow(Registries.VILLAGER_PROFESSION);
            Registry<TradeSet> tradeSets = registries.lookupOrThrow(Registries.TRADE_SET);

            for (Holder.Reference<VillagerProfession> holder : professions.listElements().toList()) {
                // Keyed by result so a profession selling the same item at several
                // levels appears once, keeping the picker list short.
                java.util.LinkedHashMap<String, VtpPayloads.TradeEntry> trades = new java.util.LinkedHashMap<>();

                for (int level = VillagerData.MIN_VILLAGER_LEVEL; level <= VillagerData.MAX_VILLAGER_LEVEL; level++) {
                    ResourceKey<TradeSet> key = holder.value().getTrades(level);
                    if (key == null) {
                        continue;
                    }
                    TradeSet tradeSet = tradeSets.getValue(key);
                    if (tradeSet == null) {
                        continue;
                    }
                    collectResults(tradeSet, trades);
                }

                if (!trades.isEmpty()) {
                    result.add(new VtpPayloads.ProfessionItems(
                            holder.key().identifier().toString(), List.copyOf(trades.values())));
                }
            }

            // The filter UI treats the wandering trader as a profession of its
            // own, so it needs an entry keyed the same way.
            java.util.LinkedHashMap<String, VtpPayloads.TradeEntry> wandering =
                    new java.util.LinkedHashMap<>();
            for (ResourceKey<TradeSet> key : WANDERING_TRADER_SETS) {
                TradeSet tradeSet = tradeSets.getValue(key);
                if (tradeSet != null) {
                    collectResults(tradeSet, wandering);
                }
            }
            if (!wandering.isEmpty()) {
                result.add(new VtpPayloads.ProfessionItems(
                        VillagerAccess.WANDERING_TRADER_ID, List.copyOf(wandering.values())));
            }
        } catch (Exception e) {
            // A missing or unusual registry must not stop players joining; the
            // client simply falls back to items it has observed.
            VtpMod.LOGGER.warn("Could not build the villager trade catalogue", e);
        }

        return List.copyOf(result);
    }

    private static void collectResults(TradeSet tradeSet,
                                       java.util.Map<String, VtpPayloads.TradeEntry> out) {
        tradeSet.getTrades().stream().forEach(tradeHolder -> {
            try {
                VillagerTrade trade = tradeHolder.value();
                VillagerTradeAccessor accessor = (VillagerTradeAccessor) trade;

                var stack = accessor.vtp$gives().create();
                if (stack.isEmpty()) {
                    return;
                }
                String result = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();

                String cost = "";
                var wants = accessor.vtp$wants();
                if (wants != null) {
                    cost = BuiltInRegistries.ITEM.getKey(wants.item().value()).toString();
                }

                // A trade can charge a second, different item -- a fletcher wants
                // gravel *and* emeralds for flint. Reading only `wants` made such a
                // trade look like it cost nothing but emeralds.
                String costB = "";
                var additional = accessor.vtp$additionalWants();
                if (additional != null && additional.isPresent()) {
                    costB = BuiltInRegistries.ITEM.getKey(
                            additional.get().item().value()).toString();
                }

                // Keyed by every side. Keyed by result alone, every buy trade a
                // profession has collapsed into one entry -- a farmer paying
                // emeralds for wheat, potato, carrot and beetroot became a single
                // "emerald" row keeping whichever cost was read first.
                out.putIfAbsent(result + ' ' + cost + ' ' + costB,
                        VtpPayloads.TradeEntry.of(result, cost, costB));
            } catch (Exception ignored) {
                // One malformed trade should not lose the whole profession.
            }
        });
    }
    //?} else {
    /*// Where the generated catalogue lives inside the jar.
    private static final String CATALOGUE_RESOURCE =
            "/data/villager_trade_scanner/trade_catalog.json";

    // Reads the catalogue generated ahead of time by SampleDump.
    //
    // It is generated rather than sampled at run time on purpose. Sampling means
    // invoking every listing, and some of them -- the cartographer's explorer
    // maps, reached through a TypeSpecificTrade wrapper that hides them from any
    // by-name skip -- run a structure search costing seconds on the server
    // thread. Capping that with a deadline only traded the delay for a catalogue
    // that came out different on every start. Doing it once, offline, costs
    // nothing at run time and gives every server the same list.
    private static List<VtpPayloads.ProfessionItems> build(MinecraftServer server) {
        try (InputStream in = TradeCatalogBuilder.class.getResourceAsStream(CATALOGUE_RESOURCE)) {
            if (in == null) {
                VtpMod.LOGGER.warn("No bundled trade catalogue; the item picker will "
                        + "fall back to observed trades");
                return List.of();
            }

            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();

            List<VtpPayloads.ProfessionItems> result = new ArrayList<>();
            for (var profession : root.entrySet()) {
                List<VtpPayloads.TradeEntry> trades = new ArrayList<>();
                for (JsonElement element : profession.getValue().getAsJsonArray()) {
                    JsonArray row = element.getAsJsonArray();
                    trades.add(VtpPayloads.TradeEntry.of(
                            row.get(0).getAsString(),
                            row.get(1).getAsString(),
                            row.get(2).getAsString()));
                }
                if (!trades.isEmpty()) {
                    result.add(new VtpPayloads.ProfessionItems(
                            profession.getKey(), List.copyOf(trades)));
                }
            }
            VtpMod.LOGGER.info("Loaded trade catalogue: {} professions, {} trades",
                    result.size(), result.stream().mapToInt(it -> it.items().size()).sum());
            return List.copyOf(result);
        } catch (Exception e) {
            // A broken catalogue must not stop players joining; the client simply
            // falls back to items it has observed.
            VtpMod.LOGGER.warn("Could not read the bundled villager trade catalogue", e);
            return List.of();
        }
    }
    *///?}
}
