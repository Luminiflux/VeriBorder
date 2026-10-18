package com.veriborder.client;

import com.veriborder.client.screen.DocumentScreen;
import com.veriborder.doc.DocumentData;
import com.veriborder.item.DocumentItem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

public final class ClientHooks {
    private ClientHooks() {
    }

    public static void openDocument(ItemStack stack) {
        DocumentData doc = DocumentItem.getDocument(stack);
        Minecraft.getInstance().setScreen(new DocumentScreen(stack, doc));
    }
}
