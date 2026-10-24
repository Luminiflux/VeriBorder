package com.veriborder.client.screen;

import com.veriborder.block.DeviceType;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.VerificationResult;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Device-hosted reader (readers, stamp config and stamp history terminals).
 *
 * <p>The reader is a thin host around the shared 26.3 {@link DocumentViewScreen}:
 * it contributes the device title, the scan target and the verification banner,
 * so every reader terminal exposes exactly the same seven pages as the held
 * document and the admin terminal.</p>
 */
public class ReaderScreen extends DocumentViewScreen {
    private final DeviceType device;

    public ReaderScreen(DeviceType device, BlockPos pos, ItemStack stack, DocumentData doc,
                        VerificationResult check, int slot, CompoundTag payload) {
        super(stack, doc, check);
        this.device = device;
        withHeader(Component.translatable(device.translationKey()).getString(),
                subtitle(device, payload))
                .withPrint(true)
                .initialTab(device == DeviceType.STAMP_HISTORY ? TAB_STAMPS : TAB_IDENTITY);
    }

    private static String subtitle(DeviceType device, CompoundTag payload) {
        String description = Component.translatable(device.descriptionKey()).getString();
        if (device == DeviceType.AIRPORT_CHECKIN && payload != null) {
            String destination = payload.getString("destination");
            if (!destination.isBlank()) {
                return description + " \u00B7 " + Component.translatable(
                        "screen.veriborder.airport.destination", destination).getString();
            }
        }
        return description;
    }

    public DeviceType device() {
        return device;
    }
}
