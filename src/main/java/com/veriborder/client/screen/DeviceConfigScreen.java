package com.veriborder.client.screen;

import com.veriborder.block.DeviceType;
import com.veriborder.client.BetterButton;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.network.DeviceActionPacket;
import com.veriborder.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Brush-only terminal configuration. Reached exclusively through
 * {@code DeviceService.openConfig} ("config" packet), which requires the
 * Device Brush in either hand.
 */
public class DeviceConfigScreen extends VeriScreen {
    private static final String[] GATE_MODES = {
            "all", "visa", "endorsement", "both"
    };

    private final DeviceType device;
    private final BlockPos pos;
    private final int slot;
    private final CompoundTag payload;
    private final boolean bindingGate;
    private final boolean issuer;
    private final int firstFieldY;
    private final int saveY;
    private final List<String> gateCodes = new ArrayList<>();

    private EditBox port;
    private EditBox country;
    private EditBox officer;
    private EditBox airportCountry;
    private EditBox gateCode;
    private EditBox lockedName;
    private EditBox lockedAuthorityField;
    private EditBox issuedCodeField;
    private EditBox lockedEndorsementCodeField;

    private String gateMode = "all";
    private String codeError = "";
    private boolean lockDocumentName;
    private boolean lockAuthority;
    private boolean lockEndorsementCode;
    private final boolean selfKiosk;
    private final boolean permitIssuer;

    public DeviceConfigScreen(DeviceType device, BlockPos pos, CompoundTag payload, int slot) {
        super(Component.translatable("screen.veriborder.config"), 440,
                device.isGate() && device != DeviceType.EXIT_GATE ? 378
                        : device == DeviceType.SELF_VISA || device == DeviceType.SELF_ENDORSEMENT ? 392
                        : device == DeviceType.PERMIT_ISSUER ? 428
                        : device.isIssuer() ? 356 : 268);
        this.device = device;
        this.pos = pos;
        this.slot = slot;
        this.payload = payload;
        this.accent = device.accentColor();
        // Exit gates intentionally have no visa/endorsement binding.
        this.bindingGate = device.isGate() && device != DeviceType.EXIT_GATE;
        this.issuer = device.isIssuer();
        this.selfKiosk = device == DeviceType.SELF_VISA || device == DeviceType.SELF_ENDORSEMENT;
        this.permitIssuer = device == DeviceType.PERMIT_ISSUER;
        this.gateMode = normalizeMode(payload.getString("gateMode"));
        this.lockDocumentName = payload.getBoolean("lockDocumentName");
        this.lockAuthority = payload.getBoolean("lockAuthority");
        this.lockEndorsementCode = payload.getBoolean("lockEndorsementCode");
        readGateCodes();
        this.firstFieldY = bindingGate ? 204 : 76;
        this.saveY = bindingGate ? 350
                : selfKiosk ? 364
                : permitIssuer ? 396
                : issuer ? 330 : firstFieldY + 156;
    }

    @Override
    protected void build() {
        int x = left + 24;
        if (bindingGate) {
            addRenderableWidget(new BetterButton(x, top + 56, 392, 20, gateModeLabel(), button -> {
                gateMode = nextMode(gateMode);
                button.setMessage(gateModeLabel());
            }, accent, true));
            gateCode = addField(x, top + 104, 300, "field.veriborder.gate_code", "",
                    "hint.veriborder.code_8");
            gateCode.setMaxLength(8);
            gateCode.setFilter(ScreenCodes::digitsUpTo8);
            addRenderableWidget(new BetterButton(x + 308, top + 100, 84, 18,
                    Component.translatable("button.veriborder.add"), button -> addGateCode(), Ui.GREEN, true));
            addRenderableWidget(new BetterButton(x, top + 148, 120, 18,
                    Component.translatable("button.veriborder.remove_last"), button -> removeLastCode(), Ui.AMBER, true));
            addRenderableWidget(new BetterButton(x + 130, top + 148, 100, 18,
                    Component.translatable("button.veriborder.clear_all"), button -> {
                        gateCodes.clear();
                        codeError = "";
                    }, Ui.RED, true));
        }
        port = addField(x, top + firstFieldY, 392, "field.veriborder.port", payload.getString("port"));
        country = addField(x, top + firstFieldY + 36, 392, "field.veriborder.country",
                payload.getString("country"));
        officer = addField(x, top + firstFieldY + 72, 392, "field.veriborder.officer",
                payload.getString("officer"));
        airportCountry = addField(x, top + firstFieldY + 108, 392, "field.veriborder.airport_country",
                payload.getString("airportCountry"), "hint.veriborder.airport");
        if (issuer) {
            addRenderableWidget(new BetterButton(x, top + 222, 190, 18, lockNameLabel(), button -> {
                lockDocumentName = !lockDocumentName;
                button.setMessage(lockNameLabel());
            }, Ui.AMBER, true));
            addRenderableWidget(new BetterButton(x + 202, top + 222, 190, 18, lockAuthorityLabel(), button -> {
                lockAuthority = !lockAuthority;
                button.setMessage(lockAuthorityLabel());
            }, Ui.AMBER, true));
            lockedName = addField(x, top + 256, 392, "field.veriborder.locked_name",
                    payload.getString("lockedDocumentName"), "hint.veriborder.lock_value");
            lockedAuthorityField = addField(x, top + 292, 392, "field.veriborder.locked_authority",
                    payload.getString("lockedAuthority"), "hint.veriborder.lock_value");
            if (selfKiosk) {
                issuedCodeField = addField(x, top + 328, 392, "field.veriborder.issued_code",
                        payload.getString("issuedCode"), "hint.veriborder.machine_code");
                issuedCodeField.setMaxLength(8);
                issuedCodeField.setFilter(ScreenCodes::digitsUpTo8);
            }
            if (permitIssuer) {
                addRenderableWidget(new BetterButton(x, top + 328, 392, 18,
                        lockEndorsementCodeLabel(), button -> {
                            lockEndorsementCode = !lockEndorsementCode;
                            button.setMessage(lockEndorsementCodeLabel());
                        }, Ui.AMBER, true));
                lockedEndorsementCodeField = addField(x, top + 362, 392,
                        "field.veriborder.locked_endorsement_code",
                        payload.getString("lockedEndorsementCode"), "hint.veriborder.lock_code");
                lockedEndorsementCodeField.setMaxLength(8);
                lockedEndorsementCodeField.setFilter(ScreenCodes::digitsUpTo8);
            }
        }
        addRenderableWidget(new BetterButton(x, top + saveY, 120, 20,
                Component.translatable("button.veriborder.save"), button -> save(), accent));
        addRenderableWidget(new BetterButton(x + 130, top + saveY, 100, 20,
                Component.translatable("button.veriborder.cancel"), button -> onClose(), Ui.MUTED, true));
    }

    private void addGateCode() {
        String value = gateCode == null ? "" : gateCode.getValue().trim();
        if (!ScreenCodes.isValid(value)) {
            codeError = "message.veriborder.code.invalid";
            return;
        }
        if (gateCodes.contains(value)) {
            codeError = "message.veriborder.code.duplicate";
            return;
        }
        gateCodes.add(value);
        gateCode.setValue("");
        codeError = "";
    }

    private void removeLastCode() {
        if (!gateCodes.isEmpty()) {
            gateCodes.remove(gateCodes.size() - 1);
            codeError = "";
        }
    }

    private void readGateCodes() {
        ListTag list = payload.getList("gateCodes", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            String value = list.getString(i).trim();
            if (ScreenCodes.isValid(value) && !gateCodes.contains(value)) {
                gateCodes.add(value);
            }
        }
    }

    private ListTag gateCodesTag() {
        ListTag list = new ListTag();
        for (String code : gateCodes) {
            list.add(StringTag.valueOf(code));
        }
        return list;
    }

    private Component gateModeLabel() {
        return Component.translatable("screen.veriborder.config.gate_mode." + gateMode);
    }

    private Component lockNameLabel() {
        return Component.translatable("screen.veriborder.config.lock_name."
                + (lockDocumentName ? "on" : "off"));
    }

    private Component lockAuthorityLabel() {
        return Component.translatable("screen.veriborder.config.lock_authority."
                + (lockAuthority ? "on" : "off"));
    }

    private Component lockEndorsementCodeLabel() {
        return Component.translatable("screen.veriborder.config.lock_endorsement_code."
                + (lockEndorsementCode ? "on" : "off"));
    }

    private void save() {
        CompoundTag data = new CompoundTag();
        data.putString("port", port.getValue());
        data.putString("country", country.getValue());
        data.putString("officer", officer.getValue());
        data.putString("airportCountry", airportCountry.getValue());
        if (device.isGate()) {
            if (bindingGate) {
                data.putString("gateMode", gateMode);
                data.put("gateCodes", gateCodesTag());
                // Legacy visa/endorsement type binding is no longer used.
                data.putString("requiredVisaType", "");
                data.putString("requiredEndorsementType", "");
            } else {
                // Exit gate: explicitly clear any stale binding.
                data.putString("gateMode", "all");
                data.put("gateCodes", new ListTag());
                data.putString("requiredVisaType", "");
                data.putString("requiredEndorsementType", "");
            }
        }
        if (issuer) {
            data.putBoolean("lockDocumentName", lockDocumentName);
            data.putString("lockedDocumentName", lockedName == null ? "" : lockedName.getValue().trim());
            data.putBoolean("lockAuthority", lockAuthority);
            data.putString("lockedAuthority",
                    lockedAuthorityField == null ? "" : lockedAuthorityField.getValue().trim());
            if (selfKiosk) {
                data.putString("issuedCode",
                        issuedCodeField == null ? "" : issuedCodeField.getValue().trim());
            }
            if (permitIssuer) {
                data.putBoolean("lockEndorsementCode", lockEndorsementCode);
                data.putString("lockedEndorsementCode",
                        lockedEndorsementCodeField == null ? "" : lockedEndorsementCodeField.getValue().trim());
            }
        }
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "save_config", data, slot));
        onClose();
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, Component.translatable(device.translationKey()).getString(),
                Component.translatable(device.descriptionKey()).getString());
        Ui.clipped(graphics, Component.translatable("screen.veriborder.config.hint"), left + 24, top + 44,
                392, Ui.MUTED);
        if (bindingGate) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.gate_hint"),
                    left + 24, top + 82, 392, Ui.DIM);
            if (codeError.isBlank()) {
                Ui.clipped(graphics, Component.translatable("screen.veriborder.config.gate_codes",
                        gateCodes.size()).getString(), left + 24, top + 124, 392, Ui.TEXT);
            } else {
                Ui.clipped(graphics, Component.translatable(codeError).getString(),
                        left + 24, top + 124, 392, Ui.RED);
            }
            String summary = gateCodes.isEmpty()
                    ? Component.translatable("screen.veriborder.config.gate_codes_none").getString()
                    : String.join(" · ", gateCodes.subList(Math.max(0, gateCodes.size() - 3), gateCodes.size()));
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.gate_codes_list",
                    summary).getString(), left + 24, top + 136, 392, Ui.MUTED);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.gate_codes_hint"),
                    left + 24, top + 170, 392, Ui.DIM);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.gate_codes_hint2"),
                    left + 24, top + 182, 392, Ui.DIM);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.airport_hint"),
                    left + 24, top + 332, 392, Ui.DIM);
        } else if (device.isGate()) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.exit_gate_hint"),
                    left + 24, top + 58, 392, Ui.AMBER);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.airport_hint"),
                    left + 24, top + 202, 392, Ui.DIM);
        } else {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.airport_hint"),
                    left + 24, top + 202, 392, Ui.DIM);
            if (issuer) {
                Ui.clipped(graphics, Component.translatable("screen.veriborder.config.lock_hint"),
                        left + 24, top + 312, 392, Ui.DIM);
            }
        }
    }

    private static String normalizeMode(String value) {
        if (value != null) {
            String key = value.trim().toLowerCase(Locale.ROOT);
            switch (key) {
                case "visa", "passport" -> {
                    return "visa";
                }
                case "endorsement", "permit" -> {
                    return "endorsement";
                }
                case "both" -> {
                    return "both";
                }
                default -> {
                    return "all";
                }
            }
        }
        return "all";
    }

    private static String nextMode(String value) {
        int index = 0;
        for (int i = 0; i < GATE_MODES.length; i++) {
            if (GATE_MODES[i].equals(value)) {
                index = i;
                break;
            }
        }
        return GATE_MODES[(index + 1) % GATE_MODES.length];
    }
}
