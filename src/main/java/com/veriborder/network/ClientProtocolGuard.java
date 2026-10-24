package com.veriborder.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import org.slf4j.Logger;

/**
 * Client-side counterpart of {@link ProtocolGuard} (26.3, protocol 2).
 *
 * <p>Loaded only through {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)} in
 * {@link ModNetwork#register()}: the class references client-only Forge types, so
 * it must never be touched on a dedicated server. No {@code @Mod.EventBusSubscriber}
 * here on purpose — automatic scanning is dist-filtered, but the explicit
 * registration keeps the guarantee local and greppable.</p>
 *
 * <p>The guard covers the case the server-side one cannot: a server that accepts
 * our older protocol generation (or accepts every version) and would otherwise
 * let the client in with a different packet layout. A server that <em>rejects</em>
 * our protocol is refused by Forge before login, so this event never fires.</p>
 */
public final class ClientProtocolGuard {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ClientProtocolGuard() {
    }

    /** Registers the client listeners; called on the client only. */
    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(ClientProtocolGuard::onLoggingIn);
    }

    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        String remote = ModNetwork.remoteProtocol(event.getConnection());
        if (remote == null || ModNetwork.remoteCompatible(remote)) {
            return;
        }
        LOGGER.warn("VeriBorder protocol mismatch: client={} server={} - disconnecting",
                ModNetwork.PROTOCOL, ProtocolVersion.describe(remote));
        Component reason = ProtocolGuard.message(remote);
        event.getConnection().disconnect(reason);
    }
}
