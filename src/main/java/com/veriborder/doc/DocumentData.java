package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable view over the single NBT compound stored on a document ItemStack.
 * Every setter re-signs the document, so the universal modifier can freely edit
 * all fields while readers still detect external NBT tampering.
 */
public final class DocumentData {
    public static final String ROOT = "VeriBorder";
    /** Current document schema; {@link DocumentSchema} owns the upgrade path. */
    public static final int SCHEMA = DocumentSchema.CURRENT;

    /** Signed signature key of the holder: {@code normalize(name)|birthDate}. */
    public static final String PERSON_KEY = "personKey";
    /**
     * 26.3 registry: the single issuing-country field, three letters {@code A-Z}.
     * The passport MRZ lines read the same value (no separate MRZ field).
     */
    public static final String ISSUER_COUNTRY = "issuerCountry";
    /** Pre-26.3 MRZ-country field, still read on legacy documents only. */
    public static final String LEGACY_MRZ_COUNTRY = "mrzCountry";
    /** Signed list of {@link PermanentCode} compounds (26.3); legacy {@code visaFreeCodes} is read too. */
    public static final String PERMANENT_CODES = "permanentCodes";
    /** Pre-26.3 permanent-code storage: a plain string list, still read for compatibility. */
    public static final String LEGACY_VISA_FREE_CODES = "visaFreeCodes";
    /** At most this many permanent codes per document. */
    public static final int MAX_PERMANENT_CODES = 16;

    /**
     * 26.4.1 registry template: id of the template this document was issued from.
     * Empty on a document issued with a plain official layout. The document
     * keeps its own copy of the template's look ({@link #TEMPLATE_TINT},
     * {@link #TEMPLATE_EMBLEM}, {@link #TEMPLATE_TEXTURE}) so deleting a template
     * never changes a document that was already issued.
     *
     * <p>26.4.2: the id is mirrored into the signed style as
     * {@link #TEMPLATE_STYLE_PREFIX}{@code <id>} — see
     * {@link #isTemplateStyle()}.</p>
     */
    public static final String TEMPLATE_ID = "templateId";
    /** Signed accent tint of the issuing template; 0 means "use the layout colour". */
    public static final String TEMPLATE_TINT = "tint";
    /** Signed preset emblem id of the issuing template. */
    public static final String TEMPLATE_EMBLEM = "emblem";
    /** Signed hash of the template's custom cover base map ("" = drawn cover). */
    public static final String TEMPLATE_TEXTURE = "templateTex";
    /** Preset emblem used when a document carries none (old documents included). */
    public static final String DEFAULT_EMBLEM = "emblem";
    /**
     * 26.4.2 style identity: a signed {@code style} of the form
     * {@code template:<templateId>} says "this document was issued from registry
     * template <id>" instead of naming an official layout.
     *
     * <p>Why the style and not just {@link #TEMPLATE_ID}: a viewer that only reads
     * {@code templateId} still looks the cover up by the layout id, so a document
     * issued from a Japanese-passport based template kept drawing the Japanese
     * red cover. The style is the single field every renderer already resolves, so
     * marking it as a template style is what makes "自定义版式" show up as one.</p>
     *
     * <p>The identity is a <em>rendering</em> hint only. The document keeps its own
     * signed snapshot of the template look ({@link #TEMPLATE_TINT},
     * {@link #TEMPLATE_EMBLEM}, {@link #TEMPLATE_TEXTURE}), and
     * {@link #baseStyleId()} still names the official layout underneath — used for
     * the credential kind, the MRZ, the item model and as the fallback when the
     * template was deleted. A document issued before 26.4.2 carries a plain layout
     * id here and behaves exactly as it always did.</p>
     */
    public static final String TEMPLATE_STYLE_PREFIX = "template:";
    /**
     * 26.4.2: the official layout the template was based on, signed next to
     * {@code style}.
     *
     * <p>{@code template:<id>} names a registry template, not a layout, so the
     * layout underneath cannot be recovered from the style alone — and it must not
     * depend on the registry either (a deleted template, a client that never
     * received the snapshot, a border terminal reading a summary). Storing it on
     * the document makes {@link #baseStyleId()} a self-contained fallback: kind,
     * MRZ, item model and cover art keep working forever. Documents issued before
     * 26.4.2 have no such key and read their style as a plain layout, as always.</p>
     */
    public static final String TEMPLATE_BASE_STYLE = "baseStyle";

    /**
     * 26.4.1 portrait reference: {@code p00}..{@code p23} (built-in preset),
     * {@code skin} (the holder's player skin, the default) or
     * {@code custom:<hash>} (an uploaded PNG held by
     * {@link PortraitLibrary}). Only the reference is signed, never the image.
     */
    public static final String PORTRAIT = "portrait";
    /**
     * 26.4.1 owner of the portrait: the UUID the {@code skin} reference reads and
     * the player who uploaded a {@code custom:<hash>} image.
     */
    public static final String PORTRAIT_OWNER = "portraitOwner";
    /** Portrait a document falls back to when it carries none. */
    public static final String DEFAULT_PORTRAIT = PortraitLibrary.DEFAULT_ID;

    /** Clock format of {@link StampRecord#time()}. */
    private static final DateTimeFormatter STAMP_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * One permanent credential code carried by a document.
     *
     * @param code          eight-digit credential code
     * @param issuerCountry the country this code belongs to; blank on legacy
     *                      codes, which match every gate (universal compatibility)
     * @param purpose       {@link PermanentPurpose#RETURN_HOME} (re-enter the
     *                      issuing country) or {@link PermanentPurpose#VISA_FREE}
     *                      (visa-free entry into the destination country)
     * @param expiry        optional {@code YYYY-MM-DD} expiry; blank = never
     */
    public record PermanentCode(String code, String issuerCountry, PermanentPurpose purpose, String expiry) {
        public PermanentCode {
            code = code == null ? "" : code.trim();
            issuerCountry = issuerCountry == null ? "" : issuerCountry.trim();
            purpose = purpose == null ? PermanentPurpose.RETURN_HOME : purpose;
            expiry = expiry == null ? "" : expiry.trim();
        }

        public static PermanentCode universal(String code) {
            return new PermanentCode(code, "", PermanentPurpose.VISA_FREE, "");
        }

        public boolean hasIssuerCountry() {
            return !issuerCountry.isBlank();
        }

        public boolean hasExpiry() {
            return DateUtil.isValid(expiry);
        }

        public boolean expired() {
            return hasExpiry() && DateUtil.expired(expiry);
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("code", code);
            tag.putString("issuerCountry", issuerCountry);
            tag.putString("purpose", purpose.id());
            if (!expiry.isBlank()) {
                tag.putString("expiry", expiry);
            }
            return tag;
        }

        public static PermanentCode fromTag(@Nullable CompoundTag tag) {
            if (tag == null) {
                return null;
            }
            String code = CredentialCode.sanitize(tag.getString("code"));
            if (!CredentialCode.isValid(code)) {
                return null;
            }
            return new PermanentCode(code, tag.getString("issuerCountry"),
                    PermanentPurpose.byId(tag.getString("purpose")), tag.getString("expiry"));
        }
    }

    /** Purpose of a permanent code; also its persisted id. */
    public enum PermanentPurpose {
        /** Re-entering the country that issued the document. */
        RETURN_HOME("return_home"),
        /** Visa-free entry into the country the code was issued for. */
        VISA_FREE("visa_free");

        private final String id;

        PermanentPurpose(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static PermanentPurpose byId(@Nullable String id) {
            String value = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            return VISA_FREE.id.equals(value) ? VISA_FREE : RETURN_HOME;
        }
    }

    private final CompoundTag tag;
    private DocStyle style;
    /** Registry template carried by the signed {@code style}, {@code ""} for a plain layout. */
    private String templateStyleId;

    private DocumentData(CompoundTag tag) {
        this.tag = tag;
        String rawStyle = tag.getString("style");
        this.templateStyleId = templateStyleIdOf(rawStyle);
        // The layout underneath a template style comes from the document's own
        // recorded base layout: "template:<id>" alone does not name a layout, and
        // the registry may have deleted the template long ago.
        this.style = DocStyle.byId(baseStyleIdOf(rawStyle, tag.getString(TEMPLATE_BASE_STYLE)));
    }

    /**
     * Template id inside a raw {@code style} value, {@code ""} when the value
     * names an official layout (or is blank/unknown).
     */
    public static String templateStyleIdOf(@Nullable String rawStyle) {
        String raw = rawStyle == null ? "" : rawStyle.trim();
        if (!raw.startsWith(TEMPLATE_STYLE_PREFIX)) {
            return "";
        }
        return raw.substring(TEMPLATE_STYLE_PREFIX.length()).trim();
    }

    /**
     * Base layout id of a raw {@code style} value.
     *
     * <p>A plain layout id is returned unchanged. For {@code template:<id>} the
     * value only names the template, so the recorded base layout
     * ({@link #TEMPLATE_BASE_STYLE}) is the answer; {@code ""} when the document
     * carries none, which callers treat as "no layout recorded".</p>
     */
    public static String baseStyleIdOf(@Nullable String rawStyle, @Nullable String recordedBase) {
        String raw = rawStyle == null ? "" : rawStyle.trim();
        if (!raw.startsWith(TEMPLATE_STYLE_PREFIX)) {
            return raw;
        }
        return recordedBase == null ? "" : recordedBase.trim();
    }

    public static DocumentData createEmpty(DocStyle style) {
        // 26.3: the blank, signed document only depends on the layout and today's
        // date, so it is served from DocumentTemplateCache. The cache hands out a
        // copy, meaning the returned document is independent of every other one.
        return new DocumentData(DocumentTemplateCache.template(style, () -> buildEmptyTag(style)));
    }

    /** Builds and signs one blank layout; called by the template cache on a miss. */
    private static CompoundTag buildEmptyTag(DocStyle style) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", SCHEMA);
        tag.putString("style", style.id());
        tag.putString("kind", style.kind().name());
        tag.putString("name", "");
        tag.putString("nameLatin", "");
        tag.putString("sex", "M");
        tag.putString("number", "");
        tag.putString("birthDate", "1990-01-01");
        tag.putString("address", "");
        tag.putString("authority", style.defaultAuthority());
        tag.putString("issueDate", DateUtil.today());
        tag.putString("expiryDate", style.permanent() ? DateUtil.PERMANENT : DateUtil.plusYears(DateUtil.today(), style.validityYears()));
        tag.putString("nationality", style.defaultNationality());
        tag.putString("placeOfIssue", style.defaultPlace());
        tag.putString("status", DocumentStatus.VALID.id());
        tag.putString("mrz1", "");
        tag.putString("mrz2", "");
        // 26.3 registry: every document kind carries the same single issuing-country
        // field. Passports print it in both MRZ lines; IDs/permits just store it.
        tag.putString(ISSUER_COUNTRY, style.mrzCountry());
        // 26.4.1: a new document carries the default portrait reference ("skin").
        // The owner key is only written once setPortrait resolves a UUID, so a
        // blank document stays free of empty strings in its signed payload.
        tag.putString(PORTRAIT, DEFAULT_PORTRAIT);
        tag.put("visas", new ListTag());
        tag.put("endorsements", new ListTag());
        tag.put("stamps", new ListTag());
        tag.put(PERMANENT_CODES, new ListTag());
        new DocumentData(tag).sign();
        return tag;
    }

    public static DocumentData fromTag(CompoundTag tag) {
        if (tag == null) {
            throw new IllegalArgumentException("Document tag cannot be null");
        }
        // 26.3 schema upgrade (document schema 2). Idempotent and tamper-aware:
        // an authentic legacy tag is upgraded and re-signed, a tag that fails its
        // own checksum is left exactly as it is.
        DocumentSchema.migrate(tag);
        if (!tag.contains("style")) {
            tag.putString("style", DocStyle.CN_ID.id());
        }
        if (!tag.contains("kind")) {
            tag.putString("kind", DocStyle.byId(baseStyleIdOf(tag.getString("style"),
                    tag.getString(TEMPLATE_BASE_STYLE))).kind().name());
        }
        if (!tag.contains("status")) {
            tag.putString("status", DocumentStatus.VALID.id());
        }
        if (!tag.contains("visas", Tag.TAG_LIST)) {
            tag.put("visas", new ListTag());
        }
        if (!tag.contains("endorsements", Tag.TAG_LIST)) {
            tag.put("endorsements", new ListTag());
        }
        if (!tag.contains("stamps", Tag.TAG_LIST)) {
            tag.put("stamps", new ListTag());
        }
        // "permanentCodes" / "visaFreeCodes" are deliberately NOT materialised
        // here: adding a key to a legacy tag would change its checksum and flag
        // every old document as tampered. Readers treat a missing list as empty
        // and the list is created (and signed) on the first add.
        return new DocumentData(tag);
    }

    public CompoundTag tag() {
        return tag;
    }

    public CompoundTag copyTag() {
        return tag.copy();
    }

    public DocumentData copy() {
        return new DocumentData(tag.copy());
    }

    public DocStyle style() {
        return style;
    }

    // ------------------------------------------------------ 26.4.2 style identity

    /**
     * Whether the signed {@code style} names a registry template
     * ({@code template:<id>}) rather than an official layout.
     *
     * <p>{@code false} for every document issued before 26.4.2 and for every
     * document issued from a plain layout, so old data keeps reading and rendering
     * exactly as it did.</p>
     */
    public boolean isTemplateStyle() {
        return !templateStyleId.isBlank();
    }

    /**
     * Registry template id carried by the style, {@code ""} when the document
     * carries a plain layout id.
     *
     * <p>Read from the signed style itself, so it is available even when the
     * registry no longer knows the template: a viewer can then still say "this is
     * a custom layout" and draw the neutral template cover.</p>
     */
    public String templateStyleId() {
        return templateStyleId;
    }

    /**
     * Official layout id under the style: the recorded base layout for a
     * {@code template:<id>} style, the style value itself for a plain layout.
     *
     * <p>This is the documented fallback for a template that was deleted or that
     * the client never received: rendering by {@code baseStyleId()} always works
     * and never throws, and it is also what the kind, the MRZ and the item model
     * have always read.</p>
     */
    public String baseStyleId() {
        String base = baseStyleIdOf(tag.getString("style"), tag.getString(TEMPLATE_BASE_STYLE));
        // "template:" with no usable recorded layout: fall back to the parsed
        // layout instead of handing the caller an empty id.
        return base.isBlank() ? style.id() : base;
    }

    public DocumentKind kind() {
        return style.kind();
    }

    public boolean customStyle() {
        return tag.getBoolean("customStyle");
    }

    /**
     * Free-text name stored independently from the custom cover. Blank when the
     * document simply uses its official layout name or its custom cover name.
     */
    public String displayNameOverride() {
        return tag.getString("displayName").trim();
    }

    public boolean hasCustomDisplayName() {
        return !displayNameOverride().isBlank();
    }

    /**
     * Sets the independent display name. An empty value removes the override so
     * the document falls back to the custom cover name or the official style.
     */
    public void setDisplayName(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) {
            tag.remove("displayName");
        } else {
            tag.putString("displayName", trimmed);
        }
        sign();
    }

    /**
     * Resolution order: explicit display name override, then the custom cover's
     * cover name, then the official style translation.
     */
    public String displayName() {
        String override = displayNameOverride();
        if (!override.isBlank()) {
            return override;
        }
        if (customStyle()) {
            String custom = tag.getString("customName").trim();
            if (!custom.isBlank()) {
                return custom;
            }
        }
        return Component.translatable(style.displayNameKey()).getString();
    }

    public Component displayNameComponent() {
        String override = displayNameOverride();
        if (!override.isBlank()) {
            return Component.literal(override);
        }
        if (customStyle()) {
            String custom = tag.getString("customName").trim();
            if (!custom.isBlank()) {
                return Component.literal(custom);
            }
        }
        return Component.translatable(style.displayNameKey());
    }

    public String shortName() {
        if (customStyle()) {
            String custom = tag.getString("customShortName").trim();
            if (!custom.isBlank()) {
                return custom;
            }
        }
        return Component.translatable(style.shortNameKey()).getString();
    }

    /**
     * 26.4 display fix: the title printed on the document cover plate.
     *
     * <p>Resolution order is the explicit (issuer-locked or moderator-set)
     * display name, then the custom cover's short name, then the layout's short
     * name — the pre-26.4 cover behaviour. The holder name
     * ({@link #name()}) is a different field: only the document title may ever
     * replace the layout name on the cover.</p>
     */
    public String coverName() {
        return DocumentTitles.coverLabel(displayNameOverride(), shortName());
    }

    /**
     * Whether the document carries a name of its own rather than the official
     * layout name: an explicit display name or a custom cover's own name.
     *
     * <p>Used when a document is summarised for another screen (the border
     * terminal snapshot): only a custom title is worth transporting, because the
     * official title can be re-derived from the layout id and would otherwise
     * replace the layout's short cover name.</p>
     */
    public boolean hasCustomTitle() {
        if (!displayNameOverride().isBlank()) {
            return true;
        }
        return customStyle() && !tag.getString("customName").trim().isBlank();
    }

    public String documentType() {
        if (customStyle()) {
            String custom = tag.getString("customType").trim();
            if (!custom.isBlank()) {
                return custom;
            }
        }
        return Component.translatable(kind().translationKey()).getString();
    }

    public int accentColor() {
        if (customStyle() || tag.getBoolean("styleColor")) {
            return tag.getInt("customColor") & 0x00FFFFFF;
        }
        return style.accentColor();
    }

    /**
     * 26.4 registry layout lock: the accent colour of the locked template, without
     * turning the document into a custom cover (the official title, short name,
     * type and item model are kept). {@link #accentColor()} honours it.
     */
    public void setStyleColor(int color) {
        tag.putInt("customColor", color & 0x00FFFFFF);
        tag.putBoolean("styleColor", true);
        sign();
    }

    /** Whether the document carries a registry-template accent colour override. */
    public boolean hasStyleColor() {
        return tag.getBoolean("styleColor");
    }

    /** 26.4 registry layout lock: icon id of the locked template ("" when unset). */
    public String customIcon() {
        return tag.getString("customIcon").trim();
    }

    /** Stores the registry-template icon; blank removes it. Signing follows. */
    public void setCustomIcon(String icon) {
        String trimmed = icon == null ? "" : icon.trim();
        if (trimmed.isBlank()) {
            tag.remove("customIcon");
        } else {
            tag.putString("customIcon", trimmed);
        }
        sign();
    }

    // ------------------------------------------------- 26.4.1 registry template

    /**
     * Registry template this document was issued from, {@code ""} when it came
     * straight from an official layout. Old documents simply have no key.
     */
    public String templateId() {
        return tag.getString(TEMPLATE_ID).trim();
    }

    /** Whether the document was issued from a registry template. */
    public boolean hasRegistryTemplate() {
        return !templateId().isBlank();
    }

    /**
     * Signed accent tint of the issuing template, masked to {@code 0xRRGGBB}.
     * {@code 0} means "no tint recorded": use the layout colour
     * ({@link #effectiveTint()}).
     */
    public int tint() {
        return tag.contains(TEMPLATE_TINT, Tag.TAG_INT) ? tag.getInt(TEMPLATE_TINT) & 0x00FFFFFF : 0;
    }

    /** Accent colour to draw with: the recorded tint, else the layout's colour. */
    public int effectiveTint() {
        int recorded = tint();
        return recorded != 0 ? recorded : style.accentColor();
    }

    /**
     * 26.4.2: ARGB colour of the document's dyed cover plate, or {@code -1} for
     * "not tinted at all".
     *
     * <p>The rule, in one place because the item colour handler, the item
     * renderer and the viewer must agree:</p>
     * <ul>
     *   <li>a signed {@link #tint()} wins;</li>
     *   <li>{@code tint == 0} means <b>no colour recorded</b> and yields
     *       {@code -1} — never opaque black ({@code 0xFF000000}, which painted the
     *       plate black) and never the base layout's accent (which kept a custom
     *       template looking like the layout it was based on);</li>
     *   <li>a cover that really owns a colour — a custom cover
     *       ({@link #customStyle()}) or the 26.4 locked template colour
     *       ({@link #hasStyleColor()}) — is dyed with {@link #accentColor()}.</li>
     * </ul>
     *
     * <p>{@code DocumentItem.tintColor} hands this value to {@code ItemColors},
     * where {@code -1} means "leave the texture alone".</p>
     */
    public int coverTint() {
        int rgb = tint();
        if (rgb == 0) {
            if (!customStyle() && !hasStyleColor()) {
                return -1;
            }
            rgb = accentColor();
        }
        rgb &= 0x00FFFFFF;
        // A recorded colour of literally 0x000000 is still "no colour": painting a
        // black plate is exactly the artefact this method must never produce.
        return rgb == 0 ? -1 : 0xFF000000 | rgb;
    }

    /** Preset emblem id of the issuing template ({@link #DEFAULT_EMBLEM} fallback). */
    public String emblem() {
        String value = tag.getString(TEMPLATE_EMBLEM).trim();
        return value.isEmpty() ? DEFAULT_EMBLEM : value;
    }

    /** Hash of the template's custom cover base map, {@code ""} when there is none. */
    public String templateTex() {
        return tag.getString(TEMPLATE_TEXTURE).trim();
    }

    /** Whether the document carries a custom cover base map reference. */
    public boolean hasTemplateTex() {
        return !templateTex().isBlank();
    }

    /**
     * Writes the whole template snapshot the document keeps for itself. Used by
     * the issuing machine, which resolves the template server-side; the values
     * travel in one signed write so a half-applied template cannot be observed.
     *
     * <p>26.4.2: the same write also turns the style into
     * {@code template:<templateId>}, which is what tells every viewer that this
     * document uses a custom layout instead of the base layout's cover.</p>
     *
     * @param tint template accent colour, {@code 0} = keep the layout colour
     */
    public void applyRegistryTemplate(String templateId, int tint, String emblem, String templateTex) {
        String id = templateId == null ? "" : templateId.trim();
        if (id.isBlank()) {
            tag.remove(TEMPLATE_ID);
            tag.remove(TEMPLATE_BASE_STYLE);
        } else {
            tag.putString(TEMPLATE_ID, id);
            // The layout this document is built on, recorded so the style identity
            // survives the template being deleted from the registry.
            tag.putString(TEMPLATE_BASE_STYLE, style.id());
        }
        this.templateStyleId = id;
        int color = tint & 0x00FFFFFF;
        if (color == 0) {
            tag.remove(TEMPLATE_TINT);
        } else {
            tag.putInt(TEMPLATE_TINT, color);
        }
        String emblemId = emblem == null ? "" : emblem.trim();
        if (emblemId.isBlank() || emblemId.equals(DEFAULT_EMBLEM)) {
            // The default emblem is what every reader falls back to; storing it
            // would only add a redundant signed key.
            tag.remove(TEMPLATE_EMBLEM);
        } else {
            tag.putString(TEMPLATE_EMBLEM, emblemId);
        }
        String texture = templateTex == null ? "" : templateTex.trim();
        if (texture.isBlank()) {
            tag.remove(TEMPLATE_TEXTURE);
        } else {
            tag.putString(TEMPLATE_TEXTURE, texture);
        }
        sign();
    }

    /** Sets (or clears, when blank) the issuing template id. Signing follows. */
    public void setTemplateId(String value) {
        String id = value == null ? "" : value.trim();
        if (id.isBlank()) {
            tag.remove(TEMPLATE_ID);
            tag.remove(TEMPLATE_BASE_STYLE);
        } else {
            tag.putString(TEMPLATE_ID, id);
            tag.putString(TEMPLATE_BASE_STYLE, style.id());
        }
        // The style identity follows the template id, so "templateId" and
        // "style = template:<id>" can never disagree.
        this.templateStyleId = id;
        sign();
    }

    /** Sets (or clears, when 0) the template accent tint. Signing follows. */
    public void setTint(int tint) {
        int color = tint & 0x00FFFFFF;
        if (color == 0) {
            tag.remove(TEMPLATE_TINT);
        } else {
            tag.putInt(TEMPLATE_TINT, color);
        }
        sign();
    }

    /** Sets (or clears, when blank/default) the preset emblem. Signing follows. */
    public void setEmblem(String emblem) {
        String id = emblem == null ? "" : emblem.trim();
        if (id.isBlank() || id.equals(DEFAULT_EMBLEM)) {
            tag.remove(TEMPLATE_EMBLEM);
        } else {
            tag.putString(TEMPLATE_EMBLEM, id);
        }
        sign();
    }

    /** Sets (or clears, when blank) the custom cover base-map hash. Signing follows. */
    public void setTemplateTex(String hash) {
        String value = hash == null ? "" : hash.trim();
        if (value.isBlank()) {
            tag.remove(TEMPLATE_TEXTURE);
        } else {
            tag.putString(TEMPLATE_TEXTURE, value);
        }
        sign();
    }

    /**
     * Drops every registry-template key (a document re-issued as a plain layout).
     * The style identity is dropped too, so the document renders as the official
     * layout it names.
     */
    public void clearRegistryTemplate() {
        tag.remove(TEMPLATE_ID);
        tag.remove(TEMPLATE_BASE_STYLE);
        tag.remove(TEMPLATE_TINT);
        tag.remove(TEMPLATE_EMBLEM);
        tag.remove(TEMPLATE_TEXTURE);
        this.templateStyleId = "";
        sign();
    }

    // ----------------------------------------------------------- 26.4.1 portrait

    /**
     * The signed portrait reference: {@code p00}..{@code p23}, {@code skin} or
     * {@code custom:<hash>}.
     *
     * <p>A document issued before 26.4.1 (no key at all), a hand-edited value and
     * a reference to a deleted upload all read as {@link #DEFAULT_PORTRAIT}, so an
     * old passport never renders as an empty photo box.</p>
     */
    public String portrait() {
        return PortraitLibrary.normalizeId(tag.getString(PORTRAIT));
    }

    /**
     * Signed UUID of the portrait owner: whose skin {@code skin} draws, and who
     * uploaded a {@code custom:<hash>} image. Empty when the document carries
     * none, which {@code skin} resolves to the first preset.
     */
    public String portraitOwner() {
        return PortraitLibrary.normalizeOwner(tag.getString(PORTRAIT_OWNER));
    }

    /** Whether the document carries a non-default portrait reference. */
    public boolean hasPortrait() {
        return !DEFAULT_PORTRAIT.equals(portrait());
    }

    /**
     * Writes the portrait reference and its owner as one signed edit, so a reader
     * can never observe a {@code custom:<hash>} reference with a stale owner (or
     * the other way round).
     *
     * <p>A blank or malformed id is stored as {@link #DEFAULT_PORTRAIT}: the field
     * always reads back as one of the three supported shapes. A blank or malformed
     * owner removes the key, which is exactly "nobody to read a skin from".</p>
     *
     * @param id        {@code p00}..{@code p23} / {@code skin} / {@code custom:<hash>}
     * @param ownerUuid UUID of the holder or uploader, may be blank
     */
    public void setPortrait(String id, String ownerUuid) {
        tag.putString(PORTRAIT, PortraitLibrary.normalizeId(id));
        String owner = PortraitLibrary.normalizeOwner(ownerUuid);
        if (owner.isEmpty()) {
            tag.remove(PORTRAIT_OWNER);
        } else {
            tag.putString(PORTRAIT_OWNER, owner);
        }
        sign();
    }

    public int itemModelData() {
        return customStyle() ? 100 : style.modelData();
    }

    public void applyCustomStyle(String name, String shortName, String type, int color) {
        tag.putBoolean("customStyle", true);
        tag.putString("customName", name == null ? "" : name.trim());
        tag.putString("customShortName", shortName == null ? "" : shortName.trim());
        tag.putString("customType", type == null ? "" : type.trim());
        tag.putInt("customColor", color & 0x00FFFFFF);
        sign();
    }

    public void clearCustomStyle() {
        tag.remove("customStyle");
        tag.remove("customName");
        tag.remove("customShortName");
        tag.remove("customType");
        tag.remove("customColor");
        // 26.4: the template accent colour is part of the cover styling too.
        tag.remove("styleColor");
        sign();
    }

    public String kindId() {
        return kind().name();
    }

    public String get(String key, String fallback) {
        String value = tag.getString(key);
        return value.isBlank() ? fallback : value;
    }

    public String name() {
        return tag.getString("name");
    }

    public String number() {
        return tag.getString("number");
    }

    /**
     * 26.3 registry: the issuing country/region code (three letters A-Z). Written
     * from the registry on issue for every document kind (ID cards and permits
     * store it too, even though only passports print it in an MRZ).
     */
    public String issuerCountry() {
        return MrzUtil.countryCode(tag.getString(ISSUER_COUNTRY));
    }

    /** Writes (and signs) the single issuing-country field; blank removes it. */
    public void setIssuerCountry(String value) {
        String code = MrzUtil.countryCode(value);
        if (code.isEmpty()) {
            tag.remove(ISSUER_COUNTRY);
        } else {
            tag.putString(ISSUER_COUNTRY, code);
        }
        sign();
    }

    public String sex() {
        return tag.getString("sex");
    }

    public String birthDate() {
        return tag.getString("birthDate");
    }

    public String address() {
        return tag.getString("address");
    }

    public String authority() {
        return tag.getString("authority");
    }

    public String issueDate() {
        return tag.getString("issueDate");
    }

    public String expiryDate() {
        return tag.getString("expiryDate");
    }

    public String nationality() {
        return tag.getString("nationality");
    }

    public String placeOfIssue() {
        return tag.getString("placeOfIssue");
    }

    public String nameLatin() {
        String latin = tag.getString("nameLatin").trim();
        if (!latin.isBlank()) {
            return latin;
        }
        String name = name();
        int slash = name.indexOf('/');
        if (slash >= 0) {
            String after = name.substring(slash + 1).trim();
            if (!after.isBlank()) {
                return after;
            }
        }
        if (name.matches(".*[A-Za-z].*")) {
            return name;
        }
        return "HOLDER";
    }

    public DocumentStatus status() {
        return DocumentStatus.byId(tag.getString("status"));
    }

    /**
     * 26.3 holder signature key: {@code normalize(name)|birthDate}. Two documents
     * of one person (a passport and its renewal, a passport and that holder's
     * permit) share the key, which is what lets one border entry follow the
     * traveller across booklets.
     *
     * <p>{@link #sign()} writes the key whenever a document is issued or edited,
     * so an officer correcting the name or the birth date refreshes it too. A
     * legacy document without the stored key derives it from the same fields.</p>
     */
    public String personKey() {
        String stored = tag.getString(PERSON_KEY).trim();
        if (!stored.isEmpty()) {
            return stored;
        }
        // No holder data means there is no person to key on: return "" so callers
        // (BorderService, BorderEntry) fall back to the document number
        // ("doc:<number>") instead of inventing a key from the default birth date.
        if (normalizeHolderName(name()).isEmpty()) {
            return "";
        }
        return computePersonKey(name(), birthDate());
    }

    /** Stateless form of {@link #personKey()} (also used for border entries). */
    public static String computePersonKey(String name, String birthDate) {
        String normalized = normalizeHolderName(name);
        String birth = birthDate == null ? "" : birthDate.trim();
        if (normalized.isEmpty() && birth.isEmpty()) {
            return "";
        }
        return normalized + "|" + birth;
    }

    /** Lower-cased, whitespace-collapsed holder name used inside {@link #personKey()}. */
    public static String normalizeHolderName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * Whether the style resolves to a real layout. A {@code template:<id>} style
     * is known through its {@link #baseStyleId() base} layout, so a
     * template-issued document is never reported as malformed (and a template that
     * was deleted afterwards does not turn the booklet invalid either).
     */
    public boolean styleKnown() {
        return DocStyle.byIdOrNull(baseStyleId()) != null;
    }

    public boolean statusKnown() {
        String raw = tag.getString("status");
        for (DocumentStatus candidate : DocumentStatus.values()) {
            if (candidate.id().equalsIgnoreCase(raw)) {
                return true;
            }
        }
        return false;
    }

    public void setStyle(DocStyle newStyle) {
        if (newStyle.kind() != kind()) {
            throw new IllegalArgumentException("Style kind mismatch");
        }
        this.style = newStyle;
        // An explicit layout choice replaces the template identity: the document is
        // no longer "issued from a custom layout", it names that official layout.
        this.templateStyleId = "";
        tag.putString("style", newStyle.id());
        tag.putString("kind", newStyle.kind().name());
        sign();
    }

    public void setName(String value) {
        tag.putString("name", value == null ? "" : value);
        sign();
    }

    public void setNameLatin(String value) {
        tag.putString("nameLatin", value == null ? "" : value);
        sign();
    }

    public void setNumber(String value) {
        tag.putString("number", value == null ? "" : value);
        sign();
    }

    public void setSex(String value) {
        String sex = value == null ? "M" : value.toUpperCase(Locale.ROOT);
        if (!sex.equals("M") && !sex.equals("F") && !sex.equals("X")) {
            sex = "M";
        }
        tag.putString("sex", sex);
        sign();
    }

    public void setBirthDate(String value) {
        tag.putString("birthDate", value == null ? "" : value);
        sign();
    }

    public void setAddress(String value) {
        tag.putString("address", value == null ? "" : value);
        sign();
    }

    public void setAuthority(String value) {
        tag.putString("authority", value == null ? "" : value);
        sign();
    }

    public void setIssueDate(String value) {
        tag.putString("issueDate", value == null ? "" : value);
        sign();
    }

    public void setExpiryDate(String value) {
        tag.putString("expiryDate", value == null ? "" : value);
        sign();
    }

    public void setNationality(String value) {
        tag.putString("nationality", value == null ? "" : value);
        sign();
    }

    public void setPlaceOfIssue(String value) {
        tag.putString("placeOfIssue", value == null ? "" : value);
        sign();
    }

    public void setStatus(DocumentStatus value) {
        tag.putString("status", value.id());
        sign();
    }

    public void setString(String key, String value) {
        tag.putString(key, value == null ? "" : value);
        sign();
    }

    public List<VisaRecord> visas() {
        ListTag list = tag.getList("visas", Tag.TAG_COMPOUND);
        List<VisaRecord> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            result.add(VisaRecord.fromTag(list.getCompound(i)));
        }
        return result;
    }

    /** The explicit display label of the visa at {@code index}, blank when absent. */
    public String visaLabel(int index) {
        List<VisaRecord> visas = visas();
        return index >= 0 && index < visas.size() ? visas.get(index).label() : "";
    }

    /**
     * Display label shared by screens: the explicit visa label when present,
     * otherwise the country, otherwise the localized visa type. A blank or
     * unknown record renders as an empty string.
     */
    public static String visaDisplayLabel(VisaRecord visa) {
        if (visa == null) {
            return "";
        }
        if (!visa.label().isBlank()) {
            return visa.label();
        }
        if (!visa.country().isBlank()) {
            return visa.country();
        }
        return Component.translatable(visa.type().translationKey()).getString();
    }

    public List<EndorsementRecord> endorsements() {
        ListTag list = tag.getList("endorsements", Tag.TAG_COMPOUND);
        List<EndorsementRecord> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            result.add(EndorsementRecord.fromTag(list.getCompound(i)));
        }
        return result;
    }

    public List<StampRecord> stamps() {
        ListTag list = tag.getList("stamps", Tag.TAG_COMPOUND);
        List<StampRecord> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            result.add(StampRecord.fromTag(list.getCompound(i)));
        }
        return result;
    }

    public List<CompoundTag> visaTags() {
        ListTag list = tag.getList("visas", Tag.TAG_COMPOUND);
        List<CompoundTag> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag copy = list.getCompound(i).copy();
            // Editing helpers see the effective code, so a pre-1.1.5 record
            // shows (and keeps) its deterministic legacy code.
            copy.putString("code", effectiveCode(copy));
            result.add(copy);
        }
        return result;
    }

    public List<CompoundTag> endorsementTags() {
        ListTag list = tag.getList("endorsements", Tag.TAG_COMPOUND);
        List<CompoundTag> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag copy = list.getCompound(i).copy();
            copy.putString("code", effectiveCode(copy));
            result.add(copy);
        }
        return result;
    }

    private static String effectiveCode(CompoundTag record) {
        String code = record.getString("code");
        if (CredentialCode.isValid(code)) {
            return code;
        }
        String id = record.getString("id");
        return id.isBlank() ? CredentialCode.random() : CredentialCode.legacy(id);
    }

    public List<CompoundTag> stampTags() {
        ListTag list = tag.getList("stamps", Tag.TAG_COMPOUND);
        List<CompoundTag> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            result.add(list.getCompound(i).copy());
        }
        return result;
    }

    /**
     * Every permanent code carried by the document (26.3). Codes written by 26.2
     * and earlier live in the plain {@code visaFreeCodes} string list; they are
     * merged here as <em>universal</em> visa-free codes ({@code issuerCountry}
     * blank), so an old document keeps opening exactly the gates it used to.
     *
     * <p>A document holds at most {@link #MAX_PERMANENT_CODES} codes.</p>
     */
    public List<PermanentCode> permanentCodes() {
        List<PermanentCode> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        ListTag list = tag.getList(PERMANENT_CODES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            PermanentCode record = PermanentCode.fromTag(list.getCompound(i));
            if (record != null && seen.add(record.code())) {
                result.add(record);
            }
        }
        ListTag legacy = tag.getList(LEGACY_VISA_FREE_CODES, Tag.TAG_STRING);
        for (int i = 0; i < legacy.size(); i++) {
            String code = CredentialCode.sanitize(legacy.getString(i));
            if (CredentialCode.isValid(code) && seen.add(code)) {
                result.add(PermanentCode.universal(code));
            }
        }
        return result;
    }

    /**
     * 26.2 view of the permanent codes: just the eight-digit codes, in order.
     * Screens and legacy call sites keep using this; the storage underneath is
     * {@link #permanentCodes()}.
     */
    public List<String> visaFreeCodes() {
        List<String> codes = new ArrayList<>();
        for (PermanentCode record : permanentCodes()) {
            codes.add(record.code());
        }
        return codes;
    }

    /** The permanent code with this eight-digit code, or {@code null}. */
    @Nullable
    public PermanentCode permanentCode(String code) {
        String normalized = CredentialCode.sanitize(code);
        if (normalized.isBlank()) {
            return null;
        }
        for (PermanentCode record : permanentCodes()) {
            if (record.code().equals(normalized)) {
                return record;
            }
        }
        return null;
    }

    /** Adds a universal visa-free code (26.2 compatibility). */
    public boolean addVisaFreeCode(String code) {
        return addPermanentCode(code, "", PermanentPurpose.VISA_FREE, "");
    }

    /**
     * Adds one permanent code. Invalid codes, codes already used by another
     * visa/endorsement/permanent record, and a full list are rejected.
     *
     * @param issuerCountry country the code belongs to; blank keeps the legacy
     *                      "matches every gate" behaviour
     * @param purpose       return-home or visa-free semantics
     * @param expiry        optional {@code YYYY-MM-DD}; an unusable value is
     *                      stored as "no expiry"
     */
    public boolean addPermanentCode(String code, String issuerCountry, PermanentPurpose purpose, String expiry) {
        String normalized = CredentialCode.sanitize(code);
        if (normalized.isBlank()) {
            return false;
        }
        // A permanent code shares the same namespace as visa/endorsement codes.
        if (allCredentialCodes().contains(normalized)) {
            return false;
        }
        List<PermanentCode> codes = permanentCodes();
        if (codes.size() >= MAX_PERMANENT_CODES) {
            return false;
        }
        String country = issuerCountry == null ? "" : issuerCountry.trim();
        String validExpiry = DateUtil.isValid(expiry) ? expiry.trim() : "";
        codes.add(new PermanentCode(normalized, country, purpose, validExpiry));
        writePermanentCodes(codes);
        sign();
        return true;
    }

    /** Removes every permanent code with this code. Returns whether anything was removed. */
    public boolean removePermanentCode(String code) {
        String normalized = CredentialCode.sanitize(code);
        if (normalized.isBlank()) {
            return false;
        }
        List<PermanentCode> codes = permanentCodes();
        if (!codes.removeIf(record -> record.code().equals(normalized))) {
            return false;
        }
        writePermanentCodes(codes);
        sign();
        return true;
    }

    /** Removes the permanent code at {@code index}. */
    public boolean removePermanentCode(int index) {
        List<PermanentCode> codes = permanentCodes();
        if (index < 0 || index >= codes.size()) {
            return false;
        }
        codes.remove(index);
        writePermanentCodes(codes);
        sign();
        return true;
    }

    public boolean removeVisaFreeCode(String code) {
        return removePermanentCode(code);
    }

    public void clearPermanentCodes() {
        writePermanentCodes(List.of());
        sign();
    }

    public void clearVisaFreeCodes() {
        clearPermanentCodes();
    }

    /**
     * Writes the canonical permanent-code list and folds the legacy string list
     * into it. Leaving {@code visaFreeCodes} behind would resurrect a removed
     * code on the next read, so it is dropped here.
     */
    private void writePermanentCodes(List<PermanentCode> codes) {
        ListTag list = new ListTag();
        Set<String> seen = new LinkedHashSet<>();
        for (PermanentCode record : codes) {
            if (record == null || !CredentialCode.isValid(record.code()) || !seen.add(record.code())) {
                continue;
            }
            if (list.size() >= MAX_PERMANENT_CODES) {
                break;
            }
            list.add(record.toTag());
        }
        tag.put(PERMANENT_CODES, list);
        tag.remove(LEGACY_VISA_FREE_CODES);
    }

    /**
     * Every code the document can present at a gate: all visa and endorsement
     * codes (including inactive records, whose codes stay reserved) plus the
     * visa-free list. Used to keep auto-generated codes unique and by tests.
     */
    public List<String> allCredentialCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (VisaRecord visa : visas()) {
            codes.add(visa.code());
        }
        for (EndorsementRecord endorsement : endorsements()) {
            codes.add(endorsement.code());
        }
        codes.addAll(visaFreeCodes());
        return new ArrayList<>(codes);
    }

    /**
     * Repairs malformed/duplicate credential codes in place and re-signs. Used
     * by the admin modifier save path, which accepts a whole NBT tree and could
     * otherwise persist two records sharing a code.
     */
    public boolean ensureUniqueCredentialCodes() {
        Set<String> used = new LinkedHashSet<>(allCredentialCodes());
        Set<String> seen = new LinkedHashSet<>();
        boolean changed = false;
        ListTag visas = tag.getList("visas", Tag.TAG_COMPOUND);
        for (int i = 0; i < visas.size(); i++) {
            CompoundTag entry = visas.getCompound(i);
            String code = CredentialCode.sanitize(entry.getString("code"));
            if (!CredentialCode.isValid(code) || !seen.add(code)) {
                code = CredentialCode.generateUnique(used);
                entry.putString("code", code);
                used.add(code);
                seen.add(code);
                changed = true;
            }
        }
        ListTag endorsements = tag.getList("endorsements", Tag.TAG_COMPOUND);
        for (int i = 0; i < endorsements.size(); i++) {
            CompoundTag entry = endorsements.getCompound(i);
            String code = CredentialCode.sanitize(entry.getString("code"));
            if (!CredentialCode.isValid(code) || !seen.add(code)) {
                code = CredentialCode.generateUnique(used);
                entry.putString("code", code);
                used.add(code);
                seen.add(code);
                changed = true;
            }
        }
        if (changed) {
            sign();
        }
        return changed;
    }

    public void addVisa(CompoundTag visa) {
        addVisa(visa, false);
    }

    /** Adds a visa preserving an explicit code even if another record shares it. */
    public void addVisaExact(CompoundTag visa) {
        addVisa(visa, true);
    }

    private void addVisa(CompoundTag visa, boolean allowDuplicateCode) {
        CompoundTag copy = visa.copy();
        if (copy.getString("id").isBlank()) {
            copy.putString("id", "V-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        }
        String code = CredentialCode.sanitize(copy.getString("code"));
        if (!CredentialCode.isValid(code) || (!allowDuplicateCode && allCredentialCodes().contains(code))) {
            copy.putString("code", CredentialCode.generateUnique(allCredentialCodes()));
        }
        ListTag list = tag.getList("visas", Tag.TAG_COMPOUND);
        list.add(copy);
        tag.put("visas", list);
        sign();
    }

    public void updateVisa(int index, CompoundTag visa) {
        ListTag list = tag.getList("visas", Tag.TAG_COMPOUND);
        if (index >= 0 && index < list.size()) {
            CompoundTag copy = visa.copy();
            String oldCode = list.getCompound(index).getString("code");
            String code = CredentialCode.sanitize(copy.getString("code"));
            boolean duplicate = allCredentialCodes().stream()
                    .filter(existing -> !existing.equals(oldCode))
                    .anyMatch(existing -> existing.equals(code));
            if (!CredentialCode.isValid(code) || duplicate) {
                copy.putString("code", CredentialCode.generateUnique(allCredentialCodes()));
            }
            list.set(index, copy);
            tag.put("visas", list);
            sign();
        }
    }

    public void removeVisa(int index) {
        ListTag list = tag.getList("visas", Tag.TAG_COMPOUND);
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            tag.put("visas", list);
            sign();
        }
    }

    public void clearVisas() {
        tag.put("visas", new ListTag());
        sign();
    }

    public void addEndorsement(CompoundTag endorsement) {
        addEndorsement(endorsement, false);
    }

    /** Adds an endorsement preserving an explicit code even if shared. */
    public void addEndorsementExact(CompoundTag endorsement) {
        addEndorsement(endorsement, true);
    }

    private void addEndorsement(CompoundTag endorsement, boolean allowDuplicateCode) {
        CompoundTag copy = endorsement.copy();
        if (copy.getString("id").isBlank()) {
            copy.putString("id", "E-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        }
        String code = CredentialCode.sanitize(copy.getString("code"));
        if (!CredentialCode.isValid(code) || (!allowDuplicateCode && allCredentialCodes().contains(code))) {
            copy.putString("code", CredentialCode.generateUnique(allCredentialCodes()));
        }
        ListTag list = tag.getList("endorsements", Tag.TAG_COMPOUND);
        list.add(copy);
        tag.put("endorsements", list);
        sign();
    }

    public void updateEndorsement(int index, CompoundTag endorsement) {
        ListTag list = tag.getList("endorsements", Tag.TAG_COMPOUND);
        if (index >= 0 && index < list.size()) {
            CompoundTag copy = endorsement.copy();
            String oldCode = list.getCompound(index).getString("code");
            String code = CredentialCode.sanitize(copy.getString("code"));
            boolean duplicate = allCredentialCodes().stream()
                    .filter(existing -> !existing.equals(oldCode))
                    .anyMatch(existing -> existing.equals(code));
            if (!CredentialCode.isValid(code) || duplicate) {
                copy.putString("code", CredentialCode.generateUnique(allCredentialCodes()));
            }
            list.set(index, copy);
            tag.put("endorsements", list);
            sign();
        }
    }

    public void removeEndorsement(int index) {
        ListTag list = tag.getList("endorsements", Tag.TAG_COMPOUND);
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            tag.put("endorsements", list);
            sign();
        }
    }

    public void clearEndorsements() {
        tag.put("endorsements", new ListTag());
        sign();
    }

    /**
     * Writes one immigration stamp stamped with the server clock: the local date,
     * the {@code HH:mm:ss} time and the matching epoch milliseconds.
     */
    public void addStamp(String direction, String port, String country, String officer) {
        addStamp(direction, port, country, officer, Instant.now());
    }

    /** Clock-injectable variant of {@link #addStamp(String, String, String, String)} (tests). */
    public void addStamp(String direction, String port, String country, String officer, Instant at) {
        Instant moment = at == null ? Instant.now() : at;
        LocalDateTime local = LocalDateTime.ofInstant(moment, ZoneId.systemDefault());
        CompoundTag stamp = new CompoundTag();
        stamp.putString("id", "S-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        stamp.putString("direction", direction);
        stamp.putString("port", port);
        stamp.putString("country", country);
        stamp.putString("date", local.toLocalDate().format(DateUtil.ISO));
        stamp.putString("time", local.format(STAMP_TIME));
        stamp.putLong("epochMillis", moment.toEpochMilli());
        stamp.putString("officer", officer);
        addStampTag(stamp);
    }

    public void addStampTag(CompoundTag stamp) {
        CompoundTag copy = stamp.copy();
        if (copy.getString("id").isBlank()) {
            copy.putString("id", "S-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        }
        ListTag list = tag.getList("stamps", Tag.TAG_COMPOUND);
        list.add(copy);
        tag.put("stamps", list);
        sign();
    }

    public void removeStamp(int index) {
        ListTag list = tag.getList("stamps", Tag.TAG_COMPOUND);
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            tag.put("stamps", list);
            sign();
        }
    }

    public void clearStamps() {
        tag.put("stamps", new ListTag());
        sign();
    }

    public boolean isPermanent() {
        return DateUtil.isPermanent(expiryDate());
    }

    public boolean isExpired() {
        return DateUtil.expired(expiryDate());
    }

    public boolean isExpired(LocalDate date) {
        return DateUtil.expiredOn(expiryDate(), date);
    }

    public long daysRemaining() {
        return DateUtil.daysRemaining(expiryDate());
    }

    public boolean integrityValid() {
        return DocumentIntegrity.verify(tag);
    }

    public void sign() {
        tag.putInt("schema", SCHEMA);
        tag.putString("kind", kind().name());
        // 26.4.2: the style keeps its template identity through every re-sign, so
        // editing a stamped/renamed document never silently drops it back to the
        // base layout cover.
        tag.putString("style", signedStyle());
        // 26.3 holder signature key, rewritten on every issue/edit so a corrected
        // name or birth date never leaves a stale key behind. A document without
        // a holder name carries no key at all: BorderService then keys it by
        // document number ("doc:<number>") instead of inventing "|birthDate".
        if (normalizeHolderName(name()).isEmpty()) {
            tag.remove(PERSON_KEY);
        } else {
            tag.putString(PERSON_KEY, computePersonKey(name(), birthDate()));
        }
        persistMissingCredentialCodes();
        if (kind() == DocumentKind.PASSPORT) {
            // 26.3: the MRZ reads the single issuing-country field, falling back
            // to a legacy mrzCountry key or the layout's official code.
            String[] mrz = MrzUtil.generate(style, number(), nameLatin(), birthDate(), sex(), expiryDate(),
                    mrzCountry());
            tag.putString("mrz1", mrz[0]);
            tag.putString("mrz2", mrz[1]);
        }
        DocumentIntegrity.sign(tag);
    }

    /**
     * The {@code style} value this document signs: {@code template:<id>} while the
     * document is bound to a registry template, the official layout id otherwise.
     */
    private String signedStyle() {
        return templateStyleId.isBlank() ? style.id() : TEMPLATE_STYLE_PREFIX + templateStyleId;
    }

    /**
     * Writes the deterministic legacy code onto records that predate 1.1.5 so it
     * is persisted the next time the document is saved or edited. Records without
     * an id (malformed NBT) get a random code rather than an unstable legacy one.
     */
    private void persistMissingCredentialCodes() {
        ListTag visas = tag.getList("visas", Tag.TAG_COMPOUND);
        for (int i = 0; i < visas.size(); i++) {
            CompoundTag entry = visas.getCompound(i);
            if (!CredentialCode.isValid(entry.getString("code"))) {
                String id = entry.getString("id");
                entry.putString("code", id.isBlank() ? CredentialCode.random() : CredentialCode.legacy(id));
            }
        }
        ListTag endorsements = tag.getList("endorsements", Tag.TAG_COMPOUND);
        for (int i = 0; i < endorsements.size(); i++) {
            CompoundTag entry = endorsements.getCompound(i);
            if (!CredentialCode.isValid(entry.getString("code"))) {
                String id = entry.getString("id");
                entry.putString("code", id.isBlank() ? CredentialCode.random() : CredentialCode.legacy(id));
            }
        }
    }

    public String mrz1() {
        return tag.getString("mrz1");
    }

    public String mrz2() {
        return tag.getString("mrz2");
    }

    /**
     * 26.3: the issuing-country code, read from the single {@code issuerCountry}
     * field. Legacy documents fall back to the old {@code mrzCountry} key and
     * finally to the layout's official code.
     */
    public String mrzCountry() {
        String value = issuerCountry();
        if (!value.isEmpty()) {
            return value;
        }
        String legacy = MrzUtil.countryCode(tag.getString(LEGACY_MRZ_COUNTRY));
        return legacy.isEmpty() ? style.mrzCountry() : legacy;
    }

    /**
     * Sets the issuing-country code. Kept for the 26.2 call sites: it writes the
     * single {@code issuerCountry} field, so MRZ and registry stay in sync.
     */
    public void setMrzCountry(String value) {
        setIssuerCountry(value);
    }

    public String sexKey() {
        return switch (sex()) {
            case "F" -> "sex.veriborder.female";
            case "X" -> "sex.veriborder.x";
            default -> "sex.veriborder.male";
        };
    }

    public boolean isRecordUsable() {
        return integrityValid() && status() == DocumentStatus.VALID && !isExpired();
    }

    public String displayNameOrStyle() {
        String name = name();
        return name.isBlank() ? style.id() : name;
    }
}
