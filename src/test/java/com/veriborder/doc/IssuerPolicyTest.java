package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.2 document-side policy tests: locked names/numbers, the mainland ID layout
 * (region, sequence, gender parity, ISO 7064 MOD 11-2 check digit), the passport
 * MRZ issuing-country override, the permanent-code list carried by the issuer
 * form, and the "new document starts in the entry state" decision.
 */
class IssuerPolicyTest {
    /** ISO 7064 MOD 11-2 check digit used by the PRC resident ID number. */
    private static String mainlandCheck(String base17) {
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] checks = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (base17.charAt(i) - '0') * weights[i];
        }
        return String.valueOf(checks[sum % 11]);
    }

    @Test
    void mainlandNumberHonoursRegionSequenceAndGenderParity() {
        String male = DocumentFactory.generateNumber(DocStyle.CN_ID, "1988-07-14", "M", "310101", "42");
        assertEquals(18, male.length());
        assertEquals("310101", male.substring(0, 6), "region override");
        assertEquals("19880714", male.substring(6, 14), "birth date");
        assertEquals("42", male.substring(14, 16), "sequence override");
        assertTrue((male.charAt(16) - '0') % 2 == 1, "male gender digit is odd");
        assertEquals(mainlandCheck(male.substring(0, 17)), male.substring(17), "MOD 11-2 check digit");

        String female = DocumentFactory.generateNumber(DocStyle.CN_ID, "1995-11-02", "F", "440305", "07");
        assertEquals("440305", female.substring(0, 6));
        assertEquals("19951102", female.substring(6, 14));
        assertEquals("07", female.substring(14, 16));
        assertEquals(0, (female.charAt(16) - '0') % 2, "female gender digit is even");
        assertEquals(mainlandCheck(female.substring(0, 17)), female.substring(17));
    }

    @Test
    void mainlandNumberDefaultsToTheLayoutPrefixAndARandomSequence() {
        for (int i = 0; i < 25; i++) {
            boolean male = i % 2 == 0;
            String number = DocumentFactory.generateNumber(DocStyle.CN_ID, "1990-01-01", male ? "M" : "F");
            assertEquals(18, number.length());
            assertEquals(DocStyle.CN_ID.numberPrefix(), number.substring(0, 6), "default region");
            int sequence = Integer.parseInt(number.substring(14, 16));
            assertTrue(sequence >= 0 && sequence <= 99, "two-digit sequence");
            int gender = number.charAt(16) - '0';
            assertEquals(male, gender % 2 == 1, "gender parity follows the sex field");
            assertEquals(mainlandCheck(number.substring(0, 17)), number.substring(17));
        }
    }

    @Test
    void unusableRegionFallsBackToTheLayoutPrefix() {
        String number = DocumentFactory.generateNumber(DocStyle.CN_ID, "1990-01-01", "M", "12", "5");
        assertEquals(DocStyle.CN_ID.numberPrefix(), number.substring(0, 6));
        assertEquals("05", number.substring(14, 16), "a one-digit sequence is padded");
        assertEquals(mainlandCheck(number.substring(0, 17)), number.substring(17));
    }

    @Test
    void lockedDocumentNameIgnoresSubmittedTitleButKeepsHolderName() {
        CompoundTag form = new CompoundTag();
        form.putString("name", "ALEX MORGAN");
        form.putString("nameLatin", "MORGAN ALEX");
        form.putString("displayName", "FORGED TITLE");
        form.putBoolean("customStyle", true);
        form.putString("customName", "FORGED COVER");
        form.putBoolean("lockDocumentName", true);
        form.putString("birthDate", "1991-02-03");
        DocumentData doc = DocumentFactory.fromForm(DocStyle.CN_PASSPORT, form);
        // The holder's name is never locked.
        assertEquals("ALEX MORGAN", doc.name());
        // The document title is locked to the official preset.
        assertFalse(doc.hasCustomDisplayName());
        assertFalse(doc.customStyle());
        assertTrue(doc.integrityValid());
    }

    @Test
    void unlockedNameKeepsTheSubmittedName() {
        CompoundTag form = new CompoundTag();
        form.putString("name", "ALEX MORGAN");
        form.putString("nameLatin", "MORGAN ALEX");
        form.putString("birthDate", "1991-02-03");
        DocumentData doc = DocumentFactory.fromForm(DocStyle.CN_ID, form);
        assertEquals("ALEX MORGAN", doc.name());
        assertEquals("MORGAN ALEX", doc.nameLatin());
    }

    @Test
    void lockedNumberIgnoresTheSubmittedNumber() {
        CompoundTag form = new CompoundTag();
        form.putString("name", "ALEX MORGAN");
        form.putBoolean("lockNumber", true);
        form.putString("number", "123456789012345678");
        form.putString("numberRegion", "310101");
        form.putString("numberSequence", "42");
        form.putString("birthDate", "1991-02-03");
        form.putString("sex", "M");
        DocumentData doc = DocumentFactory.fromForm(DocStyle.CN_ID, form);
        assertNotEquals("123456789012345678", doc.number(), "a locked number is system-generated");
        assertEquals(18, doc.number().length());
        assertEquals("310101", doc.number().substring(0, 6));
        assertEquals("19910203", doc.number().substring(6, 14));
        assertEquals("42", doc.number().substring(14, 16));
        assertTrue((doc.number().charAt(16) - '0') % 2 == 1);
        assertTrue(doc.integrityValid());
    }

    @Test
    void unlockedNumberKeepsTheSubmittedNumber() {
        CompoundTag form = new CompoundTag();
        form.putString("name", "ALEX MORGAN");
        form.putString("number", "X1234567");
        form.putString("birthDate", "1991-02-03");
        DocumentData doc = DocumentFactory.fromForm(DocStyle.HK_MACAO_PERMIT, form);
        assertEquals("X1234567", doc.number());
    }

    @Test
    void mrzCountryOverrideAppearsInBothMrzLines() {
        String[] mrz = MrzUtil.generate(DocStyle.CN_PASSPORT, "E12345678", "ZHANG WEI",
                "1990-01-01", "M", "2030-01-01", "USA");
        assertEquals(44, mrz[0].length());
        assertEquals(44, mrz[1].length());
        assertTrue(mrz[0].startsWith("P<USA"), "line 1 carries the issuing country");
        assertEquals("USA", mrz[1].substring(10, 13), "line 2 repeats the issuing country");

        String[] blank = MrzUtil.generate(DocStyle.CN_PASSPORT, "E12345678", "ZHANG WEI",
                "1990-01-01", "M", "2030-01-01", "");
        assertTrue(blank[0].startsWith("P<" + DocStyle.CN_PASSPORT.mrzCountry()), "blank falls back to the layout");

        String[] unusable = MrzUtil.generate(DocStyle.CN_PASSPORT, "E12345678", "ZHANG WEI",
                "1990-01-01", "M", "2030-01-01", "12");
        assertTrue(unusable[0].startsWith("P<" + DocStyle.CN_PASSPORT.mrzCountry()),
                "an unusable override falls back to the layout");
        assertEquals("", MrzUtil.countryCode("12"));
        assertEquals("SGP", MrzUtil.countryCode(" sgp "));
    }

    @Test
    void passportMrzCountryIsStoredSignedAndUsedBySign() {
        DocumentData doc = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        assertEquals(DocStyle.CN_PASSPORT.mrzCountry(), doc.mrzCountry());

        doc.setMrzCountry("SGP");
        assertEquals("SGP", doc.mrzCountry());
        assertTrue(doc.mrz1().startsWith("P<SGP"));
        assertEquals("SGP", doc.mrz2().substring(10, 13));
        assertTrue(doc.integrityValid());

        CompoundTag tampered = doc.copyTag();
        tampered.putString("mrzCountry", "USA");
        assertFalse(DocumentData.fromTag(tampered).integrityValid(), "the country code is signed");
    }

    @Test
    void passportFormMrzCountryOverridesTheLayoutCode() {
        CompoundTag form = new CompoundTag();
        form.putString("name", "ALEX MORGAN");
        form.putString("birthDate", "1991-02-03");
        form.putString("mrzCountry", "gbr");
        DocumentData doc = DocumentFactory.fromForm(DocStyle.CN_PASSPORT, form);
        assertEquals("GBR", doc.mrzCountry());
        assertTrue(doc.mrz1().startsWith("P<GBR"));
        assertTrue(doc.integrityValid());
    }

    @Test
    void issueEntryStateOnlyStartsANewSessionWhenNoneExists() {
        assertTrue(DocumentFactory.shouldBeginIssueEntry(true, false));
        assertFalse(DocumentFactory.shouldBeginIssueEntry(true, true),
                "an existing border session is never overwritten");
        assertFalse(DocumentFactory.shouldBeginIssueEntry(false, false),
                "the toggle is off, so no entry record is created");
    }

    @Test
    void formPermanentCodesAreDedupedAndSigned() {
        CompoundTag form = new CompoundTag();
        form.putString("name", "ALEX MORGAN");
        form.putString("birthDate", "1991-02-03");
        ListTag codes = new ListTag();
        codes.add(StringTag.valueOf("00112233"));
        codes.add(StringTag.valueOf("00112233"));
        codes.add(StringTag.valueOf("invalid"));
        codes.add(StringTag.valueOf(" 87654321 "));
        form.put("visaFreeCodes", codes);

        DocumentData doc = DocumentFactory.fromForm(DocStyle.HK_MACAO_PERMIT, form);
        assertEquals(List.of("00112233", "87654321"), doc.visaFreeCodes(), "invalid and duplicate codes are dropped");
        assertTrue(doc.integrityValid());
        assertTrue(doc.allCredentialCodes().contains("00112233"));
        assertEquals(List.of("00112233", "87654321"), DocumentData.fromTag(doc.copyTag()).visaFreeCodes());
    }

    @Test
    void serverNationalityOverrideReplacesTheSubmittedNationality() {
        // DeviceService#issueDocument writes the terminal's locked nationality into
        // the form before DocumentFactory sees it. The factory must then store that
        // value (not the applicant's) and sign it like every other field.
        CompoundTag submitted = new CompoundTag();
        submitted.putString("name", "ALEX MORGAN");
        submitted.putString("birthDate", "1991-02-03");
        submitted.putString("nationality", "FORGED REPUBLIC");
        assertEquals("FORGED REPUBLIC",
                DocumentFactory.fromForm(DocStyle.CN_PASSPORT, submitted).nationality(),
                "without the lock the submitted value would survive");

        CompoundTag enforced = submitted.copy();
        enforced.putString("nationality", "中国");
        DocumentData doc = DocumentFactory.fromForm(DocStyle.CN_PASSPORT, enforced);
        assertEquals("中国", doc.nationality(), "the server-side override is the written nationality");
        assertTrue(doc.integrityValid());

        CompoundTag tampered = doc.copyTag();
        tampered.putString("nationality", "FORGED REPUBLIC");
        assertFalse(DocumentData.fromTag(tampered).integrityValid(), "the nationality is signed");
    }

    @Test
    void blankNationalityFallsBackToTheLayoutDefault() {
        CompoundTag form = new CompoundTag();
        form.putString("name", "ALEX MORGAN");
        form.putString("birthDate", "1991-02-03");
        form.putString("nationality", "   ");
        DocumentData doc = DocumentFactory.fromForm(DocStyle.HK_MACAO_PERMIT, form);
        assertEquals(DocStyle.HK_MACAO_PERMIT.defaultNationality(), doc.nationality(),
                "a blank locked nationality is a no-op, so the layout default applies");
    }
}
