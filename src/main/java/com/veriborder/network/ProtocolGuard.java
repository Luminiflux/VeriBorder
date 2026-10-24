package com.veriborder.network;

import com.mojang.logging.LogUtils;
import com.veriborder.VeriBorder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Server-side protocol guard (26.3, protocol 2).
 *
 * <p>Forge validates the channel version during the login handshake and answers
 * a mismatch with the generic "Connection closed - mismatched mod channel list"
 * screen. Because {@link ModNetwork} accepts the known legacy generations at
 * channel level, the login reaches this guard instead, which:
 * <ol>
 *   <li>writes both versions (server + client) to the log,</li>
 *   <li>sends the traveller a localized chat line explaining which side has to
 *       be updated,</li>
 *   <li>disconnects with that same text, so it also appears on the disconnect
 *       screen and in the client's chat history.</li>
 * </ol>
 *
 * <p>Only an actual protocol disagreement is refused: a host without the channel
 * (vanilla server, single-player, proxy) reports {@code null} and is left alone.</p>
 */
@Mod.EventBusSubscriber(modid = VeriBorder.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ProtocolGuard {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ProtocolGuard() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            enforce(player);
        }
    }

    /**
     * Disconnects {@code player} when the client speaks an incompatible protocol.
     *
     * @return {@code true} when the player was disconnected
     */
    public static boolean enforce(ServerPlayer player) {
        String remote = ModNetwork.remoteProtocol(player.connection.connection);
        if (remote == null || ModNetwork.remoteCompatible(remote)) {
            return false;
        }
        LOGGER.warn("VeriBorder protocol mismatch: server={} client={} player={} uuid={} - disconnecting",
                ModNetwork.PROTOCOL, ProtocolVersion.describe(remote),
                player.getGameProfile().getName(), player.getUUID());
        Component reason = message(remote);
        // The chat line survives on the client even after the connection drops,
        // which makes the reason readable in the log as well as on the screen.
        player.sendSystemMessage(reason);
        player.connection.disconnect(reason);
        return true;
    }

    /** Localized "both versions + which side to update" reason. */
    public static Component message(String remote) {
        return Component.translatable(ProtocolVersion.MESSAGE_MISMATCH,
                        ModNetwork.PROTOCOL, ProtocolVersion.describe(remote))
                .append(" ")
                .append(Component.translatable(ProtocolVersion.hintKey(ModNetwork.PROTOCOL, remote)));
    }
}
