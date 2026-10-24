package com.veriborder.network;

import com.veriborder.block.DeviceService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client → server "save this modified document". Protocol 2: null-safe payload. */
public class ModifierSavePacket {
    private final int slot;
    private final CompoundTag data;

    public ModifierSavePacket(int slot, CompoundTag data) {
        this.slot = slot;
        this.data = data == null ? new CompoundTag() : data;
    }

    public ModifierSavePacket(FriendlyByteBuf buffer) {
        this(buffer.readVarInt(), buffer.readNbt());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(slot);
        buffer.writeNbt(data);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                DeviceService.saveFromModifier(player, slot, data);
            }
        });
        ctx.setPacketHandled(true);
    }

    public int slot() {
        return slot;
    }

    /** Never {@code null}. */
    public CompoundTag data() {
        return data;
    }
}
