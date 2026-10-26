package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.4.1 portrait library: the 24 preset ids, the upload limits, the fallback
 * chain and the wire snapshot.
 *
 * <p>The library is the only place a portrait payload is trusted, so every limit
 * (PNG signature, 32/64 square, 16 KiB, 32 uploads) is asserted here rather than
 * on the client. The images themselves are the document-rendering owner's
 * artwork; this test only covers the ids and the path contract, plus
 * {@code tools/check_portrait_textures.ps1} for the PNGs.</p>
 */
class PortraitLibraryTest {
    // ------------------------------------------------------------- fixtures

    /** A payload that only satisfies the PNG header checks (no pixel data). */
    static byte[] fakePng(int width, int height, int length) {
        return fakePng(width, height, length, 0);
    }

    /** Same, with a filler byte so two payloads hash differently. */
    static byte[] fakePng(int width, int height, int length, int seed) {
        byte[] data = new byte[Math.max(24, length)];
        data[0] = (byte) 0x89;
        data[1] = 'P';
        data[2] = 'N';
        data[3] = 'G';
        data[4] = 0x0D;
        data[5] = 0x0A;
        data[6] = 0x1A;
        data[7] = 0x0A;
        data[12] = 'I';
        data[13] = 'H';
        data[14] = 'D';
        data[15] = 'R';
        putInt(data, 16, width);
        putInt(data, 20, height);
        for (int index = 24; index < data.length; index++) {
            data[index] = (byte) (seed * 31 + index);
        }
        return data;
    }

    private static void putInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    // ------------------------------------------------------------------- ids

    @Test
    void theTwentyFourPresetsAreDeclaredInOrder() {
        List<String> presets = PortraitLibrary.presets();
        assertEquals(24, presets.size());
        assertEquals("p00", presets.get(0));
        assertEquals("p23", presets.get(23));
        Set<String> unique = new HashSet<>(presets);
        assertEquals(24, unique.size(), "preset ids must be unique");
        for (String id : presets) {
            assertTrue(PortraitLibrary.isPreset(id), id + " must be a preset");
            assertEquals(id, PortraitLibrary.normalizeId(id), "a preset normalises to itself");
        }
        assertEquals("p07", PortraitLibrary.presetId(7));
        assertEquals(7, PortraitLibrary.presetIndex("p07"));
        assertEquals("p00", PortraitLibrary.presetId(-3), "below range clamps to the first preset");
        assertEquals("p23", PortraitLibrary.presetId(99), "above range clamps to the last preset");
        assertEquals(-1, PortraitLibrary.presetIndex("p24"));
        assertEquals(-1, PortraitLibrary.presetIndex("emblem"));
    }

    @Test
    void everyPresetMapsToItsShippedResourcePath() {
        assertEquals(32, PortraitLibrary.PRESET_SOURCE_EDGE,
                "presets ship as 32x32 pixel art and are upscaled to the photo box");
        for (int index = 0; index < PortraitLibrary.PRESET_COUNT; index++) {
            String id = PortraitLibrary.presetId(index);
            assertEquals("textures/portrait/" + id + ".png", PortraitLibrary.resourcePath(id));
        }
        assertEquals("", PortraitLibrary.resourcePath("skin"), "the skin is not a resource");
        assertEquals("", PortraitLibrary.resourcePath("custom:abc"));
        assertEquals("", PortraitLibrary.resourcePath(null));
    }

    @Test
    void anUnknownOrMalformedIdReadsAsTheSkin() {
        assertEquals("skin", PortraitLibrary.normalizeId(null));
        assertEquals("skin", PortraitLibrary.normalizeId(""));
        assertEquals("skin", PortraitLibrary.normalizeId("   "));
        assertEquals("skin", PortraitLibrary.normalizeId("p24"), "out of range");
        assertEquals("skin", PortraitLibrary.normalizeId("nope"));
        assertEquals("skin", PortraitLibrary.normalizeId("custom:"), "no hash");
        assertEquals("skin", PortraitLibrary.normalizeId("custom:zzzz"), "not hex");
        assertEquals("custom:abc123", PortraitLibrary.normalizeId("custom:abc123"));
        assertEquals("custom:abc123", PortraitLibrary.normalizeId("CUSTOM:ABC123"));
        assertEquals("p07", PortraitLibrary.normalizeId("P07"), "ids are case-insensitive");
    }

    @Test
    void aPortraitOwnerIsACanonicalUuidOrNothing() {
        String uuid = "3f1a0b6c-8f5e-4d2a-9c7b-1e2d3c4b5a69";
        assertEquals(uuid, PortraitLibrary.normalizeOwner(uuid.toUpperCase()));
        assertEquals(uuid, PortraitLibrary.normalizeOwner("  " + uuid + "  "));
        assertEquals("", PortraitLibrary.normalizeOwner(null));
        assertEquals("", PortraitLibrary.normalizeOwner(""));
        assertEquals("", PortraitLibrary.normalizeOwner("not-a-uuid"));
        assertEquals("", PortraitLibrary.normalizeOwner("1-2-3-4-5"), "shortened groups are refused");
    }

    // -------------------------------------------------------- fallback chain

    @Test
    void theFallbackChainIsCustomThenSkinThenTheFirstPreset() {
        String custom = "custom:" + "ab".repeat(32);
        // A resolvable upload is drawn as uploaded.
        assertEquals(custom, PortraitLibrary.resolve(custom, true, true));
        assertEquals(custom, PortraitLibrary.resolve(custom, true, false));
        // Missing upload: fall to the holder skin, then to p00.
        assertEquals("skin", PortraitLibrary.resolve(custom, false, true));
        assertEquals("p00", PortraitLibrary.resolve(custom, false, false));
        // The skin reference itself falls to p00 when no UUID could be resolved.
        assertEquals("skin", PortraitLibrary.resolve("skin", false, true));
        assertEquals("p00", PortraitLibrary.resolve("skin", false, false));
        assertEquals("p00", PortraitLibrary.resolve(null, false, false));
        // A preset never consults the other sources.
        assertEquals("p07", PortraitLibrary.resolve("p07", false, false));
        assertEquals("p07", PortraitLibrary.resolve("p07", true, true));
        // An unknown id is treated as the skin reference.
        assertEquals("skin", PortraitLibrary.resolve("bogus", false, true));
        assertEquals("p00", PortraitLibrary.resolve("bogus", false, false));
    }

    // -------------------------------------------------------------- uploads

    @Test
    void anEmptyPayloadIsRefused() {
        PortraitLibrary library = new PortraitLibrary();
        assertEquals(PortraitLibrary.PortraitRejection.EMPTY, library.put(null).rejection());
        assertEquals(PortraitLibrary.PortraitRejection.EMPTY, library.put(new byte[0]).rejection());
        assertFalse(library.put(null).ok());
        assertEquals("message.veriborder.portrait.empty", library.put(null).errorKey());
        assertEquals(0, library.size());
    }

    @Test
    void aNonPngPayloadIsRefused() {
        PortraitLibrary library = new PortraitLibrary();
        byte[] jpeg = new byte[64];
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;
        PortraitLibrary.PortraitResult result = library.put(jpeg);
        assertEquals(PortraitLibrary.PortraitRejection.NOT_PNG, result.rejection());
        assertEquals("message.veriborder.portrait.not_png", result.errorKey());
        assertEquals(0, library.size());

        // A PNG signature without an IHDR chunk is refused too.
        byte[] noHeader = fakePng(32, 32, 64);
        noHeader[12] = 'X';
        assertEquals(PortraitLibrary.PortraitRejection.NOT_PNG, library.put(noHeader).rejection());
    }

    @Test
    void anOversizedPayloadIsRefused() {
        PortraitLibrary library = new PortraitLibrary();
        byte[] tooLarge = fakePng(32, 32, PortraitLibrary.MAX_BYTES + 1);
        PortraitLibrary.PortraitResult result = library.put(tooLarge);
        assertEquals(PortraitLibrary.PortraitRejection.TOO_LARGE, result.rejection());
        assertEquals("message.veriborder.portrait.too_large", result.errorKey());
        assertEquals(0, library.size());
    }

    @Test
    void onlyASquareOf32Or64PixelsIsAccepted() {
        PortraitLibrary library = new PortraitLibrary();
        assertEquals(PortraitLibrary.PortraitRejection.BAD_SIZE, library.put(fakePng(16, 16, 400)).rejection());
        assertEquals(PortraitLibrary.PortraitRejection.BAD_SIZE, library.put(fakePng(128, 128, 400)).rejection());
        assertEquals(PortraitLibrary.PortraitRejection.BAD_SIZE, library.put(fakePng(32, 64, 400)).rejection(),
                "a portrait must be square");
        assertEquals(PortraitLibrary.PortraitRejection.BAD_SIZE, library.put(fakePng(24, 24, 400)).rejection());
        assertEquals("message.veriborder.portrait.bad_size",
                library.put(fakePng(48, 48, 400)).errorKey());
        assertEquals(0, library.size());

        assertTrue(library.put(fakePng(32, 32, 400)).ok());
        assertTrue(library.put(fakePng(64, 64, 800, 1)).ok());
        assertEquals(2, library.size());
    }

    @Test
    void theLibraryHoldsAtMostThirtyTwoUploads() {
        PortraitLibrary library = new PortraitLibrary();
        for (int index = 0; index < PortraitLibrary.MAX_CUSTOM; index++) {
            assertTrue(library.put(fakePng(32, 32, 200, index)).ok(), "upload " + index + " must fit");
        }
        assertEquals(PortraitLibrary.MAX_CUSTOM, library.size());
        PortraitLibrary.PortraitResult overflow = library.put(fakePng(32, 32, 200, 999));
        assertEquals(PortraitLibrary.PortraitRejection.TOO_MANY, overflow.rejection());
        assertEquals("message.veriborder.portrait.too_many", overflow.errorKey());
        assertEquals(PortraitLibrary.MAX_CUSTOM, library.size(), "a refusal never evicts a stored portrait");
    }

    @Test
    void theSamePayloadIsStoredOnceAndAddressableById() {
        PortraitLibrary library = new PortraitLibrary();
        byte[] png = fakePng(32, 32, 512);
        PortraitLibrary.PortraitResult first = library.put(png);
        assertTrue(first.ok());
        String hash = first.hash();
        assertEquals(hash, PortraitLibrary.hashOf(png));
        assertEquals("custom:" + hash, first.id());
        assertEquals(hash, first.id().substring("custom:".length()));

        PortraitLibrary.PortraitResult again = library.put(png.clone());
        assertTrue(again.ok(), "re-uploading identical bytes is a no-op, not a failure");
        assertEquals(hash, again.hash());
        assertEquals(1, library.size(), "the hash is the address: no duplicate rows");

        assertTrue(library.has(first.id()));
        assertTrue(library.has(hash), "a bare hash addresses the upload too");
        assertFalse(library.has("custom:" + "0".repeat(64)));

        byte[] stored = library.portrait(first.id());
        assertNotNull(stored);
        // The library hands out a copy: mutating it must not corrupt the stored PNG.
        stored[30] = 0x7F;
        assertNotNull(library.portrait(first.id()));
        assertTrue(java.util.Arrays.equals(png, library.portrait(first.id())));
    }

    @Test
    void removingAnUploadByIdOrHashWorks() {
        PortraitLibrary library = new PortraitLibrary();
        String id = library.put(fakePng(32, 32, 300)).id();
        String hash = PortraitLibrary.hashOfId(id);
        assertTrue(library.remove(hash));
        assertFalse(library.has(id));
        assertFalse(library.remove(hash), "removing twice reports nothing to remove");
        assertEquals(0, library.size());
    }

    @Test
    void releaseUnusedDropsEveryUploadTheCallerDoesNotKeep() {
        PortraitLibrary library = new PortraitLibrary();
        String keep = library.put(fakePng(32, 32, 300, 1)).id();
        String drop = library.put(fakePng(32, 32, 300, 2)).id();
        int released = library.releaseUnused(id -> id.equals(keep));
        assertEquals(1, released);
        assertTrue(library.has(keep));
        assertFalse(library.has(drop));
        assertEquals(0, library.releaseUnused(null), "a null predicate keeps everything");
    }

    // -------------------------------------------------------------- snapshot

    @Test
    void anUploadIsSerialisedAsAHashAndBase64Row() {
        PortraitLibrary library = new PortraitLibrary();
        byte[] png = fakePng(64, 64, 900, 5);
        String id = library.put(png).id();
        String hash = PortraitLibrary.hashOfId(id);

        CompoundTag snapshot = library.snapshot();
        ListTag rows = snapshot.getList(PortraitLibrary.KEY, Tag.TAG_COMPOUND);
        assertEquals(1, rows.size());
        CompoundTag row = rows.getCompound(0);
        assertEquals(hash, row.getString("hash"));
        byte[] decoded = Base64.getDecoder().decode(row.getString("data"));
        assertTrue(java.util.Arrays.equals(png, decoded), "base64 must round-trip the exact bytes");

        // A hand-assembled snapshot reads back into a library.
        PortraitLibrary parsed = new PortraitLibrary();
        parsed.readFrom(snapshot);
        assertEquals(1, parsed.size());
        assertTrue(parsed.has(id));
        assertTrue(java.util.Arrays.equals(png, parsed.portrait(id)));
    }

    @Test
    void aHandEditedSnapshotRowIsRevalidatedOnLoad() {
        CompoundTag tag = new CompoundTag();
        ListTag rows = new ListTag();
        CompoundTag good = new CompoundTag();
        good.putString("hash", PortraitLibrary.hashOf(fakePng(32, 32, 400)));
        good.putString("data", Base64.getEncoder().encodeToString(fakePng(32, 32, 400)));
        rows.add(good);
        CompoundTag notPng = new CompoundTag();
        notPng.putString("data", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4}));
        rows.add(notPng);
        CompoundTag wrongSize = new CompoundTag();
        wrongSize.putString("data", Base64.getEncoder().encodeToString(fakePng(16, 16, 300)));
        rows.add(wrongSize);
        CompoundTag badBase64 = new CompoundTag();
        badBase64.putString("data", "!!!not base64!!!");
        rows.add(badBase64);
        tag.put(PortraitLibrary.KEY, rows);

        PortraitLibrary library = new PortraitLibrary();
        library.readFrom(tag);
        assertEquals(1, library.size(), "only the valid row is loaded");
    }

    @Test
    void theSnapshotStopsAtItsPayloadBudget() {
        PortraitLibrary library = new PortraitLibrary();
        // Twelve 16 KiB uploads are ~262 KiB of base64: over the 256 KiB budget.
        for (int index = 0; index < 12; index++) {
            assertTrue(library.put(fakePng(64, 64, PortraitLibrary.MAX_BYTES, index)).ok());
        }
        assertEquals(12, library.size());

        ListTag rows = library.snapshot().getList(PortraitLibrary.KEY, Tag.TAG_COMPOUND);
        assertTrue(rows.size() < library.size(), "the wire snapshot is budgeted, the library is not");
        int chars = 0;
        for (int index = 0; index < rows.size(); index++) {
            chars += rows.getCompound(index).getString("data").length();
        }
        assertTrue(chars <= PortraitLibrary.MAX_SNAPSHOT_CHARS,
                "the snapshot payload must stay inside " + PortraitLibrary.MAX_SNAPSHOT_CHARS + ", was " + chars);

        // The save file keeps every upload, budget or not.
        CompoundTag save = new CompoundTag();
        library.writeTo(save);
        assertEquals(12, save.getList(PortraitLibrary.KEY, Tag.TAG_COMPOUND).size());
    }

    @Test
    void anEmptyLibrarySerialisesToAnEmptyList() {
        PortraitLibrary library = new PortraitLibrary();
        CompoundTag save = new CompoundTag();
        library.writeTo(save);
        assertNotNull(save.get(PortraitLibrary.KEY));
        assertEquals(0, save.getList(PortraitLibrary.KEY, Tag.TAG_COMPOUND).size());
        assertEquals(0, library.snapshot().getList(PortraitLibrary.KEY, Tag.TAG_COMPOUND).size());
        library.readFrom(null);
        assertEquals(0, library.size(), "a missing compound is simply an empty library");
    }

    @Test
    void aHashIsLowercaseHexAndStable() {
        String hash = PortraitLibrary.hashOf(fakePng(32, 32, 200));
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertEquals(hash, PortraitLibrary.hashOf(fakePng(32, 32, 200)), "hashing is deterministic");
        assertEquals("", PortraitLibrary.hashOf(null));
        assertEquals("", PortraitLibrary.hashOf(new byte[0]));
        assertEquals("", PortraitLibrary.normalizeHash("xyz"));
        assertEquals("", PortraitLibrary.normalizeHash("  "));
        assertEquals("abcdef", PortraitLibrary.normalizeHash(" ABCDEF "));
        assertNull(PortraitLibrary.pngSize(new byte[]{1, 2, 3}));
    }
}
