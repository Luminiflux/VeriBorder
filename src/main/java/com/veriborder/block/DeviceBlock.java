package com.veriborder.block;

import com.veriborder.item.DeviceBrushItem;
import com.veriborder.item.GateConfigBrushItem;
import com.veriborder.registry.ModBlockEntities;
import com.veriborder.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class DeviceBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    private final DeviceType deviceType;

    /** Devices whose model is 2 blocks tall: the upper half gets a solid companion. */
    private static final java.util.Set<DeviceType> TALL = java.util.EnumSet.of(
            DeviceType.PASSPORT_ISSUER, DeviceType.SELF_VISA, DeviceType.SELF_ENDORSEMENT,
            DeviceType.AIRPORT_CHECKIN, DeviceType.STATION_VERIFIER, DeviceType.BLACKLIST_TERMINAL,
            DeviceType.RENEWAL_MACHINE, DeviceType.ENTRY_STAMP, DeviceType.EXIT_STAMP,
            DeviceType.STAMP_HISTORY, DeviceType.MANUAL_TERMINAL);

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
        // Gate Configuration Brush: a normal right-click applies the brush's
        // stored gate settings instead of opening or verifying anything. Sneak
        // is the brush's own edit gesture, so that falls through to Item#use.
        if (holdsGateBrush(player)) {
            if (player.isShiftKeyDown()) {
                return InteractionResult.PASS;
            }
            if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                GateConfigBrushItem.applyToGate(serverPlayer, pos);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
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

    private static boolean holdsGateBrush(Player player) {
        return player.getMainHandItem().getItem() instanceof GateConfigBrushItem
                || player.getOffhandItem().getItem() instanceof GateConfigBrushItem;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && TALL.contains(deviceType) && !oldState.is(state.getBlock())) {
            BlockPos above = pos.above();
            if (level.getBlockState(above).canBeReplaced()) {
                level.setBlockAndUpdate(above, ModBlocks.DEVICE_EXTENSION.get().defaultBlockState());
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && TALL.contains(deviceType)) {
            BlockPos above = pos.above();
            if (level.getBlockState(above).is(ModBlocks.DEVICE_EXTENSION.get())) {
                level.removeBlock(above, false);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return TALL.contains(deviceType)
                ? Shapes.or(Shapes.block(), Shapes.box(0.0D, 1.0D, 0.0D, 1.0D, 2.0D, 1.0D)).optimize()
                : Shapes.block();
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
