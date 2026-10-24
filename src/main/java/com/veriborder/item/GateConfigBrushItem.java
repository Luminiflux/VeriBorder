package com.veriborder.item;

import com.veriborder.block.DeviceBlockEntity;
import com.veriborder.block.DeviceType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Creative-only gate configuration brush.
 *
 * <ul>
 *   <li><b>Sneak + right-click</b> opens {@code GateConfigBrushScreen} on the
 *       client, seeded from this stack's {@code VeriBorderGateConfig} tag.</li>
 *   <li><b>Right-click a gate</b> (not sneaking) applies the stored settings to
 *       that gate's block entity. Server-authoritative, OP level 2.</li>
 *   <li>Right-clicking a non-gate device does nothing except a chat message; the
 *       normal {@link DeviceBrushItem} keeps its full per-block configuration.</li>
 * </ul>
 *
 * <p>The brush stores the full gate configuration — mode, codes, auto-stamp,
 * overstay limit and the checkpoint identity (port, country, officer) — so a
 * gate can be provisioned in one right-click.</p>
 */
public class GateConfigBrushItem extends Item {
    public GateConfigBrushItem(Properties properties) {
        super(properties.stacksTo(1).rarity(Rarity.EPIC));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // Only the sneak gesture edits the brush; a normal right-click on a gate
        // is handled by DeviceBlock#use and applied server-side.
        ItemStack held = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(held);
        }
        if (level.isClientSide) {
            // Post a common event; the client-only subscriber opens the screen.
            // No client type appears in this class, so it loads on a server.
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                    new com.veriborder.api.OpenScreenEvent(
                            com.veriborder.api.OpenScreenEvent.Kind.GATE_BRUSH, held));
        }
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
    }

    /**
     * Applies the brush's stored gate configuration to the gate at {@code pos}.
     * Runs on the server only: OP level 2, reach check, gate check and the
     * physical brush check are all re-verified here so no client packet can
     * skip them.
     *
     * @return true when a gate configuration was applied
     */
    public static boolean applyToGate(ServerPlayer player, BlockPos pos) {
        BlockEntity blockEntity = player.level().getBlockEntity(pos);
        if (!(blockEntity instanceof DeviceBlockEntity device)) {
            message(player, "message.veriborder.gate_brush.not_gate");
            return false;
        }
        if (!device.deviceType().isGate()) {
            message(player, "message.veriborder.gate_brush.not_gate");
            return false;
        }
        if (!player.hasPermissions(2)) {
            message(player, "message.veriborder.permission.denied");
            return false;
        }
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 100.0D) {
            message(player, "message.veriborder.too_far");
            return false;
        }
        // The brush may be in either hand; the interaction hand can hold a
        // document while the off hand holds the brush.
        ItemStack stack = heldBrush(player);
        if (stack == null) {
            message(player, "message.veriborder.gate_brush.missing");
            return false;
        }
        CompoundTag config = GateBrushConfig.read(stack);
        GateBrushConfig.applyTo(device, config);
        device.changed();
        device.sync();

        DeviceType type = device.deviceType();
        player.level().playSound(null, pos, SoundEvents.VILLAGER_WORK_CARTOGRAPHER,
                SoundSource.BLOCKS, 0.7F, 1.2F);
        message(player, "message.veriborder.gate_brush.applied", Component.translatable(type.translationKey()));
        return true;
    }

    /** Held-Brush search used by the save packet (main hand first, then off hand). */
    @Nullable
    public static ItemStack heldBrush(ServerPlayer player) {
        ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (main.getItem() instanceof GateConfigBrushItem) {
            return main;
        }
        ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
        return off.getItem() instanceof GateConfigBrushItem ? off : null;
    }

    /** Small chat helper shared by the apply path and the save packet. */
    public static void message(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), false);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        CompoundTag config = GateBrushConfig.read(stack);
        Component mode = Component.translatable(GateBrushConfig.modeKey(config.getString("gateMode")));
        tooltip.add(Component.translatable("tooltip.veriborder.gate_brush.1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.veriborder.gate_brush.2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.veriborder.gate_brush.3", mode,
                config.getString("port"), GateBrushConfig.codes(config).size()).withStyle(ChatFormatting.DARK_AQUA));
    }
}
