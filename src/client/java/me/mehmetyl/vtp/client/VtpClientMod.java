package me.mehmetyl.vtp.client;

import me.mehmetyl.vtp.VtpMod;
import me.mehmetyl.vtp.client.config.ConfigIo;
import me.mehmetyl.vtp.client.config.VtpConfig;
import me.mehmetyl.vtp.client.data.OfferRepository;
import me.mehmetyl.vtp.client.data.TradeScreenWatcher;
import me.mehmetyl.vtp.client.net.VtpClientNet;
import me.mehmetyl.vtp.client.scan.AlertManager;
import me.mehmetyl.vtp.client.scan.VillagerScanner;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Client entrypoint and the single owner of the live config, offer store and
 * scanner.
 */
public final class VtpClientMod implements ClientModInitializer {
    private static VtpConfig config = new VtpConfig();
    private static final OfferRepository OFFERS = new OfferRepository();
    private static final me.mehmetyl.vtp.client.data.TradeCatalog CATALOG = new me.mehmetyl.vtp.client.data.TradeCatalog();
    private static final VillagerScanner SCANNER = new VillagerScanner();
    private static final AlertManager ALERTS = new AlertManager();

    /**
     * Incremented whenever the filters change. The match cache stores the
     * generation it was computed under and recomputes when it differs, which
     * makes "the player edited a rule" invalidation a single int compare instead
     * of a cache wipe.
     */
    private static int configGeneration;

    @Override
    public void onInitializeClient() {
        config = ConfigIo.load();

        VtpKeybinds.register();
        VtpClientNet.register();
        TradeScreenWatcher.register();

        // Cached icon models hold baked quads; drop them when Minecraft reloads
        // resources so stale geometry is never submitted.
        net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback.EVENT.register(
                me.mehmetyl.vtp.client.data.HighlightEntry::invalidateIcons);

        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            SCANNER.onClientTick(minecraft);
            ALERTS.onScanComplete(minecraft, SCANNER.entries());
        });

        VtpMod.LOGGER.info("Client ready ({} filter rule(s), preview {})",
                config.rules.size(), config.previewEnabled ? "on" : "off");
    }

    public static VtpConfig config() {
        return config;
    }

    public static OfferRepository offers() {
        return OFFERS;
    }

    public static me.mehmetyl.vtp.client.data.TradeCatalog catalog() {
        return CATALOG;
    }

    public static AlertManager alerts() {
        return ALERTS;
    }

    public static VillagerScanner scanner() {
        return SCANNER;
    }

    public static int configGeneration() {
        return configGeneration;
    }

    public static void bumpGeneration() {
        configGeneration++;
    }

    public static void save() {
        ConfigIo.save(config);
        // Radius and the master toggle are server-visible preferences.
        VtpClientNet.sendPrefs();
    }
}
