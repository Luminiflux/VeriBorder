package com.veriborder.doc;

import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;

/**
 * Derived state of a visa / endorsement record (26.3 round 12).
 *
 * <p>The stored {@code status} tag only ever records the administrator's decision
 * ({@code ACTIVE} / {@code REVOKED}). Whether a record may actually be used is
 * <em>derived</em> from that flag plus its entry budget and expiry date, so the
 * document can never show a stale "active" record and a gate can never accept
 * one:</p>
 *
 * <ol>
 *   <li>{@link #EXHAUSTED} - the entry budget is used up ({@code usedEntries >= entries});</li>
 *   <li>{@link #EXPIRED} - the expiry date has passed (blank/invalid counts as expired);</li>
 *   <li>{@link #REVOKED} - the stored status is anything but {@code ACTIVE}
 *       (revoked/disabled/cancelled);</li>
 *   <li>{@link #VALID} - everything else.</li>
 * </ol>
 *
 * <p>The order is the documented contract: a used-up record reads EXHAUSTED, an
 * expired one EXPIRED and only an otherwise-usable record shows REVOKED. This
 * keeps the UI honest (a record that ran out is not "valid until someone
 * revokes it") while still reporting an administrative revocation for a record
 * that has entries left.</p>
 *
 * <p>Only {@link #VALID} opens a gate, and {@link #VALID} already implies
 * {@code remainingEntries() > 0} (see {@link #derive}).</p>
 */
public enum CredentialState {
    VALID("valid", 0x34D399),
    EXHAUSTED("exhausted", 0xF2B84B),
    EXPIRED("expired", 0xEF5B5B),
    REVOKED("revoked", 0xA78BFA);

    private final String id;
    private final int color;

    CredentialState(String id, int color) {
        this.id = id;
        this.color = color;
    }

    public String id() {
        return id;
    }

    /** UI colour: green / amber / red / purple. */
    public int color() {
        return color;
    }

    /** Only a VALID record may be presented at a gate. */
    public boolean usable() {
        return this == VALID;
    }

    public String translationKey() {
        return "credential.status.veriborder." + id;
    }

    /** Resolves a persisted/derived id; unknown values read as {@link #VALID}. */
    public static CredentialState byId(String id) {
        if (id != null) {
            for (CredentialState state : values()) {
                if (state.id.equalsIgnoreCase(id.trim())) {
                    return state;
                }
            }
        }
        return VALID;
    }

    /** Derives the state against today's date. */
    public static CredentialState derive(String storedStatus, int entries, int usedEntries, String expiryDate) {
        return derive(storedStatus, entries, usedEntries, expiryDate, LocalDate.now());
    }

    /** Date-injectable variant of {@link #derive(String, int, int, String)} (tests). */
    public static CredentialState derive(String storedStatus, int entries, int usedEntries, String expiryDate,
                                        @Nullable LocalDate on) {
        LocalDate day = on == null ? LocalDate.now() : on;
        if (remaining(entries, usedEntries) <= 0) {
            return EXHAUSTED;
        }
        if (DateUtil.expiredOn(expiryDate, day)) {
            return EXPIRED;
        }
        if (revoked(storedStatus)) {
            return REVOKED;
        }
        return VALID;
    }

    /** {@code entries <= 0} means unlimited, matching {@code remainingEntries()}. */
    public static int remaining(int entries, int usedEntries) {
        return entries <= 0 ? Integer.MAX_VALUE : Math.max(0, entries - usedEntries);
    }

    /** A blank status means "never touched" and counts as active. */
    public static boolean revoked(String storedStatus) {
        return storedStatus != null
                && !storedStatus.isBlank()
                && !VisaRecord.ACTIVE.equalsIgnoreCase(storedStatus.trim());
    }
}
