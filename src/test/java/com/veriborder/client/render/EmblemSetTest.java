package com.veriborder.client.render;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.4.1 crest ("徽记") index mapping.
 *
 * <p>The crest id is the only thing a document stores; the item model is selected
 * by {@code index / 16}. A wrong index paints the wrong crest, so the mapping is
 * pinned here: the documented crest set must exist, the grid order must not move,
 * and every unknown/blank/legacy spelling must fall back to the default crest
 * instead of selecting nothing.</p>
 */
class EmblemSetTest {
    @Test
    void everyDocumentedCrestExists() {
        // state emblem / shield / star / gear / globe / anchor / eagle / laurel /
        // seal / machine-readable / passport / diamond — the twelve presets.
        List<String> documented = List.of("emblem", "shield", "star", "gear", "globe", "anchor",
                "eagle", "leaf", "stamp", "mrz", "book", "diamond");
        for (String id : documented) {
            assertTrue(EmblemSet.IDS.contains(id), "missing documented crest: " + id);
        }
        assertEquals(documented.size(), 12);
    }

    @Test
    void gridIdsAreUniqueAndTheDefaultIsFirst() {
        Set<String> seen = new HashSet<>();
        for (String id : EmblemSet.IDS) {
            assertTrue(seen.add(id), "duplicate crest id: " + id);
            assertEquals(id, id.toLowerCase(java.util.Locale.ROOT), "crest id must be lower case: " + id);
        }
        assertSame(EmblemSet.IDS.get(0), EmblemSet.DEFAULT);
        assertEquals(0, EmblemSet.index(EmblemSet.DEFAULT));
        assertEquals(15, EmblemSet.count());
    }

    @Test
    void indexFollowsTheGridOrder() {
        assertEquals(0, EmblemSet.index("emblem"));
        assertEquals(1, EmblemSet.index("star"));
        assertEquals(3, EmblemSet.index("shield"));
        assertEquals(12, EmblemSet.index("gear"));
        assertEquals(13, EmblemSet.index("eagle"));
        assertEquals(14, EmblemSet.index("mrz"));
    }

    @Test
    void predicateIsTheIndexOverSixteen() {
        assertEquals(0.0F, EmblemSet.predicate("emblem"), 1.0E-6F);
        assertEquals(0.0625F, EmblemSet.predicate("star"), 1.0E-6F);
        assertEquals(0.75F, EmblemSet.predicate("gear"), 1.0E-6F);
        assertEquals(0.875F, EmblemSet.predicate("mrz"), 1.0E-6F);
        for (int index = 0; index < EmblemSet.count(); index++) {
            assertEquals(index / 16.0F, EmblemSet.predicate(EmblemSet.id(index)), 1.0E-6F,
                    "predicate must stay index/16 for " + EmblemSet.id(index));
        }
    }

    @Test
    void blankAndUnknownIdsFallBackToTheDefault() {
        assertEquals(0, EmblemSet.index(null));
        assertEquals(0, EmblemSet.index(""));
        assertEquals(0, EmblemSet.index("   "));
        assertEquals(0, EmblemSet.index("not-a-crest"));
        assertEquals(EmblemSet.DEFAULT, EmblemSet.normalize("not-a-crest"));
        assertEquals(0.0F, EmblemSet.predicate("not-a-crest"), 1.0E-6F);
    }

    @Test
    void idsAreCaseAndWhitespaceInsensitive() {
        assertEquals(EmblemSet.index("star"), EmblemSet.index("  STAR  "));
        assertEquals("shield", EmblemSet.normalize(" Shield "));
    }

    @Test
    void legacySpellingsResolveToACrest() {
        assertEquals("leaf", EmblemSet.normalize("laurel"));
        assertEquals("stamp", EmblemSet.normalize("seal"));
        assertEquals("book", EmblemSet.normalize("passport"));
        assertEquals("mrz", EmblemSet.normalize("machine_readable"));
        assertEquals("gear", EmblemSet.normalize("cog"));
        assertEquals("emblem", EmblemSet.normalize("state_emblem"));
    }

    @Test
    void idRoundTripsAndOutOfRangeFallsBack() {
        for (int index = 0; index < EmblemSet.count(); index++) {
            assertEquals(index, EmblemSet.index(EmblemSet.id(index)));
        }
        assertEquals(EmblemSet.DEFAULT, EmblemSet.id(-1));
        assertEquals(EmblemSet.DEFAULT, EmblemSet.id(EmblemSet.count()));
    }

    @Test
    void theRegistryPickerOffersExactlyTheSameGrid() {
        // The registry editor stores IconPicker ids in a template and the item
        // predicate reads them back, so the two lists are one contract.
        assertEquals(com.veriborder.client.ui.IconPicker.ICONS, EmblemSet.IDS);
        assertEquals(com.veriborder.client.ui.IconPicker.DEFAULT, EmblemSet.DEFAULT);
        for (String id : EmblemSet.IDS) {
            assertEquals(id, com.veriborder.client.ui.IconPicker.normalize(id),
                    "the picker must keep the crest id " + id);
        }
    }
}
