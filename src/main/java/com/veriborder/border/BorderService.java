package com.veriborder.border;

import com.veriborder.doc.CredentialCode;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentKind;
import com.veriborder.doc.DocumentValidator;
import com.veriborder.doc.EndorsementRecord;
import com.veriborder.doc.VisaRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Border state machine shared by the entry gate, the exit gate, the officer
 * terminal and the operator commands.
 *
 * <p>Entry never consumes a visa/endorsement record: it stores an
 * {@link BorderSession} naming the document and the exact record that matched.
 * Exit consumes precisely that record and clears the session. Everything is
 * kept in {@link BorderTravelData} (overworld saved data).</p>
 */
public final class BorderService {
    /** Denial reasons are also translation keys rendered by the reader/gate UI. */
    public static final String REASON_ALREADY_ENTERED = "verify.veriborder.reason.already_entered";
    public static final String REASON_NO_ENTRY_RECORD = "verify.veriborder.reason.no_entry_record";
    public static final String REASON_SESSION_DOCUMENT_MISMATCH =
            "verify.veriborder.reason.session_document_mismatch";
    public static final String REASON_SESSION_RECORD_MISSING = "verify.veriborder.reason.session_record_missing";
    public static final String REASON_OVERSTAY = "verify.veriborder.reason.overstay";

    /** Chat/action-bar warning shown when a traveller leaves late. */
    public static final String MESSAGE_OVERSTAY = "message.veriborder.gate.overstay";

    /** How many recent log entries the officer payload carries. */
    public static final int OFFICER_LOG_LIMIT = 100;

    private BorderService() {
    }

    /** The matched credential of a successful entry (or {@link BorderSession#KIND_ALL}). */
    public record EntryMatch(String kind, String recordId, String code) {
        public EntryMatch {
            kind = kind == null || kind.isBlank() ? BorderSession.KIND_ALL : kind;
            recordId = recordId == null ? "" : recordId;
            code = code == null ? "" : code;
        }
    }

    // ---------------------------------------------------------------- storage

    @Nullable
    public static BorderTravelData travelData(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return server == null ? null : BorderTravelData.get(server);
    }

    /** Active session of that player, or {@code null}. */
    @Nullable
    public static BorderSession session(ServerPlayer player) {
        BorderTravelData data = travelData(player);
        return data == null ? null : data.session(player.getUUID());
    }

    public static boolean hasSession(ServerPlayer player) {
        return session(player) != null;
    }

    // ------------------------------------------------------------ entry match

    /**
     * Resolves which credential of {@code doc} satisfies the gate. Mirrors
     * {@link DocumentValidator#validateGate} exactly (visa records before
     * visa-free codes, passport before permit in {@code both} mode) so a
     * document that passed validation always yields a match.
     */
    @Nullable
    public static EntryMatch resolveEntry(DocumentData doc, String gateMode, Collection<String> gateCodes) {
        if (doc == null) {
            return null;
        }
        String mode = DocumentValidator.normalizeMode(gateMode);
        if (DocumentValidator.GATE_MODE_ALL.equals(mode)) {
            return new EntryMatch(BorderSession.KIND_ALL, "", "");
        }
        List<String> codes = CredentialCode.normalizeAll(gateCodes);
        if (codes.isEmpty()) {
            return null;
        }
        boolean passport = doc.kind() == DocumentKind.PASSPORT;
        boolean permit = doc.kind() == DocumentKind.PERMIT;
        return switch (mode) {
            case DocumentValidator.GATE_MODE_VISA -> passport ? visaMatch(doc, codes) : null;
            case DocumentValidator.GATE_MODE_ENDORSEMENT -> permit ? endorsementMatch(doc, codes) : null;
            case DocumentValidator.GATE_MODE_BOTH -> {
                if (passport) {
                    yield visaMatch(doc, codes);
                }
                yield permit ? endorsementMatch(doc, codes) : null;
            }
            default -> new EntryMatch(BorderSession.KIND_ALL, "", "");
        };
    }

    @Nullable
    private static EntryMatch visaMatch(DocumentData doc, List<String> codes) {
        for (VisaRecord visa : doc.visas()) {
            if (visa.active() && codes.contains(visa.code())) {
                return new EntryMatch(BorderSession.KIND_VISA, visa.id(), visa.code());
            }
        }
        for (String free : doc.visaFreeCodes()) {
            if (codes.contains(free)) {
                return new EntryMatch(BorderSession.KIND_VISA_FREE, "", free);
            }
        }
        return null;
    }

    @Nullable
    private static EntryMatch endorsementMatch(DocumentData doc, List<String> codes) {
        for (EndorsementRecord endorsement : doc.endorsements()) {
            if (endorsement.active() && codes.contains(endorsement.code())) {
                return new EntryMatch(BorderSession.KIND_ENDORSEMENT, endorsement.id(), endorsement.code());
            }
        }
        return null;
    }

    // -------------------------------------------------------------- recording

    /**
     * Creates and stores the session for an accepted entry and logs
     * {@code ALLOW_IN}. The credential record is left untouched on purpose.
     */
    public static BorderSession beginEntry(ServerPlayer player, BlockPos gatePos, String port, int overstayDays,
                                           DocumentData doc, EntryMatch match) {
        String document = doc == null ? "" : doc.number();
        String dimension = dimension(player);
        BorderSession session = new BorderSession(player.getUUID(), playerName(player), document,
                match.recordId(), match.code(), match.kind(), dimension, gatePos,
                System.currentTimeMillis(), port, overstayDays, false);
        BorderTravelData data = travelData(player);
        if (data != null) {
            data.putSession(session);
            data.addLog(new BorderLog(System.currentTimeMillis(), player.getUUID(), playerName(player),
                    BorderLog.IN, BorderLog.ALLOW_IN, document, match.code(), match.kind(),
                    gate(gatePos, dimension), ""));
        }
        return session;
    }

    /** Logs a denied entry/exit attempt. Nothing is stored or animated. */
    public static void deny(ServerPlayer player, BlockPos gatePos, @Nullable DocumentData doc,
                            @Nullable BorderSession session, String direction, String reasonKey) {
        BorderTravelData data = travelData(player);
        if (data == null) {
            return;
        }
        String document = doc != null ? doc.number()
                : session != null ? session.documentNumber() : "";
        boolean inbound = BorderLog.IN.equals(direction);
        data.addLog(new BorderLog(System.currentTimeMillis(), player.getUUID(), playerName(player),
                direction, inbound ? BorderLog.DENY_IN : BorderLog.DENY_OUT, document,
                session == null ? "" : session.code(), session == null ? "" : session.kind(),
                gate(gatePos, dimension(player)), reasonKey));
    }

    /**
     * Completes a successful exit: detects and flags an overstay, logs
     * {@code ALLOW_OUT} / {@code OVERSTAY}, then clears the session.
     *
     * @return {@code true} when the stay was an overstay (exit still allowed)
     */
    public static boolean finishExit(ServerPlayer player, BorderSession session, BlockPos gatePos) {
        BorderTravelData data = travelData(player);
        long now = System.currentTimeMillis();
        boolean overstay = session.overstayed(now);
        if (data == null) {
            return overstay;
        }
        if (overstay) {
            // Keep the flag visible on the stored session until it is cleared.
            data.putSession(session.withOverstay(true));
        }
        data.addLog(new BorderLog(now, player.getUUID(), playerName(player), BorderLog.OUT,
                overstay ? BorderLog.OVERSTAY : BorderLog.ALLOW_OUT, session.documentNumber(),
                session.code(), session.kind(), gate(gatePos, dimension(player)),
                overstay ? REASON_OVERSTAY : ""));
        data.clearSession(player.getUUID());
        return overstay;
    }

    /**
     * Officer/command session removal. Logs an {@code OFFICER} entry naming the
     * operator when a session actually existed.
     *
     * @return the removed session, or {@code null} when the player had none
     */
    @Nullable
    public static BorderSession clearSession(MinecraftServer server, UUID player, String operatorName) {
        if (server == null || player == null) {
            return null;
        }
        BorderTravelData data = BorderTravelData.get(server);
        BorderSession removed = data.clearSession(player);
        if (removed != null) {
            data.addLog(new BorderLog(System.currentTimeMillis(), player, removed.playerName(),
                    BorderLog.OFFICER, BorderLog.OFFICER_CLEAR, removed.documentNumber(),
                    removed.code(), removed.kind(), gate(removed.gatePos(), removed.dimension()),
                    operatorName == null ? "" : operatorName));
        }
        return removed;
    }

    // ---------------------------------------------------------- officer packet

    /** Session list for the officer payload (newest entry first). */
    public static ListTag sessionsTag(MinecraftServer server) {
        ListTag list = new ListTag();
        if (server == null) {
            return list;
        }
        for (BorderSession session : BorderTravelData.get(server).sessions()) {
            list.add(session.toOfficerTag());
        }
        return list;
    }

    /** Recent log list for the officer payload (newest first). */
    public static ListTag logsTag(MinecraftServer server, int limit) {
        ListTag list = new ListTag();
        if (server == null) {
            return list;
        }
        for (BorderLog entry : BorderTravelData.get(server).recentLogs(limit)) {
            list.add(entry.toOfficerTag());
        }
        return list;
    }

    // ----------------------------------------------------------------- helpers

    public static String playerName(ServerPlayer player) {
        return player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName();
    }

    public static String dimension(ServerPlayer player) {
        return player.level().dimension().location().toString();
    }

    /** Readable gate location stored in logs: {@code minecraft:overworld@10,64,-3}. */
    public static String gate(BlockPos pos, String dimension) {
        BlockPos at = pos == null ? BlockPos.ZERO : pos;
        String dim = dimension == null || dimension.isBlank() ? "minecraft:overworld" : dimension;
        return dim + "@" + at.getX() + "," + at.getY() + "," + at.getZ();
    }
}
