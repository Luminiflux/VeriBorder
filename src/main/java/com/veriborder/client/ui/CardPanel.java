package com.veriborder.client.ui;

import com.veriborder.client.Ui;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Shared framed card used across the 26.3 screens. A card is the opaque inner
 * surface that groups related fields: dark fill, accent strip, optional title
 * and an outline. {@link #draw(GuiGraphics, Font)} returns the inner content
 * rectangle so callers never repeat padding arithmetic.
 *
 * <p>Also exposes the small row primitives ({@link #keyValue},
 * {@link #chip}, {@link #statusPill}) so every screen aligns labels and values
 * the same way.</p>
 */
public final class CardPanel {
    /** Paintable inner area of a card (design space). */
    public record Content(int x, int y, int width, int height) {
    }

    private static final int HEADER_HEIGHT = 22;
    private static final int PADDING = 6;

    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private int accent = Ui.BORDER;
    private Component title;
    private Component hint;
    private boolean header = true;

    private CardPanel(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public static CardPanel at(int x, int y, int width, int height) {
        return new CardPanel(x, y, width, height);
    }

    public CardPanel accent(int color) {
        this.accent = color;
        return this;
    }

    public CardPanel title(Component text) {
        this.title = text;
        return this;
    }

    public CardPanel hint(Component text) {
        this.hint = text;
        return this;
    }

    /** Draws the card body without a header row (content starts 6px in). */
    public CardPanel bare() {
        this.header = false;
        return this;
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

    public int headerHeight() {
        return header ? HEADER_HEIGHT : 0;
    }

    /** Inner rectangle, independent of drawing (used for hit boxes). */
    public Content content() {
        int top = y + headerHeight();
        return new Content(x + PADDING, top + (header ? 2 : PADDING), width - PADDING * 2,
                height - headerHeight() - (header ? 2 : PADDING) - PADDING);
    }

    /** Draws the card and returns its inner content rectangle. */
    public Content draw(GuiGraphics graphics, Font font) {
        graphics.fill(x, y, x + width, y + height, 0xE60B1621);
        graphics.fill(x, y, x + width, y + 1, Ui.BLEND_TOP);
        graphics.renderOutline(x, y, width, height, 0xFF2A4157);
        if (header) {
            graphics.fill(x + 1, y + 1, x + width - 1, y + HEADER_HEIGHT, 0xB3101C28);
            graphics.fill(x + 1, y + 1, x + 3, y + HEADER_HEIGHT, accent);
            graphics.fill(x + 1, y + HEADER_HEIGHT - 1, x + width - 1, y + HEADER_HEIGHT,
                    (accent & 0x00FFFFFF) | 0x55000000);
            if (title != null) {
                graphics.drawString(font, Ui.ellipsis(title.getString(), Math.max(10, width - 14)),
                        x + 8, y + 6, Ui.TEXT, false);
            }
            if (hint != null) {
                int hintWidth = font.width(hint);
                int hintX = x + width - 8 - hintWidth;
                if (hintX > x + 10) {
                    graphics.drawString(font, Ui.ellipsis(hint.getString(), width / 2), hintX, y + 6,
                            Ui.DIM, false);
                }
            }
        }
        return content();
    }

    /** One aligned label/value row; returns the y of the next row. */
    public static int keyValue(GuiGraphics graphics, Font font, int x, int y, int width,
                               String labelKey, String labelFallback, String value, int valueColor) {
        Component label = Ui.label(labelKey, labelFallback);
        graphics.drawString(font, label, x, y, Ui.DIM, false);
        int labelWidth = Math.max(52, font.width(label) + 8);
        String text = value == null || value.isBlank() ? "\u2014" : value;
        graphics.drawString(font, Ui.ellipsis(text, Math.max(12, width - labelWidth)),
                x + labelWidth, y, valueColor, false);
        return y + font.lineHeight + 3;
    }

    /** One aligned label/value row with an explicit localized label. */
    public static int keyValue(GuiGraphics graphics, Font font, int x, int y, int width,
                               Component label, String value, int valueColor) {
        graphics.drawString(font, label, x, y, Ui.DIM, false);
        int labelWidth = Math.max(52, font.width(label) + 8);
        String text = value == null || value.isBlank() ? "\u2014" : value;
        graphics.drawString(font, Ui.ellipsis(text, Math.max(12, width - labelWidth)),
                x + labelWidth, y, valueColor, false);
        return y + font.lineHeight + 3;
    }

    /** Small rounded-ish status pill; returns its width. */
    public static int statusPill(GuiGraphics graphics, Font font, int x, int y, Component text, int color) {
        int width = font.width(text) + 10;
        graphics.fill(x, y, x + width, y + 12, (color & 0x00FFFFFF) | 0x33000000);
        graphics.renderOutline(x, y, width, 12, color);
        graphics.drawString(font, text, x + 5, y + 2, color, false);
        return width;
    }

    /** Code chip (8-digit credential codes); returns the x of the next chip. */
    public static int chip(GuiGraphics graphics, Font font, int x, int y, String code, int color,
                           boolean selected) {
        int width = font.width(code) + 9;
        graphics.fill(x, y - 1, x + width, y + 10, selected ? 0x552F4A62 : 0x33101A24);
        graphics.renderOutline(x, y - 1, width, 11, selected ? Ui.GOLD : 0x332A3B50);
        graphics.drawString(font, code, x + 4, y, selected ? Ui.GOLD : color, false);
        return x + width + 4;
    }
}
