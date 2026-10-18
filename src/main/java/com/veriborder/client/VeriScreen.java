package com.veriborder.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Common chrome for every VeriBorder screen.
 *
 * <p>The layout is authored in a fixed design space ({@code imageWidth x imageHeight}).
 * When the real GUI viewport is smaller than that (for example 854x480 at GUI scale 2,
 * where the viewport is only 427x240), the whole screen is uniformly scaled down around
 * the viewport centre instead of overflowing off-screen. Mouse coordinates are mapped
 * back into design space before widgets and custom hit boxes see them, so editing,
 * clicking and scrolling keep working at any scale.</p>
 */
public abstract class VeriScreen extends Screen {
    protected final int imageWidth;
    protected final int imageHeight;
    protected int left;
    protected int top;
    protected final List<LabeledField> labeledFields = new ArrayList<>();
    protected int accent = Ui.CYAN;
    protected float uiScale = 1.0F;
    protected float uiOffsetX = 0.0F;
    protected float uiOffsetY = 0.0F;

    protected record LabeledField(EditBox box, Component label, Component hint) {
    }

    protected VeriScreen(Component title, int imageWidth, int imageHeight) {
        super(title);
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
    }

    @Override
    protected void init() {
        // Uniform downscale so the fixed design panels always fit the viewport
        // (e.g. 520x360 / 482x310 inside a 427x240 viewport at 854x480 scale 2).
        this.uiScale = scaleFor(this.width, this.height, imageWidth, imageHeight);
        this.uiOffsetX = (this.width - imageWidth * uiScale) / 2.0F;
        this.uiOffsetY = (this.height - imageHeight * uiScale) / 2.0F;
        // Controls are authored relative to the panel origin, which the pose
        // translation above places at uiOffsetX / uiOffsetY on screen.
        this.left = 0;
        this.top = 0;
        this.labeledFields.clear();
        this.build();
    }

    private static float scaleFor(int screenWidth, int screenHeight, int designWidth, int designHeight) {
        float fitX = (screenWidth - 8.0F) / designWidth;
        float fitY = (screenHeight - 8.0F) / designHeight;
        float scale = Math.min(1.0F, Math.min(fitX, fitY));
        return Math.max(0.5F, scale);
    }

    protected abstract void build();

    /** Design-space -> screen-space conversion for mouse input. */
    protected final double toUiX(double mouseX) {
        return (mouseX - uiOffsetX) / uiScale;
    }

    protected final double toUiY(double mouseY) {
        return (mouseY - uiOffsetY) / uiScale;
    }

    /** Extra right-hand padding for header text when the screen adds header buttons. */
    protected int headerRightInset() {
        return 0;
    }

    protected void header(GuiGraphics graphics, String title, String subtitle) {
        graphics.fillGradient(left, top, left + imageWidth, top + 38, 0xFF162434, 0xFF0B1520);
        graphics.fill(left, top + 38, left + imageWidth, top + 39, accent);
        graphics.fill(left, top + 39, left + imageWidth, top + 40, 0x33000000);
        graphics.fill(left + 12, top + 10, left + 15, top + 28, accent);
        graphics.fill(left + 15, top + 10, left + 16, top + 28, 0x55FFFFFF);
        int textWidth = imageWidth - 34 - headerRightInset();
        graphics.drawString(font, Ui.ellipsis(title == null ? "" : title, textWidth),
                left + 22, top + 11, Ui.TEXT, false);
        if (subtitle != null && !subtitle.isBlank()) {
            graphics.drawString(font, Ui.ellipsis(subtitle, textWidth),
                    left + 22, top + 24, Ui.MUTED, false);
        }
    }

    protected EditBox addField(int x, int y, int width, String labelKey, String value) {
        return addField(x, y, width, labelKey, value, null);
    }

    /**
     * Adds an edit box with a readable label chip. {@code hint} is either an existing
     * translation key (starting with {@code hint.}) or a literal string such as
     * {@code YYYY-MM-DD}, {@code #RRGGBB} or {@code 0 = unlimited}.
     */
    protected EditBox addField(int x, int y, int width, String labelKey, String value, String hint) {
        EditBox box = new EditBox(this.font, x, y, width, 14, Component.translatable(labelKey));
        box.setMaxLength(96);
        box.setValue(value == null ? "" : value);
        box.setBordered(false);
        box.setTextColor(0xFFE9F3FF);
        box.setTextColorUneditable(0xFF8096AC);
        addRenderableWidget(box);
        Component label = Component.translatable(labelKey);
        Component hintComponent = null;
        if (hint != null && !hint.isBlank()) {
            hintComponent = hint.startsWith("hint.") ? Component.translatable(hint) : Component.literal(hint);
        }
        labeledFields.add(new LabeledField(box, label, hintComponent));
        return box;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(uiOffsetX, uiOffsetY, 0.0F);
            graphics.pose().scale(uiScale, uiScale, 1.0F);
            // 1.20.1 does not call renderBackground from Screen#render; every screen
            // is responsible for drawing its own backdrop. Without this call the
            // panel, header and labels never appeared, so the GUI looked like it
            // had not opened at all.
            Ui.setScreenTransform(uiOffsetX, uiOffsetY, uiScale);
            this.renderBackground(graphics);
            super.render(graphics, (int) toUiX(mouseX), (int) toUiY(mouseY), partialTick);
        } finally {
            graphics.pose().popPose();
            Ui.clearScreenTransform();
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        // The dim covers the entire real viewport even after the pose transform.
        int dimLeft = (int) Math.floor(-uiOffsetX / uiScale) - 1;
        int dimTop = (int) Math.floor(-uiOffsetY / uiScale) - 1;
        int dimRight = (int) Math.ceil((this.width - uiOffsetX) / uiScale) + 1;
        int dimBottom = (int) Math.ceil((this.height - uiOffsetY) / uiScale) + 1;
        // Translucent full-screen dim: the world stays faintly visible around the
        // opaque main panel, but the UI itself is never floating bare widgets.
        graphics.fill(dimLeft, dimTop, dimRight, dimBottom, 0xD8050A10);
        graphics.fill(left - 5, top - 5, left + imageWidth + 5, top + imageHeight + 5, 0xFF05090E);
        graphics.renderOutline(left - 5, top - 5, imageWidth + 10, imageHeight + 10, 0xFF1E3247);
        Ui.panel(graphics, left, top, imageWidth, imageHeight);
        graphics.fill(left + 1, top + 1, left + imageWidth - 1, top + 2, 0xFF3A5570);
        graphics.fill(left + 1, top + imageHeight - 2, left + imageWidth - 1, top + imageHeight - 1, 0x552FC6B7);
        for (LabeledField field : labeledFields) {
            if (field.box().visible) {
                drawFieldChrome(graphics, field);
            }
        }
    }

    private void drawFieldChrome(GuiGraphics graphics, LabeledField field) {
        EditBox box = field.box();
        int x = box.getX();
        int y = box.getY();
        int width = box.getWidth();
        boolean focused = box.isFocused();
        int border = focused ? accent : Ui.BORDER;
        // Input well: dark backing makes the value readable on the opaque panel.
        graphics.fill(x - 3, y - 2, x + width + 3, y + 15, focused ? 0xFF0E1B28 : 0xFF091119);
        graphics.renderOutline(x - 3, y - 2, width + 6, 17, border);
        graphics.fill(x - 3, y + 14, x + width + 3, y + 15, focused ? accent : 0xFF223448);
        // Label chip: accent bar + dark backing + bright label text, so every
        // field states exactly what has to be entered.
        int labelWidth = font.width(field.label());
        int hintWidth = field.hint() == null ? 0 : font.width(field.hint()) + 5;
        int chipWidth = Math.min(width + 3, labelWidth + hintWidth + 12);
        int chipTop = y - 12;
        graphics.fill(x - 2, chipTop, x - 2 + chipWidth, chipTop + 10, 0xEE0A131D);
        graphics.fill(x - 2, chipTop, x, chipTop + 10, accent);
        graphics.fill(x - 2, chipTop, x - 2 + chipWidth, chipTop + 1, 0x66FFFFFF);
        String labelText = font.plainSubstrByWidth(field.label().getString(), chipWidth - 6);
        int labelTextWidth = font.width(labelText);
        graphics.drawString(font, labelText, x + 3, chipTop + 1, Ui.TEXT, false);
        if (field.hint() != null && hintWidth > 0) {
            int hintX = x + 3 + labelTextWidth + 5;
            int maxHint = x - 2 + chipWidth - hintX - 2;
            if (maxHint > 8) {
                graphics.drawString(font, font.plainSubstrByWidth(field.hint().getString(), maxHint),
                        hintX, chipTop + 1, Ui.DIM, false);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return super.mouseClicked(toUiX(mouseX), toUiY(mouseY), button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return super.mouseReleased(toUiX(mouseX), toUiY(mouseY), button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return super.mouseDragged(toUiX(mouseX), toUiY(mouseY), button, dragX / uiScale, dragY / uiScale);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(toUiX(mouseX), toUiY(mouseY));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return super.mouseScrolled(toUiX(mouseX), toUiY(mouseY), delta);
    }
}
