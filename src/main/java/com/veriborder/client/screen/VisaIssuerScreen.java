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
import com.veriborder.doc.VisaType;
import com.veriborder.network.DeviceActionPacket;
import com.veriborder.network.ModNetwork;
import com.veriborder.registry.CredentialRegistryData;
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
 * Visa issue machine (staff and self-service variants).
 *
 * <p>26.3: the staff variant is split into an Issue page and a Templates page
 * behind the shared {@link TabStrip}; saved templates are a paged
 * {@link PagedList} rather than the old prev/next arrows. Every field edited on
 * the Issue page is snapshotted before a page switch, so issuing or saving a
 * template from the Templates page still sends the visible values.</p>
 */
public class VisaIssuerScreen extends VeriScreen {
    private static final int TAB_ISSUE = 0;
    private static final int TAB_TEMPLATES = 1;

    private final DeviceType deviceType;
    private final boolean selfService;
    private final BlockPos pos;
    private final CompoundTag payload;
    private final int targetSlot;
    private final DocumentData target;
    private final boolean lockAuthority;
    private final String lockedAuthority;
    private final String machineAuthority;
    private final boolean lockVisaType;
    private final VisaType lockedVisaType;
    private final boolean lockVisaCode;
    private final String lockedVisaCode;
    private final boolean lockPlace;
    private final String lockedPlace;
    private final boolean lockIssueDate;
    private final String lockedIssueDate;
    private final boolean lockValidity;
    private final int lockedValidityYears;
    private final boolean lockVisaName;
    private final String lockedVisaName;
    private final List<CompoundTag> templates = new ArrayList<>();
    private int templateIndex;
    private VisaType type;
    private int tab = TAB_ISSUE;
    private TabStrip tabs;
    private PagedList<CompoundTag> templateList;
    private CompoundTag formCache = new CompoundTag();
    private EditBox country;
    private EditBox entries;
    private EditBox issue;
    private EditBox expiry;
    private EditBox authority;
    private EditBox code;
    private EditBox visaName;
    private EditBox templateName;
    /** 26.3 registry: issuing-country selector source (push snapshot). */
    private final List<CredentialRegistryData.Country> registryCountries = new ArrayList<>();
    private String codeError = "";

    public VisaIssuerScreen(DeviceType deviceType, BlockPos pos, CompoundTag payload) {
        super(Component.translatable(deviceType.translationKey()), 520,
                deviceType == DeviceType.SELF_VISA ? 350 : 396);
        this.deviceType = deviceType;
        this.selfService = deviceType == DeviceType.SELF_VISA
                || payload.getBoolean("selfService");
        this.pos = pos;
        this.payload = payload;
        this.targetSlot = payload.getInt("targetSlot");
        this.target = payload.contains("doc") ? DocumentData.fromTag(payload.getCompound("doc")) : null;
        this.lockedAuthority = payload.getString("lockedAuthority").trim();
        this.lockAuthority = payload.getBoolean("lockAuthority") && !lockedAuthority.isBlank();
        String machine = payload.getString("machineAuthority").trim();
        this.machineAuthority = machine.isBlank()
                ? (lockAuthority ? lockedAuthority : payload.getString("country").trim() + "驻华使领馆")
                : machine;
        VisaType resolvedVisaType = resolveVisaType(payload.getString("lockedVisaType"));
        this.lockedVisaType = resolvedVisaType == null ? VisaType.TOURIST : resolvedVisaType;
        this.lockVisaType = payload.getBoolean("lockVisaType") && resolvedVisaType != null;
        String visaCode = payload.getString("lockedVisaCode").trim();
        this.lockedVisaCode = visaCode;
        this.lockVisaCode = payload.getBoolean("lockVisaCode") && ScreenCodes.isValid(visaCode);
        String place = payload.getString("lockedPlace").trim();
        this.lockedPlace = place;
        this.lockPlace = payload.getBoolean("lockPlace") && !place.isBlank();
        String issueDate = payload.getString("lockedIssueDate").trim();
        this.lockedIssueDate = issueDate;
        this.lockIssueDate = payload.getBoolean("lockIssueDate") && DateUtil.isValid(issueDate);
        this.lockValidity = payload.getBoolean("lockValidity");
        this.lockedValidityYears = payload.getInt("lockedValidityYears");
        String name = payload.getString("lockedVisaName").trim();
        this.lockedVisaName = name;
        // A locked blank name is meaningful: it forces the country/type fallback.
        this.lockVisaName = payload.getBoolean("lockVisaName");
        this.type = lockVisaType ? lockedVisaType : VisaType.TOURIST;
        ListTag list = payload.getList("presets", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            templates.add(list.getCompound(i));
        }
        ListTag registry = payload.getList("registryCountries", Tag.TAG_COMPOUND);
        for (int i = 0; i < registry.size(); i++) {
            CredentialRegistryData.Country country =
                    CredentialRegistryData.Country.fromTag(registry.getCompound(i));
            if (country.valid()) {
                registryCountries.add(country);
            }
        }
        this.accent = deviceType.accentColor();
    }

    private static VisaType resolveVisaType(String id) {
        if (id == null) {
            return null;
        }
        String key = id.trim();
        if (key.isEmpty()) {
            return null;
        }
        for (VisaType candidate : VisaType.values()) {
            if (candidate.id().equalsIgnoreCase(key) || candidate.name().equalsIgnoreCase(key)) {
                return candidate;
            }
        }
        return null;
    }

    private String countryLockValue() {
        return lockedPlace;
    }

    // ------------------------------------------------------------------- build
    @Override
    protected void build() {
        if (selfService) {
            buildSelfService();
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

        addRenderableWidget(new BetterButton(left + 222, top + 342, 140, 22,
                Component.translatable("button.veriborder.issue_visa"), button -> issue(false), Ui.GREEN));
        addRenderableWidget(new BetterButton(left + 368, top + 342, 136, 22,
                Component.translatable("button.veriborder.issue_all"), button -> issue(true), Ui.AMBER));
        addRenderableWidget(new BetterButton(left + 444, top + 370, 52, 20,
                Component.translatable("button.veriborder.close"), button -> onClose(), Ui.MUTED, true));
    }

    private void buildIssueTab() {
        String issueValue = lockIssueDate ? lockedIssueDate : cached("issueDate", DateUtil.today());
        String expiryValue = lockValidity ? autoExpiry(issueValue)
                : cached("expiryDate", DateUtil.plusYears(issueValue, 1));
        if (!lockPlace && !registryCountries.isEmpty()) {
            // 26.3 registry: the visa issuing country is picked from the global
            // registry, never typed as free text. 26.4: dropdown with search.
            if (formCache.getString("country").isBlank()) {
                CredentialRegistryData.Country first = registryCountries.get(0);
                formCache.putString("country", first.nameEn().isBlank() ? first.nameZh() : first.nameEn());
            }
            List<Dropdown.Option> countries = new ArrayList<>();
            for (CredentialRegistryData.Country entry : registryCountries) {
                String name = entry.nameEn().isBlank() ? entry.nameZh() : entry.nameEn();
                countries.add(new Dropdown.Option(name,
                        entry.code() + "  " + entry.displayName(false)));
            }
            addDropdown(Dropdown.at(left + 222, top + 86, 170)
                    .options(countries)
                    .selected(cached("country", ""))
                    .placeholder("—")
                    .tooltip(Component.translatable("field.veriborder.visa_country"))
                    .onChange(id -> formCache.putString("country", id)));
        } else {
            country = addField(left + 222, top + 90, 170, "field.veriborder.visa_country",
                    lockPlace ? countryLockValue() : cached("country",
                            Component.translatable("screen.veriborder.visa.default_country").getString()),
                    lockPlace ? "hint.veriborder.locked" : null);
            if (lockPlace) {
                country.setValue(countryLockValue());
                country.setEditable(false);
            }
        }
        // 26.4: visa type is a dropdown (6 options) instead of a cycle button.
        addDropdown(Dropdown.at(left + 400, top + 86, 96)
                .options(visaTypeOptions())
                .selected(type.id())
                .enabled(!lockVisaType)
                .tooltip(lockedVisaTypeTooltip())
                .onChange(id -> {
                    VisaType next = VisaType.byId(id);
                    if (!lockVisaType) {
                        type = next;
                    }
                }));
        entries = addField(left + 222, top + 126, 82, "field.veriborder.entries",
                cached("entries", "2"), "hint.veriborder.unlimited");
        issue = addField(left + 312, top + 126, 184, "field.veriborder.issue", issueValue,
                lockIssueDate ? "hint.veriborder.locked" : "hint.veriborder.date");
        if (lockIssueDate) {
            issue.setValue(lockedIssueDate);
            issue.setEditable(false);
        }
        expiry = addField(left + 222, top + 162, 170, "field.veriborder.expiry", expiryValue,
                lockValidity ? "hint.veriborder.auto_years" : "hint.veriborder.date");
        if (lockValidity) {
            expiry.setValue(autoExpiry(issueValue));
            expiry.setEditable(false);
            // Keep the displayed expiry in sync when an unlocked issue date is
            // edited; the server recomputes it authoritatively on issue.
            issue.setResponder(value -> {
                if (expiry != null) {
                    expiry.setValue(autoExpiry(value));
                }
            });
        }
        authority = addField(left + 400, top + 162, 96, "field.veriborder.authority",
                lockAuthority ? lockedAuthority : cached("authority", Component.translatable("screen.veriborder.visa.default_authority").getString()),
                lockAuthority ? "hint.veriborder.locked" : null);
        if (lockAuthority) {
            authority.setValue(lockedAuthority);
            authority.setEditable(false);
        }
        code = addField(left + 222, top + 198, 274, "field.veriborder.code",
                lockVisaCode ? lockedVisaCode : cached("code", ""),
                lockVisaCode ? "hint.veriborder.locked" : "hint.veriborder.code");
        code.setMaxLength(8);
        code.setFilter(ScreenCodes::digitsUpTo8);
        if (lockVisaCode) {
            code.setValue(lockedVisaCode);
            code.setEditable(false);
        }
        visaName = addField(left + 222, top + 252, 274, "field.veriborder.visa_name",
                lockVisaName ? lockedVisaName : cached("visaName", ""),
                lockVisaName ? "hint.veriborder.locked" : "hint.veriborder.visa_name");
        if (lockVisaName) {
            visaName.setValue(lockedVisaName);
            visaName.setEditable(false);
        }
    }

    private void buildTemplatesTab() {
        templateName = addField(left + 24, top + 84, 300, "field.veriborder.template_name",
                currentTemplateName(), "hint.veriborder.preset");
        templateList = PagedList.<CompoundTag>at(left + 24, top + 112, 472, 190)
                .rowHeight(28).rowGap(1).accent(accent).emptyText(Component.translatable("hint.veriborder.no_presets"));
        templateList.setItems(templates);
        templateList.setSelectedIndex(templateIndex >= 0 && templateIndex < templates.size()
                ? templateIndex : -1);
        addRenderableWidget(new BetterButton(left + 24, top + 310, 70, 20,
                Component.translatable("button.veriborder.apply"), button -> applyTemplate(), Ui.PURPLE, true));
        addRenderableWidget(new BetterButton(left + 100, top + 310, 64, 20,
                Component.translatable("button.veriborder.save"), button -> saveTemplate(), Ui.CYAN, true));
        addRenderableWidget(new BetterButton(left + 170, top + 310, 72, 20,
                Component.translatable("button.veriborder.delete"), button -> deleteTemplate(), Ui.RED, true));
    }

    private void buildSelfService() {
        // 26.4: visa type is a dropdown (6 options) instead of a cycle button.
        addDropdown(Dropdown.at(left + 222, top + 100, 274)
                .options(visaTypeOptions())
                .selected(type.id())
                .enabled(!lockVisaType)
                .tooltip(lockedVisaTypeTooltip())
                .onChange(id -> {
                    if (!lockVisaType) {
                        type = VisaType.byId(id);
                    }
                }));
        addRenderableWidget(new BetterButton(left + 222, top + 136, 274, 24,
                Component.translatable("button.veriborder.issue_visa"), button -> issue(false), Ui.GREEN));
        addRenderableWidget(new BetterButton(left + 444, top + 320, 52, 20,
                Component.translatable("button.veriborder.close"), button -> onClose(), Ui.MUTED, true));
    }

    /** 26.4 options of the visa-type dropdown, in enum order. */
    private static List<Dropdown.Option> visaTypeOptions() {
        List<Dropdown.Option> options = new ArrayList<>();
        for (VisaType value : VisaType.values()) {
            options.add(new Dropdown.Option(value.id(),
                    Component.translatable(value.translationKey()).getString()));
        }
        return options;
    }

    /** Explains a disabled visa-type dropdown; {@code null} when it is editable. */
    private Component lockedVisaTypeTooltip() {
        return lockVisaType
                ? Component.translatable("screen.veriborder.visa.self_locked",
                        Component.translatable(lockedVisaType.translationKey()))
                : null;
    }

    private String cached(String key, String fallback) {
        String value = formCache.getString(key);
        return value.isBlank() ? fallback : value;
    }

    /** Snapshots the Issue page so a switch to Templates loses nothing. */
    private void captureFields() {
        if (selfService) {
            return;
        }
        if (country != null) {
            formCache.putString("country", country.getValue());
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
        if (visaName != null) {
            formCache.putString("visaName", visaName.getValue());
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
            return Component.translatable("screen.veriborder.visa.default_template").getString();
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
        return lockedValidityYears <= 0 ? DateUtil.PERMANENT
                : DateUtil.plusYears(issueDate, lockedValidityYears);
    }

    private String validityYearsText() {
        return lockedValidityYears <= 0
                ? Component.translatable("date.veriborder.permanent").getString()
                : Component.translatable("screen.veriborder.years", lockedValidityYears).getString();
    }

    private void applyTemplate() {
        if (templates.isEmpty()) {
            return;
        }
        CompoundTag values = templates.get(Math.floorMod(templateIndex, templates.size()))
                .getCompound("values");
        formCache = values.copy();
        if (lockPlace) {
            formCache.putString("country", lockedPlace);
        }
        type = lockVisaType ? lockedVisaType : VisaType.byId(values.getString("type"));
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
        if (lockVisaName) {
            formCache.putString("visaName", lockedVisaName);
        }
        // Show the applied values on the Issue page.
        tab = TAB_ISSUE;
        rebuildWidgets();
    }

    private CompoundTag form() {
        CompoundTag data = new CompoundTag();
        data.putString("type", type.id());
        if (selfService) {
            return data;
        }
        data.putString("country", country != null ? country.getValue() : cached("country", ""));
        try {
            data.putInt("entries", Integer.parseInt((entries != null ? entries.getValue()
                    : cached("entries", "2")).trim()));
        } catch (RuntimeException ignored) {
            data.putInt("entries", 2);
        }
        data.putString("issueDate", issue != null ? issue.getValue() : cached("issueDate", DateUtil.today()));
        data.putString("expiryDate", expiry != null ? expiry.getValue() : cached("expiryDate", ""));
        data.putString("authority", authority != null ? authority.getValue() : cached("authority", ""));
        data.putString("code", lockVisaCode ? lockedVisaCode : ScreenCodes.validOrBlank(
                code != null ? code.getValue() : cached("code", "")));
        data.putString("visaName", lockVisaName ? lockedVisaName
                : (visaName != null ? visaName.getValue() : cached("visaName", "")).trim());
        return data;
    }

    private void saveTemplate() {
        captureFields();
        CompoundTag data = form();
        // Templates never pin a code: every issued visa gets its own code.
        data.remove("code");
        String name = templateName == null ? currentTemplateName() : templateName.getValue();
        data.putString("presetName", name.isBlank() ? Component.translatable("screen.veriborder.visa.template_fallback").getString() : name);
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "save_template", data, targetSlot));
    }

    private void deleteTemplate() {
        CompoundTag data = new CompoundTag();
        data.putString("presetName", templateName == null ? currentTemplateName() : templateName.getValue());
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "delete_preset", data, targetSlot));
    }

    private void issue(boolean all) {
        if (!selfService) {
            String raw = (code != null ? code.getValue() : cached("code", "")).trim();
            if (!raw.isEmpty() && !ScreenCodes.isValid(raw)) {
                codeError = "message.veriborder.code.invalid";
                return;
            }
        }
        codeError = "";
        CompoundTag data = form();
        if (all && !selfService) {
            // Batch templates must each receive a distinct generated code.
            data.remove("code");
        }
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos,
                all && !selfService ? "issue_all_visas" : "issue_visa", data, targetSlot));
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
        header(graphics, Component.translatable(deviceType.translationKey()).getString(),
                Component.translatable(deviceType.descriptionKey()).getString());
        graphics.drawString(font, Component.translatable("screen.veriborder.visa.target"), left + 24, top + 50,
                Ui.DIM, false);
        if (target != null) {
            Ui.documentCard(graphics, left + 24, top + 64, 180, 118, target);
            graphics.drawString(font, Component.translatable("screen.veriborder.visa.slot", targetSlot),
                    left + 24, top + 188, Ui.DIM, false);
        } else {
            Ui.inset(graphics, left + 24, top + 64, 180, 118);
            graphics.drawString(font, Component.translatable("screen.veriborder.visa.no_target"),
                    left + 34, top + 112, Ui.RED, false);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.visa.no_target2"),
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
        Ui.clipped(graphics, Component.translatable("screen.veriborder.visa.hint"), left + 222, top + 68,
                274, Ui.MUTED);
        if (lockValidity) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.lock.validity_line",
                            validityYearsText(), expiry == null ? "" : expiry.getValue()),
                    left + 222, top + 216, 274, Ui.AMBER);
        }
        if (codeError.isBlank()) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.visa.entries_hint"), left + 222,
                    top + 228, 274, Ui.DIM);
        } else {
            Ui.clipped(graphics, Component.translatable(codeError), left + 222,
                    top + 228, 274, Ui.RED);
        }
        Ui.clipped(graphics, Component.translatable("screen.veriborder.visa.batch_hint"), left + 222,
                top + 366, 210, Ui.DIM);
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
                graphics.drawString(font, Ui.ellipsis(values.getString("country"), 120), x + 236, y + 9,
                        Ui.MUTED, false);
                graphics.drawString(font, Ui.ellipsis(values.getString("authority"), width - 370),
                        x + 362, y + 9, Ui.DIM, false);
            }, mouseUiX, mouseUiY);
        }
    }

    private void renderSelfService(GuiGraphics graphics) {
        Ui.clipped(graphics, Component.translatable("screen.veriborder.visa.self_info"),
                left + 222, top + 54, 274, Ui.MUTED);
        if (lockVisaType) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.visa.self_locked",
                            Component.translatable(type.translationKey()).getString()),
                    left + 222, top + 164, 274, Ui.CYAN);
        }
        Ui.clipped(graphics, Component.translatable("screen.veriborder.visa.self_authority",
                machineAuthority), left + 222, top + 188, 274, Ui.DIM);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.visa.self_code"),
                left + 222, top + 204, 274, Ui.DIM);
        if (lockValidity) {
            String issueValue = lockIssueDate ? lockedIssueDate : DateUtil.today();
            Ui.clipped(graphics, Component.translatable("screen.veriborder.lock.validity_line",
                            validityYearsText(), autoExpiry(issueValue)),
                    left + 222, top + 220, 274, Ui.AMBER);
        }
        if (lockVisaName) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.visa.self_name",
                    lockedVisaName), left + 222, top + 236, 274, Ui.CYAN);
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
        if (tab == TAB_TEMPLATES && templateList != null && templateList.mouseClicked(uiX, uiY)) {
            if (templateList.selectedIndex() >= 0) {
                selectTemplate(templateList.selectedIndex());
            }
            return true;
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
