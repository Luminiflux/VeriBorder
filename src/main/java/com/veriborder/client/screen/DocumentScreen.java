package com.veriborder.client.screen;

import com.veriborder.doc.DocumentData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class DocumentScreen extends AbstractDocumentScreen {
    public DocumentScreen(ItemStack stack, DocumentData doc) {
        super(stack, doc, null);
    }

    @Override
    protected String titleText() {
        return doc == null ? Component.translatable("screen.veriborder.document").getString()
                : doc.displayName();
    }

    @Override
    protected String subtitleText() {
        return Component.translatable("screen.veriborder.document.subtitle").getString();
    }
}
