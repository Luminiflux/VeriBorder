package com.veriborder.client.render;

import com.veriborder.VeriBorder;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 26.4.1 lifecycle of the eight dynamic cover textures.
 *
 * <p>Kept apart from {@code ClientEvents} on purpose: that class listens on the
 * mod bus (setup, registration), while this one observes the running client.</p>
 *
 * <p>Leaving a world releases the uploaded base maps. Without this the eight GL
 * textures and the eight base64 payloads would survive into the next server, and a
 * server that happens to use the same hash could show a picture the player is no
 * longer authorised to see. The next snapshot re-registers and re-uploads, so the
 * cost is one upload per map after joining.</p>
 */
@Mod.EventBusSubscriber(modid = VeriBorder.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class DocumentRenderEvents {
    private DocumentRenderEvents() {
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TemplateSlotTextures.release();
    }
}
