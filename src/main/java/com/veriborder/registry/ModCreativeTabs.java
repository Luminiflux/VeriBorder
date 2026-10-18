package com.veriborder.registry;

import com.veriborder.VeriBorder;
import com.veriborder.doc.DocStyle;
import com.veriborder.item.DocumentItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, VeriBorder.MOD_ID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.veriborder"))
                    .icon(() -> ((DocumentItem) ModItems.PASSPORT.get()).sampleStack(DocStyle.CN_PASSPORT))
                    .displayItems((parameters, output) -> {
                        addSample(output, DocStyle.CN_ID);
                        addSample(output, DocStyle.HK_ID);
                        addSample(output, DocStyle.CN_PASSPORT);
                        addSample(output, DocStyle.HK_PASSPORT);
                        addSample(output, DocStyle.MO_PASSPORT);
                        addSample(output, DocStyle.US_PASSPORT);
                        addSample(output, DocStyle.JP_PASSPORT);
                        addSample(output, DocStyle.HK_MACAO_PERMIT);
                        output.accept(ModItems.DOCUMENT_MODIFIER.get());
                        output.accept(ModItems.DEVICE_BRUSH.get());
                        for (var entry : ModItems.DEVICE_ITEMS.values()) {
                            output.accept(entry.get());
                        }
                    })
                    .build());

    private ModCreativeTabs() {
    }

    private static void addSample(CreativeModeTab.Output output, DocStyle style) {
        DocumentItem item = switch (style.kind()) {
            case ID_CARD -> (DocumentItem) ModItems.ID_CARD.get();
            case PASSPORT -> (DocumentItem) ModItems.PASSPORT.get();
            case PERMIT -> (DocumentItem) ModItems.HK_MACAO_PERMIT.get();
        };
        output.accept(item.sampleStack(style));
    }
}
