package com.veriborder.doc;

import java.util.function.ToIntFunction;

/**
 * Pure label rules shared by every place that prints a <em>document</em> title:
 * the document viewer's cover plate, the border terminal card, the reader, the
 * modifier, the chat messages and the item name.
 *
 * <p>26.4 display fix: an issuer-locked custom name ("Test") must replace the
 * layout name ("香港护照") printed on the cover and in the "issued" chat line.
 * The holder name is a different field and is never substituted here.</p>
 *
 * <p>The class deliberately depends on nothing but {@link String} and
 * {@link ToIntFunction} so the rules are unit-testable without a client: the
 * caller supplies the glyph measurement ({@code font::width}).</p>
 */
public final class DocumentTitles {
    /**
     * Smallest uniform scale the cover band accepts. Past this point the label is
     * truncated with a trailing ellipsis instead of shrinking into illegibility.
     */
    public static final float MIN_COVER_SCALE = 0.62F;
    /** Scale decrement tried while fitting a too-wide cover label. */
    private static final float SCALE_STEP = 0.04F;
    /** Trailing ellipsis used when even the smallest scale is still too wide. */
    public static final String ELLIPSIS = "\u2026";

    private DocumentTitles() {
    }

    /** One cover-band line: the text to draw plus the uniform scale it needs. */
    public record CoverLine(String text, float scale) {
        public boolean blank() {
            return text == null || text.isBlank();
        }
    }

    /**
     * The title printed on the cover: the custom document name when it has one,
     * otherwise {@code styleName} (the pre-26.4 behaviour, i.e. the layout name).
     *
     * <p>A whitespace-only custom name counts as absent, so a name that was
     * cleared in the modifier falls back to the layout name instead of blanking
     * the cover.</p>
     */
    public static String coverLabel(String displayName, String styleName) {
        return resolve(displayName, styleName);
    }

    /**
     * The document name used in chat/feedback lines ("Issued %s (%s)."): the
     * custom name when there is one, the official layout name otherwise.
     *
     * <p>Same resolution rule as {@link #coverLabel(String, String)}; it exists
     * separately so the message wording can never drift from the printed title
     * and so a blank custom name cannot leave a hole in the sentence.</p>
     */
    public static String issuedLabel(String displayName, String officialName) {
        return resolve(displayName, officialName);
    }

    /**
     * Fits a cover label into {@code maxWidth} design pixels: shrink first, then
     * truncate. The returned scale is never applied to anything but this line, so
     * a long custom name can never spill over the printed cover band.
     */
    public static CoverLine coverLine(String label, int maxWidth, ToIntFunction<String> widthOf) {
        String text = stripToEmpty(label);
        if (text.isEmpty()) {
            return new CoverLine("", 1.0F);
        }
        int limit = Math.max(1, maxWidth);
        int full = Math.max(0, widthOf.applyAsInt(text));
        if (full <= limit) {
            return new CoverLine(text, 1.0F);
        }
        for (float scale = 1.0F - SCALE_STEP; scale > MIN_COVER_SCALE; scale -= SCALE_STEP) {
            if (fits(text, limit, widthOf, scale)) {
                return new CoverLine(text, round(scale));
            }
        }
        if (fits(text, limit, widthOf, MIN_COVER_SCALE)) {
            return new CoverLine(text, MIN_COVER_SCALE);
        }
        // Still too wide at the smallest scale: keep the band intact and cut the
        // text instead (the scale already carries the ellipsis budget).
        int budget = (int) Math.floor(limit / MIN_COVER_SCALE);
        return new CoverLine(truncate(text, budget, widthOf), MIN_COVER_SCALE);
    }

    /**
     * Trailing-ellipsis truncation by measured width. Never splits a surrogate
     * pair; returns {@code ""} when not even the ellipsis fits.
     */
    public static String truncate(String value, int maxWidth, ToIntFunction<String> widthOf) {
        String text = stripToEmpty(value);
        if (text.isEmpty()) {
            return "";
        }
        int limit = Math.max(0, maxWidth);
        if (widthOf.applyAsInt(text) <= limit) {
            return text;
        }
        if (widthOf.applyAsInt(ELLIPSIS) > limit) {
            return "";
        }
        StringBuilder prefix = new StringBuilder();
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            String candidate = prefix.toString() + new String(Character.toChars(codePoint)) + ELLIPSIS;
            if (widthOf.applyAsInt(candidate) > limit) {
                break;
            }
            prefix.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
        }
        return prefix + ELLIPSIS;
    }

    private static String resolve(String custom, String fallback) {
        String value = stripToEmpty(custom);
        return value.isEmpty() ? stripToEmpty(fallback) : value;
    }

    private static boolean fits(String text, int limit, ToIntFunction<String> widthOf, float scale) {
        return widthOf.applyAsInt(text) * scale <= limit + 0.001F;
    }

    private static float round(float scale) {
        return Math.round(scale * 100.0F) / 100.0F;
    }

    /** Unicode-aware blank handling: an ideographic-space-only name is empty. */
    private static String stripToEmpty(String value) {
        return value == null ? "" : value.strip();
    }
}
