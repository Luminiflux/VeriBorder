package com.veriborder.client.render;

import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.4.1 cover appearance resolution: tint, crest and base map of one document,
 * including every "field is missing" case.
 *
 * <p>The rules being pinned:</p>
 * <ul>
 *   <li>a signed {@code tint} wins; {@code tint = 0} follows the layout, and it
 *       must follow <em>that</em> layout (a US passport is not a Chinese one),</li>
 *   <li>a custom cover keeps its own accent colour unless a tint was copied onto
 *       the document,</li>
 *   <li>a stamp without document NBT is not an error — it renders the kind's
 *       default cover,</li>
 *   <li>an unknown crest or a base-map hash the client never received degrades to
 *       "printed cover with the default crest", never to a blank card.</li>
 * </ul>
 */
class DocumentAppearanceTest {
    @BeforeEach
    void reset() {
        TemplateSlots.clear();
    }

    @Test
    void theSignedTintWins() {
        DocumentData doc = DocumentData.createEmpty(DocStyle.CN_PASSPORT);
        doc.setTint(0x3366CC);
        DocumentAppearance.Skin skin = DocumentAppearance.of(doc, DocumentKind.PASSPORT);
        assertEquals(0x3366CC, skin.tint());
        assertEquals(DocumentKind.PASSPORT, skin.kind());
    }

    @Test
    void aZeroTintFollowsTheLayoutInsteadOfAHardCodedColour() {
        DocumentData cn = DocumentData.createEmpty(DocStyle.CN_PASSPORT);
        DocumentData us = DocumentData.createEmpty(DocStyle.US_PASSPORT);
        assertEquals(0, cn.tint());
        assertEquals(DocStyle.CN_PASSPORT.accentColor(),
                DocumentAppearance.of(cn, DocumentKind.PASSPORT).tint());
        assertEquals(DocStyle.US_PASSPORT.accentColor(),
                DocumentAppearance.of(us, DocumentKind.PASSPORT).tint());
        assertFalse(DocumentAppearance.of(cn, DocumentKind.PASSPORT).tint()
                == DocumentAppearance.of(us, DocumentKind.PASSPORT).tint());
    }

    @Test
    void aCustomCoverKeepsItsOwnAccentUnlessATintWasCopied() {
        DocumentData doc = DocumentData.createEmpty(DocStyle.CN_ID);
        doc.applyCustomStyle("Custom", "CUS", "Badge", 0x112233);
        assertEquals(0x112233, DocumentAppearance.of(doc, DocumentKind.ID_CARD).tint());
        doc.setTint(0x445566);
        assertEquals(0x445566, DocumentAppearance.of(doc, DocumentKind.ID_CARD).tint());
    }

    @Test
    void aStampWithoutDocumentDataRendersTheKindDefaults() {
        for (DocumentKind kind : DocumentKind.values()) {
            DocumentAppearance.Skin skin = DocumentAppearance.of((DocumentData) null, kind);
            assertEquals(kind, skin.kind());
            assertEquals(DocumentLayers.defaultTint(kind), skin.tint());
            assertEquals(0, skin.emblemIndex());
            assertEquals(EmblemSet.DEFAULT, skin.emblemId());
            assertFalse(skin.usesBaseMap());
            assertEquals(TemplateSlots.NO_SLOT_INDEX, skin.slot());
            assertEquals(-1.0F, skin.slotPredicate(), 1.0E-6F);
        }
    }

    @Test
    void theKindDefaultTintIsTheFirstOfficialLayout() {
        assertEquals(DocStyle.CN_ID.accentColor(), DocumentLayers.defaultTint(DocumentKind.ID_CARD));
        assertEquals(DocStyle.CN_PASSPORT.accentColor(), DocumentLayers.defaultTint(DocumentKind.PASSPORT));
        assertEquals(DocStyle.HK_MACAO_PERMIT.accentColor(), DocumentLayers.defaultTint(DocumentKind.PERMIT));
    }

    @Test
    void theCrestIndexFollowsTheStoredId() {
        DocumentData doc = DocumentData.createEmpty(DocStyle.CN_ID);
        assertEquals(EmblemSet.DEFAULT, doc.emblem(), "a fresh document carries the default crest");
        assertEquals(0, DocumentAppearance.of(doc, DocumentKind.ID_CARD).emblemIndex());
        doc.setEmblem("shield");
        DocumentAppearance.Skin skin = DocumentAppearance.of(doc, DocumentKind.ID_CARD);
        assertEquals("shield", skin.emblemId());
        assertEquals(EmblemSet.index("shield"), skin.emblemIndex());
        assertEquals(EmblemSet.predicate("shield"), skin.emblemPredicate(), 1.0E-6F);
    }

    @Test
    void anUnknownCrestIdFallsBackToTheDefaultInsteadOfNothing() {
        DocumentData doc = DocumentData.createEmpty(DocStyle.CN_ID);
        doc.setEmblem("no-such-crest");
        DocumentAppearance.Skin skin = DocumentAppearance.of(doc, DocumentKind.ID_CARD);
        assertEquals(EmblemSet.DEFAULT, skin.emblemId());
        assertEquals(0, skin.emblemIndex());
    }

    @Test
    void aBaseMapBindsToTheSlotTheSnapshotAssigned() {
        String hash = "0123456789abcdef";
        TemplateSlots.rebuild(List.of(hash));
        DocumentData doc = DocumentData.createEmpty(DocStyle.CN_ID);
        doc.setTemplateTex(hash);
        DocumentAppearance.Skin skin = DocumentAppearance.of(doc, DocumentKind.ID_CARD);
        assertEquals(hash, skin.textureHash());
        assertEquals(0, skin.slot());
        assertTrue(skin.usesBaseMap());
        assertEquals(0.0F, skin.slotPredicate(), 1.0E-6F);
    }

    @Test
    void aBaseMapTheClientNeverReceivedKeepsThePrintedCover() {
        DocumentData doc = DocumentData.createEmpty(DocStyle.CN_ID);
        doc.setTemplateTex("ffffffffffffffff");
        DocumentAppearance.Skin skin = DocumentAppearance.of(doc, DocumentKind.ID_CARD);
        assertEquals("ffffffffffffffff", skin.textureHash());
        assertEquals(TemplateSlots.NO_SLOT_INDEX, skin.slot());
        assertFalse(skin.usesBaseMap());
        assertEquals(-1.0F, skin.slotPredicate(), 1.0E-6F);
    }

    @Test
    void aDocumentWithoutATextureFieldHasNoBaseMap() {
        DocumentData doc = DocumentData.createEmpty(DocStyle.HK_MACAO_PERMIT);
        DocumentAppearance.Skin skin = DocumentAppearance.of(doc, DocumentKind.PERMIT);
        assertEquals("", skin.textureHash());
        assertFalse(skin.usesBaseMap());
    }

    @Test
    void channelHelpersMatchTheTint() {
        DocumentData doc = DocumentData.createEmpty(DocStyle.CN_ID);
        doc.setTint(0xFF8000);
        DocumentAppearance.Skin skin = DocumentAppearance.of(doc, DocumentKind.ID_CARD);
        assertEquals(1.0F, skin.red(), 1.0E-3F);
        assertEquals(128 / 255.0F, skin.green(), 1.0E-3F);
        assertEquals(0.0F, skin.blue(), 1.0E-3F);
    }
}
