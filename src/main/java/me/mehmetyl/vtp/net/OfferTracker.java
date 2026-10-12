package me.mehmetyl.vtp.net;

import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.mehmetyl.vtp.compat.VillagerAccess;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-player server-side offer tracking.
 *
 * <p>One instance per connected player that completed the handshake. Runs on the
 * server thread only, which is what makes reading a villager's live
 * {@code MerchantOffers} safe.
 */
public final class OfferTracker {
    /** Amplifier sentinel for "no Hero of the Village"; real ones start at 0. */
    private static final int NO_HERO = -1;

    /**
     * Identifies the player by UUID rather than by reference.
     *
     * <p>{@code PlayerList.respawn} builds a <em>new</em> {@code ServerPlayer} on
     * death and fires no disconnect event, so a cached instance goes stale the
     * first time the player dies -- silently stopping tracking for the rest of
     * the session. Resolving through the player list every scan is immune to
     * that, and to dimension changes.
     */
    private final UUID uuid;

    /** entity id -> digest of the offers we last sent this player. */
    private final Int2LongMap sentDigests = new Int2LongOpenHashMap();

    /** Reused across scans so a steady state allocates nothing. */
    private final List<AbstractVillager> scratch = new ArrayList<>();
    private final IntOpenHashSet seenThisScan = new IntOpenHashSet();

    /**
     * Villagers still standing here whose offers went away, which is what a
     * broken job site looks like. Kept apart from {@link #seenThisScan} because
     * the forget pass has to treat the two differently.
     */
    private final IntOpenHashSet lostOffers = new IntOpenHashSet();

    private final IntArrayList toForget = new IntArrayList();

    private int radius;
    private boolean enabled = true;

    public OfferTracker(UUID uuid, int radius) {
        this.uuid = uuid;
        this.radius = radius;
        this.sentDigests.defaultReturnValue(Long.MIN_VALUE);
    }

    public void applyPrefs(int desiredRadius, boolean enabled) {
        // The client asks; the server decides. Anything outside our own limits is
        // clamped rather than honoured, and nothing here can widen what the
        // tracking gate below already allows.
        this.radius = Math.clamp(desiredRadius, 8, VtpServerNet.MAX_RADIUS);
        this.enabled = enabled;

        if (!enabled) {
            // Stop tracking immediately; the client drops its store on its side.
            sentDigests.clear();
        }
    }

    /**
     * One scan pass. Sends at most {@link VtpServerNet#MAX_PER_UPDATE} offer
     * updates, so a player walking into a trading hall drains over several scans
     * instead of producing one huge burst.
     */
    public void tick(MinecraftServer server) {
        if (!enabled) {
            return;
        }

        // Re-resolved every scan; see the note on the uuid field.
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null || !player.isAlive()) {
            return;
        }

        // level() only narrowed to ServerLevel in 1.21.6; the cast is a no-op
        // from there on and the downcast is always sound for a ServerPlayer.
        ServerLevel level = (ServerLevel) player.level();
        double r = radius;
        AABB box = player.getBoundingBox().inflate(r);
        double radiusSq = r * r;

        scratch.clear();
        seenThisScan.clear();
        lostOffers.clear();

        // The limit is a hard stop inside the entity query itself, so a pathological
        // village cannot make this scan unbounded.
        level.getEntities(
                EntityTypeTest.forClass(AbstractVillager.class),
                box,
                villager -> villager.isAlive()
                        && villager.distanceToSqr(player) <= radiusSq,
                scratch,
                VtpServerNet.MAX_SCAN_ENTITIES);

        int sent = 0;
        for (AbstractVillager villager : scratch) {
            int id = villager.getId();

            if (sent >= VtpServerNet.MAX_PER_UPDATE) {
                // Budget spent. Mark it seen so it is not mistaken for gone, and
                // let it keep its previous digest: it is picked up on the next
                // scan rather than being lost.
                seenThisScan.add(id);
                continue;
            }

            // Only ever send what this player already tracks: never reveal a
            // villager they could not have clicked on anyway.
            if (!PlayerLookup.tracking(villager).contains(player)) {
                continue;
            }

            // Reads the field directly -- calling getOffers() here would generate
            // trades for villagers that have never been traded with.
            MerchantOffers offers = VillagerAccess.peekOffers(villager);
            if (offers == null || offers.isEmpty()) {
                // A broken job site takes the villager's profession and its
                // offers with it. Recorded rather than merely left out of
                // seenThisScan: the forget pass confirms absences against the
                // world, and this villager is standing right there, so without
                // being told why it is here it would decide nothing is wrong and
                // leave the client matching against trades that no longer exist.
                lostOffers.add(id);
                continue;
            }

            seenThisScan.add(id);

            boolean rerollable = villager instanceof Villager v && VillagerAccess.isRerollable(v);
            int level0 = villager instanceof Villager v2 ? VillagerAccess.level(v2) : 0;

            // Vanilla only works the player-specific discount out when the trade
            // screen opens, so these are the inputs to it rather than the result.
            // Folded into the digest so gaining or losing the effect, or a shift
            // in reputation, actually re-sends instead of hiding behind an
            // otherwise unchanged offer list.
            int reputation = villager instanceof Villager v3 ? v3.getPlayerReputation(player) : 0;
            int heroAmplifier = villager instanceof Villager ? heroAmplifier(player) : NO_HERO;

            long digest = digestOf(offers, level0, rerollable, reputation, heroAmplifier);
            if (sentDigests.get(id) == digest) {
                continue;
            }

            // copy() so the player's packet cannot observe later mutation of the
            // live list while it sits in the send queue -- and, because it is a
            // deep copy, so the projected discount below lands on the packet
            // rather than on the villager everyone else is looking at.
            MerchantOffers outgoing = offers.copy();
            applyProjectedPrices(outgoing, reputation, heroAmplifier);

            ServerPlayNetworking.send(player,
                    new VtpPayloads.OfferUpdate(id, outgoing, level0, rerollable));

            sentDigests.put(id, digest);
            sent++;
        }

        forgetVanished(player, level, radiusSq);
    }

    /** Tells the client to drop villagers that left the radius or died. */
    private void forgetVanished(ServerPlayer player, ServerLevel level, double radiusSq) {
        toForget.clear();

        for (Int2LongMap.Entry entry : sentDigests.int2LongEntrySet()) {
            int id = entry.getIntKey();
            if (seenThisScan.contains(id)) {
                continue;
            }

            // Absence from the scan is not proof the villager is gone: the entity
            // query is capped, so a dense trading hall can push villagers out of a
            // single pass. Confirm against the world before telling the client to
            // drop it, otherwise crowded halls flicker.
            //
            // Except when we know why it is missing. A villager whose offers went
            // away is alive and nearby by definition, so the world check would
            // always keep it and the client would go on drawing the trades it
            // just lost.
            if (!lostOffers.contains(id)) {
                Entity entity = level.getEntity(id);
                if (entity != null && entity.isAlive() && entity.distanceToSqr(player) <= radiusSq) {
                    continue;
                }
            }

            toForget.add(id);
        }

        IntIterator it = toForget.iterator();
        while (it.hasNext()) {
            int id = it.nextInt();
            sentDigests.remove(id);
            ServerPlayNetworking.send(player, new VtpPayloads.Forget(id));
        }
    }

    /**
     * Folds a villager's offers into a value that changes when they do.
     *
     * <p>Computed straight from the stacks. Building an {@code OfferView[]} for
     * this meant a fresh {@code String} per item, per offer, per villager, on
     * every scan -- overwhelmingly to conclude that nothing had changed and send
     * nothing. {@code hashItemAndComponents} covers the data components, which is
     * what makes two enchanted books with different stored enchantments differ;
     * the count is folded in separately because that hash ignores stack size, and
     * an adjusted price is exactly a change in count.
     */
    private static long digestOf(MerchantOffers offers, int level, boolean rerollable,
                                 int reputation, int heroAmplifier) {
        long hash = 0xcbf29ce484222325L;

        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            hash = stack(hash, offer.getResult());
            // Base cost plus demand, never getCostA(): that folds in the live
            // special-price diff, which vanilla writes only while a trade screen
            // is open. Hashing it made simply opening and closing a villager look
            // like a change and re-send the offer twice for nothing. Everything
            // that actually determines the price this player sees is here or in
            // the reputation and effect folded in below.
            hash = stack(hash, offer.getBaseCostA());
            hash = mix(hash, offer.getDemand());
            hash = stack(hash, offer.getCostB());
            hash = mix(hash, offer.getUses());
            hash = mix(hash, offer.getMaxUses());
        }

        hash = mix(hash, level);
        hash = mix(hash, rerollable ? 1 : 0);
        hash = mix(hash, reputation);
        return mix(hash, heroAmplifier);
    }

    /**
     * Applies the discount this player would get, to a copy of the offers.
     *
     * <p>Mirrors vanilla's {@code Villager.updateSpecialPrices}, which only runs
     * when a trade screen is opened and mutates the villager's own offers as it
     * goes. Doing the same arithmetic here, on the copy already being sent, is
     * what lets a Hero of the Village see their price before clicking anything --
     * without changing what any other player sees.
     */
    private static void applyProjectedPrices(MerchantOffers offers, int reputation, int heroAmplifier) {
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            offer.setSpecialPriceDiff(projectedDiff(offer, reputation, heroAmplifier));
        }
    }

    /**
     * The special-price adjustment this player should see, as an absolute value.
     *
     * <p>Deliberately <em>set</em>, not added. While a trade screen is open,
     * vanilla has already written its own copy of this same adjustment onto the
     * live offers, and adding ours on top doubled the discount: a book at 16
     * showed 12 until the player clicked it and then 8, while the trade screen
     * beside it still said 12. Computing the absolute value makes the projection
     * idempotent, so it is correct whether or not vanilla got there first.
     */
    private static int projectedDiff(MerchantOffer offer, int reputation, int heroAmplifier) {
        int diff = 0;

        if (reputation != 0) {
            diff -= Mth.floor(reputation * offer.getPriceMultiplier());
        }

        if (heroAmplifier != NO_HERO) {
            double share = 0.3 + 0.0625 * heroAmplifier;
            int cut = (int) Math.floor(share * offer.getBaseCostA().getCount());
            diff -= Math.max(cut, 1);
        }

        return diff;
    }

    /** Hero of the Village amplifier, or {@link #NO_HERO} when the player lacks it. */
    private static int heroAmplifier(ServerPlayer player) {
        MobEffectInstance effect = player.getEffect(MobEffects.HERO_OF_THE_VILLAGE);
        return effect == null ? NO_HERO : effect.getAmplifier();
    }

    private static long stack(long hash, ItemStack stack) {
        hash = mix(hash, ItemStack.hashItemAndComponents(stack));
        return mix(hash, stack.getCount());
    }

    private static long mix(long hash, int value) {
        hash ^= value;
        hash *= 0x100000001b3L;
        return hash;
    }

    public void discard() {
        sentDigests.clear();
        scratch.clear();
    }
}
