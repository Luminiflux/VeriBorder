package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-memory cache for the immutable "blank document" template of every
 * {@link DocStyle} (26.3 round 11, template cache).
 *
 * <p>{@link DocumentData#createEmpty(DocStyle)} builds roughly twenty fields and
 * then runs a full SHA-256 integrity signature over all of them. Every issuer,
 * modifier and terminal performs that work for every document it shows, and the
 * result only depends on the layout and today's date, so the signed template is
 * cached per {@code layout@date}.</p>
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>the cache stores a <b>signed</b> template compound; callers always get a
 *       {@link CompoundTag#copy() copy}, so a document can never mutate a cached
 *       entry through its own NBT;</li>
 *   <li>the key contains {@link DateUtil#today()} — a new day is a new template
 *       (issue/expiry dates are baked into the signable payload), so the cache
 *       invalidates itself at midnight without a scheduler;</li>
 *   <li>{@link #invalidate(DocStyle)} and {@link #invalidateAll()} exist for
 *       explicit changes (a reload, a test, or a future editable template table);</li>
 *   <li>the map is bounded: once {@link #MAX_ENTRIES} layouts/dates are cached it
 *       is cleared rather than growing without bound.</li>
 * </ul>
 *
 * <p>Thread-safety: lookups and inserts use {@link ConcurrentHashMap}; entries are
 * immutable and handed out as copies, so concurrent readers are safe. The map is
 * meant for the main thread and the client render thread, not for background
 * writers.</p>
 */
public final class DocumentTemplateCache {
    /** Upper bound before the cache is reset (styles x dates stay far below it). */
    public static final int MAX_ENTRIES = 64;

    private static final Map<String, CompoundTag> TEMPLATES = new ConcurrentHashMap<>();

    private DocumentTemplateCache() {
    }

    /**
     * Returns a fresh copy of the signed template for {@code style}, building it
     * with {@code builder} on a miss.
     */
    public static CompoundTag template(DocStyle style, Supplier<CompoundTag> builder) {
        String key = key(style);
        CompoundTag cached = TEMPLATES.get(key);
        if (cached != null) {
            return cached.copy();
        }
        CompoundTag built = builder.get();
        if (built == null) {
            return new CompoundTag();
        }
        if (TEMPLATES.size() >= MAX_ENTRIES) {
            TEMPLATES.clear();
        }
        CompoundTag previous = TEMPLATES.putIfAbsent(key, built);
        return (previous == null ? built : previous).copy();
    }

    /** Cache key of one layout: {@code <style id>@<today>}. */
    public static String key(DocStyle style) {
        return style.id() + "@" + DateUtil.today();
    }

    /** Drops every cached date of one layout. */
    public static void invalidate(DocStyle style) {
        if (style == null) {
            return;
        }
        String prefix = style.id() + "@";
        TEMPLATES.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** Drops the whole cache (reload, tests, future template edits). */
    public static void invalidateAll() {
        TEMPLATES.clear();
    }

    /** Number of cached templates (tests / diagnose). */
    public static int size() {
        return TEMPLATES.size();
    }

    /** True when {@code style} is cached for today. */
    public static boolean isCached(@Nullable DocStyle style) {
        return style != null && TEMPLATES.containsKey(key(style));
    }
}
