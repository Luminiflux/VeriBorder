package com.veriborder.doc;

import com.veriborder.block.DeviceType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DocumentValidator {
    /** Gate modes: all documents, visa code, endorsement code, or either code. */
    public static final String GATE_MODE_ALL = "all";
    public static final String GATE_MODE_VISA = "visa";
    public static final String GATE_MODE_ENDORSEMENT = "endorsement";
    public static final String GATE_MODE_BOTH = "both";

    /** Legacy pre-1.1.5 aliases; accepted by {@link #normalizeMode(String)}. */
    @Deprecated
    public static final String GATE_MODE_ANY = GATE_MODE_ALL;
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

    private DocumentValidator() {
    }

    public static VerificationResult validate(DocumentData doc, DeviceType device, String destination) {
        return validateCore(doc, device, destination);
    }

    /**
     * Gate entry point. Normal document checks always run first; when they pass,
     * the gate mode decides whether the document may pass:
     * <ul>
     *   <li>{@code all}: any valid document, no code requirement;</li>
     *   <li>{@code visa}: PASSPORT, an active visa code or a visa-free code must
     *       exactly equal one of the configured gate codes;</li>
     *   <li>{@code endorsement}: PERMIT, an active endorsement code must match;</li>
     *   <li>{@code both}: either of the above.</li>
     * </ul>
     * The result detail carries the matched code on success and the required
     * mode on failure.
     */
    public static VerificationResult validateGate(DocumentData doc, DeviceType device, String gateMode,
                                                  List<String> gateCodes) {
        VerificationResult base = validateCore(doc, device, "");
        if (!base.valid()) {
            return base;
        }
        if (device == null || !device.isGate() || device == DeviceType.EXIT_GATE) {
            // Exit gates only verify that the document itself is valid.
            return base;
        }
        String mode = normalizeMode(gateMode);
        if (GATE_MODE_ALL.equals(mode)) {
            return base;
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
            default -> base;
        };
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

    private static VerificationResult validateCore(DocumentData doc, DeviceType device, String destination) {
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
        if (doc.isExpired()) {
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

    /** Legacy aliases normalize as any -> all, passport -> visa, permit -> endorsement. */
    public static String normalizeMode(String gateMode) {
        if (gateMode == null) {
            return GATE_MODE_ALL;
        }
        return switch (gateMode.trim().toLowerCase(Locale.ROOT)) {
            case GATE_MODE_VISA, "passport" -> GATE_MODE_VISA;
            case GATE_MODE_ENDORSEMENT, "permit" -> GATE_MODE_ENDORSEMENT;
            case GATE_MODE_BOTH -> GATE_MODE_BOTH;
            case GATE_MODE_ALL, "any" -> GATE_MODE_ALL;
            default -> GATE_MODE_ALL;
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
