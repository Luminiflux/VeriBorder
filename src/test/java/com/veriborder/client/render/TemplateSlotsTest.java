package com.veriborder.client.render;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.4.1 dynamic base-map slot allocation.
 *
 * <p>Eight textures carry every custom cover picture, so the numbering has to be
 * stable: the same hash must reuse the same slot (one upload, one texture bound to
 * every document issued from that template) and a snapshot refresh must not
 * reshuffle hashes that are still published.</p>
 */
class TemplateSlotsTest {
    private static final String A = "aaaa1111";
    private static final String B = "bbbb2222";
    private static final String C = "cccc3333";

    @BeforeEach
    void reset() {
        TemplateSlots.clear();
    }

    @Test
    void firstEightHashesFillTheLowerSlotsInOrder() {
        TemplateSlots.rebuild(List.of("h0", "h1", "h2", "h3", "h4", "h5", "h6", "h7"));
        for (int slot = 0; slot < TemplateSlots.CAPACITY; slot++) {
            assertEquals(slot, TemplateSlots.slotOf("h" + slot));
            assertEquals("h" + slot, TemplateSlots.hashOf(slot));
        }
        assertEquals(TemplateSlots.CAPACITY, TemplateSlots.boundCount());
    }

    @Test
    void theNinthHashHasNoSlot() {
        TemplateSlots.rebuild(List.of("h0", "h1", "h2", "h3", "h4", "h5", "h6", "h7", "h8"));
        assertEquals(TemplateSlots.NO_SLOT_INDEX, TemplateSlots.slotOf("h8"));
        assertEquals(TemplateSlots.NO_SLOT, TemplateSlots.predicate("h8"), 1.0E-6F);
        assertEquals(TemplateSlots.CAPACITY, TemplateSlots.boundCount());
    }

    @Test
    void theSameHashAlwaysReusesItsSlot() {
        TemplateSlots.rebuild(List.of(A, B));
        int slotOfB = TemplateSlots.slotOf(B);
        TemplateSlots.rebuild(List.of(B, C));
        assertEquals(slotOfB, TemplateSlots.slotOf(B), "a hash that is still published must keep its slot");
        assertEquals(TemplateSlots.NO_SLOT_INDEX, TemplateSlots.slotOf(A));
        assertEquals(0, TemplateSlots.slotOf(C), "the freed slot is reused first");
    }

    @Test
    void duplicateHashesShareOneSlot() {
        TemplateSlots.rebuild(List.of(A, A, B));
        assertEquals(2, TemplateSlots.boundCount(), "two distinct hashes, two slots");
        assertEquals(0, TemplateSlots.slotOf(A));
        assertEquals(1, TemplateSlots.slotOf(B), "the repeated hash must not shift B");
    }

    @Test
    void blankHashesAreIgnored() {
        TemplateSlots.rebuild(java.util.Arrays.asList(null, "", "   ", A));
        assertEquals(1, TemplateSlots.boundCount());
        assertEquals(0, TemplateSlots.slotOf(A));
        assertEquals(TemplateSlots.NO_SLOT_INDEX, TemplateSlots.slotOf(null));
        assertEquals(TemplateSlots.NO_SLOT_INDEX, TemplateSlots.slotOf("  "));
    }

    @Test
    void hashesAreComparedAfterTrimAndLowerCasing() {
        TemplateSlots.rebuild(List.of("ABCDEF01"));
        assertEquals(0, TemplateSlots.slotOf("  abcdef01 "));
        assertTrue(TemplateSlots.boundSlots().contains(0));
    }

    @Test
    void clearDropsEveryBinding() {
        TemplateSlots.rebuild(List.of(A, B));
        TemplateSlots.clear();
        assertEquals(0, TemplateSlots.boundCount());
        assertEquals(TemplateSlots.NO_SLOT_INDEX, TemplateSlots.slotOf(A));
        assertNull(TemplateSlots.hashOf(0));
        assertTrue(TemplateSlots.boundSlots().isEmpty());
    }

    @Test
    void theEightTextureIdsAreThePreRegisteredLocations() {
        assertEquals(new ResourceLocation("veriborder", "dynamic/template_0"),
                TemplateSlots.textureId(0));
        assertEquals(new ResourceLocation("veriborder", "dynamic/template_7"),
                TemplateSlots.textureId(7));
        assertThrows(IllegalArgumentException.class, () -> TemplateSlots.textureId(-1));
        assertThrows(IllegalArgumentException.class,
                () -> TemplateSlots.textureId(TemplateSlots.CAPACITY));
    }

    @Test
    void predicateIsTheSlotIndexOrMinusOne() {
        TemplateSlots.rebuild(List.of(A, B));
        assertEquals(0.0F, TemplateSlots.predicate(A), 1.0E-6F);
        assertEquals(1.0F, TemplateSlots.predicate(B), 1.0E-6F);
        assertEquals(-1.0F, TemplateSlots.predicate("never-sent"), 1.0E-6F);
    }

    @Test
    void anEmptySnapshotFreesEverySlot() {
        TemplateSlots.rebuild(List.of(A, B, C));
        TemplateSlots.rebuild(List.of());
        assertEquals(0, TemplateSlots.boundCount());
        assertEquals(TemplateSlots.NO_SLOT_INDEX, TemplateSlots.slotOf(A));
    }

    @Test
    void capacityIsEight() {
        assertEquals(8, TemplateSlots.CAPACITY);
    }
}
