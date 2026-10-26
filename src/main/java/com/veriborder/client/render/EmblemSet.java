package com.veriborder.client.render;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 26.4.1 preset crests ("徽记") of a document cover.
 *
 * <p>The order of {@link #IDS} is the contract between three places that must not
 * drift apart:</p>
 * <ol>
 *   <li>{@code client/ui/IconPicker.ICONS} — the grid the registry editor draws
 *       and stores in a template,</li>
 *   <li>the item-model predicate {@code veriborder:emblem}, whose value is
 *       {@code index / }{@link #DIVISOR} ({@link #predicate(String)}),</li>
 *   <li>the {@code assets/veriborder/textures/item/emblem_<id>.png} layer-2 art
 *       painted by {@code tools/generate_document_layers.ps1}.</li>
 * </ol>
 *
 * <p>A document only stores the <em>id</em>, so an id written by an older build, a
 * typo or an imported snapshot must never make a cover blank: unknown and blank
 * values fall back to {@link #DEFAULT} (index 0), which is also the crest baked
 * into the base item model.</p>
 */
public final class EmblemSet {
    /**
     * The fifteen presets, in grid order. The first twelve are the documented
     * crest set (state emblem, shield, star, gear, globe, anchor, eagle, laurel,
     * seal, machine-readable, passport, diamond); the last three were already in
     * the registry's icon grid and are kept here so the picker and the cover
     * cannot disagree.
     */
    public static final List<String> IDS = List.of(
            "emblem", "star", "crown", "shield", "globe", "leaf", "anchor", "key",
            "stamp", "book", "diamond", "flame", "gear", "eagle", "mrz");

    /** Crest used when nothing (or nothing usable) is stored. */
    public static final String DEFAULT = "emblem";

    /**
     * Predicate denominator. Model overrides of two crests must differ by at least
     * {@code 1/16}, which is what the item-model numbers are published as.
     */
    public static final int DIVISOR = 16;

    /**
     * Spelling aliases: the documented crest names (including the Chinese set's
     * English readings) map onto the canonical ids, so a hand-written NBT tag or a
     * third-party resource pack still shows a crest instead of the default.
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("laurel", "leaf"),
            Map.entry("olive", "leaf"),
            Map.entry("seal", "stamp"),
            Map.entry("passport", "book"),
            Map.entry("machine_readable", "mrz"),
            Map.entry("machine-readable", "mrz"),
            Map.entry("state_emblem", "emblem"),
            Map.entry("national_emblem", "emblem"),
            Map.entry("emblem_default", "emblem"),
            Map.entry("crest", "emblem"),
            Map.entry("cog", "gear"),
            Map.entry("wheel", "gear"));

    private EmblemSet() {
    }

    /** Canonical id of a stored value; never blank and always in {@link #IDS}. */
    public static String normalize(String id) {
        String key = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        String alias = ALIASES.get(key);
        if (alias != null) {
            return alias;
        }
        return IDS.contains(key) ? key : DEFAULT;
    }

    /** Index of a stored value, {@code 0..IDS.size()-1}; unknown and blank give 0. */
    public static int index(String id) {
        return IDS.indexOf(normalize(id));
    }

    /**
     * Item-model predicate value for a stored value: {@code index / }{@link #DIVISOR}.
     * The base model already draws crest 0, so index 0 simply never overrides.
     */
    public static float predicate(String id) {
        return index(id) / (float) DIVISOR;
    }

    /** Id at {@code index}; an out-of-range index falls back to {@link #DEFAULT}. */
    public static String id(int index) {
        return index < 0 || index >= IDS.size() ? DEFAULT : IDS.get(index);
    }

    /** Number of presets (the model generator writes {@code count - 1} overrides). */
    public static int count() {
        return IDS.size();
    }
}
