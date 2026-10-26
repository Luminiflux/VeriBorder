package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/**
 * Schema upgrades for the document NBT (document schema 2, 26.3 round 11).
 *
 * <p>A document is a signed tag: {@link DocumentIntegrity} hashes every key, so
 * "adding a compatibility field" would normally make every old booklet read as
 * tampered. The migration therefore follows one rule:</p>
 *
 * <ul>
 *   <li>an <b>authentic</b> document (its stored checksum verifies) is upgraded
 *       and re-signed — the transformation is deterministic and value-preserving,
 *       so re-signing does not hide an edit;</li>
 *   <li>a document that <b>fails</b> its own checksum is left completely untouched
 *       — re-signing it would launder tampering, which is exactly what the
 *       integrity field exists to prevent;</li>
 *   <li>an <b>unsigned</b> document (very old data, or a hand-written test tag) is
 *       structurally upgraded but never signed, so it keeps reading as
 *       unverified until its owner edits it.</li>
 * </ul>
 *
 * <p>The upgrade itself only materialises containers that older builds created
 * lazily ({@code visas}, {@code endorsements}, {@code stamps}, the 26.3
 * {@code permanentCodes} list) and fills the layout defaults the reader already
 * assumes. No document value is rewritten.</p>
 */
public final class DocumentSchema {
    /** Current document schema version. */
    public static final int CURRENT = 2;
    public static final String SCHEMA_KEY = "schema";

    private DocumentSchema() {
    }

    /** The schema a tag was written with; 0 when the field is absent. */
    public static int read(@Nullable CompoundTag tag) {
        if (tag == null) {
            return 0;
        }
        return tag.contains(SCHEMA_KEY, Tag.TAG_INT) ? tag.getInt(SCHEMA_KEY) : 0;
    }

    /**
     * Upgrades {@code tag} in place.
     *
     * @return {@code true} when the tag changed (a second call returns {@code false})
     */
    public static boolean migrate(@Nullable CompoundTag tag) {
        if (tag == null) {
            return false;
        }
        if (read(tag) >= CURRENT) {
            return false;
        }
        boolean signed = tag.contains(DocumentIntegrity.KEY, Tag.TAG_STRING);
        boolean authentic = signed && DocumentIntegrity.verify(tag);
        if (signed && !authentic) {
            // Tampered: never touch (and never re-sign) the tag.
            return false;
        }

        // Reading defaults that fromTag applies anyway; writing them makes the
        // upgraded tag self-describing and keeps the signature stable afterwards.
        if (!tag.contains("style", Tag.TAG_STRING)) {
            tag.putString("style", DocStyle.CN_ID.id());
        }
        if (!tag.contains("kind", Tag.TAG_STRING)) {
            // 26.4.2: the style may be "template:<id>"; the kind comes from the base
            // layout the document recorded next to it.
            tag.putString("kind", DocStyle.byId(DocumentData.baseStyleIdOf(
                    tag.getString("style"), tag.getString(DocumentData.TEMPLATE_BASE_STYLE)))
                    .kind().name());
        }
        if (!tag.contains("status", Tag.TAG_STRING)) {
            tag.putString("status", DocumentStatus.VALID.id());
        }
        ensureList(tag, "visas");
        ensureList(tag, "endorsements");
        ensureList(tag, "stamps");
        // 26.3: permanent codes became records ({code, issuerCountry, purpose,
        // expiry}). A pre-26.3 tag may carry the bare visaFreeCodes string list;
        // it is preserved as-is (DocumentData reads both layouts) and the record
        // list starts empty. A missing list is materialised so the next mutation
        // only has to append.
        ensureList(tag, DocumentData.PERMANENT_CODES);

        tag.putInt(SCHEMA_KEY, CURRENT);
        if (authentic) {
            DocumentIntegrity.sign(tag);
        }
        return true;
    }

    /**
     * Materialises a missing {@link ListTag}. An existing list of an unexpected
     * element type is left alone: it holds data this build does not understand,
     * and replacing it would destroy it.
     */
    private static void ensureList(CompoundTag tag, String key) {
        Tag existing = tag.get(key);
        if (existing instanceof ListTag list) {
            if (list.isEmpty() || list.getElementType() == Tag.TAG_COMPOUND) {
                return;
            }
        } else if (existing == null) {
            tag.put(key, new ListTag());
        }
    }
}
