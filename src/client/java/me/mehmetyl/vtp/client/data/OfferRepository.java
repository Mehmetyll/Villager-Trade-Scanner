package me.mehmetyl.vtp.client.data;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import me.mehmetyl.vtp.compat.OfferView;

import java.util.Map;
import java.util.UUID;

/**
 * The client's authoritative store of merchant offers.
 *
 * <p>Fed by three sources, in descending order of trust:
 * <ol>
 *   <li>the network, when the server runs the mod;</li>
 *   <li>the trade screen, which works on <em>any</em> server -- opening a
 *       merchant GUI yields its full offer list and its villager xp;</li>
 *   <li>the UUID-keyed cache, which survives entity id changes across reconnects.</li>
 * </ol>
 *
 * <p>Live entries are keyed by entity id because that is what packets and
 * {@code ClientLevel.getEntity(int)} use. The cache is keyed by UUID because
 * entity ids are not stable across a reconnect.
 */
public final class OfferRepository {
    /**
     * Ceiling on remembered trade screens.
     *
     * <p>The cache deliberately outlives a disconnect, so without a bound it grew
     * for every villager ever opened, for the life of the process. A few hundred
     * is far more than a player revisits, and the oldest entries are the ones
     * least likely to still be accurate.
     */
    private static final int MAX_CACHED = 512;

    /** Entries retained when the cache is swept, leaving headroom before the next. */
    private static final int SWEEP_TARGET = 384;

    private final Int2ObjectMap<VillagerSnapshot> live = new Int2ObjectOpenHashMap<>();
    private final Map<UUID, CachedOffers> cache = new Object2ObjectOpenHashMap<>();

    /**
     * Offers seen through a trade screen, with the tick they were seen on.
     *
     * <p>{@code groupId} is the profession the villager held at the time. Without
     * it these trades were applied to whatever job the villager has now, so
     * breaking a job site and replacing it showed the old one's trades under the
     * new one.
     */
    public record CachedOffers(OfferView[] offers, String groupId, int level,
                               boolean rerollable, long seenAtMillis) {
    }

    public VillagerSnapshot get(int entityId) {
        return live.get(entityId);
    }

    public void put(VillagerSnapshot snapshot) {
        live.put(snapshot.entityId(), snapshot);
    }

    public void forget(int entityId) {
        live.remove(entityId);
    }

    /** Records what a trade screen revealed. Works on vanilla servers. */
    public void cacheFromTradeScreen(UUID uuid, String groupId, OfferView[] offers,
                                     int level, boolean rerollable) {
        cache.put(uuid, new CachedOffers(offers, groupId, level, rerollable,
                System.currentTimeMillis()));
        if (cache.size() > MAX_CACHED) {
            evictOldest();
        }
    }

    /** Drops the least recently seen entries down to {@link #SWEEP_TARGET}. */
    private void evictOldest() {
        java.util.List<Map.Entry<UUID, CachedOffers>> entries = new java.util.ArrayList<>(cache.entrySet());
        entries.sort(java.util.Comparator.comparingLong(e -> e.getValue().seenAtMillis()));
        for (int i = 0; i < entries.size() - SWEEP_TARGET; i++) {
            cache.remove(entries.get(i).getKey());
        }
    }

    public CachedOffers cached(UUID uuid) {
        return cache.get(uuid);
    }

    /** Drops remembered trades that belong to a job the villager no longer has. */
    public void forgetCached(UUID uuid) {
        cache.remove(uuid);
    }

    /**
     * Drops live entries on world change, disconnect or dimension change.
     *
     * <p>The UUID cache deliberately survives: it is the entire reason a vanilla
     * server is usable, and it is what lets a villager the player opened before
     * relogging still show its trades.
     */
    public void clearLive() {
        live.clear();
    }

    /**
     * Collects every result item currently offered by merchants of one
     * profession, so the item picker can show what that villager actually sells
     * instead of the entire item registry.
     *
     * <p>Sourced from villagers observed so far. It is empty until some are seen,
     * which the picker handles by falling back to the full list.
     */
    public java.util.List<me.mehmetyl.vtp.net.VtpPayloads.TradeEntry> collectSoldTrades(String professionId) {
        java.util.LinkedHashMap<String, me.mehmetyl.vtp.net.VtpPayloads.TradeEntry> out =
                new java.util.LinkedHashMap<>();

        for (VillagerSnapshot snapshot : live.values()) {
            if (professionId != null && !professionId.equals(snapshot.groupId())) {
                continue;
            }
            for (OfferView offer : snapshot.offers()) {
                String cost = offer.costItemAId() == null ? "" : offer.costItemAId();
                String costB = offer.costItemBId() == null ? "" : offer.costItemBId();
                // Same dedupe key as the server-side catalogue, so both sources
                // produce the same one-row-per-trade shape. The separator was a
                // raw NUL byte, which made the file read as binary to grep and
                // did not actually match the catalogue it claims to mirror.
                out.putIfAbsent(offer.resultItemId() + ' ' + cost + ' ' + costB,
                        me.mehmetyl.vtp.net.VtpPayloads.TradeEntry.of(
                                offer.resultItemId(), cost, costB));
            }
        }

        return java.util.List.copyOf(out.values());
    }
}
