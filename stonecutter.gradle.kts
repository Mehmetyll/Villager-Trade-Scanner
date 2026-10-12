plugins {
    id("dev.kikugie.stonecutter")
}

// The version the shared source in src/ is currently written against. Run
// "Reset active project" before committing so the tree is always stored in this
// state and diffs stay readable.
stonecutter active "26.2"

// Renames between the 26 line and 1.21.x that are one-for-one substitutions.
// The shared source is written in 26 form; these rewrite it going backwards.
// Every name here was read out of the target's own jar with javap, not guessed.
// Fully-qualified forms come first, because the package moved as well as the name.
stonecutter parameters {
    replacements {
        string(current.parsed < "26.1") {
            // Package moved as well as the name.
            replace("net.minecraft.util.LightCoordsUtil", "net.minecraft.client.renderer.LightTexture")
            replace("net.minecraft.client.renderer.state.level.CameraRenderState",
                    "net.minecraft.client.renderer.state.CameraRenderState")
            replace("net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper",
                    "net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper")

            // Plain renames.
            replace("GuiGraphicsExtractor", "GuiGraphics")
            replace("LightCoordsUtil", "LightTexture")
            replace("KeyMappingHelper", "KeyBindingHelper")
            replace("registerKeyMapping(", "registerKeyBinding(")

            // Screen and list-entry hooks: same parameters, different names.
            replace("extractRenderState(", "render(")
            replace("extractContent(", "renderContent(")

            // GuiGraphics drawing methods; identical argument shapes.
            replace("graphics.centeredText(", "graphics.drawCenteredString(")
            replace("graphics.text(", "graphics.drawString(")
            replace("graphics.item(", "graphics.renderItem(")
        }

        // authlib's GameProfile was a record accessor only from 1.21.9 on. The
        // exact release is unpinned between the 1.21.8 and 1.21.10 targets;
        // both build correctly with the boundary here.
        string(current.parsed < "1.21.9") {
            replace(".getGameProfile().name()", ".getGameProfile().getName()")
            // setScreen gained the AndShow suffix in 1.21.9.
            replace("setScreenAndShow(", "setScreen(")
            // The third argument of PacketUtils.ensureRunningOnSameThread was a
            // BlockableEventLoop before 1.21.9. This is a mixin injection point,
            // so a wrong descriptor is not a compile error -- it is a crash on
            // launch with "Scanned 0 target(s)".
            replace("Lnet/minecraft/network/PacketProcessor;",
                    "Lnet/minecraft/util/thread/BlockableEventLoop;")
        }

        // 1.21.11 renamed ResourceLocation to Identifier and moved villagers into
        // their own subpackage, so these stop one release earlier than the rest.
        string(current.parsed < "1.21.11") {
            replace("net.minecraft.world.entity.npc.villager.", "net.minecraft.world.entity.npc.")
            replace("net.minecraft.resources.Identifier", "net.minecraft.resources.ResourceLocation")
            replace("Identifier", "ResourceLocation")
            replace("net.minecraft.client.renderer.rendertype.RenderTypes",
                    "net.minecraft.client.renderer.RenderType")
            replace("RenderTypes.", "RenderType.")
            // ResourceKey's accessor; the static method names on the render type
            // class are unchanged, only the class moved and lost its plural.
            replace(".identifier()", ".location()")
            // ItemListing.getOffer gained a ServerLevel parameter in 1.21.11.
            replace("listing.getOffer(level, trader, random)", "listing.getOffer(trader, random)")
        }
    }
}

// Aggregates every target's buildAndCollect, so CI produces the full set of jars
// in one invocation instead of one Gradle run per Minecraft version.
tasks.register("buildAll") {
    group = "build"
    description = "Builds every version target into build/libs/<mod version>/."
    // Lazy collection of the subproject tasks -- must not be resolved with get().
    dependsOn(stonecutter.tasks.named("buildAndCollect"))
}
