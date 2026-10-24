package com.veriborder.client.ui;

import com.veriborder.client.Ui;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared, screen-agnostic tab bar (26.3 UI round).
 *
 * <p>Every VeriBorder screen paints in the fixed design space of
 * {@link com.veriborder.client.VeriScreen}, so this component works purely in
 * design coordinates and never registers a widget: the owning screen forwards
 * its already-unprojected mouse position to {@link #mouseClicked(double, double)}
 * and draws the bar from {@code renderBackground}. That keeps tab hit boxes
 * correct at any GUI scale without another renderable.</p>
 *
 * <p>Tabs may carry a count badge (visas/stamps/records) and may be disabled;
 * the active tab is tinted with the supplied accent and underlined. Widths are
 * distributed evenly, so a strip of seven tabs still fits one row.</p>
 */
public final class TabStrip {
    /** One tab: stable id, localized label, optional count badge (-1 = none). */
    public record Tab(String id, Component label, int badge, boolean enabled) {
    }

    private static final int MIN_HEIGHT = 16;
    private static final int DEFAULT_HEIGHT = 20;

    private final List<Tab> tabs = new ArrayList<>();
    private int x;
    private int y;
    private int width = 200;
    private int height = DEFAULT_HEIGHT;
    private int gap = 3;
    private int selected;

    private TabStrip() {
    }

    public static TabStrip at(int x, int y, int width) {
        TabStrip strip = new TabStrip();
        strip.x = x;
        strip.y = y;
        strip.width = Math.max(8, width);
        return strip;
    }

    public TabStrip size(int height, int gap) {
        this.height = Math.max(MIN_HEIGHT, height);
        this.gap = Math.max(0, gap);
        return this;
    }

    public TabStrip add(String id, Component label) {
        tabs.add(new Tab(id, label == null ? Component.empty() : label, -1, true));
        return this;
    }

    public TabStrip add(String id, Component label, int badge) {
        tabs.add(new Tab(id, label == null ? Component.empty() : label, badge, true));
        return this;
    }

    public TabStrip addDisabled(String id, Component label) {
        tabs.add(new Tab(id, label == null ? Component.empty() : label, -1, false));
        return this;
    }

    public int tabCount() {
        return tabs.size();
    }

    public Tab tab(int index) {
        return index >= 0 && index < tabs.size() ? tabs.get(index) : null;
    }

    public int indexOf(String id) {
        if (id != null) {
            for (int i = 0; i < tabs.size(); i++) {
                if (id.equals(tabs.get(i).id())) {
                    return i;
                }
            }
        }
        return -1;
    }

    public int selectedIndex() {
        return selected;
    }

    public String selectedId() {
        return selected >= 0 && selected < tabs.size() ? tabs.get(selected).id() : "";
    }

    public boolean isSelected(String id) {
        return indexOf(id) >= 0 && indexOf(id) == selected;
    }

    public void setSelected(int index) {
        if (!tabs.isEmpty()) {
            selected = Math.max(0, Math.min(index, tabs.size() - 1));
        }
    }

    public void select(String id) {
        int index = indexOf(id);
        if (index >= 0) {
            selected = index;
        }
    }

    public boolean enabled(int index) {
        return index >= 0 && index < tabs.size() && tabs.get(index).enabled();
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

    public int height() {
        return height;
    }

    public int bottom() {
        return y + height;
    }

    public int tabWidth() {
        if (tabs.isEmpty()) {
            return width;
        }
        return Math.max(12, (width - (tabs.size() - 1) * gap) / tabs.size());
    }

    /** Rectangle {@code [x, y, width, height]} of one tab, or {@code null}. */
    public int[] rect(int index) {
        if (index < 0 || index >= tabs.size()) {
            return null;
        }
        return new int[]{x + index * (tabWidth() + gap), y, tabWidth(), height};
    }

    /** Index of the tab under the design-space point, or -1. */
    public int hit(double mouseX, double mouseY) {
        if (mouseY < y || mouseY > y + height || mouseX < x || mouseX > x + width) {
            return -1;
        }
        int tabWidth = tabWidth();
        for (int i = 0; i < tabs.size(); i++) {
            int left = x + i * (tabWidth + gap);
            if (mouseX >= left && mouseX <= left + tabWidth) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Selects the clicked tab. Returns true when a tab was hit (even if it was
     * already selected); disabled tabs are hit but never selected.
     */
    public boolean mouseClicked(double mouseX, double mouseY) {
        int index = hit(mouseX, mouseY);
        if (index < 0) {
            return false;
        }
        if (tabs.get(index).enabled()) {
            selected = index;
        }
        return true;
    }

    /**
     * Draws the strip and returns the design-space y coordinate of its bottom
     * edge, so callers can stack content directly underneath.
     */
    public int draw(GuiGraphics graphics, Font font, int accent) {
        if (tabs.isEmpty()) {
            return y + height;
        }
        int tabWidth = tabWidth();
        // Baseline rail: visually ties the strip together even with one tab.
        graphics.fill(x, y + height - 1, x + width, y + height, 0x552B4056);
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            int left = x + i * (tabWidth + gap);
            boolean active = i == selected;
            boolean usable = tab.enabled();
            int fill = active ? Ui.blend(Ui.PANEL_3, accent, 0.26F)
                    : usable ? Ui.PANEL_2 : 0xFF0F1822;
            int border = active ? accent : usable ? Ui.BORDER : 0xFF25303B;
            graphics.fill(left, y, left + tabWidth, y + height, fill);
            graphics.renderOutline(left, y, tabWidth, height, border);
            if (active) {
                graphics.fill(left, y, left + tabWidth, y + 2, accent);
                graphics.fill(left, y + height - 2, left + tabWidth, y + height - 1,
                        (accent & 0x00FFFFFF) | 0x66000000);
            }
            Component label = tab.label();
            int badgeWidth = tab.badge() >= 0 ? font.width(String.valueOf(tab.badge())) + 8 : 0;
            int labelSpace = tabWidth - 8 - badgeWidth;
            String text = Ui.ellipsis(label.getString(), Math.max(6, labelSpace));
            int textColor = active ? Ui.TEXT : usable ? Ui.MUTED : Ui.DIM;
            if (badgeWidth > 0) {
                graphics.drawCenteredString(font, text, left + (tabWidth - badgeWidth) / 2,
                        y + (height - 8) / 2, textColor);
                int badgeX = left + tabWidth - badgeWidth;
                graphics.fill(badgeX, y + 4, badgeX + badgeWidth - 3, y + height - 4,
                        active ? (accent & 0x00FFFFFF) | 0x33000000 : 0x33101A24);
                graphics.renderOutline(badgeX, y + 4, badgeWidth - 3, height - 8,
                        active ? accent : Ui.BORDER);
                graphics.drawString(font, String.valueOf(tab.badge()), badgeX + 3, y + (height - 8) / 2,
                        active ? Ui.TEXT : Ui.DIM, false);
            } else {
                graphics.drawCenteredString(font, text, left + tabWidth / 2, y + (height - 8) / 2, textColor);
            }
        }
        return y + height;
    }
}
