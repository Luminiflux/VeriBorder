const fs = require('fs');
const p = 'src/main/java/com/veriborder/client/EGateRenderer.java';
let t = fs.readFileSync(p, 'utf8');

// global scale back to pixels -> blocks
t = t.replace('pose.scale(1.0F, 1.0F, 1.0F);', 'pose.scale(0.0625F, 0.0625F, 0.0625F);');

const start = t.indexOf('        // --- sliding door leaves');
const end = t.indexOf('        // --- status lights');
if (start < 0 || end < 0) { console.error('markers', start, end); process.exit(1); }

const block = `        // --- sliding door leaves -----------------------------------------
        // The door cubes are authored at 16x the block-model unit, so they get
        // their own scale; slide/squeeze are applied inside that scale.
        float open = ease(entity.openProgress(partialTick));
        float slide = open * DOOR_SLIDE;
        float squeeze = 1.0F - DOOR_SQUEEZE * open;
        RenderType solid = RenderType.entitySolid(exit ? EXIT_FRAME : FRAME);
        RenderType glassType = RenderType.entityTranslucent(exit ? EXIT_GLASS : GLASS);
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

`;
t = t.slice(0, start) + block + t.slice(end);
fs.writeFileSync(p, t);
console.log('door scale 16x only');
