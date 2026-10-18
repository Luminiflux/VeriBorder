package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;

public record StampRecord(
        String id,
        String direction,
        String port,
        String country,
        String date,
        String officer
) {
    public static final String IN = "IN";
    public static final String OUT = "OUT";

    public static StampRecord fromTag(CompoundTag tag) {
        return new StampRecord(
                tag.getString("id"),
                tag.getString("direction"),
                tag.getString("port"),
                tag.getString("country"),
                tag.getString("date"),
                tag.getString("officer")
        );
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("direction", direction);
        tag.putString("port", port);
        tag.putString("country", country);
        tag.putString("date", date);
        tag.putString("officer", officer);
        return tag;
    }

    public boolean inbound() {
        return IN.equalsIgnoreCase(direction);
    }

    public String directionKey() {
        return inbound() ? "stamp.veriborder.in" : "stamp.veriborder.out";
    }
}
