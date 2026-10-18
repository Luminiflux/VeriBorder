package com.veriborder.client.screen;

import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentKind;
import com.veriborder.doc.EndorsementRecord;
import com.veriborder.doc.StampRecord;
import com.veriborder.doc.VerificationResult;
import com.veriborder.doc.VisaRecord;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractDocumentScreen extends VeriScreen {
    protected final ItemStack stack;
    protected final DocumentData doc;
    protected final VerificationResult check;
    protected int tabIndex;
    protected int scroll;
    protected int contentX;
    protected int contentY;
    protected int contentWidth;
    protected int contentHeight;
    protected int tabBarX;
    protected int tabBarY;
    protected int tabBarWidth;
    protected int tabWidth;
    protected int[] visibleTabs = {0};

    protected AbstractDocumentScreen(ItemStack stack, DocumentData doc, VerificationResult check) {
        super(Component.translatable("screen.veriborder.document"), 482, 310);
        this.stack = stack;
        this.doc = doc;
        this.check = check;
    }

    @Override
    protected void build() {
        if (doc != null) {
            accent = doc.accentColor();
            visibleTabs = tabsFor(doc.kind());
        } else {
            visibleTabs = new int[]{0};
        }
        boolean valid = false;
        for (int tab : visibleTabs) {
            if (tab == tabIndex) {
                valid = true;
                break;
            }
        }
        if (!valid) {
            tabIndex = 0;
        }
        tabBarX = left + 12;
        tabBarY = top + imageHeight - 26;
        tabBarWidth = imageWidth - 24;
        tabWidth = (tabBarWidth - (visibleTabs.length - 1) * 4) / visibleTabs.length;
        addExtraWidgets();
    }

    protected void addExtraWidgets() {
    }

    private static int[] tabsFor(DocumentKind kind) {
        return switch (kind) {
            case ID_CARD -> new int[]{0};
            case PASSPORT -> new int[]{0, 1, 3};
            case PERMIT -> new int[]{0, 2, 3};
        };
    }

    private Component tabLabel(int tab) {
        return switch (tab) {
            case 1 -> Component.translatable("tab.veriborder.visas", doc == null ? 0 : doc.visas().size());
            case 2 -> Component.translatable("tab.veriborder.endorsements",
                    doc == null ? 0 : doc.endorsements().size());
            case 3 -> Component.translatable("tab.veriborder.stamps", doc == null ? 0 : doc.stamps().size());
            default -> Component.translatable("tab.veriborder.profile");
        };
    }

    private void drawTabs(GuiGraphics graphics) {
        for (int i = 0; i < visibleTabs.length; i++) {
            int tab = visibleTabs[i];
            int x = tabBarX + i * (tabWidth + 4);
            boolean active = tab == tabIndex;
            graphics.fill(x, tabBarY, x + tabWidth, tabBarY + 19,
                    active ? Ui.blend(Ui.PANEL_3, accent, 0.24F) : Ui.PANEL_2);
            graphics.renderOutline(x, tabBarY, tabWidth, 19, active ? accent : Ui.BORDER);
            if (active) {
                graphics.fill(x, tabBarY, x + tabWidth, tabBarY + 2, accent);
            }
            Component label = tabLabel(tab);
            String text = Ui.ellipsis(label.getString(), tabWidth - 8);
            graphics.drawCenteredString(font, text, x + tabWidth / 2, tabBarY + 5,
                    active ? Ui.TEXT : Ui.MUTED);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        if (uiY >= tabBarY && uiY <= tabBarY + 19 && uiX >= tabBarX
                && uiX <= tabBarX + tabBarWidth) {
            for (int i = 0; i < visibleTabs.length; i++) {
                int x = tabBarX + i * (tabWidth + 4);
                if (uiX >= x && uiX <= x + tabWidth) {
                    tabIndex = visibleTabs[i];
                    scroll = 0;
                    return true;
                }
            }
        }
        return false;
    }

    protected abstract String titleText();

    protected abstract String subtitleText();

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, titleText(), subtitleText());
        if (doc == null) {
            graphics.drawCenteredString(font, Component.translatable("document.veriborder.empty"),
                    left + imageWidth / 2, top + imageHeight / 2, Ui.MUTED);
            return;
        }
        int bannerBottom = top + 44;
        if (check != null) {
            Ui.drawCheck(graphics, check, left + 12, bannerBottom, imageWidth - 24, 18);
        }
        int cardTop = top + (check != null ? 68 : 50);
        Ui.documentCard(graphics, left + 12, cardTop, 214, 144, doc);
        drawInfo(graphics, left + 238, cardTop, 232, 144);
        int listTop = cardTop + 150;
        contentX = left + 12;
        contentY = listTop;
        contentWidth = imageWidth - 24;
        contentHeight = top + imageHeight - 30 - listTop;
        Ui.inset(graphics, contentX, contentY, contentWidth, contentHeight);
        drawList(graphics, contentX + 6, contentY + 5, contentWidth - 12, contentHeight - 10);
        drawTabs(graphics);
    }

    private void drawInfo(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xAA101A26);
        graphics.renderOutline(x, y, width, height, Ui.BORDER);
        Ui.scissor(graphics, x + 1, y + 1, x + width - 1, y + height - 1);
        int rowY = y + 8;
        drawRow(graphics, x + 7, rowY, width - 14, "field.veriborder.name", doc.name());
        drawRow(graphics, x + 7, rowY + 14, width - 14, "field.veriborder.number", doc.number());
        drawRow(graphics, x + 7, rowY + 28, width - 14, "field.veriborder.nationality", doc.nationality());
        drawRow(graphics, x + 7, rowY + 42, width - 14, "field.veriborder.birth", doc.birthDate());
        drawRow(graphics, x + 7, rowY + 56, width - 14, "field.veriborder.sex",
                Component.translatable(doc.sexKey()).getString());
        drawRow(graphics, x + 7, rowY + 70, width - 14, "field.veriborder.issuer", doc.authority());
        drawRow(graphics, x + 7, rowY + 84, width - 14, "field.veriborder.issue", doc.issueDate());
        drawRow(graphics, x + 7, rowY + 98, width - 14, "field.veriborder.expiry",
                doc.isPermanent() ? Component.translatable("date.veriborder.permanent").getString() : doc.expiryDate());
        if (doc.kind() == DocumentKind.ID_CARD) {
            drawRow(graphics, x + 7, rowY + 112, width - 14, "field.veriborder.address", doc.address());
        } else {
            drawRow(graphics, x + 7, rowY + 112, width - 14, "field.veriborder.place", doc.placeOfIssue());
        }
        graphics.disableScissor();
    }

    private void drawRow(GuiGraphics graphics, int x, int y, int width, String labelKey, String value) {
        Component label = Component.translatable(labelKey);
        graphics.drawString(font, label, x, y, Ui.DIM, false);
        int labelWidth = Math.max(46, font.width(label) + 6);
        graphics.drawString(font, Ui.ellipsis(value == null ? "—" : value, width - labelWidth), x + labelWidth, y,
                Ui.TEXT, false);
    }

    private void drawList(GuiGraphics graphics, int x, int y, int width, int height) {
        List<Component> rows = new ArrayList<>();
        int rowHeight = 12;
        if (tabIndex == 0) {
            rows.add(Component.translatable("document.veriborder.integrity",
                    Component.translatable(doc.integrityValid() ? "document.veriborder.integrity.ok"
                            : "document.veriborder.integrity.bad")));
            rows.add(Component.translatable("document.veriborder.records", doc.visas().size(),
                    doc.endorsements().size(), doc.stamps().size()));
            if (doc.kind() == DocumentKind.PASSPORT) {
                rows.add(Component.literal(doc.mrz1()));
                rows.add(Component.literal(doc.mrz2()));
            } else {
                rows.add(Component.translatable("document.veriborder.modifier_hint"));
            }
        } else if (tabIndex == 1) {
            for (VisaRecord visa : doc.visas()) {
                rows.add(Component.literal("[" + visa.country() + "] #" + visa.code() + " "
                        + Component.translatable(visa.type().translationKey()).getString()
                        + "  " + visa.entriesText() + "  " + visa.expiryDate()
                        + "  " + Component.translatable("record.veriborder.status." + visa.status().toLowerCase()).getString()));
            }
        } else if (tabIndex == 2) {
            for (EndorsementRecord endorsement : doc.endorsements()) {
                rows.add(Component.literal("#" + endorsement.code() + " "
                        + Component.translatable(endorsement.type().translationKey()).getString()
                        + "  " + endorsement.entriesText() + "  " + endorsement.expiryDate()
                        + "  " + Component.translatable("record.veriborder.status."
                        + endorsement.status().toLowerCase()).getString()));
            }
        } else {
            for (StampRecord stamp : doc.stamps()) {
                rows.add(Component.literal(Component.translatable(stamp.directionKey()).getString()
                        + "  " + stamp.port() + " / " + stamp.country() + "  " + stamp.date()
                        + "  " + stamp.officer()));
            }
        }
        if (rows.isEmpty()) {
            graphics.drawString(font, Component.translatable("document.veriborder.no_records"), x + 2, y + 2,
                    Ui.DIM, false);
            return;
        }
        int visible = Math.max(1, height / rowHeight);
        int maxScroll = Math.max(0, rows.size() - visible);
        scroll = Math.min(scroll, maxScroll);
        Ui.scissor(graphics, x, y, x + width, y + height);
        for (int i = 0; i < visible + 1 && scroll + i < rows.size(); i++) {
            int rowY = y + i * rowHeight;
            Component row = rows.get(scroll + i);
            if (i % 2 == 0) {
                graphics.fill(x, rowY - 1, x + width, rowY + rowHeight - 1, 0x221E3346);
            }
            if (tabIndex == 3 && scroll + i < doc.stamps().size()) {
                boolean inbound = doc.stamps().get(scroll + i).inbound();
                graphics.fill(x, rowY - 1, x + 2, rowY + rowHeight - 2, inbound ? Ui.GREEN : Ui.AMBER);
            }
            graphics.drawString(font, Ui.ellipsis(row.getString(), width - 6), x + 5, rowY, Ui.MUTED, false);
        }
        graphics.disableScissor();
        if (maxScroll > 0) {
            int barHeight = Math.max(10, height * visible / rows.size());
            int barY = y + (height - barHeight) * scroll / maxScroll;
            graphics.fill(x + width - 2, y, x + width, y + height, 0x332A3B50);
            graphics.fill(x + width - 2, barY, x + width, barY + barHeight, accent);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        if (uiX >= contentX && uiX <= contentX + contentWidth && uiY >= contentY
                && uiY <= contentY + contentHeight) {
            scroll = Math.max(0, scroll - (int) Math.signum(delta) * 2);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }
}
