package me.mehmetyl.vtp;

import me.mehmetyl.vtp.net.VtpServerNet;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint.
 *
 * <p>This runs on the integrated server in singleplayer and on a LAN host, and
 * on a dedicated server -- which is exactly why the architecture has a single
 * server-side path rather than a separate "read the entity directly" path for
 * singleplayer. See the Stage 1 proposal.
 */
public final class VtpMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("VillagerTradeScanner");

    @Override
    public void onInitialize() {
        VtpServerNet.register();
        LOGGER.info("Villager Trade Scanner initialised (protocol v{})", VtpConstants.PROTOCOL_VERSION);
    }
}
