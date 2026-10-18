package com.veriborder.doc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Eight-digit credential codes. Since 1.1.5 the gate validates the code shown
 * on a visa, endorsement or visa-free entry instead of a visa/endorsement type,
 * so every credential needs a stable, human-enterable code.
 *
 * <p>Codes are always exactly eight ASCII digits: {@code 00000000} through
 * {@code 99999999}. Leading zeros are significant, therefore codes are stored
 * and compared as strings and never parsed as numbers.</p>
 */
public final class CredentialCode {
    /** Number of digits in a credential code. */
    public static final int LENGTH = 8;
    /** The all-zero code used for records whose id is blank. */
    public static final String ZERO = "00000000";

    private static final int CODE_SPACE = 100_000_000;
    private static final Pattern DIGITS = Pattern.compile("\\d{8}");

    private CredentialCode() {
    }

    /** Exactly eight digits, nothing else. */
    public static boolean isValid(String code) {
        return code != null && DIGITS.matcher(code).matches();
    }

    /** A uniformly random code in 00000000..99999999 (eight characters). */
    public static String random() {
        return format(ThreadLocalRandom.current().nextInt(CODE_SPACE));
    }

    /**
     * A random code that is not present in {@code used}. Uniqueness is checked
     * against the raw strings, so invalid entries in {@code used} never match a
     * generated code.
     */
    public static String generateUnique(Collection<String> used) {
        Set<String> taken = new LinkedHashSet<>();
        if (used != null) {
            for (String code : used) {
                if (isValid(code)) {
                    taken.add(code);
                }
            }
        }
        for (int attempt = 0; attempt < 512; attempt++) {
            String candidate = random();
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        // Practically unreachable (512 draws from 10^8); scan low codes so a
        // caller with a pathological used-set still terminates deterministically.
        for (int i = 0; i < 100_000; i++) {
            String candidate = format(i);
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        return random();
    }

    /**
     * Deterministic eight-digit code for a record that predates 1.1.5. The same
     * id always yields the same code, so the code the UI shows for an old record
     * is the code an officer can type at the gate.
     */
    public static String legacy(String id) {
        if (id == null || id.isBlank()) {
            return ZERO;
        }
        byte[] digest = sha256(id.trim());
        long value = 0L;
        for (int i = 0; i < LENGTH; i++) {
            value = (value * 256L + (digest[i] & 0xFFL)) % CODE_SPACE;
        }
        return format((int) value);
    }

    /**
     * Trims, drops invalid entries and removes duplicates while preserving the
     * original order. Used by gates and document data so stored code lists stay
     * canonical.
     */
    public static List<String> normalizeAll(Collection<String> codes) {
        List<String> result = new ArrayList<>();
        if (codes == null) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : codes) {
            if (raw == null) {
                continue;
            }
            String trimmed = raw.trim();
            if (isValid(trimmed) && seen.add(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** Trims a code, returning an empty string when it is not a valid code. */
    public static String sanitize(String code) {
        if (code == null) {
            return "";
        }
        String trimmed = code.trim();
        return isValid(trimmed) ? trimmed : "";
    }

    private static String format(int value) {
        return String.format(Locale.ROOT, "%08d", value);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
