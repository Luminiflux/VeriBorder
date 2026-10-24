package com.veriborder.border;

import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentFactory;
import com.veriborder.doc.DocumentValidator;
import com.veriborder.doc.VisaRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end gate flow without a live server (26.3 round 11, "闸机流程集成测试").
 *
 * <p>Drives the same data-level seam the entry/exit gates use: validate the
 * document, resolve the presented credential, create the entry, consume exactly
 * the bound record on departure, log and clear the entry. It catches the bugs a
 * per-class unit test misses: the record the entry bound must be the record the
 * exit consumes, and the entry must disappear only after the departure is
 * logged.</p>
 */
class GateFlowIntegrationTest {
    private static final UUID TRAVELLER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String NAME = "ALEX CARTER";
    private static final String DIMENSION = "minecraft:overworld";
    private static final BlockPos GATE = new BlockPos(10, 64, -2);
    private static final String PORT = "San Francisco International";

    private static BorderLog lastLog(BorderTravelData data) {
        List<BorderLog> logs = data.logs();
        assertFalse(logs.isEmpty(), "expected at least one border log");
        return logs.get(logs.size() - 1);
    }

    /** Mirrors DeviceService: increments usedEntries on exactly the bound record. */
    private static int consumeBoundVisa(DocumentData document, BorderEntry entry) {
        List<CompoundTag> tags = document.visaTags();
        int index = -1;
        for (int i = 0; i < tags.size(); i++) {
            CompoundTag candidate = tags.get(i);
            if (!entry.recordId().isBlank()) {
                if (entry.recordId().equals(candidate.getString("id"))) {
                    index = i;
                    break;
                }
            } else if (entry.code().equals(VisaRecord.fromTag(candidate).code())) {
                index = i;
                break;
            }
        }
        assertTrue(index >= 0, "the entry must bind a visa that still exists on the document");
        CompoundTag tag = tags.get(index);
        tag.putString("code", VisaRecord.fromTag(tag).code());
        tag.putInt("usedEntries", tag.getInt("usedEntries") + 1);
        document.updateVisa(index, tag);
        return index;
    }

    @Test
    void visaEntryThenExitConsumesExactlyTheBoundRecord() {
        DocumentData passport = DocumentFactory.sample(DocStyle.US_PASSPORT);
        assertTrue(DocumentValidator.validateGate(passport, com.veriborder.block.DeviceType.EGATE,
                "visa", List.of(passport.visas().get(0).code())).valid());

        VisaRecord visa = passport.visas().get(0);
        BorderTravelData data = new BorderTravelData();

        // ---- entry ---------------------------------------------------------
        BorderService.EntryMatch match = BorderService.resolveEntry(passport, "visa", List.of(visa.code()));
        assertNotNull(match, "the passport carries the active visa the gate requires");
        assertEquals(BorderEntry.KIND_VISA, match.kind());
        assertEquals(visa.id(), match.recordId());

        BorderEntry entry = BorderService.beginEntry(data, TRAVELLER, NAME, DIMENSION, GATE, PORT, 30,
                passport, match);
        assertEquals(BorderEntry.KIND_VISA, entry.kind());
        assertTrue(entry.consumesRecord());
        assertEquals(1, data.entryCount());
        assertEquals(entry, data.entry(entry.personKey()));
        assertEquals(entry, data.entryByDocument(passport.number()));
        assertEquals(entry, BorderService.findEntry(data, passport));
        assertTrue(BorderService.sameTraveller(entry, passport));
        assertTrue(BorderService.isEntryDocument(entry, passport));
        assertEquals(BorderLog.ALLOW_IN, lastLog(data).result(), "the entry is logged before anything else");

        // ---- exit preconditions -------------------------------------------
        assertTrue(DocumentValidator.validateGate(passport, com.veriborder.block.DeviceType.EXIT_GATE,
                "", List.of()).valid(), "the exit gate ignores codes and mode");

        // ---- exit: consume the bound record, then close the entry --------
        int remainingBefore = passport.visas().get(0).remainingEntries();
        consumeBoundVisa(passport, entry);
        assertEquals(remainingBefore - 1, passport.visas().get(0).remainingEntries(),
                "exactly one visit is consumed");
        assertTrue(passport.integrityValid(), "consuming a record re-signs the document");

        boolean overstay = BorderService.finishExit(data, TRAVELLER, NAME, DIMENSION, entry, GATE,
                passport.number());
        assertFalse(overstay, "a fresh entry is on time");
        assertEquals(0, data.entryCount(), "the entry is cleared by the exit");
        assertNull(data.entry(entry.personKey()));
        assertNull(BorderService.findEntry(data, passport));

        BorderLog departure = lastLog(data);
        assertEquals(BorderLog.OUT, departure.direction());
        assertEquals(BorderLog.ALLOW_OUT, departure.result());
        assertEquals(passport.number(), departure.documentNumber());
        assertEquals(visa.code(), departure.code());
        assertEquals(BorderEntry.KIND_VISA, departure.kind());
        assertTrue(departure.gate().contains("minecraft:overworld@10,64,-2"));
    }

    @Test
    void anExhaustedVisaNoLongerAdmitsTheTraveller() {
        DocumentData passport = DocumentFactory.sample(DocStyle.US_PASSPORT);
        VisaRecord visa = passport.visas().get(0);
        BorderTravelData data = new BorderTravelData();

        for (int visit = 0; visit < visa.entries(); visit++) {
            BorderService.EntryMatch match = BorderService.resolveEntry(passport, "visa",
                    List.of(visa.code()));
            assertNotNull(match, "visit " + (visit + 1) + " is still allowed");
            BorderEntry entry = BorderService.beginEntry(data, TRAVELLER, NAME, DIMENSION, GATE, PORT,
                    30, passport, match);
            consumeBoundVisa(passport, entry);
            BorderService.finishExit(data, TRAVELLER, NAME, DIMENSION, entry, GATE, passport.number());
        }

        assertEquals(0, passport.visas().get(0).remainingEntries());
        assertFalse(passport.visas().get(0).active(), "the visa is used up");
        assertNull(BorderService.resolveEntry(passport, "visa", List.of(visa.code())),
                "a used-up visa cannot bind another entry");
        assertEquals(0, data.entryCount());
    }

    @Test
    void aLateDepartureIsFlaggedButStillAllowed() {
        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        BorderTravelData data = new BorderTravelData();
        long thirtyOneDaysAgo = System.currentTimeMillis() - 31L * BorderEntry.MILLIS_PER_DAY;
        BorderEntry stale = new BorderEntry(BorderService.personKey(permit), permit.number(), permit.name(),
                "", "", BorderEntry.KIND_NONE, DIMENSION, GATE, thirtyOneDaysAgo, PORT, 30, false,
                TRAVELLER, NAME);
        data.putEntry(stale);

        boolean overstay = BorderService.finishExit(data, TRAVELLER, NAME, DIMENSION, stale, GATE,
                permit.number());

        assertTrue(overstay, "31 days on a 30-day allowance is an overstay");
        assertEquals(0, data.entryCount(), "the exit still clears the entry");
        BorderLog departure = lastLog(data);
        assertEquals(BorderLog.OVERSTAY, departure.result());
        assertEquals(BorderService.REASON_OVERSTAY, departure.detail());
    }

    @Test
    void exitDoesNotConsumeWhenTheEntryBoundNoRecord() {
        DocumentData identityCard = DocumentFactory.sample(DocStyle.CN_ID);
        BorderTravelData data = new BorderTravelData();
        BorderEntry entry = BorderService.beginEntry(data, TRAVELLER, NAME, DIMENSION, GATE, PORT, 30,
                identityCard, BorderService.EntryMatch.none());

        assertEquals(BorderEntry.KIND_NONE, entry.kind());
        assertFalse(entry.consumesRecord(), "a record-less entry consumes nothing on exit");
        assertFalse(entry.hasCredential());

        boolean overstay = BorderService.finishExit(data, TRAVELLER, NAME, DIMENSION, entry, GATE,
                identityCard.number());
        assertFalse(overstay);
        assertEquals(0, data.entryCount());
        assertEquals(BorderLog.ALLOW_OUT, lastLog(data).result());
    }
}
