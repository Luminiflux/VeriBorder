package com.veriborder.client.render;

import com.veriborder.client.TemplateDisplay;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentKind;
import com.veriborder.item.DocumentItem;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 26.4.1 resolved cover appearance of one document stack.
 *
 * <p>Everything the item renderer needs is read once here, with the documented
 * fallbacks baked in, so the colour handler, the two item-model predicates and the
 * custom-base-map renderer cannot disagree about what a stack looks like:</p>
 *
 * <ul>
 *   <li><b>tint</b>: the signed {@code tint} field; {@code 0} means "follow the
 *       layout", which {@link DocumentData#effectiveTint()} already resolves to the
 *       layout's own accent colour (never a hard-coded per-kind colour),</li>
 *   <li><b>emblem</b>: the stored crest id through {@link EmblemSet} — a missing,
 *       blank or unknown id becomes the default crest instead of no crest,</li>
 *   <li><b>base map</b>: the {@code templateTex} hash and the dynamic slot it is
 *       bound to ({@link TemplateSlots#NO_SLOT_INDEX} when the client has not
 *       received that map yet).</li>
 * </ul>
 *
 * <p>A stack without document NBT (creative tab icon, a freshly crafted blank
 * document) is not an error: it resolves to the kind's default tint, the default
 * crest and no base map.</p>
 */
public final class DocumentAppearance {
    /**
     * @param kind       credential kind the stack belongs to
     * @param tint       resolved {@code 0xRRGGBB} body colour
     * @param emblemId   canonical crest id ({@link EmblemSet#normalize(String)})
     * @param emblemIndex crest index in {@link EmblemSet#IDS}
     * @param textureHash stored base-map hash, {@code ""} when the cover is printed
     * @param slot       dynamic texture slot the hash is bound to, {@code -1} if none
     */
    public record Skin(DocumentKind kind, int tint, String emblemId, int emblemIndex,
                       String textureHash, int slot) {
        /** {@code veriborder:emblem} predicate value. */
        public float emblemPredicate() {
            return emblemIndex / (float) EmblemSet.DIVISOR;
        }

        /** {@code veriborder:texslot} predicate value. */
        public float slotPredicate() {
            return slot < 0 ? TemplateSlots.NO_SLOT : slot;
        }

        /** Whether a custom base map should be drawn for this stack. */
        public boolean usesBaseMap() {
            return slot >= 0;
        }

        /** Red channel of {@link #tint()} in {@code 0..1}. */
        public float red() {
            return ((tint >> 16) & 0xFF) / 255.0F;
        }

        /** Green channel of {@link #tint()} in {@code 0..1}. */
        public float green() {
            return ((tint >> 8) & 0xFF) / 255.0F;
        }

        /** Blue channel of {@link #tint()} in {@code 0..1}. */
        public float blue() {
            return (tint & 0xFF) / 255.0F;
        }
    }

    private static final Skin FALLBACK = new Skin(DocumentKind.ID_CARD,
            DocumentLayers.defaultTint(DocumentKind.ID_CARD), EmblemSet.DEFAULT, 0, "",
            TemplateSlots.NO_SLOT_INDEX);

    private DocumentAppearance() {
    }

    /** Appearance of a stack, using the item's own credential kind. */
    public static Skin of(ItemStack stack, DocumentKind kind) {
        return of(DocumentItem.getDocument(stack), kind);
    }

    /** Appearance of a document, or the kind's defaults when {@code doc} is null. */
    public static Skin of(@Nullable DocumentData doc, DocumentKind kind) {
        DocumentKind resolved = kind == null ? DocumentKind.ID_CARD : kind;
        if (doc == null) {
            return new Skin(resolved, DocumentLayers.defaultTint(resolved), EmblemSet.DEFAULT,
                    EmblemSet.index(EmblemSet.DEFAULT), "", TemplateSlots.NO_SLOT_INDEX);
        }
        String emblemId = EmblemSet.normalize(doc.emblem());
        String hash = TemplateSlots.normalize(doc.templateTex());
        return new Skin(resolved, tint(doc) & 0x00FFFFFF, emblemId,
                EmblemSet.index(emblemId), hash, TemplateSlots.slotOf(hash));
    }

    /**
     * Body colour of one document, in the documented order: the signed template
     * tint, then a custom cover's own accent, then a locked layout colour, then the
     * layout's accent colour.
     *
     * <p>26.5: the resolution itself lives in
     * {@link TemplateDisplay#coverTint(com.veriborder.doc.DocumentData)} so the item
     * card cannot be a different colour than the viewer cover, the issuing-machine
     * preview and the border-terminal card. A template-styled document never falls
     * back to the base layout's colour (the "Japanese passport template is still
     * red" bug).</p>
     */
    static int tint(DocumentData doc) {
        return TemplateDisplay.coverTint(doc) & 0x00FFFFFF;
    }

    /** Appearance of the item on {@code stack} (item type decides the kind). */
    public static Skin ofStack(ItemStack stack) {
        return of(stack, DocumentItem.kindOf(stack));
    }

    /** Default skin of a kind; used by the creative tab and by tests. */
    public static Skin defaults(DocumentKind kind) {
        DocumentKind resolved = kind == null ? DocumentKind.ID_CARD : kind;
        return resolved == FALLBACK.kind() ? FALLBACK : new Skin(resolved,
                DocumentLayers.defaultTint(resolved), EmblemSet.DEFAULT, EmblemSet.index(EmblemSet.DEFAULT),
                "", TemplateSlots.NO_SLOT_INDEX);
    }
}
