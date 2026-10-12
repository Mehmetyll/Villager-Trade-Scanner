package me.mehmetyl.vtp.client;

import com.mojang.blaze3d.platform.InputConstants;
import me.mehmetyl.vtp.VtpConstants;
import me.mehmetyl.vtp.client.config.VtpConfig;
import me.mehmetyl.vtp.client.gui.VtpScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The two rebindable keybinds, both under our own controls category.
 *
 * <p>In 26.2 a keybind category is a registered {@link KeyMapping.Category}
 * wrapping an {@code Identifier}, not the free-form string older versions used.
 * {@code Category.label()} resolves to {@code id.toLanguageKey("key.category")},
 * so ours is {@code key.category.villager_trade_scanner.main}.
 */
public final class VtpKeybinds {
    // 1.21.9 turned keybind categories into registered Category objects. Before
    // that a category is just the translation key, and both forms are passed to
    // the same KeyMapping constructor, so only the declaration differs.
    //? if >=1.21.9 {
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(VtpConstants.id("main"));
    //?} else {
    /*public static final String CATEGORY = "key.category.villager_trade_scanner.main";
    *///?}

    /** Default V -- unbound in vanilla. */
    public static final KeyMapping TOGGLE = new KeyMapping(
            "key.villager_trade_scanner.toggle",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_V,
            CATEGORY);

    /** Default U -- unbound in vanilla, and no modifier handling needed. */
    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.villager_trade_scanner.open_menu",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_U,
            CATEGORY);

    private VtpKeybinds() {
    }

    public static void register() {
        KeyMappingHelper.registerKeyMapping(TOGGLE);
        KeyMappingHelper.registerKeyMapping(OPEN_MENU);
        ClientTickEvents.END_CLIENT_TICK.register(VtpKeybinds::onEndClientTick);
    }

    private static void onEndClientTick(Minecraft minecraft) {
        // consumeClick() drains presses queued since the last tick. On a tick
        // with no input both loops are a single field read and a branch, which is
        // the cost this handler carries when the mod is toggled off.
        while (TOGGLE.consumeClick()) {
            toggle(minecraft);
        }

        while (OPEN_MENU.consumeClick()) {
            // 26.2 moved the current screen behind Gui; up to 26.1 it is a plain
            // field on Minecraft. Verified by javap against both jars.
            //? if >=26.2 {
            minecraft.setScreenAndShow(new VtpScreen(minecraft.gui.screen()));
            //?} else {
            /*minecraft.setScreenAndShow(new VtpScreen(minecraft.screen));
            *///?}
        }
    }

    private static void toggle(Minecraft minecraft) {
        VtpConfig config = VtpClientMod.config();
        config.previewEnabled = !config.previewEnabled;

        // A toggle is a deliberate one-off action, not a keystroke stream, so
        // persisting it immediately is cheap and survives a crash.
        VtpClientMod.save();

        if (minecraft.gui != null) {
            Component message = Component.translatable(config.previewEnabled
                    ? "vtp.message.enabled"
                    : "vtp.message.disabled");

            // 26.2 split the HUD out into its own Hud class reached via Gui.hud;
            // up to 26.1 Gui *is* the HUD and owns setOverlayMessage directly.
            //? if >=26.2 {
            minecraft.gui.hud.setOverlayMessage(message, false);
            //?} else {
            /*minecraft.gui.setOverlayMessage(message, false);
            *///?}
        }
    }
}
