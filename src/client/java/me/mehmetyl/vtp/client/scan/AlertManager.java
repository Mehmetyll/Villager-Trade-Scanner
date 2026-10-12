package me.mehmetyl.vtp.client.scan;

import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import me.mehmetyl.vtp.client.VtpClientMod;
import me.mehmetyl.vtp.client.config.VtpConfig;
import me.mehmetyl.vtp.client.data.HighlightEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.List;

/**
 * Plays a sound and/or posts a chat line when a new matching villager appears.
 *
 * <p>"New" means what it says: a villager that was not in the previous scan's
 * match set. This runs on every client tick against a front buffer that persists
 * between scan cycles, so without that comparison a villager the player is simply
 * standing next to re-announced itself every time its cooldown lapsed.
 *
 * <p>Alerts are also rate limited per villager, so walking the length of a
 * trading hall cannot produce a wall of chat or a machine-gun of dings.
 */
public final class AlertManager {
    /** How often expired cooldown entries are swept, in ticks. */
    private static final int PRUNE_INTERVAL_TICKS = 256;

    /** entity id -> game time when it may alert again. */
    private final Int2LongMap cooldowns = new Int2LongOpenHashMap();

    /** Match sets for the previous and current tick; swapped, never reallocated. */
    private IntOpenHashSet previous = new IntOpenHashSet();
    private IntOpenHashSet current = new IntOpenHashSet();

    private long ticks;

    public void onScanComplete(Minecraft minecraft, List<HighlightEntry> matches) {
        VtpConfig.Alerts alerts = VtpClientMod.config().alerts;
        ticks++;

        current.clear();
        for (HighlightEntry entry : matches) {
            current.add(entry.entityId);
        }

        if (minecraft.player == null || (!alerts.soundOnNewMatch && !alerts.chatOnNewMatch)) {
            // Still swap, so turning alerts back on does not announce the whole
            // village as though it had just appeared.
            swap();
            return;
        }

        boolean playedSoundThisScan = false;

        for (HighlightEntry entry : matches) {
            // Already matching a tick ago: not news.
            if (previous.contains(entry.entityId)) {
                continue;
            }

            // The renderer draws nothing for an occluded villager, so announcing
            // one would point at something the player cannot see.
            if (entry.occluded) {
                continue;
            }

            long readyAt = cooldowns.get(entry.entityId);
            if (readyAt != 0L && ticks < readyAt) {
                continue;
            }
            cooldowns.put(entry.entityId, ticks + alerts.perVillagerCooldownTicks);

            // At most one sound per scan no matter how many new villagers appeared.
            if (alerts.soundOnNewMatch && !playedSoundThisScan) {
                playedSoundThisScan = true;
                minecraft.player.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.5f, 1.6f);
            }

            if (alerts.chatOnNewMatch) {
                // The first match is the highest-priority rule, which is the one
                // drawn nearest the villager's head.
                String name = entry.rowCount() == 0 ? "" : entry.matches[0].label();
                Component message = Component.translatable("vtp.message.match_found",
                        name == null ? "" : name);
                // 26 added sendSystemMessage; before that the client player takes
                // an extra flag saying whether the message is an action-bar one.
                //? if >=26.1 {
                minecraft.player.sendSystemMessage(message);
                //?} else {
                /*minecraft.player.displayClientMessage(message, false);
                *///?}
            }
        }

        pruneExpired();
        swap();
    }

    /**
     * Drops cooldowns that have already lapsed.
     *
     * <p>Without this the map keeps an entry for every entity id ever matched,
     * for the whole session. An expired entry is indistinguishable from an absent
     * one, so removing it changes nothing a player can observe.
     */
    private void pruneExpired() {
        if (ticks % PRUNE_INTERVAL_TICKS != 0 || cooldowns.isEmpty()) {
            return;
        }
        ObjectIterator<Int2LongMap.Entry> it = cooldowns.int2LongEntrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getLongValue() <= ticks) {
                it.remove();
            }
        }
    }

    private void swap() {
        IntOpenHashSet spent = previous;
        previous = current;
        current = spent;
    }

    /** Drops cooldowns on world change so a returning player is alerted again. */
    public void clear() {
        cooldowns.clear();
        previous.clear();
        current.clear();
        ticks = 0;
    }
}
