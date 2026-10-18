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

    public String translationKey() {
        return "kind.veriborder." + name().toLowerCase();
    }
}
