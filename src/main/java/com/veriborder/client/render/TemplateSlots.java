package com.veriborder.client.render;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 26.4.1 custom cover base maps: the eight dynamic slots.
 *
 * <p>An item model cannot sample a PNG that does not exist when the resource pack
 * is loaded, so the client pre-registers a fixed pool of eight dynamic textures
 * ({@code veriborder:dynamic/template_0..7}) and assigns the base maps it receives
 * to them. The item model variant is chosen by the {@code veriborder:texslot}
 * predicate, whose value is the slot index ({@code -1} = "no base map", which
 * matches no override and therefore keeps the printed cover).</p>
 *
 * <p>This class is the pure allocator: no OpenGL, no Minecraft client state, so
 * the numbering rules are unit-tested directly.</p>
 *
 * <ul>
 *   <li><b>reuse</b>: the same hash always lands on the same slot, so two
 *       documents issued from one template share one upload,</li>
 *   <li><b>stability</b>: a rebuild keeps the slot of a hash that is still
 *       present and only moves hashes that appeared/disappeared, so a snapshot
 *       refresh does not re-upload everything,</li>
 *   <li><b>capacity</b>: at most {@link #CAPACITY} hashes are bound; the rest
 *       fall back to the printed cover (the server refuses to issue more than
 *       eight distinct maps in the first place),</li>
 *   <li><b>tolerance</b>: blank hashes are ignored, and hashes are compared
 *       case-insensitively after trimming (the registry stores lowercase hex).</li>
 * </ul>
 */
public final class TemplateSlots {
    /** Number of dynamic texture slots (= number of slot item models). */
    public static final int CAPACITY = 8;
    /** Predicate id the slot item models listen on. */
    public static final String PREDICATE = "texslot";
    /** Value the predicate reports when a document carries no usable base map. */
    public static final float NO_SLOT = -1.0F;
    /** Slot index meaning "no slot", i.e. what {@link #slotOf(String)} returns. */
    public static final int NO_SLOT_INDEX = -1;

    private static final String TEXTURE_PATH = "dynamic/template_";
    private static final String[] HASHES = new String[CAPACITY];

    private TemplateSlots() {
    }

    /** Resource id of one slot texture, e.g. {@code veriborder:dynamic/template_3}. */
    public static ResourceLocation textureId(int slot) {
        if (slot < 0 || slot >= CAPACITY) {
            throw new IllegalArgumentException("slot out of range: " + slot);
        }
        return new ResourceLocation("veriborder", TEXTURE_PATH + slot);
    }

    /**
     * Rebinds the pool to the base maps a snapshot carries (snapshot order).
     *
     * @param hashes base-map hashes; blanks and duplicates are ignored
     * @return the slot -> hash binding that is now active, compacted
     */
    public static Map<Integer, String> rebuild(Iterable<String> hashes) {
        Map<String, Integer> previous = new LinkedHashMap<>();
        for (int slot = 0; slot < CAPACITY; slot++) {
            if (HASHES[slot] != null) {
                previous.put(HASHES[slot], slot);
            }
        }
        Map<String, Integer> next = new LinkedHashMap<>();
        // 1) keep every hash that is still published on the slot it already owns
        for (String raw : hashes) {
            String hash = normalize(raw);
            if (hash.isEmpty() || next.containsKey(hash)) {
                continue;
            }
            Integer slot = previous.get(hash);
            if (slot != null && next.size() < CAPACITY) {
                next.put(hash, slot);
            }
        }
        // 2) new hashes take the lowest free slot, in snapshot order
        for (String raw : hashes) {
            String hash = normalize(raw);
            if (hash.isEmpty() || next.containsKey(hash) || next.size() >= CAPACITY) {
                continue;
            }
            boolean[] used = new boolean[CAPACITY];
            for (int slot : next.values()) {
                used[slot] = true;
            }
            for (int slot = 0; slot < CAPACITY; slot++) {
                if (!used[slot]) {
                    next.put(hash, slot);
                    break;
                }
            }
        }
        for (int slot = 0; slot < CAPACITY; slot++) {
            HASHES[slot] = null;
        }
        Map<Integer, String> bound = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : next.entrySet()) {
            HASHES[entry.getValue()] = entry.getKey();
            bound.put(entry.getValue(), entry.getKey());
        }
        return bound;
    }

    /** Slot a base-map hash is bound to, or {@link #NO_SLOT} when it has none. */
    public static int slotOf(@Nullable String hash) {
        String normalized = normalize(hash);
        if (normalized.isEmpty()) {
            return NO_SLOT_INDEX;
        }
        for (int slot = 0; slot < CAPACITY; slot++) {
            if (normalized.equals(HASHES[slot])) {
                return slot;
            }
        }
        return NO_SLOT_INDEX;
    }

    /** Hash bound to a slot ({@code null} when the slot is free). */
    @Nullable
    public static String hashOf(int slot) {
        return slot < 0 || slot >= CAPACITY ? null : HASHES[slot];
    }

    /** Item-model predicate value for a hash: the slot index, or {@link #NO_SLOT}. */
    public static float predicate(@Nullable String hash) {
        return slotOf(hash);
    }

    /** Bound slots in ascending order, e.g. for an upload pass. */
    public static List<Integer> boundSlots() {
        List<Integer> slots = new ArrayList<>(CAPACITY);
        for (int slot = 0; slot < CAPACITY; slot++) {
            if (HASHES[slot] != null) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /** Number of hashes currently bound (0..{@link #CAPACITY}). */
    public static int boundCount() {
        return boundSlots().size();
    }

    /** Drops every binding (logout, or a client that left the server). */
    public static void clear() {
        for (int slot = 0; slot < CAPACITY; slot++) {
            HASHES[slot] = null;
        }
    }

    /** Trims and lower-cases a hash; blank when there is nothing usable. */
    public static String normalize(@Nullable String hash) {
        return hash == null ? "" : hash.trim().toLowerCase(Locale.ROOT);
    }
}
