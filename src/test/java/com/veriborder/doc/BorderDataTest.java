package com.veriborder.doc;

import com.veriborder.block.DeviceType;
import com.veriborder.border.BorderEntry;
import com.veriborder.border.BorderLog;
import com.veriborder.border.BorderService;
import com.veriborder.border.BorderTravelData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Border data tests (26.3): entry/log NBT round-trips, the 500-entry log cap,
 * overstay arithmetic, one entry per person, the document index and permit
 * stamp acceptance.
 *
 * <p>All of these run without a world because {@link BorderTravelData} only
 * serialises to NBT; the overworld storage is resolved through
 * {@code BorderTravelData.get(server)} at runtime.</p>
 */
class BorderDataTest {
    private static final UUID TRAVELLER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final long DAY = BorderEntry.MILLIS_PER_DAY;
    private static final String PERSON = "张三|1990-01-01";

    @Test
    void borderEntryRoundTripsThroughNbt() {
        long entry = 1_700_000_000_000L;
        BorderEntry record = new BorderEntry(PERSON, "E12345678", "张三", "V-ABC12345",
                "12345678", BorderEntry.KIND_VISA, "minecraft:overworld",
                new BlockPos(10, 64, -3), entry, "北京首都国际机场 T3", 30, true, TRAVELLER, "ALEX");

        CompoundTag tag = record.toTag();
        assertTrue(tag.contains("personKey"));
        assertTrue(tag.contains("document"));
        assertEquals(entry, tag.getLong("entryTime"));
        assertEquals(TRAVELLER, tag.getUUID("player"));

        BorderEntry loaded = BorderEntry.fromTag(tag);
        assertNotNull(loaded);
        assertEquals(record, loaded);
        assertEquals(record.personKey(), loaded.personKey());
        assertEquals(record.holder(), loaded.holder());
        assertEquals(record.gatePos(), loaded.gatePos());
        assertEquals(record.port(), loaded.port());
        assertEquals("ALEX", loaded.playerName());
        assertTrue(loaded.overstayed());
        assertTrue(loaded.consumesRecord());
    }

    @Test
    void officerEntryTagKeepsTheDocumentedFields() {
        BorderEntry record = new BorderEntry(PERSON, "E12345678", "张三", "V-ABC12345",
                "12345678", BorderEntry.KIND_VISA, "minecraft:overworld", new BlockPos(1, 2, 3),
                1_700_000_000_000L, "北京首都国际机场 T3", 30, false, TRAVELLER, "ALEX");
        CompoundTag officer = record.toOfficerTag();
        for (String key : List.of("player", "playerName", "personKey", "document", "holder", "kind", "code",
                "gateX", "gateY", "gateZ", "dimension", "entryTime", "overstay", "overstayDays")) {
            assertTrue(officer.contains(key), "officer entry key " + key);
        }
        // Internal binding stays on the server.
        assertFalse(officer.contains("recordId"));
    }

    @Test
    void borderTravelDataRoundTripsEntriesAndLogs() {
        BorderTravelData data = new BorderTravelData();
        BorderEntry first = new BorderEntry(PERSON, "E12345678", "张三", "V-1",
                "12345678", BorderEntry.KIND_VISA, "minecraft:overworld", new BlockPos(0, 64, 0),
                1_700_000_000_000L, "北京首都国际机场 T3", 30, false, TRAVELLER, "ALEX");
        UUID second = UUID.fromString("99999999-8888-7777-6666-555555555555");
        BorderEntry other = new BorderEntry("李四|1988-02-02", "P-87654321", "李四", "", "87654321",
                BorderEntry.KIND_VISA_FREE, "minecraft:overworld", new BlockPos(5, 64, 5),
                1_700_000_500_000L, "上海浦东国际机场 T2", 15, true, second, "BOB");
        data.putEntry(first);
        data.putEntry(other);
        data.addLog(new BorderLog(1_700_000_100_000L, TRAVELLER, "ALEX", BorderLog.IN,
                BorderLog.ALLOW_IN, "E12345678", "12345678", BorderEntry.KIND_VISA,
                "minecraft:overworld@0,64,0", ""));
        data.addLog(new BorderLog(1_700_000_200_000L, TRAVELLER, "ALEX", BorderLog.IN,
                BorderLog.DENY_IN, "E12345678", "12345678", BorderEntry.KIND_VISA,
                "minecraft:overworld@0,64,0", BorderService.REASON_ALREADY_ENTERED));

        CompoundTag saved = data.save(new CompoundTag());
        assertEquals(3, saved.getInt("schema"));
        assertTrue(saved.contains("entries"));
        assertTrue(saved.contains("logs"));
        assertFalse(saved.contains("sessions"), "26.3 no longer writes per-player sessions");

        BorderTravelData loaded = BorderTravelData.load(saved);
        assertEquals(2, loaded.entryCount());
        assertEquals(first, loaded.entry(PERSON));
        assertEquals(other, loaded.entry("李四|1988-02-02"));
        assertEquals(2, loaded.logCount());
        assertEquals(data.logs(), loaded.logs());
        assertEquals(BorderLog.ALLOW_IN, loaded.logs().get(0).result());
        assertEquals(BorderLog.DENY_IN, loaded.logs().get(1).result());
        assertEquals(BorderService.REASON_ALREADY_ENTERED, loaded.logs().get(1).detail());
        // The document index is a pure function of the entries.
        assertEquals(first, loaded.entryByDocument("E12345678"));
        assertEquals(other, loaded.entryByDocument("P-87654321"));
        assertEquals(PERSON, loaded.personKeyForDocument("E12345678"));
    }

    @Test
    void prePersonKeySessionsAreMigrated() {
        // Exactly the 26.2 "sessions" layout: one compound per player, no person key.
        CompoundTag legacy = new CompoundTag();
        legacy.putInt("schema", 2);
        ListTag sessions = new ListTag();
        CompoundTag session = new CompoundTag();
        session.putUUID("player", TRAVELLER);
        session.putString("playerName", "ALEX");
        session.putString("document", "E12345678");
        session.putString("holder", "张三");
        session.putString("recordId", "V-1");
        session.putString("code", "12345678");
        session.putString("kind", BorderEntry.KIND_VISA);
        session.putString("dimension", "minecraft:overworld");
        session.putInt("gateX", 1);
        session.putInt("gateY", 64);
        session.putInt("gateZ", 2);
        session.putLong("entryTime", 1_700_000_000_000L);
        session.putString("port", "Beijing");
        session.putInt("overstayDays", 30);
        session.putBoolean("overstay", false);
        sessions.add(session);
        legacy.put("sessions", sessions);

        BorderTravelData migrated = BorderTravelData.load(legacy);
        assertEquals(1, migrated.entryCount());
        BorderEntry entry = migrated.entries().get(0);
        assertNotNull(entry);
        assertEquals("张三|E12345678", entry.personKey(),
                "a session without a person key falls back to holder + document number");
        assertEquals(TRAVELLER, entry.player());
        assertEquals("ALEX", entry.playerName());
        assertEquals(entry, migrated.entryForPlayer(TRAVELLER));
        assertEquals(entry, migrated.entryByDocument("E12345678"));
    }

    @Test
    void emptyOrMissingTagLoadsAnEmptyStore() {
        assertEquals(0, BorderTravelData.load(new CompoundTag()).entryCount());
        assertEquals(0, BorderTravelData.load(new CompoundTag()).logCount());
        assertEquals(0, BorderTravelData.load(null).entryCount());
    }

    @Test
    void logsAreCappedAtFiveHundredNewestKept() {
        BorderTravelData data = new BorderTravelData();
        int total = BorderTravelData.MAX_LOGS + 25;
        for (int i = 1; i <= total; i++) {
            data.addLog(new BorderLog(i, TRAVELLER, "ALEX", BorderLog.IN, BorderLog.ALLOW_IN,
                    "E12345678", "", BorderEntry.KIND_NONE, "", ""));
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
        BorderEntry onTime = enteredAt(now - 30 * DAY, 30);
        assertFalse(onTime.overstayed(now));
        assertEquals(0, onTime.daysOverstayed(now));

        BorderEntry oneMillisLate = enteredAt(now - 30 * DAY - 1, 30);
        assertTrue(oneMillisLate.overstayed(now));
        assertEquals(1, oneMillisLate.daysOverstayed(now));

        BorderEntry oneDayLate = enteredAt(now - 31 * DAY, 30);
        assertTrue(oneDayLate.overstayed(now));
        assertEquals(1, oneDayLate.daysOverstayed(now));

        BorderEntry twoDaysLate = enteredAt(now - 32 * DAY, 30);
        assertEquals(2, twoDaysLate.daysOverstayed(now));

        // The allowance captured at entry wins over the default.
        BorderEntry shortStay = enteredAt(now - 2 * DAY, 1);
        assertTrue(shortStay.overstayed(now));
        BorderEntry longStay = enteredAt(now - 20 * DAY, 90);
        assertFalse(longStay.overstayed(now));

        // Zero days means the traveller is late one millisecond after entry.
        assertTrue(enteredAt(now - 1, 0).overstayed(now));
    }

    @Test
    void entryDaysAreClampedAndDeadlineIsExact() {
        BorderEntry entry = new BorderEntry(PERSON, "E1", "张三", "", "",
                BorderEntry.KIND_NONE, "minecraft:overworld", BlockPos.ZERO, 1_000L, "", -5, false);
        assertEquals(0, entry.overstayDays());
        BorderEntry capped = new BorderEntry(PERSON, "E1", "张三", "", "",
                BorderEntry.KIND_NONE, "minecraft:overworld", BlockPos.ZERO, 1_000L, "", 99_999, false);
        assertEquals(BorderEntry.MAX_OVERSTAY_DAYS, capped.overstayDays());
        assertEquals(1_000L + 30 * DAY, enteredAt(1_000L, 30).overstayDeadlineMillis());
    }

    @Test
    void oneEntryPerPersonIsReplacedNotDuplicated() {
        BorderTravelData data = new BorderTravelData();
        BorderEntry first = new BorderEntry(PERSON, "E1", "张三", "", "",
                BorderEntry.KIND_NONE, "minecraft:overworld", BlockPos.ZERO, 1_000L, "", 30, false);
        BorderEntry second = new BorderEntry(PERSON, "E2", "张三", "V-9",
                "99999999", BorderEntry.KIND_VISA, "minecraft:overworld", BlockPos.ZERO, 2_000L, "", 30, false);
        data.putEntry(first);
        data.putEntry(second);
        assertEquals(1, data.entryCount(), "one person can only have one active entry");
        assertEquals(second, data.entry(PERSON));
        // The person moved to a new document: the old number no longer resolves,
        // the new one does.
        assertNull(data.entryByDocument("E1"));
        assertEquals(second, data.entryByDocument("E2"));
        assertEquals(second, data.removeEntry(PERSON));
        assertNull(data.entry(PERSON));
        assertNull(data.removeEntry(PERSON));
        assertEquals(0, data.entryCount());
    }

    @Test
    void entriesAreListedNewestEntryFirst() {
        BorderTravelData data = new BorderTravelData();
        BorderEntry older = new BorderEntry(PERSON, "E1", "张三", "", "",
                BorderEntry.KIND_NONE, "minecraft:overworld", BlockPos.ZERO, 1_000L, "", 30, false);
        BorderEntry newer = new BorderEntry("李四|1991-01-01", "E2", "李四", "", "",
                BorderEntry.KIND_NONE, "minecraft:overworld", BlockPos.ZERO, 9_000L, "", 30, false);
        data.putEntry(older);
        data.putEntry(newer);
        assertEquals(newer, data.entries().get(0));
    }

    @Test
    void overstayFlagSurvivesTheRoundTrip() {
        BorderEntry entry = enteredAt(1_000L, 30).withOverstay(true);
        BorderEntry loaded = BorderEntry.fromTag(entry.toTag());
        assertNotNull(loaded);
        // The persisted flag is the record's state; overstayed() recomputes from
        // the entry time, and this fixture entered in 1970, so it would always be
        // true. Read the stored flag from the serialised form instead.
        assertTrue(loaded.toTag().getBoolean("overstay"), "the persisted overstay flag round-trips");
        assertFalse(enteredAt(1_000L, 30).toTag().getBoolean("overstay"));
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
        assertTrue(permit.stamps().get(0).hasTime(), "26.3 stamps carry the clock time");
        assertTrue(permit.integrityValid());
        assertTrue(DocumentValidator.validate(permit, DeviceType.STAMP_HISTORY, "").valid());
    }

    @Test
    void exitGateIgnoresCodesAndMode() {
        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        // Exit reads the stored entry; gate codes/mode must not block it.
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
        assertEquals(BorderEntry.KIND_VISA, visa.kind());
        assertEquals(passport.visas().get(0).id(), visa.recordId());
        assertEquals(visaCode, visa.code());
        assertNull(BorderService.resolveEntry(passport, "visa", List.of("99999999")));

        // Wrong document kind or unmatched code resolves to nothing while the
        // document has no permanent codes.
        assertNull(BorderService.resolveEntry(passport, "endorsement", List.of(visaCode)));
        assertNull(BorderService.resolveEntry(passport, "visa", List.of()));

        String freeCode = uniqueFreeCode(passport);
        assertTrue(passport.addVisaFreeCode(freeCode));
        BorderService.EntryMatch free = BorderService.resolveEntry(passport, "visa", List.of(freeCode));
        assertNotNull(free);
        assertEquals(BorderEntry.KIND_VISA_FREE, free.kind());
        assertEquals("", free.recordId());
        assertEquals(freeCode, free.code());

        // 26.3 permanent-code slot: a country-less (legacy) code is accepted by
        // every mode, including endorsement and both.
        assertEquals(BorderEntry.KIND_VISA_FREE,
                BorderService.resolveEntry(passport, "endorsement", List.of(visaCode)).kind());
        assertEquals(BorderEntry.KIND_VISA_FREE,
                BorderService.resolveEntry(passport, "both", List.of()).kind());

        // 26.3: the removed "all" mode normalises to both, so it no longer grants
        // an entry without a credential. A fresh document with no permanent code
        // and no gate code is refused.
        DocumentData plain = DocumentFactory.sample(DocStyle.US_PASSPORT);
        assertNull(BorderService.resolveEntry(plain, "all", List.of()));
        assertNull(BorderService.resolveEntry(plain, "any", List.of()));
        assertEquals(DocumentValidator.GATE_MODE_BOTH, DocumentValidator.normalizeMode("all"),
                "the legacy all mode loads as both");

        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        String endorsementCode = permit.endorsements().get(0).code();
        BorderService.EntryMatch endorsement = BorderService.resolveEntry(permit, "endorsement",
                List.of(endorsementCode));
        assertNotNull(endorsement);
        assertEquals(BorderEntry.KIND_ENDORSEMENT, endorsement.kind());
        assertEquals(permit.endorsements().get(0).id(), endorsement.recordId());
        assertEquals(BorderEntry.KIND_ENDORSEMENT,
                BorderService.resolveEntry(permit, "both", List.of(endorsementCode)).kind());

        // A permit without permanent codes still rejects a visa-mode check.
        assertNull(BorderService.resolveEntry(permit, "visa", List.of(endorsementCode)));
    }

    private static BorderEntry enteredAt(long entryEpochMillis, int overstayDays) {
        return new BorderEntry(PERSON, "E12345678", "张三", "V-1", "12345678",
                BorderEntry.KIND_VISA, "minecraft:overworld", new BlockPos(0, 64, 0),
                entryEpochMillis, "北京首都国际机场 T3", overstayDays, false, TRAVELLER, "ALEX");
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
