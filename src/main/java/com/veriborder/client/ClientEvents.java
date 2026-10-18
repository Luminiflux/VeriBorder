package com.veriborder.client;

import com.veriborder.VeriBorder;
import com.veriborder.block.DeviceType;
import com.veriborder.item.DocumentItem;
import com.veriborder.registry.ModBlockEntities;
import com.veriborder.registry.ModBlocks;
import com.veriborder.registry.ModItems;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = VeriBorder.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        // The reworked device models use semi-transparent glass inserts and the
        // E-Gate uses transparent glass panels. Without a translucent render
        // layer the default solid layer ignores their alpha and paints them as
        // opaque slabs.
        event.enqueueWork(() -> {
            for (DeviceType type : DeviceType.values()) {
                // Gates have no semi-transparent static faces (the blades are
                // drawn by EGateRenderer), so cutout avoids translucent-sorting
                // flicker across the many overlapping gate elements.
                ItemBlockRenderTypes.setRenderLayer(ModBlocks.block(type),
                        type.isGate() ? RenderType.cutout() : RenderType.translucent());
            }
        });
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.DEVICE.get(), EGateRenderer::new);
    }

    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> tintIndex == 0 ? DocumentItem.tintColor(stack) : -1,
                ModItems.ID_CARD.get(), ModItems.PASSPORT.get(), ModItems.HK_MACAO_PERMIT.get());
    }
}
