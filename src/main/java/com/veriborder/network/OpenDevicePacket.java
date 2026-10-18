package com.veriborder.network;

import com.veriborder.client.ClientPacketHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class OpenDevicePacket {
    private final BlockPos pos;
    private final String device;
    private final CompoundTag data;

    public OpenDevicePacket(BlockPos pos, String device, CompoundTag data) {
        this.pos = pos;
        this.device = device;
        this.data = data;
    }

    public OpenDevicePacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.device = buffer.readUtf(64);
        this.data = buffer.readNbt();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeUtf(device, 64);
        buffer.writeNbt(data);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandler.handleOpenDevice(pos, device, data)));
        context.get().setPacketHandled(true);
    }
}
