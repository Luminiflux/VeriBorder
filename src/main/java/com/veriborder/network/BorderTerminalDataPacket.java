package com.veriborder.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -> client: the whole border-terminal snapshot (players + documents). */
public class BorderTerminalDataPacket {
    private final CompoundTag data;

    public BorderTerminalDataPacket(CompoundTag data) {
        this.data = data == null ? new CompoundTag() : data;
    }

    public BorderTerminalDataPacket(FriendlyByteBuf buffer) {
        CompoundTag read = buffer.readNbt();
        this.data = read == null ? new CompoundTag() : read;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeNbt(data);
    }

    /** Terminal snapshot; never {@code null} (protocol 2 fallback). */
    public CompoundTag data() {
        return data;
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                new com.veriborder.api.OpenScreenEvent(
                        com.veriborder.api.OpenScreenEvent.Kind.BORDER_TERMINAL, data)));
        context.get().setPacketHandled(true);
    }
}
