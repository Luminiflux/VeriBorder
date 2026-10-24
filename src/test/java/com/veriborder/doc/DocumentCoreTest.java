package com.veriborder.doc;

import com.veriborder.block.DeviceBlockEntity;
import com.veriborder.block.DeviceType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentCoreTest {
    @Test
    void everyOfficialLayoutIsValidAndSigned() {
        for (DocStyle style : DocStyle.values()) {
            DocumentData doc = DocumentFactory.sample(style);
            assertTrue(doc.integrityValid(), style + " integrity");
            assertEquals(style, doc.style());
            assertEquals(style.kind(), doc.kind());
            assertFalse(doc.number().isBlank(), style + " number");
            assertFalse(doc.name().isBlank(), style + " name");
            if (style == DocStyle.HK_ID) {
                assertTrue(doc.isPermanent(), "HK permanent ID");
            }
            if (style.kind() == DocumentKind.PASSPORT) {
                assertEquals(44, doc.mrz1().length());
                assertEquals(44, doc.mrz2().length());
            }
        }
    }

    @Test
    void mainlandIdentityNumberUsesTheOfficialCheckDigit() {
        String number = DocumentFactory.generateNumber(DocStyle.CN_ID, "1990-01-01", "M");
        assertEquals(18, number.length());
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] checks = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (number.charAt(i) - '0') * weights[i];
        }
        assertEquals(checks[sum % 11], number.charAt(17));
    }

    @Test
    void hongKongIdentityNumberMatchesItsCheckDigit() {
        String number = DocumentFactory.generateNumber(DocStyle.HK_ID, "1995-11-02", "F");
        assertEquals(10, number.length());
        assertEquals('(', number.charAt(7));
        char check = number.charAt(8);
        int[] weights = {9, 8, 7, 6, 5, 4, 3};
        int sum = 0;
        for (int i = 0; i < 7; i++) {
            char c = number.charAt(i);
            int value = i == 0 ? (c - 'A' + 1) : (c - '0');
            sum += value * weights[i];
        }
        int expected = 11 - (sum % 11);
        char expectedChar = expected == 10 ? 'A' : expected == 11 ? '0' : (char) ('0' + expected);
        assertEquals(expectedChar, check);
    }

    @Test
    void tamperingBreaksIntegrityAndIsRejected() {
        DocumentData doc = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        CompoundTag copy = doc.copyTag();
        copy.putString("name", "ATTACKER");
        DocumentData tampered = DocumentData.fromTag(copy);
        assertFalse(tampered.integrityValid());
        VerificationResult result = DocumentValidator.validate(tampered, DeviceType.HOTEL_READER, "");
        assertFalse(result.valid());
        assertEquals(VerificationResult.Code.TAMPERED, result.code());
    }

    @Test
    void airportRequiresValidVisaAndDestinationMatch() {
        DocumentData passport = DocumentFactory.sample(DocStyle.US_PASSPORT);
        assertTrue(DocumentValidator.validate(passport, DeviceType.AIRPORT_CHECKIN, "United States").valid());
        VerificationResult mismatch = DocumentValidator.validate(passport, DeviceType.AIRPORT_CHECKIN, "Japan");
        assertFalse(mismatch.valid());
        assertEquals(VerificationResult.Code.NO_MATCHING_VISA, mismatch.code());

        passport.clearVisas();
        VerificationResult noVisa = DocumentValidator.validate(passport, DeviceType.AIRPORT_CHECKIN, "");
        assertFalse(noVisa.valid());
        assertEquals(VerificationResult.Code.NO_VISA, noVisa.code());
    }

    @Test
    void stationRejectsPassportAndAcceptsIdentityCard() {
        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        assertEquals(VerificationResult.Code.WRONG_KIND,
                DocumentValidator.validate(passport, DeviceType.STATION_VERIFIER, "").code());

        DocumentData id = DocumentFactory.sample(DocStyle.CN_ID);
        assertTrue(DocumentValidator.validate(id, DeviceType.STATION_VERIFIER, "").valid());
    }

    @Test
    void permitRequiresMatchingEndorsementCodeAtEGate() {
        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        assertFalse(permit.endorsements().isEmpty());
        String code = permit.endorsements().get(0).code();
        assertTrue(DocumentValidator.validateGate(permit, DeviceType.EGATE, "endorsement", List.of(code)).valid());
        permit.clearEndorsements();
        VerificationResult result = DocumentValidator.validateGate(permit, DeviceType.EGATE,
                "endorsement", List.of(code));
        assertFalse(result.valid());
        assertEquals(VerificationResult.Code.NO_MATCHING_CODE, result.code());
        assertTrue(result.reasons().contains(DocumentValidator.REASON_GATE_CODE_MISMATCH));
    }

    @Test
    void blacklistedAndExpiredDocumentsAreRejected() {
        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        passport.setStatus(DocumentStatus.LOST);
        assertEquals(VerificationResult.Code.LOST,
                DocumentValidator.validate(passport, DeviceType.HOTEL_READER, "").code());

        DocumentData id = DocumentFactory.sample(DocStyle.CN_ID);
        id.setIssueDate(LocalDate.now().minusYears(2).toString());
        id.setExpiryDate(LocalDate.now().minusDays(1).toString());
        assertEquals(VerificationResult.Code.EXPIRED,
                DocumentValidator.validate(id, DeviceType.HOTEL_READER, "").code());
    }

    @Test
    void visasAndEndorsementsRespectDatesAndRemainingEntries() {
        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        passport.clearVisas();
        CompoundTag expired = DocumentFactory.createVisa("Japan", VisaType.TOURIST, 1,
                "2000-01-01", "2001-01-01", "Embassy");
        passport.addVisa(expired);
        assertFalse(passport.visas().get(0).active());
        assertEquals(VerificationResult.Code.NO_MATCHING_CODE,
                DocumentValidator.validateGate(passport, DeviceType.EGATE, "visa",
                        List.of(passport.visas().get(0).code())).code(),
                "an expired visa code must not open the gate");

        passport.clearVisas();
        CompoundTag exhausted = DocumentFactory.createVisa("Japan", VisaType.TOURIST, 1,
                DateUtil.today(), DateUtil.plusYears(DateUtil.today(), 1), "Embassy");
        exhausted.putInt("usedEntries", 1);
        passport.addVisa(exhausted);
        assertFalse(passport.visas().get(0).active());
        assertEquals(VerificationResult.Code.NO_MATCHING_CODE,
                DocumentValidator.validateGate(passport, DeviceType.EGATE, "visa",
                        List.of(passport.visas().get(0).code())).code(),
                "an exhausted visa code must not open the gate");
    }

    @Test
    void structurallyEmptyOrFutureIssuedDocumentsAreRejected() {
        DocumentData blank = DocumentData.createEmpty(DocStyle.CN_ID);
        assertEquals(VerificationResult.Code.MALFORMED,
                DocumentValidator.validate(blank, DeviceType.HOTEL_READER, "").code());

        DocumentData future = DocumentFactory.sample(DocStyle.CN_ID);
        future.setIssueDate(LocalDate.now().plusDays(3).toString());
        assertEquals(VerificationResult.Code.MALFORMED,
                DocumentValidator.validate(future, DeviceType.HOTEL_READER, "").code());
    }

    @Test
    void customCoverAndDocumentTypeAreStoredAndSigned() {
        DocumentData doc = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        doc.applyCustomStyle("Diplomatic Passport", "DIPLOMATIC", "DIPLOMATIC PASSPORT", 0x2A4D69);
        assertTrue(doc.customStyle());
        assertEquals("Diplomatic Passport", doc.displayName());
        assertEquals("DIPLOMATIC", doc.shortName());
        assertEquals("DIPLOMATIC PASSPORT", doc.documentType());
        assertEquals(0x2A4D69, doc.accentColor());
        assertEquals(100, doc.itemModelData());
        assertTrue(doc.integrityValid());
        assertTrue(DocumentValidator.validate(doc, DeviceType.HOTEL_READER, "").valid());

        doc.clearCustomStyle();
        assertFalse(doc.customStyle());
        assertEquals(doc.style().accentColor(), doc.accentColor());
        assertTrue(doc.integrityValid());
    }

    @Test
    void customStyleCanBeCreatedDirectlyFromForm() {
        CompoundTag form = new CompoundTag();
        form.putString("name", "ALEX MORGAN");
        form.putString("birthDate", "1991-02-03");
        form.putBoolean("customStyle", true);
        form.putString("customName", "Seafarer Identity Book");
        form.putString("customShortName", "SIB");
        form.putString("customType", "MARITIME IDENTITY DOCUMENT");
        form.putInt("customColor", 0x146B7A);
        DocumentData doc = DocumentFactory.fromForm(DocStyle.CN_ID, form);
        assertTrue(doc.customStyle());
        assertEquals("Seafarer Identity Book", doc.displayName());
        assertEquals("MARITIME IDENTITY DOCUMENT", doc.documentType());
        assertEquals(0x146B7A, doc.accentColor());
        assertEquals(100, doc.itemModelData());
        assertTrue(doc.integrityValid());
    }

    @Test
    void nbtRoundTripPreservesEverything() {
        DocumentData original = DocumentFactory.sample(DocStyle.HK_PASSPORT);
        CompoundTag copy = original.copyTag();
        DocumentData loaded = DocumentData.fromTag(copy);
        assertTrue(loaded.integrityValid());
        assertEquals(original.number(), loaded.number());
        assertEquals(original.visas().size(), loaded.visas().size());
        assertEquals(original.stamps().size(), loaded.stamps().size());
        assertNotNull(loaded.mrz2());
        assertTrue(loaded.isRecordUsable());
    }

    @Test
    void displayNameOverrideWinsAndSurvivesNbtRoundTrip() {
        DocumentData doc = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        String official = doc.displayName();
        assertFalse(doc.hasCustomDisplayName());
        assertEquals(official, doc.displayName());

        doc.setDisplayName("Diplomatic Courier Passport");
        assertTrue(doc.hasCustomDisplayName());
        assertEquals("Diplomatic Courier Passport", doc.displayName());
        assertEquals("Diplomatic Courier Passport", doc.displayNameComponent().getString());
        assertTrue(doc.integrityValid());

        DocumentData loaded = DocumentData.fromTag(doc.copyTag());
        assertTrue(loaded.hasCustomDisplayName());
        assertEquals("Diplomatic Courier Passport", loaded.displayName());
        assertTrue(loaded.integrityValid());
        assertTrue(DocumentValidator.validate(loaded, DeviceType.HOTEL_READER, "").valid());
    }

    @Test
    void blankDisplayNameFallsBackToCoverThenOfficialStyle() {
        DocumentData doc = DocumentFactory.sample(DocStyle.CN_ID);
        String official = doc.displayName();

        doc.setDisplayName("Border Service Identity Card");
        assertEquals("Border Service Identity Card", doc.displayName());

        // Clearing the override on an official document falls back to the style name.
        doc.setDisplayName("   ");
        assertFalse(doc.hasCustomDisplayName());
        assertEquals(official, doc.displayName());
        assertTrue(doc.integrityValid());

        // With a custom cover, clearing the override falls back to the cover name.
        doc.applyCustomStyle("Custom Cover", "COVER", "CUSTOM TYPE", 0x123456);
        assertEquals("Custom Cover", doc.displayName());
        doc.setDisplayName("Border Service Identity Card");
        assertEquals("Border Service Identity Card", doc.displayName());

        // Applying/clearing the custom cover must never delete the display name.
        doc.clearCustomStyle();
        assertFalse(doc.customStyle());
        assertEquals("Border Service Identity Card", doc.displayName());
        assertEquals("Border Service Identity Card", doc.displayNameComponent().getString());
        assertTrue(doc.integrityValid());
    }

    @Test
    void displayNameFormFieldIsAppliedByTheFactory() {
        CompoundTag officialForm = new CompoundTag();
        officialForm.putString("name", "ALEX MORGAN");
        officialForm.putString("birthDate", "1991-02-03");
        officialForm.putString("displayName", "Special Border Pass");
        DocumentData official = DocumentFactory.fromForm(DocStyle.CN_ID, officialForm);
        assertFalse(official.customStyle());
        assertEquals("Special Border Pass", official.displayName());
        assertTrue(official.integrityValid());

        CompoundTag customForm = new CompoundTag();
        customForm.putString("name", "ALEX MORGAN");
        customForm.putString("birthDate", "1991-02-03");
        customForm.putBoolean("customStyle", true);
        customForm.putString("customName", "Custom Cover");
        customForm.putString("displayName", "  ");
        DocumentData custom = DocumentFactory.fromForm(DocStyle.CN_ID, customForm);
        assertTrue(custom.customStyle());
        assertEquals("Custom Cover", custom.displayName());
        assertFalse(custom.hasCustomDisplayName());
        assertTrue(custom.integrityValid());
    }

    @Test
    void editingDisplayNameWithoutResigningBreaksIntegrity() {
        DocumentData doc = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        CompoundTag tag = doc.copyTag();
        tag.putString("displayName", "FORGED NAME");
        assertFalse(DocumentData.fromTag(tag).integrityValid());
    }

    @Test
    void gateVisaModeRequiresAPassportWithAMatchingCode() {
        DocumentData passport = DocumentFactory.sample(DocStyle.US_PASSPORT);
        String code = passport.visas().get(0).code();
        VerificationResult match = DocumentValidator.validateGate(passport, DeviceType.EGATE, "visa",
                List.of("00000000", code));
        assertTrue(match.valid());
        assertEquals(VerificationResult.Code.OK, match.code());
        assertEquals(code, match.detail(), "detail carries the matched code");

        VerificationResult mismatch = DocumentValidator.validateGate(passport, DeviceType.EGATE, "visa",
                List.of("99999999"));
        assertFalse(mismatch.valid());
        assertEquals(VerificationResult.Code.NO_MATCHING_CODE, mismatch.code());
        assertTrue(mismatch.reasons().contains(DocumentValidator.REASON_GATE_CODE_MISMATCH));
        assertEquals("visa", mismatch.detail(), "detail names the required mode");

        DocumentData idCard = DocumentFactory.sample(DocStyle.CN_ID);
        VerificationResult wrongKind = DocumentValidator.validateGate(idCard, DeviceType.EGATE, "visa",
                List.of(code));
        assertEquals(VerificationResult.Code.WRONG_KIND, wrongKind.code());
        assertTrue(wrongKind.reasons().contains(DocumentValidator.REASON_REQUIRES_PASSPORT));

        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        assertEquals(VerificationResult.Code.WRONG_KIND,
                DocumentValidator.validateGate(permit, DeviceType.EGATE, "visa",
                        List.of(permit.endorsements().get(0).code())).code());
    }

    @Test
    void gateEndorsementModeRequiresAPermitWithAMatchingCode() {
        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        String code = permit.endorsements().get(0).code();
        VerificationResult match = DocumentValidator.validateGate(permit, DeviceType.EGATE, "endorsement",
                List.of(code));
        assertTrue(match.valid());
        assertEquals(code, match.detail(), "detail carries the matched code");

        VerificationResult mismatch = DocumentValidator.validateGate(permit, DeviceType.EGATE, "endorsement",
                List.of("99999999"));
        assertFalse(mismatch.valid());
        assertEquals(VerificationResult.Code.NO_MATCHING_CODE, mismatch.code());
        assertTrue(mismatch.reasons().contains(DocumentValidator.REASON_GATE_CODE_MISMATCH));
        assertEquals("endorsement", mismatch.detail());

        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        VerificationResult wrongKind = DocumentValidator.validateGate(passport, DeviceType.EGATE,
                "endorsement", List.of(passport.visas().get(0).code()));
        assertEquals(VerificationResult.Code.WRONG_KIND, wrongKind.code());
        assertTrue(wrongKind.reasons().contains(DocumentValidator.REASON_REQUIRES_PERMIT));
    }

    @Test
    void exitGateOnlyChecksDocumentValidity() {
        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        passport.clearVisas();
        assertTrue(DocumentValidator.validate(passport, DeviceType.EXIT_GATE, "").valid(),
                "exit must not require a visa");
        assertTrue(DocumentValidator.validateGate(passport, DeviceType.EXIT_GATE,
                "visa", List.of("12345678")).valid(), "exit ignores gate codes");

        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        permit.clearEndorsements();
        assertTrue(DocumentValidator.validate(permit, DeviceType.EXIT_GATE, "").valid(),
                "exit must not require an endorsement");
        assertTrue(DocumentValidator.validateGate(permit, DeviceType.EXIT_GATE,
                "endorsement", List.of("12345678")).valid(), "exit ignores gate codes");

        assertTrue(DocumentValidator.validate(DocumentFactory.sample(DocStyle.CN_ID),
                DeviceType.EXIT_GATE, "").valid());

        DocumentData lost = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        lost.clearVisas();
        lost.setStatus(DocumentStatus.LOST);
        assertEquals(VerificationResult.Code.LOST,
                DocumentValidator.validate(lost, DeviceType.EXIT_GATE, "").code(),
                "exit still rejects a blacklisted document");

        assertTrue(DeviceType.EXIT_GATE.isGate());
        assertFalse(DeviceType.SELF_ENDORSEMENT.isGate());
    }

    @Test
    void gateModesRejectWhenNoCodesAreConfigured() {
        List<DocumentData> documents = List.of(
                DocumentFactory.sample(DocStyle.CN_PASSPORT),
                DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT),
                DocumentFactory.sample(DocStyle.CN_ID));
        for (String mode : List.of("visa", "endorsement", "both")) {
            for (DocumentData doc : documents) {
                VerificationResult result = DocumentValidator.validateGate(doc, DeviceType.EGATE, mode, List.of());
                assertFalse(result.valid(), mode + " without codes");
                assertEquals(VerificationResult.Code.NO_MATCHING_CODE, result.code(), mode);
                assertTrue(result.reasons().contains(DocumentValidator.REASON_GATE_NO_CODES), mode);
                assertEquals(mode, result.detail());

                VerificationResult invalidOnly = DocumentValidator.validateGate(doc, DeviceType.EGATE, mode,
                        List.of("12", "abcdefgh", "1234567"));
                assertEquals(VerificationResult.Code.NO_MATCHING_CODE, invalidOnly.code(), mode);
                assertTrue(invalidOnly.reasons().contains(DocumentValidator.REASON_GATE_NO_CODES), mode);
            }
        }
    }

    @Test
    void selfServiceKioskRequiresPermits() {
        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        assertTrue(DocumentValidator.validate(permit, DeviceType.SELF_ENDORSEMENT, "").valid());
        assertEquals(VerificationResult.Code.WRONG_KIND,
                DocumentValidator.validate(DocumentFactory.sample(DocStyle.CN_PASSPORT),
                        DeviceType.SELF_ENDORSEMENT, "").code());
        assertEquals(DeviceType.ScreenKind.ENDORSEMENT, DeviceType.SELF_ENDORSEMENT.screen());
    }

    @Test
    void legacyTeamAndIndividualEndorsementIdsMapToOther() {
        assertEquals(EndorsementType.OTHER, EndorsementType.byId("team"));
        assertEquals(EndorsementType.OTHER, EndorsementType.byId("individual"));
        assertEquals(EndorsementType.OTHER, EndorsementType.byId("TEAM"));
        assertEquals(EndorsementType.ONE_YEAR_MULTI, EndorsementType.byId("one_year_multi"));
        assertEquals(EndorsementType.THREE_MONTH_SINGLE, EndorsementType.byId("three_month_single"));
        assertEquals(3, EndorsementType.values().length);
        assertTrue(EndorsementType.ONE_YEAR_MULTI.translationKey().startsWith("endorsement.type.veriborder."));

        CompoundTag legacy = DocumentFactory.createEndorsement(EndorsementType.OTHER, 1,
                DateUtil.today(), DateUtil.plusYears(DateUtil.today(), 1), "Authority");
        legacy.putString("type", "individual");
        assertEquals(EndorsementType.OTHER, EndorsementRecord.fromTag(legacy).type());
    }

    @Test
    void credentialCodeValidationGenerationAndUniqueness() {
        assertTrue(CredentialCode.isValid("00000000"), "leading zeros are valid");
        assertTrue(CredentialCode.isValid("99999999"));
        assertFalse(CredentialCode.isValid("1234567"));
        assertFalse(CredentialCode.isValid("123456789"));
        assertFalse(CredentialCode.isValid("1234567a"));
        assertFalse(CredentialCode.isValid(" 12345678"));
        assertFalse(CredentialCode.isValid(null));

        for (int i = 0; i < 200; i++) {
            String code = CredentialCode.random();
            assertEquals(8, code.length());
            assertTrue(CredentialCode.isValid(code), code);
        }

        List<String> used = new ArrayList<>(List.of("00000000", "00000001", "12345678"));
        for (int i = 0; i < 50; i++) {
            String unique = CredentialCode.generateUnique(used);
            assertTrue(CredentialCode.isValid(unique));
            assertFalse(used.contains(unique));
            used.add(unique);
        }

        assertEquals(List.of("00000000", "12345678"),
                CredentialCode.normalizeAll(List.of(" 00000000", "12345678", "00000000", "abc", "")));
        assertEquals("12345678", CredentialCode.sanitize(" 12345678 "));
        assertEquals("", CredentialCode.sanitize("1234"));
    }

    @Test
    void legacyCodesAreDeterministicAndEightDigits() {
        String first = CredentialCode.legacy("V-000123");
        assertEquals(first, CredentialCode.legacy("V-000123"), "legacy codes must be stable");
        assertEquals(first, CredentialCode.legacy(" V-000123 "));
        assertTrue(CredentialCode.isValid(first));
        assertNotEquals(first, CredentialCode.legacy("V-000124"));
        assertEquals(CredentialCode.ZERO, CredentialCode.legacy(""));
        assertEquals(CredentialCode.ZERO, CredentialCode.legacy(null));
    }

    @Test
    void visaCodesRoundTripAndLegacyFallback() {
        CompoundTag created = DocumentFactory.createVisa("Japan", VisaType.TOURIST, 2, DateUtil.today(),
                DateUtil.plusYears(DateUtil.today(), 1), "Embassy", "00123456");
        VisaRecord record = VisaRecord.fromTag(created);
        assertEquals("00123456", record.code());
        assertEquals("00123456", record.toTag().getString("code"));
        assertEquals("00123456", VisaRecord.fromTag(record.toTag()).code());

        String id = created.getString("id");
        CompoundTag legacy = created.copy();
        legacy.remove("code");
        VisaRecord legacyRecord = VisaRecord.fromTag(legacy);
        assertEquals(CredentialCode.legacy(id), legacyRecord.code());
        assertEquals(legacyRecord.code(), VisaRecord.fromTag(legacyRecord.toTag()).code(),
                "saving a legacy record persists its derived code");
        assertEquals(CredentialCode.legacy(id), new VisaRecord(id, "Japan", VisaType.TOURIST, 2, 0,
                DateUtil.today(), DateUtil.plusYears(DateUtil.today(), 1), "Embassy", VisaRecord.ACTIVE,
                "not-a-code").code());

        VisaRecord anonymous = new VisaRecord("", "Japan", VisaType.TOURIST, 2, 0, DateUtil.today(),
                DateUtil.plusYears(DateUtil.today(), 1), "Embassy", VisaRecord.ACTIVE, "");
        assertTrue(CredentialCode.isValid(anonymous.code()));
    }

    @Test
    void endorsementCodesRoundTripAndLegacyFallback() {
        CompoundTag created = DocumentFactory.createEndorsement(EndorsementType.ONE_YEAR_MULTI, 0,
                DateUtil.today(), DateUtil.plusYears(DateUtil.today(), 1), "Authority", "00012345");
        EndorsementRecord record = EndorsementRecord.fromTag(created);
        assertEquals("00012345", record.code());
        assertEquals("00012345", EndorsementRecord.fromTag(record.toTag()).code());

        String id = created.getString("id");
        CompoundTag legacy = created.copy();
        legacy.remove("code");
        assertEquals(CredentialCode.legacy(id), EndorsementRecord.fromTag(legacy).code());
    }

    @Test
    void visaFreeCodesAreValidatedDedupedSignedAndPersisted() {
        DocumentData doc = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        assertTrue(doc.visaFreeCodes().isEmpty());

        assertFalse(doc.addVisaFreeCode("123"));
        assertFalse(doc.addVisaFreeCode("1234567a"));
        assertFalse(doc.addVisaFreeCode(""));
        assertTrue(doc.addVisaFreeCode("00123456"));
        assertFalse(doc.addVisaFreeCode("00123456"), "duplicates are ignored");
        assertTrue(doc.addVisaFreeCode(" 87654321 "));
        assertEquals(List.of("00123456", "87654321"), doc.visaFreeCodes());
        assertTrue(doc.integrityValid());

        DocumentData loaded = DocumentData.fromTag(doc.copyTag());
        assertEquals(doc.visaFreeCodes(), loaded.visaFreeCodes());
        assertTrue(loaded.integrityValid());

        assertTrue(loaded.removeVisaFreeCode("00123456"));
        assertFalse(loaded.removeVisaFreeCode("00123456"));
        assertTrue(loaded.integrityValid());
        assertEquals(List.of("87654321"), loaded.visaFreeCodes());

        loaded.clearVisaFreeCodes();
        assertTrue(loaded.visaFreeCodes().isEmpty());
        assertTrue(loaded.integrityValid());
        DocumentData reloaded = DocumentData.fromTag(loaded.copyTag());
        assertTrue(reloaded.visaFreeCodes().isEmpty());
        assertTrue(reloaded.integrityValid());
    }

    @Test
    void documentsWithoutAPermanentCodeListStillVerify() {
        DocumentData doc = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        CompoundTag tag = doc.copyTag();
        tag.remove("permanentCodes");
        tag.remove("visaFreeCodes");
        DocumentIntegrity.sign(tag);
        DocumentData legacy = DocumentData.fromTag(tag);
        assertTrue(legacy.integrityValid(), "reading an old tag must not invalidate its signature");
        assertTrue(legacy.permanentCodes().isEmpty());
        assertTrue(legacy.visaFreeCodes().isEmpty());
        // Without a permanent code a code-less gate still requires a credential.
        assertFalse(DocumentValidator.validateGate(legacy, DeviceType.EGATE, "both", List.of()).valid());

        assertTrue(legacy.addVisaFreeCode("12345678"));
        assertTrue(legacy.integrityValid());
        assertFalse(legacy.copyTag().getList("permanentCodes", Tag.TAG_COMPOUND).isEmpty(),
                "26.3 stores permanent codes as compounds, not as the legacy string list");
        assertFalse(legacy.copyTag().contains("visaFreeCodes"),
                "the legacy list is folded into the new structure");
    }

    @Test
    void tamperedPermanentCodesBreakIntegrity() {
        DocumentData doc = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        assertTrue(doc.addVisaFreeCode("12345678"));
        CompoundTag tampered = doc.copyTag();
        CompoundTag forged = new CompoundTag();
        forged.putString("code", "99999999");
        forged.putString("issuerCountry", "");
        forged.putString("purpose", DocumentData.PermanentPurpose.VISA_FREE.id());
        tampered.getList("permanentCodes", Tag.TAG_COMPOUND).add(forged);
        assertFalse(DocumentData.fromTag(tampered).integrityValid());
    }

    @Test
    void addVisaAssignsUniqueCodesAcrossABatch() {
        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        passport.clearVisas();
        passport.clearVisaFreeCodes();
        for (int i = 0; i < 40; i++) {
            CompoundTag visa = DocumentFactory.createVisa("Japan", VisaType.TOURIST, 1, DateUtil.today(),
                    DateUtil.plusYears(DateUtil.today(), 1), "Embassy");
            visa.remove("code");
            passport.addVisa(visa);
        }
        List<String> codes = passport.visas().stream().map(VisaRecord::code).toList();
        assertEquals(40, codes.size());
        assertEquals(40, new HashSet<>(codes).size(), "every auto code is distinct");
        assertEquals(40, passport.allCredentialCodes().size());
        assertTrue(passport.integrityValid());

        passport.addVisa(DocumentFactory.createVisa("Japan", VisaType.TOURIST, 1, DateUtil.today(),
                DateUtil.plusYears(DateUtil.today(), 1), "Embassy", "00001234"));
        assertEquals("00001234", passport.visas().get(40).code(), "an explicit valid code is kept");
    }

    @Test
    void addEndorsementAssignsUniqueCodes() {
        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        permit.clearEndorsements();
        for (int i = 0; i < 30; i++) {
            CompoundTag endorsement = DocumentFactory.createEndorsement(EndorsementType.ONE_YEAR_MULTI,
                    EndorsementType.ONE_YEAR_MULTI.defaultEntries(), DateUtil.today(),
                    DocumentFactory.endorsementExpiry(EndorsementType.ONE_YEAR_MULTI, DateUtil.today()),
                    "Authority");
            endorsement.remove("code");
            permit.addEndorsement(endorsement);
        }
        List<String> codes = permit.endorsements().stream().map(EndorsementRecord::code).toList();
        assertEquals(30, codes.size());
        assertEquals(30, new HashSet<>(codes).size());
        assertTrue(permit.integrityValid());
    }

    @Test
    void updateVisaGeneratesAMissingCode() {
        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        passport.clearVisas();
        passport.addVisa(DocumentFactory.createVisa("Japan", VisaType.TOURIST, 1, DateUtil.today(),
                DateUtil.plusYears(DateUtil.today(), 1), "Embassy", "11112222"));
        assertEquals("11112222", passport.visas().get(0).code());

        CompoundTag edited = passport.visaTags().get(0);
        edited.remove("code");
        passport.updateVisa(0, edited);

        String code = passport.visas().get(0).code();
        assertTrue(CredentialCode.isValid(code));
        assertNotEquals("11112222", code);
        assertTrue(passport.integrityValid());
        assertEquals(code, passport.visaTags().get(0).getString("code"), "the generated code is stored");
    }

    @Test
    void gateWithoutACredentialNeverOpens() {
        // 26.3 removed the "all" mode: the legacy value loads as both, so a
        // document with no matching visa/endorsement/permanent code is refused.
        DocumentData id = DocumentFactory.sample(DocStyle.CN_ID);
        assertEquals(VerificationResult.Code.NO_MATCHING_CODE,
                DocumentValidator.validateGate(id, DeviceType.EGATE, "all", List.of()).code());
        assertEquals(VerificationResult.Code.NO_MATCHING_CODE,
                DocumentValidator.validateGate(id, DeviceType.EGATE, "any", List.of()).code());
        assertEquals(VerificationResult.Code.NO_MATCHING_CODE,
                DocumentValidator.validateGate(id, DeviceType.EGATE, "", List.of()).code());

        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        passport.clearVisas();
        assertEquals(VerificationResult.Code.NO_MATCHING_CODE,
                DocumentValidator.validateGate(passport, DeviceType.EGATE, "all", List.of()).code(),
                "the removed all mode does not bypass the visa requirement");

        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        permit.clearEndorsements();
        assertEquals(VerificationResult.Code.NO_MATCHING_CODE,
                DocumentValidator.validateGate(permit, DeviceType.EGATE, "all", List.of()).code(),
                "the removed all mode does not bypass the endorsement requirement");

        // The normal document checks still run first and win over the mode.
        DocumentData lost = DocumentFactory.sample(DocStyle.CN_ID);
        lost.setStatus(DocumentStatus.LOST);
        assertEquals(VerificationResult.Code.LOST,
                DocumentValidator.validateGate(lost, DeviceType.EGATE, "all", List.of("12345678")).code());

        DocumentData expired = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        expired.setIssueDate(LocalDate.now().minusYears(2).toString());
        expired.setExpiryDate(LocalDate.now().minusDays(1).toString());
        assertEquals(VerificationResult.Code.EXPIRED,
                DocumentValidator.validateGate(expired, DeviceType.EGATE, "all", List.of()).code());
    }

    @Test
    void gateVisaModeMatchesAVisaFreeCode() {
        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        passport.clearVisas();
        assertTrue(passport.addVisaFreeCode("00112233"));
        assertTrue(DocumentValidator.validateGate(passport, DeviceType.EGATE, "visa",
                List.of("00112233")).valid());

        // 26.2 permanent-code slot: a non-empty list is accepted even when the
        // gate's configured code does not match (returning to the issuing country).
        VerificationResult permanent = DocumentValidator.validateGate(passport, DeviceType.EGATE, "visa",
                List.of("00112234"));
        assertTrue(permanent.valid(), "a permanent code opens the visa gate");
        assertEquals("00112233", permanent.detail(), "detail carries the permanent code");

        passport.clearVisaFreeCodes();
        assertEquals(VerificationResult.Code.NO_MATCHING_CODE,
                DocumentValidator.validateGate(passport, DeviceType.EGATE, "visa",
                        List.of("00112233")).code(), "removed visa-free codes no longer match");
    }

    @Test
    void gateBothModeAcceptsVisaOrEndorsement() {
        DocumentData passport = DocumentFactory.sample(DocStyle.US_PASSPORT);
        assertTrue(DocumentValidator.validateGate(passport, DeviceType.EGATE, "both",
                List.of(passport.visas().get(0).code())).valid());

        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        assertTrue(DocumentValidator.validateGate(permit, DeviceType.EGATE, "both",
                List.of(permit.endorsements().get(0).code())).valid());

        VerificationResult wrongCode = DocumentValidator.validateGate(passport, DeviceType.EGATE, "both",
                List.of("99999999"));
        assertEquals(VerificationResult.Code.NO_MATCHING_CODE, wrongCode.code());

        DocumentData id = DocumentFactory.sample(DocStyle.CN_ID);
        VerificationResult wrongKind = DocumentValidator.validateGate(id, DeviceType.EGATE, "both",
                List.of("12345678"));
        assertEquals(VerificationResult.Code.WRONG_KIND, wrongKind.code());
        assertTrue(wrongKind.reasons().contains(DocumentValidator.REASON_REQUIRES_VISA_OR_ENDORSEMENT));
        assertEquals("both", wrongKind.detail());
    }

    @Test
    void gateModesNormalizeLegacyNames() {
        // 26.3: every unknown/removed mode normalises to both (never "accept all").
        assertEquals("both", DocumentValidator.normalizeMode("any"));
        assertEquals("both", DocumentValidator.normalizeMode("ALL"));
        assertEquals("both", DocumentValidator.normalizeMode("all"));
        assertEquals("both", DocumentValidator.normalizeMode(null));
        assertEquals("both", DocumentValidator.normalizeMode(""));
        assertEquals("both", DocumentValidator.normalizeMode("nonsense"));
        assertEquals("visa", DocumentValidator.normalizeMode("passport"));
        assertEquals("visa", DocumentValidator.normalizeMode("Visa"));
        assertEquals("endorsement", DocumentValidator.normalizeMode("permit"));
        assertEquals("both", DocumentValidator.normalizeMode("both"));

        assertEquals("both", DeviceBlockEntity.normalizeGateMode("any"));
        assertEquals("both", DeviceBlockEntity.normalizeGateMode("all"));
        assertEquals("both", DeviceBlockEntity.normalizeGateMode(""));
        assertEquals("both", DeviceBlockEntity.normalizeGateMode(null));
        assertEquals("visa", DeviceBlockEntity.normalizeGateMode("passport"));
        assertEquals("endorsement", DeviceBlockEntity.normalizeGateMode("permit"));
        assertEquals("both", DeviceBlockEntity.normalizeGateMode("both"));

        DocumentData passport = DocumentFactory.sample(DocStyle.US_PASSPORT);
        assertTrue(DocumentValidator.validateGate(passport, DeviceType.EGATE, "passport",
                List.of(passport.visas().get(0).code())).valid(),
                "legacy passport mode behaves as visa mode");
    }

    @Test
    void documentFactoryCodeOverloadsArePreserved() {
        CompoundTag visa = DocumentFactory.createVisa("Japan", VisaType.TOURIST, 2, DateUtil.today(),
                DateUtil.plusYears(DateUtil.today(), 1), "Embassy", "00112233");
        assertEquals("00112233", visa.getString("code"));
        assertTrue(CredentialCode.isValid(DocumentFactory.createVisa("Japan", VisaType.TOURIST, 2,
                DateUtil.today(), DateUtil.plusYears(DateUtil.today(), 1), "Embassy").getString("code")));

        CompoundTag endorsement = DocumentFactory.createEndorsement(EndorsementType.ONE_YEAR_MULTI, 1,
                DateUtil.today(), DateUtil.plusYears(DateUtil.today(), 1), "Authority", "00998877");
        assertEquals("00998877", endorsement.getString("code"));
        assertTrue(CredentialCode.isValid(DocumentFactory.createEndorsement(EndorsementType.ONE_YEAR_MULTI, 1,
                DateUtil.today(), DateUtil.plusYears(DateUtil.today(), 1), "Authority").getString("code")));
    }

    @Test
    void duplicateCredentialCodesAreRegenerated() {
        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        CompoundTag first = DocumentFactory.createVisa("Japan", VisaType.TOURIST, 1, DateUtil.today(),
                DateUtil.plusYears(DateUtil.today(), 1), "Embassy", "11112222");
        CompoundTag duplicate = DocumentFactory.createVisa("Korea", VisaType.BUSINESS, 1, DateUtil.today(),
                DateUtil.plusYears(DateUtil.today(), 1), "Embassy", "11112222");
        passport.addVisa(first);
        passport.addVisa(duplicate);
        List<VisaRecord> visas = passport.visas();
        assertEquals("11112222", visas.get(visas.size() - 2).code());
        assertNotEquals("11112222", visas.get(visas.size() - 1).code());
        assertTrue(CredentialCode.isValid(visas.get(visas.size() - 1).code()));

        assertFalse(passport.addVisaFreeCode("11112222"),
                "a visa-free code cannot reuse a visa code");
        assertTrue(passport.addVisaFreeCode("33334444"));

        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        permit.addEndorsement(DocumentFactory.createEndorsement(EndorsementType.OTHER, 1,
                DateUtil.today(), DateUtil.plusYears(DateUtil.today(), 1), "Authority", "55556666"));
        permit.addEndorsement(DocumentFactory.createEndorsement(EndorsementType.OTHER, 1,
                DateUtil.today(), DateUtil.plusYears(DateUtil.today(), 1), "Authority", "55556666"));
        List<EndorsementRecord> endorsements = permit.endorsements();
        assertNotEquals("55556666", endorsements.get(endorsements.size() - 1).code());
    }

    @Test
    void modifierSaveRepairsDuplicateCodesInRawNbt() {
        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        passport.addVisa(DocumentFactory.createVisa("Japan", VisaType.TOURIST, 1, DateUtil.today(),
                DateUtil.plusYears(DateUtil.today(), 1), "Embassy", "12121212"));
        passport.addVisa(DocumentFactory.createVisa("Korea", VisaType.BUSINESS, 1, DateUtil.today(),
                DateUtil.plusYears(DateUtil.today(), 1), "Embassy", "34343434"));
        ListTag visas = passport.tag().getList("visas", Tag.TAG_COMPOUND);
        visas.getCompound(visas.size() - 1).putString("code", "12121212");

        assertTrue(passport.ensureUniqueCredentialCodes());
        List<VisaRecord> repaired = passport.visas();
        assertEquals("12121212", repaired.get(repaired.size() - 2).code());
        assertNotEquals("12121212", repaired.get(repaired.size() - 1).code());
        assertTrue(passport.integrityValid());
    }

    @Test
    void fixedMachineCodesMayBeSharedExplicitly() {
        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        passport.addVisaExact(DocumentFactory.createVisa("Japan", VisaType.TOURIST, 1, DateUtil.today(),
                DateUtil.plusYears(DateUtil.today(), 1), "Kiosk", "44445555"));
        passport.addVisaExact(DocumentFactory.createVisa("Korea", VisaType.BUSINESS, 1, DateUtil.today(),
                DateUtil.plusYears(DateUtil.today(), 1), "Kiosk", "44445555"));
        List<VisaRecord> visas = passport.visas();
        assertEquals("44445555", visas.get(visas.size() - 2).code());
        assertEquals("44445555", visas.get(visas.size() - 1).code());

        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        permit.addEndorsementExact(DocumentFactory.createEndorsement(EndorsementType.OTHER, 1,
                DateUtil.today(), DateUtil.plusYears(DateUtil.today(), 1), "Issuer", "66667777"));
        permit.addEndorsementExact(DocumentFactory.createEndorsement(EndorsementType.OTHER, 1,
                DateUtil.today(), DateUtil.plusYears(DateUtil.today(), 1), "Issuer", "66667777"));
        List<EndorsementRecord> endorsements = permit.endorsements();
        assertEquals("66667777", endorsements.get(endorsements.size() - 2).code());
        assertEquals("66667777", endorsements.get(endorsements.size() - 1).code());
        assertEquals(2, VisaType.BUSINESS.defaultEntries());
    }

    @Test
    void fixedMachineCodeIsAcceptedByGate() {
        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        passport.addVisaExact(DocumentFactory.createVisa("Testland", VisaType.TOURIST, 1, DateUtil.today(),
                DateUtil.plusYears(DateUtil.today(), 1), "VISA MACH", "00001111"));
        passport.addVisaExact(DocumentFactory.createVisa("Testland", VisaType.BUSINESS, 1, DateUtil.today(),
                DateUtil.plusYears(DateUtil.today(), 1), "VISA MACH", "00001111"));
        assertTrue(DocumentValidator.validateGate(passport, DeviceType.EGATE, "visa",
                List.of("00001111")).valid());
        assertTrue(DocumentValidator.validateGate(passport, DeviceType.EGATE, "both",
                List.of("00001111")).valid());
        assertFalse(DocumentValidator.validateGate(passport, DeviceType.EGATE, "visa",
                List.of("00002222")).valid());
    }

    @Test
    void gateLockConfigurationRoundTripsThroughNbt() {
        CompoundTag tag = new CompoundTag();
        DeviceBlockEntity.Locks locks = new DeviceBlockEntity.Locks(
                true, "  Border Passport  ", true, "  Border Authority ");
        locks.writeTo(tag);

        DeviceBlockEntity.Locks loaded = DeviceBlockEntity.Locks.fromTag(tag);
        assertEquals(locks, loaded);
        assertEquals("Border Passport", loaded.lockedDocumentName());
        assertEquals("Border Authority", loaded.lockedAuthority());
        assertTrue(loaded.lockDocumentName());
        assertTrue(loaded.lockAuthority());
        assertEquals(DeviceBlockEntity.Locks.NONE, DeviceBlockEntity.Locks.fromTag(new CompoundTag()));

        assertEquals(List.of("12345678", "87654321"),
                DeviceBlockEntity.normalizeGateCodes(List.of(" 12345678 ", "123", "12345678", "87654321")));
    }

    @Test
    void universalIssuerLocksRoundTripThroughNbt() {
        DeviceBlockEntity.Locks locks = new DeviceBlockEntity.Locks(
                true, "  Border Passport  ",
                true, "  Border Authority ",
                true, "  cn_passport ",
                true, "  Shanghai ",
                true, " 2024-02-29 ",
                true, 60,
                true, "  business ",
                true, " 00112233 ",
                true, "  one_year_multi ");
        CompoundTag tag = new CompoundTag();
        locks.writeTo(tag);

        DeviceBlockEntity.Locks loaded = DeviceBlockEntity.Locks.fromTag(tag);
        assertEquals(locks, loaded);
        assertEquals("Border Passport", loaded.lockedDocumentName());
        assertEquals("Border Authority", loaded.lockedAuthority());
        assertEquals("cn_passport", loaded.lockedStyle());
        assertEquals("Shanghai", loaded.lockedPlace());
        assertEquals("2024-02-29", loaded.lockedIssueDate());
        // 26.2 month precision: 5 years are stored as 60 months.
        assertEquals(60, loaded.lockedValidityMonths());
        assertEquals("business", loaded.lockedVisaType());
        assertEquals("00112233", loaded.lockedVisaCode());
        assertEquals("one_year_multi", loaded.lockedEndorsementType());
        assertTrue(loaded.lockStyle());
        assertTrue(loaded.lockPlace());
        assertTrue(loaded.lockIssueDate());
        assertTrue(loaded.lockValidity());
        assertTrue(loaded.lockVisaType());
        assertTrue(loaded.lockVisaCode());
        assertTrue(loaded.lockEndorsementType());

        // Pre-1.2.1 NBT loads neutral defaults and never turns a lock on.
        DeviceBlockEntity.Locks legacy = DeviceBlockEntity.Locks.fromTag(new CompoundTag());
        assertEquals(DeviceBlockEntity.Locks.NONE, legacy);
        assertFalse(legacy.lockStyle());
        assertEquals("", legacy.lockedStyle());
        assertFalse(legacy.lockPlace());
        assertFalse(legacy.lockIssueDate());
        assertFalse(legacy.lockValidity());
        assertEquals(0, legacy.lockedValidityMonths());
        assertFalse(legacy.lockVisaType());
        assertFalse(legacy.lockVisaCode());
        assertFalse(legacy.lockEndorsementType());

        // The 1.2.0 four-argument lock view remains source-compatible.
        DeviceBlockEntity.Locks old = new DeviceBlockEntity.Locks(
                true, " Passport ", true, " Authority ");
        assertEquals("Passport", old.lockedDocumentName());
        assertEquals("Authority", old.lockedAuthority());
        assertFalse(old.lockStyle());
        assertFalse(old.lockVisaType());
        assertFalse(old.lockEndorsementType());
    }

    @Test
    void lockedValidityMathTreatsZeroAsPermanentAndPositiveAsMonths() {
        assertEquals(DateUtil.PERMANENT, DocumentFactory.lockedExpiry("2024-03-01", 0));
        assertEquals(DateUtil.PERMANENT, DocumentFactory.lockedExpiry("2024-03-01", -3));
        assertEquals("2029-03-01", DocumentFactory.lockedExpiry("2024-03-01", 60),
                "60 months are five calendar years");
        assertEquals("2025-02-28", DocumentFactory.lockedExpiry("2024-02-29", 12),
                "leap-day issue dates clamp like LocalDate.plusMonths");
        assertEquals(DateUtil.plusMonths(DateUtil.today(), 24),
                DocumentFactory.lockedExpiry("not-a-date", 24),
                "an unusable issue date falls back to today");
    }

    @Test
    void passportAttachedVisaInheritsPassportDatesAndKeepsLockedCode() {
        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        passport.clearVisas();
        int before = passport.visas().size();

        CompoundTag visa = DocumentFactory.createAttachedVisa(passport, "Schengen", VisaType.BUSINESS,
                2, "Border Authority", "00112233");
        passport.addVisaExact(visa);

        List<VisaRecord> visas = passport.visas();
        assertEquals(before + 1, visas.size());
        VisaRecord record = visas.get(visas.size() - 1);
        assertEquals("Schengen", record.country());
        assertEquals(VisaType.BUSINESS, record.type());
        assertEquals(2, record.entries());
        assertEquals("00112233", record.code(), "a locked explicit code is preserved verbatim");
        assertEquals(passport.issueDate(), record.issueDate(), "issue date is inherited");
        assertEquals(passport.expiryDate(), record.expiryDate(), "expiry date is inherited");
        assertEquals("Border Authority", record.authority());
        assertTrue(record.active());
        assertTrue(passport.integrityValid());

        DocumentData reloaded = DocumentData.fromTag(passport.copyTag());
        assertTrue(reloaded.integrityValid());
        assertEquals(visas.size(), reloaded.visas().size());
        assertEquals("00112233", reloaded.visas().get(visas.size() - 1).code());

        // A permanent passport expiry falls back to one year after issue.
        passport.setExpiryDate(DateUtil.PERMANENT);
        CompoundTag fallback = DocumentFactory.createAttachedVisa(passport, "Japan", VisaType.TOURIST,
                1, "Embassy", "");
        assertEquals(passport.issueDate(), fallback.getString("issueDate"));
        assertEquals(DateUtil.plusYears(passport.issueDate(), 1), fallback.getString("expiryDate"));
        assertTrue(CredentialCode.isValid(fallback.getString("code")), "blank code is auto-generated");

        // An unusable passport expiry is repaired the same way, never copied.
        passport.setExpiryDate("not-a-date");
        CompoundTag repaired = DocumentFactory.createAttachedVisa(passport, "Korea", VisaType.STUDENT,
                1, "Embassy", "00112233");
        assertEquals(DateUtil.plusYears(passport.issueDate(), 1), repaired.getString("expiryDate"));
        assertEquals("00112233", repaired.getString("code"));
    }
}
