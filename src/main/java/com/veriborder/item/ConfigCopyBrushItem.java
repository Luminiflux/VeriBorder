package com.veriborder.item;

import com.veriborder.block.DeviceBlockEntity;
import com.veriborder.block.DeviceType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Creative-only configuration copy brush — the no-GUI sibling of
 * {@link DeviceBrushItem}.
 *
 * <ul>
 *   <li><b>Sneak + right-click an issuing machine</b>: copies that machine's
 *       complete configuration into this stack's {@code VeriBorderCopyBrush}
 *       tag and records its {@code sourceType}.</li>
 *   <li><b>Right-click a machine of the same model</b>: the server re-checks
 *       the model and applies the whole payload in one step. A different model
 *       is refused with {@code message.veriborder.copy_brush.mismatch}.</li>
 * </ul>
 *
 * <p>Both gestures are handled by {@link ConfigCopyBrushEvents} before vanilla
 * block/item interaction, so the machine's own configuration screen never
 * opens with this brush in hand. Everything is server-authoritative: the paste
 * path re-verifies the permission level, the reach distance and the model, so
 * a forged client can neither paste a foreign payload nor reach a far machine.</p>
 *
 * <p>The payload layout lives in {@link ConfigCopyBrushConfig}: checkpoint
 * identity, the full {@link DeviceBlockEntity.Locks} record, the 26.2 issuer
 * policy, the gate binding, the dual-clearance payload and the stored presets.</p>
 */
public class ConfigCopyBrushItem extends Item {
    /** Maximum squared distance between the player and a machine they paste onto. */
    public static final double MAX_DISTANCE_SQR = 100.0D;
    /** Permission level required to paste; copying only reads the machine. */
    public static final int APPLY_PERMISSION_LEVEL = 2;

    public ConfigCopyBrushItem(Properties properties) {
        super(properties.stacksTo(1).rarity(Rarity.EPIC));
    }

    /**
     * Copies the full configuration of {@code device} onto {@code stack}.
     * Server only: the caller ({@link ConfigCopyBrushEvents}) already rejected
     * the client side, and nothing here trusts client data.
     *
     * @return true when a payload was stored
     */
    public static boolean copyFrom(ServerPlayer player, ItemStack stack, DeviceBlockEntity device) {
        DeviceType type = device.deviceType();
        if (!type.isIssuer()) {
            message(player, "message.veriborder.copy_brush.not_issuer");
            return false;
        }
        ConfigCopyBrushConfig.write(stack, type, device.configTag());
        // NBT changed on the live stack: flag the inventory so it is persisted
        // and pushed to the client immediately (same pattern as the save packets).
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
        playFeedback(player, device.getBlockPos(), 1.4F);
        message(player, "message.veriborder.copy_brush.copied", Component.translatable(type.translationKey()));
        return true;
    }

    /**
     * Applies the stored payload to {@code device} when it is the very same
     * model. Server only; re-verifies the model, the permission level and the
     * reach distance.
     *
     * @return true when the configuration was written onto the machine
     */
    public static boolean applyTo(ServerPlayer player, ItemStack stack, DeviceBlockEntity device) {
        ConfigCopyBrushConfig.Snapshot snapshot = ConfigCopyBrushConfig.read(stack);
        if (snapshot == null) {
            message(player, "message.veriborder.copy_brush.empty");
            return false;
        }
        DeviceType target = device.deviceType();
        if (!target.isIssuer()) {
            message(player, "message.veriborder.copy_brush.not_issuer");
            return false;
        }
        if (!player.hasPermissions(APPLY_PERMISSION_LEVEL)) {
            message(player, "message.veriborder.permission.denied");
            return false;
        }
        BlockPos pos = device.getBlockPos();
        if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > MAX_DISTANCE_SQR) {
            message(player, "message.veriborder.too_far");
            return false;
        }
        // Model check: a copy brush must never migrate a payload to a machine
        // whose screen and issue rules are built for another document family.
        DeviceType source = ConfigCopyBrushConfig.typeOrNull(snapshot.sourceType());
        if (source != target) {
            message(player, "message.veriborder.copy_brush.mismatch", label(source), label(target));
            return false;
        }
        // DeviceBlockEntity#load is the canonical applier for exactly the keys
        // configTag() writes (disk load and the sync tag use the same schema),
        // so one call restores the complete configuration — locks, issuer
        // policy, checkpoint identity, gate binding, dual gate and presets.
        device.load(ConfigCopyBrushConfig.sanitize(snapshot.config()));
        device.changed();
        device.sync();
        playFeedback(player, pos, 1.2F);
        message(player, "message.veriborder.copy_brush.applied", label(target));
        return true;
    }

    /** Human-readable device name used by the chat feedback. */
    private static Component label(@Nullable DeviceType type) {
        return type == null
                ? Component.translatable("tooltip.veriborder.copy_brush.unknown")
                : Component.translatable(type.translationKey());
    }

    private static void playFeedback(ServerPlayer player, BlockPos pos, float pitch) {
        player.level().playSound(null, pos, SoundEvents.VILLAGER_WORK_CARTOGRAPHER,
                SoundSource.BLOCKS, 0.7F, pitch);
    }

    /** Small chat helper shared with the event handler. */
    public static void message(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), false);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.veriborder.copy_brush.1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.veriborder.copy_brush.2").withStyle(ChatFormatting.GRAY));
        ConfigCopyBrushConfig.Snapshot snapshot = ConfigCopyBrushConfig.read(stack);
        if (snapshot == null) {
            tooltip.add(Component.translatable("tooltip.veriborder.copy_brush.empty").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        CompoundTag config = snapshot.config();
        tooltip.add(Component.translatable("tooltip.veriborder.copy_brush.3",
                label(snapshot.sourceDevice()),
                config.getString("port"),
                ConfigCopyBrushConfig.lockedCount(config)).withStyle(ChatFormatting.DARK_AQUA));
    }
}
