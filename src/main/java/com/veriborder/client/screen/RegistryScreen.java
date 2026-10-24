package com.veriborder.client.screen;

import com.veriborder.client.BetterButton;
import com.veriborder.client.ClientRegistryCache;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.client.ui.ColorPalette;
import com.veriborder.client.ui.Dropdown;
import com.veriborder.client.ui.IconPicker;
import com.veriborder.client.ui.PagedList;
import com.veriborder.client.ui.TabStrip;
import com.veriborder.doc.DocStyle;
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
 *       codes), colour (shared palette picker) and icon (preset glyph grid). A
 *       template is only shown by the issuing machine of its own kind.</li>
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
 * <p>Reading is open to everyone; add/delete/rename requires OP level 2, which the
 * server reports as {@code admin} in the opening payload and re-checks on every
 * write. Deleting a country or template that is still referenced is refused by the
 * server with a message naming the reference.</p>
 */
public class RegistryScreen extends VeriScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger("VeriBorder/Registry");

    private static final int TAB_COUNTRIES = RegistryDraft.TAB_COUNTRIES;
    private static final int TAB_TEMPLATES = RegistryDraft.TAB_TEMPLATES;
    private static final int WIDTH = 560;
    private static final int HEIGHT = 470;
    private static final int STRIP_Y = 44;
    private static final int FOOTER_Y = 420;
    private static final int FIELD_W = 512;
    private static final int LEFT = 24;

    private final boolean admin;
    private List<CredentialRegistryData.Country> countries = new ArrayList<>();
    private List<CredentialRegistryData.Template> templates = new ArrayList<>();
    /** All form values and the current tab (26.4 fix: survives every rebuild). */
    private final RegistryDraft draft = new RegistryDraft();

    private TabStrip tabs;
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
        // 26.4: the tab, the server's error reason, the saved template and the
        // echoed form of a refused save all come from the payload.
        draft.applyPayload(payload);
        if (!draft.errorKey().isBlank()) {
            LOGGER.warn("registry editor received a server error: {}", draft.errorKey());
        }
        if (payload != null && payload.contains(RegistryDraft.HIGHLIGHT_KEY, Tag.TAG_COMPOUND)) {
            CredentialRegistryData.Template saved = CredentialRegistryData.Template
                    .fromTag(payload.getCompound(RegistryDraft.HIGHLIGHT_KEY)).normalized();
            int index = indexOfTemplate(saved.id());
            if (index >= 0) {
                selectedTemplate = index;
            }
            LOGGER.info("registry editor re-selected the saved template '{}' ({})", saved.name(), saved.id());
        } else if (draft.templateEditing() && !draft.templateId().isBlank()) {
            // A refused save echoes its form; keep the edited row highlighted.
            int index = indexOfTemplate(draft.templateId());
            if (index >= 0) {
                selectedTemplate = index;
            }
        }
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
        if (draft.tab() == TAB_COUNTRIES) {
            buildCountries();
        } else {
            buildTemplates();
        }
        addRenderableWidget(new BetterButton(left + LEFT, top + FOOTER_Y, 110, 20,
                Component.translatable("button.veriborder.refresh"), button ->
                ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(BlockPos.ZERO,
                        RegistryService.ACTION_REFRESH, new CompoundTag(), -1)), Ui.CYAN, true));
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
                Component.translatable("button.veriborder.delete"), button -> deleteCountry(), Ui.RED);
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

    // --------------------------------------------------------------- templates

    private void buildTemplates() {
        int x = left + LEFT;
        int row1 = top + 92;
        int row2 = top + 124;
        int row3 = top + 156;
        RegistryDraft.TemplateView view = draft.templateView();

        // 26.4: credential type is a real dropdown (ID card / passport / permit)
        // instead of a cycle button; changing it starts a NEW template draft.
        List<Dropdown.Option> kindOptions = new ArrayList<>();
        for (DocumentKind kind : DocumentKind.values()) {
            kindOptions.add(new Dropdown.Option(kind.id(),
                    Component.translatable(kind.translationKey()).getString()));
        }
        templateKindChoice = addDropdown(Dropdown.at(x, row1, 150)
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
            selectedTemplate = -1;
            rebuildWidgets();
        });
        templateNameField = addField(x + 158, row1 + 4, 354,
                "field.veriborder.registry.template_name", view.name());
        // 26.4: every keystroke lands in the draft, so a later rebuild re-seeds
        // this box with the typed name instead of an empty value.
        templateNameField.setResponder(draft::setTemplateName);

        // Base layout selector: every official layout of the current kind. A
        // dropdown makes the target layout pickable in one click (26.4, focus).
        List<Dropdown.Option> styleOptions = new ArrayList<>();
        for (DocStyle style : DocStyle.forKind(view.kind())) {
            styleOptions.add(new Dropdown.Option(style.id(),
                    Component.translatable(style.shortNameKey()).getString()));
        }
        templateStyleChoice = addDropdown(Dropdown.at(x, row2, 180)
                .height(20)
                .prefix(Component.translatable("field.veriborder.registry.base_style_short").getString())
                .options(styleOptions)
                .selected(view.styleId())
                .enabled(admin));
        templateStyleChoice.onChange(draft::setTemplateStyle);
        adminButton(x + 184, row2, 82, 20,
                Component.translatable("button.veriborder.registry_new_template"), button -> newTemplate(),
                Ui.CYAN);
        adminButton(x + 270, row2, 118, 20,
                Component.translatable("button.veriborder.registry_add_template"), button -> saveTemplate(),
                Ui.GREEN);
        adminButton(x + 392, row2, 120, 20,
                Component.translatable("button.veriborder.delete"), button -> deleteTemplate(), Ui.RED);

        // Row 3: default country dropdown, colour palette, icon picker. Their open
        // panels drop into the (empty) band above the list, never over a widget.
        List<Dropdown.Option> options = new ArrayList<>();
        for (CredentialRegistryData.Country country : countries) {
            options.add(new Dropdown.Option(country.code(),
                    country.code() + "  " + country.displayName(false)));
        }
        templateCountryChoice = addDropdown(Dropdown.at(x, row3, 200)
                .labelKey("field.veriborder.registry.mrz_country")
                .panelBottom(top + 390)
                .placeholder(Ui.labelText("screen.veriborder.registry.empty_countries",
                        "No countries/regions registered yet."))
                .options(options)
                .selected(view.mrzCountry())
                .onChange(draft::setTemplateMrzCountry));
        templateColorPicker = ColorPalette.at(x + 208, row3, 150, view.color());
        templateColorHex = new EditBox(this.font, templateColorPicker.hexFieldX(),
                templateColorPicker.hexFieldY(), templateColorPicker.hexFieldWidth(), 12, Component.empty());
        templateColorHex.setBordered(false);
        templateColorHex.setTextColor(0xFFE9F3FF);
        templateColorHex.setMaxLength(7);
        templateColorHex.setValue(ColorPalette.hexValue(view.color()));
        // A typed #RRGGBB is a draft value too, so a rebuild keeps it.
        templateColorHex.setResponder(value -> draft.setTemplateColor(
                ColorPalette.parse(value, draft.templateColor())));
        templateColorHex.setVisible(false);
        addRenderableWidget(templateColorHex);
        templateColorPicker.hex(templateColorHex);
        templateIconPicker = IconPicker.at(x + 366, row3, 150, view.icon());

        templateList = PagedList.<CredentialRegistryData.Template>at(x, top + 260, FIELD_W, 132)
                .rowHeight(24).rowGap(1).accent(accent)
                .emptyText(Component.translatable("screen.veriborder.registry.empty_templates"));
        templateList.setItems(templates);
        templateList.setSelectedIndex(selectedTemplate);
    }

    /** 26.4: starts a blank template draft (no id is sent for it). */
    private void newTemplate() {
        draft.newTemplate();
        draft.clearError();
        selectedTemplate = -1;
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
        String errorKey = draft.templateErrorKey();
        if (!errorKey.isBlank()) {
            LOGGER.info("registry template save blocked on the client: {}", errorKey);
            draft.setErrorKey(errorKey);
            rebuildWidgets();
            return;
        }
        // 26.4: the payload is built from the draft fields, never from the widget
        // that a rebuild may have replaced.
        CompoundTag data = draft.templatePayload();
        LOGGER.info("registry template save payload: kind='{}' name='{}' style='{}' id='{}' (editing={})",
                data.getString("kind"), data.getString("name"), data.getString("style"),
                data.getString("id"), draft.templateEditing());
        send(RegistryService.ACTION_SAVE_TEMPLATE, data);
    }

    private void deleteTemplate() {
        CredentialRegistryData.Template template = selectedTemplate();
        if (template == null) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putString("id", template.id());
        send(RegistryService.ACTION_DELETE_TEMPLATE, data);
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
            Ui.clipped(graphics, Component.translatable("screen.veriborder.registry.templates_hint"),
                    x, top + 70, FIELD_W, Ui.DIM);
            if (templateList != null) {
                templateList.setItems(templates);
                templateList.draw(graphics, font, this::drawTemplateRow, mouseUiX, mouseUiY);
            }
            // Pickers are painted after the list so their closed controls sit on
            // top of it; open panels are handled by render()'s overlay pass.
            if (templateColorPicker != null && !templateColorPicker.open()) {
                templateColorPicker.draw(graphics, font, accent);
            }
            if (templateIconPicker != null && !templateIconPicker.open()) {
                templateIconPicker.draw(graphics, font, accent);
            }
            Ui.clipped(graphics, Component.translatable("screen.veriborder.registry.templates_title",
                    templates.size()), x, top + 244, FIELD_W, Ui.MUTED);
        }
        if (!draft.errorKey().isBlank()) {
            // 26.4 fix: the server's concrete reason is shown here, not only in chat.
            Ui.clipped(graphics, Component.translatable(draft.errorKey()), x, top + 400, 300, Ui.RED);
        } else if (!admin) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.registry.op_hint"),
                    x, top + 400, FIELD_W, Ui.MUTED);
        }
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

    private void drawTemplateRow(GuiGraphics graphics, CredentialRegistryData.Template template, int index,
                                 int x, int y, int width, int height, boolean selected, boolean hovered) {
        graphics.fill(x, y, x + width, y + height, selected ? 0xFF243A50 : hovered ? 0xFF1A2A3B : 0x33101A24);
        graphics.fill(x, y, x + 4, y + height, (template.color() & 0x00FFFFFF) | 0xFF000000);
        IconPicker.drawGlyph(graphics, template.icon(), x + 10, y + 5, 14, Ui.GOLD);
        graphics.drawString(font, Ui.ellipsis(template.name(), 190), x + 30, y + 3, Ui.TEXT, false);
        graphics.drawString(font, Component.translatable(template.kind().translationKey()).getString()
                + " · " + Component.translatable(DocStyle.byId(template.style()).shortNameKey()).getString(),
                x + 230, y + 3, Ui.MUTED, false);
        graphics.drawString(font, Ui.ellipsis(Component.translatable(
                        "screen.veriborder.registry.template_row",
                        template.mrzCountry().isBlank() ? "—" : template.mrzCountry(),
                        template.kind().issuerId()).getString(), width - 240),
                x + 30, y + 13, Ui.DIM, false);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        // 26.4: dropdowns (credential type, base layout, default country) own the
        // click first, including clicks that dismiss an open panel.
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
        if (templateList != null && templateList.mouseClicked(uiX, uiY)) {
            if (templateList.selectedIndex() >= 0 && templateList.selectedIndex() != selectedTemplate) {
                selectedTemplate = templateList.selectedIndex();
                draft.selectTemplate(templates.get(selectedTemplate));
                rebuildWidgets();
            }
            return true;
        }
        return false;
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
     */
    private void refreshFromCache() {
        String countryCode = selectedCountry() == null ? "" : selectedCountry().code();
        String templateId = selectedTemplate() == null ? "" : selectedTemplate().id();
        countries = new ArrayList<>(ClientRegistryCache.countries());
        templates = new ArrayList<>(ClientRegistryCache.templates());
        selectedCountry = indexOfCountry(countryCode);
        int templateIndex = indexOfTemplate(templateId);
        if (templateIndex >= 0) {
            selectedTemplate = templateIndex;
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
}
