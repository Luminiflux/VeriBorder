package com.veriborder.client.ui;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wires the shared {@link Dropdown} to the {@link BirthDateField} value: the
 * three-dropdown date of birth can only be changed by picking a row, and picking
 * a year / month / day always produces a valid, zero-padded {@code YYYY-MM-DD}.
 *
 * <p>The interaction is driven through the same public keyboard API a player
 * uses, so this covers the real wiring (option lists, live day clamping and the
 * callback) without a running client.</p>
 */
class BirthDatePickerTest {

    private static BirthDatePicker picker(String stored) {
        return BirthDatePicker.at(10, 10, stored, null);
    }

    private static Dropdown year(BirthDatePicker picker) {
        return picker.dropdowns().get(0);
    }

    private static Dropdown month(BirthDatePicker picker) {
        return picker.dropdowns().get(1);
    }

    private static Dropdown day(BirthDatePicker picker) {
        return picker.dropdowns().get(2);
    }

    /** Opens the dropdown and walks the highlight to {@code id} with the arrow keys. */
    private static void choose(Dropdown dropdown, String id) {
        dropdown.setOpen(true);
        assertTrue(dropdown.isOpen(), "the control opens on click");
        List<Dropdown.Option> options = dropdown.currentOptions();
        int target = indexOf(options, id);
        int start = Math.max(0, indexOf(options, dropdown.selectedId()));
        assertTrue(target >= 0, id + " must be one of the offered rows");
        int steps = Math.floorMod(target - start, options.size());
        for (int i = 0; i < steps; i++) {
            assertTrue(dropdown.keyPressed(264, 0, 0), "ArrowDown moves the highlight");
        }
        assertTrue(dropdown.keyPressed(257, 0, 0), "Enter commits the row");
        assertEquals(id, dropdown.selectedId());
    }

    private static int indexOf(List<Dropdown.Option> options, String id) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------- composition

    @Test
    void pickingTheThreePartsComposesTheStoredString() {
        BirthDatePicker picker = picker("1991-02-03");

        choose(year(picker), "2000");
        assertEquals("2000-02-03", picker.value(), "only the year changed");

        choose(month(picker), "12");
        assertEquals("2000-12-03", picker.value(), "only the month changed");

        choose(day(picker), "31");
        assertEquals("2000-12-31", picker.value(), "only the day changed");
    }

    @Test
    void everyChangeIsReportedToTheScreen() {
        List<String> seen = new ArrayList<>();
        BirthDatePicker picker = BirthDatePicker.at(10, 10, "1991-02-03", field -> seen.add(field.value()));

        choose(day(picker), "7");
        choose(month(picker), "11");

        assertEquals(List.of("1991-02-07", "1991-11-07"), seen);
    }

    // ------------------------------------------------------------- day clamping

    @Test
    void theThirtyFirstClampsWhenTheNewMonthIsShorter() {
        BirthDatePicker picker = picker("2024-01-31");

        choose(month(picker), "4");

        assertEquals("2024-04-30", picker.value());
        assertEquals("30", day(picker).selectedId(), "the day control shows the clamped value");
        for (Dropdown.Option option : day(picker).currentOptions()) {
            assertTrue(Integer.parseInt(option.id()) <= 30, "April never offers the 31st");
        }
    }

    @Test
    void movingFromFebruaryToALongerMonthKeepsTheDay() {
        BirthDatePicker picker = picker("2024-02-29");

        choose(month(picker), "4");

        assertEquals("2024-04-29", picker.value(), "the 29th exists in April, so nothing is clamped");
    }

    @Test
    void changingTheYearAwayFromALeapYearClampsTheTwentyNinth() {
        BirthDatePicker picker = picker("2024-02-29");

        choose(year(picker), "2023");

        assertEquals("2023-02-28", picker.value());
        assertEquals(28, day(picker).currentOptions().size(), "a common February lists 28 days");
        assertEquals("28", day(picker).selectedId());
    }

    @Test
    void theDayListFollowsTheChosenMonth() {
        BirthDatePicker picker = picker("2023-02-10");
        assertEquals(28, day(picker).currentOptions().size(), "February 2023 has 28 days");

        choose(month(picker), "3");
        assertEquals(31, day(picker).currentOptions().size(), "March has 31 days");
    }

    // ------------------------------------------------------------------ defaults

    @Test
    void anInvalidStoredValueStartsAtTheDefaultDate() {
        BirthDatePicker picker = picker("31/02/1991");

        assertEquals("1990-01-01", picker.value());
        assertEquals("1990", year(picker).selectedId());
        assertEquals("1", month(picker).selectedId());
        assertEquals("1", day(picker).selectedId());
        assertEquals("01", month(picker).display(), "month rows are zero padded");
        assertEquals("01", day(picker).display(), "day rows are zero padded");
    }

    @Test
    void theClosedControlShowsTheStoredValue() {
        BirthDatePicker picker = picker("1988-07-09");

        assertEquals("1988", year(picker).display());
        assertEquals("07", month(picker).display());
        assertEquals("09", day(picker).display());
    }

    @Test
    void theYearListStartsAtTheCurrentYear() {
        BirthDatePicker picker = picker("1995-06-15");
        List<Dropdown.Option> years = year(picker).currentOptions();

        assertEquals(BirthDateField.YEAR_SPAN, years.size());
        assertEquals(String.valueOf(LocalDate.now().getYear()), years.get(0).id(), "newest year first");
        assertEquals("1995", year(picker).selectedId(), "the stored year is the selected row");
    }

    // ------------------------------------------------------------------- layout

    @Test
    void theThreeControlsSitSideBySideWithoutOverlapping() {
        BirthDatePicker picker = picker("1991-02-03");
        int y = 19;

        // Default widths: year 80 (x 10..90), month 56 (94..150), day 56 (154..210).
        assertTrue(year(picker).contains(10, y));
        assertTrue(year(picker).contains(90, y));
        assertFalse(year(picker).contains(92, y), "the gap belongs to no control");
        assertTrue(month(picker).contains(94, y));
        assertTrue(month(picker).contains(150, y));
        assertTrue(day(picker).contains(154, y));
        assertTrue(day(picker).contains(210, y));
        assertFalse(day(picker).contains(212, y));
    }

    @Test
    void theCompactLayoutUsedByTheModifierFitsItsNarrowColumn() {
        // The modifier's profile column is 150px wide and starts at x = 174.
        BirthDatePicker picker = BirthDatePicker.at(174, 178, 70, 42, 42, "1991-02-03", null);

        assertTrue(year(picker).contains(174, 187));
        assertTrue(year(picker).contains(244, 187));
        assertTrue(month(picker).contains(248, 187));
        assertTrue(month(picker).contains(290, 187));
        assertTrue(day(picker).contains(294, 187));
        assertTrue(day(picker).contains(336, 187), "the group ends at 336, left of the next column (344)");
        assertFalse(day(picker).contains(340, 187));
    }
}
