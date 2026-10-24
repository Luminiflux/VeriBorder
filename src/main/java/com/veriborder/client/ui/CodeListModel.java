package com.veriborder.client.ui;

import com.veriborder.doc.CredentialCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Model of one gate-code list ("编码列表") — the recorded eight-digit credential
 * codes of a stage, shown row by row with a per-row delete button.
 *
 * <p>26.4 only printed the count ("出境编码：1") after adding a code, so the list
 * looked empty. The model now owns the data, the batch parser and the small
 * scroll window so all three screens render exactly the same thing and the
 * behaviour is unit-tested without a client:</p>
 *
 * <ul>
 *   <li>{@link #addBatch(String)} parses a comma / semicolon / space separated
 *       batch, ignores duplicates and reports {@code message.veriborder.code.*};</li>
 *   <li>{@link #removeAt(int)} backs the per-row delete button,
 *       {@link #removeLast()} the bulk button and {@link #clear()} "clear all";</li>
 *   <li>{@link #scrollBy(double)} / {@link #maxScroll()} keep the visible window
 *       inside the fixed {@link #height()} so the page never grows.</li>
 * </ul>
 *
 * <p>No Minecraft types are referenced, so the whole contract is unit-tested.</p>
 */
public final class CodeListModel {
    /** Height of one code row in design pixels (matches the shared list style). */
    public static final int ROW_HEIGHT = 13;
    /** Rows shown at once by default; the rest scrolls. */
    public static final int DEFAULT_ROWS = 3;
    /** Hard cap so a forged / pasted batch cannot grow the list without bound. */
    public static final int MAX_CODES = 4096;

    /** Error key of an unparseable batch. */
    public static final String ERROR_INVALID = "message.veriborder.code.invalid";
    /** Error key of a batch whose every code was already listed. */
    public static final String ERROR_DUPLICATE = "message.veriborder.code.duplicate";

    private final List<String> codes = new ArrayList<>();
    private int rows = DEFAULT_ROWS;
    private int scroll;
    private String errorKey = "";

    public CodeListModel() {
    }

    public CodeListModel(int rows) {
        rows(rows);
    }

    // ------------------------------------------------------------------ data

    /** Live, read-only view of the recorded codes (screen persists this order). */
    public List<String> codes() {
        return Collections.unmodifiableList(codes);
    }

    public int size() {
        return codes.size();
    }

    public boolean isEmpty() {
        return codes.isEmpty();
    }

    /** Code at one absolute index, or {@code ""} when out of range. */
    public String codeAt(int index) {
        return index >= 0 && index < codes.size() ? codes.get(index) : "";
    }

    public boolean contains(String code) {
        return code != null && codes.contains(code);
    }

    /**
     * Seeds the list from an already sanitised configuration (screen opening):
     * invalid, duplicate and over-cap values are dropped silently, no error is
     * reported because the operator did not type anything.
     */
    public void seed(java.util.Collection<String> values) {
        codes.clear();
        scroll = 0;
        errorKey = "";
        if (values == null) {
            return;
        }
        for (String value : values) {
            String code = value == null ? "" : value.trim();
            if (CredentialCode.isValid(code) && !codes.contains(code) && codes.size() < MAX_CODES) {
                codes.add(code);
            }
        }
    }

    /**
     * Parses a batch field into this list. Returns how many codes were added;
     * the resulting {@link #errorKey()} states what the operator should fix.
     */
    public int addBatch(String raw) {
        errorKey = "";
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            errorKey = ERROR_INVALID;
            return 0;
        }
        int added = 0;
        boolean anyToken = false;
        boolean invalid = false;
        boolean duplicate = false;
        for (String token : value.split("[,;\\s]+")) {
            if (token.isBlank()) {
                continue;
            }
            anyToken = true;
            if (!CredentialCode.isValid(token)) {
                invalid = true;
                continue;
            }
            if (codes.contains(token)) {
                duplicate = true;
                continue;
            }
            if (codes.size() >= MAX_CODES) {
                invalid = true;
                continue;
            }
            codes.add(token);
            added++;
        }
        if (!anyToken) {
            invalid = true;
        }
        if (invalid) {
            errorKey = ERROR_INVALID;
        } else if (added == 0 && duplicate) {
            errorKey = ERROR_DUPLICATE;
        }
        return added;
    }

    public boolean removeAt(int index) {
        if (index < 0 || index >= codes.size()) {
            return false;
        }
        codes.remove(index);
        clampScroll();
        return true;
    }

    public boolean removeLast() {
        return removeAt(codes.size() - 1);
    }

    public void clear() {
        codes.clear();
        scroll = 0;
        errorKey = "";
    }

    public String errorKey() {
        return errorKey;
    }

    public void clearError() {
        errorKey = "";
    }

    // ---------------------------------------------------------------- window

    public int rows() {
        return rows;
    }

    /** Visible row count, clamped to {@code 1..8}. */
    public void rows(int value) {
        rows = Math.max(1, Math.min(8, value));
        clampScroll();
    }

    /** Fixed height of the list block: the window never resizes while scrolling. */
    public int height() {
        return rows * ROW_HEIGHT;
    }

    public int maxScroll() {
        return Math.max(0, codes.size() - rows);
    }

    public int scroll() {
        return scroll;
    }

    /** Scrolls the window; the wheel follows the usual "up = towards the start" rule. */
    public boolean scrollBy(double wheelDelta) {
        if (wheelDelta == 0.0D) {
            return false;
        }
        int before = scroll;
        scroll = clamp(scroll + (wheelDelta > 0 ? -1 : 1), 0, maxScroll());
        return scroll != before;
    }

    /** Absolute index of the first visible row. */
    public int visibleStart() {
        return scroll;
    }

    /** Absolute index one past the last visible row (exclusive). */
    public int visibleEnd() {
        return Math.min(codes.size(), scroll + rows);
    }

    /** Whether the row at {@code index} is inside the current window. */
    public boolean isVisible(int index) {
        return index >= scroll && index < visibleEnd();
    }

    /** Design-space top of a row, relative to {@code listTop}; -1 when not visible. */
    public int rowTop(int index, int listTop) {
        if (!isVisible(index)) {
            return -1;
        }
        return listTop + (index - scroll) * ROW_HEIGHT;
    }

    /** Absolute index under {@code mouseY}, or -1 when the point is on no row. */
    public int indexAt(double mouseY, int listTop) {
        if (mouseY < listTop || mouseY >= listTop + height()) {
            return -1;
        }
        int row = (int) ((mouseY - listTop) / ROW_HEIGHT);
        int index = scroll + row;
        return index >= 0 && index < codes.size() ? index : -1;
    }

    private void clampScroll() {
        scroll = clamp(scroll, 0, maxScroll());
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}
