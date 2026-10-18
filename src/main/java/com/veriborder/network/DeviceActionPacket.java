package com.veriborder.network;

import com.veriborder.block.DeviceService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class DeviceActionPacket {
    private final BlockPos pos;
    private final String action;
    private final CompoundTag data;
    private final int slot;

    public DeviceActionPacket(BlockPos pos, String action, CompoundTag data, int slot) {
        this.pos = pos;
        this.action = action;
        this.data = data;
        this.slot = slot;
    }

    public DeviceActionPacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.action = buffer.readUtf(64);
        this.data = buffer.readNbt();
        this.slot = buffer.readVarInt();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeUtf(action, 64);
        buffer.writeNbt(data);
        buffer.writeVarInt(slot);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                DeviceService.handleAction(player, pos, action, data == null ? new CompoundTag() : data, slot);
            }
        });
        ctx.setPacketHandled(true);
    }
}
