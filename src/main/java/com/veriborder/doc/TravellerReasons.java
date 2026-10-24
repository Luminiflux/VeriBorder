package com.veriborder.doc;

import org.jetbrains.annotations.Nullable;

/**
 * Two-track rejection reasons (26.3 round 12).
 *
 * <p>Every denial is stored and logged with its <b>reason code</b> (a translation
 * key such as {@code verify.veriborder.reason.no_entry_record}). That code is the
 * administrator/officer track: it is written into the border log, shown by the
 * officer console and read by {@code /veriborder border log}.</p>
 *
 * <p>The traveller standing at the gate never sees those codes. {@link #key}
 * maps the code onto a short, natural-language sentence
 * ({@code verify.veriborder.traveller.*}) instead:</p>
 *
 * <ul>
 *   <li>no entry record - “you have no entry record, please contact an officer”;</li>
 *   <li>document does not belong to the traveller - “this document is not yours”;</li>
 *   <li>missing/!matching visa or endorsement - “you have no valid visa/endorsement”;</li>
 *   <li>expired document - “your document has expired”;</li>
 *   <li>overstay - “you have overstayed”;</li>
 *   <li>country not visa-free - “your country is not on the visa-free list”;</li>
 * </ul>
 *
 * <p>The class is deliberately free of Minecraft types so the mapping is
 * unit-testable and can be reused by the gate, the reader and the chat message.</p>
 */
public final class TravellerReasons {
    public static final String PREFIX = "verify.veriborder.traveller.";

    public static final String PASS = PREFIX + "pass";
    public static final String REJECTED = PREFIX + "rejected";
    public static final String NO_ENTRY_RECORD = PREFIX + "no_entry_record";
    public static final String NOT_HOLDER = PREFIX + "not_holder";
    public static final String NO_VALID_VISA = PREFIX + "no_valid_visa";
    public static final String NO_VALID_ENDORSEMENT = PREFIX + "no_valid_endorsement";
    public static final String NO_VALID_CREDENTIAL = PREFIX + "no_valid_credential";
    public static final String DOCUMENT_EXPIRED = PREFIX + "document_expired";
    public static final String DOCUMENT_INVALID = PREFIX + "document_invalid";
    public static final String OVERSTAY = PREFIX + "overstay";
    public static final String COUNTRY_NOT_VISA_FREE = PREFIX + "country_not_visa_free";
    public static final String ALREADY_ENTERED = PREFIX + "already_entered";

    /** Document kinds whose credential is a permit endorsement. */
    public static final String MODE_ENDORSEMENT = "endorsement";
    public static final String MODE_VISA = "visa";
    public static final String MODE_BOTH = "both";

    private TravellerReasons() {
    }

    /** Traveller key for a reason code, mode-agnostic. */
    public static String key(@Nullable String reasonCode) {
        return key(reasonCode, "");
    }

    /**
     * Traveller key for a reason code, using the gate mode to word the
     * "missing credential" case ({@code visa} / {@code endorsement} / {@code both}).
     */
    public static String key(@Nullable String reasonCode, @Nullable String gateMode) {
        String code = reasonCode == null ? "" : reasonCode.trim();
        return switch (code) {
            case "verify.veriborder.reason.no_entry_record" -> NO_ENTRY_RECORD;
            case "verify.veriborder.reason.session_document_mismatch" -> NOT_HOLDER;
            case "verify.veriborder.reason.session_record_missing" -> credentialKey(gateMode);
            case "verify.veriborder.reason.gate_code_mismatch",
                 "verify.veriborder.reason.gate_no_codes",
                 "verify.veriborder.reason.no_matching_code",
                 "verify.veriborder.reason.no_visa",
                 "verify.veriborder.reason.no_endorsement",
                 "verify.veriborder.reason.no_matching_visa",
                 "verify.veriborder.reason.visa_mismatch",
                 "verify.veriborder.reason.visa_type_mismatch",
                 "verify.veriborder.reason.endorsement_type_mismatch" -> credentialKey(gateMode);
            case "verify.veriborder.reason.gate_requires_passport",
                 "verify.veriborder.reason.gate_requires_permit",
                 "verify.veriborder.reason.gate_requires_visa_or_endorsement" -> credentialKey(gateMode);
            case "verify.veriborder.reason.expired" -> DOCUMENT_EXPIRED;
            case "verify.veriborder.reason.overstay" -> OVERSTAY;
            case "verify.veriborder.reason.country_not_visa_free" -> COUNTRY_NOT_VISA_FREE;
            case "verify.veriborder.reason.already_entered" -> ALREADY_ENTERED;
            case "verify.veriborder.reason.integrity",
                 "verify.veriborder.reason.lost",
                 "verify.veriborder.reason.cancelled",
                 "verify.veriborder.reason.suspended",
                 "verify.veriborder.reason.style",
                 "verify.veriborder.reason.status",
                 "verify.veriborder.reason.name",
                 "verify.veriborder.reason.number",
                 "verify.veriborder.reason.birth_date",
                 "verify.veriborder.reason.sex",
                 "verify.veriborder.reason.issue_date",
                 "verify.veriborder.reason.expiry_date",
                 "verify.veriborder.reason.date_order",
                 "verify.veriborder.reason.issue_future",
                 "verify.veriborder.reason.wrong_kind" -> DOCUMENT_INVALID;
            default -> REJECTED;
        };
    }

    /**
     * Traveller key for a whole verification result: a valid check reads
     * {@link #PASS}, otherwise the first reason decides. The administrator still
     * receives {@link VerificationResult#reasons()} untouched.
     */
    public static String of(@Nullable VerificationResult result) {
        return of(result, "");
    }

    /** Mode-aware variant of {@link #of(VerificationResult)}. */
    public static String of(@Nullable VerificationResult result, @Nullable String gateMode) {
        if (result == null) {
            return REJECTED;
        }
        if (result.valid()) {
            return PASS;
        }
        if (result.reasons().isEmpty()) {
            return REJECTED;
        }
        return key(result.reasons().get(0), gateMode);
    }

    /** Missing-credential wording for one gate mode. */
    private static String credentialKey(@Nullable String gateMode) {
        String mode = gateMode == null ? "" : gateMode.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (mode) {
            case MODE_ENDORSEMENT -> NO_VALID_ENDORSEMENT;
            case MODE_VISA -> NO_VALID_VISA;
            default -> NO_VALID_CREDENTIAL;
        };
    }
}
