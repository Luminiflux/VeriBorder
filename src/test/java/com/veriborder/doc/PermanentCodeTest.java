package com.veriborder.doc;

import com.veriborder.block.DeviceType;
import com.veriborder.border.BorderEntry;
import com.veriborder.border.BorderService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.3 permanent-code semantics: a code carries the country it was issued for
 * and a purpose ({@code return_home} / {@code visa_free}), so it only opens a
 * gate in that country. Country-less codes written by 26.2 stay universal.
 */
class PermanentCodeTest {
    private static final DocumentData.PermanentPurpose RETURN_HOME = DocumentData.PermanentPurpose.RETURN_HOME;
    private static final DocumentData.PermanentPurpose VISA_FREE = DocumentData.PermanentPurpose.VISA_FREE;

    private static DocumentData passport() {
        return DocumentFactory.sample(DocStyle.CN_PASSPORT);
    }

    @Test
    void permanentCodesCarryCountryPurposeAndExpiry() {
        DocumentData doc = passport();
        assertTrue(doc.permanentCodes().isEmpty());

        assertTrue(doc.addPermanentCode("00112233", "中国", RETURN_HOME, ""));
        assertTrue(doc.addPermanentCode("00112234", "Japan", VISA_FREE, "2099-01-01"));
        List<DocumentData.PermanentCode> codes = doc.permanentCodes();
        assertEquals(2, codes.size());
        assertEquals("00112233", codes.get(0).code());
        assertEquals("中国", codes.get(0).issuerCountry());
        assertTrue(codes.get(0).hasIssuerCountry());
        assertEquals(RETURN_HOME, codes.get(0).purpose());
        assertFalse(codes.get(0).hasExpiry());
        assertEquals(VISA_FREE, codes.get(1).purpose());
        assertTrue(codes.get(1).hasExpiry());
        assertFalse(codes.get(1).expired());

        assertEquals(List.of("00112233", "00112234"), doc.visaFreeCodes());
        assertTrue(doc.allCredentialCodes().containsAll(List.of("00112233", "00112234")));
        assertTrue(doc.integrityValid());

        // Persisted as compounds under the 26.3 key, not as the legacy string list.
        CompoundTag tag = doc.copyTag();
        assertEquals(2, tag.getList("permanentCodes", Tag.TAG_COMPOUND).size());
        assertFalse(tag.contains("visaFreeCodes"));

        DocumentData loaded = DocumentData.fromTag(tag);
        assertEquals(codes, loaded.permanentCodes());
        assertTrue(loaded.integrityValid());
        assertEquals(codes.get(1), loaded.permanentCode("00112234"));
        assertNull(loaded.permanentCode("99999999"));
    }

    @Test
    void invalidDuplicatesAndOversizedListsAreRejected() {
        DocumentData doc = passport();
        assertFalse(doc.addPermanentCode("123", "中国", RETURN_HOME, ""), "invalid code");
        assertFalse(doc.addPermanentCode("", "中国", RETURN_HOME, ""), "blank code");
        String visaCode = doc.visas().get(0).code();
        assertFalse(doc.addPermanentCode(visaCode, "中国", RETURN_HOME, ""),
                "a permanent code shares the visa/endorsement code namespace");

        assertTrue(doc.addPermanentCode("00112233", "中国", RETURN_HOME, "not-a-date"));
        assertEquals("", doc.permanentCodes().get(0).expiry(),
                "an unusable expiry is stored as 'never', never as a fake date");
        assertFalse(doc.addPermanentCode("00112233", "中国", RETURN_HOME, ""), "duplicate code");

        // The list is capped at 16, and the cap never drops an existing code.
        int added = 0;
        for (int i = 0; i < 40; i++) {
            String code = String.format("%08d", 30_000_000 + i);
            if (doc.addPermanentCode(code, "", VISA_FREE, "")) {
                added++;
            }
        }
        assertEquals(DocumentData.MAX_PERMANENT_CODES - 1, added);
        assertEquals(DocumentData.MAX_PERMANENT_CODES, doc.permanentCodes().size());
        assertTrue(doc.integrityValid());

        // Removing index 0 removes the first added code ("00112233"), so remove
        // that code by value first and then check that a second removal is a no-op.
        assertTrue(doc.removePermanentCode("00112233"));
        assertEquals(DocumentData.MAX_PERMANENT_CODES - 1, doc.permanentCodes().size());
        assertTrue(doc.removePermanentCode(0));
        assertFalse(doc.removePermanentCode("00112233"), "removing twice is a no-op");
    }

    @Test
    void legacyStringListIsReadAsUniversalAndFoldedIntoTheNewStorage() {
        // A 26.2 tag: no permanentCodes list, a visaFreeCodes string list instead.
        DocumentData doc = passport();
        CompoundTag tag = doc.copyTag();
        tag.remove("permanentCodes");
        ListTag legacy = new ListTag();
        legacy.add(StringTag.valueOf("00123456"));
        legacy.add(StringTag.valueOf("123"));
        tag.put("visaFreeCodes", legacy);
        DocumentIntegrity.sign(tag);

        DocumentData old = DocumentData.fromTag(tag);
        assertTrue(old.integrityValid(), "reading a legacy tag never invalidates its signature");
        List<DocumentData.PermanentCode> codes = old.permanentCodes();
        assertEquals(1, codes.size());
        assertEquals("00123456", codes.get(0).code());
        assertFalse(codes.get(0).hasIssuerCountry(), "a legacy code has no country");
        assertEquals(VISA_FREE, codes.get(0).purpose());

        // Country-less codes keep the old "opens every gate" behaviour.
        assertEquals("00123456", DocumentValidator.matchPermanentCode(old, "Japan"));
        assertEquals("00123456", DocumentValidator.matchPermanentCode(old, ""));
        assertTrue(DocumentValidator.validateGate(old, DeviceType.EGATE, "both", List.of(), "Japan").valid());

        // Any mutation folds the legacy list into permanentCodes and drops it.
        assertTrue(old.addPermanentCode("00123457", "Japan", VISA_FREE, ""));
        assertFalse(old.copyTag().contains("visaFreeCodes"));
        assertEquals(2, old.permanentCodes().size());
        assertTrue(old.removePermanentCode("00123456"));
        assertEquals(List.of("00123457"), old.visaFreeCodes(),
                "the folded list no longer resurrects a removed legacy code");
        assertNull(old.permanentCode("00123456"));
    }

    @Test
    void returnHomeOnlyOpensItsOwnCountry() {
        DocumentData doc = passport();
        assertTrue(doc.addPermanentCode("11112222", "中国", RETURN_HOME, ""));

        assertEquals("11112222", DocumentValidator.matchPermanentCode(doc, "中国"));
        assertEquals("11112222", DocumentValidator.matchPermanentCode(doc, "China"));
        assertEquals("11112222", DocumentValidator.matchPermanentCode(doc, "CN"));
        assertNull(DocumentValidator.matchPermanentCode(doc, "Japan"));
        assertNull(DocumentValidator.matchPermanentCode(doc, ""),
                "a scoped code never matches a gate whose country is unknown");

        VerificationResult home = DocumentValidator.validateGate(doc, DeviceType.EGATE, "both", List.of(), "中国");
        assertTrue(home.valid());
        assertEquals("11112222", home.detail(), "detail carries the matched permanent code");

        VerificationResult abroad = DocumentValidator.validateGate(doc, DeviceType.EGATE, "both", List.of(), "日本");
        assertEquals(VerificationResult.Code.NO_MATCHING_CODE, abroad.code());
        assertTrue(abroad.reasons().contains(DocumentValidator.REASON_GATE_NO_CODES));

        // BorderService mirrors the validator exactly.
        BorderService.EntryMatch match = BorderService.resolveEntry(doc, "both", List.of(), "中国");
        assertNotNull(match);
        assertEquals(BorderEntry.KIND_VISA_FREE, match.kind());
        assertEquals("11112222", match.code());
        assertNull(BorderService.resolveEntry(doc, "both", List.of(), "日本"));
        assertNull(BorderService.resolveEntry(doc, "all", List.of(), "日本"),
                "the removed all mode normalises to both and grants nothing extra");
    }

    @Test
    void visaFreeExpiryIsHonouredButReturnHomeNeverExpires() {
        DocumentData visaFree = passport();
        assertTrue(visaFree.addPermanentCode("22223333", "中国", VISA_FREE, "2000-01-01"));
        assertTrue(visaFree.permanentCodes().get(0).expired());
        assertNull(DocumentValidator.matchPermanentCode(visaFree, "中国"),
                "an expired visa-free arrangement no longer opens the gate");
        assertEquals(VerificationResult.Code.NO_MATCHING_CODE,
                DocumentValidator.validateGate(visaFree, DeviceType.EGATE, "visa", List.of(), "中国").code());

        DocumentData returnHome = passport();
        assertTrue(returnHome.addPermanentCode("33334444", "中国", RETURN_HOME, "2000-01-01"));
        assertEquals("33334444", DocumentValidator.matchPermanentCode(returnHome, "中国"),
                "returning to your own country never expires");
    }

    @Test
    void firstMatchingCodeWins() {
        DocumentData doc = passport();
        assertTrue(doc.addPermanentCode("44445555", "Japan", RETURN_HOME, ""));
        assertTrue(doc.addPermanentCode("55556666", "中国", RETURN_HOME, ""));
        assertEquals("55556666", DocumentValidator.matchPermanentCode(doc, "中国"));
        assertEquals("44445555", DocumentValidator.matchPermanentCode(doc, "Japan"));
    }
}
