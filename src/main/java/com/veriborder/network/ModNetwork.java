package com.veriborder.network;

import com.veriborder.VeriBorder;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.ConnectionData;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.jetbrains.annotations.Nullable;

/**
 * The single {@code veriborder:main} channel. Protocol 3 (26.3 round 12): the
 * registry snapshot packet is part of the wire surface, so a protocol-2 client
 * can never silently talk to this build and end up with an empty credential
 * registry. {@link ProtocolVersion} owns the compatibility matrix; the guards in
 * {@link ProtocolGuard} / {@code ClientProtocolGuard} turn a mismatch into a
 * readable message instead of Forge's generic "mismatched mod channel list".
 */
public final class ModNetwork {
    public static final String PROTOCOL = ProtocolVersion.CURRENT;
    public static final ResourceLocation CHANNEL_NAME = new ResourceLocation(VeriBorder.MOD_ID, "main");

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(CHANNEL_NAME)
            .networkProtocolVersion(() -> PROTOCOL)
            // Accept the known legacy generations at channel level so the login
            // guard can explain the mismatch (see ProtocolVersion's class javadoc).
            .clientAcceptedVersions(ProtocolVersion::accepts)
            .serverAcceptedVersions(ProtocolVersion::accepts)
            .simpleChannel();

    private static int nextId;
    private static int packetCount;

    private ModNetwork() {
    }

    public static void register() {
        add(OpenDevicePacket.class, NetworkDirection.PLAY_TO_CLIENT,
                OpenDevicePacket::encode, OpenDevicePacket::new, OpenDevicePacket::handle);
        add(DeviceActionPacket.class, NetworkDirection.PLAY_TO_SERVER,
                DeviceActionPacket::encode, DeviceActionPacket::new, DeviceActionPacket::handle);
        add(ModifierSavePacket.class, NetworkDirection.PLAY_TO_SERVER,
                ModifierSavePacket::encode, ModifierSavePacket::new, ModifierSavePacket::handle);
        add(ModifierGeneratePacket.class, NetworkDirection.PLAY_TO_SERVER,
                ModifierGeneratePacket::encode, ModifierGeneratePacket::new, ModifierGeneratePacket::handle);
        add(GateBrushPacket.class, NetworkDirection.PLAY_TO_SERVER,
                GateBrushPacket::encode, GateBrushPacket::new, GateBrushPacket::handle);
        add(DualGateBrushPacket.class, NetworkDirection.PLAY_TO_SERVER,
                DualGateBrushPacket::encode, DualGateBrushPacket::new, DualGateBrushPacket::handle);
        add(BorderTerminalDataPacket.class, NetworkDirection.PLAY_TO_CLIENT,
                BorderTerminalDataPacket::encode, BorderTerminalDataPacket::new, BorderTerminalDataPacket::handle);
        add(RegistrySnapshotPacket.class, NetworkDirection.PLAY_TO_CLIENT,
                RegistrySnapshotPacket::encode, RegistrySnapshotPacket::new, RegistrySnapshotPacket::handle);
        add(BorderTerminalActionPacket.class, NetworkDirection.PLAY_TO_SERVER,
                BorderTerminalActionPacket::encode, BorderTerminalActionPacket::new,
                BorderTerminalActionPacket::handle);
        // Client-only guard registration. DistExecutor keeps the reference inside a
        // lambda that never runs on a dedicated server, so no client class is loaded.
        DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> ClientProtocolGuard::register);
    }

    private static <M> void add(Class<M> type, NetworkDirection direction,
                                java.util.function.BiConsumer<M, net.minecraft.network.FriendlyByteBuf> encoder,
                                java.util.function.Function<net.minecraft.network.FriendlyByteBuf, M> decoder,
                                java.util.function.BiConsumer<M, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context>> handler) {
        CHANNEL.messageBuilder(type, nextId++, direction)
                .encoder(encoder)
                .decoder(decoder)
                .consumerMainThread(handler)
                .add();
        packetCount++;
    }

    /** Number of registered packets (diagnose payload). */
    public static int packetCount() {
        return packetCount;
    }

    /**
     * The protocol string the remote side advertised for this channel, or
     * {@code null} when the remote has no VeriBorder channel (vanilla server,
     * single-player host without the mod) or the handshake data is unavailable.
     */
    @Nullable
    public static String remoteProtocol(@Nullable Connection connection) {
        if (connection == null) {
            return null;
        }
        ConnectionData data = NetworkHooks.getConnectionData(connection);
        if (data == null) {
            return null;
        }
        return data.getChannels().get(CHANNEL_NAME);
    }

    /**
     * True when {@code remote} may play on this build. Absent channels count as
     * compatible so a mod-less host never gets kicked by our guard.
     */
    public static boolean remoteCompatible(@Nullable String remote) {
        return ProtocolVersion.compatible(remote);
    }
}
