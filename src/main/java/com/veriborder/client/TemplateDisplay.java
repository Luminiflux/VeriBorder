package com.veriborder.client;

import com.veriborder.client.ui.IconPicker;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentData;
import com.veriborder.registry.CredentialRegistryData;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 26.4.1 single adapter between the registry-template definition and every
 * client surface that prints a template (registry editor list, issuing machine
 * layout selector, viewer, item tooltip, border terminal card).
 *
 * <p><b>Why one class:</b> the template definition fields ({@code titleZh},
 * {@code titleEn}, {@code tint}, {@code emblem}, {@code texture}) and the
 * document snapshot fields ({@code templateId}, {@code tint}, {@code emblem},
 * {@code templateTex}) are owned by the registry data layer. Every client file
 * names them <em>only</em> here, so a rename or a fallback change is one edit
 * instead of a hunt through six screens (and no screen has to know whether an
 * older snapshot carried the field yet).</p>
 *
 * <p>Display fallbacks, in order:</p>
 * <ul>
 *   <li><b>template title</b>: {@code titleZh}/{@code titleEn} of the current
 *       language, then the other language, then the template {@code name};</li>
 *   <li><b>document title</b>: the template named by {@code templateId}, then the
 *       document's own {@code displayName}, then the official layout name
 *       ({@link DocumentData#displayName()} already implements the last two);</li>
 *   <li><b>tint</b>: the stored tint; a tint of 0 keeps following the base
 *       layout, so the document accent colour is used then;</li>
 *   <li><b>emblem</b>: the stored emblem id, then the legacy {@code customIcon}
 *       of the document, then no glyph at all.</li>
 * </ul>
 *
 * <p>Pure display code: no rendering, no packets, no writes.</p>
 */
public final class TemplateDisplay {
    // ------------------------------------------------ template editor form keys
    /**
     * Payload keys of the template definition (26.4.1). The document side owns
     * its own keys ({@code DocumentData.TEMPLATE_*}) and is read through the
     * {@code DocumentData} accessors, so nothing here duplicates that storage.
     */
    public static final String FORM_TITLE_ZH = "titleZh";
    public static final String FORM_TITLE_EN = "titleEn";
    public static final String FORM_TINT = "tint";
    public static final String FORM_EMBLEM = "emblem";
    public static final String FORM_TEXTURE = "texture";
    /** Dropdown value of "no uploaded base map" (draw the built-in one). */
    public static final String NO_TEXTURE = "";

    /**
     * Neutral cover tint. Used when a custom layout has no colour of its own (a
     * template saved before colours existed) and as the default the registry editor
     * offers for a new template: a light warm grey that reads as "uncoloured card"
     * and never as the base layout's colour.
     */
    public static final int NEUTRAL_TINT = 0xE6E8EA;

    private TemplateDisplay() {
    }

    // --------------------------------------------------------------- language

    /**
     * Whether the client should read the Chinese column of a bilingual label.
     * Screens pass this into the pure helpers, which keeps this class free of
     * {@code net.minecraft.client.*} so the common item code can reference it.
     */
    public static boolean chinese() {
        return Ui.chinese();
    }

    // --------------------------------------------------------------- template

    /** The registered template behind {@code id}, or {@code null}. */
    @Nullable
    public static CredentialRegistryData.Template template(@Nullable String id) {
        return ClientRegistryCache.template(id);
    }

    /** Localised template title: own title, then the other language, then the name. */
    public static String templateTitle(@Nullable CredentialRegistryData.Template template, boolean chinese) {
        if (template == null) {
            return "";
        }
        String title = template.title(chinese);
        return title == null || title.isBlank() ? template.name() : title;
    }

    /** Template title in the current client language ("" for a missing template). */
    public static String templateTitle(@Nullable CredentialRegistryData.Template template) {
        return templateTitle(template, chinese());
    }

    /** Cover tint of a template; 0 keeps the base layout colour. */
    public static int templateTint(@Nullable CredentialRegistryData.Template template) {
        return template == null ? 0 : template.effectiveTint() & 0x00FFFFFF;
    }

    /** Emblem id of a template, normalised onto the preset grid. */
    public static String templateEmblem(@Nullable CredentialRegistryData.Template template) {
        return template == null ? "" : IconPicker.normalize(template.emblem());
    }

    /** Base-map hash of a template ("" = built-in cover pattern). */
    public static String templateTexture(@Nullable CredentialRegistryData.Template template) {
        return template == null ? "" : template.texture();
    }

    // --------------------------------------------------------------- document

    /** Registry template id stored on the document ("" when it has none). */
    public static String documentTemplateId(@Nullable DocumentData doc) {
        return doc == null ? "" : doc.templateId();
    }

    /** The registered template the document was issued from, or {@code null}. */
    @Nullable
    public static CredentialRegistryData.Template documentTemplate(@Nullable DocumentData doc) {
        return template(documentTemplateId(doc));
    }

    /**
     * Document name: the issuing template's title, then the document's own
     * {@code displayName}, then the official layout name.
     */
    public static String documentTitle(@Nullable DocumentData doc, boolean chinese) {
        if (doc == null) {
            return "";
        }
        String templateTitle = templateTitle(documentTemplate(doc), chinese);
        if (!templateTitle.isBlank()) {
            return templateTitle;
        }
        return doc.displayName();
    }

    /** {@link #documentTitle(DocumentData, boolean)} in the current language. */
    public static String documentTitle(@Nullable DocumentData doc) {
        return documentTitle(doc, chinese());
    }

    /** {@link #documentTitle(DocumentData)} as a literal component. */
    public static Component documentTitleComponent(@Nullable DocumentData doc) {
        return Component.literal(documentTitle(doc));
    }

    /**
     * Cover tint of a document: the value copied from its template, or the
     * document's own accent colour when the document came from a plain layout.
     */
    public static int documentTint(@Nullable DocumentData doc) {
        return coverTint(doc);
    }

    /**
     * <b>The one colour a document is drawn with.</b> Every reader — the viewer
     * cover, the issuing-machine preview card, the border-terminal card and the
     * item icon through {@code DocumentAppearance} — resolves its colour here, so
     * the four surfaces can never disagree about a credential.
     *
     * <p>Resolution order, matching {@link DocumentData#coverTint()}:</p>
     <ol>
     *   <li>the colour the document recorded itself (template tint, custom cover
     *       accent, locked layout colour) — {@code coverTint()} answers that,</li>
     *   <li>for a template-styled document without a recorded colour, the
     *       <em>neutral</em> template tint. A custom layout must never fall back to
     *       the base layout's colour: that is exactly the "Japanese passport
     *       template is still red" bug,</li>
     *   <li>for an official layout, the layout's own accent colour — a plain
     *       document keeps the look it always had.</li>
     </ol>
     *
     * @return {@code 0xRRGGBB} (never negative: callers OR in their own alpha)
     */
    public static int coverTint(@Nullable DocumentData doc) {
        if (doc == null) {
            return NEUTRAL_TINT;
        }
        int recorded = doc.coverTint();
        if (recorded != -1) {
            return recorded & 0x00FFFFFF;
        }
        // 26.2.1: a document that names a template must never fall back to the base
        // layout's colour. The issuing machine's live preview builds its DocumentData
        // from the form instead of from a signed stack, so it frequently has the
        // template id but no recorded tint; asking the registry is what keeps the
        // preview, the viewer and the item icon the same colour.
        String templateId = doc.templateId();
        if (!templateId.isBlank()) {
            CredentialRegistryData.Template template = template(templateId);
            return template == null ? NEUTRAL_TINT : template.effectiveTint() & 0x00FFFFFF;
        }
        if (doc.isTemplateStyle()) {
            return NEUTRAL_TINT;
        }
        return doc.accentColor() & 0x00FFFFFF;
    }

    /**
     * {@link #coverTint(DocumentData)} as the {@code ItemColors} value of the
     * item-card layer ({@code 0xFFRRGGBB}).
     */
    public static int itemTint(@Nullable DocumentData doc) {
        return 0xFF000000 | coverTint(doc);
    }

    /**
     * Tint of a template definition, never 0: a template the registry still holds
     * answers with its own colour ({@link CredentialRegistryData.Template#effectiveTint()}),
     * and a colourless one with the neutral default, so the live band in the editor
     * matches what will be issued.
     */
    public static int effectiveTint(@Nullable CredentialRegistryData.Template template) {
        if (template == null) {
            return NEUTRAL_TINT;
        }
        int tint = template.effectiveTint() & 0x00FFFFFF;
        return tint == 0 ? NEUTRAL_TINT : tint;
    }

    /**
     * Tint of an edited template form: the picked colour, or the neutral default
     * when the field is still empty. This is what the editor's live colour band
     * draws, so the band can never render as black.
     */
    public static int effectiveTint(int formTint) {
        int tint = formTint & 0x00FFFFFF;
        return tint == 0 ? NEUTRAL_TINT : tint;
    }

    /**
     * Cover colour of a <em>snapshot</em> card — the border terminal receives plain
     * values, not a {@link DocumentData} — in the same order as
     * {@link #coverTint(DocumentData)}: the recorded tint, then the colour the
     * registry still holds for the named template, then the base layout's accent for
     * a plain document or the neutral template tint for a template document.
     */
    public static int snapshotTint(int tint, @Nullable String templateId, @Nullable String styleId) {
        int rgb = tint & 0x00FFFFFF;
        if (rgb != 0) {
            return rgb;
        }
        boolean templated = templateId != null && !templateId.isBlank();
        CredentialRegistryData.Template template = templated ? template(templateId) : null;
        int registered = template == null ? 0 : template.effectiveTint() & 0x00FFFFFF;
        if (registered != 0) {
            return registered;
        }
        if (templated) {
            return NEUTRAL_TINT;
        }
        DocStyle layout = DocStyle.byIdOrNull(styleId);
        return layout == null ? NEUTRAL_TINT : layout.accentColor() & 0x00FFFFFF;
    }

    /**
     * Whether the document renders as a custom layout (the signed
     * {@code template:<id>} style) rather than as one of the official layouts.
     */
    public static boolean isTemplateStyle(@Nullable DocumentData doc) {
        return doc != null && doc.isTemplateStyle();
    }

    /**
     * Name of the issuing template, "" when the document is not template-styled or
     * the registry no longer holds that template.
     */
    public static String templateStyleName(@Nullable DocumentData doc) {
        if (!isTemplateStyle(doc)) {
            return "";
        }
        String registered = templateTitle(documentTemplate(doc));
        return registered.isBlank() ? doc.templateStyleId() : registered;
    }

    /**
     * Human-readable name of the layout underneath a document: the official layout
     * name of {@link DocumentData#baseStyleId()}, or the raw id for a layout this
     * build does not know.
     */
    public static String baseLayoutName(@Nullable DocumentData doc) {
        if (doc == null) {
            return "";
        }
        DocStyle layout = DocStyle.byIdOrNull(doc.baseStyleId());
        if (layout == null) {
            String raw = doc.baseStyleId();
            return raw == null || raw.isBlank() ? "" : raw;
        }
        return Component.translatable(layout.displayNameKey()).getString();
    }

    /** Label of the "layout" row: "自定义版式 · <template>" for a template document. */
    public static String layoutRowValue(@Nullable DocumentData doc) {
        if (doc == null) {
            return "";
        }
        if (isTemplateStyle(doc)) {
            String name = templateStyleName(doc);
            String custom = Component.translatable("document.veriborder.custom_layout").getString();
            return name.isBlank() ? custom : custom + " \u00B7 " + name;
        }
        return doc.style().id();
    }

    /**
     * Small print under the layout row: the official layout a template document is
     * built on ("基底 hk_passport"), "" for a plain document.
     */
    public static String layoutRowNote(@Nullable DocumentData doc) {
        if (!isTemplateStyle(doc)) {
            return "";
        }
        String base = baseLayoutName(doc);
        if (base.isBlank()) {
            return "";
        }
        return Component.translatable("document.veriborder.custom_layout.base", base).getString();
    }

    /**
     * Emblem id of a document: the template emblem copied at issue time
     * ({@code emblem} is the default preset when the template had none), or the
     * legacy {@code customIcon} an older document stored. "" means "no glyph", so
     * a plain official document keeps the neutral cover emblem.
     */
    public static String documentEmblem(@Nullable DocumentData doc) {
        if (doc == null) {
            return "";
        }
        if (doc.hasRegistryTemplate()) {
            return IconPicker.normalize(doc.emblem());
        }
        String legacy = doc.customIcon();
        return legacy.isBlank() ? "" : IconPicker.normalize(legacy);
    }

    /** Base-map hash of a document ("" = built-in cover pattern). */
    public static String documentTexture(@Nullable DocumentData doc) {
        return doc == null ? "" : doc.templateTex();
    }
}
