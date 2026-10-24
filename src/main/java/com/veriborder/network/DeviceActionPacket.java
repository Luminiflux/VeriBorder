package com.veriborder.network;

import com.veriborder.block.DeviceService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server device action. Protocol 2: every field is null-safe on both
 * ends ({@code readNbt()} may return null, {@code readUtf} returns "").
 */
public class DeviceActionPacket {
    private final BlockPos pos;
    private final String action;
    private final CompoundTag data;
    private final int slot;

    public DeviceActionPacket(BlockPos pos, String action, CompoundTag data, int slot) {
        this.pos = pos == null ? BlockPos.ZERO : pos;
        this.action = action == null ? "" : action;
        this.data = data == null ? new CompoundTag() : data;
        this.slot = slot;
    }

    public DeviceActionPacket(FriendlyByteBuf buffer) {
        this(buffer.readBlockPos(), buffer.readUtf(64), buffer.readNbt(), buffer.readVarInt());
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
                DeviceService.handleAction(player, pos, action, data, slot);
            }
        });
        ctx.setPacketHandled(true);
    }

    public BlockPos pos() {
        return pos;
    }

    public String action() {
        return action;
    }

    /** Never {@code null}. */
    public CompoundTag data() {
        return data;
    }

    public int slot() {
        return slot;
    }
}
