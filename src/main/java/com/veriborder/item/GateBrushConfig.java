package com.veriborder.item;

import com.veriborder.block.DeviceBlockEntity;
import com.veriborder.doc.CredentialCode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Gate-only configuration carried by {@link GateConfigBrushItem}.
 *
 * <p>The settings live in a single compound tag named {@code VeriBorderGateConfig}
 * on the brush item stack, so the item survives inventory moves, death and
 * dimension changes. The compound holds exactly the gate keys the block entity
 * understands:</p>
 *
 * <pre>
 * gateMode         String   visa | endorsement | both (26.3: no "all" mode)
 * gateCodes        ListTag  8-digit entry credential codes (sanitised, deduplicated)
 * exitCodes        ListTag  8-digit exit codes (empty = no exit restriction)
 * entryCountryCodes ListTag allowed entry issuing-country codes (registry codes)
 * exitCountryCodes ListTag allowed exit issuing-country codes (registry codes)
 * autoStamp        boolean  automatic IN/OUT immigration stamp
 * overstayDays     int      0..MAX_OVERSTAY_DAYS
 * port             String   checkpoint name copied onto the target gate
 * country          String   country/region copied onto the target gate
 * officer          String   officer name copied onto the target gate
 * </pre>
 *
 * <p>Port, country and officer are part of the brush so a gate can be fully
 * provisioned in one apply.</p>
 */
public final class GateBrushConfig {
    /** Root tag key on the brush item stack. */
    public static final String TAG = "VeriBorderGateConfig";

    /** Gate modes the brush cycles through, in screen order (26.3: no "all"). */
    public static final String[] MODES = {"visa", "endorsement", "both"};

    public static final String EXIT_CODES = "exitCodes";
    public static final String ENTRY_COUNTRY_CODES = "entryCountryCodes";
    public static final String EXIT_COUNTRY_CODES = "exitCountryCodes";

    private GateBrushConfig() {
    }

    /** A fresh brush: gate defaults plus empty terminal identity fields. */
    public static CompoundTag defaultConfig() {
        CompoundTag tag = new CompoundTag();
        tag.putString("gateMode", DeviceBlockEntity.GATE_MODE_BOTH);
        tag.put("gateCodes", new ListTag());
        tag.put(EXIT_CODES, new ListTag());
        tag.put(ENTRY_COUNTRY_CODES, new ListTag());
        tag.put(EXIT_COUNTRY_CODES, new ListTag());
        tag.putBoolean("autoStamp", true);
        tag.putInt("overstayDays", DeviceBlockEntity.DEFAULT_OVERSTAY_DAYS);
        tag.putString("port", "");
        tag.putString("country", "");
        tag.putString("officer", "");
        return tag;
    }

    /** Stored configuration, or the defaults when the brush has never been saved. */
    public static CompoundTag read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return defaultConfig();
        }
        CompoundTag root = stack.getTag();
        if (root == null || !root.contains(TAG, Tag.TAG_COMPOUND)) {
            return defaultConfig();
        }
        return sanitize(root.getCompound(TAG));
    }

    /** Writes an already sanitised copy so a forged packet cannot store junk. */
    public static void write(ItemStack stack, CompoundTag config) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        stack.getOrCreateTag().put(TAG, sanitize(config));
    }

    /**
     * Canonicalises an arbitrary compound into the exact brush schema: unknown
     * keys are dropped, the mode is normalised, the code list is reduced to valid
     * unique 8-digit codes and the overstay limit is clamped.
     */
    public static CompoundTag sanitize(CompoundTag input) {
        CompoundTag source = input == null ? new CompoundTag() : input;
        CompoundTag out = new CompoundTag();
        out.putString("gateMode", normalizeMode(source.getString("gateMode")));
        out.put("gateCodes", codesTag(source));
        out.put(EXIT_CODES, exitCodesTag(source));
        out.put(ENTRY_COUNTRY_CODES, countryCodesTag(source, ENTRY_COUNTRY_CODES));
        out.put(EXIT_COUNTRY_CODES, countryCodesTag(source, EXIT_COUNTRY_CODES));
        out.putBoolean("autoStamp", !source.contains("autoStamp") || source.getBoolean("autoStamp"));
        out.putInt("overstayDays", clampOverstay(source.contains("overstayDays")
                ? source.getInt("overstayDays") : DeviceBlockEntity.DEFAULT_OVERSTAY_DAYS));
        out.putString("port", source.getString("port"));
        out.putString("country", source.getString("country"));
        out.putString("officer", source.getString("officer"));
        return out;
    }

    /** Valid, deduplicated codes stored in a config compound. */
    public static List<String> codes(CompoundTag config) {
        List<String> raw = new ArrayList<>();
        if (config != null) {
            ListTag list = config.getList("gateCodes", Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                raw.add(list.getString(i));
            }
        }
        return CredentialCode.normalizeAll(raw);
    }

    /** Copy of the stored codes as a string list tag. */
    public static ListTag codesTag(CompoundTag config) {
        ListTag list = new ListTag();
        for (String code : codes(config)) {
            list.add(StringTag.valueOf(code));
        }
        return list;
    }

    /** Valid, deduplicated exit codes stored in a config compound (empty = unrestricted). */
    public static List<String> exitCodes(CompoundTag config) {
        return codes(config, EXIT_CODES);
    }

    /** Copy of the stored exit codes as a string list tag. */
    public static ListTag exitCodesTag(CompoundTag config) {
        ListTag list = new ListTag();
        for (String code : exitCodes(config)) {
            list.add(StringTag.valueOf(code));
        }
        return list;
    }

    /** Allowed entry issuing-country codes (registry codes, 3 letters). */
    public static List<String> entryCountryCodes(CompoundTag config) {
        return countryCodes(config, ENTRY_COUNTRY_CODES);
    }

    /** Allowed exit issuing-country codes (registry codes, 3 letters). */
    public static List<String> exitCountryCodes(CompoundTag config) {
        return countryCodes(config, EXIT_COUNTRY_CODES);
    }

    /** Sanitised copy of one directional country whitelist. */
    public static ListTag countryCodesTag(CompoundTag config, String key) {
        ListTag list = new ListTag();
        for (String code : countryCodes(config, key)) {
            list.add(StringTag.valueOf(code));
        }
        return list;
    }

    private static List<String> countryCodes(CompoundTag config, String key) {
        List<String> result = new ArrayList<>();
        if (config != null) {
            ListTag list = config.getList(key, Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                String code = com.veriborder.doc.MrzUtil.countryCode(list.getString(i));
                if (!code.isEmpty() && !result.contains(code)) {
                    result.add(code);
                }
            }
        }
        return result;
    }

    /** Valid, deduplicated codes stored under one key. */
    private static List<String> codes(CompoundTag config, String key) {
        List<String> raw = new ArrayList<>();
        if (config != null) {
            ListTag list = config.getList(key, Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                raw.add(list.getString(i));
            }
        }
        return CredentialCode.normalizeAll(raw);
    }

    public static int clampOverstay(int value) {
        return Math.max(0, Math.min(DeviceBlockEntity.MAX_OVERSTAY_DAYS, value));
    }

    public static String normalizeMode(String value) {
        return DeviceBlockEntity.normalizeGateMode(value);
    }

    public static String nextMode(String value) {
        String current = normalizeMode(value);
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i].equals(current)) {
                return MODES[(i + 1) % MODES.length];
            }
        }
        return MODES[0];
    }

    /** Translation key of a gate mode label (shared with the terminal screen). */
    public static String modeKey(String mode) {
        return "screen.veriborder.config.gate_mode." + normalizeMode(mode);
    }

    /**
     * Copies the stored gate settings onto a placed gate, including the
     * checkpoint identity (port, country, officer). Lock flags and presets are
     * not touched.
     */
    public static void applyTo(DeviceBlockEntity device, CompoundTag config) {
        if (device == null) {
            return;
        }
        CompoundTag clean = sanitize(config);
        device.setGateMode(clean.getString("gateMode"));
        device.setGateCodes(clean.getList("gateCodes", Tag.TAG_STRING));
        device.setExitCodes(clean.getList(EXIT_CODES, Tag.TAG_STRING));
        device.setEntryCountryCodes(clean.getList(ENTRY_COUNTRY_CODES, Tag.TAG_STRING));
        device.setExitCountryCodes(clean.getList(EXIT_COUNTRY_CODES, Tag.TAG_STRING));
        device.setAutoStamp(clean.getBoolean("autoStamp"));
        device.setOverstayDays(clean.getInt("overstayDays"));
        device.setPort(clean.getString("port"));
        device.setCountry(clean.getString("country"));
        device.setOfficer(clean.getString("officer"));
    }
}
