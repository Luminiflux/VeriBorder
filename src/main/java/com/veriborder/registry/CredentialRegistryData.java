package com.veriborder.registry;

import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * World-scoped credential registry (26.3 "注册器"): the global list of
 * countries/regions and document templates shared by every issuing machine.
 *
 * <p>Both lists are administrator-managed (OP level 2) and are synced to every
 * client as one snapshot compound. The registry is persisted through the
 * overworld {@code DimensionDataStorage}, so it survives restarts and works in
 * single-player (the integrated server owns the same {@link SavedData}).</p>
 *
 * <pre>
 * schema     int
 * countries  ListTag  {code, nameZh, nameEn, defaultNationality, note}
 * templates  ListTag  {id, kind, name, style, mrzCountry, locks, issuers, color, icon}
 * </pre>
 *
 * <p>A country code is exactly three letters {@code A-Z}; it is unique and
 * globally available (issuer nationality/country selectors read this list). A
 * template belongs to exactly one document kind ({@code ID_CARD} / {@code PASSPORT}
 * / {@code PERMIT}) and only appears in the selector of issuers that produce
 * that kind.</p>
 */
public final class CredentialRegistryData extends SavedData {
    public static final String DATA_NAME = "veriborder_registry";
    public static final int SCHEMA = 1;
    public static final int MAX_NAME_LENGTH = 64;
    /**
     * 26.3 round 12: the country note is an internal remark (it is never written
     * onto a document), so it is bounded to 64 characters.
     */
    public static final int MAX_NOTE_LENGTH = 64;
    public static final int MAX_STYLE_ID_LENGTH = 32;
    public static final int MAX_TEMPLATE_ID_LENGTH = 40;
    public static final int MAX_ICON_ID_LENGTH = 32;
    /** Icon used when a template does not pick one from the preset grid. */
    public static final String DEFAULT_ICON = "emblem";
    /** Registry entries are bounded so a forged payload cannot grow the save file. */
    public static final int MAX_COUNTRIES = 512;
    public static final int MAX_TEMPLATES = 256;

    /** One registered country/region. */
    public record Country(String code, String nameZh, String nameEn, String defaultNationality,
                          String note) {
        public static final Country EMPTY = new Country("", "", "", "", "");

        public Country {
            code = normalizeCode(code);
            nameZh = trim(nameZh, MAX_NAME_LENGTH);
            nameEn = trim(nameEn, MAX_NAME_LENGTH);
            defaultNationality = trim(defaultNationality, MAX_NAME_LENGTH);
            note = trim(note, MAX_NOTE_LENGTH);
        }

        public boolean valid() {
            return code.length() == 3 && !nameZh.isBlank() && !nameEn.isBlank();
        }

        /** Display name in the requested language, falling back to the other one. */
        public String displayName(boolean chinese) {
            if (chinese && !nameZh.isBlank()) {
                return nameZh;
            }
            if (!nameEn.isBlank()) {
                return nameEn;
            }
            return nameZh.isBlank() ? code : nameZh;
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("code", code);
            tag.putString("nameZh", nameZh);
            tag.putString("nameEn", nameEn);
            tag.putString("defaultNationality", defaultNationality);
            tag.putString("note", note);
            return tag;
        }

        public static Country fromTag(@Nullable CompoundTag tag) {
            if (tag == null) {
                return EMPTY;
            }
            return new Country(tag.getString("code"), tag.getString("nameZh"),
                    tag.getString("nameEn"), tag.getString("defaultNationality"),
                    tag.getString("note"));
        }
    }

    /**
     * One registered document template.
     *
     * <p>26.3 round 12: the {@code locks} and {@code issuers} components are kept
     * only so old snapshots still load; they are no longer editable, displayed or
     * used. A template belongs to exactly one credential kind and is offered by
     * that kind's own issuing machine ({@link DocumentKind#issuerId()}).</p>
     */
    public record Template(String id, DocumentKind kind, String name, String style, String mrzCountry,
                           String locks, String issuers, int color, String icon) {
        public static final Template EMPTY = new Template("", DocumentKind.PASSPORT, "", "",
                "", "", "", 0xFFFFFF, DEFAULT_ICON);

        public Template {
            id = trim(id, MAX_TEMPLATE_ID_LENGTH);
            kind = kind == null ? DocumentKind.PASSPORT : kind;
            name = trim(name, MAX_NAME_LENGTH);
            style = trim(style, MAX_STYLE_ID_LENGTH);
            mrzCountry = normalizeCountryCode(mrzCountry);
            locks = trim(locks, 160);
            issuers = trim(issuers, 160);
            icon = trim(icon, MAX_ICON_ID_LENGTH);
            if (icon.isBlank()) {
                icon = DEFAULT_ICON;
            }
        }

        /**
         * 26.4: only the identity and the name are required. The base style is
         * normalised by the server ({@link #normalized()}), so an empty, unknown
         * or wrong-kind style no longer makes an otherwise valid template
         * unsaveable (it used to surface as the misleading "needs a name" error).
         */
        public boolean valid() {
            return !id.isBlank() && !name.isBlank();
        }

        /**
         * Whether the stored style is a real layout of this template's credential
         * kind. Kept next to {@link #valid()} so callers that want to know why a
         * template was repaired can ask ({@link #normalized()} does the repair).
         */
        public boolean styleMatchesKind() {
            DocStyle style = DocStyle.byIdOrNull(this.style);
            return style != null && style.kind() == kind;
        }

        /**
         * 26.4 server-side normalisation: a blank, unknown or wrong-kind style is
         * replaced with the first official layout of the template's credential
         * kind. The identity and the name are never touched here; a template that
         * is still invalid after this call merely lacks an id or a name.
         */
        public Template normalized() {
            if (styleMatchesKind()) {
                return this;
            }
            List<DocStyle> styles = DocStyle.forKind(kind);
            String fallback = styles.isEmpty() ? "" : styles.get(0).id();
            return new Template(id, kind, name, fallback, mrzCountry, locks, issuers, color, icon);
        }

        /**
         * Whether this template may be offered by an issuer. 26.3 round 12: the
         * match is automatic by credential kind — {@code ID_CARD -> id_issuer},
         * {@code PASSPORT -> passport_issuer}, {@code PERMIT -> permit_issuer} —
         * so the old "allowed issuers" text field is gone. The legacy
         * {@code issuers} component is ignored; a blank issuer id still matches
         * (the registry snapshot itself does not know the caller).
         */
        public boolean allowsIssuer(String issuerId) {
            String needle = issuerId == null ? "" : issuerId.trim().toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) {
                return true;
            }
            return kind.issuerId().equalsIgnoreCase(needle);
        }

        /** Overlay this template's defaults onto an issuer form (26.3 selector). */
        public CompoundTag applyTo(CompoundTag form) {
            CompoundTag out = form == null ? new CompoundTag() : form.copy();
            out.putString("style", style);
            // One issuing-country field: the template's default country code is
            // the same code the passport MRZ will print.
            if (!mrzCountry.isBlank()) {
                out.putString(DocumentData.ISSUER_COUNTRY, mrzCountry);
            }
            out.putInt("customColor", color);
            out.putString("customIcon", icon);
            return out;
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", id);
            tag.putString("kind", kind.name());
            tag.putString("name", name);
            tag.putString("style", style);
            tag.putString("mrzCountry", mrzCountry);
            tag.putString("locks", locks);
            tag.putString("issuers", issuers);
            tag.putInt("color", color);
            tag.putString("icon", icon);
            return tag;
        }

        public static Template fromTag(@Nullable CompoundTag tag) {
            if (tag == null) {
                return EMPTY;
            }
            return new Template(tag.getString("id"), DocumentKind.byId(tag.getString("kind")),
                    tag.getString("name"), tag.getString("style"), tag.getString("mrzCountry"),
                    tag.getString("locks"), tag.getString("issuers"), tag.getInt("color"),
                    tag.getString("icon"));
        }
    }

    private final Map<String, Country> countries = new LinkedHashMap<>();
    private final Map<String, Template> templates = new LinkedHashMap<>();

    public CredentialRegistryData() {
    }

    /** Resolves the single world instance (integrated + dedicated server). */
    public static CredentialRegistryData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(CredentialRegistryData::load,
                CredentialRegistryData::new, DATA_NAME);
    }

    public static CredentialRegistryData load(@Nullable CompoundTag tag) {
        CredentialRegistryData data = new CredentialRegistryData();
        if (tag == null) {
            return data;
        }
        ListTag countryList = tag.getList("countries", Tag.TAG_COMPOUND);
        for (int i = 0; i < countryList.size() && data.countries.size() < MAX_COUNTRIES; i++) {
            Country country = Country.fromTag(countryList.getCompound(i));
            if (country.valid()) {
                data.countries.put(country.code(), country);
            }
        }
        ListTag templateList = tag.getList("templates", Tag.TAG_COMPOUND);
        for (int i = 0; i < templateList.size() && data.templates.size() < MAX_TEMPLATES; i++) {
            // Legacy rows may carry an empty or wrong-kind style; normalise them on
            // load so a repaired registry never fails to read back.
            Template template = Template.fromTag(templateList.getCompound(i)).normalized();
            if (template.valid()) {
                data.templates.put(template.id(), template);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("schema", SCHEMA);
        ListTag countryList = new ListTag();
        for (Country country : countries.values()) {
            countryList.add(country.toTag());
        }
        tag.put("countries", countryList);
        ListTag templateList = new ListTag();
        for (Template template : templates.values()) {
            templateList.add(template.toTag());
        }
        tag.put("templates", templateList);
        return tag;
    }

    // -------------------------------------------------------------- countries

    /** Registered countries, registration order. */
    public List<Country> countries() {
        return new ArrayList<>(countries.values());
    }

    @Nullable
    public Country country(String code) {
        return countries.get(normalizeCode(code));
    }

    /**
     * Registers or updates a country. The code is the primary key, so saving the
     * same code twice updates that entry instead of duplicating it.
     *
     * @return {@code false} when the country is invalid or the list is full
     */
    public boolean upsertCountry(Country country) {
        if (country == null || !country.valid()) {
            return false;
        }
        boolean isNew = !countries.containsKey(country.code());
        if (isNew && countries.size() >= MAX_COUNTRIES) {
            return false;
        }
        countries.put(country.code(), country);
        setDirty();
        return true;
    }

    public boolean removeCountry(String code) {
        boolean removed = countries.remove(normalizeCode(code)) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /**
     * Renames a registered country code (26.3 round 12 “改码”).
     *
     * <p>The code is the primary key, so a registered country cannot simply be
     * edited into a new code without leaving the old row behind. This operation
     * moves the row in place (registration order preserved) and re-points every
     * registered template whose default code referenced the old value.</p>
     *
     * <p><b>History is not rewritten:</b> documents already issued keep the old
     * three-letter code in their signed {@code issuerCountry} field. That is
     * deliberate — a document is a historical record — and the registry screen
     * says so before the operator confirms. Gate whitelists are re-pointed by
     * the caller ({@code RegistryService}), so a gate keeps accepting travellers
     * after the code changed.</p>
     *
     * @return {@code false} when either code is invalid, the old one is not
     *         registered, or the new one already belongs to another country
     */
    public boolean recodifyCountry(String oldCode, String newCode) {
        String from = normalizeCode(oldCode);
        String to = normalizeCode(newCode);
        if (from.isEmpty() || to.isEmpty() || from.equals(to)) {
            return false;
        }
        Country existing = countries.get(from);
        if (existing == null || countries.containsKey(to)) {
            return false;
        }
        List<Country> ordered = countries();
        countries.clear();
        for (Country country : ordered) {
            if (country.code().equals(from)) {
                countries.put(to, new Country(to, country.nameZh(), country.nameEn(),
                        country.defaultNationality(), country.note()));
            } else {
                countries.put(country.code(), country);
            }
        }
        List<Template> templateList = templates();
        for (Template template : templateList) {
            if (!template.mrzCountry().equals(from)) {
                continue;
            }
            templates.put(template.id(), new Template(template.id(), template.kind(), template.name(),
                    template.style(), to, template.locks(), template.issuers(), template.color(),
                    template.icon()));
        }
        setDirty();
        return true;
    }

    /**
     * Resolves the country/region whose code, English name, Chinese name or
     * default nationality matches {@code value}; used to derive the issuer country
     * code of a new document from the machine's configured country.
     */
    @Nullable
    public Country matchCountry(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String needle = value.trim();
        String normalized = normalizeForCompare(needle);
        Country byCode = countries.get(normalizeCode(needle));
        if (byCode != null) {
            return byCode;
        }
        for (Country country : countries.values()) {
            if (normalizeForCompare(country.nameZh()).equals(normalized)
                    || normalizeForCompare(country.nameEn()).equals(normalized)
                    || normalizeForCompare(country.defaultNationality()).equals(normalized)) {
                return country;
            }
        }
        // Containment is a last resort so "中国" also matches "中华人民共和国".
        for (Country country : countries.values()) {
            if (normalized.contains(normalizeForCompare(country.nameZh()))
                    || normalizeForCompare(country.nameZh()).contains(normalized)
                    || normalized.contains(normalizeForCompare(country.nameEn()))
                    || normalizeForCompare(country.nameEn()).contains(normalized)) {
                return country;
            }
        }
        return null;
    }

    // -------------------------------------------------------------- templates

    /** Registered templates, registration order. */
    public List<Template> templates() {
        return new ArrayList<>(templates.values());
    }

    /** Templates usable by an issuer producing {@code kind} and identified by {@code issuerId}. */
    public List<Template> templatesFor(DocumentKind kind, String issuerId) {
        List<Template> result = new ArrayList<>();
        for (Template template : templates.values()) {
            if (template.kind() == kind && template.allowsIssuer(issuerId)) {
                result.add(template);
            }
        }
        return result;
    }

    @Nullable
    public Template template(String id) {
        return templates.get(id == null ? "" : id.trim());
    }

    /**
     * Registers or updates a template. A blank id is derived from the kind and
     * name, so re-saving the same template updates it. 26.4: an invalid style is
     * repaired here (first layout of the kind) instead of refusing the save.
     */
    public boolean upsertTemplate(Template template) {
        if (template == null) {
            return false;
        }
        Template normalized = withDerivedId(template.normalized());
        if (!normalized.valid()) {
            return false;
        }
        boolean isNew = !templates.containsKey(normalized.id());
        if (isNew && templates.size() >= MAX_TEMPLATES) {
            return false;
        }
        templates.put(normalized.id(), normalized);
        setDirty();
        return true;
    }

    /** Whether no further template can be registered (26.4 save diagnostics). */
    public boolean templatesFull() {
        return templates.size() >= MAX_TEMPLATES;
    }

    public boolean removeTemplate(String id) {
        boolean removed = templates.remove(id == null ? "" : id.trim()) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /** Whole registry as one compound for the S→C snapshot packet. */
    public CompoundTag snapshot() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", SCHEMA);
        ListTag countryList = new ListTag();
        for (Country country : countries.values()) {
            countryList.add(country.toTag());
        }
        tag.put("countries", countryList);
        ListTag templateList = new ListTag();
        for (Template template : templates.values()) {
            templateList.add(template.toTag());
        }
        tag.put("templates", templateList);
        return tag;
    }

    // ---------------------------------------------------------------- helpers

    /** Exactly three letters A-Z, upper-cased; anything else becomes "". */
    public static String normalizeCode(@Nullable String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        return cleaned.length() == 3 ? cleaned : "";
    }

    /** MRZ issuing-country code: exactly three letters, otherwise "". */
    public static String normalizeCountryCode(@Nullable String value) {
        return normalizeCode(value);
    }

    public static boolean styleKindMatches(DocumentKind kind, String styleId) {
        if (kind == null || styleId == null || styleId.isBlank()) {
            return false;
        }
        DocStyle style = DocStyle.byId(styleId);
        return style.kind() == kind;
    }

    /** Stable template id when the administrator leaves it blank. */
    public static String deriveTemplateId(DocumentKind kind, String name) {
        String raw = name == null ? "" : name.trim();
        String slug = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.isBlank()) {
            // Non-Latin names (e.g. 中文模板) have no ASCII slug; the stable hash
            // keeps re-saving the same name an update instead of a duplicate.
            slug = "t" + Integer.toHexString(raw.hashCode());
        }
        String id = (kind == null ? "document" : kind.name().toLowerCase(Locale.ROOT)) + "_" + slug;
        return id.length() > MAX_TEMPLATE_ID_LENGTH ? id.substring(0, MAX_TEMPLATE_ID_LENGTH) : id;
    }

    private static Template withDerivedId(Template template) {
        if (!template.id().isBlank()) {
            return template;
        }
        return new Template(deriveTemplateId(template.kind(), template.name()), template.kind(),
                template.name(), template.style(), template.mrzCountry(), template.locks(),
                template.issuers(), template.color(), template.icon());
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    private static String normalizeForCompare(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s·・,，/|()（）-]", "");
    }
}
