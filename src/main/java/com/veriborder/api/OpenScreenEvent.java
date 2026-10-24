package com.veriborder.api;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.Event;

/**
 * Common-side request to open a client screen. Posted from packets/items that
 * must stay loadable on a dedicated server; a client-only subscriber opens the
 * actual {@code Screen}. No {@code net.minecraft.client} type may appear here.
 */
public class OpenScreenEvent extends Event {
    public enum Kind {
        DEVICE, GATE_BRUSH, DUAL_GATE_BRUSH, BORDER_TERMINAL
    }

    private final Kind kind;
    private final BlockPos pos;
    private final String device;
    private final CompoundTag data;
    private final ItemStack stack;

    public OpenScreenEvent(Kind kind, BlockPos pos, String device, CompoundTag data) {
        this.kind = kind;
        this.pos = pos == null ? BlockPos.ZERO : pos;
        this.device = device == null ? "" : device;
        this.data = data == null ? new CompoundTag() : data;
        this.stack = ItemStack.EMPTY;
    }

    public OpenScreenEvent(Kind kind, ItemStack stack) {
        this.kind = kind;
        this.pos = BlockPos.ZERO;
        this.device = "";
        this.data = new CompoundTag();
        this.stack = stack == null ? ItemStack.EMPTY : stack;
    }

    public OpenScreenEvent(Kind kind, CompoundTag data) {
        this.kind = kind;
        this.pos = BlockPos.ZERO;
        this.device = "";
        this.data = data == null ? new CompoundTag() : data;
        this.stack = ItemStack.EMPTY;
    }

    public Kind kind() {
        return kind;
    }

    public BlockPos pos() {
        return pos;
    }

    public String device() {
        return device;
    }

    public CompoundTag data() {
        return data;
    }

    public ItemStack stack() {
        return stack;
    }
}
