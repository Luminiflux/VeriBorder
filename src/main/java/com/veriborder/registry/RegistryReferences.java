package com.veriborder.registry;

import com.veriborder.block.DeviceBlockEntity;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.MrzUtil;
import com.veriborder.item.DocumentItem;
import com.veriborder.item.DualGateBrushConfig;
import com.veriborder.item.GateBrushConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Reference guard for the credential registry (26.3 round 12).
 *
 * <p>Deleting a registered country/region or document template that something
 * still points at would silently break that object, so the registry refuses the
 * delete and names what is in the way:</p>
 *
 * <ul>
 *   <li><b>documents</b> - an issued document whose signed {@code issuerCountry}
 *       equals the code;</li>
 *   <li><b>gate whitelists</b> - a placed gate whose entry/exit allowed-country
 *       list contains the code;</li>
 *   <li><b>gate brushes</b> - a gate/dual-gate brush item carrying the code;</li>
 *   <li><b>issuing machines</b> - the device's registry selection;</li>
 *   <li><b>templates</b> - a template whose default country code is the country,
 *       or (for a template delete) an issuing machine whose locked document name
 *       or saved preset is named after the template.</li>
 * </ul>
 *
 * <p>The classification itself is pure data ({@link Evidence} in, {@link Ref}
 * out) and therefore unit-tested; {@link #collect(MinecraftServer)} only gathers
 * the evidence from the loaded world (loaded chunks and online players — devices
 * in unloaded chunks are not inspected, which the screen states).</p>
 */
public final class RegistryReferences {
    /** Where a registry entry is used. */
    public enum Kind {
        DOCUMENT("document"),
        GATE_WHITELIST("gate_whitelist"),
        GATE_BRUSH("gate_brush"),
        DUAL_GATE_BRUSH("dual_gate_brush"),
        DEVICE_ISSUER("device_issuer"),
        TEMPLATE("template"),
        ISSUER_PRESET("issuer_preset");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public String translationKey() {
            return "reference.veriborder." + id;
        }
    }

    /** One place that references a registry entry, with a human-readable label. */
    public record Ref(Kind kind, String label) {
        public String kindKey() {
            return kind.translationKey();
        }
    }

    /**
     * One observation collected from the world.
     *
     * @param countryCode  the referenced country code ("" when this evidence is
     *                     only about a template)
     * @param templateId   the referenced template id ("" when only about a country)
     * @param templateName the referenced template name ("" when only about a country)
     */
    public record Evidence(Kind kind, String label, String countryCode, String templateId,
                           String templateName) {
        public Evidence {
            kind = kind == null ? Kind.DOCUMENT : kind;
            label = label == null ? "" : label.trim();
            countryCode = countryCode == null ? "" : countryCode.trim().toUpperCase(Locale.ROOT);
            templateId = templateId == null ? "" : templateId.trim();
            templateName = templateName == null ? "" : templateName.trim();
        }

        public static Evidence country(Kind kind, String label, String code) {
            return new Evidence(kind, label, code, "", "");
        }

        public static Evidence template(Kind kind, String label, String templateId, String templateName) {
            return new Evidence(kind, label, "", templateId, templateName);
        }
    }

    private RegistryReferences() {
    }

    /** Places that reference {@code code}; empty when the country is unused. */
    public static List<Ref> forCountry(@Nullable Collection<Evidence> evidence, @Nullable String code) {
        String needle = MrzUtil.countryCode(code);
        List<Ref> refs = new ArrayList<>();
        if (needle.isEmpty() || evidence == null) {
            return refs;
        }
        for (Evidence item : evidence) {
            if (item != null && needle.equals(item.countryCode())) {
                refs.add(new Ref(item.kind(), item.label()));
            }
        }
        return refs;
    }

    /**
     * Places that reference a template. A template can only be referenced by
     * name: issuing machines lock a document name and saved presets carry one,
     * while issued documents keep the layout id (not the template id) on purpose.
     */
    public static List<Ref> forTemplate(@Nullable Collection<Evidence> evidence, @Nullable String templateId,
                                        @Nullable String templateName) {
        String id = templateId == null ? "" : templateId.trim();
        String name = templateName == null ? "" : templateName.trim().toLowerCase(Locale.ROOT);
        List<Ref> refs = new ArrayList<>();
        if (evidence == null) {
            return refs;
        }
        for (Evidence item : evidence) {
            if (item == null) {
                continue;
            }
            boolean byId = !id.isEmpty() && id.equals(item.templateId());
            boolean byName = !name.isEmpty() && name.equals(item.templateName().toLowerCase(Locale.ROOT));
            if (byId || byName) {
                refs.add(new Ref(item.kind(), item.label()));
            }
        }
        return refs;
    }

    /**
     * Gathers the evidence from the loaded world: every online player's carried
     * documents and gate brushes, every loaded device's whitelists/registry
     * selection/locked name/presets.
     */
    public static List<Evidence> collect(@Nullable MinecraftServer server,
                                         @Nullable CredentialRegistryData registry) {
        List<Evidence> evidence = new ArrayList<>();
        if (server == null) {
            return evidence;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            collectInventory(player, evidence);
        }
        for (DeviceBlockEntity device : DeviceBlockEntity.loadedDevices(server)) {
            collectDevice(device, evidence);
        }
        if (registry != null) {
            for (CredentialRegistryData.Template template : registry.templates()) {
                if (!template.mrzCountry().isBlank()) {
                    evidence.add(Evidence.country(Kind.TEMPLATE,
                            template.name(), template.mrzCountry()));
                }
            }
        }
        return evidence;
    }

    private static void collectInventory(ServerPlayer player, List<Evidence> evidence) {
        String owner = player.getGameProfile() == null ? player.getName().getString()
                : player.getGameProfile().getName();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            DocumentData document = DocumentItem.getDocument(stack);
            if (document != null) {
                String code = document.issuerCountry();
                if (!code.isBlank()) {
                    evidence.add(Evidence.country(Kind.DOCUMENT,
                            owner + " · " + document.number(), code));
                }
                continue;
            }
            collectBrush(owner, stack, evidence);
        }
    }

    /** Gate / dual-gate brushes carry their own country whitelists. */
    private static void collectBrush(String owner, ItemStack stack, List<Evidence> evidence) {
        CompoundTag root = stack.getTag();
        if (root == null) {
            return;
        }
        if (root.contains(GateBrushConfig.TAG, Tag.TAG_COMPOUND)) {
            CompoundTag config = root.getCompound(GateBrushConfig.TAG);
            addCountries(evidence, Kind.GATE_BRUSH, owner, GateBrushConfig.entryCountryCodes(config));
            addCountries(evidence, Kind.GATE_BRUSH, owner, GateBrushConfig.exitCountryCodes(config));
        }
        if (root.contains(DualGateBrushConfig.TAG, Tag.TAG_COMPOUND)) {
            CompoundTag config = root.getCompound(DualGateBrushConfig.TAG);
            addCountries(evidence, Kind.DUAL_GATE_BRUSH, owner, DualGateBrushConfig.countryCodes(config));
            addCountries(evidence, Kind.DUAL_GATE_BRUSH, owner, DualGateBrushConfig.exitCountryCodes(config));
        }
    }

    private static void collectDevice(DeviceBlockEntity device, List<Evidence> evidence) {
        String label = device.deviceType().id() + " @ " + device.getBlockPos().toShortString();
        addCountries(evidence, Kind.GATE_WHITELIST, label, device.entryCountryCodes());
        addCountries(evidence, Kind.GATE_WHITELIST, label, device.exitCountryCodes());
        CompoundTag dualGate = device.dualGateConfig();
        if (dualGate != null && !dualGate.isEmpty()) {
            addCountries(evidence, Kind.GATE_WHITELIST, label, DualGateBrushConfig.countryCodes(dualGate));
            addCountries(evidence, Kind.GATE_WHITELIST, label, DualGateBrushConfig.exitCountryCodes(dualGate));
        }
        String issuerCountry = device.issuerCountry();
        if (device.deviceType().isIssuer() && !issuerCountry.isBlank()) {
            evidence.add(Evidence.country(Kind.DEVICE_ISSUER, label, issuerCountry));
        }
        // Template references: the issuer's locked document name and saved presets.
        String lockedName = device.lockedDocumentName();
        if (!lockedName.isBlank()) {
            evidence.add(Evidence.template(Kind.ISSUER_PRESET, label, "", lockedName));
        }
        ListTag presets = device.presets();
        for (int i = 0; i < presets.size(); i++) {
            String name = presets.getCompound(i).getString("name");
            if (!name.isBlank()) {
                evidence.add(Evidence.template(Kind.ISSUER_PRESET, label, "", name));
            }
        }
    }

    private static void addCountries(List<Evidence> evidence, Kind kind, String label, List<String> codes) {
        if (codes == null) {
            return;
        }
        for (String code : codes) {
            String normalized = MrzUtil.countryCode(code);
            if (!normalized.isEmpty()) {
                evidence.add(Evidence.country(kind, label, normalized));
            }
        }
    }

    /** Short “kind · label” summary for a refusal message/tooltip. */
    public static String summary(@Nullable List<Ref> refs) {
        if (refs == null || refs.isEmpty()) {
            return "";
        }
        Ref first = refs.get(0);
        String base = first.kind().id() + " · " + (first.label().isBlank() ? "—" : first.label());
        return refs.size() > 1 ? base + " (+" + (refs.size() - 1) + ")" : base;
    }
}
