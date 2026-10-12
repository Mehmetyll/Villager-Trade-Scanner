package me.mehmetyl.vtp.client.data;

import me.mehmetyl.vtp.compat.OfferView;

/**
 * Everything the client knows about one merchant, in the form the filter engine
 * and renderer consume.
 *
 * <p>Immutable and built only when data changes, never per tick or per frame.
 */
public record VillagerSnapshot(
        int entityId,
        String groupId,
        int level,
        OfferState offerState,
        LockState lockState,
        OfferView[] offers,
        long digest) {

    /** Where the offer data came from, or that we have none. */
    public enum OfferState {
        /** Offers are known and current. */
        KNOWN,
        /** Offers were seen earlier (trade GUI) but may be stale. */
        CACHED,
        /** Offers cannot be obtained -- vanilla server, never opened. */
        UNKNOWN
    }

    /** Whether the profession can still be re-rolled. */
    public enum LockState {
        /** Traded at least once: profession is permanent. */
        LOCKED,
        /** Never traded: breaking the job site re-rolls the profession. */
        REROLLABLE,
        /**
         * Cannot be determined. On a vanilla server every level-1 villager lands
         * here, because villager xp is not synced and level alone cannot
         * distinguish "never traded" from "traded once without levelling".
         */
        AMBIGUOUS
    }

    /**
     * The key the match cache validates a cached outcome against.
     *
     * <p>The offer digest alone is not enough. A rule tests the profession
     * before it tests anything about the offers, and breaking a job site then
     * replacing it changes the profession while the offers -- and so the digest
     * -- can stay identical for the tick or two before the server sends new
     * ones. Keyed on the digest alone, the match computed under the old
     * profession was handed straight back in that window, and the previous
     * label flashed up again before settling.
     */
    public long matchKey() {
        return digest * 31L + (groupId == null ? 0 : groupId.hashCode());
    }

    private static final OfferView[] NO_OFFERS = new OfferView[0];

    /** A snapshot for a merchant whose offers we cannot see. */
    public static VillagerSnapshot unknown(int entityId, String groupId, int level, LockState lock) {
        return new VillagerSnapshot(entityId, groupId, level, OfferState.UNKNOWN, lock, NO_OFFERS, 0L);
    }
}
