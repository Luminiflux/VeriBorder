package com.veriborder.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One audit of the whole {@code blockstate -> block model -> texture} chain of a
 * gate, shared by the unit test and by {@code /veriborder diagnose models}
 * (26.2.1).
 *
 * <p>A gate state that does not resolve to a model renders as vanilla's
 * <b>missing model</b> — the magenta/black checkerboard cube players report as
 * "紫黑缺失方块". That happens when a blockstate variant key names a property (or
 * a property value) the block does not declare, when a variant points at a model
 * file that is not in the jar, or when a model references a texture variable or
 * PNG that is missing. All three are checked here against the <b>real</b> block
 * state definition, so the audit cannot drift the way a hand-checked list can.</p>
 *
 * <p>Nothing here touches {@code net.minecraft.client.*}: the command runs on a
 * dedicated server, where only the resources (not the baked models) are needed.
 * The caller supplies a {@link Probe} that answers "does this resource exist, and
 * what does it contain" — the test probes the source tree, the command probes the
 * server {@code ResourceManager}.</p>
 *
 * <p>Locations are full resource locations, e.g.
 * {@code veriborder:blockstates/egate.json}, {@code veriborder:models/block/egate.json},
 * {@code veriborder:textures/block/egate_body.png}.</p>
 */
public final class GateModelAudit {
    /** Namespace every gate resource lives in. */
    public static final String NAMESPACE = "veriborder";
    /** Vanilla rejects block model elements outside this range. */
    public static final double MIN_COORD = -16.0D;
    public static final double MAX_COORD = 32.0D;
    /** Vanilla allows a single element rotation of at most 45 degrees. */
    public static final double MAX_ROTATION = 45.0D;
    /** The project's generators snap element rotations to 2.5 degree steps. */
    public static final double ROTATION_STEP = 2.5D;
    /** Block-model UVs are normalised to 0..16 whatever the PNG size is. */
    public static final double MAX_UV = 16.0D;

    private static final Set<String> DIRECTIONS =
            Set.of("north", "south", "east", "west", "up", "down");
    private static final String[] AXIS_NAMES = {"x", "y", "z"};
    private static final Set<String> AXES = Set.of(AXIS_NAMES);

    private GateModelAudit() {
    }

    /**
     * Resource access for the audit.
     *
     * <p>{@link #exists} is separate from {@link #read} because textures are
     * binary: the audit only ever needs to know that the PNG is there, and must
     * not try to decode it as text.</p>
     */
    public interface Probe {
        /** @return {@code true} when the resource exists (exact, case-sensitive match). */
        boolean exists(String location);

        /** @return the UTF-8 text of a JSON resource, or {@code null} when it is missing. */
        @Nullable
        String read(String location);
    }

    /** One property of the block and the values vanilla allows for it. */
    public record PropertyDomain(String name, List<String> values) {
        public String joinedValues() {
            return String.join(",", values);
        }
    }

    /** One blockstate variant and the model it resolves to. */
    public record Variant(String key, String model, boolean modelPresent) {
    }

    /** One {@code textures} entry of a model and whether its PNG is present. */
    public record Texture(String key, String value, boolean present) {
    }

    /** Audit result of one model file. */
    public record ModelReport(String location, boolean present, int elements, List<Texture> textures,
                              List<String> problems) {
        public List<String> missingTextures() {
            List<String> missing = new ArrayList<>();
            for (Texture texture : textures) {
                if (!texture.present()) {
                    missing.add(texture.key() + " -> " + texture.value());
                }
            }
            return missing;
        }
    }

    /** Audit result of one gate. */
    public record Report(String gateId, String blockClass, List<PropertyDomain> properties,
                         List<Variant> variants, int expectedVariants, List<ModelReport> models,
                         List<String> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }

        public String propertySummary() {
            StringBuilder builder = new StringBuilder();
            for (PropertyDomain domain : properties) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(domain.name()).append('(').append(domain.values().size()).append(')');
            }
            return builder.toString();
        }

        public int missingTextures() {
            int missing = 0;
            for (ModelReport model : models) {
                missing += model.missingTextures().size();
            }
            return missing;
        }

        public int textureCount() {
            int count = 0;
            for (ModelReport model : models) {
                count += model.textures().size();
            }
            return count;
        }

        public int missingModels() {
            int missing = 0;
            for (ModelReport model : models) {
                if (!model.present()) {
                    missing++;
                }
            }
            return missing;
        }
    }

    // ------------------------------------------------------------------ domains

    /**
     * The block's real property set: name plus every legal value, read from the
     * live {@code StateDefinition}. Sorted by name so the report is stable.
     */
    public static List<PropertyDomain> domainsOf(Block block) {
        List<PropertyDomain> domains = new ArrayList<>();
        for (Property<?> property : block.getStateDefinition().getProperties()) {
            List<String> values = new ArrayList<>();
            appendValues(property, values);
            domains.add(new PropertyDomain(property.getName(), List.copyOf(values)));
        }
        domains.sort((left, right) -> left.name().compareTo(right.name()));
        return List.copyOf(domains);
    }

    private static <T extends Comparable<T>> void appendValues(Property<T> property, List<String> out) {
        for (T value : property.getPossibleValues()) {
            out.add(property.getName(value));
        }
    }

    // -------------------------------------------------------------------- audit

    /**
     * Audits one gate.
     *
     * @param probe      resource access
     * @param gateId     registry path, also the blockstate file name ({@code egate})
     * @param blockClass simple class name of the registered block, for the report
     * @param properties the block's real property domains ({@link #domainsOf})
     */
    public static Report audit(Probe probe, String gateId, String blockClass, List<PropertyDomain> properties) {
        List<String> problems = new ArrayList<>();
        Map<String, Set<String>> domain = new LinkedHashMap<>();
        for (PropertyDomain property : properties) {
            domain.put(property.name(), new LinkedHashSet<>(property.values()));
        }

        int expectedVariants = expectedVariantCount(properties);
        String blockstateLocation = NAMESPACE + ":blockstates/" + gateId + ".json";
        String blockstateText = probe.read(blockstateLocation);
        if (blockstateText == null) {
            problems.add("blockstate missing: " + blockstateLocation
                    + " — every state of this gate renders the missing model");
            return new Report(gateId, blockClass, properties, List.of(), expectedVariants, List.of(), problems);
        }

        JsonObject variantsObject = null;
        try {
            JsonElement root = JsonParser.parseString(blockstateText);
            if (root.isJsonObject()) {
                JsonElement variants = root.getAsJsonObject().get("variants");
                if (variants != null && variants.isJsonObject()) {
                    variantsObject = variants.getAsJsonObject();
                }
            }
        } catch (RuntimeException exception) {
            problems.add("blockstate is not valid JSON: " + blockstateLocation + " — " + exception.getMessage());
        }
        if (variantsObject == null) {
            if (problems.isEmpty()) {
                problems.add("blockstate has no \"variants\" object: " + blockstateLocation);
            }
            return new Report(gateId, blockClass, properties, List.of(), expectedVariants, List.of(), problems);
        }

        List<Variant> variants = new ArrayList<>();
        Set<String> seenCanonical = new LinkedHashSet<>();
        Set<String> modelPaths = new LinkedHashSet<>();

        for (Map.Entry<String, JsonElement> entry : variantsObject.entrySet()) {
            String rawKey = entry.getKey();
            Map<String, String> assignment = new LinkedHashMap<>();
            boolean keyUsable = true;
            for (String pair : rawKey.split(",")) {
                int equals = pair.indexOf('=');
                if (equals <= 0 || equals == pair.length() - 1) {
                    problems.add("variant key malformed: '" + rawKey + "'");
                    keyUsable = false;
                    break;
                }
                String name = pair.substring(0, equals).trim();
                String value = pair.substring(equals + 1).trim();
                Set<String> allowed = domain.get(name);
                if (allowed == null) {
                    problems.add("variant '" + rawKey + "' uses property '" + name
                            + "' that the block does not declare (declared: " + domain.keySet()
                            + ") — vanilla drops this variant and renders the missing model");
                    keyUsable = false;
                    continue;
                }
                if (!allowed.contains(value)) {
                    problems.add("variant '" + rawKey + "' sets " + name + "=" + value
                            + " but the block only allows " + allowed + " — same result: missing model");
                    keyUsable = false;
                    continue;
                }
                assignment.put(name, value);
            }
            if (keyUsable) {
                String canonical = canonicalKey(assignment);
                if (!seenCanonical.add(canonical)) {
                    problems.add("duplicate variant '" + canonical + "' — one of the two entries is dead");
                }
            }

            JsonObject variantObject = variantObject(entry.getValue());
            String model = null;
            if (variantObject != null) {
                JsonElement modelElement = variantObject.get("model");
                if (modelElement != null && modelElement.isJsonPrimitive()) {
                    model = modelElement.getAsString();
                }
            }
            if (model == null) {
                problems.add("variant '" + rawKey + "' has no \"model\" — vanilla renders the missing model");
                variants.add(new Variant(rawKey, "(none)", false));
                continue;
            }

            String path = stripNamespace(model);
            boolean present = probe.exists(NAMESPACE + ":models/" + path + ".json");
            variants.add(new Variant(rawKey, model, present));
            if (!present) {
                problems.add("variant '" + rawKey + "' -> " + model
                        + " but models/" + path + ".json is not in the jar");
            } else {
                modelPaths.add(path);
            }

            String rotation = variantObject.has("y") ? variantObject.get("y").toString() : null;
            if (rotation != null) {
                checkQuarterTurn(rawKey, variantObject, problems);
            }
        }

        // Coverage: every reachable state must have a variant, or vanilla falls
        // back to the missing model for the uncovered ones.
        if (variants.size() != expectedVariants) {
            problems.add("blockstate has " + variants.size() + " variants but " + expectedVariants
                    + " are required (" + propertySummary(properties) + ")");
        }
        Set<String> expected = cartesianKeys(properties);
        for (String key : expected) {
            if (!seenCanonical.contains(key)) {
                problems.add("no variant for '" + key + "' — that state renders the missing model");
            }
        }
        for (String key : seenCanonical) {
            if (!expected.contains(key)) {
                problems.add("unexpected variant '" + key + "' (not a state the block can reach)");
            }
        }

        List<ModelReport> models = new ArrayList<>();
        for (String path : modelPaths) {
            models.add(auditModel(probe, path, problems));
        }

        return new Report(gateId, blockClass, properties, List.copyOf(variants), expectedVariants,
                List.copyOf(models), List.copyOf(problems));
    }

    private static void checkQuarterTurn(String rawKey, JsonObject variantObject, List<String> problems) {
        JsonElement y = variantObject.get("y");
        if (!y.isJsonPrimitive() || !y.getAsJsonPrimitive().isNumber()) {
            problems.add("variant '" + rawKey + "' has a non-numeric \"y\" rotation");
            return;
        }
        double degrees = y.getAsDouble();
        if (degrees % 90.0D != 0.0D || degrees < 0.0D || degrees >= 360.0D) {
            problems.add("variant '" + rawKey + "' has y=" + y + " (vanilla blockstates use 0/90/180/270)");
        }
    }

    // ------------------------------------------------------------------- models

    /** Audits one model file: JSON, elements, bounds, rotations, faces, textures. */
    public static ModelReport auditModel(Probe probe, String path, List<String> problems) {
        String location = NAMESPACE + ":models/" + path + ".json";
        List<String> local = new ArrayList<>();
        String text = probe.read(location);
        if (text == null) {
            local.add("model missing: " + location);
            problems.addAll(local);
            return new ModelReport(location, false, 0, List.of(), List.copyOf(local));
        }

        JsonObject model = null;
        try {
            JsonElement root = JsonParser.parseString(text);
            if (root.isJsonObject()) {
                model = root.getAsJsonObject();
            }
        } catch (RuntimeException exception) {
            local.add("model is not valid JSON: " + location + " — " + exception.getMessage());
        }
        if (model == null) {
            if (local.isEmpty()) {
                local.add("model root is not a JSON object: " + location);
            }
            problems.addAll(local);
            return new ModelReport(location, false, 0, List.of(), List.copyOf(local));
        }

        List<Texture> textures = auditTextures(probe, location, model, local);
        Set<String> textureKeys = new LinkedHashSet<>();
        for (Texture texture : textures) {
            textureKeys.add(texture.key());
        }

        JsonElement elementsElement = model.get("elements");
        int elementCount = 0;
        if (elementsElement == null || !elementsElement.isJsonArray()) {
            local.add(location + " has no \"elements\" array — nothing to render");
        } else {
            JsonArray elements = elementsElement.getAsJsonArray();
            elementCount = elements.size();
            if (elementCount == 0) {
                local.add(location + " has an empty \"elements\" array — nothing to render");
            }
            for (int index = 0; index < elementCount; index++) {
                JsonElement element = elements.get(index);
                if (!element.isJsonObject()) {
                    local.add(location + " elements[" + index + "] is not an object");
                    continue;
                }
                auditElement(location, index, element.getAsJsonObject(), textureKeys, local);
            }
        }

        problems.addAll(local);
        return new ModelReport(location, true, elementCount, List.copyOf(textures), List.copyOf(local));
    }

    private static List<Texture> auditTextures(Probe probe, String location, JsonObject model,
                                               List<String> problems) {
        List<Texture> textures = new ArrayList<>();
        JsonElement texturesElement = model.get("textures");
        if (texturesElement == null || !texturesElement.isJsonObject()) {
            problems.add(location + " has no \"textures\" object");
            return List.of();
        }
        JsonObject texturesObject = texturesElement.getAsJsonObject();
        Map<String, String> raw = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : texturesObject.entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive()) {
                problems.add(location + " textures." + entry.getKey() + " is not a string");
                continue;
            }
            raw.put(entry.getKey(), value.getAsString());
        }
        Set<String> declared = raw.keySet();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            boolean present;
            String expected;
            if (value.startsWith("#")) {
                // A texture variable aliasing another variable.
                expected = "textures." + value.substring(1);
                present = declared.contains(value.substring(1));
            } else {
                String sprite = stripNamespace(value);
                expected = NAMESPACE + ":textures/" + sprite + ".png";
                present = probe.exists(expected);
            }
            textures.add(new Texture(key, value, present));
            if (!present) {
                problems.add(location + " textures." + key + " -> " + value
                        + " — " + expected + " is not in the jar (face renders as the missing texture)");
            }
        }
        return textures;
    }

    private static void auditElement(String location, int index, JsonObject element, Set<String> textureKeys,
                                     List<String> problems) {
        String where = location + " elements[" + index + "]";
        double[] from = coordinates(element, "from", where, problems);
        double[] to = coordinates(element, "to", where, problems);

        if (from != null && to != null) {
            for (int axis = 0; axis < 3; axis++) {
                String name = AXIS_NAMES[axis];
                if (from[axis] > to[axis]) {
                    problems.add(where + " inverted on " + name + ": from " + from[axis] + " > to " + to[axis]);
                }
            }
        }

        JsonElement rotationElement = element.get("rotation");
        if (rotationElement != null && rotationElement.isJsonObject()) {
            auditRotation(where, rotationElement.getAsJsonObject(), problems);
        }

        JsonElement facesElement = element.get("faces");
        if (facesElement == null || !facesElement.isJsonObject()) {
            problems.add(where + " has no \"faces\" — the element is invisible");
            return;
        }
        for (Map.Entry<String, JsonElement> entry : facesElement.getAsJsonObject().entrySet()) {
            String direction = entry.getKey();
            String faceWhere = where + "." + direction;
            if (!DIRECTIONS.contains(direction)) {
                problems.add(faceWhere + " is not a block face (north/south/east/west/up/down)");
            }
            if (!entry.getValue().isJsonObject()) {
                problems.add(faceWhere + " is not an object");
                continue;
            }
            JsonObject face = entry.getValue().getAsJsonObject();

            JsonElement texture = face.get("texture");
            if (texture == null || !texture.isJsonPrimitive()) {
                problems.add(faceWhere + " has no \"texture\" — renders as the missing texture");
            } else {
                String reference = texture.getAsString();
                if (!reference.startsWith("#")) {
                    problems.add(faceWhere + " texture '" + reference + "' must be a #variable reference");
                } else if (!textureKeys.contains(reference.substring(1))) {
                    problems.add(faceWhere + " references undefined texture '" + reference
                            + "' (declared: " + textureKeys + ")");
                }
            }

            JsonElement uv = face.get("uv");
            if (uv != null) {
                if (!uv.isJsonArray() || uv.getAsJsonArray().size() != 4) {
                    problems.add(faceWhere + " uv must be 4 numbers");
                } else {
                    for (JsonElement component : uv.getAsJsonArray()) {
                        double value = component.getAsDouble();
                        if (value < 0.0D || value > MAX_UV) {
                            problems.add(faceWhere + " uv " + value + " is outside 0.." + (int) MAX_UV);
                        }
                    }
                }
            }

            JsonElement faceRotation = face.get("rotation");
            if (faceRotation != null) {
                double degrees = faceRotation.getAsDouble();
                if (degrees != 0.0D && degrees != 90.0D && degrees != 180.0D && degrees != 270.0D) {
                    problems.add(faceWhere + " uv rotation " + degrees + " is not 0/90/180/270");
                }
            }

            JsonElement cullFace = face.get("cullface");
            if (cullFace != null && !DIRECTIONS.contains(cullFace.getAsString())) {
                problems.add(faceWhere + " cullface '" + cullFace.getAsString() + "' is not a block face");
            }
        }
    }

    private static void auditRotation(String where, JsonObject rotation, List<String> problems) {
        JsonElement origin = rotation.get("origin");
        if (origin == null || !origin.isJsonArray() || origin.getAsJsonArray().size() != 3) {
            problems.add(where + " rotation has no 3-component \"origin\" — vanilla cannot place it");
        }
        JsonElement axis = rotation.get("axis");
        if (axis == null || !axis.isJsonPrimitive() || !AXES.contains(axis.getAsString())) {
            problems.add(where + " rotation axis must be x, y or z (was "
                    + (axis == null ? "missing" : axis.toString()) + ")");
        }
        JsonElement angle = rotation.get("angle");
        if (angle == null || !angle.isJsonPrimitive() || !angle.getAsJsonPrimitive().isNumber()) {
            problems.add(where + " rotation has no numeric \"angle\"");
            return;
        }
        double degrees = angle.getAsDouble();
        if (Math.abs(degrees) > MAX_ROTATION) {
            problems.add(where + " rotation " + degrees + " exceeds the vanilla limit of +-" + (int) MAX_ROTATION);
        }
        if (!isMultipleOf(degrees, ROTATION_STEP)) {
            problems.add(where + " rotation " + degrees + " is not a multiple of " + ROTATION_STEP);
        }
    }

    @Nullable
    private static double[] coordinates(JsonObject element, String key, String where, List<String> problems) {
        JsonElement value = element.get(key);
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 3) {
            problems.add(where + " has no 3-component \"" + key + "\"");
            return null;
        }
        JsonArray array = value.getAsJsonArray();
        double[] result = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            JsonElement component = array.get(axis);
            if (!component.isJsonPrimitive() || !component.getAsJsonPrimitive().isNumber()) {
                problems.add(where + " \"" + key + "\" component " + axis + " is not a number");
                return null;
            }
            double number = component.getAsDouble();
            if (number < MIN_COORD || number > MAX_COORD) {
                problems.add(where + " \"" + key + "\"" + " axis " + axis + " = " + number
                        + " is outside " + (int) MIN_COORD + ".." + (int) MAX_COORD);
            }
            result[axis] = number;
        }
        return result;
    }

    private static boolean isMultipleOf(double value, double step) {
        double remainder = Math.abs(value % step);
        return remainder < 1.0E-9D || Math.abs(remainder - step) < 1.0E-9D;
    }

    @Nullable
    private static JsonObject variantObject(JsonElement value) {
        if (value.isJsonObject()) {
            return value.getAsJsonObject();
        }
        if (value.isJsonArray() && !value.getAsJsonArray().isEmpty()) {
            JsonElement first = value.getAsJsonArray().get(0);
            if (first.isJsonObject()) {
                return first.getAsJsonObject();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    /** {@code veriborder:block/egate} -> {@code block/egate}. */
    public static String stripNamespace(String reference) {
        int colon = reference.indexOf(':');
        return colon < 0 ? reference : reference.substring(colon + 1);
    }

    /** Alphabetical, order-insensitive form of one assignment, used for coverage. */
    public static String canonicalKey(Map<String, String> assignment) {
        return new TreeSet<>(assignment.keySet()).stream()
                .map(name -> name + "=" + assignment.get(name))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    /** Number of distinct states the block can be in. */
    public static int expectedVariantCount(List<PropertyDomain> properties) {
        int total = 1;
        for (PropertyDomain property : properties) {
            total *= property.values().size();
        }
        return total;
    }

    /** Every state key the block can be in, in the same canonical form. */
    public static Set<String> cartesianKeys(List<PropertyDomain> properties) {
        Set<String> keys = new LinkedHashSet<>();
        List<PropertyDomain> sorted = new ArrayList<>(properties);
        sorted.sort((left, right) -> left.name().compareTo(right.name()));
        expand(sorted, 0, new ArrayList<>(), keys);
        return keys;
    }

    private static void expand(List<PropertyDomain> properties, int index, List<String> parts, Set<String> keys) {
        if (index == properties.size()) {
            keys.add(String.join(",", parts));
            return;
        }
        PropertyDomain property = properties.get(index);
        for (String value : property.values()) {
            parts.add(property.name() + "=" + value);
            expand(properties, index + 1, parts, keys);
            parts.remove(parts.size() - 1);
        }
    }

    private static String propertySummary(List<PropertyDomain> properties) {
        StringBuilder builder = new StringBuilder();
        for (PropertyDomain property : properties) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(property.name()).append('=').append(property.values().size());
        }
        return builder.toString();
    }
}
