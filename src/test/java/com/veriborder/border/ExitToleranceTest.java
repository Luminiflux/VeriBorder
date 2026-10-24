package com.veriborder.border;

import com.veriborder.block.DeviceService;
import com.veriborder.doc.DateUtil;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentFactory;
import com.veriborder.doc.DocumentValidator;
import com.veriborder.doc.VisaRecord;
import com.veriborder.doc.VisaType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exit overstay tolerance (26.3 round 12, item 18).
 *
 * <p>The tolerance range is now explicit and identical for both documents and
 * credentials: a traveller whose <em>document</em> expired, or whose bound
 * <em>visa / endorsement</em> expired, was used up or was revoked during the stay,
 * is still allowed to leave — the departure is flagged as an OVERSTAY instead of
 * trapping the traveller. A record that is genuinely gone (not just stale) is
 * still refused, so an exit is never granted without the credential that the
 * entry bound.</p>
 */
class ExitToleranceTest {
    private static final UUID TRAVELLER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String NAME = "ALEX CARTER";
    private static final String DIMENSION = "minecraft:overworld";
    private static final BlockPos GATE = new BlockPos(8, 64, -2);
    private static final String PORT = "Beijing Capital";
    private static final String TODAY = DateUtil.today();
    private static final String YESTERDAY = DateUtil.plusDays(TODAY, -1);
    private static final String TOMORROW = DateUtil.plusDays(TODAY, 1);

    private static DocumentData passport() {
        DocumentData doc = DocumentData.createEmpty(DocStyle.US_PASSPORT);
        doc.setName(NAME);
        doc.setNumber("E76543210");
        doc.setBirthDate("1990-01-01");
        return doc;
    }

    /** One visa with the requested budget/expiry; returns the document and its code. */
    private static DocumentData withVisa(int entries, int usedEntries, String expiry, String status,
                                         String code) {
        DocumentData doc = passport();
        CompoundTag visa = DocumentFactory.createVisa("日本", VisaType.TOURIST, entries, TODAY, expiry,
                "日本驻华使领馆", code);
        visa.putInt("usedEntries", usedEntries);
        visa.putString("status", status);
        doc.addVisaExact(visa);
        return doc;
    }

    /**
     * Starts an entry bound to the visa record the way the gate does after a
     * successful entry check. The record is resolved while it is still usable, so
     * the staleness these tests exercise is exactly the "went stale during the
     * stay" case.
     */
    private static BorderEntry enter(BorderTravelData data, DocumentData doc, String code) {
        String recordId = "";
        for (VisaRecord visa : doc.visas()) {
            if (visa.code().equals(code)) {
                recordId = visa.id();
            }
        }
        BorderService.EntryMatch match = new BorderService.EntryMatch(BorderEntry.KIND_VISA, recordId, code);
        return BorderService.beginEntry(data, TRAVELLER, NAME, DIMENSION, GATE, PORT, 30, doc, match);
    }

    @Test
    void anExpiredVisaStillLetsTheTravellerLeaveAndIsFlagged() {
        String code = "10101010";
        DocumentData doc = withVisa(2, 0, YESTERDAY, VisaRecord.ACTIVE, code);
        BorderTravelData data = new BorderTravelData();
        BorderEntry entry = enter(data, doc, code);

        DeviceService.ExitStage stage = DeviceService.exitStage(doc, entry, List.of(code));
        assertTrue(stage.allowed(), "an expired credential must never trap the traveller");
        assertEquals(-1, stage.recordIndex(), "a stale record is not consumed again");
        assertTrue(stage.staleCredential());
        assertTrue(stage.overstay(), "the departure has to be logged as an OVERSTAY");
    }

    @Test
    void aUsedUpVisaAndARevokedVisaBehaveLikeAnExpiredOne() {
        for (DocumentData doc : List.of(
                withVisa(1, 1, TOMORROW, VisaRecord.ACTIVE, "20202020"),
                withVisa(2, 0, TOMORROW, VisaRecord.REVOKED, "20202020"))) {
            BorderTravelData data = new BorderTravelData();
            BorderEntry entry = enter(data, doc, "20202020");
            DeviceService.ExitStage stage = DeviceService.exitStage(doc, entry, List.of("20202020"));
            assertTrue(stage.allowed(), "used-up / revoked credentials still allow the exit");
            assertTrue(stage.overstay());
        }
    }

    @Test
    void aFreshVisaLeavesWithoutAnOverstayFlag() {
        String code = "30303030";
        DocumentData doc = withVisa(2, 0, TOMORROW, VisaRecord.ACTIVE, code);
        BorderTravelData data = new BorderTravelData();
        BorderEntry entry = enter(data, doc, code);

        DeviceService.ExitStage stage = DeviceService.exitStage(doc, entry, List.of(code));
        assertTrue(stage.allowed());
        assertEquals(0, stage.recordIndex(), "the usable record is consumed exactly once");
        assertFalse(stage.staleCredential());
        assertFalse(stage.overstay());
        assertFalse(doc.isExpired(), "the fixture document itself is still valid");
    }

    @Test
    void anExpiredDocumentIsToleratedByTheExitValidator() {
        DocumentData doc = passport();
        doc.setIssueDate(DateUtil.plusDays(TODAY, -730));
        doc.setExpiryDate(YESTERDAY);
        assertTrue(doc.isExpired());
        assertTrue(DocumentValidator.validateExit(doc).valid(),
                "an expired document may still leave the country");
        assertFalse(DocumentValidator.validate(doc, com.veriborder.block.DeviceType.EXIT_GATE, "").valid(),
                "the ordinary validator still refuses an expired document");
    }

    @Test
    void aGenuinelyMissingRecordIsStillRefused() {
        String code = "40404040";
        DocumentData doc = withVisa(1, 0, TOMORROW, VisaRecord.ACTIVE, code);
        BorderTravelData data = new BorderTravelData();
        BorderEntry entry = enter(data, doc, code);
        // The record disappears entirely (not stale): the exit cannot bind it.
        doc.removeVisa(0);

        DeviceService.ExitStage stage = DeviceService.exitStage(doc, entry, List.of());
        assertFalse(stage.allowed());
        assertEquals(BorderService.REASON_SESSION_RECORD_MISSING, stage.reason());
    }

    @Test
    void theOverstayFlagReachesTheBorderLog() {
        String code = "50505050";
        DocumentData doc = withVisa(1, 1, TOMORROW, VisaRecord.ACTIVE, code);
        BorderTravelData data = new BorderTravelData();
        BorderEntry entry = enter(data, doc, code);
        DeviceService.ExitStage stage = DeviceService.exitStage(doc, entry, List.of(code));
        assertTrue(stage.overstay());

        boolean overstay = BorderService.finishExit(data, TRAVELLER, NAME, DIMENSION, entry, GATE,
                doc.number(), doc.isExpired() || stage.overstay());
        assertTrue(overstay, "the caller must report the overstay to the traveller");
        assertEquals(0, data.entryCount(), "the exit clears the entry");
        assertEquals(BorderLog.OVERSTAY, data.recentLogs(1).get(0).result(),
                "the log records OVERSTAY instead of a plain ALLOW_OUT");
    }
}
