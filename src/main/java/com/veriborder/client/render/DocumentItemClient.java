package com.veriborder.client.render;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

/**
 * Client half of {@code DocumentItem.initializeClient}: supplies the custom
 * renderer that the eight dynamic base-map slot models need.
 *
 * <p>Kept in its own client-only class so the common item class never links
 * against a renderer type (the item is registered on a dedicated server too).</p>
 */
public final class DocumentItemClient {
    private DocumentItemClient() {
    }

    /** Installs the custom renderer for one document item. */
    public static void install(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    renderer = DocumentItemRenderer.get();
                }
                return renderer;
            }
        });
    }
}
