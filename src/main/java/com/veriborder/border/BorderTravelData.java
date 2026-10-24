package com.veriborder.border;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * World-scoped border state (26.3): the active entry of every traveller plus a
 * capped event log. Persisted through the overworld {@code DimensionDataStorage},
 * so an entry survives logout, dimension changes and server restarts.
 *
 * <p>Entries are keyed by {@link BorderEntry#personKey()} — one person can only
 * have one active entry, no matter which of their documents is presented — and
 * {@link #documentIndex} maps a document number to the person that presented it,
 * which is the fast path the exit gate needs to find the bound credential
 * record. A person holding a second document still has exactly one entry.</p>
 *
 * <p>There is deliberately no static world state: every caller resolves the
 * instance through {@link #get(MinecraftServer)}.</p>
 *
 * <p>NBT layout (the vanilla {@code data} wrapper is added by the storage):</p>
 * <pre>
 * schema    int
 * entries   ListTag of BorderEntry compounds ("personKey", "document", "holder",
 *           "recordId", "code", "kind", "dimension", "gateX", "gateY", "gateZ",
 *           "entryTime", "port", "overstayDays", "overstay", "player",
 *           "playerName")
 * logs      ListTag of BorderLog compounds ("time", "player", "playerName",
 *           "direction", "result", "document", "code", "kind", "gate", "detail")
 * </pre>
 *
 * <p>The pre-26.3 {@code sessions} list is read as a migration: it uses the same
 * individual keys and {@link BorderEntry#fromTag} synthesises the person key from
 * holder name + document number when none is stored.</p>
 */
public final class BorderTravelData extends SavedData {
    /** Data file name inside the overworld data storage. */
    public static final String DATA_NAME = "veriborder_border";
    /** Logs kept on disk; older entries are dropped first. */
    public static final int MAX_LOGS = 500;
    public static final int SCHEMA = 3;
    /**
     * Ticks between two deferred {@code setDirty()} commits. Writes only mark the
     * data as pending; {@link BorderPersistence} flushes at most this often, and
     * unconditionally on shutdown.
     */
    public static final int FLUSH_INTERVAL_TICKS = 100;

    /** personKey -> active entry (at most one entry per person). */
    private final Map<String, BorderEntry> entries = new LinkedHashMap<>();
    /** documentNumber -> personKey of the entry recorded on that document. */
    private final Map<String, String> documentIndex = new LinkedHashMap<>();
    private final List<BorderLog> logs = new ArrayList<>();
    /** In-memory state changed, but the SavedData dirty flag is not set yet. */
    private boolean pendingWrites;
    /** Game time of the last committed flush ({@code Long.MIN_VALUE} = never). */
    private long lastFlushTick = Long.MIN_VALUE;

    public BorderTravelData() {
    }

    /**
     * Resolves the single world instance. Safe to call every tick: the storage
     * caches the object and only deserialises on first use per server run.
     */
    public static BorderTravelData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(BorderTravelData::load,
                BorderTravelData::new, DATA_NAME);
    }

    /** Deserialisation entry point used by the storage; tolerant of junk tags. */
    public static BorderTravelData load(@Nullable CompoundTag tag) {
        BorderTravelData data = new BorderTravelData();
        if (tag == null) {
            return data;
        }
        migrate(tag);
        loadEntries(data, tag.getList("entries", Tag.TAG_COMPOUND));
        ListTag logList = tag.getList("logs", Tag.TAG_COMPOUND);
        for (int i = 0; i < logList.size(); i++) {
            BorderLog entry = BorderLog.fromTag(logList.getCompound(i));
            if (entry != null) {
                data.logs.add(entry);
            }
        }
        data.trimLogs();
        data.pendingWrites = false;
        return data;
    }

    /**
     * Upgrades a persisted border tag in place to {@link #SCHEMA}.
     *
     * <p>26.3 replaced the per-player {@code sessions} list with the
     * per-person {@code entries} list plus a document index. Both lists use the
     * same compound keys, so the migration copies every session over and
     * synthesises the person key ({@code holder|birthDate} via
     * {@link BorderEntry#fallbackPersonKey(String, String)}) when an older build
     * stored none. The legacy list is then removed, so a downgrade cannot read
     * half-migrated state, and the schema is stamped.</p>
     *
     * @return {@code true} when the tag changed (a second call returns {@code false})
     */
    public static boolean migrate(@Nullable CompoundTag tag) {
        if (tag == null) {
            return false;
        }
        int schema = tag.contains("schema", Tag.TAG_INT) ? tag.getInt("schema") : 0;
        if (schema >= SCHEMA) {
            return false;
        }
        ListTag entries = tag.contains("entries", Tag.TAG_LIST)
                ? tag.getList("entries", Tag.TAG_COMPOUND) : new ListTag();
        ListTag legacy = tag.getList("sessions", Tag.TAG_COMPOUND);
        for (int i = 0; i < legacy.size(); i++) {
            CompoundTag converted = legacy.getCompound(i).copy();
            if (converted.getString("personKey").isBlank()) {
                converted.putString("personKey", BorderEntry.fallbackPersonKey(
                        converted.getString("holder"), converted.getString("document")));
            }
            entries.add(converted);
        }
        tag.put("entries", entries);
        tag.remove("sessions");
        if (!tag.contains("logs", Tag.TAG_LIST)) {
            tag.put("logs", new ListTag());
        }
        tag.putInt("schema", SCHEMA);
        return true;
    }

    private static void loadEntries(BorderTravelData data, ListTag list) {
        for (int i = 0; i < list.size(); i++) {
            BorderEntry entry = BorderEntry.fromTag(list.getCompound(i));
            if (entry != null && !entry.personKey().isBlank()) {
                data.putEntry(entry);
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("schema", SCHEMA);
        ListTag entryList = new ListTag();
        for (BorderEntry entry : entries.values()) {
            entryList.add(entry.toTag());
        }
        tag.put("entries", entryList);
        ListTag logList = new ListTag();
        for (BorderLog entry : logs) {
            logList.add(entry.toTag());
        }
        tag.put("logs", logList);
        pendingWrites = false;
        return tag;
    }

    // ----------------------------------------------------------- persistence

    /**
     * Records that in-memory state changed. The SavedData dirty flag is committed
     * later by {@link #flushIfDue(long)} / {@link #flush()}, so a burst of gate
     * writes produces one save flag instead of one per write.
     */
    private void markPending() {
        pendingWrites = true;
    }

    /** True when state changed and has not been committed to the dirty flag yet. */
    public boolean hasPendingWrites() {
        return pendingWrites;
    }

    /**
     * Commits the pending change to the SavedData dirty flag.
     *
     * @return {@code true} when a flag was actually set
     */
    public boolean flush() {
        if (!pendingWrites) {
            return false;
        }
        pendingWrites = false;
        setDirty();
        return true;
    }

    /**
     * Batched flush used by the server tick: commits at most once every
     * {@link #FLUSH_INTERVAL_TICKS} ticks. All calls happen on the server thread,
     * so no map is mutated from a different thread.
     *
     * @return {@code true} when a flag was actually set
     */
    public boolean flushIfDue(long gameTime) {
        if (!pendingWrites) {
            return false;
        }
        if (lastFlushTick != Long.MIN_VALUE && gameTime - lastFlushTick < FLUSH_INTERVAL_TICKS) {
            return false;
        }
        lastFlushTick = gameTime;
        return flush();
    }

    // ---------------------------------------------------------------- entries

    /**
     * Stores the entry of one person. An entry already stored under the same
     * person key (a second document of the same traveller) is replaced, and the
     * document index is repointed at the new document number.
     */
    public void putEntry(@Nullable BorderEntry entry) {
        if (entry == null || entry.personKey().isBlank()) {
            return;
        }
        BorderEntry previous = entries.put(entry.personKey(), entry);
        if (previous != null && !previous.documentNumber().equals(entry.documentNumber())) {
            // The old booklet no longer resolves to this person.
            documentIndex.remove(previous.documentNumber(), entry.personKey());
        }
        if (!entry.documentNumber().isBlank()) {
            documentIndex.put(entry.documentNumber(), entry.personKey());
        }
        markPending();
    }

    /** The entry stored under {@code personKey}, or {@code null}. */
    @Nullable
    public BorderEntry entry(String personKey) {
        return personKey == null || personKey.isBlank() ? null : entries.get(personKey);
    }

    /**
     * The entry recorded on this exact document number, or {@code null}. The
     * stored entry must still name that document: a stale index row (the person
     * moved to a renewed document) never resolves.
     */
    @Nullable
    public BorderEntry entryByDocument(String documentNumber) {
        String key = documentNumber == null ? null : documentIndex.get(documentNumber.trim());
        BorderEntry entry = key == null ? null : entries.get(key);
        if (entry == null || !entry.documentNumber().equals(documentNumber == null ? "" : documentNumber.trim())) {
            return null;
        }
        return entry;
    }

    /** The newest active entry presented by this account, or {@code null}. */
    @Nullable
    public BorderEntry entryForPlayer(@Nullable UUID player) {
        if (player == null) {
            return null;
        }
        BorderEntry newest = null;
        for (BorderEntry entry : entries.values()) {
            if (!player.equals(entry.player())) {
                continue;
            }
            if (newest == null || entry.entryEpochMillis() > newest.entryEpochMillis()) {
                newest = entry;
            }
        }
        return newest;
    }

    /** personKey of the entry recorded on this document number, or {@code null}. */
    @Nullable
    public String personKeyForDocument(String documentNumber) {
        BorderEntry entry = entryByDocument(documentNumber);
        return entry == null ? null : entry.personKey();
    }

    /** Removes and returns the entry of one person, or {@code null}. */
    @Nullable
    public BorderEntry removeEntry(@Nullable String personKey) {
        if (personKey == null || personKey.isBlank()) {
            return null;
        }
        BorderEntry removed = entries.remove(personKey);
        if (removed != null) {
            documentIndex.remove(removed.documentNumber(), personKey);
            markPending();
        }
        return removed;
    }

    /** Removes and returns the newest entry presented by this account, or {@code null}. */
    @Nullable
    public BorderEntry removeEntryForPlayer(@Nullable UUID player) {
        BorderEntry entry = entryForPlayer(player);
        return entry == null ? null : removeEntry(entry.personKey());
    }

    public int entryCount() {
        return entries.size();
    }

    /** Stable snapshot of the active entries, newest entry first (officer view). */
    public List<BorderEntry> entries() {
        List<BorderEntry> copy = new ArrayList<>(entries.values());
        copy.sort((left, right) -> Long.compare(right.entryEpochMillis(), left.entryEpochMillis()));
        return copy;
    }

    // ------------------------------------------------------------------- logs

    /** Appends one event and keeps only the newest {@link #MAX_LOGS}. */
    public void addLog(BorderLog entry) {
        if (entry == null) {
            return;
        }
        logs.add(entry);
        trimLogs();
        markPending();
    }

    /** Snapshot of the retained logs, oldest first. */
    public List<BorderLog> logs() {
        return new ArrayList<>(logs);
    }

    /** Newest-first snapshot of at most {@code limit} entries (officer view). */
    public List<BorderLog> recentLogs(int limit) {
        int size = logs.size();
        int count = Math.max(0, Math.min(limit, size));
        List<BorderLog> result = new ArrayList<>(count);
        for (int i = size - 1; i >= size - count; i--) {
            result.add(logs.get(i));
        }
        return result;
    }

    public int logCount() {
        return logs.size();
    }

    public void clearLogs() {
        if (!logs.isEmpty()) {
            logs.clear();
            markPending();
        }
    }

    private void trimLogs() {
        while (logs.size() > MAX_LOGS) {
            logs.remove(0);
        }
    }
}
