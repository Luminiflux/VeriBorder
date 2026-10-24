package com.veriborder.doc;

public enum DocumentKind {
    ID_CARD("id_card"),
    PASSPORT("passport"),
    PERMIT("permit");

    private final String itemId;

    DocumentKind(String itemId) {
        this.itemId = itemId;
    }

    public String itemId() {
        return itemId;
    }

    /**
     * Stable wire id of this credential kind. The registry editor sends this
     * value (26.4) instead of the enum name so the server and client agree on a
     * single spelling.
     */
    public String id() {
        return itemId;
    }

    public String translationKey() {
        return "kind.veriborder." + name().toLowerCase();
    }

    /**
     * The single issuing machine that produces this credential kind (26.3 round
     * 12). Registry templates are matched to an issuer automatically by kind
     * instead of by a configurable "allowed issuers" list:
     * {@code ID_CARD -> id_issuer}, {@code PASSPORT -> passport_issuer},
     * {@code PERMIT -> permit_issuer}.
     */
    public String issuerId() {
        return switch (this) {
            case ID_CARD -> "id_issuer";
            case PASSPORT -> "passport_issuer";
            case PERMIT -> "permit_issuer";
        };
    }

    /** Resolves a persisted id/name; unknown values fall back to {@link #PASSPORT}. */
    public static DocumentKind byId(String id) {
        DocumentKind kind = byIdOrNull(id);
        return kind == null ? PASSPORT : kind;
    }

    /**
     * Strict resolution (26.4): a blank or unknown kind returns {@code null}.
     * The registry save path uses this so an invalid credential type is reported
     * instead of silently becoming a passport.
     */
    public static DocumentKind byIdOrNull(String id) {
        if (id != null) {
            String key = id.trim();
            for (DocumentKind kind : values()) {
                if (kind.itemId.equalsIgnoreCase(key) || kind.name().equalsIgnoreCase(key)) {
                    return kind;
                }
            }
        }
        return null;
    }
}
