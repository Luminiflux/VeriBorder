package com.veriborder.doc;

public enum DocumentStatus {
    VALID("valid", 0x34D399, false),
    LOST("lost", 0xF59E0B, true),
    CANCELLED("cancelled", 0xEF4444, true),
    SUSPENDED("suspended", 0xA855F7, true);

    private final String id;
    private final int color;
    private final boolean blacklisted;

    DocumentStatus(String id, int color, boolean blacklisted) {
        this.id = id;
        this.color = color;
        this.blacklisted = blacklisted;
    }

    public String id() {
        return id;
    }

    public int color() {
        return color;
    }

    public boolean blacklisted() {
        return blacklisted;
    }

    public String translationKey() {
        return "status.veriborder." + id;
    }

    public static DocumentStatus byId(String id) {
        if (id != null) {
            for (DocumentStatus status : values()) {
                if (status.id.equalsIgnoreCase(id)) {
                    return status;
                }
            }
        }
        return VALID;
    }

    public DocumentStatus next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
