package com.veriborder.client.screen;

import com.veriborder.block.DeviceType;
import com.veriborder.client.BetterButton;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.client.ui.Dropdown;
import com.veriborder.client.ui.PagedList;
import com.veriborder.client.ui.TabStrip;
import com.veriborder.doc.DateUtil;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentFactory;
import com.veriborder.doc.EndorsementRecord;
import com.veriborder.doc.EndorsementType;
import com.veriborder.network.DeviceActionPacket;
import com.veriborder.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Endorsement issue machine (staff and self-service variants).
 *
 * <p>26.3: staff machines use the shared {@link TabStrip} with an Issue page and
 * a Templates page whose saved templates are a paged {@link PagedList}. The
 * target permit card and its existing endorsement list stay pinned on the left
 * of the Issue page, and the Issue/Close actions remain in the footer on both
 * pages.</p>
 */
public class EndorsementScreen extends VeriScreen {
    private static final int TAB_ISSUE = 0;
    private static final int TAB_TEMPLATES = 1;

    private final DeviceType device;
    private final BlockPos pos;
    private final CompoundTag payload;
    private final int targetSlot;
    private final DocumentData target;
    private final boolean selfService;
    private final boolean lockAuthority;
    private final String lockedAuthority;
    private final String machineAuthority;
    private final boolean lockEndorsementType;
    private final EndorsementType lockedEndorsementType;
    private final boolean lockIssueDate;
    private final String lockedIssueDate;
    private final boolean lockValidity;
    /** 26.2 month precision: locked endorsement validity in months (0 = permanent). */
    private final int lockedValidityMonths;
    private final List<CompoundTag> templates = new ArrayList<>();
    private int templateIndex;
    private EndorsementType type;
    private int tab = TAB_ISSUE;
    private TabStrip tabs;
    private PagedList<CompoundTag> templateList;
    private CompoundTag formCache = new CompoundTag();
    private EditBox entries;
    private EditBox issue;
    private EditBox expiry;
    private EditBox authority;
    private EditBox code;
    private EditBox templateName;
    private int selectedEndorsement = -1;
    private String codeError = "";

    public EndorsementScreen(DeviceType device, BlockPos pos, CompoundTag payload) {
        super(Component.translatable(device.translationKey()), 520,
                payload.getBoolean("selfService") || device == DeviceType.SELF_ENDORSEMENT ? 350 : 370);
        this.device = device;
        this.pos = pos;
        this.payload = payload;
        this.targetSlot = payload.getInt("targetSlot");
        this.target = payload.contains("doc") ? DocumentData.fromTag(payload.getCompound("doc")) : null;
        this.selfService = payload.getBoolean("selfService") || device == DeviceType.SELF_ENDORSEMENT;
        this.lockedAuthority = payload.getString("lockedAuthority").trim();
        this.lockAuthority = payload.getBoolean("lockAuthority") && !lockedAuthority.isBlank();
        String machine = payload.getString("machineAuthority").trim();
        this.machineAuthority = machine.isBlank() ? lockedAuthority : machine;
        EndorsementType resolvedType = EndorsementType.byIdOrNull(payload.getString("lockedEndorsementType"));
        this.lockedEndorsementType = resolvedType == null ? EndorsementType.ONE_YEAR_MULTI : resolvedType;
        this.lockEndorsementType = payload.getBoolean("lockEndorsementType") && resolvedType != null;
        String issueDate = payload.getString("lockedIssueDate").trim();
        this.lockedIssueDate = issueDate;
        this.lockIssueDate = payload.getBoolean("lockIssueDate") && DateUtil.isValid(issueDate);
        this.lockValidity = payload.getBoolean("lockValidity");
        // 26.2 month precision: months win, a legacy year value is migrated x12.
        this.lockedValidityMonths = payload.contains("lockedValidityMonths")
                ? Math.max(0, payload.getInt("lockedValidityMonths"))
                : Math.max(0, payload.getInt("lockedValidityYears") * 12);
        this.type = lockEndorsementType ? lockedEndorsementType : EndorsementType.ONE_YEAR_MULTI;
        ListTag list = payload.getList("presets", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            templates.add(list.getCompound(i));
        }
        this.accent = device.accentColor();
    }

    // ------------------------------------------------------------------- build
    @Override
    protected void build() {
        if (selfService) {
            buildSelfService();
            addCloseButton();
            return;
        }
        tabs = TabStrip.at(left + 24, top + 44, 472).size(20, 3);
        tabs.add("issue", Component.translatable("tab.veriborder.issue"));
        tabs.add("templates", Component.translatable("tab.veriborder.templates_tab"));
        tabs.setSelected(tab);
        if (tab == TAB_TEMPLATES) {
            buildTemplatesTab();
        } else {
            buildIssueTab();
        }
        addRenderableWidget(new BetterButton(left + 222, top + 304, 150, 22,
                Component.translatable("button.veriborder.issue_endorsement"), button -> issue(), Ui.GREEN));
        addCloseButton();
    }

    private void addCloseButton() {
        addRenderableWidget(new BetterButton(left + 444, top + 320, 52, 20,
                Component.translatable("button.veriborder.close"), button -> onClose(), Ui.MUTED, true));
    }

    /** 26.4 options of the endorsement-type dropdown, in enum order. */
    private static List<Dropdown.Option> endorsementTypeOptions() {
        List<Dropdown.Option> options = new ArrayList<>();
        for (EndorsementType value : EndorsementType.values()) {
            options.add(new Dropdown.Option(value.id(),
                    Component.translatable(value.translationKey()).getString()));
        }
        return options;
    }

    /** Explains a disabled type dropdown; {@code null} when the operator may choose. */
    private Component lockedTypeTooltip() {
        return lockEndorsementType
                ? Component.translatable("screen.veriborder.endorsement.self_locked",
                        Component.translatable(lockedEndorsementType.translationKey()))
                : null;
    }

    private void buildSelfService() {
        // 26.4: endorsement type is a dropdown (3 options) instead of a cycle.
        addDropdown(Dropdown.at(left + 222, top + 100, 274)
                .options(endorsementTypeOptions())
                .selected(type.id())
                .enabled(!lockEndorsementType)
                .tooltip(lockedTypeTooltip())
                .onChange(id -> {
                    EndorsementType next = EndorsementType.byIdOrNull(id);
                    if (next != null) {
                        type = next;
                    }
                }));
        addRenderableWidget(new BetterButton(left + 222, top + 136, 274, 24,
                Component.translatable("button.veriborder.issue_endorsement"), button -> issue(), Ui.GREEN));
    }

    private void buildIssueTab() {
        String issueValue = lockIssueDate ? lockedIssueDate : cached("issueDate", DateUtil.today());
        // 26.4: endorsement type is a dropdown (3 options) instead of a cycle.
        addDropdown(Dropdown.at(left + 222, top + 96, 170)
                .options(endorsementTypeOptions())
                .selected(type.id())
                .enabled(!lockEndorsementType)
                .tooltip(lockedTypeTooltip())
                .onChange(id -> {
                    EndorsementType next = EndorsementType.byIdOrNull(id);
                    if (next == null || next == type) {
                        return;
                    }
                    String previousDefault = String.valueOf(type.defaultEntries());
                    type = next;
                    if (entries != null && entries.getValue().trim().equals(previousDefault)) {
                        entries.setValue(String.valueOf(type.defaultEntries()));
                    }
                    if (issue != null && DateUtil.isValid(issue.getValue()) && expiry != null) {
                        expiry.setValue(expiryFor(issue.getValue()));
                    }
                }));
        entries = addField(left + 404, top + 100, 92, "field.veriborder.entries",
                cached("entries", String.valueOf(type.defaultEntries())), "hint.veriborder.unlimited");
        issue = addField(left + 222, top + 136, 170, "field.veriborder.issue", issueValue,
                lockIssueDate ? "hint.veriborder.locked" : "hint.veriborder.date");
        if (lockIssueDate) {
            issue.setValue(lockedIssueDate);
            issue.setEditable(false);
        }
        expiry = addField(left + 400, top + 136, 96, "field.veriborder.expiry",
                lockValidity ? autoExpiry(issueValue) : cached("expiryDate",
                        DocumentFactory.endorsementExpiry(type, issueValue)),
                lockValidity ? "hint.veriborder.auto_years" : "hint.veriborder.date");
        if (lockValidity) {
            expiry.setValue(autoExpiry(issueValue));
            expiry.setEditable(false);
            // Follow an unlocked issue-date edit for display; the server is
            // still authoritative when the endorsement is issued.
            issue.setResponder(value -> {
                if (expiry != null) {
                    expiry.setValue(autoExpiry(value));
                }
            });
        }
        authority = addField(left + 222, top + 172, 274, "field.veriborder.authority",
                lockAuthority ? lockedAuthority : cached("authority", Component.translatable("screen.veriborder.endorsement.default_authority").getString()),
                lockAuthority ? "hint.veriborder.locked" : null);
        if (lockAuthority) {
            authority.setValue(lockedAuthority);
            authority.setEditable(false);
        }
        code = addField(left + 222, top + 208, 274, "field.veriborder.code",
                cached("code", ""), "hint.veriborder.code");
        code.setMaxLength(8);
        code.setFilter(ScreenCodes::digitsUpTo8);
        addRenderableWidget(new BetterButton(left + 24, top + 304, 82, 20,
                Component.translatable("button.veriborder.renew_endorsement"), button -> renewSelected(),
                Ui.AMBER));
        addRenderableWidget(new BetterButton(left + 112, top + 304, 82, 20,
                Component.translatable("button.veriborder.revoke_endorsement"), button -> revokeSelected(),
                Ui.RED));
    }

    private void buildTemplatesTab() {
        templateName = addField(left + 24, top + 84, 300, "field.veriborder.template_name",
                currentTemplateName(), "hint.veriborder.preset");
        templateList = PagedList.<CompoundTag>at(left + 24, top + 112, 472, 150)
                .rowHeight(28).rowGap(1).accent(accent).emptyText(Component.translatable("hint.veriborder.no_presets"));
        templateList.setItems(templates);
        templateList.setSelectedIndex(templateIndex >= 0 && templateIndex < templates.size()
                ? templateIndex : -1);
        addRenderableWidget(new BetterButton(left + 24, top + 268, 70, 20,
                Component.translatable("button.veriborder.apply"), button -> applyTemplate(), Ui.PURPLE, true));
        addRenderableWidget(new BetterButton(left + 100, top + 268, 64, 20,
                Component.translatable("button.veriborder.save"), button -> saveTemplate(), Ui.CYAN, true));
        addRenderableWidget(new BetterButton(left + 170, top + 268, 72, 20,
                Component.translatable("button.veriborder.delete"), button -> deleteTemplate(), Ui.RED, true));
    }

    private String cached(String key, String fallback) {
        String value = formCache.getString(key);
        return value.isBlank() ? fallback : value;
    }

    private void captureFields() {
        if (selfService) {
            return;
        }
        if (entries != null) {
            formCache.putString("entries", entries.getValue());
        }
        if (issue != null) {
            formCache.putString("issueDate", issue.getValue());
        }
        if (expiry != null) {
            formCache.putString("expiryDate", expiry.getValue());
        }
        if (authority != null) {
            formCache.putString("authority", authority.getValue());
        }
        if (code != null) {
            formCache.putString("code", code.getValue());
        }
        formCache.putString("type", type.id());
    }

    private void selectTab(int next) {
        captureFields();
        tab = next == TAB_TEMPLATES ? TAB_TEMPLATES : TAB_ISSUE;
        rebuildWidgets();
    }

    private String currentTemplateName() {
        if (templates.isEmpty()) {
            return Component.translatable("screen.veriborder.endorsement.default_template").getString();
        }
        return templates.get(Math.floorMod(templateIndex, templates.size())).getString("name");
    }

    private void selectTemplate(int index) {
        templateIndex = Math.max(0, index);
        if (templateList != null) {
            templateList.setSelectedIndex(templateIndex);
        }
        if (templateName != null) {
            templateName.setValue(currentTemplateName());
        }
    }

    private String autoExpiry(String issueDate) {
        return lockedValidityMonths <= 0 ? DateUtil.PERMANENT
                : DateUtil.plusMonths(issueDate, lockedValidityMonths);
    }

    /** Locked validity as human text (26.2: month precision, years collapsed). */
    private String validityText() {
        return Ui.validityText(lockedValidityMonths);
    }

    private String expiryFor(String issueDate) {
        return lockValidity ? autoExpiry(issueDate)
                : DocumentFactory.endorsementExpiry(type, issueDate);
    }

    private void renewSelected() {
        if (selfService || target == null || selectedEndorsement < 0) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putInt("index", selectedEndorsement);
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "renew_endorsement", data, targetSlot));
    }

    private void revokeSelected() {
        if (selfService || target == null || selectedEndorsement < 0) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putInt("index", selectedEndorsement);
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "revoke_endorsement", data, targetSlot));
    }

    private void applyTemplate() {
        if (templates.isEmpty()) {
            return;
        }
        CompoundTag values = templates.get(Math.floorMod(templateIndex, templates.size()))
                .getCompound("values");
        formCache = values.copy();
        type = lockEndorsementType ? lockedEndorsementType : EndorsementType.byId(values.getString("type"));
        formCache.putString("type", type.id());
        if (lockIssueDate) {
            formCache.putString("issueDate", lockedIssueDate);
        }
        if (lockValidity) {
            formCache.putString("expiryDate", autoExpiry(cached("issueDate", DateUtil.today())));
        }
        if (lockAuthority) {
            formCache.putString("authority", lockedAuthority);
        }
        tab = TAB_ISSUE;
        rebuildWidgets();
    }

    private CompoundTag form() {
        CompoundTag data = new CompoundTag();
        data.putString("type", type.id());
        if (selfService) {
            // The machine supplies authority, code, entries and dates itself.
            return data;
        }
        try {
            data.putInt("entries", Integer.parseInt((entries != null ? entries.getValue()
                    : cached("entries", String.valueOf(type.defaultEntries()))).trim()));
        } catch (RuntimeException ignored) {
            data.putInt("entries", type.defaultEntries());
        }
        data.putString("issueDate", issue != null ? issue.getValue() : cached("issueDate", DateUtil.today()));
        data.putString("expiryDate", expiry != null ? expiry.getValue() : cached("expiryDate", ""));
        data.putString("authority", authority != null ? authority.getValue() : cached("authority", ""));
        data.putString("code", ScreenCodes.validOrBlank(code != null ? code.getValue()
                : cached("code", "")));
        return data;
    }

    private void saveTemplate() {
        captureFields();
        CompoundTag data = form();
        // Templates never pin a code: each issued endorsement receives its own.
        data.remove("code");
        String name = templateName == null ? currentTemplateName() : templateName.getValue();
        data.putString("presetName", name.isBlank() ? "Endorsement template" : name);
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "save_template", data, targetSlot));
    }

    private void deleteTemplate() {
        CompoundTag data = new CompoundTag();
        data.putString("presetName", templateName == null ? currentTemplateName() : templateName.getValue());
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "delete_preset", data, targetSlot));
    }

    private void issue() {
        if (!selfService) {
            String raw = (code != null ? code.getValue() : cached("code", "")).trim();
            if (!raw.isEmpty() && !ScreenCodes.isValid(raw)) {
                codeError = "message.veriborder.code.invalid";
                return;
            }
            codeError = "";
        }
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "issue_endorsement", form(), targetSlot));
    }

    // ------------------------------------------------------------------ render
    private double mouseUiX = -1;
    private double mouseUiY = -1;

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.mouseUiX = toUiX(mouseX);
        this.mouseUiY = toUiY(mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, Component.translatable(device.translationKey()).getString(),
                Component.translatable(device.descriptionKey()).getString());
        graphics.drawString(font, Component.translatable("screen.veriborder.endorsement.target"), left + 24,
                top + 50, Ui.DIM, false);
        if (target != null) {
            Ui.documentCard(graphics, left + 24, top + 64, 180, 112, target);
            if (!selfService && tab == TAB_ISSUE) {
                graphics.drawString(font, Component.translatable("screen.veriborder.endorsement.existing"),
                        left + 24, top + 184, Ui.DIM, false);
                List<EndorsementRecord> records = target.endorsements();
                for (int i = 0; i < Math.min(6, records.size()); i++) {
                    EndorsementRecord record = records.get(i);
                    int rowY = top + 197 + i * 12;
                    boolean selected = i == selectedEndorsement;
                    if (selected) {
                        graphics.fill(left + 24, rowY - 1, left + 204, rowY + 10, 0x552FC6B7);
                    }
                    graphics.drawString(font, Ui.ellipsis(Component.translatable(
                                    record.type().translationKey()).getString() + "  " + record.expiryDate(),
                            176), left + 28, rowY, selected ? Ui.GREEN : Ui.MUTED, false);
                }
                if (records.isEmpty()) {
                    graphics.drawString(font, Component.translatable("document.veriborder.no_records"),
                            left + 28, top + 199, Ui.DIM, false);
                }
            }
        } else {
            Ui.inset(graphics, left + 24, top + 64, 180, 118);
            graphics.drawString(font, Component.translatable("screen.veriborder.endorsement.no_target"),
                    left + 34, top + 112, Ui.RED, false);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.endorsement.no_target2"),
                    left + 34, top + 126, 160, Ui.MUTED);
        }
        if (selfService) {
            renderSelfService(graphics);
            return;
        }
        tabs.draw(graphics, font, accent);
        if (tab == TAB_TEMPLATES) {
            renderTemplatesTab(graphics);
        } else {
            renderIssueTab(graphics);
        }
    }

    private void renderIssueTab(GuiGraphics graphics) {
        Ui.clipped(graphics, Component.translatable("screen.veriborder.endorsement.hint"), left + 222,
                top + 70, 274, Ui.MUTED);
        if (lockValidity) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.lock.validity_line",
                            validityText(), expiry == null ? "" : expiry.getValue()),
                    left + 222, top + 226, 274, Ui.AMBER);
        }
        if (codeError.isBlank()) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.visa.entries_hint"), left + 222,
                    top + 238, 274, Ui.DIM);
        } else {
            Ui.clipped(graphics, Component.translatable(codeError), left + 222,
                    top + 238, 274, Ui.RED);
        }
        Ui.clipped(graphics, Component.translatable("screen.veriborder.endorsement.types"), left + 222,
                top + 330, 210, Ui.DIM);
    }

    private void renderTemplatesTab(GuiGraphics graphics) {
        if (templateList != null) {
            templateList.draw(graphics, font, (g, entry, index, x, y, width, height, selected, hovered) -> {
                CompoundTag values = entry.getCompound("values");
                graphics.fill(x, y, x + width, y + height, selected ? 0xFF243A50 : hovered
                        ? 0xFF1A2A3B : 0x66101A28);
                if (selected) {
                    graphics.fill(x, y, x + 3, y + height, accent);
                }
                graphics.drawString(font, Ui.ellipsis(entry.getString("name"), 220), x + 8, y + 3,
                        Ui.TEXT, false);
                graphics.drawString(font, Ui.ellipsis(Component.translatable(
                        EndorsementType.byId(values.getString("type")).translationKey()).getString(), 130),
                        x + 236, y + 9, Ui.MUTED, false);
                graphics.drawString(font, Ui.ellipsis(values.getString("authority"), width - 370),
                        x + 372, y + 9, Ui.DIM, false);
            }, mouseUiX, mouseUiY);
        }
    }

    private void renderSelfService(GuiGraphics graphics) {
        Ui.wrapped(graphics, Component.translatable("screen.veriborder.endorsement.self"),
                left + 222, top + 174, 274, Ui.MUTED, 2);
        if (lockEndorsementType) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.endorsement.self_locked",
                            Component.translatable(type.translationKey()).getString()),
                    left + 222, top + 160, 274, Ui.CYAN);
        }
        if (lockValidity) {
            String issueValue = lockIssueDate ? lockedIssueDate : DateUtil.today();
            Ui.clipped(graphics, Component.translatable("screen.veriborder.lock.validity_line",
                            validityText(), autoExpiry(issueValue)),
                    left + 222, top + 224, 274, Ui.AMBER);
        }
        Ui.clipped(graphics, Component.translatable("screen.veriborder.endorsement.self_code"),
                left + 222, top + 200, 274, Ui.DIM);
        if (!machineAuthority.isBlank()) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.endorsement.self_authority",
                    machineAuthority), left + 222, top + 212, 274, Ui.CYAN);
        }
    }

    // ------------------------------------------------------------------- input
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (selfService) {
            return false;
        }
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        int hit = tabs.hit(uiX, uiY);
        if (hit >= 0) {
            if (hit != tab) {
                selectTab(hit);
            }
            return true;
        }
        if (tab == TAB_TEMPLATES) {
            if (templateList != null && templateList.mouseClicked(uiX, uiY)) {
                if (templateList.selectedIndex() >= 0) {
                    selectTemplate(templateList.selectedIndex());
                }
                return true;
            }
            return false;
        }
        if (target != null) {
            int x = left + 24;
            int y = top + 196;
            if (uiX >= x && uiX <= x + 180 && uiY >= y && uiY <= y + 80) {
                int row = (int) ((uiY - y) / 12);
                if (row >= 0 && row < target.endorsements().size()) {
                    selectedEndorsement = row;
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (dropdownScroll(toUiX(mouseX), toUiY(mouseY), delta)) {
            return true;
        }
        if (!selfService && tab == TAB_TEMPLATES && templateList != null
                && templateList.mouseScrolled(toUiX(mouseX), toUiY(mouseY), delta)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }
}
