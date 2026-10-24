package com.veriborder.border;

import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentFactory;
import com.veriborder.doc.DocumentKind;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.3 "one person, many documents" rules: an entry is keyed by the holder
 * signature ({@code normalize(name)|birthDate}), a second document of the same
 * holder resolves to the same entry, and the document index always points at the
 * document the entry was last recorded on.
 */
class MultiDocumentEntryTest {
    private static final UUID TRAVELLER = UUID.fromString("12345678-1234-5678-1234-567812345678");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("87654321-4321-8765-4321-876543218765");
    private static final BlockPos PORT = new BlockPos(10, 64, 10);
    private static final String DIMENSION = "minecraft:overworld";
    private static final String HOLDER = "张伟 / ZHANG WEI";
    private static final String BIRTH = "1990-05-04";

    /** A CN passport with an explicit holder, birth date and number. */
    private static DocumentData passport(String holder, String birthDate, String number) {
        CompoundTag form = new CompoundTag();
        form.putString("name", holder);
        form.putString("number", number);
        form.putString("sex", "M");
        form.putString("birthDate", birthDate);
        form.putString("issueDate", "2024-01-01");
        form.putString("expiryDate", "2034-01-01");
        return DocumentFactory.fromForm(DocStyle.CN_PASSPORT, form);
    }

    private static BorderEntry enter(BorderTravelData data, DocumentData doc, UUID account) {
        BorderEntry entry = BorderService.beginEntry(data, account, "ALEX", DIMENSION, PORT, "Beijing",
                30, doc, BorderService.EntryMatch.none());
        assertNotNull(entry);
        return entry;
    }

    @Test
    void personKeyIsNormalisedNamePlusBirthDate() {
        assertEquals("张伟 / zhang wei", DocumentData.normalizeHolderName("  张伟 / ZHANG WEI  "));
        assertEquals("张伟 / zhang wei|1990-05-04",
                DocumentData.computePersonKey(" 张伟 / ZHANG WEI ", " 1990-05-04 "));

        DocumentData doc = passport(HOLDER, BIRTH, "E12345678");
        assertEquals(DocumentData.computePersonKey(doc.name(), doc.birthDate()), doc.personKey());
        assertEquals("张伟 / zhang wei|1990-05-04", doc.personKey());
        assertTrue(doc.integrityValid());

        // The key is signed: editing it by hand breaks the integrity check.
        CompoundTag tampered = doc.copyTag();
        tampered.putString(DocumentData.PERSON_KEY, "someone else|2000-01-01");
        assertFalse(DocumentData.fromTag(tampered).integrityValid());

        // An officer renaming the holder re-signs a new key.
        DocumentData renamed = DocumentData.fromTag(doc.copyTag());
        renamed.setName("王五 / WANG WU");
        assertNotEquals(doc.personKey(), renamed.personKey());
        assertTrue(renamed.integrityValid());

        // The key survives NBT and is *not* part of the document number identity.
        DocumentData loaded = DocumentData.fromTag(doc.copyTag());
        assertEquals(doc.personKey(), loaded.personKey());
        assertEquals(doc.personKey(), BorderService.personKey(doc));
    }

    @Test
    void entryIsKeyedByPersonAndIndexedByDocument() {
        BorderTravelData data = new BorderTravelData();
        DocumentData doc = passport(HOLDER, BIRTH, "E12345678");
        BorderEntry entry = enter(data, doc, TRAVELLER);

        assertEquals(doc.personKey(), entry.personKey());
        assertEquals("E12345678", entry.documentNumber());
        assertEquals(HOLDER, entry.holder());
        assertEquals(BorderEntry.KIND_NONE, entry.kind());
        assertFalse(entry.consumesRecord(), "an issue-policy entry consumes nothing on exit");
        assertEquals(1, data.entryCount());
        assertEquals(entry, data.entry(doc.personKey()));
        assertEquals(entry, data.entryByDocument("E12345678"));
        assertEquals(entry, data.entryForPlayer(TRAVELLER));
        assertEquals(entry, BorderService.findEntry(data, doc));
    }

    @Test
    void secondDocumentOfTheSameHolderResolvesTheSameEntry() {
        BorderTravelData data = new BorderTravelData();
        DocumentData first = passport(HOLDER, BIRTH, "E12345678");
        BorderEntry entry = enter(data, first, TRAVELLER);

        // A second passport of the same holder (different booklet, same person).
        DocumentData second = passport(HOLDER, BIRTH, "E87654321");
        assertNotEquals(first.number(), second.number());
        assertEquals(first.personKey(), second.personKey());

        assertEquals(entry, BorderService.findEntry(data, second),
                "the person key, not the document number, identifies the traveller");
        assertTrue(BorderService.sameTraveller(entry, second));
        assertFalse(BorderService.isEntryDocument(entry, second),
                "the bound credential record lives on the first booklet only");
        assertTrue(BorderService.isEntryDocument(entry, first));

        // A permit of the same holder is the same person as well.
        CompoundTag permitForm = new CompoundTag();
        permitForm.putString("name", HOLDER);
        permitForm.putString("birthDate", BIRTH);
        permitForm.putString("number", "P12345678");
        DocumentData permit = DocumentFactory.fromForm(DocStyle.HK_MACAO_PERMIT, permitForm);
        assertEquals(DocumentKind.PERMIT, permit.kind());
        assertEquals(first.personKey(), permit.personKey());
        assertEquals(entry, BorderService.findEntry(data, permit));
    }

    @Test
    void sameHolderWithAnotherBirthDateIsAnotherPerson() {
        BorderTravelData data = new BorderTravelData();
        DocumentData first = passport(HOLDER, BIRTH, "E12345678");
        BorderEntry entry = enter(data, first, TRAVELLER);

        DocumentData twin = passport(HOLDER, "1990-05-05", "E87654321");
        assertNotEquals(first.personKey(), twin.personKey());
        assertNull(BorderService.findEntry(data, twin));
        assertFalse(BorderService.sameTraveller(entry, twin));
        // The name alone would have matched: the birth date is what splits them.
        assertEquals(1, data.entryCount());
    }

    @Test
    void replacingTheEntryMovesTheDocumentIndex() {
        BorderTravelData data = new BorderTravelData();
        DocumentData first = passport(HOLDER, BIRTH, "E12345678");
        enter(data, first, TRAVELLER);

        DocumentData renewed = passport(HOLDER, BIRTH, "E87654321");
        BorderEntry replaced = BorderService.replaceEntry(data, TRAVELLER, "ALEX", DIMENSION, PORT,
                "Beijing", 30, renewed, BorderService.EntryMatch.none());

        assertEquals(1, data.entryCount(), "the person keeps exactly one entry");
        assertEquals(replaced, data.entry(first.personKey()));
        assertEquals("E87654321", replaced.documentNumber());
        assertNull(data.entryByDocument("E12345678"), "the old booklet no longer resolves");
        assertEquals(replaced, data.entryByDocument("E87654321"));
    }

    @Test
    void aDifferentAccountPresentingTheSamePersonDoesNotDuplicateTheEntry() {
        BorderTravelData data = new BorderTravelData();
        DocumentData doc = passport(HOLDER, BIRTH, "E12345678");
        BorderEntry entry = enter(data, doc, TRAVELLER);

        // The entry follows the person, so another account presenting the same
        // holder resolves the very same row.
        assertEquals(entry, BorderService.findEntry(data, doc));
        assertEquals(entry, data.entryForPlayer(TRAVELLER));
        assertNull(data.entryForPlayer(OTHER_ACCOUNT));
        assertEquals(1, data.entryCount());
    }

    @Test
    void officerClearRemovesThePersonEntry() {
        BorderTravelData data = new BorderTravelData();
        DocumentData doc = passport(HOLDER, BIRTH, "E12345678");
        BorderEntry entry = enter(data, doc, TRAVELLER);

        assertEquals(entry, data.removeEntryForPlayer(TRAVELLER));
        assertNull(data.entry(doc.personKey()));
        assertNull(data.entryByDocument("E12345678"));
        assertEquals(0, data.entryCount());
        assertNull(data.removeEntryForPlayer(TRAVELLER));
    }

    @Test
    void namelessDocumentFallsBackToTheDocumentNumberKey() {
        DocumentData blank = DocumentData.createEmpty(DocStyle.CN_PASSPORT);
        assertEquals("", blank.name());
        blank.setNumber("E00000001");
        assertEquals("doc:E00000001", BorderService.personKey(blank));

        BorderTravelData data = new BorderTravelData();
        BorderEntry entry = enter(data, blank, TRAVELLER);
        assertEquals("doc:E00000001", entry.personKey());
        assertEquals(entry, data.entryByDocument("E00000001"));
    }

    @Test
    void entryCapturesItsGateIdentityAndAllowance() {
        BorderTravelData data = new BorderTravelData();
        DocumentData doc = passport(HOLDER, BIRTH, "E12345678");
        BorderEntry entry = enter(data, doc, TRAVELLER);
        assertEquals(30, entry.overstayDays());
        assertEquals("Beijing", entry.port());
        assertEquals(PORT, entry.gatePos());
        assertEquals(DIMENSION, entry.dimension());
        assertEquals("ALEX", entry.playerName());
        assertEquals(TRAVELLER, entry.player());
    }
}
