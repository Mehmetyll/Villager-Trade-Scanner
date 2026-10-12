package me.mehmetyl.vtp.client.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import me.mehmetyl.vtp.net.VtpPayloads;

import java.util.List;
import java.util.Map;

/**
 * What each profession is capable of selling, and what it charges, as sent by
 * the server on join.
 *
 * <p>The client cannot work this out for itself -- trade definitions are a
 * worldgen registry that is never synced -- so on a vanilla server this stays
 * empty and the item picker falls back to what it has observed.
 */
public final class TradeCatalog {
    private final Map<String, List<VtpPayloads.TradeEntry>> byProfession = new Object2ObjectOpenHashMap<>();

    public void replace(List<VtpPayloads.ProfessionItems> professions) {
        byProfession.clear();
        for (var entry : professions) {
            byProfession.put(entry.profession(), entry.items());
        }
    }

    /**
     * Every trade this profession can offer; empty when unknown.
     *
     * <p>Whole trades rather than bare result items: a result reachable through
     * several different costs is several rows in the picker, not one.
     */
    public List<VtpPayloads.TradeEntry> tradesFor(String professionId) {
        List<VtpPayloads.TradeEntry> trades = byProfession.get(professionId);
        return trades == null ? List.of() : trades;
    }

    /**
     * The item a profession wants in exchange for {@code resultItemId}, or
     * {@code null} when it is not known.
     */
    public String costFor(String professionId, String resultItemId) {
        List<VtpPayloads.TradeEntry> trades = byProfession.get(professionId);
        if (trades == null || resultItemId == null) {
            return null;
        }
        for (var trade : trades) {
            if (resultItemId.equals(trade.result())) {
                return trade.cost() == null || trade.cost().isBlank() ? null : trade.cost();
            }
        }
        return null;
    }

    /** Every profession the server described, including modded ones. */
    public java.util.Set<String> professions() {
        return byProfession.keySet();
    }

    public boolean isEmpty() {
        return byProfession.isEmpty();
    }

    public void clear() {
        byProfession.clear();
    }
}
