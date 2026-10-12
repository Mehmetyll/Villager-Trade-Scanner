package me.mehmetyl.vtp.mixin.client;

import me.mehmetyl.vtp.client.data.TradeScreenWatcher;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures merchant offers as they arrive.
 *
 * <p>Vanilla has no event for this packet, and it is the one moment a client on
 * an unmodded server is legitimately handed a villager's full trade list. Purely
 * observational -- it changes nothing about vanilla trading.
 *
 * <p>Injected <em>after</em> {@code ensureRunningOnSameThread}, which is the very
 * first instruction of the handler. At HEAD this ran on the netty thread, where
 * the call then throws {@code RunningOnDifferentThreadException} and reschedules
 * the packet -- so the body executed twice, and the first time it mutated the
 * offer store off the client thread while the client tick was reading it.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(
            method = "handleMerchantOffers",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;"
                            + "ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;"
                            + "Lnet/minecraft/network/PacketListener;"
                            + "Lnet/minecraft/network/PacketProcessor;)V",
                    shift = At.Shift.AFTER))
    private void vtp$captureMerchantOffers(ClientboundMerchantOffersPacket packet, CallbackInfo ci) {
        TradeScreenWatcher.onMerchantOffers(packet);
    }
}
