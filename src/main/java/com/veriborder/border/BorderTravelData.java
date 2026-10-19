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
 * World-scoped border state: the active entry session per player plus a capped
 * event log. Persisted through the overworld {@code DimensionDataStorage}, so
 * sessions survive logout, dimension changes and server restarts.
 *
 * <p>There is deliberately no static world state: every caller resolves the
 * instance through {@link #get(MinecraftServer)}.</p>
 *
 * <p>NBT layout (the vanilla {@code data} wrapper is added by the storage):</p>
 * <pre>
 * schema    int
 * sessions  ListTag of BorderSession compounds ("player", "playerName",
 *           "document", "recordId", "code", "kind", "dimension", "gateX",
 *           "gateY", "gateZ", "entryTime", "port", "overstayDays", "overstay")
 * logs      ListTag of BorderLog compounds ("time", "player", "playerName",
 *           "direction", "result", "document", "code", "kind", "gate", "detail")
 * </pre>
 */
public final class BorderTravelData extends SavedData {
    /** Data file name inside the overworld data storage. */
    public static final String DATA_NAME = "veriborder_border";
    /** Logs kept on disk; older entries are dropped first. */
    public static final int MAX_LOGS = 500;
    public static final int SCHEMA = 1;

    private final Map<UUID, BorderSession> sessions = new LinkedHashMap<>();
    private final List<BorderLog> logs = new ArrayList<>();

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
        ListTag sessionList = tag.getList("sessions", Tag.TAG_COMPOUND);
        for (int i = 0; i < sessionList.size(); i++) {
            CompoundTag entry = sessionList.getCompound(i);
            BorderSession session = BorderSession.fromTag(entry);
            if (session != null && session.player() != null) {
                data.sessions.put(session.player(), session);
            }
        }
        ListTag logList = tag.getList("logs", Tag.TAG_COMPOUND);
        for (int i = 0; i < logList.size(); i++) {
            BorderLog entry = BorderLog.fromTag(logList.getCompound(i));
            if (entry != null) {
                data.logs.add(entry);
            }
        }
        data.trimLogs();
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("schema", SCHEMA);
        ListTag sessionList = new ListTag();
        for (BorderSession session : sessions.values()) {
            sessionList.add(session.toTag());
        }
        tag.put("sessions", sessionList);
        ListTag logList = new ListTag();
        for (BorderLog entry : logs) {
            logList.add(entry.toTag());
        }
        tag.put("logs", logList);
        return tag;
    }

    @Nullable
    public BorderSession session(UUID player) {
        return player == null ? null : sessions.get(player);
    }

    public boolean hasSession(UUID player) {
        return player != null && sessions.containsKey(player);
    }

    /**
     * Stores the session of one player. A player can only ever have one active
     * session, so a second store replaces the previous one instead of adding a
     * duplicate.
     */
    public void putSession(BorderSession session) {
        if (session == null || session.player() == null) {
            return;
        }
        sessions.put(session.player(), session);
        setDirty();
    }

    /** Removes and returns the session, or {@code null} when there was none. */
    @Nullable
    public BorderSession clearSession(UUID player) {
        if (player == null) {
            return null;
        }
        BorderSession removed = sessions.remove(player);
        if (removed != null) {
            setDirty();
        }
        return removed;
    }

    public int sessionCount() {
        return sessions.size();
    }

    /** Stable snapshot of the sessions, newest entry first (officer view). */
    public List<BorderSession> sessions() {
        List<BorderSession> copy = new ArrayList<>(sessions.values());
        copy.sort((left, right) -> Long.compare(right.entryEpochMillis(), left.entryEpochMillis()));
        return copy;
    }

    /** Appends one event and keeps only the newest {@link #MAX_LOGS}. */
    public void addLog(BorderLog entry) {
        if (entry == null) {
            return;
        }
        logs.add(entry);
        trimLogs();
        setDirty();
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
            setDirty();
        }
    }

    private void trimLogs() {
        while (logs.size() > MAX_LOGS) {
            logs.remove(0);
        }
    }
}
