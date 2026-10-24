package com.veriborder.client.ui;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared dropdown contract (26.4): the component replaces every "click to cycle"
 * button, so its mouse, keyboard, search and disabled behaviour is unit-tested
 * without a running client (only drawing needs a {@code Font}).
 */
class DropdownTest {
    private static Dropdown threeOptions() {
        return Dropdown.at(10, 10, 100)
                .options(List.of(
                        new Dropdown.Option("a", "Alpha"),
                        new Dropdown.Option("b", "Bravo"),
                        new Dropdown.Option("c", "Charlie")))
                .selected("a");
    }

    private static List<Dropdown.Option> numbered(int count) {
        List<Dropdown.Option> options = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            options.add(new Dropdown.Option("id" + i, "Entry " + (i < 10 ? "0" : "") + i));
        }
        return options;
    }

    @Test
    void clickOpensTheListAndSelectsARow() {
        Dropdown dropdown = threeOptions();
        assertFalse(dropdown.isOpen());
        assertTrue(dropdown.mouseClicked(20, 15), "clicking the closed control opens it");
        assertTrue(dropdown.isOpen());

        // The panel starts under the control (10 + 18 + 1) and rows are 13px.
        assertTrue(dropdown.mouseClicked(20, 31 + 13 + 2), "row 1 is Bravo");
        assertEquals("b", dropdown.selectedId());
        assertFalse(dropdown.isOpen());
    }

    @Test
    void keyboardMovesTheHighlightAndCommitsWithEnter() {
        Dropdown dropdown = threeOptions();
        dropdown.setOpen(true);
        assertTrue(dropdown.keyPressed(264, 0, 0), "ArrowDown is consumed");
        assertTrue(dropdown.keyPressed(257, 0, 0), "Enter is consumed");
        assertEquals("b", dropdown.selectedId());
        assertFalse(dropdown.isOpen());
    }

    @Test
    void escapeClosesWithoutChangingTheSelection() {
        Dropdown dropdown = threeOptions();
        dropdown.setOpen(true);
        assertTrue(dropdown.keyPressed(256, 0, 0));
        assertFalse(dropdown.isOpen());
        assertEquals("a", dropdown.selectedId());
    }

    @Test
    void longListsGetASearchRowThatFiltersAndCommits() {
        Dropdown dropdown = Dropdown.at(10, 10, 140)
                .options(numbered(12))
                .selected("id0");
        dropdown.setOpen(true);
        assertTrue(dropdown.charTyped('1', 0));
        assertEquals(3, dropdown.currentOptions().size(), "01, 10 and 11 match a '1' query");
        assertTrue(dropdown.charTyped('1', 0));
        List<Dropdown.Option> filtered = dropdown.currentOptions();
        assertEquals(1, filtered.size());
        assertEquals("id11", filtered.get(0).id());
        assertTrue(dropdown.keyPressed(257, 0, 0));
        assertEquals("id11", dropdown.selectedId());
    }

    @Test
    void aDisabledDropdownNeverOpensButKeepsItsTooltip() {
        Dropdown dropdown = threeOptions()
                .enabled(false)
                .tooltip("field.veriborder.sex");
        assertFalse(dropdown.mouseClicked(20, 15));
        assertFalse(dropdown.isOpen());
        assertFalse(dropdown.keyPressed(264, 0, 0));
        assertNotNull(dropdown.tooltip(), "the disabled control still explains itself");
    }

    @Test
    void selectionCanBeObservablyChangedByACallback() {
        List<String> seen = new ArrayList<>();
        Dropdown dropdown = threeOptions().onChange(seen::add);
        dropdown.setOpen(true);
        dropdown.keyPressed(264, 0, 0);
        dropdown.keyPressed(257, 0, 0);
        assertEquals(List.of("b"), seen);
    }

    @Test
    void optionsWithoutASelectionShowThePlaceholder() {
        Dropdown dropdown = Dropdown.at(0, 0, 80)
                .options(List.of(new Dropdown.Option("x", "X")))
                .placeholder("No countries/regions registered yet.");
        assertEquals("No countries/regions registered yet.", dropdown.display());
        assertNull(dropdown.selected());
        assertNull(dropdown.tooltip());
    }
}
