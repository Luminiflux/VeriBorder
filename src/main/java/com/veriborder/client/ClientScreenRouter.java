package com.veriborder.client;

import com.veriborder.VeriBorder;
import com.veriborder.api.OpenScreenEvent;
import com.veriborder.client.screen.ClientScreens;
import com.veriborder.client.screen.GateConfigBrushScreen;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-only subscriber for {@link OpenScreenEvent}. Keeping every
 * {@code Screen}/{@code Minecraft} reference here is what lets the common
 * packets and items load on a dedicated server.
 */
@Mod.EventBusSubscriber(modid = VeriBorder.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientScreenRouter {
    private ClientScreenRouter() {
    }

    @SubscribeEvent
    public static void onOpenScreen(OpenScreenEvent event) {
        switch (event.kind()) {
            case GATE_BRUSH -> Minecraft.getInstance().setScreen(new GateConfigBrushScreen(event.stack()));
            case DUAL_GATE_BRUSH -> ClientScreens.openDualGateBrush(event.stack());
            case BORDER_TERMINAL -> ClientScreens.openBorderTerminal(event.data());
            case DEVICE -> ClientPacketHandler.handleOpenDevice(event.pos(), event.device(), event.data());
            default -> {
            }
        }
    }
}
