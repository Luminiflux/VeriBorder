package com.veriborder.border;

import com.veriborder.VeriBorder;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Deferred persistence for {@link BorderTravelData} (26.3 round 11).
 *
 * <p>Every entry/exit/log write used to call {@code setDirty()} immediately. The
 * world save is throttled by vanilla anyway, but flagging the saved data on every
 * single write makes the save set large and unpredictable, and the officer
 * console can produce dozens of writes per interaction. Instead the data object
 * only records that it has unwritten state; this listener commits that flag at
 * most once per {@link BorderTravelData#FLUSH_INTERVAL_TICKS} ticks.</p>
 *
 * <p>Everything here runs on the server thread: the tick handler and the server
 * stop handler read and write the same in-memory maps the game thread uses, so
 * no map is ever touched from another thread. The interval is a <em>batching</em>
 * window, not an asynchronous disk write — a crash can therefore lose at most
 * {@link BorderTravelData#FLUSH_INTERVAL_TICKS} ticks of border history, while a
 * clean shutdown always flushes.</p>
 */
@Mod.EventBusSubscriber(modid = VeriBorder.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BorderPersistence {
    private BorderPersistence() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        BorderTravelData.get(server).flushIfDue(server.overworld().getGameTime());
    }

    /** Forced flush on shutdown: the final world save must include everything. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        if (server != null) {
            BorderTravelData.get(server).flush();
        }
    }
}
