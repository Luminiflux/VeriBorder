package com.veriborder.doc;

import com.veriborder.block.DeviceType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DocumentValidator {
    /**
     * Gate modes (26.3): the {@code all} mode is gone. Every entry gate requires a
     * matching visa, endorsement or permanent code; legacy {@code all}/{@code any}
     * values load as {@link #GATE_MODE_BOTH}.
     */
    public static final String GATE_MODE_VISA = "visa";
    public static final String GATE_MODE_ENDORSEMENT = "endorsement";
    public static final String GATE_MODE_BOTH = "both";

    /** Legacy pre-26.3 aliases; every one of them now normalises to {@code both}. */
    @Deprecated
    public static final String GATE_MODE_ALL = GATE_MODE_BOTH;
    @Deprecated
    public static final String GATE_MODE_ANY = GATE_MODE_BOTH;
    @Deprecated
    public static final String GATE_MODE_PASSPORT = GATE_MODE_VISA;
    @Deprecated
    public static final String GATE_MODE_PERMIT = GATE_MODE_ENDORSEMENT;

    public static final String REASON_GATE_NO_CODES = "verify.veriborder.reason.gate_no_codes";
    public static final String REASON_GATE_CODE_MISMATCH = "verify.veriborder.reason.gate_code_mismatch";
    public static final String REASON_REQUIRES_PASSPORT = "verify.veriborder.reason.gate_requires_passport";
    public static final String REASON_REQUIRES_PERMIT = "verify.veriborder.reason.gate_requires_permit";
    public static final String REASON_REQUIRES_VISA_OR_ENDORSEMENT =
            "verify.veriborder.reason.gate_requires_visa_or_endorsement";
    /**
     * 26.3 round 12: the document's issuing country is not on the gate's
     * visa-free whitelist. Kept as an administrator reason code; the traveller
     * sees “your country is not on the visa-free list”
     * ({@link TravellerReasons#COUNTRY_NOT_VISA_FREE}).
     */
    public static final String REASON_COUNTRY_NOT_VISA_FREE = "verify.veriborder.reason.country_not_visa_free";

    private DocumentValidator() {
    }

    public static VerificationResult validate(DocumentData doc, DeviceType device, String destination) {
        return validateCore(doc, device, destination, false);
    }

    /**
     * Exit-gate validation (26.3): the same structural checks as
     * {@link #validate} but a document that is merely <em>expired</em> is still
     * allowed to leave. A traveller whose permit/endorsement ran out while they
     * were inside the country must never be trapped; the exit is logged as an
     * OVERSTAY and flagged to the player instead. Lost/cancelled/suspended and
     * malformed documents are still refused.
     */
    public static VerificationResult validateExit(DocumentData doc) {
        return validateCore(doc, DeviceType.EXIT_GATE, "", true);
    }

    /**
     * Gate entry point. Normal document checks always run first; when they pass,
     * the gate mode decides whether the document may pass:
     * <ul>
     *   <li>{@code visa}: PASSPORT, an active visa code or a permanent code must
     *       exactly equal one of the configured gate codes;</li>
     *   <li>{@code endorsement}: PERMIT, an active endorsement code must match;</li>
     *   <li>{@code both}: either of the above.</li>
     * </ul>
     * There is no "accept anything" mode: a gate without codes only lets through
     * a document whose permanent code matches the gate country (returning home /
     * visa-free), see {@link #matchPermanentCode(DocumentData, String)}.
     * The result detail carries the matched code on success and the required
     * mode on failure.
     */
    public static VerificationResult validateGate(DocumentData doc, DeviceType device, String gateMode,
                                                  List<String> gateCodes) {
        return validateGate(doc, device, gateMode, gateCodes, "");
    }

    /**
     * Gate entry point with the gate's country, so permanent codes can be scoped
     * to the country they were issued for (26.3). An empty country keeps only
     * universal (legacy) permanent codes valid.
     */
    public static VerificationResult validateGate(DocumentData doc, DeviceType device, String gateMode,
                                                  List<String> gateCodes, String gateCountry) {
        VerificationResult base = validateCore(doc, device, "", false);
        if (!base.valid()) {
            return base;
        }
        if (device == null || !device.isGate() || device == DeviceType.EXIT_GATE) {
            // Exit gates only verify that the document itself is valid.
            return base;
        }
        String mode = normalizeMode(gateMode);
        // 26.3 permanent-code slot (permit + passport): a code issued for the
        // gate's country satisfies every checking mode without a code match.
        String permanent = matchPermanentCode(doc, gateCountry);
        if (permanent != null) {
            return VerificationResult.ok(permanent);
        }
        List<String> codes = CredentialCode.normalizeAll(gateCodes);
        if (codes.isEmpty()) {
            return VerificationResult.fail(VerificationResult.Code.NO_MATCHING_CODE, REASON_GATE_NO_CODES)
                    .withDetail(mode);
        }
        boolean passport = doc.kind() == DocumentKind.PASSPORT;
        boolean permit = doc.kind() == DocumentKind.PERMIT;
        return switch (mode) {
            case GATE_MODE_VISA -> {
                if (!passport) {
                    yield wrongKind(REASON_REQUIRES_PASSPORT, mode);
                }
                yield match(codes, visaCandidates(doc), mode);
            }
            case GATE_MODE_ENDORSEMENT -> {
                if (!permit) {
                    yield wrongKind(REASON_REQUIRES_PERMIT, mode);
                }
                yield match(codes, endorsementCandidates(doc), mode);
            }
            case GATE_MODE_BOTH -> {
                if (passport) {
                    yield match(codes, visaCandidates(doc), mode);
                }
                if (permit) {
                    yield match(codes, endorsementCandidates(doc), mode);
                }
                yield wrongKind(REASON_REQUIRES_VISA_OR_ENDORSEMENT, mode);
            }
            default -> failNoMatch(mode);
        };
    }

    /**
     * The permanent code of {@code doc} that opens a gate in {@code gateCountry},
     * or {@code null} when none does (26.3 rules):
     *
     * <ul>
     *   <li>legacy code without {@code issuerCountry} — universal, matches every
     *       gate (26.2 documents keep working);</li>
     *   <li>{@code return_home} — only when the gate is in the code's own
     *       country: {@code gateCountry == issuerCountry};</li>
     *   <li>{@code visa_free} — the destination country must match the code and
     *       the optional expiry must still be valid;</li>
     *   <li>a scoped code never matches a gate whose country is unknown.</li>
     * </ul>
     */
    public static String matchPermanentCode(DocumentData doc, String gateCountry) {
        if (doc == null) {
            return null;
        }
        String gate = gateCountry == null ? "" : gateCountry.trim();
        for (DocumentData.PermanentCode record : doc.permanentCodes()) {
            if (!record.hasIssuerCountry()) {
                return record.code();
            }
            if (gate.isEmpty()) {
                continue;
            }
            if (record.purpose() == DocumentData.PermanentPurpose.VISA_FREE && record.expired()) {
                continue;
            }
            if (countryMatches(gate, record.issuerCountry())) {
                return record.code();
            }
        }
        return null;
    }

    /**
     * Deprecated pre-1.1.5 signature: visa/endorsement type requirements no
     * longer exist. The type arguments are ignored and the gate is evaluated as
     * a code gate with no configured codes.
     */
    @Deprecated
    public static VerificationResult validateGate(DocumentData doc, DeviceType device, String gateMode,
                                                  String requiredVisaType, String requiredEndorsementType) {
        return validateGate(doc, device, gateMode, List.of());
    }

    private static VerificationResult validateCore(DocumentData doc, DeviceType device, String destination,
                                                   boolean allowExpired) {
        if (doc == null) {
            return VerificationResult.fail(VerificationResult.Code.NO_DOCUMENT);
        }
        List<String> reasons = new ArrayList<>();
        VerificationResult.Code code = VerificationResult.Code.OK;

        if (!doc.integrityValid()) {
            code = VerificationResult.Code.TAMPERED;
            reasons.add("verify.veriborder.reason.integrity");
        }
        if (!doc.styleKnown()) {
            if (code == VerificationResult.Code.OK) {
                code = VerificationResult.Code.MALFORMED;
            }
            reasons.add("verify.veriborder.reason.style");
        }
        if (!doc.statusKnown()) {
            if (code == VerificationResult.Code.OK) {
                code = VerificationResult.Code.MALFORMED;
            }
            reasons.add("verify.veriborder.reason.status");
        }
        if (doc.name().isBlank()) {
            if (code == VerificationResult.Code.OK) {
                code = VerificationResult.Code.MALFORMED;
            }
            reasons.add("verify.veriborder.reason.name");
        }
        if (doc.number().isBlank()) {
            if (code == VerificationResult.Code.OK) {
                code = VerificationResult.Code.MALFORMED;
            }
            reasons.add("verify.veriborder.reason.number");
        }
        if (doc.status() != DocumentStatus.VALID) {
            if (code == VerificationResult.Code.OK) {
                code = switch (doc.status()) {
                    case LOST -> VerificationResult.Code.LOST;
                    case CANCELLED -> VerificationResult.Code.CANCELLED;
                    case SUSPENDED -> VerificationResult.Code.SUSPENDED;
                    default -> VerificationResult.Code.MALFORMED;
                };
            }
            reasons.add("verify.veriborder.reason." + doc.status().id());
        }

        LocalDate birth = DateUtil.parseOrNull(doc.birthDate());
        if (birth == null || birth.isAfter(LocalDate.now())) {
            if (code == VerificationResult.Code.OK) {
                code = VerificationResult.Code.MALFORMED;
            }
            reasons.add("verify.veriborder.reason.birth_date");
        }
        String sex = doc.sex();
        if (!sex.equals("M") && !sex.equals("F") && !sex.equals("X")) {
            if (code == VerificationResult.Code.OK) {
                code = VerificationResult.Code.MALFORMED;
            }
            reasons.add("verify.veriborder.reason.sex");
        }
        LocalDate issue = DateUtil.parseOrNull(doc.issueDate());
        LocalDate expiry = DateUtil.parseOrNull(doc.expiryDate());
        if (issue == null) {
            if (code == VerificationResult.Code.OK) {
                code = VerificationResult.Code.MALFORMED;
            }
            reasons.add("verify.veriborder.reason.issue_date");
        }
        if (expiry == null && !doc.isPermanent()) {
            if (code == VerificationResult.Code.OK) {
                code = VerificationResult.Code.MALFORMED;
            }
            reasons.add("verify.veriborder.reason.expiry_date");
        }
        if (expiry != null && issue != null && expiry.isBefore(issue)) {
            if (code == VerificationResult.Code.OK) {
                code = VerificationResult.Code.MALFORMED;
            }
            reasons.add("verify.veriborder.reason.date_order");
        }
        if (issue != null && issue.isAfter(LocalDate.now())) {
            if (code == VerificationResult.Code.OK) {
                code = VerificationResult.Code.MALFORMED;
            }
            reasons.add("verify.veriborder.reason.issue_future");
        }
        if (doc.isExpired() && !allowExpired) {
            if (code == VerificationResult.Code.OK) {
                code = VerificationResult.Code.EXPIRED;
            }
            reasons.add("verify.veriborder.reason.expired");
        }

        DocumentKind required = requiredKind(device);
        if (required != null && doc.kind() != required) {
            if (code == VerificationResult.Code.OK) {
                code = VerificationResult.Code.WRONG_KIND;
            }
            reasons.add("verify.veriborder.reason.wrong_kind");
        } else if (isStampDevice(device) && !isStampable(doc.kind())) {
            // Stamp machines/history accept passports and HK/Macau permits; ID
            // cards are never stamped. requiredKind() returns null for these so
            // the explicit check above states the two accepted kinds.
            if (code == VerificationResult.Code.OK) {
                code = VerificationResult.Code.WRONG_KIND;
            }
            reasons.add("verify.veriborder.reason.wrong_kind");
        }

        if (device == DeviceType.AIRPORT_CHECKIN && doc.kind() == DocumentKind.PASSPORT) {
            List<VisaRecord> validVisas = doc.visas().stream().filter(VisaRecord::active).toList();
            if (validVisas.isEmpty()) {
                if (code == VerificationResult.Code.OK) {
                    code = VerificationResult.Code.NO_VISA;
                }
                reasons.add("verify.veriborder.reason.no_visa");
            } else if (destination != null && !destination.isBlank()
                    && validVisas.stream().noneMatch(visa -> countryMatches(destination, visa.country()))) {
                if (code == VerificationResult.Code.OK) {
                    code = VerificationResult.Code.NO_MATCHING_VISA;
                }
                reasons.add("verify.veriborder.reason.visa_mismatch");
            }
        }

        String detail = "";
        if (device == DeviceType.AIRPORT_CHECKIN) {
            detail = destination == null ? "" : destination;
        }
        return new VerificationResult(code, reasons, detail);
    }

    private static List<String> visaCandidates(DocumentData doc) {
        List<String> candidates = new ArrayList<>();
        for (VisaRecord visa : doc.visas()) {
            if (visa.active()) {
                candidates.add(visa.code());
            }
        }
        candidates.addAll(doc.visaFreeCodes());
        return candidates;
    }

    private static List<String> endorsementCandidates(DocumentData doc) {
        List<String> candidates = new ArrayList<>();
        for (EndorsementRecord endorsement : doc.endorsements()) {
            if (endorsement.active()) {
                candidates.add(endorsement.code());
            }
        }
        return candidates;
    }

    private static VerificationResult match(List<String> gateCodes, List<String> candidates, String mode) {
        for (String candidate : candidates) {
            if (candidate != null && gateCodes.contains(candidate)) {
                return VerificationResult.ok(candidate);
            }
        }
        return VerificationResult.fail(VerificationResult.Code.NO_MATCHING_CODE, REASON_GATE_CODE_MISMATCH)
                .withDetail(mode);
    }

    private static VerificationResult wrongKind(String reason, String mode) {
        return VerificationResult.fail(VerificationResult.Code.WRONG_KIND, reason).withDetail(mode);
    }

    private static VerificationResult failNoMatch(String mode) {
        return VerificationResult.fail(VerificationResult.Code.NO_MATCHING_CODE, REASON_GATE_CODE_MISMATCH)
                .withDetail(mode);
    }

    /**
     * Canonical gate mode (26.3). {@code visa} / {@code passport} and
     * {@code endorsement} / {@code permit} keep their aliases; every other value —
     * including the removed {@code all} mode, {@code any}, a blank and {@code null}
     * — normalises to {@link #GATE_MODE_BOTH}, so no gate can ever accept a
     * document without a matching visa, endorsement or permanent code.
     */
    public static String normalizeMode(String gateMode) {
        if (gateMode == null) {
            return GATE_MODE_BOTH;
        }
        return switch (gateMode.trim().toLowerCase(Locale.ROOT)) {
            case GATE_MODE_VISA, "passport" -> GATE_MODE_VISA;
            case GATE_MODE_ENDORSEMENT, "permit" -> GATE_MODE_ENDORSEMENT;
            default -> GATE_MODE_BOTH;
        };
    }

    private static DocumentKind requiredKind(DeviceType device) {
        if (device == null) {
            return null;
        }
        return switch (device) {
            case STATION_VERIFIER -> DocumentKind.ID_CARD;
            case AIRPORT_CHECKIN, VISA_ISSUER, SELF_VISA -> DocumentKind.PASSPORT;
            case ENDORSEMENT_MACHINE, SELF_ENDORSEMENT -> DocumentKind.PERMIT;
            // Stamps accept PASSPORT or PERMIT; see isStampable / isStampDevice.
            default -> null;
        };
    }

    /** Stamp machines and the stamp history reader accept two document kinds. */
    public static boolean isStampDevice(DeviceType device) {
        return device == DeviceType.ENTRY_STAMP
                || device == DeviceType.EXIT_STAMP
                || device == DeviceType.STAMP_HISTORY;
    }

    /**
     * Only passports and HK/Macau permits carry immigration stamps. Identity
     * cards are never stamped, so stamp devices reject them explicitly.
     */
    public static boolean isStampable(DocumentKind kind) {
        return kind == DocumentKind.PASSPORT || kind == DocumentKind.PERMIT;
    }

    public static boolean countryMatches(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        String a = normalize(left);
        String b = normalize(right);
        if (a.isBlank() || b.isBlank()) {
            return false;
        }
        if (a.equals(b) || a.contains(b) || b.contains(a)) {
            return true;
        }
        return code(a).equals(code(b)) && !code(a).isBlank();
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String code(String normalized) {
        return switch (normalized) {
            case "cn", "china", "中国", "中华人民共和国" -> "chn";
            case "hk", "hongkong", "中国香港", "香港" -> "hkg";
            case "mo", "macau", "macao", "中国澳门", "澳门" -> "mac";
            case "us", "usa", "unitedstates", "美国" -> "usa";
            case "jp", "japan", "日本" -> "jpn";
            case "uk", "unitedkingdom", "英国" -> "gbr";
            case "schengen", "申根" -> "schen";
            default -> normalized;
        };
    }
}
