package com.veriborder.client;

import com.mojang.blaze3d.vertex.PoseStack;
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
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sliding-door barrier, status lights and verification scan beam for every
 * E-Gate variant ({@code egate}, {@code exit_gate} and {@code dual_gate}).
 *
 * <p>The gate shell (dual white pillars, black curved top, reader screen, accent
 * light strips, green arrow) lives in the static block model. This renderer
 * draws the two door leaves so they can slide horizontally: when the gate is
 * closed the leaves meet in the middle of the lane and block it; when it opens
 * they glide into the cabinets using the block-entity's {@code openProgress}
 * interpolation (eased here).</p>
 *
 * <p>The dual-clearance gate reuses this exact geometry; only the door frame and
 * scan beam use its dark-green ({@code #1F5E3A}) texture set, so the existing
 * {@code egate}/{@code exit_gate} visuals are untouched.</p>
 *
 * <p>26.2 dimensions - the model is 16 px wide with the lane at x 3..13 px and
 * the cabinets at x 0..3 / 13..16 px, so in renderer space (block centre at 0,
 * 16 px per block) the lane is x -5..5:</p>
 * <ul>
 *   <li>leaf: 5 px wide &#215; 12 px tall (y 2..14) &#215; 2 px thick (z -1..1)</li>
 *   <li>closed: left leaf -5..0, right leaf 0..5, they meet on x = 0 (block x 8)</li>
 *   <li>open: both leaves slide 5.1 px outwards and squeeze from 5 px to 2.8 px
 *       wide, ending flush inside the 3 px cabinets (-7.9..-5.1 / 5.1..7.9).
 *       A rigid 5 px slide would poke 2 px through the cabinet into the
 *       neighbouring block, which breaks seamless tiling.</li>
 * </ul>
 *
 * <p>The opaque dark-metal frame uses {@code entitySolid} and the frosted glass
 * uses {@code entityTranslucent}, so the leaf is always clearly readable.</p>
 */
public class EGateRenderer implements BlockEntityRenderer<DeviceBlockEntity> {
    private static final ResourceLocation GREEN = texture("egate_light_green");
    private static final ResourceLocation AMBER = texture("egate_light_amber");
    private static final ResourceLocation RED = texture("egate_light_red");
    private static final ResourceLocation SCAN = texture("egate_scan");
    private static final ResourceLocation FRAME = texture("egate_door_frame");
    private static final ResourceLocation GLASS = texture("egate_door_glass");

    private static final ResourceLocation EXIT_GREEN = texture("exit_gate_light_green");
    private static final ResourceLocation EXIT_AMBER = texture("exit_gate_light_amber");
    private static final ResourceLocation EXIT_RED = texture("exit_gate_light_red");
    private static final ResourceLocation EXIT_SCAN = texture("exit_gate_scan");
    private static final ResourceLocation EXIT_FRAME = texture("exit_gate_door_frame");
    private static final ResourceLocation EXIT_GLASS = texture("exit_gate_door_glass");

    /** Dual-clearance gate: dark-green (#1F5E3A) door frame and scan beam. */
    private static final ResourceLocation DUAL_FRAME = texture("dual_gate_door_frame");
    private static final ResourceLocation DUAL_GLASS = texture("dual_gate_door_glass");
    private static final ResourceLocation DUAL_SCAN = texture("dual_gate_scan");

    /** Model pixels the scan beam sweeps through (inside the 12 px door). */
    private static final float SCAN_BOTTOM = 3.6F;
    private static final float SCAN_TOP = 12.4F;

    /** Closed leaf width (5 px) and travel that tucks it into the cabinet. */
    private static final float DOOR_WIDTH = 6.0F;
    private static final float DOOR_SLIDE = 5.1F;
    /** 5 px -> 2.8 px at full open so the leaf ends inside the 3 px cabinet. */
    private static final float DOOR_SQUEEZE = 0.44F;

    private final ModelPart green;
    private final ModelPart amber;
    private final ModelPart red;
    private final ModelPart scan;
    private final ModelPart leftFrame;
    private final ModelPart leftGlass;
    private final ModelPart rightFrame;
    private final ModelPart rightGlass;

    public EGateRenderer(BlockEntityRendererProvider.Context context) {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        // Gate state lens on the front face of BOTH cabinets, on the 3 px
        // pillar (x -8..-5 / 5..8) so it can never poke into the neighbour.
        // Protrudes 0.12 px in front of the cabinet skin so it cannot z-fight.
        root.addOrReplaceChild("green", CubeListBuilder.create().texOffs(0, 32)
                .addBox(-7.7F, 14.92F, -7.12F, 2.4F, 0.46F, 0.1F)
                .addBox(5.3F, 14.92F, -7.12F, 2.4F, 0.46F, 0.1F), PartPose.ZERO);
        root.addOrReplaceChild("amber", CubeListBuilder.create().texOffs(0, 32)
                .addBox(-7.7F, 14.92F, -7.12F, 2.4F, 0.46F, 0.1F)
                .addBox(5.3F, 14.92F, -7.12F, 2.4F, 0.46F, 0.1F), PartPose.ZERO);
        root.addOrReplaceChild("red", CubeListBuilder.create().texOffs(0, 32)
                .addBox(-7.7F, 14.92F, -7.12F, 2.4F, 0.46F, 0.1F)
                .addBox(5.3F, 14.92F, -7.12F, 2.4F, 0.46F, 0.1F), PartPose.ZERO);
        // Traveling verification beam inside the 10 px lane, just in front of the
        // closed leaves so the opaque beam is never hidden behind the glass.
        root.addOrReplaceChild("scan", CubeListBuilder.create().texOffs(0, 38)
                .addBox(-5.0F, 0.0F, -1.35F, 10.0F, 1.0F, 0.3F), PartPose.ZERO);

        // Left leaf: x -5..0 (block x 3..8), y 2..14 (12 px), z -1..1 (2 px).
        // Dark metal frame: 1.4 px rails/stiles plus a mid rail, frosted glass
        // panes inset 0.1 px on both faces.
        root.addOrReplaceChild("left_frame", CubeListBuilder.create().texOffs(0, 0)
                .addBox(-5.9F, 13.6F, -1.0F, 5.4F, 1.4F, 2.0F)
                .addBox(-5.9F, 0.0F, -1.0F, 5.4F, 1.2F, 2.0F)
                .addBox(-5.9F, 0.0F, -1.0F, 0.5F, 15.0F, 2.0F)
                .addBox(-1.0F, 0.0F, -1.0F, 0.5F, 15.0F, 2.0F), PartPose.ZERO);
        root.addOrReplaceChild("left_glass", CubeListBuilder.create().texOffs(0, 0)
                .addBox(-5.35F, 1.25F, -0.85F, 4.3F, 12.3F, 1.7F), PartPose.ZERO);
        root.addOrReplaceChild("right_frame", CubeListBuilder.create().texOffs(0, 0)
                .addBox(0.5F, 13.6F, -1.0F, 5.4F, 1.4F, 2.0F)
                .addBox(0.5F, 0.0F, -1.0F, 5.4F, 1.2F, 2.0F)
                .addBox(0.5F, 0.0F, -1.0F, 0.5F, 15.0F, 2.0F)
                .addBox(5.4F, 0.0F, -1.0F, 0.5F, 15.0F, 2.0F), PartPose.ZERO);
        root.addOrReplaceChild("right_glass", CubeListBuilder.create().texOffs(0, 0)
                .addBox(1.05F, 1.25F, -0.85F, 4.3F, 12.3F, 1.7F), PartPose.ZERO);

        ModelPart baked = LayerDefinition.create(mesh, 64, 64).bakeRoot();
        this.green = baked.getChild("green");
        this.amber = baked.getChild("amber");
        this.red = baked.getChild("red");
        this.scan = baked.getChild("scan");
        this.leftFrame = baked.getChild("left_frame");
        this.leftGlass = baked.getChild("left_glass");
        this.rightFrame = baked.getChild("right_frame");
        this.rightGlass = baked.getChild("right_glass");
    }

    private static ResourceLocation texture(String name) {
        return new ResourceLocation(VeriBorder.MOD_ID, "textures/entity/" + name + ".png");
    }

    /** Smoothstep ease-in-out so the leaves accelerate and settle gently. */
    private static float ease(float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        return t * t * (3.0F - 2.0F * t);
    }

    @Override
    public void render(DeviceBlockEntity entity, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        if (!entity.deviceType().isGate()) {
            return;
        }
        boolean exit = "exit_gate".equals(entity.deviceType().id());
        boolean dual = "dual_gate".equals(entity.deviceType().id());
        BlockState state = entity.getBlockState();
        Direction facing = state.hasProperty(DeviceBlock.FACING) ? state.getValue(DeviceBlock.FACING) : Direction.NORTH;
        float rotation = switch (facing) {
            case EAST -> 90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 270.0F;
            default -> 0.0F;
        };

        pose.pushPose();
        pose.translate(0.5D, 0.0D, 0.5D);
        pose.mulPose(Axis.YP.rotationDegrees(rotation));
        pose.scale(0.0625F, 0.0625F, 0.0625F);

        long time = entity.getLevel() == null ? 0L : entity.getLevel().getGameTime();
        boolean blink = (time / 6L) % 2L == 0L;
        DeviceBlockEntity.GateState gate = entity.gateState();

        // --- sliding door leaves -----------------------------------------
        // The door cubes are authored at 16x the block-model unit, so they get
        // their own scale; slide/squeeze are applied inside that scale.
        float open = ease(entity.openProgress(partialTick));
        float slide = open * DOOR_SLIDE;
        float squeeze = 1.0F - DOOR_SQUEEZE * open;
        RenderType solid = RenderType.entitySolid(dual ? DUAL_FRAME : (exit ? EXIT_FRAME : FRAME));
        RenderType glassType = RenderType.entityTranslucent(dual ? DUAL_GLASS : (exit ? EXIT_GLASS : GLASS));
        pose.pushPose();
        pose.scale(16.0F, 16.0F, 16.0F);
        pose.pushPose();
        pose.translate(-slide / 16.0F, 0.0F, 0.0F);
        pose.scale(squeeze, 1.0F, 1.0F);
        leftFrame.render(pose, buffers.getBuffer(solid), packedLight, packedOverlay);
        leftGlass.render(pose, buffers.getBuffer(glassType), packedLight, packedOverlay);
        pose.popPose();
        pose.pushPose();
        pose.translate(slide / 16.0F, 0.0F, 0.0F);
        pose.scale(squeeze, 1.0F, 1.0F);
        rightFrame.render(pose, buffers.getBuffer(solid), packedLight, packedOverlay);
        rightGlass.render(pose, buffers.getBuffer(glassType), packedLight, packedOverlay);
        pose.popPose();
        pose.popPose();

        // --- status lights + scan beam ----------------------------------
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
            scan.render(pose, buffers.getBuffer(RenderType.entityTranslucent(
                            dual ? DUAL_SCAN : (exit ? EXIT_SCAN : SCAN))),
                    packedLight, packedOverlay);
        } else {
            scan.visible = false;
        }
        pose.popPose();
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
