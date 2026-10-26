package com.veriborder.client;

import com.veriborder.registry.CredentialRegistryData;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 26.4.1 custom cover base maps found on this client.
 *
 * <p>The registry stores a base map as a content hash plus the PNG itself. The
 * editor cannot open a native file dialog, so it offers the PNGs an administrator
 * dropped into {@code config/veriborder/templates/}: picking one reads the file
 * here, validates it against the same limits the server enforces
 * ({@link CredentialRegistryData#MAX_TEXTURE_BYTES}, 64x64, real PNG), and hands
 * the hash plus the base64 payload to {@code registry_upload_texture}.</p>
 *
 * <p>In single player this is the same folder the server sees; on a dedicated
 * server the administrator copies the PNG into the client folder once. Reading a
 * missing folder, an unreadable file or a non-image is a normal outcome and is
 * reported as a concrete reason key, never as an exception.</p>
 */
public final class TemplateTextureFiles {
    /** Folder scanned for base maps, relative to the game/config directory. */
    public static final String FOLDER = "veriborder/templates";

    private TemplateTextureFiles() {
    }

    /** One PNG in the base-map folder. */
    public record LocalFile(String name, Path path) {
    }

    /**
     * Result of reading one file: the upload payload plus the concrete reason key
     * when it cannot be uploaded ({@code ""} when it can).
     */
    public record Prepared(String name, String hash, String base64, String errorKey) {
        public boolean ok() {
            return errorKey == null || errorKey.isBlank();
        }
    }

    /** {@code <game dir>/config/veriborder/templates}. */
    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(FOLDER);
    }

    /**
     * Every {@code *.png} in {@link #directory()}, name-sorted. A missing folder is
     * an empty list: the caller then simply offers "no base map".
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

    /** Reads and validates one file for {@code registry_upload_texture}. */
    public static Prepared prepare(LocalFile file) {
        if (file == null) {
            return new Prepared("", "", "", "message.veriborder.registry.texture_empty");
        }
        byte[] png;
        try {
            png = Files.readAllBytes(file.path());
        } catch (IOException | RuntimeException failure) {
            return new Prepared(file.name(), "", "", "message.veriborder.registry.texture_empty");
        }
        String error = validate(png);
        if (!error.isBlank()) {
            return new Prepared(file.name(), "", "", error);
        }
        return new Prepared(file.name(), sha256(png), Base64.getEncoder().encodeToString(png), "");
    }

    /** Same limits the server applies, so a refusal never needs a round trip. */
    static String validate(byte[] png) {
        if (png == null || png.length == 0) {
            return "message.veriborder.registry.texture_empty";
        }
        if (!isPng(png)) {
            return "message.veriborder.registry.texture_not_png";
        }
        if (png.length > CredentialRegistryData.MAX_TEXTURE_BYTES) {
            return "message.veriborder.registry.texture_too_large";
        }
        int[] size = size(png);
        if (size == null) {
            return "message.veriborder.registry.texture_not_png";
        }
        if (size[0] > CredentialRegistryData.MAX_TEXTURE_DIMENSION) {
            return "message.veriborder.registry.texture_too_wide";
        }
        if (size[1] > CredentialRegistryData.MAX_TEXTURE_DIMENSION) {
            return "message.veriborder.registry.texture_too_tall";
        }
        return "";
    }

    /** PNG signature check (89 50 4E 47 0D 0A 1A 0A). */
    static boolean isPng(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            return false;
        }
        return (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A;
    }

    /** Pixel size of a PNG, or {@code null} when it cannot be decoded. */
    static int[] size(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return null;
            }
            return new int[]{image.getWidth(), image.getHeight()};
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    /** Lowercase hex SHA-256 of the file: the registry's content hash. */
    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            String hex = builder.toString();
            return hex.length() > CredentialRegistryData.MAX_TEXTURE_HASH_LENGTH
                    ? hex.substring(0, CredentialRegistryData.MAX_TEXTURE_HASH_LENGTH) : hex;
        } catch (NoSuchAlgorithmException failure) {
            return "";
        }
    }
}
