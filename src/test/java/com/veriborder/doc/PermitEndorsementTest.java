package com.veriborder.doc;

import com.veriborder.block.DeviceBlockEntity;
import com.veriborder.block.DeviceService;
import com.veriborder.item.ConfigCopyBrushConfig;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.2 permit built-in endorsement ("自带签注"): every permit issued by a permit
 * issuer carries an endorsement - three months, one entry by default - and the
 * machine may lock the type. The rules live in the pure
 * {@link DeviceService#buildInitialEndorsement} helper, so no world, block entity
 * or client is needed.
 */
class PermitEndorsementTest {
    /** Builds a lock view from raw NBT keys, exactly like a loaded device does. */
    private static DeviceBlockEntity.Locks locks(Consumer<CompoundTag> writer) {
        CompoundTag tag = new CompoundTag();
        writer.accept(tag);
        return DeviceBlockEntity.Locks.fromTag(tag);
    }

    /** A permit as {@code DeviceService#issueDocument} builds it (before the endorsement). */
    private static DocumentData permit(String issueDate, String authority) {
        CompoundTag form = new CompoundTag();
        form.putString("name", "LI JING / LI JING");
        form.putString("birthDate", "1997-09-09");
        form.putString("issueDate", issueDate);
        form.putString("authority", authority);
        return DocumentFactory.fromForm(DocStyle.HK_MACAO_PERMIT, form);
    }

    private static DocumentData permit(String issueDate) {
        return permit(issueDate, DocStyle.HK_MACAO_PERMIT.defaultAuthority());
    }

    private static CompoundTag formWith(String endorsementType) {
        CompoundTag form = new CompoundTag();
        if (endorsementType != null) {
            form.putString(DeviceService.INITIAL_ENDORSEMENT_TYPE, endorsementType);
        }
        return form;
    }

    @Test
    void permitIssueCarriesAValidThreeMonthSingleEndorsementByDefault() {
        DocumentData permit = permit(DateUtil.today());
        CompoundTag record = DeviceService.buildInitialEndorsement(
                DeviceBlockEntity.Locks.NONE, permit, new CompoundTag(), "");
        assertNotNull(record, "a permit issuer always attaches the built-in endorsement");
        EndorsementRecord endorsement = EndorsementRecord.fromTag(record);
        assertEquals(EndorsementType.THREE_MONTH_SINGLE, endorsement.type());
        assertEquals(1, endorsement.entries(), "three-month single = exactly one entry");
        assertEquals(0, endorsement.usedEntries());
        assertEquals(DateUtil.today(), endorsement.issueDate(), "starts on the issue date");
        assertEquals(DateUtil.plusMonths(DateUtil.today(), 3), endorsement.expiryDate(),
                "ends three calendar months later");
        assertEquals(CredentialState.VALID, endorsement.state(), "the fresh endorsement is valid");
        assertTrue(endorsement.active(), "it covers today, so it opens a gate");

        // On the document: visible, signed, and it survives an NBT round-trip.
        permit.addEndorsement(record);
        DocumentData reloaded = DocumentData.fromTag(permit.copyTag());
        assertTrue(reloaded.integrityValid());
        assertEquals(1, reloaded.endorsements().size());
        EndorsementRecord shown = reloaded.endorsements().get(0);
        assertEquals(EndorsementType.THREE_MONTH_SINGLE, shown.type());
        assertEquals(1, shown.entries());
        assertEquals(reloaded.issueDate(), shown.issueDate());
        assertEquals(DateUtil.plusMonths(reloaded.issueDate(), 3), shown.expiryDate());
        assertFalse(shown.issueDate().compareTo(DateUtil.today()) > 0, "never starts in the future");
        assertTrue(shown.active(), "the issued permit shows a usable endorsement");
    }

    @Test
    void lockedEndorsementTypeWinsOverEverySubmittedValue() {
        DeviceBlockEntity.Locks locked = locks(tag -> {
            tag.putBoolean("lockEndorsementType", true);
            tag.putString("lockedEndorsementType", "three_month_single");
        });
        // A forged type and an explicit "none" must both be ignored.
        for (String submitted : List.of("one_year_multi", "other", "none", "", "bogus")) {
            CompoundTag record = DeviceService.buildInitialEndorsement(
                    locked, permit(DateUtil.today()), formWith(submitted), "");
            assertNotNull(record, "a locked type cannot be switched off by the form: " + submitted);
            assertEquals(EndorsementType.THREE_MONTH_SINGLE,
                    EndorsementRecord.fromTag(record).type(),
                    "the locked type wins over '" + submitted + "'");
        }

        // A lock left on an unusable value still issues the built-in default.
        DeviceBlockEntity.Locks unusable = locks(tag -> {
            tag.putBoolean("lockEndorsementType", true);
            tag.putString("lockedEndorsementType", "not_a_type");
        });
        assertEquals(EndorsementType.THREE_MONTH_SINGLE, EndorsementRecord.fromTag(
                DeviceService.buildInitialEndorsement(unusable, permit(DateUtil.today()),
                        formWith("one_year_multi"), "")).type());
    }

    @Test
    void unlockedPermitIssuerHonoursTheApplicantChoice() {
        DocumentData permit = permit(DateUtil.today());
        CompoundTag chosen = DeviceService.buildInitialEndorsement(
                DeviceBlockEntity.Locks.NONE, permit, formWith("one_year_multi"), "");
        assertNotNull(chosen);
        assertEquals(EndorsementType.ONE_YEAR_MULTI, EndorsementRecord.fromTag(chosen).type());
        assertEquals(0, EndorsementRecord.fromTag(chosen).entries(), "one-year multiple = unlimited");

        // An explicit "none" is the only way to issue a permit without one.
        assertNull(DeviceService.buildInitialEndorsement(
                DeviceBlockEntity.Locks.NONE, permit, formWith("none"), ""));

        // A blank / unknown / absent value falls back to the built-in default.
        for (String submitted : new String[]{"", " ", "bogus", null}) {
            CompoundTag record = DeviceService.buildInitialEndorsement(
                    DeviceBlockEntity.Locks.NONE, permit, formWith(submitted), "");
            assertEquals(EndorsementType.THREE_MONTH_SINGLE,
                    EndorsementRecord.fromTag(record).type(),
                    "default for '" + submitted + "'");
        }
    }

    @Test
    void identityCardsAndPassportsCarryNoInitialEndorsement() {
        CompoundTag form = formWith("one_year_multi");
        assertNull(DeviceService.buildInitialEndorsement(DeviceBlockEntity.Locks.NONE,
                DocumentFactory.sample(DocStyle.CN_ID), form, ""), "an ID card is not a permit");
        assertNull(DeviceService.buildInitialEndorsement(DeviceBlockEntity.Locks.NONE,
                DocumentFactory.sample(DocStyle.CN_PASSPORT), form, ""), "a passport is not a permit");

        // ... and their own credentials are untouched by the permit rule.
        DocumentData passport = DocumentFactory.sample(DocStyle.CN_PASSPORT);
        int visas = passport.visas().size();
        DeviceService.buildInitialEndorsement(DeviceBlockEntity.Locks.NONE, passport, form, "");
        assertEquals(visas, passport.visas().size());
        assertTrue(passport.endorsements().isEmpty());
    }

    @Test
    void endorsementDatesFollowTheIssuedPermitNotTheValidityLock() {
        // A ten-year permit validity lock (120 months) must not stretch a
        // three-month endorsement, and the endorsement starts on the permit's date.
        DeviceBlockEntity.Locks longValidity = locks(tag -> {
            tag.putBoolean("lockValidity", true);
            tag.putInt("lockedValidityMonths", 120);
        });
        CompoundTag record = DeviceService.buildInitialEndorsement(
                longValidity, permit("2026-01-31"), new CompoundTag(), "");
        EndorsementRecord endorsement = EndorsementRecord.fromTag(record);
        assertEquals("2026-01-31", endorsement.issueDate());
        assertEquals("2026-04-30", endorsement.expiryDate(),
                "three calendar months from 31 January clamp to 30 April");
        assertEquals(EndorsementType.THREE_MONTH_SINGLE, endorsement.type());

        // A locked authority is the endorsement's authority, otherwise the permit's.
        DeviceBlockEntity.Locks lockedAuthority = locks(tag -> {
            tag.putBoolean("lockAuthority", true);
            tag.putString("lockedAuthority", "公安部出入境管理局");
        });
        assertEquals("公安部出入境管理局", DeviceService.buildInitialEndorsement(
                lockedAuthority, permit("2026-01-31"), new CompoundTag(), "").getString("authority"));
        assertEquals("Border Authority", DeviceService.buildInitialEndorsement(
                DeviceBlockEntity.Locks.NONE, permit("2026-01-31", "Border Authority"),
                new CompoundTag(), "").getString("authority"));
    }

    @Test
    void lockedInitialEndorsementCodeIsPreservedVerbatim() {
        CompoundTag record = DeviceService.buildInitialEndorsement(
                DeviceBlockEntity.Locks.NONE, permit(DateUtil.today()), new CompoundTag(), "00112233");
        assertEquals("00112233", record.getString("code"),
                "an admin-locked endorsement code is used verbatim");
        // An explicit form code is honoured when the machine has no lock.
        CompoundTag form = new CompoundTag();
        form.putString(DeviceService.INITIAL_ENDORSEMENT_CODE, "87654321");
        assertEquals("87654321", DeviceService.buildInitialEndorsement(
                DeviceBlockEntity.Locks.NONE, permit(DateUtil.today()), form, "").getString("code"));
    }

    @Test
    void copyBrushCarriesThePermitEndorsementTypeLock() {
        CompoundTag config = new CompoundTag();
        config.putBoolean("lockEndorsementType", true);
        config.putString("lockedEndorsementType", "three_month_single");
        CompoundTag copied = ConfigCopyBrushConfig.sanitize(config);
        assertTrue(copied.getBoolean("lockEndorsementType"));
        assertEquals("three_month_single", copied.getString("lockedEndorsementType"));
        // The lock survives the block-entity NBT round-trip as well.
        DeviceBlockEntity.Locks reloaded = DeviceBlockEntity.Locks.fromTag(copied);
        assertTrue(reloaded.lockEndorsementType());
        assertEquals(EndorsementType.THREE_MONTH_SINGLE,
                EndorsementType.byIdOrNull(reloaded.lockedEndorsementType()));
    }

    @Test
    void theBuiltInDefaultIsAThreeMonthSingle() {
        assertEquals(EndorsementType.THREE_MONTH_SINGLE, DeviceService.DEFAULT_INITIAL_ENDORSEMENT);
        assertEquals(3, DeviceService.DEFAULT_INITIAL_ENDORSEMENT.defaultMonths());
        assertEquals(1, DeviceService.DEFAULT_INITIAL_ENDORSEMENT.defaultEntries());
        assertEquals("none", DeviceService.INITIAL_ENDORSEMENT_NONE);
        // The helper never invents an issue date in the future.
        assertFalse(LocalDate.parse(EndorsementRecord.fromTag(DeviceService.buildInitialEndorsement(
                DeviceBlockEntity.Locks.NONE, permit(DateUtil.today()), new CompoundTag(), ""))
                .issueDate()).isAfter(LocalDate.now()));
    }
}
