package com.veriborder.client.screen;

import com.veriborder.block.DeviceType;
import com.veriborder.client.BetterButton;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
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

public class EndorsementScreen extends VeriScreen {
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
    private final int lockedValidityYears;
    private final List<CompoundTag> templates = new ArrayList<>();
    private int templateIndex;
    private EndorsementType type;
    private EditBox entries;
    private EditBox issue;
    private EditBox expiry;
    private EditBox authority;
    private EditBox code;
    private EditBox templateName;
    private BetterButton typeButton;
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
        this.lockedValidityYears = payload.getInt("lockedValidityYears");
        this.type = lockEndorsementType ? lockedEndorsementType : EndorsementType.ONE_YEAR_MULTI;
        ListTag list = payload.getList("presets", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            templates.add(list.getCompound(i));
        }
        this.accent = device.accentColor();
    }

    @Override
    protected void build() {
        if (selfService) {
            buildSelfService();
        } else {
            buildStaff();
        }
        addRenderableWidget(new BetterButton(left + 444, top + 320, 52, 20,
                Component.translatable("button.veriborder.close"), button -> onClose(), Ui.MUTED, true));
    }

    private void buildSelfService() {
        typeButton = addRenderableWidget(new BetterButton(left + 222, top + 100, 274, 22,
                Component.translatable(type.translationKey()), button -> {
                    if (lockEndorsementType) {
                        return;
                    }
                    type = type.next();
                    button.setMessage(Component.translatable(type.translationKey()));
                }, Ui.PURPLE, true));
        if (lockEndorsementType) {
            typeButton.active = false;
        }
        addRenderableWidget(new BetterButton(left + 222, top + 136, 274, 24,
                Component.translatable("button.veriborder.issue_endorsement"), button -> issue(), Ui.GREEN));
    }

    private void buildStaff() {
        String issueValue = lockIssueDate ? lockedIssueDate : DateUtil.today();
        typeButton = addRenderableWidget(new BetterButton(left + 222, top + 86, 170, 20,
                Component.translatable(type.translationKey()), button -> {
                    if (lockEndorsementType) {
                        return;
                    }
                    String previousDefault = String.valueOf(type.defaultEntries());
                    type = type.next();
                    if (entries != null && entries.getValue().trim().equals(previousDefault)) {
                        entries.setValue(String.valueOf(type.defaultEntries()));
                    }
                    if (issue != null && DateUtil.isValid(issue.getValue())) {
                        expiry.setValue(expiryFor(issue.getValue()));
                    }
                    button.setMessage(Component.translatable(type.translationKey()));
                }, Ui.PURPLE, true));
        if (lockEndorsementType) {
            typeButton.active = false;
        }
        entries = addField(left + 404, top + 90, 92, "field.veriborder.entries",
                String.valueOf(type.defaultEntries()), "hint.veriborder.unlimited");
        issue = addField(left + 222, top + 126, 170, "field.veriborder.issue", issueValue,
                lockIssueDate ? "hint.veriborder.locked" : "hint.veriborder.date");
        if (lockIssueDate) {
            issue.setValue(lockedIssueDate);
            issue.setEditable(false);
        }
        expiry = addField(left + 400, top + 126, 96, "field.veriborder.expiry",
                lockValidity ? autoExpiry(issueValue) : DocumentFactory.endorsementExpiry(type, issueValue),
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
        authority = addField(left + 222, top + 162, 274, "field.veriborder.authority",
                lockAuthority ? lockedAuthority : "Exit-Entry Administration",
                lockAuthority ? "hint.veriborder.locked" : null);
        if (lockAuthority) {
            authority.setValue(lockedAuthority);
            authority.setEditable(false);
        }
        code = addField(left + 222, top + 198, 274, "field.veriborder.code", "",
                "hint.veriborder.code");
        code.setMaxLength(8);
        code.setFilter(ScreenCodes::digitsUpTo8);
        templateName = addField(left + 222, top + 240, 274, "field.veriborder.template_name",
                templates.isEmpty() ? "One year multiple" : templates.get(0).getString("name"),
                "hint.veriborder.preset");
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
        addRenderableWidget(new BetterButton(left + 222, top + 304, 150, 22,
                Component.translatable("button.veriborder.issue_endorsement"), button -> issue(), Ui.GREEN));
        addRenderableWidget(new BetterButton(left + 24, top + 304, 82, 20,
                Component.translatable("button.veriborder.renew_endorsement"), button -> renewSelected(), Ui.AMBER));
        addRenderableWidget(new BetterButton(left + 112, top + 304, 82, 20,
                Component.translatable("button.veriborder.revoke_endorsement"), button -> revokeSelected(), Ui.RED));
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

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (!selfService && target != null) {
            double uiX = toUiX(mouseX);
            double uiY = toUiY(mouseY);
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
        if (!lockEndorsementType) {
            type = EndorsementType.byId(values.getString("type"));
        } else {
            type = lockedEndorsementType;
        }
        entries.setValue(String.valueOf(values.contains("entries") ? values.getInt("entries") : type.defaultEntries()));
        if (!lockIssueDate) {
            issue.setValue(DateUtil.isValid(values.getString("issueDate")) ? values.getString("issueDate") : DateUtil.today());
        } else {
            issue.setValue(lockedIssueDate);
        }
        if (lockValidity) {
            expiry.setValue(autoExpiry(issue.getValue()));
        } else {
            expiry.setValue(DateUtil.isValid(values.getString("expiryDate")) ? values.getString("expiryDate")
                    : DocumentFactory.endorsementExpiry(type, issue.getValue()));
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
            // The machine supplies authority, code, entries and dates itself.
            return data;
        }
        try {
            data.putInt("entries", Integer.parseInt(entries.getValue().trim()));
        } catch (NumberFormatException ignored) {
            data.putInt("entries", type.defaultEntries());
        }
        data.putString("issueDate", issue.getValue());
        data.putString("expiryDate", expiry.getValue());
        data.putString("authority", authority.getValue());
        data.putString("code", ScreenCodes.validOrBlank(code.getValue()));
        return data;
    }

    private void saveTemplate() {
        CompoundTag data = form();
        // Templates never pin a code: each issued endorsement receives its own.
        data.remove("code");
        data.putString("presetName", templateName.getValue().isBlank() ? "Endorsement template" : templateName.getValue());
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "save_template", data, targetSlot));
    }

    private void deleteTemplate() {
        CompoundTag data = new CompoundTag();
        data.putString("presetName", templateName.getValue());
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "delete_preset", data, targetSlot));
    }

    private void issue() {
        if (!selfService) {
            String raw = code.getValue().trim();
            if (!raw.isEmpty() && !ScreenCodes.isValid(raw)) {
                codeError = "message.veriborder.code.invalid";
                return;
            }
            codeError = "";
        }
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "issue_endorsement", form(), targetSlot));
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, Component.translatable(device.translationKey()).getString(),
                Component.translatable(device.descriptionKey()).getString());
        graphics.drawString(font, Component.translatable("screen.veriborder.endorsement.target"), left + 24, top + 50,
                Ui.DIM, false);
        if (target != null) {
            Ui.documentCard(graphics, left + 24, top + 64, 180, 112, target);
            if (!selfService) {
                graphics.drawString(font, Component.translatable("screen.veriborder.endorsement.existing"),
                        left + 24, top + 184, Ui.DIM, false);
                java.util.List<EndorsementRecord> records = target.endorsements();
                for (int i = 0; i < Math.min(6, records.size()); i++) {
                    EndorsementRecord record = records.get(i);
                    int rowY = top + 197 + i * 12;
                    boolean selected = i == selectedEndorsement;
                    if (selected) {
                        graphics.fill(left + 24, rowY - 1, left + 204, rowY + 10, 0x552FC6B7);
                    }
                    graphics.drawString(font, Ui.ellipsis(Component.translatable(record.type().translationKey()).getString()
                            + "  " + record.expiryDate(), 176), left + 28, rowY, selected ? Ui.GREEN : Ui.MUTED, false);
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
                                validityYearsText(), autoExpiry(issueValue)),
                        left + 222, top + 224, 274, Ui.AMBER);
            }
            Ui.clipped(graphics, Component.translatable("screen.veriborder.endorsement.self_code"),
                    left + 222, top + 200, 274, Ui.DIM);
            if (!machineAuthority.isBlank()) {
                Ui.clipped(graphics, Component.translatable("screen.veriborder.endorsement.self_authority",
                        machineAuthority), left + 222, top + 212, 274, Ui.CYAN);
            }
            return;
        }
        if (lockValidity) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.lock.validity_line",
                            validityYearsText(), expiry == null ? "" : expiry.getValue()),
                    left + 222, top + 66, 274, Ui.AMBER);
        }
        Ui.clipped(graphics, Component.translatable("screen.veriborder.endorsement.hint"), left + 222, top + 54,
                274, Ui.MUTED);
        if (codeError.isBlank()) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.visa.entries_hint"), left + 222,
                    top + 216, 274, Ui.DIM);
        } else {
            Ui.clipped(graphics, Component.translatable(codeError), left + 222,
                    top + 216, 274, Ui.RED);
        }
        Ui.clipped(graphics, Component.translatable("screen.veriborder.endorsement.types"), left + 222,
                top + 330, 210, Ui.DIM);
        if (!templates.isEmpty()) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.templates",
                    templates.get(Math.floorMod(templateIndex, templates.size())).getString("name")),
                    left + 222, top + 290, 274, Ui.DIM);
        }
    }
}
