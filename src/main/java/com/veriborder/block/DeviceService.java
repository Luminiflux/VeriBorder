package com.veriborder.block;

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
            case HOTEL_READER, DESK_SCANNER, MANUAL_TERMINAL, AIRPORT_CHECKIN, STATION_VERIFIER ->
                    openReader(player, pos, device, type, held, type == DeviceType.AIRPORT_CHECKIN
                            ? device.airportCountry() : "");
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
        if (held != null && held.data.kind() == DocumentKind.PASSPORT) {
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

    public static void openModifier(ServerPlayer player) {
        ListTag docs = scanDocuments(player);
        CompoundTag payload = new CompoundTag();
        payload.put("docs", docs);
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new OpenDevicePacket(BlockPos.ZERO, "modifier", payload));
    }

    public static void generateFromModifier(ServerPlayer player, CompoundTag data) {
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
        if (held == null || held.data.kind() != DocumentKind.PASSPORT) {
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
        VerificationResult result = DocumentValidator.validateGate(held.data, type, device.gateMode(),
                device.gateCodes());
        if (!result.valid()) {
            // Code/mode failures are rejected before the gate state machine starts,
            // and the chat message names the required mode or the mismatched code.
            CompoundTag payload = payload(device, type);
            payload.putString("gateState", device.gateState().name());
            payload.putString("gateMessage", "gate.veriborder.denied");
            payload.put("check", result.save());
            send(player, pos, type.id(), payload);
            player.displayClientMessage(result.message(), false);
            return;
        }
        if (device.startVerification(player, result)) {
            // Exit gates never consume a visa/endorsement: leaving the country
            // does not use up an entry permission. Entry gates consume the one
            // active record whose code matched the gate's code list; visa-free
            // matches keep the passport untouched and "all" mode never consumes.
            if (type != DeviceType.EXIT_GATE) {
                consumeGateEntry(player, held, device.gateMode(), device.gateCodes());
            }
            message(player, "message.veriborder.gate.verifying");
        } else {
            message(player, "message.veriborder.gate.busy");
        }
    }

    private static void openGateStatus(ServerPlayer player, BlockPos pos, DeviceBlockEntity device, DeviceType type) {
        CompoundTag payload = payload(device, type);
        payload.putString("gateState", device.gateState().name());
        payload.putString("gateMessage", device.gateMessage());
        send(player, pos, type.id(), payload);
    }

    private static void consumeGateEntry(ServerPlayer player, HeldDocument held, String gateMode,
                                         List<String> gateCodes) {
        String mode = DeviceBlockEntity.normalizeGateMode(gateMode);
        if (DeviceBlockEntity.GATE_MODE_ALL.equals(mode)) {
            return;
        }
        List<String> codes = CredentialCode.normalizeAll(gateCodes);
        if (codes.isEmpty()) {
            return;
        }
        DocumentData doc = held.data;
        if (doc.kind() == DocumentKind.PASSPORT) {
            List<CompoundTag> visas = doc.visaTags();
            for (int i = 0; i < visas.size(); i++) {
                CompoundTag tag = visas.get(i);
                VisaRecord record = VisaRecord.fromTag(tag);
                if (record.active() && codes.contains(record.code())) {
                    // Persist the effective code (legacy records derive it from
                    // their id) so consuming an entry never changes the code the
                    // gate was configured with.
                    tag.putString("code", record.code());
                    tag.putInt("usedEntries", tag.getInt("usedEntries") + 1);
                    doc.updateVisa(i, tag);
                    DocumentItem.writeDocument(held.stack, doc);
                    sync(player, held.slot, held.stack);
                    return;
                }
            }
            // Visa-free-only match: the passport entry is not consumed.
        } else if (doc.kind() == DocumentKind.PERMIT) {
            List<CompoundTag> endorsements = doc.endorsementTags();
            for (int i = 0; i < endorsements.size(); i++) {
                CompoundTag tag = endorsements.get(i);
                EndorsementRecord record = EndorsementRecord.fromTag(tag);
                if (record.active() && codes.contains(record.code())) {
                    tag.putString("code", record.code());
                    tag.putInt("usedEntries", tag.getInt("usedEntries") + 1);
                    doc.updateEndorsement(i, tag);
                    DocumentItem.writeDocument(held.stack, doc);
                    sync(player, held.slot, held.stack);
                    return;
                }
            }
        }
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
        if (style.kind() != expected) {
            message(player, "message.veriborder.issuer.wrong_style");
            return;
        }
        // Locked issuer fields are enforced server-side before the factory reads
        // the form, so a client that ignores the lock still cannot issue a
        // different document name or authority.
        if (device.lockDocumentName() && !device.lockedDocumentName().isBlank()) {
            data.putString("displayName", device.lockedDocumentName());
        }
        if (device.lockAuthority() && !device.lockedAuthority().isBlank()) {
            data.putString("authority", device.lockedAuthority());
        }
        DocumentData doc = DocumentFactory.fromForm(style, data);
        if (expected == DocumentKind.PERMIT && data.contains("initialEndorsementType")
                && !data.getString("initialEndorsementType").isBlank()) {
            EndorsementType endorsementType = EndorsementType.byId(data.getString("initialEndorsementType"));
            String today = DateUtil.today();
            String authority = device.lockAuthority() && !device.lockedAuthority().isBlank()
                    ? device.lockedAuthority() : style.defaultAuthority();
            boolean fixedCode = device.lockEndorsementCode()
                    && CredentialCode.isValid(device.lockedEndorsementCode());
            String code = fixedCode ? device.lockedEndorsementCode()
                    : CredentialCode.isValid(data.getString("initialEndorsementCode"))
                    ? data.getString("initialEndorsementCode") : "";
            CompoundTag initial = DocumentFactory.createEndorsement(endorsementType,
                    endorsementType.defaultEntries(), today,
                    DocumentFactory.endorsementExpiry(endorsementType, today), authority, code);
            if (fixedCode) {
                // The issuer's locked initial-endorsement code is used verbatim,
                // even if the permit already carries a record with the same code.
                doc.addEndorsementExact(initial);
            } else {
                doc.addEndorsement(initial);
            }
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
        if (selfService) {
            // The kiosk exposes only the visa type. Country, authority, dates,
            // entries and code are machine/admin parameters.
            VisaType type = VisaType.byId(data.getString("type"));
            String code = CredentialCode.isValid(device.issuedCode())
                    ? device.issuedCode() : CredentialCode.generateUnique(doc.allCredentialCodes());
            String country = device.country().isBlank() ? "中国" : device.country();
            String issue = DateUtil.today();
            String expiry = DateUtil.plusYears(issue, 1);
            CompoundTag values = new CompoundTag();
            values.putString("country", country);
            values.putString("type", type.id());
            values.putInt("entries", type.defaultEntries());
            values.putString("issueDate", issue);
            values.putString("expiryDate", expiry);
            values.putString("authority", visaMachineAuthority(device));
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
            // across the batch.
            List<String> used = new ArrayList<>(doc.allCredentialCodes());
            int count = 0;
            for (int i = 0; i < presets.size(); i++) {
                CompoundTag values = presets.getCompound(i).getCompound("values");
                String code = CredentialCode.generateUnique(used);
                used.add(code);
                doc.addVisa(toVisa(values, authority, code, authorityLocked));
                count++;
            }
            message(player, "message.veriborder.visa.batch", count);
        } else {
            String requested = data.getString("code");
            String code = CredentialCode.isValid(requested) ? requested.trim()
                    : CredentialCode.generateUnique(doc.allCredentialCodes());
            if (CredentialCode.isValid(requested) && doc.allCredentialCodes().contains(code)) {
                message(player, "message.veriborder.code.duplicate");
                return;
            }
            doc.addVisa(toVisa(data, authority, code, authorityLocked));
            message(player, "message.veriborder.visa.issued", data.getString("country"));
        }
        DocumentItem.writeDocument(stack, doc);
        sync(player, slot, stack);
        player.level().playSound(null, pos, SoundEvents.VILLAGER_WORK_CARTOGRAPHER, SoundSource.BLOCKS, 0.8F, 0.9F);
        openVisa(player, pos, device, slot);
    }

    private static CompoundTag toVisa(CompoundTag data, String fallbackAuthority, String code,
                                      boolean forceAuthority) {
        VisaType visaType = VisaType.byId(data.getString("type"));
        String issue = DateUtil.isValid(data.getString("issueDate")) ? data.getString("issueDate") : DateUtil.today();
        String expiry = DateUtil.isValid(data.getString("expiryDate"))
                ? data.getString("expiryDate") : DateUtil.plusYears(issue, 1);
        String authority = forceAuthority ? fallbackAuthority
                : data.getString("authority").isBlank() ? fallbackAuthority : data.getString("authority");
        return DocumentFactory.createVisa(data.getString("country"), visaType,
                Math.max(0, data.getInt("entries")), issue, expiry, authority, code);
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
        EndorsementType type = EndorsementType.byId(data.getString("type"));
        String issue;
        String expiry;
        int entries;
        String authority;
        String code;
        if (selfService) {
            // Self-service kiosks ignore every player-supplied field: only the
            // preset endorsement type is honoured, with type defaults + today and
            // the machine's own authority/code.
            issue = DateUtil.today();
            expiry = DocumentFactory.endorsementExpiry(type, issue);
            entries = type.defaultEntries();
            authority = machineAuthority(device);
            code = CredentialCode.isValid(device.issuedCode()) ? device.issuedCode() : "";
        } else {
            issue = DateUtil.isValid(data.getString("issueDate")) ? data.getString("issueDate") : DateUtil.today();
            expiry = DateUtil.isValid(data.getString("expiryDate"))
                    ? data.getString("expiryDate") : DocumentFactory.endorsementExpiry(type, issue);
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
