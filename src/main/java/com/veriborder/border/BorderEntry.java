package com.veriborder.border;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * One traveller's active entry at the border, keyed by {@link #personKey} instead
 * of by one booklet (26.3, "one person, many documents").
 *
 * <p>An entry is created by an entry gate and removed by the matching exit gate
 * (or by an officer). It records which document was presented and, for
 * {@link #KIND_VISA} / {@link #KIND_ENDORSEMENT}, exactly which credential
 * record matched, so the exit gate consumes that record and nothing else. The
 * credential is deliberately <em>not</em> consumed at entry: one permission
 * grants one entry and is used up when the traveller leaves.</p>
 *
 * <p><b>Identity.</b> {@link #personKey} is the holder signature of the entry
 * document ({@code normalize(name)|birthDate}), so a renewed passport, a second
 * passport or the holder's permit all resolve to the same traveller. The entry
 * still keeps the document number it was recorded on: the exit gate needs it to
 * find the bound credential record, which only exists on that booklet.</p>
 *
 * <p>{@link #player} / {@link #playerName} identify the account that presented
 * the document. They are not part of the identity rule — the entry follows the
 * person, not the account — but the officer console needs them to list and clear
 * an entry, and every border log line names the account.</p>
 *
 * <p>All timestamps are real epoch milliseconds; an overstay is
 * {@code now - entryEpochMillis > overstayDays * 24h}. The allowance is captured
 * from the entry gate, so changing a gate's configuration later never rewrites
 * an existing traveller's limit.</p>
 */
public record BorderEntry(
        String personKey,
        String documentNumber,
        String holder,
        String recordId,
        String code,
        String kind,
        String dimension,
        BlockPos gatePos,
        long entryEpochMillis,
        String port,
        int overstayDays,
        boolean overstayed,
        UUID player,
        String playerName
) {
    /** Active passport visa record presented at the gate. */
    public static final String KIND_VISA = "VISA";
    /** Permanent "visa-free / return home" code presented at the gate. Never consumes a record. */
    public static final String KIND_VISA_FREE = "VISA_FREE";
    /** Active permit endorsement presented at the gate. */
    public static final String KIND_ENDORSEMENT = "ENDORSEMENT";
    /**
     * Entry with no credential bound: an officer-forced entry or the issuer's
     * "new document starts in the entry state" policy. Nothing is consumed on exit.
     */
    public static final String KIND_NONE = "NONE";

    public static final int DEFAULT_OVERSTAY_DAYS = 30;
    public static final int MAX_OVERSTAY_DAYS = 3650;
    public static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;

    public BorderEntry {
        personKey = safe(personKey);
        documentNumber = safe(documentNumber);
        holder = safe(holder);
        recordId = safe(recordId);
        code = safe(code);
        String normalizedKind = safe(kind).toUpperCase(Locale.ROOT);
        kind = normalizedKind.isEmpty() ? KIND_NONE : normalizedKind;
        dimension = safe(dimension);
        gatePos = gatePos == null ? BlockPos.ZERO : gatePos.immutable();
        port = safe(port);
        playerName = safe(playerName);
        overstayDays = Math.max(0, Math.min(MAX_OVERSTAY_DAYS, overstayDays));
        if (entryEpochMillis <= 0L) {
            entryEpochMillis = System.currentTimeMillis();
        }
    }

    /**
     * Entry without the presenting account (data-level tests and migrated data
     * whose player id was lost).
     */
    public BorderEntry(String personKey, String documentNumber, String holder, String recordId, String code,
                       String kind, String dimension, BlockPos gatePos, long entryEpochMillis, String port,
                       int overstayDays, boolean overstayed) {
        this(personKey, documentNumber, holder, recordId, code, kind, dimension, gatePos, entryEpochMillis,
                port, overstayDays, overstayed, null, "");
    }

    /** True when the exit gate must consume exactly the stored record. */
    public boolean consumesRecord() {
        return KIND_VISA.equals(kind) || KIND_ENDORSEMENT.equals(kind);
    }

    /** True when the entry is bound to a credential record (visa/endorsement/permanent code). */
    public boolean hasCredential() {
        return consumesRecord() || KIND_VISA_FREE.equals(kind);
    }

    /** Epoch millis after which this stay counts as an overstay. */
    public long overstayDeadlineMillis() {
        return entryEpochMillis + overstayDays * MILLIS_PER_DAY;
    }

    /** Strict comparison: exactly {@code overstayDays} is still on time. */
    public boolean overstayed(long nowEpochMillis) {
        return nowEpochMillis - entryEpochMillis > overstayDays * MILLIS_PER_DAY;
    }

    public boolean overstayed() {
        return overstayed(System.currentTimeMillis());
    }

    /** Whole days beyond the allowance (0 when on time). */
    public long daysOverstayed(long nowEpochMillis) {
        long excess = nowEpochMillis - overstayDeadlineMillis();
        if (excess <= 0L) {
            return 0L;
        }
        return (excess + MILLIS_PER_DAY - 1L) / MILLIS_PER_DAY;
    }

    public BorderEntry withOverstay(boolean value) {
        return new BorderEntry(personKey, documentNumber, holder, recordId, code, kind, dimension, gatePos,
                entryEpochMillis, port, overstayDays, value, player, playerName);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("personKey", personKey);
        tag.putString("document", documentNumber);
        tag.putString("holder", holder);
        tag.putString("recordId", recordId);
        tag.putString("code", code);
        tag.putString("kind", kind);
        tag.putString("dimension", dimension);
        tag.putInt("gateX", gatePos.getX());
        tag.putInt("gateY", gatePos.getY());
        tag.putInt("gateZ", gatePos.getZ());
        tag.putLong("entryTime", entryEpochMillis);
        tag.putString("port", port);
        tag.putInt("overstayDays", overstayDays);
        tag.putBoolean("overstay", overstayed);
        if (player != null) {
            tag.putUUID("player", player);
        }
        tag.putString("playerName", playerName);
        return tag;
    }

    /**
     * Compound handed to the officer screen. It contains exactly the fields the
     * officer UI needs (plus the port for a readable location line); the internal
     * record id is not exposed.
     */
    public CompoundTag toOfficerTag() {
        CompoundTag tag = toTag();
        tag.remove("recordId");
        return tag;
    }

    @Nullable
    public static BorderEntry fromTag(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        String document = tag.getString("document");
        String holder = tag.getString("holder");
        String personKey = tag.getString("personKey").trim();
        if (personKey.isEmpty()) {
            // 26.2/26.3 data written before the person key existed: fall back to
            // holder + document number so the entry keeps a stable identity.
            personKey = fallbackPersonKey(holder, document);
        }
        BlockPos pos = new BlockPos(tag.getInt("gateX"), tag.getInt("gateY"), tag.getInt("gateZ"));
        UUID player = tag.hasUUID("player") ? tag.getUUID("player") : null;
        return new BorderEntry(personKey, document, holder, tag.getString("recordId"), tag.getString("code"),
                tag.getString("kind"), tag.getString("dimension"), pos, tag.getLong("entryTime"),
                tag.getString("port"),
                tag.contains("overstayDays") ? tag.getInt("overstayDays") : DEFAULT_OVERSTAY_DAYS,
                tag.getBoolean("overstay"), player, tag.getString("playerName"));
    }

    /**
     * Entry identity when no {@code personKey} is stored: the normalised holder
     * name plus the document number, or {@code doc:<number>} for a nameless
     * document. Never {@code null}; blank input yields a blank key.
     */
    public static String fallbackPersonKey(@Nullable String holder, @Nullable String documentNumber) {
        String number = safe(documentNumber);
        String name = safe(holder);
        if (!name.isEmpty()) {
            return com.veriborder.doc.DocumentData.computePersonKey(name, number);
        }
        return number.isEmpty() ? "" : "doc:" + number;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
