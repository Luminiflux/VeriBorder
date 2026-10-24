package com.veriborder.item;

import com.veriborder.block.DeviceBlockEntity;
import com.veriborder.doc.CredentialCode;
import com.veriborder.doc.MrzUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Two-stage configuration carried by {@link DualGateBrushItem}.
 *
 * <p>The settings live in a single compound named {@code VeriBorderDualGateConfig}
 * on the brush item stack, so the item survives inventory moves, death and
 * dimension changes. The same schema is copied onto the
 * {@link DeviceBlockEntity} of a placed {@code veriborder:dual_gate}:</p>
 *
 * <pre>
 * exitAuthority   String    authority printed on the OUT stamp
 * exitOfficer     String    officer printed on the OUT stamp
 * exitPort        String    checkpoint printed on the OUT stamp / exit session
 * entryAuthority  String    authority printed on the IN stamp
 * entryOfficer    String    officer printed on the IN stamp
 * entryPort       String    checkpoint printed on the IN stamp / new session
 * entryMode       String    visa | endorsement | both (GateBrushConfig semantics)
 * exitCodes       ListTag   eight-digit codes for the exit stage (fallback: gate codes)
 * entryCodes      ListTag   eight-digit codes checked by the entry stage
 * entryCountryCodes ListTag allowed entry issuing-country codes (registry codes)
 * exitCountryCodes ListTag  allowed exit issuing-country codes (registry codes)
 * </pre>
 *
 * <p>The two code lists are independent: the exit stage reads {@code exitCodes}
 * and the entry stage reads {@code entryCodes}, each falling back to the gate's
 * generic {@code gateCodes} only when its own list is empty. The entry mode
 * reuses {@link GateBrushConfig}'s mode vocabulary: {@code visa} a passport
 * visa, {@code endorsement} a permit endorsement and {@code both} either of
 * them (26.3 removed the old {@code all} mode). A document whose permanent-code
 * list matches the gate country is always accepted by the entry stage (kind
 * {@code visa_free}).</p>
 */
public final class DualGateBrushConfig {
    /** Root tag key on the brush item stack and on the dual gate block entity. */
    public static final String TAG = "VeriBorderDualGateConfig";

    /** Entry-stage modes, in screen cycle order (matches GateBrushConfig, no "all"). */
    public static final String[] MODES = {"visa", "endorsement", "both"};

    public static final String EXIT_AUTHORITY = "exitAuthority";
    public static final String EXIT_OFFICER = "exitOfficer";
    public static final String EXIT_PORT = "exitPort";
    public static final String ENTRY_AUTHORITY = "entryAuthority";
    public static final String ENTRY_OFFICER = "entryOfficer";
    public static final String ENTRY_PORT = "entryPort";
    public static final String ENTRY_MODE = "entryMode";
    public static final String EXIT_CODES = "exitCodes";
    public static final String ENTRY_CODES = "entryCodes";
    /** Allowed issuing-country codes for the entry stage (visa-free pass). */
    public static final String ENTRY_COUNTRY_CODES = "entryCountryCodes";
    /** Allowed issuing-country codes for the exit stage (directional whitelist). */
    public static final String EXIT_COUNTRY_CODES = "exitCountryCodes";

    private DualGateBrushConfig() {
    }

    /** A fresh brush: both stages unset (the gate falls back to its own identity). */
    public static CompoundTag defaultConfig() {
        CompoundTag tag = new CompoundTag();
        tag.putString(EXIT_AUTHORITY, "");
        tag.putString(EXIT_OFFICER, "");
        tag.putString(EXIT_PORT, "");
        tag.putString(ENTRY_AUTHORITY, "");
        tag.putString(ENTRY_OFFICER, "");
        tag.putString(ENTRY_PORT, "");
        tag.putString(ENTRY_MODE, DeviceBlockEntity.GATE_MODE_BOTH);
        tag.put(EXIT_CODES, new ListTag());
        tag.put(ENTRY_CODES, new ListTag());
        tag.put(ENTRY_COUNTRY_CODES, new ListTag());
        tag.put(EXIT_COUNTRY_CODES, new ListTag());
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
     * Canonicalises an arbitrary compound into the exact dual-gate schema:
     * unknown keys are dropped, the entry mode is normalised and both code lists
     * are reduced to valid unique eight-digit codes.
     */
    public static CompoundTag sanitize(CompoundTag input) {
        CompoundTag source = input == null ? new CompoundTag() : input;
        CompoundTag out = new CompoundTag();
        out.putString(EXIT_AUTHORITY, text(source.getString(EXIT_AUTHORITY)));
        out.putString(EXIT_OFFICER, text(source.getString(EXIT_OFFICER)));
        out.putString(EXIT_PORT, text(source.getString(EXIT_PORT)));
        out.putString(ENTRY_AUTHORITY, text(source.getString(ENTRY_AUTHORITY)));
        out.putString(ENTRY_OFFICER, text(source.getString(ENTRY_OFFICER)));
        out.putString(ENTRY_PORT, text(source.getString(ENTRY_PORT)));
        out.putString(ENTRY_MODE, normalizeMode(source.getString(ENTRY_MODE)));
        out.put(EXIT_CODES, codesTag(source, EXIT_CODES));
        out.put(ENTRY_CODES, codesTag(source, ENTRY_CODES));
        out.put(ENTRY_COUNTRY_CODES, countryCodesTag(source, ENTRY_COUNTRY_CODES));
        out.put(EXIT_COUNTRY_CODES, countryCodesTag(source, EXIT_COUNTRY_CODES));
        return out;
    }

    /** Valid, deduplicated codes of the exit stage stored in a config compound. */
    public static List<String> exitCodes(CompoundTag config) {
        return codes(config, EXIT_CODES);
    }

    /** Valid, deduplicated entry codes stored in a config compound. */
    public static List<String> codes(CompoundTag config) {
        return codes(config, ENTRY_CODES);
    }

    /** Copy of the stored exit-stage codes as a string list tag. */
    public static ListTag exitCodesTag(CompoundTag config) {
        return codesTag(config, EXIT_CODES);
    }

    /** Copy of the stored entry-stage codes as a string list tag. */
    public static ListTag codesTag(CompoundTag config) {
        return codesTag(config, ENTRY_CODES);
    }

    /** Allowed issuing-country codes for the entry stage (3 letters). */
    public static List<String> countryCodes(CompoundTag config) {
        return countryCodes(config, ENTRY_COUNTRY_CODES);
    }

    /** Allowed issuing-country codes for the exit stage (3 letters). */
    public static List<String> exitCountryCodes(CompoundTag config) {
        return countryCodes(config, EXIT_COUNTRY_CODES);
    }

    /** Sanitised copy of the entry-stage allowed country codes. */
    public static ListTag countryCodesTag(CompoundTag config) {
        return countryCodesTag(config, ENTRY_COUNTRY_CODES);
    }

    /** Sanitised copy of the exit-stage allowed country codes. */
    public static ListTag exitCountryCodesTag(CompoundTag config) {
        return countryCodesTag(config, EXIT_COUNTRY_CODES);
    }

    private static List<String> countryCodes(CompoundTag config, String key) {
        if (config == null) {
            return List.of();
        }
        ListTag list = config.getList(key, Tag.TAG_STRING);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String code = MrzUtil.countryCode(list.getString(i));
            if (!code.isEmpty() && !result.contains(code)) {
                result.add(code);
            }
        }
        return result;
    }

    private static ListTag countryCodesTag(CompoundTag config, String key) {
        ListTag list = new ListTag();
        for (String code : countryCodes(config, key)) {
            list.add(StringTag.valueOf(code));
        }
        return list;
    }
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

    private static ListTag codesTag(CompoundTag config, String key) {
        ListTag list = new ListTag();
        for (String code : codes(config, key)) {
            list.add(StringTag.valueOf(code));
        }
        return list;
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

    /** Translation key of an entry-mode label (shared with the generic gate screen). */
    public static String modeKey(String mode) {
        return GateBrushConfig.modeKey(mode);
    }

    /**
     * Copies the sanitised two-stage configuration onto a placed dual gate.
     * Nothing else on the block entity (gate codes, overstay limit, presets) is
     * touched.
     */
    public static void applyTo(DeviceBlockEntity device, CompoundTag config) {
        if (device == null) {
            return;
        }
        device.setDualGateConfig(sanitize(config));
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
