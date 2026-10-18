package com.veriborder.registry;

import com.veriborder.VeriBorder;
import com.veriborder.block.DeviceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, VeriBorder.MOD_ID);

    public static final RegistryObject<BlockEntityType<DeviceBlockEntity>> DEVICE =
            BLOCK_ENTITIES.register("device", () -> BlockEntityType.Builder
                    .of(DeviceBlockEntity::new, ModBlocks.allBlocks())
                    .build(null));

    private ModBlockEntities() {
    }
}
