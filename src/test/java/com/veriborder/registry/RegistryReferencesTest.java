package com.veriborder.registry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registry reference guard (26.3 round 12, item 12).
 *
 * <p>A country/region or template that something still points at must not be
 * deleted. The classification is pure data ({@link RegistryReferences.Evidence}
 * in, {@link RegistryReferences.Ref} out), so every reference kind is checked
 * here without a live server: documents, gate whitelists, gate brushes,
 * issuing machines, template defaults and issuer presets/locked names.</p>
 */
class RegistryReferencesTest {
    private static List<RegistryReferences.Evidence> evidence() {
        List<RegistryReferences.Evidence> list = new ArrayList<>();
        list.add(RegistryReferences.Evidence.country(RegistryReferences.Kind.DOCUMENT,
                "ALEX · E12345678", "CHN"));
        list.add(RegistryReferences.Evidence.country(RegistryReferences.Kind.GATE_WHITELIST,
                "egate @ 4,64,9", "HKG"));
        list.add(RegistryReferences.Evidence.country(RegistryReferences.Kind.GATE_BRUSH,
                "ALEX", "JPN"));
        list.add(RegistryReferences.Evidence.country(RegistryReferences.Kind.DUAL_GATE_BRUSH,
                "ALEX", "USA"));
        list.add(RegistryReferences.Evidence.country(RegistryReferences.Kind.DEVICE_ISSUER,
                "passport_issuer @ 0,64,0", "SGP"));
        list.add(RegistryReferences.Evidence.country(RegistryReferences.Kind.TEMPLATE,
                "公务护照", "GBR"));
        list.add(RegistryReferences.Evidence.template(RegistryReferences.Kind.ISSUER_PRESET,
                "passport_issuer @ 0,64,0", "", "公务护照"));
        return list;
    }

    @Test
    void everyReferenceKindBlocksTheDelete() {
        List<RegistryReferences.Evidence> data = evidence();
        assertEquals(RegistryReferences.Kind.DOCUMENT,
                RegistryReferences.forCountry(data, "CHN").get(0).kind());
        assertEquals(RegistryReferences.Kind.GATE_WHITELIST,
                RegistryReferences.forCountry(data, "hkg").get(0).kind(),
                "the lookup normalises the three-letter code");
        assertEquals(RegistryReferences.Kind.GATE_BRUSH,
                RegistryReferences.forCountry(data, "JPN").get(0).kind());
        assertEquals(RegistryReferences.Kind.DUAL_GATE_BRUSH,
                RegistryReferences.forCountry(data, "USA").get(0).kind());
        assertEquals(RegistryReferences.Kind.DEVICE_ISSUER,
                RegistryReferences.forCountry(data, "SGP").get(0).kind());
        assertEquals(RegistryReferences.Kind.TEMPLATE,
                RegistryReferences.forCountry(data, "GBR").get(0).kind());
        assertTrue(RegistryReferences.forCountry(data, "CHN").get(0).label().contains("E12345678"),
                "the refusal names the referencing object");
    }

    @Test
    void anUnreferencedCountryIsDeletable() {
        assertTrue(RegistryReferences.forCountry(evidence(), "MAC").isEmpty());
        assertTrue(RegistryReferences.forCountry(evidence(), "").isEmpty());
        assertTrue(RegistryReferences.forCountry(null, "CHN").isEmpty());
        assertTrue(RegistryReferences.forCountry(evidence(), null).isEmpty());
    }

    @Test
    void aTemplateIsBlockedByItsLockedNameOrPreset() {
        List<RegistryReferences.Ref> refs = RegistryReferences.forTemplate(evidence(), "tpl-1", "公务护照");
        assertEquals(1, refs.size(), "the issuer preset named after the template blocks it");
        assertEquals(RegistryReferences.Kind.ISSUER_PRESET, refs.get(0).kind());
        // Id match also blocks, and an unrelated template does not.
        assertTrue(RegistryReferences.forTemplate(evidence(), "", "其他模板").isEmpty());
        assertEquals(1, RegistryReferences.forTemplate(evidence(), "", "公务护照").size());
        assertTrue(RegistryReferences.forTemplate(null, "tpl-1", "公务护照").isEmpty());
    }

    @Test
    void theSummaryNamesTheFirstReferenceAndItsCount() {
        List<RegistryReferences.Ref> refs = RegistryReferences.forCountry(evidence(), "CHN");
        String summary = RegistryReferences.summary(refs);
        assertTrue(summary.startsWith("document"), summary);
        assertTrue(summary.contains("E12345678"), summary);
        assertEquals("", RegistryReferences.summary(List.of()));
    }

    @Test
    void kindKeysAreStableTranslationKeys() {
        for (RegistryReferences.Kind kind : RegistryReferences.Kind.values()) {
            assertEquals("reference.veriborder." + kind.id(), kind.translationKey());
        }
        assertEquals("reference.veriborder.gate_whitelist",
                RegistryReferences.Kind.GATE_WHITELIST.translationKey());
    }
}
