package com.veriborder.block;

import com.veriborder.border.BorderEntry;
import com.veriborder.border.BorderLog;
import com.veriborder.border.BorderService;
import com.veriborder.doc.DateUtil;
import com.veriborder.doc.CredentialCode;
import com.veriborder.doc.VisaRecord;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentFactory;
import com.veriborder.doc.DocumentKind;
import com.veriborder.doc.DocumentStatus;
import com.veriborder.doc.DocumentTitles;
import com.veriborder.doc.DocumentValidator;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.EndorsementRecord;
import com.veriborder.doc.EndorsementType;
import com.veriborder.doc.MrzUtil;
import com.veriborder.doc.StampRecord;
import com.veriborder.doc.TravellerReasons;
import com.veriborder.doc.VerificationResult;
import com.veriborder.doc.VisaType;
import com.veriborder.item.DeviceBrushItem;
import com.veriborder.item.DocumentItem;
import com.veriborder.item.DualGateBrushConfig;
import com.veriborder.network.DeviceActionPacket;
import com.veriborder.network.ModNetwork;
import com.veriborder.network.OpenDevicePacket;
import com.veriborder.registry.CredentialRegistryData;
import com.veriborder.registry.ModItems;
import com.veriborder.registry.RegistryService;
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
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class DeviceService {
    /**
     * 26.2 permit issuer: the endorsement a newly issued permit carries when the
     * applicant did not choose one (or sent nothing at all). "Three months, one
     * entry" is the built-in default of the permit ("self-carried endorsement").
     */
    public static final EndorsementType DEFAULT_INITIAL_ENDORSEMENT = EndorsementType.THREE_MONTH_SINGLE;
    /** Form value meaning "issue this permit without a built-in endorsement". */
    public static final String INITIAL_ENDORSEMENT_NONE = "none";
    /** Form key: the initial endorsement type chosen on the applicant screen. */
    public static final String INITIAL_ENDORSEMENT_TYPE = "initialEndorsementType";
    /** Form key: an optional explicit eight-digit code for the initial endorsement. */
    public static final String INITIAL_ENDORSEMENT_CODE = "initialEndorsementCode";

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
            case DUAL_GATE -> useDualGate(player, pos, device, held);
            case ENTRY_STAMP, EXIT_STAMP -> useStamp(player, pos, device, type, held);
            case STAMP_HISTORY -> openHistory(player, pos, device, held);
            case LOSS_TERMINAL, RENEWAL_MACHINE, PRINT_PREVIEW -> openService(player, pos, device, type, held);
            case BLACKLIST_TERMINAL -> openBlacklist(player, pos, device);
        }
    }

    private static void openIssuer(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, DeviceType type) {
        CompoundTag payload = payload(device, type);
        // 26.3 credential registry: the applicant screen reads its nationality /
        // country selector and template list from the global registry snapshot.
        DocumentKind kind = switch (type) {
            case ID_ISSUER -> DocumentKind.ID_CARD;
            case PASSPORT_ISSUER -> DocumentKind.PASSPORT;
            case PERMIT_ISSUER -> DocumentKind.PERMIT;
            default -> null;
        };
        CompoundTag registry = RegistryService.issuerPayload(player.getServer(), type.id(), kind);
        payload.put("registryCountries", registry.getList("countries", Tag.TAG_COMPOUND));
        payload.put("registryTemplates", registry.getList("templates", Tag.TAG_COMPOUND));
        send(player, pos, type.id(), payload);
    }

    private static void openVisa(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, int slot) {
        DeviceType type = device.deviceType();
        CompoundTag payload = payload(device, type);
        payload.putBoolean("selfService", type == DeviceType.SELF_VISA);
        payload.putString("machineAuthority", visaMachineAuthority(device));
        // 26.3 registry: issuing-country selector source.
        payload.put("registryCountries", RegistryService.countriesTag(player.getServer()));
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
        // 26.3 registry: issuing-country selector source.
        payload.put("registryCountries", RegistryService.countriesTag(player.getServer()));
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
        // 26.2 issuer policy: the number lock/region/sequence, MRZ country, the
        // issue-entry state and the permanent issue codes are administrator
        // configuration. configTag() already carries them; the explicit puts make
        // the brush-GUI contract visible in one place and keep the keys stable.
        payload.putBoolean("lockNumber", device.lockNumber());
        payload.putString("numberRegion", device.numberRegion());
        payload.putString("numberSequence", device.numberSequence());
        payload.putString("mrzCountry", device.mrzCountry());
        payload.putString("issuerCountry", device.issuerCountry());
        payload.putBoolean("issueEntryState", device.issueEntryState());
        payload.put("issueCodes", device.issueCodesTag());
        // 26.3: dedicated exit codes + the allowed issuing-country whitelist (both
        // also travel in configTag(); listed here so the brush-GUI contract is
        // visible in one place). The whitelist is the old permanent-code pass.
        payload.put("exitCodes", device.exitCodesTag());
        payload.put("entryCountryCodes", device.entryCountryCodesTag());
        payload.put("exitCountryCodes", device.exitCountryCodesTag());
        // Registry snapshot for the issuer country/region selector.
        payload.put("registryCountries", RegistryService.countriesTag(player.getServer()));
        // 26.4: registry templates of this machine's kind, so the layout lock also
        // lists what the registry editor just saved.
        DocumentKind configKind = switch (type) {
            case ID_ISSUER -> DocumentKind.ID_CARD;
            case PASSPORT_ISSUER -> DocumentKind.PASSPORT;
            case PERMIT_ISSUER -> DocumentKind.PERMIT;
            default -> null;
        };
        if (configKind != null) {
            payload.put("registryTemplates", RegistryService
                    .issuerPayload(player.getServer(), type.id(), configKind)
                    .getList("templates", Tag.TAG_COMPOUND));
        }
        // 26.2 nationality lock: document issuers force the holder nationality on
        // every issue, so the brush GUI shows the toggle and its locked value.
        payload.putBoolean("lockNationality", device.lockNationality());
        payload.putString("lockedNationality", device.lockedNationality());
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
     * sessions   ListTag          BorderEntry compounds, newest entry first
     *                             (payload key kept for the 26.2 client; 26.3 shows
     *                             one row per person, not per document)
     * entries    ListTag          the same list under its 26.3 name
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
        ListTag entries = BorderService.entriesTag(player.getServer());
        payload.put("sessions", entries);
        payload.put("entries", entries.copy());
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
                // Logs an officer entry naming the operator. The console targets an
                // account; the entry it clears belongs to the person that account
                // presented, whichever document that entry is bound to.
                BorderService.clearEntryFor(player.getServer(), target, BorderService.playerName(player));
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
     * Entry gate entry point: a person who already has an active entry is refused
     * (26.3: the entry belongs to the person, so presenting a second document does
     * not open a second entry), then the document is validated against the gate
     * mode/codes and its country, and a successful check creates the entry and
     * applies the IN auto stamp.
     *
     * <p>The gate country is passed to both the validator and
     * {@link BorderService#resolveEntry} so a permanent code only opens a gate in
     * the country it was issued for.</p>
     */
    private static void useEntryGate(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, HeldDocument held) {
        if (BorderService.findEntry(player, held.data) != null) {
            denyGate(player, pos, device, held, BorderLog.IN, BorderService.REASON_ALREADY_ENTERED);
            return;
        }
        // 26.3 registry whitelist: a document issued by an allowed country/region
        // is a visa-free pass (the old permanent-code slot), so only the document
        // itself has to be valid — no credential code is required.
        boolean countryPass = countryBypass(held.data, device.entryCountryCodes());
        VerificationResult result = countryPass
                ? DocumentValidator.validate(held.data, device.deviceType(), "")
                : DocumentValidator.validateGate(held.data, device.deviceType(),
                device.gateMode(), device.gateCodes(), device.country());
        if (!result.valid()) {
            // Code/mode failures are rejected before the gate state machine starts.
            // When the gate has a visa-free country whitelist and the document's
            // issuing country is not on it, that is the reason the traveller is
            // told (and the code the officer log keeps).
            VerificationResult denial = result;
            String reason = firstReason(result);
            if (!device.entryCountryCodes().isEmpty()
                    && result.code() == VerificationResult.Code.NO_MATCHING_CODE) {
                reason = DocumentValidator.REASON_COUNTRY_NOT_VISA_FREE;
                denial = VerificationResult.fail(result.code(), reason).withDetail(result.detail());
            }
            BorderService.deny(player, pos, held.data, null, BorderLog.IN, reason);
            sendGateCheck(player, pos, device, denial);
            return;
        }
        BorderService.EntryMatch match = countryPass
                ? new BorderService.EntryMatch(BorderEntry.KIND_VISA_FREE, "",
                held.data.issuerCountry())
                : BorderService.resolveEntry(held.data, device.gateMode(),
                device.gateCodes(), device.country());
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
     * Read-only outcome of the shared exit stage (single exit gate and the dual
     * gate's exit stage). {@code allowed} means the traveller may leave; the
     * decision never mutates the document, the entry or the world, so both gate
     * kinds run exactly the same rules before anything is committed.
     *
     * @param reason      denial reason translation key ("" when allowed)
     * @param check       verification result for the gate UI
     * @param recordIndex index of the entry-bound credential record, or -1
     * @param kind        credential kind of the entry (visa/endorsement/...)
     * @param staleCredential the entry's credential exists but its derived state is
     *                        EXPIRED/EXHAUSTED/REVOKED. 26.3 round 12: the exit is
     *                        still allowed (a traveller must never be trapped) and
     *                        is flagged as an overstay instead.
     */
    public record ExitStage(boolean allowed, String reason, VerificationResult check, int recordIndex,
                            String kind, boolean staleCredential) {
        static ExitStage allow(int recordIndex, String kind, VerificationResult check) {
            return new ExitStage(true, "", check, recordIndex, kind, false);
        }

        static ExitStage allow(int recordIndex, String kind, VerificationResult check, boolean staleCredential) {
            return new ExitStage(true, "", check, recordIndex, kind, staleCredential);
        }

        static ExitStage deny(String reason, VerificationResult check) {
            return new ExitStage(false, reason, check, -1, BorderEntry.KIND_NONE, false);
        }

        /** The departure must be logged as an OVERSTAY. */
        public boolean overstay() {
            return allowed && staleCredential;
        }
    }

    /**
     * Exit gate. Restores the "exit code" mechanism: the traveller needs an
     * active entry, the presented document must belong to the same person and be
     * valid, and the document must carry one of the gate's exit codes (unless the
     * gate has no exit codes, or the entry is a visa-free/permanent-code entry
     * that entered before exit codes were configured). The code match is
     * read-only; on success exactly the record that entry bound is consumed once,
     * then the entry is finished and the OUT stamp is written.
     *
     * <p>26.3: the entry identifies the <em>person</em>, so a document of the same
     * holder with a different number (a renewed passport) is accepted via
     * {@link BorderService#sameTraveller}. The bound credential record only exists
     * on the document the entry was recorded on, so it is required (and consumed)
     * only for that document; the exit log always names the document actually used.</p>
     */
    private static void useExitGate(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, HeldDocument held) {
        BorderEntry entry = BorderService.findEntry(player, held.data);
        ExitStage stage = exitStage(held.data, entry, device.exitCodes(), device.exitCountryCodes());
        if (!stage.allowed()) {
            denyExit(player, pos, device, held, entry, stage);
            return;
        }
        if (!device.startVerification(player, stage.check())) {
            message(player, "message.veriborder.gate.busy");
            return;
        }
        if (stage.recordIndex() >= 0) {
            consumeBoundRecord(player, held, entry.kind(), stage.recordIndex());
        }
        // The entry is cleared on exit, so a renewed document needs no update;
        // the log names the document that actually left. A document that expired
        // during the stay — or a credential that expired / ran out — is still
        // allowed out, flagged as an overstay.
        boolean overstay = BorderService.finishExit(player, entry, pos, held.data.number(),
                held.data.isExpired() || stage.overstay());
        autoGateStamp(player, device, held, StampRecord.OUT);
        if (overstay) {
            // The exit is still allowed; the warning plus the OVERSTAY log record it.
            player.displayClientMessage(Component.translatable(BorderService.MESSAGE_OVERSTAY), false);
        }
    }

    /**
     * Pure exit-stage decision shared by the single exit gate and the dual gate.
     * <strong>Read-only:</strong> it never consumes a record, clears an entry or
     * writes anything; the caller commits only after {@code allowed}.
     *
     * <p>Rules, in order:</p>
     * <ol>
     *   <li>an active entry exists (else {@code no_entry_record});</li>
     *   <li>the presented document belongs to the same traveller;</li>
     *   <li>the document itself is valid — a merely <em>expired</em> document is
     *       allowed to leave (the exit is flagged as an overstay);</li>
     *   <li>the document's issuing country is on the exit allowed-country
     *       whitelist → pass directly (visa-free pass); otherwise, when the exit
     *       code list is non-empty, the document must carry one of those codes.
     *       An empty exit-code list means "no restriction";</li>
     *   <li>the entry document still carries the record the entry bound.</li>
     * </ol>
     *
     * @param exitCodes           the gate's dedicated exit-code list (empty = no restriction)
     * @param allowedCountryCodes the gate's allowed issuing-country whitelist for the exit stage
     */
    public static ExitStage exitStage(@Nullable DocumentData doc, @Nullable BorderEntry entry,
                                      Collection<String> exitCodes, Collection<String> allowedCountryCodes) {
        if (doc == null || entry == null) {
            return ExitStage.deny(BorderService.REASON_NO_ENTRY_RECORD,
                    VerificationResult.fail(VerificationResult.Code.NOT_APPLICABLE,
                            BorderService.REASON_NO_ENTRY_RECORD));
        }
        if (!BorderService.sameTraveller(entry, doc)) {
            return ExitStage.deny(BorderService.REASON_SESSION_DOCUMENT_MISMATCH,
                    VerificationResult.fail(VerificationResult.Code.NOT_APPLICABLE,
                            BorderService.REASON_SESSION_DOCUMENT_MISMATCH));
        }
        // Exit is tolerant of an expired document: a traveller whose credential
        // ran out while inside must still be able to leave (flagged as overstay).
        VerificationResult base = DocumentValidator.validateExit(doc);
        if (!base.valid()) {
            return ExitStage.deny(firstReason(base), base);
        }
        List<String> codes = CredentialCode.normalizeAll(exitCodes);
        // 26.3: an empty exit-code list means "no restriction"; the country
        // whitelist is checked first and passes the traveller directly.
        if (!codes.isEmpty()
                && !countryBypass(doc, allowedCountryCodes)
                && !carriesAnyCode(doc, codes)) {
            return ExitStage.deny("verify.veriborder.reason.no_matching_code",
                    VerificationResult.fail(VerificationResult.Code.NO_MATCHING_CODE,
                            "verify.veriborder.reason.no_matching_code"));
        }
        int recordIndex = boundRecordIndex(doc, entry, true);
        // 26.3 round 12 exit tolerance: a credential that ran out or expired while
        // the traveller was inside must not trap them. A present-but-unusable
        // record still lets the traveller leave and the departure is logged as an
        // OVERSTAY instead (same rule the expired document already follows).
        boolean staleCredential = recordIndex < 0 && boundRecordIndex(doc, entry, false) >= 0;
        if (BorderService.isEntryDocument(entry, doc) && entry.consumesRecord() && recordIndex < 0
                && !staleCredential) {
            return ExitStage.deny(BorderService.REASON_SESSION_RECORD_MISSING,
                    VerificationResult.fail(VerificationResult.Code.NOT_APPLICABLE,
                            BorderService.REASON_SESSION_RECORD_MISSING));
        }
        VerificationResult ok = VerificationResult.ok(entry.code());
        return ExitStage.allow(recordIndex, entry.kind(), ok, staleCredential);
    }

    /** Convenience overload without an exit allowed-country whitelist. */
    public static ExitStage exitStage(@Nullable DocumentData doc, @Nullable BorderEntry entry,
                                      Collection<String> exitCodes) {
        return exitStage(doc, entry, exitCodes, List.of());
    }

    /**
     * True when the document's issuing country ({@link DocumentData#issuerCountry()})
     * is on the gate's allowed-country whitelist. A hit is a visa-free pass: it
     * satisfies entry checks without a credential code and exempts the exit-code
     * match, exactly like the old permanent-code slot did.
     */
    public static boolean countryBypass(@Nullable DocumentData doc, @Nullable Collection<String> allowedCountryCodes) {
        if (doc == null || allowedCountryCodes == null || allowedCountryCodes.isEmpty()) {
            return false;
        }
        // mrzCountry() is the same single field for new documents and falls back
        // to the legacy mrzCountry / layout code on pre-registry documents, so an
        // older passport still matches its own issuing country.
        String country = MrzUtil.countryCode(doc.mrzCountry());
        if (country.isEmpty()) {
            return false;
        }
        for (String allowed : allowedCountryCodes) {
            if (country.equals(MrzUtil.countryCode(allowed))) {
                return true;
            }
        }
        return false;
    }

    /** True when the document carries any of {@code codes} (read-only). */
    public static boolean carriesAnyCode(DocumentData doc, Collection<String> codes) {
        if (doc == null || codes == null || codes.isEmpty()) {
            return false;
        }
        List<String> wanted = CredentialCode.normalizeAll(codes);
        if (wanted.isEmpty()) {
            return false;
        }
        return wanted.stream().anyMatch(doc.allCredentialCodes()::contains);
    }

    /** Logs + sends the denial payload for a failed shared exit stage. */
    private static void denyExit(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, HeldDocument held,
                                 @Nullable BorderEntry entry, ExitStage stage) {
        BorderService.deny(player, pos, held.data, entry, BorderLog.OUT, stage.reason());
        sendGateCheck(player, pos, device, stage.check());
    }


    /**
     * Dual-clearance gate (一地两检): one right-click runs two stages.
     *
     * <ol>
     *   <li><b>Exit stage</b>: exactly the shared {@link #exitStage} decision the
     *       single exit gate uses — an active {@link BorderEntry}, the same
     *       traveller, a valid document, a matching exit code (brush
     *       {@code exitCodes}, falling back to the gate's generic codes) and the
     *       entry's bound record still present. The record check only applies when
     *       the presented document <em>is</em> the entry document.</li>
     *   <li><b>Entry stage</b>: the document is checked again as a normal gate
     *       document, then {@link BorderService#resolveEntry} is called with the
     *       brush-configured entry mode/codes and the gate country (a permanent
     *       code only matches a gate in its own country).</li>
     * </ol>
     *
     * <p>Both stages are validated <em>before</em> anything is written: a failure
     * at either stage denies the traveller and leaves the entry untouched
     * (exit failure = no entry, entry failure = the original entry is kept).
     * Only when both stages pass are the OUT stamp (exit identity), the entry
     * exit, the new entry and the IN stamp (entry identity) committed, so a
     * half-cleared traveller can never be produced.</p>
     *
     * <p>Audio and animation are the stock gate ones: {@code startVerification}
     * clicks the read head and the shared gate state machine opens the doors /
     * buzzes on failure. No particles are spawned.</p>
     */
    private static void useDualGate(ServerPlayer player, BlockPos pos, DeviceBlockEntity device,
                                    @Nullable HeldDocument held) {
        if (held == null) {
            openGateStatus(player, pos, device, DeviceType.DUAL_GATE);
            return;
        }
        CompoundTag config = device.dualGateConfig();
        // Unset identity fields fall back to the gate's own port/country/officer,
        // so a freshly placed dual gate works before it is brushed.
        String exitAuthority = identity(config.getString(DualGateBrushConfig.EXIT_AUTHORITY), device.country());
        String exitOfficer = identity(config.getString(DualGateBrushConfig.EXIT_OFFICER), device.officer());
        String exitPort = identity(config.getString(DualGateBrushConfig.EXIT_PORT), device.port());
        String entryAuthority = identity(config.getString(DualGateBrushConfig.ENTRY_AUTHORITY), device.country());
        String entryOfficer = identity(config.getString(DualGateBrushConfig.ENTRY_OFFICER), device.officer());
        String entryPort = identity(config.getString(DualGateBrushConfig.ENTRY_PORT), device.port());
        String entryMode = DualGateBrushConfig.normalizeMode(config.getString(DualGateBrushConfig.ENTRY_MODE));
        // The two stages own separate code lists. An empty stage list falls back
        // to the gate's generic list, so a device-brush-provisioned gate still
        // matches records exactly like before.
        List<String> deviceCodes = device.gateCodes();
        List<String> entryCodes = dualGateEntryCodes(config, deviceCodes);

        // ---- stage 1: exit preconditions (read-only) --------------------
        // The exit stage is the same shared decision the single exit gate uses:
        // active entry + same traveller + valid document + exit-code match + bound
        // record still present. It never consumes anything.
        BorderEntry entry = BorderService.findEntry(player, held.data);
        ExitStage exit = exitStage(held.data, entry, DualGateBrushConfig.exitCodes(config),
                DualGateBrushConfig.exitCountryCodes(config));
        if (!exit.allowed()) {
            denyExit(player, pos, device, held, entry, exit);
            return;
        }
        int recordIndex = exit.recordIndex();

        // ---- stage 2: entry preconditions (read-only) -------------------
        // Base document validity first (kind/status/dates/integrity); the mode
        // match itself is resolved by BorderService so the permanent-code slot
        // works for visa / endorsement / both as well.
        VerificationResult entryCheck = DocumentValidator.validate(held.data, DeviceType.DUAL_GATE, "");
        if (!entryCheck.valid()) {
            BorderService.deny(player, pos, held.data, entry, BorderLog.IN, firstReason(entryCheck));
            sendGateCheck(player, pos, device, entryCheck);
            return;
        }
        BorderService.EntryMatch match = countryBypass(held.data,
                DualGateBrushConfig.countryCodes(config))
                ? new BorderService.EntryMatch(BorderEntry.KIND_VISA_FREE, "",
                held.data.issuerCountry())
                : BorderService.resolveEntry(held.data, entryMode, entryCodes, device.country());
        if (match == null) {
            // Same two-track reason rule as the single gate: with a visa-free
            // country whitelist configured, "country not visa-free" is the real
            // reason; otherwise it is a credential-code mismatch.
            String reason = DualGateBrushConfig.countryCodes(config).isEmpty()
                    ? DocumentValidator.REASON_GATE_CODE_MISMATCH
                    : DocumentValidator.REASON_COUNTRY_NOT_VISA_FREE;
            BorderService.deny(player, pos, held.data, entry, BorderLog.IN, reason);
            sendGateCheck(player, pos, device, VerificationResult.fail(
                    VerificationResult.Code.NO_MATCHING_CODE, reason));
            return;
        }

        // ---- both stages passed: commit --------------------------------
        if (!device.startVerification(player, VerificationResult.ok(match.code()))) {
            message(player, "message.veriborder.gate.busy");
            return;
        }
        if (recordIndex >= 0) {
            consumeBoundRecord(player, held, entry.kind(), recordIndex);
        }
        boolean overstay = BorderService.finishExit(player, entry, pos, held.data.number(),
                held.data.isExpired() || exit.overstay());
        dualGateStamp(player, held, StampRecord.OUT, exitPort, exitAuthority, exitOfficer);
        BorderService.beginEntry(player, pos, entryPort, device.overstayDays(), held.data, match);
        dualGateStamp(player, held, StampRecord.IN, entryPort, entryAuthority, entryOfficer);
        if (overstay) {
            // The exit is still allowed; the warning plus the OVERSTAY log record it.
            player.displayClientMessage(Component.translatable(BorderService.MESSAGE_OVERSTAY), false);
        }
        message(player, "message.veriborder.gate.verifying");
    }

    /**
     * Entry-stage code list for a dual gate: the brush's own {@code entryCodes}
     * when it has any, otherwise the gate's generic code list. (The exit stage
     * has no such fallback in 26.3: an empty exit list means "no restriction".)
     */
    public static List<String> dualGateEntryCodes(CompoundTag config, List<String> deviceCodes) {
        List<String> codes = DualGateBrushConfig.codes(config);
        if (!codes.isEmpty()) {
            return codes;
        }
        return deviceCodes == null ? List.of() : deviceCodes;
    }

    /** Writes one dual-gate stamp with the stage identity (passport/permit only). */
    private static void dualGateStamp(ServerPlayer player, HeldDocument held, String direction,
                                      String port, String authority, String officer) {
        if (!DocumentValidator.isStampable(held.data.kind())) {
            return;
        }
        held.data.addStamp(direction, port, authority, officer);
        DocumentItem.writeDocument(held.stack, held.data);
        sync(player, held.slot, held.stack);
    }

    /** Configured identity value, or the gate's own value when unset. */
    private static String identity(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void openGateStatus(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, DeviceType type) {
        CompoundTag payload = payload(device, type);
        payload.putString("gateState", device.gateState().name());
        payload.putString("gateMessage", device.gateMessage());
        send(player, pos, type.id(), payload);
    }

    /** Index of the entry-bound record in the presented document, or -1. */
    private static int boundRecordIndex(DocumentData doc, BorderEntry entry) {
        return boundRecordIndex(doc, entry, true);
    }

    /**
     * Index of the entry-bound record.
     *
     * @param requireUsable when true only a currently usable (derived
     *                      {@code VALID}) record counts; false also finds an
     *                      expired / used-up / revoked record, which the exit
     *                      stage tolerates and flags as an overstay instead of
     *                      trapping the traveller
     */
    private static int boundRecordIndex(DocumentData doc, BorderEntry entry, boolean requireUsable) {
        if (BorderEntry.KIND_VISA.equals(entry.kind())) {
            List<CompoundTag> tags = doc.visaTags();
            for (int i = 0; i < tags.size(); i++) {
                VisaRecord record = VisaRecord.fromTag(tags.get(i));
                if ((!requireUsable || record.active()) && sameRecord(entry, record.id(), record.code())) {
                    return i;
                }
            }
            return -1;
        }
        if (BorderEntry.KIND_ENDORSEMENT.equals(entry.kind())) {
            List<CompoundTag> tags = doc.endorsementTags();
            for (int i = 0; i < tags.size(); i++) {
                EndorsementRecord record = EndorsementRecord.fromTag(tags.get(i));
                if ((!requireUsable || record.active()) && sameRecord(entry, record.id(), record.code())) {
                    return i;
                }
            }
            return -1;
        }
        return -1;
    }

    /**
     * Identity of the bound record: the stored record id wins; the eight-digit
     * code is only used for entries without an id (permanent code or legacy).
     */
    private static boolean sameRecord(BorderEntry entry, String id, String code) {
        if (!entry.recordId().isBlank()) {
            return entry.recordId().equals(id);
        }
        return !entry.code().isBlank() && entry.code().equals(code);
    }

    /**
     * Increments {@code usedEntries} on exactly one record and re-signs the
     * document. Pure data-level seam shared by the gates and their tests: no
     * player, world or item stack involved.
     *
     * @return {@code true} when a record was consumed
     */
    public static boolean consumeBoundRecord(DocumentData doc, String kind, int index) {
        if (doc == null) {
            return false;
        }
        if (BorderEntry.KIND_VISA.equals(kind)) {
            List<CompoundTag> tags = doc.visaTags();
            if (index < 0 || index >= tags.size()) {
                return false;
            }
            CompoundTag tag = tags.get(index);
            // Persist the effective code (legacy records derive it from their id)
            // so consuming an entry never changes the code the gate was set to.
            tag.putString("code", VisaRecord.fromTag(tag).code());
            tag.putInt("usedEntries", tag.getInt("usedEntries") + 1);
            doc.updateVisa(index, tag);
            return true;
        }
        if (BorderEntry.KIND_ENDORSEMENT.equals(kind)) {
            List<CompoundTag> tags = doc.endorsementTags();
            if (index < 0 || index >= tags.size()) {
                return false;
            }
            CompoundTag tag = tags.get(index);
            tag.putString("code", EndorsementRecord.fromTag(tag).code());
            tag.putInt("usedEntries", tag.getInt("usedEntries") + 1);
            doc.updateEndorsement(index, tag);
            return true;
        }
        return false;
    }

    /** Consumes the entry-bound record and persists the item stack. */
    private static void consumeBoundRecord(ServerPlayer player, HeldDocument held, String kind, int index) {
        if (!consumeBoundRecord(held.data, kind, index)) {
            return;
        }
        DocumentItem.writeDocument(held.stack, held.data);
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
        // Two-track reason (26.3 round 12): the payload keeps the administrator
        // reason codes, the traveller only ever sees a plain-language sentence.
        player.displayClientMessage(Component.translatable(
                TravellerReasons.of(result, device.gateMode())), false);
    }

    /**
     * Session-level denial (already entered, no entry record, person/document
     * mismatch, record missing). Logs the attempt with its reason code and shows
     * the traveller-facing sentence; the gate stays idle and is not animated.
     */
    private static void denyGate(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, HeldDocument held,
                                 String direction, String reasonKey) {
        BorderService.deny(player, pos, held.data, BorderService.findEntry(player, held.data), direction,
                reasonKey);
        sendGateDenied(player, pos, device, reasonKey);
    }

    /** Plain gate denial payload: idle state, failure check, translated chat reason. */
    private static void sendGateDenied(ServerPlayer player, BlockPos pos, DeviceBlockEntity device,
                                       String reasonKey) {
        VerificationResult check = VerificationResult.fail(VerificationResult.Code.NOT_APPLICABLE, reasonKey);
        CompoundTag payload = payload(device, device.deviceType());
        payload.putString("gateState", device.gateState().name());
        payload.putString("gateMessage", "gate.veriborder.denied");
        payload.put("check", check.save());
        send(player, pos, device.deviceType().id(), payload);
        player.displayClientMessage(Component.translatable(
                TravellerReasons.key(reasonKey, device.gateMode())), false);
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
        // Credential-registry actions are world-level (no device) and OP-gated, so
        // they are routed before the block-entity lookup.
        if (RegistryService.handleAction(player, action, data)) {
            return;
        }
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
            case "save_dual_gate" -> saveDualGateConfig(player, device, data);
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
                    // The key is optional: an exit-gate screen keeps the stored
                    // fallback list instead of clearing it.
                    if (data.contains("gateCodes")) {
                        device.setGateCodes(data.getList("gateCodes", Tag.TAG_STRING));
                    }
                    // 26.3 exit gate restore: the dedicated exit-code list and the
                    // permanent case-by-case exemption list are optional payload
                    // keys, so an older/other screen never resets them.
                    if (data.contains("exitCodes")) {
                        device.setExitCodes(data.getList("exitCodes", Tag.TAG_STRING));
                    }
                    if (data.contains("entryCountryCodes")) {
                        device.setEntryCountryCodes(data.getList("entryCountryCodes", Tag.TAG_STRING));
                    }
                    if (data.contains("exitCountryCodes")) {
                        device.setExitCountryCodes(data.getList("exitCountryCodes", Tag.TAG_STRING));
                    }
                    if (data.contains("issuerCountry")) {
                        device.setIssuerCountry(data.getString("issuerCountry"));
                    }
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
                    // 26.3 registry: the machine's issuing-country code (shared by
                    // every issuer type, not just document issuers).
                    if (data.contains("issuerCountry")) {
                        device.setIssuerCountry(data.getString("issuerCountry"));
                    }
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
                // 26.4: the client resolved a template-backed layout lock before
                // sending it; when the template had already been deleted it fell
                // back to the first official layout of the machine's kind. Say so
                // instead of silently saving a different layout.
                if (data.getBoolean("lockedStyleFallback")) {
                    message(player, "message.veriborder.config.locked_style_fallback");
                }
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
     * Device-mode save of the dual-clearance editor: the exact same schema the
     * dual-gate brush writes, applied to a placed dual gate. OP level 2 and the
     * device brush are re-checked server-side; the payload is sanitised by
     * {@link DualGateBrushConfig} before it reaches the block entity.
     */
    private static void saveDualGateConfig(ServerPlayer player, DeviceBlockEntity device, CompoundTag data) {
        if (device.deviceType() != DeviceType.DUAL_GATE) {
            message(player, "message.veriborder.dual_gate_brush.not_gate");
            return;
        }
        if (!player.hasPermissions(2)) {
            message(player, "message.veriborder.permission.denied");
            return;
        }
        if (!holdsBrush(player)) {
            message(player, "message.veriborder.brush.required");
            return;
        }
        device.setDualGateConfig(DualGateBrushConfig.sanitize(data));
        device.changed();
        device.sync();
        player.level().playSound(null, device.getBlockPos(), SoundEvents.VILLAGER_WORK_CARTOGRAPHER,
                SoundSource.BLOCKS, 0.7F, 1.2F);
        message(player, "message.veriborder.dual_gate_brush.saved");
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
            // 26.4: setLockedStyle validates the id (DocStyle.byIdOrNull) and
            // stores "" for an unusable value, so a stale/template value can
            // never reject the whole configuration save.
            device.setLockedStyle(data.getString("lockedStyle"));
            if (data.contains("lockedStyleTemplate")) {
                device.setLockedStyleTemplate(data.getString("lockedStyleTemplate"));
            }
            // 26.2 issuer policy, now administrator configuration rather than an
            // applicant toggle. Keys are optional so an older/other screen cannot
            // silently reset a value it does not display.
            if (data.contains("lockNumber")) {
                device.setLockNumber(data.getBoolean("lockNumber"));
            }
            if (data.contains("numberRegion")) {
                device.setNumberRegion(data.getString("numberRegion"));
            }
            if (data.contains("numberSequence")) {
                device.setNumberSequence(data.getString("numberSequence"));
            }
            if (data.contains("mrzCountry")) {
                device.setMrzCountry(data.getString("mrzCountry"));
            }
            if (data.contains("issueEntryState")) {
                device.setIssueEntryState(data.getBoolean("issueEntryState"));
            }
            if (data.contains("issueCodes")) {
                device.setIssueCodes(data.getList("issueCodes", Tag.TAG_STRING));
            }
            // 26.2 nationality lock. Only document issuers expose it, and the keys
            // are optional so an older/other screen cannot reset the stored value.
            if (data.contains("lockNationality")) {
                device.setLockNationality(data.getBoolean("lockNationality"));
            }
            if (data.contains("lockedNationality")) {
                device.setLockedNationality(data.getString("lockedNationality"));
            }
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
            // 26.2 month precision: the month key is authoritative, a legacy year
            // payload (older client / older NBT) is migrated x12. An omitted key
            // leaves the stored value untouched, like every other optional lock.
            if (data.contains("lockedValidityMonths") || data.contains("lockedValidityYears")) {
                device.setLockedValidityMonths(DeviceBlockEntity.validityMonths(data));
            }
        }
        if (endorsementIssuer) {
            device.setLockEndorsementType(data.getBoolean("lockEndorsementType"));
            device.setLockedEndorsementType(data.getString("lockedEndorsementType"));
        } else if (type == DeviceType.PERMIT_ISSUER && data.contains("lockEndorsementType")) {
            // 26.2: the permit issuer reuses the same fields for the type of the
            // built-in endorsement it attaches to every new permit. The keys are
            // optional so an older screen cannot silently reset the stored lock.
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
            if (data.contains("lockedVisaValidityMonths")
                    || data.contains("lockedVisaValidityYears")) {
                device.setLockedVisaValidityMonths(DeviceBlockEntity.visaValidityMonths(data));
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
        boolean lockedLayout = false;
        if (device.lockStyle() && lockedStyle != null && lockedStyle.kind() == expected) {
            // A valid lock replaces the submitted layout; a malicious client
            // cannot switch an ID issuer to a passport layout.
            style = lockedStyle;
            lockedLayout = true;
        } else if (style.kind() != expected) {
            message(player, "message.veriborder.issuer.wrong_style");
            return;
        }
        // Locked issuer fields are enforced server-side before the factory reads
        // the form, so a client that ignores the lock still cannot issue a
        // different document name, authority, place or date.
        CompoundTag form = data.copy();
        // 26.4 fix: the document-name lock is resolved entirely from the device.
        // The trusted "lockedDisplayName" key carries the administrator's title
        // and the applicant form can neither supply nor unlock it.
        form = applyLockedDocumentName(device.locks(), form);
        if (lockedLayout) {
            // 26.4: a layout lock backed by a registry template also carries the
            // template's colour/icon. A template deleted since configuration
            // contributes nothing — the layout lock keeps working on its own.
            applyLockedStyleTemplate(form, RegistryService.templateById(
                    player.getServer(), device.lockedStyleTemplate()), style);
        }
        if (device.lockAuthority() && !device.lockedAuthority().isBlank()) {
            form.putString("authority", device.lockedAuthority());
        }
        // 26.2 nationality lock: the device, never the applicant form, decides the
        // holder nationality. A forged form field is overwritten before the factory
        // reads it, and a blank locked value is a no-op (same rule as the GUI).
        if (device.lockNationality() && !device.lockedNationality().isBlank()) {
            form.putString("nationality", device.lockedNationality());
        }
        if (device.lockPlace() && !device.lockedPlace().isBlank()) {
            form.putString("placeOfIssue", device.lockedPlace());
        }
        // 26.2 issue-date lock: authoritative even when the admin left the value
        // blank ("blank = issue day"). The applicant form date is overwritten
        // before the factory reads it; the locked validity / permit endorsement
        // below then recompute from this forced date.
        form = applyIssueDateLock(device.locks(), form);
        // 26.2 issuer policy is resolved from the device, never from the applicant
        // form: a client cannot unlock its own number, pick a region/sequence or
        // inject permanent codes. The applicant screen no longer offers them.
        form.putBoolean("lockNumber", device.lockNumber());
        form.putString("numberRegion", device.numberRegion());
        form.putString("numberSequence", device.numberSequence());
        if (device.lockNumber()) {
            form.putString("number", "");
        }
        form.remove("visaFreeCodes");
        // 26.3 round 12: one issuing-country field for every document kind, decided
        // by the machine's single country selector. The form and finally the layout
        // code are fallbacks only. The old port/officer/airport country text fields
        // and the legacy MRZ-country policy no longer take part in issuing (their
        // NBT is kept on the block entity but ignored here).
        String issuerCountry = device.issuerCountry();
        if (issuerCountry.isBlank()) {
            issuerCountry = MrzUtil.countryCode(form.getString("issuerCountry"));
        }
        if (issuerCountry.isBlank()) {
            issuerCountry = RegistryService.resolveIssuerCountryCode(player.getServer(), "", form);
        }
        if (issuerCountry.isBlank()) {
            issuerCountry = MrzUtil.countryCode(form.getString("mrzCountry"));
        }
        if (issuerCountry.isBlank()) {
            issuerCountry = style.mrzCountry();
        }
        form.putString("issuerCountry", issuerCountry);
        form.remove("mrzCountry");
        // 26.3 round 12 (issue machine nationality binding): the machine's single
        // country selector decides BOTH values, so the holder nationality is
        // derived from the same registry country as the MRZ issuing code. The
        // applicant cannot pick a nationality that disagrees with the machine, and
        // the superseded "nationality lock" NBT is no longer applied.
        String machineNationality = RegistryService.nationalityFor(player.getServer(), issuerCountry);
        if (!machineNationality.isBlank()) {
            form.putString("nationality", machineNationality);
        }
        form.putString("style", style.id());
        DocumentData doc = DocumentFactory.fromForm(style, form);
        // Remember the issuer's title policy on the signed document so the border
        // terminal can grey out the custom display name instead of silently
        // violating lockDocumentName.
        doc.setString("nameLocked", device.lockDocumentName() ? "true" : "false");
        // 26.3 permanent-code slot: passports and permits only. A domestic
        // identity card has no "return to the issuing country" credential, so the
        // configured codes are not copied onto it (the device keeps them for its
        // passport/permit issues). Each code is scoped to the device's country:
        // "return home" only opens a gate in that country, which is exactly what
        // an immigration code means.
        if (expected != DocumentKind.ID_CARD) {
            for (String code : device.issueCodes()) {
                doc.addPermanentCode(code, device.country(),
                        DocumentData.PermanentPurpose.RETURN_HOME, "");
            }
        }
        if (device.lockValidity()) {
            // 0/permanent styles stay permanent; positive months win over the
            // style default and over any form-supplied expiry date.
            doc.setExpiryDate(DocumentFactory.lockedExpiry(doc.issueDate(), device.lockedValidityMonths()));
        }
        if (expected == DocumentKind.PERMIT) {
            // 26.2: every permit carries a built-in endorsement ("self-carried
            // endorsement"). The device decides the type; see
            // buildInitialEndorsement for the complete lock/default rules.
            String fixedCode = device.lockEndorsementCode()
                    && CredentialCode.isValid(device.lockedEndorsementCode())
                    ? device.lockedEndorsementCode() : "";
            CompoundTag initial = buildInitialEndorsement(device.locks(), doc, form, fixedCode);
            if (initial != null) {
                if (!fixedCode.isBlank()) {
                    // The issuer's locked initial-endorsement code is used verbatim,
                    // even if the permit already carries a record with the same code.
                    doc.addEndorsementExact(initial);
                } else {
                    doc.addEndorsement(initial);
                }
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
        // The entry must bind the document that was ACTUALLY written to the item
        // (its number is final by now), not the pre-write form.
        DocumentData issuedDoc = DocumentItem.getDocument(stack);
        if (issuedDoc == null) {
            issuedDoc = doc;
        }
        String issuedNumber = issuedDoc.number();
        // 26.4 display fix: the "issued" chat line names the DOCUMENT as it was
        // actually written — the issuer-locked / custom name when there is one —
        // and only falls back to the official layout name, instead of always
        // reporting the layout ("已签发 中国香港特别行政区护照" for a doc titled TEST).
        String officialName = Component.translatable(style.displayNameKey()).getString();
        String issuedTitle = DocumentTitles.issuedLabel(issuedDoc.displayName(), officialName);
        message(player, "message.veriborder.issuer.issued", Component.literal(issuedTitle), issuedNumber);
        // 26.2/26.3: a freshly issued PASSPORT/PERMIT may start the holder in the
        // entry state so an exit gate is immediately usable. An ID card is a
        // domestic document: it never creates an entry record. A stale entry of the
        // same person is replaced (not skipped), so the exit gate matches the
        // document just issued.
        BorderEntry previous = BorderService.entry(player);
        if (BorderService.shouldAdoptIssuedEntry(expected, device.issueEntryState(), previous, issuedNumber)) {
            if (previous == null) {
                BorderService.beginEntry(player, device.getBlockPos(), device.port(), device.overstayDays(),
                        issuedDoc, BorderService.EntryMatch.none());
            } else {
                BorderService.replaceEntry(player, device.getBlockPos(), device.port(), device.overstayDays(),
                        issuedDoc, BorderService.EntryMatch.none());
            }
        }
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
                label, issue, settings.lockVisaValidity(), settings.lockedVisaValidityMonths());
    }

    /**
     * Builds the endorsement a permit issuer attaches to every newly issued permit
     * ("self-carried endorsement"). The device is authoritative:
     *
     * <ul>
     *   <li>a locked initial-endorsement type always wins - even over a forged
     *       {@code initialEndorsementType} and over an explicit {@code none};
     *       an unusable locked value falls back to the built-in default;</li>
     *   <li>unlocked: the applicant's valid choice is honoured; an explicit
     *       {@code none} issues the permit without one; a blank/unknown/absent
     *       value falls back to {@link #DEFAULT_INITIAL_ENDORSEMENT};</li>
     *   <li>the record starts on the <em>issued document's</em> issue date (which
     *       already carries the terminal's issue-date lock) and expires after the
     *       type's own validity in months - a three-month endorsement stays three
     *       months even when the document validity is locked to ten years;</li>
     *   <li>the entry budget is the type's default (1 for the three-month single
     *       endorsement), the authority is the permit's authority (locked
     *       authority included), and a locked code is preserved verbatim.</li>
     * </ul>
     *
     * <p>Pure (no world/block-entity access), so every rule is unit-testable.
     * Returns {@code null} for a non-permit document or an explicit unlocked
     * "none".</p>
     */
    @Nullable
    public static CompoundTag buildInitialEndorsement(DeviceBlockEntity.Locks locks, DocumentData permit,
                                                      CompoundTag form, String lockedCode) {
        if (permit == null || permit.kind() != DocumentKind.PERMIT) {
            return null;
        }
        DeviceBlockEntity.Locks settings = locks == null ? DeviceBlockEntity.Locks.NONE : locks;
        EndorsementType type;
        if (settings.lockEndorsementType()) {
            EndorsementType lockedType = EndorsementType.byIdOrNull(settings.lockedEndorsementType());
            type = lockedType == null ? DEFAULT_INITIAL_ENDORSEMENT : lockedType;
        } else {
            String submitted = form == null ? "" : form.getString(INITIAL_ENDORSEMENT_TYPE).trim();
            if (INITIAL_ENDORSEMENT_NONE.equalsIgnoreCase(submitted)) {
                return null;
            }
            EndorsementType requested = EndorsementType.byIdOrNull(submitted);
            type = requested == null ? DEFAULT_INITIAL_ENDORSEMENT : requested;
        }
        String issue = DateUtil.isValid(permit.issueDate())
                ? permit.issueDate().trim() : DateUtil.today();
        String authority = settings.lockAuthority() && !settings.lockedAuthority().isBlank()
                ? settings.lockedAuthority() : permit.authority();
        String code = "";
        if (CredentialCode.isValid(lockedCode)) {
            code = lockedCode.trim();
        } else if (form != null) {
            code = CredentialCode.sanitize(form.getString(INITIAL_ENDORSEMENT_CODE));
        }
        return DocumentFactory.createEndorsement(type, type.defaultEntries(), issue,
                DocumentFactory.endorsementExpiry(type, issue), authority, code);
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
                    ? DocumentFactory.lockedExpiry(issue, device.lockedValidityMonths())
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
            form.putString("expiryDate", DocumentFactory.lockedExpiry(issue, settings.lockedValidityMonths()));
        }
        if (settings.lockVisaName()) {
            // A locked blank value is meaningful: force the country/type fallback
            // and prevent a forged label/visaName from surviving.
            form.putString("visaName", settings.lockedVisaName());
            form.putString("label", settings.lockedVisaName());
        }
        return form;
    }

    /**
     * Applies the terminal's issue-date lock to a submitted issuer form. A locked
     * device always decides the date: the stored value when it is a real date,
     * otherwise the issue day ({@link DateUtil#today()}), so an empty locked value
     * means "issued today" instead of "no lock". The submitted form date only
     * survives when the lock is off. Pure, so the rule is unit-testable.
     */
    public static CompoundTag applyIssueDateLock(DeviceBlockEntity.Locks locks, CompoundTag values) {
        DeviceBlockEntity.Locks settings = locks == null ? DeviceBlockEntity.Locks.NONE : locks;
        CompoundTag form = values == null ? new CompoundTag() : values.copy();
        if (settings.lockIssueDate()) {
            form.putString("issueDate", lockedIssueDate(settings, DateUtil.today()));
        }
        return form;
    }

    /** The locked issue date when enabled and valid, otherwise {@code fallback}. */
    public static String lockedIssueDate(DeviceBlockEntity.Locks settings, String fallback) {
        if (settings == null || !settings.lockIssueDate()) {
            return fallback;
        }
        String value = settings.lockedIssueDate();
        return DateUtil.isValid(value) ? value.trim() : fallback;
    }

    /**
     * 26.4 document-name lock. The device always decides: the trusted
     * {@link DocumentFactory#LOCKED_DISPLAY_NAME} key carries the administrator's
     * locked title, the applicant's {@code displayName} and custom cover are
     * dropped while the lock is on, and the {@code lockDocumentName} flag itself
     * is overwritten so a forged form can neither unlock a lock nor fake one.
     *
     * <p>Every trusted key is scrubbed here (including the registry-template
     * colour/icon), so a client-supplied value never survives; the caller then
     * re-injects the device's own values via {@link #applyLockedStyleTemplate}.</p>
     *
     * <p>A blank locked title is meaningful: the factory then keeps the official
     * preset name. Pure (no block entity/world access), so every rule is
     * unit-testable.</p>
     */
    public static CompoundTag applyLockedDocumentName(DeviceBlockEntity.Locks locks, CompoundTag values) {
        DeviceBlockEntity.Locks settings = locks == null ? DeviceBlockEntity.Locks.NONE : locks;
        CompoundTag form = values == null ? new CompoundTag() : values.copy();
        form.remove(DocumentFactory.LOCKED_DISPLAY_NAME);
        form.remove(DocumentFactory.LOCKED_STYLE_COLOR);
        form.remove(DocumentFactory.LOCKED_STYLE_ICON);
        form.putBoolean("lockDocumentName", settings.lockDocumentName());
        if (settings.lockDocumentName()) {
            // The trusted key replaces the client's title; a forged custom cover
            // cannot leak a different name onto the document either.
            form.remove("displayName");
            form.remove("customStyle");
            form.putString(DocumentFactory.LOCKED_DISPLAY_NAME, settings.lockedDocumentName());
        }
        return form;
    }

    /**
     * 26.4 registry-template layout lock: copies the locked template's colour and
     * icon onto the (already trusted) issuer form so the issued document carries
     * the operator's template, not just its base layout. The template is resolved
     * again at issue time, and a {@code null} (deleted) template or one whose
     * layout no longer matches the locked style contributes nothing. Pure.
     */
    public static CompoundTag applyLockedStyleTemplate(CompoundTag values,
                                                       @Nullable CredentialRegistryData.Template template,
                                                       DocStyle style) {
        CompoundTag form = values == null ? new CompoundTag() : values;
        if (template == null || style == null) {
            return form;
        }
        if (!template.style().equals(style.id()) || template.kind() != style.kind()) {
            // The template was re-pointed at another layout: the lock still uses
            // the stored layout, so its colour/icon must not contradict it.
            return form;
        }
        form.putInt(DocumentFactory.LOCKED_STYLE_COLOR, template.color() & 0x00FFFFFF);
        String icon = template.icon() == null ? "" : template.icon().trim();
        if (!icon.isBlank()) {
            form.putString(DocumentFactory.LOCKED_STYLE_ICON, icon);
        }
        return form;
    }

    private static String lockedIssueDate(DeviceBlockEntity device, String fallback) {
        return device == null ? fallback : lockedIssueDate(device.locks(), fallback);
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
                    ? DocumentFactory.lockedExpiry(issue, device.lockedValidityMonths())
                    : DocumentFactory.endorsementExpiry(type, issue);
            entries = type.defaultEntries();
            authority = machineAuthority(device);
            code = CredentialCode.isValid(device.issuedCode()) ? device.issuedCode() : "";
        } else {
            issue = lockedIssueDate(device,
                    DateUtil.isValid(data.getString("issueDate")) ? data.getString("issueDate") : DateUtil.today());
            if (device.lockValidity()) {
                expiry = DocumentFactory.lockedExpiry(issue, device.lockedValidityMonths());
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
            case EGATE, EXIT_GATE, DUAL_GATE -> openGateStatus(player, pos, device, type);
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
        message(player, "message.veriborder.modifier.saved",
                Component.literal(DocumentTitles.issuedLabel(incoming.displayName(),
                        Component.translatable(incoming.style().displayNameKey()).getString())));
    }

    private static void message(ServerPlayer player, String key, Object... arguments) {
        player.displayClientMessage(Component.translatable(key, arguments), true);
    }
}
