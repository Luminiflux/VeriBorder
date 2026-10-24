package com.veriborder.doc;

import com.veriborder.block.DeviceBlockEntity;
import com.veriborder.block.DeviceService;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.1 lock data tests: the visa {@code label} component, the passport-attached
 * visa locks and the standalone visa issuer label. The lock rules are exercised
 * through the pure helpers on {@link DeviceService} and the {@code Locks} record,
 * so no world, block entity or client is needed.
 */
class VisaLockTest {
    /** Builds a lock view from raw NBT keys, exactly like a loaded device does. */
    private static DeviceBlockEntity.Locks locks(Consumer<CompoundTag> writer) {
        CompoundTag tag = new CompoundTag();
        writer.accept(tag);
        return DeviceBlockEntity.Locks.fromTag(tag);
    }

    private static DocumentData passport(String issue, String expiry) {
        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        passport.clearVisas();
        passport.setIssueDate(issue);
        passport.setExpiryDate(expiry);
        return passport;
    }

    @Test
    void visaRecordLabelRoundTripsAndIsTrimmed() {
        CompoundTag tag = DocumentFactory.createVisa("Japan", VisaType.TOURIST, 2,
                "2024-01-01", "2025-01-01", "Embassy", "00112233", "  Business Visit  ");
        assertEquals("Business Visit", tag.getString("label"), "the label is trimmed on creation");
        assertTrue(tag.contains("label"));

        VisaRecord record = VisaRecord.fromTag(tag);
        assertEquals("Business Visit", record.label());
        assertTrue(record.hasLabel());
        assertEquals("Business Visit", VisaRecord.fromTag(record.toTag()).label(),
                "the label survives toTag/fromTag");

        // Missing label keys (pre-26.1 records) load as blank, never null.
        CompoundTag legacy = DocumentFactory.createVisa("Japan", VisaType.TOURIST, 2,
                "2024-01-01", "2025-01-01", "Embassy", "00112233");
        assertEquals("", VisaRecord.fromTag(legacy).label());
        assertEquals("", new VisaRecord("V-1", "Japan", VisaType.TOURIST, 1, 0, "2024-01-01",
                "2025-01-01", "Embassy", VisaRecord.ACTIVE, "00112233").label(),
                "the pre-26.1 constructor stays source-compatible");
    }

    @Test
    void labelHelpersFallBackToCountryWhenBlank() {
        DocumentData document = passport("2024-01-15", "2034-01-15");
        CompoundTag labelled = DocumentFactory.createVisa("Schengen", VisaType.BUSINESS, 2,
                "2024-02-01", "2025-02-01", "Embassy", "00112233", "Business Visit");
        CompoundTag unlabelled = DocumentFactory.createVisa("Japan", VisaType.TOURIST, 1,
                "2024-02-01", "2025-02-01", "Embassy", "00112244");
        document.addVisa(labelled);
        document.addVisa(unlabelled);

        assertEquals("Business Visit", document.visaLabel(0));
        assertEquals("Business Visit", DocumentData.visaDisplayLabel(document.visas().get(0)));
        assertEquals("Japan", document.visaDisplayLabel(document.visas().get(1)),
                "a blank label falls back to the country");
        assertEquals("", document.visaLabel(9), "out-of-range indexes are blank");
        assertEquals("", DocumentData.visaDisplayLabel(null));
        // A blank country still produces a non-empty (type) fallback.
        assertFalse(DocumentData.visaDisplayLabel(VisaRecord.fromTag(
                DocumentFactory.createVisa("", VisaType.STUDENT, 1, "2024-02-01", "2025-02-01",
                        "Embassy", "00112255"))).isBlank());
    }

    @Test
    void passportVisaLocksRoundTripThroughNbt() {
        DeviceBlockEntity.Locks configured = new DeviceBlockEntity.Locks(
                false, "", false, "", false, "", false, "", false, "", false, 0,
                false, "", false, "", false, "",
                true, false, true, 3, true, " Schengen ", true, " 2024-05-06 ",
                true, 24, true, " Business ");
        CompoundTag tag = new CompoundTag();
        configured.writeTo(tag);
        for (String key : List.of("lockIncludeVisa", "lockedIncludeVisa", "lockVisaEntries",
                "lockedVisaEntries", "lockVisaCountry", "lockedVisaCountry", "lockVisaIssueDate",
                "lockedVisaIssueDate", "lockVisaValidity", "lockedVisaValidityMonths",
                "lockVisaName", "lockedVisaName")) {
            assertTrue(tag.contains(key), "NBT key " + key);
        }
        // 26.2 keeps the legacy year key in sync for older readers (24 months = 2).
        assertEquals(2, tag.getInt("lockedVisaValidityYears"));

        DeviceBlockEntity.Locks loaded = DeviceBlockEntity.Locks.fromTag(tag);
        assertEquals(configured, loaded);
        assertTrue(loaded.lockIncludeVisa());
        assertFalse(loaded.lockedIncludeVisa());
        assertEquals(3, loaded.lockedVisaEntries());
        assertEquals("Schengen", loaded.lockedVisaCountry());
        assertEquals("2024-05-06", loaded.lockedVisaIssueDate());
        assertEquals(24, loaded.lockedVisaValidityMonths());
        assertEquals("Business", loaded.lockedVisaName());

        // Pre-26.1 NBT loads neutral defaults and never turns a lock on.
        DeviceBlockEntity.Locks legacy = DeviceBlockEntity.Locks.fromTag(new CompoundTag());
        assertEquals(DeviceBlockEntity.Locks.NONE, legacy);
        assertFalse(legacy.lockIncludeVisa());
        assertFalse(legacy.lockVisaEntries());
        assertEquals(0, legacy.lockedVisaEntries());
        assertFalse(legacy.lockVisaCountry());
        assertFalse(legacy.lockVisaIssueDate());
        assertFalse(legacy.lockVisaValidity());
        assertFalse(legacy.lockVisaName());
        assertEquals("", legacy.lockedVisaName());

        // Negative entries are meaningless (0 = unlimited), so they clamp to 0.
        CompoundTag negative = new CompoundTag();
        negative.putInt("lockedVisaEntries", -4);
        assertEquals(0, DeviceBlockEntity.Locks.fromTag(negative).lockedVisaEntries());
        assertEquals(VisaType.BUSINESS, DeviceBlockEntity.visaTypeOrNull(" business "));
        assertNull(DeviceBlockEntity.visaTypeOrNull(""));
    }

    @Test
    void attachedVisaLocksOverrideAForgedForm() {
        DocumentData passport = passport("2024-01-15", "2034-01-15");
        String lockedIssue = DateUtil.plusYears(DateUtil.today(), -1);
        String lockedExpiry = DocumentFactory.lockedExpiry(lockedIssue, 24);
        DeviceBlockEntity.Locks locked = locks(tag -> {
            tag.putBoolean("lockIncludeVisa", true);
            tag.putBoolean("lockedIncludeVisa", true);
            tag.putBoolean("lockVisaEntries", true);
            tag.putInt("lockedVisaEntries", 3);
            tag.putBoolean("lockVisaCountry", true);
            tag.putString("lockedVisaCountry", "Schengen");
            tag.putBoolean("lockVisaIssueDate", true);
            tag.putString("lockedVisaIssueDate", lockedIssue);
            tag.putBoolean("lockVisaValidity", true);
            tag.putInt("lockedVisaValidityMonths", 24);
            tag.putBoolean("lockVisaName", true);
            tag.putString("lockedVisaName", "Business");
            tag.putBoolean("lockVisaCode", true);
            tag.putString("lockedVisaCode", "00112233");
        });
        // A forged form claims no visa, another country, more entries, a name,
        // a date, a validity and a code: every locked value must win.
        CompoundTag forged = new CompoundTag();
        forged.putBoolean("includeVisa", false);
        forged.putString("visaCountry", "Korea");
        forged.putInt("visaEntries", 9);
        forged.putString("visaName", "Forged");
        forged.putString("visaIssueDate", "2020-01-01");
        forged.putString("visaCode", "99998888");

        CompoundTag visa = DeviceService.buildPassportVisa(locked, passport, forged, "China");
        assertNotNull(visa, "a locked include=true attaches the visa despite the form");
        assertEquals("Schengen", visa.getString("country"));
        assertEquals(3, visa.getInt("entries"));
        assertEquals("Business", visa.getString("label"));
        assertEquals(lockedIssue, visa.getString("issueDate"));
        assertEquals(lockedExpiry, visa.getString("expiryDate"), "locked validity derives the expiry");
        assertEquals("00112233", visa.getString("code"));
        assertEquals("tourist", visa.getString("type"), "an unlocked visa type still follows the form");

        passport.addVisaExact(visa);
        VisaRecord record = passport.visas().get(0);
        assertEquals("Schengen", record.country());
        assertEquals(3, record.entries());
        assertEquals("Business", record.label());
        assertTrue(record.active());
        assertTrue(passport.integrityValid());
        assertEquals("Business", DocumentData.visaDisplayLabel(record));
    }

    @Test
    void lockedIncludeVisaDecidesInBothDirections() {
        DocumentData passport = passport("2024-01-15", "2034-01-15");
        CompoundTag forgedInclude = new CompoundTag();
        forgedInclude.putBoolean("includeVisa", true);

        assertNull(DeviceService.buildPassportVisa(locks(tag -> {
            tag.putBoolean("lockIncludeVisa", true);
            tag.putBoolean("lockedIncludeVisa", false);
        }), passport, forgedInclude, "China"), "a locked include=false wins over a forged form");

        CompoundTag forgedSkip = new CompoundTag();
        forgedSkip.putBoolean("includeVisa", false);
        assertNotNull(DeviceService.buildPassportVisa(locks(tag -> {
            tag.putBoolean("lockIncludeVisa", true);
            tag.putBoolean("lockedIncludeVisa", true);
        }), passport, forgedSkip, "China"), "a locked include=true wins over a forged form");

        // Unlocked, the form decides.
        assertNull(DeviceService.buildPassportVisa(DeviceBlockEntity.Locks.NONE,
                passport, forgedSkip, "China"));
        assertNotNull(DeviceService.buildPassportVisa(DeviceBlockEntity.Locks.NONE,
                passport, forgedInclude, "China"));
    }

    @Test
    void unlockedAttachedVisaFollowsTheFormAndPassportDates() {
        DocumentData passport = passport("2024-01-15", "2034-01-15");
        CompoundTag form = new CompoundTag();
        form.putBoolean("includeVisa", true);
        form.putString("visaCountry", "Japan");
        form.putString("visaType", "student");
        form.putInt("visaEntries", 2);
        form.putString("visaName", "Visit");

        CompoundTag visa = DeviceService.buildPassportVisa(DeviceBlockEntity.Locks.NONE,
                passport, form, "China");
        assertNotNull(visa);
        assertEquals("Japan", visa.getString("country"));
        assertEquals("student", visa.getString("type"));
        assertEquals(2, visa.getInt("entries"));
        assertEquals("Visit", visa.getString("label"));
        assertEquals("2024-01-15", visa.getString("issueDate"), "the passport issue date is inherited");
        assertEquals("2034-01-15", visa.getString("expiryDate"), "a real passport expiry is inherited");
    }

    @Test
    void attachedVisaFallsBackToDefaultsAndPermanentValidity() {
        DocumentData passport = passport("2024-01-15", DateUtil.PERMANENT);
        CompoundTag form = new CompoundTag();
        form.putBoolean("includeVisa", true);

        CompoundTag visa = DeviceService.buildPassportVisa(DeviceBlockEntity.Locks.NONE,
                passport, form, "China");
        assertNotNull(visa);
        assertEquals("China", visa.getString("country"), "the style nationality is the last resort");
        assertEquals(1, visa.getInt("entries"), "entries default to 1");
        assertEquals("", visa.getString("label"));
        assertEquals("2024-01-15", visa.getString("issueDate"));
        assertEquals(DateUtil.plusYears("2024-01-15", 1), visa.getString("expiryDate"),
                "a permanent passport expiry falls back to one year after issue");

        // Locked validity 0 means a permanent visa even with a dated passport expiry.
        DocumentData dated = passport("2024-01-15", "2034-01-15");
        CompoundTag permanent = DeviceService.buildPassportVisa(locks(tag -> {
            tag.putBoolean("lockVisaValidity", true);
            tag.putInt("lockedVisaValidityMonths", 0);
        }), dated, form, "China");
        assertNotNull(permanent);
        assertEquals(DateUtil.PERMANENT, permanent.getString("expiryDate"));
    }

    @Test
    void attachedVisaCountryPrefersLockedCountryThenLegacyPlace() {
        DocumentData passport = passport("2024-01-15", "2034-01-15");
        CompoundTag form = new CompoundTag();
        form.putBoolean("includeVisa", true);
        form.putString("visaCountry", "Korea");

        DeviceBlockEntity.Locks both = locks(tag -> {
            tag.putBoolean("lockVisaCountry", true);
            tag.putString("lockedVisaCountry", "Schengen");
            tag.putBoolean("lockPlace", true);
            tag.putString("lockedPlace", "Shanghai");
        });
        assertEquals("Schengen", DeviceService.buildPassportVisa(both, passport, form, "China")
                .getString("country"), "the visa country lock wins over the legacy place lock");

        DeviceBlockEntity.Locks legacyPlace = locks(tag -> {
            tag.putBoolean("lockPlace", true);
            tag.putString("lockedPlace", "Shanghai");
        });
        assertEquals("Shanghai", DeviceService.buildPassportVisa(legacyPlace, passport, form, "China")
                .getString("country"), "the legacy place lock still applies when unlocked");

        assertEquals("Korea", DeviceService.buildPassportVisa(DeviceBlockEntity.Locks.NONE,
                passport, form, "China").getString("country"));

        // A lock flag with a blank value falls through instead of blanking the country.
        DeviceBlockEntity.Locks blank = locks(tag -> {
            tag.putBoolean("lockVisaCountry", true);
            tag.putString("lockedVisaCountry", "  ");
        });
        assertEquals("Korea", DeviceService.buildPassportVisa(blank, passport, form, "China")
                .getString("country"));
    }

    @Test
    void standaloneVisaIssuerAppliesTheNameLockAndReadsTheForm() {
        CompoundTag form = new CompoundTag();
        form.putString("country", "Japan");
        form.putString("type", "business");
        form.putInt("entries", 2);
        form.putString("issueDate", "2024-03-01");
        form.putString("expiryDate", "2025-03-01");
        form.putString("visaName", "Forged Name");

        DeviceBlockEntity.Locks locked = locks(tag -> {
            tag.putBoolean("lockVisaName", true);
            tag.putString("lockedVisaName", "Official Name");
        });
        CompoundTag lockedVisa = DeviceService.toVisa(DeviceService.applyVisaLocks(locked, form),
                "Embassy", "00112233", false);
        assertEquals("Official Name", lockedVisa.getString("label"));
        assertEquals("business", lockedVisa.getString("type"));
        assertEquals("Japan", lockedVisa.getString("country"));

        // Unlocked: the form name wins, with `label` as a fallback key.
        assertEquals("Forged Name", DeviceService.toVisa(
                DeviceService.applyVisaLocks(DeviceBlockEntity.Locks.NONE, form),
                "Embassy", "00112233", false).getString("label"));
        form.remove("visaName");
        form.putString("label", "From Label Key");
        assertEquals("From Label Key", DeviceService.toVisa(
                DeviceService.applyVisaLocks(DeviceBlockEntity.Locks.NONE, form),
                "Embassy", "00112233", false).getString("label"));
        form.remove("label");
        assertEquals("", DeviceService.toVisa(
                DeviceService.applyVisaLocks(DeviceBlockEntity.Locks.NONE, form),
                "Embassy", "00112233", false).getString("label"),
                "no name at all keeps the country/type fallback");
    }

    @Test
    void standaloneVisaLocksKeepTypeCountryDateAndValidity() {
        CompoundTag form = new CompoundTag();
        form.putString("country", "Korea");
        form.putString("type", "tourist");
        form.putInt("entries", 1);
        form.putString("issueDate", "2024-01-01");
        form.putString("expiryDate", "2024-06-01");

        DeviceBlockEntity.Locks locked = locks(tag -> {
            tag.putBoolean("lockVisaType", true);
            tag.putString("lockedVisaType", "business");
            tag.putBoolean("lockPlace", true);
            tag.putString("lockedPlace", "Schengen");
            tag.putBoolean("lockIssueDate", true);
            tag.putString("lockedIssueDate", "2024-02-29");
            tag.putBoolean("lockValidity", true);
            // Legacy year key: 26.2 migrates it to 60 months on read.
            tag.putInt("lockedValidityYears", 5);
        });
        CompoundTag visa = DeviceService.toVisa(DeviceService.applyVisaLocks(locked, form),
                "Embassy", "00112233", false);
        assertEquals("business", visa.getString("type"));
        assertEquals("Schengen", visa.getString("country"));
        assertEquals("2024-02-29", visa.getString("issueDate"));
        assertEquals("2029-02-28", visa.getString("expiryDate"),
                "a legacy 5-year lock becomes 60 months, leap day clamped");
        assertEquals("00112233", visa.getString("code"));
    }

    @Test
    void lockedIssueDateWithBlankValueForcesTodayAndIgnoresTheForm() {
        DeviceBlockEntity.Locks locked = locks(tag -> tag.putBoolean("lockIssueDate", true));
        CompoundTag submitted = new CompoundTag();
        submitted.putString("name", "ALEX MORGAN");
        submitted.putString("birthDate", "1991-02-03");
        submitted.putString("issueDate", "2020-01-01");

        CompoundTag enforced = DeviceService.applyIssueDateLock(locked, submitted);
        assertEquals(DateUtil.today(), enforced.getString("issueDate"),
                "a locked blank date means 'issued today', not 'no lock'");
        assertEquals("2020-01-01", submitted.getString("issueDate"),
                "the submitted form is never mutated");
        assertEquals(DateUtil.today(),
                DocumentFactory.fromForm(DocStyle.CN_PASSPORT, enforced).issueDate());

        // An unusable stored value is treated exactly like a blank one.
        DeviceBlockEntity.Locks unusable = locks(tag -> {
            tag.putBoolean("lockIssueDate", true);
            tag.putString("lockedIssueDate", "not-a-date");
        });
        assertEquals(DateUtil.today(),
                DeviceService.applyIssueDateLock(unusable, submitted).getString("issueDate"));
    }

    @Test
    void lockedIssueDateWithAConfiguredValueWinsAndRecomputesExpiry() {
        DeviceBlockEntity.Locks locked = locks(tag -> {
            tag.putBoolean("lockIssueDate", true);
            tag.putString("lockedIssueDate", " 2024-02-29 ");
        });
        CompoundTag submitted = new CompoundTag();
        submitted.putString("name", "ALEX MORGAN");
        submitted.putString("birthDate", "1991-02-03");
        submitted.putString("issueDate", "2020-01-01");
        submitted.putString("expiryDate", "2021-01-01");

        CompoundTag enforced = DeviceService.applyIssueDateLock(locked, submitted);
        assertEquals("2024-02-29", enforced.getString("issueDate"), "the locked value wins");
        DocumentData doc = DocumentFactory.fromForm(DocStyle.CN_PASSPORT, enforced);
        assertEquals("2024-02-29", doc.issueDate());
        // DeviceService#issueDocument recomputes the locked expiry after the date
        // was forced, so the validity follows the locked (not the submitted) date.
        doc.setExpiryDate(DocumentFactory.lockedExpiry(doc.issueDate(), 120));
        assertEquals("2034-02-28", doc.expiryDate(),
                "a 120-month (10-year) lock is recomputed from the forced issue date (leap day clamped)");
    }

    @Test
    void unlockedIssueDateKeepsTheSubmittedDate() {
        CompoundTag submitted = new CompoundTag();
        submitted.putString("name", "ALEX MORGAN");
        submitted.putString("birthDate", "1991-02-03");
        submitted.putString("issueDate", "2020-01-01");

        CompoundTag passed = DeviceService.applyIssueDateLock(DeviceBlockEntity.Locks.NONE, submitted);
        assertEquals("2020-01-01", passed.getString("issueDate"));
        assertEquals("2020-01-01",
                DocumentFactory.fromForm(DocStyle.CN_PASSPORT, passed).issueDate());

        // A stored date without the lock enabled has no effect.
        DeviceBlockEntity.Locks storedButUnlocked = locks(tag ->
                tag.putString("lockedIssueDate", "2024-02-29"));
        assertEquals("2020-01-01",
                DeviceService.applyIssueDateLock(storedButUnlocked, submitted).getString("issueDate"));
    }
}
