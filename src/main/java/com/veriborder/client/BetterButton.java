package com.veriborder.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class BetterButton extends Button {
    private final int accent;
    private final boolean subtle;

    public BetterButton(int x, int y, int width, int height, Component message, OnPress onPress, int accent) {
        this(x, y, width, height, message, onPress, accent, false);
    }

    public BetterButton(int x, int y, int width, int height, Component message, OnPress onPress, int accent,
                        boolean subtle) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.accent = accent;
        this.subtle = subtle;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hovered = isHoveredOrFocused();
        int border = active ? (hovered ? accent : Ui.BORDER) : 0xFF26313D;
        int fill = active ? (hovered ? Ui.blend(Ui.PANEL_3, accent, 0.22F) : subtle ? Ui.PANEL_2 : Ui.PANEL_3)
                : Ui.PANEL_2;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, fill);
        graphics.renderOutline(getX(), getY(), width, height, border);
        if (!subtle) {
            graphics.fill(getX(), getY(), getX() + 2, getY() + height, active ? accent : 0xFF26313D);
        }
        int textColor = active ? Ui.TEXT : Ui.DIM;
        String label = Minecraft.getInstance().font.plainSubstrByWidth(getMessage().getString(),
                Math.max(8, width - 8));
        graphics.drawCenteredString(Minecraft.getInstance().font, label, getX() + width / 2,
                getY() + (height - 8) / 2, textColor);
    }

    @Override
    public void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
