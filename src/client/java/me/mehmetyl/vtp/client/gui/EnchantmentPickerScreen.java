package me.mehmetyl.vtp.client.gui;

import me.mehmetyl.vtp.client.VtpClientMod;
import me.mehmetyl.vtp.client.filter.FilterRule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Searchable enchantment list.
 *
 * <p>Enchantments live in a datapack registry in 26.2, so the list comes from the
 * connected world rather than a hardcoded set -- modded and datapack
 * enchantments appear automatically.
 *
 * <p>There is deliberately no "any enchantment" option. It read as a filter but
 * behaved as a wildcard, matching every villager of the profession, which is
 * almost never what someone picking an enchantment wants. Leaving the
 * enchantment unset is still possible from the rule editor.
 */
public final class EnchantmentPickerScreen extends Screen {
    private static final int ROWS = 9;
    private static final int ROW_HEIGHT = 18;
    private static final int LIST_WIDTH = 300;

    private final RuleEditScreen parent;
    private final FilterRule rule;

    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> filtered = new ArrayList<>();
    private final List<Button> rows = new ArrayList<>();

    private final ItemStack icon = new ItemStack(Items.ENCHANTED_BOOK);

    private EditBox search;
    private int scroll;

    private record Entry(String id, String display) {
    }

    public EnchantmentPickerScreen(RuleEditScreen parent, FilterRule rule) {
        super(Component.translatable("vtp.screen.pick_enchantment"));
        this.parent = parent;
        this.rule = rule;
    }

    @Override
    protected void init() {
        loadEnchantments();

        search = new EditBox(this.font, this.width / 2 - LIST_WIDTH / 2, 32, LIST_WIDTH, 20,
                Component.translatable("vtp.field.search"));
        search.setHint(Component.translatable("vtp.field.search"));
        search.setResponder(text -> {
            scroll = 0;
            applyFilter(text);
        });
        this.setInitialFocus(search);

        applyFilter("");
    }

    private void loadEnchantments() {
        all.clear();
        if (this.minecraft == null || this.minecraft.level == null) {
            return;
        }
        try {
            var registry = this.minecraft.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            for (Holder.Reference<Enchantment> holder : registry.listElements().toList()) {
                // Villagers sell from the tradeable pool only. Listing the whole
                // registry offered enchantments no librarian can ever produce --
                // Wind Burst, and every other trial-chamber or treasure-only one
                // -- so those rules matched nothing, forever. A tag rather than a
                // hardcoded list, so a datapack that widens the pool is picked up.
                if (!holder.is(EnchantmentTags.TRADEABLE)) {
                    continue;
                }

                Identifier id = holder.key().identifier();
                all.add(new Entry(id.toString(),
                        Component.translatable(id.toLanguageKey("enchantment")).getString()));
            }
            all.sort((a, b) -> a.display().compareToIgnoreCase(b.display()));
        } catch (Exception e) {
            // A missing registry must not break the screen; the list is simply empty.
            me.mehmetyl.vtp.VtpMod.LOGGER.warn("Could not list enchantments", e);
        }
    }

    private void applyFilter(String query) {
        filtered.clear();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (Entry entry : all) {
            if (needle.isEmpty()
                    || entry.display().toLowerCase(Locale.ROOT).contains(needle)
                    || entry.id().toLowerCase(Locale.ROOT).contains(needle)) {
                filtered.add(entry);
            }
        }
        rebuildRows();
    }

    private void rebuildRows() {
        this.clearWidgets();
        this.addRenderableWidget(search);
        rows.clear();

        int left = this.width / 2 - LIST_WIDTH / 2;
        int top = 58;
        int visible = Math.min(ROWS, Math.max(0, filtered.size() - scroll));

        for (int i = 0; i < visible; i++) {
            Entry entry = filtered.get(scroll + i);
            Button row = Button.builder(Component.literal(entry.display() + "   "), b -> {
                rule.enchantment = entry.id();
                // Back to "any": a level carried over from a previous choice can
                // exceed what this enchantment supports, which then matched
                // nothing at all. The level is narrowed back in the editor.
                rule.minEnchantmentLevel = FilterRule.ANY_LEVEL;
                parent.onEnchantmentChanged();
                this.onClose();
            }).bounds(left, top + i * ROW_HEIGHT, LIST_WIDTH, 16).build();

            rows.add(row);
            this.addRenderableWidget(row);
        }

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!filtered.isEmpty()) {
            int max = Math.max(0, filtered.size() - ROWS);
            int next = (int) Math.clamp((long) (scroll - scrollY), 0, max);
            if (next != scroll) {
                scroll = next;
                rebuildRows();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);

        for (Button row : rows) {
            graphics.item(icon, row.getX() + row.getWidth() - 20, row.getY());
        }

        if (filtered.isEmpty()) {
            graphics.centeredText(this.font, Component.translatable("vtp.label.no_enchantments"),
                    this.width / 2, 80, 0xFFAAAAAA);
        } else if (filtered.size() > ROWS) {
            graphics.centeredText(this.font,
                    Component.translatable("vtp.label.scroll_hint",
                            scroll + 1, Math.min(scroll + ROWS, filtered.size()), filtered.size()),
                    this.width / 2, this.height - 42, 0xFFAAAAAA);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
