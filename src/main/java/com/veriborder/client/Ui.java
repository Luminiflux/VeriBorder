package com.veriborder.client;

import com.veriborder.client.ui.IconPicker;
import com.veriborder.client.ui.PortraitArt;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentKind;
import com.veriborder.doc.DocumentStatus;
import com.veriborder.doc.TravellerReasons;
import com.veriborder.doc.VerificationResult;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

public final class Ui {
    public static final int BG = 0xF4080D14;
    public static final int PANEL = 0xFF101924;
    public static final int PANEL_2 = 0xFF162332;
    public static final int PANEL_3 = 0xFF1C2C3E;
    public static final int BORDER = 0xFF2B4056;
    public static final int TEXT = 0xFFE8F2FF;
    public static final int MUTED = 0xFF91A8BF;
    public static final int DIM = 0xFF62788E;
    public static final int GOLD = 0xFFD9B96E;
    public static final int GREEN = 0xFF3DDC97;
    public static final int RED = 0xFFEF5B5B;
    public static final int AMBER = 0xFFF2B84B;
    public static final int CYAN = 0xFF45D6D0;
    public static final int PURPLE = 0xFFA78BFA;
    /** Faint top highlight used by the shared {@code CardPanel} chrome. */
    public static final int BLEND_TOP = 0x40FFFFFF;

    // Current VeriScreen pose transform. Minecraft 1.20.1 applies scissor
    // rectangles in raw GUI coordinates (the pose stack is NOT taken into
    // account), so scissor calls must be mapped through the same transform the
    // screen uses for painting. These fields are set for the duration of one
    // screen render and reset afterwards.
    private static float scissorScale = 1.0F;
    private static float scissorOffsetX = 0.0F;
    private static float scissorOffsetY = 0.0F;

    private Ui() {
    }

    public static void setScreenTransform(float offsetX, float offsetY, float scale) {
        scissorOffsetX = offsetX;
        scissorOffsetY = offsetY;
        scissorScale = scale <= 0.0F ? 1.0F : scale;
    }

    public static void clearScreenTransform() {
        scissorOffsetX = 0.0F;
        scissorOffsetY = 0.0F;
        scissorScale = 1.0F;
    }

    /** Design-space scissor, converted to raw GUI coordinates. */
    public static void scissor(GuiGraphics graphics, int x1, int y1, int x2, int y2) {
        int sx1 = (int) Math.floor(scissorOffsetX + x1 * scissorScale);
        int sy1 = (int) Math.floor(scissorOffsetY + y1 * scissorScale);
        int sx2 = (int) Math.ceil(scissorOffsetX + x2 * scissorScale);
        int sy2 = (int) Math.ceil(scissorOffsetY + y2 * scissorScale);
        graphics.enableScissor(sx1, sy1, sx2, sy2);
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    /**
     * Whether the client reads the Chinese column of a bilingual label (26.4.1).
     * The template definition carries a Chinese and an English title; every
     * surface picks the one that matches the running language.
     */
    public static boolean chinese() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getLanguageManager() == null) {
            return false;
        }
        String selected = minecraft.getLanguageManager().getSelected();
        return selected != null && selected.toLowerCase(java.util.Locale.ROOT).startsWith("zh");
    }

    public static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, PANEL);
        // Subtle top-down gradient keeps the large opaque panel from looking flat.
        graphics.fillGradient(x + 1, y + 1, x + width - 1, y + 34, 0xFF182838, PANEL);
        // Very low-alpha scanlines: texture only, never enough to hurt legibility.
        for (int lineY = y + 42; lineY < y + height - 4; lineY += 6) {
            graphics.fill(x + 1, lineY, x + width - 1, lineY + 1, 0x06000000);
        }
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, 0x40FFFFFF);
        graphics.renderOutline(x, y, width, height, BORDER);
    }

    public static void inset(GuiGraphics graphics, int x, int y, int width, int height) {
        // Inner cards are nearly opaque and lighter than the panel so their
        // content stays high-contrast instead of blending into the backdrop.
        graphics.fill(x, y, x + width, y + height, 0xFF0B1621);
        graphics.fill(x, y, x + width, y + 1, 0xFF2A4157);
        graphics.renderOutline(x, y, width, height, 0xFF2A4157);
    }

    public static void divider(GuiGraphics graphics, int x, int y, int width) {
        graphics.fill(x, y, x + width, y + 1, BORDER);
    }

    public static void text(GuiGraphics graphics, String text, int x, int y, int color) {
        graphics.drawString(font(), text, x, y, color, false);
    }

    public static void text(GuiGraphics graphics, Component text, int x, int y, int color) {
        graphics.drawString(font(), text, x, y, color, false);
    }

    /** Draws a single line clipped to {@code width} design pixels. */
    public static void clipped(GuiGraphics graphics, Component text, int x, int y, int width, int color) {
        graphics.drawString(font(), ellipsis(text.getString(), width), x, y, color, false);
    }

    public static void clipped(GuiGraphics graphics, String text, int x, int y, int width, int color) {
        graphics.drawString(font(), ellipsis(text, width), x, y, color, false);
    }

    public static void centered(GuiGraphics graphics, Component text, int centerX, int y, int color) {
        graphics.drawCenteredString(font(), text, centerX, y, color);
    }

    public static void badge(GuiGraphics graphics, int x, int y, Component text, int color) {
        int width = font().width(text) + 10;
        graphics.fill(x, y, x + width, y + 12, (color & 0x00FFFFFF) | 0x33000000);
        graphics.renderOutline(x, y, width, 12, color);
        graphics.drawString(font(), text, x + 5, y + 2, color, false);
    }

    public static int statusColor(DocumentStatus status) {
        return status.color();
    }

    public static int checkColor(VerificationResult result) {
        if (result == null) {
            return MUTED;
        }
        return result.valid() ? GREEN : RED;
    }

    public static void wrapped(GuiGraphics graphics, Component component, int x, int y, int width, int color, int maxLines) {
        List<FormattedCharSequence> lines = font().split(component, width);
        int count = Math.min(maxLines, lines.size());
        for (int i = 0; i < count; i++) {
            graphics.drawString(font(), lines.get(i), x, y + i * (font().lineHeight + 2), color, false);
        }
    }

    public static void drawCheck(GuiGraphics graphics, VerificationResult check, int x, int y, int width, int height) {
        drawCheck(graphics, check, x, y, width, height, false);
    }

    /**
     * Verification banner.
     *
     * @param travellerFacing when true the reason line is the plain-language
     *                        traveller text ({@link com.veriborder.doc.TravellerReasons})
     *                        instead of the administrator reason codes, which stay
     *                        available in the officer console and the border log
     */
    public static void drawCheck(GuiGraphics graphics, VerificationResult check, int x, int y, int width, int height,
                                 boolean travellerFacing) {
        if (check == null) {
            return;
        }
        int color = checkColor(check);
        graphics.fill(x, y, x + width, y + height, (color & 0x00FFFFFF) | 0x26000000);
        graphics.fill(x, y, x + 3, y + height, color);
        graphics.renderOutline(x, y, width, height, (color & 0x00FFFFFF) | 0x88000000);
        String symbol = check.valid() ? "✓" : "✕";
        graphics.drawString(font(), symbol, x + 8, y + 4, color, false);
        Component status = Component.translatable(check.valid() ? "verify.veriborder.pass" : "verify.veriborder.fail");
        graphics.drawString(font(), status, x + 21, y + 4, color, false);
        String reasons = travellerFacing ? travellerReasons(check) : reasons(check);
        int reasonX = x + 21 + font().width(status) + 8;
        int maxWidth = width - (reasonX - x) - 8;
        if (maxWidth > 20) {
            graphics.drawString(font(), font().plainSubstrByWidth(reasons, maxWidth), reasonX, y + 4, MUTED, false);
        }
    }

    /** Traveller-facing reason sentence for a check (no administrator codes). */
    public static String travellerReasons(VerificationResult check) {
        if (check == null) {
            return "";
        }
        return Component.translatable(TravellerReasons.of(check)).getString();
    }

    public static String reasons(VerificationResult check) {
        if (!check.valid() && !check.reasons().isEmpty()) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < check.reasons().size(); i++) {
                if (i > 0) {
                    builder.append(" / ");
                }
                builder.append(Component.translatable(check.reasons().get(i)).getString());
            }
            return builder.toString();
        }
        if (check.valid()) {
            return Component.translatable("verify.veriborder.ok").getString();
        }
        return Component.translatable(check.code().translationKey()).getString();
    }

    public static void documentCard(GuiGraphics graphics, int x, int y, int width, int height, DocumentData doc) {
        if (width < 24 || height < 24) {
            return;
        }
        // 26.4.1: the card uses the colour copied from the issuing template (or the
        // document's own accent when it carries none) and the template emblem.
        int accent = doc == null ? GOLD : TemplateDisplay.documentTint(doc) | 0xFF000000;
        String emblem = doc == null ? "" : TemplateDisplay.documentEmblem(doc);
        int dark = doc == null ? 0xFF10151C : blend(accent, 0xFF111820, 0.72F);
        graphics.fillGradient(x, y, x + width, y + height, dark, 0xFF090E14);
        graphics.renderOutline(x, y, width, height, doc == null ? BORDER : accent);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 4, accent);
        scissor(graphics, x + 1, y + 1, x + width - 1, y + height - 1);        try {
            if (doc == null) {
                graphics.drawCenteredString(font(), Component.translatable("document.veriborder.empty"),
                        x + width / 2, y + height / 2 - 4, MUTED);
                return;
            }
            int contentWidth = width - 14;
            boolean passport = doc.kind() == DocumentKind.PASSPORT;
            // 26.4.1: name priority is template title -> display name -> official name.
            String title = TemplateDisplay.documentTitle(doc);
            int titleWidth = contentWidth - (doc.customStyle() ? 38 : 0) - (emblem.isBlank() ? 0 : 14);
            graphics.drawString(font(), ellipsis(title, Math.max(20, titleWidth)), x + 7, y + 7, 0xFFF7EEDB, false);
            if (!emblem.isBlank() && width > 90) {
                // Small template emblem on the title row: identifies the credential
                // series at a glance without adding another line.
                IconPicker.drawGlyph(graphics, emblem, x + width - 15, y + 5, 10, accent);
            }
            graphics.drawString(font(), ellipsis(doc.number(), contentWidth), x + 7, y + 17, 0xFFD8C9A8, false);
            if (doc.customStyle()) {
                String custom = Component.translatable("document.veriborder.custom").getString();
                int cw = font().width(custom) + 8;
                if (cw < width - 14) {
                    graphics.fill(x + width - 7 - cw, y + 7, x + width - 7, y + 17, 0x552FC6B7);
                    graphics.drawString(font(), custom, x + width - 3 - cw, y + 8, CYAN, false);
                }
            }
            if (passport) {
                int infoX = x + 8;
                int infoY = y + 35;
                // 26.5 portrait: a passport card shows the holder's photo too. The
                // viewer draws the same face on the cover plate, so the officer, the
                // issuing clerk and the holder all look at one image. The box is
                // reserved first and the text column then shrinks around it, so a
                // card without a resolvable portrait keeps exactly this geometry.
                int photoW = Math.min(30, Math.max(18, width / 5));
                int photoH = Math.min(42, Math.max(22, height - 62));
                int photoX = x + width - 8 - photoW;
                int photoY = infoY - 5;
                if (!PortraitArt.draw(graphics, photoX, photoY, photoW, photoH, doc, 0xFF263B4D)) {
                    graphics.fill(photoX, photoY, photoX + photoW, photoY + photoH, 0xFF263B4D);
                    graphics.renderOutline(photoX, photoY, photoW, photoH, 0x66FFFFFF);
                    graphics.fill(photoX + 5, photoY + 7, photoX + photoW - 5, photoY + 15, 0xFF8FA5B7);
                    graphics.fill(photoX + 3, photoY + 19, photoX + photoW - 3, photoY + photoH - 4,
                            0xFF6F899E);
                }
                int textWidth = Math.max(40, photoX - infoX - 4);
                graphics.drawString(font(), ellipsis(doc.name(), textWidth), infoX, infoY, TEXT, false);
                graphics.drawString(font(), ellipsis(Component.translatable(doc.sexKey()).getString()
                        + "  " + doc.birthDate(), textWidth), infoX, infoY + 11, MUTED, false);
                graphics.drawString(font(), ellipsis(doc.nationality(), textWidth), infoX, infoY + 22, MUTED, false);
                graphics.drawString(font(), ellipsis(Component.translatable("field.veriborder.expiry").getString()
                        + " " + (doc.isPermanent() ? Component.translatable("date.veriborder.permanent").getString()
                        : doc.expiryDate()), textWidth), infoX, infoY + 33,
                        doc.isExpired() ? RED : MUTED, false);
                String mrz = doc.mrz2();
                // The MRZ plate gets its own band only on tall cards. On the
                // compact cards (readers, modifier, issuer preview) it would
                // collide with the status/footer line, so it is omitted there.
                if (!mrz.isBlank() && height >= 150) {
                    graphics.pose().pushPose();
                    graphics.pose().translate(x + 7, y + height - 32, 0);
                    graphics.pose().scale(0.45F, 0.45F, 1.0F);
                    graphics.drawString(font(), ellipsis(mrz, (int) ((width - 62) / 0.45F)), 0, 0,
                            0xFFE4D8BC, false);
                    graphics.pose().popPose();
                }
            } else {
                int photoWidth = Math.min(30, Math.max(18, width / 4));
                int photoHeight = Math.min(42, Math.max(22, height - 62));
                int photoY = y + 30;
                // 26.4.1 portrait: the ID-card photo slot draws the real portrait
                // (skin / preset / uploaded PNG) whenever this client can resolve
                // one. The colour-block silhouette below is only the fallback, so a
                // card keeps its geometry and never leaves an empty hole. One hook
                // here covers the border terminal's document card and the print
                // preview, which both render through this method.
                if (!PortraitArt.draw(graphics, x + 8, photoY, photoWidth, photoHeight, doc, 0xFF263B4D)) {
                    graphics.fill(x + 8, photoY, x + 8 + photoWidth, photoY + photoHeight, 0xFF263B4D);
                    graphics.renderOutline(x + 8, photoY, photoWidth, photoHeight, 0x66FFFFFF);
                    graphics.fill(x + 13, photoY + 7, x + 8 + photoWidth - 5, photoY + 15, 0xFF8FA5B7);
                    graphics.fill(x + 11, photoY + 19, x + 8 + photoWidth - 3, photoY + photoHeight - 5, 0xFF6F899E);
                }
                int infoX = x + 12 + photoWidth;
                int textWidth = width - (infoX - x) - 7;
                graphics.drawString(font(), ellipsis(doc.name(), textWidth), infoX, y + 32, TEXT, false);
                graphics.drawString(font(), ellipsis(Component.translatable("field.veriborder.sex").getString()
                        + " " + Component.translatable(doc.sexKey()).getString(), textWidth),
                        infoX, y + 43, MUTED, false);
                graphics.drawString(font(), ellipsis(doc.birthDate(), textWidth), infoX, y + 54, MUTED, false);
                graphics.drawString(font(), ellipsis(Component.translatable("field.veriborder.expiry").getString()
                        + " " + (doc.isPermanent() ? Component.translatable("date.veriborder.permanent").getString()
                        : doc.expiryDate()), textWidth), infoX, y + 65,
                        doc.isExpired() ? RED : MUTED, false);
            }
            String status = Component.translatable(doc.status().translationKey()).getString();
            int statusWidth = font().width(status);
            int statusX = x + width - 7 - statusWidth;
            if (statusX > x + 8) {
                graphics.fill(statusX - 5, y + height - 14, statusX - 2, y + height - 11, doc.status().color());
                graphics.drawString(font(), status, statusX, y + height - 14, doc.status().color(), false);
            }
            String footer = passport ? Component.translatable("field.veriborder.issuer").getString()
                    + ": " + doc.authority() : doc.authority();
            graphics.drawString(font(), ellipsis(footer, Math.max(24, width - 18 - statusWidth)),
                    x + 7, y + height - 14, DIM, false);
            if (doc.customStyle()) {
                String type = ellipsis(doc.documentType(), contentWidth);
                graphics.drawString(font(), type, x + 7, y + height - 25, accent, false);
            }
        } finally {
            graphics.disableScissor();
        }
    }

    /**
     * 26.5 crest slot of a document card: the template's preset glyph when the
     * document carries one, the neutral cross otherwise, so old and new documents
     * keep the same card geometry.
     */
    public static void drawCardEmblem(GuiGraphics graphics, int x, int y, int size, int accent,
                                      String emblem) {
        if (emblem != null && !emblem.isBlank()) {
            IconPicker.drawGlyph(graphics, emblem, x, y, size, accent);
            return;
        }
        drawEmblem(graphics, x, y, size, accent);
    }

    /**
     * 26.4.1 credential colour band: the one-line live preview shared by the
     * registry template editor and the issuing machine. It shows the template's
     * tint as a band, its emblem on the left and its title on the right, which is
     * exactly what the issued cover will look like.
     */
    public static void credentialBand(GuiGraphics graphics, int x, int y, int width, int height,
                                      int tint, String emblem, String title, String footer) {
        if (width < 16 || height < 10) {
            return;
        }
        int color = (tint & 0x00FFFFFF) | 0xFF000000;
        int shade = blend(color, 0xFF0A1119, 0.66F);
        graphics.fillGradient(x, y, x + width, y + height, color, shade);
        graphics.renderOutline(x, y, width, height, (color & 0x00FFFFFF) | 0xCC000000);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, 0x55FFFFFF);
        int glyph = Math.min(height - 4, 14);
        if (emblem != null && !emblem.isBlank()) {
            int glyphY = y + (height - glyph) / 2;
            graphics.fill(x + 3, glyphY - 1, x + 6 + glyph, glyphY + glyph + 1, 0x44000000);
            IconPicker.drawGlyph(graphics, emblem, x + 4, glyphY, glyph, 0xFFFFFFFF);
        } else {
            graphics.fill(x + 4, y + 4, x + 4 + glyph, y + 4 + glyph, 0x33000000);
        }
        int textX = x + 9 + glyph;
        int textWidth = Math.max(12, width - (textX - x) - 6);
        String label = title == null ? "" : title;
        graphics.drawString(font(), ellipsis(label, textWidth), textX, y + 3, 0xFFFFFFFF, true);
        if (footer != null && !footer.isBlank() && height >= 22) {
            graphics.drawString(font(), ellipsis(footer, textWidth), textX, y + 13,
                    (blend(0xFFFFFFFF, color, 0.45F) & 0x00FFFFFF) | 0xCC000000, false);
        }
    }

    public static void drawEmblem(GuiGraphics graphics, int x, int y, int size, int color) {
        int cx = x + size / 2;
        int cy = y + size / 2;
        graphics.fill(cx - 1, y, cx + 1, y + size, color);
        graphics.fill(x, cy - 1, x + size, cy + 1, color);
        graphics.renderOutline(x + 3, y + 3, size - 6, size - 6, (color & 0x00FFFFFF) | 0xAA000000);
        graphics.fill(cx - 3, cy - 3, cx + 3, cy + 3, color);
    }

    public static int blend(int from, int to, float ratio) {
        int a = (int) (((from >>> 24) & 0xFF) * (1 - ratio) + ((to >>> 24) & 0xFF) * ratio);
        int r = (int) (((from >>> 16) & 0xFF) * (1 - ratio) + ((to >>> 16) & 0xFF) * ratio);
        int g = (int) (((from >>> 8) & 0xFF) * (1 - ratio) + ((to >>> 8) & 0xFF) * ratio);
        int b = (int) ((from & 0xFF) * (1 - ratio) + (to & 0xFF) * ratio);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static String format(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    /**
     * 26.2 month-precision validity text. 0 (or less) is "permanent"; whole
     * twelves collapse into years, so 3 -> "3 months", 12 -> "1 year",
     * 15 -> "1 year 3 months", 24 -> "2 years".
     */
    public static String validityText(int months) {
        if (months <= 0) {
            return Component.translatable("date.veriborder.permanent").getString();
        }
        int years = months / 12;
        int rest = months % 12;
        if (years <= 0) {
            return Component.translatable("duration.veriborder.months", rest).getString();
        }
        if (rest == 0) {
            return Component.translatable("duration.veriborder.years", years).getString();
        }
        return Component.translatable("duration.veriborder.years_months", years, rest).getString();
    }

    /**
     * New 26.3 UI strings are shared with the language owners: the code ships a
     * readable English fallback and automatically switches to the translation as
     * soon as the key exists in {@code lang/*.json}, so no screen blocks on a
     * lang file edit (and no owner has to overwrite another owner's keys).
     */
    public static Component label(String key, String fallback) {
        if (key == null || key.isBlank()) {
            return Component.literal(fallback == null ? "" : fallback);
        }
        if (I18n.exists(key)) {
            return Component.translatable(key);
        }
        return Component.literal(fallback == null ? key : fallback);
    }

    /** Same fallback contract as {@link #label(String, String)}, as plain text. */
    public static String labelText(String key, String fallback) {
        return label(key, fallback).getString();
    }

    /** Existing translation when present, otherwise the raw server value. */
    public static Component translatedOr(String key, String fallback) {
        if (key != null && !key.isBlank() && I18n.exists(key)) {
            return Component.translatable(key);
        }
        return Component.literal(fallback == null ? "" : fallback);
    }

    public static int rowColor(boolean hovered, boolean selected) {
        if (selected) {
            return 0xFF243A50;
        }
        return hovered ? 0xFF1A2A3B : 0x00000000;
    }

    public static String ellipsis(String value, int width) {
        return font().plainSubstrByWidth(value, width);
    }

    public static void tooltip(GuiGraphics graphics, int mouseX, int mouseY, Component... lines) {
        graphics.renderTooltip(font(), List.of(lines), java.util.Optional.empty(), mouseX, mouseY);
    }

    public static String statusLine(DocumentStatus status) {
        return Component.translatable(status.translationKey()).withStyle(ChatFormatting.WHITE).getString();
    }
}
