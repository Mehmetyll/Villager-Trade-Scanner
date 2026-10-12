package me.mehmetyl.vtp.client.gui;

import me.mehmetyl.vtp.client.ItemNames;
import me.mehmetyl.vtp.client.VtpClientMod;
import me.mehmetyl.vtp.client.filter.FilterRule;
import me.mehmetyl.vtp.net.VtpPayloads;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Searchable list of the trades a profession can offer.
 *
 * <p>One row per <em>trade</em>, not per result item. A farmer pays emeralds for
 * wheat, potato, carrot and beetroot; as one row per result those four collapsed
 * into a single "Emerald" entry and could not be told apart or filtered
 * separately. Each row now draws the trade it stands for on the right.
 *
 * <p>Scoped to what the chosen profession actually trades. If nothing is known --
 * a vanilla server with no villager opened yet -- the full registry is offered
 * instead and the screen says so.
 */
public final class ItemPickerScreen extends Screen {
    private static final int ROWS = 9;
    private static final int ROW_HEIGHT = 18;
    private static final int LIST_WIDTH = 300;

    /** Right-hand strip holding cost icon, arrow and result icon. */
    private static final int PAIR_WIDTH = 58;

    /** Wider zone for a trade charging two items: icon, plus, icon, arrow. */
    private static final int DOUBLE_PAIR_WIDTH = 90;

    private final RuleEditScreen parent;
    private final FilterRule rule;

    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> filtered = new ArrayList<>();
    private final List<Button> rows = new ArrayList<>();

    private EditBox search;
    private int scroll;
    private boolean showingEverything;

    /**
     * One row.
     *
     * @param costId      the item paid, or {@code null} for the "any cost" row
     * @param costDisplay name of that item, or the word Any, used for sorting
     */
    private record Entry(String id,
                         String costId,
                         String costBId,
                         String display,
                         String costDisplay,
                         String costBDisplay,
                         ItemStack icon,
                         ItemStack costIcon,
                         ItemStack costBIcon) {

        /**
         * True when this row draws the trade rather than just the item.
         *
         * <p>Only trades that pay <em>out</em> emeralds need it: those are the
         * ones where one result is reachable through many different costs, so a
         * farmer's four crops would otherwise be four identical "Emerald" rows.
         * Everything else has a result unique to its row already.
         */
        boolean showsPair() {
            return ItemNames.isEmerald(id) || chargesMoreThanEmeralds();
        }

        /**
         * True when the trade wants something other than plain emeralds.
         *
         * <p>A fletcher charges gravel and emeralds for flint. Showing only the
         * result hid half the price, and showing only the first cost hid the
         * other half.
         */
        boolean chargesMoreThanEmeralds() {
            return costBId != null || (costId != null && !ItemNames.isEmerald(costId));
        }

        boolean isAny() {
            return costId == null;
        }
    }

    public ItemPickerScreen(RuleEditScreen parent, FilterRule rule) {
        super(Component.translatable("vtp.screen.pick_item"));
        this.parent = parent;
        this.rule = rule;
    }

    @Override
    protected void init() {
        loadItems();

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

    private void loadItems() {
        all.clear();

        // Preferred source: the server's trade catalogue, which lists what this
        // profession *can* trade whether or not one has been seen. Falls back to
        // trades actually observed, which is all a vanilla server can give us.
        List<VtpPayloads.TradeEntry> trades = VtpClientMod.catalog().tradesFor(rule.profession);
        if (trades.isEmpty()) {
            trades = VtpClientMod.offers().collectSoldTrades(rule.profession);
        }

        showingEverything = trades.isEmpty();

        if (showingEverything) {
            for (Item item : BuiltInRegistries.ITEM) {
                if (item != Items.AIR) {
                    all.add(anyRow(BuiltInRegistries.ITEM.getKey(item).toString()));
                }
            }
        } else {
            buildTradeRows(trades);
        }

        // Rows for one result stay together, with its catch-all above the
        // specific trades so the general case reads first.
        all.sort(Comparator
                .comparing(Entry::display, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(entry -> entry.isAny() ? 0 : 1)
                .thenComparing(Entry::costDisplay, String.CASE_INSENSITIVE_ORDER));
    }

    private void buildTradeRows(List<VtpPayloads.TradeEntry> trades) {
        Set<String> plainResults = new LinkedHashSet<>();

        for (VtpPayloads.TradeEntry trade : trades) {
            Item result = ItemNames.item(trade.result());
            if (result == null || result == Items.AIR) {
                continue;
            }

            String costId = blankToNull(trade.cost());
            String costBId = blankToNull(trade.costB());
            Item cost = costId == null ? null : ItemNames.item(costId);
            Item costB = costBId == null ? null : ItemNames.item(costBId);

            boolean plainEmeraldPurchase = !ItemNames.isEmerald(trade.result())
                    && costBId == null
                    && (costId == null || ItemNames.isEmerald(costId));

            if (plainEmeraldPurchase) {
                // You pay emeralds and get this; the result names the row on its
                // own, so the cost is neither shown nor recorded and one row per
                // result is enough.
                if (!plainResults.add(trade.result())) {
                    continue;
                }
                all.add(anyRow(trade.result()));
                continue;
            }

            all.add(new Entry(
                    trade.result(),
                    costId,
                    costBId,
                    ItemNames.displayName(trade.result()),
                    costId == null ? "" : ItemNames.displayName(costId),
                    costBId == null ? "" : ItemNames.displayName(costBId),
                    result.getDefaultInstance(),
                    cost == null ? ItemStack.EMPTY : cost.getDefaultInstance(),
                    costB == null ? ItemStack.EMPTY : costB.getDefaultInstance()));
        }

    }

    /** A row matching a result through any cost -- the behaviour before per-trade rows. */
    private static Entry anyRow(String resultId) {
        Item result = ItemNames.item(resultId);
        return new Entry(
                resultId,
                null,
                null,
                ItemNames.displayName(resultId),
                "",
                "",
                result == null ? ItemStack.EMPTY : result.getDefaultInstance(),
                ItemStack.EMPTY,
                ItemStack.EMPTY);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Filters the list.
     *
     * <p>Also matches the cost item, which the row does not name: several rows
     * read "Emerald" and differ only by their icons, so searching "wheat" has to
     * find the wheat-to-emerald one.
     */
    private void applyFilter(String query) {
        filtered.clear();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

        for (Entry entry : all) {
            if (needle.isEmpty()
                    || contains(entry.display(), needle)
                    || contains(entry.id(), needle)
                    || contains(entry.costDisplay(), needle)
                    || contains(entry.costId(), needle)
                    || contains(entry.costBDisplay(), needle)
                    || contains(entry.costBId(), needle)) {
                filtered.add(entry);
            }
        }
        rebuildRows();
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
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

            Button row = Button.builder(Component.literal(entry.display()), b -> {
                rule.resultItem = entry.id();
                // The chosen cost narrows the rule to this one trade. An "any"
                // row clears it, which is what an unconstrained rule has always
                // been -- CompiledRule already treats an empty list that way.
                rule.inputItems = new ArrayList<>();
                if (entry.costId() != null) {
                    rule.inputItems.add(entry.costId());
                }
                // Both costs, because CompiledRule requires every listed input to
                // be present -- that is what pins the rule to this one trade.
                if (entry.costBId() != null) {
                    rule.inputItems.add(entry.costBId());
                }
                parent.onItemChanged();
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

        // Icons go on top of the already-drawn buttons: the item being picked at
        // the left edge, and the trade it stands for at the right.
        for (int i = 0; i < rows.size(); i++) {
            Button row = rows.get(i);
            Entry entry = filtered.get(scroll + i);

            int right = row.getX() + row.getWidth();
            int y = row.getY();

            if (entry.showsPair()) {
                if (entry.isAny()) {
                    graphics.text(this.font, Component.translatable("vtp.field.any_cost"),
                            right - PAIR_WIDTH, y + 4, 0xFFAAAAAA);
                } else if (entry.costBId() != null) {
                    graphics.item(entry.costIcon(), right - DOUBLE_PAIR_WIDTH, y);
                    graphics.text(this.font, Component.literal("+"),
                            right - DOUBLE_PAIR_WIDTH + 18, y + 4, 0xFFAAAAAA);
                    graphics.item(entry.costBIcon(), right - DOUBLE_PAIR_WIDTH + 26, y);
                } else {
                    graphics.item(entry.costIcon(), right - PAIR_WIDTH, y);
                }
                graphics.text(this.font, Component.literal("->"), right - 38, y + 4, 0xFFAAAAAA);
            }

            graphics.item(entry.icon(), right - 20, y);
        }

        if (filtered.isEmpty()) {
            graphics.centeredText(this.font, Component.translatable("vtp.label.no_items"),
                    this.width / 2, 80, 0xFFAAAAAA);
        } else if (showingEverything) {
            graphics.centeredText(this.font, Component.translatable("vtp.label.all_items"),
                    this.width / 2, this.height - 42, 0xFFAAAAAA);
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
