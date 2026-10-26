package com.veriborder.client;

import com.veriborder.VeriBorder;
import com.veriborder.block.DeviceType;
import com.veriborder.client.render.DocumentAppearance;
import com.veriborder.client.render.DocumentLayers;
import com.veriborder.client.render.TemplateSlotTextures;
import com.veriborder.registry.ModBlockEntities;
import com.veriborder.registry.ModBlocks;
import com.veriborder.registry.ModItems;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.util.List;

@Mod.EventBusSubscriber(modid = VeriBorder.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEvents {
    /** Item-model predicate selecting the preset crest of a cover. */
    public static final ResourceLocation EMBLEM_PROPERTY =
            new ResourceLocation(VeriBorder.MOD_ID, "emblem");
    /** Item-model predicate selecting the dynamic base-map slot of a cover. */
    public static final ResourceLocation SLOT_PROPERTY =
            new ResourceLocation(VeriBorder.MOD_ID, "texslot");

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

    /**
     * 26.5 document cards: layer0 of every document model is the whole flat card
     * and is tinted through {@code ItemColors} index 0. The crest (layer1,
     * tintIndex 1) is never tinted, so a coloured card keeps a neutral seal.
     *
     * <p>The colour comes from {@link DocumentAppearance}, i.e. from the same
     * resolver the viewer cover, the issuing-machine preview and the border
     * terminal read ({@code TemplateDisplay.coverTint}), so the inventory icon can
     * never show a different colour than the document page.</p>
     */
    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> tintIndex == DocumentLayers.TINT_INDEX
                        ? 0xFF000000 | DocumentAppearance.ofStack(stack).tint()
                        : -1,
                ModItems.ID_CARD.get(), ModItems.PASSPORT.get(), ModItems.HK_MACAO_PERMIT.get());
    }

    /**
     * 26.4.1 item-model predicates of the document covers:
     * <ul>
     *   <li>{@code veriborder:emblem} = crest index / 16, which selects one of the
     *       fifteen crest variants of the item model,</li>
     *   <li>{@code veriborder:texslot} = dynamic base-map slot (0..7), or -1 when the
     *       document has no custom cover picture — -1 matches no override, so the
     *       printed cover stays.</li>
     * </ul>
     * Registered before the model bake (client setup), which is what
     * {@code ItemProperties} requires.
     */
    @SubscribeEvent
    public static void registerDocumentProperties(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            List<Item> documents = List.of(ModItems.ID_CARD.get(), ModItems.PASSPORT.get(),
                    ModItems.HK_MACAO_PERMIT.get());
            for (Item document : documents) {
                ItemProperties.register(document, EMBLEM_PROPERTY,
                        (stack, level, entity, seed) -> DocumentAppearance.ofStack(stack).emblemPredicate());
                ItemProperties.register(document, SLOT_PROPERTY,
                        (stack, level, entity, seed) -> DocumentAppearance.ofStack(stack).slotPredicate());
            }
        });
    }

    /**
     * 26.4.1: the eight dynamic cover textures are registered by hand, so a
     * resource reload (F3+T) has to rebuild them from the last snapshot payload
     * instead of leaving eight released texture ids behind the item models.
     */
    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void value, ResourceManager resourceManager, ProfilerFiller profiler) {
                TemplateSlotTextures.refresh();
            }
        });
    }
}
