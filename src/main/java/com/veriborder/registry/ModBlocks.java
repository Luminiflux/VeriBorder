package com.veriborder.registry;

import com.veriborder.VeriBorder;
import com.veriborder.block.DeviceBlock;
import com.veriborder.block.DeviceType;
import com.veriborder.block.EGateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, VeriBorder.MOD_ID);
    public static final Map<DeviceType, RegistryObject<Block>> DEVICES = new EnumMap<>(DeviceType.class);

    static {
        for (DeviceType type : DeviceType.values()) {
            BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(type.isGate() ? 5.0F : 3.2F, 8.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .requiresCorrectToolForDrops();
            if (type.isGate()) {
                properties.lightLevel(state -> 7);
            }
            DEVICES.put(type, BLOCKS.register(type.id(), () -> type.isGate()
                    ? new EGateBlock(properties, type)
                    : new DeviceBlock(properties, type)));
        }
    }

    private ModBlocks() {
    }

    public static Block block(DeviceType type) {
        return DEVICES.get(type).get();
    }

    public static Block[] allBlocks() {
        return DEVICES.values().stream().map(RegistryObject::get).toArray(Block[]::new);
    }
}
