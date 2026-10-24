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
 * <p>Entry never consumes a visa/endorsement record: it stores a
 * {@link BorderEntry} naming the person, the document and the exact record that
 * matched. Exit consumes precisely that record and clears the entry. Everything
 * is kept in {@link BorderTravelData} (overworld saved data).</p>
 *
 * <p><b>26.3 rules.</b></p>
 * <ul>
 *   <li>The {@code all} gate mode is gone: an entry is only granted by a visa, an
 *       endorsement or a permanent code that matches the gate.</li>
 *   <li>An entry belongs to a <em>person</em> ({@link BorderEntry#personKey()}),
 *       not to one booklet, so a renewed passport or a second document of the
 *       same holder resolves to the same entry.</li>
 *   <li>A permanent code is only valid for the country it was issued for
 *       ({@link DocumentValidator#matchPermanentCode(DocumentData, String)});
 *       codes without a country keep the legacy universal behaviour.</li>
 * </ul>
 */
public final class BorderService {
    /** Denial reasons are also translation keys rendered by the reader/gate UI. */
    public static final String REASON_ALREADY_ENTERED = "verify.veriborder.reason.already_entered";
    public static final String REASON_NO_ENTRY_RECORD = "verify.veriborder.reason.no_entry_record";
    public static final String REASON_SESSION_DOCUMENT_MISMATCH =
            "verify.veriborder.reason.session_document_mismatch";
    public static final String REASON_NO_MATCHING_CODE = "verify.veriborder.reason.no_matching_code";
    public static final String REASON_SESSION_RECORD_MISSING = "verify.veriborder.reason.session_record_missing";
    public static final String REASON_OVERSTAY = "verify.veriborder.reason.overstay";

    /** Chat/action-bar warning shown when a traveller leaves late. */
    public static final String MESSAGE_OVERSTAY = "message.veriborder.gate.overstay";

    /** How many recent log entries the officer payload carries. */
    public static final int OFFICER_LOG_LIMIT = 100;

    private BorderService() {
    }

    /** The matched credential of a successful entry. */
    public record EntryMatch(String kind, String recordId, String code) {
        public EntryMatch {
            kind = kind == null || kind.isBlank() ? BorderEntry.KIND_NONE : kind;
            recordId = recordId == null ? "" : recordId;
            code = code == null ? "" : code;
        }

        /** Entry granted by the issuer/officer policy instead of a gate credential. */
        public static EntryMatch none() {
            return new EntryMatch(BorderEntry.KIND_NONE, "", "");
        }
    }

    // ---------------------------------------------------------------- storage

    @Nullable
    public static BorderTravelData travelData(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return server == null ? null : BorderTravelData.get(server);
    }

    /** Active entry presented by that account, or {@code null}. */
    @Nullable
    public static BorderEntry entry(ServerPlayer player) {
        BorderTravelData data = travelData(player);
        return data == null ? null : data.entryForPlayer(player.getUUID());
    }

    public static boolean hasEntry(ServerPlayer player) {
        return entry(player) != null;
    }

    // -------------------------------------------------------------- identity

    /**
     * Signature key of the person a document belongs to. The signed
     * {@link DocumentData#personKey()} wins; a document without holder data falls
     * back to its document number so the entry stays addressable.
     */
    public static String personKey(@Nullable DocumentData doc) {
        if (doc == null) {
            return "";
        }
        String key = doc.personKey().trim();
        if (!key.isEmpty()) {
            return key;
        }
        String number = doc.number().trim();
        return number.isEmpty() ? "" : "doc:" + number;
    }

    /** Stateless person key from a holder name and birth date. */
    public static String personKey(@Nullable String name, @Nullable String birthDate) {
        return DocumentData.computePersonKey(name, birthDate);
    }

    /**
     * Resolves which credential of {@code doc} satisfies the gate at
     * {@code gateCountry}. Mirrors {@link DocumentValidator#validateGate} exactly
     * (permanent code before configured codes, visa records before visa-free
     * entries, passport before permit in {@code both} mode) so a document that
     * passed validation always yields a match.
     *
     * <p>There is no {@code all} branch: without a matching visa, endorsement or
     * permanent code the result is {@code null} and the gate must deny.</p>
     */
    @Nullable
    public static EntryMatch resolveEntry(DocumentData doc, String gateMode, Collection<String> gateCodes,
                                          String gateCountry) {
        if (doc == null) {
            return null;
        }
        String mode = DocumentValidator.normalizeMode(gateMode);
        // 26.3 permanent-code slot (permit + passport): the code must belong to
        // the gate's country; legacy country-less codes stay universal.
        String permanent = DocumentValidator.matchPermanentCode(doc, gateCountry);
        if (permanent != null) {
            return new EntryMatch(BorderEntry.KIND_VISA_FREE, "", permanent);
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
            default -> null;
        };
    }

    /** Country-less variant of {@link #resolveEntry(DocumentData, String, Collection, String)}. */
    @Nullable
    public static EntryMatch resolveEntry(DocumentData doc, String gateMode, Collection<String> gateCodes) {
        return resolveEntry(doc, gateMode, gateCodes, "");
    }

    @Nullable
    private static EntryMatch visaMatch(DocumentData doc, List<String> codes) {
        for (VisaRecord visa : doc.visas()) {
            if (visa.active() && codes.contains(visa.code())) {
                return new EntryMatch(BorderEntry.KIND_VISA, visa.id(), visa.code());
            }
        }
        for (String free : doc.visaFreeCodes()) {
            if (codes.contains(free)) {
                return new EntryMatch(BorderEntry.KIND_VISA_FREE, "", free);
            }
        }
        return null;
    }

    @Nullable
    private static EntryMatch endorsementMatch(DocumentData doc, List<String> codes) {
        for (EndorsementRecord endorsement : doc.endorsements()) {
            if (endorsement.active() && codes.contains(endorsement.code())) {
                return new EntryMatch(BorderEntry.KIND_ENDORSEMENT, endorsement.id(), endorsement.code());
            }
        }
        return null;
    }

    /**
     * The active entry of the traveller presenting {@code doc}: first by that
     * document number, then by the holder's person key. This is what makes a
     * renewed passport of the same holder find the entry recorded on the old one.
     */
    @Nullable
    public static BorderEntry findEntry(@Nullable BorderTravelData data, @Nullable DocumentData doc) {
        if (data == null || doc == null) {
            return null;
        }
        BorderEntry byNumber = data.entryByDocument(doc.number());
        if (byNumber != null) {
            return byNumber;
        }
        String key = personKey(doc);
        return key.isEmpty() ? null : data.entry(key);
    }

    /** World-level variant of {@link #findEntry(BorderTravelData, DocumentData)}. */
    @Nullable
    public static BorderEntry findEntry(ServerPlayer player, @Nullable DocumentData doc) {
        return findEntry(travelData(player), doc);
    }

    /**
     * 26.3 identity rule: an entry binds the <em>person</em>, not one booklet.
     *
     * <p>The document presented at the exit gate is accepted when it is the very
     * document the entry was recorded on, when its person key equals the entry's,
     * or — for entries migrated from 26.2, whose key carries no birth date — when
     * the holder name matches (ignoring case and surrounding whitespace).</p>
     */
    public static boolean sameTraveller(@Nullable BorderEntry entry, @Nullable DocumentData doc) {
        if (entry == null || doc == null) {
            return false;
        }
        if (!entry.documentNumber().isBlank() && entry.documentNumber().equals(doc.number())) {
            return true;
        }
        String key = personKey(doc);
        if (!entry.personKey().isBlank() && !key.isEmpty() && entry.personKey().equals(key)) {
            return true;
        }
        String holder = entry.holder();
        String presented = doc.name() == null ? "" : doc.name().trim();
        if (holder.isEmpty() || presented.isEmpty() || !holder.equalsIgnoreCase(presented)) {
            return false;
        }
        // A name-only match is reserved for migrated 26.2 entries: their fallback
        // key has no birth date and carries the document number in that slot.
        // A modern key (name|birthDate) must not let a namesake with a different
        // birth date pass as the same traveller.
        String entryKey = entry.personKey();
        int separator = entryKey.indexOf('|');
        String birthSlot = separator < 0 ? "" : entryKey.substring(separator + 1);
        return !entry.documentNumber().isBlank() && birthSlot.equals(entry.documentNumber());
    }

    /**
     * True when {@code doc} is the very document the entry was recorded on. Only
     * in that case does the exit gate require (and consume) the credential record
     * the entry bound: a renewed passport of the same holder cannot carry a record
     * that stayed on the previous booklet.
     */
    public static boolean isEntryDocument(@Nullable BorderEntry entry, @Nullable DocumentData doc) {
        return entry != null && doc != null && entry.documentNumber().equals(doc.number());
    }

    // -------------------------------------------------------------- recording

    /**
     * Creates and stores the entry for an accepted arrival and logs
     * {@code ALLOW_IN}. The credential record is left untouched on purpose.
     */
    public static BorderEntry beginEntry(ServerPlayer player, BlockPos gatePos, String port, int overstayDays,
                                         DocumentData doc, EntryMatch match) {
        return beginEntry(travelData(player), player.getUUID(), playerName(player), dimension(player),
                gatePos, port, overstayDays, doc, match);
    }

    /**
     * Data-level entry used by the gates and by the issue-entry policy. Kept free
     * of world/player access so the entry contract is unit-testable.
     */
    public static BorderEntry beginEntry(@Nullable BorderTravelData data, UUID player, String playerName,
                                         String dimension, BlockPos gatePos, String port, int overstayDays,
                                         DocumentData doc, EntryMatch match) {
        String document = doc == null ? "" : doc.number();
        String holder = doc == null ? "" : doc.name();
        BorderEntry entry = new BorderEntry(personKey(doc), document, holder, match.recordId(), match.code(),
                match.kind(), dimension, gatePos, System.currentTimeMillis(), port, overstayDays, false,
                player, playerName);
        if (data != null && !entry.personKey().isBlank()) {
            data.putEntry(entry);
            data.addLog(new BorderLog(System.currentTimeMillis(), player, playerName,
                    BorderLog.IN, BorderLog.ALLOW_IN, document, match.code(), match.kind(),
                    gate(gatePos, dimension), ""));
        }
        return entry;
    }

    /**
     * 26.2/26.3 issue-entry policy: a freshly issued passport/permit may start its
     * holder in the entry state, but a domestic ID card never does.
     *
     * <p>The entry must be bound to the <em>issued</em> document number. An
     * existing entry is only kept when it already names that exact document; a
     * stale entry (an older document or a leftover test entry) is replaced,
     * otherwise the new document would never match at the exit gate.</p>
     */
    public static boolean shouldAdoptIssuedEntry(DocumentKind kind, boolean issueEntryState,
                                                 @Nullable BorderEntry existing, String issuedNumber) {
        if (kind == null || kind == DocumentKind.ID_CARD || !issueEntryState) {
            return false;
        }
        String number = issuedNumber == null ? "" : issuedNumber.trim();
        if (number.isEmpty()) {
            return false;
        }
        return existing == null || !number.equals(existing.documentNumber());
    }

    /**
     * Replaces any entry of the same person with one bound to the freshly issued
     * document, so the exit gate can match the new document instead of refusing
     * the traveller with "no entry record".
     */
    public static BorderEntry replaceEntry(ServerPlayer player, BlockPos gatePos, String port, int overstayDays,
                                           DocumentData doc, EntryMatch match) {
        return replaceEntry(travelData(player), player.getUUID(), playerName(player), dimension(player),
                gatePos, port, overstayDays, doc, match);
    }

    /** Data-level variant of {@link #replaceEntry(ServerPlayer, BlockPos, String, int, DocumentData, EntryMatch)}. */
    public static BorderEntry replaceEntry(@Nullable BorderTravelData data, UUID player, String playerName,
                                           String dimension, BlockPos gatePos, String port, int overstayDays,
                                           DocumentData doc, EntryMatch match) {
        if (data != null) {
            // The person's entry (by key) and anything this account still presents
            // are both replaced, so a stale leftover row can never survive.
            data.removeEntry(personKey(doc));
            data.removeEntryForPlayer(player);
        }
        return beginEntry(data, player, playerName, dimension, gatePos, port, overstayDays, doc, match);
    }

    /** Logs a denied entry/exit attempt. Nothing is stored or animated. */
    public static void deny(ServerPlayer player, BlockPos gatePos, @Nullable DocumentData doc,
                            @Nullable BorderEntry entry, String direction, String reasonKey) {
        BorderTravelData data = travelData(player);
        if (data == null) {
            return;
        }
        String document = doc != null ? doc.number()
                : entry != null ? entry.documentNumber() : "";
        boolean inbound = BorderLog.IN.equals(direction);
        data.addLog(new BorderLog(System.currentTimeMillis(), player.getUUID(), playerName(player),
                direction, inbound ? BorderLog.DENY_IN : BorderLog.DENY_OUT, document,
                entry == null ? "" : entry.code(), entry == null ? "" : entry.kind(),
                gate(gatePos, dimension(player)), reasonKey));
    }

    /**
     * Completes a successful departure: detects and flags an overstay, logs
     * {@code ALLOW_OUT} / {@code OVERSTAY}, then clears the entry.
     *
     * @return {@code true} when the stay was an overstay (exit still allowed)
     */
    public static boolean finishExit(ServerPlayer player, BorderEntry entry, BlockPos gatePos) {
        return finishExit(player, entry, gatePos, entry.documentNumber());
    }

    /**
     * Exit variant that logs the document <em>actually presented</em> at the exit
     * gate. A traveller leaving on a renewed passport of the same holder is logged
     * with that passport number, not with the number the entry was recorded on.
     */
    public static boolean finishExit(ServerPlayer player, BorderEntry entry, BlockPos gatePos,
                                     @Nullable String exitDocumentNumber) {
        return finishExit(player, entry, gatePos, exitDocumentNumber, false);
    }

    /**
     * Exit variant that also marks the departure as an OVERSTAY when the
     * presented document itself expired during the stay. A permit/endorsement (or
     * passport) that ran out while the traveller was inside the country must not
     * trap them: the exit is allowed and flagged instead.
     */
    public static boolean finishExit(ServerPlayer player, BorderEntry entry, BlockPos gatePos,
                                     @Nullable String exitDocumentNumber, boolean documentExpired) {
        return finishExit(travelData(player), player.getUUID(), playerName(player), dimension(player),
                entry, gatePos, exitDocumentNumber, documentExpired);
    }

    /**
     * Data-level departure used by the gates and by the integration tests: no
     * world/player access, so the complete entry -> exit contract (overstay
     * detection, log record, entry removal) is testable without a live server.
     *
     * @return {@code true} when the stay was an overstay (exit still allowed)
     */
    public static boolean finishExit(@Nullable BorderTravelData data, UUID player, String playerName,
                                     String dimension, BorderEntry entry, BlockPos gatePos,
                                     @Nullable String exitDocumentNumber) {
        return finishExit(data, player, playerName, dimension, entry, gatePos, exitDocumentNumber, false);
    }

    /**
     * Full data-level departure: {@code documentExpired} marks the log as an
     * OVERSTAY even when the entry itself is still inside its allowance.
     */
    public static boolean finishExit(@Nullable BorderTravelData data, UUID player, String playerName,
                                     String dimension, BorderEntry entry, BlockPos gatePos,
                                     @Nullable String exitDocumentNumber, boolean documentExpired) {
        long now = System.currentTimeMillis();
        boolean overstay = entry.overstayed(now) || documentExpired;
        if (data == null) {
            return overstay;
        }
        String exitNumber = exitDocumentNumber == null || exitDocumentNumber.isBlank()
                ? entry.documentNumber() : exitDocumentNumber.trim();
        data.addLog(new BorderLog(now, player, playerName, BorderLog.OUT,
                overstay ? BorderLog.OVERSTAY : BorderLog.ALLOW_OUT, exitNumber,
                entry.code(), entry.kind(), gate(gatePos, dimension),
                overstay ? REASON_OVERSTAY : ""));
        data.removeEntry(entry.personKey());
        return overstay;
    }

    /**
     * Officer/command entry removal by person key. Logs an {@code OFFICER} entry
     * naming the operator when an entry actually existed.
     *
     * @return the removed entry, or {@code null} when there was none
     */
    @Nullable
    public static BorderEntry clearEntry(@Nullable MinecraftServer server, @Nullable String personKey,
                                         String operatorName) {
        if (server == null || personKey == null || personKey.isBlank()) {
            return null;
        }
        BorderTravelData data = BorderTravelData.get(server);
        BorderEntry removed = data.removeEntry(personKey);
        if (removed != null) {
            logOfficerClear(data, removed, operatorName);
        }
        return removed;
    }

    /**
     * Officer-console entry removal by account id: clears the entry that account
     * presented, whichever document it is bound to.
     */
    @Nullable
    public static BorderEntry clearEntryFor(@Nullable MinecraftServer server, @Nullable UUID player,
                                            String operatorName) {
        if (server == null || player == null) {
            return null;
        }
        BorderTravelData data = BorderTravelData.get(server);
        BorderEntry removed = data.removeEntryForPlayer(player);
        if (removed != null) {
            logOfficerClear(data, removed, operatorName);
        }
        return removed;
    }

    private static void logOfficerClear(BorderTravelData data, BorderEntry removed, String operatorName) {
        data.addLog(new BorderLog(System.currentTimeMillis(), removed.player(), removed.playerName(),
                BorderLog.OFFICER, BorderLog.OFFICER_CLEAR, removed.documentNumber(),
                removed.code(), removed.kind(), gate(removed.gatePos(), removed.dimension()),
                operatorName == null ? "" : operatorName));
    }

    // ---------------------------------------------------------- officer packet

    /** Entry list for the officer payload (newest entry first, one row per person). */
    public static ListTag entriesTag(MinecraftServer server) {
        ListTag list = new ListTag();
        if (server == null) {
            return list;
        }
        for (BorderEntry entry : BorderTravelData.get(server).entries()) {
            list.add(entry.toOfficerTag());
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
