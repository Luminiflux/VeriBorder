package com.veriborder.registry;

import com.veriborder.api.OpenScreenEvent;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentKind;
import com.veriborder.doc.PortraitLibrary;
import com.veriborder.network.ModNetwork;
import com.veriborder.network.OpenDevicePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Server-side front door of the credential registry ("娉ㄥ唽鍣?).
 *
 * <p>Opening the registry sends the whole snapshot to the requesting player and
 * then asks the client to show {@code RegistryScreen}; every successful write
 * broadcasts a fresh snapshot to all players (S鈫扖 sync) and re-opens the editor
 * for the actor. Single-player works because the integrated server owns the same
 * {@link CredentialRegistryData} instance.</p>
 *
 * <p>Writes require OP level 2 and are re-validated here; the client only sends
 * plain form fields. Reads are open to everyone because the issuer screens need
 * the country list.</p>
 */
public final class RegistryService {
    private static final Logger LOGGER = LoggerFactory.getLogger("VeriBorder/Registry");

    /** Pseudo device id used by {@code OpenDevicePacket}. */
    public static final String OPEN_DEVICE = "registry";

    public static final String ACTION_REFRESH = "registry_refresh";
    /** Read-only snapshot request sent when a client's registry cache is empty. */
    public static final String ACTION_SNAPSHOT_REQUEST = "registry_snapshot_request";
    public static final String ACTION_SAVE_COUNTRY = "registry_save_country";
    public static final String ACTION_DELETE_COUNTRY = "registry_delete_country";
    /** Renames a registered three-letter code (26.3 round 12 "change code"). */
    public static final String ACTION_RECODE_COUNTRY = "registry_recode_country";
    public static final String ACTION_SAVE_TEMPLATE = "registry_save_template";
    public static final String ACTION_DELETE_TEMPLATE = "registry_delete_template";
    /** 26.4.1: uploads one custom cover base map ({@code hash} + base64 {@code png}). */
    public static final String ACTION_UPLOAD_TEXTURE = "registry_upload_texture";
    /**
     * 26.4.1 document portrait upload: {@code {png(base64), owner(uuid)}}. Sent by
     * the photo booth (an issuing machine) or an operator; the accepted reply is
     * the {@code custom:<hash>} id of the stored image.
     */
    public static final String ACTION_UPLOAD_PORTRAIT = "registry_upload_portrait";

    private RegistryService() {
    }

    /** Opens the registry editor for {@code player} (read access: everyone). */
    public static void open(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        CredentialRegistryData data = CredentialRegistryData.get(server);
        sendSnapshot(player, data);
        sendEditor(player, data);
    }

    private static void sendEditor(ServerPlayer player, CredentialRegistryData data) {
        sendEditor(player, data, null);
    }

    /**
     * @param highlight template just saved, so the reopened editor can select it
     *                  and show the server's normalised style (26.4)
     */
    private static void sendEditor(ServerPlayer player, CredentialRegistryData data,
                                   @Nullable CredentialRegistryData.Template highlight) {
        sendEditor(player, data, highlight, "", "", null);
    }

    /**
     * @param errorKey  concrete reason of a refused write, rendered by the screen
     *                  itself instead of only being sent to chat (26.4 fix)
     * @param editorTab page the editor must show ({@code countries}/{@code templates})
     * @param echo      form that was refused, so the reopened editor can restore
     *                  exactly what the operator typed (26.4 fix)
     */
    private static void sendEditor(ServerPlayer player, CredentialRegistryData data,
                                   @Nullable CredentialRegistryData.Template highlight,
                                   @Nullable String errorKey, @Nullable String editorTab,
                                   @Nullable CompoundTag echo) {
        CompoundTag payload = editorPayload(player, data, highlight, errorKey, editorTab, echo);
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new OpenDevicePacket(BlockPos.ZERO, OPEN_DEVICE, payload));
    }

    /** Payload of the editor screen: snapshot + whether this player may write. */
    public static CompoundTag editorPayload(ServerPlayer player, CredentialRegistryData data) {
        return editorPayload(player, data, null);
    }

    /** Payload of the editor screen plus the template the save just produced. */
    public static CompoundTag editorPayload(ServerPlayer player, CredentialRegistryData data,
                                            @Nullable CredentialRegistryData.Template highlight) {
        return editorPayload(player, data, highlight, "", "", null);
    }

    /**
     * Full editor payload (26.4): snapshot, admin flag, the template a save
     * produced, the concrete error key of a refused write, the page to show and
     * the echoed form so a failed save never drops the typed input.
     */
    public static CompoundTag editorPayload(ServerPlayer player, CredentialRegistryData data,
                                            @Nullable CredentialRegistryData.Template highlight,
                                            @Nullable String errorKey, @Nullable String editorTab,
                                            @Nullable CompoundTag echo) {
        CompoundTag payload = data.snapshot();
        payload.putBoolean("admin", player != null && player.hasPermissions(2));
        if (highlight != null) {
            // 26.4: the final, normalised template is echoed back so the editor
            // can re-select it and display the repaired style immediately.
            payload.put("highlightTemplate", highlight.toTag());
        }
        if (errorKey != null && !errorKey.isBlank()) {
            payload.putString("error", errorKey);
        }
        if (editorTab != null && !editorTab.isBlank()) {
            payload.putString("editorTab", editorTab);
        }
        if (echo != null) {
            payload.put("echo", echo.copy());
        }
        return payload;
    }

    private static void sendSnapshot(ServerPlayer player, CredentialRegistryData data) {
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new com.veriborder.network.RegistrySnapshotPacket(data.snapshot()));
    }

    /** Sends the current snapshot to one player (used on login). */
    public static void pushSnapshot(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        sendSnapshot(player, CredentialRegistryData.get(server));
    }

    /** Pushes the current snapshot to every player online (S鈫扖 sync). */
    public static void syncAll(@Nullable MinecraftServer server) {
        if (server == null) {
            return;
        }
        CompoundTag snapshot = CredentialRegistryData.get(server).snapshot();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new com.veriborder.network.RegistrySnapshotPacket(snapshot));
        }
    }

    // ------------------------------------------------------------------ writes

    /**
     * Handles one registry action packet.
     *
     * <p>{@link #ACTION_SNAPSHOT_REQUEST} is the only action that requires no
     * permission: it answers a client whose cache is empty (a fresh client, a
     * respawned screen after {@code /reload}) with the current snapshot and opens
     * nothing.</p>
     *
     * @return {@code true} when the action belonged to the registry
     */
    public static boolean handleAction(ServerPlayer player, String action, CompoundTag data) {
        if (action == null || !action.startsWith("registry_")) {
            return false;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return true;
        }
        CredentialRegistryData registry = CredentialRegistryData.get(server);
        if (ACTION_SNAPSHOT_REQUEST.equals(action)) {
            // 26.3 round 12: opening the registry or a gate-config screen with an
            // empty client cache asks for exactly this; no editor is opened.
            sendSnapshot(player, registry);
            return true;
        }
        if (ACTION_UPLOAD_PORTRAIT.equals(action)) {
            // 26.4.1: the photo booth is an ordinary issuing machine, so this one
            // action is deliberately not OP-gated — otherwise a traveller could
            // never hand in their own photo. It stays safe because the payload is
            // content-addressed and bounded by PortraitLibrary (real PNG, 32/64
            // square, 16 KiB, 32 slots), and a caller without OP level 2 may only
            // store a portrait for their own UUID.
            WriteOutcome portraitOutcome = uploadPortrait(player, registry, data);
            if (portraitOutcome.changed()) {
                syncAll(server);
            }
            // No editor is opened: the uploader keeps whatever screen it is on and
            // learns the new id from the chat reply plus the refreshed snapshot.
            sendSnapshot(player, registry);
            return true;
        }
        if (!reopensEditor(action)) {
            // 26.4 fix: the open editor listens to this snapshot and refreshes its
            // lists in place. Re-opening the editor here reset the page to
            // Countries and discarded the typed form (the reported "refresh jumps
            // back to 国家登记" bug).
            sendSnapshot(player, registry);
            return true;
        }
        if (!player.hasPermissions(2)) {
            message(player, "message.veriborder.registry.denied");
            sendEditor(player, registry, null, "message.veriborder.registry.denied",
                    editorTabFor(action), null);
            return true;
        }
        WriteOutcome outcome;
        switch (action) {
            case ACTION_SAVE_COUNTRY -> outcome = saveCountry(player, registry, data);
            case ACTION_DELETE_COUNTRY -> outcome = deleteCountry(player, server, registry, data);
            case ACTION_RECODE_COUNTRY -> outcome = recodeCountry(player, server, registry, data);
            // 26.4: a template save reports its own, specific message ("saved",
            // "style corrected", "name required", "kind invalid", "full") and the
            // same key travels back in the editor payload for the GUI footer.
            case ACTION_SAVE_TEMPLATE -> outcome = saveTemplate(player, registry, data);
            case ACTION_DELETE_TEMPLATE -> outcome = deleteTemplate(player, server, registry, data);
            case ACTION_UPLOAD_TEXTURE -> outcome = uploadTexture(player, registry, data);
            default -> outcome = WriteOutcome.of(false);
        }
        if (outcome.changed()) {
            registry.setDirty();
            if (!ACTION_SAVE_TEMPLATE.equals(action)) {
                message(player, "message.veriborder.registry.saved");
            }
            syncAll(server);
        }
        sendSnapshot(player, registry);
        if (ACTION_UPLOAD_TEXTURE.equals(action)) {
            // 26.4.1: an upload is not a form save. The open editor refreshes its
            // base-map strip from the snapshot; re-opening it would throw away the
            // template draft the operator is still editing.
            return true;
        }
        // 26.4 fix: the reopened editor keeps the page it was on and receives the
        // concrete reason (plus the refused form) when the write failed.
        sendEditor(player, registry, outcome.template(), outcome.errorKey(),
                editorTabFor(action), outcome.echo());
        return true;
    }

    /**
     * Issue registry actions that must reopen the editor with a result. A refresh
     * or a cache request is deliberately <b>not</b> one of them: the client
     * updates the open editor from the snapshot itself (26.4 fix).
     */
    public static boolean reopensEditor(@Nullable String action) {
        return action != null && !ACTION_REFRESH.equals(action) && !ACTION_SNAPSHOT_REQUEST.equals(action);
    }

    /** Page of the registry editor an action belongs to. */
    public static String editorTabFor(@Nullable String action) {
        return action != null && (action.contains("template") || action.contains("texture"))
                ? "templates" : "countries";
    }

    /**
     * Translation key of the concrete server reason for a template save status
     * (rendered by the editor footer and sent to chat).
     */
    public static String errorKeyFor(@Nullable TemplateSaveStatus status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case MISSING_NAME -> "message.veriborder.registry.template_name_required";
            case INVALID_KIND -> "message.veriborder.registry.template_kind_invalid";
            default -> "";
        };
    }

    /**
     * Result of one registry write: whether the registry changed, the template a
     * save produced, the concrete error key of a refusal and the submitted
     * <b>template</b> form to echo back so a failed save never clears the GUI
     * (26.4 fix).
     */
    private record WriteOutcome(boolean changed,
                                @Nullable CredentialRegistryData.Template template,
                                String errorKey,
                                @Nullable CompoundTag echo) {
        static WriteOutcome of(boolean changed) {
            return new WriteOutcome(changed, null, "", null);
        }

        static WriteOutcome saved(CredentialRegistryData.Template template) {
            return new WriteOutcome(true, template, "", null);
        }

        static WriteOutcome failed(@Nullable String errorKey, @Nullable CompoundTag echo) {
            return new WriteOutcome(false, null, errorKey == null ? "" : errorKey, echo);
        }

        /** A refusal the chat message already explains with arguments. */
        static WriteOutcome refused() {
            return failed("", null);
        }
    }

    private static WriteOutcome saveCountry(ServerPlayer player, CredentialRegistryData registry, CompoundTag data) {
        CredentialRegistryData.Country country = new CredentialRegistryData.Country(
                data.getString("code"), data.getString("nameZh"), data.getString("nameEn"),
                data.getString("defaultNationality"), data.getString("note"));
        if (!country.valid()) {
            message(player, "message.veriborder.registry.invalid_country");
            // The country form has no echo: the reason key is enough, and only a
            // template echo may restore the template draft (26.4).
            return WriteOutcome.failed("message.veriborder.registry.invalid_country", null);
        }
        if (!registry.upsertCountry(country)) {
            message(player, "message.veriborder.registry.full");
            return WriteOutcome.failed("message.veriborder.registry.full", null);
        }
        return WriteOutcome.of(true);
    }

    /**
     * 26.3 round 12: a country/region that something still references (a document,
     * a gate whitelist, a gate brush, an issuing machine or a template's default
     * code) is not removed; the operator is told what is in the way.
     */
    private static WriteOutcome deleteCountry(ServerPlayer player, MinecraftServer server,
                                              CredentialRegistryData registry, CompoundTag data) {
        String code = CredentialRegistryData.normalizeCode(data.getString("code"));
        if (code.isBlank()) {
            message(player, "message.veriborder.registry.invalid_country");
            return WriteOutcome.failed("message.veriborder.registry.invalid_country", data);
        }
        List<RegistryReferences.Ref> refs = RegistryReferences.forCountry(
                RegistryReferences.collect(server, registry), code);
        if (!refs.isEmpty()) {
            message(player, "message.veriborder.registry.country_in_use", code,
                    Component.translatable(refs.get(0).kindKey()));
            return WriteOutcome.refused();
        }
        return WriteOutcome.of(registry.removeCountry(code));
    }

    /**
     * 26.3 round 12 "change code": the three-letter code of a registered
     * country/region is immutable by default (the editor greys the field out);
     * renaming it is an explicit operation. Documents already issued keep the old
     * code (they are historical records - the operator is told so), while
     * templates' default codes and every loaded gate/gate-brush whitelist follow
     * the new code so the gate keeps accepting arrivals from that country.
     */
    private static WriteOutcome recodeCountry(ServerPlayer player, MinecraftServer server,
                                              CredentialRegistryData registry, CompoundTag data) {
        String from = CredentialRegistryData.normalizeCode(data.getString("code"));
        String to = CredentialRegistryData.normalizeCode(data.getString("to"));
        if (!registry.recodifyCountry(from, to)) {
            message(player, "message.veriborder.registry.recode_invalid", from);
            return WriteOutcome.refused();
        }
        followCountryCode(server, from, to);
        message(player, "message.veriborder.registry.recoded", from, to);
        return WriteOutcome.of(true);
    }

    /** Re-points every loaded gate and gate brush whitelist from {@code from} to {@code to}. */
    private static void followCountryCode(MinecraftServer server, String from, String to) {
        for (com.veriborder.block.DeviceBlockEntity device
                : com.veriborder.block.DeviceBlockEntity.loadedDevices(server)) {
            device.setEntryCountryCodes(rename(device.entryCountryCodesTag(), from, to));
            device.setExitCountryCodes(rename(device.exitCountryCodesTag(), from, to));
            CompoundTag dualGate = device.dualGateConfig();
            if (dualGate != null && !dualGate.isEmpty()) {
                CompoundTag renamed = dualGate.copy();
                renamed.put(com.veriborder.item.DualGateBrushConfig.ENTRY_COUNTRY_CODES,
                        rename(renamed.getList(com.veriborder.item.DualGateBrushConfig.ENTRY_COUNTRY_CODES,
                                Tag.TAG_STRING), from, to));
                renamed.put(com.veriborder.item.DualGateBrushConfig.EXIT_COUNTRY_CODES,
                        rename(renamed.getList(com.veriborder.item.DualGateBrushConfig.EXIT_COUNTRY_CODES,
                                Tag.TAG_STRING), from, to));
                device.setDualGateConfig(renamed);
            }
            device.changed();
        }
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            for (int slot = 0; slot < online.getInventory().getContainerSize(); slot++) {
                net.minecraft.world.item.ItemStack stack = online.getInventory().getItem(slot);
                if (stack == null || stack.isEmpty() || stack.getTag() == null) {
                    continue;
                }
                CompoundTag root = stack.getTag();
                boolean touched = false;
                if (root.contains(com.veriborder.item.GateBrushConfig.TAG, Tag.TAG_COMPOUND)) {
                    CompoundTag config = root.getCompound(com.veriborder.item.GateBrushConfig.TAG);
                    config.put(com.veriborder.item.GateBrushConfig.ENTRY_COUNTRY_CODES,
                            rename(config.getList(com.veriborder.item.GateBrushConfig.ENTRY_COUNTRY_CODES,
                                    Tag.TAG_STRING), from, to));
                    config.put(com.veriborder.item.GateBrushConfig.EXIT_COUNTRY_CODES,
                            rename(config.getList(com.veriborder.item.GateBrushConfig.EXIT_COUNTRY_CODES,
                                    Tag.TAG_STRING), from, to));
                    touched = true;
                }
                if (root.contains(com.veriborder.item.DualGateBrushConfig.TAG, Tag.TAG_COMPOUND)) {
                    CompoundTag config = root.getCompound(com.veriborder.item.DualGateBrushConfig.TAG);
                    config.put(com.veriborder.item.DualGateBrushConfig.ENTRY_COUNTRY_CODES,
                            rename(config.getList(com.veriborder.item.DualGateBrushConfig.ENTRY_COUNTRY_CODES,
                                    Tag.TAG_STRING), from, to));
                    config.put(com.veriborder.item.DualGateBrushConfig.EXIT_COUNTRY_CODES,
                            rename(config.getList(com.veriborder.item.DualGateBrushConfig.EXIT_COUNTRY_CODES,
                                    Tag.TAG_STRING), from, to));
                    touched = true;
                }
                if (touched) {
                    online.getInventory().setItem(slot, stack);
                    online.getInventory().setChanged();
                    online.containerMenu.broadcastChanges();
                }
            }
        }
    }

    /** Copy of a three-letter string list with {@code from} replaced by {@code to}. */
    public static ListTag rename(ListTag list, String from, String to) {
        ListTag out = new ListTag();
        if (list == null) {
            return out;
        }
        String needle = CredentialRegistryData.normalizeCountryCode(from);
        String replacement = CredentialRegistryData.normalizeCountryCode(to);
        for (int i = 0; i < list.size(); i++) {
            String code = CredentialRegistryData.normalizeCountryCode(list.getString(i));
            if (code.isEmpty()) {
                continue;
            }
            String value = code.equals(needle) ? replacement : code;
            boolean duplicate = false;
            for (int j = 0; j < out.size(); j++) {
                if (out.getString(j).equals(value)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate && !value.isEmpty()) {
                out.add(net.minecraft.nbt.StringTag.valueOf(value));
            }
        }
        return out;
    }

    /** Why a template save request could not be normalised into a row. */
    public enum TemplateSaveStatus {
        OK,
        MISSING_NAME,
        INVALID_KIND
    }

    /**
     * Pure result of validating/normalising a template save request (26.4).
     *
     * @param styleCorrected the submitted style was blank, unknown or belonged to
     *                       another credential kind and was replaced by the first
     *                       official layout of the template's kind
     */
    public record PreparedTemplate(TemplateSaveStatus status,
                                   @Nullable CredentialRegistryData.Template template,
                                   boolean styleCorrected) {
        public boolean ok() {
            return status == TemplateSaveStatus.OK;
        }
    }

    /**
     * Validates and normalises a {@code registry_save_template} payload without
     * touching the world, so the rules are unit-testable:
     *
     * <ul>
     *   <li>an invalid credential kind is refused ({@link TemplateSaveStatus#INVALID_KIND});</li>
     *   <li>a blank name is refused ({@link TemplateSaveStatus#MISSING_NAME});</li>
     *   <li>style is never a reason to refuse: blank, unknown and wrong-kind styles
     *       fall back to {@code DocStyle.forKind(kind).get(0)}.</li>
     * </ul>
     */
    public static PreparedTemplate prepareTemplate(@Nullable CompoundTag data) {
        String rawId = data == null ? "" : data.getString("id");
        String rawKind = data == null ? "" : data.getString("kind");
        String rawName = data == null ? "" : data.getString("name");
        String rawStyle = data == null ? "" : data.getString("style");
        // 26.4 fix: log exactly what arrived, so a "needs a name" report can be
        // traced to an empty client payload instead of a server-side guess.
        LOGGER.info("registry template save request: id='{}' kind='{}' name='{}' style='{}' "
                        + "mrzCountry='{}' color={} icon='{}' titleZh='{}' titleEn='{}' tint={} "
                        + "emblem='{}' texture='{}'",
                rawId, rawKind, rawName, rawStyle,
                data == null ? "" : data.getString("mrzCountry"),
                data == null ? 0 : data.getInt("color"),
                data == null ? "" : data.getString("icon"),
                data == null ? "" : data.getString("titleZh"),
                data == null ? "" : data.getString("titleEn"),
                data == null ? 0 : data.getInt("tint"),
                data == null ? "" : data.getString("emblem"),
                data == null ? "" : data.getString("texture"));
        DocumentKind kind = DocumentKind.byIdOrNull(rawKind);
        if (kind == null) {
            LOGGER.info("registry template save refused: unknown kind '{}'", rawKind);
            return new PreparedTemplate(TemplateSaveStatus.INVALID_KIND, null, false);
        }
        if (rawName.isBlank()) {
            LOGGER.info("registry template save refused: blank name (kind={})", kind);
            return new PreparedTemplate(TemplateSaveStatus.MISSING_NAME, null, false);
        }
        CredentialRegistryData.Template raw = new CredentialRegistryData.Template(rawId, kind, rawName, rawStyle,
                data.getString("mrzCountry"), "", "", data.getInt("color"), data.getString("icon"),
                data.getString("titleZh"), data.getString("titleEn"), data.getInt("tint"),
                data.getString("emblem"), data.getString("texture"));
        CredentialRegistryData.Template normalized = raw.normalized();
        boolean corrected = !raw.style().equals(normalized.style());
        LOGGER.info("registry template save normalised: id='{}' kind={} name='{}' style='{}' corrected={}",
                normalized.id().isBlank()
                        ? CredentialRegistryData.deriveTemplateId(normalized.kind(), normalized.name())
                        : normalized.id(),
                normalized.kind(), normalized.name(), normalized.style(), corrected);
        return new PreparedTemplate(TemplateSaveStatus.OK, normalized, corrected);
    }

    /**
     * Saves a template. 26.4: only a missing name, an invalid kind or a full list
     * is refused; a bad style is repaired and the operator is told so.
     *
     * @return the write result carrying the final stored template (with the
     *         derived id and normalised style) or the concrete reason key
     */
    private static WriteOutcome saveTemplate(ServerPlayer player,
                                             CredentialRegistryData registry,
                                             CompoundTag data) {
        // 26.3 round 12: the "field locks" and "allowed issuers" fields are gone;
        // a template is offered by its kind's own issuer. The legacy keys are
        // accepted but always stored blank so old snapshots clean up on save.
        PreparedTemplate prepared = prepareTemplate(data);
        if (!prepared.ok()) {
            String errorKey = errorKeyFor(prepared.status());
            message(player, errorKey);
            // Echo the refused form so the reopened editor keeps the typed name.
            return WriteOutcome.failed(errorKey, data);
        }
        CredentialRegistryData.Template template = prepared.template();
        if (template == null) {
            String errorKey = "message.veriborder.registry.full";
            message(player, errorKey);
            return WriteOutcome.failed(errorKey, data);
        }
        // 26.4.1: the base-map reference must point at a stored cover. An unknown
        // hash is dropped (the template is still saved, its cover falls back to the
        // drawn one) instead of failing the whole edit.
        if (!template.texture().isBlank() && !registry.hasTexture(template.texture())) {
            LOGGER.info("registry template '{}' references unknown texture '{}': dropped",
                    template.id(), template.texture());
            template = template.withTexture("");
        }
        if (!registry.upsertTemplate(template)) {
            message(player, "message.veriborder.registry.full");
            return WriteOutcome.failed("message.veriborder.registry.full", data);
        }
        // upsertTemplate derives a stable id when the client did not send one, so
        // re-read the stored row before echoing it back to the editor.
        String finalId = template.id().isBlank()
                ? CredentialRegistryData.deriveTemplateId(template.kind(), template.name())
                : template.id();
        CredentialRegistryData.Template saved = registry.template(finalId);
        if (saved == null) {
            saved = template;
        }
        LOGGER.info("registry template stored: id='{}' kind={} style='{}' name='{}'",
                saved.id(), saved.kind(), saved.style(), saved.name());
        String styleName = Component.translatable(DocStyle.byId(saved.style()).shortNameKey()).getString();
        if (prepared.styleCorrected()) {
            message(player, "message.veriborder.registry.template_style_corrected", saved.name(), styleName);
        } else {
            message(player, "message.veriborder.registry.template_saved", saved.name(), styleName);
        }
        return WriteOutcome.saved(saved);
    }

    /**
     * 26.4.1 template deletion.
     *
     * <p>An issued document carries its own snapshot of the template (id, tint,
     * emblem, base-map hash, title), so documents no longer block the delete —
     * they keep their look and fall back gracefully. What <b>does</b> block it is
     * an issuing machine that locked the template as its layout, because that
     * machine would silently issue a different document afterwards.</p>
     *
     * <p>A blank or unknown id is a concrete error (never a silent success), and a
     * successful delete releases the base-map slot the template occupied.</p>
     */
    private static WriteOutcome deleteTemplate(ServerPlayer player, MinecraftServer server,
                                               CredentialRegistryData registry, CompoundTag data) {
        String id = data.getString("id").trim();
        if (id.isBlank()) {
            message(player, "message.veriborder.registry.template_missing_id");
            return WriteOutcome.failed("message.veriborder.registry.template_missing_id", data);
        }
        CredentialRegistryData.Template template = registry.template(id);
        if (template == null) {
            message(player, "message.veriborder.registry.template_not_found", id);
            return WriteOutcome.failed("message.veriborder.registry.template_not_found", data);
        }
        // Only a machine that locked/selected this template as its layout counts;
        // documents, presets and locked document names do not (26.4.1).
        int lockCount = templateLockCount(RegistryReferences.collect(server, registry), template.id());
        if (lockCount > 0) {
            message(player, "message.veriborder.registry.template_locked", template.name(), lockCount);
            return WriteOutcome.failed("message.veriborder.registry.template_locked", data);
        }
        if (!registry.removeTemplate(template.id())) {
            message(player, "message.veriborder.registry.template_not_found", id);
            return WriteOutcome.failed("message.veriborder.registry.template_not_found", data);
        }
        int released = registry.releaseUnusedTextures();
        LOGGER.info("registry template deleted: id='{}' name='{}' releasedTextures={}",
                template.id(), template.name(), released);
        return WriteOutcome.of(true);
    }

    /**
     * 26.4.1 pure delete gate: how many issuing machines currently lock this
     * template as their layout.
     *
     * <p>An issued document keeps its own signed snapshot of the template (id,
     * tint, emblem, base map, title), so documents — and the locked document
     * names / saved presets that only borrow its name — are deliberately not
     * counted: deleting the template must never change a document that is already
     * in someone's pocket. What still blocks the delete is a machine that would
     * silently issue a different document afterwards.</p>
     */
    public static int templateLockCount(@Nullable java.util.Collection<RegistryReferences.Evidence> evidence,
                                        @Nullable String templateId) {
        String id = templateId == null ? "" : templateId.trim();
        if (id.isEmpty() || evidence == null) {
            return 0;
        }
        int count = 0;
        for (RegistryReferences.Evidence item : evidence) {
            if (item != null && item.kind() == RegistryReferences.Kind.TEMPLATE_LOCK
                    && id.equals(item.templateId())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 26.4.1 custom cover upload: {@code {hash, png}} where {@code png} is the
     * base64 payload. Every limit (PNG, 64x64, 8 KiB, 16 slots) is enforced by
     * {@link CredentialRegistryData#putTextureChecked}; a refusal is reported with
     * its concrete reason, never silently dropped.
     */
    private static WriteOutcome uploadTexture(ServerPlayer player, CredentialRegistryData registry,
                                              CompoundTag data) {
        byte[] png = decodeBase64(data.getString("png"));
        CredentialRegistryData.TextureResult result =
                registry.putTextureChecked(data.getString("hash"), png);
        if (!result.ok()) {
            String errorKey = result.errorKey();
            LOGGER.info("registry texture upload refused: reason={} hash='{}' bytes={}",
                    result.rejection(), result.hash(), png == null ? 0 : png.length);
            message(player, errorKey);
            return WriteOutcome.failed(errorKey, null);
        }
        LOGGER.info("registry texture uploaded: hash='{}' bytes={} stored={}",
                result.hash(), png == null ? 0 : png.length, registry.textureHashes().size());
        message(player, "message.veriborder.registry.texture_saved");
        return WriteOutcome.of(true);
    }

    /**
     * 26.4.1 portrait upload: {@code {png, owner}} where {@code png} is the base64
     * payload. {@link PortraitLibrary#put(byte[])} enforces every limit (real PNG,
     * 32 or 64 square, 16 KiB, 32 portraits), so a forged packet can neither grow
     * the save file nor smuggle a non-image into the client renderer.
     *
     * <p>The owner records who handed the photo in (the {@code skin} reference
     * reads it, a {@code custom:<hash>} document keeps it as provenance). A caller
     * without OP level 2 can only claim their own UUID.</p>
     */
    private static WriteOutcome uploadPortrait(ServerPlayer player, CredentialRegistryData registry,
                                               CompoundTag data) {
        byte[] png = decodeBase64(data.getString("png"));
        String owner = PortraitLibrary.normalizeOwner(data.getString("owner"));
        if (!player.hasPermissions(2) || owner.isEmpty()) {
            owner = PortraitLibrary.normalizeOwner(player.getStringUUID());
        }
        PortraitLibrary.PortraitResult result = registry.putPortrait(png);
        if (!result.ok()) {
            String errorKey = result.errorKey();
            LOGGER.info("portrait upload refused by '{}': reason={} bytes={}",
                    player.getGameProfile().getName(), result.rejection(),
                    png == null ? 0 : png.length);
            message(player, errorKey);
            return WriteOutcome.failed(errorKey, null);
        }
        LOGGER.info("portrait uploaded by '{}': id='{}' owner='{}' bytes={} stored={}",
                player.getGameProfile().getName(), result.id(), owner,
                png == null ? 0 : png.length, registry.portraits().size());
        // The accepted reply is the id the client writes onto the document.
        message(player, "message.veriborder.portrait.uploaded", result.id());
        return WriteOutcome.of(true);
    }

    @Nullable
    private static byte[] decodeBase64(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.util.Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    // ------------------------------------------------------------------ reads

    /**
     * 26.4.1 portrait library of the server's world, or {@code null} when there is
     * no server (client-side call from a screen that only has a document).
     */
    @Nullable
    public static PortraitLibrary portraits(@Nullable MinecraftServer server) {
        return server == null ? null : CredentialRegistryData.get(server).portraits();
    }

    /** Country list for an issuer payload / client cache. */
    public static ListTag countriesTag(@Nullable MinecraftServer server) {
        if (server == null) {
            return new ListTag();
        }
        ListTag list = new ListTag();
        for (CredentialRegistryData.Country country : CredentialRegistryData.get(server).countries()) {
            list.add(country.toTag());
        }
        return list;
    }

    /**
     * Registry slice the issuer screen needs: all countries/regions plus the
     * templates that belong to this document kind and are allowed for this
     * issuer type. A non-document issuer (visa/endorsement) gets countries only.
     */
    public static CompoundTag issuerPayload(@Nullable MinecraftServer server, String issuerId,
                                            DocumentKind kind) {
        CompoundTag payload = new CompoundTag();
        if (server == null) {
            payload.put("countries", new ListTag());
            payload.put("templates", new ListTag());
            payload.put(PortraitLibrary.KEY, new ListTag());
            return payload;
        }
        CredentialRegistryData registry = CredentialRegistryData.get(server);
        payload.put("countries", countriesTag(server));
        ListTag templates = new ListTag();
        if (kind != null) {
            for (CredentialRegistryData.Template template : registry.templatesFor(kind, issuerId)) {
                templates.add(template.toTag());
            }
        }
        payload.put("templates", templates);
        // 26.4.1: the photo booth's portrait picker needs the uploaded portraits
        // next to the countries and templates, so it does not have to wait for a
        // registry snapshot to show the photos a traveller can pick from.
        payload.put(PortraitLibrary.KEY,
                registry.portraits().snapshot().getList(PortraitLibrary.KEY, Tag.TAG_COMPOUND));
        return payload;
    }

    /**
     * Country/region code written onto a newly issued document
     * ({@code issuerCountryCode}). The machine's configured country wins; the
     * submitted form is only a fallback when the machine's value is not
     * registered. Returns "" when the registry has no match, which leaves the
     * document without an issuer country (legacy behaviour).
     */
    public static String resolveIssuerCountryCode(@Nullable MinecraftServer server, String deviceCountry,
                                                  @Nullable CompoundTag form) {
        if (server == null) {
            return "";
        }
        CredentialRegistryData registry = CredentialRegistryData.get(server);
        String fromDevice = codeOf(registry.matchCountry(deviceCountry));
        if (!fromDevice.isBlank()) {
            return fromDevice;
        }
        if (form != null) {
            String requested = CredentialRegistryData.normalizeCode(form.getString("issuerCountryCode"));
            if (!requested.isBlank() && registry.country(requested) != null) {
                return requested;
            }
            String fromNationality = codeOf(registry.matchCountry(form.getString("nationality")));
            if (!fromNationality.isBlank()) {
                return fromNationality;
            }
        }
        return "";
    }

    /** Registered country code for a raw name/code, or "". */
    public static String countryCode(@Nullable MinecraftServer server, String value) {
        if (server == null) {
            return "";
        }
        return codeOf(CredentialRegistryData.get(server).matchCountry(value));
    }

    /**
     * Default nationality of a registered country code (26.3 round 12). The
     * issuing machine has a single country selector, and this is the nationality
     * it writes onto every document it issues; "" when the code is unknown.
     */
    public static String nationalityFor(@Nullable MinecraftServer server, @Nullable String code) {
        if (server == null) {
            return "";
        }
        CredentialRegistryData.Country country = CredentialRegistryData.get(server)
                .country(CredentialRegistryData.normalizeCountryCode(code));
        if (country == null) {
            return "";
        }
        return country.defaultNationality().isBlank() ? country.displayName(true)
                : country.defaultNationality();
    }

    private static String codeOf(@Nullable CredentialRegistryData.Country country) {
        return country == null ? "" : country.code();
    }

    /** Template snapshot for the issuer selector, as a plain compound. */
    @Nullable
    public static CompoundTag templateByName(@Nullable MinecraftServer server, String name) {
        if (server == null || name == null || name.isBlank()) {
            return null;
        }
        for (CredentialRegistryData.Template template : CredentialRegistryData.get(server).templates()) {
            if (template.name().equalsIgnoreCase(name.trim())) {
                return template.toTag();
            }
        }
        return null;
    }

    /**
     * 26.4 registry layout lock: the template behind a locked issuer layout, or
     * {@code null} when it has been deleted (the layout lock then keeps working
     * without the template's colour/icon).
     */
    @Nullable
    public static CredentialRegistryData.Template templateById(@Nullable MinecraftServer server,
                                                               @Nullable String id) {
        if (server == null || id == null || id.isBlank()) {
            return null;
        }
        CredentialRegistryData.Template template = CredentialRegistryData.get(server).template(id.trim());
        return template == null || !template.valid() ? null : template.normalized();
    }

    /** Short public summary used by the diagnostic command. */
    public static String summary(@Nullable MinecraftServer server) {
        if (server == null) {
            return "0 countries, 0 templates";
        }
        CredentialRegistryData registry = CredentialRegistryData.get(server);
        List<CredentialRegistryData.Country> countries = registry.countries();
        return countries.size() + " countries, " + registry.templates().size() + " templates";
    }

    private static void message(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
    }
}
