package me.mehmetyl.vtp.client.gui;

import me.mehmetyl.vtp.client.VtpClientMod;
import me.mehmetyl.vtp.client.filter.FilterRule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The scrollable list of filter rules, grouped by profession.
 *
 * <p>Rules keep their config order -- which is also their match priority -- and
 * a header row is inserted whenever the profession changes, so a list covering
 * several villager types stays readable.
 */
public final class RuleListWidget extends ContainerObjectSelectionList<RuleListWidget.Row> {
    private final VtpScreen screen;

    public RuleListWidget(Minecraft minecraft, VtpScreen screen, int width, int height, int y, int itemHeight) {
        super(minecraft, width, height, y, itemHeight);
        this.screen = screen;
    }

    /**
     * Rebuilds every row from the current config.
     *
     * <p>Rules are kept grouped by profession in the config itself, so each
     * profession appears under exactly one header no matter how many rules it
     * has, and the displayed order still equals match priority.
     */
    public void rebuild() {
        regroup();
        this.clearEntries();

        List<FilterRule> rules = VtpClientMod.config().rules;
        String currentGroup = null;

        for (int i = 0; i < rules.size(); i++) {
            FilterRule rule = rules.get(i);
            if (!java.util.Objects.equals(rule.profession, currentGroup)) {
                currentGroup = rule.profession;
                this.addEntry(new HeaderRow(currentGroup));
            }
            this.addEntry(new RuleRow(rule, i));
        }
    }

    /**
     * Reorders the config so rules of the same profession are contiguous,
     * preserving relative order within each profession.
     */
    private static void regroup() {
        List<FilterRule> rules = VtpClientMod.config().rules;
        java.util.LinkedHashMap<String, List<FilterRule>> groups = new java.util.LinkedHashMap<>();

        for (FilterRule rule : rules) {
            groups.computeIfAbsent(rule.profession, key -> new ArrayList<>()).add(rule);
        }

        List<FilterRule> ordered = new ArrayList<>(rules.size());
        groups.values().forEach(ordered::addAll);

        if (!ordered.equals(rules)) {
            rules.clear();
            rules.addAll(ordered);
        }
    }

    /**
     * Moves a rule within its own profession group.
     *
     * <p>Crossing a group boundary would either break the grouping or silently
     * change the rule's profession, so the swap targets the nearest neighbour
     * that shares this rule's profession.
     */
    /** True when {@link #move} would find a same-profession neighbour to swap with. */
    private static boolean canMove(int index, int delta) {
        List<FilterRule> rules = VtpClientMod.config().rules;
        if (index < 0 || index >= rules.size()) {
            return false;
        }
        String profession = rules.get(index).profession;
        for (int target = index + delta; target >= 0 && target < rules.size(); target += delta) {
            if (java.util.Objects.equals(rules.get(target).profession, profession)) {
                return true;
            }
        }
        return false;
    }

    private void move(int index, int delta) {
        List<FilterRule> rules = VtpClientMod.config().rules;
        if (index < 0 || index >= rules.size()) {
            return;
        }
        FilterRule rule = rules.get(index);

        for (int target = index + delta; target >= 0 && target < rules.size(); target += delta) {
            if (java.util.Objects.equals(rules.get(target).profession, rule.profession)) {
                rules.set(index, rules.get(target));
                rules.set(target, rule);
                screen.commitAndRefresh();
                return;
            }
        }
    }

    private void delete(int index) {
        List<FilterRule> rules = VtpClientMod.config().rules;
        if (index >= 0 && index < rules.size()) {
            rules.remove(index);
            screen.commitAndRefresh();
        }
    }

    /** Shared row base so the list can hold headers and rules together. */
    public abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
    }

    /** A profession separator. */
    public final class HeaderRow extends Row {
        private final String professionId;

        HeaderRow(String professionId) {
            this.professionId = professionId;
        }

        @Override
        //? if >=1.21.9 {
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   boolean hovered, float partialTick) {
            draw(graphics, this.getContentX(), this.getContentY());
        }
        //?} else {
        /*public void render(GuiGraphics graphics, int index, int top, int left, int width,
                           int height, int mouseX, int mouseY, boolean hovered, float partialTick) {
            draw(graphics, left, top);
        }
        *///?}

        private void draw(GuiGraphicsExtractor graphics, int contentX, int contentY) {
            int y = contentY + 6;
            graphics.item(ProfessionIcons.icon(professionId), contentX + 2, y - 4);
            graphics.text(RuleListWidget.this.minecraft.font,
                    ProfessionIcons.name(professionId),
                    contentX + 24, y, 0xFFFFD070);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    /** One rule: [x] Name ... [item icon] [edit] [up] [down] [delete] */
    public final class RuleRow extends Row {
        private final FilterRule rule;
        private final int index;

        private final Checkbox enabled;
        private final Button edit;
        private final Button up;
        private final Button down;
        private final Button delete;

        private final List<AbstractWidget> widgets = new ArrayList<>();
        private final ItemStack icon;

        /** The maximum price, or null when the rule sets none. */
        private final String priceText;
        private final ItemStack costIcon;
        private final ItemStack costBIcon;

        RuleRow(FilterRule rule, int index) {
            this.rule = rule;
            this.index = index;
            this.icon = iconFor(rule);

            String costId = RuleEditScreen.firstInput(rule);
            if (costId == null) {
                costId = VtpClientMod.catalog().costFor(rule.profession, rule.resultItem);
            }
            // A trade can charge two different items; naming only the first made
            // a fletcher's gravel-and-emerald rule look half as expensive.
            String costBId = RuleEditScreen.secondInput(rule);
            this.costIcon = stackFor(costId);
            this.costBIcon = stackFor(costBId);

            // Second line: what the trade costs, shown as icons. Spelling the
            // items out as well pushed the row wide for nothing the icon does
            // not already say, and the names were what crowded it.
            //
            // The maximum price stays as a number, because nothing else in the
            // list shows it. When the cost item is unknown there will be no icon
            // to give the number meaning, so that case keeps the wording.
            if (rule.maxEmeraldPrice != FilterRule.UNSET) {
                this.priceText = costId == null
                        ? Component.translatable("vtp.label.price", rule.maxEmeraldPrice,
                                Component.translatable("vtp.label.price_generic").getString())
                                .getString()
                        : Integer.toString(rule.maxEmeraldPrice);
            } else {
                this.priceText = null;
            }

            this.enabled = Checkbox.builder(Component.empty(), RuleListWidget.this.minecraft.font)
                    .selected(rule.enabled)
                    .onValueChange((box, value) -> {
                        rule.enabled = value;
                        screen.commitAndRefresh();
                    })
                    .build();

            this.edit = Button.builder(Component.translatable("vtp.button.edit"),
                            b -> RuleListWidget.this.minecraft.setScreenAndShow(
                                    RuleEditScreen.edit(screen, rule)))
                    .bounds(0, 0, 40, 20).build();

            this.up = Button.builder(Component.literal("▲"), b -> move(index, -1))
                    .bounds(0, 0, 20, 20).build();
            this.down = Button.builder(Component.literal("▼"), b -> move(index, 1))
                    .bounds(0, 0, 20, 20).build();
            this.delete = Button.builder(Component.literal("✖"), b -> delete(index))
                    .bounds(0, 0, 20, 20).build();

            widgets.add(enabled);
            widgets.add(edit);
            widgets.add(up);
            widgets.add(down);
            widgets.add(delete);
        }

        /** Resolves an item id to a stack, or empty when unknown. */
        private static ItemStack stackFor(String id) {
            if (id == null || id.isBlank() || FilterRule.ANY.equals(id)) {
                return ItemStack.EMPTY;
            }
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                    net.minecraft.resources.Identifier.parse(id));
            return item == null ? ItemStack.EMPTY : item.getDefaultInstance();
        }

        private static ItemStack iconFor(FilterRule rule) {
            if (rule.resultItem != null && !FilterRule.ANY.equals(rule.resultItem)) {
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                        net.minecraft.resources.Identifier.parse(rule.resultItem));
                if (item != null) {
                    return item.getDefaultInstance();
                }
            }
            return ItemStack.EMPTY;
        }

        @Override
        //? if >=1.21.9 {
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   boolean hovered, float partialTick) {
            draw(graphics, this.getContentX(), this.getContentY(), this.getContentRight(),
                    mouseX, mouseY, partialTick);
        }
        //?} else {
        /*public void render(GuiGraphics graphics, int index, int top, int left, int width,
                           int height, int mouseX, int mouseY, boolean hovered, float partialTick) {
            draw(graphics, left, top, left + width, mouseX, mouseY, partialTick);
        }
        *///?}

        private void draw(GuiGraphicsExtractor graphics, int contentX, int contentY,
                          int contentRight, int mouseX, int mouseY, float partialTick) {
            int y = contentY + 2;
            int right = contentRight;

            enabled.setPosition(contentX + 4, y);
            delete.setPosition(right - 22, y);
            down.setPosition(right - 44, y);
            up.setPosition(right - 66, y);
            edit.setPosition(right - 108, y);

            // Computed against the rule own profession group, not the flat list:
            // a rule at the top of its group has nowhere to go, and an arrow that
            // looks enabled but does nothing is worse than a disabled one.
            up.active = canMove(index, -1);
            down.active = canMove(index, 1);

            for (AbstractWidget widget : widgets) {
                widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
            }

            var font = RuleListWidget.this.minecraft.font;
            int textX = contentX + 28;

            // Everything from here to the Edit button is ours; reserve the icon
            // slot so a long name is trimmed instead of running under the buttons.
            int iconSlot = icon.isEmpty() ? 0 : 22;
            int available = Math.max(0, edit.getX() - 6 - textX - iconSlot);

            String name = rule.label == null || rule.label.isBlank() ? "-" : rule.label;
            String shown = trim(font, name, available);

            int nameColour = rule.enabled ? 0xFFFFFFFF : 0xFF808080;
            graphics.text(font, Component.literal(shown), textX, y + 1, nameColour);

            // Item icon sits immediately to the right of the (possibly trimmed) name.
            if (!icon.isEmpty()) {
                graphics.item(icon, textX + font.width(shown) + 4, y - 3);
            }

            // Second line: the price if the rule sets one, then the cost icons.
            if (priceText != null || !costIcon.isEmpty()) {
                int costX = textX;

                if (priceText != null) {
                    String price = trim(font, priceText, available);
                    graphics.text(font, Component.literal(price), costX, y + 12, 0xFF909090);
                    costX += font.width(price) + 4;
                }

                if (!costIcon.isEmpty()) {
                    graphics.item(costIcon, costX, y + 8);
                    if (!costBIcon.isEmpty()) {
                        // The plus only appears when there is a second item to
                        // join; two bare icons side by side read as one pile.
                        graphics.text(font, Component.literal("+"), costX + 18, y + 12, 0xFF909090);
                        graphics.item(costBIcon, costX + 26, y + 8);
                    }
                }
            }
        }

        /** Shortens text with an ellipsis so it never runs under the buttons. */
        private static String trim(net.minecraft.client.gui.Font font, String text, int available) {
            if (font.width(text) <= available) {
                return text;
            }
            return font.plainSubstrByWidth(text, Math.max(0, available - font.width("..."))) + "...";
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return widgets;
        }
    }
}
