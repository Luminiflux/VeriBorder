package com.veriborder.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire round-trips for every packet (26.3 round 11, "包 round-trip 测试"), including
 * the protocol-2 null fallbacks: a decoder must never hand {@code null} to a
 * handler, because {@code readNbt()} returns null for an empty payload and
 * {@code readUtf()} for a truncated one.
 */
class PacketRoundTripTest {
    /** Encodes with {@code packet.encode}, then decodes a fresh packet from the same bytes. */
    private static <T> T roundTrip(T packet, java.util.function.BiConsumer<T, FriendlyByteBuf> encoder,
                                   Function<FriendlyByteBuf, T> decoder) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        encoder.accept(packet, buffer);
        // Fresh reader over the same bytes: the decoder must consume exactly what
        // the encoder wrote and nothing more.
        return decoder.apply(buffer);
    }

    @Test
    void openDevicePacketRoundTripsAndNullsBecomeEmpty() {
        CompoundTag payload = new CompoundTag();
        payload.putString("gateMessage", "gate.veriborder.denied");
        OpenDevicePacket packet = new OpenDevicePacket(new BlockPos(4, 65, -7), "egate", payload);
        OpenDevicePacket decoded = roundTrip(packet, OpenDevicePacket::encode, OpenDevicePacket::new);
        assertEquals(new BlockPos(4, 65, -7), decoded.pos());
        assertEquals("egate", decoded.device());
        assertEquals("gate.veriborder.denied", decoded.data().getString("gateMessage"));

        OpenDevicePacket empty = roundTrip(new OpenDevicePacket(null, null, null),
                OpenDevicePacket::encode, OpenDevicePacket::new);
        assertEquals(BlockPos.ZERO, empty.pos());
        assertEquals("", empty.device());
        assertNotNull(empty.data(), "protocol 2: a null payload decodes as an empty compound");
        assertTrue(empty.data().isEmpty());
    }

    @Test
    void deviceActionPacketRoundTripsItsSlotAndPayload() {
        CompoundTag data = new CompoundTag();
        data.putString("action", "save");
        DeviceActionPacket packet = new DeviceActionPacket(new BlockPos(1, 2, 3), "config_save", data, 7);
        DeviceActionPacket decoded = roundTrip(packet, DeviceActionPacket::encode, DeviceActionPacket::new);
        assertEquals(new BlockPos(1, 2, 3), decoded.pos());
        assertEquals("config_save", decoded.action());
        assertEquals(7, decoded.slot());
        assertEquals("save", decoded.data().getString("action"));

        DeviceActionPacket empty = roundTrip(new DeviceActionPacket(null, null, null, 0),
                DeviceActionPacket::encode, DeviceActionPacket::new);
        assertEquals(BlockPos.ZERO, empty.pos());
        assertEquals("", empty.action());
        assertTrue(empty.data().isEmpty());
    }

    @Test
    void modifierPacketsKeepTheirWholeDocumentPayload() {
        CompoundTag document = new CompoundTag();
        document.putString("number", "E12345678");
        ListTag visas = new ListTag();
        CompoundTag visa = new CompoundTag();
        visa.putString("id", "V-1");
        visas.add(visa);
        document.put("visas", visas);

        ModifierSavePacket save = roundTrip(new ModifierSavePacket(3, document),
                ModifierSavePacket::encode, ModifierSavePacket::new);
        assertEquals(3, save.slot());
        assertEquals("E12345678", save.data().getString("number"));
        assertEquals(1, save.data().getList("visas", net.minecraft.nbt.Tag.TAG_COMPOUND).size());

        ModifierGeneratePacket generate = roundTrip(new ModifierGeneratePacket(document),
                ModifierGeneratePacket::encode, ModifierGeneratePacket::new);
        assertEquals(document, generate.data());

        ModifierSavePacket empty = roundTrip(new ModifierSavePacket(0, null),
                ModifierSavePacket::encode, ModifierSavePacket::new);
        assertNotNull(empty.data());
        assertTrue(empty.data().isEmpty());
        assertTrue(roundTrip(new ModifierGeneratePacket((CompoundTag) null), ModifierGeneratePacket::encode,
                buffer -> new ModifierGeneratePacket(buffer)).data().isEmpty());
    }

    @Test
    void brushPacketsRoundTripTheirConfiguration() {
        CompoundTag config = new CompoundTag();
        config.putString("entryMode", "endorsement");
        ListTag codes = new ListTag();
        codes.add(StringTag.valueOf("12345678"));
        config.put("entryCodes", codes);

        GateBrushPacket gate = roundTrip(new GateBrushPacket(config),
                GateBrushPacket::encode, GateBrushPacket::new);
        assertEquals("endorsement", gate.config().getString("entryMode"));
        assertEquals(1, gate.config().getList("entryCodes", net.minecraft.nbt.Tag.TAG_STRING).size());

        DualGateBrushPacket dual = roundTrip(new DualGateBrushPacket(config),
                DualGateBrushPacket::encode, DualGateBrushPacket::new);
        assertEquals(config, dual.config());

        assertTrue(roundTrip(new GateBrushPacket((CompoundTag) null), GateBrushPacket::encode,
                buffer -> new GateBrushPacket(buffer)).config().isEmpty());
        assertTrue(roundTrip(new DualGateBrushPacket((CompoundTag) null), DualGateBrushPacket::encode,
                buffer -> new DualGateBrushPacket(buffer)).config().isEmpty());
    }

    @Test
    void borderTerminalPacketsRoundTripTheSnapshotAndTheAction() {
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("playerName", "ALEX");
        BorderTerminalDataPacket data = roundTrip(new BorderTerminalDataPacket(snapshot),
                BorderTerminalDataPacket::encode, BorderTerminalDataPacket::new);
        assertEquals("ALEX", data.data().getString("playerName"));
        assertTrue(roundTrip(new BorderTerminalDataPacket((CompoundTag) null), BorderTerminalDataPacket::encode,
                buffer -> new BorderTerminalDataPacket(buffer)).data().isEmpty());

        CompoundTag payload = new CompoundTag();
        payload.putString("presetName", "airport");
        BorderTerminalActionPacket action = roundTrip(
                new BorderTerminalActionPacket("save_preset", "E12345678", 2, payload),
                BorderTerminalActionPacket::encode, BorderTerminalActionPacket::new);
        assertEquals("save_preset", action.action());
        assertEquals("E12345678", action.target());
        assertEquals(2, action.docSlot());
        assertEquals("airport", action.payload().getString("presetName"));

        BorderTerminalActionPacket empty = roundTrip(
                new BorderTerminalActionPacket(null, null, 0, null),
                BorderTerminalActionPacket::encode, BorderTerminalActionPacket::new);
        assertEquals("", empty.action());
        assertEquals("", empty.target());
        assertFalse(empty.action() == null);
        assertTrue(empty.payload().isEmpty());
    }
}
