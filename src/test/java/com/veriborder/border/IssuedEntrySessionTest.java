package com.veriborder.border;

import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentFactory;
import com.veriborder.doc.DocumentKind;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.2/26.3 issue-entry regression tests: a passport/permit issued "as entered"
 * must create an entry bound to the number of the document that was actually
 * written to the item, must replace a stale entry, and an identity card must
 * never create one.
 */
class IssuedEntrySessionTest {
    private static final UUID TRAVELLER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final BlockPos PORT = new BlockPos(10, 64, 10);
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void issuedPassportEntryIsBoundToTheIssuedDocumentNumber() {
        BorderTravelData data = new BorderTravelData();
        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);

        BorderService.beginEntry(data, TRAVELLER, "ALEX", DIMENSION, PORT, "Beijing",
                30, passport, BorderService.EntryMatch.none());

        BorderEntry entry = data.entryForPlayer(TRAVELLER);
        assertNotNull(entry, "an issued passport with entry state creates an entry");
        assertEquals(passport.number(), entry.documentNumber(),
                "the entry names the document that was written to the item");
        assertEquals(passport.personKey(), entry.personKey());
        assertEquals(BorderEntry.KIND_NONE, entry.kind());
        assertEquals("ALEX", entry.playerName());
        assertEquals(1, data.entryCount(), "one entry per person");
    }

    @Test
    void staleEntryIsReplacedByTheNewlyIssuedDocument() {
        BorderTravelData data = new BorderTravelData();
        BorderEntry stale = new BorderEntry("old person|1970-01-01", "E00000000", "OLD PERSON", "", "",
                BorderEntry.KIND_NONE, DIMENSION, PORT, 1L, "Old Port", 30, false, TRAVELLER, "ALEX");
        data.putEntry(stale);
        assertEquals(1, data.entryCount());

        DocumentData issued = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        assertTrue(BorderService.shouldAdoptIssuedEntry(DocumentKind.PASSPORT, true, stale, issued.number()),
                "an entry bound to another document is refreshed, not skipped");

        BorderService.replaceEntry(data, TRAVELLER, "ALEX", DIMENSION, PORT, "Beijing",
                30, issued, BorderService.EntryMatch.none());

        assertNotNull(data.entryForPlayer(TRAVELLER));
        assertEquals(issued.number(), data.entryForPlayer(TRAVELLER).documentNumber());
        assertNull(data.entry(stale.personKey()), "the stale row of this account is dropped");
        assertEquals(1, data.entryCount(), "the stale entry is replaced, never duplicated");
        assertEquals(issued.number(), data.entryByDocument(issued.number()).documentNumber());
    }

    @Test
    void idCardNeverCreatesAnEntry() {
        DocumentData id = DocumentFactory.sample(DocStyle.CN_ID);
        assertEquals(DocumentKind.ID_CARD, id.kind());
        assertFalse(BorderService.shouldAdoptIssuedEntry(DocumentKind.ID_CARD, true, null, id.number()),
                "identity cards are domestic documents and never start an entry");
        assertFalse(BorderService.shouldAdoptIssuedEntry(DocumentKind.ID_CARD, true,
                new BorderEntry("old|1970-01-01", "OLD", "OLD", "", "", BorderEntry.KIND_NONE,
                        DIMENSION, PORT, 1L, "Old Port", 30, false), id.number()));

        // Simulate the issuer policy: no decision -> no store call -> no entry.
        BorderTravelData data = new BorderTravelData();
        if (BorderService.shouldAdoptIssuedEntry(DocumentKind.ID_CARD, true, null, id.number())) {
            BorderService.beginEntry(data, TRAVELLER, "ALEX", DIMENSION, PORT, "Beijing",
                    30, id, BorderService.EntryMatch.none());
        }
        assertNull(data.entryForPlayer(TRAVELLER));
    }

    @Test
    void permitIsAdoptedButOnlyWhenTheEntryPolicyIsOn() {
        DocumentData permit = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        assertTrue(BorderService.shouldAdoptIssuedEntry(DocumentKind.PERMIT, true, null, permit.number()));
        assertFalse(BorderService.shouldAdoptIssuedEntry(DocumentKind.PERMIT, false, null, permit.number()),
                "issueEntryState off keeps the traveller outside");
        assertFalse(BorderService.shouldAdoptIssuedEntry(DocumentKind.PASSPORT, true,
                        new BorderEntry("old|1970-01-01", permit.number(), "OLD", "", "",
                                BorderEntry.KIND_NONE, DIMENSION, PORT, 1L, "Port", 30, false),
                        permit.number()),
                "an entry already bound to the same number is left untouched");
    }
}
