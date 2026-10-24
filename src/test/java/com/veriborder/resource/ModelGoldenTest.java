package com.veriborder.resource;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Model golden test (26.3 round 11). Every block model, item model and blockstate
 * must (a) be valid JSON and (b) match the committed golden manifest
 * {@code src/test/resources/golden/model_manifest.sha256}.
 *
 * <p>This is the guard against silent resource churn: a generator rerun, a
 * hand-edited model or a deleted blockstate shows up as a one-line manifest diff
 * instead of a visual regression nobody notices. Refresh the manifest on purpose:</p>
 *
 * <pre>
 *   gradlew test --tests com.veriborder.resource.ModelGoldenTest -Dveriborder.golden.update=true
 * </pre>
 */
class ModelGoldenTest {
    private static final Path MANIFEST = Path.of("src/test/resources/golden/model_manifest.sha256");
    private static final List<String> ROOTS = List.of(
            "src/main/resources/assets/veriborder/models",
            "src/main/resources/assets/veriborder/blockstates");

    private static Path projectRoot() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        assertTrue(Files.isDirectory(cwd.resolve("src/main/resources")),
                "run the tests from the project root (user.dir=" + cwd.toAbsolutePath() + ")");
        return cwd;
    }

    /** relative forward-slash path -> sha256. */
    private static Map<String, String> manifest(Path project) throws IOException {
        Map<String, String> result = new TreeMap<>();
        for (String root : ROOTS) {
            Path dir = project.resolve(root);
            assertTrue(Files.isDirectory(dir), "resource root missing: " + dir);
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (!file.getFileName().toString().endsWith(".json")) {
                        continue;
                    }
                    String relative = project.relativize(file).toString().replace('\\', '/');
                    result.put(relative, sha256(Files.readAllBytes(file)));
                }
            }
        }
        assertTrue(result.size() > 50, "expected the full model set, found " + result.size());
        return result;
    }

    @Test
    void modelsMatchTheGoldenManifest() throws IOException {
        Path project = projectRoot();
        Map<String, String> actual = manifest(project);
        Path manifestPath = project.resolve(MANIFEST);

        boolean update = Boolean.getBoolean("veriborder.golden.update")
                || "1".equals(System.getenv("VERIBORDER_GOLDEN_UPDATE"));
        if (update || !Files.exists(manifestPath)) {
            Files.createDirectories(manifestPath.getParent());
            StringBuilder builder = new StringBuilder();
            builder.append("# VeriBorder model golden manifest (sha256, forward-slash paths)\n");
            builder.append("# refresh: gradlew test --tests com.veriborder.resource.ModelGoldenTest ")
                    .append("-Dveriborder.golden.update=true\n");
            actual.forEach((path, hash) -> builder.append(hash).append("  ").append(path).append('\n'));
            Files.writeString(manifestPath, builder.toString(), StandardCharsets.UTF_8);
            System.out.println("Wrote golden manifest (" + actual.size() + " files) to " + manifestPath);
            return;
        }

        Map<String, String> expected = new TreeMap<>();
        for (String line : Files.readAllLines(manifestPath, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int split = trimmed.indexOf(' ');
            assertTrue(split > 0, "malformed golden line: " + line);
            expected.put(trimmed.substring(split).trim(), trimmed.substring(0, split).trim());
        }

        List<String> differences = new ArrayList<>();
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (!actual.containsKey(entry.getKey())) {
                differences.add("missing: " + entry.getKey());
            } else if (!entry.getValue().equals(actual.get(entry.getKey()))) {
                differences.add("changed: " + entry.getKey());
            }
        }
        for (String path : actual.keySet()) {
            if (!expected.containsKey(path)) {
                differences.add("new: " + path);
            }
        }

        assertTrue(differences.isEmpty(),
                "model resources differ from the golden manifest (" + differences.size() + " file(s)); "
                        + "review the change and refresh with "
                        + "-Dveriborder.golden.update=true\n  " + String.join("\n  ", differences));
        assertEquals(expected.size(), actual.size());
    }

    @Test
    void everyModelAndBlockstateIsValidJson() throws IOException {
        Path project = projectRoot();
        int checked = 0;
        for (String root : ROOTS) {
            try (Stream<Path> files = Files.walk(project.resolve(root))) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (!file.getFileName().toString().endsWith(".json")) {
                        continue;
                    }
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    try {
                        JsonParser.parseString(text);
                    } catch (RuntimeException exception) {
                        throw new AssertionError("invalid JSON: " + file + " — " + exception.getMessage());
                    }
                    checked++;
                }
            }
        }
        assertTrue(checked > 50, "expected to validate the full model set, checked " + checked);
    }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
