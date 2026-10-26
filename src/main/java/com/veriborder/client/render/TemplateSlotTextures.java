package com.veriborder.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.veriborder.client.ClientRegistryCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 26.4.1 upload side of {@link TemplateSlots}: the eight dynamic textures that
 * carry the players' custom cover base maps.
 *
 * <p>The registry ships each base map as {@code hash -> base64 PNG} inside the
 * sync snapshot. They are decoded into the pre-registered
 * {@code veriborder:dynamic/template_N} textures, nearest-neighbour scaled up to
 * the 64x64 quarter-canvas the item quad samples and uploaded once per changed
 * hash, so re-opening a screen or re-entering the world costs nothing.</p>
 *
 * <p>Lifecycle, matching the client lifecycle rather than the item's:</p>
 * <ul>
 *   <li><b>snapshot</b> -> {@link #update(CompoundTag)}: rebind the slots, then
 *       upload only the hashes whose slot changed (an unchanged map is not
 *       re-uploaded, so a periodic snapshot does not stutter),</li>
 *   <li><b>resource reload</b> -> {@link #refresh()}: re-register and re-upload
 *       from the last known payload, because a reload drops every texture the mod
 *       registered by hand,</li>
 *   <li><b>logout</b> -> {@link #release()}: release the GL textures and forget the
 *       payload so nothing of the previous server survives into the next one.</li>
 * </ul>
 *
 * <p>Uploaded pixel data is hardened to a binary alpha (and transparent pixels are
 * zeroed) exactly like the shipped item art, so an uploaded picture cannot make a
 * card look semi-transparent or bleed a dark halo under a shader pack.</p>
 */
public final class TemplateSlotTextures {
    /** Side length of the dynamic textures (the item quad is 64x64 art upscaled 4x). */
    public static final int SIZE = 64;

    private static final DynamicTexture[] TEXTURES = new DynamicTexture[TemplateSlots.CAPACITY];
    /** Hash currently uploaded into each slot ({@code null} = empty texture). */
    private static final String[] UPLOADED = new String[TemplateSlots.CAPACITY];
    /** Base maps of the last snapshot, in snapshot order. */
    private static final Map<String, String> PAYLOAD = new LinkedHashMap<>();

    private TemplateSlotTextures() {
    }

    /** Rebinds the slots to a new registry snapshot and uploads what changed. */
    public static void update(@Nullable CompoundTag snapshot) {
        PAYLOAD.clear();
        for (ClientRegistryCache.Texture texture : ClientRegistryCache.texturesOf(snapshot)) {
            if (texture == null || texture.hash().isBlank() || texture.data().isBlank()) {
                continue;
            }
            PAYLOAD.putIfAbsent(TemplateSlots.normalize(texture.hash()), texture.data());
        }
        TemplateSlots.rebuild(PAYLOAD.keySet());
        apply();
    }

    /** Re-applies the last payload (after a resource reload dropped the textures). */
    public static void refresh() {
        apply();
    }

    /** Releases every dynamic texture and forgets the payload (logout). */
    public static void release() {
        for (int slot = 0; slot < TemplateSlots.CAPACITY; slot++) {
            DynamicTexture texture = TEXTURES[slot];
            TEXTURES[slot] = null;
            UPLOADED[slot] = null;
            if (texture != null) {
                Minecraft.getInstance().getTextureManager().release(TemplateSlots.textureId(slot));
            }
        }
        PAYLOAD.clear();
        TemplateSlots.clear();
    }

    /** Whether a slot currently holds an uploaded base map. */
    public static boolean ready(int slot) {
        return slot >= 0 && slot < TemplateSlots.CAPACITY && UPLOADED[slot] != null;
    }

    /** Hash uploaded into a slot, {@code null} when empty. */
    @Nullable
    public static String uploadedHash(int slot) {
        return slot < 0 || slot >= TemplateSlots.CAPACITY ? null : UPLOADED[slot];
    }

    /** Base maps of the last snapshot (hash -> base64), read-only view for tests. */
    public static Map<String, String> payload() {
        return Map.copyOf(PAYLOAD);
    }

    // ------------------------------------------------------------------ upload

    private static void apply() {
        for (int slot = 0; slot < TemplateSlots.CAPACITY; slot++) {
            String hash = TemplateSlots.hashOf(slot);
            String data = hash == null ? null : PAYLOAD.get(hash);
            if (data == null) {
                if (UPLOADED[slot] != null) {
                    clearSlot(slot);
                    UPLOADED[slot] = null;
                }
                continue;
            }
            if (hash.equals(UPLOADED[slot])) {
                continue;
            }
            if (upload(slot, data)) {
                UPLOADED[slot] = hash;
            } else {
                clearSlot(slot);
                UPLOADED[slot] = null;
            }
        }
    }

    /**
     * Decodes one base64 PNG into a slot.
     *
     * @return whether the picture was usable (a corrupt or oversized payload keeps
     *         the printed cover instead of painting garbage)
     */
    private static boolean upload(int slot, String base64) {
        NativeImage source = null;
        try {
            byte[] png = Base64.getDecoder().decode(stripDataUri(base64));
            source = NativeImage.read(png);
            int width = source.getWidth();
            int height = source.getHeight();
            if (width <= 0 || height <= 0 || width > SIZE || height > SIZE) {
                return false;
            }
            DynamicTexture texture = texture(slot);
            NativeImage target = texture.getPixels();
            for (int y = 0; y < SIZE; y++) {
                int sourceY = Math.min(height - 1, y * height / SIZE);
                for (int x = 0; x < SIZE; x++) {
                    int sourceX = Math.min(width - 1, x * width / SIZE);
                    target.setPixelRGBA(x, y, harden(source.getPixelRGBA(sourceX, sourceY)));
                }
            }
            texture.upload();
            return true;
        } catch (IOException | RuntimeException failure) {
            return false;
        } finally {
            if (source != null) {
                source.close();
            }
        }
    }

    /** Binary alpha: opaque stays opaque, everything else becomes clean transparent. */
    static int harden(int pixel) {
        int alpha = (pixel >>> 24) & 0xFF;
        return alpha >= 128 ? (0xFF000000 | (pixel & 0x00FFFFFF)) : 0;
    }

    private static String stripDataUri(String data) {
        String payload = data == null ? "" : data.trim();
        int comma = payload.indexOf(',');
        if (comma > 0 && payload.startsWith("data:")) {
            return payload.substring(comma + 1);
        }
        return payload;
    }

    private static void clearSlot(int slot) {
        if (TEXTURES[slot] == null) {
            return;
        }
        NativeImage pixels = TEXTURES[slot].getPixels();
        pixels.fillRect(0, 0, SIZE, SIZE, 0);
        TEXTURES[slot].upload();
    }

    /** Registers the slot texture on first use (idempotent). */
    private static DynamicTexture texture(int slot) {
        DynamicTexture texture = TEXTURES[slot];
        if (texture == null) {
            texture = new DynamicTexture(SIZE, SIZE, true);
            Minecraft.getInstance().getTextureManager().register(TemplateSlots.textureId(slot), texture);
            TEXTURES[slot] = texture;
        }
        return texture;
    }
}
