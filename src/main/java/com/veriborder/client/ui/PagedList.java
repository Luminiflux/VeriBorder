package com.veriborder.client.ui;

import com.veriborder.client.Ui;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Shared paged list used by every 26.3 screen that shows an unbounded record
 * set (documents, border logs, visas, endorsements, stamps, history, integrity
 * checks, preset lists, configuration code grids).
 *
 * <p>The component owns page state, row geometry, hover/selection hit testing
 * and the footer navigation; the screen only supplies a {@link RowRenderer}
 * that paints one row. Rows are never scrolled by pixels: a list shows exactly
 * one page of {@link #perPage()} rows and flips pages, which keeps long stamp /
 * log histories readable at any GUI scale and makes every row hit box stable.</p>
 */
public final class PagedList<T> {
    /** Paints one row. Coordinates are design-space and already clipped. */
    public interface RowRenderer<T> {
        void render(GuiGraphics graphics, T item, int index, int x, int y, int width, int height,
                    boolean selected, boolean hovered);
    }

    /** Height reserved under the rows for the page navigation strip. */
    public static final int FOOTER_HEIGHT = 14;
    private static final int MIN_ROW_HEIGHT = 8;

    private final List<T> items = new ArrayList<>();
    private int x;
    private int y;
    private int width = 100;
    private int height = 40;
    private int rowHeight = 14;
    private int rowGap = 1;
    private int page;
    private int selected = -1;
    private boolean footer = true;
    private int accent = Ui.CYAN;
    private Component emptyText = Component.translatable("document.veriborder.no_records");
    private int hovered = -1;

    private PagedList() {
    }

    public static <T> PagedList<T> at(int x, int y, int width, int height) {
        PagedList<T> list = new PagedList<>();
        list.x = x;
        list.y = y;
        list.width = Math.max(8, width);
        list.height = Math.max(MIN_ROW_HEIGHT, height);
        return list;
    }

    public PagedList<T> rowHeight(int value) {
        this.rowHeight = Math.max(MIN_ROW_HEIGHT, value);
        return this;
    }

    public PagedList<T> rowGap(int value) {
        this.rowGap = Math.max(0, value);
        return this;
    }

    public PagedList<T> accent(int color) {
        this.accent = color;
        return this;
    }

    /** Reserves (or frees) the bottom navigation strip. */
    public PagedList<T> footer(boolean value) {
        this.footer = value;
        return this;
    }

    public PagedList<T> emptyText(Component text) {
        this.emptyText = text == null ? Component.empty() : text;
        return this;
    }

    public PagedList<T> bounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = Math.max(8, width);
        this.height = Math.max(MIN_ROW_HEIGHT, height);
        return this;
    }

    public PagedList<T> setItems(List<T> values) {
        items.clear();
        if (values != null) {
            items.addAll(values);
        }
        int pages = pageCount();
        page = Math.max(0, Math.min(page, pages - 1));
        if (selected >= items.size()) {
            selected = -1;
        }
        return this;
    }

    public List<T> items() {
        return items;
    }

    public int size() {
        return items.size();
    }

    public T item(int index) {
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    public int indexOf(T value) {
        return value == null ? -1 : items.indexOf(value);
    }

    public int indexOf(Function<T, Boolean> matcher) {
        for (int i = 0; i < items.size(); i++) {
            if (matcher.apply(items.get(i))) {
                return i;
            }
        }
        return -1;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int rowHeight() {
        return rowHeight;
    }

    public int rowGapValue() {
        return rowGap;
    }

    /** Height available to rows after the navigation strip is reserved. */
    public int listHeight() {
        int reserved = footer ? FOOTER_HEIGHT : 0;
        return Math.max(MIN_ROW_HEIGHT, height - reserved);
    }

    public int perPage() {
        int pitch = rowHeight + rowGap;
        return Math.max(1, (listHeight() + rowGap) / pitch);
    }

    public int pageCount() {
        int perPage = perPage();
        return Math.max(1, (items.size() + perPage - 1) / perPage);
    }

    public int page() {
        return page;
    }

    public void setPage(int value) {
        page = Math.max(0, Math.min(value, pageCount() - 1));
    }

    public void nextPage() {
        setPage(page + 1);
    }

    public void previousPage() {
        setPage(page - 1);
    }

    public boolean hasNext() {
        return page + 1 < pageCount();
    }

    public boolean hasPrevious() {
        return page > 0;
    }

    public int selectedIndex() {
        return selected;
    }

    public void setSelectedIndex(int index) {
        selected = index >= 0 && index < items.size() ? index : -1;
    }

    public T selectedItem() {
        return item(selected);
    }

    public boolean isSelected(int index) {
        return index == selected;
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    /** Absolute index under the point, or -1 when outside the row area. */
    public int indexAt(double mouseX, double mouseY) {
        if (mouseX < x || mouseX > x + width || mouseY < y || mouseY >= y + listHeight()) {
            return -1;
        }
        int row = (int) ((mouseY - y) / (rowHeight + rowGap));
        if (row < 0 || row >= perPage()) {
            return -1;
        }
        int index = page * perPage() + row;
        return index >= 0 && index < items.size() ? index : -1;
    }

    /**
     * Absolute design y of a row, or -1 when that row is not on the current
     * page. Used by screens that need an extra hit box inside a row (chips).
     */
    public int rowTop(int index) {
        if (index < 0 || index >= items.size()) {
            return -1;
        }
        int perPage = perPage();
        int row = index - page * perPage;
        if (row < 0 || row >= perPage) {
            return -1;
        }
        return y + row * (rowHeight + rowGap);
    }

    /** Row rectangle in design space, or {@code null} when off-page. */
    public int[] rowRect(int index) {
        int top = rowTop(index);
        if (top < 0) {
            return null;
        }
        return new int[]{x, top, width, rowHeight};
    }

    public int[] footerRect() {
        return new int[]{x, y + height - FOOTER_HEIGHT, width, FOOTER_HEIGHT};
    }

    private int[] previousRect() {
        int top = y + height - FOOTER_HEIGHT;
        return new int[]{x + width - 78, top + 1, 22, FOOTER_HEIGHT - 2};
    }

    private int[] nextRect() {
        int top = y + height - FOOTER_HEIGHT;
        return new int[]{x + width - 22, top + 1, 22, FOOTER_HEIGHT - 2};
    }

    /**
     * Handles row selection and page navigation. Returns true when the click was
     * consumed by this list.
     */
    public boolean mouseClicked(double mouseX, double mouseY) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        if (footer && mouseY >= y + height - FOOTER_HEIGHT) {
            if (inside(mouseX, mouseY, previousRect())) {
                previousPage();
                return true;
            }
            if (inside(mouseX, mouseY, nextRect())) {
                nextPage();
                return true;
            }
            return true;
        }
        int index = indexAt(mouseX, mouseY);
        if (index >= 0) {
            selected = index;
            return true;
        }
        return true;
    }

    /** Mouse wheel flips pages while the cursor is over the list. */
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!contains(mouseX, mouseY) || !footer) {
            return false;
        }
        if (delta > 0) {
            previousPage();
        } else if (delta < 0) {
            nextPage();
        }
        return true;
    }

    /**
     * Draws the page of rows, the empty placeholder and the navigation footer.
     * The caller has already applied the screen pose transform.
     */
    public void draw(GuiGraphics graphics, Font font, RowRenderer<T> renderer, double mouseX, double mouseY) {
        hovered = indexAt(mouseX, mouseY);
        int perPage = perPage();
        int start = page * perPage;
        if (items.isEmpty()) {
            String text = Ui.ellipsis(emptyText.getString(), width - 8);
            graphics.drawString(font, text, x + 4, y + 4, Ui.DIM, false);
            drawFooter(graphics, font);
            return;
        }
        Ui.scissor(graphics, x, y, x + width, y + listHeight());
        try {
            for (int row = 0; row < perPage; row++) {
                int index = start + row;
                if (index >= items.size()) {
                    break;
                }
                int rowY = y + row * (rowHeight + rowGap);
                if (index == hovered) {
                    graphics.fill(x, rowY, x + width, rowY + rowHeight - 1, 0x221E3346);
                }
                renderer.render(graphics, items.get(index), index, x, rowY, width, rowHeight - 1,
                        index == selected, index == hovered);
            }
        } finally {
            graphics.disableScissor();
        }
        drawFooter(graphics, font);
    }

    private void drawFooter(GuiGraphics graphics, Font font) {
        if (!footer) {
            return;
        }
        int top = y + height - FOOTER_HEIGHT;
        graphics.fill(x, top, x + width, top + 1, 0x332B4056);
        String counter = (page + 1) + "/" + pageCount() + "  (" + items.size() + ")";
        graphics.drawString(font, Ui.ellipsis(counter, Math.max(10, width - 92)), x + 2, top + 4,
                items.isEmpty() ? Ui.DIM : Ui.MUTED, false);
        drawArrow(graphics, font, previousRect(), "\u25C0", hasPrevious());
        drawArrow(graphics, font, nextRect(), "\u25B6", hasNext());
        // Page progress bar: shows at a glance how much of the list is shown.
        if (pageCount() > 1) {
            int barWidth = Math.max(0, width - 92);
            int filled = Math.max(4, barWidth * (page + 1) / pageCount());
            graphics.fill(x + 2, top + FOOTER_HEIGHT - 4, x + 2 + barWidth, top + FOOTER_HEIGHT - 3,
                    0x332A3B50);
            graphics.fill(x + 2, top + FOOTER_HEIGHT - 4, x + 2 + filled, top + FOOTER_HEIGHT - 3, accent);
        }
    }

    private void drawArrow(GuiGraphics graphics, Font font, int[] rect, String symbol, boolean enabled) {
        int color = enabled ? accent : Ui.DIM;
        graphics.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3], enabled ? Ui.PANEL_2 : 0xFF111A24);
        graphics.renderOutline(rect[0], rect[1], rect[2], rect[3], enabled ? Ui.BORDER : 0xFF25303B);
        graphics.drawCenteredString(font, symbol, rect[0] + rect[2] / 2, rect[1] + 2, color);
    }

    private static boolean inside(double mouseX, double mouseY, int[] rect) {
        return mouseX >= rect[0] && mouseX <= rect[0] + rect[2]
                && mouseY >= rect[1] && mouseY <= rect[1] + rect[3];
    }
}
