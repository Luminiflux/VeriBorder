package com.veriborder.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.veriborder.doc.DocumentKind;
import com.veriborder.item.DocumentItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * 26.4.1 custom cover base map renderer.
 *
 * <p><b>Why this exists.</b> An item model may only sample textures that the block
 * atlas stitched while the resource pack was loaded. A base map uploaded by an
 * admin does not exist at that moment, so it cannot be a {@code layer0..2} entry:
 * the neutral thing to do is what vanilla does for shields and chests — mark the
 * slot model as {@code builtin/entity} and draw it from a
 * {@link BlockEntityWithoutLevelRenderer}.</p>
 *
 * <p>Only the eight slot models are marked that way, so a document without a base
 * map never enters this class: it renders through the ordinary baked model with
 * its two layers (flat card + crest) and the {@code ItemColors} tint. When a base
 * map <em>is</em> present, this renderer draws two quads:</p>
 * <ol>
 *   <li>the uploaded picture, stretched over the whole 16x16 item face,</li>
 *   <li>{@code <art>_frame} on top — the opaque card with the printed window cut
 *       out, so the picture can only be seen inside the window and the card keeps
 *       its silhouette, shadow and print.</li>
 * </ol>
 *
 * <p>If the map was not received (a snapshot without its payload, a feature the
 * server refused) the renderer falls back to the tinted flat card plus its crest,
 * so a custom-template document always looks like a document and never like a
 * missing texture.</p>
 */
public final class DocumentItemRenderer extends BlockEntityWithoutLevelRenderer {
    private static DocumentItemRenderer instance;

    private DocumentItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    /** Lazily created singleton; created on the client thread only. */
    public static DocumentItemRenderer get() {
        if (instance == null) {
            instance = new DocumentItemRenderer();
        }
        return instance;
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                             MultiBufferSource buffer, int light, int overlay) {
        DocumentKind kind = DocumentItem.kindOf(stack);
        DocumentAppearance.Skin skin = DocumentAppearance.of(stack, kind);
        Matrix4f matrix = pose.last().pose();
        Matrix3f normal = pose.last().normal();

        if (skin.usesBaseMap() && TemplateSlotTextures.ready(skin.slot())) {
            // picture first, then the printed card with its window cut out
            quad(buffer.getBuffer(RenderType.entityCutoutNoCull(TemplateSlots.textureId(skin.slot()))),
                    matrix, normal, light, overlay, 1.0F, 1.0F, 1.0F);
            quad(buffer.getBuffer(RenderType.entityCutoutNoCull(DocumentLayers.frame(kind))),
                    matrix, normal, light, overlay, 1.0F, 1.0F, 1.0F);
            return;
        }
        // No usable picture (slot not uploaded yet, or the server refused the map):
        // draw exactly what the baked model would have drawn — the flat card tinted
        // with the document colour and the crest on top — so a custom-template
        // document never looks like a broken texture.
        quad(buffer.getBuffer(RenderType.entityCutoutNoCull(DocumentLayers.card(kind))),
                matrix, normal, light, overlay, skin.red(), skin.green(), skin.blue());
        quad(buffer.getBuffer(RenderType.entityCutoutNoCull(DocumentLayers.emblem(skin.emblemId()))),
                matrix, normal, light, overlay, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Draws the 16x16 item face at z=0 (the item space after the renderer's own
     * {@code translate(-0.5, -0.5, -0.5)}), with the texture's top edge at v=0.
     * {@code entityCutoutNoCull} has no back-face culling, so one quad is enough.
     */
    private static void quad(VertexConsumer consumer, Matrix4f matrix, Matrix3f normal,
                             int light, int overlay, float red, float green, float blue) {
        vertex(consumer, matrix, normal, -0.5F, 0.5F, 0.0F, 0.0F, light, overlay, red, green, blue);
        vertex(consumer, matrix, normal, -0.5F, -0.5F, 0.0F, 1.0F, light, overlay, red, green, blue);
        vertex(consumer, matrix, normal, 0.5F, -0.5F, 1.0F, 1.0F, light, overlay, red, green, blue);
        vertex(consumer, matrix, normal, 0.5F, 0.5F, 1.0F, 0.0F, light, overlay, red, green, blue);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Matrix3f normal,
                               float x, float y, float u, float v, int light, int overlay,
                               float red, float green, float blue) {
        consumer.vertex(matrix, x, y, 0.0F)
                .color(red, green, blue, 1.0F)
                .uv(u, v)
                .overlayCoords(overlay)
                .uv2(light)
                .normal(normal, 0.0F, 0.0F, 1.0F)
                .endVertex();
    }
}
