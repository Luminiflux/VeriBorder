package com.veriborder.doc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two-track rejection reasons (26.3 round 12, item 5).
 *
 * <p>The traveller standing at a gate only ever sees a plain-language sentence;
 * the administrator track keeps the machine-readable reason code in the border
 * log and the officer console. These tests pin the mapping of every requested
 * case — no entry record, document not yours, missing valid visa/endorsement,
 * expired document, overstay, country not visa-free — and prove that the codes
 * themselves are untouched.</p>
 */
class TravellerReasonsTest {
    @Test
    void theSixRequestedCasesMapToNaturalLanguage() {
        assertEquals(TravellerReasons.NO_ENTRY_RECORD,
                TravellerReasons.key("verify.veriborder.reason.no_entry_record"));
        assertEquals(TravellerReasons.NOT_HOLDER,
                TravellerReasons.key("verify.veriborder.reason.session_document_mismatch"));
        assertEquals(TravellerReasons.NO_VALID_CREDENTIAL,
                TravellerReasons.key("verify.veriborder.reason.gate_code_mismatch"));
        assertEquals(TravellerReasons.DOCUMENT_EXPIRED,
                TravellerReasons.key("verify.veriborder.reason.expired"));
        assertEquals(TravellerReasons.OVERSTAY,
                TravellerReasons.key("verify.veriborder.reason.overstay"));
        assertEquals(TravellerReasons.COUNTRY_NOT_VISA_FREE,
                TravellerReasons.key("verify.veriborder.reason.country_not_visa_free"));
    }

    @Test
    void theMissingCredentialCaseFollowsTheGateMode() {
        assertEquals(TravellerReasons.NO_VALID_VISA,
                TravellerReasons.key("verify.veriborder.reason.gate_code_mismatch", "visa"));
        assertEquals(TravellerReasons.NO_VALID_ENDORSEMENT,
                TravellerReasons.key("verify.veriborder.reason.gate_code_mismatch", "endorsement"));
        assertEquals(TravellerReasons.NO_VALID_CREDENTIAL,
                TravellerReasons.key("verify.veriborder.reason.session_record_missing", "both"));
        // An unknown/blank mode keeps the neutral wording.
        assertEquals(TravellerReasons.NO_VALID_CREDENTIAL,
                TravellerReasons.key("verify.veriborder.reason.session_record_missing", null));
    }

    @Test
    void documentFaultsCollapseIntoOneClearSentence() {
        for (String reason : new String[]{
                "verify.veriborder.reason.integrity",
                "verify.veriborder.reason.lost",
                "verify.veriborder.reason.cancelled",
                "verify.veriborder.reason.suspended",
                "verify.veriborder.reason.name",
                "verify.veriborder.reason.number",
                "verify.veriborder.reason.birth_date",
                "verify.veriborder.reason.wrong_kind"}) {
            assertEquals(TravellerReasons.DOCUMENT_INVALID, TravellerReasons.key(reason), reason);
        }
        assertEquals(TravellerReasons.ALREADY_ENTERED,
                TravellerReasons.key("verify.veriborder.reason.already_entered"));
        // Unknown codes never leak a raw translation key to the traveller.
        assertEquals(TravellerReasons.REJECTED, TravellerReasons.key("some.future.reason"));
        assertEquals(TravellerReasons.REJECTED, TravellerReasons.key(null));
    }

    @Test
    void aWholeVerificationResultMapsThroughTheAdministratorCode() {
        VerificationResult denied = VerificationResult.fail(VerificationResult.Code.NO_MATCHING_CODE,
                "verify.veriborder.reason.country_not_visa_free");
        // Administrator track: the reason code is preserved verbatim.
        assertEquals("verify.veriborder.reason.country_not_visa_free", denied.reasons().get(0));
        // Traveller track: a sentence, not a key.
        assertEquals(TravellerReasons.COUNTRY_NOT_VISA_FREE, TravellerReasons.of(denied));
        assertTrue(TravellerReasons.of(denied).startsWith("verify.veriborder.traveller."));

        assertEquals(TravellerReasons.PASS, TravellerReasons.of(VerificationResult.ok("")));
        assertEquals(TravellerReasons.REJECTED, TravellerReasons.of(null));
        assertEquals(TravellerReasons.REJECTED,
                TravellerReasons.of(VerificationResult.fail(VerificationResult.Code.MALFORMED)));
    }

    @Test
    void everyTravellerKeyIsDistinctAndNamespaced() {
        // A missing lang entry would show the key itself at the gate, so the
        // namespace is asserted here (LangParityTest covers both files).
        String[] keys = {
                TravellerReasons.PASS, TravellerReasons.REJECTED, TravellerReasons.NO_ENTRY_RECORD,
                TravellerReasons.NOT_HOLDER, TravellerReasons.NO_VALID_VISA,
                TravellerReasons.NO_VALID_ENDORSEMENT, TravellerReasons.NO_VALID_CREDENTIAL,
                TravellerReasons.DOCUMENT_EXPIRED, TravellerReasons.DOCUMENT_INVALID,
                TravellerReasons.OVERSTAY, TravellerReasons.COUNTRY_NOT_VISA_FREE,
                TravellerReasons.ALREADY_ENTERED
        };
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String key : keys) {
            assertTrue(key.startsWith(TravellerReasons.PREFIX), key);
            assertTrue(seen.add(key), "duplicate traveller key " + key);
        }
        assertFalse(TravellerReasons.OVERSTAY.equals(TravellerReasons.DOCUMENT_EXPIRED));
    }
}
