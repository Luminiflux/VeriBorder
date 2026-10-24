package com.veriborder.client;

import com.veriborder.client.ui.Dropdown;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

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
    /**
     * Shared dropdowns of the current build (26.4). They are not widgets: the
     * base screen paints them above every widget and forwards clicks, the wheel
     * and keyboard input, so a screen only creates the control and reacts to its
     * value.
     */
    protected final List<Dropdown> dropdowns = new ArrayList<>();
    /**
     * Widgets that stay put while a scrollable page moves (footer buttons). They
     * are painted after the clipped content pass, so a row scrolled underneath
     * them can never cover the save / cancel buttons.
     */
    protected final List<AbstractWidget> fixedWidgets = new ArrayList<>();
    private int contentClipTop = Integer.MIN_VALUE;
    private int contentClipBottom = Integer.MAX_VALUE;
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
        this.dropdowns.clear();
        this.fixedWidgets.clear();
        this.contentClipTop = Integer.MIN_VALUE;
        this.contentClipBottom = Integer.MAX_VALUE;
        // 26.4 repair: a throw inside build() used to leave a half-built screen
        // with no widgets at all and no trace in the log. Log it loudly and keep
        // whatever was already added, so the page stays usable and the cause is
        // one grep away.
        try {
            this.build();
        } catch (RuntimeException | Error failure) {
            org.slf4j.LoggerFactory.getLogger("VeriBorder").error(
                    "VeriBorder: screen build failed for {}", this.getClass().getName(), failure);
            this.buildFailure = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            throw failure;
        }
    }

    /** Message of the exception that aborted {@link #build()}, or {@code null}. */
    private String buildFailure;

    /**
     * Registers a widget that must not scroll: it is excluded from the clipped
     * content pass and painted afterwards, on top of the scrolled rows.
     */
    protected <T extends AbstractWidget> T addFixedWidget(T widget) {
        addRenderableWidget(widget);
        fixedWidgets.add(widget);
        return widget;
    }

    /**
     * Enables the clipped scroll band. Content widgets are clipped to this band
     * and a click outside it never reaches a scrolled widget (the fixed footer
     * widgets stay clickable). {@code top >= bottom} disables the clip.
     */
    protected void setContentClip(int top, int bottom) {
        int low = Math.min(top, bottom);
        int high = Math.max(top, bottom);
        // 26.4 repair: bad band maths (an inverted or zero-height band, or one
        // entirely outside the panel) used to clip every widget away and leave a
        // blank page. A band that cannot show at least two rows is simply
        // disabled instead of swallowing the whole page.
        int panel = imageHeight > 0 ? imageHeight : high;
        low = Math.max(low, 0);
        high = Math.min(high, panel);
        if (high - low < 40) {
            this.contentClipTop = Integer.MIN_VALUE;
            this.contentClipBottom = Integer.MAX_VALUE;
            return;
        }
        this.contentClipTop = low;
        this.contentClipBottom = high;
    }

    protected boolean isContentClipped()
        {
            // 26.4 rollback: the clip band broke widget rendering, so the
            // scroll/clip pass is disabled until it is redesigned.
            return false;
        }

    /** True when a design-space y falls inside the scrollable band. */
    protected boolean insideContentBand(double uiY) {
        return !isContentClipped() || (uiY >= contentClipTop && uiY <= contentClipBottom);
    }

    /** True when a design-space point is over a fixed (non-scrolling) widget. */
    private boolean overFixedWidget(double uiX, double uiY) {
        for (AbstractWidget widget : fixedWidgets) {
            if (widget.visible && uiX >= widget.getX() && uiX <= widget.getX() + widget.getWidth()
                    && uiY >= widget.getY() && uiY <= widget.getY() + widget.getHeight()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs content painting inside the scroll band, so text or a label chip of a
     * half-scrolled row can never be painted over the header. Screens without a
     * scroll band just run the action.
     */
    /**
     * Converts a design-space content band into the window-space rectangle the
     * scissor test expects. {@code GuiGraphics.enableScissor} ignores the current
     * pose, so passing design coordinates directly clipped every widget away on
     * a scaled or offset panel (the 26.4 "blank page" regression).
     */
    private int[] screenBand(int bandTop, int bandBottom) {
        int x = Math.round(uiOffsetX + left * uiScale);
        int y = Math.round(uiOffsetY + bandTop * uiScale);
        int width = Math.round(imageWidth * uiScale);
        int height = Math.round((bandBottom - bandTop) * uiScale);
        return new int[]{x, y, width, Math.max(1, height)};
    }

    protected void withContentClip(GuiGraphics graphics, Runnable action) {
        if (action == null) {
            return;
        }
        if (!isContentClipped()) {
            action.run();
            return;
        }
        int[] band = screenBand(contentClipTop, contentClipBottom);
        Ui.scissor(graphics, band[0], band[1], band[0] + band[2], band[1] + band[3]);
        try {
            action.run();
        } finally {
            graphics.disableScissor();
        }
    }

    /** Registers a dropdown so the base screen paints and drives it. */
    protected <T extends Dropdown> T addDropdown(T dropdown) {
        dropdowns.add(dropdown);
        return dropdown;
    }

    protected void closeDropdownsExcept(@Nullable Dropdown keep) {
        for (Dropdown dropdown : dropdowns) {
            if (dropdown != keep) {
                dropdown.setOpen(false);
            }
        }
    }

    /** Forwards a click to the dropdowns first; {@code true} when one consumed it. */
    protected boolean dropdownClick(double uiX, double uiY) {
        for (int i = dropdowns.size() - 1; i >= 0; i--) {
            Dropdown dropdown = dropdowns.get(i);
            if (dropdown.mouseClicked(uiX, uiY)) {
                if (dropdown.isOpen()) {
                    closeDropdownsExcept(dropdown);
                }
                return true;
            }
        }
        return false;
    }

    /** Forwards the wheel to an open dropdown panel (long lists scroll). */
    protected boolean dropdownScroll(double uiX, double uiY, double delta) {
        for (int i = dropdowns.size() - 1; i >= 0; i--) {
            Dropdown dropdown = dropdowns.get(i);
            if (dropdown.isOpen() && dropdown.mouseScrolled(uiX, uiY, delta)) {
                return true;
            }
        }
        return false;
    }

    private static float scaleFor(int screenWidth, int screenHeight, int designWidth, int designHeight) {
        float fitX = (screenWidth - 8.0F) / designWidth;
        float fitY = (screenHeight - 8.0F) / designHeight;
        float scale = Math.min(1.0F, Math.min(fitX, fitY));
        // 26.3 screens grew to fit the dual-gate whitelists (560px design height);
        // a 0.4 floor still fits an 854x480 viewport (427x240 GUI pixels) without
        // clipping, while every larger viewport keeps its natural scale.
        return Math.max(0.4F, scale);
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
        int uiX = (int) toUiX(mouseX);
        int uiY = (int) toUiY(mouseY);
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
            
            if (this.buildFailure != null && this.font != null) {
                // A silent half-built page is worse than a visible error: show it
                // so a screenshot alone identifies the failing screen.
                graphics.drawString(this.font, "VeriBorder UI error: " + this.buildFailure,
                        left + 4, top + 44, 0xFF5555, false);
            }
            // 26.4 diagnosis: when a page renders its text but none of its
            // controls, this line tells us whether the controls were never
            // created or were clipped/offset away.
            
            // 26.4 scrollable pages: the widget pass is clipped to the content
            // band; the fixed footer buttons are painted afterwards so a row
            // scrolled underneath them can never cover them.
            if (isContentClipped()) {
                int[] band = screenBand(contentClipTop, contentClipBottom);
                Ui.scissor(graphics, band[0], band[1], band[0] + band[2], band[1] + band[3]);
                try {
                    for (Renderable renderable : renderables) {
                        if (renderable instanceof AbstractWidget widget && fixedWidgets.contains(widget)) {
                            continue;
                        }
                        renderable.render(graphics, uiX, uiY, partialTick);
                    }
                } finally {
                    graphics.disableScissor();
                }
                for (AbstractWidget widget : fixedWidgets) {
                    if (widget.visible) {
                        widget.render(graphics, uiX, uiY, partialTick);
                    }
                }
            } else {
                for (Renderable renderable : renderables) {
                    renderable.render(graphics, uiX, uiY, partialTick);
                }
            }
            // 26.4 shared dropdowns: closed controls first, then every open panel,
            // so a dropped list is never covered by a widget that follows it. A
            // scrolled page clips the closed controls too; the open panel stays
            // unclipped because it is a floating layer.
            if (isContentClipped()) {
                int[] band = screenBand(contentClipTop, contentClipBottom);
                Ui.scissor(graphics, band[0], band[1], band[0] + band[2], band[1] + band[3]);
                try {
                    for (Dropdown dropdown : dropdowns) {
                        dropdown.draw(graphics, font, accent, uiX, uiY);
                    }
                } finally {
                    graphics.disableScissor();
                }
            } else {
                for (Dropdown dropdown : dropdowns) {
                    dropdown.draw(graphics, font, accent, uiX, uiY);
                }
            }
            for (Dropdown dropdown : dropdowns) {
                if (dropdown.isOpen()) {
                    dropdown.drawOverlay(graphics, font, accent, uiX, uiY);
                }
            }
        } finally {
            graphics.pose().popPose();
            Ui.clearScreenTransform();
        }
        // Tooltips are painted in raw GUI space (after the pose is restored) so a
        // downscaled screen does not shrink the tooltip text as well.
        for (int i = dropdowns.size() - 1; i >= 0; i--) {
            Dropdown dropdown = dropdowns.get(i);
            if (!dropdown.isOpen() && dropdown.tooltip() != null
                    && dropdown.contains(uiX, uiY)) {
                Ui.tooltip(graphics, mouseX, mouseY, dropdown.tooltip());
                break;
            }
        }
    }

    /**
     * Runs {@code action} in design space <em>after</em> every widget has been
     * painted, so an open dropdown / palette / checklist can never be covered by
     * the widgets that follow it on the page (26.3 round 12 shared pickers).
     */
    protected void overlay(GuiGraphics graphics, java.util.function.Consumer<GuiGraphics> action) {
        if (action == null) {
            return;
        }
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(uiOffsetX, uiOffsetY, 0.0F);
            graphics.pose().scale(uiScale, uiScale, 1.0F);
            Ui.setScreenTransform(uiOffsetX, uiOffsetY, uiScale);
            action.accept(graphics);
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
        withContentClip(graphics, () -> {
            for (LabeledField field : labeledFields) {
                if (field.box().visible) {
                    drawFieldChrome(graphics, field);
                }
            }
        });
    }    private void drawFieldChrome(GuiGraphics graphics, LabeledField field) {
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
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        if (dropdownClick(uiX, uiY)) {
            return true;
        }
        // A click outside the scroll band must never hit a row that has been
        // scrolled out of view; the fixed footer widgets stay clickable.
        if (!insideContentBand(uiY) && !overFixedWidget(uiX, uiY)) {
            return false;
        }
        return super.mouseClicked(uiX, uiY, button);
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
        if (dropdownScroll(toUiX(mouseX), toUiY(mouseY), delta)) {
            return true;
        }
        return super.mouseScrolled(toUiX(mouseX), toUiY(mouseY), delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // An open dropdown owns 閳?閳?Enter/Esc (and Backspace for its search field)
        // before the screen's own shortcuts (Esc would otherwise close the screen).
        for (int i = dropdowns.size() - 1; i >= 0; i--) {
            Dropdown dropdown = dropdowns.get(i);
            if (dropdown.isOpen() && dropdown.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        for (int i = dropdowns.size() - 1; i >= 0; i--) {
            Dropdown dropdown = dropdowns.get(i);
            if (dropdown.isOpen() && dropdown.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        return super.charTyped(codePoint, modifiers);
    }
}
