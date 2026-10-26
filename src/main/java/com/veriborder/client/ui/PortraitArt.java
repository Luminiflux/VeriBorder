package com.veriborder.client.ui;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import com.veriborder.client.ClientRegistryCache;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.PortraitLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 26.4.1 document portrait rendering.
 *
 * <p>Three sources, one look:</p>
 * <ul>
 *   <li><b>preset</b> 鈥?{@code textures/portrait/p00.png} 鈥?{@code p23.png}, the
 *       shipped 32脳32 pixel art, upscaled with nearest neighbour to the photo
 *       box;</li>
 *   <li><b>skin</b> 鈥?the holder's own player skin head (the {@code 8,8} face of
 *       the 64脳64 skin), upscaled to the box. The UUID is resolved through the
 *       player in the current level first and the local skin cache second, so an
 *       offline client still shows the right face;</li>
 *   <li><b>custom</b> 鈥?an uploaded PNG from the registry snapshot, uploaded into
 *       one of {@value #SLOTS} dynamic texture slots. Slots are rebuilt when the
 *       snapshot changes ({@link ClientRegistryCache#revision()}), evicted least
 *       recently used first and released on logout.</li>
 * </ul>
 *
 * <p>Every source is finished identically: a 2-pixel rounded crop, a 1-pixel inner
 * shadow and the bottom {@value #BOTTOM_SHADE_PERCENT}% darkened, so a photo never
 * looks pasted onto the card. The fallback chain itself (custom 鈫?skin 鈫?p00)
 * lives in {@link PortraitLibrary#resolve} and is unit-tested there.</p>
 */
public final class PortraitArt {
    /** Dynamic texture slots available for uploaded portraits (26.4.1). */
    public static final int SLOTS = 16;
    /** Corner rounding of the photo crop, in design pixels. */
    public static final int ROUNDING = 2;
    /** Bottom band darkened to {@value}% of the photo height. */
    public static final int BOTTOM_SHADE_PERCENT = 8;
    /** Head face offset inside a 64脳64 player skin. */
    public static final int SKIN_HEAD_U = 8;
    public static final int SKIN_HEAD_V = 8;
    /** The head face is 8脳8 texels of the 64脳64 skin. */
    /** Head overlay (hat) layer, where hair fringes and accessories live. */
    public static final int SKIN_HAT_U = 40;
    public static final int SKIN_HEAD_SIZE = 8;
    public static final int SKIN_TEXTURE_SIZE = 64;
    /** Edge length of the shipped preset images (32脳32 pixel art, upscaled). */
    public static final int PRESET_PIXELS = PortraitLibrary.PRESET_SOURCE_EDGE;
    /** Hash characters shown in a {@code custom} label ("custom 3f2a1b"). */
    public static final int SHORT_HASH = 6;
    /** Faint wash that softens the nearest-neighbour upscale of a skin face. */
    private static final int SOFT_LIGHT = 0x12FFFFFF;
    private static final int SOFT_DARK = 0x16000000;

    private static final String MOD_ID = "veriborder";

    /** One registered upload: its texture and the edge length of that image. */
    private record Slot(ResourceLocation location, int edge) {
    }

    /** Live upload slots, keyed by hash, in least-recently-used order. */
    private static final Map<String, Slot> SLOTS_BY_HASH = new LinkedHashMap<>(SLOTS, 0.75F, true);
    /** Registry generation the slots were built from ({@code -1} = empty). */
    private static long slotsRevision = -1;

    private PortraitArt() {
    }

    /**
     * Draws the portrait of {@code doc} into the rectangle a caller reserved.
     *
     * @param frameColor colour the caller framed the photo with; the rounded
     *                   corner pixels are given back to it, which is what makes
     *                   the crop read as rounded on a card
     * @return {@code true} when a real portrait was drawn (the caller then skips
     *         its own placeholder artwork), {@code false} when nothing could be
     *         resolved and the placeholder should stay
     */
    public static boolean draw(GuiGraphics graphics, int x, int y, int width, int height,
                               @Nullable DocumentData doc, int frameColor) {
        if (doc == null || width <= 0 || height <= 0) {
            return false;
        }
        String id = doc.portrait();
        String owner = doc.portraitOwner();
        Slot custom = customSlot(id);
        ResourceLocation skin = skinTexture(owner);
        String resolved = PortraitLibrary.resolve(id, custom != null, skin != null);
        // 26.2.1: a passport photo is square. Callers reserve a portrait box (a wide
        // one on the passport page), and drawing straight into it squashed the face
        // horizontally. Draw a centred square and let the card colour fill the rest.
        int side = Math.min(width, height);
        int boxX = x + (width - side) / 2;
        int boxY = y + (height - side) / 2;
        boolean drawn;
        if (PortraitLibrary.isCustom(resolved)) {
            Slot slot = custom != null ? custom : customSlot(resolved);
            drawn = blit(graphics, boxX, boxY, side, side, slot);
        } else if (PortraitLibrary.DEFAULT_ID.equals(resolved)) {
            drawn = blitSkin(graphics, boxX, boxY, side, side, skin);
        } else {
            drawn = blitPreset(graphics, boxX, boxY, side, side, resolved);
        }
        if (!drawn) {
            return false;
        }
        finish(graphics, boxX, boxY, side, side, frameColor);
        return true;
    }

    /** Convenience overload for callers that frame the photo in the card tone. */
    public static boolean draw(GuiGraphics graphics, int x, int y, int width, int height,
                               @Nullable DocumentData doc) {
        return draw(graphics, x, y, width, height, doc, 0xFF263B4D);
    }

    // ------------------------------------------------------------------ source

    /**
     * Portrait reference that is actually drawn for this document, following the
     * documented chain <b>custom 鈫?skin 鈫?p00</b>.
     *
     * <p>Never blank: a document whose {@code skin} reference cannot be resolved on
     * this client (an offline owner, a player who never joined, a malformed UUID)
     * falls back to the {@code p00} preset instead of leaving the photo box empty.
     * The preview label and the renderer therefore always agree.</p>
     */
    public static String resolvedId(@Nullable DocumentData doc) {
        if (doc == null) {
            return PortraitLibrary.FALLBACK_PRESET;
        }
        String id = doc.portrait();
        Slot custom = customSlot(id);
        ResourceLocation skin = skinTexture(doc.portraitOwner());
        return PortraitLibrary.resolve(id, custom != null, skin != null);
    }

    /**
     * Where the drawn portrait comes from, as the short label the issuing machine
     * prints next to its preview: "player skin", {@code P07}, or
     * {@code custom 3f2a1b}.
     */
    public static String sourceLabel(@Nullable DocumentData doc) {
        return label(resolvedId(doc));
    }

    /**
     * Short label of one portrait reference. Shared by the picker (so an option
     * reads exactly like the source line under the preview) and the source line.
     */
    public static String label(@Nullable String id) {
        String normalized = PortraitLibrary.normalizeId(id);
        if (PortraitLibrary.DEFAULT_ID.equals(normalized)) {
            return Component.translatable("portrait.veriborder.skin").getString();
        }
        if (PortraitLibrary.isCustom(normalized)) {
            String hash = PortraitLibrary.hashOfId(normalized);
            return "custom " + (hash.length() > SHORT_HASH ? hash.substring(0, SHORT_HASH) : hash);
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ sources

    /** Shipped preset image, nearest-neighbour upscaled to the box. */
    private static boolean blitPreset(GuiGraphics graphics, int x, int y, int width, int height,
                                      String presetId) {
        ResourceLocation texture = presetTexture(presetId);
        if (texture == null) {
            return false;
        }
        graphics.blit(texture, x, y, width, height, 0.0F, 0.0F,
                PRESET_PIXELS, PRESET_PIXELS, PRESET_PIXELS, PRESET_PIXELS);
        return true;
    }

    /** The 8脳8 head face of a 64脳64 skin, upscaled with a soft vignette. */
    private static boolean blitSkin(GuiGraphics graphics, int x, int y, int width, int height,
                                    @Nullable ResourceLocation skin) {
        if (skin == null) {
            return false;
        }
        // Nearest neighbour: the blit samples texels, and skin textures are
        // registered without linear filtering, so an 8脳8 face stays crisp.
        graphics.blit(skin, x, y, width, height, (float) SKIN_HEAD_U, (float) SKIN_HEAD_V,
                SKIN_HEAD_SIZE, SKIN_HEAD_SIZE, SKIN_TEXTURE_SIZE, SKIN_TEXTURE_SIZE);
        // 26.2.1: the head overlay layer (hat/accessory) sits at u=40 and carries
        // hair fringes, glasses, masks and ears. Drawing the bare face lost all of
        // that, which is why the portrait read as a flat, low-detail face. The
        // overlay is alpha-blended, so a skin without one is unchanged.
        graphics.blit(skin, x, y, width, height, (float) SKIN_HAT_U, (float) SKIN_HEAD_V,
                SKIN_HEAD_SIZE, SKIN_HEAD_SIZE, SKIN_TEXTURE_SIZE, SKIN_TEXTURE_SIZE);
        soften(graphics, x, y, width, height);
        return true;
    }

    /** One uploaded PNG already registered in a dynamic texture slot. */
    private static boolean blit(GuiGraphics graphics, int x, int y, int width, int height,
                                @Nullable Slot slot) {
        if (slot == null) {
            return false;
        }
        int edge = slot.edge();
        graphics.blit(slot.location(), x, y, width, height, 0.0F, 0.0F, edge, edge, edge, edge);
        return true;
    }

    /**
     * Faint edge wash over a skin head. An 8脳8 face upscaled to a photo box has
     * very hard pixel steps; a light top/left and dark bottom/right band keeps the
     * character recognisable while reading as a printed photo.
     */
    private static void soften(GuiGraphics graphics, int x, int y, int width, int height) {
        int band = Math.max(1, Math.min(width, height) / 16);
        graphics.fill(x, y, x + width, y + band, SOFT_LIGHT);
        graphics.fill(x, y, x + band, y + height, SOFT_LIGHT);
        graphics.fill(x + width - band, y, x + width, y + height, SOFT_DARK);
        graphics.fill(x, y + height - band, x + width, y + height, SOFT_DARK);
    }

    // ------------------------------------------------------------------ finish

    /** Rounded crop, inner shadow and the darkened bottom band. */
    private static void finish(GuiGraphics graphics, int x, int y, int width, int height, int frameColor) {
        // 1px inner shadow on all four edges: the photo sits *in* the card.
        graphics.fill(x, y, x + width, y + 1, 0x44000000);
        graphics.fill(x, y + height - 1, x + width, y + height, 0x44000000);
        graphics.fill(x, y, x + 1, y + height, 0x44000000);
        graphics.fill(x + width - 1, y, x + width, y + height, 0x44000000);
        // Bottom band: an 8% shade so a bright photo does not fight the caption.
        int band = Math.max(1, Math.round(height * BOTTOM_SHADE_PERCENT / 100.0F));
        graphics.fill(x + 1, y + height - 1 - band, x + width - 1, y + height - 1, 0x33000000);
        roundCorners(graphics, x, y, width, height, frameColor);
    }

    /** Gives the four 2-px corner steps back to the frame: the rounded crop. */
    private static void roundCorners(GuiGraphics graphics, int x, int y, int width, int height, int frameColor) {
        if (width <= ROUNDING * 2 || height <= ROUNDING * 2) {
            return;
        }
        int radius = Math.min(ROUNDING, Math.min(width, height) / 2);
        for (int step = 0; step < radius; step++) {
            int inset = radius - step;
            // Top-left, top-right, bottom-left, bottom-right: one step each.
            graphics.fill(x, y + step, x + inset, y + step + 1, frameColor);
            graphics.fill(x + width - inset, y + step, x + width, y + step + 1, frameColor);
            graphics.fill(x, y + height - 1 - step, x + inset, y + height - step, frameColor);
            graphics.fill(x + width - inset, y + height - 1 - step, x + width, y + height - step, frameColor);
        }
    }

    // ----------------------------------------------------------------- presets

    /** Resource location of a preset portrait, or {@code null} for a non-preset. */
    @Nullable
    public static ResourceLocation presetTexture(@Nullable String id) {
        String path = PortraitLibrary.resourcePath(id);
        return path.isEmpty() ? null : new ResourceLocation(MOD_ID, path);
    }

    // ------------------------------------------------------------------- slots

    /**
     * The dynamic texture slot of an uploaded portrait, registering it on first
     * use. Returns {@code null} when the id is not a custom portrait, the snapshot
     * does not carry the image (budget) or the payload cannot be decoded.
     */
    @Nullable
    private static Slot customSlot(@Nullable String id) {
        if (!PortraitLibrary.isCustom(id)) {
            return null;
        }
        ensureSlots();
        String hash = PortraitLibrary.hashOfId(id);
        Slot existing = SLOTS_BY_HASH.get(hash);
        return existing != null ? existing : register(hash);
    }

    /** Drops the slots the current snapshot no longer publishes (rebuild step). */
    private static void ensureSlots() {
        long revision = ClientRegistryCache.revision();
        if (revision == slotsRevision) {
            return;
        }
        slotsRevision = revision;
        Set<String> live = new HashSet<>();
        for (ClientRegistryCache.Portrait portrait : ClientRegistryCache.portraits()) {
            if (portrait.hasData()) {
                live.add(portrait.hash());
            }
        }
        Iterator<Map.Entry<String, Slot>> iterator = SLOTS_BY_HASH.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Slot> entry = iterator.next();
            if (live.contains(entry.getKey())) {
                continue;
            }
            release(entry.getValue());
            iterator.remove();
        }
    }

    /** Uploads one snapshot payload into a free slot (least recently used first). */
    @Nullable
    private static Slot register(String hash) {
        ClientRegistryCache.Portrait portrait = ClientRegistryCache.portrait(hash);
        if (portrait == null || !portrait.hasData()) {
            return null;
        }
        byte[] png = decode(portrait.data());
        int[] size = PortraitLibrary.pngSize(png);
        // The server validated the payload before it was stored; re-check on the
        // client so a foreign/older server can never make the renderer allocate a
        // huge image.
        if (size == null || size[0] != size[1] || size[0] > PortraitLibrary.MAX_EDGE) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }
        NativeImage image;
        try {
            image = NativeImage.read(png);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
        while (SLOTS_BY_HASH.size() >= SLOTS) {
            Iterator<Map.Entry<String, Slot>> iterator = SLOTS_BY_HASH.entrySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            Map.Entry<String, Slot> eldest = iterator.next();
            release(eldest.getValue());
            iterator.remove();
        }
        DynamicTexture texture = new DynamicTexture(image);
        ResourceLocation location = new ResourceLocation(MOD_ID, "portrait/" + hash);
        minecraft.getTextureManager().register(location, texture);
        Slot slot = new Slot(location, size[0]);
        SLOTS_BY_HASH.put(hash, slot);
        return slot;
    }

    /**
     * Releases every dynamic slot (called on logout, so a disconnected client does
     * not keep up to {@value #SLOTS} uploaded images resident).
     */
    public static void releaseAll() {
        for (Slot slot : SLOTS_BY_HASH.values()) {
            release(slot);
        }
        SLOTS_BY_HASH.clear();
        slotsRevision = -1;
    }

    /** Number of slots currently registered (diagnose / tests). */
    public static int slotCount() {
        return SLOTS_BY_HASH.size();
    }

    private static void release(@Nullable Slot slot) {
        if (slot == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        TextureManager manager = minecraft.getTextureManager();
        if (manager == null) {
            return;
        }
        try {
            manager.release(slot.location());
        } catch (RuntimeException ignored) {
            // A texture that was already dropped is not an error.
        }
    }

    @Nullable
    private static byte[] decode(@Nullable String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    // -------------------------------------------------------------------- skin

    /**
     * Skin texture of the portrait owner: the player in the current level first
     * (an online skin), the local skin cache second (offline, or a player who is
     * not in this world).
     */
    @Nullable
    private static ResourceLocation skinTexture(@Nullable String ownerUuid) {
        String owner = PortraitLibrary.normalizeOwner(ownerUuid);
        if (owner.isEmpty()) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(owner);
        } catch (IllegalArgumentException failure) {
            return null;
        }
        try {
            if (minecraft.level != null) {
                Player player = minecraft.level.getPlayerByUUID(uuid);
                if (player instanceof AbstractClientPlayer client) {
                    ResourceLocation location = client.getSkinTextureLocation();
                    if (location != null) {
                        return location;
                    }
                }
            }
            if (minecraft.getSkinManager() != null) {
                ResourceLocation location =
                        minecraft.getSkinManager().getInsecureSkinLocation(new GameProfile(uuid, ""));
                if (location != null) {
                    return location;
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }
}

