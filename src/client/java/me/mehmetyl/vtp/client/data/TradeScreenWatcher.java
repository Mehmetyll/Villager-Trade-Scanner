package me.mehmetyl.vtp.client.data;

import me.mehmetyl.vtp.client.VtpClientMod;
import me.mehmetyl.vtp.compat.OfferView;
import me.mehmetyl.vtp.compat.VillagerAccess;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;

/**
 * The vanilla-server fallback: learn offers from the trade screen.
 *
 * <p>When any merchant GUI opens, the server sends
 * {@link ClientboundMerchantOffersPacket} with the complete offer list and the
 * villager's xp -- on <em>every</em> server, modded or not. That is exact data we
 * are entitled to, and it also resolves the lock state that level alone cannot
 * (see {@link VillagerSnapshot.LockState#AMBIGUOUS}).
 *
 * <p>The packet identifies a container, not an entity, so we remember which
 * merchant the player last interacted with and bind the two together.
 */
public final class TradeScreenWatcher {
    /** Ticks within which an offers packet is attributed to the last interaction. */
    private static final long BIND_WINDOW_MILLIS = 3000L;

    private static int lastMerchantEntityId = -1;
    private static long lastInteractionMillis;

    private TradeScreenWatcher() {
    }

    public static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            // Fires on both sides; we only care about the client's own view.
            if (level.isClientSide() && entity instanceof AbstractVillager) {
                lastMerchantEntityId = entity.getId();
                lastInteractionMillis = System.currentTimeMillis();
            }
            return InteractionResult.PASS;
        });
    }

    /**
     * Called from the client packet-listener mixin when merchant offers arrive.
     * Must not throw: it runs on the packet handler path.
     */
    public static void onMerchantOffers(ClientboundMerchantOffersPacket packet) {
        try {
            Minecraft client = Minecraft.getInstance();
            // The injection point already guarantees this, but the offer store is
            // a plain hash map read by the client tick -- cheap insurance against
            // ever being called from anywhere else.
            if (!client.isSameThread()) {
                return;
            }

            if (lastMerchantEntityId < 0
                    || System.currentTimeMillis() - lastInteractionMillis > BIND_WINDOW_MILLIS) {
                return;
            }

            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return;
            }

            Entity entity = minecraft.level.getEntity(lastMerchantEntityId);
            if (!(entity instanceof AbstractVillager villager)) {
                return;
            }

            OfferView[] offers = OfferView.of(packet.getOffers());
            int level = packet.getVillagerLevel();

            // Exactly the vanilla ResetProfession condition, now computable because
            // the packet carries the xp the entity never syncs.
            boolean rerollable = villager instanceof Villager
                    && packet.getVillagerXp() == 0
                    && level <= VillagerData.MIN_VILLAGER_LEVEL;

            OfferRepository repository = VtpClientMod.offers();
            repository.cacheFromTradeScreen(villager.getUUID(),
                    VillagerAccess.groupId(villager), offers, level, rerollable);
            repository.put(new VillagerSnapshot(
                    villager.getId(),
                    VillagerAccess.groupId(villager),
                    level,
                    VillagerSnapshot.OfferState.KNOWN,
                    rerollable
                            ? VillagerSnapshot.LockState.REROLLABLE
                            : VillagerSnapshot.LockState.LOCKED,
                    offers,
                    OfferView.digest(offers)));
        } catch (Exception e) {
            // Never let a fallback path break trading itself.
            me.mehmetyl.vtp.VtpMod.LOGGER.error("Failed to record offers from trade screen", e);
        }
    }
}
