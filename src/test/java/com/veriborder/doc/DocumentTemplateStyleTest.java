package com.veriborder.doc;

import com.veriborder.block.DeviceService;
import com.veriborder.registry.CredentialRegistryData;
import com.veriborder.registry.RegistryService;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.4.2 "证件样式身份与颜色": a document issued from a registry template is
 * <em>identifiable</em> as a custom layout, its colour comes from the template,
 * and a document without any recorded colour is not painted at all.
 *
 * <p>The three failures this pins down:</p>
 * <ol>
 *   <li>the issued document kept the base layout's {@code style}, so a viewer drew
 *       that layout's cover — a Japanese-passport template stayed red;</li>
 *   <li>{@code tint == 0} reached the item colour handler and painted the plate
 *       black ({@code 0xFF000000}) instead of "untinted";</li>
 *   <li>a template saved without a colour stored {@code 0}, which is exactly the
 *       value that triggers both of the above.</li>
 * </ol>
 *
 * <p>Pure data-level tests: the same helpers {@code DeviceService} runs at issue
 * time, the {@link DocumentData} accessors the renderer reads
 * ({@link DocumentData#coverTint()} is what {@code DocumentItem.tintColor} hands
 * to {@code ItemColors}), and the registry template record. No world, no client,
 * no item registry.</p>
 */
class DocumentTemplateStyleTest {
    private static final int TEMPLATE_COLOR = 0x2F6FE4;
    private static final int TEMPLATE_TINT = 0x1E7A46;
    private static final String TEMPLATE_ID = "diplomatic";

    // ------------------------------------------------------------- fixtures

    private static CredentialRegistryData.Template template(String id, int color, int tint) {
        return new CredentialRegistryData.Template(id, DocumentKind.PASSPORT, "Template " + id,
                DocStyle.HK_PASSPORT.id(), "HKG", "", "", color, "custom_passport",
                "", "", tint, "star", "");
    }

    private static CompoundTag applicantForm() {
        CompoundTag form = new CompoundTag();
        form.putString("name", "ALEX MORGAN");
        form.putString("nameLatin", "MORGAN ALEX");
        form.putString("birthDate", "1991-02-03");
        form.putString("sex", "M");
        return form;
    }

    /** Mirrors {@code DeviceService.issueDocument}: resolve, inject, build. */
    private static DocumentData issue(CredentialRegistryData.Template submitted, DocStyle style) {
        CompoundTag form = applicantForm();
        CredentialRegistryData.Template resolved =
                DeviceService.resolveIssueTemplate(null, submitted, style.kind(), style);
        DeviceService.applyRegistryTemplate(form, resolved, style);
        return DocumentFactory.fromForm(style, form);
    }

    /** A sample document with every template key stripped (pre-26.4.1 data). */
    private static CompoundTag templateFreeSample(DocStyle style) {
        CompoundTag tag = DocumentFactory.sample(style).copyTag();
        tag.remove(DocumentData.TEMPLATE_ID);
        tag.remove(DocumentData.TEMPLATE_BASE_STYLE);
        tag.remove(DocumentData.TEMPLATE_TINT);
        tag.remove(DocumentData.TEMPLATE_EMBLEM);
        tag.remove(DocumentData.TEMPLATE_TEXTURE);
        return tag;
    }

    // ------------------------------------------------- 1. style identity

    @Test
    void aTemplateIssuedDocumentSignsTheTemplateAsItsStyle() {
        DocumentData doc = issue(template(TEMPLATE_ID, TEMPLATE_COLOR, TEMPLATE_TINT),
                DocStyle.HK_PASSPORT);

        assertEquals("template:" + TEMPLATE_ID, doc.tag().getString("style"),
                "the signed style names the template, not the base layout");
        assertTrue(doc.isTemplateStyle());
        assertEquals(TEMPLATE_ID, doc.templateStyleId());
        assertEquals(DocStyle.HK_PASSPORT.id(), doc.tag().getString(DocumentData.TEMPLATE_BASE_STYLE),
                "the layout underneath is recorded, so it survives a template delete");

        // The base layout is still readable underneath, and it is the layout that
        // decides the credential kind / MRZ / item model.
        assertEquals(DocStyle.HK_PASSPORT.id(), doc.baseStyleId());
        assertEquals(DocStyle.HK_PASSPORT, doc.style());
        assertEquals(DocumentKind.PASSPORT, doc.kind());
        assertTrue(doc.styleKnown(), "a template style is never 'malformed'");
        assertTrue(doc.integrityValid());

        // …and it survives a save/load round trip (the tag is the only state).
        DocumentData reloaded = DocumentData.fromTag(doc.copyTag());
        assertTrue(reloaded.isTemplateStyle());
        assertEquals(TEMPLATE_ID, reloaded.templateStyleId());
        assertEquals(DocStyle.HK_PASSPORT.id(), reloaded.baseStyleId());
        assertEquals(DocumentKind.PASSPORT, reloaded.kind());
        assertTrue(reloaded.integrityValid());
    }

    @Test
    void theDocumentTintIsTheTemplateTint() {
        DocumentData doc = issue(template(TEMPLATE_ID, TEMPLATE_COLOR, TEMPLATE_TINT),
                DocStyle.HK_PASSPORT);

        assertEquals(TEMPLATE_TINT, doc.tint());
        assertEquals(TEMPLATE_TINT, doc.effectiveTint());
        assertEquals(TEMPLATE_ID, doc.templateId());
        assertEquals("star", doc.emblem());
        assertEquals(0xFF000000 | TEMPLATE_TINT, doc.coverTint(),
                "the plate is dyed with the template tint");
    }

    @Test
    void editingATemplateIssuedDocumentKeepsItsStyleIdentity() {
        DocumentData doc = issue(template(TEMPLATE_ID, TEMPLATE_COLOR, TEMPLATE_TINT),
                DocStyle.HK_PASSPORT);

        // Every setter re-signs; a re-sign must never fall back to the base layout
        // (that would silently turn the custom cover into the layout cover again).
        doc.setName("JORDAN LEE");
        doc.setTint(0x00AA55);
        doc.setEmblem("shield");
        doc.addStamp("IN", "HKG", "HKG", "Officer");

        assertEquals("template:" + TEMPLATE_ID, doc.tag().getString("style"));
        assertTrue(doc.isTemplateStyle());
        assertEquals(DocStyle.HK_PASSPORT.id(), doc.baseStyleId());
        assertTrue(doc.integrityValid());

        // A document re-issued as a plain layout loses the identity again.
        doc.clearRegistryTemplate();
        assertEquals("", doc.templateStyleId());
        assertFalse(doc.isTemplateStyle());
        assertEquals(DocStyle.HK_PASSPORT.id(), doc.tag().getString("style"));
        assertFalse(doc.tag().contains(DocumentData.TEMPLATE_BASE_STYLE));
        assertTrue(doc.integrityValid());
    }

    @Test
    void aPlainLayoutDocumentKeepsItsLayoutStyle() {
        DocumentData doc = issue(null, DocStyle.CN_ID);

        assertFalse(doc.isTemplateStyle());
        assertEquals("", doc.templateStyleId());
        assertEquals(DocStyle.CN_ID.id(), doc.tag().getString("style"),
                "the pre-26.4.2 plain-layout path is unchanged");
        assertEquals(DocStyle.CN_ID.id(), doc.baseStyleId());
        assertFalse(doc.tag().contains(DocumentData.TEMPLATE_BASE_STYLE),
                "a plain layout document records no base-layout key either");
        assertTrue(doc.styleKnown());
    }

    // --------------------------------------------- 2. tint zero semantics

    @Test
    void aDocumentWithoutATintIsNotPainted() {
        // A document issued before templates existed: no tint key at all.
        DocumentData legacy = DocumentData.fromTag(templateFreeSample(DocStyle.CN_PASSPORT));

        assertEquals(0, legacy.tint(), "no tint recorded");
        assertEquals(-1, legacy.coverTint(),
                "tint == 0 is 'untinted', never opaque black and never the layout accent");
        assertNotEquals(0xFF000000, legacy.coverTint());
    }

    @Test
    void aColourTheDocumentReallyOwnsStillDyesThePlate() {
        // A custom cover carries its accent in customColor, not in tint.
        DocumentData custom = DocumentData.createEmpty(DocStyle.CN_ID);
        custom.applyCustomStyle("Custom", "CUS", "Badge", 0x112233);
        assertEquals(0xFF112233, custom.coverTint());

        // The 26.4 locked template colour is the same shape of "own colour".
        DocumentData locked = DocumentData.createEmpty(DocStyle.HK_PASSPORT);
        locked.setStyleColor(0x336699);
        assertEquals(0xFF336699, locked.coverTint());

        // A template tint always wins over both.
        DocumentData both = DocumentData.createEmpty(DocStyle.HK_PASSPORT);
        both.setStyleColor(0x336699);
        both.setTint(TEMPLATE_TINT);
        assertEquals(0xFF000000 | TEMPLATE_TINT, both.coverTint());

        // An explicit black is still "no colour", never a black plate.
        DocumentData black = DocumentData.createEmpty(DocStyle.CN_ID);
        black.applyCustomStyle("Custom", "CUS", "Badge", 0x000000);
        assertEquals(-1, black.coverTint());
    }

    // ------------------------------------- 3. a template is never colourless

    @Test
    void aTemplateSavedWithoutAColourGetsTheNeutralDefault() {
        CredentialRegistryData.Template colourless = template("plain", 0, 0);

        assertEquals(CredentialRegistryData.DEFAULT_TEMPLATE_TINT, colourless.tint(),
                "a saved template always carries a colour");
        assertEquals(CredentialRegistryData.DEFAULT_TEMPLATE_TINT, colourless.effectiveTint());
        assertEquals(0xE6E8EA, CredentialRegistryData.DEFAULT_TEMPLATE_TINT);

        // Through the real save path (registry editor → prepareTemplate).
        CompoundTag request = new CompoundTag();
        request.putString("kind", "passport");
        request.putString("name", "No Colour Passport");
        request.putString("style", DocStyle.HK_PASSPORT.id());
        RegistryService.PreparedTemplate prepared = RegistryService.prepareTemplate(request);
        assertTrue(prepared.ok());
        assertNotNull(prepared.template());
        assertEquals(CredentialRegistryData.DEFAULT_TEMPLATE_TINT, prepared.template().tint());
        assertEquals(CredentialRegistryData.DEFAULT_TEMPLATE_TINT,
                prepared.template().effectiveTint());

        // …and it survives the NBT round trip (save file + snapshot).
        assertEquals(CredentialRegistryData.DEFAULT_TEMPLATE_TINT,
                CredentialRegistryData.Template.fromTag(prepared.template().toTag()).tint());

        // A registry save keeps it too (upsert → stored row).
        CredentialRegistryData registry = new CredentialRegistryData();
        assertTrue(registry.upsertTemplate(colourless));
        assertEquals(CredentialRegistryData.DEFAULT_TEMPLATE_TINT,
                registry.template("plain").tint());
    }

    @Test
    void aTemplateSavedBeforeTheFixLoadsWithTheNeutralDefault() {
        // A row written by an older build: colour 0, no tint key at all.
        CompoundTag legacy = new CompoundTag();
        legacy.putString("id", "old");
        legacy.putString("kind", "passport");
        legacy.putString("name", "Old Template");
        legacy.putString("style", DocStyle.HK_PASSPORT.id());
        legacy.putInt("color", 0);

        CredentialRegistryData.Template loaded = CredentialRegistryData.Template.fromTag(legacy);
        assertEquals(CredentialRegistryData.DEFAULT_TEMPLATE_TINT, loaded.tint(),
                "a colourless template is repaired on read, so issuing cannot copy 0");
        assertEquals(CredentialRegistryData.DEFAULT_TEMPLATE_TINT, loaded.effectiveTint());

        // The released 9-argument form (a pre-26.4.1 template) is repaired the same way.
        CredentialRegistryData.Template old = new CredentialRegistryData.Template("old",
                DocumentKind.PASSPORT, "Old Template", DocStyle.HK_PASSPORT.id(), "HKG", "", "", 0, "");
        assertEquals(CredentialRegistryData.DEFAULT_TEMPLATE_TINT, old.tint());
    }

    @Test
    void issuingFromAColourlessTemplateNeverProducesABlackOrUntintedDocument() {
        // The exact user story: a template created from the Japanese passport
        // layout without picking a colour.
        CredentialRegistryData.Template colourless = new CredentialRegistryData.Template(
                "jp_custom", DocumentKind.PASSPORT, "Custom JP", DocStyle.JP_PASSPORT.id(),
                "JPN", "", "", 0, "custom_passport");

        DocumentData doc = issue(colourless, DocStyle.JP_PASSPORT);

        assertEquals("template:jp_custom", doc.tag().getString("style"));
        assertEquals(DocStyle.JP_PASSPORT.id(), doc.baseStyleId(),
                "the Japanese layout is the fallback, not the identity");
        assertNotEquals(0, doc.tint(), "the document never records tint == 0 any more");
        assertEquals(CredentialRegistryData.DEFAULT_TEMPLATE_TINT, doc.tint());
        assertEquals(CredentialRegistryData.DEFAULT_TEMPLATE_TINT, doc.effectiveTint());
        int tint = doc.coverTint();
        assertEquals(0xFFE6E8EA, tint);
        assertNotEquals(0xFF000000, tint, "the plate is never painted black");
        assertNotEquals(DocStyle.JP_PASSPORT.accentColor() | 0xFF000000, tint,
                "the template's own colour, not the base layout's red");
        assertTrue(doc.integrityValid());
    }

    // ------------------------------ 4. a deleted template still renders

    @Test
    void aDeletedTemplateStillResolvesItsBaseLayout() {
        DocumentData doc = issue(template(TEMPLATE_ID, TEMPLATE_COLOR, TEMPLATE_TINT),
                DocStyle.HK_PASSPORT);

        // Deleting the template in the registry has no effect on the document: it
        // keeps its own signed snapshot, and the style still points at the layout
        // underneath. Nothing here resolves a registry, so nothing can throw.
        CredentialRegistryData registry = new CredentialRegistryData();
        assertNull(registry.template(TEMPLATE_ID), "the template is gone");
        assertEquals(DocStyle.HK_PASSPORT.id(), doc.baseStyleId());
        assertEquals(DocStyle.HK_PASSPORT, doc.style());
        assertTrue(doc.styleKnown());
        assertTrue(doc.integrityValid());
    }

    @Test
    void anUnknownOrBrokenTemplateStyleNeverThrows() {
        // A style that names a template nobody ever had: the base layout recorded on
        // the document keeps it renderable.
        DocumentData unknown = DocumentData.fromTag(templateFreeSample(DocStyle.US_PASSPORT));
        unknown.applyRegistryTemplate("never_existed", TEMPLATE_TINT, "", "");
        assertEquals("template:never_existed", unknown.tag().getString("style"));
        assertEquals(DocStyle.US_PASSPORT.id(), unknown.baseStyleId());
        assertEquals(DocStyle.US_PASSPORT, unknown.style());
        assertEquals(DocumentKind.PASSPORT, unknown.kind());
        assertTrue(unknown.styleKnown());

        // A prefix with no id is not a template identity; the document still reads.
        CompoundTag brokenPrefix = templateFreeSample(DocStyle.HK_PASSPORT);
        brokenPrefix.putString("style", "template:");
        DocumentData broken = DocumentData.fromTag(brokenPrefix);
        assertFalse(broken.isTemplateStyle(), "an empty id is not a template identity");
        assertEquals(DocStyle.CN_ID.id(), broken.baseStyleId(),
                "no recorded layout either, so the parsed default is used");
        assertTrue(broken.styleKnown());

        // The static parsers are total: no null, no exception.
        assertEquals("", DocumentData.templateStyleIdOf(null));
        assertEquals("", DocumentData.templateStyleIdOf(""));
        assertEquals("", DocumentData.templateStyleIdOf("hk_passport"));
        assertEquals("abc", DocumentData.templateStyleIdOf(" template:abc "));
        assertEquals("", DocumentData.baseStyleIdOf(null, null));
        assertEquals("", DocumentData.baseStyleIdOf("template:abc", null));
        assertEquals("hk_passport", DocumentData.baseStyleIdOf("template:abc", "hk_passport"));
        assertEquals("hk_passport", DocumentData.baseStyleIdOf(" hk_passport ", "us_passport"),
                "a plain layout id is returned as it is, never replaced by a recorded base");
    }

    // --------------------------------------- 5. legacy documents unchanged

    @Test
    void aDocumentFromBeforeThisReleaseRoundTripsUnchanged() {
        CompoundTag original = templateFreeSample(DocStyle.HK_PASSPORT);

        DocumentData doc = DocumentData.fromTag(original.copy());
        assertEquals(original, doc.copyTag(),
                "reading an old document writes nothing into it");

        assertFalse(doc.isTemplateStyle());
        assertEquals("", doc.templateStyleId());
        assertEquals(DocStyle.HK_PASSPORT.id(), doc.baseStyleId());

        // Even a re-sign keeps the plain style and adds no template keys.
        doc.setName("NEW HOLDER");
        assertEquals(DocStyle.HK_PASSPORT.id(), doc.tag().getString("style"));
        assertFalse(doc.tag().contains(DocumentData.TEMPLATE_ID));
        assertFalse(doc.tag().contains(DocumentData.TEMPLATE_BASE_STYLE));
        assertFalse(doc.tag().contains(DocumentData.TEMPLATE_TINT));
        assertEquals(DocStyle.HK_PASSPORT.accentColor(), doc.effectiveTint());
        assertTrue(doc.integrityValid());
    }
}
