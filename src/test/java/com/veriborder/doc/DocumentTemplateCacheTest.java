package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Template cache behaviour (26.3 round 11): the cache must be invisible to
 * callers — every document is independent, the cache key follows the date, and
 * invalidation really drops the entries.
 */
class DocumentTemplateCacheTest {
    @BeforeEach
    @AfterEach
    void resetCache() {
        DocumentTemplateCache.invalidateAll();
    }

    @Test
    void twoBlankDocumentsAreIndependentCopies() {
        DocumentData first = DocumentData.createEmpty(DocStyle.CN_PASSPORT);
        DocumentData second = DocumentData.createEmpty(DocStyle.CN_PASSPORT);

        assertEquals(first.copyTag(), second.copyTag(), "same layout + same day -> same template");
        assertNotSame(first.tag(), second.tag(), "the cache must hand out copies, never the entry");

        first.setName("ALEX");
        assertEquals("", second.name(), "mutating one document cannot leak into the next");
        assertEquals("", DocumentData.createEmpty(DocStyle.CN_PASSPORT).name(),
                "and the cached template itself is untouched");
        assertTrue(second.integrityValid(), "the copy is still a signed document");
    }

    @Test
    void theCacheIsKeyedByLayoutAndDay() {
        assertEquals(DocStyle.CN_ID.id() + "@" + DateUtil.today(), DocumentTemplateCache.key(DocStyle.CN_ID));
        assertFalse(DocumentTemplateCache.isCached(DocStyle.CN_ID));

        DocumentData.createEmpty(DocStyle.CN_ID);
        assertTrue(DocumentTemplateCache.isCached(DocStyle.CN_ID));
        assertFalse(DocumentTemplateCache.isCached(DocStyle.CN_PASSPORT),
                "another layout is a different template");

        int size = DocumentTemplateCache.size();
        for (int i = 0; i < 5; i++) {
            DocumentData.createEmpty(DocStyle.CN_ID);
        }
        assertEquals(size, DocumentTemplateCache.size(), "repeated creation does not grow the cache");
        assertTrue(size <= DocStyle.values().length);
    }

    @Test
    void everyLayoutIsCachedSeparatelyAndStaysBounded() {
        for (DocStyle style : DocStyle.values()) {
            assertNotNull(DocumentData.createEmpty(style));
        }
        assertEquals(DocStyle.values().length, DocumentTemplateCache.size());
        assertTrue(DocumentTemplateCache.size() <= DocumentTemplateCache.MAX_ENTRIES);
    }

    @Test
    void invalidationRebuildsAnIdenticalTemplate() {
        CompoundTag before = DocumentData.createEmpty(DocStyle.HK_MACAO_PERMIT).copyTag();
        assertTrue(DocumentTemplateCache.isCached(DocStyle.HK_MACAO_PERMIT));

        DocumentTemplateCache.invalidate(DocStyle.HK_MACAO_PERMIT);
        assertFalse(DocumentTemplateCache.isCached(DocStyle.HK_MACAO_PERMIT));
        assertEquals(before, DocumentData.createEmpty(DocStyle.HK_MACAO_PERMIT).copyTag(),
                "rebuilding produces the same signed template");
        assertTrue(DocumentTemplateCache.isCached(DocStyle.HK_MACAO_PERMIT));

        DocumentTemplateCache.invalidateAll();
        assertEquals(0, DocumentTemplateCache.size());
        assertFalse(DocumentTemplateCache.isCached(DocStyle.HK_MACAO_PERMIT));
        assertEquals(before, DocumentData.createEmpty(DocStyle.HK_MACAO_PERMIT).copyTag());
    }

    @Test
    void aCachedTemplateIsACompleteSignedDocument() {
        DocumentData passport = DocumentData.createEmpty(DocStyle.US_PASSPORT);
        assertTrue(passport.integrityValid());
        assertEquals(DocumentSchema.CURRENT, DocumentSchema.read(passport.copyTag()));
        assertEquals(DocStyle.US_PASSPORT.defaultAuthority(), passport.authority());
        assertEquals(DocumentStatus.VALID, passport.status());
        assertTrue(passport.visas().isEmpty());
        assertTrue(passport.stamps().isEmpty());
    }
}
