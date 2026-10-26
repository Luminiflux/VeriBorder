package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 26.4.1 portrait library: the portraits a document can carry.
 *
 * <p>A document does not store the image itself. Its signed {@code portrait}
 * field holds one identifier in three shapes:</p>
 *
 * <ul>
 *   <li>{@code p00}..{@code p23} — one of the 24 built-in presets shipped as
 *       {@value #PRESET_SOURCE_EDGE}×{@value #PRESET_SOURCE_EDGE} pixel art at
 *       {@code textures/portrait/<id>.png}, upscaled to the photo box;</li>
 *   <li>{@code skin} — the holder's own player skin head (the default);</li>
 *   <li>{@code custom:<hash>} — one uploaded PNG, held by this library and
 *       addressed by the SHA-256 of its bytes.</li>
 * </ul>
 *
 * <p>The library is world-scoped and travels to every client inside the
 * credential-registry snapshot ({@code portraits: [{hash, data(base64)}]}), which
 * is why an uploaded photo needs no extra packet and no extra file: the registry
 * {@link net.minecraft.world.level.saveddata.SavedData} owns one instance and
 * persists it next to its countries and templates.</p>
 *
 * <p>Every limit is enforced here, on the server, so a forged upload packet can
 * neither grow the save file nor smuggle a non-PNG payload into the client
 * renderer: a real PNG signature, a square image with an edge of
 * {@value #MIN_EDGE} or {@value #MAX_EDGE} pixels, at most {@value #MAX_BYTES}
 * bytes and at most {@value #MAX_CUSTOM} stored portraits. A refusal always
 * carries a concrete reason key ({@code message.veriborder.portrait.*}) — nothing
 * is ever dropped silently.</p>
 */
public final class PortraitLibrary {
    /** NBT key of the portrait list, in the save file and in the wire snapshot. */
    public static final String KEY = "portraits";
    /** Identifier used when a document carries no portrait (the player skin). */
    public static final String DEFAULT_ID = "skin";
    /** Prefix of an uploaded portrait identifier. */
    public static final String CUSTOM_PREFIX = "custom:";
    /** Built-in preset count ({@code p00}..{@code p23}). */
    public static final int PRESET_COUNT = 24;
    /** Preset drawn when nothing else can be resolved. */
    public static final String FALLBACK_PRESET = "p00";
    /** Resource folder of the preset PNGs. */
    public static final String RESOURCE_FOLDER = "textures/portrait/";
    /**
     * Edge length of the shipped preset images: the presets are 32×32 pixel art
     * (one-pixel outline, shared 28-colour palette) drawn into a 64×64 photo box
     * with nearest-neighbour upscaling. The renderer needs this number to sample
     * the whole image instead of a corner, so the asset contract lives here and
     * {@code tools/check_portrait_textures.ps1} audits the files against it.
     */
    public static final int PRESET_SOURCE_EDGE = 32;

    /** One uploaded portrait: at most 16 KiB of PNG. */
    public static final int MAX_BYTES = 16 * 1024;
    /** Uploaded portraits must be square with this edge length … */
    public static final int MIN_EDGE = 32;
    /** … or this one. */
    public static final int MAX_EDGE = 64;
    /** At most this many distinct uploads live in the library. */
    public static final int MAX_CUSTOM = 32;
    /**
     * Snapshot budget of the base64 payload. The save file always keeps every
     * portrait; only the wire snapshot stops adding entries once the budget is
     * spent, so a full library can never turn one registry sync into a huge
     * packet.
     */
    public static final int MAX_SNAPSHOT_CHARS = 256 * 1024;
    /** Portrait hashes are lowercase hex, bounded like a digest. */
    public static final int MAX_HASH_LENGTH = 64;

    private static final List<String> PRESETS = buildPresets();

    /** Uploaded portraits, keyed by their lowercase hex SHA-256. */
    private final Map<String, byte[]> custom = new LinkedHashMap<>();

    private static List<String> buildPresets() {
        List<String> ids = new ArrayList<>(PRESET_COUNT);
        for (int index = 0; index < PRESET_COUNT; index++) {
            ids.add(String.format(Locale.ROOT, "p%02d", index));
        }
        return List.copyOf(ids);
    }

    // ------------------------------------------------------------------- ids

    /** The 24 built-in preset ids, in registration order. */
    public static List<String> presets() {
        return PRESETS;
    }

    /** Preset id of one slot, clamped into {@code p00..p23}. */
    public static String presetId(int index) {
        int slot = Math.max(0, Math.min(PRESET_COUNT - 1, index));
        return PRESETS.get(slot);
    }

    /** Position of a preset id, or {@code -1} when it is not one. */
    public static int presetIndex(@Nullable String id) {
        return PRESETS.indexOf(id == null ? "" : id.trim().toLowerCase(Locale.ROOT));
    }

    /** Whether {@code id} is one of the 24 built-in presets. */
    public static boolean isPreset(@Nullable String id) {
        return presetIndex(id) >= 0;
    }

    /** {@code custom:<hash>} for a hash, or {@code ""} when the hash is unusable. */
    public static String customId(@Nullable String hash) {
        String normalized = normalizeHash(hash);
        return normalized.isEmpty() ? "" : CUSTOM_PREFIX + normalized;
    }

    /** Whether {@code id} addresses an uploaded portrait. */
    public static boolean isCustom(@Nullable String id) {
        String value = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return value.startsWith(CUSTOM_PREFIX)
                && !normalizeHash(value.substring(CUSTOM_PREFIX.length())).isEmpty();
    }

    /** Hash behind a {@code custom:<hash>} id, {@code ""} for presets and skins. */
    public static String hashOfId(@Nullable String id) {
        if (!isCustom(id)) {
            return "";
        }
        return normalizeHash(id.trim().substring(CUSTOM_PREFIX.length()));
    }

    /**
     * A well-formed portrait id. Blank, unknown and malformed values become
     * {@link #DEFAULT_ID}: an old document without the key, a hand-edited tag and
     * a deleted preset all read as "player skin" instead of drawing nothing.
     */
    public static String normalizeId(@Nullable String id) {
        String value = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return DEFAULT_ID;
        }
        if (DEFAULT_ID.equals(value) || isPreset(value)) {
            return value;
        }
        String customId = customId(hashOfRaw(value));
        return customId.isEmpty() ? DEFAULT_ID : customId;
    }

    /**
     * A portrait owner UUID in its canonical lowercase form, or {@code ""}.
     *
     * <p>{@code UUID.fromString} accepts shortened groups, so the shape is checked
     * explicitly: a document must never carry a value that stops the skin lookup
     * (or the "who uploaded this" record) from being unambiguous. A blank or
     * malformed owner is simply no owner, and {@code skin} then falls back to the
     * first preset.</p>
     */
    public static String normalizeOwner(@Nullable String uuid) {
        if (uuid == null) {
            return "";
        }
        String value = uuid.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            return "";
        }
        return value;
    }

    /** Hash part of an id that may or may not carry the {@code custom:} prefix. */
    private static String hashOfRaw(String value) {
        String trimmed = value.startsWith(CUSTOM_PREFIX)
                ? value.substring(CUSTOM_PREFIX.length()) : value;
        return normalizeHash(trimmed);
    }

    /** Mod resource path of a preset, {@code ""} when {@code id} is not one. */
    public static String resourcePath(@Nullable String id) {
        return isPreset(id) ? RESOURCE_FOLDER + normalizeId(id) + ".png" : "";
    }

    /**
     * The identifier that should actually be drawn, following the documented
     * fallback chain <b>custom → skin → p00</b>.
     *
     * @param id              the signed portrait id of a document
     * @param customAvailable whether this client holds the uploaded image
     * @param skinAvailable   whether the holder skin could be resolved
     */
    public static String resolve(@Nullable String id, boolean customAvailable, boolean skinAvailable) {
        String normalized = normalizeId(id);
        if (isCustom(normalized)) {
            if (customAvailable) {
                return normalized;
            }
            return skinAvailable ? DEFAULT_ID : FALLBACK_PRESET;
        }
        if (DEFAULT_ID.equals(normalized) && !skinAvailable) {
            return FALLBACK_PRESET;
        }
        return normalized;
    }

    // ------------------------------------------------------------------ store

    /** Number of uploaded portraits currently stored. */
    public int size() {
        return custom.size();
    }

    /** Stored hashes, upload order. */
    public List<String> hashes() {
        return new ArrayList<>(custom.keySet());
    }

    /** Stored ids ({@code custom:<hash>}), upload order. */
    public List<String> ids() {
        List<String> result = new ArrayList<>(custom.size());
        for (String hash : custom.keySet()) {
            result.add(CUSTOM_PREFIX + hash);
        }
        return result;
    }

    /** Whether this library holds the upload behind {@code id} (or a bare hash). */
    public boolean has(@Nullable String id) {
        String hash = isCustom(id) ? hashOfId(id) : normalizeHash(id);
        return !hash.isEmpty() && custom.containsKey(hash);
    }

    /**
     * The PNG behind {@code id} (or a bare hash), or {@code null}.
     *
     * <p>A copy is returned: the caller can never mutate the stored payload.</p>
     */
    @Nullable
    public byte[] portrait(@Nullable String id) {
        String hash = isCustom(id) ? hashOfId(id) : normalizeHash(id);
        byte[] png = custom.get(hash);
        return png == null ? null : png.clone();
    }

    /**
     * Stores one uploaded portrait, deriving its address from its own bytes.
     *
     * @return the concrete outcome; a duplicate counts as stored (it is already
     *         there), so uploading the same photo twice is a no-op
     */
    public PortraitResult put(@Nullable byte[] png) {
        return putChecked(null, png);
    }

    /** {@link #put(byte[])} with the raw refusal reason, used by the load path. */
    public PortraitResult putChecked(@Nullable String hash, @Nullable byte[] png) {
        if (png == null || png.length == 0) {
            return new PortraitResult(false, PortraitRejection.EMPTY, "");
        }
        if (!hasPngSignature(png)) {
            return new PortraitResult(false, PortraitRejection.NOT_PNG, normalizeHash(hash));
        }
        int[] size = pngSize(png);
        if (size == null) {
            return new PortraitResult(false, PortraitRejection.NOT_PNG, normalizeHash(hash));
        }
        String normalized = normalizeHash(hash);
        if (png.length > MAX_BYTES) {
            return new PortraitResult(false, PortraitRejection.TOO_LARGE, normalized);
        }
        if (size[0] != size[1] || (size[0] != MIN_EDGE && size[0] != MAX_EDGE)) {
            return new PortraitResult(false, PortraitRejection.BAD_SIZE, normalized);
        }
        if (normalized.isEmpty()) {
            normalized = hashOf(png);
        }
        if (normalized.isEmpty()) {
            return new PortraitResult(false, PortraitRejection.BAD_HASH, "");
        }
        if (custom.containsKey(normalized)) {
            return new PortraitResult(true, PortraitRejection.NONE, normalized);
        }
        if (custom.size() >= MAX_CUSTOM) {
            return new PortraitResult(false, PortraitRejection.TOO_MANY, normalized);
        }
        custom.put(normalized, png.clone());
        return new PortraitResult(true, PortraitRejection.NONE, normalized);
    }

    /** Removes one upload by id or bare hash; returns whether it was stored. */
    public boolean remove(@Nullable String id) {
        String hash = isCustom(id) ? hashOfId(id) : normalizeHash(id);
        return hash != null && !hash.isEmpty() && custom.remove(hash) != null;
    }

    /**
     * Drops every upload {@code keep} does not accept (the "free the slots" step
     * an administrator runs after the documents that used a portrait are gone).
     *
     * @return how many uploads were released
     */
    public int releaseUnused(Predicate<String> keep) {
        if (keep == null) {
            return 0;
        }
        int before = custom.size();
        custom.keySet().removeIf(hash -> !keep.test(CUSTOM_PREFIX + hash));
        return before - custom.size();
    }

    /** Drops every upload. */
    public void clear() {
        custom.clear();
    }

    // ------------------------------------------------------------------- nbt

    /**
     * Reads the portrait list of a saved compound. A row is re-validated on the
     * way in, so a hand-edited save file cannot load an oversized or non-PNG
     * payload back into the library.
     */
    public void readFrom(@Nullable CompoundTag parent) {
        custom.clear();
        if (parent == null) {
            return;
        }
        ListTag list = parent.getList(KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && custom.size() < MAX_CUSTOM; i++) {
            CompoundTag row = list.getCompound(i);
            byte[] png = decodeBase64(row.getString("data"));
            if (png != null) {
                putChecked(row.getString("hash"), png);
            }
        }
    }

    /** Writes every upload into a save compound (never budgeted). */
    public void writeTo(CompoundTag parent) {
        if (parent == null) {
            return;
        }
        parent.put(KEY, rows(Integer.MAX_VALUE));
    }

    /** {@code {portraits: [{hash, data}]}} for the registry snapshot packet. */
    public CompoundTag snapshot() {
        CompoundTag tag = new CompoundTag();
        tag.put(KEY, rows(MAX_SNAPSHOT_CHARS));
        return tag;
    }

    /**
     * The portrait rows as {@code {hash, data(base64)}}. With a positive budget the
     * list stops before an entry would push the payload over it — the save file
     * keeps the rest, the snapshot simply does not carry them.
     */
    private ListTag rows(int budgetChars) {
        ListTag list = new ListTag();
        int chars = 0;
        for (Map.Entry<String, byte[]> entry : custom.entrySet()) {
            String base64 = Base64.getEncoder().encodeToString(entry.getValue());
            if (chars + base64.length() > budgetChars) {
                break;
            }
            chars += base64.length();
            CompoundTag row = new CompoundTag();
            row.putString("hash", entry.getKey());
            row.putString("data", base64);
            list.add(row);
        }
        return list;
    }

    @Nullable
    private static byte[] decodeBase64(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    // --------------------------------------------------------------- png util

    /** PNG signature check: {@code 89 50 4E 47 0D 0A 1A 0A}. */
    public static boolean hasPngSignature(@Nullable byte[] png) {
        if (png == null || png.length < 8) {
            return false;
        }
        return (png[0] & 0xFF) == 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G'
                && png[4] == 0x0D && png[5] == 0x0A && png[6] == 0x1A && png[7] == 0x0A;
    }

    /**
     * Width and height of a PNG, read straight out of the IHDR chunk, or
     * {@code null} when the bytes are not a PNG.
     *
     * <p>Deliberately no image decoding: a hostile payload must never reach a
     * decoder just to be refused, and this stays a pure function a test can run
     * headless.</p>
     */
    @Nullable
    public static int[] pngSize(@Nullable byte[] png) {
        if (png == null || png.length < 24 || !hasPngSignature(png)) {
            return null;
        }
        if (png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') {
            return null;
        }
        int width = intAt(png, 16);
        int height = intAt(png, 20);
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new int[]{width, height};
    }

    private static int intAt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    /** SHA-256 of a payload as lowercase hex (a portrait's address). */
    public static String hashOf(@Nullable byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** Lowercase hex hash, at most {@link #MAX_HASH_LENGTH} characters; else {@code ""}. */
    public static String normalizeHash(@Nullable String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim().toLowerCase(Locale.ROOT);
        if (cleaned.isEmpty() || cleaned.length() > MAX_HASH_LENGTH || !cleaned.matches("[0-9a-f]+")) {
            return "";
        }
        return cleaned;
    }

    // --------------------------------------------------------------- results

    /** Why an uploaded portrait was refused. */
    public enum PortraitRejection {
        /** Accepted. */
        NONE(""),
        EMPTY("empty"),
        NOT_PNG("not_png"),
        TOO_LARGE("too_large"),
        BAD_SIZE("bad_size"),
        TOO_MANY("too_many"),
        BAD_HASH("bad_hash");

        private final String id;

        PortraitRejection(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** Translation key of the refusal, rendered by the issuer / registry UI. */
        public String messageKey() {
            return "message.veriborder.portrait." + id;
        }
    }

    /**
     * Outcome of one upload.
     *
     * @param stored    whether the portrait is now in the library (a duplicate
     *                  counts as stored: it is already there)
     * @param rejection concrete refusal reason, {@link PortraitRejection#NONE} on success
     * @param hash      normalised hash of the stored (or offered) portrait
     */
    public record PortraitResult(boolean stored, PortraitRejection rejection, String hash) {
        public boolean ok() {
            return stored;
        }

        /** Translation key of the refusal, {@code ""} when the upload was accepted. */
        public String errorKey() {
            return rejection == null || rejection == PortraitRejection.NONE ? "" : rejection.messageKey();
        }

        /** The {@code custom:<hash>} identifier to write onto a document. */
        public String id() {
            return customId(hash);
        }
    }
}
