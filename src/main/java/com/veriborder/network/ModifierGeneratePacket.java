package com.veriborder.network;

import com.veriborder.block.DeviceService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ModifierGeneratePacket {
    private final CompoundTag data;

    public ModifierGeneratePacket(CompoundTag data) {
        this.data = data;
    }

    public ModifierGeneratePacket(FriendlyByteBuf buffer) {
        this.data = buffer.readNbt();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeNbt(data);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                DeviceService.generateFromModifier(player, data == null ? new CompoundTag() : data);
            }
        });
        ctx.setPacketHandled(true);
    }
}
