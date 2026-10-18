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
    private final List<CompoundTag> templates = new ArrayList<>();
    private int templateIndex;
    private VisaType type = VisaType.TOURIST;
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
        ListTag list = payload.getList("presets", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            templates.add(list.getCompound(i));
        }
        this.accent = deviceType.accentColor();
    }

    @Override
    protected void build() {
        if (selfService) {
            buildSelfService();
            return;
        }
        country = addField(left + 222, top + 90, 170, "field.veriborder.visa_country", "United States");
        typeButton = addRenderableWidget(new BetterButton(left + 400, top + 86, 96, 20,
                Component.translatable(type.translationKey()), button -> {
                    type = type.next();
                    typeButton.setMessage(Component.translatable(type.translationKey()));
                }, Ui.PURPLE, true));
        entries = addField(left + 222, top + 126, 82, "field.veriborder.entries", "2",
                "hint.veriborder.unlimited");
        issue = addField(left + 312, top + 126, 184, "field.veriborder.issue", DateUtil.today(),
                "hint.veriborder.date");
        expiry = addField(left + 222, top + 162, 170, "field.veriborder.expiry",
                DateUtil.plusYears(DateUtil.today(), 1), "hint.veriborder.date");
        authority = addField(left + 400, top + 162, 96, "field.veriborder.authority",
                lockAuthority ? lockedAuthority : "Embassy", lockAuthority ? "hint.veriborder.locked" : null);
        if (lockAuthority) {
            authority.setValue(lockedAuthority);
            authority.setEditable(false);
        }
        code = addField(left + 222, top + 198, 274, "field.veriborder.code", "",
                "hint.veriborder.code");
        code.setMaxLength(8);
        code.setFilter(ScreenCodes::digitsUpTo8);
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
                    type = type.next();
                    button.setMessage(Component.translatable(type.translationKey()));
                }, Ui.GREEN, true));
        addRenderableWidget(new BetterButton(left + 222, top + 136, 274, 24,
                Component.translatable("button.veriborder.issue_visa"), button -> issue(false), Ui.GREEN));
        addRenderableWidget(new BetterButton(left + 444, top + 320, 52, 20,
                Component.translatable("button.veriborder.close"), button -> onClose(), Ui.MUTED, true));
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
        country.setValue(values.getString("country"));
        type = VisaType.byId(values.getString("type"));
        entries.setValue(values.contains("entries") ? String.valueOf(values.getInt("entries")) : "2");
        issue.setValue(DateUtil.isValid(values.getString("issueDate")) ? values.getString("issueDate") : DateUtil.today());
        expiry.setValue(DateUtil.isValid(values.getString("expiryDate")) ? values.getString("expiryDate")
                : DateUtil.plusYears(DateUtil.today(), 1));
        if (!lockAuthority) {
            authority.setValue(values.getString("authority"));
        } else {
            authority.setValue(lockedAuthority);
        }
        typeButton.setMessage(Component.translatable(type.translationKey()));
    }

    private CompoundTag form() {
        CompoundTag data = new CompoundTag();
        if (selfService) {
            data.putString("type", type.id());
            return data;
        }
        data.putString("country", country.getValue());
        data.putString("type", type.id());
        try {
            data.putInt("entries", Integer.parseInt(entries.getValue().trim()));
        } catch (NumberFormatException ignored) {
            data.putInt("entries", 2);
        }
        data.putString("issueDate", issue.getValue());
        data.putString("expiryDate", expiry.getValue());
        data.putString("authority", authority.getValue());
        data.putString("code", ScreenCodes.validOrBlank(code.getValue()));
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
            Ui.clipped(graphics, Component.translatable("screen.veriborder.visa.self_authority",
                    machineAuthority), left + 222, top + 188, 274, Ui.DIM);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.visa.self_code"),
                    left + 222, top + 204, 274, Ui.DIM);
            return;
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
