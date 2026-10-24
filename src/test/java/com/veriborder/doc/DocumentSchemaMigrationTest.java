package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Document schema migration (26.3 round 11, document schema 2).
 *
 * <p>The signed tag makes this the delicate schema in the mod: adding a
 * compatibility key changes the checksum. The migration must therefore upgrade
 * authentic data, never launder a tampered tag, and be idempotent.</p>
 */
class DocumentSchemaMigrationTest {
    /** A schema-1 tag that is signed with its own (old) layout. */
    private static CompoundTag authenticSchemaOne() {
        CompoundTag tag = DocumentData.createEmpty(DocStyle.CN_PASSPORT).copyTag();
        tag.putInt("schema", 1);
        tag.remove(DocumentData.PERMANENT_CODES);
        DocumentIntegrity.sign(tag);
        assertTrue(DocumentIntegrity.verify(tag), "fixture must be authentic");
        return tag;
    }

    @Test
    void aCurrentDocumentIsNotTouched() {
        CompoundTag tag = DocumentData.createEmpty(DocStyle.CN_ID).copyTag();
        assertEquals(DocumentSchema.CURRENT, DocumentSchema.read(tag));
        assertFalse(DocumentSchema.migrate(tag), "already current: no change");
        assertTrue(DocumentIntegrity.verify(tag));
    }

    @Test
    void anAuthenticLegacyDocumentIsUpgradedAndStaysSigned() {
        CompoundTag tag = authenticSchemaOne();
        String number = tag.getString("number");
        String authority = tag.getString("authority");
        String expiry = tag.getString("expiryDate");

        assertTrue(DocumentSchema.migrate(tag), "schema 1 -> 2 must change the tag");

        assertEquals(DocumentSchema.CURRENT, tag.getInt("schema"));
        assertTrue(tag.contains(DocumentData.PERMANENT_CODES, Tag.TAG_LIST),
                "the 26.3 permanent-code list is materialised");
        assertTrue(tag.contains("visas", Tag.TAG_LIST));
        assertTrue(tag.contains("endorsements", Tag.TAG_LIST));
        assertTrue(tag.contains("stamps", Tag.TAG_LIST));
        assertTrue(DocumentIntegrity.verify(tag), "an authentic upgrade is re-signed, not invalidated");

        // Values are preserved exactly: this is a structural upgrade.
        assertEquals(number, tag.getString("number"));
        assertEquals(authority, tag.getString("authority"));
        assertEquals(expiry, tag.getString("expiryDate"));
        assertEquals(DocStyle.CN_PASSPORT.id(), tag.getString("style"));
        assertEquals(DocumentKind.PASSPORT.name(), tag.getString("kind"));
        assertEquals(DocumentStatus.VALID.id(), tag.getString("status"));

        assertFalse(DocumentSchema.migrate(tag), "migration is idempotent");
    }

    @Test
    void aTamperedDocumentIsNeverUpgradedOrResigned() {
        CompoundTag tag = authenticSchemaOne();
        tag.putString("name", "MALLORY"); // invalidate the checksum
        assertFalse(DocumentIntegrity.verify(tag));

        assertFalse(DocumentSchema.migrate(tag), "tampered data must not be migrated");
        assertEquals(1, tag.getInt("schema"), "schema is left untouched");
        assertFalse(tag.contains(DocumentData.PERMANENT_CODES), "nothing is added to a tampered tag");
        assertFalse(DocumentIntegrity.verify(tag), "tampering must stay detectable");
    }

    @Test
    void anUnsignedLegacyDocumentIsUpgradedButNotSigned() {
        CompoundTag tag = DocumentData.createEmpty(DocStyle.CN_ID).copyTag();
        tag.putInt("schema", 1);
        tag.remove(DocumentIntegrity.KEY);
        tag.remove(DocumentData.PERMANENT_CODES);

        assertTrue(DocumentSchema.migrate(tag));
        assertEquals(DocumentSchema.CURRENT, tag.getInt("schema"));
        assertFalse(tag.contains(DocumentIntegrity.KEY), "unsigned data must not gain a signature");
        assertFalse(DocumentIntegrity.verify(tag), "and it still reads as unverified");
    }

    @Test
    void loadingALegacyTagUpgradesItInPlace() {
        CompoundTag tag = authenticSchemaOne();
        DocumentData document = DocumentData.fromTag(tag);

        assertEquals(DocumentSchema.CURRENT, tag.getInt("schema"), "fromTag migrates the stored tag");
        assertTrue(DocumentIntegrity.verify(tag));
        assertNotNull(document);
        assertEquals(DocStyle.CN_PASSPORT.id(), document.style().id());
        assertTrue(document.integrityValid());
        assertTrue(document.permanentCodes().isEmpty());
        // A migrated passport is still a readable, valid passport.
        assertTrue(document.isRecordUsable());
    }

    @Test
    void readingATamperedLegacyTagKeepsItInvalid() {
        CompoundTag tag = authenticSchemaOne();
        tag.putString("authority", "FORGED AUTHORITY");
        DocumentData document = DocumentData.fromTag(tag);

        assertEquals(1, tag.getInt("schema"), "a tampered tag is not migrated on read");
        assertFalse(document.integrityValid(), "the reader still reports the tampering");
    }
}
