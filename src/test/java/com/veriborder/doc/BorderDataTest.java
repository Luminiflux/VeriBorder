package com.veriborder.doc;

import com.veriborder.block.DeviceType;
import com.veriborder.border.BorderLog;
import com.veriborder.border.BorderService;
import com.veriborder.border.BorderSession;
import com.veriborder.border.BorderTravelData;
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
 * Border data tests (26.1): session/log NBT round-trips, the 500-entry log cap,
 * overstay arithmetic, single-session-per-player and permit stamp acceptance.
 * All of these run without a world because {@link BorderTravelData} only
 * serialises to NBT; the overworld storage is resolved through
 * {@code BorderTravelData.get(server)} at runtime.
 */
class BorderDataTest {
    private static final UUID TRAVELLER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final long DAY = BorderSession.MILLIS_PER_DAY;

    @Test
    void borderSessionRoundTripsThroughNbt() {
        long entry = 1_700_000_000_000L;
        BorderSession session = new BorderSession(TRAVELLER, "张三", "E12345678", "V-ABC12345",
                "12345678", BorderSession.KIND_VISA, "minecraft:overworld",
                new BlockPos(10, 64, -3), entry, "北京首都国际机场 T3", 30, true);

        CompoundTag tag = session.toTag();
        assertTrue(tag.contains("player"));
        assertTrue(tag.contains("document"));
        assertEquals(entry, tag.getLong("entryTime"));

        BorderSession loaded = BorderSession.fromTag(tag);
        assertNotNull(loaded);
        assertEquals(session, loaded);
        assertEquals(session.playerName(), loaded.playerName());
        assertEquals(session.gatePos(), loaded.gatePos());
        assertEquals(session.port(), loaded.port());
        assertTrue(loaded.overstay());
        assertTrue(loaded.consumesRecord());
    }

    @Test
    void officerSessionTagKeepsTheDocumentedFields() {
        BorderSession session = new BorderSession(TRAVELLER, "张三", "E12345678", "V-ABC12345",
                "12345678", BorderSession.KIND_VISA, "minecraft:overworld", new BlockPos(1, 2, 3),
                1_700_000_000_000L, "北京首都国际机场 T3", 30, false);
        CompoundTag officer = session.toOfficerTag();
        for (String key : List.of("player", "playerName", "document", "kind", "code",
                "gateX", "gateY", "gateZ", "dimension", "entryTime", "overstay", "overstayDays")) {
            assertTrue(officer.contains(key), "officer session key " + key);
        }
        // Internal binding stays on the server.
        assertFalse(officer.contains("recordId"));
    }

    @Test
    void borderTravelDataRoundTripsSessionsAndLogs() {
        BorderTravelData data = new BorderTravelData();
        BorderSession first = new BorderSession(TRAVELLER, "张三", "E12345678", "V-1",
                "12345678", BorderSession.KIND_VISA, "minecraft:overworld", new BlockPos(0, 64, 0),
                1_700_000_000_000L, "北京首都国际机场 T3", 30, false);
        UUID second = UUID.fromString("99999999-8888-7777-6666-555555555555");
        BorderSession other = new BorderSession(second, "李四", "P-87654321", "", "87654321",
                BorderSession.KIND_VISA_FREE, "minecraft:overworld", new BlockPos(5, 64, 5),
                1_700_000_500_000L, "上海浦东国际机场 T2", 15, true);
        data.putSession(first);
        data.putSession(other);
        data.addLog(new BorderLog(1_700_000_100_000L, TRAVELLER, "张三", BorderLog.IN,
                BorderLog.ALLOW_IN, "E12345678", "12345678", BorderSession.KIND_VISA,
                "minecraft:overworld@0,64,0", ""));
        data.addLog(new BorderLog(1_700_000_200_000L, TRAVELLER, "张三", BorderLog.IN,
                BorderLog.DENY_IN, "E12345678", "12345678", BorderSession.KIND_VISA,
                "minecraft:overworld@0,64,0", BorderService.REASON_ALREADY_ENTERED));

        CompoundTag saved = data.save(new CompoundTag());
        assertTrue(saved.contains("sessions"));
        assertTrue(saved.contains("logs"));

        BorderTravelData loaded = BorderTravelData.load(saved);
        assertEquals(2, loaded.sessionCount());
        assertEquals(first, loaded.session(TRAVELLER));
        assertEquals(other, loaded.session(second));
        assertEquals(2, loaded.logCount());
        assertEquals(data.logs(), loaded.logs());
        assertEquals(BorderLog.ALLOW_IN, loaded.logs().get(0).result());
        assertEquals(BorderLog.DENY_IN, loaded.logs().get(1).result());
        assertEquals(BorderService.REASON_ALREADY_ENTERED, loaded.logs().get(1).detail());
    }

    @Test
    void emptyOrMissingTagLoadsAnEmptyStore() {
        assertEquals(0, BorderTravelData.load(new CompoundTag()).sessionCount());
        assertEquals(0, BorderTravelData.load(new CompoundTag()).logCount());
        assertEquals(0, BorderTravelData.load(null).sessionCount());
    }

    @Test
    void logsAreCappedAtFiveHundredNewestKept() {
        BorderTravelData data = new BorderTravelData();
        int total = BorderTravelData.MAX_LOGS + 25;
        for (int i = 1; i <= total; i++) {
            data.addLog(new BorderLog(i, TRAVELLER, "张三", BorderLog.IN, BorderLog.ALLOW_IN,
                    "E12345678", "", BorderSession.KIND_ALL, "", ""));
        }
        assertEquals(BorderTravelData.MAX_LOGS, data.logCount());
        // Oldest 25 were dropped; the retained window starts at 26.
        assertEquals(26L, data.logs().get(0).epochMillis());
        assertEquals((long) total, data.logs().get(data.logCount() - 1).epochMillis());
        // Officer view is newest first.
        assertEquals((long) total, data.recentLogs(1).get(0).epochMillis());
        assertEquals(10, data.recentLogs(10).size());

        BorderTravelData reloaded = BorderTravelData.load(data.save(new CompoundTag()));
        assertEquals(BorderTravelData.MAX_LOGS, reloaded.logCount());
        assertEquals(26L, reloaded.logs().get(0).epochMillis());
    }

    @Test
    void overstayUsesEntryGateDaysAndIsStrict() {
        long now = 1_800_000_000_000L;
        // Exactly the allowance is still on time.
        BorderSession onTime = sessionEnteredAt(now - 30 * DAY, 30);
        assertFalse(onTime.overstayed(now));
        assertEquals(0, onTime.daysOverstayed(now));

        BorderSession oneMillisLate = sessionEnteredAt(now - 30 * DAY - 1, 30);
        assertTrue(oneMillisLate.overstayed(now));
        assertEquals(1, oneMillisLate.daysOverstayed(now));

        BorderSession oneDayLate = sessionEnteredAt(now - 31 * DAY, 30);
        assertTrue(oneDayLate.overstayed(now));
        assertEquals(1, oneDayLate.daysOverstayed(now));

        BorderSession twoDaysLate = sessionEnteredAt(now - 32 * DAY, 30);
        assertEquals(2, twoDaysLate.daysOverstayed(now));

        // The allowance captured at entry wins over the default.
        BorderSession shortStay = sessionEnteredAt(now - 2 * DAY, 1);
        assertTrue(shortStay.overstayed(now));
        BorderSession longStay = sessionEnteredAt(now - 20 * DAY, 90);
        assertFalse(longStay.overstayed(now));

        // Zero days means the traveller is late one millisecond after entry.
        assertTrue(sessionEnteredAt(now - 1, 0).overstayed(now));
    }

    @Test
    void sessionDaysAreClampedAndDeadlineIsExact() {
        BorderSession session = new BorderSession(TRAVELLER, "张三", "E1", "", "",
                BorderSession.KIND_ALL, "minecraft:overworld", BlockPos.ZERO, 1_000L, "", -5, false);
        assertEquals(0, session.overstayDays());
        BorderSession capped = new BorderSession(TRAVELLER, "张三", "E1", "", "",
                BorderSession.KIND_ALL, "minecraft:overworld", BlockPos.ZERO, 1_000L, "", 99_999, false);
        assertEquals(BorderSession.MAX_OVERSTAY_DAYS, capped.overstayDays());
        assertEquals(1_000L + 30 * DAY, sessionEnteredAt(1_000L, 30).overstayDeadlineMillis());
    }

    @Test
    void oneSessionPerPlayerIsReplacedNotDuplicated() {
        BorderTravelData data = new BorderTravelData();
        BorderSession first = new BorderSession(TRAVELLER, "张三", "E1", "", "",
                BorderSession.KIND_ALL, "minecraft:overworld", BlockPos.ZERO, 1_000L, "", 30, false);
        BorderSession second = new BorderSession(TRAVELLER, "张三", "E2", "V-9",
                "99999999", BorderSession.KIND_VISA, "minecraft:overworld", BlockPos.ZERO, 2_000L, "", 30, false);
        data.putSession(first);
        data.putSession(second);
        assertEquals(1, data.sessionCount());
        assertEquals(second, data.session(TRAVELLER));
        assertEquals(second, data.clearSession(TRAVELLER));
        assertNull(data.session(TRAVELLER));
        assertNull(data.clearSession(TRAVELLER));
        assertEquals(0, data.sessionCount());
    }

    @Test
    void sessionsAreListedNewestEntryFirst() {
        BorderTravelData data = new BorderTravelData();
        BorderSession older = new BorderSession(TRAVELLER, "张三", "E1", "", "",
                BorderSession.KIND_ALL, "minecraft:overworld", BlockPos.ZERO, 1_000L, "", 30, false);
        BorderSession newer = new BorderSession(UUID.randomUUID(), "李四", "E2", "", "",
                BorderSession.KIND_ALL, "minecraft:overworld", BlockPos.ZERO, 9_000L, "", 30, false);
        data.putSession(older);
        data.putSession(newer);
        assertEquals(newer, data.sessions().get(0));
    }

    @Test
    void overstayFlagSurvivesTheRoundTrip() {
        BorderSession session = sessionEnteredAt(1_000L, 30).withOverstay(true);
        BorderSession loaded = BorderSession.fromTag(session.toTag());
        assertNotNull(loaded);
        assertTrue(loaded.overstay());
        assertFalse(sessionEnteredAt(1_000L, 30).overstay());
    }

    @Test
    void permitsAndPassportsAreStampableButIdentityCardsAreNot() {
        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        DocumentData idCard = DocumentFactory.sample(DocStyle.CN_ID);

        assertTrue(DocumentValidator.isStampable(DocumentKind.PASSPORT));
        assertTrue(DocumentValidator.isStampable(DocumentKind.PERMIT));
        assertFalse(DocumentValidator.isStampable(DocumentKind.ID_CARD));

        assertTrue(DocumentValidator.validate(passport, DeviceType.ENTRY_STAMP, "").valid());
        assertTrue(DocumentValidator.validate(permit, DeviceType.ENTRY_STAMP, "").valid());
        assertTrue(DocumentValidator.validate(permit, DeviceType.EXIT_STAMP, "").valid());
        assertTrue(DocumentValidator.validate(permit, DeviceType.STAMP_HISTORY, "").valid());

        assertEquals(VerificationResult.Code.WRONG_KIND,
                DocumentValidator.validate(idCard, DeviceType.ENTRY_STAMP, "").code());
        assertEquals(VerificationResult.Code.WRONG_KIND,
                DocumentValidator.validate(idCard, DeviceType.STAMP_HISTORY, "").code());

        // A permit physically carries stamps like a passport does.
        permit.addStamp(StampRecord.IN, "北京首都国际机场 T3", "中国", "边检警官");
        assertEquals(1, permit.stamps().size());
        assertTrue(permit.integrityValid());
        assertTrue(DocumentValidator.validate(permit, DeviceType.STAMP_HISTORY, "").valid());
    }

    @Test
    void exitGateIgnoresCodesAndMode() {
        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        // Exit reads the stored session; gate codes/mode must not block it.
        assertTrue(DocumentValidator.validateGate(permit, DeviceType.EXIT_GATE, "visa",
                List.of("99999999")).valid());
        assertTrue(DocumentValidator.validateGate(permit, DeviceType.EXIT_GATE, "endorsement",
                List.of()).valid());
    }

    @Test
    void entryMatchBindsThePresentedCredential() {
        DocumentData passport = DocumentFactory.sample(DocStyle.US_PASSPORT);
        String visaCode = passport.visas().get(0).code();
        BorderService.EntryMatch visa = BorderService.resolveEntry(passport, "visa", List.of(visaCode));
        assertNotNull(visa);
        assertEquals(BorderSession.KIND_VISA, visa.kind());
        assertEquals(passport.visas().get(0).id(), visa.recordId());
        assertEquals(visaCode, visa.code());
        assertNull(BorderService.resolveEntry(passport, "visa", List.of("99999999")));

        String freeCode = uniqueFreeCode(passport);
        assertTrue(passport.addVisaFreeCode(freeCode));
        BorderService.EntryMatch free = BorderService.resolveEntry(passport, "visa", List.of(freeCode));
        assertNotNull(free);
        assertEquals(BorderSession.KIND_VISA_FREE, free.kind());
        assertEquals("", free.recordId());
        assertEquals(freeCode, free.code());

        // all never binds a record and never consumes.
        BorderService.EntryMatch all = BorderService.resolveEntry(passport, "all", List.of());
        assertNotNull(all);
        assertEquals(BorderSession.KIND_ALL, all.kind());
        assertFalse(new BorderSession(TRAVELLER, "张三", "E1", "", "", all.kind(),
                "minecraft:overworld", BlockPos.ZERO, 1_000L, "", 30, false).consumesRecord());

        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        String endorsementCode = permit.endorsements().get(0).code();
        BorderService.EntryMatch endorsement = BorderService.resolveEntry(permit, "endorsement",
                List.of(endorsementCode));
        assertNotNull(endorsement);
        assertEquals(BorderSession.KIND_ENDORSEMENT, endorsement.kind());
        assertEquals(permit.endorsements().get(0).id(), endorsement.recordId());
        assertEquals(BorderSession.KIND_ENDORSEMENT,
                BorderService.resolveEntry(permit, "both", List.of(endorsementCode)).kind());

        // Wrong document kind or unmatched code resolves to nothing.
        assertNull(BorderService.resolveEntry(permit, "visa", List.of(endorsementCode)));
        assertNull(BorderService.resolveEntry(passport, "endorsement", List.of(visaCode)));
        assertNull(BorderService.resolveEntry(passport, "visa", List.of()));
    }

    private static BorderSession sessionEnteredAt(long entryEpochMillis, int overstayDays) {
        return new BorderSession(TRAVELLER, "张三", "E12345678", "V-1", "12345678",
                BorderSession.KIND_VISA, "minecraft:overworld", new BlockPos(0, 64, 0),
                entryEpochMillis, "北京首都国际机场 T3", overstayDays, false);
    }

    /** A visa-free code that cannot collide with the document's own codes. */
    private static String uniqueFreeCode(DocumentData doc) {
        int value = 20_000_000;
        while (doc.allCredentialCodes().contains(String.format("%08d", value))) {
            value++;
        }
        return String.format("%08d", value);
    }
}
