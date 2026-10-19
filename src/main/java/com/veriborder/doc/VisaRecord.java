package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;

import java.time.LocalDate;

public record VisaRecord(
        String id,
        String country,
        VisaType type,
        int entries,
        int usedEntries,
        String issueDate,
        String expiryDate,
        String authority,
        String status,
        String code,
        String label
) {
    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";
    public static final String EXPIRED = "EXPIRED";

    /**
     * A record always exposes a usable eight-digit code: an explicit valid code
     * is kept, otherwise the deterministic legacy code for its id is used (or a
     * random code when even the id is missing). The optional display label is
     * trimmed; blank means the UI falls back to country/type.
     */
    public VisaRecord {
        code = normalizeCode(id, code);
        label = label == null ? "" : label.trim();
    }

    /** Pre-26.1 record view: no display label. */
    public VisaRecord(String id, String country, VisaType type, int entries, int usedEntries,
                      String issueDate, String expiryDate, String authority, String status,
                      String code) {
        this(id, country, type, entries, usedEntries, issueDate, expiryDate, authority, status,
                code, "");
    }

    public static VisaRecord fromTag(CompoundTag tag) {
        return new VisaRecord(
                tag.getString("id"),
                tag.getString("country"),
                VisaType.byId(tag.getString("type")),
                tag.getInt("entries"),
                tag.getInt("usedEntries"),
                tag.getString("issueDate"),
                tag.getString("expiryDate"),
                tag.getString("authority"),
                tag.contains("status") ? tag.getString("status") : ACTIVE,
                tag.getString("code"),
                tag.getString("label")
        );
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("country", country);
        tag.putString("type", type.id());
        tag.putInt("entries", entries);
        tag.putInt("usedEntries", usedEntries);
        tag.putString("issueDate", issueDate);
        tag.putString("expiryDate", expiryDate);
        tag.putString("authority", authority);
        tag.putString("status", status);
        tag.putString("code", code);
        tag.putString("label", label);
        return tag;
    }

    /** Whether this record carries an explicit display label. */
    public boolean hasLabel() {
        return !label.isBlank();
    }

    public int remainingEntries() {
        return entries <= 0 ? Integer.MAX_VALUE : Math.max(0, entries - usedEntries);
    }

    public boolean active() {
        return active(LocalDate.now());
    }

    public boolean active(LocalDate on) {
        LocalDate issued = DateUtil.parseOrNull(issueDate);
        return ACTIVE.equalsIgnoreCase(status)
                && issued != null
                && !issued.isAfter(on)
                && !DateUtil.expiredOn(expiryDate, on)
                && remainingEntries() > 0;
    }

    public String entriesText() {
        if (entries <= 0) {
            return "∞";
        }
        return Math.max(0, entries - usedEntries) + "/" + entries;
    }

    private static String normalizeCode(String id, String rawCode) {
        String valid = CredentialCode.sanitize(rawCode);
        if (!valid.isBlank()) {
            return valid;
        }
        if (id == null || id.isBlank()) {
            return CredentialCode.random();
        }
        return CredentialCode.legacy(id);
    }
}
