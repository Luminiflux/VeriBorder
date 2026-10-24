package com.veriborder.border;

import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.3 passport-renewal exit: an entry binds the person, not one booklet.
 *
 * <p>A traveller who entered on passport A and then carries a renewed passport B
 * must be able to leave. The identity rule is "same document number, same person
 * key, or — for an entry migrated from 26.2, whose key carries no birth date —
 * the same holder name". An entry without any holder (a pre-26.2 row) stays
 * strict and only matches its recorded document number.</p>
 */
class RenewedPassportExitTest {
    private static final UUID TRAVELLER = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
    private static final BlockPos PORT = new BlockPos(10, 64, 10);
    private static final String DIMENSION = "minecraft:overworld";
    private static final String HOLDER = "张伟 / ZHANG WEI";
    private static final String BIRTH = "1990-05-04";
    private static final String ENTRY_NUMBER = "E12345678";
    private static final String RENEWED_NUMBER = "E87654321";

    /** A CN passport with an explicit holder name, birth date and number. */
    private static DocumentData passport(String holder, String birthDate, String number) {
        CompoundTag form = new CompoundTag();
        form.putString("name", holder);
        form.putString("birthDate", birthDate);
        form.putString("number", number);
        form.putString("sex", "M");
        form.putString("issueDate", "2024-01-01");
        form.putString("expiryDate", "2034-01-01");
        return DocumentFactory.fromForm(DocStyle.CN_PASSPORT, form);
    }

    /** Entry recorded on {@link #ENTRY_NUMBER} for {@link #HOLDER}. */
    private static BorderEntry enteredOnFirstPassport() {
        BorderTravelData data = new BorderTravelData();
        BorderService.beginEntry(data, TRAVELLER, "ALEX", DIMENSION, PORT, "Beijing",
                30, passport(HOLDER, BIRTH, ENTRY_NUMBER), BorderService.EntryMatch.none());
        BorderEntry entry = data.entryForPlayer(TRAVELLER);
        assertNotNull(entry);
        return entry;
    }

    @Test
    void entryRecordsTheHolderAndPersonKeyOfTheEntryDocument() {
        BorderEntry entry = enteredOnFirstPassport();
        assertEquals(HOLDER, entry.holder(), "the entry carries the entry document's holder name");
        assertEquals(HOLDER.toLowerCase() + "|" + BIRTH, entry.personKey());
        assertEquals(ENTRY_NUMBER, entry.documentNumber());
        assertEquals(BorderEntry.KIND_NONE, entry.kind());
    }

    @Test
    void renewedPassportOfTheSameHolderIsTheSameTraveller() {
        BorderEntry entry = enteredOnFirstPassport();
        DocumentData renewed = passport(HOLDER, BIRTH, RENEWED_NUMBER);

        assertNotEquals(entry.documentNumber(), renewed.number(), "the booklet really changed");
        assertTrue(BorderService.sameTraveller(entry, renewed),
                "a renewed passport of the same holder may exit");
        assertFalse(BorderService.isEntryDocument(entry, renewed),
                "the bound credential record lives on the entry document, not on the renewal");
        assertTrue(BorderService.isEntryDocument(entry, passport(HOLDER, BIRTH, ENTRY_NUMBER)),
                "the very document used at entry is still recognised");
    }

    @Test
    void sameDocumentNumberIsAcceptedEvenWhenTheNameChanged() {
        BorderEntry entry = enteredOnFirstPassport();
        // e.g. an officer corrected the printed name after the entry was recorded.
        DocumentData renamed = passport("王五 / WANG WU", BIRTH, ENTRY_NUMBER);
        assertTrue(BorderService.sameTraveller(entry, renamed),
                "the document number alone still proves the same document");
    }

    @Test
    void anotherHolderWithAnotherDocumentIsRejected() {
        BorderEntry entry = enteredOnFirstPassport();
        assertFalse(BorderService.sameTraveller(entry, passport("李四 / LI SI", BIRTH, RENEWED_NUMBER)),
                "a different holder is a different person");
        assertFalse(BorderService.sameTraveller(null, passport(HOLDER, BIRTH, ENTRY_NUMBER)),
                "no entry means no traveller");
        assertFalse(BorderService.sameTraveller(entry, null), "no document means no traveller");
    }

    @Test
    void personKeyComparisonIgnoresCaseAndSurroundingWhitespace() {
        BorderEntry entry = enteredOnFirstPassport();
        assertTrue(BorderService.sameTraveller(entry, passport("张伟 / zhang wei", BIRTH, RENEWED_NUMBER)),
                "case-only differences must not split one traveller");
        assertTrue(BorderService.sameTraveller(entry, passport("  张伟 / ZHANG WEI  ", BIRTH, RENEWED_NUMBER)),
                "surrounding whitespace must not split one traveller");
        assertFalse(BorderService.sameTraveller(entry, passport("张伟 / ZHANG WE", BIRTH, RENEWED_NUMBER)),
                "a different name is still a different person");
    }

    @Test
    void entryWithoutHolderOnlyMatchesItsDocumentNumber() {
        // Exactly the pre-26.2 NBT layout: no "holder" and no "personKey" at all.
        CompoundTag legacy = new CompoundTag();
        legacy.putUUID("player", TRAVELLER);
        legacy.putString("playerName", "ALEX");
        legacy.putString("document", ENTRY_NUMBER);
        legacy.putString("recordId", "");
        legacy.putString("code", "");
        legacy.putString("kind", BorderEntry.KIND_NONE);
        legacy.putString("dimension", DIMENSION);
        legacy.putInt("gateX", PORT.getX());
        legacy.putInt("gateY", PORT.getY());
        legacy.putInt("gateZ", PORT.getZ());
        legacy.putLong("entryTime", 1_700_000_000_000L);
        legacy.putString("port", "Beijing");
        legacy.putInt("overstayDays", 30);
        legacy.putBoolean("overstay", false);

        BorderEntry loaded = BorderEntry.fromTag(legacy);
        assertNotNull(loaded);
        assertEquals("", loaded.holder(), "missing holder loads as an empty string, never null");
        assertEquals("doc:" + ENTRY_NUMBER, loaded.personKey(),
                "without a holder the document number is the only identity");
        assertTrue(BorderService.sameTraveller(loaded, passport(HOLDER, BIRTH, ENTRY_NUMBER)),
                "the entry document still matches by number");
        assertFalse(BorderService.sameTraveller(loaded, passport(HOLDER, BIRTH, RENEWED_NUMBER)),
                "a holder-less entry keeps the strict document-number rule");
    }

    @Test
    void holderSurvivesTheNbtRoundTrip() {
        BorderEntry entry = enteredOnFirstPassport();
        CompoundTag tag = entry.toTag();
        assertTrue(tag.contains("holder"));
        assertTrue(tag.contains("personKey"));
        assertEquals(HOLDER, tag.getString("holder"));
        assertEquals(entry, BorderEntry.fromTag(tag));
        assertEquals(HOLDER, entry.toOfficerTag().getString("holder"),
                "the officer view also shows who the entry belongs to");
    }

    @Test
    void reissuedDocumentReplacesTheEntryAndCarriesItsHolder() {
        BorderTravelData data = new BorderTravelData();
        BorderService.beginEntry(data, TRAVELLER, "ALEX", DIMENSION, PORT, "Beijing",
                30, passport(HOLDER, BIRTH, ENTRY_NUMBER), BorderService.EntryMatch.none());

        DocumentData reissued = passport(HOLDER, BIRTH, RENEWED_NUMBER);
        BorderService.replaceEntry(data, TRAVELLER, "ALEX", DIMENSION, PORT, "Beijing",
                30, reissued, BorderService.EntryMatch.none());

        BorderEntry refreshed = data.entryForPlayer(TRAVELLER);
        assertNotNull(refreshed);
        assertEquals(RENEWED_NUMBER, refreshed.documentNumber());
        assertEquals(HOLDER, refreshed.holder(), "the re-issued document's holder is recorded");
        assertEquals(1, data.entryCount(), "the previous entry is replaced, never duplicated");
    }
}
