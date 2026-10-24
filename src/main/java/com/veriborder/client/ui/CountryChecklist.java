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
import java.util.Locale;

/**
 * Collapsible registered-country checklist shared by the gate brush and the
 * dual-clearance gate brush (26.3 round 12).
 *
 * <p>One row per registered country/region with a tick box: a single click
 * toggles that code, the search field filters by code / Chinese name / English
 * name, and the action row offers “select all” (of the filtered set) and
 * “clear”. An empty registry shows a hint instead of an empty box, so the
 * operator knows to register a country first.</p>
 *
 * <p>The widget owns its geometry and hit testing; the owning screen forwards its
 * already-unprojected mouse coordinates, adds the search {@link EditBox} when the
 * checklist is open, and draws it from {@code renderBackground}. The selected
 * codes live in the screen's list (passed in by reference), so saving is
 * unchanged.</p>
 */
public final class CountryChecklist {
    public static final int HEADER_HEIGHT = 18;
    public static final int SEARCH_HEIGHT = 15;
    public static final int ROW_HEIGHT = 13;
    public static final int ACTIONS_HEIGHT = 15;
    /** Rows shown while open by default; more countries scroll. */
    public static final int VISIBLE_ROWS = 4;

    private final List<String> selected;
    private final String titleKey;
    private int x;
    private int y;
    private int width = 200;
    private int rows = VISIBLE_ROWS;
    private List<CredentialRegistryData.Country> countries = List.of();
    private boolean open;
    private String query = "";
    private int scroll;
    @Nullable
    private EditBox search;

    private CountryChecklist(int x, int y, int width, List<String> selected, String titleKey) {
        this.x = x;
        this.y = y;
        this.width = Math.max(40, width);
        this.selected = selected == null ? new ArrayList<>() : selected;
        this.titleKey = titleKey == null ? "" : titleKey;
    }

    /** Creates a checklist bound to the screen's mutable selection list. */
    public static CountryChecklist at(int x, int y, int width, List<String> selected, String titleKey) {
        return new CountryChecklist(x, y, width, selected, titleKey);
    }

    public CountryChecklist countries(List<CredentialRegistryData.Country> values) {
        this.countries = values == null ? List.of() : values;
        this.scroll = Math.max(0, Math.min(scroll, Math.max(0, filtered().size() - rows)));
        return this;
    }

    /** Number of visible rows while open (1..{@link #VISIBLE_ROWS}); default 4. */
    public CountryChecklist rows(int value) {
        this.rows = Math.max(1, Math.min(VISIBLE_ROWS, value));
        return this;
    }

    /** Attaches the screen-created search box (only while open). */
    public CountryChecklist search(@Nullable EditBox box) {
        this.search = box;
        if (box == null) {
            query = "";
        }
        return this;
    }

    public boolean open() {
        return open;
    }

    public void setOpen(boolean value) {
        open = value;
        if (!open) {
            query = "";
            scroll = 0;
        }
    }

    public void toggleOpen() {
        setOpen(!open);
    }

    public int selectedCount() {
        return selected.size();
    }

    /** Design-space x of the checklist block. */
    public int x() {
        return x;
    }

    /** Design-space width of the checklist block. */
    public int width() {
        return width;
    }

    public List<String> selectedCodes() {
        return selected;
    }

    public int height() {
        return open ? openHeight() : HEADER_HEIGHT;
    }

    public int openHeight() {
        return HEADER_HEIGHT + 2 + SEARCH_HEIGHT + rows * ROW_HEIGHT + ACTIONS_HEIGHT;
    }

    public int bottom() {
        return y + height();
    }

    // --------------------------------------------------------------- geometry

    /** Design-space y of the search well (the screen places its EditBox here). */
    public int searchTop() {
        return y + HEADER_HEIGHT + 2;
    }

    /** Current search text (the screen re-creates the box after a rebuild). */
    public String query() {
        return query;
    }

    private int rowsTop() {
        return searchTop() + SEARCH_HEIGHT;
    }

    private int actionsTop() {
        return rowsTop() + rows * ROW_HEIGHT;
    }

    private int[] selectAllRect() {
        return new int[]{x, actionsTop(), (width - 4) / 2, ACTIONS_HEIGHT - 2};
    }

    private int[] clearRect() {
        int half = (width - 4) / 2;
        return new int[]{x + width - half, actionsTop(), half, ACTIONS_HEIGHT - 2};
    }

    /** Countries matching the current search query, in registry order. */
    public List<CredentialRegistryData.Country> filtered() {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return countries;
        }
        List<CredentialRegistryData.Country> result = new ArrayList<>();
        for (CredentialRegistryData.Country country : countries) {
            if (country.code().toLowerCase(Locale.ROOT).contains(needle)
                    || country.nameZh().toLowerCase(Locale.ROOT).contains(needle)
                    || country.nameEn().toLowerCase(Locale.ROOT).contains(needle)
                    || country.defaultNationality().toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(country);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ draw

    public void draw(GuiGraphics graphics, Font font) {
        int accent = Ui.CYAN;
        // Header: title, count badge and a fold chevron.
        int fill = open ? Ui.blend(Ui.PANEL_3, accent, 0.18F) : Ui.PANEL_2;
        graphics.fill(x, y, x + width, y + HEADER_HEIGHT, fill);
        graphics.renderOutline(x, y, width, HEADER_HEIGHT, open ? accent : Ui.BORDER);
        graphics.fill(x, y, x + 3, y + HEADER_HEIGHT, accent);
        String title = titleKey.isBlank() ? "" : Component.translatable(titleKey).getString();
        String count = selected.size() + "/" + countries.size();
        String header = Ui.ellipsis(title, Math.max(20, width - 20 - font.width(count)));
        graphics.drawString(font, header, x + 8, y + 5, Ui.TEXT, false);
        graphics.drawString(font, count, x + width - 12 - font.width(count), y + 5, Ui.MUTED, false);
        graphics.drawString(font, open ? "\u25BE" : "\u25B8", x + width - 10, y + 5, accent, false);
        if (!open) {
            return;
        }
        // Search well: the EditBox widget itself is registered by the screen and
        // painted above this chrome, so only the dark backing and the icon go here.
        int searchTop = searchTop();
        graphics.fill(x, searchTop, x + width, searchTop + SEARCH_HEIGHT, 0xFF091119);
        graphics.renderOutline(x, searchTop, width, SEARCH_HEIGHT, Ui.BORDER);
        graphics.drawString(font, "\u2315", x + 4, searchTop + 4, Ui.DIM, false);

        List<CredentialRegistryData.Country> visible = filtered();
        int rowsTop = rowsTop();
        Ui.scissor(graphics, x, rowsTop, x + width, rowsTop + rows * ROW_HEIGHT);
        try {
            if (visible.isEmpty()) {
                Component hint = countries.isEmpty()
                        ? Component.translatable("checklist.veriborder.empty_registry")
                        : Component.translatable("checklist.veriborder.no_match");
                graphics.drawString(font, Ui.ellipsis(hint.getString(), width - 8), x + 4, rowsTop + 3,
                        Ui.DIM, false);
            } else {
                for (int row = 0; row < rows; row++) {
                    int index = scroll + row;
                    if (index >= visible.size()) {
                        break;
                    }
                    CredentialRegistryData.Country country = visible.get(index);
                    int rowY = rowsTop + row * ROW_HEIGHT;
                    boolean ticked = selected.contains(country.code());
                    if (ticked) {
                        graphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT - 1,
                                Ui.blend(Ui.PANEL_2, accent, 0.22F));
                    }
                    // Tick box.
                    int boxX = x + 4;
                    int boxY = rowY + 3;
                    graphics.fill(boxX, boxY, boxX + 8, boxY + 8, ticked ? accent : 0xFF0B1621);
                    graphics.renderOutline(boxX, boxY, 8, 8, ticked ? accent : Ui.BORDER);
                    if (ticked) {
                        graphics.drawString(font, "\u2713", boxX + 1, boxY - 1, 0xFF07101A, false);
                    }
                    graphics.drawString(font, country.code(), x + 18, rowY + 2, Ui.GOLD, false);
                    String label = country.displayName(true) + " · " + country.displayName(false);
                    graphics.drawString(font, Ui.ellipsis(label, width - 80),
                            x + 52, rowY + 2, ticked ? Ui.TEXT : Ui.MUTED, false);
                }
                // Scroll indicator on the right edge.
                if (visible.size() > rows) {
                    int trackTop = rowsTop;
                    int trackHeight = rows * ROW_HEIGHT;
                    int thumbHeight = Math.max(6, trackHeight * rows / visible.size());
                    int thumbTop = trackTop + (trackHeight - thumbHeight) * scroll
                            / Math.max(1, visible.size() - rows);
                    graphics.fill(x + width - 3, trackTop, x + width - 1, trackTop + trackHeight, 0x332B4056);
                    graphics.fill(x + width - 3, thumbTop, x + width - 1, thumbTop + thumbHeight, accent);
                }
            }
        } finally {
            graphics.disableScissor();
        }
        // Action row: select all (filtered) / clear.
        drawAction(graphics, font, selectAllRect(), "checklist.veriborder.select_all", Ui.GREEN);
        drawAction(graphics, font, clearRect(), "checklist.veriborder.clear", Ui.RED);
    }

    private void drawAction(GuiGraphics graphics, Font font, int[] rect, String key, int color) {
        boolean enabled = countries.size() > 0;
        int fill = enabled ? Ui.PANEL_2 : 0xFF111A24;
        graphics.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3], fill);
        graphics.renderOutline(rect[0], rect[1], rect[2], rect[3], enabled ? color : 0xFF25303B);
        String label = Ui.ellipsis(Component.translatable(key).getString(), rect[2] - 6);
        graphics.drawCenteredString(font, label, rect[0] + rect[2] / 2, rect[1] + 3,
                enabled ? color : Ui.DIM);
    }

    // ------------------------------------------------------------------ input

    /**
     * Handles a design-space click. Returns true when the checklist consumed it
     * (header toggle, row tick, select all, clear, or a click inside its own area).
     */
    public boolean mouseClicked(double mouseX, double mouseY) {
        if (!inside(mouseX, mouseY, x, y, width, HEADER_HEIGHT)) {
            if (!open) {
                return false;
            }
            boolean insideOpen = mouseX >= x && mouseX <= x + width
                    && mouseY >= y && mouseY <= y + openHeight();
            if (!insideOpen) {
                return false;
            }
        } else {
            toggleOpen();
            return true;
        }
        if (inside(mouseX, mouseY, selectAllRect())) {
            for (CredentialRegistryData.Country country : filtered()) {
                if (!selected.contains(country.code())) {
                    selected.add(country.code());
                }
            }
            return true;
        }
        if (inside(mouseX, mouseY, clearRect())) {
            selected.clear();
            return true;
        }
        int rowsTop = rowsTop();
        if (mouseX >= x && mouseX <= x + width && mouseY >= rowsTop
                && mouseY < rowsTop + rows * ROW_HEIGHT) {
            int row = (int) ((mouseY - rowsTop) / ROW_HEIGHT);
            int index = scroll + row;
            List<CredentialRegistryData.Country> visible = filtered();
            if (index >= 0 && index < visible.size()) {
                String code = visible.get(index).code();
                if (!selected.remove(code)) {
                    selected.add(code);
                }
            }
            return true;
        }
        // Clicks on the search well are consumed here; the EditBox widget has
        // already had its chance in Screen#mouseClicked.
        return true;
    }

    /** Mouse wheel scrolls the visible rows while the cursor is over them. */
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!open) {
            return false;
        }
        int rowsTop = rowsTop();
        if (mouseX < x || mouseX > x + width || mouseY < rowsTop
                || mouseY > rowsTop + rows * ROW_HEIGHT) {
            return false;
        }
        int maxScroll = Math.max(0, filtered().size() - rows);
        if (maxScroll == 0) {
            return false;
        }
        scroll = Math.max(0, Math.min(maxScroll, scroll + (delta > 0 ? -1 : 1)));
        return true;
    }

    /** Reads the current search text from the attached box (call before drawing). */
    public void syncQuery() {
        if (search != null && open) {
            String value = search.getValue();
            if (!value.equals(query)) {
                query = value;
                scroll = 0;
            }
        }
    }

    private static boolean inside(double mouseX, double mouseY, int[] rect) {
        return inside(mouseX, mouseY, rect[0], rect[1], rect[2], rect[3]);
    }

    private static boolean inside(double mouseX, double mouseY, int left, int top, int width, int height) {
        return mouseX >= left && mouseX <= left + width && mouseY >= top && mouseY <= top + height;
    }
}
