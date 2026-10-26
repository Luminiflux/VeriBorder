package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.time.LocalDate;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public final class DocumentFactory {
    /**
     * Trusted form key an issuing terminal injects with the administrator's
     * locked document title. 26.4: the lock must not clear the configured name,
     * so the device supplies it under its own key and the applicant's
     * {@code displayName} is ignored while the lock is on.
     */
    public static final String LOCKED_DISPLAY_NAME = "lockedDisplayName";
    /** Trusted form key: accent colour of the locked registry template. */
    public static final String LOCKED_STYLE_COLOR = "lockedStyleColor";
    /** Trusted form key: icon id of the locked registry template. */
    public static final String LOCKED_STYLE_ICON = "lockedStyleIcon";
    /**
     * 26.4.1 trusted form keys: the registry template the issuing machine
     * resolved (id + the document's own snapshot of its look). Only the server
     * writes them; the applicant form's plain {@code templateId} / {@code tint} /
     * {@code emblem} / {@code templateTex} keys are never read.
     */
    public static final String LOCKED_TEMPLATE_ID = "lockedTemplateId";
    public static final String LOCKED_TEMPLATE_TINT = "lockedTemplateTint";
    public static final String LOCKED_TEMPLATE_EMBLEM = "lockedTemplateEmblem";
    public static final String LOCKED_TEMPLATE_TEX = "lockedTemplateTex";
    /** Trusted form key: the template's own title, used when nothing names the document. */
    public static final String LOCKED_TEMPLATE_TITLE = "lockedTemplateTitle";

    private DocumentFactory() {
    }

    public static DocumentData fromForm(DocStyle style, CompoundTag form) {
        DocumentData data = DocumentData.createEmpty(style);
        // 26.2 identity locks. "lockDocumentName" locks the DOCUMENT name (the
        // printed title / custom cover name), not the holder's name: the holder
        // name always comes from the form. "lockNumber" keeps the number
        // system-generated.
        boolean lockDocumentName = form.getBoolean("lockDocumentName");
        boolean lockNumber = form.getBoolean("lockNumber");
        String name = form.getString("name");
        String nameLatin = form.getString("nameLatin");
        String sex = normalizeSex(form.getString("sex"));
        String birthDate = validOr(form.getString("birthDate"), "1990-01-01");
        String issueDate = validOr(form.getString("issueDate"), DateUtil.today());
        String authority = blankTo(form.getString("authority"), style.defaultAuthority());
        String nationality = blankTo(form.getString("nationality"), style.defaultNationality());
        String place = blankTo(form.getString("placeOfIssue"), style.defaultPlace());
        String address = blankTo(form.getString("address"), defaultAddress(style));
        String region = form.getString("numberRegion");
        String sequence = form.getString("numberSequence");
        String number;
        if (lockNumber) {
            number = generateNumber(style, birthDate, sex, region, sequence);
        } else {
            number = form.getString("number").trim();
            if (number.isBlank()) {
                number = generateNumber(style, birthDate, sex, region, sequence);
            }
        }
        String expiry;
        if (style.permanent()) {
            expiry = DateUtil.PERMANENT;
        } else {
            String requested = form.getString("expiryDate");
            expiry = DateUtil.isValid(requested) ? requested : DateUtil.plusYears(issueDate, style.validityYears());
            if (DateUtil.parseOrNull(expiry).isBefore(DateUtil.parseOrNull(issueDate))) {
                expiry = DateUtil.plusYears(issueDate, style.validityYears());
            }
        }

        data.setName(blankTo(name, sampleName(style)));
        data.setNameLatin(blankTo(nameLatin, deriveLatin(data.name())));
        data.setSex(sex);
        data.setBirthDate(birthDate);
        data.setNumber(number);
        data.setAddress(address);
        data.setAuthority(authority);
        data.setIssueDate(issueDate);
        data.setExpiryDate(expiry);
        data.setNationality(nationality);
        data.setPlaceOfIssue(place);
        data.setStatus(DocumentStatus.VALID);
        // 26.3 registry: one issuing-country field for every document kind. The
        // registry code (form issuerCountry) wins; the legacy mrzCountry key and
        // finally the layout code keep old forms/documents working.
        String issuerCountry = form.getString(DocumentData.ISSUER_COUNTRY).trim();
        if (issuerCountry.isBlank()) {
            issuerCountry = form.getString(DocumentData.LEGACY_MRZ_COUNTRY).trim();
        }
        data.setIssuerCountry(issuerCountry.isBlank() ? style.mrzCountry() : issuerCountry);
        applyVisaFreeCodes(data, form);
        if (!lockDocumentName) {
            applyCustomization(data, form);
        }
        // Independent display name: applied after the profile/custom cover so it
        // always survives document creation and never gets overwritten by them.
        // 26.4 fix: a locked document name is taken from the TRUSTED device key
        // ("lockedDisplayName") the issuing terminal injects, not from the
        // applicant's "displayName" — the old code cleared the name, so a locked
        // custom title never reached the issued document. A blank locked value
        // still means "keep the official preset title".
        String displayName = lockDocumentName
                ? form.getString(LOCKED_DISPLAY_NAME).trim()
                : form.getString("displayName").trim();
        // 26.4.1: the registry template's own title is the next candidate — an
        // explicit (locked or typed) name always wins, the official layout name
        // stays the last fallback.
        if (displayName.isBlank()) {
            displayName = form.getString(LOCKED_TEMPLATE_TITLE).trim();
        }
        if (!displayName.isBlank()) {
            data.setDisplayName(displayName);
        }
        // 26.4 registry-template layout lock: the device may carry the template's
        // colour/icon next to the layout id. Both keys are server-injected, and a
        // document that already has a custom cover keeps its own colour.
        if (form.contains(LOCKED_STYLE_COLOR) && !data.customStyle()) {
            data.setStyleColor(form.getInt(LOCKED_STYLE_COLOR));
        }
        String styleIcon = form.getString(LOCKED_STYLE_ICON).trim();
        if (!styleIcon.isBlank()) {
            data.setCustomIcon(styleIcon);
        }
        // 26.4.1: the template snapshot the document keeps for itself. The id is
        // only written when the server resolved a real template, so a document
        // never claims a template it was not issued from.
        String templateId = form.getString(LOCKED_TEMPLATE_ID).trim();
        if (!templateId.isBlank()) {
            data.applyRegistryTemplate(templateId,
                    form.contains(LOCKED_TEMPLATE_TINT) ? form.getInt(LOCKED_TEMPLATE_TINT) : 0,
                    form.getString(LOCKED_TEMPLATE_EMBLEM),
                    form.getString(LOCKED_TEMPLATE_TEX));
        }
        // 26.4.1 portrait: the reference travels in one signed write with its
        // owner. The issuing machine has already validated the reference against
        // the world portrait library, so the factory only normalises it (blank or
        // unknown -> "skin"), which keeps every document drawable.
        data.setPortrait(form.getString(DocumentData.PORTRAIT),
                form.getString(DocumentData.PORTRAIT_OWNER));
        return data;
    }

    public static void applyCustomization(DocumentData data, CompoundTag form) {
        if (!form.getBoolean("customStyle")) {
            return;
        }
        String name = form.getString("customName").trim();
        String shortName = form.getString("customShortName").trim();
        String type = form.getString("customType").trim();
        int color = form.contains("customColor") ? form.getInt("customColor") : data.style().accentColor();
        data.applyCustomStyle(name, shortName, type, color);
    }

    /**
     * 26.2: copies the permanent-code list carried by an issuer form onto the new
     * document. The existing {@code DocumentData.visaFreeCodes} storage is reused
     * (no new field): invalid entries are dropped, duplicates collapse and every
     * added code re-signs the document.
     */
    public static void applyVisaFreeCodes(DocumentData data, CompoundTag form) {
        ListTag list = form.getList("visaFreeCodes", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            data.addVisaFreeCode(list.getString(i));
        }
    }

    /**
     * 26.2 entry-state policy: a newly issued document only creates an entry
     * record when the issuer asked for it <em>and</em> the player has no active
     * session. An existing session is never overwritten.
     */
    public static boolean shouldBeginIssueEntry(boolean issueEntryState, boolean hasActiveSession) {
        return issueEntryState && !hasActiveSession;
    }

    /** The preset sample name of a layout (locked-name issuers always use it). */
    public static String sampleNameFor(DocStyle style) {
        return sampleName(style);
    }

    /** The preset sample Latin name of a layout (matches {@link #sampleNameFor}). */
    public static String sampleLatinFor(DocStyle style) {
        return sampleLatin(style);
    }

    public static DocumentData sample(DocStyle style) {
        CompoundTag form = new CompoundTag();
        form.putString("name", sampleName(style));
        form.putString("nameLatin", sampleLatin(style));
        form.putString("sex", "M");
        form.putString("birthDate", sampleBirth(style));
        form.putString("number", style.sampleNumber());
        form.putString("address", defaultAddress(style));
        form.putString("authority", style.defaultAuthority());
        form.putString("issueDate", DateUtil.plusYears(DateUtil.today(), -2));
        form.putString("expiryDate", style.permanent() ? DateUtil.PERMANENT : DateUtil.plusYears(DateUtil.today(), 8));
        form.putString("nationality", style.defaultNationality());
        form.putString("placeOfIssue", style.defaultPlace());
        DocumentData data = fromForm(style, form);
        if (style.kind() == DocumentKind.PASSPORT) {
            CompoundTag visa = createVisa(style == DocStyle.US_PASSPORT ? "United States" : "Schengen",
                    VisaType.TOURIST, 2, DateUtil.plusYears(DateUtil.today(), -1),
                    DateUtil.plusYears(DateUtil.today(), 4), style.defaultAuthority());
            data.addVisa(visa);
        }
        if (style.kind() == DocumentKind.PERMIT) {
            CompoundTag endorsement = createEndorsement(EndorsementType.ONE_YEAR_MULTI, 0,
                    DateUtil.today(), DateUtil.plusYears(DateUtil.today(), 1), style.defaultAuthority());
            data.addEndorsement(endorsement);
        }
        return data;
    }

    public static String generateNumber(DocStyle style, String birthDate, String sex) {
        return generateNumber(style, birthDate, sex, "", "");
    }

    /**
     * 26.2 number generator with explicit mainland overrides.
     *
     * <p>CN_ID is 18 digits: {@code RRRRRR} region (6, defaults to
     * {@link DocStyle#numberPrefix()}), {@code YYYYMMDD} birth date, {@code SS}
     * two-digit sequence (default random), {@code G} gender digit (odd = male,
     * even = female, chosen automatically) and {@code C} the ISO 7064 MOD 11-2
     * check digit. Every other layout keeps its previous generator; the overrides
     * only affect CN_ID.</p>
     */
    public static String generateNumber(DocStyle style, String birthDate, String sex, String region, String sequence2) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (style == DocStyle.CN_ID) {
            String birth = birthDate.replace("-", "");
            if (birth.length() != 8) {
                birth = "19900101";
            }
            String area = mainlandRegion(region, style.numberPrefix());
            String sequence = mainlandSequence(sequence2, random);
            char gender = mainlandGenderDigit(sex, random);
            String base = area + birth + sequence + gender;
            return base + mainlandCheckDigit(base);
        }
        if (style == DocStyle.HK_ID) {
            char letter = (char) ('A' + random.nextInt(26));
            int digits = random.nextInt(100000, 1000000);
            String body = letter + String.format("%06d", digits);
            return body + "(" + hkCheckDigit(body) + ")";
        }
        String prefix = style.numberPrefix();
        if ("US_PASSPORT".equals(style.name())) {
            return String.format("%09d", random.nextInt(100000000, 1000000000));
        }
        if (style == DocStyle.JP_PASSPORT) {
            return prefix + String.format("%07d", random.nextInt(1000000, 10000000));
        }
        return prefix + String.format("%08d", random.nextInt(10000000, 100000000));
    }

    /**
     * Six-digit mainland region code: a usable override wins, otherwise the
     * layout prefix, otherwise zero padding. Non-digits are ignored.
     */
    private static String mainlandRegion(String override, String fallback) {
        String digits = override == null ? "" : override.replaceAll("[^0-9]", "");
        if (digits.length() >= 6) {
            return digits.substring(0, 6);
        }
        String prefix = fallback == null ? "" : fallback.replaceAll("[^0-9]", "");
        if (prefix.length() >= 6) {
            return prefix.substring(0, 6);
        }
        return (prefix + "000000").substring(0, 6);
    }

    /** Two-digit mainland sequence code: a usable override wins, else random 00..99. */
    private static String mainlandSequence(String override, ThreadLocalRandom random) {
        String digits = override == null ? "" : override.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return String.format("%02d", random.nextInt(0, 100));
        }
        if (digits.length() > 2) {
            digits = digits.substring(digits.length() - 2);
        }
        return String.format("%02d", Integer.parseInt(digits));
    }

    /**
     * Mainland gender digit: odd for male, even for female. An unspecified/other
     * sex gets an arbitrary digit (the field is still a valid check-digit input).
     */
    private static char mainlandGenderDigit(String sex, ThreadLocalRandom random) {
        int half = random.nextInt(5);
        if ("F".equalsIgnoreCase(sex)) {
            return (char) ('0' + half * 2);
        }
        if ("M".equalsIgnoreCase(sex)) {
            return (char) ('0' + (half * 2 + 1));
        }
        return (char) ('0' + random.nextInt(10));
    }

    private static String mainlandCheckDigit(String base17) {
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] checks = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (base17.charAt(i) - '0') * weights[i];
        }
        return String.valueOf(checks[sum % 11]);
    }

    private static String hkCheckDigit(String body) {
        int[] weights = {9, 8, 7, 6, 5, 4, 3};
        int sum = 0;
        for (int i = 0; i < 7; i++) {
            char c = body.charAt(i);
            int value = i == 0 ? (c - 'A' + 1) : (c - '0');
            sum += value * weights[i];
        }
        int check = 11 - (sum % 11);
        return check == 10 ? "A" : check == 11 ? "0" : String.valueOf(check);
    }

    public static CompoundTag createVisa(String country, VisaType type, int entries,
                                         String issueDate, String expiryDate, String authority) {
        return createVisa(country, type, entries, issueDate, expiryDate, authority, "");
    }

    /**
     * Creates a visa record with an explicit eight-digit code. A blank or invalid
     * code is replaced by a generated one; {@link DocumentData#addVisa} then keeps
     * it unique among the document's other credentials.
     */
    public static CompoundTag createVisa(String country, VisaType type, int entries,
                                         String issueDate, String expiryDate, String authority,
                                         String code) {
        return createVisa(country, type, entries, issueDate, expiryDate, authority, code, "");
    }

    /**
     * Creates a visa record with an explicit code and an optional display label.
     * The label is trimmed; blank keeps the old country/type fallback on screen.
     */
    public static CompoundTag createVisa(String country, VisaType type, int entries,
                                         String issueDate, String expiryDate, String authority,
                                         String code, String label) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "V-" + randomId());
        tag.putString("country", country);
        tag.putString("type", type.id());
        tag.putInt("entries", Math.max(0, entries));
        tag.putInt("usedEntries", 0);
        tag.putString("issueDate", issueDate);
        tag.putString("expiryDate", expiryDate);
        tag.putString("authority", authority);
        tag.putString("status", VisaRecord.ACTIVE);
        tag.putString("code", CredentialCode.isValid(code) ? code.trim() : CredentialCode.random());
        tag.putString("label", label == null ? "" : label.trim());
        return tag;
    }

    /**
     * Expiry implied by a locked validity period in <b>months</b> (26.2 month
     * precision): zero or a negative count means permanent, otherwise the issue
     * date plus that many calendar months. An unusable issue date is handled by
     * {@link DateUtil#plusMonths(String, int)} (today), and month-end dates clamp
     * to the last day of the target month (2026-01-31 + 3 months = 2026-04-30).
     */
    public static String lockedExpiry(String issueDate, int months) {
        return months <= 0 ? DateUtil.PERMANENT : DateUtil.plusMonths(issueDate, months);
    }

    /**
     * Creates the optional visa attached to a newly issued passport. The visa
     * inherits the passport's issue date and, when the passport expiry is a real
     * date, that expiry; a permanent (or unusable) passport expiry falls back to
     * one year after issue as required by the 1.2.1 contract.
     */
    public static CompoundTag createAttachedVisa(DocumentData passport, String country, VisaType type,
                                                 int entries, String authority, String code) {
        return createAttachedVisa(passport, country, type, entries, authority, code,
                "", "", false, 0);
    }

    /**
     * Attached visa with the 26.1 passport-visa overrides: an explicit issue date
     * (blank or unusable keeps the passport issue date), an optional locked
     * validity period in months and a display label. When validity is locked, the
     * expiry is derived from the visa issue date with {@link #lockedExpiry};
     * otherwise the passport expiry is inherited when it is a real (non-permanent)
     * date and one year after issue is used otherwise.
     */
    public static CompoundTag createAttachedVisa(DocumentData passport, String country, VisaType type,
                                                 int entries, String authority, String code,
                                                 String label, String issueDate,
                                                 boolean lockValidity, int validityMonths) {
        String issue = DateUtil.isValid(issueDate) ? issueDate.trim()
                : DateUtil.isValid(passport.issueDate()) ? passport.issueDate().trim()
                : DateUtil.today();
        String expiry;
        if (lockValidity) {
            expiry = lockedExpiry(issue, validityMonths);
        } else {
            String inherited = passport.expiryDate() == null ? "" : passport.expiryDate().trim();
            expiry = DateUtil.isValid(inherited) && !DateUtil.isPermanent(inherited)
                    ? inherited : DateUtil.plusYears(issue, 1);
        }
        return createVisa(country == null ? "" : country.trim(),
                type == null ? VisaType.TOURIST : type,
                entries, issue, expiry,
                authority == null ? "" : authority.trim(), code, label);
    }

    public static CompoundTag createEndorsement(EndorsementType type, int entries,
                                                String issueDate, String expiryDate, String authority) {
        return createEndorsement(type, entries, issueDate, expiryDate, authority, "");
    }

    /**
     * Creates an endorsement record with an explicit eight-digit code. A blank or
     * invalid code is replaced by a generated one; {@link DocumentData#addEndorsement}
     * then keeps it unique among the document's other credentials.
     */
    public static CompoundTag createEndorsement(EndorsementType type, int entries,
                                                String issueDate, String expiryDate, String authority,
                                                String code) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "E-" + randomId());
        tag.putString("type", type.id());
        tag.putInt("entries", entries < 0 ? type.defaultEntries() : entries);
        tag.putInt("usedEntries", 0);
        tag.putString("issueDate", issueDate);
        tag.putString("expiryDate", expiryDate);
        tag.putString("authority", authority);
        tag.putString("status", VisaRecord.ACTIVE);
        tag.putString("code", CredentialCode.isValid(code) ? code.trim() : CredentialCode.random());
        return tag;
    }

    public static CompoundTag createStamp(String direction, String port, String country, String officer) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "S-" + randomId());
        tag.putString("direction", direction);
        tag.putString("port", port);
        tag.putString("country", country);
        tag.putString("date", DateUtil.today());
        tag.putString("officer", officer);
        return tag;
    }

    public static String endorsementExpiry(EndorsementType type, String issueDate) {
        LocalDate issue = DateUtil.parseOrNull(issueDate);
        if (issue == null) {
            issue = LocalDate.now();
        }
        if (type.defaultMonths() > 0) {
            return issue.plusMonths(type.defaultMonths()).format(DateUtil.ISO);
        }
        return issue.plusYears(1).format(DateUtil.ISO);
    }

    private static String randomId() {
        return String.format("%06d", ThreadLocalRandom.current().nextInt(0, 1000000));
    }

    private static String normalizeSex(String sex) {
        String normalized = sex == null ? "M" : sex.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "F", "FEMALE", "女" -> "F";
            case "X", "OTHER", "其他" -> "X";
            default -> "M";
        };
    }

    private static String validOr(String value, String fallback) {
        return DateUtil.isValid(value) ? value : fallback;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String deriveLatin(String name) {
        int slash = name.indexOf('/');
        if (slash >= 0 && slash < name.length() - 1) {
            return name.substring(slash + 1).trim();
        }
        return name.matches(".*[A-Za-z].*") ? name : "HOLDER";
    }

    private static String sampleName(DocStyle style) {
        return switch (style) {
            case CN_ID, CN_PASSPORT -> "张伟 / ZHANG WEI";
            case HK_ID, HK_PASSPORT -> "陈嘉明 / CHAN KA MING";
            case MO_PASSPORT -> "何家豪 / HO KA HOU";
            case US_PASSPORT -> "JOHN SMITH";
            case JP_PASSPORT -> "山田太郎 / YAMADA TARO";
            case HK_MACAO_PERMIT -> "李静 / LI JING";
        };
    }

    private static String sampleLatin(DocStyle style) {
        return switch (style) {
            case CN_ID, CN_PASSPORT -> "ZHANG WEI";
            case HK_ID, HK_PASSPORT -> "CHAN KA MING";
            case MO_PASSPORT -> "HO KA HOU";
            case US_PASSPORT -> "JOHN SMITH";
            case JP_PASSPORT -> "YAMADA TARO";
            case HK_MACAO_PERMIT -> "LI JING";
        };
    }

    private static String sampleBirth(DocStyle style) {
        return switch (style) {
            case US_PASSPORT -> "1988-07-14";
            case JP_PASSPORT -> "1992-03-21";
            case HK_ID, HK_PASSPORT -> "1995-11-02";
            case MO_PASSPORT -> "1993-05-18";
            case HK_MACAO_PERMIT -> "1997-09-09";
            default -> "1990-01-01";
        };
    }

    public static String defaultAddress(DocStyle style) {
        return switch (style) {
            case CN_ID -> "北京市东城区长安街 1 号";
            case HK_ID -> "香港特别行政区九龙旺角弥敦道 618 号";
            case HK_MACAO_PERMIT -> "北京市朝阳区建国路 88 号";
            default -> "—";
        };
    }
}
