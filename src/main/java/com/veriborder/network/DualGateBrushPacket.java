package com.veriborder.network;

import com.veriborder.item.DualGateBrushConfig;
import com.veriborder.item.DualGateBrushItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server save of the dual-clearance gate brush.
 *
 * <p>The screen edits a local copy of {@code VeriBorderDualGateConfig}; this
 * packet carries the edited compound back. The server re-validates OP level 2,
 * finds the brush in either hand and writes a sanitised copy onto it, so the
 * item NBT stays authoritative and survives.</p>
 */
public class DualGateBrushPacket {
    private final CompoundTag config;

    public DualGateBrushPacket(CompoundTag config) {
        this.config = config == null ? new CompoundTag() : config;
    }

    public DualGateBrushPacket(FriendlyByteBuf buffer) {
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
                DualGateBrushItem.message(player, "message.veriborder.permission.denied");
                return;
            }
            ItemStack stack = DualGateBrushItem.heldBrush(player);
            if (stack == null) {
                DualGateBrushItem.message(player, "message.veriborder.dual_gate_brush.missing");
                return;
            }
            DualGateBrushConfig.write(stack, config);
            // The held stack is live, but flag the inventory so the new NBT is
            // persisted and pushed to the client immediately.
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            player.containerMenu.broadcastChanges();
            DualGateBrushItem.message(player, "message.veriborder.dual_gate_brush.saved");
        });
        ctx.setPacketHandled(true);
    }
}
