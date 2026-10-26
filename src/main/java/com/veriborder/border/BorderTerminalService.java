package com.veriborder.border;

import com.veriborder.doc.CredentialCode;
import com.veriborder.doc.DateUtil;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentFactory;
import com.veriborder.doc.DocumentKind;
import com.veriborder.doc.DocumentStatus;
import com.veriborder.doc.EndorsementRecord;
import com.veriborder.doc.EndorsementType;
import com.veriborder.doc.MrzUtil;
import com.veriborder.doc.StampRecord;
import com.veriborder.doc.VisaRecord;
import com.veriborder.doc.VisaType;
import com.veriborder.item.BorderTerminalItem;
import com.veriborder.item.DocumentItem;
import com.veriborder.network.BorderTerminalDataPacket;
import com.veriborder.network.ModNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Server-authoritative backend of the admin {@code border_terminal} item.
 *
 * <p>The item only asks this service to open the terminal. Every read is
 * collected here and pushed to the requesting player as a
 * {@link BorderTerminalDataPacket}; every write arrives as an action from
 * {@code BorderTerminalActionPacket}, is re-checked against permission level 2,
 * the held terminal item, the online target and a valid document slot, and
 * re-signs the document through the {@link DocumentData} setters.</p>
 *
 * <p>Nothing in this class bypasses {@link BorderService}: forced exits go
 * through {@link BorderService#clearEntryFor} (which writes the
 * {@code OFFICER_CLEAR} log) and forced entries through
 * {@link BorderService#replaceEntry} (which replaces a stale entry and writes
 * the {@code ALLOW_IN} log). Document edits append an {@code OFFICER} log record
 * naming the operator, so the officer console shows every terminal action.</p>
 */
public final class BorderTerminalService {
    // --------------------------------------------------------------- actions
    public static final String ACTION_SELECT = "select";
    public static final String ACTION_REFRESH = "refresh";
    public static final String ACTION_SET_STATUS = "set_status";
    public static final String ACTION_EDIT_DOC = "edit_doc";
    public static final String ACTION_RESET_DOC = "reset_doc";
    public static final String ACTION_ADD_VISA = "add_visa";
    public static final String ACTION_VISA = "visa";
    public static final String ACTION_ADD_ENDORSEMENT = "add_endorsement";
    public static final String ACTION_ENDORSEMENT = "endorsement";
    public static final String ACTION_STAMP = "stamp";
    public static final String ACTION_FORCE_EXIT = "force_exit";
    public static final String ACTION_FORCE_ENTRY = "force_entry";

    /** Border log result written when an administrator edits a document. */
    public static final String RESULT_EDIT = "OFFICER_EDIT";
    /** Border log result written when an administrator creates an entry. */
    public static final String RESULT_ENTRY = "OFFICER_ENTRY";

    /** Port label stored on a forced entry session when none is supplied. */
    public static final String FORCED_PORT = "Border Terminal";

    /** Recent border log entries sent with each snapshot. */
    public static final int LOG_LIMIT = 20;

    /** Inventory slots scanned for documents (hotbar + main + off hand). */
    private static final int INVENTORY_SLOTS = 41;

    /** Record operation payload keys. */
    private static final String OP_REMOVE = "remove";
    private static final String OP_TOGGLE = "toggle";
    private static final String OP_CLEAR = "clear";

    private BorderTerminalService() {
    }

    // ------------------------------------------------------------------ open

    /** Opens the terminal for {@code viewer} with no traveller selected. */
    public static void open(ServerPlayer viewer) {
        open(viewer, null);
    }

    /**
     * Sends a fresh terminal payload. {@code selected} is the traveller whose
     * carried documents and recent logs are included; {@code null} sends the
     * online list only.
     */
    public static void open(ServerPlayer viewer, @Nullable UUID selected) {
        MinecraftServer server = viewer.getServer();
        if (server == null) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putBoolean("admin", viewer.hasPermissions(2));
        data.putString("self", viewer.getUUID().toString());
        data.put("players", playersTag(server));
        // 26.3: the officer view lists one row per traveller entry (person keyed),
        // so a person holding two documents still shows exactly one row.
        data.put("entries", BorderService.entriesTag(server));
        if (selected != null) {
            ServerPlayer target = server.getPlayerList().getPlayer(selected);
            if (target != null) {
                data.putString("selected", selected.toString());
                data.putString("targetName", BorderService.playerName(target));
                data.put("docs", documentsTag(target));
                data.put("logs", logsTag(server, selected, LOG_LIMIT));
            }
        }
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> viewer),
                new BorderTerminalDataPacket(data));
    }

    // ------------------------------------------------------------------ read

    /** One entry per online player with their current border entry state. */
    private static ListTag playersTag(MinecraftServer server) {
        BorderTravelData data = BorderTravelData.get(server);
        ListTag list = new ListTag();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("uuid", player.getUUID().toString());
            entry.putString("name", BorderService.playerName(player));
            BorderEntry active = data.entryForPlayer(player.getUUID());
            entry.putBoolean("session", active != null);
            if (active != null) {
                entry.putString("personKey", active.personKey());
                entry.putString("holder", active.holder());
                entry.putLong("entryTime", active.entryEpochMillis());
                entry.putString("port", active.port());
                entry.putString("document", active.documentNumber());
                entry.putString("kind", active.kind());
                entry.putString("code", active.code());
                entry.putString("dimension", active.dimension());
                entry.putString("gate", BorderService.gate(active.gatePos(), active.dimension()));
                entry.putBoolean("overstay", active.overstayed());
                entry.putInt("overstayDays", active.overstayDays());
            }
            list.add(entry);
        }
        return list;
    }

    /** Every document carried by {@code target}, with the full terminal summary. */
    private static ListTag documentsTag(ServerPlayer target) {
        ListTag list = new ListTag();
        for (int slot = 0; slot < INVENTORY_SLOTS; slot++) {
            ItemStack stack = target.getInventory().getItem(slot);
            DocumentData doc = DocumentItem.getDocument(stack);
            if (doc == null) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt("slot", slot);
            entry.putString("style", doc.style().id());
            entry.putString("kind", doc.kind().name());
            entry.putBoolean("custom", doc.customStyle());
            entry.putBoolean("nameLocked", nameLocked(doc));
            entry.putString("name", doc.name());
            entry.putString("nameLatin", doc.nameLatin());
            entry.putString("number", doc.number());
            entry.putString("status", doc.status().id());
            entry.putString("birthDate", doc.birthDate());
            entry.putString("sex", doc.sex());
            entry.putString("nationality", doc.nationality());
            entry.putString("authority", doc.authority());
            entry.putString("place", doc.placeOfIssue());
            entry.putString("issueDate", doc.issueDate());
            entry.putString("expiry", doc.expiryDate());
            entry.putBoolean("permanent", doc.isPermanent());
            entry.putString("mrzCountry", doc.mrzCountry());
            entry.putString("mrz1", doc.mrz1());
            entry.putString("mrz2", doc.mrz2());
            entry.putBoolean("integrity", doc.integrityValid());
            entry.putString("displayName", doc.displayName());
            // 26.4 display fix: the title printed on the cover plate (custom name
            // wins, layout name otherwise) so the terminal card shows the same
            // title as the document itself. "customTitle" tells the client whether
            // the resolved title is worth transporting at all: an official title
            // only repeats the layout, and carrying it would replace the layout's
            // short cover name in the terminal-opened viewer.
            entry.putString("coverName", doc.coverName());
            entry.putBoolean("customTitle", doc.hasCustomTitle());
            // 26.4.1: the registry template the document was issued from, so the
            // terminal card can draw the same tint/emblem as the document itself.
            // The document keeps its own signed copy, so these values survive the
            // template being edited or deleted.
            entry.putString("templateId", doc.templateId());
            entry.putInt("tint", doc.effectiveTint());
            entry.putString("emblem", doc.emblem());
            entry.putString("templateTex", doc.templateTex());
            ListTag codes = new ListTag();
            for (String code : doc.visaFreeCodes()) {
                codes.add(StringTag.valueOf(code));
            }
            entry.put("codes", codes);
            entry.put("visas", visasTag(doc));
            entry.put("endorsements", endorsementsTag(doc));
            entry.put("stamps", stampsTag(doc));
            list.add(entry);
        }
        return list;
    }

    private static ListTag visasTag(DocumentData doc) {
        ListTag list = new ListTag();
        for (VisaRecord visa : doc.visas()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", visa.id());
            entry.putString("type", visa.type().id());
            entry.putString("country", visa.country());
            entry.putString("code", visa.code());
            entry.putBoolean("active", visa.active());
            entry.putString("status", visa.status());
            // 26.3 round 12: the derived state (valid/used up/expired/revoked) is
            // what the terminal colours and labels a record with.
            entry.putString("state", visa.state().id());
            entry.putString("entries", visa.entriesText());
            entry.putInt("entriesTotal", visa.entries());
            entry.putInt("usedEntries", visa.usedEntries());
            entry.putString("issueDate", visa.issueDate());
            entry.putString("expiry", visa.expiryDate());
            entry.putString("label", visa.label());
            list.add(entry);
        }
        return list;
    }

    private static ListTag endorsementsTag(DocumentData doc) {
        ListTag list = new ListTag();
        for (EndorsementRecord endorsement : doc.endorsements()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", endorsement.id());
            entry.putString("type", endorsement.type().id());
            entry.putString("code", endorsement.code());
            entry.putBoolean("active", endorsement.active());
            entry.putString("status", endorsement.status());
            entry.putString("state", endorsement.state().id());
            entry.putString("entries", endorsement.entriesText());
            entry.putInt("entriesTotal", endorsement.entries());
            entry.putInt("usedEntries", endorsement.usedEntries());
            entry.putString("issueDate", endorsement.issueDate());
            entry.putString("expiry", endorsement.expiryDate());
            list.add(entry);
        }
        return list;
    }

    private static ListTag stampsTag(DocumentData doc) {
        ListTag list = new ListTag();
        for (StampRecord stamp : doc.stamps()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", stamp.id());
            entry.putString("direction", stamp.direction());
            entry.putString("port", stamp.port());
            entry.putString("country", stamp.country());
            entry.putString("date", stamp.date());
            // 26.3 stamp moment: the exact clock time plus the epoch value. A 26.2
            // stamp has no time, so the UI renders the "--:--" placeholder.
            entry.putString("time", stamp.time());
            entry.putString("timeText", stamp.timeOrDash());
            entry.putLong("epochMillis", stamp.epochMillis());
            entry.putString("officer", stamp.officer());
            list.add(entry);
        }
        return list;
    }

    /** Newest-first recent logs of one traveller (at most {@code limit}). */
    private static ListTag logsTag(MinecraftServer server, UUID target, int limit) {
        ListTag list = new ListTag();
        List<BorderLog> recent = BorderTravelData.get(server).recentLogs(BorderTravelData.MAX_LOGS);
        for (BorderLog entry : recent) {
            if (list.size() >= limit) {
                break;
            }
            if (target.equals(entry.player())) {
                list.add(entry.toOfficerTag());
            }
        }
        return list;
    }

    // ---------------------------------------------------------------- actions

    /**
     * Entry point for {@code BorderTerminalActionPacket}. Read-only actions
     * (select / refresh) are available to everyone who can open the terminal;
     * every mutation is re-checked here for permission level 2, the held terminal
     * item, the online target and a valid document slot.
     */
    public static void handle(ServerPlayer actor, String action, String targetRaw, int docSlot,
                              CompoundTag payload) {
        MinecraftServer server = actor.getServer();
        if (server == null || action == null) {
            return;
        }
        CompoundTag data = payload == null ? new CompoundTag() : payload;
        boolean readOnly = ACTION_SELECT.equals(action) || ACTION_REFRESH.equals(action);
        if (!readOnly) {
            if (!actor.hasPermissions(2)) {
                message(actor, "message.veriborder.permission.denied");
                return;
            }
            if (!holdsTerminal(actor)) {
                message(actor, "message.veriborder.terminal.terminal_required");
                return;
            }
        }
        ServerPlayer target = resolve(server, targetRaw);
        if (target == null) {
            if (ACTION_REFRESH.equals(action)) {
                // Refreshing with nobody selected only re-sends the online list.
                open(actor, null);
                return;
            }
            message(actor, "message.veriborder.terminal.target_missing");
            open(actor, null);
            return;
        }
        UUID selected = target.getUUID();
        switch (action) {
            case ACTION_SELECT, ACTION_REFRESH -> {
                open(actor, selected);
                return;
            }
            case ACTION_SET_STATUS -> setStatus(actor, target, docSlot, data.getString("status"));
            case ACTION_EDIT_DOC -> editDocument(actor, target, docSlot, data);
            case ACTION_RESET_DOC -> resetDocument(actor, target, docSlot);
            case ACTION_ADD_VISA -> addVisa(actor, target, docSlot, data);
            case ACTION_VISA -> visaOperation(actor, target, docSlot, data);
            case ACTION_ADD_ENDORSEMENT -> addEndorsement(actor, target, docSlot, data);
            case ACTION_ENDORSEMENT -> endorsementOperation(actor, target, docSlot, data);
            case ACTION_STAMP -> stampOperation(actor, target, docSlot, data);
            case ACTION_FORCE_EXIT -> forceExit(actor, target);
            case ACTION_FORCE_ENTRY -> forceEntry(actor, target, docSlot, data);
            default -> {
                return;
            }
        }
        // Every mutation re-sends the live state so the screen can never show a
        // stale document, session or log after an action.
        open(actor, selected);
    }

    /** Changes only the document status; the setter re-signs the tag. */
    private static void setStatus(ServerPlayer actor, ServerPlayer target, int slot, String statusId) {
        ItemStack stack = stackAt(target, slot);
        DocumentData doc = editableDocument(stack);
        if (doc == null) {
            message(actor, "message.veriborder.terminal.no_document");
            return;
        }
        DocumentStatus status = DocumentStatus.byId(statusId);
        doc.setStatus(status);
        persist(target, slot, stack, doc);
        log(actor, target, doc.number(), RESULT_EDIT, "status=" + status.id());
        message(actor, "message.veriborder.terminal.status",
                Component.translatable(status.translationKey()));
    }

    /**
     * Edits the selected document's core fields. Only keys present in the
     * payload are written, so a client that omits a field never clears it.
     * Every applied setter re-signs the document; invalid dates and a locked
     * display name are rejected before anything is written.
     */
    private static void editDocument(ServerPlayer actor, ServerPlayer target, int slot, CompoundTag data) {
        ItemStack stack = stackAt(target, slot);
        DocumentData doc = editableDocument(stack);
        if (doc == null) {
            message(actor, "message.veriborder.terminal.no_document");
            return;
        }
        boolean changed = false;
        if (data.contains("name")) {
            String name = data.getString("name").trim();
            if (name.isEmpty()) {
                message(actor, "message.veriborder.terminal.name_required");
                return;
            }
            doc.setName(name);
            changed = true;
        }
        if (data.contains("nameLatin")) {
            doc.setNameLatin(data.getString("nameLatin").trim());
            changed = true;
        }
        if (data.contains("number")) {
            String number = data.getString("number").trim();
            if (number.isEmpty()) {
                message(actor, "message.veriborder.terminal.number_required");
                return;
            }
            doc.setNumber(number);
            changed = true;
        }
        if (data.contains("birthDate")) {
            if (!DateUtil.isValid(data.getString("birthDate"))) {
                message(actor, "message.veriborder.terminal.date_invalid");
                return;
            }
            doc.setBirthDate(data.getString("birthDate").trim());
            changed = true;
        }
        if (data.contains("sex")) {
            doc.setSex(data.getString("sex"));
            changed = true;
        }
        if (data.contains("nationality")) {
            doc.setNationality(data.getString("nationality").trim());
            changed = true;
        }
        if (data.contains("authority")) {
            doc.setAuthority(data.getString("authority").trim());
            changed = true;
        }
        if (data.contains("place")) {
            doc.setPlaceOfIssue(data.getString("place").trim());
            changed = true;
        }
        if (data.contains("issueDate")) {
            if (!DateUtil.isValid(data.getString("issueDate"))) {
                message(actor, "message.veriborder.terminal.date_invalid");
                return;
            }
            doc.setIssueDate(data.getString("issueDate").trim());
            changed = true;
        }
        if (data.contains("expiryDate")) {
            String expiry = data.getString("expiryDate").trim();
            if (!DateUtil.isValid(expiry) && !DateUtil.isPermanent(expiry)) {
                message(actor, "message.veriborder.terminal.date_invalid");
                return;
            }
            doc.setExpiryDate(expiry);
            changed = true;
        }
        if (data.contains("mrzCountry")) {
            // MRZ only exists on passports; a blank value falls back to the style.
            if (doc.kind() == DocumentKind.PASSPORT) {
                String raw = data.getString("mrzCountry").trim();
                if (!raw.isEmpty() && MrzUtil.countryCode(raw).isEmpty()) {
                    message(actor, "message.veriborder.terminal.mrz_invalid");
                    return;
                }
                doc.setMrzCountry(raw);
                changed = true;
            }
        }
        if (data.contains("displayName")) {
            if (nameLocked(doc)) {
                message(actor, "message.veriborder.terminal.name_locked");
                return;
            }
            doc.setDisplayName(data.getString("displayName").trim());
            changed = true;
        }
        if (data.contains("status")) {
            String status = data.getString("status").trim();
            if (!status.isEmpty()) {
                doc.setStatus(DocumentStatus.byId(status));
                changed = true;
            }
        }
        if (data.contains("addCode")) {
            String code = CredentialCode.sanitize(data.getString("addCode"));
            if (code.isEmpty()) {
                message(actor, "message.veriborder.terminal.code_invalid");
                return;
            }
            // 26.3 permanent codes carry the country they were issued for and a
            // purpose; a payload without those keys keeps the legacy universal
            // (country-less, visa-free) behaviour of the 26.2 screen.
            String country = data.getString("codeCountry").trim();
            DocumentData.PermanentPurpose purpose =
                    DocumentData.PermanentPurpose.byId(data.getString("codePurpose"));
            String expiry = data.getString("codeExpiry").trim();
            if (doc.addPermanentCode(code, country, purpose, expiry)) {
                changed = true;
            } else {
                message(actor, "message.veriborder.terminal.code_duplicate");
                return;
            }
        }
        if (data.contains("removeCode")) {
            String code = CredentialCode.sanitize(data.getString("removeCode"));
            if (code.isEmpty()) {
                message(actor, "message.veriborder.terminal.code_invalid");
                return;
            }
            if (doc.removePermanentCode(code)) {
                changed = true;
            } else {
                message(actor, "message.veriborder.terminal.code_missing");
                return;
            }
        }
        if (data.contains("clearCodes") && data.getBoolean("clearCodes")) {
            doc.clearPermanentCodes();
            changed = true;
        }
        if (!changed) {
            message(actor, "message.veriborder.terminal.unchanged");
            return;
        }
        persist(target, slot, stack, doc);
        log(actor, target, doc.number(), RESULT_EDIT, "fields edited");
        message(actor, "message.veriborder.terminal.saved", doc.number());
    }

    /** One-click "valid again": status VALID, unique credential codes, re-signed. */
    private static void resetDocument(ServerPlayer actor, ServerPlayer target, int slot) {
        ItemStack stack = stackAt(target, slot);
        DocumentData doc = editableDocument(stack);
        if (doc == null) {
            message(actor, "message.veriborder.terminal.no_document");
            return;
        }
        doc.setStatus(DocumentStatus.VALID);
        doc.ensureUniqueCredentialCodes();
        doc.sign();
        persist(target, slot, stack, doc);
        log(actor, target, doc.number(), RESULT_EDIT, "reset to valid");
        message(actor, "message.veriborder.terminal.reset", doc.number());
    }

    // -------------------------------------------------------------- records

    /** Adds a visa to the selected passport. */
    private static void addVisa(ServerPlayer actor, ServerPlayer target, int slot, CompoundTag data) {
        ItemStack stack = stackAt(target, slot);
        DocumentData doc = editableDocument(stack);
        if (doc == null) {
            message(actor, "message.veriborder.terminal.no_document");
            return;
        }
        if (doc.kind() != DocumentKind.PASSPORT) {
            message(actor, "message.veriborder.terminal.visa_passport_only");
            return;
        }
        String country = data.getString("country").trim();
        if (country.isEmpty()) {
            message(actor, "message.veriborder.terminal.visa_country_required");
            return;
        }
        VisaType type = VisaType.byId(data.getString("type"));
        String issue = DateUtil.isValid(data.getString("issueDate"))
                ? data.getString("issueDate").trim() : DateUtil.today();
        String expiry = DateUtil.isValid(data.getString("expiryDate"))
                ? data.getString("expiryDate").trim() : DateUtil.plusYears(issue, 1);
        int entries = data.contains("entries")
                ? Math.max(0, data.getInt("entries")) : type.defaultEntries();
        String authority = data.getString("authority").trim();
        if (authority.isEmpty()) {
            authority = doc.authority();
        }
        String code = CredentialCode.sanitize(data.getString("code"));
        if (code.isEmpty()) {
            code = CredentialCode.generateUnique(doc.allCredentialCodes());
        } else if (doc.allCredentialCodes().contains(code)) {
            message(actor, "message.veriborder.terminal.code_duplicate");
            return;
        }
        String label = data.getString("label").trim();
        doc.addVisa(DocumentFactory.createVisa(country, type, entries, issue, expiry, authority, code, label));
        persist(target, slot, stack, doc);
        log(actor, target, doc.number(), RESULT_EDIT, "visa added " + code);
        message(actor, "message.veriborder.terminal.visa_added", country, code);
    }

    /** Toggles or removes one visa of the selected passport. */
    private static void visaOperation(ServerPlayer actor, ServerPlayer target, int slot, CompoundTag data) {
        ItemStack stack = stackAt(target, slot);
        DocumentData doc = editableDocument(stack);
        if (doc == null) {
            message(actor, "message.veriborder.terminal.no_document");
            return;
        }
        int index = data.getInt("index");
        List<CompoundTag> visas = doc.visaTags();
        if (index < 0 || index >= visas.size()) {
            message(actor, "message.veriborder.terminal.record_missing");
            return;
        }
        String op = data.getString("op");
        if (OP_REMOVE.equals(op)) {
            doc.removeVisa(index);
            message(actor, "message.veriborder.terminal.visa_removed");
        } else if (OP_TOGGLE.equals(op)) {
            CompoundTag tag = visas.get(index);
            // Persist the effective code so a legacy record keeps its gate code.
            tag.putString("code", VisaRecord.fromTag(tag).code());
            boolean active = VisaRecord.ACTIVE.equalsIgnoreCase(tag.getString("status"));
            tag.putString("status", active ? VisaRecord.REVOKED : VisaRecord.ACTIVE);
            doc.updateVisa(index, tag);
            message(actor, active ? "message.veriborder.terminal.visa_disabled"
                    : "message.veriborder.terminal.visa_enabled");
        } else {
            return;
        }
        persist(target, slot, stack, doc);
        log(actor, target, doc.number(), RESULT_EDIT, "visa " + op + " " + index);
    }

    /** Adds an endorsement to the selected permit. */
    private static void addEndorsement(ServerPlayer actor, ServerPlayer target, int slot, CompoundTag data) {
        ItemStack stack = stackAt(target, slot);
        DocumentData doc = editableDocument(stack);
        if (doc == null) {
            message(actor, "message.veriborder.terminal.no_document");
            return;
        }
        if (doc.kind() != DocumentKind.PERMIT) {
            message(actor, "message.veriborder.terminal.endorsement_permit_only");
            return;
        }
        EndorsementType type = EndorsementType.byId(data.getString("type"));
        String issue = DateUtil.isValid(data.getString("issueDate"))
                ? data.getString("issueDate").trim() : DateUtil.today();
        String expiry = DateUtil.isValid(data.getString("expiryDate"))
                ? data.getString("expiryDate").trim() : DocumentFactory.endorsementExpiry(type, issue);
        int entries = data.contains("entries")
                ? Math.max(0, data.getInt("entries")) : type.defaultEntries();
        String authority = data.getString("authority").trim();
        if (authority.isEmpty()) {
            authority = doc.authority();
        }
        String code = CredentialCode.sanitize(data.getString("code"));
        if (code.isEmpty()) {
            code = CredentialCode.generateUnique(doc.allCredentialCodes());
        } else if (doc.allCredentialCodes().contains(code)) {
            message(actor, "message.veriborder.terminal.code_duplicate");
            return;
        }
        doc.addEndorsement(DocumentFactory.createEndorsement(type, entries, issue, expiry, authority, code));
        persist(target, slot, stack, doc);
        log(actor, target, doc.number(), RESULT_EDIT, "endorsement added " + code);
        message(actor, "message.veriborder.terminal.endorsement_added", code);
    }

    /** Toggles or removes one endorsement of the selected permit. */
    private static void endorsementOperation(ServerPlayer actor, ServerPlayer target, int slot, CompoundTag data) {
        ItemStack stack = stackAt(target, slot);
        DocumentData doc = editableDocument(stack);
        if (doc == null) {
            message(actor, "message.veriborder.terminal.no_document");
            return;
        }
        int index = data.getInt("index");
        List<CompoundTag> endorsements = doc.endorsementTags();
        if (index < 0 || index >= endorsements.size()) {
            message(actor, "message.veriborder.terminal.record_missing");
            return;
        }
        String op = data.getString("op");
        if (OP_REMOVE.equals(op)) {
            doc.removeEndorsement(index);
            message(actor, "message.veriborder.terminal.endorsement_removed");
        } else if (OP_TOGGLE.equals(op)) {
            CompoundTag tag = endorsements.get(index);
            tag.putString("code", EndorsementRecord.fromTag(tag).code());
            boolean active = VisaRecord.ACTIVE.equalsIgnoreCase(tag.getString("status"));
            tag.putString("status", active ? VisaRecord.REVOKED : VisaRecord.ACTIVE);
            doc.updateEndorsement(index, tag);
            message(actor, active ? "message.veriborder.terminal.endorsement_disabled"
                    : "message.veriborder.terminal.endorsement_enabled");
        } else {
            return;
        }
        persist(target, slot, stack, doc);
        log(actor, target, doc.number(), RESULT_EDIT, "endorsement " + op + " " + index);
    }

    /** Removes one stamp or clears the stamp history. */
    private static void stampOperation(ServerPlayer actor, ServerPlayer target, int slot, CompoundTag data) {
        ItemStack stack = stackAt(target, slot);
        DocumentData doc = editableDocument(stack);
        if (doc == null) {
            message(actor, "message.veriborder.terminal.no_document");
            return;
        }
        String op = data.getString("op");
        if (OP_CLEAR.equals(op)) {
            doc.clearStamps();
            message(actor, "message.veriborder.terminal.stamps_cleared");
        } else {
            int index = data.getInt("index");
            List<CompoundTag> stamps = doc.stampTags();
            if (index < 0 || index >= stamps.size()) {
                message(actor, "message.veriborder.terminal.record_missing");
                return;
            }
            doc.removeStamp(index);
            message(actor, "message.veriborder.terminal.stamp_removed");
        }
        persist(target, slot, stack, doc);
        log(actor, target, doc.number(), RESULT_EDIT, "stamp " + op);
    }

    // --------------------------------------------------------------- session

    /** Removes the traveller's active entry through the normal officer path. */
    private static void forceExit(ServerPlayer actor, ServerPlayer target) {
        MinecraftServer server = actor.getServer();
        BorderEntry removed = server == null ? null
                : BorderService.clearEntryFor(server, target.getUUID(), BorderService.playerName(actor));
        if (removed == null) {
            message(actor, "message.veriborder.terminal.no_session",
                    Component.literal(BorderService.playerName(target)));
            return;
        }
        message(actor, "message.veriborder.terminal.force_exit",
                Component.literal(BorderService.playerName(target)));
    }

    /**
     * Creates an entry session for the traveller, replacing any stale one. The
     * selected document is bound to the session so an exit gate can match it; a
     * custom port may be supplied, otherwise {@link #FORCED_PORT} is used.
     */
    private static void forceEntry(ServerPlayer actor, ServerPlayer target, int slot, CompoundTag data) {
        int docSlot = validDocumentSlot(target, slot);
        if (docSlot < 0) {
            message(actor, "message.veriborder.terminal.no_document");
            return;
        }
        ItemStack stack = stackAt(target, docSlot);
        DocumentData doc = editableDocument(stack);
        if (doc == null) {
            message(actor, "message.veriborder.terminal.no_document");
            return;
        }
        String port = data.getString("port").trim();
        if (port.isEmpty()) {
            port = FORCED_PORT;
        }
        BorderService.replaceEntry(target, target.blockPosition(), port,
                BorderEntry.DEFAULT_OVERSTAY_DAYS, doc, BorderService.EntryMatch.none());
        log(actor, target, doc.number(), RESULT_ENTRY, "forced entry");
        message(actor, "message.veriborder.terminal.force_entry",
                Component.literal(BorderService.playerName(target)));
    }

    // ---------------------------------------------------------------- helpers

    /** The terminal item must be held in either hand for a write action. */
    private static boolean holdsTerminal(ServerPlayer player) {
        return player.getItemInHand(InteractionHand.MAIN_HAND).getItem() instanceof BorderTerminalItem
                || player.getItemInHand(InteractionHand.OFF_HAND).getItem() instanceof BorderTerminalItem;
    }

    /** Signed 26.2 flag written by name-locking issuers; absent = editable. */
    private static boolean nameLocked(DocumentData doc) {
        return doc != null && "true".equalsIgnoreCase(doc.get("nameLocked", ""));
    }

    @Nullable
    private static ServerPlayer resolve(MinecraftServer server, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return server.getPlayerList().getPlayerByName(value);
        }
    }

    @Nullable
    private static ItemStack stackAt(ServerPlayer target, int slot) {
        if (slot < 0 || slot >= target.getInventory().getContainerSize()) {
            return null;
        }
        return target.getInventory().getItem(slot);
    }

    @Nullable
    private static DocumentData documentAt(@Nullable ItemStack stack) {
        return stack == null ? null : DocumentItem.getDocument(stack);
    }

    /**
     * Independent working copy of the document. Edits are applied to this copy and
     * only written back by {@link #persist} once every validation passed, so a
     * rejected field (invalid date, duplicate code, locked name) can never leave a
     * half-applied document on the live item.
     */
    @Nullable
    private static DocumentData editableDocument(@Nullable ItemStack stack) {
        DocumentData doc = documentAt(stack);
        return doc == null ? null : doc.copy();
    }

    /** The requested slot when it holds a document, otherwise the first one. */
    private static int validDocumentSlot(ServerPlayer target, int requested) {
        if (requested >= 0 && requested < INVENTORY_SLOTS
                && DocumentItem.getDocument(target.getInventory().getItem(requested)) != null) {
            return requested;
        }
        for (int slot = 0; slot < INVENTORY_SLOTS; slot++) {
            if (DocumentItem.getDocument(target.getInventory().getItem(slot)) != null) {
                return slot;
            }
        }
        return -1;
    }

    /** Writes the re-signed tag back to the exact inventory slot. */
    private static void persist(ServerPlayer target, int slot, ItemStack stack, DocumentData doc) {
        if (stack == null) {
            return;
        }
        DocumentItem.writeDocument(stack, doc);
        if (slot >= 0 && slot < target.getInventory().getContainerSize()) {
            target.getInventory().setItem(slot, stack);
        }
        if (slot == target.getInventory().selected) {
            target.setItemInHand(InteractionHand.MAIN_HAND, stack);
        }
        target.getInventory().setChanged();
        target.inventoryMenu.broadcastChanges();
        target.containerMenu.broadcastChanges();
    }

    /** Appends an officer action to the shared border log. */
    private static void log(ServerPlayer actor, ServerPlayer target, String document, String result,
                            String detail) {
        MinecraftServer server = actor.getServer();
        if (server == null) {
            return;
        }
        String operator = BorderService.playerName(actor);
        String text = detail == null || detail.isBlank() ? operator : operator + " " + detail;
        BorderTravelData.get(server).addLog(new BorderLog(System.currentTimeMillis(), target.getUUID(),
                BorderService.playerName(target), BorderLog.OFFICER, result, document, "", "",
                "border_terminal", text));
    }

    private static void message(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), false);
    }
}
