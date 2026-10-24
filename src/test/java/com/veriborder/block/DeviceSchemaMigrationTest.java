package com.veriborder.block;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Device block-entity schema migration (26.3 round 11, device schema 2).
 *
 * <p>The migration pins the historical defaults down once and rewrites the
 * pre-26.3 {@code all} gate mode to its 26.3 equivalent, so a stored device keeps
 * its behaviour across the model change.</p>
 */
class DeviceSchemaMigrationTest {
    private static CompoundTag legacyDevice() {
        CompoundTag tag = new CompoundTag();
        tag.putString("port", "  Beijing  ");
        tag.putString("gateMode", "ALL");
        tag.putInt("overstayDays", 99_999);
        tag.putString("gateCodes", "12345678");
        return tag;
    }

    @Test
    void aLegacyDeviceIsCompletedAndStamped() {
        CompoundTag tag = legacyDevice();

        assertTrue(DeviceSchema.migrate(tag));

        assertEquals(DeviceBlockEntity.GATE_MODE_BOTH, tag.getString("gateMode"),
                "the removed all-mode maps to both");
        assertEquals(DeviceBlockEntity.MAX_OVERSTAY_DAYS, tag.getInt("overstayDays"), "allowance is clamped");
        assertTrue(tag.getBoolean("autoStamp"), "autoStamp defaults on");
        assertTrue(tag.getBoolean("lockNumber"), "lockNumber defaults on");
        assertFalse(tag.getBoolean("lockNationality"), "nationality lock defaults off");
        assertTrue(tag.getBoolean("issueEntryState"), "issue-entry policy defaults on");
        assertTrue(tag.contains("dualGate", Tag.TAG_COMPOUND));
        assertTrue(tag.contains("presets", Tag.TAG_LIST));
        assertTrue(tag.contains("gateCodes", Tag.TAG_LIST), "the string code list becomes a list");
        assertEquals(1, tag.getList("gateCodes", Tag.TAG_STRING).size());
        assertEquals("12345678", tag.getList("gateCodes", Tag.TAG_STRING).getString(0));
        assertEquals("Beijing", tag.getString("port"), "strings are trimmed once");
        assertEquals(DeviceSchema.CURRENT, tag.getInt(DeviceSchema.SCHEMA_KEY));

        assertFalse(DeviceSchema.migrate(tag), "migration is idempotent");
    }

    @Test
    void anAlreadyCurrentDeviceIsNotTouched() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(DeviceSchema.SCHEMA_KEY, DeviceSchema.CURRENT);
        tag.putString("gateMode", DeviceBlockEntity.GATE_MODE_VISA);
        tag.putString("port", "  Keep my spaces  ");

        assertFalse(DeviceSchema.migrate(tag));
        assertEquals("  Keep my spaces  ", tag.getString("port"));
        assertFalse(tag.contains("autoStamp"), "a current tag does not gain migration defaults");
        assertFalse(tag.contains("dualGate"));
    }

    @Test
    void legacyModeAliasesAreCanonicalised() {
        for (String alias : new String[]{"ALL", "all", "any", "ANY"}) {
            CompoundTag tag = new CompoundTag();
            tag.putString("gateMode", alias);
            DeviceSchema.migrate(tag);
            assertEquals(DeviceBlockEntity.GATE_MODE_BOTH, tag.getString("gateMode"), alias);
        }
        CompoundTag permit = new CompoundTag();
        permit.putString("gateMode", "permit");
        DeviceSchema.migrate(permit);
        assertEquals(DeviceBlockEntity.GATE_MODE_ENDORSEMENT, permit.getString("gateMode"));
        CompoundTag passport = new CompoundTag();
        passport.putString("gateMode", "passport");
        DeviceSchema.migrate(passport);
        assertEquals(DeviceBlockEntity.GATE_MODE_VISA, passport.getString("gateMode"));
        CompoundTag blank = new CompoundTag();
        DeviceSchema.migrate(blank);
        assertEquals(DeviceBlockEntity.GATE_MODE_BOTH, blank.getString("gateMode"), "blank defaults to both");
    }

    @Test
    void anExistingCodeListIsPreserved() {
        CompoundTag tag = new CompoundTag();
        ListTag codes = new ListTag();
        codes.add(StringTag.valueOf("11111111"));
        codes.add(StringTag.valueOf("22222222"));
        tag.put("gateCodes", codes);

        DeviceSchema.migrate(tag);

        assertEquals(2, tag.getList("gateCodes", Tag.TAG_STRING).size());
        assertEquals("22222222", tag.getList("gateCodes", Tag.TAG_STRING).getString(1));
    }

    @Test
    void negativeAndUnsetOverstayValuesAreClamped() {
        CompoundTag negative = new CompoundTag();
        negative.putInt("overstayDays", -5);
        DeviceSchema.migrate(negative);
        assertEquals(0, negative.getInt("overstayDays"));

        CompoundTag missing = new CompoundTag();
        DeviceSchema.migrate(missing);
        assertEquals(DeviceBlockEntity.DEFAULT_OVERSTAY_DAYS, missing.getInt("overstayDays"));
    }
}
