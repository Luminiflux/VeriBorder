package com.veriborder.border;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.UUID;

/**
 * One traveller's active entry at the border.
 *
 * <p>A session is created by an entry gate and removed by the matching exit
 * gate (or by an officer). It records which document was used and, for
 * {@link #KIND_VISA} / {@link #KIND_ENDORSEMENT}, exactly which credential
 * record was presented, so the exit gate consumes that record and nothing
 * else. The entry visit sequence is deliberately <em>not</em> consumed at
 * entry: one permission grants one entry and is used up when the traveller
 * leaves.</p>
 *
 * <p>All timestamps are real epoch milliseconds; an overstay is
 * {@code now - entryEpochMillis > overstayDays * 24h}. The overstay limit is
 * captured from the entry gate so changing a gate's configuration later never
 * rewrites an existing traveller's allowance.</p>
 */
public record BorderSession(
        UUID player,
        String playerName,
        String documentNumber,
        String recordId,
        String code,
        String kind,
        String dimension,
        BlockPos gatePos,
        long entryEpochMillis,
        String port,
        int overstayDays,
        boolean overstay
) {
    /** Entry accepted with no credential record (gate mode {@code all}). */
    public static final String KIND_ALL = "ALL";
    /** Active passport visa record presented at the gate. */
    public static final String KIND_VISA = "VISA";
    /** Passport visa-free code presented at the gate. Never consumes anything. */
    public static final String KIND_VISA_FREE = "VISA_FREE";
    /** Active permit endorsement presented at the gate. */
    public static final String KIND_ENDORSEMENT = "ENDORSEMENT";

    public static final int DEFAULT_OVERSTAY_DAYS = 30;
    public static final int MAX_OVERSTAY_DAYS = 3650;
    public static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;

    public BorderSession {
        playerName = safe(playerName);
        documentNumber = safe(documentNumber);
        recordId = safe(recordId);
        code = safe(code);
        String normalizedKind = safe(kind).toUpperCase(Locale.ROOT);
        kind = normalizedKind.isEmpty() ? KIND_ALL : normalizedKind;
        dimension = safe(dimension);
        gatePos = gatePos == null ? BlockPos.ZERO : gatePos.immutable();
        port = safe(port);
        overstayDays = Math.max(0, Math.min(MAX_OVERSTAY_DAYS, overstayDays));
        if (entryEpochMillis <= 0L) {
            entryEpochMillis = System.currentTimeMillis();
        }
    }

    /** True when the exit gate must consume exactly the stored record. */
    public boolean consumesRecord() {
        return KIND_VISA.equals(kind) || KIND_ENDORSEMENT.equals(kind);
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

    public BorderSession withOverstay(boolean value) {
        return new BorderSession(player, playerName, documentNumber, recordId, code, kind, dimension,
                gatePos, entryEpochMillis, port, overstayDays, value);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("player", player == null ? new UUID(0L, 0L) : player);
        tag.putString("playerName", playerName);
        tag.putString("document", documentNumber);
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
        tag.putBoolean("overstay", overstay);
        return tag;
    }

    /**
     * Compound handed to the officer screen. It contains exactly the fields the
     * officer UI needs (plus the port for a readable location line); the
     * internal record id is not exposed.
     */
    public CompoundTag toOfficerTag() {
        CompoundTag tag = toTag();
        tag.remove("recordId");
        return tag;
    }

    public static BorderSession fromTag(CompoundTag tag) {
        if (tag == null) {
            return null;
        }
        UUID uuid = tag.hasUUID("player") ? tag.getUUID("player") : null;
        if (uuid == null) {
            return null;
        }
        BlockPos pos = new BlockPos(tag.getInt("gateX"), tag.getInt("gateY"), tag.getInt("gateZ"));
        return new BorderSession(uuid,
                tag.getString("playerName"),
                tag.getString("document"),
                tag.getString("recordId"),
                tag.getString("code"),
                tag.getString("kind"),
                tag.getString("dimension"),
                pos,
                tag.getLong("entryTime"),
                tag.getString("port"),
                tag.contains("overstayDays") ? tag.getInt("overstayDays") : DEFAULT_OVERSTAY_DAYS,
                tag.getBoolean("overstay"));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
