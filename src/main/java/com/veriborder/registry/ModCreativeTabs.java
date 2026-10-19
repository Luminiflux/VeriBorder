package com.veriborder.registry;

import com.veriborder.VeriBorder;
import com.veriborder.doc.DocStyle;
import com.veriborder.item.DocumentItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, VeriBorder.MOD_ID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.veriborder"))
                    .icon(() -> ((DocumentItem) ModItems.PASSPORT.get()).sampleStack(DocStyle.CN_PASSPORT))
                    .build());

    private ModCreativeTabs() {
    }

    /**
     * Creative-tab contents are filled from the Forge event, not from the
     * {@code displayItems} lambda. The event runs after all item registry
     * objects are present, which avoids registry-object timing crashes on
     * heavily modded/Connector clients.
     */
    public static void buildContents(BuildCreativeModeTabContentsEvent event) {
        if (!event.getTabKey().equals(MAIN.getKey())) {
            return;
        }
        addSample(event, DocStyle.CN_ID);
        addSample(event, DocStyle.HK_ID);
        addSample(event, DocStyle.CN_PASSPORT);
        addSample(event, DocStyle.HK_PASSPORT);
        addSample(event, DocStyle.MO_PASSPORT);
        addSample(event, DocStyle.US_PASSPORT);
        addSample(event, DocStyle.JP_PASSPORT);
        addSample(event, DocStyle.HK_MACAO_PERMIT);
        event.accept(ModItems.DOCUMENT_MODIFIER.get());
        event.accept(ModItems.DEVICE_BRUSH.get());
        for (var entry : ModItems.DEVICE_ITEMS.values()) {
            if (entry.isPresent()) {
                event.accept(entry.get());
            }
        }
    }

    private static void addSample(BuildCreativeModeTabContentsEvent event, DocStyle style) {
        DocumentItem item = switch (style.kind()) {
            case ID_CARD -> (DocumentItem) ModItems.ID_CARD.get();
            case PASSPORT -> (DocumentItem) ModItems.PASSPORT.get();
            case PERMIT -> (DocumentItem) ModItems.HK_MACAO_PERMIT.get();
        };
        event.accept(item.sampleStack(style));
    }
}
