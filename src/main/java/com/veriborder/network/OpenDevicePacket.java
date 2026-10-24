package com.veriborder.network;

import com.veriborder.api.OpenScreenEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → client "open this device screen".
 *
 * <p>Protocol 2 hardening: {@code device} and {@code data} are normalised on both
 * the constructor and the decode path, so a malformed or legacy payload can never
 * hand {@code null} to the screen router ({@code readNbt()} returns null for an
 * empty buffer / a tag of the wrong type).</p>
 */
public class OpenDevicePacket {
    private final BlockPos pos;
    private final String device;
    private final CompoundTag data;

    public OpenDevicePacket(BlockPos pos, String device, CompoundTag data) {
        this.pos = pos == null ? BlockPos.ZERO : pos;
        this.device = device == null ? "" : device;
        this.data = data == null ? new CompoundTag() : data;
    }

    public OpenDevicePacket(FriendlyByteBuf buffer) {
        this(buffer.readBlockPos(), buffer.readUtf(64), buffer.readNbt());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeUtf(device, 64);
        buffer.writeNbt(data);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> MinecraftForge.EVENT_BUS.post(
                new OpenScreenEvent(OpenScreenEvent.Kind.DEVICE, pos, device, data)));
        context.get().setPacketHandled(true);
    }

    public BlockPos pos() {
        return pos;
    }

    public String device() {
        return device;
    }

    /** Never {@code null}; an empty compound when the sender had no payload. */
    public CompoundTag data() {
        return data;
    }
}
