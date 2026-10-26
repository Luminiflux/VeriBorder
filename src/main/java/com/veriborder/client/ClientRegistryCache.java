package com.veriborder.client;

import com.veriborder.doc.DocumentKind;
import com.veriborder.doc.PortraitLibrary;
import com.veriborder.network.DeviceActionPacket;
import com.veriborder.network.ModNetwork;
import com.veriborder.registry.CredentialRegistryData;
import com.veriborder.registry.RegistryService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Client mirror of the server's credential registry (26.3 S→C snapshot sync).
 *
 * <p>The snapshot is pushed on join/open and after every registry write, so the
 * issuer screens can render their country/region and template selectors without
 * asking the server again. It is a cache only: all writes still go through the
 * server, which re-validates them at OP level 2.</p>
 *
 * <p>26.3 round 12 recovery: a client whose cache is still empty when the registry
 * editor, a gate brush or a device configuration screen opens asks for the
 * snapshot itself ({@link #requestIfEmpty()}). That covers a fresh client, a
 * screen opened before the join snapshot arrived and a {@code /reload} whose
 * re-sync was missed.</p>
 */
public final class ClientRegistryCache {
    /** Minimum gap between two on-demand snapshot requests (anti-spam). */
    private static final long REQUEST_COOLDOWN_MILLIS = 2_000L;

    /**
     * One custom cover base map offered by the registry (26.4.1).
     *
     * @param name file name shown in the editor dropdown (without {@code .png})
     * @param hash content hash the template stores in its {@code texture} field
     * @param data base64 PNG payload of the snapshot, "" when the snapshot left
     *             this entry out (budget) or the file could not be read
     */
    public record Texture(String name, String hash, String data) {
        public boolean hasData() {
            return data != null && !data.isBlank();
        }
    }

    /**
     * One uploaded document portrait (26.4.1).
     *
     * @param hash lowercase hex SHA-256 of the PNG bytes (its address)
     * @param data base64 PNG payload of the snapshot; {@code ""} when the snapshot
     *             left this portrait out for the size budget
     */
    public record Portrait(String hash, String data) {
        public boolean hasData() {
            return data != null && !data.isBlank();
        }

        /** {@code custom:<hash>} reference a document stores for this image. */
        public String id() {
            return com.veriborder.doc.PortraitLibrary.customId(hash);
        }
    }

    private static volatile List<CredentialRegistryData.Country> countries = List.of();
    private static volatile List<CredentialRegistryData.Template> templates = List.of();
    private static volatile List<Texture> textures = List.of();
    /** 26.4.1 uploaded document portraits published by the registry. */
    private static volatile List<Portrait> portraits = List.of();
    /**
     * Bumped on every {@link #update(CompoundTag)}. The portrait renderer reads it
     * to know when its dynamic texture slots are stale (26.4.1), so a snapshot
     * rebuild never needs a second listener on this class.
     */
    private static volatile long revision;
    private static volatile Runnable listener;
    private static volatile long lastRequest;

    private ClientRegistryCache() {
    }

    /** Replaces the cache with a fresh server snapshot. */
    public static void update(@Nullable CompoundTag snapshot) {
        List<CredentialRegistryData.Country> newCountries = new ArrayList<>();
        List<CredentialRegistryData.Template> newTemplates = new ArrayList<>();
        List<Texture> newTextures = texturesOf(snapshot);
        List<Portrait> newPortraits = portraitsOf(snapshot);
        if (snapshot != null) {
            ListTag countryList = snapshot.getList("countries", Tag.TAG_COMPOUND);
            for (int i = 0; i < countryList.size(); i++) {
                CredentialRegistryData.Country country =
                        CredentialRegistryData.Country.fromTag(countryList.getCompound(i));
                if (country.valid()) {
                    newCountries.add(country);
                }
            }
            ListTag templateList = snapshot.getList("templates", Tag.TAG_COMPOUND);
            for (int i = 0; i < templateList.size(); i++) {
                // 26.4: normalise on the client too, so a legacy snapshot can never
                // hand a blank/wrong-kind style to the registry editor.
                CredentialRegistryData.Template template =
                        CredentialRegistryData.Template.fromTag(templateList.getCompound(i)).normalized();
                if (template.valid()) {
                    newTemplates.add(template);
                }
            }
        }
        countries = List.copyOf(newCountries);
        templates = List.copyOf(newTemplates);
        textures = List.copyOf(newTextures);
        portraits = List.copyOf(newPortraits);
        revision++;
        Runnable callback = listener;
        if (callback != null) {
            callback.run();
        }
    }

    /**
     * Uploaded portraits of one snapshot (26.4.1): {@code {hash, data}} rows where
     * {@code data} is the base64 PNG. Pure parsing, so a screen can read the list
     * straight from its own editor payload, and a snapshot that lost an entry to
     * the size budget simply carries fewer rows (the document then falls back to
     * the holder skin, never to an empty photo box).
     */
    public static List<Portrait> portraitsOf(@Nullable CompoundTag snapshot) {
        List<Portrait> result = new ArrayList<>();
        if (snapshot == null) {
            return result;
        }
        ListTag list = snapshot.getList(PortraitLibrary.KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && result.size() < PortraitLibrary.MAX_CUSTOM; i++) {
            CompoundTag entry = list.getCompound(i);
            String hash = PortraitLibrary.normalizeHash(entry.getString("hash"));
            String data = entry.getString("data").trim();
            if (hash.isEmpty() || data.isEmpty()) {
                continue;
            }
            Portrait portrait = new Portrait(hash, data);
            boolean duplicate = result.stream().anyMatch(existing -> existing.hash().equals(portrait.hash()));
            if (!duplicate) {
                result.add(portrait);
            }
        }
        return result;
    }

    /** Cached uploaded portraits, snapshot order. */
    public static List<Portrait> portraits() {
        return portraits;
    }

    /** Cache generation; changes whenever a snapshot is applied. */
    public static long revision() {
        return revision;
    }

    /** The cached upload behind a {@code custom:<hash>} id or a bare hash. */
    @Nullable
    public static Portrait portrait(@Nullable String idOrHash) {
        if (idOrHash == null || idOrHash.isBlank()) {
            return null;
        }
        String hash = PortraitLibrary.isCustom(idOrHash)
                ? PortraitLibrary.hashOfId(idOrHash) : PortraitLibrary.normalizeHash(idOrHash);
        if (hash.isEmpty()) {
            return null;
        }
        for (Portrait portrait : portraits) {
            if (portrait.hash().equals(hash)) {
                return portrait;
            }
        }
        return null;
    }

    /**
     * Custom cover base maps of one snapshot (26.4.1). The registry publishes
     * every PNG it read from {@code config/veriborder/templates/} as a
     * {@code {name, hash, data}} compound; {@code data} is the base64 payload the
     * editor previews. Older snapshots (and snapshots that left a texture out for
     * the size budget) simply carry no entry, which the editor renders as "no
     * uploaded base map" instead of failing.
     *
     * <p>Pure parsing, so the registry screen can read the list straight out of
     * its own editor payload without waiting for a snapshot round trip.</p>
     */
    public static List<Texture> texturesOf(@Nullable CompoundTag snapshot) {
        List<Texture> result = new ArrayList<>();
        if (snapshot == null) {
            return result;
        }
        ListTag list = snapshot.getList("textures", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && result.size() < CredentialRegistryData.MAX_TEXTURES; i++) {
            CompoundTag entry = list.getCompound(i);
            String hash = entry.getString("hash").trim();
            String name = firstNonBlank(entry.getString("name"), entry.getString("file"),
                    entry.getString("path"), hash);
            String data = firstNonBlank(entry.getString("data"), entry.getString("png"), "");
            if (hash.isBlank() && name.isBlank()) {
                continue;
            }
            Texture texture = new Texture(name, hash, data);
            boolean duplicate = result.stream().anyMatch(existing ->
                    !texture.hash().isBlank() && existing.hash().equals(texture.hash()));
            if (!duplicate) {
                result.add(texture);
            }
        }
        return result;
    }

    /** Cached custom cover base maps, snapshot order. */
    public static List<Texture> textures() {
        return textures;
    }

    /** The cached base map with this hash, or {@code null}. */
    @Nullable
    public static Texture texture(@Nullable String hash) {
        if (hash == null || hash.isBlank()) {
            return null;
        }
        for (Texture texture : textures) {
            if (texture.hash().equals(hash.trim())) {
                return texture;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    public static List<CredentialRegistryData.Country> countries() {
        return countries;
    }

    public static List<CredentialRegistryData.Template> templates() {
        return templates;
    }

    /**
     * The cached template with this id, or {@code null} (a document whose
     * template has been deleted keeps working from its own snapshot fields).
     */
    @Nullable
    public static CredentialRegistryData.Template template(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String key = id.trim();
        for (CredentialRegistryData.Template template : templates) {
            if (template.id().equals(key)) {
                return template;
            }
        }
        return null;
    }

    @Nullable
    public static CredentialRegistryData.Country country(String code) {
        if (code == null) {
            return null;
        }
        String key = CredentialRegistryData.normalizeCode(code);
        for (CredentialRegistryData.Country country : countries) {
            if (country.code().equals(key)) {
                return country;
            }
        }
        return null;
    }

    /** Templates of one document kind allowed for one issuer, snapshot order. */
    public static List<CredentialRegistryData.Template> templates(DocumentKind kind, String issuerId) {
        List<CredentialRegistryData.Template> result = new ArrayList<>();
        for (CredentialRegistryData.Template template : templates) {
            if (template.kind() == kind && template.allowsIssuer(issuerId)) {
                result.add(template);
            }
        }
        return result;
    }

    /** Lets an open registry screen refresh itself when a snapshot arrives. */
    public static void setListener(@Nullable Runnable callback) {
        listener = callback;
    }

    public static void clearListener(Runnable callback) {
        if (listener == callback) {
            listener = null;
        }
    }

    public static boolean isEmpty() {
        return countries.isEmpty() && templates.isEmpty();
    }

    /**
     * 26.3 round 12: asks the server for the snapshot when this client has nothing
     * cached yet. Called from every screen that needs the registry (registry
     * editor, gate brushes, device configuration); a no-op when the cache is warm
     * and rate-limited so a rebuild of the widgets cannot spam the server.
     *
     * @return {@code true} when a request was actually sent
     */
    public static boolean requestIfEmpty() {
        if (!isEmpty()) {
            return false;
        }
        return requestSnapshot();
    }

    /** Sends one snapshot request, honouring the anti-spam cooldown. */
    public static boolean requestSnapshot() {
        long now = System.currentTimeMillis();
        if (now - lastRequest < REQUEST_COOLDOWN_MILLIS) {
            return false;
        }
        lastRequest = now;
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(BlockPos.ZERO,
                RegistryService.ACTION_SNAPSHOT_REQUEST, new CompoundTag(), -1));
        return true;
    }
}
