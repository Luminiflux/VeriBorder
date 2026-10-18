package com.veriborder.client.screen;

import com.veriborder.block.DeviceType;
import com.veriborder.client.BetterButton;
import com.veriborder.client.Ui;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.VerificationResult;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class ReaderScreen extends AbstractDocumentScreen {
    private final DeviceType device;
    private final BlockPos pos;
    private final int slot;
    private final CompoundTag payload;

    public ReaderScreen(DeviceType device, BlockPos pos, ItemStack stack, DocumentData doc, VerificationResult check,
                        int slot, CompoundTag payload) {
        super(stack, doc, check);
        this.device = device;
        this.pos = pos;
        this.slot = slot;
        this.payload = payload;
        if (device == DeviceType.STAMP_HISTORY) {
            this.tabIndex = 3;
        }
    }

    @Override
    protected String titleText() {
        return Component.translatable(device.translationKey()).getString();
    }

    @Override
    protected String subtitleText() {
        String description = Component.translatable(device.descriptionKey()).getString();
        if (device == DeviceType.AIRPORT_CHECKIN) {
            String destination = payload.getString("destination");
            if (!destination.isBlank()) {
                return description + " · " + Component.translatable("screen.veriborder.airport.destination",
                        destination).getString();
            }
        }
        return description;
    }

    @Override
    protected int headerRightInset() {
        return doc != null ? 192 : 100;
    }

    @Override
    protected void addExtraWidgets() {
        // Terminal configuration is deliberately NOT reachable from a reader:
        // it requires the Device Brush (DeviceService.openConfig / "config").
        if (doc != null) {
            addRenderableWidget(new BetterButton(left + imageWidth - 92, top + 10, 80, 18,
                    Component.translatable("screen.veriborder.print"), button ->
                    ClientScreens.openPrint(doc), Ui.GOLD, true));
        }
    }
}
