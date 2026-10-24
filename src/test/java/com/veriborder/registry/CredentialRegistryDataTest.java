package com.veriborder.registry;

import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentFactory;
import com.veriborder.doc.DocumentKind;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Credential registry (26.3 "注册器") data contract: three-letter country codes
 * (which are also the MRZ code), global template list scoped by document kind,
 * snapshot round-trip and the signed {@code issuerCountry} field.
 */
class CredentialRegistryDataTest {
    private static CredentialRegistryData.Country country(String code, String zh, String en) {
        return new CredentialRegistryData.Country(code, zh, en, zh, "");
    }

    @Test
    void countryCodesAreThreeUppercaseLetters() {
        assertEquals("CHN", CredentialRegistryData.normalizeCode("chn"));
        assertEquals("HKG", CredentialRegistryData.normalizeCode(" hkg "));
        assertEquals("", CredentialRegistryData.normalizeCode("CH"));
        assertEquals("", CredentialRegistryData.normalizeCode("CHINA"));
        assertEquals("", CredentialRegistryData.normalizeCode("C1N"));
        assertFalse(country("中国", "中国", "China").valid(), "a non-code entry is invalid");
        assertTrue(country("CHN", "中国", "China").valid());
    }

    @Test
    void countriesAreUniqueAndUpsertedByCode() {
        CredentialRegistryData data = new CredentialRegistryData();
        assertTrue(data.upsertCountry(country("CHN", "中国", "China")));
        assertTrue(data.upsertCountry(country("HKG", "中国香港", "Hong Kong")));
        assertEquals(2, data.countries().size());

        // Re-saving the same code updates the row instead of duplicating it.
        assertTrue(data.upsertCountry(new CredentialRegistryData.Country("CHN", "中华人民共和国",
                "People's Republic of China", "中国", "note")));
        assertEquals(2, data.countries().size());
        assertEquals("中华人民共和国", data.country("CHN").nameZh());
        assertEquals("note", data.country("chn").note());

        assertTrue(data.removeCountry("HKG"));
        assertNull(data.country("HKG"));
        assertEquals(1, data.countries().size());
    }

    @Test
    void matchingAcceptsCodeNamesAndDefaultNationality() {
        CredentialRegistryData data = new CredentialRegistryData();
        data.upsertCountry(new CredentialRegistryData.Country("CHN", "中华人民共和国",
                "People's Republic of China", "中国", ""));
        assertEquals("CHN", data.matchCountry("CHN").code());
        assertEquals("CHN", data.matchCountry("中国").code());
        assertEquals("CHN", data.matchCountry("中华人民共和国").code());
        assertEquals("CHN", data.matchCountry("People's Republic of China").code());
        assertNull(data.matchCountry("日本"));
    }

    @Test
    void templatesAreScopedToDocumentKindAndIssuer() {
        CredentialRegistryData data = new CredentialRegistryData();
        assertTrue(data.upsertTemplate(new CredentialRegistryData.Template("", DocumentKind.PASSPORT,
                "公务护照", DocStyle.CN_PASSPORT.id(), "CHN", "", "passport_issuer", 0x336699, "book")));
        assertTrue(data.upsertTemplate(new CredentialRegistryData.Template("", DocumentKind.PERMIT,
                "港澳通行证", DocStyle.HK_MACAO_PERMIT.id(), "", "", "", 0x1D4F91, "card")));
        assertEquals(2, data.templates().size());

        // A blank id is derived from kind + name, so re-saving updates.
        assertTrue(data.upsertTemplate(new CredentialRegistryData.Template("", DocumentKind.PASSPORT,
                "公务护照", DocStyle.CN_PASSPORT.id(), "CHN", "", "passport_issuer", 0x336699, "book")));
        assertEquals(2, data.templates().size(), "a re-saved template updates in place");

        assertEquals(1, data.templatesFor(DocumentKind.PASSPORT, "passport_issuer").size());
        assertEquals(0, data.templatesFor(DocumentKind.PASSPORT, "id_issuer").size(),
                "a template is only offered to its allowed issuer types");
        assertEquals(1, data.templatesFor(DocumentKind.PERMIT, "permit_issuer").size(),
                "a blank issuer list means every issuer of that kind");
        assertEquals(0, data.templatesFor(DocumentKind.ID_CARD, "id_issuer").size(),
                "a template never leaks into another credential kind");
    }

    @Test
    void aTemplateNeedsOnlyItsIdAndName() {
        // 26.4 split: the style is no longer part of valid(); it is normalised by
        // the server instead. Identity + name remain mandatory.
        CredentialRegistryData.Template blankStyle = new CredentialRegistryData.Template("id",
                DocumentKind.PASSPORT, "Name", "", "", "", "", 0, "");
        assertTrue(blankStyle.valid());
        assertFalse(blankStyle.styleMatchesKind());
        assertEquals(DocStyle.CN_PASSPORT.id(), blankStyle.normalized().style());
        assertTrue(blankStyle.normalized().valid());

        assertFalse(new CredentialRegistryData.Template("", DocumentKind.PASSPORT, "Name",
                DocStyle.CN_PASSPORT.id(), "", "", "", 0, "").valid(), "an id is required");
        assertFalse(new CredentialRegistryData.Template("id", DocumentKind.PASSPORT, "",
                DocStyle.CN_PASSPORT.id(), "", "", "", 0, "").valid(), "a name is required");
    }

    @Test
    void aWrongStyleIsForcedBackToItsKindInsteadOfRejectingTheSave() {
        // 26.4: the style is a normalisation concern, never a reason to refuse an
        // otherwise valid template (the old failure surfaced as "needs a name").
        CredentialRegistryData data = new CredentialRegistryData();
        assertTrue(data.upsertTemplate(new CredentialRegistryData.Template("", DocumentKind.PASSPORT,
                "Wrong", DocStyle.CN_ID.id(), "", "", "", 0xFFFFFF, "")));
        assertEquals(1, data.templates().size());
        assertEquals(DocStyle.CN_PASSPORT.id(), data.templates().get(0).style(),
                "a wrong-kind style falls back to the first layout of the kind");
    }

    @Test
    void prepareTemplateRepairsBlankUnknownAndWrongKindStyles() {
        CompoundTag request = new CompoundTag();
        request.putString("kind", "passport");
        request.putString("name", "公务护照");
        request.putString("style", DocStyle.CN_ID.id());
        RegistryService.PreparedTemplate wrongKind = RegistryService.prepareTemplate(request);
        assertTrue(wrongKind.ok());
        assertEquals(DocStyle.CN_PASSPORT.id(), wrongKind.template().style());
        assertTrue(wrongKind.styleCorrected(), "the operator is told the layout was replaced");

        request.putString("style", "");
        RegistryService.PreparedTemplate blank = RegistryService.prepareTemplate(request);
        assertTrue(blank.ok());
        assertEquals(DocStyle.CN_PASSPORT.id(), blank.template().style());

        request.putString("style", "not_a_layout");
        RegistryService.PreparedTemplate unknown = RegistryService.prepareTemplate(request);
        assertTrue(unknown.ok());
        assertEquals(DocStyle.CN_PASSPORT.id(), unknown.template().style());

        // A correct style is left untouched and is not reported as corrected.
        request.putString("style", DocStyle.JP_PASSPORT.id());
        RegistryService.PreparedTemplate valid = RegistryService.prepareTemplate(request);
        assertTrue(valid.ok());
        assertEquals(DocStyle.JP_PASSPORT.id(), valid.template().style());
        assertFalse(valid.styleCorrected());
    }

    @Test
    void everyCredentialKindCanBeSavedWithItsFallbackStyle() {
        CredentialRegistryData data = new CredentialRegistryData();
        for (DocumentKind kind : DocumentKind.values()) {
            CompoundTag request = new CompoundTag();
            request.putString("kind", kind.id());
            request.putString("name", "模板 " + kind.id());
            request.putString("style", "broken");
            RegistryService.PreparedTemplate prepared = RegistryService.prepareTemplate(request);
            assertTrue(prepared.ok(), kind + " must be saveable");
            assertTrue(data.upsertTemplate(prepared.template()), kind + " must be stored");
        }
        assertEquals(3, data.templates().size());
        for (CredentialRegistryData.Template template : data.templates()) {
            assertEquals(DocStyle.forKind(template.kind()).get(0).id(), template.style());
        }
    }

    @Test
    void aBlankNameIsStillRejected() {
        CompoundTag request = new CompoundTag();
        request.putString("kind", "passport");
        request.putString("name", "   ");
        request.putString("style", DocStyle.CN_PASSPORT.id());
        assertEquals(RegistryService.TemplateSaveStatus.MISSING_NAME,
                RegistryService.prepareTemplate(request).status());
        assertFalse(new CredentialRegistryData().upsertTemplate(new CredentialRegistryData.Template(
                "", DocumentKind.PASSPORT, "  ", DocStyle.CN_PASSPORT.id(), "", "", "", 0, "")),
                "a nameless template is never stored");
    }

    @Test
    void anUnknownCredentialKindIsRejected() {
        CompoundTag request = new CompoundTag();
        request.putString("kind", "spaceship");
        request.putString("name", "Unknown");
        request.putString("style", "");
        assertEquals(RegistryService.TemplateSaveStatus.INVALID_KIND,
                RegistryService.prepareTemplate(request).status());
    }

    @Test
    void theFinalTemplateIsEchoedBackToTheEditor() {
        CredentialRegistryData data = new CredentialRegistryData();
        data.upsertTemplate(new CredentialRegistryData.Template("", DocumentKind.PASSPORT,
                "公务护照", "broken", "CHN", "", "", 0x336699, "book"));
        CredentialRegistryData.Template saved = data.templates().get(0);
        CompoundTag payload = RegistryService.editorPayload(null, data, saved);
        assertTrue(payload.contains("highlightTemplate", CompoundTag.TAG_COMPOUND));
        CompoundTag echo = payload.getCompound("highlightTemplate");
        assertEquals(saved.id(), echo.getString("id"));
        assertEquals(DocStyle.CN_PASSPORT.id(), echo.getString("style"),
                "the editor receives the normalised style, not the submitted one");
    }

    @Test
    void snapshotRoundTripsThroughSavedData() {
        CredentialRegistryData data = new CredentialRegistryData();
        data.upsertCountry(country("CHN", "中国", "China"));
        data.upsertTemplate(new CredentialRegistryData.Template("", DocumentKind.PASSPORT,
                "公务护照", DocStyle.CN_PASSPORT.id(), "CHN", "name,number", "passport_issuer",
                0x336699, "book"));

        CompoundTag snapshot = data.snapshot();
        CredentialRegistryData loaded = CredentialRegistryData.load(snapshot);
        assertEquals(1, loaded.countries().size());
        assertEquals("中国", loaded.countries().get(0).nameZh());
        assertEquals(1, loaded.templates().size());
        assertEquals("CHN", loaded.templates().get(0).mrzCountry());
        assertEquals(CredentialRegistryData.SCHEMA, loaded.snapshot().getInt("schema"));
    }

    @Test
    void issuedDocumentCarriesASignedIssuerCountry() {
        DocumentData doc = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        assertEquals("CHN", doc.issuerCountry(),
                "the layout's official code seeds the single issuing-country field");
        assertTrue(doc.mrz1().startsWith("P<CHN"), "the passport MRZ reads the same field");
        assertTrue(doc.integrityValid());

        doc.setIssuerCountry("usa");
        assertEquals("USA", doc.issuerCountry());
        assertTrue(doc.mrz1().startsWith("P<USA"), "changing the country changes MRZ line 1");
        assertTrue(doc.integrityValid(), "writing the issuer country re-signs the document");

        doc.setIssuerCountry("");
        assertEquals("", doc.issuerCountry());
        assertEquals(DocStyle.CN_PASSPORT.mrzCountry(), doc.mrzCountry(),
                "a blank field falls back to the layout code for display/MRZ");
        assertTrue(doc.integrityValid());
    }

    @Test
    void templateDefaultsApplyToAnIssuerForm() {
        CredentialRegistryData.Template template = new CredentialRegistryData.Template("p1",
                DocumentKind.PASSPORT, "公务护照", DocStyle.JP_PASSPORT.id(), "JPN", "", "",
                0x112233, "book");
        CompoundTag form = new CompoundTag();
        CompoundTag applied = template.applyTo(form);
        assertNotNull(applied);
        assertEquals(DocStyle.JP_PASSPORT.id(), applied.getString("style"));
        assertEquals("JPN", applied.getString(DocumentData.ISSUER_COUNTRY));
        assertEquals(0x112233, applied.getInt("customColor"));
        assertEquals(0, form.size(), "the input form is not mutated");
    }

    // ---------------------------------------------- 26.3 round 12 additions

    @Test
    void aTemplateBelongsToItsOwnIssuerAutomatically() {
        // The old "allowed issuers" text field is gone: a template is offered by
        // exactly the issuer of its credential kind, whatever the legacy NBT says.
        CredentialRegistryData data = new CredentialRegistryData();
        data.upsertTemplate(new CredentialRegistryData.Template("", DocumentKind.PASSPORT,
                "公务护照", DocStyle.CN_PASSPORT.id(), "CHN", "", "id_issuer", 0x336699, "book"));
        data.upsertTemplate(new CredentialRegistryData.Template("", DocumentKind.ID_CARD,
                "居住证", DocStyle.CN_ID.id(), "CHN", "", "", 0x224466, "card"));
        data.upsertTemplate(new CredentialRegistryData.Template("", DocumentKind.PERMIT,
                "港澳通行证", DocStyle.HK_MACAO_PERMIT.id(), "CHN", "", "visa_issuer", 0x1D4F91, "card"));

        assertEquals(1, data.templatesFor(DocumentKind.PASSPORT, "passport_issuer").size());
        assertEquals(0, data.templatesFor(DocumentKind.PASSPORT, "id_issuer").size(),
                "a passport template is never offered by the ID issuer");
        assertEquals(1, data.templatesFor(DocumentKind.ID_CARD, "id_issuer").size());
        assertEquals(1, data.templatesFor(DocumentKind.PERMIT, "permit_issuer").size(),
                "a legacy issuer list is ignored in favour of the kind's own issuer");
        assertEquals("id_issuer", DocumentKind.ID_CARD.issuerId());
        assertEquals("passport_issuer", DocumentKind.PASSPORT.issuerId());
        assertEquals("permit_issuer", DocumentKind.PERMIT.issuerId());
    }

    @Test
    void theInternalNoteIsCappedAt64CharactersAndTheIconHasADefault() {
        String longNote = "x".repeat(CredentialRegistryData.MAX_NOTE_LENGTH + 40);
        CredentialRegistryData.Country country = new CredentialRegistryData.Country("CHN", "中国", "China",
                "中国", longNote);
        assertEquals(CredentialRegistryData.MAX_NOTE_LENGTH, country.note().length(),
                "the internal note is bounded to 64 characters");
        assertEquals(64, CredentialRegistryData.MAX_NOTE_LENGTH);

        CredentialRegistryData.Template template = new CredentialRegistryData.Template("t",
                DocumentKind.PASSPORT, "T", DocStyle.CN_PASSPORT.id(), "CHN", "", "", 0xFFFFFF, "");
        assertEquals(CredentialRegistryData.DEFAULT_ICON, template.icon(),
                "a blank icon falls back to the default preset id");
    }

    @Test
    void recodifyMovesTheRowAndFollowsTemplateDefaults() {
        CredentialRegistryData data = new CredentialRegistryData();
        data.upsertCountry(country("CHN", "中国", "China"));
        data.upsertCountry(country("HKG", "中国香港", "Hong Kong"));
        data.upsertTemplate(new CredentialRegistryData.Template("", DocumentKind.PASSPORT,
                "公务护照", DocStyle.CN_PASSPORT.id(), "CHN", "", "", 0x336699, "book"));

        assertTrue(data.recodifyCountry("CHN", "PRC"));
        assertNull(data.country("CHN"), "the old key is gone");
        assertEquals("中国", data.country("prc").nameZh());
        assertEquals("PRC", data.templates().get(0).mrzCountry(),
                "a template default follows the new code");
        assertEquals(2, data.countries().size(), "the row moved instead of duplicating");

        // Refusals: invalid code, unknown code, code already in use.
        assertFalse(data.recodifyCountry("PRC", "PRC"));
        assertFalse(data.recodifyCountry("PRC", "TOOLONG"));
        assertFalse(data.recodifyCountry("XXX", "ABC"));
        assertFalse(data.recodifyCountry("PRC", "HKG"), "an existing code is never overwritten");
        assertEquals(2, data.countries().size());
    }

    @Test
    void recodeAlsoRepointsGateWhitelists() {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        list.add(net.minecraft.nbt.StringTag.valueOf("CHN"));
        list.add(net.minecraft.nbt.StringTag.valueOf("HKG"));
        net.minecraft.nbt.ListTag renamed = RegistryService.rename(list, "chn", "prc");
        assertEquals(2, renamed.size());
        assertEquals("PRC", renamed.getString(0));
        assertEquals("HKG", renamed.getString(1));
        // A rename that collapses two entries into one stays deduplicated.
        net.minecraft.nbt.ListTag duplicate = new net.minecraft.nbt.ListTag();
        duplicate.add(net.minecraft.nbt.StringTag.valueOf("PRC"));
        duplicate.add(net.minecraft.nbt.StringTag.valueOf("CHN"));
        net.minecraft.nbt.ListTag collapsed = RegistryService.rename(duplicate, "CHN", "PRC");
        assertEquals(1, collapsed.size(), "the whitelist never keeps duplicate codes");
        assertEquals("PRC", collapsed.getString(0));
    }
}
