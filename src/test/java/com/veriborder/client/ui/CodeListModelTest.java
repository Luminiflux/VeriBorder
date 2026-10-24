package com.veriborder.client.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate-code list contract (26.4 UI repair): a batch field feeds a visible,
 * deletable list. The old screen only showed the count, so these tests pin the
 * add / duplicate / delete / clear / scroll behaviour the new list renders.
 */
class CodeListModelTest {
    private static CodeListModel modelWith(String... codes) {
        CodeListModel model = new CodeListModel(3);
        for (String code : codes) {
            model.addBatch(code);
        }
        return model;
    }

    @Test
    void batchAddParsesAllThreeSeparatorsAndKeepsOrder() {
        CodeListModel model = new CodeListModel();
        int added = model.addBatch("12345678, 87654321;11112222  22223333");
        assertEquals(4, added);
        assertEquals(List.of("12345678", "87654321", "11112222", "22223333"), model.codes());
        assertEquals("", model.errorKey());
        assertEquals(4, model.size());
    }

    @Test
    void duplicateOnlyBatchReportsDuplicateAndAddsNothing() {
        CodeListModel model = modelWith("12345678");
        assertEquals(0, model.addBatch("12345678"));
        assertEquals(CodeListModel.ERROR_DUPLICATE, model.errorKey());
        assertEquals(1, model.size());
    }

    @Test
    void invalidTokenIsReportedEvenWhenOtherTokensWereAdded() {
        CodeListModel model = new CodeListModel();
        assertEquals(1, model.addBatch("12345678, abc"));
        assertEquals(CodeListModel.ERROR_INVALID, model.errorKey());
        assertEquals(List.of("12345678"), model.codes());
    }

    @Test
    void blankBatchIsRejected() {
        CodeListModel model = new CodeListModel();
        assertEquals(0, model.addBatch("   "));
        assertEquals(CodeListModel.ERROR_INVALID, model.errorKey());
        assertEquals(0, model.size());
    }

    @Test
    void removingTheLastItemEmptiesTheListAndClearsTheWindow() {
        CodeListModel model = modelWith("12345678");
        assertTrue(model.removeLast());
        assertTrue(model.isEmpty());
        assertEquals(0, model.maxScroll());
        assertFalse(model.removeLast(), "an empty list cannot remove anything");
    }

    @Test
    void removeAtAndClearKeepTheVisibleWindowValid() {
        CodeListModel model = modelWith("11111111", "22222222", "33333333", "44444444", "55555555");
        assertEquals(2, model.maxScroll());
        model.scrollBy(-1);
        assertEquals(1, model.scroll());
        assertTrue(model.removeAt(0));
        assertEquals(List.of("22222222", "33333333", "44444444", "55555555"), model.codes());
        model.clear();
        assertTrue(model.isEmpty());
        assertEquals(0, model.scroll());
        assertEquals("", model.errorKey());
    }

    @Test
    void windowScrollsAndMapsRowsBackToAbsoluteIndexes() {
        CodeListModel model = modelWith("11111111", "22222222", "33333333", "44444444", "55555555");
        assertEquals(2, model.maxScroll());         // 5 codes - 3 visible rows
        assertEquals(3 * CodeListModel.ROW_HEIGHT, model.height());
        assertEquals(0, model.visibleStart());
        assertEquals(3, model.visibleEnd());
        assertEquals(113, model.rowTop(1, 100));
        assertEquals(-1, model.rowTop(3, 100), "row 3 is outside the window");

        model.scrollBy(-1);
        assertEquals(1, model.visibleStart());
        assertEquals(4, model.visibleEnd());
        assertEquals(100, model.rowTop(1, 100));
        assertEquals(100 + 2 * CodeListModel.ROW_HEIGHT, model.rowTop(3, 100));
        assertEquals(2, model.indexAt(100 + CodeListModel.ROW_HEIGHT + 1, 100));
        assertEquals(-1, model.indexAt(100 + model.height() + 1, 100));
    }

    @Test
    void rowsAreFixedSoTheLayoutNeverJumps() {
        CodeListModel model = new CodeListModel();
        assertEquals(3 * CodeListModel.ROW_HEIGHT, model.height());
        model.addBatch("12345678");
        assertEquals(3 * CodeListModel.ROW_HEIGHT, model.height());
        model.rows(2);
        assertEquals(2 * CodeListModel.ROW_HEIGHT, model.height());
    }
}
