package com.veriborder.resource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Localization parity (26.3 round 11, "本地化一致性检查") — the same contract as
 * {@code tools/check_lang.js}, enforced by {@code gradlew test}.
 *
 * <p>Both language files must carry exactly the same keys, every value must be
 * non-empty, and the printf placeholders of a key must agree between the two
 * languages: that mismatch is what turns "a message with two numbers" into a
 * {@code MissingFormatArgumentException} at runtime.</p>
 */
class LangParityTest {
    private static final String PATH = "/assets/veriborder/lang/%s.json";
    private static final String REFERENCE = "en_us";
    private static final List<String> TRANSLATIONS = List.of("zh_cn");
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?[sdf]");

    private static JsonObject load(String code) throws IOException {
        String resource = String.format(PATH, code);
        try (InputStream stream = LangParityTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "missing language resource " + resource);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static List<String> placeholders(String value) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    @Test
    void bothLanguagesCarryExactlyTheSameKeys() throws IOException {
        JsonObject reference = load(REFERENCE);
        assertTrue(reference.size() > 500, "expected the full catalogue, found " + reference.size());
        for (String code : TRANSLATIONS) {
            JsonObject translation = load(code);
            Set<String> missing = new TreeSet<>(reference.keySet());
            missing.removeAll(translation.keySet());
            Set<String> extra = new TreeSet<>(translation.keySet());
            extra.removeAll(reference.keySet());
            assertTrue(missing.isEmpty(), code + " is missing keys: " + missing);
            assertTrue(extra.isEmpty(), code + " has keys not in " + REFERENCE + ": " + extra);
        }
    }

    @Test
    void noTranslationIsEmpty() throws IOException {
        for (String code : new LinkedHashSet<>(
                java.util.stream.Stream.concat(java.util.stream.Stream.of(REFERENCE), TRANSLATIONS.stream())
                        .toList())) {
            JsonObject catalogue = load(code);
            List<String> empty = new ArrayList<>();
            for (String key : catalogue.keySet()) {
                JsonElement value = catalogue.get(key);
                if (!value.isJsonPrimitive() || value.getAsString().trim().isEmpty()) {
                    empty.add(key);
                }
            }
            assertTrue(empty.isEmpty(), code + " has empty values: " + empty);
        }
    }

    @Test
    void printfPlaceholdersAgreeBetweenLanguages() throws IOException {
        JsonObject reference = load(REFERENCE);
        for (String code : TRANSLATIONS) {
            JsonObject translation = load(code);
            List<String> mismatches = new ArrayList<>();
            for (String key : reference.keySet()) {
                if (!translation.has(key)) {
                    continue;
                }
                List<String> expected = placeholders(reference.get(key).getAsString());
                List<String> actual = placeholders(translation.get(key).getAsString());
                if (!expected.equals(actual)) {
                    mismatches.add(key + ": " + REFERENCE + expected + " vs " + code + " " + actual);
                }
            }
            assertTrue(mismatches.isEmpty(), code + " has placeholder mismatches: " + mismatches);
        }
    }
}
