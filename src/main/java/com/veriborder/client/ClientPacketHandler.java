package com.veriborder.client;

import com.veriborder.block.DeviceType;
import com.veriborder.client.screen.BlacklistScreen;
import com.veriborder.client.screen.DeviceConfigScreen;
import com.veriborder.client.screen.EGateScreen;
import com.veriborder.client.screen.EndorsementScreen;
import com.veriborder.client.screen.IssuerScreen;
import com.veriborder.client.screen.LossScreen;
import com.veriborder.client.screen.ModifierScreen;
import com.veriborder.client.screen.OfficerScreen;
import com.veriborder.client.screen.PrintPreviewScreen;
import com.veriborder.client.screen.ReaderScreen;
import com.veriborder.client.screen.RenewalScreen;
import com.veriborder.client.screen.VisaIssuerScreen;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.VerificationResult;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public final class ClientPacketHandler {
    private ClientPacketHandler() {
    }

    public static void handleOpenDevice(BlockPos pos, String device, CompoundTag data) {
        if (data == null) {
            return;
        }
        if ("modifier".equals(device)) {
            if (data.getList("docs", CompoundTag.TAG_COMPOUND).isEmpty()) {
                Minecraft.getInstance().setScreen(new com.veriborder.client.screen.GeneratorScreen());
            } else {
                Minecraft.getInstance().setScreen(new ModifierScreen(data));
            }
            return;
        }
        if ("config".equals(device)) {
            // Brush-only terminal configuration, sent by DeviceService.openConfig.
            DeviceType configType = DeviceType.byId(data.getString("deviceType"));
            Minecraft.getInstance().setScreen(new DeviceConfigScreen(configType, pos, data, data.getInt("slot")));
            return;
        }
        if (data.getBoolean("officer") && isOfficerConsole(device, data)) {
            // Operator console (sessions, logs, clear, refresh). The server is
            // authoritative for the OP check; the client never decides that.
            // A manual terminal without officer=true still opens the reader below.
            Minecraft.getInstance().setScreen(new OfficerScreen(pos, data));
            return;
        }
        DeviceType type = DeviceType.byId(device);
        DocumentData doc = null;
        if (data.contains("doc")) {
            try {
                doc = DocumentData.fromTag(data.getCompound("doc").copy());
            } catch (RuntimeException ignored) {
                doc = null;
            }
        }
        VerificationResult check = data.contains("check") ? VerificationResult.load(data.getCompound("check")) : null;
        int slot = data.getInt("slot");
        ItemStack stack = doc == null ? ItemStack.EMPTY : new ItemStack(switch (doc.kind()) {
            case ID_CARD -> com.veriborder.registry.ModItems.ID_CARD.get();
            case PASSPORT -> com.veriborder.registry.ModItems.PASSPORT.get();
            case PERMIT -> com.veriborder.registry.ModItems.HK_MACAO_PERMIT.get();
        });
        switch (type.screen()) {
            case ISSUER_ID, ISSUER_PASSPORT, ISSUER_PERMIT ->
                    Minecraft.getInstance().setScreen(new IssuerScreen(type, pos, data));
            case VISA_ISSUER -> {
                data.putBoolean("selfService", type == DeviceType.SELF_VISA);
                if (type == DeviceType.SELF_VISA && data.getString("machineAuthority").isBlank()) {
                    String authority = data.getString("lockedAuthority").trim();
                    if (authority.isBlank()) {
                        authority = data.getString("country").trim() + "驻华使领馆";
                    }
                    data.putString("machineAuthority", authority);
                }
                Minecraft.getInstance().setScreen(new VisaIssuerScreen(type, pos, data));
            }
            case ENDORSEMENT -> {
                if (type == DeviceType.SELF_ENDORSEMENT) {
                    data.putBoolean("selfService", true);
                    if (data.getString("machineAuthority").isBlank()) {
                        String authority = data.getString("lockedAuthority").trim();
                        if (authority.isBlank()) {
                            authority = data.getString("country").trim() + "出入境管理局";
                        }
                        data.putString("machineAuthority", authority);
                    }
                } else {
                    data.putBoolean("selfService", false);
                }
                Minecraft.getInstance().setScreen(new EndorsementScreen(type, pos, data));
            }
            case READER -> Minecraft.getInstance().setScreen(new ReaderScreen(type, pos, stack, doc, check, slot, data));
            case STAMP_CONFIG, STAMP_HISTORY ->
                    Minecraft.getInstance().setScreen(new ReaderScreen(type, pos, stack, doc, check, slot, data));
            case LOSS -> Minecraft.getInstance().setScreen(new LossScreen(pos, slot, doc, check));
            case RENEWAL -> Minecraft.getInstance().setScreen(new RenewalScreen(pos, slot, doc));
            case PRINT -> Minecraft.getInstance().setScreen(new PrintPreviewScreen(doc));
            case BLACKLIST -> Minecraft.getInstance().setScreen(new BlacklistScreen(data));
            case EGATE -> Minecraft.getInstance().setScreen(new EGateScreen(type, data));
        }
    }

    /** Officer payloads are only honoured on a manual verification terminal. */
    private static boolean isOfficerConsole(String device, CompoundTag data) {
        // DeviceService.openOfficer sends the payload under the pseudo device id
        // "officer" (like "config") and also tags deviceType=manual_terminal.
        return "officer".equals(device)
                || DeviceType.MANUAL_TERMINAL.id().equals(device)
                || DeviceType.MANUAL_TERMINAL.id().equals(data.getString("deviceType"));
    }
}
