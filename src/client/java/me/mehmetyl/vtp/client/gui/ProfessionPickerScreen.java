package me.mehmetyl.vtp.client.gui;

import me.mehmetyl.vtp.client.filter.FilterRule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Drop-down style profession chooser, each entry shown with its job site block.
 */
public final class ProfessionPickerScreen extends Screen {
    private static final int ROWS = 9;
    private static final int ROW_HEIGHT = 18;
    private static final int LIST_WIDTH = 220;

    private final RuleEditScreen parent;
    private final FilterRule rule;

    private final List<Button> rows = new ArrayList<>();

    /** Snapshotted per rebuild so the rows and their icons cannot disagree. */
    private List<String> professions = ProfessionIcons.PROFESSIONS;

    private int scroll;

    public ProfessionPickerScreen(RuleEditScreen parent, FilterRule rule) {
        super(Component.translatable("vtp.screen.pick_profession"));
        this.parent = parent;
        this.rule = rule;
    }

    @Override
    protected void init() {
        rebuildRows();
    }

    private void rebuildRows() {
        this.clearWidgets();
        rows.clear();

        professions = ProfessionIcons.available();
        int left = this.width / 2 - LIST_WIDTH / 2;
        int top = 40;
        int visible = Math.min(ROWS, Math.max(0, professions.size() - scroll));

        for (int i = 0; i < visible; i++) {
            String id = professions.get(scroll + i);
            // Trailing spaces reserve room for the icon drawn at the right edge.
            Button row = Button.builder(ProfessionIcons.name(id).copy().append("   "), b -> {
                rule.profession = id;
                parent.onProfessionChanged();
                this.onClose();
            }).bounds(left, top + i * ROW_HEIGHT, LIST_WIDTH, 16).build();

            rows.add(row);
            this.addRenderableWidget(row);
        }

        // Scrollbar hugs the list; the arrows sit outboard of it.
        int arrowX = left + LIST_WIDTH + 16;
        Button up = Button.builder(Component.literal("▲"), b -> scrollBy(-1))
                .bounds(arrowX, top, 16, 16).build();
        up.active = scroll > 0;
        this.addRenderableWidget(up);

        Button down = Button.builder(Component.literal("▼"), b -> scrollBy(1))
                .bounds(arrowX, top + (ROWS - 1) * ROW_HEIGHT, 16, 16).build();
        down.active = scroll < maxScroll();
        this.addRenderableWidget(down);

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
    }

    private int maxScroll() {
        return Math.max(0, professions.size() - ROWS);
    }

    private void scrollBy(int delta) {
        int next = Math.clamp(scroll + delta, 0, maxScroll());
        if (next != scroll) {
            scroll = next;
            rebuildRows();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // A purely horizontal wheel or trackpad gesture reports scrollY == 0, and
        // the old ternary treated that as a scroll down.
        if (scrollY == 0.0) {
            return false;
        }
        scrollBy(scrollY > 0 ? -1 : 1);
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);

        for (int i = 0; i < rows.size(); i++) {
            Button row = rows.get(i);
            String id = professions.get(scroll + i);
            graphics.item(ProfessionIcons.icon(id), row.getX() + row.getWidth() - 20, row.getY());
        }

        // Scrollbar track and thumb, sized to how much of the list is showing.
        int max = maxScroll();
        if (max > 0 && !rows.isEmpty()) {
            int left = this.width / 2 - LIST_WIDTH / 2;
            int barX = left + LIST_WIDTH + 6;
            int top = 40;
            int trackHeight = ROWS * ROW_HEIGHT;

            graphics.fill(barX, top, barX + 4, top + trackHeight, 0xFF202020);

            int total = professions.size();
            int thumbHeight = Math.max(12, trackHeight * ROWS / total);
            int thumbY = top + (trackHeight - thumbHeight) * scroll / max;
            graphics.fill(barX, thumbY, barX + 4, thumbY + thumbHeight, 0xFFA0A0A0);
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
