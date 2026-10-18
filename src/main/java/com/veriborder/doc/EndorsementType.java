package com.veriborder.doc;

import org.jetbrains.annotations.Nullable;

public enum EndorsementType {
    ONE_YEAR_MULTI("one_year_multi", 0, 12),
    THREE_MONTH_SINGLE("three_month_single", 1, 3),
    OTHER("other", 1, 0);

    private final String id;
    private final int entries;
    private final int months;

    EndorsementType(String id, int entries, int months) {
        this.id = id;
        this.entries = entries;
        this.months = months;
    }

    /**
     * Legacy type ids that existed before the round-2 consolidation. Documents
     * carrying them are still readable; they simply resolve to {@link #OTHER}.
     */
    private static final String LEGACY_TEAM = "team";
    private static final String LEGACY_INDIVIDUAL = "individual";

    public String id() {
        return id;
    }

    public int defaultEntries() {
        return entries;
    }

    public int defaultMonths() {
        return months;
    }

    public String translationKey() {
        return "endorsement.type.veriborder." + id;
    }

    /**
     * Resolves an id or enum name. Legacy {@code team}/{@code individual}
     * values from old NBT map to {@link #OTHER}; unknown values also fall back
     * to {@link #OTHER} so old documents stay readable.
     */
    public static EndorsementType byId(String id) {
        EndorsementType resolved = byIdOrNull(id);
        return resolved == null ? OTHER : resolved;
    }

    /**
     * Strict resolution used for gate requirements: blanks and unknown ids
     * return {@code null} instead of silently becoming {@link #OTHER}.
     * Legacy ids still map to {@link #OTHER}.
     */
    @Nullable
    public static EndorsementType byIdOrNull(String id) {
        if (id == null) {
            return null;
        }
        String key = id.trim();
        if (key.isEmpty()) {
            return null;
        }
        if (LEGACY_TEAM.equalsIgnoreCase(key) || LEGACY_INDIVIDUAL.equalsIgnoreCase(key)) {
            return OTHER;
        }
        for (EndorsementType type : values()) {
            if (type.id.equalsIgnoreCase(key) || type.name().equalsIgnoreCase(key)) {
                return type;
            }
        }
        return null;
    }

    public EndorsementType next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
