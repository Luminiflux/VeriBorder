package com.veriborder.client.screen;

import com.veriborder.block.DeviceBlockEntity;
import com.veriborder.client.BetterButton;
import com.veriborder.client.ClientRegistryCache;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.client.ui.CodeListBox;
import com.veriborder.client.ui.CodeListModel;
import com.veriborder.client.ui.CountryPicker;
import com.veriborder.client.ui.Dropdown;
import com.veriborder.client.ui.FlowLayout;
import com.veriborder.item.GateBrushConfig;
import com.veriborder.network.GateBrushPacket;
import com.veriborder.network.ModNetwork;
import com.veriborder.registry.CredentialRegistryData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Gate Configuration Brush editor (single entry / single exit gate).
 *
 * <p>26.4 repair: the page is now a <b>sequential flow</b> built with
 * {@link FlowLayout}. Every row asks the cursor for its y ({@code y += height +
 * gap}) instead of hard-coding one, so a label chip can never be painted on top
 * of the next row and a conditional row only exists when it is shown. The whole
 * content band scrolls (wheel + scrollbar) when it is taller than the page, and
 * the save/cancel footer stays fixed.</p>
 *
 * <p>The two credential-code lists are real lists now ({@link CodeListModel} +
 * {@link CodeListBox}): each code is shown with its own delete button, the
 * count is in the section header, and the window scrolls inside a fixed block.</p>
 *
 * <p>The two country whitelists are floating pickers ({@link CountryPicker}):
 * the button shows “selected: N”, the layer is painted above every widget, has
 * search, one-click toggles and its own scrollbar, and the registry codes are
 * stored under the same payload keys as the dual-clearance screen
 * ({@code entryCountryCodes} / {@code exitCountryCodes}).</p>
 */
public class GateConfigBrushScreen extends VeriScreen {
    private static final int WIDTH = 520;
    private static final int HEIGHT = 570;
    /** Design-space top of the scrollable content (below the header). */
    private static final int CONTENT_TOP = 44;
    /** Fixed footer band: save / cancel are never scrolled away. */
    private static final int FOOTER_HEIGHT = 44;
    private static final int GAP = 6;
    private static final int CODE_ROWS = 3;
    /** Height of the label chip drawn above an EditBox. */
    private static final int LABEL = 12;
    /** EditBox height (see {@code VeriScreen#addField}). */
    private static final int FIELD = 14;

    private final CodeListModel gateCodes = new CodeListModel(CODE_ROWS);
    private final CodeListModel exitCodes = new CodeListModel(CODE_ROWS);
    /** Entry-stage visa-free issuing-country whitelist (registry codes). */
    private final List<String> entryCountryCodes = new ArrayList<>();
    /** Exit-stage allowed issuing-country whitelist (registry codes). */
    private final List<String> exitCountryCodes = new ArrayList<>();
    private final List<Anchor> anchors = new ArrayList<>();

    private FlowLayout layout;
    private Dropdown modeDropdown;
    private CountryPicker entryPicker;
    private CountryPicker exitPicker;
    private CodeListBox gateCodeBox;
    private CodeListBox exitCodeBox;

    private EditBox codeField;
    private EditBox exitCodeField;
    private EditBox overstayDaysField;
    private EditBox portField;
    private EditBox countryField;
    private EditBox officerField;

    private String gateMode;
    private boolean autoStamp;
    private int overstayDays;
    private String port;
    private String country;
    private String officer;

    // Layout-space row tops (paint with paint(y) to apply the scroll offset).
    private int hintRowY;
    private int modeRowY;
    private int entryTitleY;
    private int entryInputY;
    private int entryListY;
    private int entryButtonsY;
    private int entryPickerY;
    private int exitTitleY;
    private int exitInputY;
    private int exitListY;
    private int exitButtonsY;
    private int exitPickerY;
    private int behaviorTitleY;
    private int behaviorRowY;
    private int behaviorHintY;
    private int toleranceY;
    private int identityTitleY;
    private int identityRowY;

    private double mouseUiX = -1;
    private double mouseUiY = -1;

    /** One widget plus the layout y it was created at (scroll shifts it). */
    private record Anchor(AbstractWidget widget, int baseY) {
    }

    public GateConfigBrushScreen(ItemStack stack) {
        super(Component.translatable("screen.veriborder.gate_brush"), WIDTH, HEIGHT);
        CompoundTag config = GateBrushConfig.read(stack);
        this.gateMode = GateBrushConfig.normalizeMode(config.getString("gateMode"));
        this.autoStamp = config.getBoolean("autoStamp");
        this.overstayDays = config.contains("overstayDays")
                ? GateBrushConfig.clampOverstay(config.getInt("overstayDays"))
                : DeviceBlockEntity.DEFAULT_OVERSTAY_DAYS;
        this.port = config.getString("port");
        this.country = config.getString("country");
        this.officer = config.getString("officer");
        this.accent = Ui.CYAN;
        this.gateCodes.seed(GateBrushConfig.codes(config));
        this.exitCodes.seed(GateBrushConfig.exitCodes(config));
        this.entryCountryCodes.addAll(GateBrushConfig.entryCountryCodes(config));
        this.exitCountryCodes.addAll(GateBrushConfig.exitCountryCodes(config));
    }

    @Override
    protected void build() {
        // A brush opened before the registry snapshot arrived asks for it instead
        // of showing an empty whitelist.
        ClientRegistryCache.requestIfEmpty();
        List<CredentialRegistryData.Country> countries = ClientRegistryCache.countries();
        int x = left + 24;
        int width = WIDTH - 48;
        this.anchors.clear();
        this.layout = FlowLayout.at(x, top + CONTENT_TOP, width, GAP);

        // ---- inspection mode (dropdown, 3 options) ---------------------------
        hintRowY = layout.row(10);
        modeRowY = layout.row(LABEL + 18, 8);
        List<Dropdown.Option> modes = new ArrayList<>();
        for (String mode : GateBrushConfig.MODES) {
            modes.add(new Dropdown.Option(mode,
                    Component.translatable(GateBrushConfig.modeKey(mode)).getString()));
        }
        modeDropdown = addDropdown(Dropdown.at(x, modeRowY + LABEL, width)
                .labelKey("field.veriborder.gate_mode")
                .options(modes)
                .selected(gateMode)
                .panelBottom(top + HEIGHT - FOOTER_HEIGHT)
                .tooltip("screen.veriborder.gate_brush.mode_hint")
                .onChange(id -> gateMode = GateBrushConfig.normalizeMode(id)));

        // ---- entry stage: codes + visa-free country whitelist ----------------
        entryTitleY = layout.row(12);
        entryInputY = layout.row(LABEL + FIELD);
        codeField = anchored(addField(x, entryInputY + LABEL, width - 96,
                "field.veriborder.entry_codes", "", "hint.veriborder.code_batch"));
        codeField.setMaxLength(512);
        codeField.setFilter(GateConfigBrushScreen::codeBatchInput);
        button(x + width - 88, entryInputY + LABEL, 88, 18,
                Component.translatable("button.veriborder.add"), pressed -> addGateCode(), Ui.GREEN);
        entryListY = layout.row(gateCodes.height());
        gateCodeBox = new CodeListBox(x, entryListY, width, gateCodes,
                Component.translatable("screen.veriborder.gate_brush.codes_none"));
        entryButtonsY = layout.row(18);
        button(x, entryButtonsY, 120, 18, Component.translatable("button.veriborder.remove_last"),
                pressed -> gateCodes.removeLast(), Ui.AMBER);
        button(x + 130, entryButtonsY, 100, 18, Component.translatable("button.veriborder.clear_all"),
                pressed -> gateCodes.clear(), Ui.RED);
        entryPickerY = layout.row(CountryPicker.BUTTON_HEIGHT);
        entryPicker = CountryPicker.at(font, x, entryPickerY, width, entryCountryCodes,
                        "checklist.veriborder.entry_whitelist")
                .countries(countries)
                .bounds(top + CONTENT_TOP, top + HEIGHT - FOOTER_HEIGHT);

        // ---- exit stage: dedicated exit codes + its own whitelist ------------
        exitTitleY = layout.row(12);
        exitInputY = layout.row(LABEL + FIELD);
        exitCodeField = anchored(addField(x, exitInputY + LABEL, width - 96,
                "field.veriborder.exit_codes", "", "hint.veriborder.code_batch"));
        exitCodeField.setMaxLength(512);
        exitCodeField.setFilter(GateConfigBrushScreen::codeBatchInput);
        button(x + width - 88, exitInputY + LABEL, 88, 18,
                Component.translatable("button.veriborder.add"), pressed -> addExitCode(), Ui.GREEN);
        exitListY = layout.row(exitCodes.height());
        exitCodeBox = new CodeListBox(x, exitListY, width, exitCodes,
                Component.translatable("screen.veriborder.gate_brush.exit_codes_none"));
        exitButtonsY = layout.row(18);
        button(x, exitButtonsY, 120, 18, Component.translatable("button.veriborder.remove_last"),
                pressed -> exitCodes.removeLast(), Ui.AMBER);
        button(x + 130, exitButtonsY, 100, 18, Component.translatable("button.veriborder.clear_all"),
                pressed -> exitCodes.clear(), Ui.RED);
        exitPickerY = layout.row(CountryPicker.BUTTON_HEIGHT);
        exitPicker = CountryPicker.at(font, x, exitPickerY, width, exitCountryCodes,
                        "checklist.veriborder.exit_whitelist")
                .countries(countries)
                .bounds(top + CONTENT_TOP, top + HEIGHT - FOOTER_HEIGHT);

        // ---- gate behaviour ---------------------------------------------------
        behaviorTitleY = layout.row(12);
        behaviorRowY = layout.row(LABEL + 18);
        anchored(addRenderableWidget(new BetterButton(x, behaviorRowY + LABEL, 190, 18,
                autoStampLabel(), pressed -> {
                    autoStamp = !autoStamp;
                    pressed.setMessage(autoStampLabel());
                }, accent, true)));
        overstayDaysField = anchored(addField(x + 200, behaviorRowY + LABEL, width - 200,
                "field.veriborder.overstay_days", String.valueOf(overstayDays), "hint.veriborder.days"));
        overstayDaysField.setMaxLength(4);
        overstayDaysField.setFilter(value -> value.length() <= 4
                && value.chars().allMatch(character -> character >= '0' && character <= '9'));
        behaviorHintY = layout.row(10);
        toleranceY = layout.row(10);

        // ---- checkpoint identity (three compact columns) ----------------------
        identityTitleY = layout.row(12);
        identityRowY = layout.row(LABEL + FIELD);
        int columnWidth = (width - 12) / 3;
        portField = anchored(addField(x, identityRowY + LABEL, columnWidth,
                "field.veriborder.port", port));
        countryField = anchored(addField(x + columnWidth + 6, identityRowY + LABEL, columnWidth,
                "field.veriborder.country", country));
        officerField = anchored(addField(x + (columnWidth + 6) * 2, identityRowY + LABEL, columnWidth,
                "field.veriborder.officer", officer));

        // ---- fixed footer (never scrolls, painted above the content) ---------
        addFixedWidget(new BetterButton(x, top + HEIGHT - 30, 120, 20,
                Component.translatable("button.veriborder.save"), pressed -> save(), accent));
        addFixedWidget(new BetterButton(x + 130, top + HEIGHT - 30, 100, 20,
                Component.translatable("button.veriborder.cancel"), pressed -> onClose(), Ui.MUTED, true));

        layout.viewportHeight(HEIGHT - CONTENT_TOP - FOOTER_HEIGHT);
        setContentClip(top + CONTENT_TOP, top + HEIGHT - FOOTER_HEIGHT);
        applyScroll();
    }

    /** Registers a widget together with its scroll anchor. */
    private <T extends AbstractWidget> T anchored(T widget) {
        anchors.add(new Anchor(widget, widget.getY()));
        return widget;
    }

    /** Content button helper (subtle styling, added and anchored). */
    private BetterButton button(int x, int y, int width, int height, Component label,
                                Button.OnPress onPress, int color) {
        return anchored(addRenderableWidget(new BetterButton(x, y, width, height, label, onPress, color, true)));
    }

    /** Shifts every anchored widget / component by the current scroll offset. */
    private void applyScroll() {
        if (layout == null) {
            return;
        }
        int offset = layout.offset();
        for (Anchor anchor : anchors) {
            anchor.widget().setY(anchor.baseY() - offset);
        }
        if (modeDropdown != null) {
            modeDropdown.setY(modeRowY + LABEL - offset);
        }
        if (gateCodeBox != null) {
            gateCodeBox.moveTo(left + 24, entryListY - offset);
        }
        if (exitCodeBox != null) {
            exitCodeBox.moveTo(left + 24, exitListY - offset);
        }
        if (entryPicker != null) {
            entryPicker.moveTo(left + 24, entryPickerY - offset);
        }
        if (exitPicker != null) {
            exitPicker.moveTo(left + 24, exitPickerY - offset);
        }
    }

    private int paint(int layoutY) {
        return layout == null ? layoutY : layout.paintY(layoutY);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        if (handlePicker(entryPicker, uiX, uiY, button)) {
            return true;
        }
        if (handlePicker(exitPicker, uiX, uiY, button)) {
            return true;
        }
        if (gateCodeBox != null && gateCodeBox.mouseClicked(uiX, uiY)) {
            return true;
        }
        if (exitCodeBox != null && exitCodeBox.mouseClicked(uiX, uiY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** The whitelist popup is topmost: opening it dismisses every other popup. */
    private boolean handlePicker(CountryPicker picker, double uiX, double uiY, int button) {
        if (picker == null) {
            return false;
        }
        boolean wasOpen = picker.isOpen();
        if (!picker.mouseClicked(uiX, uiY, button)) {
            return false;
        }
        if (!wasOpen && picker.isOpen()) {
            closeDropdownsExcept(null);
            closePickersExcept(picker);
        }
        return true;
    }

    private void closePickersExcept(CountryPicker keep) {
        if (entryPicker != null && entryPicker != keep) {
            entryPicker.close();
        }
        if (exitPicker != null && exitPicker != keep) {
            exitPicker.close();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        if (dropdownScroll(uiX, uiY, delta)) {
            return true;
        }
        if (entryPicker != null && entryPicker.mouseScrolled(uiX, uiY, delta)) {
            return true;
        }
        if (exitPicker != null && exitPicker.mouseScrolled(uiX, uiY, delta)) {
            return true;
        }
        if (gateCodeBox != null && gateCodeBox.mouseScrolled(uiX, uiY, delta)) {
            return true;
        }
        if (exitCodeBox != null && exitCodeBox.mouseScrolled(uiX, uiY, delta)) {
            return true;
        }
        if (layout != null && layout.scrollBy(delta)) {
            // The page moved: a floating layer would drift away from its button.
            closePickersExcept(null);
            applyScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (entryPicker != null && entryPicker.isOpen()) {
            return entryPicker.keyPressed(keyCode, scanCode, modifiers);
        }
        if (exitPicker != null && exitPicker.isOpen()) {
            return exitPicker.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (entryPicker != null && entryPicker.isOpen()) {
            return entryPicker.charTyped(codePoint, modifiers);
        }
        if (exitPicker != null && exitPicker.isOpen()) {
            return exitPicker.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    // ------------------------------------------------------------------- draw

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.mouseUiX = toUiX(mouseX);
        this.mouseUiY = toUiY(mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        boolean anyOpen = (entryPicker != null && entryPicker.isOpen())
                || (exitPicker != null && exitPicker.isOpen());
        if (anyOpen) {
            // Floating whitelists are painted after every widget, so no control
            // below them can ever cover the layer.
            overlay(graphics, canvas -> {
                if (entryPicker != null && entryPicker.isOpen()) {
                    entryPicker.drawPanel(canvas, accent, mouseUiX, mouseUiY);
                }
                if (exitPicker != null && exitPicker.isOpen()) {
                    exitPicker.drawPanel(canvas, accent, mouseUiX, mouseUiY);
                }
            });
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, Component.translatable("screen.veriborder.gate_brush").getString(),
                Component.translatable("screen.veriborder.gate_brush.subtitle").getString());
        int x = left + 24;
        int width = WIDTH - 48;
        drawScrollbar(graphics);
        withContentClip(graphics, () -> {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.gate_brush.hint"),
                    x, paint(hintRowY), width, Ui.MUTED);

            // Entry stage.
            sectionHeader(graphics, "screen.veriborder.gate_brush.entry_title",
                    Component.translatable("screen.veriborder.gate_brush.codes", gateCodes.size()).getString(),
                    gateCodes.errorKey(), paint(entryTitleY));
            if (gateCodeBox != null) {
                gateCodeBox.draw(graphics, font, accent, mouseUiX, mouseUiY);
            }
            if (entryPicker != null) {
                entryPicker.drawButton(graphics, accent, mouseUiX, mouseUiY);
            }

            // Exit stage.
            sectionHeader(graphics, "screen.veriborder.gate_brush.exit_title",
                    Component.translatable("screen.veriborder.gate_brush.exit_codes", exitCodes.size()).getString(),
                    exitCodes.errorKey(), paint(exitTitleY));
            if (exitCodeBox != null) {
                exitCodeBox.draw(graphics, font, accent, mouseUiX, mouseUiY);
            }
            if (exitPicker != null) {
                exitPicker.drawButton(graphics, accent, mouseUiX, mouseUiY);
            }

            // Gate behaviour.
            Ui.clipped(graphics, Component.translatable("field.veriborder.gate_behavior"),
                    x, paint(behaviorTitleY), width, accent);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.gate_brush.behavior_hint"),
                    x, paint(behaviorHintY), width, Ui.DIM);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.exit_tolerance"),
                    x, paint(toleranceY), width - 12, Ui.AMBER);

            // Checkpoint identity.
            Ui.clipped(graphics, Component.translatable("field.veriborder.checkpoint_identity"),
                    x, paint(identityTitleY), width, accent);
        });

        // Fixed footer chrome.
        graphics.fill(x, top + HEIGHT - 36, x + width, top + HEIGHT - 35, Ui.BORDER);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.gate_brush.op_hint"),
                x, top + HEIGHT - 46, width, Ui.AMBER);
    }

    /** Section title + count on the left, validation error on the right. */
    private void sectionHeader(GuiGraphics graphics, String titleKey, String count, String errorKey, int y) {
        int width = WIDTH - 48;
        String text = Component.translatable(titleKey).getString() + "  ·  " + count;
        if (errorKey == null || errorKey.isBlank()) {
            Ui.clipped(graphics, text, left + 24, y, width, accent);
            return;
        }
        String error = Component.translatable(errorKey).getString();
        int errorWidth = Math.min(font.width(error), width / 2);
        Ui.clipped(graphics, text, left + 24, y, width - errorWidth - 8, accent);
        graphics.drawString(font, Ui.ellipsis(error, errorWidth),
                left + 24 + width - errorWidth, y, Ui.RED, false);
    }

    /** Right-hand scrollbar of the content band (only when the page overflows). */
    private void drawScrollbar(GuiGraphics graphics) {
        if (layout == null || layout.scrollRange() <= 0) {
            return;
        }
        int trackX = left + WIDTH - 10;
        int trackTop = top + CONTENT_TOP;
        int trackHeight = HEIGHT - CONTENT_TOP - FOOTER_HEIGHT;
        graphics.fill(trackX, trackTop, trackX + 3, trackTop + trackHeight, 0x332B4056);
        int thumbHeight = Math.max(12, trackHeight * trackHeight / Math.max(1, layout.contentHeight()));
        int thumbTop = trackTop + (trackHeight - thumbHeight) * layout.offset()
                / Math.max(1, layout.scrollRange());
        graphics.fill(trackX, thumbTop, trackX + 3, thumbTop + thumbHeight, accent);
    }

    // ------------------------------------------------------------------ model

    /** Batch gate-code field: digits plus the three accepted separators. */
    private static boolean codeBatchInput(String value) {
        if (value == null || value.length() > 512) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if ((character < '0' || character > '9') && character != ','
                    && character != ';' && character != ' ') {
                return false;
            }
        }
        return true;
    }

    private void addGateCode() {
        if (gateCodes.addBatch(codeField == null ? "" : codeField.getValue()) > 0 && codeField != null) {
            codeField.setValue("");
        }
    }

    private void addExitCode() {
        if (exitCodes.addBatch(exitCodeField == null ? "" : exitCodeField.getValue()) > 0
                && exitCodeField != null) {
            exitCodeField.setValue("");
        }
    }

    private Component autoStampLabel() {
        return Component.translatable("screen.veriborder.config.auto_stamp."
                + (autoStamp ? "on" : "off"));
    }

    private int overstayDays() {
        if (overstayDaysField == null) {
            return overstayDays;
        }
        try {
            return GateBrushConfig.clampOverstay(Integer.parseInt(overstayDaysField.getValue().trim()));
        } catch (NumberFormatException ignored) {
            return overstayDays;
        }
    }

    private void save() {
        CompoundTag data = new CompoundTag();
        data.putString("gateMode", gateMode);
        data.put("gateCodes", codesTag(gateCodes.codes()));
        data.put(GateBrushConfig.EXIT_CODES, codesTag(exitCodes.codes()));
        data.put(GateBrushConfig.ENTRY_COUNTRY_CODES, codesTag(entryCountryCodes));
        data.put(GateBrushConfig.EXIT_COUNTRY_CODES, codesTag(exitCountryCodes));
        data.putBoolean("autoStamp", autoStamp);
        data.putInt("overstayDays", overstayDays());
        data.putString("port", portField == null ? port : portField.getValue());
        data.putString("country", countryField == null ? country : countryField.getValue());
        data.putString("officer", officerField == null ? officer : officerField.getValue());
        ModNetwork.CHANNEL.sendToServer(new GateBrushPacket(data));
        onClose();
    }

    private static ListTag codesTag(List<String> codes) {
        ListTag tag = new ListTag();
        for (String code : codes) {
            tag.add(StringTag.valueOf(code));
        }
        return tag;
    }
}
