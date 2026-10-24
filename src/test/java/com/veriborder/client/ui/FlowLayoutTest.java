package com.veriborder.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sequential flow contract (26.4 UI repair): rows are laid out by a cursor
 * ({@code y += height + gap}), never by hard-coded coordinates, and the scroll
 * range guarantees that no row can leave the viewport band.
 */
class FlowLayoutTest {
    @Test
    void rowsAdvanceByHeightPlusGap() {
        FlowLayout layout = FlowLayout.at(10, 20, 200, 6);
        assertEquals(20, layout.row(18));
        assertEquals(44, layout.row(14));
        assertEquals(64, layout.row(10));
        // 18 + 6 + 14 + 6 + 10 + 6: the trailing gap is part of the content box.
        assertEquals(60, layout.contentHeight());
        assertEquals(64 + 10 + 6, layout.y());
    }

    @Test
    void customGapAndSkipControlSectionSpacing() {
        FlowLayout layout = FlowLayout.at(0, 0, 100, 4);
        assertEquals(0, layout.row(10));       // next y = 14
        assertEquals(14, layout.row(10, 0));   // glued row, next y = 24
        layout.skip(8);                        // next y = 32
        assertEquals(32, layout.row(12));      // next y = 48
        assertEquals(48, layout.contentHeight());
    }

    @Test
    void scrollRangeIsContentMinusViewport() {
        FlowLayout layout = FlowLayout.at(0, 10, 100, 2);
        layout.row(40);   // 0..40
        layout.row(40);   // 42..82
        layout.viewportHeight(50);
        assertEquals(84, layout.contentHeight());
        assertEquals(34, layout.scrollRange());
        layout.offset(1000);
        assertEquals(34, layout.offset());
        layout.offset(-5);
        assertEquals(0, layout.offset());
    }

    @Test
    void viewportBiggerThanContentNeverScrolls() {
        FlowLayout layout = FlowLayout.at(0, 0, 100);
        layout.row(20);
        layout.viewportHeight(200);
        assertEquals(0, layout.scrollRange());
        assertFalse(layout.scrollBy(-1), "a short page ignores the wheel");
        assertEquals(0, layout.offset());
    }

    @Test
    void wheelScrollsByOneStepAndClamps() {
        FlowLayout layout = FlowLayout.at(0, 0, 100);
        layout.row(100);                 // content = 100 + the default gap (6)
        layout.viewportHeight(40);
        assertEquals(66, layout.scrollRange());
        assertTrue(layout.scrollBy(-1));
        assertEquals(FlowLayout.SCROLL_STEP, layout.offset());
        // One wheel notch is one step, never a jump proportional to the delta.
        layout.scrollBy(-100);
        assertEquals(FlowLayout.SCROLL_STEP * 2, layout.offset());
        assertTrue(layout.scrollBy(1));
        assertEquals(FlowLayout.SCROLL_STEP, layout.offset());
        layout.scrollBy(-1);
        layout.scrollBy(-1);
        layout.scrollBy(-1);
        layout.scrollBy(-1);
        assertEquals(66, layout.offset(), "the offset never exceeds the scroll range");
    }

    @Test
    void paintYSubtractsTheOffset() {
        FlowLayout layout = FlowLayout.at(0, 0, 100);
        int row = layout.row(100);
        layout.viewportHeight(40);
        assertEquals(row, layout.paintY(row));
        layout.scrollBy(-1);
        assertEquals(row - FlowLayout.SCROLL_STEP, layout.paintY(row));
    }

    @Test
    void scrollToBottomShowsTheLastRowAtTheViewportBottom() {
        FlowLayout layout = FlowLayout.at(0, 0, 100, 0);
        layout.row(30);
        int last = layout.row(30);
        layout.viewportHeight(40);
        layout.scrollToBottom();
        assertEquals(20, layout.offset());
        assertEquals(40, layout.paintY(last) + 30);
    }
}
