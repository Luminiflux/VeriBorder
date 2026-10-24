package com.veriborder.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Invisible solid companion placed above 2-block-tall machines so the upper half
 * of the model has real collision instead of letting players stand inside it.
 * It is not obtainable, drops nothing, and dies with the machine below.
 */
public class DeviceExtensionBlock extends Block {
    public DeviceExtensionBlock(Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        // The invisible top half must behave like the machine below it, otherwise
        // right-clicking the upper block of a 2-block machine does nothing.
        BlockPos below = pos.below();
        BlockState base = level.getBlockState(below);
        if (base.getBlock() instanceof DeviceBlock device) {
            BlockHitResult shifted = new BlockHitResult(
                    hit.getLocation().add(0.0D, -1.0D, 0.0D), hit.getDirection(), below, hit.isInside());
            return device.use(base, level, below, player, hand, shifted);
        }
        return InteractionResult.PASS;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock())) {
            if (!(level.getBlockState(pos.below()).getBlock() instanceof DeviceBlock)) {
                level.removeBlock(pos, false);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            BlockPos below = pos.below();
            if (level.getBlockState(below).getBlock() instanceof DeviceBlock) {
                level.destroyBlock(below, !player.isCreative());
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }
}
