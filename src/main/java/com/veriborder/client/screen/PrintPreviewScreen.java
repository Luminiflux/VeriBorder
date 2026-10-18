package com.veriborder.client.screen;

import com.veriborder.client.BetterButton;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentKind;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public class PrintPreviewScreen extends VeriScreen {
    private final DocumentData doc;

    public PrintPreviewScreen(DocumentData doc) {
        super(Component.translatable("screen.veriborder.print"), 520, 330);
        this.doc = doc;
        this.accent = doc == null ? Ui.GOLD : doc.style().accentColor();
    }

    @Override
    protected void build() {
        addRenderableWidget(new BetterButton(left + imageWidth - 100, top + imageHeight - 30, 88, 20,
                Component.translatable("button.veriborder.close"), button -> onClose(), Ui.GOLD, true));
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, Component.translatable("screen.veriborder.print.title").getString(),
                Component.translatable("screen.veriborder.print.subtitle").getString());
        if (doc == null) {
            graphics.drawCenteredString(font, Component.translatable("document.veriborder.empty"),
                    left + imageWidth / 2, top + imageHeight / 2, Ui.MUTED);
            return;
        }
        int cardX = left + 24;
        int cardY = top + 56;
        Ui.documentCard(graphics, cardX, cardY, 292, 186, doc);
        int infoX = left + 336;
        int y = top + 60;
        graphics.drawString(font, Component.translatable("screen.veriborder.print.inspection"), infoX, y, Ui.GOLD, false);
        y += 18;
        drawLine(graphics, infoX, y, "field.veriborder.display_name", doc.displayName());
        drawLine(graphics, infoX, y += 16, "field.veriborder.name", doc.name());
        drawLine(graphics, infoX, y += 16, "field.veriborder.number", doc.number());
        drawLine(graphics, infoX, y += 16, "field.veriborder.nationality", doc.nationality());
        drawLine(graphics, infoX, y += 16, "field.veriborder.issue", doc.issueDate());
        drawLine(graphics, infoX, y += 16, "field.veriborder.expiry",
                doc.isPermanent() ? Component.translatable("date.veriborder.permanent").getString() : doc.expiryDate());
        drawLine(graphics, infoX, y += 16, "field.veriborder.issuer", doc.authority());
        drawLine(graphics, infoX, y += 16, "field.veriborder.status",
                Component.translatable(doc.status().translationKey()).getString());
        y += 24;
        graphics.drawString(font, Component.translatable("screen.veriborder.print.integrity",
                Component.translatable(doc.integrityValid() ? "document.veriborder.integrity.ok"
                        : "document.veriborder.integrity.bad")), infoX, y, doc.integrityValid() ? Ui.GREEN : Ui.RED, false);
        y += 18;
        Ui.clipped(graphics, Component.translatable("screen.veriborder.print.footer"), infoX, y, 172, Ui.DIM);
        if (doc.kind() == DocumentKind.PASSPORT) {
            graphics.drawString(font, Component.translatable("screen.veriborder.print.mrz"), left + 24, top + 256,
                    Ui.GOLD, false);
            graphics.pose().pushPose();
            graphics.pose().translate(left + 24, top + 270, 0);
            graphics.pose().scale(0.72F, 0.72F, 1.0F);
            graphics.drawString(font, doc.mrz1(), 0, 0, Ui.MUTED, false);
            graphics.drawString(font, doc.mrz2(), 0, 14, Ui.MUTED, false);
            graphics.pose().popPose();
        }
        String watermark = "VERIBORDER · OFFICIAL DOCUMENT PREVIEW";
        graphics.drawString(font, watermark, left + 24, top + imageHeight - 28, 0x33FFFFFF, false);
    }

    private void drawLine(GuiGraphics graphics, int x, int y, String key, String value) {
        Component label = Component.translatable(key);
        graphics.drawString(font, label, x, y, Ui.DIM, false);
        int labelWidth = font.width(label) + 5;
        int maxValue = Math.max(24, left + imageWidth - 12 - (x + labelWidth));
        graphics.drawString(font, Ui.ellipsis(value == null ? "—" : value, maxValue), x + labelWidth, y,
                Ui.TEXT, false);
    }
}
