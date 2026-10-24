package com.veriborder.client.ui;

import com.veriborder.client.Ui;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Shared colour-palette picker (26.3 round 12).
 *
 * <p>Every colour field of the mod now opens the same control instead of a bare
 * {@code #RRGGBB} text box: 16 preset swatches, the eight most recently used
 * colours (shared process-wide, so a colour picked in the document generator is
 * one click away in the registry) and an RGB/hex input for arbitrary values.</p>
 *
 * <p>The screen creates the hex {@link EditBox} and attaches it; the widget paints
 * the swatch grid and hit-tests it. {@link #color()} always returns the value the
 * screen should persist, no matter whether it came from a swatch or the box.</p>
 */
public final class ColorPalette {
    /** Preset swatches: document covers, registry accents and neutral tones. */
    public static final int[] PRESETS = {
            0x1F6FB2, 0x7C3AED, 0xB91C1C, 0x0F766E,
            0xC2410C, 0x374151, 0xA16207, 0x111827,
            0x2FC6B7, 0xD9B96E, 0x3E8FC4, 0x9B6AF2,
            0xE0663A, 0x1F5E3A, 0xE8E3D5, 0xFFFFFF
    };

    /** Recently used colours, newest first, shared by every screen. */
    private static final LinkedHashSet<Integer> RECENT = new LinkedHashSet<>();
    private static final int MAX_RECENT = 8;
    private static final int COLUMNS = 8;
    private static final int CELL = 13;
    private static final int GAP = 1;
    public static final int HEIGHT = 18;

    private int x;
    private int y;
    private int width = 120;
    private int color;
    private boolean open;
    @Nullable
    private EditBox hex;

    private ColorPalette(int x, int y, int width, int color) {
        this.x = x;
        this.y = y;
        this.width = Math.max(60, width);
        this.color = color & 0x00FFFFFF;
    }

    /** Creates a palette control showing {@code color} (a 0xRRGGBB value). */
    public static ColorPalette at(int x, int y, int width, int color) {
        return new ColorPalette(x, y, width, color);
    }

    /** Attaches the screen-created hex box; a null box hides the RGB input. */
    public ColorPalette hex(@Nullable EditBox box) {
        this.hex = box;
        if (box != null) {
            box.setValue(hexValue(color));
        }
        return this;
    }

    public int color() {
        return color;
    }

    public void setColor(int value) {
        this.color = value & 0x00FFFFFF;
        if (hex != null) {
            hex.setValue(hexValue(this.color));
        }
    }

    public boolean open() {
        return open;
    }

    public void setOpen(boolean value) {
        open = value;
        if (!open) {
            syncFromHex();
        }
    }

    /** Total height: the closed control, or the expanded grid. */
    public int height() {
        return open ? openHeight() : HEIGHT;
    }

    public int bottom() {
        return y + height();
    }

    public static int openHeight() {
        return HEIGHT + 3 + 2 * CELL + GAP + 3 + CELL + 3 + 14;
    }

    // --------------------------------------------------------------- geometry

    private int presetsTop() {
        return y + HEIGHT + 3;
    }

    private int recentTop() {
        return presetsTop() + 2 * (CELL + GAP) + 2;
    }

    private int hexTop() {
        return recentTop() + CELL + 3;
    }

    /** Design-space y of the RGB input (the screen places its EditBox here). */
    public int hexFieldY() {
        return hexTop() - 1;
    }

    /** Design-space x of the RGB input. */
    public int hexFieldX() {
        return x + 30;
    }

    /** Width available for the RGB input. */
    public int hexFieldWidth() {
        return width - 34;
    }

    /**
     * True when the point is over the RGB input (which is a real {@link EditBox}
     * owned by the screen). An open palette routes that click to the widget
     * instead of treating it as a swatch click.
     */
    public boolean overHexField(double mouseX, double mouseY) {
        if (!open || hex == null) {
            return false;
        }
        return mouseX >= hexFieldX() - 2 && mouseX <= hexFieldX() + hexFieldWidth() + 2
                && mouseY >= hexFieldY() - 2 && mouseY <= hexFieldY() + 14;
    }

    private int[] cellRect(int index, int top) {
        int col = index % COLUMNS;
        int row = index / COLUMNS;
        return new int[]{x + col * (CELL + GAP), top + row * (CELL + GAP), CELL, CELL};
    }

    // ------------------------------------------------------------------ draw

    /** Draws the closed control and, while open, the palette itself. */
    public void draw(GuiGraphics graphics, Font font, int accent) {
        graphics.fill(x, y, x + width, y + HEIGHT, open ? 0xFF0E1B28 : 0xFF0B1621);
        graphics.renderOutline(x, y, width, HEIGHT, open ? accent : Ui.BORDER);
        int swatch = 10;
        graphics.fill(x + 4, y + 4, x + 4 + swatch, y + 4 + swatch, 0xFF000000 | color);
        graphics.renderOutline(x + 4, y + 4, swatch, swatch, 0x99FFFFFF);
        String label = hexValue(color);
        graphics.drawString(font, label, x + 18, y + 5, Ui.TEXT, false);
        graphics.drawString(font, open ? "\u25B4" : "\u25BE", x + width - 10, y + 5, accent, false);
        if (!open) {
            return;
        }
        int bottom = recentTop() + CELL + 3 + 14;
        graphics.fill(x, y + HEIGHT + 1, x + width, bottom + 3, 0xFF0B1520);
        graphics.renderOutline(x, y + HEIGHT + 1, width, bottom + 3 - (y + HEIGHT + 1), accent);
        for (int i = 0; i < PRESETS.length; i++) {
            drawSwatch(graphics, cellRect(i, presetsTop()), PRESETS[i], PRESETS[i] == color);
        }
        List<Integer> recent = recent(MAX_RECENT);
        for (int i = 0; i < recent.size() && i < MAX_RECENT; i++) {
            drawSwatch(graphics, cellRect(i, recentTop()), recent.get(i), recent.get(i) == color);
        }
        // RGB hint next to the hex box (the box widget itself is painted by the screen).
        graphics.drawString(font, Component.translatable("color.veriborder.rgb").getString(),
                x + 4, hexTop() + 4, Ui.DIM, false);
    }

    private void drawSwatch(GuiGraphics graphics, int[] rect, int value, boolean selected) {
        graphics.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3], 0xFF000000 | value);
        graphics.renderOutline(rect[0], rect[1], rect[2], rect[3], selected ? Ui.TEXT : 0x66FFFFFF);
        if (selected) {
            graphics.renderOutline(rect[0] - 1, rect[1] - 1, rect[2] + 2, rect[3] + 2, Ui.CYAN);
        }
    }

    // ------------------------------------------------------------------ input

    /** Handles a click: swatch pick, header toggle, or closing the open panel. */
    public boolean mouseClicked(double mouseX, double mouseY) {
        boolean onHeader = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + HEIGHT;
        if (onHeader) {
            open = !open;
            if (!open) {
                syncFromHex();
            }
            return true;
        }
        if (!open) {
            return false;
        }
        for (int i = 0; i < PRESETS.length; i++) {
            if (inCell(mouseX, mouseY, cellRect(i, presetsTop()))) {
                setColor(PRESETS[i]);
                remember(PRESETS[i]);
                return true;
            }
        }
        List<Integer> recent = recent(MAX_RECENT);
        for (int i = 0; i < recent.size() && i < MAX_RECENT; i++) {
            if (inCell(mouseX, mouseY, cellRect(i, recentTop()))) {
                setColor(recent.get(i));
                remember(recent.get(i));
                return true;
            }
        }
        if (mouseX >= x && mouseX <= x + width && mouseY >= y + HEIGHT
                && mouseY <= y + openHeight()) {
            // Click inside the expanded panel (e.g. the hex box): keep it open.
            return true;
        }
        setOpen(false);
        return false;
    }

    /**
     * Reads the hex box back into {@link #color()}. Called by the screen before it
     * saves, so a typed value is honoured even without pressing a swatch.
     */
    public int commit() {
        syncFromHex();
        remember(color);
        return color;
    }

    private void syncFromHex() {
        if (hex == null) {
            return;
        }
        color = parse(hex.getValue(), color);
    }

    // ---------------------------------------------------------------- helpers

    public static String hexValue(int value) {
        return String.format("#%06X", value & 0xFFFFFF);
    }

    /** Parses {@code #RRGGBB} / {@code RRGGBB} / {@code 0xRRGGBB}, else {@code fallback}. */
    public static int parse(@Nullable String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        String cleaned = value.trim().replace("#", "").replace("0x", "").replace("0X", "");
        if (cleaned.length() != 6 || !cleaned.matches("[0-9a-fA-F]{6}")) {
            return fallback;
        }
        try {
            return Integer.parseInt(cleaned, 16) & 0x00FFFFFF;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** Records a colour in the process-wide "recently used" row. */
    public static void remember(int value) {
        int rgb = value & 0x00FFFFFF;
        synchronized (RECENT) {
            RECENT.remove(rgb);
            RECENT.add(rgb);
            while (RECENT.size() > MAX_RECENT) {
                Integer oldest = RECENT.iterator().next();
                RECENT.remove(oldest);
            }
        }
    }

    /** Recently used colours, newest first. */
    public static List<Integer> recent(int limit) {
        List<Integer> all;
        synchronized (RECENT) {
            all = new ArrayList<>(RECENT);
        }
        List<Integer> result = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && result.size() < limit; i--) {
            result.add(all.get(i));
        }
        return result;
    }

    /** Clears the recently-used row (tests). */
    public static void clearRecent() {
        synchronized (RECENT) {
            RECENT.clear();
        }
    }

    private static boolean inCell(double mouseX, double mouseY, int[] rect) {
        return mouseX >= rect[0] && mouseX <= rect[0] + rect[2]
                && mouseY >= rect[1] && mouseY <= rect[1] + rect[3];
    }
}
