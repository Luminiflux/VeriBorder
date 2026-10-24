package com.veriborder.client.ui;

import com.veriborder.doc.DateUtil;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure value object behind the three-dropdown date-of-birth input (26.2).
 *
 * <p>The 26.2 form used to take the birthday as a free-text {@code YYYY-MM-DD}
 * field. It is now three dropdowns (year / month / day) so an invalid date can
 * never be typed at all, while the persisted value stays the very same
 * {@code YYYY-MM-DD} string written into the existing {@code birthDate} form key
 * (no save-format change). This class owns everything that does not need a
 * screen:</p>
 *
 * <ul>
 *   <li>{@link #parse(String)} turns a stored string into a field, falling back
 *       to {@code 1990-01-01} for blank / invalid input;</li>
 *   <li>{@link #value()} renders the zero-padded {@code YYYY-MM-DD} string;</li>
 *   <li>{@link #daysInMonth(int, int)} is leap-year correct, and
 *       {@link #withYear(int)} / {@link #withMonth(int)} clamp the day to the
 *       last day of the (new) month instead of inventing a date;</li>
 *   <li>{@link #yearOptions(int)} is the descending 120-year list a dropdown
 *       shows, while still keeping an out-of-range stored year selectable.</li>
 * </ul>
 *
 * <p>Instances are immutable: a picker replaces the field instead of mutating
 * it, which keeps the dropdown callbacks easy to reason about.</p>
 */
public final class BirthDateField {
    /** Fallback year for blank / unparsable input. */
    public static final int DEFAULT_YEAR = 1990;
    public static final int DEFAULT_MONTH = 1;
    public static final int DEFAULT_DAY = 1;
    /** The current year plus the 119 before it: the year dropdown offers 120. */
    public static final int YEAR_SPAN = 120;
    public static final int MIN_MONTH = 1;
    public static final int MAX_MONTH = 12;
    public static final int MIN_YEAR = 1;
    public static final int MAX_YEAR = 9999;

    private final int year;
    private final int month;
    private final int day;

    private BirthDateField(int year, int month, int day) {
        this.year = year;
        this.month = month;
        this.day = day;
    }

    /** {@code 1990-01-01}, the value blank or invalid input falls back to. */
    public static BirthDateField defaultField() {
        return new BirthDateField(DEFAULT_YEAR, DEFAULT_MONTH, DEFAULT_DAY);
    }

    /**
     * Builds a field from three parts. The month is clamped to 1..12 and the day
     * to the last day of that month, so the result is always a real date.
     */
    public static BirthDateField of(int year, int month, int day) {
        int safeYear = clamp(year, MIN_YEAR, MAX_YEAR);
        int safeMonth = clamp(month, MIN_MONTH, MAX_MONTH);
        int safeDay = clampDay(safeYear, safeMonth, day);
        return new BirthDateField(safeYear, safeMonth, safeDay);
    }

    /**
     * Parses a stored {@code YYYY-MM-DD} value. Blank, {@code null} and invalid
     * strings (malformed, 1990-02-30, "permanent", ...) all return
     * {@link #defaultField()}.
     */
    public static BirthDateField parse(@Nullable String value) {
        LocalDate date = DateUtil.parseOrNull(value);
        if (date == null) {
            return defaultField();
        }
        return of(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }

    public int year() {
        return year;
    }

    public int month() {
        return month;
    }

    public int day() {
        return day;
    }

    /** The persisted form value: zero-padded {@code YYYY-MM-DD}. */
    public String value() {
        return String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day);
    }

    /** Same field in another year; 29 February falls back to the 28th. */
    public BirthDateField withYear(int nextYear) {
        return of(nextYear, month, day);
    }

    /** Same field in another month; a 31st is clamped to that month's last day. */
    public BirthDateField withMonth(int nextMonth) {
        return of(year, nextMonth, day);
    }

    /** Same field on another day, clamped to the current month. */
    public BirthDateField withDay(int nextDay) {
        return of(year, month, nextDay);
    }

    /**
     * Days in a month: 29 in a leap February, 31 / 30 elsewhere. A month outside
     * 1..12 is clamped, so this never throws.
     */
    public static int daysInMonth(int year, int month) {
        int safeMonth = clamp(month, MIN_MONTH, MAX_MONTH);
        return switch (safeMonth) {
            case 2 -> isLeapYear(year) ? 29 : 28;
            case 4, 6, 9, 11 -> 30;
            default -> 31;
        };
    }

    /** Gregorian rule: every 4 years, except centuries, except every 400 years. */
    public static boolean isLeapYear(int year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    /** Clamps a day-of-month into {@code 1..daysInMonth(year, month)}. */
    public static int clampDay(int year, int month, int day) {
        return clamp(day, 1, daysInMonth(year, month));
    }

    /** Day numbers of the month the field currently sits in (1..28/29/30/31). */
    public List<Integer> dayOptions() {
        return dayOptions(year, month);
    }

    public static List<Integer> dayOptions(int year, int month) {
        int last = daysInMonth(year, month);
        List<Integer> days = new ArrayList<>(last);
        for (int day = 1; day <= last; day++) {
            days.add(day);
        }
        return days;
    }

    /** Month numbers 1..12, in calendar order. */
    public static List<Integer> monthOptions() {
        List<Integer> months = new ArrayList<>(MAX_MONTH);
        for (int month = MIN_MONTH; month <= MAX_MONTH; month++) {
            months.add(month);
        }
        return months;
    }

    /** The year dropdown of {@code currentYear}: descending, {@value #YEAR_SPAN} entries. */
    public static List<Integer> yearOptions(int currentYear) {
        return yearOptions(currentYear, 0);
    }

    /**
     * The descending year list (current year first, then the 119 before it). A
     * stored {@code keepYear} outside that window is kept selectable: an
     * out-of-range birthday from an older save must not silently change when the
     * form is opened.
     */
    public static List<Integer> yearOptions(int currentYear, int keepYear) {
        int newest = clamp(currentYear, MIN_YEAR, MAX_YEAR);
        List<Integer> years = new ArrayList<>(YEAR_SPAN + 1);
        for (int i = 0; i < YEAR_SPAN; i++) {
            int year = newest - i;
            if (year < MIN_YEAR) {
                break;
            }
            years.add(year);
        }
        if (keepYear >= MIN_YEAR && keepYear <= MAX_YEAR && !years.contains(keepYear)) {
            int index = 0;
            while (index < years.size() && years.get(index) > keepYear) {
                index++;
            }
            years.add(index, keepYear);
        }
        return years;
    }

    /** Two-digit label for a month / day dropdown row ({@code 01}..{@code 31}). */
    public static String padded(int value) {
        return String.format(Locale.ROOT, "%02d", value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BirthDateField field)) {
            return false;
        }
        return year == field.year && month == field.month && day == field.day;
    }

    @Override
    public int hashCode() {
        return (year * 100 + month) * 100 + day;
    }

    @Override
    public String toString() {
        return value();
    }
}
