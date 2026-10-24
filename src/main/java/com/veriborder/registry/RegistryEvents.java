package com.veriborder.registry;

import com.veriborder.VeriBorder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Pushes the credential-registry snapshot to a player on login, and again after
 * every datapack sync (which the server runs for all players after {@code /reload}).
 *
 * <p>The gate and dual-gate brush screens read the registry for their country
 * whitelist selectors, so a client that never opened the registry still needs the
 * snapshot. 26.3 round 12 adds the {@code /reload} path: the cache is client-side
 * only, so a reload that re-sent nothing would leave every selector empty until
 * the registry was opened by hand.</p>
 */
@Mod.EventBusSubscriber(modid = VeriBorder.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RegistryEvents {
    private RegistryEvents() {
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RegistryService.pushSnapshot(player);
        }
    }

    /**
     * Datapack sync: sent to one player on login and to every player after
     * {@code /reload}. Re-pushing the registry keeps the client cache - and
     * therefore every gate/config screen opened afterwards - correct.
     */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player != null) {
            RegistryService.pushSnapshot(player);
            return;
        }
        MinecraftServer server = event.getPlayerList() == null ? null : event.getPlayerList().getServer();
        RegistryService.syncAll(server);
    }
}
