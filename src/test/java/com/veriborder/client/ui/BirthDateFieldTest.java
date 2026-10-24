package com.veriborder.client.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract of the pure value object behind the 26.2 three-dropdown date of birth
 * ({@link BirthDatePicker}). The picker itself only forwards to this class, so the
 * parsing, the fallback default, the leap-year rule, the day clamping and the
 * zero-padded output format are all tested without a running client.
 */
class BirthDateFieldTest {

    // ------------------------------------------------------------------ parsing

    @Test
    void aStoredValueParsesIntoTheThreeParts() {
        BirthDateField field = BirthDateField.parse("1991-02-03");

        assertEquals(1991, field.year());
        assertEquals(2, field.month());
        assertEquals(3, field.day());
        assertEquals("1991-02-03", field.value(), "the parsed value round-trips unchanged");
    }

    @Test
    void surroundingWhitespaceAndTheHistoricalDefaultBothParse() {
        assertEquals("1991-02-03", BirthDateField.parse("  1991-02-03  ").value());
        assertEquals("1990-01-01", BirthDateField.parse("1990-01-01").value());
    }

    @Test
    void blankAndInvalidValuesFallBackToTheDefault() {
        for (String invalid : new String[]{null, "", "   ", "1991-2-3", "not a date", "1990-02-30",
                "1991-13-01", "9999-12-31x"}) {
            assertEquals(BirthDateField.defaultField(), BirthDateField.parse(invalid),
                    "\"" + invalid + "\" must fall back to 1990-01-01");
            assertEquals("1990-01-01", BirthDateField.parse(invalid).value());
        }
    }

    // --------------------------------------------------------------- leap years

    @Test
    void februaryHas29DaysOnlyInALeapYear() {
        assertTrue(BirthDateField.isLeapYear(2024));
        assertTrue(BirthDateField.isLeapYear(2000), "a 400-year multiple is a leap year");
        assertFalse(BirthDateField.isLeapYear(1900), "a century that is not a 400 multiple is not");
        assertFalse(BirthDateField.isLeapYear(2023));

        assertEquals(29, BirthDateField.daysInMonth(2024, 2));
        assertEquals(28, BirthDateField.daysInMonth(2023, 2));
        assertEquals(29, BirthDateField.daysInMonth(2000, 2));
        assertEquals(28, BirthDateField.daysInMonth(1900, 2));
        assertEquals(31, BirthDateField.daysInMonth(2024, 1));
        assertEquals(30, BirthDateField.daysInMonth(2024, 4));
    }

    @Test
    void theTwentyNinthOfFebruarySurvivesAParseAndClampsWhenTheYearChanges() {
        BirthDateField leap = BirthDateField.parse("2024-02-29");
        assertEquals("2024-02-29", leap.value());

        assertEquals("2023-02-28", leap.withYear(2023).value(), "29 Feb clamps to 28 Feb");
        assertEquals("2000-02-29", leap.withYear(2000).value(), "2000 keeps the 29th");
    }

    @Test
    void anImpossibleTwentyNinthIsNotAcceptedByTheParser() {
        assertEquals("1990-01-01", BirthDateField.parse("2023-02-29").value());
    }

    // ------------------------------------------------------------- day clamping

    @Test
    void changingTheMonthClampsTheDayToItsLastDay() {
        BirthDateField endOfJanuary = BirthDateField.of(2023, 1, 31);

        assertEquals("2023-04-30", endOfJanuary.withMonth(4).value(), "April has 30 days");
        assertEquals("2023-02-28", endOfJanuary.withMonth(2).value(), "a common February has 28");
        assertEquals("2024-02-29", endOfJanuary.withYear(2024).withMonth(2).value());
        assertEquals("2023-03-31", endOfJanuary.withMonth(3).value(), "March keeps the 31st");
    }

    @Test
    void outOfRangePartsAreClampedInsteadOfProducingAnInvalidDate() {
        assertEquals("2023-02-28", BirthDateField.of(2023, 2, 31).value());
        assertEquals("2023-12-31", BirthDateField.of(2023, 13, 40).value());
        assertEquals("2023-01-01", BirthDateField.of(2023, 0, 0).value());
    }

    // ------------------------------------------------------------- output format

    @Test
    void theValueIsAlwaysZeroPaddedIso() {
        assertEquals("1990-01-01", BirthDateField.of(1990, 1, 1).value());
        assertEquals("2024-02-09", BirthDateField.of(2024, 2, 9).value());
        assertEquals("0005-03-07", BirthDateField.of(5, 3, 7).value(), "4-digit years are padded");
        assertEquals("03", BirthDateField.padded(3));
        assertEquals("31", BirthDateField.padded(31));
    }

    // ------------------------------------------------------------------- options

    @Test
    void theYearOptionsAreTheCurrentYearAndThe119BeforeItInDescendingOrder() {
        List<Integer> years = BirthDateField.yearOptions(2026);

        assertEquals(BirthDateField.YEAR_SPAN, years.size());
        assertEquals(120, years.size());
        assertEquals(2026, years.get(0), "newest year first");
        assertEquals(1907, years.get(years.size() - 1), "120 years back, inclusive");
        for (int i = 1; i < years.size(); i++) {
            assertEquals(years.get(i - 1) - 1, years.get(i), "strictly descending, one year apart");
        }
    }

    @Test
    void aStoredYearOutsideTheWindowStaysSelectable() {
        List<Integer> withOldYear = BirthDateField.yearOptions(2026, 1850);

        assertEquals(BirthDateField.YEAR_SPAN + 1, withOldYear.size(), "the stored year is added once");
        assertEquals(1850, withOldYear.get(withOldYear.size() - 1), "and keeps the descending order");
        assertEquals(BirthDateField.YEAR_SPAN, BirthDateField.yearOptions(2026, 1990).size(),
                "a year already in the window is not duplicated");
    }

    @Test
    void theDayOptionsFollowTheSelectedMonth() {
        List<Integer> leapFebruary = BirthDateField.dayOptions(2024, 2);
        assertEquals(29, leapFebruary.size());
        assertEquals(29, leapFebruary.get(leapFebruary.size() - 1));

        List<Integer> commonFebruary = BirthDateField.dayOptions(1900, 2);
        assertEquals(28, commonFebruary.size());

        List<Integer> april = BirthDateField.dayOptions(2023, 4);
        assertEquals(30, april.size());
        assertEquals(1, april.get(0));

        List<Integer> months = BirthDateField.monthOptions();
        assertEquals(12, months.size());
        assertEquals(1, months.get(0));
        assertEquals(12, months.get(11));
        // The instance helper reads the field's own year and month.
        assertEquals(31, BirthDateField.of(1990, 5, 15).dayOptions().size());
        assertEquals(30, BirthDateField.of(1990, 9, 15).dayOptions().size());
    }
}
