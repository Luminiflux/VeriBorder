package com.veriborder.registry;

import com.veriborder.VeriBorder;
import com.veriborder.block.DeviceBlockItem;
import com.veriborder.block.DeviceType;
import com.veriborder.doc.DocumentKind;
import com.veriborder.item.DeviceBrushItem;
import com.veriborder.item.DocumentItem;
import com.veriborder.item.DocumentModifierItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, VeriBorder.MOD_ID);

    public static final RegistryObject<Item> ID_CARD = ITEMS.register("id_card",
            () -> new DocumentItem(DocumentKind.ID_CARD, new Item.Properties()));
    public static final RegistryObject<Item> PASSPORT = ITEMS.register("passport",
            () -> new DocumentItem(DocumentKind.PASSPORT, new Item.Properties()));
    public static final RegistryObject<Item> HK_MACAO_PERMIT = ITEMS.register("hk_macao_permit",
            () -> new DocumentItem(DocumentKind.PERMIT, new Item.Properties()));
    public static final RegistryObject<Item> DOCUMENT_MODIFIER = ITEMS.register("document_modifier",
            () -> new DocumentModifierItem(new Item.Properties()));
    public static final RegistryObject<Item> DEVICE_BRUSH = ITEMS.register("device_brush",
            () -> new DeviceBrushItem(new Item.Properties().stacksTo(1)));

    public static final Map<DeviceType, RegistryObject<Item>> DEVICE_ITEMS = new EnumMap<>(DeviceType.class);

    static {
        for (DeviceType type : DeviceType.values()) {
            DEVICE_ITEMS.put(type, ITEMS.register(type.id(),
                    () -> new DeviceBlockItem(ModBlocks.block(type), new Item.Properties())));
        }
    }

    private ModItems() {
    }

    public static Item deviceItem(DeviceType type) {
        return DEVICE_ITEMS.get(type).get();
    }
}
