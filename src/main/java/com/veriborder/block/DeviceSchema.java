package com.veriborder.block;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/**
 * Schema upgrades for a device block entity (device schema 2, 26.3 round 11).
 *
 * <p>The block entity has always been tolerant while reading — absent keys fall
 * back to the historical defaults — but "tolerant" is not "migrated": a device
 * saved by an older build keeps missing keys forever, and the next build that
 * changes a default silently changes the device's behaviour. This migration
 * writes the defaults the reader implies, normalises the legacy gate-mode names
 * once, and stamps the schema version, so the stored compound describes the
 * device completely.</p>
 *
 * <p>It is applied in {@link DeviceBlockEntity#load(CompoundTag)} (the persisted
 * path). The block-entity sync payload ({@code handleUpdateTag}) is deliberately
 * not migrated: it is transient, already normalised by the sender and would only
 * add work on every block update.</p>
 */
public final class DeviceSchema {
    /** Current device block-entity schema. */
    public static final int CURRENT = 3;
    public static final String SCHEMA_KEY = "schema";

    private DeviceSchema() {
    }

    /** The schema a tag was written with; 0 when the field is absent. */
    public static int read(@Nullable CompoundTag tag) {
        if (tag == null) {
            return 0;
        }
        return tag.contains(SCHEMA_KEY, Tag.TAG_INT) ? tag.getInt(SCHEMA_KEY) : 0;
    }

    /**
     * Upgrades a persisted device compound in place.
     *
     * @return {@code true} when the tag changed (a second call returns {@code false})
     */
    public static boolean migrate(@Nullable CompoundTag tag) {
        if (tag == null) {
            return false;
        }
        if (read(tag) >= CURRENT) {
            return false;
        }

        // 26.3 removed "all" mode: every legacy alias ("all", "any", "passport",
        // "permit", unknown) is mapped to its surviving equivalent exactly once.
        tag.putString("gateMode", DeviceBlockEntity.normalizeGateMode(tag.getString("gateMode")));

        // Pre-1.1.5 gate codes were a single string; the list form is the
        // canonical one. A code list of an unexpected type is left alone (it is
        // data this build does not understand, not something to delete).
        Tag codes = tag.get("gateCodes");
        if (codes instanceof StringTag) {
            ListTag list = new ListTag();
            if (!((StringTag) codes).getAsString().isBlank()) {
                list.add(StringTag.valueOf(((StringTag) codes).getAsString().trim()));
            }
            tag.put("gateCodes", list);
        } else if (codes == null) {
            tag.put("gateCodes", new ListTag());
        }
        if (!(tag.get("issueCodes") instanceof ListTag)) {
            if (tag.get("issueCodes") == null) {
                tag.put("issueCodes", new ListTag());
            }
        }

        ensureCompound(tag, "dualGate");
        ensureList(tag, "presets");
        // Device schema 3: a gate may carry its own exit-code list (empty = the
        // gate-wide codes) and an allowed issuing-country whitelist; a device may
        // carry the registry issuing-country code used for new documents. Writing
        // the canonical empty values keeps an upgraded device explicit without
        // changing any behaviour (all default to "unset").
        ensureList(tag, "exitCodes");
        ensureList(tag, "entryCountryCodes");
        ensureList(tag, "exitCountryCodes");
        // Earlier build of this schema used one combined whitelist; seed the
        // entry stage from it so a gate configured then keeps its pass rules.
        if (tag.contains("countryCodes", Tag.TAG_LIST)) {
            if (!tag.contains("entryCountryCodes", Tag.TAG_LIST)
                    || tag.getList("entryCountryCodes", Tag.TAG_STRING).isEmpty()) {
                tag.put("entryCountryCodes", tag.getList("countryCodes", Tag.TAG_STRING).copy());
            }
            tag.remove("countryCodes");
        }
        if (!tag.contains("issuerCountry")) {
            tag.putString("issuerCountry", "");
        }

        // Behavioural defaults of the current reader, written down explicitly.
        if (!tag.contains("autoStamp", Tag.TAG_BYTE)) {
            tag.putBoolean("autoStamp", true);
        }
        if (!tag.contains("lockNumber", Tag.TAG_BYTE)) {
            tag.putBoolean("lockNumber", true);
        }
        if (!tag.contains("lockNationality", Tag.TAG_BYTE)) {
            tag.putBoolean("lockNationality", false);
        }
        if (!tag.contains("issueEntryState", Tag.TAG_BYTE)) {
            tag.putBoolean("issueEntryState", true);
        }
        int overstay = tag.contains("overstayDays", Tag.TAG_INT)
                ? tag.getInt("overstayDays") : DeviceBlockEntity.DEFAULT_OVERSTAY_DAYS;
        tag.putInt("overstayDays", Math.max(0, Math.min(DeviceBlockEntity.MAX_OVERSTAY_DAYS, overstay)));

        tag.putString("port", safe(tag.getString("port")));
        tag.putString("country", safe(tag.getString("country")));
        tag.putString("officer", safe(tag.getString("officer")));

        tag.putInt(SCHEMA_KEY, CURRENT);
        return true;
    }

    private static void ensureCompound(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_COMPOUND)) {
            tag.put(key, new CompoundTag());
        }
    }

    private static void ensureList(CompoundTag tag, String key) {
        if (tag.get(key) == null) {
            tag.put(key, new ListTag());
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
