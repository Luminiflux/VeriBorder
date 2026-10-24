package com.veriborder.item;

import com.veriborder.border.BorderTerminalService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Admin border terminal: right-click opens the online-player / document console.
 * Read-only for everyone; mutations are re-checked server-side at permission
 * level 2 by {@link BorderTerminalService}.
 */
public class BorderTerminalItem extends Item {
    public BorderTerminalItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            BorderTerminalService.open(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
    }
}
