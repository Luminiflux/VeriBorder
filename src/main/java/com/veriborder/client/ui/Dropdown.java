package com.veriborder.client.ui;

import com.veriborder.client.Ui;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Shared dropdown (26.4) that replaces the old "click to cycle" buttons.
 *
 * <p>The closed control shows {@code prefix + current value + ▾}. Clicking it
 * opens a floating option list below the control (or above it when there is no
 * room); the list never pushes the surrounding layout around. Every part of the
 * interaction is owned by this class:</p>
 *
 * <ul>
 *   <li><b>mouse</b>: click a row to select it, wheel to scroll, click outside to
 *       dismiss;</li>
 *   <li><b>keyboard</b>: ↑/↓ move the highlight, Enter commits, Esc closes;</li>
 *   <li><b>long lists</b>: more than {@value #SEARCH_THRESHOLD} entries get a
 *       search row and a scrollbar, so a 500-country registry stays usable;</li>
 *   <li><b>disabled state</b>: a locked value is painted dim and ignores input;</li>
 *   <li><b>tooltip</b>: a hovered control can explain what it changes.</li>
 * </ul>
 *
 * <p>Screens register instances through {@code VeriScreen.addDropdown(...)};
 * the base screen paints them above every widget and forwards input, so a screen
 * only supplies the options and reacts to {@link #onChange(Consumer)}.</p>
 */
public final class Dropdown {
    public static final int HEIGHT = 18;
    /** Narrowest control the component draws (keeps the arrow and 2-3 chars). */
    public static final int MIN_WIDTH = 36;
    /** Above this many options the panel grows a search row. */
    public static final int SEARCH_THRESHOLD = 8;

    private static final int ROW_HEIGHT = 13;
    private static final int MAX_VISIBLE = 8;
    private static final int PANEL_PADDING = 2;
    private static final int SEARCH_HEIGHT = 13;
    private static final int MAX_QUERY_LENGTH = 40;

    /** One selectable value; the id is what the screen persists. */
    public record Option(String id, String label) {
    }

    private final List<Option> options = new ArrayList<>();
    private final List<Option> filtered = new ArrayList<>();

    private int x;
    private int y;
    private int width = 120;
    private int height = HEIGHT;
    private int panelBottom = Integer.MAX_VALUE;

    private String selectedId = "";
    private String prefix = "";
    private String labelKey = "";
    private String placeholder = "";
    private Component tooltip;
    private boolean enabled = true;
    private boolean open;

    private String query = "";
    private int highlighted;
    private int scroll;

    private Consumer<String> onChange;

    private Dropdown(int x, int y, int width) {
        this.x = x;
        this.y = y;
        this.width = Math.max(MIN_WIDTH, width);
    }

    public static Dropdown at(int x, int y, int width) {
        return new Dropdown(x, y, width);
    }

    // ------------------------------------------------------------- configured

    /** Control height (the default matches the old 18px buttons). */
    public Dropdown height(int value) {
        this.height = Math.max(12, value);
        return this;
    }

    /** Design-space y past which an open panel flips above the control. */
    public Dropdown panelBottom(int value) {
        this.panelBottom = value;
        return this;
    }

    /** Muted text drawn above the closed control (like the old label chips). */
    public Dropdown labelKey(String key) {
        this.labelKey = key == null ? "" : key;
        return this;
    }

    /** Text drawn before the current value inside the control ("Sex: "). */
    public Dropdown prefix(String value) {
        this.prefix = value == null ? "" : value;
        return this;
    }

    public Dropdown placeholder(String value) {
        this.placeholder = value == null ? "" : value;
        return this;
    }

    public Dropdown tooltip(@Nullable Component value) {
        this.tooltip = value;
        return this;
    }

    public Dropdown tooltip(String translationKey) {
        this.tooltip = translationKey == null || translationKey.isBlank()
                ? null : Component.translatable(translationKey);
        return this;
    }

    public Dropdown options(List<Option> values) {
        options.clear();
        if (values != null) {
            options.addAll(values);
        }
        if (!selectedId.isBlank() && selectedIndex() < 0) {
            selectedId = "";
        }
        resetView();
        return this;
    }

    /** Initial value; does not fire {@link #onChange(Consumer)}. */
    public Dropdown selected(String id) {
        this.selectedId = id == null ? "" : id;
        resetView();
        return this;
    }

    public Dropdown onChange(Consumer<String> listener) {
        this.onChange = listener;
        return this;
    }

    /** Disabled controls are painted dim and never open. */
    public Dropdown enabled(boolean value) {
        this.enabled = value;
        if (!value) {
            close();
        }
        return this;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ------------------------------------------------------------------ state

    public String selectedId() {
        return selectedId;
    }

    public boolean isOpen() {
        return open;
    }

    public boolean open() {
        return open;
    }

    @Nullable
    public Component tooltip() {
        return tooltip;
    }

    public void setOpen(boolean value) {
        if (value && enabled && !options.isEmpty()) {
            open = true;
            query = "";
            highlighted = Math.max(0, selectedIndex());
            scroll = 0;
            ensureVisible();
        } else if (!value) {
            close();
        }
    }

    public void toggle() {
        setOpen(!open);
    }

    private void close() {
        open = false;
        query = "";
        scroll = 0;
        highlighted = 0;
    }

    private void resetView() {
        query = "";
        scroll = 0;
        highlighted = Math.max(0, selectedIndex());
    }

    @Nullable
    public Option selected() {
        int index = selectedIndex();
        return index < 0 ? null : options.get(index);
    }

    private int selectedIndex() {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).id().equals(selectedId)) {
                return i;
            }
        }
        return -1;
    }

    /** Text shown in the closed control. */
    public String display() {
        Option option = selected();
        if (option != null) {
            return option.label();
        }
        if (!placeholder.isBlank()) {
            return placeholder;
        }
        return "—";
    }

    /** Options matching the current search query (all of them when blank). */
    public List<Option> currentOptions() {
        if (query.isBlank()) {
            return options;
        }
        filtered.clear();
        String needle = query.toLowerCase(Locale.ROOT);
        for (Option option : options) {
            if (option.label().toLowerCase(Locale.ROOT).contains(needle)
                    || option.id().toLowerCase(Locale.ROOT).contains(needle)) {
                filtered.add(option);
            }
        }
        return filtered;
    }

    private boolean searchable() {
        return options.size() > SEARCH_THRESHOLD;
    }

    private int searchHeight() {
        return searchable() ? SEARCH_HEIGHT : 0;
    }

    private int visibleRows() {
        int count = currentOptions().size();
        return Math.min(MAX_VISIBLE, Math.max(1, count));
    }

    public int panelHeight() {
        return PANEL_PADDING * 2 + searchHeight() + visibleRows() * ROW_HEIGHT;
    }

    /** Top of the open panel in design space (flips above when it would clip). */
    public int overlayTop() {
        int below = y + height + 1;
        if (below + panelHeight() > panelBottom) {
            return Math.max(0, y - 1 - panelHeight());
        }
        return below;
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    /** Design-space y of the closed control. */
    public int y() {
        return y;
    }

    /**
     * Moves the control vertically. Scrolling screens shift their widgets with
     * the flow layout; an open panel follows because it is positioned from this
     * value, and it still flips against the absolute {@link #panelBottom(int)}.
     */
    public void setY(int value) {
        this.y = value;
    }

    /** Whether the point is inside the open panel (used to swallow wheel events). */
    public boolean panelContains(double mouseX, double mouseY) {
        if (!open) {
            return false;
        }
        int top = overlayTop();
        return mouseX >= x && mouseX <= x + width && mouseY >= top && mouseY <= top + panelHeight();
    }

    // ------------------------------------------------------------------ input

    /**
     * Handles a click in design space. Returns {@code true} when the dropdown
     * consumed it: a row was picked, the control toggled, or an open panel was
     * dismissed (so the click never falls through to a widget underneath).
     */
    public boolean mouseClicked(double mouseX, double mouseY) {
        if (open) {
            int top = overlayTop();
            int listTop = top + PANEL_PADDING + searchHeight();
            if (mouseX >= x && mouseX <= x + width && mouseY >= top && mouseY <= top + panelHeight()) {
                if (mouseY < listTop) {
                    // Search row: keep the panel open, typing is handled by keys.
                    return true;
                }
                int row = (int) ((mouseY - listTop) / ROW_HEIGHT);
                List<Option> list = currentOptions();
                int index = scroll + row;
                if (row >= 0 && index >= 0 && index < list.size()) {
                    apply(list.get(index).id());
                }
                close();
                return true;
            }
            close();
            return true;
        }
        if (enabled && contains(mouseX, mouseY)) {
            setOpen(true);
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!open || !panelContains(mouseX, mouseY)) {
            return false;
        }
        int maxScroll = Math.max(0, currentOptions().size() - visibleRows());
        if (maxScroll == 0) {
            return true;
        }
        scroll = clamp(scroll + (delta > 0 ? -1 : 1), 0, maxScroll);
        return true;
    }

    /** Keyboard handling while open: ↑/↓, Enter, Esc, Backspace (search). */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!open) {
            return false;
        }
        switch (keyCode) {
            case 256 -> {          // Escape
                close();
                return true;
            }
            case 265 -> {          // Up
                moveHighlight(-1);
                return true;
            }
            case 264 -> {          // Down
                moveHighlight(1);
                return true;
            }
            case 257, 335 -> {     // Enter / numpad Enter
                List<Option> list = currentOptions();
                if (!list.isEmpty()) {
                    apply(list.get(clamp(highlighted, 0, list.size() - 1)).id());
                }
                close();
                return true;
            }
            case 259 -> {          // Backspace
                if (searchable() && !query.isEmpty()) {
                    query = query.substring(0, query.length() - 1);
                    rehighlight();
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Printable characters feed the search field of a long list. */
    public boolean charTyped(char codePoint, int modifiers) {
        if (!open || !searchable() || query.length() >= MAX_QUERY_LENGTH) {
            return false;
        }
        if (codePoint < ' ' || codePoint == 127) {
            return false;
        }
        query += codePoint;
        rehighlight();
        return true;
    }

    private void moveHighlight(int delta) {
        List<Option> list = currentOptions();
        if (list.isEmpty()) {
            return;
        }
        highlighted = Math.floorMod(highlighted + delta, list.size());
        ensureVisible();
    }

    private void rehighlight() {
        highlighted = 0;
        scroll = 0;
        ensureVisible();
    }

    private void ensureVisible() {
        int rows = visibleRows();
        int count = currentOptions().size();
        if (highlighted < scroll) {
            scroll = highlighted;
        } else if (highlighted >= scroll + rows) {
            scroll = highlighted - rows + 1;
        }
        scroll = clamp(scroll, 0, Math.max(0, count - rows));
    }

    private void apply(String id) {
        selectedId = id;
        if (onChange != null) {
            onChange.accept(id);
        }
    }

    // ------------------------------------------------------------------- draw

    /** Draws the closed control (and its label / tooltip state). */
    public void draw(GuiGraphics graphics, Font font, int accent, double mouseX, double mouseY) {
        if (!labelKey.isBlank()) {
            String label = Component.translatable(labelKey).getString();
            graphics.drawString(font, Ui.ellipsis(label, width), x, y - 10, Ui.DIM, false);
        }
        int border = !enabled ? 0xFF26313D : open ? accent : Ui.BORDER;
        int fill = !enabled ? Ui.PANEL_2 : open ? 0xFF0E1B28 : 0xFF0B1621;
        graphics.fill(x, y, x + width, y + height, fill);
        graphics.renderOutline(x, y, width, height, border);
        graphics.fill(x, y, x + 3, y + height, enabled ? accent : 0xFF26313D);

        int textY = y + (height - 8) / 2;
        int cursorX = x + 7;
        int limitX = x + width - 14;
        if (!prefix.isBlank()) {
            String drawn = font.plainSubstrByWidth(prefix, Math.max(0, limitX - cursorX));
            graphics.drawString(font, drawn, cursorX, textY, enabled ? Ui.MUTED : Ui.DIM, false);
            cursorX += font.width(drawn);
        }
        String value = display();
        int room = limitX - cursorX;
        if (room > 4) {
            int color = !enabled ? Ui.DIM : selected() == null ? Ui.DIM : Ui.TEXT;
            graphics.drawString(font, font.plainSubstrByWidth(value, room), cursorX, textY, color, false);
        }
        graphics.drawString(font, open ? "\u25B4" : "\u25BE", x + width - 11, textY,
                enabled ? accent : Ui.DIM, false);
    }

    /** Draws the dropped panel; the screen calls this after every other widget. */
    public void drawOverlay(GuiGraphics graphics, Font font, int accent, double mouseX, double mouseY) {
        if (!open) {
            return;
        }
        int top = overlayTop();
        int panelHeight = panelHeight();
        graphics.fill(x, top, x + width, top + panelHeight, 0xFF0B1520);
        graphics.renderOutline(x, top, width, panelHeight, accent);
        Ui.scissor(graphics, x, top, x + width, top + panelHeight);
        try {
            int listTop = top + PANEL_PADDING + searchHeight();
            if (searchable()) {
                int searchTop = top + PANEL_PADDING;
                graphics.fill(x + 2, searchTop, x + width - 2, searchTop + SEARCH_HEIGHT - 1, 0xFF091119);
                graphics.renderOutline(x + 2, searchTop, width - 4, SEARCH_HEIGHT - 1, Ui.BORDER);
                String shown = query.isEmpty()
                        ? Component.translatable("dropdown.veriborder.search_hint").getString()
                        : query + "|";
                graphics.drawString(font, Ui.ellipsis(shown, width - 12), x + 6, searchTop + 3,
                        query.isEmpty() ? Ui.DIM : Ui.TEXT, false);
            }
            List<Option> list = currentOptions();
            int rows = visibleRows();
            if (list.isEmpty()) {
                graphics.drawString(font,
                        Component.translatable("dropdown.veriborder.no_matches").getString(),
                        x + 6, listTop + 2, Ui.DIM, false);
            }
            for (int row = 0; row < rows; row++) {
                int index = scroll + row;
                if (index >= list.size()) {
                    break;
                }
                Option option = list.get(index);
                int rowY = listTop + row * ROW_HEIGHT;
                boolean isSelected = option.id().equals(selectedId);
                boolean isHighlighted = index == highlighted
                        || (mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT);
                if (isSelected || isHighlighted) {
                    graphics.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT - 1,
                            isSelected ? Ui.blend(Ui.PANEL_2, accent, 0.35F) : 0xFF1A2A3B);
                }
                if (isSelected) {
                    graphics.fill(x + 1, rowY, x + 3, rowY + ROW_HEIGHT - 1, accent);
                }
                graphics.drawString(font, Ui.ellipsis(option.label(), width - 14), x + 6, rowY + 2,
                        isSelected ? Ui.TEXT : Ui.MUTED, false);
            }
            if (list.size() > rows) {
                int trackHeight = rows * ROW_HEIGHT;
                int thumb = Math.max(5, trackHeight * rows / list.size());
                int thumbTop = listTop + (trackHeight - thumb) * scroll / Math.max(1, list.size() - rows);
                graphics.fill(x + width - 3, listTop, x + width - 1, listTop + trackHeight, 0x332B4056);
                graphics.fill(x + width - 3, thumbTop, x + width - 1, thumbTop + thumb, accent);
            }
        } finally {
            graphics.disableScissor();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
