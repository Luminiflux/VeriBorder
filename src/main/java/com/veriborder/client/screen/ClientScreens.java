package com.veriborder.client.screen;

import com.veriborder.doc.DocumentData;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public final class ClientScreens {
    private ClientScreens() {
    }

    public static void openPrint(DocumentData doc) {
        Minecraft.getInstance().setScreen(new PrintPreviewScreen(doc));
    }

    /** Opens the dual-clearance gate brush editor seeded from the held brush. */
    public static void openDualGateBrush(ItemStack stack) {
        Minecraft.getInstance().setScreen(new DualGateBrushScreen(stack));
    }

    /** Opens the admin border terminal with a server snapshot. */
    public static void openBorderTerminal(CompoundTag data) {
        Minecraft.getInstance().setScreen(new BorderTerminalScreen(data));
    }
}
