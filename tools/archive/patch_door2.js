const fs = require('fs');
const p = 'src/main/java/com/veriborder/client/EGateRenderer.java';
let t = fs.readFileSync(p, 'utf8');

t = t.replace('private static final float DOOR_SLIDE = 5.1F;', 'private static final float DOOR_SLIDE = 6.0F;');
t = t.replace('private static final float DOOR_SQUEEZE = 0.44F;', 'private static final float DOOR_SQUEEZE = 0.0F;');

const start = t.indexOf('        root.addOrReplaceChild("left_frame"');
const end = t.indexOf('        ModelPart baked = LayerDefinition.create');
if (start < 0 || end < 0) { console.error('markers'); process.exit(1); }

const block = `        root.addOrReplaceChild("left_frame", CubeListBuilder.create().texOffs(0, 0)
                .addBox(-6.0F, 16.6F, -1.0F, 6.0F, 1.4F, 2.0F)
                .addBox(-6.0F, 0.0F, -1.0F, 6.0F, 1.2F, 2.0F)
                .addBox(-6.0F, 0.0F, -1.0F, 0.5F, 18.0F, 2.0F)
                .addBox(-0.5F, 0.0F, -1.0F, 0.5F, 18.0F, 2.0F)
                .addBox(-6.0F, 8.6F, -1.0F, 6.0F, 0.8F, 2.0F), PartPose.ZERO);
        root.addOrReplaceChild("left_glass", CubeListBuilder.create().texOffs(0, 0)
                .addBox(-5.5F, 1.2F, -0.9F, 5.0F, 7.4F, 1.8F)
                .addBox(-5.5F, 9.4F, -0.9F, 5.0F, 7.2F, 1.8F), PartPose.ZERO);
        root.addOrReplaceChild("right_frame", CubeListBuilder.create().texOffs(0, 0)
                .addBox(0.0F, 16.6F, -1.0F, 6.0F, 1.4F, 2.0F)
                .addBox(0.0F, 0.0F, -1.0F, 6.0F, 1.2F, 2.0F)
                .addBox(0.0F, 0.0F, -1.0F, 0.5F, 18.0F, 2.0F)
                .addBox(5.5F, 0.0F, -1.0F, 0.5F, 18.0F, 2.0F)
                .addBox(0.0F, 8.6F, -1.0F, 6.0F, 0.8F, 2.0F), PartPose.ZERO);
        root.addOrReplaceChild("right_glass", CubeListBuilder.create().texOffs(0, 0)
                .addBox(0.5F, 1.2F, -0.9F, 5.0F, 7.4F, 1.8F)
                .addBox(0.5F, 9.4F, -0.9F, 5.0F, 7.2F, 1.8F), PartPose.ZERO);

`;
t = t.slice(0, start) + block + t.slice(end);
fs.writeFileSync(p, t);
console.log('door rebuilt');
