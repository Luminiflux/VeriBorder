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
        super(Component.translatable("screen.veriborder.gate"), 420, 286);
        this.device = device;
        this.payload = payload;
        this.accent = device.accentColor();
    }

    @Override
    protected void build() {
        addRenderableWidget(new BetterButton(left + 20, top + 254, 110, 20,
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
        graphics.drawCenteredString(font, Ui.ellipsis(Component.translatable("screen.veriborder.gate.codes",
                gateCodeCount()).getString(), hintWidth), cx, top + 176, Ui.AMBER);
        graphics.drawCenteredString(font, Ui.ellipsis(Component.translatable("screen.veriborder.gate.lights").getString(),
                hintWidth), cx, top + 194, Ui.DIM);
        VerificationResult check = payload.contains("check")
                ? VerificationResult.load(payload.getCompound("check")) : null;
        Ui.drawCheck(graphics, check, left + 12, top + 210, imageWidth - 24, 18);
    }

    /** Mode summary for this gate: all / visa / endorsement / both. */
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
        ListTag list = payload.getList("gateCodes", Tag.TAG_STRING);
        int count = 0;
        for (int i = 0; i < list.size(); i++) {
            if (ScreenCodes.isValid(list.getString(i).trim())) {
                count++;
            }
        }
        return count;
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
                    return "all";
                }
            }
        }
        return "all";
    }
}
