package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A deterministic checksum over every field of the document. The universal modifier
 * recalculates it after each legitimate edit; external NBT tampering is detected by
 * every reader and by the E-Gate.
 */
public final class DocumentIntegrity {
    public static final String KEY = "integrity";

    private DocumentIntegrity() {
    }

    public static String sign(CompoundTag tag) {
        CompoundTag copy = tag.copy();
        copy.remove(KEY);
        String canonical = canonical(copy);
        String digest = sha256(canonical);
        tag.putString(KEY, digest);
        return digest;
    }

    public static boolean verify(CompoundTag tag) {
        if (!tag.contains(KEY, Tag.TAG_STRING)) {
            return false;
        }
        String expected = tag.getString(KEY);
        CompoundTag copy = tag.copy();
        copy.remove(KEY);
        return constantTimeEquals(expected, sha256(canonical(copy)));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String canonical(Tag tag) {
        StringBuilder builder = new StringBuilder();
        append(tag, builder);
        return builder.toString();
    }

    private static void append(Tag tag, StringBuilder builder) {
        if (tag instanceof CompoundTag compound) {
            builder.append('{');
            List<String> keys = new ArrayList<>(compound.getAllKeys());
            Collections.sort(keys);
            for (String key : keys) {
                builder.append(key).append('=');
                append(compound.get(key), builder);
                builder.append(';');
            }
            builder.append('}');
        } else if (tag instanceof ListTag list) {
            builder.append('[');
            for (Tag element : list) {
                append(element, builder);
                builder.append(',');
            }
            builder.append(']');
        } else {
            builder.append(tag.getAsString());
        }
    }
}
