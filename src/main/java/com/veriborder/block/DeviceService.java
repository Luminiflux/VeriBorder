package com.veriborder.block;

import com.veriborder.border.BorderLog;
import com.veriborder.border.BorderService;
import com.veriborder.border.BorderSession;
import com.veriborder.doc.DateUtil;
import com.veriborder.doc.CredentialCode;
import com.veriborder.doc.VisaRecord;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentFactory;
import com.veriborder.doc.DocumentKind;
import com.veriborder.doc.DocumentStatus;
import com.veriborder.doc.DocumentValidator;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.EndorsementRecord;
import com.veriborder.doc.EndorsementType;
import com.veriborder.doc.StampRecord;
import com.veriborder.doc.VerificationResult;
import com.veriborder.doc.VisaType;
import com.veriborder.item.DeviceBrushItem;
import com.veriborder.item.DocumentItem;
import com.veriborder.network.DeviceActionPacket;
import com.veriborder.network.ModNetwork;
import com.veriborder.network.OpenDevicePacket;
import com.veriborder.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class DeviceService {
    private DeviceService() {
    }

    private record HeldDocument(int slot, ItemStack stack, DocumentData data) {
    }

    public static void onUse(ServerPlayer player, BlockPos pos, InteractionHand hand) {
        BlockEntity blockEntity = player.level().getBlockEntity(pos);
        if (!(blockEntity instanceof DeviceBlockEntity device)) {
            return;
        }
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 100.0D) {
            message(player, "message.veriborder.too_far");
            return;
        }
        DeviceType type = device.deviceType();
        HeldDocument held = heldDocument(player, hand);
        switch (type) {
            case ID_ISSUER, PASSPORT_ISSUER, PERMIT_ISSUER -> openIssuer(player, pos, device, type);
            case VISA_ISSUER, SELF_VISA -> openVisa(player, pos, device, held != null
                    && held.data.kind() == DocumentKind.PASSPORT
                    ? held.slot : findFirstSlot(player, DocumentKind.PASSPORT));
            case ENDORSEMENT_MACHINE, SELF_ENDORSEMENT -> openEndorsement(player, pos, device, type,
                    held != null && held.data.kind() == DocumentKind.PERMIT
                            ? held.slot : findFirstSlot(player, DocumentKind.PERMIT));
            case HOTEL_READER, DESK_SCANNER, AIRPORT_CHECKIN, STATION_VERIFIER ->
                    openReader(player, pos, device, type, held, type == DeviceType.AIRPORT_CHECKIN
                            ? device.airportCountry() : "");
            case MANUAL_TERMINAL -> {
                // The manual terminal doubles as the officer console: OPs get the
                // session/log overview, everyone else the normal document reader.
                if (player.hasPermissions(2)) {
                    openOfficer(player, pos, device);
                } else {
                    openReader(player, pos, device, type, held, "");
                }
            }
            case EGATE, EXIT_GATE -> useGate(player, pos, device, held);
            case ENTRY_STAMP, EXIT_STAMP -> useStamp(player, pos, device, type, held);
            case STAMP_HISTORY -> openHistory(player, pos, device, held);
            case LOSS_TERMINAL, RENEWAL_MACHINE, PRINT_PREVIEW -> openService(player, pos, device, type, held);
            case BLACKLIST_TERMINAL -> openBlacklist(player, pos, device);
        }
    }

    private static void openIssuer(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, DeviceType type) {
        CompoundTag payload = payload(device, type);
        send(player, pos, type.id(), payload);
    }

    private static void openVisa(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, int slot) {
        DeviceType type = device.deviceType();
        CompoundTag payload = payload(device, type);
        payload.putBoolean("selfService", type == DeviceType.SELF_VISA);
        payload.putString("machineAuthority", visaMachineAuthority(device));
        ItemStack stack = stackAt(player, slot);
        DocumentData doc = stack == null ? null : DocumentItem.getDocument(stack);
        if (doc != null && doc.kind() == DocumentKind.PASSPORT) {
            payload.putInt("targetSlot", slot);
            payload.put("doc", doc.tag().copy());
        } else {
            payload.putInt("targetSlot", -1);
        }
        send(player, pos, type.id(), payload);
    }

    /** The authority a self-service visa kiosk stamps on visas. */
    private static String visaMachineAuthority(DeviceBlockEntity device) {
        if (device.lockAuthority() && !device.lockedAuthority().isBlank()) {
            return device.lockedAuthority();
        }
        return device.country() + "驻华使领馆";
    }

    private static void openEndorsement(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, DeviceType type,
                                        int slot) {
        CompoundTag payload = payload(device, type);
        // Self-service kiosks issue with the machine's authority and never show
        // authority/template/code/renew/revoke controls.
        payload.putBoolean("selfService", type == DeviceType.SELF_ENDORSEMENT);
        payload.putString("machineAuthority", machineAuthority(device));
        ItemStack stack = stackAt(player, slot);
        DocumentData doc = stack == null ? null : DocumentItem.getDocument(stack);
        if (doc != null && doc.kind() == DocumentKind.PERMIT) {
            payload.putInt("targetSlot", slot);
            payload.put("doc", doc.tag().copy());
        } else {
            payload.putInt("targetSlot", -1);
        }
        send(player, pos, type.id(), payload);
    }

    /** The authority a self-service endorsement machine stamps on records. */
    private static String machineAuthority(DeviceBlockEntity device) {
        if (device.lockAuthority() && !device.lockedAuthority().isBlank()) {
            return device.lockedAuthority();
        }
        return device.country() + "出入境管理局";
    }

    private static String endorsementAuthority(DeviceBlockEntity device, String requested) {
        if (device.lockAuthority() && !device.lockedAuthority().isBlank()) {
            return device.lockedAuthority();
        }
        return requested == null || requested.isBlank()
                ? device.country() + "出入境管理局" : requested;
    }

    private static String visaAuthority(DeviceBlockEntity device, String requested) {
        if (device.lockAuthority() && !device.lockedAuthority().isBlank()) {
            return device.lockedAuthority();
        }
        return requested == null || requested.isBlank()
                ? device.country() + "驻华使领馆" : requested;
    }

    /**
     * Opens the brush-only configuration GUI. The brush is re-checked here as
     * well as in {@code DeviceBlock#use} and {@code save_config} so that no
     * packet or command path can reach terminal configuration without it.
     */
    public static void openConfig(ServerPlayer player, BlockPos pos) {
        BlockEntity blockEntity = player.level().getBlockEntity(pos);
        if (!(blockEntity instanceof DeviceBlockEntity device)) {
            return;
        }
        // Terminal configuration is an admin action: OP level 2, checked on the
        // server because the client can send this packet directly.
        if (!player.hasPermissions(2)) {
            message(player, "message.veriborder.permission.denied");
            return;
        }
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 100.0D) {
            message(player, "message.veriborder.too_far");
            return;
        }
        if (!holdsBrush(player)) {
            message(player, "message.veriborder.brush.required");
            return;
        }
        DeviceType type = device.deviceType();
        CompoundTag payload = payload(device, type);
        payload.putInt("slot", -1);
        send(player, pos, "config", payload);
    }

    private static void openReader(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, DeviceType type,
                                   @Nullable HeldDocument held, String destination) {
        CompoundTag payload = payload(device, type);
        VerificationResult result;
        if (held == null) {
            result = VerificationResult.fail(VerificationResult.Code.NO_DOCUMENT);
            payload.putInt("slot", -1);
        } else {
            payload.putInt("slot", held.slot);
            payload.put("doc", held.data.tag().copy());
            result = DocumentValidator.validate(held.data, type, destination);
        }
        payload.put("check", result.save());
        if (type == DeviceType.AIRPORT_CHECKIN) {
            payload.putString("destination", destination);
        }
        send(player, pos, type.id(), payload);
    }

    private static void openHistory(ServerPlayer player, BlockPos pos, DeviceBlockEntity device,
                                    @Nullable HeldDocument held) {
        CompoundTag payload = payload(device, DeviceType.STAMP_HISTORY);
        // Stamp history covers passports and HK/Macau permits.
        if (held != null && DocumentValidator.isStampable(held.data.kind())) {
            payload.putInt("slot", held.slot);
            payload.put("doc", held.data.tag().copy());
            payload.put("check", DocumentValidator.validate(held.data, DeviceType.STAMP_HISTORY, "").save());
        } else {
            payload.putInt("slot", -1);
            payload.put("check", VerificationResult.fail(VerificationResult.Code.WRONG_KIND).save());
        }
        send(player, pos, DeviceType.STAMP_HISTORY.id(), payload);
    }

    private static void openService(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, DeviceType type,
                                    @Nullable HeldDocument held) {
        CompoundTag payload = payload(device, type);
        if (held != null) {
            payload.putInt("slot", held.slot);
            payload.put("doc", held.data.tag().copy());
            payload.put("check", DocumentValidator.validate(held.data, type, "").save());
        } else {
            payload.putInt("slot", -1);
            payload.put("check", VerificationResult.fail(VerificationResult.Code.NO_DOCUMENT).save());
        }
        send(player, pos, type.id(), payload);
    }

    private static void openBlacklist(ServerPlayer player, BlockPos pos, DeviceBlockEntity device) {
        CompoundTag payload = payload(device, DeviceType.BLACKLIST_TERMINAL);
        payload.put("docs", scanDocuments(player));
        send(player, pos, DeviceType.BLACKLIST_TERMINAL.id(), payload);
    }

    /**
     * Officer console payload. The device id is {@code "officer"} (like
     * {@code "config"}) so the client routes it to the officer screen instead of
     * a reader; {@code officer=true} is the authoritative flag.
     *
     * <pre>
     * deviceType String           "manual_terminal"
     * officer    boolean          true
     * sessions   ListTag          BorderSession compounds, newest entry first
     * logs       ListTag          BorderLog compounds, newest first
     * </pre>
     */
    private static void openOfficer(ServerPlayer player, BlockPos pos, @Nullable DeviceBlockEntity device) {
        if (!player.hasPermissions(2)) {
            message(player, "message.veriborder.permission.denied");
            return;
        }
        CompoundTag payload = device == null ? new CompoundTag() : payload(device, DeviceType.MANUAL_TERMINAL);
        payload.putString("deviceType", DeviceType.MANUAL_TERMINAL.id());
        payload.putBoolean("officer", true);
        payload.put("sessions", BorderService.sessionsTag(player.getServer()));
        payload.put("logs", BorderService.logsTag(player.getServer(), BorderService.OFFICER_LOG_LIMIT));
        send(player, pos, "officer", payload);
    }

    /**
     * Officer-screen actions. They are OP level 2 and do not require a device to
     * be looked at again, so the console keeps working after the terminal was
     * broken or the player walked away; a refresh re-sends the current state.
     */
    private static void handleOfficerAction(ServerPlayer player, BlockPos pos, String action, CompoundTag data) {
        if (!player.hasPermissions(2)) {
            message(player, "message.veriborder.permission.denied");
            return;
        }
        if ("officer_clear".equals(action)) {
            UUID target = targetUuid(player, data);
            if (target != null) {
                // Logs an officer entry naming the operator.
                BorderService.clearSession(player.getServer(), target, BorderService.playerName(player));
            }
        }
        BlockEntity blockEntity = player.level().getBlockEntity(pos);
        DeviceBlockEntity device = blockEntity instanceof DeviceBlockEntity found ? found : null;
        openOfficer(player, pos, device);
    }

    /**
     * Target UUID of an officer clear. Accepts the canonical {@code target}
     * string (UUID) and tolerates {@code uuid} / {@code player}; a player name
     * resolves to the online player.
     */
    @Nullable
    private static UUID targetUuid(ServerPlayer player, CompoundTag data) {
        String raw = data.getString("target").trim();
        if (raw.isEmpty()) {
            raw = data.getString("uuid").trim();
        }
        if (raw.isEmpty()) {
            raw = data.getString("player").trim();
        }
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            MinecraftServer server = player.getServer();
            ServerPlayer online = server == null ? null : server.getPlayerList().getPlayerByName(raw);
            return online == null ? null : online.getUUID();
        }
    }

    public static void openModifier(ServerPlayer player) {
        // The universal document modifier is a creative-only admin tool.
        if (!player.hasPermissions(2)) {
            message(player, "message.veriborder.permission.denied");
            return;
        }
        ListTag docs = scanDocuments(player);
        CompoundTag payload = new CompoundTag();
        payload.put("docs", docs);
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new OpenDevicePacket(BlockPos.ZERO, "modifier", payload));
    }

    public static void generateFromModifier(ServerPlayer player, CompoundTag data) {
        if (!player.hasPermissions(2)) {
            message(player, "message.veriborder.permission.denied");
            return;
        }
        DocumentKind kind;
        try {
            kind = DocumentKind.valueOf(data.getString("kind"));
        } catch (IllegalArgumentException exception) {
            message(player, "message.veriborder.generator.invalid");
            return;
        }
        DocStyle style = DocStyle.byId(data.getString("style"));
        if (style.kind() != kind) {
            message(player, "message.veriborder.generator.invalid");
            return;
        }
        if (data.getString("name").isBlank() || !DateUtil.isValid(data.getString("birthDate"))) {
            message(player, "message.veriborder.generator.incomplete");
            return;
        }
        DocumentData doc = DocumentFactory.fromForm(style, data);
        Item item = switch (kind) {
            case ID_CARD -> ModItems.ID_CARD.get();
            case PASSPORT -> ModItems.PASSPORT.get();
            case PERMIT -> ModItems.HK_MACAO_PERMIT.get();
        };
        ItemStack stack = new ItemStack(item);
        DocumentItem.writeDocument(stack, doc);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_WORK_CARTOGRAPHER,
                SoundSource.PLAYERS, 0.8F, 1.15F);
        message(player, "message.veriborder.generator.created", doc.displayNameComponent(), doc.number());
    }

    private static void useStamp(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, DeviceType type,
                                 @Nullable HeldDocument held) {
        // Passports and HK/Macau permits are stamped; identity cards are not.
        if (held == null || !DocumentValidator.isStampable(held.data.kind())) {
            CompoundTag payload = payload(device, type);
            payload.putInt("slot", -1);
            payload.putString("stampDirection", type == DeviceType.ENTRY_STAMP ? StampRecord.IN : StampRecord.OUT);
            send(player, pos, type.id(), payload);
            return;
        }
        VerificationResult result = DocumentValidator.validate(held.data, type, "");
        if (!result.valid()) {
            message(player, "message.veriborder.stamp.rejected");
            CompoundTag payload = payload(device, type);
            payload.putInt("slot", held.slot);
            payload.put("doc", held.data.tag().copy());
            payload.put("check", result.save());
            payload.putString("stampDirection", type == DeviceType.ENTRY_STAMP ? StampRecord.IN : StampRecord.OUT);
            send(player, pos, type.id(), payload);
            return;
        }
        held.data.addStamp(type == DeviceType.ENTRY_STAMP ? StampRecord.IN : StampRecord.OUT,
                device.port(), device.country(), device.officer());
        DocumentItem.writeDocument(held.stack, held.data);
        sync(player, held.slot, held.stack);
        player.level().playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.8F, 0.8F);
        message(player, "message.veriborder.stamp.done", device.port());
        CompoundTag payload = payload(device, type);
        payload.putInt("slot", held.slot);
        payload.put("doc", held.data.tag().copy());
        payload.put("check", VerificationResult.ok("").save());
        payload.putString("stampDirection", type == DeviceType.ENTRY_STAMP ? StampRecord.IN : StampRecord.OUT);
        send(player, pos, type.id(), payload);
    }

    private static void useGate(ServerPlayer player, BlockPos pos, DeviceBlockEntity device,
                                @Nullable HeldDocument held) {
        DeviceType type = device.deviceType();
        if (held == null) {
            openGateStatus(player, pos, device, type);
            return;
        }
        if (type == DeviceType.EXIT_GATE) {
            useExitGate(player, pos, device, held);
        } else {
            useEntryGate(player, pos, device, held);
        }
    }

    /**
     * Entry gate. The matched visa/endorsement is deliberately <em>not</em>
     * consumed here: the entry is recorded as a {@link BorderSession} and the
     * record is only used up by the matching exit. A traveller who is already
     * inside is always refused, whatever entries the document still shows.
     */
    private static void useEntryGate(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, HeldDocument held) {
        if (BorderService.session(player) != null) {
            denyGate(player, pos, device, held, BorderLog.IN, BorderService.REASON_ALREADY_ENTERED);
            return;
        }
        VerificationResult result = DocumentValidator.validateGate(held.data, device.deviceType(),
                device.gateMode(), device.gateCodes());
        if (!result.valid()) {
            // Code/mode failures are rejected before the gate state machine starts,
            // and the chat message names the required mode or the mismatched code.
            BorderService.deny(player, pos, held.data, null, BorderLog.IN, firstReason(result));
            sendGateCheck(player, pos, device, result);
            return;
        }
        BorderService.EntryMatch match = BorderService.resolveEntry(held.data, device.gateMode(),
                device.gateCodes());
        if (match == null) {
            // Defensive: validation passed but no credential could be bound.
            BorderService.deny(player, pos, held.data, null, BorderLog.IN,
                    DocumentValidator.REASON_GATE_CODE_MISMATCH);
            sendGateCheck(player, pos, device, VerificationResult.fail(
                    VerificationResult.Code.NO_MATCHING_CODE, DocumentValidator.REASON_GATE_CODE_MISMATCH));
            return;
        }
        if (!device.startVerification(player, result)) {
            message(player, "message.veriborder.gate.busy");
            return;
        }
        BorderService.beginEntry(player, pos, device.port(), device.overstayDays(), held.data, match);
        autoGateStamp(player, device, held, StampRecord.IN);
        message(player, "message.veriborder.gate.verifying");
    }

    /**
     * Exit gate. Reads the stored session (gate codes/mode are ignored) and
     * consumes exactly the record that entry bound; visa-free and
     * {@code all}-mode entries consume nothing.
     */
    private static void useExitGate(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, HeldDocument held) {
        BorderSession session = BorderService.session(player);
        if (session == null) {
            denyGate(player, pos, device, held, BorderLog.OUT, BorderService.REASON_NO_ENTRY_RECORD);
            return;
        }
        if (!session.documentNumber().equals(held.data.number())) {
            denyGate(player, pos, device, held, BorderLog.OUT, BorderService.REASON_SESSION_DOCUMENT_MISMATCH);
            return;
        }
        VerificationResult result = DocumentValidator.validateGate(held.data, DeviceType.EXIT_GATE, "", List.of());
        if (!result.valid()) {
            BorderService.deny(player, pos, held.data, session, BorderLog.OUT, firstReason(result));
            sendGateCheck(player, pos, device, result);
            return;
        }
        int recordIndex = boundRecordIndex(held.data, session);
        if (session.consumesRecord() && recordIndex < 0) {
            denyGate(player, pos, device, held, BorderLog.OUT, BorderService.REASON_SESSION_RECORD_MISSING);
            return;
        }
        if (!device.startVerification(player, result)) {
            message(player, "message.veriborder.gate.busy");
            return;
        }
        if (recordIndex >= 0) {
            consumeBoundRecord(player, held, session.kind(), recordIndex);
        }
        boolean overstay = BorderService.finishExit(player, session, pos);
        autoGateStamp(player, device, held, StampRecord.OUT);
        if (overstay) {
            // The exit is still allowed; the warning plus the OVERSTAY log record it.
            player.displayClientMessage(Component.translatable(BorderService.MESSAGE_OVERSTAY), false);
        }
    }

    private static void openGateStatus(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, DeviceType type) {
        CompoundTag payload = payload(device, type);
        payload.putString("gateState", device.gateState().name());
        payload.putString("gateMessage", device.gateMessage());
        send(player, pos, type.id(), payload);
    }

    /** Index of the session-bound record in the presented document, or -1. */
    private static int boundRecordIndex(DocumentData doc, BorderSession session) {
        if (BorderSession.KIND_VISA.equals(session.kind())) {
            List<CompoundTag> tags = doc.visaTags();
            for (int i = 0; i < tags.size(); i++) {
                VisaRecord record = VisaRecord.fromTag(tags.get(i));
                if (record.active() && sameRecord(session, record.id(), record.code())) {
                    return i;
                }
            }
            return -1;
        }
        if (BorderSession.KIND_ENDORSEMENT.equals(session.kind())) {
            List<CompoundTag> tags = doc.endorsementTags();
            for (int i = 0; i < tags.size(); i++) {
                EndorsementRecord record = EndorsementRecord.fromTag(tags.get(i));
                if (record.active() && sameRecord(session, record.id(), record.code())) {
                    return i;
                }
            }
            return -1;
        }
        return -1;
    }

    /**
     * Identity of the bound record: the stored record id wins; the eight-digit
     * code is only used for sessions without an id (visa-free or legacy).
     */
    private static boolean sameRecord(BorderSession session, String id, String code) {
        if (!session.recordId().isBlank()) {
            return session.recordId().equals(id);
        }
        return !session.code().isBlank() && session.code().equals(code);
    }

    /** Increments {@code usedEntries} on exactly one record and persists it. */
    private static void consumeBoundRecord(ServerPlayer player, HeldDocument held, String kind, int index) {
        DocumentData doc = held.data;
        if (BorderSession.KIND_VISA.equals(kind)) {
            List<CompoundTag> tags = doc.visaTags();
            if (index < 0 || index >= tags.size()) {
                return;
            }
            CompoundTag tag = tags.get(index);
            // Persist the effective code (legacy records derive it from their id)
            // so consuming an entry never changes the code the gate was set to.
            tag.putString("code", VisaRecord.fromTag(tag).code());
            tag.putInt("usedEntries", tag.getInt("usedEntries") + 1);
            doc.updateVisa(index, tag);
        } else if (BorderSession.KIND_ENDORSEMENT.equals(kind)) {
            List<CompoundTag> tags = doc.endorsementTags();
            if (index < 0 || index >= tags.size()) {
                return;
            }
            CompoundTag tag = tags.get(index);
            tag.putString("code", EndorsementRecord.fromTag(tag).code());
            tag.putInt("usedEntries", tag.getInt("usedEntries") + 1);
            doc.updateEndorsement(index, tag);
        } else {
            return;
        }
        DocumentItem.writeDocument(held.stack, doc);
        sync(player, held.slot, held.stack);
    }

    /** Gate auto stamp: passports and HK/Macau permits only, when enabled. */
    private static void autoGateStamp(ServerPlayer player, DeviceBlockEntity device, HeldDocument held,
                                      String direction) {
        if (!device.autoStamp() || !DocumentValidator.isStampable(held.data.kind())) {
            return;
        }
        held.data.addStamp(direction, device.port(), device.country(), device.officer());
        DocumentItem.writeDocument(held.stack, held.data);
        sync(player, held.slot, held.stack);
    }

    /** First machine-readable reason of a failed check, for the log detail. */
    private static String firstReason(VerificationResult result) {
        return result.reasons().isEmpty() ? "verify.veriborder.fail" : result.reasons().get(0);
    }

    /** Ordinary gate rejection payload; starts no animation. */
    private static void sendGateCheck(ServerPlayer player, BlockPos pos, DeviceBlockEntity device,
                                      VerificationResult result) {
        CompoundTag payload = payload(device, device.deviceType());
        payload.putString("gateState", device.gateState().name());
        payload.putString("gateMessage", "gate.veriborder.denied");
        payload.put("check", result.save());
        send(player, pos, device.deviceType().id(), payload);
        player.displayClientMessage(result.message(), false);
    }

    /**
     * Session-level denial (already entered, no entry record, document
     * mismatch, record missing). Logs the attempt and shows the plain reason;
     * the gate stays idle and is not animated.
     */
    private static void denyGate(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, HeldDocument held,
                                 String direction, String reasonKey) {
        BorderService.deny(player, pos, held.data, BorderService.session(player), direction, reasonKey);
        VerificationResult check = VerificationResult.fail(VerificationResult.Code.NOT_APPLICABLE, reasonKey);
        CompoundTag payload = payload(device, device.deviceType());
        payload.putString("gateState", device.gateState().name());
        payload.putString("gateMessage", "gate.veriborder.denied");
        payload.put("check", check.save());
        send(player, pos, device.deviceType().id(), payload);
        player.displayClientMessage(Component.translatable(reasonKey), false);
    }

    private static CompoundTag payload(DeviceBlockEntity device, DeviceType type) {
        CompoundTag payload = device.configTag();
        payload.putString("deviceType", type.id());
        return payload;
    }

    private static void send(ServerPlayer player, BlockPos pos, String device, CompoundTag payload) {
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new OpenDevicePacket(pos, device, payload));
    }

    /** Configuration is only reachable while the device brush is held. */
    private static boolean holdsBrush(ServerPlayer player) {
        return player.getItemInHand(InteractionHand.MAIN_HAND).getItem() instanceof DeviceBrushItem
                || player.getItemInHand(InteractionHand.OFF_HAND).getItem() instanceof DeviceBrushItem;
    }

    @Nullable
    private static HeldDocument heldDocument(ServerPlayer player, InteractionHand hand) {
        ItemStack main = player.getItemInHand(hand);
        HeldDocument primary = heldFromStack(player, hand, main);
        if (primary != null) {
            return primary;
        }
        InteractionHand other = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        return heldFromStack(player, other, player.getItemInHand(other));
    }

    @Nullable
    private static HeldDocument heldFromStack(ServerPlayer player, InteractionHand hand, ItemStack stack) {
        DocumentData data = DocumentItem.getDocument(stack);
        if (data == null) {
            return null;
        }
        return new HeldDocument(hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : 40, stack, data);
    }

    @Nullable
    private static HeldDocument firstDocument(ServerPlayer player, DocumentKind kind) {
        for (int slot = 0; slot < 41; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            DocumentData data = DocumentItem.getDocument(stack);
            if (data != null && data.kind() == kind) {
                return new HeldDocument(slot, stack, data);
            }
        }
        return null;
    }

    private static int findFirstSlot(ServerPlayer player, DocumentKind kind) {
        HeldDocument document = firstDocument(player, kind);
        return document == null ? -1 : document.slot;
    }

    @Nullable
    private static ItemStack stackAt(ServerPlayer player, int slot) {
        if (slot < 0 || slot >= player.getInventory().getContainerSize()) {
            return null;
        }
        return player.getInventory().getItem(slot);
    }

    private static ListTag scanDocuments(ServerPlayer player) {
        ListTag list = new ListTag();
        for (int slot = 0; slot < 41; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            DocumentData data = DocumentItem.getDocument(stack);
            if (data != null) {
                CompoundTag entry = new CompoundTag();
                entry.putInt("slot", slot);
                entry.put("doc", data.tag().copy());
                list.add(entry);
            }
        }
        return list;
    }

    private static void sync(ServerPlayer player, int slot, ItemStack stack) {
        if (slot >= 0 && slot < player.getInventory().getContainerSize()) {
            player.getInventory().setItem(slot, stack);
        }
        if (slot == player.getInventory().selected) {
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        }
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
    }

    public static void handleAction(ServerPlayer player, BlockPos pos, String action, CompoundTag data, int slot) {
        // Officer console actions are OP-only and independent of the block they
        // were opened from, so they are handled before the device lookup.
        if ("officer_clear".equals(action) || "officer_refresh".equals(action)) {
            handleOfficerAction(player, pos, action, data);
            return;
        }
        BlockEntity blockEntity = player.level().getBlockEntity(pos);
        if (!(blockEntity instanceof DeviceBlockEntity device)) {
            return;
        }
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 100.0D) {
            message(player, "message.veriborder.too_far");
            return;
        }
        switch (action) {
            case "issue_document" -> issueDocument(player, device, data);
            case "save_preset" -> {
                device.savePreset(data.getString("presetName"), stripPresetMeta(data));
                message(player, "message.veriborder.preset.saved");
                refresh(player, pos, device, slot);
            }
            case "delete_preset" -> {
                device.deletePreset(data.getString("presetName"));
                message(player, "message.veriborder.preset.deleted");
                refresh(player, pos, device, slot);
            }
            case "save_config" -> {
                // Configuration is an OP-2 admin action; the brush is still
                // required as a second, physical gate on the same operation.
                if (!player.hasPermissions(2)) {
                    message(player, "message.veriborder.permission.denied");
                    return;
                }
                if (!holdsBrush(player)) {
                    message(player, "message.veriborder.brush.required");
                    return;
                }
                device.setPort(data.getString("port"));
                device.setCountry(data.getString("country"));
                device.setOfficer(data.getString("officer"));
                device.setAirportCountry(data.getString("airportCountry"));
                if (device.deviceType().isGate()) {
                    device.setGateMode(data.getString("gateMode"));
                    // Codes are the only gate validation source. The block entity
                    // validates 8 digits and dedupes; the list has no length limit.
                    device.setGateCodes(data.getList("gateCodes", Tag.TAG_STRING));
                    // 26.1 gate behaviour. Applied only when the screen sends the
                    // keys, so an older client cannot silently reset the defaults.
                    if (data.contains("autoStamp")) {
                        device.setAutoStamp(data.getBoolean("autoStamp"));
                    }
                    if (data.contains("overstayDays")) {
                        device.setOverstayDays(data.getInt("overstayDays"));
                    }
                }
                if (device.deviceType().isIssuer()) {
                    device.setLockDocumentName(data.getBoolean("lockDocumentName"));
                    device.setLockedDocumentName(data.getString("lockedDocumentName"));
                    device.setLockAuthority(data.getBoolean("lockAuthority"));
                    device.setLockedAuthority(data.getString("lockedAuthority"));
                    if (device.deviceType() == DeviceType.SELF_VISA
                            || device.deviceType() == DeviceType.SELF_ENDORSEMENT) {
                        device.setIssuedCode(data.getString("issuedCode"));
                    }
                    if (device.deviceType() == DeviceType.PERMIT_ISSUER) {
                        device.setLockEndorsementCode(data.getBoolean("lockEndorsementCode"));
                        device.setLockedEndorsementCode(data.getString("lockedEndorsementCode"));
                    }
                    applyIssuerLockConfig(device, data);
                }
                message(player, "message.veriborder.config.saved");
                // The client closes the brush config screen after saving; do not
                // reopen an operational GUI here (that would surprise the player).
            }
            case "issue_visa" -> issueVisa(player, pos, device, data, slot, false);
            case "issue_all_visas" -> issueVisa(player, pos, device, data, slot, true);
            case "save_template" -> {
                device.savePreset(data.getString("presetName"), stripPresetMeta(data));
                message(player, "message.veriborder.template.saved");
                refresh(player, pos, device, slot);
            }
            case "issue_endorsement" -> issueEndorsement(player, pos, device, data, slot);
            case "renew_endorsement" -> renewEndorsement(player, pos, device, data, slot);
            case "revoke_endorsement" -> revokeEndorsement(player, pos, device, data, slot);
            case "set_status" -> setStatus(player, pos, device, data, slot);
            case "renew" -> renew(player, pos, device, data, slot);
            case "clear_stamps" -> clearStamps(player, pos, device, slot);
            case "open_reader" -> refresh(player, pos, device, slot);
            default -> {
            }
        }
    }

    private static CompoundTag stripPresetMeta(CompoundTag data) {
        CompoundTag copy = data.copy();
        copy.remove("presetName");
        copy.remove("action");
        return copy;
    }

    /**
     * Persists the per-device-type 1.2.1 lock fields from the brush config
     * payload. Reading a key only for the device kinds that actually show the
     * control keeps a value that is not part of this screen untouched.
     */
    private static void applyIssuerLockConfig(DeviceBlockEntity device, CompoundTag data) {
        DeviceType type = device.deviceType();
        boolean documentIssuer = type == DeviceType.ID_ISSUER
                || type == DeviceType.PASSPORT_ISSUER
                || type == DeviceType.PERMIT_ISSUER;
        boolean visaIssuer = type == DeviceType.VISA_ISSUER
                || type == DeviceType.SELF_VISA
                || type == DeviceType.PASSPORT_ISSUER;
        boolean endorsementIssuer = type == DeviceType.ENDORSEMENT_MACHINE
                || type == DeviceType.SELF_ENDORSEMENT;
        if (documentIssuer) {
            device.setLockStyle(data.getBoolean("lockStyle"));
            device.setLockedStyle(data.getString("lockedStyle"));
        }
        if (visaIssuer) {
            // PASSPORT_ISSUER also locks the visa attached on issue.
            device.setLockVisaType(data.getBoolean("lockVisaType"));
            device.setLockedVisaType(data.getString("lockedVisaType"));
            device.setLockVisaCode(data.getBoolean("lockVisaCode"));
            device.setLockedVisaCode(data.getString("lockedVisaCode"));
        }
        if (documentIssuer || visaIssuer) {
            device.setLockPlace(data.getBoolean("lockPlace"));
            device.setLockedPlace(data.getString("lockedPlace"));
        }
        if (documentIssuer || visaIssuer || endorsementIssuer) {
            device.setLockIssueDate(data.getBoolean("lockIssueDate"));
            device.setLockedIssueDate(data.getString("lockedIssueDate"));
            device.setLockValidity(data.getBoolean("lockValidity"));
            device.setLockedValidityYears(data.getInt("lockedValidityYears"));
        }
        if (endorsementIssuer) {
            device.setLockEndorsementType(data.getBoolean("lockEndorsementType"));
            device.setLockedEndorsementType(data.getString("lockedEndorsementType"));
        }
        // 26.1 passport-attached visa locks. Only devices that can use them read
        // the keys, and only when the screen actually sends them: an older client
        // or another device kind leaves the stored values untouched.
        if (type == DeviceType.PASSPORT_ISSUER) {
            if (data.contains("lockIncludeVisa")) {
                device.setLockIncludeVisa(data.getBoolean("lockIncludeVisa"));
            }
            if (data.contains("lockedIncludeVisa")) {
                device.setLockedIncludeVisa(data.getBoolean("lockedIncludeVisa"));
            }
            if (data.contains("lockVisaEntries")) {
                device.setLockVisaEntries(data.getBoolean("lockVisaEntries"));
            }
            if (data.contains("lockedVisaEntries")) {
                device.setLockedVisaEntries(data.getInt("lockedVisaEntries"));
            }
            if (data.contains("lockVisaCountry")) {
                device.setLockVisaCountry(data.getBoolean("lockVisaCountry"));
            }
            if (data.contains("lockedVisaCountry")) {
                device.setLockedVisaCountry(data.getString("lockedVisaCountry"));
            }
            if (data.contains("lockVisaIssueDate")) {
                device.setLockVisaIssueDate(data.getBoolean("lockVisaIssueDate"));
            }
            if (data.contains("lockedVisaIssueDate")) {
                device.setLockedVisaIssueDate(data.getString("lockedVisaIssueDate"));
            }
            if (data.contains("lockVisaValidity")) {
                device.setLockVisaValidity(data.getBoolean("lockVisaValidity"));
            }
            if (data.contains("lockedVisaValidityYears")) {
                device.setLockedVisaValidityYears(data.getInt("lockedVisaValidityYears"));
            }
        }
        if (type == DeviceType.PASSPORT_ISSUER || type == DeviceType.VISA_ISSUER
                || type == DeviceType.SELF_VISA) {
            if (data.contains("lockVisaName")) {
                device.setLockVisaName(data.getBoolean("lockVisaName"));
            }
            if (data.contains("lockedVisaName")) {
                device.setLockedVisaName(data.getString("lockedVisaName"));
            }
        }
    }

    private static void issueDocument(ServerPlayer player, DeviceBlockEntity device, CompoundTag data) {
        DeviceType type = device.deviceType();
        DocumentKind expected = switch (type) {
            case ID_ISSUER -> DocumentKind.ID_CARD;
            case PASSPORT_ISSUER -> DocumentKind.PASSPORT;
            case PERMIT_ISSUER -> DocumentKind.PERMIT;
            default -> null;
        };
        if (expected == null) {
            return;
        }
        if (data.getString("name").isBlank()) {
            message(player, "message.veriborder.issuer.name_required");
            return;
        }
        if (!DateUtil.isValid(data.getString("birthDate"))) {
            message(player, "message.veriborder.issuer.birth_required");
            return;
        }
        DocStyle style = DocStyle.byId(data.getString("style"));
        DocStyle lockedStyle = device.lockedStyleOrNull();
        if (device.lockStyle() && lockedStyle != null && lockedStyle.kind() == expected) {
            // A valid lock replaces the submitted layout; a malicious client
            // cannot switch an ID issuer to a passport layout.
            style = lockedStyle;
        } else if (style.kind() != expected) {
            message(player, "message.veriborder.issuer.wrong_style");
            return;
        }
        // Locked issuer fields are enforced server-side before the factory reads
        // the form, so a client that ignores the lock still cannot issue a
        // different document name, authority, place or date.
        CompoundTag form = data.copy();
        if (device.lockDocumentName() && !device.lockedDocumentName().isBlank()) {
            form.putString("displayName", device.lockedDocumentName());
        }
        if (device.lockAuthority() && !device.lockedAuthority().isBlank()) {
            form.putString("authority", device.lockedAuthority());
        }
        if (device.lockPlace() && !device.lockedPlace().isBlank()) {
            form.putString("placeOfIssue", device.lockedPlace());
        }
        if (device.lockIssueDate() && DateUtil.isValid(device.lockedIssueDate())) {
            form.putString("issueDate", device.lockedIssueDate());
        }
        form.putString("style", style.id());
        DocumentData doc = DocumentFactory.fromForm(style, form);
        if (device.lockValidity()) {
            // 0/permanent styles stay permanent; positive years win over the
            // style default and over any form-supplied expiry date.
            doc.setExpiryDate(DocumentFactory.lockedExpiry(doc.issueDate(), device.lockedValidityYears()));
        }
        if (expected == DocumentKind.PERMIT && form.contains("initialEndorsementType")
                && !form.getString("initialEndorsementType").isBlank()) {
            EndorsementType endorsementType = EndorsementType.byId(form.getString("initialEndorsementType"));
            String today = device.lockIssueDate() && DateUtil.isValid(device.lockedIssueDate())
                    ? device.lockedIssueDate() : DateUtil.today();
            String authority = device.lockAuthority() && !device.lockedAuthority().isBlank()
                    ? device.lockedAuthority() : style.defaultAuthority();
            boolean fixedCode = device.lockEndorsementCode()
                    && CredentialCode.isValid(device.lockedEndorsementCode());
            String code = fixedCode ? device.lockedEndorsementCode()
                    : CredentialCode.isValid(form.getString("initialEndorsementCode"))
                    ? form.getString("initialEndorsementCode") : "";
            String initialExpiry = device.lockValidity()
                    ? DocumentFactory.lockedExpiry(today, device.lockedValidityYears())
                    : DocumentFactory.endorsementExpiry(endorsementType, today);
            CompoundTag initial = DocumentFactory.createEndorsement(endorsementType,
                    endorsementType.defaultEntries(), today, initialExpiry, authority, code);
            if (fixedCode) {
                // The issuer's locked initial-endorsement code is used verbatim,
                // even if the permit already carries a record with the same code.
                doc.addEndorsementExact(initial);
            } else {
                doc.addEndorsement(initial);
            }
        }
        if (expected == DocumentKind.PASSPORT) {
            // The visa is appended after the passport exists (and is signed) but
            // before the item stack is written, so it travels in the same NBT.
            // The include/entries/country/date/validity/name locks are resolved
            // server-side, so a forged "includeVisa" cannot bypass them.
            appendPassportVisa(device, style, form, doc);
        }
        Item item = switch (expected) {
            case ID_CARD -> ModItems.ID_CARD.get();
            case PASSPORT -> ModItems.PASSPORT.get();
            case PERMIT -> ModItems.HK_MACAO_PERMIT.get();
        };
        ItemStack stack = new ItemStack(item);
        DocumentItem.writeDocument(stack, doc);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_WORK_CARTOGRAPHER,
                SoundSource.PLAYERS, 0.7F, 1.1F);
        message(player, "message.veriborder.issuer.issued", Component.translatable(style.displayNameKey()), doc.number());
    }

    /**
     * Appends the optional visa carried by a newly issued passport. The 26.1
     * passport-visa locks are resolved by {@link #buildPassportVisa}; a locked
     * code is preserved verbatim ({@code addVisaExact}), otherwise the code is
     * kept unique ({@code addVisa}).
     */
    private static void appendPassportVisa(DeviceBlockEntity device, DocStyle style, CompoundTag form,
                                           DocumentData passport) {
        CompoundTag visa = buildPassportVisa(device.locks(), passport, form,
                style.defaultNationality());
        if (visa == null) {
            return;
        }
        boolean fixedCode = device.lockVisaCode() && CredentialCode.isValid(device.lockedVisaCode());
        if (fixedCode) {
            passport.addVisaExact(visa);
        } else {
            passport.addVisa(visa);
        }
    }

    /**
     * Builds the optional visa attached to a newly issued passport, applying the
     * 26.1 passport-visa locks. Pure (no block entity/world access) so every rule
     * is unit-testable. Returns {@code null} when no visa is attached.
     *
     * <ul>
     *   <li>include: {@code lockIncludeVisa} forces {@code lockedIncludeVisa},
     *       otherwise the submitted form decides — a forged form cannot override
     *       the lock in either direction.</li>
     *   <li>entries: locked entries (0 = unlimited), else form {@code visaEntries}
     *       (default 1).</li>
     *   <li>country: locked visa country, else the legacy locked place, else the
     *       form, else the passport style nationality.</li>
     *   <li>name: locked visa name, else form {@code visaName} / {@code label}.</li>
     *   <li>issue: a valid locked issue date, else the passport issue date.</li>
     *   <li>expiry: locked validity (0/permanent), else the inherited passport
     *       expiry or one year after issue.</li>
     * </ul>
     */
    @Nullable
    public static CompoundTag buildPassportVisa(DeviceBlockEntity.Locks locks, DocumentData passport,
                                                CompoundTag form, String fallbackCountry) {
        DeviceBlockEntity.Locks settings = locks == null ? DeviceBlockEntity.Locks.NONE : locks;
        CompoundTag values = form == null ? new CompoundTag() : form;
        boolean include = settings.lockIncludeVisa()
                ? settings.lockedIncludeVisa() : values.getBoolean("includeVisa");
        if (!include) {
            return null;
        }
        VisaType lockedType = DeviceBlockEntity.visaTypeOrNull(settings.lockedVisaType());
        VisaType visaType = settings.lockVisaType() && lockedType != null
                ? lockedType : VisaType.byId(values.getString("visaType"));
        String country = settings.lockVisaCountry() ? settings.lockedVisaCountry() : "";
        if (country.isBlank() && settings.lockPlace()) {
            country = settings.lockedPlace();
        }
        if (country.isBlank()) {
            country = values.getString("visaCountry").trim();
        }
        if (country.isBlank()) {
            country = fallbackCountry == null ? "" : fallbackCountry.trim();
        }
        int entries;
        if (settings.lockVisaEntries()) {
            entries = Math.max(0, settings.lockedVisaEntries());
        } else {
            entries = values.contains("visaEntries") ? Math.max(0, values.getInt("visaEntries")) : 1;
        }
        String label;
        if (settings.lockVisaName()) {
            label = settings.lockedVisaName();
        } else {
            label = values.getString("visaName").trim();
            if (label.isBlank()) {
                label = values.getString("label").trim();
            }
        }
        String issue = settings.lockVisaIssueDate() && DateUtil.isValid(settings.lockedVisaIssueDate())
                ? settings.lockedVisaIssueDate() : "";
        String authority = settings.lockAuthority() && !settings.lockedAuthority().isBlank()
                ? settings.lockedAuthority() : passport.authority();
        boolean fixedCode = settings.lockVisaCode() && CredentialCode.isValid(settings.lockedVisaCode());
        String code = fixedCode ? settings.lockedVisaCode()
                : CredentialCode.sanitize(values.getString("visaCode"));
        return DocumentFactory.createAttachedVisa(passport, country, visaType, entries, authority, code,
                label, issue, settings.lockVisaValidity(), settings.lockedVisaValidityYears());
    }

    private static void issueVisa(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, CompoundTag data,
                                  int slot, boolean allTemplates) {
        ItemStack stack = stackAt(player, slot);
        DocumentData doc = stack == null ? null : DocumentItem.getDocument(stack);
        if (doc == null || doc.kind() != DocumentKind.PASSPORT) {
            message(player, "message.veriborder.visa.no_passport");
            return;
        }
        if (!doc.integrityValid() || !doc.styleKnown() || !doc.statusKnown()
                || doc.status() != DocumentStatus.VALID || doc.isExpired()
                || doc.name().isBlank() || doc.number().isBlank()) {
            message(player, "message.veriborder.visa.invalid_passport");
            return;
        }
        String authority = visaAuthority(device, data.getString("authority"));
        boolean authorityLocked = device.lockAuthority() && !device.lockedAuthority().isBlank();
        boolean selfService = device.deviceType() == DeviceType.SELF_VISA;
        VisaType lockedType = device.lockedVisaTypeOrNull();
        if (selfService) {
            // The kiosk exposes only the visa type (unless locked). Country,
            // authority, dates, entries and code are machine/admin parameters.
            VisaType type = device.lockVisaType() && lockedType != null
                    ? lockedType : VisaType.byId(data.getString("type"));
            // The 1.2.0 machine code stays authoritative; a 1.2.1 locked visa
            // code is honoured when the machine has no fixed code of its own.
            String fixedCode = CredentialCode.isValid(device.issuedCode())
                    ? device.issuedCode()
                    : device.lockVisaCode() && CredentialCode.isValid(device.lockedVisaCode())
                    ? device.lockedVisaCode() : "";
            String code = fixedCode.isBlank()
                    ? CredentialCode.generateUnique(doc.allCredentialCodes()) : fixedCode;
            String country = device.lockPlace() && !device.lockedPlace().isBlank()
                    ? device.lockedPlace()
                    : device.country().isBlank() ? "中国" : device.country();
            String issue = lockedIssueDate(device, DateUtil.today());
            String expiry = device.lockValidity()
                    ? DocumentFactory.lockedExpiry(issue, device.lockedValidityYears())
                    : DateUtil.plusYears(issue, 1);
            CompoundTag values = new CompoundTag();
            values.putString("country", country);
            values.putString("type", type.id());
            values.putInt("entries", type.defaultEntries());
            values.putString("issueDate", issue);
            values.putString("expiryDate", expiry);
            values.putString("authority", visaMachineAuthority(device));
            // The kiosk has no name field: only an admin lock supplies a label.
            values.putString("visaName", device.lockVisaName() ? device.lockedVisaName() : "");
            doc.addVisaExact(toVisa(values, visaMachineAuthority(device), code, true));
            message(player, "message.veriborder.visa.issued", country);
            DocumentItem.writeDocument(stack, doc);
            sync(player, slot, stack);
            player.level().playSound(null, pos, SoundEvents.VILLAGER_WORK_CARTOGRAPHER,
                    SoundSource.BLOCKS, 0.8F, 0.9F);
            openVisa(player, pos, device, slot);
            return;
        }
        if (allTemplates) {
            ListTag presets = device.presets();
            // Every template gets its own generated code; one code is never copied
            // across the batch. Locked type/place/date/validity apply to each one.
            List<String> used = new ArrayList<>(doc.allCredentialCodes());
            int count = 0;
            for (int i = 0; i < presets.size(); i++) {
                CompoundTag values = applyVisaLocks(device.locks(),
                        presets.getCompound(i).getCompound("values"));
                String code = CredentialCode.generateUnique(used);
                used.add(code);
                doc.addVisa(toVisa(values, authority, code, authorityLocked));
                count++;
            }
            message(player, "message.veriborder.visa.batch", count);
        } else {
            CompoundTag form = applyVisaLocks(device.locks(), data);
            boolean fixedCode = device.lockVisaCode() && CredentialCode.isValid(device.lockedVisaCode());
            String requested = fixedCode ? device.lockedVisaCode() : form.getString("code");
            String code = CredentialCode.isValid(requested) ? requested.trim()
                    : CredentialCode.generateUnique(doc.allCredentialCodes());
            if (!fixedCode && CredentialCode.isValid(requested)
                    && doc.allCredentialCodes().contains(code)) {
                message(player, "message.veriborder.code.duplicate");
                return;
            }
            CompoundTag visa = toVisa(form, authority, code, authorityLocked);
            if (fixedCode) {
                // An admin-locked code is used verbatim, like a kiosk code.
                doc.addVisaExact(visa);
            } else {
                doc.addVisa(visa);
            }
            String country = form.getString("country");
            message(player, "message.veriborder.visa.issued",
                    country.isBlank() ? doc.nationality() : country);
        }
        DocumentItem.writeDocument(stack, doc);
        sync(player, slot, stack);
        player.level().playSound(null, pos, SoundEvents.VILLAGER_WORK_CARTOGRAPHER, SoundSource.BLOCKS, 0.8F, 0.9F);
        openVisa(player, pos, device, slot);
    }

    /**
     * Builds a standalone visa record from the (already lock-filtered) form. The
     * display label comes from {@code visaName}, falling back to {@code label};
     * blank keeps the country/type display fallback.
     */
    public static CompoundTag toVisa(CompoundTag data, String fallbackAuthority, String code,
                                     boolean forceAuthority) {
        VisaType visaType = VisaType.byId(data.getString("type"));
        String issue = DateUtil.isValid(data.getString("issueDate")) ? data.getString("issueDate") : DateUtil.today();
        String expiry = DateUtil.isValid(data.getString("expiryDate"))
                ? data.getString("expiryDate") : DateUtil.plusYears(issue, 1);
        String authority = forceAuthority ? fallbackAuthority
                : data.getString("authority").isBlank() ? fallbackAuthority : data.getString("authority");
        String label = data.getString("visaName").trim();
        if (label.isBlank()) {
            label = data.getString("label").trim();
        }
        return DocumentFactory.createVisa(data.getString("country"), visaType,
                Math.max(0, data.getInt("entries")), issue, expiry, authority, code, label);
    }

    /**
     * Applies the issuer's visa locks to a submitted form or template. The
     * returned copy carries the forced type/country/issue date/name and, when
     * validity is locked, the expiry implied by {@link DocumentFactory#lockedExpiry}.
     */
    public static CompoundTag applyVisaLocks(DeviceBlockEntity.Locks locks, CompoundTag values) {
        DeviceBlockEntity.Locks settings = locks == null ? DeviceBlockEntity.Locks.NONE : locks;
        CompoundTag form = values.copy();
        VisaType lockedType = DeviceBlockEntity.visaTypeOrNull(settings.lockedVisaType());
        if (settings.lockVisaType() && lockedType != null) {
            form.putString("type", lockedType.id());
        }
        if (settings.lockPlace() && !settings.lockedPlace().isBlank()) {
            form.putString("country", settings.lockedPlace());
        }
        if (settings.lockIssueDate() && DateUtil.isValid(settings.lockedIssueDate())) {
            form.putString("issueDate", settings.lockedIssueDate());
        }
        if (settings.lockValidity()) {
            String issue = DateUtil.isValid(form.getString("issueDate"))
                    ? form.getString("issueDate") : DateUtil.today();
            form.putString("expiryDate", DocumentFactory.lockedExpiry(issue, settings.lockedValidityYears()));
        }
        if (settings.lockVisaName()) {
            // A locked blank value is meaningful: force the country/type fallback
            // and prevent a forged label/visaName from surviving.
            form.putString("visaName", settings.lockedVisaName());
            form.putString("label", settings.lockedVisaName());
        }
        return form;
    }

    /** The locked issue date when enabled and valid, otherwise {@code fallback}. */
    private static String lockedIssueDate(DeviceBlockEntity device, String fallback) {
        return device.lockIssueDate() && DateUtil.isValid(device.lockedIssueDate())
                ? device.lockedIssueDate().trim() : fallback;
    }

    private static void issueEndorsement(ServerPlayer player, BlockPos pos, DeviceBlockEntity device,
                                         CompoundTag data, int slot) {
        ItemStack stack = stackAt(player, slot);
        DocumentData doc = stack == null ? null : DocumentItem.getDocument(stack);
        if (doc == null || doc.kind() != DocumentKind.PERMIT) {
            message(player, "message.veriborder.endorsement.no_permit");
            return;
        }
        if (!doc.integrityValid() || !doc.styleKnown() || !doc.statusKnown()
                || doc.status() != DocumentStatus.VALID || doc.isExpired()
                || doc.name().isBlank() || doc.number().isBlank()) {
            message(player, "message.veriborder.endorsement.invalid_permit");
            return;
        }
        boolean selfService = device.deviceType() == DeviceType.SELF_ENDORSEMENT;
        EndorsementType lockedType = device.lockedEndorsementTypeOrNull();
        EndorsementType type = device.lockEndorsementType() && lockedType != null
                ? lockedType : EndorsementType.byId(data.getString("type"));
        String issue;
        String expiry;
        int entries;
        String authority;
        String code;
        if (selfService) {
            // Self-service kiosks ignore every player-supplied field: only the
            // preset endorsement type is honoured (unless locked), with type
            // defaults + today and the machine's own authority/code.
            issue = lockedIssueDate(device, DateUtil.today());
            expiry = device.lockValidity()
                    ? DocumentFactory.lockedExpiry(issue, device.lockedValidityYears())
                    : DocumentFactory.endorsementExpiry(type, issue);
            entries = type.defaultEntries();
            authority = machineAuthority(device);
            code = CredentialCode.isValid(device.issuedCode()) ? device.issuedCode() : "";
        } else {
            issue = lockedIssueDate(device,
                    DateUtil.isValid(data.getString("issueDate")) ? data.getString("issueDate") : DateUtil.today());
            if (device.lockValidity()) {
                expiry = DocumentFactory.lockedExpiry(issue, device.lockedValidityYears());
            } else {
                expiry = DateUtil.isValid(data.getString("expiryDate"))
                        ? data.getString("expiryDate") : DocumentFactory.endorsementExpiry(type, issue);
            }
            entries = data.contains("entries") ? data.getInt("entries") : type.defaultEntries();
            authority = endorsementAuthority(device, data.getString("authority"));
            code = CredentialCode.isValid(data.getString("code")) ? data.getString("code").trim() : "";
        }
        if (code.isBlank()) {
            code = CredentialCode.generateUnique(doc.allCredentialCodes());
        } else if (!selfService && doc.allCredentialCodes().contains(code)) {
            message(player, "message.veriborder.code.duplicate");
            return;
        }
        CompoundTag record = DocumentFactory.createEndorsement(type, entries, issue, expiry, authority, code);
        if (selfService) {
            doc.addEndorsementExact(record);
        } else {
            doc.addEndorsement(record);
        }
        DocumentItem.writeDocument(stack, doc);
        sync(player, slot, stack);
        message(player, "message.veriborder.endorsement.issued");
        player.level().playSound(null, pos, SoundEvents.VILLAGER_WORK_CARTOGRAPHER, SoundSource.BLOCKS, 0.8F, 0.9F);
        openEndorsement(player, pos, device, device.deviceType(), slot);
    }

    private static void renewEndorsement(ServerPlayer player, BlockPos pos, DeviceBlockEntity device,
                                         CompoundTag data, int slot) {
        ItemStack stack = stackAt(player, slot);
        DocumentData doc = stack == null ? null : DocumentItem.getDocument(stack);
        if (doc == null || doc.kind() != DocumentKind.PERMIT) {
            message(player, "message.veriborder.endorsement.no_permit");
            return;
        }
        int index = data.getInt("index");
        List<CompoundTag> endorsements = doc.endorsementTags();
        if (index < 0 || index >= endorsements.size()) {
            return;
        }
        CompoundTag record = endorsements.get(index);
        // Keep the effective code (legacy records derive it from their id).
        record.putString("code", EndorsementRecord.fromTag(record).code());
        EndorsementType type = EndorsementType.byId(record.getString("type"));
        LocalDate base = DateUtil.parseOrNull(record.getString("expiryDate"));
        if (base == null || base.isBefore(LocalDate.now())) {
            base = LocalDate.now();
        }
        LocalDate newExpiry = type.defaultMonths() > 0
                ? base.plusMonths(type.defaultMonths()) : base.plusYears(1);
        record.putString("issueDate", DateUtil.today());
        record.putString("expiryDate", newExpiry.format(DateUtil.ISO));
        record.putString("status", VisaRecord.ACTIVE);
        record.putInt("usedEntries", 0);
        doc.updateEndorsement(index, record);
        DocumentItem.writeDocument(stack, doc);
        sync(player, slot, stack);
        message(player, "message.veriborder.endorsement.renewed", record.getString("expiryDate"));
        player.level().playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.6F, 1.2F);
        openEndorsement(player, pos, device, device.deviceType(), slot);
    }

    private static void revokeEndorsement(ServerPlayer player, BlockPos pos, DeviceBlockEntity device,
                                          CompoundTag data, int slot) {
        ItemStack stack = stackAt(player, slot);
        DocumentData doc = stack == null ? null : DocumentItem.getDocument(stack);
        if (doc == null || doc.kind() != DocumentKind.PERMIT) {
            message(player, "message.veriborder.endorsement.no_permit");
            return;
        }
        int index = data.getInt("index");
        List<CompoundTag> endorsements = doc.endorsementTags();
        if (index < 0 || index >= endorsements.size()) {
            return;
        }
        CompoundTag record = endorsements.get(index);
        // Keep the effective code (legacy records derive it from their id).
        record.putString("code", EndorsementRecord.fromTag(record).code());
        record.putString("status", VisaRecord.REVOKED);
        doc.updateEndorsement(index, record);
        DocumentItem.writeDocument(stack, doc);
        sync(player, slot, stack);
        message(player, "message.veriborder.endorsement.revoked");
        player.level().playSound(null, pos, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.6F, 1.4F);
        openEndorsement(player, pos, device, device.deviceType(), slot);
    }

    private static void setStatus(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, CompoundTag data, int slot) {
        ItemStack stack = stackAt(player, slot);
        DocumentData doc = stack == null ? null : DocumentItem.getDocument(stack);
        if (doc == null) {
            message(player, "message.veriborder.no_document");
            return;
        }
        DocumentStatus status = DocumentStatus.byId(data.getString("status"));
        doc.setStatus(status);
        DocumentItem.writeDocument(stack, doc);
        sync(player, slot, stack);
        message(player, "message.veriborder.status.changed", Component.translatable(status.translationKey()));
        player.level().playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.get(), SoundSource.BLOCKS, 0.6F, 1.0F);
        openService(player, pos, device, device.deviceType(), new HeldDocument(slot, stack, doc));
    }

    private static void renew(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, CompoundTag data, int slot) {
        ItemStack stack = stackAt(player, slot);
        DocumentData doc = stack == null ? null : DocumentItem.getDocument(stack);
        if (doc == null) {
            message(player, "message.veriborder.no_document");
            return;
        }
        String expiry = data.getString("expiryDate");
        if (!DateUtil.isValid(expiry) && !DateUtil.isPermanent(expiry)) {
            int years = Math.max(1, data.getInt("years"));
            expiry = DateUtil.plusYears(doc.expiryDate(), years);
        }
        if (!doc.isPermanent()) {
            doc.setExpiryDate(expiry);
        }
        doc.setIssueDate(DateUtil.today());
        DocumentItem.writeDocument(stack, doc);
        sync(player, slot, stack);
        message(player, "message.veriborder.renew.done", doc.expiryDate());
        player.level().playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.6F, 1.2F);
        openService(player, pos, device, device.deviceType(), new HeldDocument(slot, stack, doc));
    }

    private static void clearStamps(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, int slot) {
        ItemStack stack = stackAt(player, slot);
        DocumentData doc = stack == null ? null : DocumentItem.getDocument(stack);
        if (doc == null) {
            message(player, "message.veriborder.no_document");
            return;
        }
        doc.clearStamps();
        DocumentItem.writeDocument(stack, doc);
        sync(player, slot, stack);
        message(player, "message.veriborder.stamps.cleared");
        openHistory(player, pos, device, new HeldDocument(slot, stack, doc));
    }

    private static void refresh(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, int slot) {
        DeviceType type = device.deviceType();
        ItemStack stack = stackAt(player, slot);
        DocumentData doc = stack == null ? null : DocumentItem.getDocument(stack);
        HeldDocument held = doc == null ? null : new HeldDocument(slot, stack, doc);
        switch (type) {
            case ID_ISSUER, PASSPORT_ISSUER, PERMIT_ISSUER -> openIssuer(player, pos, device, type);
            case VISA_ISSUER, SELF_VISA -> openVisa(player, pos, device, slot);
            case ENDORSEMENT_MACHINE, SELF_ENDORSEMENT -> openEndorsement(player, pos, device, type, slot);
            case EGATE, EXIT_GATE -> openGateStatus(player, pos, device, type);
            case ENTRY_STAMP, EXIT_STAMP -> {
                if (held != null) {
                    sendStampResult(player, pos, device, type, held);
                } else {
                    CompoundTag payload = payload(device, type);
                    payload.putInt("slot", -1);
                    send(player, pos, type.id(), payload);
                }
            }
            case STAMP_HISTORY -> openHistory(player, pos, device, held);
            case LOSS_TERMINAL, RENEWAL_MACHINE, PRINT_PREVIEW -> openService(player, pos, device, type, held);
            case BLACKLIST_TERMINAL -> openBlacklist(player, pos, device);
            default -> openReader(player, pos, device, type, held, type == DeviceType.AIRPORT_CHECKIN
                    ? device.airportCountry() : "");
        }
    }

    private static void sendStampResult(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, DeviceType type,
                                        HeldDocument held) {
        CompoundTag payload = payload(device, type);
        payload.putInt("slot", held.slot);
        payload.put("doc", held.data.tag().copy());
        payload.put("check", VerificationResult.ok("").save());
        payload.putString("stampDirection", type == DeviceType.ENTRY_STAMP ? StampRecord.IN : StampRecord.OUT);
        send(player, pos, type.id(), payload);
    }

    public static void saveFromModifier(ServerPlayer player, int slot, CompoundTag incomingTag) {
        if (!player.hasPermissions(2)) {
            message(player, "message.veriborder.permission.denied");
            return;
        }
        if (incomingTag == null || incomingTag.isEmpty() || incomingTag.sizeInBytes() > 262144) {
            message(player, "message.veriborder.modifier.invalid");
            return;
        }
        ItemStack stack = stackAt(player, slot);
        if (stack == null || !(stack.getItem() instanceof DocumentItem item)) {
            message(player, "message.veriborder.modifier.invalid");
            return;
        }
        DocumentData incoming;
        try {
            incoming = DocumentData.fromTag(incomingTag.copy());
        } catch (RuntimeException exception) {
            message(player, "message.veriborder.modifier.invalid");
            return;
        }
        if (incoming.kind() != item.kind()) {
            message(player, "message.veriborder.modifier.invalid");
            return;
        }
        // Keep explicit machine-fixed codes intact: a self-service kiosk may
        // intentionally issue the same configured code on multiple credentials.
        incoming.sign();
        DocumentItem.writeDocument(stack, incoming);
        sync(player, slot, stack);
        message(player, "message.veriborder.modifier.saved", incoming.name());
    }

    private static void message(ServerPlayer player, String key, Object... arguments) {
        player.displayClientMessage(Component.translatable(key, arguments), true);
    }
}
