package me.mehmetyl.vtp.net;

import me.mehmetyl.vtp.VtpConstants;
import me.mehmetyl.vtp.VtpMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side networking: payload registration, the handshake, and the scan tick.
 *
 * <p>Runs identically on the integrated server (singleplayer and LAN host) and on
 * a dedicated server. There is no separate singleplayer path -- see Stage 1.
 */
public final class VtpServerNet {
    /** Hard ceiling on what a client may ask to be tracked, in blocks. */
    public static final int MAX_RADIUS = 64;

    /** Minimum ticks between scans, enforced regardless of client preference. */
    public static final int MIN_INTERVAL_TICKS = 5;

    /** Offer updates sent to one player per scan. */
    public static final int MAX_PER_UPDATE = 16;

    /** Upper bound on entities examined per player per scan. */
    public static final int MAX_SCAN_ENTITIES = 1024;

    private static final int SCAN_INTERVAL_TICKS = 10;

    /**
     * Sessions for players that completed the handshake. Concurrent because
     * disconnect handling can run off the main tick thread.
     */
    private static final Map<UUID, OfferTracker> SESSIONS = new ConcurrentHashMap<>();

    private static boolean scanErrorLogged;

    private VtpServerNet() {
    }

    public static void register() {
        // Both directions must be registered on both sides for the codec to exist;
        // registering the S2C types here is what makes ClientPlayNetworking.canSend
        // report true for a modded client.
        // Fabric API renamed these accessors; playC2S/playS2C are the older names.
        // Verified with javap against fabric-networking-api-v1 for each target.
        //? if >=26.1 {
        PayloadTypeRegistry.serverboundPlay().register(VtpPayloads.Hello.TYPE, VtpPayloads.Hello.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(VtpPayloads.Prefs.TYPE, VtpPayloads.Prefs.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(VtpPayloads.ServerHello.TYPE, VtpPayloads.ServerHello.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(VtpPayloads.OfferUpdate.TYPE, VtpPayloads.OfferUpdate.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(VtpPayloads.Forget.TYPE, VtpPayloads.Forget.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(VtpPayloads.TradeCatalog.TYPE, VtpPayloads.TradeCatalog.CODEC);
        //?} else {
        /*PayloadTypeRegistry.playC2S().register(VtpPayloads.Hello.TYPE, VtpPayloads.Hello.CODEC);
        PayloadTypeRegistry.playC2S().register(VtpPayloads.Prefs.TYPE, VtpPayloads.Prefs.CODEC);
        PayloadTypeRegistry.playS2C().register(VtpPayloads.ServerHello.TYPE, VtpPayloads.ServerHello.CODEC);
        PayloadTypeRegistry.playS2C().register(VtpPayloads.OfferUpdate.TYPE, VtpPayloads.OfferUpdate.CODEC);
        PayloadTypeRegistry.playS2C().register(VtpPayloads.Forget.TYPE, VtpPayloads.Forget.CODEC);
        PayloadTypeRegistry.playS2C().register(VtpPayloads.TradeCatalog.TYPE, VtpPayloads.TradeCatalog.CODEC);
        *///?}

        ServerPlayNetworking.registerGlobalReceiver(VtpPayloads.Hello.TYPE, VtpServerNet::onHello);
        ServerPlayNetworking.registerGlobalReceiver(VtpPayloads.Prefs.TYPE, VtpServerNet::onPrefs);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                discard(handler.getPlayer()));

        ServerTickEvents.END_SERVER_TICK.register(VtpServerNet::onServerTick);

        // The catalogue is built from a worldgen registry and cached statically,
        // so it has to be dropped whenever those definitions could have changed:
        // a datapack reload, or -- in singleplayer, where the client and the
        // integrated server share a JVM -- loading a different world.
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(
                (server, resourceManager, success) -> TradeCatalogBuilder.invalidate());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            TradeCatalogBuilder.invalidate();
            SESSIONS.clear();
        });

        // Offline catalogue generation; see TradeCatalogBuilder. Never active
        // unless the property is set, and the code does not exist from 26 on.
        //? if <26.1 {
        /*if (TradeCatalogDump.dumpRequested()) {
            ServerLifecycleEvents.SERVER_STARTED.register(TradeCatalogDump::dump);
        }
        *///?}

        VtpMod.LOGGER.info("Server networking registered (protocol v{})", VtpConstants.PROTOCOL_VERSION);
    }

    private static void onHello(VtpPayloads.Hello payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();

        if (payload.protocolVersion() != VtpConstants.PROTOCOL_VERSION) {
            // Version mismatch is not an error: the client stays in vanilla mode
            // and we simply never open a session for it.
            VtpMod.LOGGER.info("{} speaks protocol v{}, server speaks v{}; no preview session",
                    player.getGameProfile().name(), payload.protocolVersion(), VtpConstants.PROTOCOL_VERSION);
            return;
        }

        // A second hello would reset the digest map -- forcing a full re-send of
        // every nearby villager -- and re-transmit the whole catalogue. One
        // handshake per connection is all the protocol needs.
        if (SESSIONS.putIfAbsent(player.getUUID(),
                new OfferTracker(player.getUUID(), MAX_RADIUS)) != null) {
            return;
        }

        ServerPlayNetworking.send(player, new VtpPayloads.ServerHello(
                VtpConstants.PROTOCOL_VERSION, MAX_RADIUS, MIN_INTERVAL_TICKS, MAX_PER_UPDATE));

        // Sent once: the client cannot read trade definitions itself, so this is
        // what lets the filter UI list a profession's items before the player has
        // ever met one.
        ServerPlayNetworking.send(player, new VtpPayloads.TradeCatalog(
                TradeCatalogBuilder.get(context.server())));
    }

    private static void onPrefs(VtpPayloads.Prefs payload, ServerPlayNetworking.Context context) {
        OfferTracker tracker = SESSIONS.get(context.player().getUUID());
        if (tracker != null) {
            // Clamping happens inside applyPrefs; nothing the client sends is
            // trusted verbatim.
            tracker.applyPrefs(payload.desiredRadius(), payload.enabled());
        }
    }

    private static void onServerTick(net.minecraft.server.MinecraftServer server) {
        if (SESSIONS.isEmpty()) {
            return;
        }

        int tick = server.getTickCount();

        for (Map.Entry<UUID, OfferTracker> entry : SESSIONS.entrySet()) {
            // Spread the players across the interval instead of scanning every
            // session on the same tick, which concentrated the whole cost into
            // one tick in ten.
            if (Math.floorMod(tick + entry.getKey().hashCode(), SCAN_INTERVAL_TICKS) != 0) {
                continue;
            }

            try {
                entry.getValue().tick(server);
            } catch (Exception e) {
                // Caught per player: one failing session must not skip the scan
                // for everybody after it in the map. Logged once so a recurring
                // fault does not fill the log every 10 ticks.
                if (!scanErrorLogged) {
                    scanErrorLogged = true;
                    VtpMod.LOGGER.error("Offer scan failed; further errors suppressed", e);
                }
            }
        }
    }

    private static void discard(ServerPlayer player) {
        if (player == null) {
            return;
        }
        OfferTracker tracker = SESSIONS.remove(player.getUUID());
        if (tracker != null) {
            tracker.discard();
        }
    }
}
