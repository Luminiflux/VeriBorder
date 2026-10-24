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
 *
 * <p>26.2 rebuild: the shell is exactly one block wide/deep (16&#215;16 px) and
 * 24 px (1.5 blocks) tall, so two gates placed side by side share a pillar and
 * never overlap. Collision is derived from that shell - nothing pokes outside
 * the {@code 0..1} footprint and there is no second invisible wall above the
 * 1.5-block body.</p>
 *
 * <p>All numbers below are block-space fractions of the 16 px model; the
 * comment next to each one is the pixel value used by the model / renderer.</p>
 */
public class EGateBlock extends DeviceBlock {
    public static final BooleanProperty OPEN = BooleanProperty.create("open");

    /**
     * 24 model px = 1.5 blocks. The collision is exactly as tall as the visible
     * shell, so there is no invisible "second wall" above the gate. 1.5 is also
     * above the vanilla jump apex (1.2522), so the gate body cannot be jumped
     * over.
     */
    public static final double GATE_HEIGHT = 1.3125D;

    // ---- shell, default orientation (FACING north/south, lane runs along Z) ----
    /** Cabinet width: x 0..3 px and x 13..16 px. */
    private static final double PILLAR = 0.1875D;
    /** Top cap (the 1 px lid of the shell): y 23..24 px. */
    private static final double CAP_BOTTOM = 1.25D;
    /** Lane / doorway: x 3..13 px (10 px wide). */
    private static final double LANE_MIN = 0.1875D;
    private static final double LANE_MAX = 0.8125D;
    /** Door leaf: 12 px tall (y 2..14 px), 2 px thick (z 7..9 px). */
    private static final double DOOR_BOTTOM = 0.125D;
    private static final double DOOR_TOP = 1.125D;
    private static final double DOOR_Z_MIN = 0.4375D;
    private static final double DOOR_Z_MAX = 0.5625D;
    /**
     * While the gate is closed the doorway is solid from the bottom of the leaf
     * up to just under the cap. The visible leaf is 12 px, the extra 9 px above
     * it is the anti-vault volume: without it a player can hop onto the 0.875
     * high leaf and drop over the other side. It only exists while closed, so
     * the open gate has zero invisible collision.
     */
    private static final double DOOR_SOLID_TOP = GATE_HEIGHT;

    /** Two cabinets (x 0..3 / 13..16, full depth, full height). */
    private static final VoxelShape CABINETS_NS = Shapes.or(
            Shapes.box(0.0D, 0.0D, 0.0D, PILLAR, GATE_HEIGHT, 1.0D),
            Shapes.box(1.0D - PILLAR, 0.0D, 0.0D, 1.0D, GATE_HEIGHT, 1.0D)
    ).optimize();

    /** Same shell rotated 90 degrees: cabinets on the Z edges, lane along X. */
    private static final VoxelShape CABINETS_EW = Shapes.or(
            Shapes.box(0.0D, 0.0D, 0.0D, 1.0D, GATE_HEIGHT, PILLAR),
            Shapes.box(0.0D, 0.0D, 1.0D - PILLAR, 1.0D, GATE_HEIGHT, 1.0D)
    ).optimize();

    /** Full-width top cap (x 0..16 px at y 23..24 px) - selection outline only. */
    private static final VoxelShape CAP = Shapes.box(0.0D, CAP_BOTTOM, 0.0D, 1.0D, GATE_HEIGHT, 1.0D);

    /** Visible 12 px door leaf, both leaves closed. */
    private static final VoxelShape DOOR_NS = Shapes.box(
            LANE_MIN, DOOR_BOTTOM, DOOR_Z_MIN, LANE_MAX, DOOR_TOP, DOOR_Z_MAX);
    private static final VoxelShape DOOR_EW = Shapes.box(
            DOOR_Z_MIN, DOOR_BOTTOM, LANE_MIN, DOOR_Z_MAX, DOOR_TOP, LANE_MAX);

    /** Closed-gate collision plate: leaf footprint, but tall enough to vault-proof. */
    private static final VoxelShape DOOR_SOLID_NS = Shapes.box(
            LANE_MIN, DOOR_BOTTOM, DOOR_Z_MIN, LANE_MAX, DOOR_SOLID_TOP, DOOR_Z_MAX);
    private static final VoxelShape DOOR_SOLID_EW = Shapes.box(
            DOOR_Z_MIN, DOOR_BOTTOM, LANE_MIN, DOOR_Z_MAX, DOOR_SOLID_TOP, LANE_MAX);

    private static final VoxelShape COLLISION_OPEN_NS = CABINETS_NS;
    private static final VoxelShape COLLISION_OPEN_EW = CABINETS_EW;
    private static final VoxelShape COLLISION_CLOSED_NS = Shapes.or(CABINETS_NS, DOOR_SOLID_NS).optimize();
    private static final VoxelShape COLLISION_CLOSED_EW = Shapes.or(CABINETS_EW, DOOR_SOLID_EW).optimize();

    /**
     * Selection outline = the whole visible shell (cabinets + full-width cap, plus
     * the leaf while closed). The cap is intentionally <em>not</em> part of the
     * collision: a 1.5-block gate leaves a 1.4375 opening under it and the player
     * collision box is 1.8 tall, so a solid lane ceiling would make the gate
     * impossible to walk through even when open.
     */
    private static final VoxelShape OUTLINE_OPEN_NS = Shapes.or(CABINETS_NS, CAP).optimize();
    private static final VoxelShape OUTLINE_OPEN_EW = OUTLINE_OPEN_NS;
    private static final VoxelShape OUTLINE_CLOSED_NS = Shapes.or(CABINETS_NS, CAP, DOOR_NS).optimize();
    private static final VoxelShape OUTLINE_CLOSED_EW = Shapes.or(CABINETS_EW, CAP, DOOR_EW).optimize();

    public EGateBlock(Properties properties, DeviceType deviceType) {
        super(properties, deviceType);
        registerDefaultState(defaultBlockState().setValue(OPEN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(OPEN);
    }

    /** The lane runs along the axis the gate faces, so the cabinets sit on the other axis. */
    private static boolean laneAlongZ(BlockState state) {
        Direction facing = state.getValue(FACING);
        return facing.getAxis() == Direction.Axis.Z;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        boolean open = state.getValue(OPEN);
        boolean alongZ = laneAlongZ(state);
        if (open) {
            return alongZ ? COLLISION_OPEN_NS : COLLISION_OPEN_EW;
        }
        return alongZ ? COLLISION_CLOSED_NS : COLLISION_CLOSED_EW;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        boolean open = state.getValue(OPEN);
        boolean alongZ = laneAlongZ(state);
        if (open) {
            return alongZ ? OUTLINE_OPEN_NS : OUTLINE_OPEN_EW;
        }
        return alongZ ? OUTLINE_CLOSED_NS : OUTLINE_CLOSED_EW;
    }
}
