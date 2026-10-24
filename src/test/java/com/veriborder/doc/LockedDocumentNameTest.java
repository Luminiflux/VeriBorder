package com.veriborder.doc;

import com.veriborder.block.DeviceBlockEntity;
import com.veriborder.block.DeviceService;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.4 bug fix: "发证机锁定自定义证件名无效".
 *
 * <p>The issuing machine's document-name lock used to clear the display name
 * unconditionally ({@code lockDocumentName ? "" : displayName}), so a machine
 * locked to "Test" still issued the official preset title ("香港护照"). The
 * device now supplies its locked title on a dedicated trusted form key
 * ({@link DocumentFactory#LOCKED_DISPLAY_NAME}) that the applicant form can
 * neither supply nor unlock.</p>
 */
class LockedDocumentNameTest {
    private static DeviceBlockEntity.Locks locksLockingName(String lockedName) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("lockDocumentName", true);
        tag.putString("lockedDocumentName", lockedName);
        return DeviceBlockEntity.Locks.fromTag(tag);
    }

    private static CompoundTag applicantForm() {
        CompoundTag form = new CompoundTag();
        form.putString("name", "ALEX MORGAN");
        form.putString("nameLatin", "MORGAN ALEX");
        form.putString("birthDate", "1991-02-03");
        form.putString("sex", "M");
        return form;
    }

    @Test
    void lockedCustomNameReachesTheIssuedDocument() {
        CompoundTag submitted = applicantForm();
        // A forged title plus a forged custom cover from the applicant form.
        submitted.putString("displayName", "FORGED TITLE");
        submitted.putBoolean("customStyle", true);
        submitted.putString("customName", "FORGED COVER");

        CompoundTag form = DeviceService.applyLockedDocumentName(locksLockingName("Test"), submitted);
        DocumentData doc = DocumentFactory.fromForm(DocStyle.HK_PASSPORT, form);

        assertEquals("Test", doc.displayName());
        assertEquals("Test", doc.displayNameComponent().getString());
        assertTrue(doc.hasCustomDisplayName());
        // The holder's name is never locked, and no forged cover survives.
        assertEquals("ALEX MORGAN", doc.name());
        assertFalse(doc.customStyle());
        assertTrue(doc.integrityValid());
    }

    @Test
    void blankLockedNameKeepsTheOfficialPresetTitle() {
        CompoundTag form = DeviceService.applyLockedDocumentName(locksLockingName(""), applicantForm());
        DocumentData doc = DocumentFactory.fromForm(DocStyle.HK_PASSPORT, form);

        assertFalse(doc.hasCustomDisplayName());
        assertEquals(DocumentFactory.sample(DocStyle.HK_PASSPORT).displayName(), doc.displayName());
        assertTrue(doc.integrityValid());
    }

    @Test
    void forgedTrustedKeyIsOverwrittenByTheDevice() {
        CompoundTag submitted = applicantForm();
        submitted.putString("displayName", "FORGED TITLE");
        submitted.putString(DocumentFactory.LOCKED_DISPLAY_NAME, "FORGED TRUSTED KEY");

        CompoundTag form = DeviceService.applyLockedDocumentName(locksLockingName("Test"), submitted);
        // Exactly one trusted value may survive: the device's own.
        assertEquals("Test", form.getString(DocumentFactory.LOCKED_DISPLAY_NAME));
        assertFalse(form.contains("displayName"));

        DocumentData doc = DocumentFactory.fromForm(DocStyle.HK_PASSPORT, form);
        assertEquals("Test", doc.displayName());
    }

    @Test
    void forgedLockFlagCannotLockAnUnlockedMachine() {
        CompoundTag submitted = applicantForm();
        submitted.putBoolean("lockDocumentName", true);
        submitted.putString(DocumentFactory.LOCKED_DISPLAY_NAME, "FORGED TRUSTED KEY");
        submitted.putString("displayName", "Legal Applicant Title");
        // Trusted template keys must not survive from the client either.
        submitted.putInt(DocumentFactory.LOCKED_STYLE_COLOR, 0x00FF00);

        CompoundTag form = DeviceService.applyLockedDocumentName(DeviceBlockEntity.Locks.NONE, submitted);
        assertFalse(form.getBoolean("lockDocumentName"), "the device flag always wins");
        assertFalse(form.contains(DocumentFactory.LOCKED_DISPLAY_NAME),
                "a client-supplied trusted key is dropped while the lock is off");
        assertFalse(form.contains(DocumentFactory.LOCKED_STYLE_COLOR),
                "the template colour is only ever injected by the device");

        DocumentData doc = DocumentFactory.fromForm(DocStyle.HK_PASSPORT, form);
        assertEquals("Legal Applicant Title", doc.displayName());
        assertEquals(DocStyle.HK_PASSPORT.accentColor(), doc.accentColor());
        assertTrue(doc.integrityValid());
    }

    @Test
    void unlockedMachineKeepsApplicantTitleAndCustomCover() {
        CompoundTag submitted = applicantForm();
        submitted.putString("displayName", "Special Border Pass");
        CompoundTag form = DeviceService.applyLockedDocumentName(DeviceBlockEntity.Locks.NONE, submitted);

        DocumentData official = DocumentFactory.fromForm(DocStyle.CN_ID, form);
        assertEquals("Special Border Pass", official.displayName());
        assertTrue(official.integrityValid());

        // The custom cover still works when the name is not locked either.
        CompoundTag cover = applicantForm();
        cover.putBoolean("customStyle", true);
        cover.putString("customName", "Custom Cover");
        DocumentData custom = DocumentFactory.fromForm(DocStyle.CN_ID,
                DeviceService.applyLockedDocumentName(DeviceBlockEntity.Locks.NONE, cover));
        assertTrue(custom.customStyle());
        assertEquals("Custom Cover", custom.displayName());
        assertTrue(custom.integrityValid());
    }

    @Test
    void lockedNameSurvivesTheNbtRoundTrip() {
        CompoundTag form = DeviceService.applyLockedDocumentName(locksLockingName("Test"), applicantForm());
        DocumentData doc = DocumentFactory.fromForm(DocStyle.HK_PASSPORT, form);

        DocumentData loaded = DocumentData.fromTag(doc.copyTag());
        assertEquals("Test", loaded.displayName());
        assertTrue(loaded.hasCustomDisplayName());
        assertTrue(loaded.integrityValid());
    }
}
