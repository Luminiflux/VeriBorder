package com.veriborder.item;

import com.veriborder.registry.RegistryService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Credential registry ("注册器"): right-click opens the global registry editor.
 *
 * <p>Everyone may open and read it (issuer selectors read the same registry), but
 * adding/deleting countries and templates requires OP level 2, re-checked on the
 * server by {@link RegistryService}.</p>
 */
public class RegistryItem extends Item {
    public RegistryItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            RegistryService.open(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.veriborder.registry.1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.veriborder.registry.2").withStyle(ChatFormatting.AQUA));
    }
}
