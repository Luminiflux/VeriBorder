package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.3 stamp moment: a stamp records the server clock ({@code HH:mm:ss} plus the
 * epoch millis), not just the date, and stamps written before 26.3 render the
 * {@code --:--} placeholder instead of an empty time.
 */
class StampMomentTest {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Test
    void stampsRecordTheServerClock() {
        Instant at = Instant.parse("2024-03-04T05:06:07Z");
        LocalDateTime local = LocalDateTime.ofInstant(at, ZoneId.systemDefault());

        DocumentData doc = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        doc.addStamp(StampRecord.IN, "北京首都国际机场 T3", "中国", "边检警官", at);

        StampRecord stamp = doc.stamps().get(0);
        assertEquals(local.toLocalDate().format(DateUtil.ISO), stamp.date());
        assertEquals(local.format(TIME), stamp.time());
        assertEquals(at.toEpochMilli(), stamp.epochMillis());
        assertTrue(stamp.hasTime());
        assertEquals(stamp.time(), stamp.timeOrDash());
        assertEquals(stamp.date() + " " + stamp.time(), stamp.dateTimeText());
        assertTrue(doc.integrityValid());
    }

    @Test
    void defaultAddStampUsesTheCurrentServerInstant() {
        DocumentData doc = DocumentFactory.sample(DocStyle.HK_MACAO_PERMIT);
        long before = System.currentTimeMillis();
        doc.addStamp(StampRecord.OUT, "深圳湾口岸", "中国", "边检警官");
        long after = System.currentTimeMillis();

        StampRecord stamp = doc.stamps().get(0);
        assertEquals(DateUtil.today(), stamp.date(), "the stamp date is the server's today");
        assertTrue(stamp.time().matches("\\d{2}:\\d{2}:\\d{2}"), "HH:mm:ss, got " + stamp.time());
        assertTrue(stamp.epochMillis() >= before && stamp.epochMillis() <= after,
                "the epoch value is the wall-clock instant of the stamp");
        assertTrue(doc.integrityValid());
    }

    @Test
    void legacyStampsRenderTheDashPlaceholder() {
        CompoundTag legacy = new CompoundTag();
        legacy.putString("id", "S-LEGACY01");
        legacy.putString("direction", StampRecord.IN);
        legacy.putString("port", "北京首都国际机场 T3");
        legacy.putString("country", "中国");
        legacy.putString("date", "2024-01-02");
        legacy.putString("officer", "边检警官");

        StampRecord stamp = StampRecord.fromTag(legacy);
        assertEquals("", stamp.time());
        assertEquals(0L, stamp.epochMillis());
        assertFalse(stamp.hasTime());
        assertEquals("--:--", stamp.timeOrDash());
        assertEquals("2024-01-02 --:--", stamp.dateTimeText());

        // A legacy stamp read into a live document still shows the placeholder and
        // keeps its signature valid.
        DocumentData doc = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        doc.addStampTag(legacy);
        StampRecord loaded = doc.stamps().get(0);
        assertEquals("--:--", loaded.timeOrDash());
        assertTrue(doc.integrityValid());
    }

    @Test
    void stampTimeSurvivesTheNbtRoundTrip() {
        Instant at = Instant.parse("2025-12-31T15:59:59Z");
        DocumentData doc = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        doc.addStamp(StampRecord.IN, "上海浦东国际机场 T2", "中国", "边检警官", at);

        CompoundTag stored = doc.stampTags().get(0);
        assertTrue(stored.contains("time"));
        assertEquals(at.toEpochMilli(), stored.getLong("epochMillis"));

        DocumentData loaded = DocumentData.fromTag(doc.copyTag());
        assertTrue(loaded.integrityValid());
        StampRecord stamp = loaded.stamps().get(0);
        assertEquals(doc.stamps().get(0), stamp);
        assertEquals(at.toEpochMilli(), stamp.epochMillis());
        assertEquals(StampRecord.fromTag(stamp.toTag()), stamp);
        assertNotNull(stamp.time());
    }
}
