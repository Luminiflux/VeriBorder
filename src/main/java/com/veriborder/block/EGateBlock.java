package com.veriborder.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The E-Gate becomes physically passable only while the barrier is open.
 * The door meshes themselves are animated by EGateRenderer; this property
 * controls the collision shape and keeps the doorway blocked while closed.
 */
public class EGateBlock extends DeviceBlock {
    public static final BooleanProperty OPEN = BooleanProperty.create("open");

    /**
     * The visual barrier is one block high, but the collision wall must be tall
     * enough that a player cannot jump onto the gate top and cross the border.
     * The open passage stays clear at full height; only the side cabinets use
     * this additional height.
     */
    private static final double GATE_HEIGHT = 2.0D;

    /** Open collision for a north/south-facing gate: cabinets on the X edges. */
    private static final VoxelShape OPEN_NS = Shapes.or(
            Shapes.box(0.0D, 0.0D, 0.0D, 0.125D, GATE_HEIGHT, 1.0D),
            Shapes.box(0.875D, 0.0D, 0.0D, 1.0D, GATE_HEIGHT, 1.0D)
    );

    /** Open collision for an east/west-facing gate: cabinets on the Z edges. */
    private static final VoxelShape OPEN_EW = Shapes.or(
            Shapes.box(0.0D, 0.0D, 0.0D, 1.0D, GATE_HEIGHT, 0.125D),
            Shapes.box(0.0D, 0.0D, 0.875D, 1.0D, GATE_HEIGHT, 1.0D)
    );

    /** Closed gate: a full two-block-high wall, not a jumpable one-block step. */
    private static final VoxelShape CLOSED = Shapes.box(0.0D, 0.0D, 0.0D, 1.0D, GATE_HEIGHT, 1.0D);

    public EGateBlock(Properties properties, DeviceType deviceType) {
        super(properties, deviceType);
        registerDefaultState(defaultBlockState().setValue(OPEN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(OPEN);
    }

    private static VoxelShape openShape(BlockState state) {
        Direction facing = state.getValue(FACING);
        return facing.getAxis() == Direction.Axis.X ? OPEN_EW : OPEN_NS;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(OPEN) ? openShape(state) : CLOSED;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(OPEN) ? openShape(state) : CLOSED;
    }
}
