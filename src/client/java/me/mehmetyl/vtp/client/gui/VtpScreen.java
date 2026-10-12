package me.mehmetyl.vtp.client.gui;

import me.mehmetyl.vtp.client.VtpClientMod;
import me.mehmetyl.vtp.client.config.VtpConfig;
import me.mehmetyl.vtp.client.filter.FilterRule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The mod config and filter screen.
 *
 * <p>Three tabs implemented as plain buttons that swap the visible widget set.
 * That is deliberately simpler than {@code TabNavigationBar}: it keeps the whole
 * screen inside widget APIs that are stable, and the tab strip is the only piece
 * of chrome this screen needs.
 *
 * <p>26.2 note: drawing happens in {@link #extractRenderState} against a
 * {@link GuiGraphicsExtractor}. {@code GuiGraphics} does not exist in this
 * version.
 */
public final class VtpScreen extends Screen {
    private enum Tab {
        FILTERS("vtp.tab.filters"),
        APPEARANCE("vtp.tab.appearance"),
        GENERAL("vtp.tab.general");

        final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    private static final int HEADER = 52;

    /**
     * Reserved space below the rule list.
     *
     * <p>Must clear both footer rows -- the add/remove buttons at
     * {@code height - 52} and Done at {@code height - 26}. If the list is allowed
     * to extend over them it swallows their clicks, because a selection list
     * consumes mouse input across its whole area.
     */
    private static final int FOOTER = 58;

    /**
     * Row pitches.
     *
     * <p>Two of them, because the two widget kinds have different vertical needs:
     * a checkbox carries its own label inline, while a text field needs a caption
     * drawn 10px above it. Interleaving them on one pitch is what used to drop a
     * caption on top of the checkbox in the row above, so each tab now runs its
     * toggles and its fields as separate blocks.
     */
    private static final int CHECK_PITCH = 24;
    private static final int FIELD_PITCH = 34;
    private static final int GROUP_GAP = 10;

    private final Screen parent;
    private Tab tab = Tab.FILTERS;

    /** Widgets belonging to the current tab, cleared on every tab switch. */
    private final List<AbstractWidget> tabWidgets = new ArrayList<>();

    private RuleListWidget ruleList;

    public VtpScreen(Screen parent) {
        super(Component.translatable("vtp.screen.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Tab strip.
        int tabWidth = 100;
        int totalWidth = tabWidth * Tab.values().length;
        int x = this.width / 2 - totalWidth / 2;

        for (Tab value : Tab.values()) {
            Button button = Button.builder(Component.translatable(value.key), b -> selectTab(value))
                    .bounds(x, 24, tabWidth, 20)
                    .build();
            button.active = value != tab;
            this.addRenderableWidget(button);
            x += tabWidth;
        }

        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
                        .bounds(this.width / 2 - 100, this.height - 26, 200, 20)
                        .build());

        buildTab();
    }

    private void selectTab(Tab value) {
        this.tab = value;
        this.rebuildWidgets();
    }

    private void buildTab() {
        tabWidgets.clear();
        ruleList = null;

        switch (tab) {
            case FILTERS -> buildFiltersTab();
            case APPEARANCE -> buildAppearanceTab();
            case GENERAL -> buildGeneralTab();
        }
    }

    // ---------------------------------------------------------------- filters

    private void buildFiltersTab() {
        VtpConfig config = VtpClientMod.config();

        ruleList = new RuleListWidget(this.minecraft, this,
                this.width, this.height - HEADER - FOOTER, HEADER, 30);
        this.addRenderableWidget(ruleList);
        ruleList.rebuild();

        // The new rule is not added to the config here -- the editor commits it
        // only if Done is pressed, so an abandoned rule never starts highlighting.
        add(Button.builder(Component.translatable("vtp.button.add_rule"), b ->
                        this.minecraft.setScreenAndShow(RuleEditScreen.create(this)))
                .bounds(this.width / 2 - 154, this.height - 52, 150, 20).build());

        Button removeAll = Button.builder(Component.translatable("vtp.button.reset_rules"), b ->
                        this.minecraft.setScreenAndShow(new ConfirmScreen(
                                confirmed -> {
                                    if (confirmed) {
                                        config.rules.clear();
                                        commitAndRefresh();
                                    }
                                    this.minecraft.setScreenAndShow(this);
                                },
                                Component.translatable("vtp.confirm.remove_all.title"),
                                Component.translatable("vtp.confirm.remove_all.message"))))
                .bounds(this.width / 2 + 4, this.height - 52, 150, 20).build();
        removeAll.active = !config.rules.isEmpty();
        add(removeAll);
    }

    // ------------------------------------------------------------- appearance

    private void buildAppearanceTab() {
        VtpConfig.Appearance appearance = VtpClientMod.config().appearance;
        int left = this.width / 2 - 154;
        int right = this.width / 2 + 4;
        int y = HEADER + 6;

        add(checkbox(left, y, "vtp.option.glow", appearance.glowEnabled,
                value -> appearance.glowEnabled = value));
        add(checkbox(right, y, "vtp.option.label", appearance.labelEnabled,
                value -> appearance.labelEnabled = value));
        y += CHECK_PITCH;

        // Directly under the toggle it depends on: a background with no label to
        // sit behind does nothing, and stacking them says so.
        add(checkbox(right, y, "vtp.option.label_background", appearance.labelBackground,
                value -> appearance.labelBackground = value));
        y += CHECK_PITCH + GROUP_GAP;

        add(numberBox(left, y, "vtp.option.label_scale", Float.toString(appearance.labelScale),
                text -> appearance.labelScale = parseFloat(text, appearance.labelScale)));
        add(numberBox(right, y, "vtp.option.max_render_distance",
                Integer.toString((int) appearance.maxRenderDistance),
                text -> appearance.maxRenderDistance = parseInt(text, (int) appearance.maxRenderDistance)));
        y += FIELD_PITCH;

        add(textBox(left, y, "vtp.option.color_locked", appearance.colorLocked,
                text -> appearance.colorLocked = text));
        add(textBox(right, y, "vtp.option.color_rerollable", appearance.colorRerollable,
                text -> appearance.colorRerollable = text));
        y += FIELD_PITCH;

        add(textBox(left, y, "vtp.option.color_unknown", appearance.colorUnknown,
                text -> appearance.colorUnknown = text));
    }

    // ---------------------------------------------------------------- general

    private void buildGeneralTab() {
        VtpConfig config = VtpClientMod.config();
        VtpConfig.General general = config.general;
        VtpConfig.Alerts alerts = config.alerts;
        int left = this.width / 2 - 154;
        int right = this.width / 2 + 4;
        int y = HEADER + 12;

        add(numberBox(left, y, "vtp.option.scan_radius", Integer.toString(general.scanRadius),
                text -> general.scanRadius = parseInt(text, general.scanRadius)));
        // The one option here whose cost is not obvious from its name, so it
        // carries the numbers rather than making the player work them out. A
        // tooltip rather than a line of text: the tab has no room for one, and
        // it needs no warning glyph, which not every resource pack has.
        EditBox scanInterval = numberBox(right, y, "vtp.option.scan_interval",
                Integer.toString(general.scanIntervalTicks),
                text -> general.scanIntervalTicks = parseInt(text, general.scanIntervalTicks));
        // Three lines rather than a paragraph: what the knob does, then the
        // numbers picked out in yellow so they can be found without reading, then
        // the caveat in grey. Tooltip lays out through Font.split, which honours
        // the newlines.
        scanInterval.setTooltip(Tooltip.create(
                Component.translatable("vtp.tooltip.scan_interval")
                        .append("\n")
                        .append(Component.translatable("vtp.tooltip.scan_interval.examples")
                                .withStyle(ChatFormatting.YELLOW))
                        .append("\n")
                        .append(Component.translatable("vtp.tooltip.scan_interval.hint")
                                .withStyle(ChatFormatting.GRAY))));
        add(scanInterval);
        y += FIELD_PITCH;

        add(numberBox(left, y, "vtp.option.max_highlighted", Integer.toString(general.maxHighlighted),
                text -> general.maxHighlighted = parseInt(text, general.maxHighlighted)));
        add(numberBox(right, y, "vtp.option.alert_cooldown",
                Integer.toString(alerts.perVillagerCooldownTicks),
                text -> alerts.perVillagerCooldownTicks =
                        parseInt(text, alerts.perVillagerCooldownTicks)));
        y += FIELD_PITCH + GROUP_GAP;

        add(checkbox(left, y, "vtp.option.alert_sound", alerts.soundOnNewMatch,
                value -> alerts.soundOnNewMatch = value));
        add(checkbox(right, y, "vtp.option.alert_chat", alerts.chatOnNewMatch,
                value -> alerts.chatOnNewMatch = value));
        y += CHECK_PITCH + GROUP_GAP;

        // This clears every filter rule as well as the options, which is not
        // obvious from the button, so it asks first.
        add(Button.builder(Component.translatable("vtp.button.reset_defaults"), b ->
                this.minecraft.setScreenAndShow(new ConfirmScreen(
                        confirmed -> {
                            if (confirmed) {
                                config.resetToDefaults();
                                markDirty();
                                VtpClientMod.save();
                            }
                            this.minecraft.setScreenAndShow(this);
                        },
                        Component.translatable("vtp.confirm.reset_defaults.title"),
                        Component.translatable("vtp.confirm.reset_defaults.message"))))
                .bounds(left, y, 150, 20).build());
    }

    // ----------------------------------------------------------------- render

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);

        if (tab == Tab.FILTERS) {
            if (VtpClientMod.config().rules.isEmpty()) {
                graphics.centeredText(this.font,
                        Component.translatable("vtp.label.no_rules"),
                        this.width / 2, HEADER + 20, 0xFFAAAAAA);
            }
        } else {
            // Field captions, drawn above their boxes.
            for (AbstractWidget widget : tabWidgets) {
                if (widget instanceof EditBox box && box.getMessage() != null) {
                    graphics.text(this.font, box.getMessage(),
                            box.getX(), box.getY() - 10, 0xFFAAAAAA);
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private <T extends AbstractWidget> T add(T widget) {
        tabWidgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    private Checkbox checkbox(int x, int y, String key, boolean value, java.util.function.Consumer<Boolean> sink) {
        return Checkbox.builder(Component.translatable(key), this.font)
                .pos(x, y)
                .selected(value)
                .onValueChange((box, selected) -> {
                    sink.accept(selected);
                    markDirty();
                })
                .build();
    }

    private EditBox textBox(int x, int y, String key, String value, java.util.function.Consumer<String> sink) {
        EditBox box = new EditBox(this.font, x, y, 150, 20, Component.translatable(key));
        box.setMaxLength(64);
        box.setValue(value == null ? "" : value);
        box.setResponder(text -> {
            sink.accept(text);
            markDirty();
        });
        return box;
    }

    private EditBox numberBox(int x, int y, String key, String value, java.util.function.Consumer<String> sink) {
        EditBox box = textBox(x, y, key, value, sink);
        box.setMaxLength(10);
        return box;
    }

    static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static float parseFloat(String text, float fallback) {
        try {
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Marks the filters as changed so the match cache recomputes. Cheap enough to
     * call on every edit; the config file itself is only written on close.
     */
    void markDirty() {
        VtpClientMod.bumpGeneration();
    }

    /** Applies a rule-list change and redraws: used by reorder, toggle and delete. */
    void commitAndRefresh() {
        VtpClientMod.bumpGeneration();
        VtpClientMod.save();
        this.rebuildWidgets();
    }

    void reopen() {
        this.rebuildWidgets();
    }

    @Override
    public void onClose() {
        // Section 4.2: persist on close, not on every keystroke.
        VtpClientMod.config().normalize();
        VtpClientMod.bumpGeneration();
        VtpClientMod.save();

        this.minecraft.setScreenAndShow(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        // The live match count needs the world ticking while the screen is open.
        return false;
    }
}
