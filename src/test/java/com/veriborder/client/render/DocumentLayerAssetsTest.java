package com.veriborder.client.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.2.1: guards the shipped document item assets after the look reverted to the
 * flat card design (one tintable card layer plus an optional crest layer).
 */
class DocumentLayerAssetsTest {

    private static final Path ITEM_MODELS =
            Path.of("src/main/resources/assets/veriborder/models/item");

    private record Kind(String art, String item) {
    }

    private static final List<Kind> KINDS = List.of(
            new Kind("id_card", "id_card"),
            new Kind("passport", "passport"),
            new Kind("permit", "hk_macao_permit"));

    @Test
    void everyCredentialKindHasAFlatTintableCard() throws IOException {
        for (Kind kind : KINDS) {
            JsonObject textures = read(ITEM_MODELS.resolve("doc_layers_" + kind.art() + ".json"))
                    .getAsJsonObject("textures");
            assertNotNull(textures, kind.art() + " has no textures block");
            String layer0 = textures.get("layer0").getAsString();
            assertTrue(layer0.endsWith("_flat"), kind.art() + " layer0 should be flat, was " + layer0);
            BufferedImage card = image(texturePath(layer0));
            assertNotNull(card, kind.art() + " flat card is undecodable");
            assertEquals(64, card.getWidth(), kind.art() + " flat card width");
            assertEquals(64, card.getHeight(), kind.art() + " flat card height");
            assertTrue(textures.has("layer1"), kind.art() + " lost its crest layer");
        }
    }

    @Test
    void everyCredentialKindOffersTheSameCrestGrid() throws IOException {
        for (Kind kind : KINDS) {
            // The default crest is baked into the base model, so only the other
            // ids need a variant file.
            for (int index = 1; index < EmblemSet.IDS.size(); index++) {
                String emblem = EmblemSet.IDS.get(index);
                Path variant = ITEM_MODELS.resolve(kind.item() + "_emblem_" + emblem + ".json");
                assertTrue(Files.isRegularFile(variant), "missing crest variant " + variant);
                JsonObject model = read(variant);
                JsonObject textures = model.getAsJsonObject("textures");
                String parent = model.has("parent") ? model.get("parent").getAsString() : "";
                if (textures != null && textures.has("layer0")) {
                    assertTrue(textures.get("layer0").getAsString().endsWith("_flat"),
                            variant + " must keep the flat card on layer0");
                } else {
                    // The crest variants only override layer1 and inherit the card
                    // from the shared palette model.
                    assertTrue(parent.contains("doc_layers"),
                            variant + " must inherit the flat card from doc_layers_*");
                }
            }
        }
    }

    @Test
    void everySlotVariantIsACustomRendererModel() throws IOException {
        for (Kind kind : KINDS) {
            for (int slot = 0; slot < 8; slot++) {
                Path variant = ITEM_MODELS.resolve(kind.item() + "_slot_" + slot + ".json");
                assertTrue(Files.isRegularFile(variant), "missing slot variant " + variant);
                assertEquals("builtin/entity", read(variant).get("parent").getAsString(),
                        variant + " must be drawn by the BEWLR, not the item atlas");
            }
        }
    }

    @Test
    void everyTextureReferencedByADocumentModelExistsAndDecodes() throws IOException {
        List<String> problems = new ArrayList<>();
        try (var stream = Files.list(ITEM_MODELS)) {
            for (Path model : stream.toList()) {
                String name = model.getFileName().toString();
                if (!name.endsWith(".json")) {
                    continue;
                }
                boolean ours = name.startsWith("doc_layers_");
                for (Kind kind : KINDS) {
                    ours = ours || name.startsWith(kind.item() + "_emblem_")
                            || name.startsWith(kind.item() + "_slot_")
                            || name.equals(kind.item() + ".json");
                }
                if (!ours) {
                    continue;
                }
                JsonObject json = read(model);
                if (!json.has("textures")) {
                    continue;
                }
                for (var entry : json.getAsJsonObject("textures").entrySet()) {
                    String reference = entry.getValue().getAsString();
                    Path texture = texturePath(reference);
                    if (!Files.isRegularFile(texture)) {
                        problems.add(name + " -> missing " + reference);
                        continue;
                    }
                    try {
                        if (image(texture) == null) {
                            problems.add(name + " -> undecodable " + reference);
                        }
                    } catch (IOException failure) {
                        problems.add(name + " -> undecodable " + reference);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static Path texturePath(String reference) {
        String path = reference;
        int colon = path.indexOf(':');
        if (colon >= 0) {
            path = path.substring(colon + 1);
        }
        if (!path.startsWith("item/")) {
            path = "item/" + path;
        }
        return Path.of("src/main/resources/assets/veriborder/textures/" + path + ".png");
    }

    private static BufferedImage image(Path path) throws IOException {
        return ImageIO.read(path.toFile());
    }

    private static JsonObject read(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
