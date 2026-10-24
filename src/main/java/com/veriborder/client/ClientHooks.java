package com.veriborder.client;

import com.veriborder.client.screen.DocumentViewScreen;
import com.veriborder.doc.DocumentData;
import com.veriborder.item.DocumentItem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

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
}
