package com.veriborder.registry;

import com.veriborder.block.DeviceBlockEntity;
import com.veriborder.block.DeviceService;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentFactory;
import com.veriborder.doc.DocumentKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.4.1 "自定义证件模板 · 数据与签发": a registry template is a real document
 * definition (its own title, colour, emblem and optional base map), the issuing
 * machine writes that definition onto the document it issues, and the registry
 * stores/inspects uploaded base maps.
 *
 * <p>Everything here is pure logic: the same template helpers
 * {@code DeviceService} uses at issue time, plus the registry data class. No
 * world, no client, no server.</p>
 */
class RegistryTemplateIssueTest {
    private static final int TEMPLATE_COLOR = 0x2F6FE4;
    private static final int TEMPLATE_TINT = 0x1E7A46;
    private static final String TEX_HASH = "a1b2c3d4e5f6";

    // ------------------------------------------------------------- fixtures

    private static CredentialRegistryData.Template template(String id, DocumentKind kind, String style,
                                                            int color, String titleZh, String titleEn,
                                                            int tint, String emblem, String texture) {
        return new CredentialRegistryData.Template(id, kind, "Template " + id, style, "HKG",
                "", "", color, "custom_passport", titleZh, titleEn, tint, emblem, texture);
    }

    /** The 26.4.1 default template: no own title, no tint, no emblem, no base map. */
    private static CredentialRegistryData.Template plainTemplate(String id, DocumentKind kind, String style) {
        return new CredentialRegistryData.Template(id, kind, "Template " + id, style, "",
                "", "", TEMPLATE_COLOR, "custom_passport");
    }

    private static CompoundTag applicantForm() {
        CompoundTag form = new CompoundTag();
        form.putString("name", "ALEX MORGAN");
        form.putString("nameLatin", "MORGAN ALEX");
        form.putString("birthDate", "1991-02-03");
        form.putString("sex", "M");
        return form;
    }

    /** Mirrors the issue path: resolve the template, inject it, build the document. */
    private static DocumentData issue(CredentialRegistryData.Template locked,
                                      CredentialRegistryData.Template submitted, DocStyle style) {
        CompoundTag form = applicantForm();
        CredentialRegistryData.Template resolved =
                DeviceService.resolveIssueTemplate(locked, submitted, style.kind(), style);
        DeviceService.applyRegistryTemplate(form, resolved, style);
        return DocumentFactory.fromForm(style, form);
    }

    private static DocumentData issue(CredentialRegistryData.Template submitted, DocStyle style) {
        return issue(null, submitted, style);
    }

    /** A PNG header with the requested IHDR dimensions (the payload is padded). */
    private static byte[] png(int width, int height, int totalBytes) {
        byte[] data = new byte[Math.max(33, totalBytes)];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(signature, 0, data, 0, 8);
        data[8] = 0;
        data[9] = 0;
        data[10] = 0;
        data[11] = 13;
        data[12] = 'I';
        data[13] = 'H';
        data[14] = 'D';
        data[15] = 'R';
        data[16] = (byte) (width >>> 24);
        data[17] = (byte) (width >>> 16);
        data[18] = (byte) (width >>> 8);
        data[19] = (byte) width;
        data[20] = (byte) (height >>> 24);
        data[21] = (byte) (height >>> 16);
        data[22] = (byte) (height >>> 8);
        data[23] = (byte) height;
        return data;
    }

    private static byte[] png(int width, int height) {
        return png(width, height, 200);
    }

    private static DeviceBlockEntity.Locks lockingName(String lockedName) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("lockDocumentName", true);
        tag.putString("lockedDocumentName", lockedName);
        return DeviceBlockEntity.Locks.fromTag(tag);
    }

    // ------------------------------------------------- template definition

    @Test
    void anIssuedDocumentCarriesItsTemplateDefinition() {
        CredentialRegistryData.Template diplomatic = template("diplomatic", DocumentKind.PASSPORT,
                DocStyle.HK_PASSPORT.id(), TEMPLATE_COLOR, "外交护照", "Diplomatic Passport",
                TEMPLATE_TINT, "star", TEX_HASH);

        DocumentData doc = issue(diplomatic, DocStyle.HK_PASSPORT);

        assertEquals("diplomatic", doc.templateId());
        assertEquals(TEMPLATE_TINT, doc.tint());
        assertEquals(TEMPLATE_TINT, doc.effectiveTint());
        assertEquals("star", doc.emblem());
        assertEquals(TEX_HASH, doc.templateTex());
        assertTrue(doc.hasRegistryTemplate());
        assertTrue(doc.hasTemplateTex());
        // The template's own title names the document.
        assertEquals("外交护照", doc.displayName());
        // …and the 26.4 colour/icon lock still applies next to it.
        assertEquals(TEMPLATE_COLOR, doc.accentColor());
        assertEquals("custom_passport", doc.customIcon());
        assertEquals(DocStyle.HK_PASSPORT.id(), doc.style().id(), "the base layout is unchanged");
        assertTrue(doc.integrityValid());
    }

    @Test
    void aTemplateWithoutItsOwnTintOrEmblemFallsBackToItsColourAndTheDefaultEmblem() {
        CredentialRegistryData.Template plain = plainTemplate("plain", DocumentKind.PASSPORT,
                DocStyle.HK_PASSPORT.id());

        DocumentData doc = issue(plain, DocStyle.HK_PASSPORT);

        assertEquals("plain", doc.templateId());
        assertEquals(TEMPLATE_COLOR, doc.tint(), "an old template tints with its own colour");
        assertEquals(DocumentData.DEFAULT_EMBLEM, doc.emblem());
        assertEquals("", doc.templateTex());
        assertEquals("Template plain", doc.displayName(),
                "no own title, so the template name names the document");
        assertTrue(doc.integrityValid());
    }

    @Test
    void aPlainLayoutDocumentWritesNoTemplateKeys() {
        DocumentData doc = issue(null, null, DocStyle.CN_ID);

        assertFalse(doc.hasRegistryTemplate());
        assertEquals("", doc.templateId());
        assertEquals(0, doc.tint());
        assertEquals(DocStyle.CN_ID.accentColor(), doc.effectiveTint());
        assertEquals(DocumentData.DEFAULT_EMBLEM, doc.emblem());
        assertEquals("", doc.templateTex());
        assertTrue(doc.integrityValid());
    }

    @Test
    void theTemplateTitleIsTheLastNamingCandidate() {
        CredentialRegistryData.Template diplomatic = template("diplomatic", DocumentKind.PASSPORT,
                DocStyle.HK_PASSPORT.id(), TEMPLATE_COLOR, "外交护照", "Diplomatic Passport",
                0, "", "");

        // 1. An explicit applicant title wins over the template title.
        CompoundTag typed = applicantForm();
        typed.putString("displayName", "Special Border Pass");
        DeviceService.applyRegistryTemplate(typed,
                DeviceService.resolveIssueTemplate(null, diplomatic, DocumentKind.PASSPORT,
                        DocStyle.HK_PASSPORT), DocStyle.HK_PASSPORT);
        assertEquals("Special Border Pass",
                DocumentFactory.fromForm(DocStyle.HK_PASSPORT, typed).displayName());

        // 2. A locked document name (the device's) wins over both.
        CompoundTag locked = DeviceService.applyLockedDocumentName(lockingName("Test"), typed);
        DeviceService.applyRegistryTemplate(locked, diplomatic, DocStyle.HK_PASSPORT);
        assertEquals("Test", DocumentFactory.fromForm(DocStyle.HK_PASSPORT, locked).displayName());

        // 3. A blank locked name leaves the template title as the document name.
        CompoundTag blankLock = DeviceService.applyLockedDocumentName(lockingName(""), applicantForm());
        DeviceService.applyRegistryTemplate(blankLock, diplomatic, DocStyle.HK_PASSPORT);
        assertEquals("外交护照", DocumentFactory.fromForm(DocStyle.HK_PASSPORT, blankLock).displayName());
    }

    @Test
    void theTemplateCountryBecomesTheIssuerCountry() {
        CredentialRegistryData.Template japanese = new CredentialRegistryData.Template("jp",
                DocumentKind.PASSPORT, "Japanese Passport", DocStyle.JP_PASSPORT.id(), "JPN",
                "", "", TEMPLATE_COLOR, "book");

        assertEquals("JPN", DeviceService.issueCountryCode("", japanese, "USA"),
                "the template code beats the applicant's claim");
        assertEquals("CHN", DeviceService.issueCountryCode("CHN", japanese, "USA"),
                "the machine's own selector still wins");
        assertEquals("USA", DeviceService.issueCountryCode("", null, "USA"),
                "without a template the form is the first fallback");
        assertEquals("", DeviceService.issueCountryCode("", null, "not a code"));

        // …and the same code reaches the signed document.
        CompoundTag form = applicantForm();
        form.putString(DocumentData.ISSUER_COUNTRY,
                DeviceService.issueCountryCode("", japanese, "USA"));
        DocumentData doc = DocumentFactory.fromForm(DocStyle.JP_PASSPORT, form);
        assertEquals("JPN", doc.issuerCountry());
        assertTrue(doc.mrz1().startsWith("P<JPN"), "the passport MRZ reads the same field");
        assertTrue(doc.integrityValid());
    }

    // --------------------------------------------------- forgery / fallbacks

    @Test
    void aForgedTemplateIdOrKindNeverDecoratesTheDocument() {
        CredentialRegistryData.Template idCard = plainTemplate("resident", DocumentKind.ID_CARD,
                DocStyle.CN_ID.id());
        CredentialRegistryData.Template passport = plainTemplate("diplomatic", DocumentKind.PASSPORT,
                DocStyle.HK_PASSPORT.id());

        // Wrong credential kind: refused outright (not "repaired" onto this kind).
        assertNull(DeviceService.resolveIssueTemplate(null, idCard, DocumentKind.PASSPORT,
                DocStyle.HK_PASSPORT));
        // Right kind, but the template was re-pointed at another layout since.
        CredentialRegistryData.Template moved = plainTemplate("moved", DocumentKind.PASSPORT,
                DocStyle.US_PASSPORT.id());
        assertNull(DeviceService.resolveIssueTemplate(null, moved, DocumentKind.PASSPORT,
                DocStyle.HK_PASSPORT));

        // Applying a refused template contributes nothing at all to the form.
        CompoundTag form = applicantForm();
        DeviceService.applyRegistryTemplate(form, moved, DocStyle.HK_PASSPORT);
        assertFalse(form.contains(DocumentFactory.LOCKED_TEMPLATE_ID));
        assertFalse(form.contains(DocumentFactory.LOCKED_STYLE_COLOR));
        DocumentData doc = DocumentFactory.fromForm(DocStyle.HK_PASSPORT, form);
        assertEquals("", doc.templateId());
        assertEquals(0, doc.tint());

        // A valid one still resolves, so the refusals above are about the mismatch.
        assertSame(passport, DeviceService.resolveIssueTemplate(null, passport,
                DocumentKind.PASSPORT, DocStyle.HK_PASSPORT));
    }

    @Test
    void clientSuppliedTemplateStylingIsNeverTrusted() {
        // The plain (untrusted) keys a client could send: the factory only reads
        // the server's "lockedTemplate*" keys, so all of these are inert.
        CompoundTag form = applicantForm();
        form.putString(DocumentData.TEMPLATE_ID, "forged");
        form.putInt(DocumentData.TEMPLATE_TINT, 0xFF0000);
        form.putString(DocumentData.TEMPLATE_EMBLEM, "skull");
        form.putString(DocumentData.TEMPLATE_TEXTURE, "deadbeef");

        DocumentData doc = DocumentFactory.fromForm(DocStyle.HK_PASSPORT, form);

        assertEquals("", doc.templateId());
        assertEquals(0, doc.tint());
        assertEquals(DocumentData.DEFAULT_EMBLEM, doc.emblem());
        assertEquals("", doc.templateTex());
        assertEquals(DocStyle.HK_PASSPORT.accentColor(), doc.accentColor());
        assertTrue(doc.integrityValid());
    }

    @Test
    void theScrubberDropsClientSuppliedTrustedTemplateKeys() {
        CompoundTag submitted = applicantForm();
        submitted.putString(DocumentFactory.LOCKED_TEMPLATE_ID, "forged");
        submitted.putInt(DocumentFactory.LOCKED_TEMPLATE_TINT, 0x00FF00);
        submitted.putString(DocumentFactory.LOCKED_TEMPLATE_EMBLEM, "skull");
        submitted.putString(DocumentFactory.LOCKED_TEMPLATE_TEX, "deadbeef");
        submitted.putString(DocumentFactory.LOCKED_TEMPLATE_TITLE, "FORGED TITLE");

        CompoundTag scrubbed = DeviceService.applyLockedDocumentName(DeviceBlockEntity.Locks.NONE,
                submitted);
        for (String key : List.of(DocumentFactory.LOCKED_TEMPLATE_ID,
                DocumentFactory.LOCKED_TEMPLATE_TINT, DocumentFactory.LOCKED_TEMPLATE_EMBLEM,
                DocumentFactory.LOCKED_TEMPLATE_TEX, DocumentFactory.LOCKED_TEMPLATE_TITLE)) {
            assertFalse(scrubbed.contains(key), key + " must never survive from the client");
        }
        DocumentData doc = DocumentFactory.fromForm(DocStyle.HK_PASSPORT, scrubbed);
        assertEquals("", doc.templateId());
        assertEquals(0, doc.tint());
    }

    @Test
    void theDeviceLockedTemplateWinsOverTheSubmittedOne() {
        CredentialRegistryData.Template locked = template("locked", DocumentKind.PASSPORT,
                DocStyle.HK_PASSPORT.id(), 0x112233, "锁定的", "", 0x445566, "book", "");
        CredentialRegistryData.Template submitted = template("submitted", DocumentKind.PASSPORT,
                DocStyle.HK_PASSPORT.id(), 0x778899, "提交的", "", 0x99AABB, "star", "");

        assertSame(locked, DeviceService.resolveIssueTemplate(locked, submitted,
                        DocumentKind.PASSPORT, DocStyle.HK_PASSPORT),
                "the machine's layout lock always decides first");

        // A stale locked template that no longer matches falls through…
        CredentialRegistryData.Template stale = template("stale", DocumentKind.PASSPORT,
                DocStyle.CN_PASSPORT.id(), 0x112233, "", "", 0, "", "");
        assertSame(submitted, DeviceService.resolveIssueTemplate(stale, submitted,
                DocumentKind.PASSPORT, DocStyle.HK_PASSPORT));
        // …and without any usable template nothing is applied.
        assertNull(DeviceService.resolveIssueTemplate(stale, null, DocumentKind.PASSPORT,
                DocStyle.HK_PASSPORT));
    }

    @Test
    void aDocumentIssuedBefore2641StillReads() {
        CompoundTag legacy = DocumentFactory.sample(DocStyle.CN_PASSPORT).copyTag();
        legacy.remove(DocumentData.TEMPLATE_ID);
        legacy.remove(DocumentData.TEMPLATE_TINT);
        legacy.remove(DocumentData.TEMPLATE_EMBLEM);
        legacy.remove(DocumentData.TEMPLATE_TEXTURE);

        DocumentData doc = DocumentData.fromTag(legacy);

        assertFalse(doc.hasRegistryTemplate());
        assertEquals("", doc.templateId());
        assertEquals(0, doc.tint());
        assertEquals(DocStyle.CN_PASSPORT.accentColor(), doc.effectiveTint());
        assertEquals(DocumentData.DEFAULT_EMBLEM, doc.emblem());
        assertEquals("", doc.templateTex());
        assertEquals("CHN", doc.issuerCountry(), "the rest of the document is unaffected");
    }

    @Test
    void theTemplateKeysAreSigned() {
        CredentialRegistryData.Template diplomatic = template("diplomatic", DocumentKind.PASSPORT,
                DocStyle.HK_PASSPORT.id(), TEMPLATE_COLOR, "外交护照", "", TEMPLATE_TINT, "star",
                TEX_HASH);
        DocumentData doc = issue(diplomatic, DocStyle.HK_PASSPORT);
        CompoundTag original = doc.copyTag();
        assertTrue(survives(original, tag -> { }), "the issued document verifies");

        // A recoloured / re-emblemed / re-pointed document is no longer authentic.
        assertFalse(survives(original, tag -> tag.putInt(DocumentData.TEMPLATE_TINT, 0x654321)),
                "changing the tint invalidates the signature");
        assertFalse(survives(original, tag -> tag.putString(DocumentData.TEMPLATE_EMBLEM, "skull")));
        assertFalse(survives(original, tag -> tag.putString(DocumentData.TEMPLATE_TEXTURE, "ffff")),
                "swapping the base map invalidates the signature");
        assertFalse(survives(original, tag -> tag.putString(DocumentData.TEMPLATE_ID, "other")));

        // Legitimate writes re-sign, so the document stays verifiable.
        doc.setTint(0x00AA55);
        assertEquals(0x00AA55, doc.tint());
        doc.setEmblem("book");
        assertEquals("book", doc.emblem());
        doc.setTemplateTex("ffff");
        assertEquals("ffff", doc.templateTex());
        assertTrue(doc.integrityValid());
        doc.clearRegistryTemplate();
        assertEquals("", doc.templateId());
        assertEquals(0, doc.tint());
        assertTrue(doc.integrityValid());
    }

    private static boolean survives(CompoundTag base, Consumer<CompoundTag> edit) {
        CompoundTag copy = base.copy();
        edit.accept(copy);
        return DocumentData.fromTag(copy).integrityValid();
    }

    // ------------------------------------------------------- base-map store

    @Test
    void aBaseMapMustBeARealPngWithinTheLimits() {
        CredentialRegistryData data = new CredentialRegistryData();
        assertTrue(data.putTexture("", png(64, 64)), "64x64 is the biggest accepted cover");
        assertEquals(1, data.textureHashes().size());

        CredentialRegistryData.TextureResult tooWide = data.putTextureChecked("", png(65, 64));
        assertFalse(tooWide.ok());
        assertEquals(CredentialRegistryData.TextureRejection.TOO_WIDE, tooWide.rejection());
        assertEquals("message.veriborder.registry.texture_too_wide", tooWide.errorKey());

        assertEquals(CredentialRegistryData.TextureRejection.TOO_TALL,
                data.putTextureChecked("", png(64, 65)).rejection());
        assertEquals(CredentialRegistryData.TextureRejection.TOO_LARGE,
                data.putTextureChecked("", png(64, 64, CredentialRegistryData.MAX_TEXTURE_BYTES + 1))
                        .rejection());
        assertEquals(CredentialRegistryData.TextureRejection.NOT_PNG,
                data.putTextureChecked("", "not a png at all".getBytes(StandardCharsets.UTF_8))
                        .rejection());
        assertEquals(CredentialRegistryData.TextureRejection.NOT_PNG,
                data.putTextureChecked("", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9}).rejection());
        assertEquals(CredentialRegistryData.TextureRejection.EMPTY,
                data.putTextureChecked("", new byte[0]).rejection());
        assertEquals(CredentialRegistryData.TextureRejection.EMPTY,
                data.putTextureChecked("", null).rejection());
        assertEquals(CredentialRegistryData.TextureRejection.BAD_HASH,
                data.putTextureChecked("not-a-hex-hash", png(8, 8)).rejection());
        assertEquals(CredentialRegistryData.TextureRejection.BAD_HASH,
                data.putTextureChecked("a".repeat(CredentialRegistryData.MAX_TEXTURE_HASH_LENGTH + 1),
                        png(8, 8)).rejection());

        assertEquals(1, data.textureHashes().size(), "a refused upload is never stored");
        assertEquals(64, CredentialRegistryData.MAX_TEXTURE_DIMENSION);
        assertEquals(8192, CredentialRegistryData.MAX_TEXTURE_BYTES);
        assertEquals(16, CredentialRegistryData.MAX_TEXTURES);
    }

    @Test
    void baseMapsAreDeduplicatedByHashAndCappedAtSixteen() {
        CredentialRegistryData data = new CredentialRegistryData();
        byte[] cover = png(16, 16, 300);
        String hash = CredentialRegistryData.hashOf(cover);
        assertEquals(64, hash.length(), "the derived hash is a SHA-256 hex digest");

        assertTrue(data.putTexture("", cover));
        assertTrue(data.putTexture(hash, cover.clone()), "the same hash is shared, not duplicated");
        assertTrue(data.putTexture(hash, cover), "re-uploading is a no-op, never a second slot");
        assertEquals(1, data.textureHashes().size());
        assertArrayEquals(cover, data.texture(hash));
        assertArrayEquals(cover, data.texture(hash.toUpperCase(Locale.ROOT)),
                "hashes are compared lowercased");
        assertNull(data.texture("beef"));

        // The registry keeps at most MAX_TEXTURES distinct covers (the shared one
        // above already occupies the first slot).
        int index = 0;
        while (data.textureHashes().size() < CredentialRegistryData.MAX_TEXTURES) {
            assertTrue(data.putTexture(Integer.toHexString(0x100 + index), png(8, 8, 200)),
                    "slot " + index);
            index++;
        }
        assertEquals(CredentialRegistryData.MAX_TEXTURES, data.textureHashes().size());

        CredentialRegistryData.TextureResult full = data.putTextureChecked("ffff", png(8, 8, 200));
        assertFalse(full.ok());
        assertEquals(CredentialRegistryData.TextureRejection.TOO_MANY, full.rejection());
        assertEquals(CredentialRegistryData.MAX_TEXTURES, data.textureHashes().size());
        assertEquals("message.veriborder.registry.texture_too_many", full.errorKey());
    }

    @Test
    void theSnapshotCarriesBaseMapsAsBase64WithinItsBudget() {
        CredentialRegistryData data = new CredentialRegistryData();
        byte[] cover = png(8, 8);
        String hash = data.putTextureChecked("", cover).hash();

        ListTag rows = data.snapshot().getList("textures", Tag.TAG_COMPOUND);
        assertEquals(1, rows.size());
        assertEquals(hash, rows.getCompound(0).getString("hash"));
        assertArrayEquals(cover, Base64.getDecoder().decode(rows.getCompound(0).getString("data")),
                "the base map travels as base64");

        // A registry full of maximum-size covers is trimmed to the wire budget…
        CredentialRegistryData fullData = new CredentialRegistryData();
        for (int i = 0; i < CredentialRegistryData.MAX_TEXTURES; i++) {
            assertTrue(fullData.putTexture(Integer.toHexString(0x100 + i), png(64, 64, 8000)));
        }
        ListTag budgeted = fullData.snapshot().getList("textures", Tag.TAG_COMPOUND);
        assertTrue(budgeted.size() < CredentialRegistryData.MAX_TEXTURES,
                "the snapshot budget trims the payload");
        int chars = 0;
        for (int i = 0; i < budgeted.size(); i++) {
            chars += budgeted.getCompound(i).getString("data").length();
        }
        assertTrue(chars <= CredentialRegistryData.MAX_SNAPSHOT_TEXTURE_CHARS,
                "the snapshot stays inside its budget, was " + chars);

        // …while the save file keeps every base map and reloads them.
        CredentialRegistryData reloaded = CredentialRegistryData.load(fullData.save(new CompoundTag()));
        assertEquals(CredentialRegistryData.MAX_TEXTURES, reloaded.textureHashes().size());
        assertNotNull(reloaded.texture("100"));
    }

    @Test
    void aSavedRegistryRoundTripsItsBaseMaps() {
        CredentialRegistryData data = new CredentialRegistryData();
        byte[] cover = png(12, 12, 400);
        String hash = data.putTextureChecked("", cover).hash();
        data.upsertTemplate(template("diplomatic", DocumentKind.PASSPORT, DocStyle.HK_PASSPORT.id(),
                TEMPLATE_COLOR, "外交护照", "Diplomatic Passport", TEMPLATE_TINT, "star", hash));

        CompoundTag saved = data.save(new CompoundTag());
        CredentialRegistryData reloaded = CredentialRegistryData.load(saved);
        assertEquals(1, reloaded.textureHashes().size());
        assertArrayEquals(cover, reloaded.texture(hash));
        assertEquals(hash, reloaded.templates().get(0).texture());
    }

    @Test
    void aTamperedSaveFileCannotSmuggleAnOversizedBaseMap() {
        CredentialRegistryData data = new CredentialRegistryData();
        CompoundTag saved = data.save(new CompoundTag());
        ListTag rows = new ListTag();
        CompoundTag row = new CompoundTag();
        row.putString("hash", "deadbeef");
        row.putString("data", Base64.getEncoder().encodeToString(
                "not a png, but long enough to look like one".getBytes(StandardCharsets.UTF_8)));
        rows.add(row);
        saved.put("textures", rows);

        CredentialRegistryData reloaded = CredentialRegistryData.load(saved);
        assertTrue(reloaded.textureHashes().isEmpty(), "only real PNG covers may be loaded");
    }

    // --------------------------------------------------- template definition

    @Test
    void templateDefinitionFieldsAreBoundedAndDefaulted() {
        CredentialRegistryData.Template blank = new CredentialRegistryData.Template("t",
                DocumentKind.PASSPORT, "T", DocStyle.HK_PASSPORT.id(), "", "", "", 0, "");
        assertEquals(CredentialRegistryData.DEFAULT_EMBLEM, blank.emblem());
        assertEquals(CredentialRegistryData.DEFAULT_TEMPLATE_TINT, blank.tint(),
                "26.4.2: a saved template is never colourless, so issuing cannot copy 0");
        assertEquals("", blank.texture());
        assertEquals("T", blank.title(true), "no own title falls back to the template name");
        assertEquals("T", blank.title(false));
        assertFalse(blank.hasOwnTitle());
        assertEquals(CredentialRegistryData.DEFAULT_TEMPLATE_TINT, blank.effectiveTint(),
                "no colour picked means the neutral card colour, not the layout palette");

        CredentialRegistryData.Template longFields = new CredentialRegistryData.Template("t",
                DocumentKind.PASSPORT, "T", DocStyle.HK_PASSPORT.id(), "", "", "", 0, "",
                "标".repeat(80), "T".repeat(80), 0, "x".repeat(80), "A".repeat(64));
        assertEquals(CredentialRegistryData.MAX_NAME_LENGTH, longFields.titleZh().length());
        assertEquals(CredentialRegistryData.MAX_NAME_LENGTH, longFields.titleEn().length());
        assertEquals(CredentialRegistryData.MAX_EMBLEM_LENGTH, longFields.emblem().length());
        assertEquals(CredentialRegistryData.MAX_TEXTURE_HASH_LENGTH, longFields.texture().length());
        assertEquals(longFields.texture().toLowerCase(Locale.ROOT), longFields.texture(),
                "a hash is stored lowercase");
        // An over-long or non-hex hash is not a hash at all: the template falls
        // back to the drawn cover instead of keeping half a digest.
        assertEquals("", new CredentialRegistryData.Template("t", DocumentKind.PASSPORT, "T",
                DocStyle.HK_PASSPORT.id(), "", "", "", 0, "", "", "", 0, "",
                "a".repeat(CredentialRegistryData.MAX_TEXTURE_HASH_LENGTH + 1)).texture());
        assertEquals("", new CredentialRegistryData.Template("t", DocumentKind.PASSPORT, "T",
                DocStyle.HK_PASSPORT.id(), "", "", "", 0, "", "", "", 0, "",
                "not-a-hash").texture());

        // A template saved before 26.4.1 keeps its old meaning.
        CredentialRegistryData.Template legacy = new CredentialRegistryData.Template("t",
                DocumentKind.PASSPORT, "T", DocStyle.HK_PASSPORT.id(), "", "", "", 0x336699, "");
        assertFalse(legacy.hasOwnTitle());
        assertEquals(0, legacy.tint());
        assertEquals(0x336699, legacy.effectiveTint());
    }

    @Test
    void aTemplateSavedBefore2641LoadsWithDefaults() {
        CompoundTag legacy = new CompoundTag();
        legacy.putString("id", "old");
        legacy.putString("kind", "passport");
        legacy.putString("name", "Old Template");
        legacy.putString("style", DocStyle.HK_PASSPORT.id());
        legacy.putInt("color", 0x123456);

        CredentialRegistryData.Template template = CredentialRegistryData.Template.fromTag(legacy);

        assertEquals("", template.titleZh());
        assertEquals("", template.titleEn());
        assertEquals(0, template.tint());
        assertEquals(CredentialRegistryData.DEFAULT_EMBLEM, template.emblem());
        assertEquals("", template.texture());
        assertEquals(0x123456, template.effectiveTint());
        assertEquals("Old Template", template.title(true));
    }

    @Test
    void templateDefinitionsSurviveTheSnapshotRoundTrip() {
        CredentialRegistryData data = new CredentialRegistryData();
        String hash = data.putTextureChecked("", png(8, 8)).hash();
        data.upsertTemplate(template("diplomatic", DocumentKind.PASSPORT, DocStyle.HK_PASSPORT.id(),
                TEMPLATE_COLOR, "外交护照", "Diplomatic Passport", TEMPLATE_TINT, "star", hash));

        CredentialRegistryData.Template stored = CredentialRegistryData
                .load(data.snapshot()).templates().get(0);

        assertEquals("外交护照", stored.titleZh());
        assertEquals("Diplomatic Passport", stored.titleEn());
        assertEquals(TEMPLATE_TINT, stored.tint());
        assertEquals("star", stored.emblem());
        assertEquals(hash, stored.texture());
        assertEquals("外交护照", stored.title(true));
        assertEquals("Diplomatic Passport", stored.title(false));
    }

    @Test
    void aTemplateSaveKeepsTheNewDefinitionFields() {
        CompoundTag request = new CompoundTag();
        request.putString("kind", "passport");
        request.putString("name", "Diplomatic Passport");
        request.putString("style", DocStyle.HK_PASSPORT.id());
        request.putString("titleZh", "外交护照");
        request.putString("titleEn", "Diplomatic Passport");
        request.putInt("tint", TEMPLATE_TINT);
        request.putString("emblem", "star");
        request.putString("texture", TEX_HASH.toUpperCase(Locale.ROOT));

        RegistryService.PreparedTemplate prepared = RegistryService.prepareTemplate(request);

        assertTrue(prepared.ok());
        CredentialRegistryData.Template stored = prepared.template();
        assertNotNull(stored);
        assertEquals("外交护照", stored.titleZh());
        assertEquals("Diplomatic Passport", stored.titleEn());
        assertEquals(TEMPLATE_TINT, stored.tint());
        assertEquals("star", stored.emblem());
        assertEquals(TEX_HASH, stored.texture(), "the hash is normalised to lowercase");
    }

    @Test
    void deletingATemplateReleasesOnlyItsUnusedBaseMapSlot() {
        CredentialRegistryData data = new CredentialRegistryData();
        String sharedHash = data.putTextureChecked("", png(8, 8)).hash();
        String ownHash = data.putTextureChecked("", png(9, 9, 240)).hash();
        data.upsertTemplate(template("first", DocumentKind.PASSPORT, DocStyle.HK_PASSPORT.id(),
                TEMPLATE_COLOR, "", "", 0, "", sharedHash));
        data.upsertTemplate(template("second", DocumentKind.PASSPORT, DocStyle.CN_PASSPORT.id(),
                TEMPLATE_COLOR, "", "", 0, "", ownHash));
        assertEquals(2, data.textureHashes().size());

        assertTrue(data.removeTemplate("first"));
        assertEquals(1, data.releaseUnusedTextures(), "the freed slot is released");
        assertFalse(data.hasTexture(sharedHash), "nobody points at it any more");
        assertTrue(data.hasTexture(ownHash), "a base map still used by another template stays");

        assertTrue(data.removeTemplate("second"));
        assertEquals(1, data.releaseUnusedTextures());
        assertTrue(data.textureHashes().isEmpty());
        assertEquals(0, data.releaseUnusedTextures(), "nothing left to release");
    }

    @Test
    void onlyAnIssuingMachineLayoutLockBlocksATemplateDelete() {
        List<RegistryReferences.Evidence> evidence = new ArrayList<>();
        // An issued document keeps its own signed snapshot: it never blocks.
        evidence.add(RegistryReferences.Evidence.country(RegistryReferences.Kind.DOCUMENT,
                "Alex · E1234567", "HKG"));
        // A machine's locked document name or saved preset only borrows the name.
        evidence.add(RegistryReferences.Evidence.template(RegistryReferences.Kind.ISSUER_PRESET,
                "passport_issuer @ 1,2,3", "", "Template diplomatic"));
        evidence.add(RegistryReferences.Evidence.country(RegistryReferences.Kind.TEMPLATE,
                "Template diplomatic", "HKG"));
        assertEquals(0, RegistryService.templateLockCount(evidence, "diplomatic"),
                "documents and presets no longer block a template delete");

        evidence.add(RegistryReferences.Evidence.template(RegistryReferences.Kind.TEMPLATE_LOCK,
                "passport_issuer @ 4,5,6", "diplomatic", ""));
        evidence.add(RegistryReferences.Evidence.template(RegistryReferences.Kind.TEMPLATE_LOCK,
                "passport_issuer @ 7,8,9", "diplomatic", ""));
        evidence.add(RegistryReferences.Evidence.template(RegistryReferences.Kind.TEMPLATE_LOCK,
                "id_issuer @ 1,1,1", "resident", ""));
        assertEquals(2, RegistryService.templateLockCount(evidence, "diplomatic"),
                "two machines lock this template as their layout");
        assertEquals(1, RegistryService.templateLockCount(evidence, "resident"));
        assertEquals(0, RegistryService.templateLockCount(evidence, ""));
        assertEquals(0, RegistryService.templateLockCount(evidence, "gone"));
    }
}
