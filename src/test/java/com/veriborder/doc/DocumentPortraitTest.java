package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.4.1 portrait fields on a signed document.
 *
 * <p>{@code portrait} and {@code portraitOwner} are ordinary signed fields: they
 * must round-trip through NBT and a reload, they must be inside the integrity
 * hash (a hand-edited value has to read as tampered), and a document issued
 * before this round — which has no key at all — must fall back to the holder
 * skin instead of rendering as an empty photo box.</p>
 */
class DocumentPortraitTest {
    private static final String OWNER = "3f1a0b6c-8f5e-4d2a-9c7b-1e2d3c4b5a69";
    private static final String CUSTOM_HASH = "ab".repeat(32);

    @Test
    void aNewDocumentCarriesTheDefaultPortrait() {
        DocumentData document = DocumentData.createEmpty(DocStyle.CN_ID);
        assertEquals("skin", document.portrait(), "an unset portrait reads as the holder skin");
        assertEquals("", document.portraitOwner());
        assertFalse(document.hasPortrait(), "the default is not a customised portrait");
        assertTrue(document.integrityValid());
        assertTrue(document.tag().contains(DocumentData.PORTRAIT),
                "the blank template stores the default reference explicitly");
    }

    @Test
    void setPortraitWritesTheReferenceAndOwnerAndReSigns() {
        DocumentData document = DocumentData.createEmpty(DocStyle.CN_PASSPORT);
        document.setPortrait("p07", OWNER);

        assertEquals("p07", document.portrait());
        assertEquals(OWNER, document.portraitOwner());
        assertTrue(document.hasPortrait());
        assertTrue(document.integrityValid(), "a written portrait is signed like every other edit");
    }

    @Test
    void thePortraitSurvivesACopyAndAReload() {
        DocumentData document = DocumentData.createEmpty(DocStyle.CN_ID);
        document.setName("HOLDER");
        document.setPortrait("custom:" + CUSTOM_HASH, OWNER);

        DocumentData copied = document.copy();
        assertEquals("custom:" + CUSTOM_HASH, copied.portrait());
        assertEquals(OWNER, copied.portraitOwner());

        DocumentData reloaded = DocumentData.fromTag(document.copyTag());
        assertEquals("custom:" + CUSTOM_HASH, reloaded.portrait());
        assertEquals(OWNER, reloaded.portraitOwner());
        assertTrue(reloaded.integrityValid());
    }

    @Test
    void aHandEditedPortraitReadsAsTampered() {
        DocumentData document = DocumentData.createEmpty(DocStyle.CN_ID);
        document.setPortrait("p03", OWNER);
        assertTrue(document.integrityValid());

        // Swap the reference behind the modifier's back: the checksum must notice.
        document.tag().putString(DocumentData.PORTRAIT, "p19");
        assertFalse(document.integrityValid(), "the portrait reference is inside the signed payload");

        // Same for the owner UUID.
        DocumentData other = DocumentData.createEmpty(DocStyle.CN_ID);
        other.setPortrait("p03", OWNER);
        other.tag().putString(DocumentData.PORTRAIT_OWNER, UUID.randomUUID().toString());
        assertFalse(other.integrityValid(), "the portrait owner is signed too");
    }

    @Test
    void aLegacyDocumentWithoutTheKeysStillReads() {
        // A pre-26.4.1 tag: no portrait keys at all, signed with its own payload.
        CompoundTag tag = DocumentData.createEmpty(DocStyle.CN_ID).copyTag();
        tag.remove(DocumentData.PORTRAIT);
        tag.remove(DocumentData.PORTRAIT_OWNER);
        DocumentIntegrity.sign(tag);
        assertTrue(DocumentIntegrity.verify(tag), "fixture must be authentic");

        DocumentData document = DocumentData.fromTag(tag);
        assertTrue(document.integrityValid(), "a document without a portrait is not tampered");
        assertEquals("skin", document.portrait(), "the missing key falls back to the holder skin");
        assertEquals("", document.portraitOwner());
        assertFalse(document.hasPortrait());
    }

    @Test
    void setPortraitNormalisesWhateverItIsGiven() {
        DocumentData document = DocumentData.createEmpty(DocStyle.CN_ID);
        document.setPortrait("  P07  ", OWNER.toUpperCase());
        assertEquals("p07", document.portrait(), "ids are trimmed, lower-cased and validated");
        assertEquals(OWNER, document.portraitOwner(), "the owner is stored canonically");

        document.setPortrait(null, null);
        assertEquals("skin", document.portrait(), "a blank id becomes the default reference");
        assertEquals("", document.portraitOwner());
        assertFalse(document.tag().contains(DocumentData.PORTRAIT_OWNER),
                "a blank owner removes the key instead of storing an empty string");
        assertTrue(document.integrityValid());

        document.setPortrait("custom:" + CUSTOM_HASH, "not-a-uuid");
        assertEquals("custom:" + CUSTOM_HASH, document.portrait());
        assertEquals("", document.portraitOwner(), "a malformed UUID is dropped, the reference stays");
        assertTrue(document.integrityValid());
    }

    @Test
    void aDocumentIssuedFromAFormCarriesThePortrait() {
        CompoundTag form = new CompoundTag();
        form.putString("name", "ZHANG/SAN");
        form.putString("birthDate", "1990-01-01");
        form.putString("number", "12345678");
        form.putString("portrait", "p11");
        form.putString("portraitOwner", OWNER);

        DocumentData document = DocumentFactory.fromForm(DocStyle.CN_ID, form);
        assertEquals("p11", document.portrait());
        assertEquals(OWNER, document.portraitOwner());
        assertTrue(document.integrityValid());
    }

    @Test
    void aFormWithoutAPortraitIssuesTheDefaultOne() {
        CompoundTag form = new CompoundTag();
        form.putString("name", "ZHANG/SAN");
        form.putString("birthDate", "1990-01-01");
        form.putString("number", "12345678");

        DocumentData document = DocumentFactory.fromForm(DocStyle.CN_ID, form);
        assertEquals("skin", document.portrait());
        assertEquals("", document.portraitOwner());
    }
}
