package com.veriborder.item;

import com.veriborder.block.DeviceBlockEntity;
import com.veriborder.block.DeviceType;
import com.veriborder.doc.CredentialCode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Configuration payload carried by {@link ConfigCopyBrushItem}.
 *
 * <p>The brush stores one complete issuing-machine configuration in a single
 * compound tag named {@code VeriBorderCopyBrush} on the item stack, so it
 * survives inventory moves, death and dimension changes:</p>
 *
 * <pre>
 * VeriBorderCopyBrush
 *   sourceType   String      the {@link DeviceType#id()} the payload came from
 *   config       CompoundTag the source machine's {@link DeviceBlockEntity#configTag()}
 * </pre>
 *
 * <p>{@code config} is the very same payload the device configuration GUI and
 * the block-entity NBT use: checkpoint identity (port / country / officer /
 * airport country), the {@link DeviceBlockEntity.Locks} record (document name,
 * authority, layout, place, issue date, validity and every passport-attached
 * visa lock), the 26.2 issuer policy (number lock, nationality lock, region /
 * sequence / MRZ overrides, entry state, permanent codes), gate binding (mode,
 * codes, auto-stamp, overstay limit), the dual-clearance payload and the stored
 * presets. Only the two runtime keys ({@code gateState}, {@code gateMessage})
 * are dropped, because a copy is a configuration, not a gate's transient
 * state.</p>
 *
 * <p>Every ingress is funnelled through {@link #sanitize(CompoundTag)}, which
 * rebuilds the compound from a key whitelist, re-normalises every value through
 * the block entity's own rules and bounds the lists, so a hand-edited stack can
 * never push junk (or an unbounded NBT tree) into a placed machine.</p>
 */
public final class ConfigCopyBrushConfig {
    /** Root tag key on the brush item stack. */
    public static final String TAG = "VeriBorderCopyBrush";
    /** Device type the payload was copied from. */
    public static final String SOURCE_TYPE = "sourceType";
    /** The copied configuration compound. */
    public static final String CONFIG = "config";

    /** Upper bound for a single text value so a forged tag cannot grow without bound. */
    public static final int MAX_TEXT_LENGTH = 512;
    /** Upper bound for the two eight-digit code lists. */
    public static final int MAX_CODES = 4096;
    /** Upper bound for the preset list (matches {@link DeviceBlockEntity#savePreset}). */
    public static final int MAX_PRESETS = 32;
    /** Upper bound for a bulk tag so a deeply nested forged payload is rejected. */
    public static final int MAX_TAG_DEPTH = 8;

    /** Text keys copied verbatim (after trimming and length clamping). */
    private static final String[] TEXT_KEYS = {
            "port", "country", "officer", "airportCountry",
            "requiredVisaType", "requiredEndorsementType"
    };

    /** Boolean keys copied from the source (defaults mirror {@code DeviceBlockEntity}). */
    private static final String[] FLAG_KEYS = {
            "autoStamp", "lockNumber", "lockNationality", "issueEntryState", "lockEndorsementCode"
    };

    private ConfigCopyBrushConfig() {
    }

    /** A readable copy: the device type it came from plus its configuration. */
    public record Snapshot(String sourceType, CompoundTag config) {
        /** Resolved source device type, or {@code null} when the tag carries an unknown id. */
        @Nullable
        public DeviceType sourceDevice() {
            return ConfigCopyBrushConfig.typeOrNull(sourceType);
        }
    }

    /** Resolves a {@link DeviceType} by its registry id; unknown ids return {@code null}. */
    @Nullable
    public static DeviceType typeOrNull(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String trimmed = id.trim();
        for (DeviceType type : DeviceType.values()) {
            if (type.id().equals(trimmed)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Stored payload, or {@code null} when the brush is empty / carries an
     * unknown source type. The configuration is returned unsanitised; callers
     * that write it into a machine must run {@link #sanitize(CompoundTag)} first
     * (the apply path does).
     */
    @Nullable
    public static Snapshot read(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CompoundTag root = stack.getTag();
        if (root == null || !root.contains(TAG, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag brush = root.getCompound(TAG);
        DeviceType source = typeOrNull(brush.getString(SOURCE_TYPE));
        if (source == null) {
            return null;
        }
        CompoundTag config = brush.getCompound(CONFIG);
        if (config.isEmpty()) {
            return null;
        }
        return new Snapshot(source.id(), config);
    }

    /**
     * Stores {@code config} (the source machine's {@code configTag()}) on the
     * brush together with its {@code sourceType}. A sanitised copy is written so
     * the item NBT is canonical from the start.
     */
    public static void write(@Nullable ItemStack stack, @Nullable DeviceType sourceType, @Nullable CompoundTag config) {
        if (stack == null || stack.isEmpty() || sourceType == null) {
            return;
        }
        CompoundTag brush = new CompoundTag();
        brush.putString(SOURCE_TYPE, sourceType.id());
        brush.put(CONFIG, sanitize(config));
        stack.getOrCreateTag().put(TAG, brush);
    }

    /** Whether the brush currently holds a payload that {@link #read} accepts. */
    public static boolean isEmpty(@Nullable ItemStack stack) {
        return read(stack) == null;
    }

    /**
     * Rebuilds an arbitrary compound as the exact copy-brush schema: unknown
     * keys (including the runtime {@code gateState} / {@code gateMessage}) are
     * dropped, every value is re-normalised through the same rules the block
     * entity applies on load, and lists are bounded.
     */
    public static CompoundTag sanitize(@Nullable CompoundTag input) {
        CompoundTag source = input == null ? new CompoundTag() : input;
        CompoundTag out = new CompoundTag();

        // ---- checkpoint identity (口岸 / 国家 / 官员 / 机场国家) --------------
        for (String key : TEXT_KEYS) {
            out.putString(key, text(source.getString(key)));
        }
        out.putString("gateMode", DeviceBlockEntity.normalizeGateMode(source.getString("gateMode")));
        // Issuer policy number overrides have the same constraints as the block entity.
        out.putString("lockedNationality", text(source.getString("lockedNationality"),
                DeviceBlockEntity.MAX_NATIONALITY_LENGTH));
        out.putString("numberRegion", digits(source.getString("numberRegion"), 6, false));
        out.putString("numberSequence", digits(source.getString("numberSequence"), 2, true));
        out.putString("mrzCountry", mrzCountry(source.getString("mrzCountry")));
        // 26.3 registry: the single issuing-country code and the gate whitelists
        // must travel with a copied configuration, otherwise a paste loses them.
        out.putString("issuerCountry", mrzCountry(source.getString("issuerCountry")));
        out.putString("issuedCode", CredentialCode.sanitize(source.getString("issuedCode")));
        out.putString("lockedEndorsementCode",
                CredentialCode.sanitize(source.getString("lockedEndorsementCode")));
        // 26.4: the layout lock can point at a registry template. Copying a config
        // without this key silently downgraded the locked layout to its base style
        // on every paste, so the template link is part of the whitelist now.
        out.putString("lockedStyleTemplate",
                DeviceBlockEntity.normalizeLockedStyleTemplate(source.getString("lockedStyleTemplate")));

        // ---- flags ----------------------------------------------------------
        out.putBoolean("autoStamp", flag(source, "autoStamp", true));
        out.putBoolean("lockNumber", flag(source, "lockNumber", true));
        out.putBoolean("lockNationality", flag(source, "lockNationality", false));
        out.putBoolean("issueEntryState", flag(source, "issueEntryState", true));
        out.putBoolean("lockEndorsementCode", flag(source, "lockEndorsementCode", false));

        // ---- gate behaviour -------------------------------------------------
        out.putInt("overstayDays", clampOverstay(source.contains("overstayDays")
                ? source.getInt("overstayDays") : DeviceBlockEntity.DEFAULT_OVERSTAY_DAYS));
        out.put("gateCodes", codesTag(source.getList("gateCodes", Tag.TAG_STRING)));
        out.put("exitCodes", codesTag(source.getList("exitCodes", Tag.TAG_STRING)));
        out.put("entryCountryCodes", countryCodesTag(source.getList("entryCountryCodes", Tag.TAG_STRING)));
        out.put("exitCountryCodes", countryCodesTag(source.getList("exitCountryCodes", Tag.TAG_STRING)));
        out.put("issueCodes", codesTag(source.getList("issueCodes", Tag.TAG_STRING)));

        // ---- the full lock record (document name .. passport-attached visa) --
        DeviceBlockEntity.Locks.fromTag(source).writeTo(out);

        // ---- dual-clearance gate payload (dual gates only) ------------------
        if (source.get("dualGate") instanceof CompoundTag dualGate && depthOk(dualGate)) {
            // 26.4: re-run the dual-gate schema instead of a raw copy, so a copied
            // configuration cannot carry stale/unknown two-stage keys (and the two
            // directional whitelists are normalised like the editor writes them).
            out.put("dualGate", DualGateBrushConfig.sanitize(dualGate));
        }

        // ---- device-local presets (bounded) ---------------------------------
        out.put("presets", presetsTag(source.get("presets")));
        return out;
    }

    /** Number of {@code lock*} flags set in a configuration compound. */
    public static int lockedCount(@Nullable CompoundTag config) {
        if (config == null) {
            return 0;
        }
        int count = 0;
        for (String key : config.getAllKeys()) {
            if (!key.startsWith("lock") || key.startsWith("locked")) {
                continue;
            }
            if (config.getTagType(key) == Tag.TAG_BYTE && config.getBoolean(key)) {
                count++;
            }
        }
        return count;
    }

    /** Clamped overstay limit, shared with the gate brush and the block entity. */
    public static int clampOverstay(int value) {
        return Math.max(0, Math.min(DeviceBlockEntity.MAX_OVERSTAY_DAYS, value));
    }

    // ---- helpers -----------------------------------------------------------

    private static String text(@Nullable String value) {
        return text(value, MAX_TEXT_LENGTH);
    }

    private static String text(@Nullable String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }

    /** Digits only, truncated from the left (regions) or kept as the last N (sequences). */
    private static String digits(@Nullable String value, int maxLength, boolean keepTail) {
        String cleaned = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        return keepTail ? cleaned.substring(cleaned.length() - maxLength) : cleaned.substring(0, maxLength);
    }

    /** MRZ country: exactly three letters, upper-cased; anything else means "layout default". */
    private static String mrzCountry(@Nullable String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        return cleaned.length() == 3 ? cleaned : "";
    }

    private static boolean flag(CompoundTag source, String key, boolean fallback) {
        return source.contains(key) ? source.getBoolean(key) : fallback;
    }

    private static ListTag codesTag(ListTag source) {
        List<String> raw = new ArrayList<>();
        for (int i = 0; i < source.size() && raw.size() < MAX_CODES; i++) {
            raw.add(source.getString(i));
        }
        ListTag list = new ListTag();
        for (String code : CredentialCode.normalizeAll(raw)) {
            list.add(StringTag.valueOf(code));
        }
        return list;
    }

    /** Three-letter country-code list (registry whitelists), deduplicated. */
    private static ListTag countryCodesTag(ListTag source) {
        ListTag list = new ListTag();
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < source.size() && seen.size() < MAX_CODES; i++) {
            String code = mrzCountry(source.getString(i));
            if (!code.isEmpty() && !seen.contains(code)) {
                seen.add(code);
                list.add(StringTag.valueOf(code));
            }
        }
        return list;
    }

    private static ListTag presetsTag(@Nullable Tag raw) {
        ListTag presets = new ListTag();
        if (!(raw instanceof ListTag list)) {
            return presets;
        }
        for (int i = 0; i < list.size() && presets.size() < MAX_PRESETS; i++) {
            if (!(list.get(i) instanceof CompoundTag entry)) {
                continue;
            }
            Tag values = entry.get("values");
            if (!(values instanceof CompoundTag valueTag) || !depthOk(valueTag)) {
                continue;
            }
            CompoundTag copy = new CompoundTag();
            copy.putString("name", text(entry.getString("name")));
            copy.put("values", valueTag.copy());
            presets.add(copy);
        }
        return presets;
    }

    /** Rejects absurdly nested compounds so a forged tag cannot blow the NBT tree up. */
    private static boolean depthOk(CompoundTag tag) {
        return depth(tag, 0) <= MAX_TAG_DEPTH;
    }

    private static int depth(Tag tag, int level) {
        if (level > MAX_TAG_DEPTH) {
            return level;
        }
        if (tag instanceof CompoundTag compound) {
            int deepest = level;
            for (String key : compound.getAllKeys()) {
                deepest = Math.max(deepest, depth(compound.get(key), level + 1));
            }
            return deepest;
        }
        if (tag instanceof ListTag list) {
            int deepest = level;
            for (int i = 0; i < list.size(); i++) {
                deepest = Math.max(deepest, depth(list.get(i), level + 1));
            }
            return deepest;
        }
        return level;
    }
}
