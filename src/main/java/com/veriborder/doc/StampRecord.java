package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;

/**
 * One immigration stamp written into a passport or HK/Macau permit.
 *
 * <p>26.3 adds the exact moment of the stamp: {@link #time} is the local
 * {@code HH:mm:ss} of the server clock and {@link #epochMillis} the matching
 * epoch value (used for ordering, sorting and tooltips). Stamps written by
 * 26.2 and earlier carry no {@code time}; {@link #timeOrDash()} renders the
 * {@code --:--} placeholder for them instead of showing an empty field.</p>
 */
public record StampRecord(
        String id,
        String direction,
        String port,
        String country,
        String date,
        String time,
        long epochMillis,
        String officer
) {
    public static final String IN = "IN";
    public static final String OUT = "OUT";

    /** Rendered in place of the time of a stamp written before 26.3. */
    public static final String UNKNOWN_TIME = "--:--";

    public StampRecord {
        id = safe(id);
        direction = safe(direction);
        port = safe(port);
        country = safe(country);
        date = safe(date);
        time = safe(time);
        officer = safe(officer);
        if (epochMillis < 0L) {
            epochMillis = 0L;
        }
    }

    /**
     * Pre-26.3 stamp (no clock time). Kept so legacy NBT and old call sites keep
     * working; {@link #timeOrDash()} reports the unknown-time placeholder.
     */
    public StampRecord(String id, String direction, String port, String country, String date, String officer) {
        this(id, direction, port, country, date, "", 0L, officer);
    }

    public static StampRecord fromTag(CompoundTag tag) {
        return new StampRecord(
                tag.getString("id"),
                tag.getString("direction"),
                tag.getString("port"),
                tag.getString("country"),
                tag.getString("date"),
                // 26.2 stamps have no "time"; an empty string, never null.
                tag.getString("time"),
                tag.getLong("epochMillis"),
                tag.getString("officer")
        );
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("direction", direction);
        tag.putString("port", port);
        tag.putString("country", country);
        tag.putString("date", date);
        tag.putString("time", time);
        tag.putLong("epochMillis", epochMillis);
        tag.putString("officer", officer);
        return tag;
    }

    public boolean inbound() {
        return IN.equalsIgnoreCase(direction);
    }

    public String directionKey() {
        return inbound() ? "stamp.veriborder.in" : "stamp.veriborder.out";
    }

    /** Whether this stamp carries a real clock time (26.3 and later). */
    public boolean hasTime() {
        return !time.isBlank();
    }

    /** The stored time, or {@link #UNKNOWN_TIME} for a stamp written before 26.3. */
    public String timeOrDash() {
        return hasTime() ? time : UNKNOWN_TIME;
    }

    /** {@code date time} for one-line displays; the dash placeholder keeps the column aligned. */
    public String dateTimeText() {
        return date.isBlank() ? timeOrDash() : date + " " + timeOrDash();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
