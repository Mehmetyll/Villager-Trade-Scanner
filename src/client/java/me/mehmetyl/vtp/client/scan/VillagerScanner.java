package me.mehmetyl.vtp.client.scan;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.mehmetyl.vtp.VtpMod;
import me.mehmetyl.vtp.client.VtpClientMod;
import me.mehmetyl.vtp.client.config.VtpConfig;
import me.mehmetyl.vtp.client.data.HighlightEntry;
import me.mehmetyl.vtp.client.data.MatchCache;
import me.mehmetyl.vtp.client.data.OfferRepository;
import me.mehmetyl.vtp.client.data.VillagerSnapshot;
import me.mehmetyl.vtp.client.filter.FilterEngine;
import me.mehmetyl.vtp.compat.OfferView;
import me.mehmetyl.vtp.compat.VillagerAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds nearby merchants, decides which ones match, and produces the list the
 * renderer consumes.
 *
 * <p>Work is spread over a scan cycle rather than done in one spike. Each cycle
 * begins with a single broad-phase entity query, then processes the results in
 * {@link #BUCKETS} slices on consecutive ticks. Results accumulate into a back
 * buffer and are swapped in only when the cycle completes, so the renderer never
 * observes a half-built list.
 *
 * <p>The hot loop culls by squared distance, reuses every collection and
 * {@link HighlightEntry}, and sorts in place, so a steady-state cycle allocates
 * nothing.
 */
public final class VillagerScanner {
    /** Upper bound on entities examined per cycle. */
    private static final int MAX_SCAN_ENTITIES = 1024;

    /** Upper bound on simultaneously glowing villagers. */
    private static final int MAX_ENTRIES = 256;

    /** Ticks a scan cycle is spread across. */
    private static final int BUCKETS = 4;




    private final FilterEngine engine = new FilterEngine();
    private final MatchCache matchCache = new MatchCache();

    private final List<AbstractVillager> scratch = new ArrayList<>();

    /**
     * Two independent entry pools, one per buffer.
     *
     * <p>This must stay two pools. A single shared pool makes the double
     * buffering an illusion: the back buffer would overwrite the very objects the
     * renderer is still reading from the front buffer, mid-cycle, which shows up
     * in game as labels flickering on and off.
     */
    private final List<HighlightEntry> poolA = new ArrayList<>();
    private final List<HighlightEntry> poolB = new ArrayList<>();
    private boolean buildIntoA = true;

    /** Front buffer: what the renderer reads. Swapped, never rebuilt in place. */
    private List<HighlightEntry> active = new ArrayList<>();

    /** Back buffer: filled across the buckets of the current cycle. */
    private List<HighlightEntry> building = new ArrayList<>();

    /** Scratch for selecting the nearest N without a full sort. */
    private HighlightEntry[] nearest = new HighlightEntry[0];

    /** Packed block position -> labels already placed there, for stacking. */
    private final Long2IntMap stackCounts = new Long2IntOpenHashMap();

    /** Entity id -> entry, so the render path resolves in O(1) with an int key. */
    private final Int2ObjectMap<HighlightEntry> activeById = new Int2ObjectOpenHashMap<>();

    private ClientLevel lastLevel;
    private int tickCounter;

    /**
     * Config generation the last cycle ran under, so an edit can cut the wait.
     * -1 forces the first cycle to start without idling.
     */
    private int seenGeneration = -1;
    private int bucket = -1;
    private int cursor;
    private boolean errorLogged;

    private int lastScanned;
    private int lastMatched;
    private int lastLabelled;
    private long cycleNanos;
    private long lastCycleNanos;

    public void onClientTick(Minecraft minecraft) {
        VtpConfig config = VtpClientMod.config();

        // Toggled off: this is the whole cost per tick. Fabric events cannot be
        // unregistered, so a single field read and branch is the floor.
        if (!config.previewEnabled) {
            if (!active.isEmpty()) {
                clearBuffers();
            }
            return;
        }

        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            if (lastLevel != null) {
                reset();
            }
            return;
        }

        // Dimension change, world reload or a different server: drop everything
        // keyed by entity id, which is only meaningful within one level.
        if (level != lastLevel) {
            reset();
            lastLevel = level;
        }

        try {
            if (bucket < 0) {
                // A filter edit takes effect on the next cycle, so at the default
                // interval it could sit invisible for half a second and at the
                // maximum for five. Waiting that long after pressing Save reads
                // as the mod ignoring the change, so an edit ends the idle now.
                int generation = VtpClientMod.configGeneration();
                if (generation != seenGeneration) {
                    seenGeneration = generation;
                    tickCounter = config.general.scanIntervalTicks;
                }

                // Idle between cycles.
                if (++tickCounter < config.general.scanIntervalTicks) {
                    return;
                }
                tickCounter = 0;
                beginCycle(minecraft, level, config);
            } else {
                processBucket(minecraft, config);
            }
        } catch (Exception e) {
            if (!errorLogged) {
                errorLogged = true;
                VtpMod.LOGGER.error("Villager scan failed; further errors suppressed", e);
            }
            abortCycle();
        }
    }

    /** Broad phase: one entity query per cycle, the dominant constant cost. */
    private void beginCycle(Minecraft minecraft, ClientLevel level, VtpConfig config) {
        long start = System.nanoTime();

        engine.refresh(config.rules, VtpClientMod.configGeneration());

        if (!engine.hasRules()) {
            clearBuffers();
            lastScanned = 0;
            lastMatched = 0;
            lastCycleNanos = System.nanoTime() - start;
            return;
        }

        double radius = config.general.scanRadius;

        scratch.clear();
        level.getEntities(
                EntityTypeTest.forClass(AbstractVillager.class),
                minecraft.player.getBoundingBox().inflate(radius),
                AbstractVillager::isAlive,
                scratch,
                MAX_SCAN_ENTITIES);

        building.clear();
        bucket = 0;
        cursor = 0;
        lastScanned = scratch.size();
        cycleNanos = System.nanoTime() - start;

        // A small village finishes in the same tick it started.
        processBucket(minecraft, config);
    }

    /** Narrow phase: one slice of the queried villagers. */
    private void processBucket(Minecraft minecraft, VtpConfig config) {
        long start = System.nanoTime();

        double radius = config.general.scanRadius;
        double radiusSq = radius * radius;

        OfferRepository repository = VtpClientMod.offers();
        int generation = VtpClientMod.configGeneration();

        // Ceiling division so the final bucket never leaves a remainder behind.
        int perBucket = Math.max(1, (scratch.size() + BUCKETS - 1) / BUCKETS);
        int end = Math.min(scratch.size(), cursor + perBucket);

        for (; cursor < end; cursor++) {
            AbstractVillager villager = scratch.get(cursor);

            // The entity may have died or unloaded since the broad phase.
            if (!villager.isAlive()) {
                continue;
            }

            // Squared distance only -- no sqrt anywhere in this loop.
            double distanceSq = villager.distanceToSqr(minecraft.player);
            if (distanceSq > radiusSq) {
                continue;
            }

            VillagerSnapshot snapshot = resolve(villager, repository);

            MatchCache.Entry cached =
                    matchCache.lookup(villager.getId(), snapshot.matchKey(), generation);
            FilterEngine.Match[] match;
            if (cached != null) {
                match = cached.match;
            } else {
                match = engine.match(snapshot);
                matchCache.store(villager.getId(), snapshot.matchKey(), generation, match);
            }

            if (match == null || match.length == 0 || building.size() >= MAX_ENTRIES) {
                continue;
            }

            HighlightEntry entry = borrow(building.size());
            entry.set(villager.getId(),
                    colourFor(snapshot.lockState(), snapshot.offerState(), config),
                    match,
                    distanceSq,
                    villager.getX(), villager.getY(), villager.getZ(),
                    // Stacking is decided in finishCycle, once the nearest
                    // labelled entries are known and can be compared to each other.
                    0);
            // Tested here, on the scan interval, rather than per frame: a raycast
            // per villager per frame would be far too expensive.
            entry.occluded = !hasLineOfSight(minecraft, villager);
            building.add(entry);
        }

        cycleNanos += System.nanoTime() - start;

        if (cursor >= scratch.size() || ++bucket >= BUCKETS) {
            finishCycle(minecraft, config);
        }
    }

    /** Publishes the back buffer and rebuilds the render-side index. */
    private void finishCycle(Minecraft minecraft, VtpConfig config) {
        long start = System.nanoTime();

        // Only the nearest few get labels, so a full sort of every match is
        // wasted work. Select the top N directly instead -- glow does not care
        // about ordering.
        int labelled = selectNearest(building, config.general.maxHighlighted);

        // Every selected match gets its own label. Collapsing crowds into a count
        // existed for catch-all rules that matched whole villages; rules now
        // always name a profession and an item, so matches are few and a plain
        // label per villager is clearer.
        //
        // Villagers in a trading hall routinely share a block, and two labels at
        // the same height are unreadable, so each one after the first is stacked a
        // line higher. nearest[] is sorted by distance, so the closest villager
        // keeps the lowest -- most readable -- slot.
        stackCounts.clear();

        for (int i = 0; i < labelled; i++) {
            HighlightEntry entry = nearest[i];
            long cell = BlockPos.asLong(Mth.floor(entry.x), Mth.floor(entry.y), Mth.floor(entry.z));

            // Counted in lines, not labels: a villager matching three rules takes
            // three of them, so the next villager on this block starts above all
            // three instead of writing over the middle of the stack.
            int placed = stackCounts.get(cell);
            stackCounts.put(cell, placed + entry.rowCount());

            entry.stackIndex = placed;
            entry.labelVisible = true;
            // Text is measured only for entries that will actually draw one.
            entry.refreshRenderData(minecraft.font);
        }

        // Swap buffers. The renderer keeps reading the old front buffer until
        // this assignment, and the pools are distinct, so nothing it holds is
        // mutated underneath it.
        List<HighlightEntry> previous = active;
        active = building;
        building = previous;
        building.clear();
        buildIntoA = !buildIntoA;

        activeById.clear();
        for (HighlightEntry entry : active) {
            activeById.put(entry.entityId, entry);
        }

        lastMatched = active.size();
        lastLabelled = labelled;
        bucket = -1;
        cursor = 0;
        lastCycleNanos = cycleNanos + (System.nanoTime() - start);
    }

    /**
     * Fills {@link #nearest} with the {@code limit} closest entries, ascending.
     *
     * <p>A bounded insertion beats sorting everything: most candidates fail a
     * single comparison against the current worst and are rejected immediately.
     *
     * @return how many entries were selected
     */
    private int selectNearest(List<HighlightEntry> candidates, int limit) {
        int cap = Math.min(limit, candidates.size());
        if (nearest.length < cap) {
            nearest = new HighlightEntry[cap];
        }

        int count = 0;
        for (HighlightEntry entry : candidates) {
            entry.labelVisible = false;

            if (count == cap && (cap == 0 || entry.distanceSq >= nearest[count - 1].distanceSq)) {
                continue;
            }

            int i = Math.min(count, cap - 1);
            while (i > 0 && nearest[i - 1].distanceSq > entry.distanceSq) {
                nearest[i] = nearest[i - 1];
                i--;
            }
            nearest[i] = entry;
            if (count < cap) {
                count++;
            }
        }
        return count;
    }

    private void abortCycle() {
        bucket = -1;
        cursor = 0;
        building.clear();
        clearBuffers();
    }


    /**
     * True when nothing solid stands between the player and this villager.
     *
     * <p>This is what keeps the mod from working as an X-ray. The entity outline
     * pass draws through terrain -- that is how vanilla's glowing effect behaves
     * -- so a villager behind a wall would otherwise be visible through it, and
     * a highlighted villager is a good proxy for a hidden base.
     *
     * <p>Tested against block collision shapes only; fluids and non-colliding
     * blocks like grass do not hide a villager.
     *
     * <p>Three rays, up the body rather than to the eye alone. A villager penned
     * into a single block -- the standard trading-hall arrangement -- has its eye
     * directly beneath the block above it, so an eye-only ray clips that block and
     * the villager reads as hidden even while the player is looking straight at
     * it. Any one clear ray means it can be seen.
     */
    private static boolean hasLineOfSight(Minecraft minecraft, AbstractVillager villager) {
        Vec3 from = minecraft.player.getEyePosition();

        double x = villager.getX();
        double z = villager.getZ();
        double feet = villager.getY() + 0.1;
        double eye = villager.getEyeY();
        double waist = (feet + eye) * 0.5;

        return isClear(minecraft, from, new Vec3(x, waist, z))
                || isClear(minecraft, from, new Vec3(x, eye, z))
                || isClear(minecraft, from, new Vec3(x, feet, z));
    }

    private static boolean isClear(Minecraft minecraft, Vec3 from, Vec3 to) {
        ClipContext context = new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player);
        return minecraft.level.clip(context).getType() == HitResult.Type.MISS;
    }

    /**
     * Builds the best snapshot available for a villager, in descending order of
     * trust: live network data, then what a trade screen revealed earlier, then
     * profession-only knowledge from synced entity data.
     */
    private VillagerSnapshot resolve(AbstractVillager villager, OfferRepository repository) {
        String groupId = VillagerAccess.groupId(villager);

        // Break a job site and vanilla nulls the villager's offers, so a live
        // entry the server has not retracted yet describes a job it no longer
        // has. Dropping it here only covers the case where we actually observe
        // the jobless state; breaking and replacing inside one scan interval
        // often skips it entirely, which is why the UUID cache is checked
        // against the current profession further down rather than relying on
        // this.
        if (villager instanceof Villager jobless && VillagerAccess.hasNoTrades(jobless)) {
            repository.forget(villager.getId());
            return VillagerSnapshot.unknown(villager.getId(), groupId,
                    VillagerAccess.level(jobless), VillagerSnapshot.LockState.AMBIGUOUS);
        }

        VillagerSnapshot live = repository.get(villager.getId());
        if (live != null) {
            // Breaking a job site changes the profession without necessarily
            // changing the offers, so the group stored when the packet arrived can
            // be out of date. The live entity is authoritative.
            if (!groupId.equals(live.groupId())) {
                live = new VillagerSnapshot(live.entityId(), groupId, live.level(),
                        live.offerState(), live.lockState(), live.offers(), live.digest());
                repository.put(live);
            }
            return live;
        }
        int level = villager instanceof Villager v ? VillagerAccess.level(v) : 0;

        OfferRepository.CachedOffers cached = repository.cached(villager.getUUID());
        if (cached != null && !groupId.equals(cached.groupId())) {
            // Those trades came from a job this villager no longer has. They can
            // never become valid again either, because a re-roll generates new
            // ones, so the entry goes rather than merely being skipped.
            repository.forgetCached(villager.getUUID());
            cached = null;
        }
        if (cached != null) {
            return new VillagerSnapshot(
                    villager.getId(),
                    groupId,
                    cached.level(),
                    VillagerSnapshot.OfferState.CACHED,
                    cached.rerollable()
                            ? VillagerSnapshot.LockState.REROLLABLE
                            : VillagerSnapshot.LockState.LOCKED,
                    cached.offers(),
                    OfferView.digest(cached.offers()));
        }

        // No offer data. Level is synced, villager xp is not, so anything past
        // novice is definitely locked and novices are genuinely ambiguous.
        VillagerSnapshot.LockState lock = level > VillagerData.MIN_VILLAGER_LEVEL
                ? VillagerSnapshot.LockState.LOCKED
                : VillagerSnapshot.LockState.AMBIGUOUS;

        return VillagerSnapshot.unknown(villager.getId(), groupId, level, lock);
    }

    private static int colourFor(VillagerSnapshot.LockState lock,
                                 VillagerSnapshot.OfferState offers,
                                 VtpConfig config) {
        if (offers == VillagerSnapshot.OfferState.UNKNOWN
                || lock == VillagerSnapshot.LockState.AMBIGUOUS) {
            return config.appearance.unknownArgb();
        }
        return lock == VillagerSnapshot.LockState.REROLLABLE
                ? config.appearance.rerollableArgb()
                : config.appearance.lockedArgb();
    }

    private HighlightEntry borrow(int index) {
        List<HighlightEntry> pool = buildIntoA ? poolA : poolB;
        while (pool.size() <= index) {
            pool.add(new HighlightEntry());
        }
        return pool.get(index);
    }

    private void clearBuffers() {
        active.clear();
        activeById.clear();
    }

    private void reset() {
        clearBuffers();
        building.clear();
        matchCache.clear();
        scratch.clear();
        lastLevel = null;
        tickCounter = 0;
        seenGeneration = -1;
        bucket = -1;
        cursor = 0;
    }

    /**
     * Drops one villager entirely, on a server Forget.
     *
     * <p>The rendered entry has to go too, not just the cached verdict. The
     * renderer reads {@link #activeById} every frame, and that index is only
     * rebuilt when a scan cycle finishes -- so dropping the offers and the match
     * alone left the old label on screen for up to a full cycle afterwards. With
     * the interval at 8 that is about 11 ticks, which is exactly long enough to
     * read as the previous trade flashing back when a job site is replaced.
     *
     * <p>Removing an entry mid-cycle leaves the stack offsets of any labels above
     * it one row high until the next cycle recomputes them. That is a far smaller
     * wrong than drawing trades the villager no longer has.
     */
    public void forget(int entityId) {
        matchCache.forget(entityId);

        HighlightEntry entry = activeById.remove(entityId);
        if (entry != null) {
            active.remove(entry);
        }
    }

    /**
     * The render path entry point: O(1) lookup by entity id, called from the
     * entity renderer during state extraction.
     */
    public HighlightEntry lookup(int entityId) {
        return activeById.get(entityId);
    }

    /** The renderer input. Never null; may be empty. */
    public List<HighlightEntry> entries() {
        return active;
    }

    public int lastScanned() {
        return lastScanned;
    }

    /** How many matches currently carry a label (capped by maxHighlighted). */
    public int lastLabelled() {
        return lastLabelled;
    }

    public int lastMatched() {
        return lastMatched;
    }

    /** Total time for the whole cycle, summed across its buckets. */
    public long lastScanMicros() {
        return lastCycleNanos / 1000L;
    }

    public int cacheHitRatePercent() {
        return matchCache.hitRatePercent();
    }

    public int cachedVillagers() {
        return matchCache.size();
    }

    /** True while a cycle is mid-flight, for the debug overlay. */
    public boolean scanning() {
        return bucket >= 0;
    }
}
