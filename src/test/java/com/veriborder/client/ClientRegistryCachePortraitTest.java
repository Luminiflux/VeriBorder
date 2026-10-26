package com.veriborder.client;

import com.veriborder.doc.PortraitLibrary;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.4.1 portrait rows of the credential-registry snapshot.
 *
 * <p>The registry publishes uploaded portraits as {@code {portraits: [{hash,
 * data(base64)}]}} inside the snapshot every client already receives. This is the
 * client half of that contract: rows parse, duplicates collapse onto their hash,
 * a row without a payload is skipped (a budget slip must degrade to the holder
 * skin, never to an empty photo box) and a fresh snapshot replaces the cache.</p>
 */
class ClientRegistryCachePortraitTest {

    /** Two distinct payloads that only need to satisfy the PNG header checks. */
    private static byte[] fakePng(int width, int height, int seed) {
        byte[] data = new byte[200];
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
        data[16] = (byte) (width >>> 24);
        data[17] = (byte) (width >>> 16);
        data[18] = (byte) (width >>> 8);
        data[19] = (byte) width;
        data[20] = (byte) (height >>> 24);
        data[21] = (byte) (height >>> 16);
        data[22] = (byte) (height >>> 8);
        data[23] = (byte) height;
        for (int index = 24; index < data.length; index++) {
            data[index] = (byte) (seed * 29 + index);
        }
        return data;
    }

    private static CompoundTag snapshot(byte[]... payloads) {
        CompoundTag tag = new CompoundTag();
        ListTag rows = new ListTag();
        for (byte[] payload : payloads) {
            CompoundTag row = new CompoundTag();
            row.putString("hash", PortraitLibrary.hashOf(payload));
            row.putString("data", Base64.getEncoder().encodeToString(payload));
            rows.add(row);
        }
        tag.put(PortraitLibrary.KEY, rows);
        return tag;
    }

    @AfterEach
    void resetCache() {
        // The cache is process-global; leave it empty for the next test class.
        ClientRegistryCache.update(new CompoundTag());
    }

    @Test
    void snapshotRowsParseIntoPortraits() {
        byte[] first = fakePng(32, 32, 1);
        byte[] second = fakePng(64, 64, 2);
        List<ClientRegistryCache.Portrait> portraits =
                ClientRegistryCache.portraitsOf(snapshot(first, second));

        assertEquals(2, portraits.size());
        assertEquals(PortraitLibrary.hashOf(first), portraits.get(0).hash());
        assertEquals("custom:" + PortraitLibrary.hashOf(first), portraits.get(0).id());
        assertTrue(portraits.get(0).hasData());
        assertEquals(Base64.getEncoder().encodeToString(first), portraits.get(0).data());
    }

    @Test
    void duplicateHashesCollapseOntoOneRow() {
        byte[] payload = fakePng(32, 32, 7);
        List<ClientRegistryCache.Portrait> portraits =
                ClientRegistryCache.portraitsOf(snapshot(payload, payload.clone(), payload.clone()));
        assertEquals(1, portraits.size(), "the hash is the identity of an upload");
    }

    @Test
    void rowsWithoutAHashOrPayloadAreSkipped() {
        CompoundTag tag = new CompoundTag();
        ListTag rows = new ListTag();
        CompoundTag noHash = new CompoundTag();
        noHash.putString("data", Base64.getEncoder().encodeToString(fakePng(32, 32, 3)));
        rows.add(noHash);
        CompoundTag noData = new CompoundTag();
        noData.putString("hash", PortraitLibrary.hashOf(fakePng(32, 32, 4)));
        rows.add(noData);
        CompoundTag badHash = new CompoundTag();
        badHash.putString("hash", "not-hex");
        badHash.putString("data", Base64.getEncoder().encodeToString(fakePng(32, 32, 5)));
        rows.add(badHash);
        tag.put(PortraitLibrary.KEY, rows);

        assertTrue(ClientRegistryCache.portraitsOf(tag).isEmpty());
        assertTrue(ClientRegistryCache.portraitsOf(null).isEmpty(),
                "an older server snapshot simply carries no portraits");
    }

    @Test
    void anUpdateReplacesThePortraitsAndBumpsTheRevision() {
        long before = ClientRegistryCache.revision();
        byte[] payload = fakePng(32, 32, 9);
        ClientRegistryCache.update(snapshot(payload));

        assertEquals(1, ClientRegistryCache.portraits().size());
        assertTrue(ClientRegistryCache.revision() > before, "the renderer watches this counter");

        String id = "custom:" + PortraitLibrary.hashOf(payload);
        assertNotNull(ClientRegistryCache.portrait(id));
        assertNotNull(ClientRegistryCache.portrait(PortraitLibrary.hashOf(payload)),
                "a bare hash resolves as well");
        assertNull(ClientRegistryCache.portrait("custom:" + "0".repeat(64)));
        assertNull(ClientRegistryCache.portrait("p07"), "a preset is not a snapshot row");
        assertNull(ClientRegistryCache.portrait(null));

        long afterFirst = ClientRegistryCache.revision();
        ClientRegistryCache.update(new CompoundTag());
        assertTrue(ClientRegistryCache.portraits().isEmpty(), "a fresh snapshot drops gone uploads");
        assertTrue(ClientRegistryCache.revision() > afterFirst);
    }

    @Test
    void aPresetIsNotAnUpload() {
        ClientRegistryCache.update(snapshot(fakePng(32, 32, 11)));
        assertFalse(ClientRegistryCache.portraits().isEmpty());
        // Presets live in the mod resources, never in the snapshot.
        for (ClientRegistryCache.Portrait portrait : ClientRegistryCache.portraits()) {
            assertTrue(PortraitLibrary.isCustom(portrait.id()));
        }
    }
}
