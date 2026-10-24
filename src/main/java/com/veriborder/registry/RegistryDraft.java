package com.veriborder.registry;

import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 26.4 fix: the editable state of the credential registry ("注册器") screen.
 *
 * <p>The screen used to rebuild its widgets straight from the selected registry
 * row, so every {@code rebuildWidgets()} (a dropdown change, a palette pick, the
 * change-code step, a refresh snapshot) replaced the name/code/note inputs with
 * fresh, empty {@code EditBox}es and the typed value was gone. Saving then sent
 * a blank name and the server answered "the template needs a name".</p>
 *
 * <p>This class is the single source of truth for the form: the screen seeds
 * every widget from {@link #countryView()} / {@link #templateView()}, writes
 * each keystroke back through the setters, and builds the save payload from the
 * same fields. It also owns the tab policy: a snapshot-only refresh never changes
 * the page, and only a server editor payload ({@code editorTab} /
 * {@code highlightTemplate} / {@code echo}) may select one.</p>
 *
 * <p>Everything here is plain data (no client classes), so the regressions are
 * covered by unit tests without a running Minecraft client.</p>
 */
public final class RegistryDraft {
    public static final int TAB_COUNTRIES = 0;
    public static final int TAB_TEMPLATES = 1;
    public static final String TAB_ID_COUNTRIES = "countries";
    public static final String TAB_ID_TEMPLATES = "templates";
    public static final String ERROR_KEY = "error";
    public static final String EDITOR_TAB_KEY = "editorTab";
    public static final String ECHO_KEY = "echo";
    public static final String HIGHLIGHT_KEY = "highlightTemplate";

    private int tab = TAB_COUNTRIES;
    private String errorKey = "";

    // ---- country form (kept across every rebuild) ----
    private String countryCode = "";
    private String countryNameZh = "";
    private String countryNameEn = "";
    private String countryNationality = "";
    private String countryNote = "";
    private boolean countryEditing;
    private String countryId = "";

    // ---- template form (kept across every rebuild) ----
    private DocumentKind templateKind = DocumentKind.PASSPORT;
    private String templateStyle;
    private String templateName = "";
    private String templateMrzCountry = "";
    private int templateColor = 0xFFFFFF;
    private String templateIcon = CredentialRegistryData.DEFAULT_ICON;
    private boolean templateEditing;
    private String templateId = "";

    public RegistryDraft() {
        this.templateStyle = firstStyleId(templateKind);
    }

    /** Immutable snapshot the widgets are seeded from (one per rebuild). */
    public record CountryView(String code, String nameZh, String nameEn, String defaultNationality,
                              String note, boolean editing, String id) {
    }

    /** Immutable snapshot the template widgets are seeded from. */
    public record TemplateView(String name, DocumentKind kind, String styleId, String mrzCountry,
                               int color, String icon, boolean editing, String id) {
    }

    // ------------------------------------------------------------------- tab

    public int tab() {
        return tab;
    }

    public void setTab(int value) {
        tab = value == TAB_TEMPLATES ? TAB_TEMPLATES : TAB_COUNTRIES;
    }

    /**
     * Tab the editor must show for one server payload. A payload without
     * {@code editorTab}/{@code highlightTemplate}/{@code echo} is a plain snapshot
     * (the refresh path), which must keep the page the operator is on.
     */
    public static int tabFromPayload(@Nullable CompoundTag payload, int fallback) {
        if (payload == null) {
            return fallback;
        }
        String editorTab = payload.getString(EDITOR_TAB_KEY);
        if (TAB_ID_TEMPLATES.equals(editorTab)) {
            return TAB_TEMPLATES;
        }
        if (TAB_ID_COUNTRIES.equals(editorTab)) {
            return TAB_COUNTRIES;
        }
        if (payload.contains(HIGHLIGHT_KEY, Tag.TAG_COMPOUND)
                || payload.contains(ECHO_KEY, Tag.TAG_COMPOUND)) {
            return TAB_TEMPLATES;
        }
        return fallback;
    }

    // ----------------------------------------------------------------- error

    public String errorKey() {
        return errorKey;
    }

    public void setErrorKey(@Nullable String key) {
        errorKey = key == null ? "" : key;
    }

    public void clearError() {
        errorKey = "";
    }

    // --------------------------------------------------------------- payload

    /**
     * Applies one server editor payload: the tab, the concrete error reason and
     * either the saved template ({@code highlightTemplate}) or the echoed form of
     * a refused save ({@code echo}). A snapshot-only payload changes nothing but
     * the error text, so a refresh keeps the page and the typed input.
     */
    public void applyPayload(@Nullable CompoundTag payload) {
        if (payload == null) {
            return;
        }
        tab = tabFromPayload(payload, tab);
        if (payload.contains(ERROR_KEY, Tag.TAG_STRING)) {
            errorKey = payload.getString(ERROR_KEY);
        }
        if (payload.contains(ECHO_KEY, Tag.TAG_COMPOUND)) {
            restoreTemplate(payload.getCompound(ECHO_KEY));
            tab = TAB_TEMPLATES;
            return;
        }
        if (payload.contains(HIGHLIGHT_KEY, Tag.TAG_COMPOUND)) {
            CredentialRegistryData.Template saved =
                    CredentialRegistryData.Template.fromTag(payload.getCompound(HIGHLIGHT_KEY)).normalized();
            selectTemplate(saved);
            errorKey = "";
        }
    }

    // -------------------------------------------------------------- countries

    public CountryView countryView() {
        return new CountryView(countryCode, countryNameZh, countryNameEn, countryNationality,
                countryNote, countryEditing, countryId);
    }

    /** Seeds the country form from a selected row (editing that row in place). */
    public void selectCountry(@Nullable CredentialRegistryData.Country country) {
        if (country == null) {
            clearCountry();
            return;
        }
        countryCode = country.code();
        countryNameZh = country.nameZh();
        countryNameEn = country.nameEn();
        countryNationality = country.defaultNationality();
        countryNote = country.note();
        countryEditing = true;
        countryId = country.code();
    }

    /** Blanks the country form (a new registration). */
    public void clearCountry() {
        countryCode = "";
        countryNameZh = "";
        countryNameEn = "";
        countryNationality = "";
        countryNote = "";
        countryEditing = false;
        countryId = "";
    }

    public void setCountryCode(@Nullable String value) {
        countryCode = value == null ? "" : value;
    }

    public void setCountryNameZh(@Nullable String value) {
        countryNameZh = value == null ? "" : value;
    }

    public void setCountryNameEn(@Nullable String value) {
        countryNameEn = value == null ? "" : value;
    }

    public void setCountryNationality(@Nullable String value) {
        countryNationality = value == null ? "" : value;
    }

    public void setCountryNote(@Nullable String value) {
        countryNote = value == null ? "" : value;
    }

    /** Client-side validation key, or "" when the country can be saved. */
    public String countryErrorKey() {
        if (CredentialRegistryData.normalizeCode(countryCode).isBlank()
                || countryNameZh.trim().isBlank() || countryNameEn.trim().isBlank()) {
            return "screen.veriborder.registry.invalid";
        }
        return "";
    }

    /** {@code registry_save_country} payload, read from the fields (never a widget). */
    public CompoundTag countryPayload() {
        CompoundTag tag = new CompoundTag();
        tag.putString("code", CredentialRegistryData.normalizeCode(countryCode));
        tag.putString("nameZh", countryNameZh.trim());
        tag.putString("nameEn", countryNameEn.trim());
        tag.putString("defaultNationality", countryNationality.trim());
        tag.putString("note", countryNote.trim());
        if (countryEditing && !countryId.isBlank()) {
            tag.putString("id", countryId);
        }
        return tag;
    }

    // -------------------------------------------------------------- templates

    public TemplateView templateView() {
        return new TemplateView(templateName, templateKind, resolvedStyle(),
                templateMrzCountry, templateColor, templateIcon, templateEditing, templateId);
    }

    public DocumentKind templateKind() {
        return templateKind;
    }

    public String templateName() {
        return templateName;
    }

    public String templateStyle() {
        return resolvedStyle();
    }

    public int templateColor() {
        return templateColor;
    }

    public String templateIcon() {
        return templateIcon;
    }

    public boolean templateEditing() {
        return templateEditing;
    }

    public String templateId() {
        return templateId;
    }

    public void setTemplateName(@Nullable String value) {
        templateName = value == null ? "" : value;
    }

    public void setTemplateMrzCountry(@Nullable String value) {
        templateMrzCountry = CredentialRegistryData.normalizeCountryCode(value);
    }

    public void setTemplateColor(int value) {
        templateColor = value & 0x00FFFFFF;
    }

    public void setTemplateIcon(@Nullable String value) {
        templateIcon = value == null || value.isBlank()
                ? CredentialRegistryData.DEFAULT_ICON : value.trim();
    }

    /** Keeps the typed name: changing the credential kind starts a new draft. */
    public void setTemplateKind(@Nullable DocumentKind next) {
        if (next == null || next == templateKind) {
            return;
        }
        templateKind = next;
        templateStyle = firstStyleId(next);
        templateEditing = false;
        templateId = "";
    }

    /** Only a layout of the current kind may be selected. */
    public void setTemplateStyle(@Nullable String styleId) {
        if (styleId != null && styleMatchesKind(templateKind, styleId)) {
            templateStyle = styleId;
        }
    }

    /** Starts a blank draft of the current kind (no id is sent for it). */
    public void newTemplate() {
        templateName = "";
        templateStyle = firstStyleId(templateKind);
        templateMrzCountry = "";
        templateColor = 0xFFFFFF;
        templateIcon = CredentialRegistryData.DEFAULT_ICON;
        templateEditing = false;
        templateId = "";
    }

    /** Seeds the template form from a stored row (editing it in place). */
    public void selectTemplate(@Nullable CredentialRegistryData.Template template) {
        if (template == null) {
            newTemplate();
            return;
        }
        templateKind = template.kind();
        templateStyle = styleMatchesKind(template.kind(), template.style())
                ? template.style() : firstStyleId(template.kind());
        templateName = template.name();
        templateMrzCountry = template.mrzCountry();
        templateColor = template.color() & 0x00FFFFFF;
        templateIcon = template.icon().isBlank()
                ? CredentialRegistryData.DEFAULT_ICON : template.icon();
        templateEditing = !template.id().isBlank();
        templateId = template.id();
    }

    /** Client-side validation key, or "" when the template can be saved. */
    public String templateErrorKey() {
        if (templateName.trim().isBlank()) {
            return "message.veriborder.registry.template_name_required";
        }
        if (templateKind == null) {
            return "message.veriborder.registry.template_kind_invalid";
        }
        return "";
    }

    /**
     * {@code registry_save_template} payload, read from the fields. This is what
     * the "needs a name" bug got wrong: it used to read a widget that a rebuild
     * had already replaced with an empty one.
     */
    public CompoundTag templatePayload() {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", templateKind == null ? "" : templateKind.id());
        tag.putString("name", templateName.trim());
        tag.putString("style", resolvedStyle());
        tag.putString("mrzCountry", templateMrzCountry == null ? "" : templateMrzCountry);
        tag.putInt("color", templateColor);
        tag.putString("icon", templateIcon == null ? "" : templateIcon);
        if (templateEditing && templateId != null && !templateId.isBlank()) {
            tag.putString("id", templateId);
        }
        return tag;
    }

    // ---------------------------------------------------------------- helpers

    /** Restores a refused save from the server echo, id included. */
    private void restoreTemplate(@Nullable CompoundTag echo) {
        if (echo == null) {
            return;
        }
        DocumentKind kind = DocumentKind.byIdOrNull(echo.getString("kind"));
        if (kind != null) {
            templateKind = kind;
        }
        templateName = echo.getString("name");
        templateStyle = styleMatchesKind(templateKind, echo.getString("style"))
                ? echo.getString("style") : firstStyleId(templateKind);
        templateMrzCountry = CredentialRegistryData.normalizeCountryCode(echo.getString("mrzCountry"));
        templateColor = echo.getInt("color") & 0x00FFFFFF;
        setTemplateIcon(echo.getString("icon"));
        templateId = echo.getString("id");
        templateEditing = !templateId.isBlank();
    }

    private String resolvedStyle() {
        return styleMatchesKind(templateKind, templateStyle)
                ? templateStyle : firstStyleId(templateKind);
    }

    private static boolean styleMatchesKind(DocumentKind kind, @Nullable String styleId) {
        if (kind == null || styleId == null || styleId.isBlank()) {
            return false;
        }
        DocStyle style = DocStyle.byIdOrNull(styleId);
        return style != null && style.kind() == kind;
    }

    private static String firstStyleId(DocumentKind kind) {
        List<DocStyle> styles = DocStyle.forKind(kind == null ? DocumentKind.PASSPORT : kind);
        return styles.isEmpty() ? "" : styles.get(0).id();
    }
}
