package me.mehmetyl.vtp.net;

import me.mehmetyl.vtp.VtpConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * The wire protocol.
 *
 * <p>Payload ids carry the protocol version ({@code .../offers_v1}). A future
 * incompatible change registers a <em>new</em> id rather than reinterpreting an
 * old one, so a mismatched client and server simply never exchange that packet
 * instead of misreading each other's bytes.
 *
 * <p>Offer lists reuse vanilla's {@link MerchantOffers#STREAM_CODEC} rather than
 * a hand-rolled format. That keeps item components (notably an enchanted book's
 * stored enchantments) exactly as the server sees them, with no bespoke
 * serialisation to get wrong.
 */
public final class VtpPayloads {
    private static final String SUFFIX = "_v" + VtpConstants.PROTOCOL_VERSION;

    private VtpPayloads() {
    }

    /** C2S: sent on join to ask whether the server speaks our protocol. */
    public record Hello(int protocolVersion) implements CustomPacketPayload {
        public static final Type<Hello> TYPE =
                new CustomPacketPayload.Type<>(VtpConstants.id("hello" + SUFFIX));

        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Hello::protocolVersion,
                        Hello::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S2C: the server's answer, including the limits it will enforce. The client
     * treats these as authoritative and never sends requests outside them.
     */
    public record ServerHello(int acceptedVersion, int maxRadius, int minIntervalTicks, int maxPerUpdate)
            implements CustomPacketPayload {
        public static final Type<ServerHello> TYPE =
                new CustomPacketPayload.Type<>(VtpConstants.id("server_hello" + SUFFIX));

        public static final StreamCodec<RegistryFriendlyByteBuf, ServerHello> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ServerHello::acceptedVersion,
                        ByteBufCodecs.VAR_INT, ServerHello::maxRadius,
                        ByteBufCodecs.VAR_INT, ServerHello::minIntervalTicks,
                        ByteBufCodecs.VAR_INT, ServerHello::maxPerUpdate,
                        ServerHello::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C2S: the client's preferences. These are requests, not commands -- the
     * server clamps the radius to its own maximum and ignores anything else.
     */
    public record Prefs(int desiredRadius, boolean enabled) implements CustomPacketPayload {
        public static final Type<Prefs> TYPE =
                new CustomPacketPayload.Type<>(VtpConstants.id("prefs" + SUFFIX));

        public static final StreamCodec<RegistryFriendlyByteBuf, Prefs> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Prefs::desiredRadius,
                        ByteBufCodecs.BOOL, Prefs::enabled,
                        Prefs::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S2C: one villager's offers changed (or is being sent for the first time).
     *
     * @param rerollable computed server-side from villager xp and level, so the
     *                   client never has to guess the lock state on a modded server
     */
    public record OfferUpdate(int entityId, MerchantOffers offers, int level, boolean rerollable)
            implements CustomPacketPayload {
        public static final Type<OfferUpdate> TYPE =
                new CustomPacketPayload.Type<>(VtpConstants.id("offers" + SUFFIX));

        public static final StreamCodec<RegistryFriendlyByteBuf, OfferUpdate> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, OfferUpdate::entityId,
                        MerchantOffers.STREAM_CODEC, OfferUpdate::offers,
                        ByteBufCodecs.VAR_INT, OfferUpdate::level,
                        ByteBufCodecs.BOOL, OfferUpdate::rerollable,
                        OfferUpdate::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * One trade a profession can offer: what it gives, and what it wants for it.
     *
     * <p>The cost item is what lets the filter UI label a price field with the
     * actual currency -- "Max price (Rotten Flesh)" rather than assuming emeralds.
     *
     * <p>{@code costB} is the second item a trade can charge, which vanilla
     * models as {@code VillagerTrade.additionalWants}. Without it a fletcher's
     * gravel-and-emerald offer looked like it cost nothing but emeralds.
     */
    public record TradeEntry(String result, String cost, String costB) {
        public static final StreamCodec<RegistryFriendlyByteBuf, TradeEntry> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, TradeEntry::result,
                        ByteBufCodecs.STRING_UTF8, TradeEntry::cost,
                        ByteBufCodecs.STRING_UTF8, TradeEntry::costB,
                        TradeEntry::new);

        /** Absent costs travel as "" rather than null, which the codec rejects. */
        public static TradeEntry of(String result, String cost, String costB) {
            return new TradeEntry(result, cost == null ? "" : cost, costB == null ? "" : costB);
        }

        /** True when the trade charges a second, different item alongside the first. */
        public boolean hasCostB() {
            return costB != null && !costB.isBlank();
        }
    }

    /** One profession and every trade it is capable of offering. */
    public record ProfessionItems(String profession, java.util.List<TradeEntry> items) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ProfessionItems> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, ProfessionItems::profession,
                        TradeEntry.CODEC.apply(ByteBufCodecs.list()), ProfessionItems::items,
                        ProfessionItems::new);
    }

    /**
     * S2C: what each profession can sell, sent once on join.
     *
     * <p>Trade definitions are a worldgen registry the client never receives, so
     * without this the filter UI could only offer items it had already watched a
     * villager selling.
     */
    public record TradeCatalog(java.util.List<ProfessionItems> professions) implements CustomPacketPayload {
        public static final Type<TradeCatalog> TYPE =
                new CustomPacketPayload.Type<>(VtpConstants.id("trade_catalog" + SUFFIX));

        public static final StreamCodec<RegistryFriendlyByteBuf, TradeCatalog> CODEC =
                StreamCodec.composite(
                        ProfessionItems.CODEC.apply(ByteBufCodecs.list()), TradeCatalog::professions,
                        TradeCatalog::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: the villager left the tracked set; drop it from the client store. */
    public record Forget(int entityId) implements CustomPacketPayload {
        public static final Type<Forget> TYPE =
                new CustomPacketPayload.Type<>(VtpConstants.id("forget" + SUFFIX));

        public static final StreamCodec<RegistryFriendlyByteBuf, Forget> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Forget::entityId,
                        Forget::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
