package com.veriborder.border;

import com.veriborder.block.DeviceService;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentFactory;
import com.veriborder.doc.DocumentKind;
import com.veriborder.item.DualGateBrushConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dual-clearance gate (一地两检) rules: the permanent-code slot must satisfy the
 * visa / endorsement / both gate modes without a code match, and the brush
 * configuration schema must be canonicalised before it reaches a block entity.
 */
class DualGateEntryTest {
    @Test
    void permanentCodeSatisfiesEndorsementModeWithoutCodes() {
        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        assertEquals(DocumentKind.PERMIT, permit.kind());
        assertTrue(permit.addVisaFreeCode("12345678"));

        BorderService.EntryMatch match = BorderService.resolveEntry(permit, "endorsement", List.of());
        assertNotNull(match);
        assertEquals(BorderEntry.KIND_VISA_FREE, match.kind());
        assertEquals("12345678", match.code());
    }

    @Test
    void permanentCodeSatisfiesBothModeForPassport() {
        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        assertTrue(passport.addVisaFreeCode("87654321"));

        BorderService.EntryMatch match = BorderService.resolveEntry(passport, "both", List.of());
        assertNotNull(match);
        assertEquals(BorderEntry.KIND_VISA_FREE, match.kind());
    }

    @Test
    void legacyPermanentCodeIsUniversalButScopedCodeNeedsItsCountry() {
        // A 26.2 style permanent code (no issuer country) opens every country.
        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        assertTrue(permit.addVisaFreeCode("12345678"));
        assertNotNull(BorderService.resolveEntry(permit, "both", List.of(), "Japan"));
        assertEquals(BorderEntry.KIND_VISA_FREE,
                BorderService.resolveEntry(permit, "both", List.of(), "Japan").kind());

        // A 26.3 scoped code only opens a gate in the country it was issued for.
        DocumentData scoped = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        assertTrue(scoped.addPermanentCode("22334455", "中国",
                DocumentData.PermanentPurpose.RETURN_HOME, ""));
        assertNotNull(BorderService.resolveEntry(scoped, "both", List.of(), "中国"));
        assertNull(BorderService.resolveEntry(scoped, "both", List.of(), "日本"));
        // The removed "all" mode is no help either: it normalises to both.
        assertNull(BorderService.resolveEntry(scoped, "all", List.of(), "日本"));
    }

    @Test
    void endorsementModeWithoutCodesOrPermanentCodesFindsNothing() {
        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        assertNull(BorderService.resolveEntry(permit, "endorsement", List.of()));
    }

    @Test
    void brushConfigSanitisesModeAndCodes() {
        CompoundTag input = new CompoundTag();
        input.putString("entryMode", "PERMIT"); // legacy alias for endorsement
        input.putString("exitPort", "  深圳湾口岸  ");
        input.putString("unknown", "dropped");
        ListTag codes = new ListTag();
        codes.add(StringTag.valueOf("12345678"));
        codes.add(StringTag.valueOf("12345678"));
        codes.add(StringTag.valueOf("not-a-code"));
        input.put("entryCodes", codes);

        CompoundTag clean = DualGateBrushConfig.sanitize(input);
        assertEquals("endorsement", clean.getString("entryMode"));
        assertEquals("深圳湾口岸", clean.getString("exitPort"));
        assertTrue(!clean.contains("unknown"));
        assertEquals(List.of("12345678"), DualGateBrushConfig.codes(clean));
        // The block entity stores the sanitised compound verbatim.
        assertEquals(clean, DualGateBrushConfig.sanitize(clean));
    }

    @Test
    void brushConfigModeCycleOnlyCoversTheThreeRealModes() {
        String mode = "visa";
        StringBuilder seen = new StringBuilder(mode);
        for (int i = 0; i < 2; i++) {
            mode = DualGateBrushConfig.nextMode(mode);
            seen.append(',').append(mode);
        }
        assertEquals("visa,endorsement,both", seen.toString());
        // 26.3: the removed "all" mode (and every unknown value) loads as both.
        assertEquals("both", DualGateBrushConfig.normalizeMode(""));
        assertEquals("both", DualGateBrushConfig.normalizeMode("all"));
        assertEquals("both", DualGateBrushConfig.normalizeMode("any"));
        assertEquals("both", DualGateBrushConfig.normalizeMode(null));
        assertEquals("both", DualGateBrushConfig.defaultConfig().getString(DualGateBrushConfig.ENTRY_MODE),
                "a fresh brush requires a credential");
        assertEquals(3, DualGateBrushConfig.MODES.length);
    }

    @Test
    void brushConfigKeepsExitAndEntryCodesSeparate() {
        CompoundTag input = new CompoundTag();
        ListTag exits = new ListTag();
        exits.add(StringTag.valueOf("11112222"));
        exits.add(StringTag.valueOf("11112222"));
        exits.add(StringTag.valueOf("bad"));
        exits.add(StringTag.valueOf(" 33334444 "));
        ListTag entries = new ListTag();
        entries.add(StringTag.valueOf("55556666"));
        input.put(DualGateBrushConfig.EXIT_CODES, exits);
        input.put(DualGateBrushConfig.ENTRY_CODES, entries);

        CompoundTag clean = DualGateBrushConfig.sanitize(input);
        assertEquals(List.of("11112222", "33334444"), DualGateBrushConfig.exitCodes(clean),
                "exit codes are normalized and deduplicated");
        assertEquals(List.of("55556666"), DualGateBrushConfig.codes(clean),
                "the entry list is untouched by the exit list");
        assertEquals(2, DualGateBrushConfig.exitCodesTag(clean).size());
        assertEquals(1, DualGateBrushConfig.codesTag(clean).size());
        assertEquals(clean, DualGateBrushConfig.sanitize(clean), "both lists round-trip");

        // A fresh brush carries both lists (empty), never only the entry one.
        CompoundTag defaults = DualGateBrushConfig.defaultConfig();
        assertTrue(defaults.contains(DualGateBrushConfig.EXIT_CODES));
        assertTrue(defaults.contains(DualGateBrushConfig.ENTRY_CODES));
        assertTrue(DualGateBrushConfig.exitCodes(defaults).isEmpty());
        assertTrue(DualGateBrushConfig.codes(defaults).isEmpty());

        // A legacy brush (entryCodes only) is upgraded with an empty exit list.
        CompoundTag legacy = new CompoundTag();
        legacy.put(DualGateBrushConfig.ENTRY_CODES, entries);
        CompoundTag upgraded = DualGateBrushConfig.sanitize(legacy);
        assertTrue(upgraded.contains(DualGateBrushConfig.EXIT_CODES));
        assertTrue(DualGateBrushConfig.exitCodes(upgraded).isEmpty());
        assertEquals(List.of("55556666"), DualGateBrushConfig.codes(upgraded));
    }

    @Test
    void dualGateStagesUseTheirOwnCodes() {
        CompoundTag config = new CompoundTag();
        ListTag exits = new ListTag();
        exits.add(StringTag.valueOf("11112222"));
        ListTag entries = new ListTag();
        entries.add(StringTag.valueOf("33334444"));
        config.put(DualGateBrushConfig.EXIT_CODES, exits);
        config.put(DualGateBrushConfig.ENTRY_CODES, entries);
        List<String> gateCodes = List.of("99998888");

        // 26.3: the exit stage enforces its own list (empty = no restriction, it
        // never falls back to the entry/gate-wide codes).
        assertEquals(List.of("11112222"), DualGateBrushConfig.exitCodes(config));
        assertTrue(DualGateBrushConfig.exitCodes(new CompoundTag()).isEmpty(),
                "an unconfigured exit list means no exit restriction");
        assertEquals(List.of("33334444"),
                DeviceService.dualGateEntryCodes(config, gateCodes),
                "the entry stage reads entryCodes");

        // The entry stage still falls back to the gate-wide list when empty.
        CompoundTag onlyExit = new CompoundTag();
        onlyExit.put(DualGateBrushConfig.EXIT_CODES, exits);
        assertEquals(gateCodes, DeviceService.dualGateEntryCodes(onlyExit, gateCodes));
        assertEquals(gateCodes, DeviceService.dualGateEntryCodes(new CompoundTag(), gateCodes));

        // Directional country whitelists are independent.
        ListTag entryCountries = new ListTag();
        entryCountries.add(StringTag.valueOf("CHN"));
        ListTag exitCountries = new ListTag();
        exitCountries.add(StringTag.valueOf("USA"));
        config.put(DualGateBrushConfig.ENTRY_COUNTRY_CODES, entryCountries);
        config.put(DualGateBrushConfig.EXIT_COUNTRY_CODES, exitCountries);
        CompoundTag clean = DualGateBrushConfig.sanitize(config);
        assertEquals(List.of("CHN"), DualGateBrushConfig.countryCodes(clean));
        assertEquals(List.of("USA"), DualGateBrushConfig.exitCountryCodes(clean));
    }
}
