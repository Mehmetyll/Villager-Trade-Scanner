package me.mehmetyl.vtp.client.net;

import me.mehmetyl.vtp.VtpConstants;
import me.mehmetyl.vtp.VtpMod;
import me.mehmetyl.vtp.client.VtpClientMod;
import me.mehmetyl.vtp.client.data.VillagerSnapshot;
import me.mehmetyl.vtp.compat.OfferView;
import me.mehmetyl.vtp.compat.VillagerAccess;
import me.mehmetyl.vtp.net.VtpPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;

/**
 * Client-side networking and the vanilla-server fallback.
 *
 * <p>Three outcomes after joining a world:
 * <ul>
 *   <li><b>modded server</b> (including singleplayer and LAN, where our own
 *       integrated server answers) -- handshake succeeds, offers stream in;</li>
 *   <li><b>vanilla server</b> -- the channel is absent, so we stay in cache-only
 *       mode;</li>
 *   <li><b>version mismatch</b> -- the server ignores our hello, which lands us in
 *       the same cache-only mode.</li>
 * </ul>
 *
 * <p>None of the three says anything in chat. Falling back is normal operation,
 * not a problem the player needs telling about, and a mod that greets you on
 * every join is a mod you notice for the wrong reason. The log line on a
 * successful handshake is enough to diagnose from.
 */
public final class VtpClientNet {
    private static boolean sessionActive;

    private static int serverMaxRadius = 48;

    private VtpClientNet() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(VtpPayloads.ServerHello.TYPE, VtpClientNet::onServerHello);
        ClientPlayNetworking.registerGlobalReceiver(VtpPayloads.OfferUpdate.TYPE, VtpClientNet::onOfferUpdate);
        ClientPlayNetworking.registerGlobalReceiver(VtpPayloads.Forget.TYPE, VtpClientNet::onForget);
        ClientPlayNetworking.registerGlobalReceiver(VtpPayloads.TradeCatalog.TYPE,
                (payload, context) -> VtpClientMod.catalog().replace(payload.professions()));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect());
    }

    private static void onJoin() {
        sessionActive = false;
        VtpClientMod.offers().clearLive();

        // canSend() is only meaningful once the server has advertised its
        // channels, which has happened by the time JOIN fires.
        if (ClientPlayNetworking.canSend(VtpPayloads.Hello.TYPE)) {
            ClientPlayNetworking.send(new VtpPayloads.Hello(VtpConstants.PROTOCOL_VERSION));
        }
    }

    private static void onDisconnect() {
        sessionActive = false;
        VtpClientMod.alerts().clear();
        VtpClientMod.catalog().clear();
        // Live entries are per-connection; the UUID cache is kept on purpose so a
        // reconnect does not lose everything the player already looked at.
        VtpClientMod.offers().clearLive();
    }

    private static void onServerHello(VtpPayloads.ServerHello payload, ClientPlayNetworking.Context context) {
        sessionActive = true;
        serverMaxRadius = payload.maxRadius();

        VtpMod.LOGGER.info("Preview session established (server protocol v{}, max radius {})",
                payload.acceptedVersion(), payload.maxRadius());

        sendPrefs();
    }

    /** Tells the server what we want; it clamps to its own limits regardless. */
    public static void sendPrefs() {
        if (!sessionActive || !ClientPlayNetworking.canSend(VtpPayloads.Prefs.TYPE)) {
            return;
        }
        var config = VtpClientMod.config();
        ClientPlayNetworking.send(new VtpPayloads.Prefs(
                Math.min(config.general.scanRadius, serverMaxRadius),
                config.previewEnabled));
    }

    private static void onOfferUpdate(VtpPayloads.OfferUpdate payload, ClientPlayNetworking.Context context) {
        Entity entity = context.client().level == null ? null : context.client().level.getEntity(payload.entityId());

        // A merchant we cannot resolve yet is not a wandering trader -- treating
        // it as one used to store the wrong profession group, which then failed
        // every profession rule and wrongly satisfied wandering-trader ones. The
        // server re-sends on the next change, and the scanner resolves the group
        // from the live entity anyway, so dropping this packet costs nothing.
        if (!(entity instanceof AbstractVillager merchant)) {
            return;
        }

        String groupId = VillagerAccess.groupId(merchant);

        OfferView[] offers = OfferView.of(payload.offers());

        VtpClientMod.offers().put(new VillagerSnapshot(
                payload.entityId(),
                groupId,
                payload.level(),
                VillagerSnapshot.OfferState.KNOWN,
                payload.rerollable()
                        ? VillagerSnapshot.LockState.REROLLABLE
                        : VillagerSnapshot.LockState.LOCKED,
                offers,
                OfferView.digest(offers)));
    }

    private static void onForget(VtpPayloads.Forget payload, ClientPlayNetworking.Context context) {
        VtpClientMod.offers().forget(payload.entityId());
        // The match cache is keyed by the same entity id; without this it kept a
        // verdict for a villager the store no longer knows anything about.
        VtpClientMod.scanner().forget(payload.entityId());

        // And the trades remembered from a trade screen, which are keyed by UUID
        // and outlive everything else on purpose.
        //
        // Dropping the live entry alone let the very next scan fall straight
        // through to that memory and resolve the villager as CACHED, carrying
        // the trades it had before its job site was broken -- the same label,
        // right back. The profession does not change when a lectern is replaced,
        // so nothing keyed on the profession catches it either.
        //
        // A server that sends Forget is authoritative about this villager, so
        // remembered trades cannot be more right than it is. A vanilla server
        // sends none at all, and that is where this memory is the whole point,
        // so it is left alone there.
        Entity entity = context.client().level == null
                ? null
                : context.client().level.getEntity(payload.entityId());
        if (entity != null) {
            VtpClientMod.offers().forgetCached(entity.getUUID());
        }
    }

}
