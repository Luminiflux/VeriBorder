package com.veriborder.client.ui;

import com.veriborder.client.Ui;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Fixed-height, scrollable view of a {@link CodeListModel}: one row per code
 * with its own delete button, a scrollbar when the list is longer than the
 * window, and an empty placeholder.
 *
 * <p>The operator sees the codes immediately after adding them (26.4 only
 * printed the count, so the list looked empty). The view owns no data and no
 * page state beyond the model's window, so all three screens behave the same
 * and only the model needs unit tests.</p>
 */
public final class CodeListBox {
    private static final int DELETE_WIDTH = 14;

    private final CodeListModel model;
    private final Component emptyText;
    private int x;
    private int y;
    private int width;

    public CodeListBox(int x, int y, int width, CodeListModel model, Component emptyText) {
        this.x = x;
        this.y = y;
        this.width = Math.max(40, width);
        this.model = model == null ? new CodeListModel() : model;
        this.emptyText = emptyText == null ? Component.empty() : emptyText;
    }

    public CodeListModel model() {
        return model;
    }

    public void moveTo(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return width;
    }

    /** Fixed block height (the window never resizes while scrolling). */
    public int height() {
        return model.height();
    }

    public int bottom() {
        return y + height();
    }

    // ------------------------------------------------------------------ draw

    public void draw(GuiGraphics graphics, Font font, int accent, double mouseX, double mouseY) {
        int height = height();
        graphics.fill(x, y, x + width, y + height, 0xFF091119);
        graphics.renderOutline(x, y, width, height, Ui.BORDER);
        if (model.isEmpty()) {
            graphics.drawString(font, Ui.ellipsis(emptyText.getString(), width - 8), x + 5, y + 3,
                    Ui.DIM, false);
            return;
        }
        int hovered = indexAt(mouseX, mouseY);
        for (int index = model.visibleStart(); index < model.visibleEnd(); index++) {
            int rowY = model.rowTop(index, y);
            int[] delete = deleteRect(index);
            boolean rowHover = index == hovered;
            if (rowHover) {
                graphics.fill(x + 1, rowY, x + width - 1, rowY + CodeListModel.ROW_HEIGHT - 1, 0x221E3346);
            }
            graphics.drawString(font, model.codeAt(index), x + 5, rowY + 2, Ui.TEXT, false);
            boolean deleteHover = inside(mouseX, mouseY, delete);
            graphics.fill(delete[0], delete[1], delete[0] + delete[2], delete[1] + delete[3],
                    deleteHover ? Ui.RED : Ui.PANEL_2);
            graphics.renderOutline(delete[0], delete[1], delete[2], delete[3],
                    deleteHover ? Ui.RED : Ui.BORDER);
            graphics.drawCenteredString(font, "\u00D7", delete[0] + delete[2] / 2, delete[1] + 1,
                    deleteHover ? Ui.RED : Ui.MUTED);
        }
        if (model.maxScroll() > 0) {
            int rows = model.rows();
            int trackTop = y;
            int trackHeight = rows * CodeListModel.ROW_HEIGHT;
            int thumbHeight = Math.max(6, trackHeight * rows / (rows + model.maxScroll()));
            int thumbTop = trackTop + (trackHeight - thumbHeight) * model.scroll() / model.maxScroll();
            graphics.fill(x + width - 3, trackTop, x + width - 1, trackTop + trackHeight, 0x332B4056);
            graphics.fill(x + width - 3, thumbTop, x + width - 1, thumbTop + thumbHeight, accent);
        }
    }

    private int[] deleteRect(int index) {
        int rowY = model.rowTop(index, y);
        return new int[]{x + width - DELETE_WIDTH - 3, rowY + 2, DELETE_WIDTH, CodeListModel.ROW_HEIGHT - 4};
    }

    // ----------------------------------------------------------------- input

    /** Handles a click: the per-row × removes that row. Consumes clicks inside. */
    public boolean mouseClicked(double mouseX, double mouseY) {
        int index = indexAt(mouseX, mouseY);
        if (index < 0) {
            return false;
        }
        if (inside(mouseX, mouseY, deleteRect(index))) {
            model.removeAt(index);
        }
        return true;
    }

    /** Wheel over the list scrolls its window. */
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!inside(mouseX, mouseY, x, y, width, height())) {
            return false;
        }
        if (model.maxScroll() == 0) {
            // Nothing to scroll inside the box: let the page take the wheel.
            return false;
        }
        // Consume even at the end of the window, so the page does not jump while
        // the cursor is still over the list.
        model.scrollBy(delta);
        return true;
    }

    private int indexAt(double mouseX, double mouseY) {
        if (!inside(mouseX, mouseY, x, y, width, height())) {
            return -1;
        }
        return model.indexAt(mouseY, y);
    }

    private static boolean inside(double mouseX, double mouseY, int[] rect) {
        return inside(mouseX, mouseY, rect[0], rect[1], rect[2], rect[3]);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }
}
