package com.veriborder.registry;

import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentKind;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 26.4 fix: the "document layout" (证件版式) selector options of an issuing
 * machine = every official layout of the machine's credential kind <b>plus</b>
 * the registry templates of that same kind.
 *
 * <p>The registry screen ("注册器") saved templates that the issuer could only see
 * in the separate presets tab; the layout dropdown itself ignored them, so a
 * freshly saved template looked like it had gone nowhere. Each option is either
 * a layout id ({@code cn_passport}) or {@code template:<template id>}; picking a
 * template applies its layout and its colour/icon defaults to the form, which is
 * exactly what gets written onto the issued document.</p>
 *
 * <p>Pure data, so the merge is unit-tested without a client.</p>
 */
public final class IssuerChoices {
    /** Value prefix that distinguishes a registry template from a layout id. */
    public static final String TEMPLATE_PREFIX = "template:";

    /**
     * One selectable entry.
     *
     * @param value      what the dropdown stores ({@code style id} or {@code template:<id>})
     * @param label      what the operator reads
     * @param style      official layout the entry issues with
     * @param templateId registry template id, or {@code null} for a plain layout
     */
    public record Choice(String value, String label, DocStyle style, @Nullable String templateId) {
        public boolean isTemplate() {
            return templateId != null && !templateId.isBlank();
        }
    }

    private IssuerChoices() {
    }

    /**
     * Layout options for one issuer kind: official layouts first (registration
     * order), then the kind's registry templates in registry order. A template
     * whose layout cannot be resolved for the kind is skipped rather than offered
     * as a broken entry.
     */
    public static List<Choice> layoutChoices(DocumentKind kind,
                                             @Nullable List<CredentialRegistryData.Template> templates,
                                             @Nullable Function<DocStyle, String> styleLabel) {
        List<Choice> choices = new ArrayList<>();
        if (kind == null) {
            return choices;
        }
        for (DocStyle style : DocStyle.forKind(kind)) {
            choices.add(new Choice(style.id(), label(style, styleLabel), style, null));
        }
        if (templates == null) {
            return choices;
        }
        for (CredentialRegistryData.Template raw : templates) {
            if (raw == null) {
                continue;
            }
            CredentialRegistryData.Template template = raw.normalized();
            if (!template.valid() || template.kind() != kind) {
                continue;
            }
            DocStyle style = DocStyle.byIdOrNull(template.style());
            if (style == null || style.kind() != kind) {
                continue;
            }
            choices.add(new Choice(valueOf(template), template.name(), style, template.id()));
        }
        return choices;
    }

    /** Dropdown value of a registry template. */
    public static String valueOf(@Nullable CredentialRegistryData.Template template) {
        return template == null ? "" : TEMPLATE_PREFIX + template.id();
    }

    /** Template behind a dropdown value, or {@code null} for a plain layout id. */
    @Nullable
    public static CredentialRegistryData.Template resolve(@Nullable String value,
                                                          @Nullable List<CredentialRegistryData.Template> templates) {
        if (value == null || !value.startsWith(TEMPLATE_PREFIX) || templates == null) {
            return null;
        }
        String id = value.substring(TEMPLATE_PREFIX.length()).trim();
        for (CredentialRegistryData.Template template : templates) {
            if (template != null && template.id().equals(id)) {
                return template;
            }
        }
        return null;
    }

    /**
     * Layout id a dropdown value issues with: the template's base layout for a
     * template value, the value itself for a plain layout id.
     */
    public static String styleId(@Nullable String value,
                                 @Nullable List<CredentialRegistryData.Template> templates) {
        CredentialRegistryData.Template template = resolve(value, templates);
        if (template != null) {
            return template.style();
        }
        return value == null ? "" : value;
    }

    /**
     * 26.4 fix: the layout-lock dropdown value resolved into what the device
     * stores. {@code template:<id>} becomes the template's base official layout
     * plus the template id (so issuing can also carry the template's colour and
     * icon); a plain layout id stays as it is.
     *
     * @param styleId    official layout id to store as {@code lockedStyle}
     * @param templateId registry template behind the choice, "" for a plain layout
     * @param fellBack   the choice no longer resolves (template deleted / wrong
     *                   kind / unknown id) and the first official layout of the
     *                   machine's kind was substituted
     */
    public record LockedStyle(String styleId, String templateId, boolean fellBack) {
        public boolean isTemplate() {
            return !templateId.isBlank();
        }
    }

    /**
     * Resolves the layout-lock dropdown value for a document issuer of
     * {@code kind}. A template that has been deleted (or no longer belongs to the
     * kind) falls back to the first official layout of the kind, flagged as
     * {@link LockedStyle#fellBack()} so the screen/save path can tell the
     * administrator instead of silently storing a different layout. A blank value
     * means "nothing picked yet" and yields the first official layout silently.
     * Pure, so every rule is unit-testable.
     */
    public static LockedStyle resolveLockedStyle(@Nullable String choice,
                                                 @Nullable DocumentKind kind,
                                                 @Nullable List<CredentialRegistryData.Template> templates) {
        String value = choice == null ? "" : choice.trim();
        String fallback = kind == null ? "" : DocStyle.forKind(kind).stream()
                .findFirst().map(DocStyle::id).orElse("");
        CredentialRegistryData.Template template = resolve(value, templates);
        if (template != null) {
            DocStyle style = DocStyle.byIdOrNull(template.style());
            if (style != null && (kind == null || style.kind() == kind)) {
                return new LockedStyle(style.id(), template.id(), false);
            }
            return new LockedStyle(fallback, "", true);
        }
        DocStyle style = DocStyle.byIdOrNull(value);
        if (style != null && (kind == null || style.kind() == kind)) {
            return new LockedStyle(style.id(), "", false);
        }
        return new LockedStyle(fallback, "", !value.isEmpty());
    }

    private static String label(DocStyle style, @Nullable Function<DocStyle, String> styleLabel) {
        String label = styleLabel == null ? null : styleLabel.apply(style);
        return label == null || label.isBlank() ? style.id() : label;
    }
}
