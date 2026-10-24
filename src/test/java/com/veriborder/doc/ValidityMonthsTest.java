package com.veriborder.doc;

import com.veriborder.block.DeviceBlockEntity;
import com.veriborder.block.DeviceService;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 26.2 validity month precision: every duration is stored and computed in
 * calendar months ({@link DateUtil#plusMonths}), never in 30-day approximations,
 * with month-end and leap-day clamping. Legacy {@code validityYears} NBT values
 * are migrated ×12 on read and written back as {@code months / 12} for older
 * readers.
 */
class ValidityMonthsTest {
    private static DeviceBlockEntity.Locks locks(Consumer<CompoundTag> writer) {
        CompoundTag tag = new CompoundTag();
        writer.accept(tag);
        return DeviceBlockEntity.Locks.fromTag(tag);
    }

    @Test
    void plusMonthsClampsToTheEndOfTheTargetMonth() {
        assertEquals("2026-02-28", DateUtil.plusMonths("2026-01-31", 1));
        assertEquals("2026-04-30", DateUtil.plusMonths("2026-01-31", 3));
        assertEquals("2026-07-31", DateUtil.plusMonths("2026-01-31", 6));
        assertEquals("2027-01-31", DateUtil.plusMonths("2026-01-31", 12));
        assertEquals("2026-03-01", DateUtil.plusMonths("2026-01-01", 2));
        assertEquals("2027-02-28", DateUtil.plusMonths("2026-08-31", 6),
                "August 31 + 6 months is the last day of February");
    }

    @Test
    void threeMonthEndorsementExpiryIsExact() {
        assertEquals("2026-04-30",
                DocumentFactory.endorsementExpiry(EndorsementType.THREE_MONTH_SINGLE, "2026-01-31"));
        assertEquals("2026-02-28", DocumentFactory.lockedExpiry("2026-01-31", 1));
        assertEquals("2026-04-30", DocumentFactory.lockedExpiry("2026-01-31", 3));
        assertEquals(DateUtil.PERMANENT, DocumentFactory.lockedExpiry("2026-01-31", 0));
    }

    @Test
    void twelveMonthsAreOneYearAndTwentyFourAreTwo() {
        assertEquals("2027-01-15", DocumentFactory.lockedExpiry("2026-01-15", 12));
        assertEquals("2028-01-15", DocumentFactory.lockedExpiry("2026-01-15", 24));
        assertEquals("2031-01-15", DocumentFactory.lockedExpiry("2026-01-15", 60));
        assertEquals(1, DateUtil.wholeYears(12));
        assertEquals(0, DateUtil.remainingMonths(12));
        assertEquals(2, DateUtil.wholeYears(24));
        assertEquals(1, DateUtil.wholeYears(15));
        assertEquals(3, DateUtil.remainingMonths(15));
        assertEquals(0, DateUtil.wholeYears(11));
        assertEquals(11, DateUtil.remainingMonths(11));
    }

    @Test
    void leapDayAndMonthEndAreClamped() {
        assertEquals("2024-02-29", DateUtil.plusMonths("2024-01-31", 1));
        assertEquals("2025-02-28", DateUtil.plusMonths("2024-02-29", 12));
        assertEquals("2024-05-29", DateUtil.plusMonths("2024-02-29", 3));
        assertEquals("2024-02-29", DocumentFactory.lockedExpiry("2024-01-31", 1));
        assertEquals("2028-02-29", DocumentFactory.lockedExpiry("2028-01-31", 1),
                "2028 is another leap year");
    }

    @Test
    void legacyValidityYearsMigrateToMonths() {
        // Pre-26.2 NBT: only the year key exists and must become months.
        CompoundTag legacy = new CompoundTag();
        legacy.putInt("lockedValidityYears", 5);
        legacy.putInt("lockedVisaValidityYears", 2);
        DeviceBlockEntity.Locks loaded = DeviceBlockEntity.Locks.fromTag(legacy);
        assertEquals(60, loaded.lockedValidityMonths(), "5 years = 60 months");
        assertEquals(24, loaded.lockedVisaValidityMonths(), "2 years = 24 months");

        // Writing keeps both keys in sync: months are authoritative, the year key
        // is the floor (an older reader must never round a duration up).
        CompoundTag written = new CompoundTag();
        loaded.writeTo(written);
        assertEquals(60, written.getInt("lockedValidityMonths"));
        assertEquals(5, written.getInt("lockedValidityYears"));
        assertEquals(24, written.getInt("lockedVisaValidityMonths"));
        assertEquals(2, written.getInt("lockedVisaValidityYears"));

        DeviceBlockEntity.Locks odd = new DeviceBlockEntity.Locks(
                false, "", false, "", false, "", false, "", false, "", true, 15,
                false, "", false, "", false, "",
                false, false, false, 0, false, "", false, "", false, 18, false, "");
        CompoundTag oddTag = new CompoundTag();
        odd.writeTo(oddTag);
        assertEquals(15, oddTag.getInt("lockedValidityMonths"));
        assertEquals(1, oddTag.getInt("lockedValidityYears"), "15 months floor to 1 year");
        assertEquals(18, oddTag.getInt("lockedVisaValidityMonths"));
        assertEquals(1, oddTag.getInt("lockedVisaValidityYears"), "18 months floor to 1 year");
        assertEquals(odd, DeviceBlockEntity.Locks.fromTag(oddTag));

        // The explicit month key wins over a stale legacy year key.
        CompoundTag mixed = new CompoundTag();
        mixed.putInt("lockedValidityYears", 5);
        mixed.putInt("lockedValidityMonths", 3);
        assertEquals(3, DeviceBlockEntity.Locks.fromTag(mixed).lockedValidityMonths());
    }

    @Test
    void monthCountsAreClampedIntoASaneRange() {
        assertEquals(0, DeviceBlockEntity.clampValidityMonths(-7));
        assertEquals(0, DeviceBlockEntity.clampValidityMonths(0));
        assertEquals(1200, DeviceBlockEntity.clampValidityMonths(999_999));
        assertEquals(DeviceBlockEntity.MAX_VALIDITY_MONTHS,
                locks(tag -> tag.putInt("lockedValidityMonths", 5000)).lockedValidityMonths());
        // A legacy year value that overflows the cap clamps too.
        assertEquals(DeviceBlockEntity.MAX_VALIDITY_MONTHS,
                locks(tag -> tag.putInt("lockedValidityYears", 500)).lockedValidityMonths());
    }

    @Test
    void lockedValidityIsEnforcedInMonthsWhenIssuing() {
        // Standalone visa form (VisaIssuerScreen): a 6-month lock wins over the
        // submitted expiry date and is computed from the issued date.
        CompoundTag form = new CompoundTag();
        form.putString("country", "Japan");
        form.putString("type", "tourist");
        form.putString("issueDate", "2026-01-31");
        form.putString("expiryDate", "2030-01-31");
        DeviceBlockEntity.Locks locked = locks(tag -> {
            tag.putBoolean("lockValidity", true);
            tag.putInt("lockedValidityMonths", 6);
        });
        CompoundTag visa = DeviceService.toVisa(DeviceService.applyVisaLocks(locked, form),
                "Embassy", "00112233", false);
        assertEquals("2026-07-31", visa.getString("expiryDate"));

        // Passport-attached visa: the visa-level lock is months as well.
        DeviceBlockEntity.Locks visaLocked = locks(tag -> {
            tag.putBoolean("lockVisaValidity", true);
            tag.putInt("lockedVisaValidityMonths", 3);
            tag.putBoolean("lockVisaIssueDate", true);
            tag.putString("lockedVisaIssueDate", "2026-01-31");
        });
        CompoundTag attachedForm = new CompoundTag();
        attachedForm.putBoolean("includeVisa", true);
        CompoundTag attached = DeviceService.buildPassportVisa(visaLocked,
                DocumentFactory.sample(DocStyle.CN_PASSPORT), attachedForm, "China");
        assertEquals("2026-01-31", attached.getString("issueDate"));
        assertEquals("2026-04-30", attached.getString("expiryDate"));
    }
}
