package com.veriborder.registry;

import com.veriborder.VeriBorder;
import com.veriborder.block.DeviceBlockItem;
import com.veriborder.block.DeviceType;
import com.veriborder.doc.DocumentKind;
import com.veriborder.item.ConfigCopyBrushItem;
import com.veriborder.item.DeviceBrushItem;
import com.veriborder.item.DocumentItem;
import com.veriborder.item.DualGateBrushItem;
import com.veriborder.item.GateConfigBrushItem;
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
    /** Creative-only gate configuration brush (no recipe; OP 2 for save/apply). */
    public static final RegistryObject<Item> GATE_CONFIG_BRUSH = ITEMS.register("gate_config_brush",
            () -> new GateConfigBrushItem(new Item.Properties()));
    /** Creative-only dual-clearance gate brush (no recipe; OP 2 for save/apply). */
    public static final RegistryObject<Item> DUAL_GATE_BRUSH = ITEMS.register("dual_gate_brush",
            () -> new DualGateBrushItem(new Item.Properties()));
    /**
     * Creative-only configuration copy brush (no recipe, no screen): sneak +
     * right-click an issuing machine to copy its full configuration, right-click
     * a machine of the same model to paste it (OP 2).
     */
    public static final RegistryObject<Item> CONFIG_COPY_BRUSH = ITEMS.register("config_copy_brush",
            () -> new ConfigCopyBrushItem(new Item.Properties()));
    /** Admin border terminal: read all players/documents, OP 2 to edit. */
    public static final RegistryObject<Item> BORDER_TERMINAL = ITEMS.register("border_terminal",
            () -> new com.veriborder.item.BorderTerminalItem(new Item.Properties().stacksTo(1)));
    /**
     * 26.3 credential registry ("注册器"): right-click opens the global country /
     * region and template registry. Readable by everyone, OP 2 to edit.
     */
    public static final RegistryObject<Item> CREDENTIAL_REGISTRY = ITEMS.register("credential_registry",
            () -> new com.veriborder.item.RegistryItem(new Item.Properties()));

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
