package com.veriborder.client.screen;

import com.veriborder.block.DeviceType;
import com.veriborder.client.BetterButton;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.doc.VerificationResult;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.util.Locale;

public class EGateScreen extends VeriScreen {
    private final DeviceType device;
    private final CompoundTag payload;

    public EGateScreen(DeviceType device, CompoundTag payload) {
        super(Component.translatable("screen.veriborder.gate"), 420, 300);
        this.device = device;
        this.payload = payload;
        this.accent = device.accentColor();
    }

    @Override
    protected void build() {
        addRenderableWidget(new BetterButton(left + 20, top + 268, 110, 20,
                Component.translatable("button.veriborder.close"), button -> onClose(), accent, true));
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, Component.translatable(device.translationKey()).getString(),
                Component.translatable(device.descriptionKey()).getString());
        String state = payload.getString("gateState");
        int color = switch (state) {
            case "GRANTED" -> Ui.GREEN;
            case "DENIED" -> Ui.RED;
            case "VERIFYING" -> Ui.AMBER;
            default -> accent;
        };
        int cx = left + imageWidth / 2;
        graphics.fill(cx - 58, top + 52, cx + 58, top + 116, 0xFF0B141E);
        graphics.renderOutline(cx - 58, top + 52, 116, 64, color);
        graphics.fill(cx - 1, top + 60, cx + 1, top + 108, color);
        graphics.fill(cx - 38, top + 62, cx + 38, top + 64, color);
        graphics.fill(cx - 38, top + 104, cx + 38, top + 106, color);
        String gateKey = "gate.veriborder." + (state == null || state.isBlank() ? "idle" : state.toLowerCase());
        graphics.drawCenteredString(font, Component.translatable(gateKey), cx, top + 78, color);
        int hintWidth = imageWidth - 24;
        graphics.drawCenteredString(font, Ui.ellipsis(Component.translatable("screen.veriborder.gate.hint").getString(),
                hintWidth), cx, top + 124, Ui.MUTED);
        graphics.drawCenteredString(font, Ui.ellipsis(Component.translatable("screen.veriborder.gate.hint2").getString(),
                hintWidth), cx, top + 136, Ui.DIM);
        graphics.drawCenteredString(font, Ui.ellipsis(Component.translatable("screen.veriborder.gate.hint3").getString(),
                hintWidth), cx, top + 148, Ui.DIM);
        graphics.drawCenteredString(font, Ui.ellipsis(bindingText(), hintWidth), cx, top + 164, accent);
        // 26.3 read-only summary: directional allowed-country whitelists and the
        // entry/exit code lists. The gate configuration screens edit these.
        graphics.drawCenteredString(font, Ui.ellipsis(Component.translatable(
                "screen.veriborder.gate.entry_whitelist", whitelistText(false)).getString(),
                hintWidth), cx, top + 176, Ui.MUTED);
        graphics.drawCenteredString(font, Ui.ellipsis(Component.translatable(
                "screen.veriborder.gate.exit_whitelist", whitelistText(true)).getString(),
                hintWidth), cx, top + 188, Ui.MUTED);
        graphics.drawCenteredString(font, Ui.ellipsis(Component.translatable(
                "screen.veriborder.gate.codes", gateCodeCount()).getString(),
                hintWidth), cx, top + 200, Ui.AMBER);
        graphics.drawCenteredString(font, Ui.ellipsis(exitCodesText(), hintWidth), cx, top + 212, Ui.DIM);
        graphics.drawCenteredString(font, Ui.ellipsis(Component.translatable("screen.veriborder.gate.lights").getString(),
                hintWidth), cx, top + 224, Ui.DIM);
        VerificationResult check = payload.contains("check")
                ? VerificationResult.load(payload.getCompound("check")) : null;
        // The traveller standing at the gate sees a plain sentence; the reason
        // codes remain in the payload for the officer console/border log.
        Ui.drawCheck(graphics, check, left + 12, top + 232, imageWidth - 24, 18, true);
    }

    /** Mode summary for this gate: visa / endorsement / both (26.3 has no all). */
    private String bindingText() {
        if (device == DeviceType.EXIT_GATE) {
            // Exit control only checks the document itself; no visa/endorsement.
            return Component.translatable("screen.veriborder.gate.binding.exit").getString();
        }
        String mode = normalizeMode(payload.getString("gateMode"));
        return Component.translatable("screen.veriborder.gate.binding." + mode).getString();
    }

    private int gateCodeCount() {
        if (device == DeviceType.EXIT_GATE) {
            return 0;
        }
        ListTag list = device == DeviceType.DUAL_GATE
                ? dualGateConfig().getList("entryCodes", Tag.TAG_STRING)
                : payload.getList("gateCodes", Tag.TAG_STRING);
        int count = 0;
        for (int i = 0; i < list.size(); i++) {
            if (ScreenCodes.isValid(list.getString(i).trim())) {
                count++;
            }
        }
        return count;
    }

    /** Allowed-country whitelist text for one direction (read-only summary). */
    private String whitelistText(boolean exitStage) {
        ListTag list;
        if (device == DeviceType.DUAL_GATE) {
            // 26.4 fix: the entry whitelist key is "entryCountryCodes". The old
            // "countryCodes" key never existed in the dual-gate schema, so the
            // summary always claimed "none" even when the gate had a whitelist.
            list = dualGateConfig().getList(
                    exitStage ? "exitCountryCodes" : "entryCountryCodes", Tag.TAG_STRING);
        } else {
            list = payload.getList(exitStage ? "exitCountryCodes" : "entryCountryCodes", Tag.TAG_STRING);
        }
        if (list.isEmpty()) {
            return Component.translatable("screen.veriborder.config.country_whitelist_none").getString();
        }
        return String.join(" · ", codes(list));
    }

    /** Exit-code summary; an empty list means "no restriction" (26.3). */
    private String exitCodesText() {
        ListTag list = device == DeviceType.DUAL_GATE
                ? dualGateConfig().getList("exitCodes", Tag.TAG_STRING)
                : payload.getList("exitCodes", Tag.TAG_STRING);
        if (list.isEmpty()) {
            return Component.translatable("screen.veriborder.config.exit_codes_unrestricted").getString();
        }
        return Component.translatable("screen.veriborder.gate.exit_codes", list.size()).getString();
    }

    private CompoundTag dualGateConfig() {
        return payload.getCompound("dualGate");
    }

    private static java.util.List<String> codes(ListTag list) {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String value = list.getString(i).trim().toUpperCase(Locale.ROOT);
            if (!value.isEmpty() && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private static String normalizeMode(String value) {
        if (value != null) {
            switch (value.trim().toLowerCase(Locale.ROOT)) {
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
                    // 26.3: "all" and any unknown legacy value display as both.
                    return "both";
                }
            }
        }
        return "both";
    }
}
