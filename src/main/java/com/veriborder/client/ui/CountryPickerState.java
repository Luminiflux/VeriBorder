package com.veriborder.client.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Selection state of the country/region whitelist popup (国家/地区白名单浮层).
 *
 * <p>26.3 rendered the whitelist as an inline fold the operator could not open
 * (the button was covered by the next block), and 26.4 only offered a "cycle"
 * button. The picker now opens a floating layer above the page; this class is
 * the layer's model and owns every rule that is worth testing on its own:</p>
 *
 * <ul>
 *   <li>{@link #toggle(String)} – a single click ticks / unticks one country;</li>
 *   <li>{@link #query(String)} / {@link #filtered()} – search by code, Chinese
 *       name, English name or default nationality, case-insensitively;</li>
 *   <li>{@link #selectAllFiltered()} / {@link #clear()} – the action row;</li>
 *   <li>{@link #selectedCount()} – the "selected: N" badge of the button;</li>
 *   <li>{@link #scrollBy(double)} – the rows window, so a 500-country registry
 *       still fits on screen.</li>
 * </ul>
 *
 * <p>The selected codes are the screen's own mutable list (passed by reference),
 * so saving stays exactly the same list the payload builder already knows. No
 * Minecraft types are referenced: the selection contract is unit-tested.</p>
 */
public final class CountryPickerState {
    /** Rows the popup shows at once by default. */
    public static final int DEFAULT_ROWS = 6;
    public static final int MAX_ROWS = 10;

    /** One selectable registry entry (a plain, test-friendly copy of the record). */
    public record Entry(String code, String nameZh, String nameEn, String nationality) {
        public Entry {
            code = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
            nameZh = nameZh == null ? "" : nameZh.trim();
            nameEn = nameEn == null ? "" : nameEn.trim();
            nationality = nationality == null ? "" : nationality.trim();
        }

        /** Whether this entry matches a lower-case search needle. */
        public boolean matches(String needle) {
            if (needle == null || needle.isBlank()) {
                return true;
            }
            return code.toLowerCase(Locale.ROOT).contains(needle)
                    || nameZh.toLowerCase(Locale.ROOT).contains(needle)
                    || nameEn.toLowerCase(Locale.ROOT).contains(needle)
                    || nationality.toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    private final List<Entry> countries = new ArrayList<>();
    private final List<Entry> filtered = new ArrayList<>();
    private final List<String> selected;
    private String query = "";
    private int rows = DEFAULT_ROWS;
    private int scroll;

    /** @param selected the screen's live whitelist (mutated in place) */
    public CountryPickerState(List<String> selected) {
        this.selected = selected == null ? new ArrayList<>() : selected;
    }

    /** Replaces the registry snapshot offered by the popup (keeps the selection). */
    public void countries(List<Entry> values) {
        countries.clear();
        if (values != null) {
            for (Entry entry : values) {
                if (entry != null && !entry.code().isEmpty() && !countries.contains(entry)) {
                    countries.add(entry);
                }
            }
        }
        filtered.clear();
        scroll = 0;
    }

    public List<Entry> countries() {
        return Collections.unmodifiableList(countries);
    }

    /** True when the registry has no country/region at all (the popup says so). */
    public boolean empty() {
        return countries.isEmpty();
    }

    public List<String> selectedCodes() {
        return Collections.unmodifiableList(selected);
    }

    public int selectedCount() {
        return selected.size();
    }

    public boolean isSelected(String code) {
        return code != null && selected.contains(code.trim().toUpperCase(Locale.ROOT));
    }

    /** Ticks or unticks one code; returns the new state. */
    public boolean toggle(String code) {
        String value = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            return false;
        }
        if (selected.remove(value)) {
            return false;
        }
        selected.add(value);
        return true;
    }

    /** Ticks every currently filtered country (select all of the search result). */
    public int selectAllFiltered() {
        int added = 0;
        for (Entry entry : filtered()) {
            if (!selected.contains(entry.code())) {
                selected.add(entry.code());
                added++;
            }
        }
        return added;
    }

    public void clear() {
        selected.clear();
    }

    // ---------------------------------------------------------------- search

    public String query() {
        return query;
    }

    /** Sets the search needle; the window returns to the first row. */
    public void query(String value) {
        String next = value == null ? "" : value.trim();
        if (!next.equals(query)) {
            query = next;
            scroll = 0;
        }
    }

    /** Countries matching the current query, in registry order. */
    public List<Entry> filtered() {
        if (query.isBlank()) {
            return countries;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        filtered.clear();
        for (Entry entry : countries) {
            if (entry.matches(needle)) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    // ---------------------------------------------------------------- window

    public int rows() {
        return rows;
    }

    /** Visible row count, clamped to {@code 1..}{@link #MAX_ROWS}. */
    public void rows(int value) {
        rows = Math.max(1, Math.min(MAX_ROWS, value));
        clampScroll();
    }

    public int maxScroll() {
        return Math.max(0, filtered().size() - rows);
    }

    public int scroll() {
        return scroll;
    }

    /** Wheel over the row area; {@code true} when the window moved. */
    public boolean scrollBy(double wheelDelta) {
        if (wheelDelta == 0.0D) {
            return false;
        }
        int before = scroll;
        scroll = clamp(scroll + (wheelDelta > 0 ? -1 : 1), 0, maxScroll());
        return scroll != before;
    }

    /** The entries of the current window, top to bottom. */
    public List<Entry> visible() {
        List<Entry> all = filtered();
        int from = Math.min(scroll, all.size());
        int to = Math.min(all.size(), from + rows);
        return all.subList(from, to);
    }

    /** Absolute index of the entry under a row (0-based row inside the window). */
    public int indexAtRow(int row) {
        int index = scroll + row;
        return index >= 0 && index < filtered().size() ? index : -1;
    }

    public Entry entryAt(int index) {
        List<Entry> all = filtered();
        return index >= 0 && index < all.size() ? all.get(index) : null;
    }

    private void clampScroll() {
        scroll = clamp(scroll, 0, maxScroll());
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}
