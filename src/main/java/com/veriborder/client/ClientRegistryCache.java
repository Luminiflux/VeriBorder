package com.veriborder.client;

import com.veriborder.doc.DocumentKind;
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

    private static volatile List<CredentialRegistryData.Country> countries = List.of();
    private static volatile List<CredentialRegistryData.Template> templates = List.of();
    private static volatile Runnable listener;
    private static volatile long lastRequest;

    private ClientRegistryCache() {
    }

    /** Replaces the cache with a fresh server snapshot. */
    public static void update(@Nullable CompoundTag snapshot) {
        List<CredentialRegistryData.Country> newCountries = new ArrayList<>();
        List<CredentialRegistryData.Template> newTemplates = new ArrayList<>();
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
        Runnable callback = listener;
        if (callback != null) {
            callback.run();
        }
    }

    public static List<CredentialRegistryData.Country> countries() {
        return countries;
    }

    public static List<CredentialRegistryData.Template> templates() {
        return templates;
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
