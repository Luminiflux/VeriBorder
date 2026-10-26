package com.veriborder.network;

import com.veriborder.client.ClientRegistryCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → client credential-registry snapshot (26.3 S→C sync).
 *
 * <p>The whole registry is small (bounded lists), so it is sent as one compound.
 * On a dedicated server the handler executes on the main thread and only touches
 * the client cache through {@link DistExecutor}, so no client class is loaded on
 * a server.</p>
 */
public class RegistrySnapshotPacket {
    private final CompoundTag data;

    public RegistrySnapshotPacket(CompoundTag data) {
        this.data = data == null ? new CompoundTag() : data.copy();
    }

    public RegistrySnapshotPacket(FriendlyByteBuf buffer) {
        CompoundTag read = buffer.readNbt();
        this.data = read == null ? new CompoundTag() : read;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeNbt(data);
    }

    /** Snapshot; never {@code null}. */
    public CompoundTag data() {
        return data;
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> {
                    ClientRegistryCache.update(data);
                    // 26.4.1: the snapshot also carries the custom cover base maps
                    // (hash + base64 PNG). They are uploaded into the eight dynamic
                    // item textures right after the cache is refreshed, so an item
                    // never renders a cover the client has not received yet.
                    com.veriborder.client.render.TemplateSlotTextures.update(data);
                }));
        ctx.setPacketHandled(true);
    }
}
