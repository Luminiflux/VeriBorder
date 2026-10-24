package com.veriborder.border;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Border data migration + deferred persistence (26.3 round 11, items 8 and 18).
 *
 * <p>The 26.3 border store moved from one session per player to one entry per
 * person; {@link BorderTravelData#migrate(CompoundTag)} performs that rewrite in
 * place. Writes are batched: mutators only mark the store pending, and the
 * {@link BorderTravelData#flush()}/{@link BorderTravelData#flushIfDue(long)}
 * pair commits the SavedData dirty flag at most once per window.</p>
 */
class BorderTravelDataMigrationTest {
    private static final UUID TRAVELLER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static CompoundTag legacySessionsTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", 2);
        ListTag sessions = new ListTag();
        CompoundTag session = new CompoundTag();
        session.putUUID("player", TRAVELLER);
        session.putString("playerName", "ALEX");
        session.putString("document", "E12345678");
        session.putString("holder", "ALEX CARTER");
        session.putString("recordId", "V-1");
        session.putString("code", "12345678");
        session.putString("kind", BorderEntry.KIND_VISA);
        session.putString("dimension", "minecraft:overworld");
        session.putInt("gateX", 3);
        session.putInt("gateY", 64);
        session.putInt("gateZ", -2);
        session.putLong("entryTime", 1_700_000_000_000L);
        session.putString("port", "SFO");
        session.putInt("overstayDays", 30);
        session.putBoolean("overstay", false);
        sessions.add(session);
        tag.put("sessions", sessions);
        return tag;
    }

    @Test
    void sessionsBecomeEntriesWithAPersonKey() {
        CompoundTag tag = legacySessionsTag();

        assertTrue(BorderTravelData.migrate(tag));

        assertEquals(BorderTravelData.SCHEMA, tag.getInt("schema"));
        assertFalse(tag.contains("sessions"), "the legacy list is consumed, not duplicated");
        ListTag entries = tag.getList("entries", Tag.TAG_COMPOUND);
        assertEquals(1, entries.size());
        // Person keys normalise the holder name (lower-case, collapsed spaces).
        assertEquals("alex carter|E12345678", entries.getCompound(0).getString("personKey"));
        assertTrue(tag.contains("logs", Tag.TAG_LIST));

        BorderTravelData data = BorderTravelData.load(tag);
        assertEquals(1, data.entryCount());
        BorderEntry entry = data.entries().get(0);
        assertNotNull(entry);
        assertEquals(TRAVELLER, entry.player());
        assertEquals("alex carter|E12345678", entry.personKey());
        assertEquals(entry, data.entryByDocument("E12345678"));

        assertFalse(BorderTravelData.migrate(tag), "migration is idempotent");
    }

    @Test
    void anExplicitPersonKeySurvivesTheMigration() {
        CompoundTag tag = legacySessionsTag();
        CompoundTag session = tag.getList("sessions", Tag.TAG_COMPOUND).getCompound(0);
        session.putString("personKey", "ALEX CARTER|1988-02-02");

        BorderTravelData.migrate(tag);

        assertEquals("ALEX CARTER|1988-02-02",
                tag.getList("entries", Tag.TAG_COMPOUND).getCompound(0).getString("personKey"));
    }

    @Test
    void currentDataIsNotMigratedAgain() {
        BorderTravelData data = new BorderTravelData();
        data.putEntry(new BorderEntry("ALEX CARTER|1988-02-02", "E12345678", "ALEX CARTER", "",
                "", BorderEntry.KIND_NONE, "minecraft:overworld", BlockPos.ZERO, 1_000L, "", 30, false,
                TRAVELLER, "ALEX"));
        CompoundTag saved = data.save(new CompoundTag());

        assertFalse(BorderTravelData.migrate(saved));
        assertTrue(saved.getList("entries", Tag.TAG_COMPOUND).size() == 1);
    }

    @Test
    void writesArePendingUntilAFlushCommitsThem() {
        BorderTravelData data = new BorderTravelData();
        assertFalse(data.hasPendingWrites(), "a fresh store has nothing to write");
        assertFalse(data.flush(), "flushing clean data is a no-op");
        assertFalse(data.isDirty());

        data.putEntry(new BorderEntry("ALEX CARTER|1988-02-02", "E12345678", "ALEX CARTER", "",
                "", BorderEntry.KIND_NONE, "minecraft:overworld", BlockPos.ZERO, 1_000L, "", 30, false,
                TRAVELLER, "ALEX"));
        assertTrue(data.hasPendingWrites(), "a write only marks the store pending");
        assertFalse(data.isDirty(), "the SavedData dirty flag is not touched on every write");

        assertTrue(data.flushIfDue(100L), "the first flush after a write commits");
        assertFalse(data.hasPendingWrites());
        assertTrue(data.isDirty(), "committing the pending write sets the SavedData dirty flag");

        data.addLog(new BorderLog(1_100L, TRAVELLER, "ALEX", BorderLog.IN, BorderLog.ALLOW_IN,
                "E12345678", "", BorderEntry.KIND_NONE, "", ""));
        assertTrue(data.hasPendingWrites());
        assertFalse(data.flushIfDue(150L), "inside the window the flush is deferred");
        assertTrue(data.hasPendingWrites(), "the write stays pending");
        assertTrue(data.flushIfDue(200L), "after FLUSH_INTERVAL_TICKS the write is committed");
    }

    @Test
    void saveAndLoadResetThePendingState() {
        BorderTravelData data = new BorderTravelData();
        data.addLog(new BorderLog(1_100L, TRAVELLER, "ALEX", BorderLog.IN, BorderLog.ALLOW_IN,
                "E12345678", "", BorderEntry.KIND_NONE, "", ""));
        assertTrue(data.hasPendingWrites());

        CompoundTag saved = data.save(new CompoundTag());
        assertFalse(data.hasPendingWrites(), "the save wrote everything");
        assertEquals(BorderTravelData.SCHEMA, saved.getInt("schema"));

        BorderTravelData loaded = BorderTravelData.load(saved);
        assertFalse(loaded.hasPendingWrites(), "freshly loaded data is clean");
        assertEquals(1, loaded.logCount());
    }

    @Test
    void anEmptyTagGetsACompleteCurrentLayout() {
        CompoundTag tag = new CompoundTag();
        assertTrue(BorderTravelData.migrate(tag));
        assertEquals(BorderTravelData.SCHEMA, tag.getInt("schema"));
        assertTrue(tag.contains("entries", Tag.TAG_LIST));
        assertTrue(tag.contains("logs", Tag.TAG_LIST));

        BorderTravelData data = BorderTravelData.load(tag);
        assertEquals(0, data.entryCount());
        assertEquals(0, data.logCount());
        assertNull(data.entryByDocument("E12345678"));
    }
}
