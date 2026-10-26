package com.veriborder.client.render;

import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentKind;
import com.veriborder.client.TemplateDisplay;
import net.minecraft.resources.ResourceLocation;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resource map of the three credential items (26.5: flat single-sheet cards).
 *
 * <p>Each credential kind is one {@code minecraft:item/generated} model with two
 * layers, both authored on the same 64x64 canvas by
 * {@code tools/generate_document_cards.ps1}:</p>
 *
 * <pre>
 *   layer0  &lt;art&gt;_flat    the whole neutral card  -&gt; tinted, tintIndex 0
 *   layer1  emblem_&lt;id&gt;   the optional crest       -&gt; never tinted
 * </pre>
 *
 * <p><b>Why one sheet and not three stacked layers:</b> the printed card is a
 * single design — body, print, window and seal belong together. Splitting it into
 * "dyed body + print + crest" made the inventory icon read as three grey
 * rectangles at 16 px. The card is drawn as one neutral grey sheet instead, and
 * {@code ItemColors} multiplies that sheet with the document colour, which is what
 * makes the template tint show up on the item.</p>
 *
 * <p><b>Why the tintable sheet is layer0:</b> {@code ItemModelGenerator} assigns
 * {@code tintIndex = layer index}, so the only layer that can be tinted through
 * {@code ItemColors} index 0 is the first one. The crest is layer1 and is
 * therefore never multiplied by the colour.</p>
 *
 * <p>{@code <art>} is the art family ({@code id_card}, {@code passport},
 * {@code permit}); the third credential kind's item id is {@code hk_macao_permit}
 * while its art family is {@code permit}.</p>
 */
public final class DocumentLayers {
    /** {@code ItemColors} index of the tintable card (== layer0). */
    public static final int TINT_INDEX = 0;
    /** Layer index of the card sheet. */
    public static final int CARD_LAYER = 0;
    /** Layer index of the crest (never tinted). */
    public static final int EMBLEM_LAYER = 1;
    /** Number of texture layers of one document model. */
    public static final int LAYER_COUNT = 2;

    private static final Map<DocumentKind, String> ART = new EnumMap<>(DocumentKind.class);

    static {
        ART.put(DocumentKind.ID_CARD, "id_card");
        ART.put(DocumentKind.PASSPORT, "passport");
        ART.put(DocumentKind.PERMIT, "permit");
    }

    private DocumentLayers() {
    }

    /** Art family of a credential kind ({@code id_card} / {@code passport} / {@code permit}). */
    public static String art(DocumentKind kind) {
        String art = kind == null ? null : ART.get(kind);
        return art == null ? "id_card" : art;
    }

    /** Texture of the flat tintable card (layer0, tintIndex 0). */
    public static ResourceLocation card(DocumentKind kind) {
        return texture(art(kind) + "_flat");
    }

    /**
     * Texture drawn over a custom base map: the same card with the printed window
     * cut out, so an uploaded map only shows inside that window.
     */
    public static ResourceLocation frame(DocumentKind kind) {
        return texture(art(kind) + "_frame");
    }

    /** Texture of one preset crest, e.g. {@code veriborder:textures/item/emblem_star.png}. */
    public static ResourceLocation emblem(String emblemId) {
        return texture("emblem_" + EmblemSet.normalize(emblemId));
    }

    /**
     * Tint used when a stack carries no document at all (creative tab icon, a
     * blank booklet): the accent colour of the kind's official layout.
     *
     * <p>A document always resolves its own colour through
     * {@link TemplateDisplay#coverTint(com.veriborder.doc.DocumentData)}, which is
     * the single source the viewer, the issuing machine, the border terminal and
     * the item icon share.</p>
     */
    public static int defaultTint(DocumentKind kind) {
        List<DocStyle> styles = DocStyle.forKind(kind == null ? DocumentKind.ID_CARD : kind);
        return styles.isEmpty() ? 0xFFFFFF : styles.get(0).accentColor() & 0x00FFFFFF;
    }

    private static ResourceLocation texture(String file) {
        return new ResourceLocation("veriborder", "textures/item/" + file + ".png");
    }
}
