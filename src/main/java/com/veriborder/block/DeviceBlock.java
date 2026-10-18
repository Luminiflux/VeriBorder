package com.veriborder.block;

import com.veriborder.item.DeviceBrushItem;
import com.veriborder.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class DeviceBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    private final DeviceType deviceType;

    public DeviceBlock(Properties properties, DeviceType deviceType) {
        super(properties);
        this.deviceType = deviceType;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public DeviceType deviceType() {
        return deviceType;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            if (holdsBrush(player)) {
                DeviceService.openConfig(serverPlayer, pos);
            } else {
                DeviceService.onUse(serverPlayer, pos, hand);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static boolean holdsBrush(Player player) {
        return player.getMainHandItem().getItem() instanceof DeviceBrushItem
                || player.getOffhandItem().getItem() instanceof DeviceBrushItem;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DeviceBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (type != ModBlockEntities.DEVICE.get()) {
            return null;
        }
        return (tickLevel, tickPos, tickState, blockEntity) -> {
            if (blockEntity instanceof DeviceBlockEntity device) {
                DeviceBlockEntity.tick(tickLevel, tickPos, tickState, device);
            }
        };
    }
}
