package com.veriborder.border;

import com.veriborder.block.DeviceService;
import com.veriborder.block.DeviceType;
import com.veriborder.doc.DateUtil;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentFactory;
import com.veriborder.doc.DocumentStatus;
import com.veriborder.doc.DocumentValidator;
import com.veriborder.doc.EndorsementRecord;
import com.veriborder.doc.EndorsementType;
import com.veriborder.doc.VisaRecord;
import com.veriborder.doc.VisaType;
import com.veriborder.item.DualGateBrushConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.3 exit-gate restore and directional country whitelists.
 *
 * <p>The shared {@link DeviceService#exitStage} decision is checked through its
 * data-level seam (no live server): an entry is required, the traveller and the
 * document must be acceptable (an expired document may still leave), a document
 * issued by an allowed country/region passes directly, and only otherwise is the
 * exit-code list enforced (empty list = no restriction). Exactly the record the
 * entry bound is consumed, once. The dual gate's exit stage calls the same
 * method, so both gate kinds behave identically.</p>
 */
class ExitGateTest {
    private static final UUID TRAVELLER = UUID.fromString("12121212-3434-5656-7878-909090909090");
    private static final String NAME = "ALEX CARTER";
    private static final String DIMENSION = "minecraft:overworld";
    private static final BlockPos GATE = new BlockPos(4, 64, 9);
    private static final String PORT = "Beijing Capital";

    /** A passport sample with two independent visas (the second must stay untouched). */
    private static DocumentData passportWithTwoVisas() {
        DocumentData passport = DocumentFactory.sample(DocStyle.US_PASSPORT);
        String today = DateUtil.today();
        CompoundTag second = DocumentFactory.createVisa("日本", VisaType.TOURIST, 1, today,
                DateUtil.plusYears(today, 1), "日本驻华使领馆", "87654321");
        passport.addVisa(second);
        return passport;
    }

    private static BorderEntry entered(BorderTravelData data, DocumentData doc, BorderService.EntryMatch match) {
        return BorderService.beginEntry(data, TRAVELLER, NAME, DIMENSION, GATE, PORT, 30, doc, match);
    }

    @Test
    void exitConsumesExactlyTheBoundVisaRecordOnce() {
        DocumentData passport = passportWithTwoVisas();
        assertEquals(2, passport.visas().size(), "the fixture really has two records");
        VisaRecord bound = passport.visas().get(0);

        BorderTravelData data = new BorderTravelData();
        BorderService.EntryMatch match = BorderService.resolveEntry(passport, "visa", List.of(bound.code()));
        assertNotNull(match);
        BorderEntry entry = entered(data, passport, match);

        // Exit codes are configured and the presented document carries one.
        DeviceService.ExitStage stage = DeviceService.exitStage(passport, entry, List.of(bound.code()));
        assertTrue(stage.allowed(), "a matching exit code lets the traveller leave");
        assertEquals(0, stage.recordIndex(), "the entry bound the first visa record");
        assertEquals(BorderEntry.KIND_VISA, stage.kind());

        // The match itself is read-only: nothing changed before the commit.
        assertEquals(0, passport.visas().get(0).usedEntries());
        assertEquals(0, passport.visas().get(1).usedEntries());

        boolean consumed = DeviceService.consumeBoundRecord(passport, stage.kind(), stage.recordIndex());
        assertTrue(consumed);
        assertEquals(1, passport.visas().get(0).usedEntries(), "the bound record is consumed exactly once");
        assertEquals(0, passport.visas().get(1).usedEntries(), "no other record is touched");
        assertTrue(passport.integrityValid(), "consuming a record re-signs the document");

        assertFalse(BorderService.finishExit(data, TRAVELLER, NAME, DIMENSION, entry, GATE,
                passport.number()), "a fresh entry is on time");
        assertEquals(0, data.entryCount(), "the exit clears the entry");
    }

    @Test
    void aSecondExitWithoutAnEntryRecordIsRejected() {
        DocumentData passport = passportWithTwoVisas();
        VisaRecord bound = passport.visas().get(0);
        BorderTravelData data = new BorderTravelData();
        BorderEntry entry = entered(data, passport,
                BorderService.resolveEntry(passport, "visa", List.of(bound.code())));

        DeviceService.ExitStage first = DeviceService.exitStage(passport, entry, List.of(bound.code()));
        assertTrue(first.allowed());
        DeviceService.consumeBoundRecord(passport, first.kind(), first.recordIndex());
        BorderService.finishExit(data, TRAVELLER, NAME, DIMENSION, entry, GATE, passport.number());

        // The entry is gone: a second departure has no record to bind to.
        DeviceService.ExitStage second = DeviceService.exitStage(passport, null, List.of(bound.code()));
        assertFalse(second.allowed());
        assertEquals(BorderService.REASON_NO_ENTRY_RECORD, second.reason());
        assertEquals(1, passport.visas().get(0).usedEntries(), "the first exit consumed one entry only");
    }

    @Test
    void anEndorsementEntryConsumesItsOwnRecord() {
        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        String today = DateUtil.today();
        permit.addEndorsement(DocumentFactory.createEndorsement(EndorsementType.THREE_MONTH_SINGLE, 1, today,
                DateUtil.plusDays(today, 90), "香港入境事务处", "11223344"));
        int index = -1;
        List<EndorsementRecord> records = permit.endorsements();
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).code().equals("11223344")) {
                index = i;
            }
        }
        assertTrue(index >= 0, "the fixture carries the endorsement");

        BorderTravelData data = new BorderTravelData();
        BorderEntry entry = entered(data, permit,
                BorderService.resolveEntry(permit, "endorsement", List.of("11223344")));
        DeviceService.ExitStage stage = DeviceService.exitStage(permit, entry, List.of("11223344"));
        assertTrue(stage.allowed());
        assertEquals(index, stage.recordIndex());
        assertEquals(BorderEntry.KIND_ENDORSEMENT, stage.kind());

        int before = permit.endorsements().get(index).usedEntries();
        assertTrue(DeviceService.consumeBoundRecord(permit, stage.kind(), stage.recordIndex()));
        assertEquals(before + 1, permit.endorsements().get(index).usedEntries());
    }

    @Test
    void aSingleEntryEndorsementCannotBeUsedTwice() {
        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        String today = DateUtil.today();
        permit.addEndorsement(DocumentFactory.createEndorsement(EndorsementType.THREE_MONTH_SINGLE, 1, today,
                DateUtil.plusDays(today, 90), "香港入境事务处", "22334455"));
        int index = -1;
        for (int i = 0; i < permit.endorsements().size(); i++) {
            if (permit.endorsements().get(i).code().equals("22334455")) {
                index = i;
            }
        }
        assertTrue(index >= 0);

        // Entry 1: the endorsement binds the entry.
        BorderTravelData data = new BorderTravelData();
        BorderService.EntryMatch match = BorderService.resolveEntry(permit, "endorsement", List.of("22334455"));
        assertNotNull(match);
        BorderEntry entry = entered(data, permit, match);

        // Exit: the single entry is consumed (usedEntries 1) and the entry cleared.
        DeviceService.ExitStage stage = DeviceService.exitStage(permit, entry, List.of());
        assertTrue(stage.allowed());
        assertTrue(DeviceService.consumeBoundRecord(permit, stage.kind(), stage.recordIndex()));
        assertEquals(1, permit.endorsements().get(index).usedEntries());
        BorderService.finishExit(data, TRAVELLER, NAME, DIMENSION, entry, GATE, permit.number());

        // Entry 2: the exhausted endorsement can no longer bind an entry.
        assertFalse(permit.endorsements().get(index).active());
        assertNull(BorderService.resolveEntry(permit, "endorsement", List.of("22334455")),
                "a single-entry endorsement cannot admit a second arrival");
        assertEquals(0, data.entryCount());
    }

    @Test
    void anEmptyExitCodeListMeansNoRestriction() {
        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        BorderTravelData data = new BorderTravelData();
        BorderEntry entry = entered(data, permit, BorderService.EntryMatch.none());

        // No configured exit codes: the departure is not restricted at all.
        DeviceService.ExitStage stage = DeviceService.exitStage(permit, entry, List.of());
        assertTrue(stage.allowed());
        assertEquals(-1, stage.recordIndex(), "a record-less entry consumes nothing");
    }

    @Test
    void aConfiguredExitCodeListMustMatch() {
        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        BorderTravelData data = new BorderTravelData();
        BorderEntry entry = entered(data, permit, BorderService.EntryMatch.none());

        DeviceService.ExitStage denied = DeviceService.exitStage(permit, entry, List.of("11112222"));
        assertFalse(denied.allowed());
        assertEquals("verify.veriborder.reason.no_matching_code", denied.reason());
        assertEquals(1, data.entryCount(), "a denial leaves the entry untouched");

        assertTrue(permit.addVisaFreeCode("11112222"));
        DeviceService.ExitStage allowed = DeviceService.exitStage(permit, entry, List.of("11112222"));
        assertTrue(allowed.allowed(), "a document carrying the configured code may leave");
    }

    @Test
    void aVisaFreeEntryLeavesWithoutExitCodes() {
        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        assertTrue(permit.addVisaFreeCode("55667788"));
        BorderTravelData data = new BorderTravelData();
        BorderEntry entry = entered(data, permit,
                new BorderService.EntryMatch(BorderEntry.KIND_VISA_FREE, "", "55667788"));

        int recordsBefore = permit.visas().size() + permit.endorsements().size();
        DeviceService.ExitStage stage = DeviceService.exitStage(permit, entry, List.of());
        assertTrue(stage.allowed());
        assertEquals(-1, stage.recordIndex(), "a visa-free entry consumes no record");
        assertFalse(DeviceService.consumeBoundRecord(permit, stage.kind(), stage.recordIndex()));
        assertEquals(recordsBefore, permit.visas().size() + permit.endorsements().size(),
                "no visa/endorsement record was changed");
        for (EndorsementRecord record : permit.endorsements()) {
            assertEquals(0, record.usedEntries(), "no record is consumed by a visa-free exit");
        }
        assertFalse(BorderService.finishExit(data, TRAVELLER, NAME, DIMENSION, entry, GATE, permit.number()),
                "the departure is on time");
        assertEquals(0, data.entryCount());
    }

    @Test
    void anAllowedCountryPassesTheExitCodeCheck() {
        DocumentData passport = passportWithTwoVisas();
        assertEquals("USA", passport.issuerCountry(), "the sample passport is issued by the US");
        BorderTravelData data = new BorderTravelData();
        BorderEntry entry = entered(data, passport,
                BorderService.resolveEntry(passport, "visa",
                        List.of(passport.visas().get(0).code())));

        // Exit codes do not match, but the issuing country is whitelisted.
        DeviceService.ExitStage allowed = DeviceService.exitStage(passport, entry,
                List.of("12345678"), List.of("USA"));
        assertTrue(allowed.allowed(), "an allowed issuing country passes directly");
        assertEquals(0, allowed.recordIndex(), "the bound record is still identified for consumption");

        // Another whitelist does not help.
        DeviceService.ExitStage denied = DeviceService.exitStage(passport, entry,
                List.of("12345678"), List.of("CHN"));
        assertFalse(denied.allowed());
        assertEquals("verify.veriborder.reason.no_matching_code", denied.reason());

        // An empty exit-code list is unrestricted regardless of the whitelist.
        assertTrue(DeviceService.exitStage(passport, entry, List.of(), List.of("CHN")).allowed());
    }

    @Test
    void countryBypassDrivesTheEntryGateAndTheExitStage() {
        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        assertEquals("CHN", passport.issuerCountry());
        assertTrue(DeviceService.countryBypass(passport, List.of("CHN")));
        assertFalse(DeviceService.countryBypass(passport, List.of("USA")));
        assertFalse(DeviceService.countryBypass(passport, List.of()));

        BorderTravelData data = new BorderTravelData();
        BorderEntry entry = entered(data, passport,
                new BorderService.EntryMatch(BorderEntry.KIND_VISA_FREE, "", passport.issuerCountry()));
        assertEquals(BorderEntry.KIND_VISA_FREE, entry.kind());
        assertTrue(DeviceService.exitStage(passport, entry, List.of()).allowed());
    }

    @Test
    void theDualGateExitStageBehavesExactlyLikeTheSingleExitGate() {
        DocumentData passport = passportWithTwoVisas();
        VisaRecord bound = passport.visas().get(0);
        BorderTravelData data = new BorderTravelData();
        BorderEntry entry = entered(data, passport,
                BorderService.resolveEntry(passport, "visa", List.of(bound.code())));

        CompoundTag config = new CompoundTag();
        ListTag exits = new ListTag();
        exits.add(StringTag.valueOf(bound.code()));
        config.put(DualGateBrushConfig.EXIT_CODES, exits);
        config.put(DualGateBrushConfig.ENTRY_CODES, new ListTag());
        ListTag countries = new ListTag();
        countries.add(StringTag.valueOf("USA"));
        config.put(DualGateBrushConfig.EXIT_COUNTRY_CODES, countries);

        DeviceService.ExitStage single = DeviceService.exitStage(passport, entry, List.of(bound.code()),
                List.of("USA"));
        DeviceService.ExitStage dual = DeviceService.exitStage(passport, entry,
                DualGateBrushConfig.exitCodes(config), DualGateBrushConfig.exitCountryCodes(config));

        assertEquals(single.allowed(), dual.allowed(), "both gate kinds share one exit decision");
        assertEquals(single.reason(), dual.reason());
        assertEquals(single.recordIndex(), dual.recordIndex());
        assertEquals(single.kind(), dual.kind());
        assertTrue(dual.allowed());

        // Failures agree too (neither the code nor the country matches).
        CompoundTag noMatch = new CompoundTag();
        ListTag other = new ListTag();
        other.add(StringTag.valueOf("12345678"));
        noMatch.put(DualGateBrushConfig.EXIT_CODES, other);
        ListTag otherCountry = new ListTag();
        otherCountry.add(StringTag.valueOf("CHN"));
        noMatch.put(DualGateBrushConfig.EXIT_COUNTRY_CODES, otherCountry);
        DeviceService.ExitStage singleDenied = DeviceService.exitStage(passport, entry, List.of("12345678"),
                List.of("CHN"));
        DeviceService.ExitStage dualDenied = DeviceService.exitStage(passport, entry,
                DualGateBrushConfig.exitCodes(noMatch), DualGateBrushConfig.exitCountryCodes(noMatch));
        assertEquals(singleDenied.allowed(), dualDenied.allowed());
        assertEquals(singleDenied.reason(), dualDenied.reason());
    }

    @Test
    void anExpiredDocumentMayLeaveAndIsFlaggedAsOverstay() {
        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        permit.setIssueDate("2018-01-01");
        permit.setExpiryDate("2020-01-01");
        assertTrue(permit.isExpired(), "the fixture is expired");
        assertFalse(DocumentValidator.validate(permit, DeviceType.EXIT_GATE, "").valid(),
                "strict validation refuses an expired document");
        assertTrue(DocumentValidator.validateExit(permit).valid(),
                "the exit validation tolerates an expired document");

        BorderTravelData data = new BorderTravelData();
        BorderEntry entry = entered(data, permit, BorderService.EntryMatch.none());
        DeviceService.ExitStage stage = DeviceService.exitStage(permit, entry, List.of());
        assertTrue(stage.allowed(), "an expired permit must not trap the traveller");

        boolean overstay = BorderService.finishExit(data, TRAVELLER, NAME, DIMENSION, entry, GATE,
                permit.number(), true);
        assertTrue(overstay, "the expired document marks the departure as an overstay");
        assertEquals(0, data.entryCount());
        BorderLog departure = data.logs().get(data.logs().size() - 1);
        assertEquals(BorderLog.OVERSTAY, departure.result());
        assertEquals(BorderService.REASON_OVERSTAY, departure.detail());
    }

    @Test
    void theEntryGateStillRejectsAnExpiredDocument() {
        DocumentData passport = DocumentFactory.sample(DocStyle.US_PASSPORT);
        String code = passport.visas().get(0).code();
        assertTrue(DocumentValidator.validateGate(passport, DeviceType.EGATE, "visa", List.of(code),
                "USA").valid());
        passport.setIssueDate("2018-01-01");
        passport.setExpiryDate("2020-01-01");
        assertFalse(DocumentValidator.validateGate(passport, DeviceType.EGATE, "visa", List.of(code),
                "USA").valid(), "an expired document cannot enter");
    }

    @Test
    void issuerCountryWrittenAtIssueMatchesTheGateWhitelist() {
        // The three-letter registry code is written by the issuer form ...
        CompoundTag form = new CompoundTag();
        form.putString("name", "ALEX CARTER");
        form.putString("birthDate", "1990-01-01");
        form.putString("issuerCountry", "chn");
        form.putString("mrzCountry", "USA"); // legacy key must not win over issuerCountry
        DocumentData doc = DocumentFactory.fromForm(DocStyle.CN_PASSPORT, form);
        assertEquals("CHN", doc.issuerCountry(), "the registry code is written as issuerCountry");
        assertTrue(doc.mrz1().startsWith("P<CHN"), "the passport MRZ reads the same code");

        // ... and the gate matches the same code: entry whitelist hit = visa-free.
        assertTrue(DeviceService.countryBypass(doc, List.of("CHN")));
        assertFalse(DeviceService.countryBypass(doc, List.of("USA")));
        BorderTravelData data = new BorderTravelData();
        BorderEntry entry = entered(data, doc,
                new BorderService.EntryMatch(BorderEntry.KIND_VISA_FREE, "", doc.issuerCountry()));

        // Exit whitelist hit passes even when the configured exit code does not match.
        DeviceService.ExitStage stage = DeviceService.exitStage(doc, entry, List.of("99998888"),
                List.of("CHN"));
        assertTrue(stage.allowed());
        assertFalse(BorderService.finishExit(data, TRAVELLER, NAME, DIMENSION, entry, GATE,
                doc.number()), "the departure is on time");
        assertEquals(0, data.entryCount());
    }

    @Test
    void theGateBrushesCarryTheDirectionalWhitelistsAndExitCodes() {
        CompoundTag config = new CompoundTag();
        ListTag entryCountries = new ListTag();
        entryCountries.add(StringTag.valueOf("CHN"));
        ListTag exitCountries = new ListTag();
        exitCountries.add(StringTag.valueOf("USA"));
        ListTag exits = new ListTag();
        exits.add(StringTag.valueOf("12345678"));
        config.put("entryCountryCodes", entryCountries);
        config.put("exitCountryCodes", exitCountries);
        config.put("exitCodes", exits);

        CompoundTag gate = com.veriborder.item.GateBrushConfig.sanitize(config);
        assertEquals(List.of("CHN"), com.veriborder.item.GateBrushConfig.entryCountryCodes(gate));
        assertEquals(List.of("USA"), com.veriborder.item.GateBrushConfig.exitCountryCodes(gate));
        assertEquals(List.of("12345678"), com.veriborder.item.GateBrushConfig.exitCodes(gate));

        CompoundTag dual = DualGateBrushConfig.sanitize(config);
        assertEquals(List.of("CHN"), DualGateBrushConfig.countryCodes(dual));
        assertEquals(List.of("USA"), DualGateBrushConfig.exitCountryCodes(dual));
        assertEquals(List.of("12345678"), DualGateBrushConfig.exitCodes(dual));
    }

    @Test
    void anotherHolderIsRejected() {
        DocumentData passport = passportWithTwoVisas();
        VisaRecord bound = passport.visas().get(0);
        BorderTravelData data = new BorderTravelData();
        BorderEntry entry = entered(data, passport,
                BorderService.resolveEntry(passport, "visa", List.of(bound.code())));

        DocumentData other = DocumentFactory.sample(DocStyle.JP_PASSPORT);
        DeviceService.ExitStage mismatch = DeviceService.exitStage(other, entry, List.of(bound.code()));
        assertFalse(mismatch.allowed());
        assertEquals(BorderService.REASON_SESSION_DOCUMENT_MISMATCH, mismatch.reason());

        passport.setStatus(DocumentStatus.CANCELLED);
        DeviceService.ExitStage cancelled = DeviceService.exitStage(passport, entry, List.of(bound.code()));
        assertFalse(cancelled.allowed(), "a cancelled document is still refused at exit");

        passport.setStatus(DocumentStatus.VALID);
        assertTrue(DocumentValidator.validate(passport, DeviceType.EXIT_GATE, "").valid(),
                "the document itself is valid again");
    }
}
