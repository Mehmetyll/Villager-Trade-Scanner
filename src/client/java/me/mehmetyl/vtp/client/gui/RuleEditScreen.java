package me.mehmetyl.vtp.client.gui;

import me.mehmetyl.vtp.client.VtpClientMod;
import me.mehmetyl.vtp.client.filter.FilterRule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Edits a single filter rule.
 *
 * <p>Edits go to a <em>draft</em>. Nothing reaches the live config -- and so
 * nothing starts highlighting villagers -- until Done is pressed. Leaving any
 * other way asks before throwing the draft away.
 *
 * <p>The form adapts to the profession: enchantment only appears for librarians,
 * because that is the only trade where an enchantment is the thing being bought.
 */
public final class RuleEditScreen extends Screen {
    private static final String LIBRARIAN = "minecraft:librarian";

    private final VtpScreen parent;

    /** The rule in the config, or null when this is a brand new rule. */
    private final FilterRule target;

    /** What the screen actually edits. */
    private final FilterRule draft;

    private final List<EditBox> captioned = new ArrayList<>();

    private Button itemButton;
    private Button doneButton;
    private EditBox priceBox;
    private int levelRowY = -1;
    private Button professionButton;
    private ItemStack itemIcon = ItemStack.EMPTY;

    private RuleEditScreen(VtpScreen parent, FilterRule target, FilterRule draft) {
        super(Component.translatable("vtp.screen.edit_rule"));
        this.parent = parent;
        this.target = target;
        this.draft = draft;
    }

    /** Opens the editor for a rule that already exists in the config. */
    public static RuleEditScreen edit(VtpScreen parent, FilterRule rule) {
        return new RuleEditScreen(parent, rule, rule.copy());
    }

    /** Opens the editor for a new rule that is not in the config yet. */
    public static RuleEditScreen create(VtpScreen parent) {
        FilterRule fresh = new FilterRule();
        fresh.profession = ProfessionIcons.PROFESSIONS.get(0);
        return new RuleEditScreen(parent, null, fresh);
    }

    private boolean isLibrarian() {
        return LIBRARIAN.equals(draft.profession);
    }

    @Override
    protected void init() {
        captioned.clear();
        levelRowY = -1;
        refreshIcon();

        int left = this.width / 2 - 154;
        int right = this.width / 2 + 4;
        int full = 308;
        int y = 46;

        professionButton = Button.builder(
                        ProfessionIcons.name(draft.profession).copy().append("   "),
                        b -> this.minecraft.setScreenAndShow(new ProfessionPickerScreen(this, draft)))
                .bounds(left, y, full, 20).build();
        this.addRenderableWidget(professionButton);
        y += 34;

        if (isEnchantedBook()) {
            itemButton = Button.builder(itemLabel(), b ->
                            this.minecraft.setScreenAndShow(new ItemPickerScreen(this, draft)))
                    .bounds(left, y, 150, 20).build();
            this.addRenderableWidget(itemButton);

            // Only a book carries stored enchantments, so these controls exist
            // only here. Showing them greyed out for every other trade suggested
            // a choice that was never available.
            Button enchantmentButton = Button.builder(enchantmentLabel(), b ->
                            this.minecraft.setScreenAndShow(new EnchantmentPickerScreen(this, draft)))
                    .bounds(right, y, 150, 20).build();
            this.addRenderableWidget(enchantmentButton);
            y += 34;

            priceBox = box(left, y, 150, priceCaption(), unset(draft.maxEmeraldPrice),
                    text -> draft.maxEmeraldPrice = parseUnset(text));

            buildLevelButtons(right, y, 150);
            levelRowY = y;
            y += 34;

            stockCheckbox(left, y);
        } else {
            itemButton = Button.builder(itemLabel(), b ->
                            this.minecraft.setScreenAndShow(new ItemPickerScreen(this, draft)))
                    .bounds(left, y, full, 20).build();
            this.addRenderableWidget(itemButton);
            y += 34;

            priceBox = box(left, y, 150, priceCaption(), unset(draft.maxEmeraldPrice),
                    text -> draft.maxEmeraldPrice = parseUnset(text));

            stockCheckbox(right, y);
        }

        // Done is disabled until the rule actually names something, so without a
        // Cancel button a half-built rule could only be escaped with Esc.
        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
                        .bounds(this.width / 2 - 154, this.height - 28, 150, 20)
                        .build());

        doneButton = Button.builder(CommonComponents.GUI_DONE, b -> commit())
                .bounds(this.width / 2 + 4, this.height - 28, 150, 20)
                .build();
        // A rule with neither an item nor an enchantment matches every villager
        // of the profession, which is never what someone is trying to build.
        doneButton.active = isComplete();
        this.addRenderableWidget(doneButton);
    }

    private boolean isComplete() {
        // An enchanted book with no enchantment named matches every book trade a
        // librarian has, which is as good as no filter at all.
        if (isEnchantedBook() && draft.enchantment == null) {
            return false;
        }
        return (draft.resultItem != null && !FilterRule.ANY.equals(draft.resultItem))
                || draft.enchantment != null;
    }

    /** True when the chosen item is an enchanted book. */
    private boolean isEnchantedBook() {
        return "minecraft:enchanted_book".equals(draft.resultItem);
    }

    /**
     * One small button per level the enchantment supports, laid out inline.
     *
     * <p>Fixed narrow buttons rather than ones that divide the column: a
     * single-level enchantment otherwise produced one button stretched across
     * the whole column, which read as a text field rather than a choice.
     *
     * <p>The selected level is shown greyed out, matching the tab strip on the
     * main screen. That is safe here because the row sits alongside the rest of
     * the form -- as a standalone screen it was a dead end.
     */
    private void buildLevelButtons(int x, int y, int width) {
        int max = maxEnchantmentLevel();
        boolean usable = draft.enchantment != null;

        int spacing = 2;
        int buttonWidth = 20;
        int anyWidth = 30;
        int cursor = x;

        // A one-level enchantment -- Mending, Aqua Affinity -- makes "Any" and "1"
        // the same filter, so only "1" is offered. Anything deeper keeps "Any",
        // which is where a new rule starts.
        if (max > 1) {
            Button any = Button.builder(Component.translatable("vtp.field.level_any"), b -> {
                draft.minEnchantmentLevel = FilterRule.ANY_LEVEL;
                this.rebuildWidgets();
            }).bounds(x, y, anyWidth, 20).build();
            any.active = usable && draft.minEnchantmentLevel != FilterRule.ANY_LEVEL;
            this.addRenderableWidget(any);
            cursor = x + anyWidth + spacing;
        } else if (usable) {
            // Nothing else to pick, so make the stored value agree with the one
            // button on screen rather than leaving it on a hidden "Any".
            draft.minEnchantmentLevel = 1;
        }
        for (int level = 1; level <= max; level++) {
            int chosen = level;

            Button button = Button.builder(Component.literal(Integer.toString(level)), b -> {
                draft.minEnchantmentLevel = chosen;
                this.rebuildWidgets();
            }).bounds(cursor, y, buttonWidth, 20).build();

            button.active = usable && level != draft.minEnchantmentLevel;
            this.addRenderableWidget(button);
            cursor += buttonWidth + spacing;
        }
    }

    /**
     * "Only in stock" toggle.
     *
     * <p>Replaces the old minimum-stock number: the only threshold anyone
     * actually wanted was "not exhausted", and a number box invited values a
     * trade could never satisfy.
     */
    private void stockCheckbox(int x, int y) {
        this.addRenderableWidget(
                Checkbox.builder(Component.translatable("vtp.field.only_in_stock"), this.font)
                        .pos(x, y)
                        .selected(draft.onlyInStock)
                        .onValueChange((box, selected) -> draft.onlyInStock = selected)
                        .build());
    }

    /** Highest level the chosen enchantment supports; 1 when unresolvable. */
    private int maxEnchantmentLevel() {
        if (draft.enchantment == null || this.minecraft == null || this.minecraft.level == null) {
            return 1;
        }
        try {
            var registry = this.minecraft.level.registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
            var enchantment = registry.getValue(Identifier.parse(draft.enchantment));
            return enchantment == null ? 1 : Math.max(1, enchantment.getMaxLevel());
        } catch (Exception e) {
            return 1;
        }
    }

    /** Writes the draft into the config. The only path that changes anything. */
    private void commit() {
        updateName();
        draft.normalize();

        if (target == null) {
            VtpClientMod.config().rules.add(draft);
        } else {
            target.copyFrom(draft);
        }

        VtpClientMod.bumpGeneration();
        VtpClientMod.save();

        this.minecraft.setScreenAndShow(parent);
        parent.reopen();
    }

    void onProfessionChanged() {
        // An enchantment on a non-librarian rule would silently match nothing,
        // since only books carry stored enchantments.
        if (!isLibrarian()) {
            draft.enchantment = null;
        }
        // The item list is scoped per profession, so a carried-over item would
        // usually be something this villager never sells -- and the cost that
        // narrowed it belongs to a trade this villager does not have.
        draft.resultItem = FilterRule.ANY;
        draft.inputItems.clear();
        updateName();
        this.rebuildWidgets();
    }

    void onItemChanged() {
        // An enchantment only applies to books; keeping one on a different
        // item would silently match nothing.
        if (!isEnchantedBook()) {
            draft.enchantment = null;
            draft.minEnchantmentLevel = FilterRule.ANY_LEVEL;
        }
        updateName();
        refreshIcon();
        this.rebuildWidgets();
    }

    void onEnchantmentChanged() {
        updateName();
        this.rebuildWidgets();
    }

    /** Names the rule after what it matches: enchantment, else item, else profession. */
    private void updateName() {
        if (draft.enchantment != null) {
            draft.label = Component.translatable(
                    Identifier.parse(draft.enchantment).toLanguageKey("enchantment")).getString();
        } else if (draft.resultItem != null && !FilterRule.ANY.equals(draft.resultItem)) {
            // Always what the trade gives. The rule row names this on its first
            // line and the item paid on its second, so several emerald-paying
            // rules stay distinct without renaming any of them.
            draft.label = itemName(draft.resultItem);
        } else {
            draft.label = ProfessionIcons.name(draft.profession).getString();
        }
    }

    private void refreshIcon() {
        itemIcon = ItemStack.EMPTY;
        Item item = lookupItem(draft.resultItem);
        if (item != null) {
            itemIcon = item.getDefaultInstance();
        }
    }

    private static Item lookupItem(String id) {
        return FilterRule.ANY.equals(id) ? null : me.mehmetyl.vtp.client.ItemNames.item(id);
    }

    /** Display name from the item itself, so block items resolve correctly. */
    static String itemName(String id) {
        return FilterRule.ANY.equals(id) ? "" : me.mehmetyl.vtp.client.ItemNames.displayName(id);
    }

    private Component itemLabel() {
        if (draft.resultItem == null || FilterRule.ANY.equals(draft.resultItem)) {
            return Component.translatable("vtp.field.item_choose");
        }
        return Component.literal(itemName(draft.resultItem) + "   ");
    }

    private Component enchantmentLabel() {
        if (draft.enchantment == null) {
            return Component.translatable("vtp.field.enchantment_none");
        }
        return Component.translatable(Identifier.parse(draft.enchantment).toLanguageKey("enchantment"));
    }

    /**
     * The item this trade is actually paid in, if the server told us.
     *
     * <p>A cleric wanting rotten flesh should not have its price field labelled
     * "emeralds".
     */
    String costItemId() {
        // The rule's own choice first: a result reachable through several trades
        // has several costs, and costFor can only guess at the first of them.
        String chosen = firstInput(draft);
        if (chosen != null) {
            return chosen;
        }
        return VtpClientMod.catalog().costFor(draft.profession, draft.resultItem);
    }

    /** The first cost item this rule was narrowed to, or null for any. */
    static String firstInput(FilterRule rule) {
        return rule.inputItems == null || rule.inputItems.isEmpty() ? null : rule.inputItems.get(0);
    }

    /**
     * The second cost item, for a trade that charges two, or null.
     *
     * <p>FilterRule.sanitize already caps inputItems at two, which is the most
     * vanilla charges for one trade.
     */
    static String secondInput(FilterRule rule) {
        return rule.inputItems == null || rule.inputItems.size() < 2 ? null : rule.inputItems.get(1);
    }

    private Component priceCaption() {
        String cost = costItemId();
        if (cost == null) {
            return Component.translatable("vtp.field.max_price");
        }
        return Component.translatable("vtp.field.max_price_of", itemName(cost));
    }

    private EditBox box(int x, int y, int width, Component caption, String value,
                        java.util.function.Consumer<String> sink) {
        EditBox editBox = new EditBox(this.font, x, y, width, 20, caption);
        editBox.setMaxLength(10);
        editBox.setValue(value == null ? "" : value);
        // Writes to the draft only; nothing is live until Done.
        editBox.setResponder(sink);
        this.addRenderableWidget(editBox);
        captioned.add(editBox);
        return editBox;
    }

    private static String unset(int value) {
        return value == FilterRule.UNSET ? "" : Integer.toString(value);
    }

    private static int parseUnset(String text) {
        if (text == null || text.isBlank()) {
            return FilterRule.UNSET;
        }
        return VtpScreen.parseInt(text, FilterRule.UNSET);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);

        for (EditBox box : captioned) {
            graphics.text(this.font, box.getMessage(), box.getX(), box.getY() - 10, 0xFFAAAAAA);
        }

        // Icons sit at the right edge of their buttons, matching the pickers.
        if (professionButton != null) {
            graphics.item(ProfessionIcons.icon(draft.profession),
                    professionButton.getX() + professionButton.getWidth() - 22,
                    professionButton.getY() + 2);
        }
        if (itemButton != null && !itemIcon.isEmpty()) {
            graphics.item(itemIcon,
                    itemButton.getX() + itemButton.getWidth() - 22,
                    itemButton.getY() + 2);
        }

        // The currency icon sits beside the price caption, so "Max price (Rotten
        // Flesh)" is backed up by the item it refers to.
        if (priceBox != null) {
            String cost = costItemId();
            Item item = cost == null ? null : lookupItem(cost);
            if (item != null) {
                // Captions sit at y-10 and item icons are 16px tall, so the icon
                // has to start 6px higher to end level with the caption rather
                // than hanging into the text box below it.
                graphics.item(item.getDefaultInstance(),
                        priceBox.getX() + this.font.width(priceBox.getMessage()) + 4,
                        priceBox.getY() - 16);
            }
        }

        if (levelRowY >= 0) {
            graphics.text(this.font, Component.translatable("vtp.field.min_level"),
                    this.width / 2 + 4, levelRowY - 10, 0xFFAAAAAA);
        }
    }

    @Override
    public void onClose() {
        // Esc, or anything else that is not Done. Leaving a rule you were editing
        // throws away an edit, not the rule -- saying "discard this rule" there
        // reads as though the rule itself is about to be deleted.
        boolean editing = target != null;

        Component title = editing
                ? Component.translatable("vtp.confirm.discard_edit.title")
                : Component.translatable("vtp.confirm.discard.title");
        Component message = editing
                ? Component.translatable("vtp.confirm.discard_edit.message")
                : Component.translatable("vtp.confirm.discard.message");

        this.minecraft.setScreenAndShow(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        this.minecraft.setScreenAndShow(parent);
                        parent.reopen();
                    } else {
                        this.minecraft.setScreenAndShow(this);
                    }
                },
                title,
                message));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
