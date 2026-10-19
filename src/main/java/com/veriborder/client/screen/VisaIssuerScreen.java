package com.veriborder.client.screen;

import com.veriborder.block.DeviceType;
import com.veriborder.client.BetterButton;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.doc.DateUtil;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.VisaType;
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

public class VisaIssuerScreen extends VeriScreen {
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
    private final List<CompoundTag> templates = new ArrayList<>();
    private int templateIndex;
    private VisaType type;
    private EditBox country;
    private EditBox entries;
    private EditBox issue;
    private EditBox expiry;
    private EditBox authority;
    private EditBox code;
    private EditBox templateName;
    private BetterButton typeButton;
    private String codeError = "";

    public VisaIssuerScreen(DeviceType deviceType, BlockPos pos, CompoundTag payload) {
        super(Component.translatable(deviceType.translationKey()), 520,
                deviceType == DeviceType.SELF_VISA ? 350 : 360);
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
        this.type = lockVisaType ? lockedVisaType : VisaType.TOURIST;
        ListTag list = payload.getList("presets", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            templates.add(list.getCompound(i));
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

    @Override
    protected void build() {
        if (selfService) {
            buildSelfService();
            return;
        }
        String issueValue = lockIssueDate ? lockedIssueDate : DateUtil.today();
        String expiryValue = lockValidity ? autoExpiry(issueValue) : DateUtil.plusYears(issueValue, 1);
        country = addField(left + 222, top + 90, 170, "field.veriborder.visa_country",
                lockPlace ? lockedPlace : "United States", lockPlace ? "hint.veriborder.locked" : null);
        if (lockPlace) {
            country.setValue(lockedPlace);
            country.setEditable(false);
        }
        typeButton = addRenderableWidget(new BetterButton(left + 400, top + 86, 96, 20,
                Component.translatable(type.translationKey()), button -> {
                    if (lockVisaType) {
                        return;
                    }
                    type = type.next();
                    button.setMessage(Component.translatable(type.translationKey()));
                }, Ui.PURPLE, true));
        if (lockVisaType) {
            typeButton.active = false;
        }
        entries = addField(left + 222, top + 126, 82, "field.veriborder.entries", "2",
                "hint.veriborder.unlimited");
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
                lockAuthority ? lockedAuthority : "Embassy", lockAuthority ? "hint.veriborder.locked" : null);
        if (lockAuthority) {
            authority.setValue(lockedAuthority);
            authority.setEditable(false);
        }
        code = addField(left + 222, top + 198, 274, "field.veriborder.code",
                lockVisaCode ? lockedVisaCode : "", lockVisaCode ? "hint.veriborder.locked" : "hint.veriborder.code");
        code.setMaxLength(8);
        code.setFilter(ScreenCodes::digitsUpTo8);
        if (lockVisaCode) {
            code.setValue(lockedVisaCode);
            code.setEditable(false);
        }
        templateName = addField(left + 222, top + 240, 274, "field.veriborder.template_name",
                templates.isEmpty() ? "Tourist visa" : templates.get(0).getString("name"), "hint.veriborder.preset");
        addRenderableWidget(new BetterButton(left + 222, top + 264, 30, 19, Component.literal("◀"),
                button -> moveTemplate(-1), Ui.MUTED, true));
        addRenderableWidget(new BetterButton(left + 256, top + 264, 30, 19, Component.literal("▶"),
                button -> moveTemplate(1), Ui.MUTED, true));
        addRenderableWidget(new BetterButton(left + 292, top + 264, 64, 19,
                Component.translatable("button.veriborder.apply"), button -> applyTemplate(), Ui.PURPLE, true));
        addRenderableWidget(new BetterButton(left + 360, top + 264, 60, 19,
                Component.translatable("button.veriborder.save"), button -> saveTemplate(), Ui.PURPLE, true));
        addRenderableWidget(new BetterButton(left + 424, top + 264, 72, 19,
                Component.translatable("button.veriborder.delete"), button -> deleteTemplate(), Ui.RED, true));

        addRenderableWidget(new BetterButton(left + 222, top + 302, 140, 22,
                Component.translatable("button.veriborder.issue_visa"), button -> issue(false), Ui.GREEN));
        addRenderableWidget(new BetterButton(left + 368, top + 302, 136, 22,
                Component.translatable("button.veriborder.issue_all"), button -> issue(true), Ui.AMBER));
        addRenderableWidget(new BetterButton(left + 444, top + 330, 52, 20,
                Component.translatable("button.veriborder.close"), button -> onClose(), Ui.MUTED, true));
    }

    private void buildSelfService() {
        typeButton = addRenderableWidget(new BetterButton(left + 222, top + 100, 274, 22,
                Component.translatable(type.translationKey()), button -> {
                    if (lockVisaType) {
                        return;
                    }
                    type = type.next();
                    button.setMessage(Component.translatable(type.translationKey()));
                }, Ui.GREEN, true));
        if (lockVisaType) {
            typeButton.active = false;
        }
        addRenderableWidget(new BetterButton(left + 222, top + 136, 274, 24,
                Component.translatable("button.veriborder.issue_visa"), button -> issue(false), Ui.GREEN));
        addRenderableWidget(new BetterButton(left + 444, top + 320, 52, 20,
                Component.translatable("button.veriborder.close"), button -> onClose(), Ui.MUTED, true));
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

    private void moveTemplate(int direction) {
        if (templates.isEmpty()) {
            return;
        }
        templateIndex = (templateIndex + direction + templates.size()) % templates.size();
        templateName.setValue(templates.get(templateIndex).getString("name"));
    }

    private void applyTemplate() {
        if (templates.isEmpty()) {
            return;
        }
        CompoundTag values = templates.get(Math.floorMod(templateIndex, templates.size())).getCompound("values");
        if (!lockPlace) {
            country.setValue(values.getString("country"));
        } else {
            country.setValue(lockedPlace);
        }
        if (!lockVisaType) {
            type = VisaType.byId(values.getString("type"));
        } else {
            type = lockedVisaType;
        }
        entries.setValue(values.contains("entries") ? String.valueOf(values.getInt("entries")) : "2");
        if (!lockIssueDate) {
            issue.setValue(DateUtil.isValid(values.getString("issueDate"))
                    ? values.getString("issueDate") : DateUtil.today());
        } else {
            issue.setValue(lockedIssueDate);
        }
        if (lockValidity) {
            expiry.setValue(autoExpiry(issue.getValue()));
        } else {
            expiry.setValue(DateUtil.isValid(values.getString("expiryDate")) ? values.getString("expiryDate")
                    : DateUtil.plusYears(issue.getValue(), 1));
        }
        if (!lockAuthority) {
            authority.setValue(values.getString("authority"));
        } else {
            authority.setValue(lockedAuthority);
        }
        typeButton.setMessage(Component.translatable(type.translationKey()));
    }

    private CompoundTag form() {
        CompoundTag data = new CompoundTag();
        data.putString("type", type.id());
        if (selfService) {
            return data;
        }
        data.putString("country", country.getValue());
        try {
            data.putInt("entries", Integer.parseInt(entries.getValue().trim()));
        } catch (NumberFormatException ignored) {
            data.putInt("entries", 2);
        }
        data.putString("issueDate", issue.getValue());
        data.putString("expiryDate", expiry.getValue());
        data.putString("authority", authority.getValue());
        data.putString("code", lockVisaCode ? lockedVisaCode : ScreenCodes.validOrBlank(code.getValue()));
        return data;
    }

    private void saveTemplate() {
        CompoundTag data = form();
        // Templates never pin a code: every issued visa gets its own code.
        data.remove("code");
        data.putString("presetName", templateName.getValue().isBlank() ? "Visa template" : templateName.getValue());
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "save_template", data, targetSlot));
    }

    private void deleteTemplate() {
        CompoundTag data = new CompoundTag();
        data.putString("presetName", templateName.getValue());
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "delete_preset", data, targetSlot));
    }

    private void issue(boolean all) {
        if (!selfService) {
            String raw = code.getValue().trim();
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
            return;
        }
        if (lockValidity) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.lock.validity_line",
                            validityYearsText(), expiry == null ? "" : expiry.getValue()),
                    left + 222, top + 66, 274, Ui.AMBER);
        }
        Ui.clipped(graphics, Component.translatable("screen.veriborder.visa.hint"), left + 222, top + 54,
                274, Ui.MUTED);
        if (codeError.isBlank()) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.visa.entries_hint"), left + 222,
                    top + 216, 274, Ui.DIM);
        } else {
            Ui.clipped(graphics, Component.translatable(codeError), left + 222,
                    top + 216, 274, Ui.RED);
        }
        Ui.clipped(graphics, Component.translatable("screen.veriborder.visa.batch_hint"), left + 222,
                top + 330, 210, Ui.DIM);
        if (!templates.isEmpty()) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.templates",
                    templates.get(Math.floorMod(templateIndex, templates.size())).getString("name")),
                    left + 222, top + 290, 274, Ui.DIM);
        }
    }
}
