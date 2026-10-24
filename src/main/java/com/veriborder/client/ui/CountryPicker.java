package com.veriborder.client.ui;

import com.veriborder.client.Ui;
import com.veriborder.registry.CredentialRegistryData;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Floating country/region whitelist picker (国家/地区白名单浮层).
 *
 * <p>26.4's whitelist button could not be opened: the checklist was an inline
 * fold whose header was painted under the next block, and the click handler
 * belonged to the paint pass rather than to a control. The picker now consists
 * of a real button row drawn by the screen and a floating layer that the screen
 * paints <em>after</em> every widget (see {@code VeriScreen#overlay}), so it is
 * always on top:</p>
 *
 * <ul>
 *   <li>the button shows the whitelist title plus “selected: N” and a chevron;</li>
 *   <li>clicking it opens the layer below the button (or above when there is no
 *       room), clicking it again closes it;</li>
 *   <li>the layer has a search box, one click toggles a country, the action row
 *       selects all filtered entries / clears the selection, and the rows scroll
 *       with the wheel (with a scrollbar);</li>
 *   <li>Esc or a click outside closes it, and that outside click is
 *       <b>not</b> swallowed – the control under the cursor still receives it;</li>
 *   <li>an empty registry shows “register a country/region first” instead of an
 *       empty list.</li>
 * </ul>
 *
 * <p>All rules that do not need drawing live in {@link CountryPickerState} and
 * are unit-tested; this class only owns geometry, painting and input routing.</p>
 */
public final class CountryPicker {
    public static final int BUTTON_HEIGHT = 18;
    private static final int PAD = 3;
    private static final int TITLE_HEIGHT = 12;
    private static final int SEARCH_HEIGHT = 15;
    private static final int ROW_HEIGHT = 13;
    private static final int ACTION_HEIGHT = 15;
    private static final int SCROLLBAR_WIDTH = 2;

    private final Font font;
    private final CountryPickerState state;
    private final String titleKey;
    private int x;
    private int y;
    private int width;

    private int limitTop;
    private int limitBottom = Integer.MAX_VALUE;
    private boolean open;
    @Nullable
    private EditBox search;

    private CountryPicker(Font font, int x, int y, int width, List<String> selected, String titleKey) {
        this.font = font;
        this.x = x;
        this.y = y;
        this.width = Math.max(80, width);
        this.titleKey = titleKey == null ? "" : titleKey;
        this.state = new CountryPickerState(selected);
    }

    /** Creates a picker whose selected codes are the screen's live list. */
    public static CountryPicker at(Font font, int x, int y, int width, List<String> selected, String titleKey) {
        return new CountryPicker(font, x, y, width, selected, titleKey);
    }

    /** Registry snapshot offered by the layer (converted to the pure state). */
    public CountryPicker countries(List<CredentialRegistryData.Country> values) {
        List<CountryPickerState.Entry> entries = new ArrayList<>();
        if (values != null) {
            for (CredentialRegistryData.Country country : values) {
                if (country != null && country.valid()) {
                    entries.add(new CountryPickerState.Entry(country.code(), country.nameZh(),
                            country.nameEn(), country.defaultNationality()));
                }
            }
        }
        state.countries(entries);
        return this;
    }

    /** Design-space limits of the page; the layer flips / clamps against them. */
    public CountryPicker bounds(int top, int bottom) {
        this.limitTop = top;
        this.limitBottom = bottom;
        return this;
    }

    /** Visible rows offered by the layer (default 6). */
    public CountryPicker rows(int value) {
        state.rows(value);
        return this;
    }

    /** Moves the button row (scrolling screens keep an open layer attached). */
    public void moveTo(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public CountryPickerState state() {
        return state;
    }

    public boolean isOpen() {
        return open;
    }

    public int bottom() {
        return y + BUTTON_HEIGHT;
    }

    public List<String> selectedCodes() {
        return state.selectedCodes();
    }

    public int selectedCount() {
        return state.selectedCount();
    }

    // ------------------------------------------------------------------ open

    public void open() {
        if (open) {
            return;
        }
        open = true;
        state.query("");
        ensureSearch();
        if (search != null) {
            search.setValue("");
            search.setFocused(true);
        }
    }

    public void close() {
        open = false;
        state.query("");
        if (search != null) {
            search.setValue("");
            search.setFocused(false);
        }
    }

    public void toggleOpen() {
        if (open) {
            close();
        } else {
            open();
        }
    }

    private void ensureSearch() {
        if (search != null) {
            return;
        }
        search = new EditBox(font, x, y, width, 10, Component.empty());
        search.setBordered(false);
        search.setTextColor(0xFFE9F3FF);
        search.setMaxLength(40);
        search.setResponder(state::query);
    }

    // -------------------------------------------------------------- geometry

    private int rowCount() {
        return state.rows();
    }

    public int panelHeight() {
        return PAD * 2 + TITLE_HEIGHT + SEARCH_HEIGHT + rowCount() * ROW_HEIGHT + ACTION_HEIGHT;
    }

    public int panelX() {
        return x;
    }

    public int panelWidth() {
        return width;
    }

    public int panelTop() {
        int height = panelHeight();
        int limit = limitBottom == Integer.MAX_VALUE ? Integer.MAX_VALUE : limitBottom;
        int below = y + BUTTON_HEIGHT + 2;
        if (below + height <= limit) {
            return below;
        }
        int above = y - 2 - height;
        if (above >= limitTop) {
            return above;
        }
        int clamped = limit - height;
        return Math.max(limitTop, clamped);
    }

    private int titleTop() {
        return panelTop() + PAD;
    }

    public int searchTop() {
        return titleTop() + TITLE_HEIGHT;
    }

    private int searchX() {
        return panelX() + PAD + 2;
    }

    private int searchWidth() {
        return panelWidth() - PAD * 2 - 4;
    }

    private int rowsTop() {
        return searchTop() + SEARCH_HEIGHT;
    }

    private int actionsTop() {
        return rowsTop() + rowCount() * ROW_HEIGHT;
    }

    private int[] selectAllRect() {
        int half = (panelWidth() - PAD * 2 - 4) / 2;
        return new int[]{panelX() + PAD, actionsTop() + 1, half, ACTION_HEIGHT - 3};
    }

    private int[] clearRect() {
        int half = (panelWidth() - PAD * 2 - 4) / 2;
        return new int[]{panelX() + PAD + half + 4, actionsTop() + 1, half, ACTION_HEIGHT - 3};
    }

    private void positionSearch() {
        if (search == null) {
            return;
        }
        search.setX(searchX());
        search.setY(searchTop() + 3);
        search.setWidth(searchWidth());
    }

    // ------------------------------------------------------------------ draw

    /** Paints the button row (called from the screen's content pass). */
    public void drawButton(GuiGraphics graphics, int accent, double mouseX, double mouseY) {
        boolean hovered = inside(mouseX, mouseY, x, y, width, BUTTON_HEIGHT);
        int fill = open ? Ui.blend(Ui.PANEL_3, accent, 0.20F)
                : hovered ? Ui.blend(Ui.PANEL_2, accent, 0.10F) : Ui.PANEL_2;
        graphics.fill(x, y, x + width, y + BUTTON_HEIGHT, fill);
        graphics.renderOutline(x, y, width, BUTTON_HEIGHT, open ? accent : Ui.BORDER);
        graphics.fill(x, y, x + 3, y + BUTTON_HEIGHT, accent);
        String title = Component.translatable(titleKey).getString();
        String count = Component.translatable("picker.veriborder.selected", state.selectedCount()).getString();
        int countWidth = font.width(count);
        graphics.drawString(font, Ui.ellipsis(title, width - countWidth - 26), x + 9, y + 5, Ui.TEXT, false);
        graphics.drawString(font, count, x + width - countWidth - 14, y + 5,
                state.selectedCount() > 0 ? Ui.GOLD : Ui.DIM, false);
        graphics.drawString(font, open ? "\u25BE" : "\u25B8", x + width - 11, y + 5, accent, false);
    }

    /**
     * Paints the floating layer. The screen must call this from
     * {@code VeriScreen#overlay} (after every widget) so it is always on top.
     */
    public void drawPanel(GuiGraphics graphics, int accent, double mouseX, double mouseY) {
        if (!open) {
            return;
        }
        ensureSearch();
        positionSearch();
        int panelX = panelX();
        int panelTop = panelTop();
        int panelWidth = panelWidth();
        int height = panelHeight();
        graphics.fill(panelX, panelTop, panelX + panelWidth, panelTop + height, 0xFF0B1621);
        graphics.renderOutline(panelX, panelTop, panelWidth, height, accent);
        graphics.fill(panelX, panelTop, panelX + panelWidth, panelTop + 1, accent);

        // Title + selected badge.
        String title = Component.translatable(titleKey).getString();
        String count = Component.translatable("picker.veriborder.selected", state.selectedCount()).getString();
        int countWidth = font.width(count);
        graphics.drawString(font, Ui.ellipsis(title, panelWidth - countWidth - 16),
                panelX + PAD + 1, titleTop(), accent, false);
        graphics.drawString(font, count, panelX + panelWidth - countWidth - PAD, titleTop(), Ui.GOLD, false);

        // Search well: the EditBox itself is painted on top at the end.
        int searchTop = searchTop();
        graphics.fill(searchX() - 2, searchTop, searchX() + searchWidth() + 2, searchTop + SEARCH_HEIGHT,
                0xFF091119);
        graphics.renderOutline(searchX() - 2, searchTop, searchWidth() + 4, SEARCH_HEIGHT, Ui.BORDER);
        graphics.drawString(font, "\u2315", searchX(), searchTop + 4, Ui.DIM, false);
        if (state.query().isBlank()) {
            graphics.drawString(font, Ui.ellipsis(Component.translatable("picker.veriborder.search").getString(),
                    searchWidth() - 8), searchX() + 8, searchTop + 4, Ui.DIM, false);
        }

        // Rows.
        int rowsTop = rowsTop();
        List<CountryPickerState.Entry> visible = state.visible();
        if (state.empty()) {
            graphics.drawString(font, Ui.ellipsis(
                            Component.translatable("checklist.veriborder.empty_registry").getString(),
                            panelWidth - PAD * 2),
                    panelX + PAD, rowsTop + 3, Ui.AMBER, false);
        } else if (visible.isEmpty()) {
            graphics.drawString(font, Ui.ellipsis(
                            Component.translatable("checklist.veriborder.no_match").getString(),
                            panelWidth - PAD * 2),
                    panelX + PAD, rowsTop + 3, Ui.DIM, false);
        } else {
            for (int row = 0; row < visible.size(); row++) {
                CountryPickerState.Entry entry = visible.get(row);
                int rowY = rowsTop + row * ROW_HEIGHT;
                boolean ticked = state.isSelected(entry.code());
                boolean hovered = mouseX >= panelX + 2 && mouseX <= panelX + panelWidth - 2
                        && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
                if (ticked || hovered) {
                    graphics.fill(panelX + 2, rowY, panelX + panelWidth - 2, rowY + ROW_HEIGHT - 1,
                            ticked ? Ui.blend(Ui.PANEL_2, accent, 0.22F) : 0x221E3346);
                }
                int boxX = panelX + PAD + 1;
                int boxY = rowY + 3;
                graphics.fill(boxX, boxY, boxX + 8, boxY + 8, ticked ? accent : 0xFF0B1621);
                graphics.renderOutline(boxX, boxY, 8, 8, ticked ? accent : Ui.BORDER);
                if (ticked) {
                    graphics.drawString(font, "\u2713", boxX + 1, boxY - 1, 0xFF07101A, false);
                }
                graphics.drawString(font, entry.code(), boxX + 14, rowY + 2, Ui.GOLD, false);
                graphics.drawString(font, Ui.ellipsis(label(entry), panelWidth - 62),
                        boxX + 44, rowY + 2, ticked ? Ui.TEXT : Ui.MUTED, false);
            }
            // Scrollbar.
            int maxScroll = state.maxScroll();
            if (maxScroll > 0) {
                int trackTop = rowsTop;
                int trackHeight = rowCount() * ROW_HEIGHT;
                int thumbHeight = Math.max(6, trackHeight * rowCount() / (rowCount() + maxScroll));
                int thumbTop = trackTop + (trackHeight - thumbHeight) * state.scroll() / maxScroll;
                int barX = panelX + panelWidth - PAD - SCROLLBAR_WIDTH + 1;
                graphics.fill(barX, trackTop, barX + SCROLLBAR_WIDTH, trackTop + trackHeight, 0x332B4056);
                graphics.fill(barX, thumbTop, barX + SCROLLBAR_WIDTH, thumbTop + thumbHeight, accent);
            }
        }

        // Action row.
        boolean anyCountry = !state.empty();
        drawAction(graphics, selectAllRect(), Component.translatable("checklist.veriborder.select_all").getString(),
                Ui.GREEN, anyCountry);
        drawAction(graphics, clearRect(), Component.translatable("checklist.veriborder.clear").getString(),
                Ui.RED, anyCountry || state.selectedCount() > 0);

        // The search EditBox is not a screen widget: paint it above the layer.
        if (search != null) {
            search.render(graphics, (int) mouseX, (int) mouseY, 0.0F);
        }
    }

    private String label(CountryPickerState.Entry entry) {
        if (!entry.nameZh().isBlank() && !entry.nameEn().isBlank()) {
            return entry.nameZh() + " · " + entry.nameEn();
        }
        return entry.nameZh().isBlank() ? entry.nameEn() : entry.nameZh();
    }

    private void drawAction(GuiGraphics graphics, int[] rect, String label, int color, boolean enabled) {
        graphics.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3],
                enabled ? Ui.PANEL_2 : 0xFF111A24);
        graphics.renderOutline(rect[0], rect[1], rect[2], rect[3], enabled ? color : 0xFF25303B);
        graphics.drawCenteredString(font, Ui.ellipsis(label, rect[2] - 6), rect[0] + rect[2] / 2, rect[1] + 3,
                enabled ? color : Ui.DIM);
    }

    // ----------------------------------------------------------------- input

    /**
     * Handles a design-space click. Returns {@code true} when the picker consumed
     * it. A click outside the open layer closes it and returns {@code false}, so
     * the control underneath still receives the click.
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!open) {
            if (inside(mouseX, mouseY, x, y, width, BUTTON_HEIGHT)) {
                open();
                return true;
            }
            return false;
        }
        if (inside(mouseX, mouseY, x, y, width, BUTTON_HEIGHT)) {
            close();
            return true;
        }
        int panelTop = panelTop();
        if (!inside(mouseX, mouseY, panelX(), panelTop, panelWidth(), panelHeight())) {
            close();
            return false;
        }
        if (search != null) {
            positionSearch();
            if (inside(mouseX, mouseY, searchX() - 2, searchTop(), searchWidth() + 4, SEARCH_HEIGHT)) {
                search.setFocused(true);
                search.mouseClicked(mouseX, mouseY, button);
                return true;
            }
        }
        if (inside(mouseX, mouseY, selectAllRect())) {
            state.selectAllFiltered();
            return true;
        }
        if (inside(mouseX, mouseY, clearRect())) {
            state.clear();
            return true;
        }
        double rowsTop = rowsTop();
        if (mouseY >= rowsTop && mouseY < rowsTop + rowCount() * ROW_HEIGHT) {
            int row = (int) ((mouseY - rowsTop) / ROW_HEIGHT);
            CountryPickerState.Entry entry = state.entryAt(state.indexAtRow(row));
            if (entry != null) {
                state.toggle(entry.code());
            }
            return true;
        }
        // Padding / title / search chrome: consume so the click never falls through.
        return true;
    }

    /** Wheel over the layer scrolls its rows; elsewhere it falls through. */
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!open) {
            return false;
        }
        double rowsTop = rowsTop();
        if (mouseX < panelX() || mouseX > panelX() + panelWidth()
                || mouseY < rowsTop || mouseY > rowsTop + rowCount() * ROW_HEIGHT) {
            return false;
        }
        // Consume the wheel even at the end of the list: the page underneath must
        // not scroll away from the button the layer belongs to.
        state.scrollBy(delta);
        return true;
    }

    /**
     * Keyboard while open: Esc closes; everything else goes to the search box.
     * Always consumes while open so the screen's own shortcuts (Esc would close
     * the screen, Backspace can act elsewhere) never steal a keystroke.
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!open) {
            return false;
        }
        if (keyCode == 256) {
            close();
            return true;
        }
        if (search != null) {
            search.keyPressed(keyCode, scanCode, modifiers);
        }
        return true;
    }

    /** Typed characters land in the search box while the layer is open. */
    public boolean charTyped(char codePoint, int modifiers) {
        if (!open) {
            return false;
        }
        if (search != null) {
            search.charTyped(codePoint, modifiers);
        }
        return true;
    }

    private static boolean inside(double mouseX, double mouseY, int[] rect) {
        return inside(mouseX, mouseY, rect[0], rect[1], rect[2], rect[3]);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }
}
