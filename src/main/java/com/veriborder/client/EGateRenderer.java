package com.veriborder.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.veriborder.VeriBorder;
import com.veriborder.block.DeviceBlock;
import com.veriborder.block.DeviceBlockEntity;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Animated wings, status lights and scan beam for every E-Gate variant
 * ({@code egate} and {@code exit_gate}).
 *
 * <p>The static gate shell is an original single-lane ticket-barrier design:
 * two 4 px cabinets straddling the controller edges, a 12 px passage and a
 * 16 px waist-high body. The animated 6 x 9 px glass wings are drawn by this
 * renderer and slide into the cabinets; collision stays with
 * {@code EGateBlock} on the single controller block.</p>
 *
 * <p>{@code DeviceBlockEntity.rawOpenProgress()} is advanced once per tick, so
 * the renderer keeps a per-gate pair of previous/current samples and lerps
 * them with the render partial tick, then applies a smoothstep ease so the
 * wings glide instead of stepping 20 times a second.</p>
 */
public class EGateRenderer implements BlockEntityRenderer<DeviceBlockEntity> {
    private static final ResourceLocation DOOR = texture("egate_door");
    private static final ResourceLocation GREEN = texture("egate_light_green");
    private static final ResourceLocation AMBER = texture("egate_light_amber");
    private static final ResourceLocation RED = texture("egate_light_red");
    private static final ResourceLocation SCAN = texture("egate_scan");

    private static final ResourceLocation EXIT_DOOR = texture("exit_gate_door");
    private static final ResourceLocation EXIT_GREEN = texture("exit_gate_light_green");
    private static final ResourceLocation EXIT_AMBER = texture("exit_gate_light_amber");
    private static final ResourceLocation EXIT_RED = texture("exit_gate_light_red");
    private static final ResourceLocation EXIT_SCAN = texture("exit_gate_scan");

    /** Model pixels the 6 px wings slide sideways into the cabinet pockets. */
    private static final float WING_TRAVEL = 6.0F;
    /** Model pixels the scan beam sweeps through. */
    private static final float SCAN_BOTTOM = 4.2F;
    private static final float SCAN_TOP = 12.8F;

    private final ModelPart leftDoor;
    private final ModelPart rightDoor;
    private final ModelPart green;
    private final ModelPart amber;
    private final ModelPart red;
    private final ModelPart scan;

    /** Per-gate tick samples for partial-tick interpolation (weak keys). */
    private final Map<DeviceBlockEntity, Motion> motion = new WeakHashMap<>();

    public EGateRenderer(BlockEntityRendererProvider.Context context) {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        // Two framed-glass leaves, 6 x 9 x 1 model pixels each. They meet on
        // the controller centre line and slide 6 px into the 4 px cabinets when
        // the gate opens. The 12 px passage matches the shell.
        root.addOrReplaceChild("left", CubeListBuilder.create().texOffs(0, 0)
                .addBox(-6.0F, 4.0F, -0.5F, 6.0F, 9.0F, 1.0F), PartPose.ZERO);
        root.addOrReplaceChild("right", CubeListBuilder.create().texOffs(8, 0)
                .addBox(0.0F, 4.0F, -0.5F, 6.0F, 9.0F, 1.0F), PartPose.ZERO);
        // Status lens on the front face of the right cabinet (protrudes 0.08 px
        // forward of the cap so it cannot z-fight with the static shell).
        root.addOrReplaceChild("green", CubeListBuilder.create().texOffs(0, 32)
                .addBox(6.0F, 15.05F, -7.08F, 4.0F, 0.55F, 0.12F), PartPose.ZERO);
        root.addOrReplaceChild("amber", CubeListBuilder.create().texOffs(0, 32)
                .addBox(6.0F, 15.05F, -7.08F, 4.0F, 0.55F, 0.12F), PartPose.ZERO);
        root.addOrReplaceChild("red", CubeListBuilder.create().texOffs(0, 32)
                .addBox(6.0F, 15.05F, -7.08F, 4.0F, 0.55F, 0.12F), PartPose.ZERO);
        // Traveling verification beam between the closed wings.
        root.addOrReplaceChild("scan", CubeListBuilder.create().texOffs(0, 38)
                .addBox(-6.0F, 0.0F, -0.6F, 12.0F, 1.0F, 1.2F), PartPose.ZERO);
        ModelPart baked = LayerDefinition.create(mesh, 64, 64).bakeRoot();
        this.leftDoor = baked.getChild("left");
        this.rightDoor = baked.getChild("right");
        this.green = baked.getChild("green");
        this.amber = baked.getChild("amber");
        this.red = baked.getChild("red");
        this.scan = baked.getChild("scan");
    }

    private static ResourceLocation texture(String name) {
        return new ResourceLocation(VeriBorder.MOD_ID, "textures/entity/" + name + ".png");
    }

    @Override
    public void render(DeviceBlockEntity entity, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        if (!entity.deviceType().isGate()) {
            return;
        }
        boolean exit = "exit_gate".equals(entity.deviceType().id());
        BlockState state = entity.getBlockState();
        Direction facing = state.hasProperty(DeviceBlock.FACING) ? state.getValue(DeviceBlock.FACING) : Direction.NORTH;
        float rotation = switch (facing) {
            case EAST -> 90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 270.0F;
            default -> 0.0F;
        };

        // Smooth, eased wing travel: interpolate the tick-based progress across
        // the render frame, then smoothstep it so the leaves accelerate away
        // from the centre and settle softly at the ends.
        float open = interpolateOpen(entity, partialTick);
        float eased = open * open * (3.0F - 2.0F * open);
        leftDoor.x = -eased * WING_TRAVEL;
        rightDoor.x = eased * WING_TRAVEL;

        pose.pushPose();
        pose.translate(0.5D, 0.0D, 0.5D);
        pose.mulPose(Axis.YP.rotationDegrees(rotation));
        pose.scale(0.0625F, 0.0625F, 0.0625F);

        VertexConsumer wingBuffer = buffers.getBuffer(RenderType.entityTranslucent(exit ? EXIT_DOOR : DOOR));
        leftDoor.render(pose, wingBuffer, packedLight, packedOverlay);
        rightDoor.render(pose, wingBuffer, packedLight, packedOverlay);

        long time = entity.getLevel() == null ? 0L : entity.getLevel().getGameTime();
        boolean blink = (time / 6L) % 2L == 0L;
        DeviceBlockEntity.GateState gate = entity.gateState();
        green.visible = gate == DeviceBlockEntity.GateState.GRANTED;
        amber.visible = gate == DeviceBlockEntity.GateState.VERIFYING && blink;
        red.visible = gate == DeviceBlockEntity.GateState.DENIED && blink;
        if (green.visible) {
            green.render(pose, buffers.getBuffer(RenderType.entityTranslucent(exit ? EXIT_GREEN : GREEN)),
                    packedLight, packedOverlay);
        }
        if (amber.visible) {
            amber.render(pose, buffers.getBuffer(RenderType.entityTranslucent(exit ? EXIT_AMBER : AMBER)),
                    packedLight, packedOverlay);
        }
        if (red.visible) {
            red.render(pose, buffers.getBuffer(RenderType.entityTranslucent(exit ? EXIT_RED : RED)),
                    packedLight, packedOverlay);
        }
        if (gate == DeviceBlockEntity.GateState.VERIFYING) {
            // triangle-wave sweep: the beam travels up then back down
            float t = (time % 40L) / 40.0F;
            float sweep = t < 0.5F ? t * 2.0F : (1.0F - t) * 2.0F;
            scan.y = SCAN_BOTTOM + sweep * (SCAN_TOP - SCAN_BOTTOM);
            scan.visible = true;
            scan.render(pose, buffers.getBuffer(RenderType.entityTranslucent(exit ? EXIT_SCAN : SCAN)),
                    packedLight, packedOverlay);
        } else {
            scan.visible = false;
        }
        pose.popPose();
    }

    /**
     * Partial-tick interpolation of {@code rawOpenProgress()}. The block entity
     * only advances the value on its tick, so keep the previous sample and lerp
     * with {@code partialTick}; the bookkeeping is per block entity because a
     * single renderer instance serves every gate in the level.
     */
    private float interpolateOpen(DeviceBlockEntity entity, float partialTick) {
        Motion m = motion.computeIfAbsent(entity, e -> new Motion(e.rawOpenProgress()));
        long tick = entity.getLevel() == null ? 0L : entity.getLevel().getGameTime();
        if (m.tick != tick) {
            m.previous = m.current;
            m.current = entity.rawOpenProgress();
            m.tick = tick;
        }
        return Mth.clamp(Mth.lerp(partialTick, m.previous, m.current), 0.0F, 1.0F);
    }

    private static final class Motion {
        private float previous;
        private float current;
        private long tick = Long.MIN_VALUE;

        private Motion(float value) {
            this.previous = value;
            this.current = value;
        }
    }

    @Override
    public boolean shouldRenderOffScreen(DeviceBlockEntity blockEntity) {
        return false;
    }

    @Override
    public int getViewDistance() {
        return 80;
    }
}
