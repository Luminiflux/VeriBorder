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
