package com.veriborder.client;

import com.veriborder.doc.PortraitLibrary;
import com.veriborder.network.DeviceActionPacket;
import com.veriborder.network.ModNetwork;
import com.veriborder.registry.RegistryService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 26.4.1 portrait photos found on this client.
 *
 * <p>The photo booth cannot open a native file dialog, so it offers the PNGs an
 * administrator (or the traveller) dropped into {@code config/veriborder/portraits/}.
 * Picking one reads the file here, validates it against the same limits the server
 * enforces ({@link PortraitLibrary#MAX_BYTES}, {@value PortraitLibrary#MIN_EDGE} or
 * {@value PortraitLibrary#MAX_EDGE} pixels square, a real PNG), and hands the
 * base64 payload to {@link RegistryService#ACTION_UPLOAD_PORTRAIT}.</p>
 *
 * <p>In single player this is the same folder the server sees; on a dedicated
 * server the photo is copied into the client folder once. A missing folder, an
 * unreadable file or a non-image is a normal outcome reported as a concrete
 * reason key, never an exception — and never a blocked screen.</p>
 */
public final class PortraitFiles {
    /** Folder scanned for portrait photos, relative to the game/config directory. */
    public static final String FOLDER = "veriborder/portraits";

    private PortraitFiles() {
    }

    /** One PNG in the portrait folder. */
    public record LocalFile(String name, Path path) {
    }

    /**
     * Result of reading one file: the upload payload, the {@code custom:<hash>} id
     * the server will store it under, and the concrete reason key when it cannot be
     * uploaded ({@code ""} when it can).
     */
    public record Prepared(String name, String id, String hash, String base64, String errorKey) {
        public boolean ok() {
            return errorKey == null || errorKey.isBlank();
        }
    }

    /** {@code <game dir>/config/veriborder/portraits}. */
    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(FOLDER);
    }

    /**
     * Every {@code *.png} in {@link #directory()}, name-sorted. A missing folder is
     * an empty list: the screen then simply offers no photo.
     */
    public static List<LocalFile> list() {
        List<LocalFile> result = new ArrayList<>();
        Path directory = directory();
        if (!Files.isDirectory(directory)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.{png,PNG}")) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String name = path.getFileName().toString();
                String base = name.toLowerCase(Locale.ROOT).endsWith(".png")
                        ? name.substring(0, name.length() - 4) : name;
                result.add(new LocalFile(base, path));
            }
        } catch (IOException | RuntimeException failure) {
            return result;
        }
        result.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
        return result;
    }

    /** Reads and validates one file for {@code registry_upload_portrait}. */
    public static Prepared prepare(@Nullable LocalFile file) {
        if (file == null) {
            return new Prepared("", "", "", "", "message.veriborder.portrait.empty");
        }
        byte[] png;
        try {
            png = Files.readAllBytes(file.path());
        } catch (IOException | RuntimeException failure) {
            return new Prepared(file.name(), "", "", "", "message.veriborder.portrait.empty");
        }
        String error = validate(png);
        if (!error.isBlank()) {
            return new Prepared(file.name(), "", "", "", error);
        }
        String hash = PortraitLibrary.hashOf(png);
        return new Prepared(file.name(), PortraitLibrary.customId(hash), hash,
                Base64.getEncoder().encodeToString(png), "");
    }

    /** Same limits the server applies, so a refusal never needs a round trip. */
    public static String validate(@Nullable byte[] png) {
        if (png == null || png.length == 0) {
            return "message.veriborder.portrait.empty";
        }
        if (!PortraitLibrary.hasPngSignature(png)) {
            return "message.veriborder.portrait.not_png";
        }
        int[] size = PortraitLibrary.pngSize(png);
        if (size == null) {
            return "message.veriborder.portrait.not_png";
        }
        if (png.length > PortraitLibrary.MAX_BYTES) {
            return "message.veriborder.portrait.too_large";
        }
        if (size[0] != size[1] || (size[0] != PortraitLibrary.MIN_EDGE && size[0] != PortraitLibrary.MAX_EDGE)) {
            return "message.veriborder.portrait.bad_size";
        }
        return "";
    }

    /**
     * Upload payload of one prepared file: {@code {png, owner}} where {@code owner}
     * is the UUID the portrait is stored for ("" lets the server use the sender).
     */
    public static CompoundTag uploadPayload(Prepared prepared, @Nullable String ownerUuid) {
        CompoundTag payload = new CompoundTag();
        if (prepared == null || !prepared.ok()) {
            return payload;
        }
        payload.putString("png", prepared.base64());
        String owner = PortraitLibrary.normalizeOwner(ownerUuid);
        if (!owner.isEmpty()) {
            payload.putString("owner", owner);
        }
        return payload;
    }

    /**
     * Sends one prepared portrait to the server.
     *
     * @return {@code false} when the file was not uploadable (nothing is sent)
     */
    public static boolean send(Prepared prepared, @Nullable String ownerUuid) {
        if (prepared == null || !prepared.ok()) {
            return false;
        }
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(BlockPos.ZERO,
                RegistryService.ACTION_UPLOAD_PORTRAIT, uploadPayload(prepared, ownerUuid), -1));
        return true;
    }

    /** Reads the first usable photo and sends it (the photo booth's one-button path). */
    public static boolean sendFirst(@Nullable String ownerUuid) {
        for (LocalFile file : list()) {
            Prepared prepared = prepare(file);
            if (prepared.ok()) {
                return send(prepared, ownerUuid);
            }
        }
        return false;
    }
}
