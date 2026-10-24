package com.veriborder.network;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol 3 compatibility matrix (26.3 round 12). Pure logic — no game instance,
 * which is the point of keeping {@link ProtocolVersion} free of Minecraft types.
 *
 * <p>Protocol 3 ships the credential-registry snapshot packet, so both older
 * generations (2 and 1) are recognised at channel level but refused at login with
 * an "update that side" hint.</p>
 */
class ProtocolVersionTest {
    @Test
    void protocolIsThreeAndBothLegacyGenerationsAreStillRecognised() {
        assertEquals("3", ModNetwork.PROTOCOL);
        assertEquals(ProtocolVersion.CURRENT, ModNetwork.PROTOCOL);
        assertEquals(List.of("2", "1"), ProtocolVersion.LEGACY);
    }

    @Test
    void channelAcceptanceCoversCurrentAndLegacyButNotNewerOrAbsent() {
        // Accepted at channel level so the login guard can explain the mismatch.
        assertTrue(ProtocolVersion.accepts("3"));
        assertTrue(ProtocolVersion.accepts("2"));
        assertTrue(ProtocolVersion.accepts("1"));
        assertTrue(ProtocolVersion.accepts(" 3 "));
        // A peer without the channel must stay incompatible for the server list.
        assertFalse(ProtocolVersion.accepts(null));
        assertFalse(ProtocolVersion.accepts(""));
        assertFalse(ProtocolVersion.accepts("4"));
        assertFalse(ProtocolVersion.accepts("ABSENT 🤔"));
    }

    @Test
    void verdictsClassifyEveryDocumentedCase() {
        assertEquals(ProtocolVersion.Verdict.MATCH, ProtocolVersion.compare("3"));
        assertEquals(ProtocolVersion.Verdict.REMOTE_OLDER, ProtocolVersion.compare("2"));
        assertEquals(ProtocolVersion.Verdict.REMOTE_OLDER, ProtocolVersion.compare("1"));
        assertEquals(ProtocolVersion.Verdict.REMOTE_NEWER, ProtocolVersion.compare("4"));
        assertEquals(ProtocolVersion.Verdict.ABSENT, ProtocolVersion.compare(null));
        assertEquals(ProtocolVersion.Verdict.ABSENT, ProtocolVersion.compare(""));
        // Forge sentinels for "remote has no such channel" / "remote is vanilla".
        assertEquals(ProtocolVersion.Verdict.ABSENT, ProtocolVersion.compare("ABSENT 🤔"));
        assertEquals(ProtocolVersion.Verdict.ABSENT, ProtocolVersion.compare("ALLOWVANILLA 💕💕💕"));
        assertEquals(ProtocolVersion.Verdict.UNKNOWN, ProtocolVersion.compare("custom-build"));
    }

    @Test
    void onlyAMatchingProtocolMayPlay() {
        assertTrue(ProtocolVersion.compatible("3"));
        assertFalse(ProtocolVersion.compatible("2"), "a protocol-2 client must not play on protocol 3");
        assertFalse(ProtocolVersion.compatible("1"));
        assertFalse(ProtocolVersion.compatible("4"));
        // Absent/unknown channels: no proof of a mismatch, so do not kick.
        assertTrue(ProtocolVersion.compatible(null));
        assertTrue(ProtocolVersion.compatible("ABSENT 🤔"));
        assertTrue(ProtocolVersion.compatible("custom-build"));
    }

    @Test
    void theOlderSideIsTheOneToldToUpdate() {
        assertEquals(ProtocolVersion.MESSAGE_UPDATE_CLIENT, ProtocolVersion.hintKey("3", "2"));
        assertEquals(ProtocolVersion.MESSAGE_UPDATE_CLIENT, ProtocolVersion.hintKey("3", "1"));
        assertEquals(ProtocolVersion.MESSAGE_UPDATE_SERVER, ProtocolVersion.hintKey("3", "4"));
        assertEquals(ProtocolVersion.MESSAGE_UPDATE_CLIENT, ProtocolVersion.hintKey("3", "2"));
        // Unparseable remote: fall back to the server-side hint, never a null key.
        assertEquals(ProtocolVersion.MESSAGE_UPDATE_SERVER, ProtocolVersion.hintKey("3", "garbage"));
    }

    @Test
    void matrixIsGeneratedFromTheSamePredicates() {
        List<String> rows = ProtocolVersion.matrix();
        assertTrue(rows.size() >= 5, "matrix documents current, both legacy, newer and absent");
        assertTrue(rows.stream().anyMatch(row -> row.contains("client 3 : compatible")), rows.toString());
        assertTrue(rows.stream().anyMatch(row -> row.contains("client 2 : refused")
                && row.contains(ProtocolVersion.MESSAGE_UPDATE_CLIENT)), rows.toString());
        assertTrue(rows.stream().anyMatch(row -> row.contains("client 1 : refused")
                && row.contains(ProtocolVersion.MESSAGE_UPDATE_CLIENT)), rows.toString());
        assertTrue(rows.stream().anyMatch(row -> row.contains("client 4 : refused")
                && row.contains(ProtocolVersion.MESSAGE_UPDATE_SERVER)), rows.toString());
        assertTrue(rows.stream().anyMatch(row -> row.contains("client absent : compatible")), rows.toString());
    }

    @Test
    void theRegistrySnapshotPacketIsPartOfProtocolThree() {
        // The protocol bump exists because of this packet: a generation without it
        // would open a registry editor with no countries/templates.
        assertTrue(ProtocolVersion.FEATURES.contains(ProtocolVersion.FEATURE_REGISTRY_SNAPSHOT));
        assertTrue(ProtocolVersion.has("3", ProtocolVersion.FEATURE_REGISTRY_SNAPSHOT));
        assertTrue(ProtocolVersion.features("3").contains(ProtocolVersion.FEATURE_REGISTRY_SNAPSHOT));
        assertTrue(ProtocolVersion.features("2").isEmpty(), "protocol 2 predates the registry snapshot");
        assertTrue(ProtocolVersion.features("1").isEmpty());
        assertFalse(ProtocolVersion.has("2", ProtocolVersion.FEATURE_REGISTRY_SNAPSHOT));
        // The matrix prints the feature, so an operator can see why 2 is refused.
        assertTrue(ProtocolVersion.matrix().get(0).contains(ProtocolVersion.FEATURE_REGISTRY_SNAPSHOT));
    }

    @Test
    void describeRendersAReadableVersion() {
        assertEquals("3", ProtocolVersion.describe("3"));
        assertEquals("2", ProtocolVersion.describe("2"));
        assertEquals("absent", ProtocolVersion.describe(null));
        assertEquals("absent", ProtocolVersion.describe("ABSENT 🤔"));
    }
}
