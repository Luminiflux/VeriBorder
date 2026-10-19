package com.veriborder.border;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.UUID;

/**
 * One border event: an entry/exit attempt, an overstay warning or an officer
 * action. Logs are append-only, capped by {@link BorderTravelData} and shown to
 * officers together with the active sessions.
 *
 * <p>{@code detail} holds a translation key ({@code verify.veriborder.reason.*})
 * for denials and warnings and free text (the officer's name) for officer
 * actions. {@code gate} is a readable "<code>dimension@x,y,z</code>" string.</p>
 */
public record BorderLog(
        long epochMillis,
        UUID player,
        String playerName,
        String direction,
        String result,
        String documentNumber,
        String code,
        String kind,
        String gate,
        String detail
) {
    public static final String IN = "IN";
    public static final String OUT = "OUT";
    public static final String OFFICER = "OFFICER";

    public static final String ALLOW_IN = "ALLOW_IN";
    public static final String DENY_IN = "DENY_IN";
    public static final String ALLOW_OUT = "ALLOW_OUT";
    public static final String DENY_OUT = "DENY_OUT";
    public static final String OVERSTAY = "OVERSTAY";
    public static final String OFFICER_CLEAR = "OFFICER_CLEAR";

    public BorderLog {
        playerName = safe(playerName);
        direction = safe(direction).toUpperCase(Locale.ROOT);
        result = safe(result).toUpperCase(Locale.ROOT);
        documentNumber = safe(documentNumber);
        code = safe(code);
        kind = safe(kind).toUpperCase(Locale.ROOT);
        gate = safe(gate);
        detail = safe(detail);
        if (epochMillis <= 0L) {
            epochMillis = System.currentTimeMillis();
        }
    }

    public boolean inbound() {
        return IN.equals(direction);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("time", epochMillis);
        tag.putUUID("player", player == null ? new UUID(0L, 0L) : player);
        tag.putString("playerName", playerName);
        tag.putString("direction", direction);
        tag.putString("result", result);
        tag.putString("document", documentNumber);
        tag.putString("code", code);
        tag.putString("kind", kind);
        tag.putString("gate", gate);
        tag.putString("detail", detail);
        return tag;
    }

    /** Officer payload entries share the persisted layout. */
    public CompoundTag toOfficerTag() {
        return toTag();
    }

    public static BorderLog fromTag(CompoundTag tag) {
        if (tag == null) {
            return null;
        }
        UUID uuid = tag.hasUUID("player") ? tag.getUUID("player") : null;
        return new BorderLog(tag.getLong("time"), uuid,
                tag.getString("playerName"),
                tag.getString("direction"),
                tag.getString("result"),
                tag.getString("document"),
                tag.getString("code"),
                tag.getString("kind"),
                tag.getString("gate"),
                tag.getString("detail"));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
