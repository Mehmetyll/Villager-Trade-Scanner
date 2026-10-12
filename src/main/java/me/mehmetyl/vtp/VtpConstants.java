package me.mehmetyl.vtp;

import net.minecraft.resources.Identifier;

/**
 * Constants shared by both source sets.
 *
 * <p>This lives in the {@code main} source set because the server-side offer
 * tracker (Stage 3) needs the same mod id and protocol version as the client.
 */
public final class VtpConstants {
    public static final String MOD_ID = "villager_trade_scanner";

    /**
     * Wire format version for the custom payloads added in Stage 3.
     *
     * <p>Bumped only when the payload layout changes incompatibly. A bump means
     * a new payload id, never a silent reinterpretation of the old one, so an
     * old client and a new server simply fail the handshake and fall back to
     * vanilla mode instead of misreading each other.
     */
    public static final int PROTOCOL_VERSION = 2;

    private VtpConstants() {
    }

    /** Builds an {@code villager_trade_scanner:path} identifier. */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
