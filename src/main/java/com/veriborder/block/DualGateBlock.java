package com.veriborder.block;

import com.veriborder.item.DualGateBrushItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * One-stop dual-clearance gate (一地两检).
 *
 * <p>The shell, collision and door animation are exactly those of
 * {@link EGateBlock} - this block only adds the dedicated brush gesture and the
 * {@link DeviceType#DUAL_GATE} identity. Its use logic lives in
 * {@link DeviceService#onUse} (exit stage followed by entry stage).</p>
 *
 * <p>Right-clicking with {@link DualGateBrushItem} applies the brush's stored
 * two-stage configuration to this gate (OP level 2, server side). Sneak +
 * right-click is the brush's own edit gesture and is left to the item.</p>
 */
public class DualGateBlock extends EGateBlock {
    public DualGateBlock(Properties properties, DeviceType deviceType) {
        super(properties, deviceType);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (DualGateBrushItem.holdsBrush(player)) {
            if (player.isShiftKeyDown()) {
                return InteractionResult.PASS;
            }
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                DualGateBrushItem.applyToGate(serverPlayer, pos);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return super.use(state, level, pos, player, hand, hit);
    }
}
