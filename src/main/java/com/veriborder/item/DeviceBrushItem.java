package com.veriborder.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Configuration brush for VeriBorder machines. Right-clicking a device block
 * with this item opens its configuration screen; without it the device keeps
 * its normal document/operational behaviour.
 */
public class DeviceBrushItem extends Item {
    public DeviceBrushItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.veriborder.brush.1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.veriborder.brush.2").withStyle(ChatFormatting.DARK_AQUA));
    }
}
