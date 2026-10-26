package com.veriborder.client.ui;

import com.veriborder.client.Ui;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Preset icon grid for registry templates (26.3 round 12).
 *
 * <p>The old free-text “icon” field became a picker: a fixed grid of procedural
 * glyphs, each with a stable id stored in the template's {@code icon} tag (so an
 * existing snapshot keeps working, and an unknown id falls back to
 * {@link #DEFAULT}). The glyphs are drawn with the plain {@code GuiGraphics} fill
 * primitives the rest of the UI uses, so no extra texture is loaded.</p>
 */
public final class IconPicker {
    /**
     * Stable icon ids, in grid order.
     *
     * <p>26.4.1: this list is the single crest set of the mod — the item-model
     * predicate {@code veriborder:emblem} selects
     * {@code index / 16} out of exactly this order
     * ({@code com.veriborder.client.render.EmblemSet.IDS}), and
     * {@code textures/item/emblem_<id>.png} carries one layer-2 art per id. The
     * last three (gear / eagle / machine readable) complete the documented crest
     * brief; the earlier nine were already here.</p>
     */
    public static final List<String> ICONS = List.of(
            "emblem", "star", "crown", "shield", "globe", "leaf",
            "anchor", "key", "stamp", "book", "diamond", "flame",
            "gear", "eagle", "mrz"
    );
    public static final String DEFAULT = "emblem";

    public static final int COLUMNS = 6;
    private static final int CELL = 16;
    private static final int GAP = 2;
    public static final int HEIGHT = 18;
    public static final int OPEN_HEIGHT = HEIGHT + 4
            + ((ICONS.size() + COLUMNS - 1) / COLUMNS) * (CELL + GAP) + 2;

    private int x;
    private int y;
    private int width = 120;
    private String selected = DEFAULT;
    private boolean open;

    private IconPicker(int x, int y, int width, String selected) {
        this.x = x;
        this.y = y;
        this.width = Math.max(60, width);
        this.selected = normalize(selected);
    }

    public static IconPicker at(int x, int y, int width, String selected) {
        return new IconPicker(x, y, width, selected);
    }

    /** Any stored value maps onto a known icon id. */
    public static String normalize(String value) {
        String key = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        return ICONS.contains(key) ? key : DEFAULT;
    }

    public String selected() {
        return selected;
    }

    public void setSelected(String value) {
        this.selected = normalize(value);
    }

    public boolean open() {
        return open;
    }

    /** Closes (or opens) the grid; other pickers use this for mutual exclusion. */
    public void setOpen(boolean value) {
        this.open = value;
    }

    public int height() {
        return open ? OPEN_HEIGHT : HEIGHT;
    }

    public int bottom() {
        return y + height();
    }

    private int gridTop() {
        return y + HEIGHT + 4;
    }

    private int[] cellRect(int index) {
        int col = index % COLUMNS;
        int row = index / COLUMNS;
        return new int[]{x + 2 + col * (CELL + GAP), gridTop() + row * (CELL + GAP), CELL, CELL};
    }

    // ------------------------------------------------------------------ draw

    public void draw(GuiGraphics graphics, Font font, int accent) {
        graphics.fill(x, y, x + width, y + HEIGHT, open ? 0xFF0E1B28 : 0xFF0B1621);
        graphics.renderOutline(x, y, width, HEIGHT, open ? accent : Ui.BORDER);
        drawGlyph(graphics, selected, x + 4, y + 4, 10, Ui.TEXT);
        graphics.drawString(font, Ui.ellipsis(Component.translatable("icon.veriborder." + selected).getString(),
                width - 34), x + 19, y + 5, Ui.TEXT, false);
        graphics.drawString(font, open ? "\u25B4" : "\u25BE", x + width - 10, y + 5, accent, false);
        if (!open) {
            return;
        }
        int rows = (ICONS.size() + COLUMNS - 1) / COLUMNS;
        int bottom = gridTop() + rows * (CELL + GAP) + 2;
        graphics.fill(x, y + HEIGHT + 1, x + width, bottom, 0xFF0B1520);
        graphics.renderOutline(x, y + HEIGHT + 1, width, bottom - (y + HEIGHT + 1), accent);
        for (int i = 0; i < ICONS.size(); i++) {
            int[] rect = cellRect(i);
            boolean chosen = ICONS.get(i).equals(selected);
            graphics.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3],
                    chosen ? Ui.blend(Ui.PANEL_2, accent, 0.32F) : 0xFF101A24);
            graphics.renderOutline(rect[0], rect[1], rect[2], rect[3], chosen ? accent : Ui.BORDER);
            drawGlyph(graphics, ICONS.get(i), rect[0] + 3, rect[1] + 3, CELL - 6, chosen ? Ui.TEXT : Ui.MUTED);
        }
    }

    /**
     * Paints one preset glyph inside a {@code size x size} box. Unknown ids draw
     * the default emblem, so an imported snapshot never renders an empty cell.
     */
    public static void drawGlyph(GuiGraphics graphics, String iconId, int x, int y, int size, int color) {
        int unit = Math.max(1, size / 8);
        int mid = x + size / 2;
        int midY = y + size / 2;
        switch (normalize(iconId)) {
            case "star" -> {
                graphics.fill(mid - unit, y + unit, mid + unit, y + size - unit, color);
                graphics.fill(x + unit, midY - unit, x + size - unit, midY + unit, color);
                graphics.fill(x + 2 * unit, y + 2 * unit, x + size - 2 * unit, y + size - 2 * unit,
                        (color & 0x00FFFFFF) | 0x55000000);
            }
            case "crown" -> {
                graphics.fill(x + unit, y + size - 3 * unit, x + size - unit, y + size - unit, color);
                graphics.fill(x + unit, y + 2 * unit, x + 2 * unit, y + size - 3 * unit, color);
                graphics.fill(mid - unit / 2, y + unit, mid + unit, y + size - 3 * unit, color);
                graphics.fill(x + size - 2 * unit, y + 2 * unit, x + size - unit, y + size - 3 * unit, color);
            }
            case "shield" -> {
                graphics.fill(x + unit, y + unit, x + size - unit, midY + 2 * unit, color);
                graphics.fill(mid - 2 * unit, midY + 2 * unit, mid + 2 * unit, y + size - unit, color);
                graphics.fill(mid - unit / 2, y + 2 * unit, mid + unit - unit / 2, midY + unit,
                        (color & 0x00FFFFFF) | 0x88000000);
            }
            case "globe" -> {
                graphics.fill(x + 2 * unit, y + unit, x + size - 2 * unit, y + size - unit, color);
                graphics.fill(x + unit, y + 2 * unit, x + 2 * unit, y + size - 2 * unit, color);
                graphics.fill(x + size - 2 * unit, y + 2 * unit, x + size - unit, y + size - 2 * unit, color);
                graphics.fill(mid - unit / 2, y + unit, mid + unit - unit / 2, y + size - unit,
                        (color & 0x00FFFFFF) | 0x99000000);
            }
            case "leaf" -> {
                graphics.fill(x + 2 * unit, y + unit, x + size - unit, y + 3 * unit, color);
                graphics.fill(x + unit, y + 2 * unit, x + 2 * unit, y + size - 2 * unit, color);
                graphics.fill(x + 2 * unit, y + size - 2 * unit, x + size - 2 * unit, y + size - unit, color);
            }
            case "anchor" -> {
                graphics.fill(mid - unit / 2, y + unit, mid + unit - unit / 2, y + size - 2 * unit, color);
                graphics.fill(x + unit, y + 2 * unit, x + size - unit, y + 3 * unit, color);
                graphics.fill(x + unit, y + size - 3 * unit, x + 2 * unit, y + size - unit, color);
                graphics.fill(x + size - 2 * unit, y + size - 3 * unit, x + size - unit, y + size - unit, color);
            }
            case "key" -> {
                graphics.fill(x + unit, midY - unit, x + size - 2 * unit, midY + unit, color);
                graphics.fill(x + size - 3 * unit, midY + unit, x + size - 2 * unit, y + size - unit, color);
                graphics.fill(x + size - 5 * unit, midY + unit, x + size - 4 * unit, y + size - 2 * unit, color);
                graphics.fill(x + unit, midY - 2 * unit, x + 3 * unit, midY + 2 * unit,
                        (color & 0x00FFFFFF) | 0x88000000);
            }
            case "stamp" -> {
                graphics.fill(x + unit, y + unit, x + size - unit, y + size - 3 * unit, color);
                graphics.fill(x, y + size - 2 * unit, x + size, y + size - unit,
                        (color & 0x00FFFFFF) | 0xAA000000);
            }
            case "book" -> {
                graphics.fill(x + 2 * unit, y + unit, x + size - 2 * unit, y + size - unit, color);
                graphics.fill(x + 2 * unit, y + unit, x + 3 * unit, y + size - unit,
                        (color & 0x00FFFFFF) | 0x88000000);
                graphics.fill(x + 4 * unit, y + 3 * unit, x + size - 3 * unit, y + 4 * unit,
                        (color & 0x00FFFFFF) | 0x77000000);
            }
            case "diamond" -> {
                graphics.fill(mid - 2 * unit, y + 2 * unit, mid + 2 * unit, y + size - 2 * unit, color);
                graphics.fill(x + 2 * unit, midY - unit, x + size - 2 * unit, midY + unit, color);
            }
            case "flame" -> {
                graphics.fill(mid - unit, y + unit, mid + unit, y + size - unit, color);
                graphics.fill(mid - 2 * unit, midY, mid - unit, y + size - unit, color);
                graphics.fill(mid + unit, midY, mid + 2 * unit, y + size - unit, color);
            }
            case "gear" -> {
                graphics.fill(x + 2 * unit, y + 2 * unit, x + size - 2 * unit, y + size - 2 * unit, color);
                graphics.fill(x + unit, midY - 2 * unit, x + size - unit, midY + 2 * unit,
                        (color & 0x00FFFFFF) | 0xAA000000);
                graphics.fill(x + 3 * unit, y + unit, x + size - 3 * unit, y + 2 * unit, color);
                graphics.fill(x + 3 * unit, y + size - 2 * unit, x + size - 3 * unit, y + size - unit, color);
                graphics.fill(mid - unit, midY - unit, mid + unit, midY + unit,
                        (color & 0x00FFFFFF) | 0x66000000);
            }
            case "eagle" -> {
                graphics.fill(x + unit, y + 2 * unit, x + size - unit, y + 4 * unit, color);
                graphics.fill(mid - unit, y + unit, mid + unit, y + size - 2 * unit, color);
                graphics.fill(x, y + 4 * unit, x + 2 * unit, y + 5 * unit, color);
                graphics.fill(x + size - 2 * unit, y + 4 * unit, x + size, y + 5 * unit, color);
                graphics.fill(mid - 2 * unit, y + size - 3 * unit, mid + 2 * unit, y + size - unit,
                        (color & 0x00FFFFFF) | 0xAA000000);
            }
            case "mrz" -> {
                for (int column = 0; column < 4; column++) {
                    int barX = x + unit + column * 2 * unit;
                    graphics.fill(barX, y + unit, barX + unit, y + size - 2 * unit, color);
                }
                graphics.fill(x + unit, midY - unit / 2, x + size - unit, midY + unit / 2,
                        (color & 0x00FFFFFF) | 0x88000000);
                graphics.fill(x + unit, y + size - 2 * unit, x + size - unit, y + size - unit, color);
            }
            default -> {
                graphics.fill(mid - unit, y, mid + unit, y + size, color);
                graphics.fill(x, midY - unit, x + size, midY + unit, color);
                graphics.renderOutline(x + 2 * unit, y + 2 * unit, size - 4 * unit, size - 4 * unit,
                        (color & 0x00FFFFFF) | 0xAA000000);
                graphics.fill(mid - 2 * unit, midY - 2 * unit, mid + 2 * unit, midY + 2 * unit, color);
            }
        }
    }

    // ------------------------------------------------------------------ input

    public boolean mouseClicked(double mouseX, double mouseY) {
        boolean onHeader = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + HEIGHT;
        if (onHeader) {
            open = !open;
            return true;
        }
        if (!open) {
            return false;
        }
        for (int i = 0; i < ICONS.size(); i++) {
            int[] rect = cellRect(i);
            if (mouseX >= rect[0] && mouseX <= rect[0] + rect[2]
                    && mouseY >= rect[1] && mouseY <= rect[1] + rect[3]) {
                selected = ICONS.get(i);
                open = false;
                return true;
            }
        }
        if (mouseX >= x && mouseX <= x + width && mouseY >= y + HEIGHT && mouseY <= y + OPEN_HEIGHT) {
            return true;
        }
        open = false;
        return false;
    }
}
