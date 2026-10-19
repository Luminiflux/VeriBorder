package com.veriborder;

import com.veriborder.network.ModNetwork;
import com.veriborder.registry.ModBlockEntities;
import com.veriborder.registry.ModBlocks;
import com.veriborder.registry.ModCreativeTabs;
import com.veriborder.registry.ModItems;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(VeriBorder.MOD_ID)
public final class VeriBorder {
    public static final String MOD_ID = "veriborder";
    private static final Logger LOGGER = LogUtils.getLogger();

    public VeriBorder() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);
        ModCreativeTabs.TABS.register(modBus);
        modBus.addListener(ModCreativeTabs::buildContents);
        ModNetwork.register();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        long modRecipes = server.getRecipeManager().getRecipes().stream()
                .filter(recipe -> recipe.getId().getNamespace().equals(MOD_ID))
                .count();
        LOGGER.info("VeriBorder server ready: {} recipes total, {} VeriBorder recipes.",
                server.getRecipeManager().getRecipes().size(), modRecipes);
    }
}
