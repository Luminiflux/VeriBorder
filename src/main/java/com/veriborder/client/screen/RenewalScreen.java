package com.veriborder.client.screen;

import com.veriborder.block.DeviceType;
import com.veriborder.client.BetterButton;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.doc.DateUtil;
import com.veriborder.doc.DocumentData;
import com.veriborder.network.DeviceActionPacket;
import com.veriborder.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

public class RenewalScreen extends VeriScreen {
    private final BlockPos pos;
    private final int slot;
    private final DocumentData doc;
    private EditBox expiry;
    private EditBox years;

    public RenewalScreen(BlockPos pos, int slot, DocumentData doc) {
        super(Component.translatable("screen.veriborder.renewal"), 450, 285);
        this.pos = pos;
        this.slot = slot;
        this.doc = doc;
        this.accent = Ui.AMBER;
    }

    @Override
    protected void build() {
        int x = left + 226;
        expiry = addField(x, top + 92, 196, "field.veriborder.new_expiry",
                doc == null ? DateUtil.plusYears(DateUtil.today(), 10) : doc.expiryDate(), "hint.veriborder.date");
        years = addField(x, top + 132, 70, "field.veriborder.years", "10", "hint.veriborder.years");
        addRenderableWidget(new BetterButton(x + 80, top + 128, 56, 20, Component.literal("+5"),
                button -> extend(5), Ui.AMBER, true));
        addRenderableWidget(new BetterButton(x + 141, top + 128, 55, 20, Component.literal("+10"),
                button -> extend(10), Ui.AMBER, true));
        addRenderableWidget(new BetterButton(x, top + 178, 110, 20, Component.translatable("button.veriborder.renew"),
                button -> renew(), Ui.GREEN));
        addRenderableWidget(new BetterButton(x + 120, top + 178, 76, 20, Component.translatable("button.veriborder.close"),
                button -> onClose(), Ui.MUTED, true));
    }

    private void extend(int amount) {
        String base = expiry.getValue();
        if (!DateUtil.isValid(base) || DateUtil.isPermanent(base)) {
            base = DateUtil.today();
        }
        expiry.setValue(DateUtil.plusYears(base, amount));
        years.setValue(String.valueOf(amount));
    }

    private void renew() {
        CompoundTag data = new CompoundTag();
        String value = expiry.getValue().trim();
        if (DateUtil.isValid(value) || DateUtil.isPermanent(value)) {
            data.putString("expiryDate", value);
        }
        try {
            data.putInt("years", Integer.parseInt(years.getValue().trim()));
        } catch (NumberFormatException ignored) {
            data.putInt("years", 10);
        }
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "renew", data, slot));
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, Component.translatable(DeviceType.RENEWAL_MACHINE.translationKey()).getString(),
                Component.translatable("screen.veriborder.renewal.subtitle").getString());
        if (doc == null) {
            graphics.drawCenteredString(font, Component.translatable("document.veriborder.empty"),
                    left + imageWidth / 2, top + 110, Ui.MUTED);
            return;
        }
        Ui.documentCard(graphics, left + 20, top + 52, 194, 142, doc);
        int x = left + 226;
        Ui.clipped(graphics, Component.translatable("screen.veriborder.renewal.current", doc.expiryDate()),
                x, top + 54, 196, Ui.TEXT);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.renewal.today"), x, top + 70, 196, Ui.MUTED);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.renewal.note"), x, top + 158, 196, Ui.DIM);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.renewal.note2"), x, top + 168, 196, Ui.DIM);
    }
}
