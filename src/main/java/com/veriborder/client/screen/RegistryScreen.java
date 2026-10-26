package com.veriborder.client.screen;

import com.veriborder.client.BetterButton;
import com.veriborder.client.ClientRegistryCache;
import com.veriborder.client.TemplateDisplay;
import com.veriborder.client.TemplateTextureFiles;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.client.ui.ColorPalette;
import com.veriborder.client.ui.Dropdown;
import com.veriborder.client.ui.IconPicker;
import com.veriborder.client.ui.PagedList;
import com.veriborder.client.ui.TabStrip;
import com.veriborder.client.ui.TemplateTextures;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentKind;
import com.veriborder.network.DeviceActionPacket;
import com.veriborder.network.ModNetwork;
import com.veriborder.registry.CredentialRegistryData;
import com.veriborder.registry.RegistryDraft;
import com.veriborder.registry.RegistryService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 26.3 credential registry ("注册器"), VeriScreen style with two tabs:
 *
 * <ol>
 *   <li><b>Countries/Regions</b>: three-letter code (A-Z, unique, globally
 *       available), Chinese name, English name, default nationality and an
 *       internal note. Saved to the server {@link CredentialRegistryData} and
 *       synced to every client; issuer nationality/country selectors read this
 *       list.</li>
 *   <li><b>Templates</b>: credential kind (ID_CARD / PASSPORT / PERMIT), template
 *       name, base style, default country code (a dropdown over the registered
 *       codes), the credential definition (bilingual title, cover tint, emblem,
 *       custom base map) and the template list itself. A template is only shown
 *       by the issuing machine of its own kind.</li>
 * </ol>
 *
 * <p>26.4 fix (form state): every input is persisted in a {@link RegistryDraft}
 * field, {@code onChanged} writes each keystroke back, and {@code rebuildWidgets()}
 * re-seeds the widgets from those fields. Before this, a dropdown change / palette
 * pick / refresh rebuilt the name box as an empty {@code EditBox}, so a save sent
 * a blank name and the server answered "the template needs a name". Saving reads
 * the draft, never the widget.</p>
 *
 * <p>26.4 fix (page): a refresh snapshot updates the lists in place and never
 * resets the tab nor the typed form; only a server editor payload may select a
 * page ({@code editorTab}), the saved template ({@code highlightTemplate}) or the
 * echoed form of a refused save ({@code echo}). Save failures now carry the
 * server's concrete reason key, which the footer renders in this screen.</p>
 *
 * <p>26.4.1 (template definition): the template page is split into three paged
 * sub-views — <b>Basics</b> (kind / name / layout / default country),
 * <b>Appearance</b> (bilingual credential title, cover tint with a live colour
 * band, emblem picker with a live card) and <b>Base map</b> (custom cover PNG
 * from {@code config/veriborder/templates/} with a thumbnail, or the built-in
 * pattern). The three views share one preview strip and one template list, so no
 * field has to be squeezed into a single 470px page: the height budget is spent
 * on a page, not on every field at once.</p>
 *
 * <p>26.4.1 (deleting): every template row carries its own delete button. The
 * first click arms it ("confirm delete"), a second click within
 * {@value #DELETE_CONFIRM_MILLIS} ms sends the delete; the armed state expires by
 * itself, so a stray click can never remove a template. Deleting a template is
 * allowed while issued documents still refer to it: those documents keep their
 * own title/tint/emblem snapshot. Only an issuing machine that has the template
 * locked as its layout refuses the delete, and it says how many machines those
 * are.</p>
 *
 * <p>Reading is open to everyone; add/delete/rename requires OP level 2, which the
 * server reports as {@code admin} in the opening payload and re-checks on every
 * write.</p>
 */
public class RegistryScreen extends VeriScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger("VeriBorder/Registry");

    private static final int TAB_COUNTRIES = RegistryDraft.TAB_COUNTRIES;
    private static final int TAB_TEMPLATES = RegistryDraft.TAB_TEMPLATES;
    private static final int WIDTH = 560;
    private static final int HEIGHT = 470;
    private static final int STRIP_Y = 44;
    private static final int FIELD_W = 512;
    private static final int LEFT = 24;

    // ---- template page geometry (26.4.1: three paged sub-views) -------------
    private static final int PAGE_STRIP_Y = 68;
    private static final int PAGE_STRIP_H = 16;
    private static final int ROW1_Y = 100;
    private static final int ROW2_Y = 132;
    private static final int ROW3_Y = 160;
    /** Shared one-line credential preview under the form rows. */
    private static final int PREVIEW_Y = 188;
    private static final int PREVIEW_H = 26;
    private static final int LIST_TITLE_Y = 222;
    private static final int LIST_Y = 230;
    private static final int LIST_H = 150;
    private static final int NOTE_Y = 384;
    private static final int ERROR_Y = 396;
    private static final int FOOTER_Y = 418;
    /** A "confirm delete" click is valid for this long. */
    static final long DELETE_CONFIRM_MILLIS = 3_000L;
    /** Dropdown-value prefix of a not-yet-uploaded local base map. */
    private static final String LOCAL_FILE_PREFIX = "file:";
    /** Row delete button: width / height and right-hand inset. */
    private static final int TRASH_W = 50;
    private static final int TRASH_H = 16;
    private static final int TRASH_RIGHT_INSET = 6;

    private static final int PAGE_BASICS = 0;
    private static final int PAGE_LOOK = 1;
    private static final int PAGE_TEXTURE = 2;

    private final boolean admin;
    private List<CredentialRegistryData.Country> countries = new ArrayList<>();
    private List<CredentialRegistryData.Template> templates = new ArrayList<>();
    /** Custom cover base maps published by the registry (26.4.1). */
    private List<ClientRegistryCache.Texture> textures = new ArrayList<>();
    /** PNGs found in this client's {@code config/veriborder/templates/} folder. */
    private List<TemplateTextureFiles.LocalFile> localFiles = new ArrayList<>();
    /** Validation result of each local file, computed once per rebuild. */
    private final List<TemplateTextureFiles.Prepared> localPrepared = new ArrayList<>();
    /** Hash of an upload in flight; confirmed (or refuted) by the next snapshot. */
    private String pendingTextureHash = "";
    /** All form values and the current tab (26.4 fix: survives every rebuild). */
    private final RegistryDraft draft = new RegistryDraft();
    /** Sub-view of the template page (26.4.1: keeps each page inside the height). */
    private int templatePage = PAGE_BASICS;
    /** Credential definition fields the draft does not own (26.4.1). */
    private String titleZh = "";
    private String titleEn = "";
    private String textureHash = "";

    private TabStrip tabs;
    private TabStrip templatePages;
    private PagedList<CredentialRegistryData.Country> countryList;
    private PagedList<CredentialRegistryData.Template> templateList;
    private int selectedCountry = -1;
    private int selectedTemplate = -1;

    private EditBox codeField;
    private EditBox nameZhField;
    private EditBox nameEnField;
    private EditBox nationalityField;
    private EditBox noteField;
    /** “Change code” is an explicit two-step action: arm, type, confirm. */
    private boolean recodeArmed;
    private EditBox recodeField;

    private EditBox templateNameField;
    private Dropdown templateKindChoice;
    private Dropdown templateStyleChoice;
    private Dropdown templateCountryChoice;
    private ColorPalette templateColorPicker;
    private EditBox templateColorHex;
    private IconPicker templateIconPicker;
    private EditBox titleZhField;
    private EditBox titleEnField;
    private Dropdown templateTextureChoice;
    /**
     * Whether the "new template" colour has already been seeded with the neutral
     * default. Seeded once per fresh draft, never again: after that a deliberately
     * picked white must survive the widget rebuilds of a tab switch.
     */
    private boolean templateColourDefaulted;

    /** Template id whose row delete button is armed ("confirm delete"). */
    private String deleteArmedId = "";
    private long deleteArmedAt;
    /** Template the last delete request removed; used for the local confirmation. */
    private String pendingDeleteId = "";
    private String pendingDeleteName = "";
    /** Local result line (the delete confirmation), cleared by the next action. */
    private Component statusLine;

    private double mouseUiX = -1;
    private double mouseUiY = -1;
    /** Stable listener instance so close can unregister exactly this screen. */
    private final Runnable cacheListener = this::refreshFromCache;

    public RegistryScreen(CompoundTag payload) {
        super(Component.translatable("screen.veriborder.registry"), WIDTH, HEIGHT);
        this.admin = payload != null && payload.getBoolean("admin");
        this.accent = Ui.CYAN;
        read(payload);
    }

    private void read(CompoundTag payload) {
        CredentialRegistryData data = CredentialRegistryData.load(payload);
        this.countries = data.countries();
        this.templates = data.templates();
        this.textures = ClientRegistryCache.texturesOf(payload);
        // 26.4.1: only a refused *template save* echoes a form. A refused delete
        // echoes just the id it could not remove (for example a template an
        // issuing machine still locks); restoring that as a form would blank the
        // editor, so it is dropped and only the concrete reason is shown.
        CompoundTag editorPayload = payload;
        if (payload != null && payload.contains(RegistryDraft.ECHO_KEY, Tag.TAG_COMPOUND)
                && !isTemplateForm(payload.getCompound(RegistryDraft.ECHO_KEY))) {
            editorPayload = payload.copy();
            editorPayload.remove(RegistryDraft.ECHO_KEY);
        }
        // 26.4: the tab, the server's error reason, the saved template and the
        // echoed form of a refused save all come from the payload.
        draft.applyPayload(editorPayload);
        if (!draft.errorKey().isBlank()) {
            LOGGER.warn("registry editor received a server error: {}", draft.errorKey());
        }
        // 26.4.1: the credential definition fields live outside the draft, so the
        // refused form is restored here (title + base map) and the tint/emblem
        // colour/icon fall back to the values the draft restored.
        if (editorPayload != null && editorPayload.contains(RegistryDraft.ECHO_KEY, Tag.TAG_COMPOUND)) {
            CompoundTag echo = editorPayload.getCompound(RegistryDraft.ECHO_KEY);
            titleZh = echo.getString(TemplateDisplay.FORM_TITLE_ZH);
            titleEn = echo.getString(TemplateDisplay.FORM_TITLE_EN);
            textureHash = echo.getString(TemplateDisplay.FORM_TEXTURE);
            int tint = echo.getInt(TemplateDisplay.FORM_TINT);
            if (tint != 0) {
                draft.setTemplateColor(tint);
            }
            String emblem = echo.getString(TemplateDisplay.FORM_EMBLEM);
            if (!emblem.isBlank()) {
                draft.setTemplateIcon(emblem);
            }
        }
        if (payload != null && payload.contains(RegistryDraft.HIGHLIGHT_KEY, Tag.TAG_COMPOUND)) {
            CredentialRegistryData.Template saved = CredentialRegistryData.Template
                    .fromTag(payload.getCompound(RegistryDraft.HIGHLIGHT_KEY)).normalized();
            int index = indexOfTemplate(saved.id());
            if (index >= 0) {
                selectedTemplate = index;
            }
            seedDefinitionFields(saved);
            LOGGER.info("registry editor re-selected the saved template '{}' ({})", saved.name(), saved.id());
        } else if (draft.templateEditing() && !draft.templateId().isBlank()) {
            // A refused save echoes its form; keep the edited row highlighted.
            int index = indexOfTemplate(draft.templateId());
            if (index >= 0) {
                selectedTemplate = index;
            }
        }
    }

    /**
     * Whether an echoed compound is a template form (a refused save) rather than
     * the payload of a refused write of another kind.
     */
    private static boolean isTemplateForm(CompoundTag echo) {
        return echo != null && echo.contains("kind", Tag.TAG_STRING);
    }

    private int indexOfTemplate(String id) {
        if (id == null || id.isBlank()) {
            return -1;
        }
        for (int i = 0; i < templates.size(); i++) {
            if (templates.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfCountry(String code) {
        if (code == null || code.isBlank()) {
            return -1;
        }
        for (int i = 0; i < countries.size(); i++) {
            if (countries.get(i).code().equals(code)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void init() {
        super.init();
        ClientRegistryCache.setListener(cacheListener);
        // 26.3 round 12: opening the editor with an empty cache asks the server for
        // the snapshot instead of showing an empty registry.
        ClientRegistryCache.requestIfEmpty();
    }

    @Override
    public void onClose() {
        ClientRegistryCache.clearListener(cacheListener);
        super.onClose();
    }

    @Override
    protected void build() {
        tabs = TabStrip.at(left + LEFT, top + STRIP_Y, FIELD_W).size(20, 3);
        tabs.add("countries", Component.translatable("tab.veriborder.registry.countries"));
        tabs.add("templates", Component.translatable("tab.veriborder.registry.templates"));
        tabs.setSelected(draft.tab());
        templatePages = null;
        templateNameField = null;
        templateKindChoice = null;
        templateStyleChoice = null;
        templateCountryChoice = null;
        templateColorPicker = null;
        templateColorHex = null;
        templateIconPicker = null;
        titleZhField = null;
        titleEnField = null;
        templateTextureChoice = null;
        templateList = null;
        countryList = null;
        if (draft.tab() == TAB_COUNTRIES) {
            buildCountries();
        } else {
            buildTemplatePages();
            buildTemplates();
        }
        addRenderableWidget(new BetterButton(left + LEFT, top + FOOTER_Y, 110, 20,
                Component.translatable("button.veriborder.refresh"), button ->
                ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(BlockPos.ZERO,
                        RegistryService.ACTION_REFRESH, new CompoundTag(), -1)), Ui.CYAN, true));
        if (draft.tab() == TAB_TEMPLATES) {
            // 26.4.1: saving is not tied to one sub-view — the pages only split the
            // fields, the actions stay on the footer of every page.
            adminButton(left + LEFT + 116, top + FOOTER_Y, 110, 20,
                    Component.translatable("button.veriborder.registry_add_template"),
                    button -> saveTemplate(), Ui.GREEN);
            adminButton(left + LEFT + 232, top + FOOTER_Y, 96, 20,
                    Component.translatable("button.veriborder.registry_new_template"),
                    button -> newTemplate(), Ui.CYAN);
        }
        addRenderableWidget(new BetterButton(left + LEFT + 412, top + FOOTER_Y, 100, 20,
                Component.translatable("button.veriborder.close"), button -> onClose(), Ui.MUTED, true));
    }

    /** Adds an editing control that is only active for OP level 2. */
    private BetterButton adminButton(int x, int y, int width, int height, Component label,
                                     net.minecraft.client.gui.components.Button.OnPress press, int color) {
        BetterButton button = new BetterButton(x, y, width, height, label, press, color, true);
        button.active = admin;
        return addRenderableWidget(button);
    }

    // --------------------------------------------------------------- countries

    private void buildCountries() {
        int x = left + LEFT;
        int row1 = top + 92;
        int row2 = top + 124;
        // 26.4: widgets are seeded from the draft, so rebuildWidgets() (palette,
        // dropdown, change-code, refresh) never clears what the operator typed.
        RegistryDraft.CountryView view = draft.countryView();
        codeField = addField(x, row1, 64, "field.veriborder.registry.code",
                view.code(), "hint.veriborder.registry_code");
        codeField.setMaxLength(3);
        codeField.setFilter(value -> value == null || value.length() <= 3);
        codeField.setResponder(draft::setCountryCode);
        if (view.editing()) {
            // 26.3 round 12: the code of a registered country is the primary key,
            // so it is read-only here. “Change code” below is the explicit path.
            codeField.setEditable(false);
            codeField.setValue(view.code());
        }
        nameZhField = addField(x + 72, row1, 180, "field.veriborder.registry.name_zh", view.nameZh());
        nameZhField.setResponder(draft::setCountryNameZh);
        nameEnField = addField(x + 260, row1, 252, "field.veriborder.registry.name_en", view.nameEn());
        nameEnField.setResponder(draft::setCountryNameEn);
        nationalityField = addField(x, row2, 250, "field.veriborder.registry.default_nationality",
                view.defaultNationality());
        nationalityField.setResponder(draft::setCountryNationality);
        noteField = addField(x + 258, row2, 254, "field.veriborder.registry.note",
                view.note(), "hint.veriborder.internal_note");
        noteField.setMaxLength(CredentialRegistryData.MAX_NOTE_LENGTH);
        noteField.setResponder(draft::setCountryNote);

        adminButton(x, row2 + 32, 120, 20,
                Component.translatable("button.veriborder.registry_add_country"), button -> saveCountry(),
                Ui.GREEN);
        adminButton(x + 130, row2 + 32, 120, 20,
                Component.translatable("button.veriborder.delete"),
                button -> deleteCountry(), Ui.RED);
        // The rename action only makes sense for an existing row and only for OP.
        BetterButton recode = adminButton(x + 258, row2 + 32, 150, 20, recodeLabel(), button -> {
            String selectedCode = selectedCountry() == null ? "" : selectedCountry().code();
            if (!recodeArmed) {
                recodeArmed = true;
                rebuildWidgets();
                return;
            }
            String target = recodeField == null ? "" : recodeField.getValue();
            CompoundTag data = new CompoundTag();
            data.putString("code", selectedCode);
            data.putString("to", target);
            recodeArmed = false;
            send(RegistryService.ACTION_RECODE_COUNTRY, data);
        }, Ui.AMBER);
        if (selectedCountry() == null) {
            recode.active = false;
        }
        if (recodeArmed) {
            recodeField = addField(x + 258, row2 + 62, 100, "field.veriborder.registry.new_code", "",
                    "hint.veriborder.registry_code");
            recodeField.setMaxLength(3);
            recodeField.setFilter(value -> value == null || value.length() <= 3);
            addRenderableWidget(new BetterButton(x + 366, row2 + 58, 90, 18,
                    Component.translatable("button.veriborder.cancel"), button -> {
                        recodeArmed = false;
                        rebuildWidgets();
                    }, Ui.MUTED, true));
        }

        countryList = PagedList.<CredentialRegistryData.Country>at(x, top + 200, FIELD_W, 192)
                .rowHeight(26).rowGap(1).accent(accent)
                .emptyText(Component.translatable("screen.veriborder.registry.empty_countries"));
        countryList.setItems(countries);
        countryList.setSelectedIndex(selectedCountry);
    }

    private void deleteCountry() {
        CredentialRegistryData.Country country = selectedCountry();
        if (country == null) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putString("code", country.code());
        recodeArmed = false;
        send(RegistryService.ACTION_DELETE_COUNTRY, data);
    }

    private void saveCountry() {
        String errorKey = draft.countryErrorKey();
        if (!errorKey.isBlank()) {
            LOGGER.info("registry country save blocked on the client: {}", errorKey);
            draft.setErrorKey(errorKey);
            rebuildWidgets();
            return;
        }
        CompoundTag data = draft.countryPayload();
        LOGGER.info("registry country save payload: code='{}' id='{}'",
                data.getString("code"), data.getString("id"));
        send(RegistryService.ACTION_SAVE_COUNTRY, data);
    }

    // ------------------------------------------------------- template sub-tabs

    /** The three paged sub-views of the template editor (26.4.1). */
    private void buildTemplatePages() {
        templatePages = TabStrip.at(left + LEFT, top + PAGE_STRIP_Y, FIELD_W).size(PAGE_STRIP_H, 3);
        templatePages.add("basics", Component.translatable("tab.veriborder.registry.template_basics"));
        templatePages.add("look", Component.translatable("tab.veriborder.registry.template_look"));
        templatePages.add("texture", Component.translatable("tab.veriborder.registry.template_texture"));
        templatePages.setSelected(templatePage);
    }

    // --------------------------------------------------------------- templates

    private void buildTemplates() {
        switch (templatePage) {
            case PAGE_LOOK -> buildTemplateLook();
            case PAGE_TEXTURE -> buildTemplateTexture();
            default -> buildTemplateBasics();
        }
        templateList = PagedList.<CredentialRegistryData.Template>at(left + LEFT, top + LIST_Y, FIELD_W, LIST_H)
                .rowHeight(24).rowGap(1).accent(accent)
                .emptyText(Component.translatable("screen.veriborder.registry.empty_templates"));
        templateList.setItems(templates);
        templateList.setSelectedIndex(selectedTemplate);
    }

    /** Basics page: credential kind, template name, base layout, default country. */
    private void buildTemplateBasics() {
        int x = left + LEFT;
        RegistryDraft.TemplateView view = draft.templateView();

        // 26.4: credential type is a real dropdown (ID card / passport / permit)
        // instead of a cycle button; changing it starts a NEW template draft.
        List<Dropdown.Option> kindOptions = new ArrayList<>();
        for (DocumentKind kind : DocumentKind.values()) {
            kindOptions.add(new Dropdown.Option(kind.id(),
                    Component.translatable(kind.translationKey()).getString()));
        }
        templateKindChoice = addDropdown(Dropdown.at(x, top + ROW1_Y, 150)
                .height(20)
                .options(kindOptions)
                .selected(view.kind().id())
                // Editing metadata still requires OP level 2, like the old button.
                .enabled(admin));
        templateKindChoice.onChange(id -> {
            DocumentKind next = DocumentKind.byIdOrNull(id);
            if (next == null) {
                return;
            }
            // The typed name is a draft field now, so a kind change keeps it.
            draft.setTemplateKind(next);
            draft.clearError();
            statusLine = null;
            selectedTemplate = -1;
            rebuildWidgets();
        });
        templateNameField = addField(x + 158, top + ROW1_Y + 4, 354,
                "field.veriborder.registry.template_name", view.name());
        // 26.4: every keystroke lands in the draft, so a later rebuild re-seeds
        // this box with the typed name instead of an empty value.
        templateNameField.setResponder(draft::setTemplateName);

        // Base layout selector: every official layout of the current kind.
        List<Dropdown.Option> styleOptions = new ArrayList<>();
        for (DocStyle style : DocStyle.forKind(view.kind())) {
            styleOptions.add(new Dropdown.Option(style.id(),
                    Component.translatable(style.shortNameKey()).getString()));
        }
        templateStyleChoice = addDropdown(Dropdown.at(x, top + ROW2_Y, 240)
                .height(20)
                .prefix(Component.translatable("field.veriborder.registry.base_style_short").getString())
                .options(styleOptions)
                .selected(view.styleId())
                .enabled(admin));
        templateStyleChoice.onChange(draft::setTemplateStyle);

        // Default country code (a dropdown over the registered codes).
        List<Dropdown.Option> options = new ArrayList<>();
        for (CredentialRegistryData.Country country : countries) {
            options.add(new Dropdown.Option(country.code(),
                    country.code() + "  " + country.displayName(false)));
        }
        templateCountryChoice = addDropdown(Dropdown.at(x + 248, top + ROW2_Y, 264)
                .labelKey("field.veriborder.registry.mrz_country")
                .panelBottom(top + 388)
                .placeholder(Ui.labelText("screen.veriborder.registry.empty_countries",
                        "No countries/regions registered yet."))
                .options(options)
                .selected(view.mrzCountry())
                .onChange(draft::setTemplateMrzCountry));
    }

    /** Appearance page: bilingual title, tint with a live band, emblem with a card. */
    private void buildTemplateLook() {        int x = left + LEFT;
        titleZhField = addField(x, top + ROW1_Y + 4, 250, "field.veriborder.registry.title_zh", titleZh,
                "hint.veriborder.blank_template_name");
        titleZhField.setResponder(value -> titleZh = value == null ? "" : value);
        titleEnField = addField(x + 258, top + ROW1_Y + 4, 254, "field.veriborder.registry.title_en", titleEn,
                "hint.veriborder.blank_template_name");
        titleEnField.setResponder(value -> titleEn = value == null ? "" : value);

        // 26.3 round 12 shared palette picker; the band next to it is the live
        // colour strip of the credential (26.4.1).
        // 26.5: a brand-new template starts on the NEUTRAL cover colour instead of
        // an empty field, so a template can never be saved without a colour and the
        // palette never opens on black.
        seedTemplateColour();
        int paletteTop = top + ROW2_Y;
        templateColorPicker = ColorPalette.at(x, paletteTop, 150, draft.templateColor());
        templateColorHex = new EditBox(this.font, templateColorPicker.hexFieldX(),
                templateColorPicker.hexFieldY(), templateColorPicker.hexFieldWidth(), 12, Component.empty());
        templateColorHex.setBordered(false);
        templateColorHex.setTextColor(0xFFE9F3FF);
        templateColorHex.setMaxLength(7);
        templateColorHex.setValue(ColorPalette.hexValue(draft.templateColor()));
        // A typed #RRGGBB is a draft value too, so a rebuild keeps it.
        templateColorHex.setResponder(value -> draft.setTemplateColor(
                ColorPalette.parse(value, draft.templateColor())));
        templateColorHex.setVisible(false);
        addRenderableWidget(templateColorHex);
        templateColorPicker.hex(templateColorHex);

        templateIconPicker = IconPicker.at(x, top + ROW3_Y, 150, draft.templateIcon());
    }

    /** Base-map page: the uploaded cover PNG (or the built-in pattern). */
    private void buildTemplateTexture() {
        int x = left + LEFT;
        List<Dropdown.Option> options = new ArrayList<>();
        options.add(new Dropdown.Option(TemplateDisplay.NO_TEXTURE,
                Component.translatable("option.veriborder.registry.texture_none").getString()));
        // Read the local folder first: a hash already in the registry can then be
        // labelled with the file name the administrator recognises.
        localFiles = TemplateTextureFiles.list();
        localPrepared.clear();
        for (TemplateTextureFiles.LocalFile file : localFiles) {
            localPrepared.add(TemplateTextureFiles.prepare(file));
        }
        // Already uploaded base maps (from the registry snapshot).
        for (ClientRegistryCache.Texture texture : textures) {
            String id = texture.hash().isBlank() ? texture.name() : texture.hash();
            String label = localNameOf(id);
            if (label.isBlank()) {
                label = texture.name().isBlank() || texture.name().equals(texture.hash())
                        ? shortHash(id) : texture.name();
            }
            options.add(new Dropdown.Option(id, label));
        }
        // Base maps found on this client that are not in the registry yet: picking
        // one uploads it (registry_upload_texture) and then selects it.
        for (int i = 0; i < localFiles.size(); i++) {
            TemplateTextureFiles.LocalFile file = localFiles.get(i);
            TemplateTextureFiles.Prepared prepared = localPrepared.get(i);
            if (!prepared.ok() || ClientRegistryCache.texture(prepared.hash()) != null) {
                // Already in the registry, or a file the server would refuse: it is
                // simply not offered (the refusal reason appears when the same
                // hash is referenced by a template).
                continue;
            }
            options.add(new Dropdown.Option(LOCAL_FILE_PREFIX + file.name(),
                    Component.translatable("option.veriborder.registry.texture_upload", file.name())
                            .getString()));
        }
        templateTextureChoice = addDropdown(Dropdown.at(x, top + ROW1_Y, 300)
                .height(20)
                .labelKey("field.veriborder.registry.texture")
                .panelBottom(top + 320)
                .placeholder(Ui.labelText("screen.veriborder.registry.texture_none_available",
                        "No base maps in config/veriborder/templates/"))
                .options(options)
                .selected(textureHash)
                .enabled(admin));
        templateTextureChoice.onChange(this::selectTexture);
    }

    /** Applies a base-map dropdown value: built-in, an uploaded hash, or an upload. */
    private void selectTexture(String id) {
        String value = id == null ? "" : id.trim();
        statusLine = null;
        draft.clearError();
        if (value.startsWith(LOCAL_FILE_PREFIX)) {
            uploadLocalTexture(value.substring(LOCAL_FILE_PREFIX.length()));
            return;
        }
        textureHash = value;
        rebuildWidgets();
    }

    /**
     * Uploads one PNG from {@code config/veriborder/templates/} (26.4.1). The
     * file is validated here with the server's own limits first, so an oversized
     * or non-PNG file shows its concrete reason immediately instead of after a
     * round trip.
     */
    private void uploadLocalTexture(String fileName) {
        TemplateTextureFiles.Prepared prepared = null;
        for (int i = 0; i < localFiles.size(); i++) {
            if (localFiles.get(i).name().equals(fileName) && i < localPrepared.size()) {
                prepared = localPrepared.get(i);
                break;
            }
        }
        if (prepared == null) {
            draft.setErrorKey("message.veriborder.registry.texture_empty");
            rebuildWidgets();
            return;
        }
        if (!prepared.ok()) {
            LOGGER.info("registry base map refused on the client: file='{}' reason={}",
                    fileName, prepared.errorKey());
            draft.setErrorKey(prepared.errorKey());
            rebuildWidgets();
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putString("hash", prepared.hash());
        data.putString("png", prepared.base64());
        pendingTextureHash = prepared.hash();
        textureHash = prepared.hash();
        statusLine = Component.translatable("screen.veriborder.registry.texture_uploading", fileName);
        LOGGER.info("registry base map upload: file='{}' hash='{}' bytes={}",
                fileName, prepared.hash(), prepared.base64().length());
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(BlockPos.ZERO,
                RegistryService.ACTION_UPLOAD_TEXTURE, data, -1));
        rebuildWidgets();
    }

    /** 26.4: starts a blank template draft (no id is sent for it). */
    private void newTemplate() {
        draft.newTemplate();
        draft.clearError();
        selectedTemplate = -1;
        titleZh = "";
        titleEn = "";
        textureHash = "";
        statusLine = null;
        // 26.5: the next build seeds the neutral cover colour for this draft.
        templateColourDefaulted = false;
        armDelete(null);
        rebuildWidgets();
    }

    private void saveTemplate() {
        // The palette/picker are the live controls for colour and icon; commit
        // them into the draft before the payload is built.
        if (templateColorPicker != null) {
            draft.setTemplateColor(templateColorPicker.commit());
        }
        if (templateIconPicker != null) {
            draft.setTemplateIcon(templateIconPicker.selected());
        }
        // 26.5: never save a template without a colour. An empty field is filled
        // with the neutral default here (the server applies the same rule), so a
        // saved template can always dye the card and never falls back to the base
        // layout's colour.
        if ((draft.templateColor() & 0x00FFFFFF) == 0) {
            draft.setTemplateColor(TemplateDisplay.NEUTRAL_TINT);
            if (templateColorHex != null) {
                templateColorHex.setValue(ColorPalette.hexValue(TemplateDisplay.NEUTRAL_TINT));
            }
        }
        String errorKey = draft.templateErrorKey();
        if (!errorKey.isBlank()) {
            LOGGER.info("registry template save blocked on the client: {}", errorKey);
            draft.setErrorKey(errorKey);
            rebuildWidgets();
            return;
        }
        // 26.4: the payload is built from the draft fields, never from the widget
        // that a rebuild may have replaced. 26.4.1 adds the credential definition.
        CompoundTag data = draft.templatePayload();
        data.putString(TemplateDisplay.FORM_TITLE_ZH, titleZh.trim());
        data.putString(TemplateDisplay.FORM_TITLE_EN, titleEn.trim());
        data.putInt(TemplateDisplay.FORM_TINT, draft.templateColor() & 0x00FFFFFF);
        data.putString(TemplateDisplay.FORM_EMBLEM, draft.templateIcon());
        data.putString(TemplateDisplay.FORM_TEXTURE, textureHash == null ? "" : textureHash.trim());
        statusLine = null;
        LOGGER.info("registry template save payload: kind='{}' name='{}' style='{}' titleZh='{}' "
                        + "titleEn='{}' tint=#{:06X} emblem='{}' texture='{}' id='{}' (editing={})",
                data.getString("kind"), data.getString("name"), data.getString("style"),
                data.getString(TemplateDisplay.FORM_TITLE_ZH), data.getString(TemplateDisplay.FORM_TITLE_EN),
                data.getInt(TemplateDisplay.FORM_TINT), data.getString(TemplateDisplay.FORM_EMBLEM),
                data.getString(TemplateDisplay.FORM_TEXTURE), data.getString("id"), draft.templateEditing());
        send(RegistryService.ACTION_SAVE_TEMPLATE, data);
    }

    /**
     * Row delete (26.4.1): the first click arms the row ("confirm delete"), the
     * second within {@link #DELETE_CONFIRM_MILLIS} sends the request. Deleting is
     * not blocked by documents that were already issued from the template — they
     * keep their own title/tint/emblem snapshot.
     */
    private void armDelete(CredentialRegistryData.Template template) {
        long now = System.currentTimeMillis();
        String id = template == null ? "" : template.id();
        if (template == null || id.isBlank()) {
            deleteArmedId = "";
            deleteArmedAt = 0;
            return;
        }
        if (deleteArmedId.equals(id) && now - deleteArmedAt <= DELETE_CONFIRM_MILLIS) {
            deleteArmedId = "";
            deleteArmedAt = 0;
            pendingDeleteId = id;
            pendingDeleteName = template.name();
            statusLine = null;
            CompoundTag data = new CompoundTag();
            data.putString("id", id);
            LOGGER.info("registry template delete requested: id='{}' name='{}'", id, template.name());
            send(RegistryService.ACTION_DELETE_TEMPLATE, data);
            rebuildWidgets();
            return;
        }
        deleteArmedId = id;
        deleteArmedAt = now;
        statusLine = null;
    }

    /** Whether the row of this template currently shows "confirm delete". */
    private boolean isDeleteArmed(CredentialRegistryData.Template template) {
        return template != null && !deleteArmedId.isBlank() && deleteArmedId.equals(template.id())
                && System.currentTimeMillis() - deleteArmedAt <= DELETE_CONFIRM_MILLIS;
    }

    private void send(String action, CompoundTag data) {
        draft.clearError();
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(BlockPos.ZERO, action, data, -1));
    }

    // ----------------------------------------------------------------- render

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.mouseUiX = toUiX(mouseX);
        this.mouseUiY = toUiY(mouseY);
        // The RGB input only exists (and accepts clicks) while the palette is open.
        if (templateColorHex != null) {
            templateColorHex.setVisible(templateColorPicker != null && templateColorPicker.open());
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        // Open pickers are painted above every widget and the list; the RGB input
        // is re-rendered on top of the palette panel so it stays usable.
        overlay(graphics, g -> {
            if (draft.tab() != TAB_TEMPLATES) {
                return;
            }
            if (templateColorPicker != null && templateColorPicker.open()) {
                templateColorPicker.draw(g, font, accent);
                if (templateColorHex != null) {
                    templateColorHex.render(g, (int) toUiX(mouseX), (int) toUiY(mouseY), partialTick);
                }
            }
            if (templateIconPicker != null && templateIconPicker.open()) {
                templateIconPicker.draw(g, font, accent);
            }
        });
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, Component.translatable("screen.veriborder.registry").getString(),
                Component.translatable("screen.veriborder.registry.subtitle").getString());
        tabs.draw(graphics, font, accent);
        int x = left + LEFT;
        if (draft.tab() == TAB_COUNTRIES) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.registry.countries_hint"),
                    x, top + 70, FIELD_W, Ui.DIM);
            if (countryList != null) {
                countryList.setItems(countries);
                countryList.draw(graphics, font, this::drawCountryRow, mouseUiX, mouseUiY);
            }
            if (recodeArmed) {
                Ui.clipped(graphics, Component.translatable("screen.veriborder.registry.recode_warning"),
                        x, top + 188, FIELD_W, Ui.AMBER);
            } else if (selectedCountry() != null) {
                Ui.clipped(graphics, Component.translatable("screen.veriborder.registry.code_locked"),
                        x, top + 188, FIELD_W, Ui.DIM);
            } else {
                Ui.clipped(graphics, Component.translatable("screen.veriborder.registry.countries_title",
                        countries.size()), x, top + 188, FIELD_W, Ui.MUTED);
            }
        } else {
            renderTemplatePage(graphics, x);
        }
        renderFooterLines(graphics, x);
    }

    private void renderTemplatePage(GuiGraphics graphics, int x) {
        templatePages.setSelected(templatePage);
        templatePages.draw(graphics, font, accent);
        switch (templatePage) {
            case PAGE_LOOK -> {
                // Live colour band beside the palette: the EFFECTIVE tint — the form
                // colour, or the neutral default while the field is still empty — so
                // the band shows what the issued cover will print (26.5).
                Ui.credentialBand(graphics, x + 158, top + ROW2_Y, 354, 18, currentTint(),
                        currentEmblem(), currentTitle(), null);
                // Live credential card: the same artwork the viewer and the issuing
                // machine use, so the operator sees the real cover, not a mock-up.
                Ui.documentCard(graphics, x + 158, top + ROW3_Y, 354, 54, previewDocument());
            }
            case PAGE_TEXTURE -> drawTexturePreview(graphics, x + 310, top + ROW1_Y - 4, 54);
            default -> {
            }
        }
        // Shared one-line preview under the form rows (all pages but Appearance,
        // which already carries the full card).
        if (templatePage != PAGE_LOOK) {
            Ui.credentialBand(graphics, x, top + PREVIEW_Y, FIELD_W, PREVIEW_H,
                    currentTint(), currentEmblem(), currentTitle(), credentialFooter());
        }
        if (templateColorPicker != null && !templateColorPicker.open()) {
            templateColorPicker.draw(graphics, font, accent);
        }
        if (templateIconPicker != null && !templateIconPicker.open()) {
            templateIconPicker.draw(graphics, font, accent);
        }
        if (templateList != null) {
            templateList.setItems(templates);
            templateList.draw(graphics, font, this::drawTemplateRow, mouseUiX, mouseUiY);
        }
        if (isDeleteArmedAny()) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.registry.delete_armed_hint"),
                    x, top + LIST_TITLE_Y, FIELD_W, Ui.AMBER);
        } else {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.registry.templates_title",
                    templates.size()), x, top + LIST_TITLE_Y, FIELD_W, Ui.MUTED);
        }
        if (templates.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("screen.veriborder.registry.empty_templates"),
                    x + FIELD_W / 2, top + LIST_Y + 52, Ui.MUTED);
        }
    }

    private void renderFooterLines(GuiGraphics graphics, int x) {
        if (!draft.errorKey().isBlank()) {
            // 26.4 fix: the server's concrete reason is shown here, not only in chat.
            Ui.clipped(graphics, Component.translatable(draft.errorKey()), x, top + ERROR_Y, 360, Ui.RED);
            return;
        }
        if (statusLine != null) {
            Ui.clipped(graphics, statusLine, x, top + ERROR_Y, 460, Ui.GREEN);
            return;
        }
        if (draft.tab() == TAB_TEMPLATES) {
            // One contextual hint line for the whole page: the per-page texts are
            // long, and the field chips already label every control.
            Ui.clipped(graphics, Component.translatable(switch (templatePage) {
                case PAGE_LOOK -> "screen.veriborder.registry.look_hint";
                case PAGE_TEXTURE -> "screen.veriborder.registry.texture_hint";
                default -> "screen.veriborder.registry.templates_hint";
            }), x, top + NOTE_Y, FIELD_W, Ui.DIM);
        }
        if (!admin) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.registry.op_hint"),
                    x, top + ERROR_Y, FIELD_W, Ui.MUTED);
        } else if (draft.tab() == TAB_TEMPLATES) {
            // The delete contract, stated where the delete buttons are: documents
            // never block a delete, an issuing machine's layout lock does.
            Ui.clipped(graphics, Component.translatable("screen.veriborder.registry.notes_line"),
                    x, top + ERROR_Y, FIELD_W, Ui.DIM);
        }
    }

    /** Base-map thumbnail of the current selection (26.4.1). */
    private void drawTexturePreview(GuiGraphics graphics, int x, int y, int size) {
        graphics.fill(x, y, x + size, y + size, 0xFF091119);
        graphics.renderOutline(x, y, size, size, Ui.BORDER);
        ClientRegistryCache.Texture texture = ClientRegistryCache.texture(textureHash);
        int[][] grid = texture == null ? null : TemplateTextures.thumbnail(texture.hash(), texture.data());
        if (grid != null) {
            TemplateTextures.draw(graphics, x + 3, y + 3, size - 6, grid);
            return;
        }
        graphics.drawCenteredString(font, Component.translatable("option.veriborder.registry.texture_none")
                .getString(), x + size / 2, y + size / 2 - 4, Ui.DIM);
    }

    private void drawCountryRow(GuiGraphics graphics, CredentialRegistryData.Country country, int index,
                                int x, int y, int width, int height, boolean selected, boolean hovered) {
        graphics.fill(x, y, x + width, y + height, selected ? 0xFF243A50 : hovered ? 0xFF1A2A3B : 0x33101A24);
        graphics.fill(x, y, x + 4, y + height, accent);
        graphics.drawString(font, country.code(), x + 10, y + 8, Ui.GOLD, false);
        graphics.drawString(font, Ui.ellipsis(country.nameZh() + " · " + country.nameEn(), 260),
                x + 52, y + 4, Ui.TEXT, false);
        graphics.drawString(font, Ui.ellipsis(Component.translatable(
                        "screen.veriborder.registry.row_detail", country.defaultNationality(), country.note())
                .getString(), width - 66), x + 52, y + 14, Ui.MUTED, false);
    }

    /**
     * One template row: title · credential type · tint swatch · emblem, the
     * default country/issuer detail line, and the row's own delete button
     * (26.4.1).
     */
    private void drawTemplateRow(GuiGraphics graphics, CredentialRegistryData.Template template, int index,
                                 int x, int y, int width, int height, boolean selected, boolean hovered) {
        // 26.5: the row swatch is the colour the cover will actually print
        // (effective tint), so a template saved without a colour no longer reads as
        // a black or colourless row.
        int tint = TemplateDisplay.effectiveTint(template);
        int color = (tint & 0x00FFFFFF) | 0xFF000000;
        graphics.fill(x, y, x + width, y + height, selected ? 0xFF243A50 : hovered ? 0xFF1A2A3B : 0x33101A24);
        graphics.fill(x, y, x + 4, y + height, color);
        IconPicker.drawGlyph(graphics, TemplateDisplay.templateEmblem(template), x + 9, y + 5, 13, Ui.GOLD);
        graphics.drawString(font, Ui.ellipsis(TemplateDisplay.templateTitle(template), 168),
                x + 26, y + 3, Ui.TEXT, false);
        // Tint swatch: the colour the cover will be printed in.
        graphics.fill(x + 198, y + 5, x + 210, y + 15, color);
        graphics.renderOutline(x + 198, y + 5, 12, 10, 0x99FFFFFF);
        String type = Component.translatable(template.kind().translationKey()).getString()
                + " · " + Component.translatable(DocStyle.byId(template.style()).shortNameKey()).getString();
        graphics.drawString(font, Ui.ellipsis(type, 190), x + 216, y + 3, Ui.MUTED, false);
        String detail = Component.translatable("screen.veriborder.registry.template_row",
                template.mrzCountry().isBlank() ? "—" : template.mrzCountry(),
                template.kind().issuerId()).getString();
        if (template.hasTexture()) {
            detail = detail + " · " + Component.translatable("screen.veriborder.registry.texture_badge").getString();
        }
        graphics.drawString(font, Ui.ellipsis(detail, width - TRASH_W - 46),
                x + 26, y + 13, Ui.DIM, false);
        drawDeleteChip(graphics, trashRect(x, y, width), isDeleteArmed(template), hovered);
    }

    private int[] trashRect(int x, int y, int width) {
        return new int[]{x + width - TRASH_RIGHT_INSET - TRASH_W, y + 4, TRASH_W, TRASH_H};
    }

    private void drawDeleteChip(GuiGraphics graphics, int[] rect, boolean armed, boolean hovered) {
        if (!admin) {
            return;
        }
        int fill = armed ? 0xFF7A1F1F : hovered ? 0xFF3A1D22 : 0xFF1E2530;
        int border = armed ? Ui.RED : Ui.BORDER;
        graphics.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3], fill);
        graphics.renderOutline(rect[0], rect[1], rect[2], rect[3], border);
        Component label = Component.translatable(armed
                ? "button.veriborder.registry_confirm_delete" : "button.veriborder.delete");
        graphics.drawCenteredString(font, label.getString(), rect[0] + rect[2] / 2, rect[1] + 4,
                armed ? Ui.TEXT : Ui.MUTED);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        // 26.4: dropdowns (credential type, base layout, default country, base map)
        // own the click first, including clicks that dismiss an open panel.
        if (dropdownClick(uiX, uiY)) {
            return true;
        }
        // An open picker owns the click even where the list or a button sits under it.
        if (draft.tab() == TAB_TEMPLATES) {
            if (templateColorPicker != null && templateColorPicker.open()
                    && templateColorPicker.overHexField(uiX, uiY)) {
                // Its RGB input is a real widget: let the EditBox take the event.
                return super.mouseClicked(mouseX, mouseY, button);
            }
            if (templateColorPicker != null && templateColorPicker.open()
                    && templateColorPicker.mouseClicked(uiX, uiY)) {
                if (!templateColorPicker.open()) {
                    draft.setTemplateColor(templateColorPicker.commit());
                    rebuildWidgets();
                }
                closeOtherPickers(templateColorPicker);
                return true;
            }
            if (templateIconPicker != null && templateIconPicker.open()
                    && templateIconPicker.mouseClicked(uiX, uiY)) {
                if (!templateIconPicker.open()) {
                    draft.setTemplateIcon(templateIconPicker.selected());
                    rebuildWidgets();
                }
                closeOtherPickers(templateIconPicker);
                return true;
            }
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (tabs != null) {
            int hit = tabs.hit(uiX, uiY);
            if (hit >= 0) {
                if (hit != draft.tab()) {
                    draft.setTab(hit);
                    recodeArmed = false;
                    statusLine = null;
                    rebuildWidgets();
                }
                return true;
            }
        }
        if (draft.tab() == TAB_COUNTRIES) {
            if (countryList != null && countryList.mouseClicked(uiX, uiY)) {
                if (countryList.selectedIndex() >= 0 && countryList.selectedIndex() != selectedCountry) {
                    selectedCountry = countryList.selectedIndex();
                    recodeArmed = false;
                    // Seed the form from the row (the draft keeps it afterwards).
                    draft.selectCountry(countries.get(selectedCountry));
                    rebuildWidgets();
                }
                return true;
            }
            return false;
        }
        // 26.4.1: the template sub-view strip, hit before the form controls.
        if (templatePages != null) {
            int page = templatePages.hit(uiX, uiY);
            if (page >= 0) {
                if (page != templatePage) {
                    templatePage = page;
                    rebuildWidgets();
                }
                return true;
            }
        }
        if (templateColorPicker != null && templateColorPicker.mouseClicked(uiX, uiY)) {
            if (!templateColorPicker.open()) {
                draft.setTemplateColor(templateColorPicker.commit());
                rebuildWidgets();
            }
            return true;
        }
        if (templateIconPicker != null && templateIconPicker.mouseClicked(uiX, uiY)) {
            if (!templateIconPicker.open()) {
                draft.setTemplateIcon(templateIconPicker.selected());
                rebuildWidgets();
            }
            return true;
        }
        if (templateList != null) {
            // Row delete button first: it sits inside the row hit box.
            if (templateRowDeleteClicked(uiX, uiY)) {
                return true;
            }
            if (templateList.mouseClicked(uiX, uiY)) {
                if (templateList.selectedIndex() >= 0 && templateList.selectedIndex() != selectedTemplate) {
                    selectTemplateAt(templateList.selectedIndex());
                }
                return true;
            }
        }
        return false;
    }

    /** Handles a click on a row delete button (26.4.1 two-step confirmation). */
    private boolean templateRowDeleteClicked(double uiX, double uiY) {
        int index = templateList.indexAt(uiX, uiY);
        if (index < 0) {
            return false;
        }
        int[] row = templateList.rowRect(index);
        if (row == null) {
            return false;
        }
        int[] trash = trashRect(row[0], row[1], row[2]);
        if (uiX < trash[0] || uiX > trash[0] + trash[2] || uiY < trash[1] || uiY > trash[1] + trash[3]) {
            return false;
        }
        if (!admin || index >= templates.size()) {
            return true;
        }
        CredentialRegistryData.Template template = templates.get(index);
        // A delete always acts on the row it was clicked on, so a stale selection
        // can never delete a different template.
        if (index != selectedTemplate) {
            selectTemplateAt(index);
        }
        armDelete(template);
        rebuildWidgets();
        return true;
    }

    /** Selects one row and seeds every editor field from it. */
    private void selectTemplateAt(int index) {
        if (index < 0 || index >= templates.size()) {
            return;
        }
        selectedTemplate = index;
        CredentialRegistryData.Template template = templates.get(index);
        draft.selectTemplate(template);
        // 26.5: the palette shows the template's EFFECTIVE tint — a template saved
        // without a colour (or by an older build, where the colour lived in the
        // legacy `color` field) opens on the colour it will actually print, never on
        // black and never on the base layout's colour.
        draft.setTemplateColor(TemplateDisplay.effectiveTint(template));
        templateColourDefaulted = true;
        draft.setTemplateIcon(TemplateDisplay.templateEmblem(template));
        seedDefinitionFields(template);
        statusLine = null;
    }

    private void seedDefinitionFields(CredentialRegistryData.Template template) {
        if (template == null) {
            titleZh = "";
            titleEn = "";
            textureHash = "";
            return;
        }
        titleZh = template.titleZh();
        titleEn = template.titleEn();
        textureHash = template.texture();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        if (dropdownScroll(uiX, uiY, delta)) {
            return true;
        }
        if (draft.tab() == TAB_COUNTRIES && countryList != null && countryList.mouseScrolled(uiX, uiY, delta)) {
            return true;
        }
        if (draft.tab() == TAB_TEMPLATES) {
            if (templateList != null && templateList.mouseScrolled(uiX, uiY, delta)) {
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /** Only one registry picker is unwrapped at a time. */
    private void closeOtherPickers(Object keep) {
        if (templateColorPicker != null && templateColorPicker != keep) {
            templateColorPicker.setOpen(false);
        }
        if (templateIconPicker != null && templateIconPicker != keep) {
            templateIconPicker.setOpen(false);
        }
        closeDropdownsExcept(null);
    }

    /**
     * S→C snapshot arrived while the editor is open: refresh the lists and keep
     * the current selection by its stable key (country code / template id) —
     * never by raw index, because an update can reorder the snapshot (26.4).
     *
     * <p>26.4 fix: the tab and the whole form draft deliberately survive this
     * path. It used to call the server editor reopen, which reset the page to
     * Countries and cleared every typed field.</p>
     *
     * <p>26.4.1: a selection whose template disappeared (deleted, possibly by an
     * administrator on another client) is cleared and the local confirmation keeps
     * the operator informed, because a snapshot carries no chat line.</p>
     */
    private void refreshFromCache() {
        String countryCode = selectedCountry() == null ? "" : selectedCountry().code();
        String templateId = selectedTemplate() == null ? "" : selectedTemplate().id();
        countries = new ArrayList<>(ClientRegistryCache.countries());
        templates = new ArrayList<>(ClientRegistryCache.templates());
        // A delete releases the base-map slot, so the list is always taken from the
        // snapshot — including when it became empty.
        textures = new ArrayList<>(ClientRegistryCache.textures());
        // 26.4.1: an upload only counts once the registry snapshot shows the hash.
        if (!pendingTextureHash.isBlank()) {
            if (ClientRegistryCache.texture(pendingTextureHash) != null) {
                textureHash = pendingTextureHash;
                statusLine = Component.translatable("message.veriborder.registry.texture_saved");
            } else {
                textureHash = "";
                statusLine = Component.translatable("screen.veriborder.registry.texture_upload_failed");
            }
            pendingTextureHash = "";
        }
        selectedCountry = indexOfCountry(countryCode);
        int templateIndex = indexOfTemplate(templateId);
        if (templateIndex >= 0) {
            selectedTemplate = templateIndex;
        } else if (!templateId.isBlank() && indexOfTemplate(templateId) < 0) {
            // The edited template is gone: clear the selection and the form so the
            // editor never keeps showing a row that no longer exists.
            selectedTemplate = -1;
            if (!templateId.equals(pendingDeleteId)) {
                pendingDeleteName = draft.templateName();
            }
            draft.newTemplate();
            seedDefinitionFields(null);
            if (!pendingDeleteName.isBlank()) {
                statusLine = Component.translatable(
                        "message.veriborder.registry.template_deleted", pendingDeleteName);
            }
            pendingDeleteId = "";
            pendingDeleteName = "";
            armDelete(null);
        } else if (selectedTemplate >= templates.size()) {
            selectedTemplate = -1;
        }
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- helpers

    private CredentialRegistryData.Country selectedCountry() {
        return selectedCountry >= 0 && selectedCountry < countries.size() ? countries.get(selectedCountry) : null;
    }

    private CredentialRegistryData.Template selectedTemplate() {
        return selectedTemplate >= 0 && selectedTemplate < templates.size()
                ? templates.get(selectedTemplate) : null;
    }

    private Component recodeLabel() {
        return Component.translatable(recodeArmed
                ? "button.veriborder.registry_confirm_code"
                : "button.veriborder.registry_change_code");
    }

    /** Whether any row currently shows its "confirm delete" state. */
    private boolean isDeleteArmedAny() {
        return !deleteArmedId.isBlank()
                && System.currentTimeMillis() - deleteArmedAt <= DELETE_CONFIRM_MILLIS;
    }

    /** Live tint of the form (palette commit while it is open). */
    private int currentTint() {
        int tint = templateColorPicker != null && templateColorPicker.open()
                ? templateColorPicker.color() : draft.templateColor();
        // 26.5: an empty field previews as the neutral default, exactly like the
        // value a save would store, so the band and the card never render black.
        return TemplateDisplay.effectiveTint(tint);
    }

    /**
     * 26.5: a brand-new template draft starts on the neutral cover colour instead of
     * an empty field. Seeded once per draft; afterwards the operator's choice — a
     * deliberately white card included — survives every rebuild.
     */
    private void seedTemplateColour() {
        if (templateColourDefaulted) {
            return;
        }
        templateColourDefaulted = true;
        if (!draft.templateEditing() && (draft.templateColor() & 0x00FFFFFF) == 0xFFFFFF) {
            draft.setTemplateColor(TemplateDisplay.NEUTRAL_TINT);
        }
    }

    private String currentEmblem() {
        if (templateIconPicker != null) {
            return templateIconPicker.selected();
        }
        return draft.templateIcon();
    }

    /** Live credential title: the typed title, else the template name, else "—". */
    private String currentTitle() {
        String chinese = titleZh.trim();
        String english = titleEn.trim();
        String typed = TemplateDisplay.chinese() ? chinese : english;
        if (typed.isBlank()) {
            typed = TemplateDisplay.chinese() ? english : chinese;
        }
        if (!typed.isBlank()) {
            return typed;
        }
        return draft.templateName().isBlank() ? "—" : draft.templateName().trim();
    }

    private String credentialFooter() {
        DocStyle style = DocStyle.byId(draft.templateStyle());
        String layout = Component.translatable(style.shortNameKey()).getString();
        return Component.translatable(draft.templateKind().translationKey()).getString() + " · " + layout;
    }

    /**
     * Preview-only document for the live card: never signed, never saved. It
     * carries the form's tint/emblem/title so {@code Ui.documentCard} renders
     * exactly what the issuing machine would print.
     */
    private DocumentData previewDocument() {
        CompoundTag tag = new CompoundTag();
        DocStyle style = DocStyle.byId(draft.templateStyle());
        tag.putString("style", style.id());
        tag.putString("kind", draft.templateKind().name());
        tag.putString("authority", style.defaultAuthority());
        tag.putString("number", "—");
        tag.putString("name", currentTitle());
        tag.putString("displayName", currentTitle());
        tag.putString("birthDate", "1990-01-01");
        tag.putString("nationality", style.defaultNationality());
        tag.putString("expiryDate", style.permanent()
                ? com.veriborder.doc.DateUtil.PERMANENT
                : com.veriborder.doc.DateUtil.plusYears(com.veriborder.doc.DateUtil.today(), style.validityYears()));
        tag.putInt("customColor", currentTint());
        tag.putString("customIcon", currentEmblem());
        DocumentData preview = DocumentData.fromTag(tag);
        // The preview names no template id on purpose: it must show the *edited*
        // title/tint/emblem, not what the registry snapshot still holds.
        preview.setTint(currentTint());
        preview.setEmblem(currentEmblem());
        preview.setTemplateTex(textureHash);
        return preview;
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "—";
        }
        return hash.length() <= 10 ? hash : hash.substring(0, 10);
    }

    /** File name behind an uploaded hash, or "" when it came from elsewhere. */
    private String localNameOf(String hash) {
        if (hash == null || hash.isBlank()) {
            return "";
        }
        for (int i = 0; i < localPrepared.size() && i < localFiles.size(); i++) {
            TemplateTextureFiles.Prepared prepared = localPrepared.get(i);
            if (prepared.ok() && hash.equals(prepared.hash())) {
                return localFiles.get(i).name();
            }
        }
        return "";
    }
}
