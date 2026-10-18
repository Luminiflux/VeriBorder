package com.veriborder.client.screen;

import com.veriborder.block.DeviceType;
import com.veriborder.client.BetterButton;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentStatus;
import com.veriborder.doc.VerificationResult;
import com.veriborder.network.DeviceActionPacket;
import com.veriborder.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

public class LossScreen extends VeriScreen {
    private final BlockPos pos;
    private final int slot;
    private final DocumentData doc;

    public LossScreen(BlockPos pos, int slot, DocumentData doc, VerificationResult check) {
        super(Component.translatable("screen.veriborder.loss"), 450, 260);
        this.pos = pos;
        this.slot = slot;
        this.doc = doc;
        this.accent = Ui.RED;
    }

    @Override
    protected void build() {
        int y = top + 205;
        addRenderableWidget(new BetterButton(left + 20, y, 94, 20, Component.translatable("status.veriborder.lost"),
                button -> setStatus(DocumentStatus.LOST), Ui.AMBER));
        addRenderableWidget(new BetterButton(left + 120, y, 94, 20, Component.translatable("status.veriborder.cancelled"),
                button -> setStatus(DocumentStatus.CANCELLED), Ui.RED));
        addRenderableWidget(new BetterButton(left + 220, y, 94, 20, Component.translatable("status.veriborder.suspended"),
                button -> setStatus(DocumentStatus.SUSPENDED), Ui.PURPLE));
        addRenderableWidget(new BetterButton(left + 320, y, 94, 20, Component.translatable("status.veriborder.valid"),
                button -> setStatus(DocumentStatus.VALID), Ui.GREEN));
    }

    private void setStatus(DocumentStatus status) {
        if (doc == null) {
            onClose();
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putString("status", status.id());
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "set_status", data, slot));
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, Component.translatable(DeviceType.LOSS_TERMINAL.translationKey()).getString(),
                Component.translatable("screen.veriborder.loss.subtitle").getString());
        if (doc == null) {
            graphics.drawCenteredString(font, Component.translatable("document.veriborder.empty"),
                    left + imageWidth / 2, top + 110, Ui.MUTED);
            return;
        }
        Ui.documentCard(graphics, left + 20, top + 50, 194, 140, doc);
        int x = left + 230;
        Ui.clipped(graphics, Component.translatable("screen.veriborder.loss.current",
                Component.translatable(doc.status().translationKey())), x, top + 56, 208, doc.status().color());
        Ui.clipped(graphics, Component.translatable("screen.veriborder.loss.warning.1"), x, top + 78, 208, Ui.TEXT);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.loss.warning.2"), x, top + 92, 208, Ui.MUTED);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.loss.warning.3"), x, top + 106, 208, Ui.MUTED);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.loss.warning.4"), x, top + 126, 208, Ui.AMBER);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.loss.warning.5"), x, top + 140, 208, Ui.AMBER);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.loss.note"), x, top + 164, 208, Ui.DIM);
    }
}
