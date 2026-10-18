package com.veriborder.doc;

import java.util.Arrays;
import java.util.List;

/**
 * The eight official document layouts. They are defaults only: every field, date,
 * visa, endorsement and stamp can be changed in-game by the universal modifier.
 */
public enum DocStyle {
    CN_ID(DocumentKind.ID_CARD, "cn_id", 1, "110101", "北京市公安局", "中国", "北京市", 10, 0xC43B2D,
            "CHN", "110101199001011234"),
    HK_ID(DocumentKind.ID_CARD, "hk_id", 2, "HK", "香港特别行政区入境事务处", "中国香港", "香港特别行政区", 0, 0x8A2330,
            "HKG", "A123456(3)"),
    CN_PASSPORT(DocumentKind.PASSPORT, "cn_passport", 1, "E", "中华人民共和国国家移民管理局", "中国", "北京", 10, 0x9E2B25,
            "CHN", "E12345678"),
    HK_PASSPORT(DocumentKind.PASSPORT, "hk_passport", 2, "H", "香港特别行政区入境事务处", "中国香港", "香港", 10, 0x203A6B,
            "HKG", "H12345678"),
    MO_PASSPORT(DocumentKind.PASSPORT, "mo_passport", 3, "M", "澳门特别行政区身份证明局", "中国澳门", "澳门", 10, 0x1F5B45,
            "MAC", "M12345678"),
    US_PASSPORT(DocumentKind.PASSPORT, "us_passport", 4, "", "United States Department of State", "United States", "Washington, D.C.", 10, 0x1B356B,
            "USA", "123456789"),
    JP_PASSPORT(DocumentKind.PASSPORT, "jp_passport", 5, "TR", "日本国外务省", "日本", "东京", 10, 0xA3212B,
            "JPN", "TR1234567"),
    HK_MACAO_PERMIT(DocumentKind.PERMIT, "hk_macao_permit", 1, "C", "中华人民共和国出入境管理局", "中国", "北京", 10, 0x1D4F91,
            "CHN", "C12345678");

    private final DocumentKind kind;
    private final String id;
    private final int modelData;
    private final String numberPrefix;
    private final String defaultAuthority;
    private final String defaultNationality;
    private final String defaultPlace;
    private final int validityYears;
    private final int accentColor;
    private final String mrzCountry;
    private final String sampleNumber;

    DocStyle(DocumentKind kind, String id, int modelData, String numberPrefix, String defaultAuthority,
             String defaultNationality, String defaultPlace, int validityYears, int accentColor,
             String mrzCountry, String sampleNumber) {
        this.kind = kind;
        this.id = id;
        this.modelData = modelData;
        this.numberPrefix = numberPrefix;
        this.defaultAuthority = defaultAuthority;
        this.defaultNationality = defaultNationality;
        this.defaultPlace = defaultPlace;
        this.validityYears = validityYears;
        this.accentColor = accentColor;
        this.mrzCountry = mrzCountry;
        this.sampleNumber = sampleNumber;
    }

    public DocumentKind kind() {
        return kind;
    }

    public String id() {
        return id;
    }

    public int modelData() {
        return modelData;
    }

    public String numberPrefix() {
        return numberPrefix;
    }

    public String defaultAuthority() {
        return defaultAuthority;
    }

    public String defaultNationality() {
        return defaultNationality;
    }

    public String defaultPlace() {
        return defaultPlace;
    }

    public int validityYears() {
        return validityYears;
    }

    public int accentColor() {
        return accentColor;
    }

    public String mrzCountry() {
        return mrzCountry;
    }

    public String sampleNumber() {
        return sampleNumber;
    }

    public boolean permanent() {
        return validityYears <= 0;
    }

    public String displayNameKey() {
        return "doc.veriborder." + id;
    }

    public String shortNameKey() {
        return "doc.veriborder." + id + ".short";
    }

    public static DocStyle byId(String id) {
        if (id != null) {
            for (DocStyle style : values()) {
                if (style.id.equals(id)) {
                    return style;
                }
            }
        }
        return CN_ID;
    }

    public static List<DocStyle> forKind(DocumentKind kind) {
        return Arrays.stream(values()).filter(style -> style.kind == kind).toList();
    }

    public static DocStyle nextForKind(DocumentKind kind, DocStyle current) {
        List<DocStyle> styles = forKind(kind);
        int index = styles.indexOf(current);
        return styles.get((index + 1) % styles.size());
    }
}
