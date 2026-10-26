package com.veriborder.client.ui;

import com.veriborder.client.Ui;
import com.veriborder.doc.DocumentTitles;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Shared document artwork primitives for the 26.3 viewer: photo placeholder,
 * emblem, barcode placeholder, monospace MRZ strips and ink stamps.
 *
 * <p>All geometry is design-space so a screen only passes the rectangle it
 * reserved. The MRZ strip is rendered cell-by-cell with a fixed advance, which
 * gives the deliberately monospaced look the Latin OCR-B line needs without a
 * second font resource.</p>
 */
public final class DocumentArt {
    /** A rectangle the caller may draw a portrait into. */
    public record Slot(int x, int y, int width, int height) {
        public boolean valid() {
            return width > 4 && height > 4;
        }
    }

    private DocumentArt() {
    }

    /**
     * Portrait placeholder: an ID-card style silhouette or a passport emblem,
     * framed like a printed photo. Never pretends to be a real portrait.
     */
    public static void portrait(GuiGraphics graphics, Font font, int x, int y, int width, int height,
                                int accent, boolean passport, String caption) {
        portrait(graphics, font, x, y, width, height, accent, passport, caption, "");
    }

    /**
     * 26.5 portrait/cover plate with the issuing template's emblem.
     *
     * <p>The plate is painted with the document's own colour ({@code accent}, which
     * every caller resolves through {@code TemplateDisplay.coverTint}): a template
     * document shows its template colour as a neutral cover washed with that tint,
     * an official layout shows that layout's accent. The fixed slate-blue plate of
     * 26.4 is gone — that is what kept a Japanese-passport template looking like
     * the Japanese passport.</p>
     *
     * @param emblem preset emblem id of the template, "" for the neutral cover
     *               emblem an official layout (or a pre-26.4.1 document) prints
     */
    public static void portrait(GuiGraphics graphics, Font font, int x, int y, int width, int height,
                                int accent, boolean passport, String caption, String emblem) {
        boolean customEmblem = emblem != null && !emblem.isBlank();
        int plate = 0xFF000000 | (accent & 0x00FFFFFF);
        int top = Ui.blend(plate, 0xFFFFFFFF, 0.30F);
        int bottom = Ui.blend(plate, 0xFF06090D, 0.55F);
        graphics.fill(x, y, x + width, y + height, bottom);
        graphics.fillGradient(x + 1, y + 1, x + width - 1, y + height - 1, top, bottom);
        graphics.renderOutline(x, y, width, height, Ui.blend(plate, 0xFFFFFFFF, 0.45F));
        if (passport) {
            // Guilloche-style rings behind the emblem, then the emblem itself: the
            // template glyph when the document carries one, the neutral cross for
            // an official layout / pre-26.4.1 document.
            drawCoverRings(graphics, x, y, width, height, accent);
            if (customEmblem) {
                IconPicker.drawGlyph(graphics, emblem, x + width / 2 - 15, y + height / 2 - 21, 30, accent);
            } else {
                drawEmblem(graphics, x + width / 2, y + height / 2 - 6, 30, accent);
            }
            coverTitle(graphics, font, x + width / 2, y + height - 16, width - 8, caption, 0xCCE8F2FF);
            return;
        }
        int head = Math.max(10, height / 4);
        int headX = x + width / 2 - head / 3;
        int headY = y + Math.max(6, height / 5);
        graphics.fill(headX, headY, headX + (head * 2) / 3, headY + head, Ui.blend(plate, 0xFFFFFFFF, 0.62F));
        graphics.renderOutline(headX, headY, Math.max(4, (head * 2) / 3), head, 0x66000000);
        int bodyTop = headY + head + 4;
        int bodyBottom = y + height - 8;
        if (bodyBottom > bodyTop + 6) {
            int half = Math.min(width / 3, head);
            graphics.fill(x + width / 2 - half, bodyTop, x + width / 2 + half, bodyBottom,
                    Ui.blend(plate, 0xFFFFFFFF, 0.40F));
            graphics.fill(x + width / 2 - half, bodyTop, x + width / 2 - half + 3, bodyBottom, 0x33FFFFFF);
        }
        if (caption != null && !caption.isBlank()) {
            coverTitle(graphics, font, x + width / 2, y + height - 13, width - 8, caption, 0xCCDCE9F5);
        }
    }

    /**
     * 26.5 passport cover plate with a portrait window beside the emblem.
     *
     * <p>The viewer used to treat the passport slot as "cover only", which is why an
     * issued passport showed no portrait at all. The cover now keeps its emblem AND
     * the title, and reserves a photo window on the left when the holder's portrait
     * is resolvable — the two no longer exclude each other.</p>
     *
     * @return the rectangle the caller should draw the portrait into, or
     *         {@code null} when the cover is drawn without a photo window
     */
    public static Slot passportCover(GuiGraphics graphics, Font font, int x, int y, int width, int height,
                                     int accent, String emblem, String caption, boolean withPhoto) {
        int plate = 0xFF000000 | (accent & 0x00FFFFFF);
        int top = Ui.blend(plate, 0xFFFFFFFF, 0.34F);
        int bottom = Ui.blend(plate, 0xFF06090D, 0.60F);
        graphics.fill(x, y, x + width, y + height, bottom);
        graphics.fillGradient(x + 1, y + 1, x + width - 1, y + height - 1, top, bottom);
        graphics.renderOutline(x, y, width, height, Ui.blend(plate, 0xFFFFFFFF, 0.48F));
        // inner foil frame: a second, thinner line like a printed cover border
        if (width > 30 && height > 30) {
            graphics.renderOutline(x + 3, y + 3, width - 6, height - 6,
                    (Ui.blend(plate, 0xFFFFFFFF, 0.28F) & 0x00FFFFFF) | 0x66000000);
        }
        drawCoverRings(graphics, x, y, width, height, accent);

        Slot slot = null;
        if (withPhoto) {
            int photoH = height - 24;
            int photoW = Math.min(54, Math.max(28, Math.round(photoH * 0.78F)));
            slot = new Slot(x + 10, y + 10, photoW, photoH);
        }
        // Emblem: right of the photo window, otherwise centred on the plate.
        String glyph = emblem == null ? "" : emblem;
        int glyphSize = Math.min(34, Math.max(20, height / 3));
        int glyphY = y + (height - 26) / 2 + 4 - glyphSize / 2;
        if (slot != null && slot.valid()) {
            int centreX = slot.x() + slot.width() + (x + width - 10 - (slot.x() + slot.width())) / 2;
            drawCoverEmblem(graphics, glyph, centreX, glyphY, glyphSize, accent);
        } else {
            int centreX = x + width / 2;
            drawCoverEmblem(graphics, glyph, centreX - glyphSize / 2, glyphY, glyphSize, accent);
        }
        coverTitle(graphics, font, x + width / 2, y + height - 15, width - 10, caption, 0xDDE9F3FF);
        return slot != null && slot.valid() ? slot : null;
    }

    /** The cover glyph: the template crest, or the neutral cross for a plain layout. */
    private static void drawCoverEmblem(GuiGraphics graphics, String emblem, int x, int y, int size,
                                        int accent) {
        if (emblem != null && !emblem.isBlank()) {
            IconPicker.drawGlyph(graphics, emblem, x, y, size, accent);
            return;
        }
        drawEmblem(graphics, x + size / 2, y + size / 2, size, 0xFFE9F1F8);
    }

    /**
     * Frame colour the portrait is cropped into: the dark end of a cover plate of
     * {@code accent}. {@code PortraitArt} paints its rounded corners with it, so the
     * photo sits in the plate instead of on top of it.
     */
    public static int coverTone(int accent) {
        return Ui.blend(0xFF000000 | (accent & 0x00FFFFFF), 0xFF06090D, 0.55F);
    }

    /**
     * Generic photo placeholder for a window whose portrait cannot be resolved on
     * this client. Never a real portrait: a silhouette in the plate tone, framed
     * like the printed photo it stands in for.
     */
    public static void photoPlaceholder(GuiGraphics graphics, int x, int y, int width, int height,
                                        int frameColor) {
        if (width < 6 || height < 6) {
            return;
        }
        graphics.fill(x, y, x + width, y + height, frameColor);
        int head = Math.max(6, height / 4);
        int headW = Math.max(4, (head * 2) / 3);
        int headX = x + (width - headW) / 2;
        int headY = y + Math.max(3, height / 6);
        graphics.fill(headX, headY, headX + headW, headY + head, 0xFF9DB2C4);
        graphics.renderOutline(headX, headY, headW, head, 0x66000000);
        int bodyTop = headY + head + 3;
        if (bodyTop < y + height - 3) {
            int half = Math.min(width / 3, head);
            graphics.fill(x + width / 2 - half, bodyTop, x + width / 2 + half, y + height - 3,
                    0xFF7F97AB);
        }
        graphics.renderOutline(x, y, width, height, 0x66000000);
    }

    /**
     * Cover-band title: the document name printed on the artwork.
     *
     * <p>The line is fitted into the plate width first by uniform down-scaling
     * and only then by a trailing ellipsis, so an arbitrarily long custom
     * document name can never overflow the printed band (26.4 display fix). The
     * caller passes the document title, never the holder name.</p>
     */
    public static void coverTitle(GuiGraphics graphics, Font font, int centerX, int y, int maxWidth,
                                  String label, int color) {
        DocumentTitles.CoverLine line = DocumentTitles.coverLine(label, maxWidth, font::width);
        if (line.blank()) {
            return;
        }
        if (line.scale() >= 0.999F) {
            graphics.drawCenteredString(font, line.text(), centerX, y, color);
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, y, 0.0F);
        graphics.pose().scale(line.scale(), line.scale(), 1.0F);
        graphics.drawCenteredString(font, line.text(), 0, 0, color);
        graphics.pose().popPose();
    }

    /**
     * Faint concentric rings behind a passport cover emblem. Split out of
     * {@link #portrait} in 26.4.1 so the custom-emblem path draws the same
     * backdrop without duplicating the geometry.
     */
    private static void drawCoverRings(GuiGraphics graphics, int x, int y, int width, int height, int accent) {
        for (int ring = 1; ring <= 3; ring++) {
            int inset = ring * 7;
            if (width - inset * 2 > 8 && height - inset * 2 > 8) {
                graphics.renderOutline(x + inset, y + inset, width - inset * 2, height - inset * 2,
                        (accent & 0x00FFFFFF) | 0x22FFFFFF);
            }
        }
    }

    /** Simple cross emblem (kept local so the shared Ui art is unchanged). */
    public static void drawEmblem(GuiGraphics graphics, int centerX, int centerY, int size, int color) {
        int half = Math.max(4, size / 2);
        graphics.fill(centerX - 1, centerY - half, centerX + 1, centerY + half, color);
        graphics.fill(centerX - half, centerY - 1, centerX + half, centerY + 1, color);
        graphics.renderOutline(centerX - half + 3, centerY - half + 3, half * 2 - 6, half * 2 - 6,
                (color & 0x00FFFFFF) | 0xAA000000);
        graphics.fill(centerX - 3, centerY - 3, centerX + 3, centerY + 3, color);
    }

    /**
     * Deterministic barcode placeholder: bar widths derive from the document
     * number so every document has a stable, recognizable pattern. It is a
     * visual placeholder only, never a scannable code.
     */
    public static void barcode(GuiGraphics graphics, int x, int y, int width, int height, String seed,
                               int color) {
        if (width <= 2 || height <= 2) {
            return;
        }
        graphics.fill(x, y, x + width, y + height, 0x66050B12);
        graphics.renderOutline(x, y, width, height, 0x332A3B50);
        int hash = seed == null ? 0 : seed.hashCode();
        int cursor = x + 3;
        int limit = x + width - 3;
        int index = 0;
        while (cursor < limit - 1) {
            hash = hash * 1103515245 + 12345 + index * 31;
            int bar = 1 + (Math.abs(hash) % 3);
            int gap = 1 + (Math.abs(hash / 7) % 2);
            int barWidth = Math.min(bar, limit - cursor);
            if (index % 9 == 8) {
                // Guard bar: taller and brighter, like a real barcode margin.
                graphics.fill(cursor, y + 2, cursor + Math.max(1, barWidth - 1), y + height - 2, color);
            } else {
                graphics.fill(cursor, y + 4, cursor + barWidth, y + height - 5, color);
            }
            cursor += barWidth + gap;
            index++;
        }
    }

    /**
     * Monospace MRZ line. Each character occupies a fixed cell so the 44-column
     * machine readable zone lines up exactly like a printed document; the strip
     * gets a light plate background and grouped separator ticks.
     */
    public static void mrzStrip(GuiGraphics graphics, Font font, int x, int y, int width, String line,
                                int color) {
        String value = line == null ? "" : line;
        if (value.isBlank()) {
            graphics.drawString(font, "\u2014", x, y, Ui.DIM, false);
            return;
        }
        int cell = Math.max(4, Math.min(7, width / Math.max(1, value.length())));
        int stripWidth = cell * value.length();
        int height = 12;
        graphics.fill(x - 2, y - 2, x + stripWidth + 2, y + height, 0x55101824);
        graphics.renderOutline(x - 2, y - 2, stripWidth + 4, height + 2, 0x332A3B50);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            String text = character == '<' ? "<" : String.valueOf(character);
            int cellX = x + i * cell;
            if (i > 0 && i % 11 == 0) {
                graphics.fill(cellX - 1, y - 1, cellX, y + height - 1, 0x332B4056);
            }
            int textWidth = font.width(text);
            graphics.drawString(font, text, cellX + (cell - textWidth) / 2, y, color, false);
        }
    }

    /** Width one MRZ strip needs at the same cell sizing as {@link #mrzStrip}. */
    public static int mrzWidth(int availableWidth, String line) {
        int length = line == null ? 0 : line.length();
        if (length <= 0) {
            return 0;
        }
        return Math.max(4, Math.min(7, availableWidth / length)) * length;
    }

    /**
     * Rotated ink stamp: two rings, direction word, port, date/time and officer,
     * drawn with a slightly imperfect angle and translucent ink so it reads as
     * a physical stamp rather than a list row.
     */
    public static void stampInk(GuiGraphics graphics, Font font, int centerX, int centerY, int radiusX,
                                int radiusY, String direction, String port, String date, String time,
                                String officer, int color, float angleDegrees) {
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(centerX, centerY, 0.0F);
            graphics.pose().mulPose(Axis.ZP.rotationDegrees(angleDegrees));
            int ink = (color & 0x00FFFFFF) | 0xCC000000;
            int faint = (color & 0x00FFFFFF) | 0x33000000;
            // Oval approximated by stacked horizontal bands: outer ring, inner
            // ring and a translucent body wash.
            int steps = Math.max(6, radiusY);
            for (int i = 0; i < steps; i++) {
                double v = (double) i / (steps - 1) * 2.0 - 1.0;
                int half = (int) Math.round(radiusX * Math.sqrt(Math.max(0.0, 1.0 - v * v)));
                int rowY = (int) Math.round(-radiusY + i * (2.0 * radiusY / (steps - 1)));
                graphics.fill(-half, rowY, half, rowY + 1, faint);
                if (i == 0 || i == steps - 1) {
                    graphics.fill(-half, rowY, half, rowY + 1, ink);
                }
                if (i == 1 || i == steps - 2) {
                    graphics.fill(-half + 2, rowY, half - 2, rowY + 1, (color & 0x00FFFFFF) | 0x99000000);
                }
            }
            int left = -radiusX + 4;
            graphics.fill(left, -1, left + 3, 0, ink);
            graphics.fill(-radiusX + 4, -radiusY + 3, -radiusX + 6, radiusY - 3, faint);
            graphics.fill(radiusX - 6, -radiusY + 3, radiusX - 4, radiusY - 3, faint);
            graphics.drawString(font, direction, -font.width(direction) / 2, -radiusY + 3, ink, false);
            String place = port == null || port.isBlank() ? "\u2014" : port;
            graphics.drawString(font, Ui.ellipsis(place, radiusX * 2 - 10), -Math.min(font.width(place),
                    radiusX * 2 - 10) / 2, -4, ink, false);
            String stampTime = (date == null ? "" : date) + " " + (time == null || time.isBlank() ? "--:--" : time);
            graphics.drawString(font, Ui.ellipsis(stampTime, radiusX * 2 - 8),
                    -Math.min(font.width(stampTime), radiusX * 2 - 8) / 2, 4, faint | 0xFF000000, false);
            if (officer != null && !officer.isBlank()) {
                String line = Ui.ellipsis(officer, radiusX * 2 - 10);
                graphics.drawString(font, line, -font.width(line) / 2, radiusY - 9, faint | 0xFF000000, false);
            }
        } finally {
            graphics.pose().popPose();
        }
    }

    /** Ink blotch accents around a stamp so the mark does not look printed. */
    public static void inkSpecks(GuiGraphics graphics, int centerX, int centerY, int radiusX, int radiusY,
                                 int color, int seed) {
        int hash = seed;
        int ink = (color & 0x00FFFFFF) | 0x55000000;
        for (int i = 0; i < 5; i++) {
            hash = hash * 1664525 + 1013904223;
            int dx = (hash & 0xFF) * 2 * radiusX / 255 - radiusX;
            hash = hash * 1664525 + 1013904223;
            int dy = (hash & 0xFF) * 2 * radiusY / 255 - radiusY;
            if (Math.abs(dx) < radiusX / 2 && Math.abs(dy) < radiusY / 2) {
                continue;
            }
            graphics.fill(centerX + dx, centerY + dy, centerX + dx + 1, centerY + dy + 1, ink);
        }
    }
}
