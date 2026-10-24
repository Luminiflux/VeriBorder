package com.veriborder.client.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whitelist popup contract (26.4 UI repair): one click toggles a country, the
 * search box filters by code / Chinese name / English name / nationality, the
 * action row selects the filtered set or clears everything, and the rows window
 * stays inside the popup.
 */
class CountryPickerStateTest {
    private static CountryPickerState state(List<String> selected) {
        CountryPickerState state = new CountryPickerState(selected);
        state.countries(List.of(
                new CountryPickerState.Entry("CHN", "中国", "China", "Chinese"),
                new CountryPickerState.Entry("JPN", "日本", "Japan", "Japanese"),
                new CountryPickerState.Entry("SGP", "新加坡", "Singapore", "Singaporean"),
                new CountryPickerState.Entry("USA", "美国", "United States", "American")));
        return state;
    }

    @Test
    void toggleAddsAndRemovesAndReportsTheNewState() {
        List<String> selected = new ArrayList<>();
        CountryPickerState state = state(selected);
        assertTrue(state.toggle("chn"), "lower-case input is normalised to CHN");
        assertTrue(state.isSelected("CHN"));
        assertEquals(1, state.selectedCount());
        assertTrue(selected.contains("CHN"), "the screen's own list is mutated in place");
        assertFalse(state.toggle("CHN"));
        assertEquals(0, state.selectedCount());
        assertFalse(state.toggle(""), "a blank code is ignored");
    }

    @Test
    void searchFiltersByCodeChineseNameEnglishNameAndNationality() {
        CountryPickerState state = state(new ArrayList<>());
        state.query("jpn");
        assertEquals(1, state.filtered().size());
        assertEquals("JPN", state.filtered().get(0).code());

        state.query("日本");
        assertEquals("JPN", state.filtered().get(0).code());

        state.query("singapore");
        assertEquals(1, state.filtered().size(), "matches both the name and the nationality");

        state.query("american");
        assertEquals("USA", state.filtered().get(0).code());

        state.query("");
        assertEquals(4, state.filtered().size());
    }

    @Test
    void selectAllOnlyTicksTheFilteredSet() {
        List<String> selected = new ArrayList<>();
        CountryPickerState state = state(selected);
        state.query("j");
        assertEquals(1, state.filtered().size(), "only Japan matches the letter j");
        state.selectAllFiltered();
        assertEquals(1, state.selectedCount());
        assertTrue(state.isSelected("JPN"));
        assertFalse(state.isSelected("CHN"), "countries outside the search stay untouched");
    }

    @Test
    void clearEmptiesTheSelection() {
        List<String> selected = new ArrayList<>(List.of("CHN", "JPN"));
        CountryPickerState state = state(selected);
        state.clear();
        assertTrue(selected.isEmpty());
        assertEquals(0, state.selectedCount());
    }

    @Test
    void emptyRegistryIsReportedInsteadOfAnEmptyList() {
        CountryPickerState state = new CountryPickerState(new ArrayList<>());
        assertTrue(state.empty());
        assertTrue(state.filtered().isEmpty());
        assertNull(state.entryAt(0));
    }

    @Test
    void rowsWindowScrollsAndClamps() {
        CountryPickerState state = state(new ArrayList<>());
        state.rows(2);
        assertEquals(2, state.maxScroll());
        assertEquals(2, state.visible().size());
        assertEquals("CHN", state.visible().get(0).code());

        state.scrollBy(-1);
        assertEquals(1, state.scroll());
        assertEquals("JPN", state.visible().get(0).code());
        state.scrollBy(-5);
        assertEquals(2, state.scroll());
        assertEquals("SGP", state.visible().get(0).code());

        // A new search resets the window to the first row.
        state.query("a");
        assertEquals(0, state.scroll());
        state.rows(1);
        assertEquals(1, state.rows());
        assertTrue(state.maxScroll() >= 1);
    }

    @Test
    void indexAtRowMapsToTheAbsoluteEntry() {
        CountryPickerState state = state(new ArrayList<>());
        state.rows(2);
        state.scrollBy(-1);
        assertEquals(1, state.indexAtRow(0));
        assertEquals(2, state.indexAtRow(1));
        assertEquals(-1, state.indexAtRow(9));
        assertEquals("JPN", state.entryAt(1).code());
    }
}
