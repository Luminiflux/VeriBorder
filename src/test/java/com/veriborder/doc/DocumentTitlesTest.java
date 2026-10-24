package com.veriborder.doc;

import com.veriborder.block.DeviceBlockEntity;
import com.veriborder.block.DeviceService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.4 display fix: "发证机锁定 Test 后封面仍画版式名 香港护照".
 *
 * <p>The cover plate, the terminal card and the "issued" chat line all print the
 * <em>document</em> title. {@link DocumentTitles} is the single pure rule behind
 * them: a non-blank custom name wins, a blank/whitespace-only one falls back to
 * the official layout name, and a long name is scaled/ellipsized so it cannot
 * overflow the cover band. The holder name is a different field and must never
 * be substituted for the title.</p>
 */
class DocumentTitlesTest {
    /** Test font metric: 6 design pixels per UTF-16 unit. */
    private static final ToIntFunction<String> SIX_PX = value -> value.length() * 6;

    // ------------------------------------------------------------- label rules

    @Test
    void customNameReplacesTheLayoutNameOnTheCover() {
        assertEquals("Test", DocumentTitles.coverLabel("Test", "香港护照"));
    }

    @Test
    void blankCustomNameFallsBackToTheLayoutName() {
        assertEquals("香港护照", DocumentTitles.coverLabel("", "香港护照"));
        assertEquals("香港护照", DocumentTitles.coverLabel(null, "香港护照"));
    }

    @Test
    void whitespaceOnlyCustomNameCountsAsEmpty() {
        assertEquals("香港护照", DocumentTitles.coverLabel("   ", "香港护照"));
        assertEquals("香港护照", DocumentTitles.coverLabel("\t \n", "香港护照"));
        assertEquals("香港护照", DocumentTitles.coverLabel(" \u3000 ", "香港护照"));
    }

    @Test
    void surroundingWhitespaceIsTrimmedFromThePrintedName() {
        assertEquals("Test", DocumentTitles.coverLabel("  Test  ", "香港护照"));
        assertEquals("香港护照", DocumentTitles.coverLabel(null, "  香港护照  "));
    }

    @Test
    void aDocumentWithoutAnyNameDoesNotProduceABlankFallback() {
        assertEquals("Test", DocumentTitles.coverLabel("Test", ""));
        assertEquals("", DocumentTitles.coverLabel("", ""));
        assertEquals("", DocumentTitles.coverLabel(null, null));
    }

    @Test
    void issuedMessageLabelUsesTheSameResolutionAsTheCover() {
        assertEquals("Test", DocumentTitles.issuedLabel("Test", "中国香港特别行政区护照"));
        assertEquals("中国香港特别行政区护照", DocumentTitles.issuedLabel("", "中国香港特别行政区护照"));
        assertEquals("中国香港特别行政区护照", DocumentTitles.issuedLabel("   ", "中国香港特别行政区护照"));
        assertEquals(DocumentTitles.coverLabel("Test", "香港护照"),
                DocumentTitles.issuedLabel("Test", "香港护照"));
    }

    // -------------------------------------------------------------- cover band

    @Test
    void aShortCoverLabelIsDrawnAtFullSize() {
        DocumentTitles.CoverLine line = DocumentTitles.coverLine("Test", 100, SIX_PX);
        assertEquals("Test", line.text());
        assertEquals(1.0F, line.scale());
        assertFalse(line.blank());
    }

    @Test
    void aLongCoverLabelIsScaledDownUntilItFits() {
        String label = "Electronic Passport";           // 19 chars = 114 px
        DocumentTitles.CoverLine line = DocumentTitles.coverLine(label, 100, SIX_PX);
        assertEquals(label, line.text(), "the full name stays readable, it is only scaled");
        assertTrue(line.scale() < 1.0F);
        assertTrue(line.scale() > DocumentTitles.MIN_COVER_SCALE);
        assertTrue(SIX_PX.applyAsInt(line.text()) * line.scale() <= 100.0F,
                "the scaled line must fit the cover band");
    }

    @Test
    void anExtremelyLongCoverLabelIsTruncatedAtTheMinimumScale() {
        String label = "A".repeat(120);
        DocumentTitles.CoverLine line = DocumentTitles.coverLine(label, 100, SIX_PX);
        assertEquals(DocumentTitles.MIN_COVER_SCALE, line.scale());
        assertTrue(line.text().endsWith(DocumentTitles.ELLIPSIS), "it is ellipsized, not cut mid-glyph");
        assertTrue(line.text().length() < label.length());
        assertTrue(SIX_PX.applyAsInt(line.text()) * line.scale() <= 100.0F,
                "even the truncated line stays inside the band");
    }

    @Test
    void theCoverLabelNeverOverflowsTheBandAtAnyLength() {
        for (int length = 1; length <= 200; length++) {
            String label = "W".repeat(length);
            DocumentTitles.CoverLine line = DocumentTitles.coverLine(label, 120, SIX_PX);
            assertTrue(SIX_PX.applyAsInt(line.text()) * line.scale() <= 120.0F + 0.001F,
                    "length " + length + " overflows the cover band");
            assertTrue(line.scale() >= DocumentTitles.MIN_COVER_SCALE,
                    "length " + length + " shrinks below the legibility floor");
        }
    }

    @Test
    void aBlankCoverLabelDrawsNothing() {
        assertTrue(DocumentTitles.coverLine("   ", 100, SIX_PX).blank());
        assertTrue(DocumentTitles.coverLine(null, 100, SIX_PX).blank());
        assertEquals(1.0F, DocumentTitles.coverLine("", 100, SIX_PX).scale());
    }

    @Test
    void truncationKeepsShortTextAndAddsAnEllipsisOtherwise() {
        assertEquals("Test", DocumentTitles.truncate("Test", 100, SIX_PX));
        String cut = DocumentTitles.truncate("ABCDEFGHIJ", 30, SIX_PX);
        assertTrue(cut.endsWith(DocumentTitles.ELLIPSIS));
        assertTrue(SIX_PX.applyAsInt(cut) <= 30);
        assertTrue("ABCDEFGHIJ".startsWith(cut.substring(0, cut.length() - 1)));
    }

    @Test
    void truncationNeverSplitsASurrogatePair() {
        String label = "\uD83D\uDEC2".repeat(20);       // passport emoji, 2 UTF-16 units each
        String cut = DocumentTitles.truncate(label, 40, SIX_PX);
        assertTrue(cut.endsWith(DocumentTitles.ELLIPSIS));
        int withoutEllipsis = cut.length() - DocumentTitles.ELLIPSIS.length();
        assertTrue(withoutEllipsis % 2 == 0, "a half surrogate pair was kept");
        assertTrue(Character.isHighSurrogate(cut.charAt(withoutEllipsis - 2)));
        assertTrue(Character.isLowSurrogate(cut.charAt(withoutEllipsis - 1)));
    }

    // ----------------------------------------------------- document behaviour

    private static CompoundTag applicantForm() {
        CompoundTag form = new CompoundTag();
        form.putString("name", "ALEX MORGAN");
        form.putString("nameLatin", "MORGAN ALEX");
        form.putString("birthDate", "1991-02-03");
        form.putString("sex", "M");
        return form;
    }

    private static DocumentData lockedTestDocument() {
        CompoundTag locks = new CompoundTag();
        locks.putBoolean("lockDocumentName", true);
        locks.putString("lockedDocumentName", "Test");
        CompoundTag form = DeviceService.applyLockedDocumentName(
                DeviceBlockEntity.Locks.fromTag(locks), applicantForm());
        return DocumentFactory.fromForm(DocStyle.HK_PASSPORT, form);
    }

    @Test
    void aLockedDocumentPrintsItsCustomNameOnTheCover() {
        DocumentData doc = lockedTestDocument();
        assertEquals("Test", doc.coverName());
        assertEquals("Test", doc.displayName());
        // The cover is the document title; the holder row below stays untouched.
        assertEquals("ALEX MORGAN", doc.name());
        assertNotEquals(doc.name(), doc.coverName());
    }

    @Test
    void anOfficialDocumentKeepsTheLayoutCoverName() {
        DocumentData doc = DocumentFactory.sample(DocStyle.HK_PASSPORT);
        assertFalse(doc.hasCustomDisplayName());
        assertEquals(Component.translatable(DocStyle.HK_PASSPORT.shortNameKey()).getString(),
                doc.coverName());
    }

    @Test
    void aCustomCoverKeepsItsShortNameUntilACustomTitleOverridesIt() {
        CompoundTag form = applicantForm();
        form.putBoolean("customStyle", true);
        form.putString("customName", "Special Border Pass");
        form.putString("customShortName", "SBP");
        DocumentData doc = DocumentFactory.fromForm(DocStyle.HK_PASSPORT, form);
        assertEquals("SBP", doc.coverName(), "a custom cover supplies its own short cover name");

        doc.setDisplayName("Test");
        assertEquals("Test", doc.coverName(), "the explicit name always wins on the cover");

        doc.setDisplayName("   ");
        assertEquals("SBP", doc.coverName(), "clearing the name restores the cover short name");
    }

    @Test
    void theCoverNameSurvivesTheNbtRoundTrip() {
        DocumentData loaded = DocumentData.fromTag(lockedTestDocument().copyTag());
        assertEquals("Test", loaded.coverName());
    }

    @Test
    void onlyACustomTitleIsWorthTransportingToTheTerminal() {
        // The issuer-locked title must travel so the terminal shows "Test"...
        assertTrue(lockedTestDocument().hasCustomTitle());

        // ...while an official document carries no title of its own: sending it
        // would replace the layout's short cover name with the long official one.
        assertFalse(DocumentFactory.sample(DocStyle.HK_PASSPORT).hasCustomTitle());

        // A custom cover's own name is a custom title too.
        CompoundTag cover = applicantForm();
        cover.putBoolean("customStyle", true);
        cover.putString("customName", "Special Border Pass");
        assertTrue(DocumentFactory.fromForm(DocStyle.HK_PASSPORT, cover).hasCustomTitle());

        // Clearing the explicit name drops back to the official layout title.
        DocumentData doc = lockedTestDocument();
        doc.setDisplayName("");
        assertFalse(doc.hasCustomTitle());
        assertEquals(Component.translatable(DocStyle.HK_PASSPORT.shortNameKey()).getString(),
                doc.coverName());
    }
}
