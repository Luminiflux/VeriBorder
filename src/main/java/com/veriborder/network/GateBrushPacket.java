package com.veriborder.network;

import com.veriborder.item.GateBrushConfig;
import com.veriborder.item.GateConfigBrushItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server save of the gate configuration brush.
 *
 * <p>The screen edits a local copy of {@code VeriBorderGateConfig}; this packet
 * carries the edited compound back. The server re-validates OP level 2, finds
 * the brush in either hand and writes a sanitised copy onto it, so the item NBT
 * stays authoritative and survives.</p>
 */
public class GateBrushPacket {
    private final CompoundTag config;

    public GateBrushPacket(CompoundTag config) {
        this.config = config == null ? new CompoundTag() : config;
    }

    public GateBrushPacket(FriendlyByteBuf buffer) {
        CompoundTag read = buffer.readNbt();
        this.config = read == null ? new CompoundTag() : read;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeNbt(config);
    }

    /** The edited configuration; never {@code null} (protocol 2 fallback). */
    public CompoundTag config() {
        return config;
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            // Server-authoritative: the client never decides whether it may save.
            if (!player.hasPermissions(2)) {
                GateConfigBrushItem.message(player, "message.veriborder.permission.denied");
                return;
            }
            ItemStack stack = GateConfigBrushItem.heldBrush(player);
            if (stack == null) {
                GateConfigBrushItem.message(player, "message.veriborder.gate_brush.missing");
                return;
            }
            GateBrushConfig.write(stack, config);
            // The held stack is live, but flag the inventory so the new NBT is
            // persisted and pushed to the client immediately.
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            player.containerMenu.broadcastChanges();
            GateConfigBrushItem.message(player, "message.veriborder.gate_brush.saved");
        });
        ctx.setPacketHandled(true);
    }
}
