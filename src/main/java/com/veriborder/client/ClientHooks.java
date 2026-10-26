package com.veriborder.client;

import com.veriborder.client.screen.DocumentViewScreen;
import com.veriborder.doc.DocumentData;
import com.veriborder.item.DocumentItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class ClientHooks {
    private ClientHooks() {
    }

    /**
     * 26.3: right-clicking a held document opens the paged viewer (Identity /
     * MRZ / Visas / Endorsements / Stamps / History / Integrity) instead of the
     * old single-pane screen.
     */
    public static void openDocument(ItemStack stack) {
        DocumentData doc = DocumentItem.getDocument(stack);
        Minecraft.getInstance().setScreen(new DocumentViewScreen(stack, doc));
    }

    /**
     * 26.4.1 item name of a document: the issuing template's own credential title
     * first, then the document's display name, then the official layout name.
     * Runs on the client only (the template titles live in the client registry
     * cache), so {@code DocumentItem} calls it through {@code DistExecutor}.
     */
    public static Component documentTitle(DocumentData doc) {
        return TemplateDisplay.documentTitleComponent(doc);
    }

    /**
     * 26.4.1 tooltip lines that need the client registry cache: the template the
     * document was issued from and the emblem it prints.
     */
    public static void appendTemplateTooltip(DocumentData doc, List<Component> tooltip) {
        if (doc == null || tooltip == null) {
            return;
        }
        if (TemplateDisplay.documentTemplate(doc) != null) {
            tooltip.add(Component.translatable("tooltip.veriborder.document.template",
                    TemplateDisplay.documentTitle(doc)).withStyle(ChatFormatting.AQUA));
        }
        String emblem = TemplateDisplay.documentEmblem(doc);
        if (!emblem.isBlank()) {
            tooltip.add(Component.translatable("tooltip.veriborder.document.emblem",
                    Component.translatable("icon.veriborder." + emblem)).withStyle(ChatFormatting.GRAY));
        }
        if (!TemplateDisplay.documentTexture(doc).isBlank()) {
            tooltip.add(Component.translatable("tooltip.veriborder.document.texture")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
