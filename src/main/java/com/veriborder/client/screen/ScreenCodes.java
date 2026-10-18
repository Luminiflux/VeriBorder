package com.veriborder.client.screen;

/**
 * Client-side helpers for the 8-digit credential code entry fields. The
 * authoritative validation, generation and legacy derivation live in
 * {@code com.veriborder.doc.CredentialCode}; this only keeps the text fields
 * limited to digits and lets callers treat invalid input as "automatic".
 */
final class ScreenCodes {
    private ScreenCodes() {
    }

    /** Exactly 8 ASCII digits; leading zeros are significant. */
    static boolean isValid(String value) {
        if (value == null || value.length() != 8) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /** The trimmed value when it is a valid code, otherwise {@code ""}. */
    static String validOrBlank(String value) {
        String trimmed = value == null ? "" : value.trim();
        return isValid(trimmed) ? trimmed : "";
    }

    /** EditBox filter: digits only, never longer than eight characters. */
    static boolean digitsUpTo8(String value) {
        if (value == null || value.length() > 8) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
