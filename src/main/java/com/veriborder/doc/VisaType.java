package com.veriborder.doc;

public enum VisaType {
    TOURIST("tourist"),
    BUSINESS("business"),
    STUDENT("student"),
    WORK("work"),
    FAMILY("family"),
    OTHER("other");

    private final String id;

    VisaType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return "visa.type.veriborder." + id;
    }

    public static VisaType byId(String id) {
        if (id != null) {
            for (VisaType type : values()) {
                if (type.id.equalsIgnoreCase(id) || type.name().equalsIgnoreCase(id)) {
                    return type;
                }
            }
        }
        return TOURIST;
    }

    public VisaType next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** Default entries used by a self-service visa kiosk (no player entry field). */
    public int defaultEntries() {
        return switch (this) {
            case BUSINESS -> 2;
            default -> 1;
        };
    }
}
