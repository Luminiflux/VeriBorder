package com.veriborder.doc;

import java.util.Locale;

public final class MrzUtil {
    private MrzUtil() {
    }

    public static String[] generate(DocStyle style, String number, String latinName, String birthDate,
                                    String sex, String expiryDate) {
        String code = style.mrzCountry();
        String cleanedName = sanitizeName(latinName);
        String surname;
        String given;
        int slash = cleanedName.indexOf('/');
        if (slash >= 0) {
            surname = cleanedName.substring(0, slash);
            given = cleanedName.substring(slash + 1);
        } else {
            int space = cleanedName.indexOf(' ');
            if (space > 0) {
                surname = cleanedName.substring(0, space);
                given = cleanedName.substring(space + 1);
            } else {
                surname = cleanedName;
                given = "";
            }
        }
        String first = ("P<" + code + surname + "<<" + given).replace(' ', '<');
        first = pad(first, 44);

        String numberPart = pad(number.replaceAll("[^A-Z0-9]", "").toUpperCase(Locale.ROOT), 9);
        String numberCheck = String.valueOf(checkDigit(numberPart));
        String birth = DateUtil.mrzDate(birthDate);
        String birthCheck = String.valueOf(checkDigit(birth));
        String sexCode = sex.equalsIgnoreCase("F") ? "F" : sex.equalsIgnoreCase("X") ? "X" : "M";
        String expiry = DateUtil.mrzDate(expiryDate);
        String expiryCheck = String.valueOf(checkDigit(expiry));
        String personal = "<<" + pad("", 12);
        String personalCheck = String.valueOf(checkDigit(personal));
        String second = numberPart + numberCheck + code + birth + birthCheck + sexCode + expiry + expiryCheck
                + personal + personalCheck;
        second = pad(second, 44);
        return new String[]{first, second};
    }

    private static String sanitizeName(String name) {
        if (name == null) {
            return "HOLDER";
        }
        String cleaned = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z /]", "").trim();
        return cleaned.isBlank() ? "HOLDER" : cleaned;
    }

    private static String pad(String value, int length) {
        if (value.length() >= length) {
            return value.substring(0, length);
        }
        return value + "<".repeat(length - value.length());
    }

    private static int checkDigit(String value) {
        int[] weights = {7, 3, 1};
        int sum = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int v;
            if (c >= '0' && c <= '9') {
                v = c - '0';
            } else if (c >= 'A' && c <= 'Z') {
                v = c - 'A' + 10;
            } else {
                v = 0;
            }
            sum += v * weights[i % 3];
        }
        return sum % 10;
    }
}
