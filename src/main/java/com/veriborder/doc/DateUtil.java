package com.veriborder.doc;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

public final class DateUtil {
    public static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    public static final String PERMANENT = "9999-12-31";

    private DateUtil() {
    }

    public static String today() {
        return LocalDate.now().format(ISO);
    }

    public static String plusYears(String date, int years) {
        LocalDate parsed = parseOrNull(date);
        if (parsed == null) {
            parsed = LocalDate.now();
        }
        return parsed.plusYears(Math.max(0, years)).format(ISO);
    }

    /**
     * 26.2 validity months: adds {@code months} calendar months to {@code date}
     * (an unusable date falls back to today). Month arithmetic clamps to the end
     * of the target month exactly like {@link LocalDate#plusMonths(long)}
     * (2026-01-31 + 1 month = 2026-02-28, 2024-01-31 + 1 = 2024-02-29), so a
     * three-month endorsement issued on the 31st never rolls into the next month.
     */
    public static String plusMonths(String date, int months) {
        LocalDate parsed = parseOrNull(date);
        if (parsed == null) {
            parsed = LocalDate.now();
        }
        return parsed.plusMonths(Math.max(0, months)).format(ISO);
    }

    /** The number of whole years a month count represents (12 months = 1 year). */
    public static int wholeYears(int months) {
        return Math.max(0, months) / 12;
    }

    /** The month remainder after whole years (15 months = 1 year + 3). */
    public static int remainingMonths(int months) {
        return Math.max(0, months) % 12;
    }

    public static String plusDays(String date, int days) {
        LocalDate parsed = parseOrNull(date);
        if (parsed == null) {
            parsed = LocalDate.now();
        }
        return parsed.plusDays(days).format(ISO);
    }

    public static LocalDate parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), ISO);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    public static boolean isValid(String value) {
        return parseOrNull(value) != null;
    }

    public static boolean isPermanent(String value) {
        return PERMANENT.equals(value);
    }

    public static boolean expired(String expiry) {
        if (isPermanent(expiry)) {
            return false;
        }
        LocalDate date = parseOrNull(expiry);
        return date == null || date.isBefore(LocalDate.now());
    }

    public static boolean expiredOn(String expiry, LocalDate on) {
        if (isPermanent(expiry)) {
            return false;
        }
        LocalDate date = parseOrNull(expiry);
        return date == null || date.isBefore(on);
    }

    public static long daysRemaining(String expiry) {
        if (isPermanent(expiry)) {
            return Long.MAX_VALUE;
        }
        LocalDate date = parseOrNull(expiry);
        if (date == null) {
            return Long.MIN_VALUE;
        }
        return ChronoUnit.DAYS.between(LocalDate.now(), date);
    }

    public static String display(String value) {
        if (isPermanent(value)) {
            return "PERMANENT";
        }
        return value == null || value.isBlank() ? "—" : value;
    }

    public static String mrzDate(String value) {
        LocalDate date = parseOrNull(value);
        if (date == null) {
            return "000000";
        }
        return String.format("%02d%02d%02d", date.getYear() % 100, date.getMonthValue(), date.getDayOfMonth());
    }
}
