package com.veriborder.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wire-protocol identity and compatibility matrix for the {@code veriborder:main}
 * channel (26.3 round 12, protocol 3).
 *
 * <p>This class is deliberately free of Minecraft/Forge types so the matrix can
 * be unit-tested without a game instance. {@link ModNetwork} owns the channel and
 * the two guards ({@link ProtocolGuard} on the server, {@link ClientProtocolGuard}
 * on the client) turn a verdict into a readable disconnect message.</p>
 *
 * <h2>Why the channel accepts legacy versions</h2>
 * <p>Forge refuses a login whose channel version does not satisfy
 * {@code serverAcceptedVersions}/{@code clientAcceptedVersions} with the generic
 * "mismatched mod channel list" screen, before any of our code runs. To show a
 * <em>readable</em> message instead, the channel additionally accepts every
 * protocol generation listed in {@link #LEGACY}; the login guard then disconnects
 * the peer with a localized explanation and both versions in the log. Only
 * {@link #CURRENT} is actually playable — {@link #accepts(String)} therefore
 * answers "may this packet stream reach the guard?" and {@link #compatible(String)}
 * answers "may this peer play?"</p>
 *
 * <h2>Protocol 3</h2>
 * <p>Protocol 3 adds the credential-registry snapshot packet
 * ({@link #FEATURE_REGISTRY_SNAPSHOT}) and the placeholder-only registry action
 * vocabulary: a protocol-2 peer would silently ignore {@code RegistrySnapshotPacket}
 * and open a registry editor with no countries/templates, so 2 and 1 are refused
 * with the "update the client/server" hint. {@link #features(String)} reports the
 * wire features a generation carries, which is what {@code /veriborder diagnose}
 * prints next to the matrix.</p>
 */
public final class ProtocolVersion {
    /** Current wire protocol. Bump with every packet layout change. */
    public static final String CURRENT = "3";

    /** Older generations we still recognise (and can therefore explain). */
    public static final List<String> LEGACY = List.of("2", "1");

    /**
     * Wire feature introduced by protocol 3: the S→C credential-registry
     * snapshot ({@code RegistrySnapshotPacket}). Without it a client cannot
     * resolve registered country codes or templates.
     */
    public static final String FEATURE_REGISTRY_SNAPSHOT = "registry_snapshot";

    /** Every wire feature of {@link #CURRENT}, in report order. */
    public static final List<String> FEATURES = List.of(FEATURE_REGISTRY_SNAPSHOT);

    /** Translation keys shown when the two sides disagree. */
    public static final String MESSAGE_MISMATCH = "message.veriborder.protocol.mismatch";
    public static final String MESSAGE_UPDATE_CLIENT = "message.veriborder.protocol.update_client";
    public static final String MESSAGE_UPDATE_SERVER = "message.veriborder.protocol.update_server";

    /** Forge's "the remote has no such channel" version strings start like this. */
    private static final String FORGE_ABSENT = "ABSENT";
    private static final String FORGE_VANILLA = "ALLOWVANILLA";

    public enum Verdict {
        /** The remote speaks exactly {@link #CURRENT}. */
        MATCH,
        /** The remote speaks an older known generation (here: {@code 2} or {@code 1}). */
        REMOTE_OLDER,
        /** The remote speaks a newer generation than this build. */
        REMOTE_NEWER,
        /** No channel on the remote (vanilla server, or the mod is not installed). */
        ABSENT,
        /** A version string we do not understand (hand-edited config, future build). */
        UNKNOWN
    }

    private ProtocolVersion() {
    }

    /**
     * Channel-level acceptance. True for the current protocol and every known
     * legacy generation, false for absent channels: a peer that does not ship
     * VeriBorder at all must still be flagged as incompatible by Forge's server
     * list ping.
     */
    public static boolean accepts(String version) {
        if (version == null) {
            return false;
        }
        String trimmed = version.trim();
        return CURRENT.equals(trimmed) || LEGACY.contains(trimmed);
    }

    /** Classifies a version advertised by the remote side. */
    public static Verdict compare(String remote) {
        if (remote == null || remote.isBlank()) {
            return Verdict.ABSENT;
        }
        String version = remote.trim();
        if (version.startsWith(FORGE_ABSENT) || version.startsWith(FORGE_VANILLA)) {
            // Forge sends these sentinels when the remote has no channel at all.
            return Verdict.ABSENT;
        }
        if (CURRENT.equals(version)) {
            return Verdict.MATCH;
        }
        Integer local = number(CURRENT);
        Integer other = number(version);
        if (other != null) {
            if (other < local) {
                return Verdict.REMOTE_OLDER;
            }
            if (other > local) {
                return Verdict.REMOTE_NEWER;
            }
        }
        return LEGACY.contains(version) ? Verdict.REMOTE_OLDER : Verdict.UNKNOWN;
    }

    /**
     * True when a peer advertising {@code remote} may play on this build.
     *
     * <p>Absent/unknown channels are tolerated: a single-player world without the
     * channel, a proxy or a future build must not be kicked by a mod that simply
     * cannot prove anything about it. Every <em>known</em> disagreement is
     * refused — that is the whole point of the protocol bump.</p>
     */
    public static boolean compatible(String remote) {
        Verdict verdict = compare(remote);
        return verdict == Verdict.MATCH || verdict == Verdict.ABSENT || verdict == Verdict.UNKNOWN;
    }

    /**
     * The side that should install a new VeriBorder build. {@code remote} is the
     * peer's advertised protocol, {@code local} this build's protocol.
     */
    public static String hintKey(String local, String remote) {
        Integer localNumber = number(local == null ? CURRENT : local.trim());
        Integer remoteNumber = number(remote == null ? "" : remote.trim());
        if (localNumber != null && remoteNumber != null) {
            return remoteNumber < localNumber ? MESSAGE_UPDATE_CLIENT : MESSAGE_UPDATE_SERVER;
        }
        // Unknown generations: the older side is the one we cannot identify, so
        // point at both sides instead of guessing.
        return MESSAGE_UPDATE_SERVER;
    }

    /** Rendered version for logs and the diagnose command ("absent", "2", "4"). */
    public static String describe(String remote) {
        Verdict verdict = compare(remote);
        return switch (verdict) {
            case MATCH -> CURRENT;
            case ABSENT -> "absent";
            case REMOTE_OLDER, REMOTE_NEWER -> remote.trim();
            case UNKNOWN -> remote.trim().isEmpty() ? "unknown" : remote.trim();
        };
    }

    /**
     * Wire features a protocol generation carries. Protocol 3 introduced the
     * credential-registry snapshot; older generations have none of the 26.3
     * round 12 packets.
     */
    public static List<String> features(String version) {
        Integer number = number(version == null ? "" : version.trim());
        if (number == null || number < 3) {
            return List.of();
        }
        return FEATURES;
    }

    /** True when a generation carries one named wire feature. */
    public static boolean has(String version, String feature) {
        return FEATURE_REGISTRY_SNAPSHOT.equals(feature) && features(version).contains(feature);
    }

    /**
     * Human-readable compatibility matrix for {@code /veriborder diagnose}. The
     * rows are generated from the same predicates the network path uses, so the
     * command cannot drift from the enforcement.
     */
    public static List<String> matrix() {
        List<String> rows = new ArrayList<>();
        for (String remote : candidates()) {
            Verdict verdict = compare(remote);
            String status = compatible(remote) ? "compatible" : "refused";
            String hint = compatible(remote) ? "" : " -> " + hintKey(CURRENT, remote);
            String features = features(remote).isEmpty() ? "" : " [" + String.join(",", features(remote)) + "]";
            rows.add(String.format(Locale.ROOT, "server %s <-> client %s : %s%s%s",
                    CURRENT, describe(remote), status, features, hint));
        }
        return rows;
    }

    /** Every remote version string the matrix documents, in display order. */
    private static List<String> candidates() {
        List<String> versions = new ArrayList<>();
        versions.add(CURRENT);
        versions.addAll(LEGACY);
        Integer current = number(CURRENT);
        if (current != null) {
            versions.add(Integer.toString(current + 1));
        }
        versions.add("absent");
        return versions;
    }

    private static Integer number(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
