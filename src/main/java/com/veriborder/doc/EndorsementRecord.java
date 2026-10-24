package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;

import java.time.LocalDate;

public record EndorsementRecord(
        String id,
        EndorsementType type,
        int entries,
        int usedEntries,
        String issueDate,
        String expiryDate,
        String authority,
        String status,
        String code
) {
    /**
     * A record always exposes a usable eight-digit code: an explicit valid code
     * is kept, otherwise the deterministic legacy code for its id is used (or a
     * random code when even the id is missing).
     */
    public EndorsementRecord {
        code = normalizeCode(id, code);
    }

    public static EndorsementRecord fromTag(CompoundTag tag) {
        return new EndorsementRecord(
                tag.getString("id"),
                EndorsementType.byId(tag.getString("type")),
                tag.getInt("entries"),
                tag.getInt("usedEntries"),
                tag.getString("issueDate"),
                tag.getString("expiryDate"),
                tag.getString("authority"),
                tag.contains("status") ? tag.getString("status") : VisaRecord.ACTIVE,
                tag.getString("code")
        );
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("type", type.id());
        tag.putInt("entries", entries);
        tag.putInt("usedEntries", usedEntries);
        tag.putString("issueDate", issueDate);
        tag.putString("expiryDate", expiryDate);
        tag.putString("authority", authority);
        tag.putString("status", status);
        tag.putString("code", code);
        return tag;
    }

    public int remainingEntries() {
        return CredentialState.remaining(entries, usedEntries);
    }

    /** Derived state against today (exhausted / expired / revoked / valid). */
    public CredentialState state() {
        return state(LocalDate.now());
    }

    /** Date-injectable derived state (tests and date-sensitive screens). */
    public CredentialState state(LocalDate on) {
        return CredentialState.derive(status, entries, usedEntries, expiryDate, on);
    }

    /** UI colour of the derived state. */
    public int stateColor() {
        return state().color();
    }

    /** A permit endorsement opens a gate only while its derived state is VALID. */
    public boolean active() {
        return active(LocalDate.now());
    }

    /** Date-injectable active check; {@code VALID} already implies a spare entry. */
    public boolean active(LocalDate on) {
        LocalDate issued = DateUtil.parseOrNull(issueDate);
        return state(on) == CredentialState.VALID
                && issued != null
                && !issued.isAfter(on);
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
