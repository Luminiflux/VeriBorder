package com.veriborder.client.ui;

/**
 * Sequential vertical flow ("顺序流式布局") for the gate configuration screens.
 *
 * <p>Every screen row is registered here and the layout hands back the row's
 * top: {@code int y = layout.row(height);} then the next row starts below it
 * after the configured gap. Nothing is allowed to hard-code an absolute y any
 * more, which is what produced the overlapping labels and fields in 26.4.</p>
 *
 * <p>The cursor has two coordinate spaces:</p>
 * <ul>
 *   <li><b>layout space</b> – the {@link #row(int)} result, always measured from
 *       the content top and independent of scrolling;</li>
 *   <li><b>paint space</b> – {@link #paintY(int)} subtracts the current scroll
 *       {@link #offset()}, so a caller can position a widget or paint a label
 *       without tracking the scroll itself.</li>
 * </ul>
 *
 * <p>{@link #contentHeight()} is the height the rows actually need.
 * {@link #scrollRange()} is {@code max(0, contentHeight - viewportHeight)}: the
 * largest legal offset. Because the offset is clamped to that range, every row
 * always stays inside the viewport band – at offset 0 the first row starts at
 * the top, at the maximum offset the last row ends at the bottom.</p>
 *
 * <p>This class only does arithmetic: no Minecraft types, no drawing, so the
 * row pitch / gap / scroll-range contract is unit-tested directly.</p>
 */
public final class FlowLayout {
    /** Default vertical gap between two rows. */
    public static final int DEFAULT_GAP = 6;
    /** Pixels per mouse-wheel notch. */
    public static final int SCROLL_STEP = 16;

    private final int x;
    private final int width;
    private final int top;
    private final int gap;
    private int y;
    private int viewportHeight = Integer.MAX_VALUE;
    private int offset;

    private FlowLayout(int x, int top, int width, int gap) {
        this.x = x;
        this.top = top;
        this.width = Math.max(1, width);
        this.gap = Math.max(0, gap);
        this.y = top;
    }

    /** A flow that starts at {@code (x, top)} with the default gap. */
    public static FlowLayout at(int x, int top, int width) {
        return new FlowLayout(x, top, width, DEFAULT_GAP);
    }

    /** A flow with an explicit gap (a tighter page can use 4, a roomy one 8). */
    public static FlowLayout at(int x, int top, int width, int gap) {
        return new FlowLayout(x, top, width, gap);
    }

    public int x() {
        return x;
    }

    public int width() {
        return width;
    }

    public int top() {
        return top;
    }

    public int gap() {
        return gap;
    }

    /** Next free row top, in layout space. */
    public int y() {
        return y;
    }

    /**
     * Registers a row of {@code height} pixels and returns its layout-space top.
     * The default gap is inserted below it.
     */
    public int row(int height) {
        return row(height, gap);
    }

    /** Registers a row with a custom gap below it (0 glues rows, 10 separates sections). */
    public int row(int height, int gapAfter) {
        int rowTop = y;
        y += Math.max(0, height) + Math.max(0, gapAfter);
        return rowTop;
    }

    /** Skips vertical space without reporting a row (section spacing / indents). */
    public void skip(int height) {
        y += Math.max(0, height);
    }

    /** Height the registered rows need, measured from the content top. */
    public int contentHeight() {
        return Math.max(0, y - top);
    }

    /** Sets the visible height of the scrolling band, then re-clamps the offset. */
    public void viewportHeight(int value) {
        this.viewportHeight = Math.max(0, value);
        this.offset = clamp(offset, 0, scrollRange());
    }

    public int viewportHeight() {
        return viewportHeight;
    }

    /** Largest legal scroll offset for the current content and viewport. */
    public int scrollRange() {
        if (viewportHeight == Integer.MAX_VALUE) {
            return 0;
        }
        return Math.max(0, contentHeight() - viewportHeight);
    }

    public int offset() {
        return offset;
    }

    /** Sets the scroll offset, clamped to {@code [0, scrollRange()]}. */
    public void offset(int value) {
        this.offset = clamp(value, 0, scrollRange());
    }

    /** Applies one wheel notch; {@code true} when the offset actually changed. */
    public boolean scrollBy(double wheelDelta) {
        if (wheelDelta == 0.0D) {
            return false;
        }
        int before = offset;
        offset(clamp(offset + (wheelDelta > 0 ? -SCROLL_STEP : SCROLL_STEP), 0, scrollRange()));
        return offset != before;
    }

    /** Layout-space y -> paint-space y for the current offset. */
    public int paintY(int layoutY) {
        return layoutY - offset;
    }

    /** Scrolls to the very bottom (the last row is fully visible). */
    public void scrollToBottom() {
        offset(scrollRange());
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}
