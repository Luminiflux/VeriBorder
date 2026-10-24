package com.veriborder.item;

import com.veriborder.VeriBorder;
import com.veriborder.block.DeviceBlockEntity;
import com.veriborder.block.DeviceExtensionBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

/**
 * Right-click dispatch for {@link ConfigCopyBrushItem}.
 *
 * <p>The copy brush lives entirely in the item: it has no screen and no packet,
 * but a normal right-click on a machine would otherwise be swallowed by
 * {@code DeviceBlock#use} (which returns a consuming result and would open the
 * configuration screen). The brush therefore hooks
 * {@link PlayerInteractEvent.RightClickBlock}, which Forge fires on the server
 * <em>before</em> {@code Item#onItemUseFirst}, {@code BlockState#use} and
 * {@code Item#useOn}, and resolves both gestures itself:</p>
 *
 * <ul>
 *   <li>sneak → copy the machine's configuration into the brush NBT;</li>
 *   <li>no sneak → paste it back onto a machine of the same model.</li>
 * </ul>
 *
 * <p>Only the server side acts: the client keeps its vanilla prediction (which
 * does nothing for these blocks, because their {@code use} is guarded by
 * {@code isClientSide}), so no desynchronisation is possible and no extra
 * packet is needed. {@code setUseBlock/setUseItem(DENY)} stops the machine's own
 * GUI from opening with the brush in hand.</p>
 */
@Mod.EventBusSubscriber(modid = VeriBorder.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ConfigCopyBrushEvents {
    private ConfigCopyBrushEvents() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // Server-authoritative: the client prediction stays untouched.
        if (event.getSide() != LogicalSide.SERVER) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof ConfigCopyBrushItem)) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        DeviceBlockEntity device = resolveDevice(event.getLevel(), event.getPos());
        if (device == null) {
            // Not a VeriBorder machine: let vanilla handle the interaction.
            return;
        }
        // Both gestures belong to the brush, so the machine's own config screen
        // must never open and no item use may run on top of the paste.
        event.setUseBlock(Event.Result.DENY);
        event.setUseItem(Event.Result.DENY);
        if (player.isShiftKeyDown()) {
            ConfigCopyBrushItem.copyFrom(player, stack, device);
        } else {
            ConfigCopyBrushItem.applyTo(player, stack, device);
        }
    }

    /**
     * The machine at {@code pos}, or the machine below it when the player hit the
     * invisible upper half of a two-block machine (that block forwards its own
     * interaction to the block below, so the brush must do the same).
     */
    @Nullable
    private static DeviceBlockEntity resolveDevice(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof DeviceBlockEntity device) {
            return device;
        }
        if (level.getBlockState(pos).getBlock() instanceof DeviceExtensionBlock
                && level.getBlockEntity(pos.below()) instanceof DeviceBlockEntity below) {
            return below;
        }
        return null;
    }
}
