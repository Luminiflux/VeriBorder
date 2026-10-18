package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record VerificationResult(Code code, List<String> reasons, String detail) {
    public enum Code {
        OK(true),
        NO_DOCUMENT(false),
        WRONG_KIND(false),
        TAMPERED(false),
        LOST(false),
        CANCELLED(false),
        SUSPENDED(false),
        EXPIRED(false),
        MALFORMED(false),
        NO_VISA(false),
        NO_MATCHING_VISA(false),
        NO_ENDORSEMENT(false),
        NO_MATCHING_ENDORSEMENT(false),
        NO_MATCHING_CODE(false),
        NOT_APPLICABLE(false);

        private final boolean success;

        Code(boolean success) {
            this.success = success;
        }

        public boolean success() {
            return success;
        }

        public String translationKey() {
            return "verify.veriborder." + name().toLowerCase();
        }
    }

    public VerificationResult {
        reasons = List.copyOf(reasons);
    }

    public static VerificationResult ok(String detail) {
        return new VerificationResult(Code.OK, List.of(), detail == null ? "" : detail);
    }

    public static VerificationResult fail(Code code, String... reasons) {
        List<String> list = new ArrayList<>();
        Collections.addAll(list, reasons);
        return new VerificationResult(code, list, "");
    }

    public VerificationResult withDetail(String value) {
        return new VerificationResult(code, reasons, value == null ? "" : value);
    }

    public boolean valid() {
        return code.success();
    }

    public Component title() {
        return Component.translatable("verify.veriborder." + (valid() ? "pass" : "fail"));
    }

    public MutableComponent message() {
        MutableComponent component = Component.translatable(code.translationKey());
        if (!reasons.isEmpty()) {
            component.append(Component.literal(" · ")).append(Component.translatable(reasons.get(0)));
        }
        if (!detail.isBlank()) {
            // Gate binding details are stored as translation keys so a dedicated
            // server can send them to the client and let the client resolve the
            // actual visa/endorsement name.
            Component detailComponent = detail.startsWith("visa.type.")
                    || detail.startsWith("endorsement.type.")
                    ? Component.translatable(detail) : Component.literal(detail);
            component.append(Component.literal(" · ")).append(detailComponent);
        }
        return component;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("valid", valid());
        tag.putString("code", code.name());
        ListTag list = new ListTag();
        for (String reason : reasons) {
            list.add(StringTag.valueOf(reason));
        }
        tag.put("reasons", list);
        tag.putString("detail", detail);
        return tag;
    }

    public static VerificationResult load(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return fail(Code.NO_DOCUMENT);
        }
        Code code;
        try {
            code = Code.valueOf(tag.getString("code"));
        } catch (IllegalArgumentException exception) {
            code = Code.MALFORMED;
        }
        List<String> reasons = new ArrayList<>();
        ListTag list = tag.getList("reasons", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            reasons.add(list.getString(i));
        }
        return new VerificationResult(code, reasons, tag.getString("detail"));
    }
}
