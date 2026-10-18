package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;

import java.time.LocalDate;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public final class DocumentFactory {
    private DocumentFactory() {
    }

    public static DocumentData fromForm(DocStyle style, CompoundTag form) {
        DocumentData data = DocumentData.createEmpty(style);
        String name = form.getString("name");
        String nameLatin = form.getString("nameLatin");
        String sex = normalizeSex(form.getString("sex"));
        String birthDate = validOr(form.getString("birthDate"), "1990-01-01");
        String issueDate = validOr(form.getString("issueDate"), DateUtil.today());
        String authority = blankTo(form.getString("authority"), style.defaultAuthority());
        String nationality = blankTo(form.getString("nationality"), style.defaultNationality());
        String place = blankTo(form.getString("placeOfIssue"), style.defaultPlace());
        String address = blankTo(form.getString("address"), defaultAddress(style));
        String number = form.getString("number").trim();
        if (number.isBlank()) {
            number = generateNumber(style, birthDate, sex);
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
        applyCustomization(data, form);
        // Independent display name: applied after the profile/custom cover so it
        // always survives document creation and never gets overwritten by them.
        String displayName = form.getString("displayName").trim();
        if (!displayName.isBlank()) {
            data.setDisplayName(displayName);
        }
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
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (style == DocStyle.CN_ID) {
            String birth = birthDate.replace("-", "");
            if (birth.length() != 8) {
                birth = "19900101";
            }
            int sequence = Math.min(998, random.nextInt(100, 1000));
            if ("F".equalsIgnoreCase(sex) && sequence % 2 != 0) {
                sequence++;
            } else if (!"F".equalsIgnoreCase(sex) && sequence % 2 == 0) {
                sequence++;
            }
            String base = style.numberPrefix() + birth + String.format("%03d", sequence);
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
        return tag;
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
