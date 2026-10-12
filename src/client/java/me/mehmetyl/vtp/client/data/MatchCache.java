package me.mehmetyl.vtp.client.data;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.mehmetyl.vtp.client.filter.FilterEngine;

/**
 * Caches the filter outcome per villager.
 *
 * <p>An entry stays valid until either the villager's offer digest changes or
 * the player edits the filters (config generation). Both checks are a single
 * comparison, so a steady-state scan of a 200-villager hall performs no rule
 * evaluation at all.
 */
public final class MatchCache {
    private final Int2ObjectMap<Entry> entries = new Int2ObjectOpenHashMap<>();

    private int hits;
    private int misses;

    public static final class Entry {
        long digest;
        int generation;

        /** Null when this villager matched no rule. */
        public FilterEngine.Match[] match;
    }

    /**
     * Returns the cached entry if it is still valid for this digest and
     * generation, otherwise {@code null}.
     */
    public Entry lookup(int entityId, long digest, int generation) {
        Entry entry = entries.get(entityId);
        if (entry != null && entry.digest == digest && entry.generation == generation) {
            hits++;
            return entry;
        }
        misses++;
        return null;
    }

    public void store(int entityId, long digest, int generation, FilterEngine.Match[] match) {
        Entry entry = entries.get(entityId);
        if (entry == null) {
            entry = new Entry();
            entries.put(entityId, entry);
        }
        entry.digest = digest;
        entry.generation = generation;
        entry.match = match;
    }

    public void forget(int entityId) {
        entries.remove(entityId);
    }

    /** Called on world change and disconnect so nothing leaks across sessions. */
    public void clear() {
        entries.clear();
        hits = 0;
        misses = 0;
    }

    public int size() {
        return entries.size();
    }

    /** Hit rate as a percentage, for the debug readout. */
    public int hitRatePercent() {
        int total = hits + misses;
        return total == 0 ? 100 : (hits * 100) / total;
    }
}
