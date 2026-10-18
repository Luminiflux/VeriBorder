package com.veriborder.network;

import com.veriborder.VeriBorder;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ModNetwork {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(VeriBorder.MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();

    private static int nextId;

    private ModNetwork() {
    }

    public static void register() {
        CHANNEL.messageBuilder(OpenDevicePacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OpenDevicePacket::encode)
                .decoder(OpenDevicePacket::new)
                .consumerMainThread(OpenDevicePacket::handle)
                .add();
        CHANNEL.messageBuilder(DeviceActionPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(DeviceActionPacket::encode)
                .decoder(DeviceActionPacket::new)
                .consumerMainThread(DeviceActionPacket::handle)
                .add();
        CHANNEL.messageBuilder(ModifierSavePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ModifierSavePacket::encode)
                .decoder(ModifierSavePacket::new)
                .consumerMainThread(ModifierSavePacket::handle)
                .add();
        CHANNEL.messageBuilder(ModifierGeneratePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ModifierGeneratePacket::encode)
                .decoder(ModifierGeneratePacket::new)
                .consumerMainThread(ModifierGeneratePacket::handle)
                .add();
    }
}
