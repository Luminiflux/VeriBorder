package com.veriborder.client.screen;

import com.veriborder.client.BetterButton;
import com.veriborder.client.ClientRegistryCache;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.client.ui.CodeListBox;
import com.veriborder.client.ui.CodeListModel;
import com.veriborder.client.ui.CountryPicker;
import com.veriborder.client.ui.Dropdown;
import com.veriborder.client.ui.FlowLayout;
import com.veriborder.item.DualGateBrushConfig;
import com.veriborder.network.DeviceActionPacket;
import com.veriborder.network.DualGateBrushPacket;
import com.veriborder.network.ModNetwork;
import com.veriborder.registry.CredentialRegistryData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Dual-clearance gate brush editor (一地两检), used both for the brush and for a
 * placed dual gate (device mode).
 *
 * <p>26.4 repair: the page is a sequential flow ({@link FlowLayout}); the exit
 * stage block owns its code list and its allowed-country picker, then the entry
 * stage block owns the inspection mode, its code list and its own picker. A
 * label can never land on top of the next row again, and the Advanced identity
 * overrides are conditional rows that only exist while the fold is open (the
 * page scrolls with the wheel and a scrollbar when they do not fit).</p>
 *
 * <p>Every editable value is held in a screen field through an EditBox
 * responder, so opening / closing the fold (which rebuilds the widget list) can
 * never drop typed text.</p>
 *
 * <p>Brush mode saves onto the held {@code VeriBorderDualGateConfig} tag; device
 * mode saves the exact same schema back onto the block entity through
 * {@code save_dual_gate}, so the two entry points cannot drift apart.</p>
 */
public class DualGateBrushScreen extends VeriScreen {
    private static final int WIDTH = 520;
    private static final int HEIGHT = 560;
    private static final int CONTENT_TOP = 44;
    /** Band reserved under the content: footer hint, divider and save / cancel. */
    private static final int FOOTER_HEIGHT = 50;
    private static final int GAP = 6;
    private static final int CODE_ROWS = 3;
    private static final int LABEL = 12;
    private static final int FIELD = 14;
    /** Readable dark-green UI accent (the block accent #1F5E3A is too dark for text). */
    private static final int DUAL_ACCENT = 0xFF3FA46B;

    private final CodeListModel exitCodes = new CodeListModel(CODE_ROWS);
    private final CodeListModel entryCodes = new CodeListModel(CODE_ROWS);
    /** Entry-stage allowed issuing-country whitelist (registry codes, 3 letters). */
    private final List<String> entryCountryCodes = new ArrayList<>();
    /** Exit-stage allowed issuing-country whitelist (registry codes, 3 letters). */
    private final List<String> exitCountryCodes = new ArrayList<>();
    private final List<Anchor> anchors = new ArrayList<>();

    /** Device mode: write back to the block entity at {@code pos}; otherwise the brush. */
    private final boolean deviceMode;
    private final BlockPos pos;
    private final int slot;

    private String exitAuthority = "";
    private String exitOfficer = "";
    private String exitPort = "";
    private String entryAuthority = "";
    private String entryOfficer = "";
    private String entryPort = "";
    private String entryMode;

    private FlowLayout layout;
    private Dropdown entryModeDropdown;
    private CountryPicker exitPicker;
    private CountryPicker entryPicker;
    private CodeListBox exitCodeBox;
    private CodeListBox entryCodeBox;
    private EditBox exitCodeField;
    private EditBox entryCodeField;
    private EditBox exitAuthorityField;
    private EditBox exitOfficerField;
    private EditBox exitPortField;
    private EditBox entryAuthorityField;
    private EditBox entryOfficerField;
    private EditBox entryPortField;

    /** Advanced fold: low-frequency stage identity overrides. */
    private boolean advancedOpen;

    // Layout-space row tops.
    private int hintRowY;
    private int exitTitleY;
    private int exitInputY;
    private int exitListY;
    private int exitButtonsY;
    private int exitPickerY;
    private int entryTitleY;
    private int entryModeRowY;
    private int entryInputY;
    private int entryListY;
    private int entryButtonsY;
    private int entryPickerY;
    private int advancedButtonY;
    private int advancedHintY;
    private int opHintY;
    private int flowHintY;

    private double mouseUiX = -1;
    private double mouseUiY = -1;

    private record Anchor(AbstractWidget widget, int baseY) {
    }

    /** Brush mode: the two-stage configuration stored on the held brush. */
    public DualGateBrushScreen(ItemStack stack) {
        super(Component.translatable("screen.veriborder.dual_gate_brush"), WIDTH, HEIGHT);
        this.deviceMode = false;
        this.pos = BlockPos.ZERO;
        this.slot = -1;
        this.accent = DUAL_ACCENT;
        seed(DualGateBrushConfig.read(stack));
    }

    /**
     * Device mode: the dual-gate configuration of the block entity the device
     * brush was used on. Saving writes the same schema straight back to it.
     */
    public DualGateBrushScreen(com.veriborder.block.DeviceType device, BlockPos pos,
                               CompoundTag payload, int slot) {
        super(Component.translatable("screen.veriborder.dual_gate_brush.device_title"), WIDTH, HEIGHT);
        this.deviceMode = true;
        this.pos = pos == null ? BlockPos.ZERO : pos;
        this.slot = slot;
        this.accent = DUAL_ACCENT;
        // Device payload: DeviceBlockEntity#configTag carries the two-stage config
        // under the "dualGate" key (the brush stores it under its own TAG).
        seed(DualGateBrushConfig.sanitize(payload == null ? new CompoundTag()
                : payload.getCompound("dualGate")));
    }

    private void seed(CompoundTag config) {
        this.exitAuthority = config.getString(DualGateBrushConfig.EXIT_AUTHORITY);
        this.exitOfficer = config.getString(DualGateBrushConfig.EXIT_OFFICER);
        this.exitPort = config.getString(DualGateBrushConfig.EXIT_PORT);
        this.entryAuthority = config.getString(DualGateBrushConfig.ENTRY_AUTHORITY);
        this.entryOfficer = config.getString(DualGateBrushConfig.ENTRY_OFFICER);
        this.entryPort = config.getString(DualGateBrushConfig.ENTRY_PORT);
        this.entryMode = DualGateBrushConfig.normalizeMode(
                config.getString(DualGateBrushConfig.ENTRY_MODE));
        this.exitCodes.seed(DualGateBrushConfig.exitCodes(config));
        this.entryCodes.seed(DualGateBrushConfig.codes(config));
        this.entryCountryCodes.addAll(DualGateBrushConfig.countryCodes(config));
        this.exitCountryCodes.addAll(DualGateBrushConfig.exitCountryCodes(config));
    }

    private Component title() {
        return Component.translatable(deviceMode ? "screen.veriborder.dual_gate_brush.device_title"
                : "screen.veriborder.dual_gate_brush");
    }

    private Component subtitle() {
        return Component.translatable(deviceMode ? "screen.veriborder.dual_gate_brush.device_subtitle"
                : "screen.veriborder.dual_gate_brush.subtitle");
    }

    @Override
    protected void build() {
        ClientRegistryCache.requestIfEmpty();
        List<CredentialRegistryData.Country> countries = ClientRegistryCache.countries();
        int x = left + 24;
        int width = WIDTH - 48;
        this.anchors.clear();
        this.layout = FlowLayout.at(x, top + CONTENT_TOP, width, GAP);

        hintRowY = layout.row(10);

        // ---- exit stage: credential codes + allowed-country picker -----------
        exitTitleY = layout.row(12);
        exitInputY = layout.row(LABEL + FIELD);
        exitCodeField = anchored(addField(x, exitInputY + LABEL, width - 96,
                "field.veriborder.exit_codes", "", "hint.veriborder.code_batch"));
        exitCodeField.setMaxLength(512);
        exitCodeField.setFilter(DualGateBrushScreen::codeBatchInput);
        button(x + width - 88, exitInputY + LABEL, 88, 18,
                Component.translatable("button.veriborder.add"), pressed -> addExitCode(), Ui.GREEN);
        exitListY = layout.row(exitCodes.height());
        exitCodeBox = new CodeListBox(x, exitListY, width, exitCodes,
                Component.translatable("screen.veriborder.dual_gate_brush.exit_codes_none"));
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

        // ---- entry stage: mode + credential codes + allowed-country picker ---
        entryTitleY = layout.row(12);
        entryModeRowY = layout.row(LABEL + 18, 8);
        List<Dropdown.Option> modes = new ArrayList<>();
        for (String mode : DualGateBrushConfig.MODES) {
            modes.add(new Dropdown.Option(mode,
                    Component.translatable(DualGateBrushConfig.modeKey(mode)).getString()));
        }
        entryModeDropdown = addDropdown(Dropdown.at(x, entryModeRowY + LABEL, width)
                .labelKey("field.veriborder.gate_mode")
                .options(modes)
                .selected(entryMode)
                .panelBottom(top + HEIGHT - FOOTER_HEIGHT)
                .tooltip("screen.veriborder.gate_brush.mode_hint")
                .onChange(id -> entryMode = DualGateBrushConfig.normalizeMode(id)));
        entryInputY = layout.row(LABEL + FIELD);
        entryCodeField = anchored(addField(x, entryInputY + LABEL, width - 96,
                "field.veriborder.entry_codes", "", "hint.veriborder.code_batch"));
        entryCodeField.setMaxLength(512);
        entryCodeField.setFilter(DualGateBrushScreen::codeBatchInput);
        button(x + width - 88, entryInputY + LABEL, 88, 18,
                Component.translatable("button.veriborder.add"), pressed -> addEntryCode(), Ui.GREEN);
        entryListY = layout.row(entryCodes.height());
        entryCodeBox = new CodeListBox(x, entryListY, width, entryCodes,
                Component.translatable("screen.veriborder.dual_gate_brush.codes_none"));
        entryButtonsY = layout.row(18);
        button(x, entryButtonsY, 120, 18, Component.translatable("button.veriborder.remove_last"),
                pressed -> entryCodes.removeLast(), Ui.AMBER);
        button(x + 130, entryButtonsY, 100, 18, Component.translatable("button.veriborder.clear_all"),
                pressed -> entryCodes.clear(), Ui.RED);
        entryPickerY = layout.row(CountryPicker.BUTTON_HEIGHT);
        entryPicker = CountryPicker.at(font, x, entryPickerY, width, entryCountryCodes,
                        "checklist.veriborder.entry_whitelist")
                .countries(countries)
                .bounds(top + CONTENT_TOP, top + HEIGHT - FOOTER_HEIGHT);

        // ---- Advanced: low-frequency stage identity overrides ----------------
        advancedButtonY = layout.row(18);
        anchored(addRenderableWidget(new BetterButton(x, advancedButtonY, width, 18, advancedLabel(),
                pressed -> {
                    advancedOpen = !advancedOpen;
                    closePickersExcept(null);
                    // The widget list is rebuilt: drop the old focus so no removed
                    // button keeps keyboard focus.
                    rebuildWidgets();
                    setFocused(null);
                }, Ui.PURPLE, true)));
        if (advancedOpen) {
            int columnWidth = (width - 12) / 2;
            int rowOne = layout.row(LABEL + FIELD);
            exitAuthorityField = anchored(addField(x, rowOne + LABEL, columnWidth,
                    "field.veriborder.exit_authority", exitAuthority));
            exitAuthorityField.setResponder(value -> exitAuthority = value);
            exitOfficerField = anchored(addField(x + columnWidth + 6, rowOne + LABEL, columnWidth,
                    "field.veriborder.exit_officer", exitOfficer));
            exitOfficerField.setResponder(value -> exitOfficer = value);
            int rowTwo = layout.row(LABEL + FIELD);
            exitPortField = anchored(addField(x, rowTwo + LABEL, columnWidth,
                    "field.veriborder.exit_port", exitPort));
            exitPortField.setResponder(value -> exitPort = value);
            entryAuthorityField = anchored(addField(x + columnWidth + 6, rowTwo + LABEL, columnWidth,
                    "field.veriborder.entry_authority", entryAuthority));
            entryAuthorityField.setResponder(value -> entryAuthority = value);
            int rowThree = layout.row(LABEL + FIELD);
            entryOfficerField = anchored(addField(x, rowThree + LABEL, columnWidth,
                    "field.veriborder.entry_officer", entryOfficer));
            entryOfficerField.setResponder(value -> entryOfficer = value);
            entryPortField = anchored(addField(x + columnWidth + 6, rowThree + LABEL, columnWidth,
                    "field.veriborder.entry_port", entryPort));
            entryPortField.setResponder(value -> entryPort = value);
        }
        advancedHintY = layout.row(10);
        opHintY = layout.row(10);
        flowHintY = layout.row(10);

        addFixedWidget(new BetterButton(x, top + HEIGHT - 30, 120, 20,
                Component.translatable("button.veriborder.save"), pressed -> save(), accent));
        addFixedWidget(new BetterButton(x + 130, top + HEIGHT - 30, 100, 20,
                Component.translatable("button.veriborder.cancel"), pressed -> onClose(), Ui.MUTED, true));

        layout.viewportHeight(HEIGHT - CONTENT_TOP - FOOTER_HEIGHT);
        setContentClip(top + CONTENT_TOP, top + HEIGHT - FOOTER_HEIGHT);
        applyScroll();
    }

    private <T extends AbstractWidget> T anchored(T widget) {
        anchors.add(new Anchor(widget, widget.getY()));
        return widget;
    }

    private BetterButton button(int x, int y, int width, int height, Component label,
                                Button.OnPress onPress, int color) {
        return anchored(addRenderableWidget(new BetterButton(x, y, width, height, label, onPress, color, true)));
    }

    private void applyScroll() {
        if (layout == null) {
            return;
        }
        int offset = layout.offset();
        for (Anchor anchor : anchors) {
            anchor.widget().setY(anchor.baseY() - offset);
        }
        if (entryModeDropdown != null) {
            entryModeDropdown.setY(entryModeRowY + LABEL - offset);
        }
        if (exitCodeBox != null) {
            exitCodeBox.moveTo(left + 24, exitListY - offset);
        }
        if (entryCodeBox != null) {
            entryCodeBox.moveTo(left + 24, entryListY - offset);
        }
        if (exitPicker != null) {
            exitPicker.moveTo(left + 24, exitPickerY - offset);
        }
        if (entryPicker != null) {
            entryPicker.moveTo(left + 24, entryPickerY - offset);
        }
    }

    private int paint(int layoutY) {
        return layout == null ? layoutY : layout.paintY(layoutY);
    }

    private Component advancedLabel() {
        return Component.translatable(advancedOpen
                ? "screen.veriborder.config.advanced.open" : "screen.veriborder.config.advanced.closed");
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        if (handlePicker(exitPicker, uiX, uiY, button)) {
            return true;
        }
        if (handlePicker(entryPicker, uiX, uiY, button)) {
            return true;
        }
        if (exitCodeBox != null && exitCodeBox.mouseClicked(uiX, uiY)) {
            return true;
        }
        if (entryCodeBox != null && entryCodeBox.mouseClicked(uiX, uiY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

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
        if (exitPicker != null && exitPicker != keep) {
            exitPicker.close();
        }
        if (entryPicker != null && entryPicker != keep) {
            entryPicker.close();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        if (dropdownScroll(uiX, uiY, delta)) {
            return true;
        }
        if (exitPicker != null && exitPicker.mouseScrolled(uiX, uiY, delta)) {
            return true;
        }
        if (entryPicker != null && entryPicker.mouseScrolled(uiX, uiY, delta)) {
            return true;
        }
        if (exitCodeBox != null && exitCodeBox.mouseScrolled(uiX, uiY, delta)) {
            return true;
        }
        if (entryCodeBox != null && entryCodeBox.mouseScrolled(uiX, uiY, delta)) {
            return true;
        }
        if (layout != null && layout.scrollBy(delta)) {
            closePickersExcept(null);
            applyScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (exitPicker != null && exitPicker.isOpen()) {
            return exitPicker.keyPressed(keyCode, scanCode, modifiers);
        }
        if (entryPicker != null && entryPicker.isOpen()) {
            return entryPicker.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (exitPicker != null && exitPicker.isOpen()) {
            return exitPicker.charTyped(codePoint, modifiers);
        }
        if (entryPicker != null && entryPicker.isOpen()) {
            return entryPicker.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    // ------------------------------------------------------------------- draw

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.mouseUiX = toUiX(mouseX);
        this.mouseUiY = toUiY(mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        boolean anyOpen = (exitPicker != null && exitPicker.isOpen())
                || (entryPicker != null && entryPicker.isOpen());
        if (anyOpen) {
            overlay(graphics, canvas -> {
                if (exitPicker != null && exitPicker.isOpen()) {
                    exitPicker.drawPanel(canvas, accent, mouseUiX, mouseUiY);
                }
                if (entryPicker != null && entryPicker.isOpen()) {
                    entryPicker.drawPanel(canvas, accent, mouseUiX, mouseUiY);
                }
            });
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, title().getString(), subtitle().getString());
        int x = left + 24;
        int width = WIDTH - 48;
        drawScrollbar(graphics);
        withContentClip(graphics, () -> {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.dual_gate_brush.hint"),
                    x, paint(hintRowY), width, Ui.MUTED);

            // ---- exit stage block ------------------------------------------------
            sectionHeader(graphics, "screen.veriborder.dual_gate_brush.exit",
                    Component.translatable("screen.veriborder.dual_gate_brush.exit_codes",
                            exitCodes.size()).getString(),
                    exitCodes.errorKey(), paint(exitTitleY));
            if (exitCodeBox != null) {
                exitCodeBox.draw(graphics, font, accent, mouseUiX, mouseUiY);
            }
            if (exitPicker != null) {
                exitPicker.drawButton(graphics, accent, mouseUiX, mouseUiY);
            }

            // ---- entry stage block ----------------------------------------------
            sectionHeader(graphics, "screen.veriborder.dual_gate_brush.entry",
                    Component.translatable("screen.veriborder.dual_gate_brush.codes",
                            entryCodes.size()).getString(),
                    entryCodes.errorKey(), paint(entryTitleY));
            if (entryCodeBox != null) {
                entryCodeBox.draw(graphics, font, accent, mouseUiX, mouseUiY);
            }
            if (entryPicker != null) {
                entryPicker.drawButton(graphics, accent, mouseUiX, mouseUiY);
            }

            // ---- Advanced fold hints --------------------------------------------
            if (advancedOpen) {
                Ui.clipped(graphics, Component.translatable("screen.veriborder.config.advanced_identity_hint"),
                        x, paint(advancedHintY), width, Ui.DIM);
            } else {
                Ui.clipped(graphics, Component.translatable("screen.veriborder.config.advanced_hint"),
                        x, paint(advancedHintY), width, Ui.DIM);
            }
            Ui.clipped(graphics, Component.translatable("screen.veriborder.dual_gate_brush.op_hint"),
                    x, paint(opHintY), width, Ui.AMBER);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.dual_gate_brush.flow_hint"),
                    x, paint(flowHintY), width, Ui.DIM);
        });

        // Fixed footer chrome.
        graphics.fill(x, top + HEIGHT - 36, x + width, top + HEIGHT - 35, Ui.BORDER);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.config.exit_tolerance"),
                x, top + HEIGHT - 48, width, Ui.AMBER);
    }

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

    private void addExitCode() {
        if (exitCodes.addBatch(exitCodeField == null ? "" : exitCodeField.getValue()) > 0
                && exitCodeField != null) {
            exitCodeField.setValue("");
        }
    }

    private void addEntryCode() {
        if (entryCodes.addBatch(entryCodeField == null ? "" : entryCodeField.getValue()) > 0
                && entryCodeField != null) {
            entryCodeField.setValue("");
        }
    }

    private void save() {
        CompoundTag data = new CompoundTag();
        data.putString(DualGateBrushConfig.EXIT_AUTHORITY, exitAuthority);
        data.putString(DualGateBrushConfig.EXIT_OFFICER, exitOfficer);
        data.putString(DualGateBrushConfig.EXIT_PORT, exitPort);
        data.putString(DualGateBrushConfig.ENTRY_AUTHORITY, entryAuthority);
        data.putString(DualGateBrushConfig.ENTRY_OFFICER, entryOfficer);
        data.putString(DualGateBrushConfig.ENTRY_PORT, entryPort);
        data.putString(DualGateBrushConfig.ENTRY_MODE, entryMode);
        data.put(DualGateBrushConfig.EXIT_CODES, codesTag(exitCodes.codes()));
        data.put(DualGateBrushConfig.ENTRY_CODES, codesTag(entryCodes.codes()));
        data.put(DualGateBrushConfig.ENTRY_COUNTRY_CODES, codesTag(entryCountryCodes));
        data.put(DualGateBrushConfig.EXIT_COUNTRY_CODES, codesTag(exitCountryCodes));
        if (deviceMode) {
            // Same schema, same sanitisation; only the destination differs.
            ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "save_dual_gate", data, slot));
        } else {
            ModNetwork.CHANNEL.sendToServer(new DualGateBrushPacket(data));
        }
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
