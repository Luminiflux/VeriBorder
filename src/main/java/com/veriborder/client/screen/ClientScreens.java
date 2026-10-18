package com.veriborder.client.screen;

import com.veriborder.doc.DocumentData;
import net.minecraft.client.Minecraft;

public final class ClientScreens {
    private ClientScreens() {
    }

    public static void openPrint(DocumentData doc) {
        Minecraft.getInstance().setScreen(new PrintPreviewScreen(doc));
    }
}
