package com.veriborder.block;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class DeviceBlockItem extends BlockItem {
    public DeviceBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        if (getBlock() instanceof DeviceBlock deviceBlock) {
            DeviceType type = deviceBlock.deviceType();
            tooltip.add(Component.translatable(type.descriptionKey()).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("tooltip.veriborder.device.category." + type.category().name().toLowerCase())
                    .withStyle(ChatFormatting.DARK_AQUA));
        }
    }
}
