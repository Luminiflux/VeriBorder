package com.veriborder.network;

import com.veriborder.border.BorderTerminalService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client -> server: one border-terminal action. */
public class BorderTerminalActionPacket {
    private final String action;
    private final String target;
    private final int docSlot;
    private final CompoundTag payload;

    public BorderTerminalActionPacket(String action, String target, int docSlot, CompoundTag payload) {
        this.action = action == null ? "" : action;
        this.target = target == null ? "" : target;
        this.docSlot = docSlot;
        this.payload = payload == null ? new CompoundTag() : payload;
    }

    public BorderTerminalActionPacket(FriendlyByteBuf buffer) {
        this.action = buffer.readUtf(32);
        this.target = buffer.readUtf(64);
        this.docSlot = buffer.readVarInt();
        CompoundTag read = buffer.readNbt();
        this.payload = read == null ? new CompoundTag() : read;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(action, 32);
        buffer.writeUtf(target, 64);
        buffer.writeVarInt(docSlot);
        buffer.writeNbt(payload);
    }

    public String action() {
        return action;
    }

    public String target() {
        return target;
    }

    public int docSlot() {
        return docSlot;
    }

    /** Never {@code null} (protocol 2 fallback). */
    public CompoundTag payload() {
        return payload;
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                BorderTerminalService.handle(player, action, target, docSlot, payload);
            }
        });
        ctx.setPacketHandled(true);
    }
}
