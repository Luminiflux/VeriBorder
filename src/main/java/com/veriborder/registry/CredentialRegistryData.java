package com.veriborder.registry;

import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentKind;
import com.veriborder.doc.PortraitLibrary;
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
 * templates  ListTag  {id, kind, name, style, mrzCountry, locks, issuers, color, icon,
 *                      titleZh, titleEn, tint, emblem, texture}
 * textures   ListTag  {hash, data}   // custom cover base map, base64 PNG (26.4.1)
 * portraits  ListTag  {hash, data}   // uploaded portrait, base64 PNG (26.4.1)
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
    /** 26.4.1: preset emblem a template falls back to (same id as the default icon). */
    public static final String DEFAULT_EMBLEM = "emblem";
    /**
     * 26.4.2: the neutral card colour a template is saved with when the operator
     * picked no colour at all.
     *
     * <p>Storing {@code 0} for both {@code tint} and {@code color} meant "follow
     * the layout", which the issuing machine copied onto the document as "no
     * tint" — so a custom template rendered as its base layout's cover (a
     * Japanese-passport template stayed red) and, once the zero value reached the
     * item colour handler, could even paint a black plate. A saved template
     * therefore always carries a colour of its own; this neutral light grey is
     * what "no colour chosen" means.</p>
     */
    public static final int DEFAULT_TEMPLATE_TINT = 0xE6E8EA;
    public static final int MAX_EMBLEM_LENGTH = 32;
    /** Custom cover base-map hash: lowercase hex, bounded like a digest. */
    public static final int MAX_TEXTURE_HASH_LENGTH = 64;
    /** One uploaded cover base map: at most 64x64 pixels, 8 KiB of PNG. */
    public static final int MAX_TEXTURE_DIMENSION = 64;
    public static final int MAX_TEXTURE_BYTES = 8192;
    /** At most this many distinct cover base maps live in the registry. */
    public static final int MAX_TEXTURES = 16;
    /**
     * 26.4.1 snapshot budget: the base64 payload of all textures travels inside
     * one registry snapshot, so it is capped at 128 KiB (one 8 KiB PNG is roughly
     * 11 KiB base64). Textures that would overflow the budget are left out of the
     * snapshot, never out of the save file.
     */
    public static final int MAX_SNAPSHOT_TEXTURE_CHARS = 128 * 1024;
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
                           String locks, String issuers, int color, String icon,
                           String titleZh, String titleEn, int tint, String emblem, String texture) {
        public static final Template EMPTY = new Template("", DocumentKind.PASSPORT, "", "",
                "", "", "", 0xFFFFFF, DEFAULT_ICON);

        /**
         * Pre-26.4.1 form (nine components). The 26.4.1 template definition fields
         * take their defaults: no own title (the name is used), the layout's accent
         * colour as tint, the preset emblem and no custom base map.
         */
        public Template(String id, DocumentKind kind, String name, String style, String mrzCountry,
                        String locks, String issuers, int color, String icon) {
            this(id, kind, name, style, mrzCountry, locks, issuers, color, icon,
                    "", "", 0, DEFAULT_EMBLEM, "");
        }

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
            titleZh = trim(titleZh, MAX_NAME_LENGTH);
            titleEn = trim(titleEn, MAX_NAME_LENGTH);
            // The tint is stored raw — 0 alone would mean "the original layout's
            // colour", which keeps a template following its layout if the layout
            // palette changes. A template with NO colour at all (both fields 0) is
            // the case that used to break issuing: it is saved with the neutral
            // default instead of 0, so a saved template always has a colour.
            tint &= 0x00FFFFFF;
            if (tint == 0 && (color & 0x00FFFFFF) == 0) {
                tint = DEFAULT_TEMPLATE_TINT;
            }
            emblem = trim(emblem, MAX_EMBLEM_LENGTH);
            if (emblem.isBlank()) {
                emblem = DEFAULT_EMBLEM;
            }
            texture = normalizeTextureHash(texture);
        }

        /**
         * The template's own credential title in the requested language, falling
         * back to the other language and finally to {@link #name()}.
         */
        public String title(boolean chinese) {
            if (chinese) {
                if (!titleZh.isBlank()) {
                    return titleZh;
                }
                return titleEn.isBlank() ? name : titleEn;
            }
            if (!titleEn.isBlank()) {
                return titleEn;
            }
            return titleZh.isBlank() ? name : titleZh;
        }

        /** Whether the template carries a title of its own (not just {@link #name()}). */
        public boolean hasOwnTitle() {
            return !titleZh.isBlank() || !titleEn.isBlank();
        }

        /**
         * Accent colour actually used for the cover: the configured tint, then the
         * template's older {@code color} field (a template saved before 26.4.1 has
         * no tint at all), and finally the original layout's accent colour.
         *
         * <p>26.4.2: the fallback chain is kept for snapshots written by older
         * builds, but a template this build saves always carries
         * {@link #DEFAULT_TEMPLATE_TINT} at worst, so the result is never 0 for a
         * stored template.</p>
         */
        public int effectiveTint() {
            if (tint != 0) {
                return tint;
            }
            if ((color & 0x00FFFFFF) != 0) {
                return color & 0x00FFFFFF;
            }
            DocStyle layout = DocStyle.byIdOrNull(style);
            return layout == null ? 0 : layout.accentColor();
        }

        /** Whether the template uses an uploaded base map instead of the drawn one. */
        public boolean hasTexture() {
            return !texture.isBlank();
        }

        /** Copy of this template carrying another base-map hash ({@code ""} clears it). */
        public Template withTexture(@Nullable String hash) {
            return new Template(id, kind, name, style, mrzCountry, locks, issuers, color, icon,
                    titleZh, titleEn, tint, emblem, hash);
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
            return new Template(id, kind, name, fallback, mrzCountry, locks, issuers, color, icon,
                    titleZh, titleEn, tint, emblem, texture);
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
            // 26.4.1: the form also names the template it was filled from, so the
            // issue path can resolve the template's own title/tint/emblem/texture
            // server-side instead of trusting the client's copies of them.
            if (!id.isBlank()) {
                out.putString("templateId", id);
            }
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
            // 26.4.1 template definition. The new keys are always written so a
            // snapshot is self-describing; readers of older snapshots fall back.
            tag.putString("titleZh", titleZh);
            tag.putString("titleEn", titleEn);
            tag.putInt("tint", tint);
            tag.putString("emblem", emblem);
            tag.putString("texture", texture);
            return tag;
        }

        public static Template fromTag(@Nullable CompoundTag tag) {
            if (tag == null) {
                return EMPTY;
            }
            return new Template(tag.getString("id"), DocumentKind.byId(tag.getString("kind")),
                    tag.getString("name"), tag.getString("style"), tag.getString("mrzCountry"),
                    tag.getString("locks"), tag.getString("issuers"), tag.getInt("color"),
                    tag.getString("icon"), tag.getString("titleZh"), tag.getString("titleEn"),
                    tag.getInt("tint"), tag.getString("emblem"), tag.getString("texture"));
        }
    }

    private final Map<String, Country> countries = new LinkedHashMap<>();
    private final Map<String, Template> templates = new LinkedHashMap<>();
    /** 26.4.1 custom cover base maps, keyed by their lowercase hex hash. */
    private final Map<String, byte[]> textures = new LinkedHashMap<>();
    /**
     * 26.4.1 portrait library: the uploaded document portraits. It lives here so
     * the images travel inside the registry snapshot every client already
     * receives, and are persisted next to the registry's own data (no second
     * {@code SavedData} file, no extra packet and no protocol change).
     */
    private final PortraitLibrary portraits = new PortraitLibrary();

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
        // 26.4.1: the custom cover base maps stored next to the templates. A row
        // is re-validated on load, so a hand-edited save file cannot smuggle an
        // oversized or non-PNG payload into the registry.
        ListTag textureList = tag.getList("textures", Tag.TAG_COMPOUND);
        for (int i = 0; i < textureList.size() && data.textures.size() < MAX_TEXTURES; i++) {
            CompoundTag row = textureList.getCompound(i);
            byte[] png = decodeBase64(row.getString("data"));
            if (png != null) {
                data.putTextureChecked(row.getString("hash"), png);
            }
        }
        // 26.4.1: uploaded portraits. {@link PortraitLibrary#readFrom} re-validates
        // every row (PNG signature, 32/64 square, 16 KiB) and silently skips the
        // rest, so a hand-edited save cannot load an oversized payload.
        data.portraits.readFrom(tag);
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
        // The save file always keeps every base map; only the wire snapshot is
        // budgeted (see MAX_SNAPSHOT_TEXTURE_CHARS).
        writeTextures(tag, false);
        // 26.4.1: the save file keeps every uploaded portrait too; the snapshot
        // below is the budgeted copy.
        portraits.writeTo(tag);
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
                    template.icon(), template.titleZh(), template.titleEn(), template.tint(),
                    template.emblem(), template.texture()));
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
        // 26.4.1: base maps travel as base64 inside the same snapshot, capped so a
        // registry full of 64x64 covers can never blow past a packet budget.
        writeTextures(tag, true);
        // 26.4.1: uploaded portraits ride along as {portraits: [{hash, data}]},
        // budgeted to 256 KiB of base64 so one sync stays a single small packet.
        CompoundTag portraitSnapshot = portraits.snapshot();
        tag.put(PortraitLibrary.KEY, portraitSnapshot.getList(PortraitLibrary.KEY, Tag.TAG_COMPOUND));
        return tag;
    }

    // --------------------------------------------------------------- portraits

    /** 26.4.1 portrait library of this world (presets plus uploaded images). */
    public PortraitLibrary portraits() {
        return portraits;
    }

    /**
     * Stores one uploaded portrait and marks the registry dirty so it is written
     * to disk. Limits stay in {@link PortraitLibrary#put(byte[])}.
     */
    public PortraitLibrary.PortraitResult putPortrait(@Nullable byte[] png) {
        PortraitLibrary.PortraitResult result = portraits.put(png);
        if (result.stored()) {
            setDirty();
        }
        return result;
    }

    /** Removes one uploaded portrait by id or hash; returns whether it existed. */
    public boolean removePortrait(@Nullable String id) {
        boolean removed = portraits.remove(id);
        if (removed) {
            setDirty();
        }
        return removed;
    }

    // ---------------------------------------------------------------- textures

    /** Why an uploaded cover base map was refused (26.4.1). */
    public enum TextureRejection {
        /** Accepted. */
        NONE(""),
        EMPTY("empty"),
        NOT_PNG("not_png"),
        TOO_LARGE("too_large"),
        TOO_WIDE("too_wide"),
        TOO_TALL("too_tall"),
        TOO_MANY("too_many"),
        BAD_HASH("bad_hash");

        private final String id;

        TextureRejection(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** Translation key of the refusal, rendered by the registry editor. */
        public String messageKey() {
            return "message.veriborder.registry.texture_" + id;
        }
    }

    /**
     * Outcome of one upload.
     *
     * @param stored    whether the base map is now in the registry (a duplicate
     *                  hash counts as stored: it is already there)
     * @param rejection concrete refusal reason, {@link TextureRejection#NONE} on success
     * @param hash      normalised hash of the stored (or offered) base map
     */
    public record TextureResult(boolean stored, TextureRejection rejection, String hash) {
        public boolean ok() {
            return stored;
        }

        /** Translation key of the refusal, {@code ""} when the upload was accepted. */
        public String errorKey() {
            return rejection == null || rejection == TextureRejection.NONE ? "" : rejection.messageKey();
        }
    }

    /** Stored base-map hashes, registration order. */
    public List<String> textureHashes() {
        return new ArrayList<>(textures.keySet());
    }

    /** Copy of every stored base map (hash → PNG bytes). */
    public Map<String, byte[]> textures() {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        textures.forEach((hash, png) -> copy.put(hash, png.clone()));
        return copy;
    }

    /** The stored base map behind {@code hash}, or {@code null}. */
    @Nullable
    public byte[] texture(@Nullable String hash) {
        byte[] png = textures.get(normalizeTextureHash(hash));
        return png == null ? null : png.clone();
    }

    public boolean hasTexture(@Nullable String hash) {
        return textures.containsKey(normalizeTextureHash(hash));
    }

    /**
     * Stores one custom cover base map (26.4.1).
     *
     * <p>Limits, all enforced here so a forged packet cannot grow the save file:
     * the payload must be a real PNG, at most {@link #MAX_TEXTURE_BYTES} bytes,
     * at most {@link #MAX_TEXTURE_DIMENSION}×{@link #MAX_TEXTURE_DIMENSION}
     * pixels, and the registry holds at most {@link #MAX_TEXTURES} of them. A
     * blank hash is derived from the content; re-uploading the same hash is a
     * no-op (deduplicated), which is what makes one base map shareable by
     * several templates.</p>
     *
     * @return {@code true} when the base map is stored (or already was)
     */
    public boolean putTexture(String hash, byte[] png) {
        return putTextureChecked(hash, png).stored();
    }

    /** {@link #putTexture(String, byte[])} with the concrete refusal reason. */
    public TextureResult putTextureChecked(@Nullable String hash, byte[] png) {
        String provided = hash == null ? "" : hash.trim();
        String normalized = normalizeTextureHash(provided);
        if (!provided.isEmpty() && normalized.isEmpty()) {
            // A malformed hash is refused instead of being silently replaced by a
            // different one: the caller must know which base map it now owns.
            return new TextureResult(false, TextureRejection.BAD_HASH, "");
        }
        if (png == null || png.length == 0) {
            return new TextureResult(false, TextureRejection.EMPTY, normalized);
        }
        int[] size = pngSize(png);
        if (size == null) {
            return new TextureResult(false, TextureRejection.NOT_PNG, normalized);
        }
        if (png.length > MAX_TEXTURE_BYTES) {
            return new TextureResult(false, TextureRejection.TOO_LARGE, normalized);
        }
        if (size[0] > MAX_TEXTURE_DIMENSION) {
            return new TextureResult(false, TextureRejection.TOO_WIDE, normalized);
        }
        if (size[1] > MAX_TEXTURE_DIMENSION) {
            return new TextureResult(false, TextureRejection.TOO_TALL, normalized);
        }
        if (normalized.isBlank()) {
            normalized = hashOf(png);
        }
        if (normalized.isBlank()) {
            return new TextureResult(false, TextureRejection.BAD_HASH, "");
        }
        if (textures.containsKey(normalized)) {
            // Same content hash already stored: share it instead of duplicating.
            return new TextureResult(true, TextureRejection.NONE, normalized);
        }
        if (textures.size() >= MAX_TEXTURES) {
            return new TextureResult(false, TextureRejection.TOO_MANY, normalized);
        }
        textures.put(normalized, png.clone());
        setDirty();
        return new TextureResult(true, TextureRejection.NONE, normalized);
    }

    /** Removes one base map. Returns whether anything was stored under that hash. */
    public boolean removeTexture(@Nullable String hash) {
        boolean removed = textures.remove(normalizeTextureHash(hash)) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /**
     * Drops every base map no template points at any more (26.4.1 "释放槽位").
     * Called after a template is deleted so the freed slot can be reused.
     *
     * @return how many base maps were released
     */
    public int releaseUnusedTextures() {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (Template template : templates.values()) {
            if (template.hasTexture()) {
                used.add(template.texture());
            }
        }
        int before = textures.size();
        textures.keySet().removeIf(hash -> !used.contains(hash));
        if (textures.size() != before) {
            setDirty();
        }
        return before - textures.size();
    }

    /** Writes the texture rows as {@code {hash, data(base64)}}. */
    private void writeTextures(CompoundTag tag, boolean budgeted) {
        ListTag list = new ListTag();
        int chars = 0;
        for (Map.Entry<String, byte[]> entry : textures.entrySet()) {
            String base64 = java.util.Base64.getEncoder().encodeToString(entry.getValue());
            if (budgeted && chars + base64.length() > MAX_SNAPSHOT_TEXTURE_CHARS) {
                // Out of the wire budget: the save file keeps it, the snapshot
                // simply does not carry it.
                break;
            }
            chars += base64.length();
            CompoundTag row = new CompoundTag();
            row.putString("hash", entry.getKey());
            row.putString("data", base64);
            list.add(row);
        }
        tag.put("textures", list);
    }

    /**
     * Dimensions of a PNG payload, or {@code null} when the bytes are not a PNG.
     *
     * <p>Read straight out of the IHDR chunk (signature + {@code IHDR} + width +
     * height) instead of {@code ImageIO}, so the check is pure, fast and works in
     * a headless unit test — decoding an attacker's image is exactly what must
     * not happen here.</p>
     */
    @Nullable
    public static int[] pngSize(@Nullable byte[] png) {
        if (png == null || png.length < 24) {
            return null;
        }
        int[] signature = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < signature.length; i++) {
            if ((png[i] & 0xFF) != signature[i]) {
                return null;
            }
        }
        if (png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') {
            return null;
        }
        int width = intAt(png, 16);
        int height = intAt(png, 20);
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new int[]{width, height};
    }

    private static int intAt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    /** SHA-256 of a payload as lowercase hex (the default base-map hash). */
    public static String hashOf(@Nullable byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /**
     * A base-map hash is lowercase hex, at most {@link #MAX_TEXTURE_HASH_LENGTH}
     * characters; anything else becomes "" (and is then derived from the bytes).
     */
    public static String normalizeTextureHash(@Nullable String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim().toLowerCase(Locale.ROOT);
        if (cleaned.length() > MAX_TEXTURE_HASH_LENGTH || !cleaned.matches("[0-9a-f]+")) {
            return "";
        }
        return cleaned;
    }

    @Nullable
    private static byte[] decodeBase64(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.util.Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
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
                template.issuers(), template.color(), template.icon(), template.titleZh(),
                template.titleEn(), template.tint(), template.emblem(), template.texture());
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
