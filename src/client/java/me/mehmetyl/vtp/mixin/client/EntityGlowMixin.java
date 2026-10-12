package me.mehmetyl.vtp.mixin.client;

// The glow, for 1.21.8 and below.
//
// From 1.21.9 the entity render state carries an outlineColor field, and the
// renderer simply writes it. There is no such field before that: the outline
// pass only draws entities that report themselves as glowing, and takes the
// colour from getTeamColor. Both are overridden here.
//
// This is still client-only and still writes nothing back to the world -- it
// changes what two getters answer on this client, not the entity's state, so
// there is nothing to desync and no packet is sent.
//
// isCurrentlyGlowing runs for every entity, so the check leading it is ordered
// cheapest first: an instanceof, then two booleans already in memory, and only
// then the scan lookup.
//? if <1.21.9 {
/*import me.mehmetyl.vtp.client.VtpClientMod;
import me.mehmetyl.vtp.client.config.VtpConfig;
import me.mehmetyl.vtp.client.data.HighlightEntry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityGlowMixin {

    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
    private void vtp$forceGlow(CallbackInfoReturnable<Boolean> cir) {
        if (vtp$highlight() != null) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void vtp$glowColour(CallbackInfoReturnable<Integer> cir) {
        HighlightEntry entry = vtp$highlight();
        if (entry != null) {
            // The outline reads this as plain RGB; the configured colour carries
            // an alpha byte that would otherwise be taken as part of the red.
            cir.setReturnValue(entry.argb & 0xFFFFFF);
        }
    }

    private HighlightEntry vtp$highlight() {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof AbstractVillager)) {
            return null;
        }

        VtpConfig config = VtpClientMod.config();
        if (!config.previewEnabled || !config.appearance.glowEnabled) {
            return null;
        }

        // Occluded villagers are never highlighted: the outline pass draws
        // through walls, so glowing one would make this an X-ray for finding
        // hidden bases. Same rule the renderer applies.
        HighlightEntry entry = VtpClientMod.scanner().lookup(self.getId());
        return entry != null && !entry.occluded ? entry : null;
    }
}
*///?}
