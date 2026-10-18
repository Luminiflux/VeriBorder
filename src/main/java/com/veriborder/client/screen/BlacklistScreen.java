package com.veriborder.client.screen;

import com.veriborder.block.DeviceType;
import com.veriborder.client.BetterButton;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentStatus;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class BlacklistScreen extends VeriScreen {
    private record Entry(int slot, DocumentData doc) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private int scroll;
    private boolean onlyBlacklisted;

    public BlacklistScreen(CompoundTag payload) {
        super(Component.translatable("screen.veriborder.blacklist"), 500, 310);
        this.accent = Ui.RED;
        ListTag docs = payload.getList("docs", Tag.TAG_COMPOUND);
        for (int i = 0; i < docs.size(); i++) {
            CompoundTag entry = docs.getCompound(i);
            try {
                entries.add(new Entry(entry.getInt("slot"), DocumentData.fromTag(entry.getCompound("doc"))));
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    protected void build() {
        addRenderableWidget(new BetterButton(left + 14, top + imageHeight - 28, 118, 19,
                Component.translatable(onlyBlacklisted ? "screen.veriborder.blacklist.filter.on"
                        : "screen.veriborder.blacklist.filter"), button -> {
                    onlyBlacklisted = !onlyBlacklisted;
                    scroll = 0;
                    rebuildWidgets();
                }, Ui.AMBER, true));
        addRenderableWidget(new BetterButton(left + imageWidth - 100, top + imageHeight - 28, 86, 19,
                Component.translatable("button.veriborder.close"), button -> onClose(), Ui.MUTED, true));
    }

    private List<Entry> visible() {
        if (!onlyBlacklisted) {
            return entries;
        }
        return entries.stream().filter(entry -> entry.doc.status() != DocumentStatus.VALID).toList();
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, Component.translatable(DeviceType.BLACKLIST_TERMINAL.translationKey()).getString(),
                Component.translatable("screen.veriborder.blacklist.subtitle", entries.size()).getString());
        List<Entry> rows = visible();
        int x = left + 16;
        int y = top + 50;
        int width = imageWidth - 32;
        if (rows.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.veriborder.blacklist.empty"), x, y + 8,
                    Ui.MUTED, false);
            return;
        }
        Ui.clipped(graphics, Component.translatable("screen.veriborder.blacklist.columns"), x + 4, y, width - 8,
                Ui.DIM);
        y += 14;
        int rowHeight = 21;
        int visibleRows = 9;
        scroll = Math.min(scroll, Math.max(0, rows.size() - visibleRows));
        Ui.scissor(graphics, x, y, x + width, y + visibleRows * rowHeight);
        for (int i = 0; i < visibleRows && scroll + i < rows.size(); i++) {
            Entry entry = rows.get(scroll + i);
            int rowY = y + i * rowHeight;
            graphics.fill(x, rowY, x + width, rowY + rowHeight - 2, i % 2 == 0 ? 0x33111C28 : 0x221A2A3A);
            int color = entry.doc.status().color();
            graphics.fill(x, rowY, x + 3, rowY + rowHeight - 2, color);
            graphics.drawString(font, "#" + entry.slot(), x + 8, rowY + 6, Ui.DIM, false);
            graphics.drawString(font, Ui.ellipsis(entry.doc.displayName(), 130), x + 34, rowY + 6, Ui.TEXT, false);
            graphics.drawString(font, Ui.ellipsis(entry.doc.name(), 120), x + 170, rowY + 6, Ui.MUTED, false);
            graphics.drawString(font, Ui.ellipsis(entry.doc.number(), 90), x + 294, rowY + 6, Ui.MUTED, false);
            Ui.clipped(graphics, Component.translatable(entry.doc.status().translationKey()), x + 392,
                    rowY + 6, 72, color);
        }
        graphics.disableScissor();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        if (uiX >= left && uiX <= left + imageWidth && uiY >= top + 50 && uiY <= top + imageHeight - 32) {
            scroll = Math.max(0, scroll - (int) Math.signum(delta));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }
}
