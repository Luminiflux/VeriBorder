package com.veriborder.client.screen;

import com.veriborder.block.DeviceType;
import com.veriborder.client.BetterButton;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.client.ui.BirthDatePicker;
import com.veriborder.client.ui.Dropdown;
import com.veriborder.client.ui.PagedList;
import com.veriborder.client.ui.TabStrip;
import com.veriborder.doc.DateUtil;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentFactory;
import com.veriborder.doc.DocumentKind;
import com.veriborder.doc.EndorsementType;
import com.veriborder.doc.VisaType;
import com.veriborder.network.DeviceActionPacket;
import com.veriborder.network.ModNetwork;
import com.veriborder.registry.CredentialRegistryData;
import com.veriborder.registry.IssuerChoices;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Applicant-facing document issuer (ID card, passport and permit machines).
 *
 * <p>26.3 layout: the single long form is split into a shared {@link TabStrip}
 * with Applicant / Document / Attached visa / Presets pages. The issue action
 * stays in the footer on every page, and every page switch snapshots the
 * visible fields into {@code formCache}, so editing a field on one page and
 * issuing from another can never lose input. The preset list is a shared
 * {@link PagedList} instead of the old one-at-a-time arrows.</p>
 */
public class IssuerScreen extends VeriScreen {
    private static final int TAB_APPLICANT = 0;
    private static final int TAB_DOCUMENT = 1;
    private static final int TAB_VISA = 2;
    private static final int TAB_PRESETS = 3;

    private static final int TAB_STRIP_Y = 44;
    private static final int FOOTER_Y = 352;

    private final DeviceType device;
    private final BlockPos pos;
    private final CompoundTag payload;
    private final DocumentKind kind;
    private final boolean lockDocumentName;
    private final boolean lockAuthority;
    private final String lockedDocumentName;
    private final String lockedAuthority;
    private final boolean lockStyle;
    private final DocStyle lockedStyle;
    private final boolean lockPlace;
    private final String lockedPlace;
    private final boolean lockIssueDate;
    private final String lockedIssueDate;
    private final boolean lockValidity;
    /** 26.2 month precision: locked document validity in months (0 = permanent). */
    private final int lockedValidityMonths;
    private final boolean lockVisaType;
    private final VisaType lockedVisaType;
    private final boolean lockVisaCode;
    private final String lockedVisaCode;
    private final boolean lockIncludeVisa;
    private final boolean lockedIncludeVisa;
    private final boolean lockVisaEntries;
    private final int lockedVisaEntries;
    private final boolean lockVisaCountry;
    private final String lockedVisaCountry;
    private final boolean lockVisaIssueDate;
    private final String lockedVisaIssueDate;
    private final boolean lockVisaValidity;
    /** 26.2 month precision: locked attached-visa validity in months (0 = permanent). */
    private final int lockedVisaValidityMonths;
    private final boolean lockVisaName;
    private final String lockedVisaName;
    /** 26.2 nationality lock: the holder nationality is fixed by the terminal. */
    private final boolean lockNationality;
    private final String lockedNationality;
    /** Device number policy: locked = generated server-side, unlocked = typed. */
    private final boolean lockNumber;
    /** 26.3 round 12: nationality forced by the machine's issuing country ("" = free). */
    private String machineNationality = "";
    private final List<DocStyle> styles;
    /** 26.3 registry: countries/regions and templates pushed by the server. */
    private final List<CredentialRegistryData.Country> registryCountries = new ArrayList<>();
    private final List<CredentialRegistryData.Template> registryTemplates = new ArrayList<>();
    private DocStyle style;
    /** 26.4: registry template currently chosen in the layout dropdown ("" = a plain layout). */
    private String selectedTemplateId = "";
    private int presetIndex;
    private int tab = TAB_APPLICANT;
    private CompoundTag formCache;
    private TabStrip tabs;
    private PagedList<CompoundTag> presetList;

    private EditBox name;
    private EditBox nameLatin;
    private EditBox number;
    /** 26.2: date of birth is three dropdowns (year / month / day), never typed. */
    private BirthDatePicker birth;
    private EditBox nationality;
    private EditBox authority;
    private EditBox place;
    private EditBox issue;
    private EditBox expiry;
    private EditBox address;
    private EditBox displayName;
    private EditBox presetName;
    private EditBox visaCountry;
    private EditBox visaCode;
    private EditBox visaEntries;
    private EditBox visaName;
    private BetterButton includeVisaButton;
    private String sex = "M";
    private VisaType visaType;
    private boolean includeVisa;
    private final String[] endorsementChoices = {
            "none", EndorsementType.ONE_YEAR_MULTI.id(), EndorsementType.THREE_MONTH_SINGLE.id(),
            EndorsementType.OTHER.id()
    };
    /** Default of the index above: three months, one entry (26.2 permit default). */
    private static final int DEFAULT_ENDORSEMENT_CHOICE = 2;
    /**
     * 26.2: the built-in endorsement a permit carries. A locked type is forced by
     * the terminal - the dropdown is read-only and the server ignores the form.
     */
    private final boolean lockEndorsementType;
    private final EndorsementType lockedEndorsementType;
    private int endorsementChoice = DEFAULT_ENDORSEMENT_CHOICE;

    public IssuerScreen(DeviceType device, BlockPos pos, CompoundTag payload) {
        super(Component.translatable(device.translationKey()), 520, 400);
        this.device = device;
        this.pos = pos;
        this.payload = payload;
        this.kind = switch (device) {
            case ID_ISSUER -> DocumentKind.ID_CARD;
            case PASSPORT_ISSUER -> DocumentKind.PASSPORT;
            default -> DocumentKind.PERMIT;
        };
        this.lockedDocumentName = payload.getString("lockedDocumentName").trim();
        this.lockedAuthority = payload.getString("lockedAuthority").trim();
        // A locked document name with a BLANK value means "official preset name":
        // the applicant may not customise the title at all.
        this.lockDocumentName = payload.getBoolean("lockDocumentName");
        // Device policy (administrator): true keeps the number system-generated.
        this.lockNumber = !payload.contains("lockNumber") || payload.getBoolean("lockNumber");
        this.lockAuthority = payload.getBoolean("lockAuthority") && !lockedAuthority.isBlank();
        DocStyle resolvedStyle = resolveStyle(payload.getString("lockedStyle"), kind);
        this.lockedStyle = resolvedStyle;
        this.lockStyle = payload.getBoolean("lockStyle") && resolvedStyle != null;
        this.styles = DocStyle.forKind(kind);
        this.style = lockStyle ? resolvedStyle : styles.get(0);
        String placeValue = payload.getString("lockedPlace").trim();
        this.lockedPlace = placeValue;
        this.lockPlace = payload.getBoolean("lockPlace") && !placeValue.isBlank();
        String issueValue = payload.getString("lockedIssueDate").trim();
        this.lockedIssueDate = issueValue;
        // A lock with a blank date still applies: the terminal then issues on
        // "today" (enforced server-side), so the field must show it read-only
        // instead of silently becoming editable.
        this.lockIssueDate = payload.getBoolean("lockIssueDate");
        this.lockValidity = payload.getBoolean("lockValidity");
        // 26.2 month precision: months are authoritative, a legacy year payload
        // (older device NBT) is migrated x12.
        this.lockedValidityMonths = payload.contains("lockedValidityMonths")
                ? Math.max(0, payload.getInt("lockedValidityMonths"))
                : Math.max(0, payload.getInt("lockedValidityYears") * 12);
        VisaType resolvedVisaType = resolveVisaType(payload.getString("lockedVisaType"));
        this.lockedVisaType = resolvedVisaType == null ? VisaType.TOURIST : resolvedVisaType;
        this.lockVisaType = payload.getBoolean("lockVisaType") && resolvedVisaType != null;
        String visaCodeValue = payload.getString("lockedVisaCode").trim();
        this.lockedVisaCode = visaCodeValue;
        this.lockVisaCode = payload.getBoolean("lockVisaCode") && ScreenCodes.isValid(visaCodeValue);
        // 26.1 passport-attached visa locks. Each is independent from the
        // document-level lockPlace / lockIssueDate / lockValidity controls.
        this.lockIncludeVisa = payload.getBoolean("lockIncludeVisa");
        this.lockedIncludeVisa = payload.getBoolean("lockedIncludeVisa");
        this.lockVisaEntries = payload.getBoolean("lockVisaEntries");
        this.lockedVisaEntries = Math.max(0, payload.getInt("lockedVisaEntries"));
        String visaCountryValue = payload.getString("lockedVisaCountry").trim();
        this.lockedVisaCountry = visaCountryValue;
        this.lockVisaCountry = payload.getBoolean("lockVisaCountry") && !visaCountryValue.isBlank();
        String visaIssueValue = payload.getString("lockedVisaIssueDate").trim();
        this.lockedVisaIssueDate = visaIssueValue;
        this.lockVisaIssueDate = payload.getBoolean("lockVisaIssueDate") && DateUtil.isValid(visaIssueValue);
        this.lockVisaValidity = payload.getBoolean("lockVisaValidity");
        this.lockedVisaValidityMonths = payload.contains("lockedVisaValidityMonths")
                ? Math.max(0, payload.getInt("lockedVisaValidityMonths"))
                : Math.max(0, payload.getInt("lockedVisaValidityYears") * 12);
        String visaNameValue = payload.getString("lockedVisaName").trim();
        this.lockedVisaName = visaNameValue;
        // A locked blank name is meaningful: it forces the country/type fallback.
        this.lockVisaName = payload.getBoolean("lockVisaName");
        // 26.2 nationality lock. A blank locked value has no effect on the server,
        // so the form only reads as locked when a usable value is stored.
        String nationalityValue = payload.getString("lockedNationality").trim();
        this.lockedNationality = nationalityValue;
        this.lockNationality = payload.getBoolean("lockNationality") && !nationalityValue.isBlank();
        this.visaType = lockVisaType ? lockedVisaType : VisaType.TOURIST;
        // 26.2 permit built-in endorsement: the terminal's locked type is shown
        // read-only; otherwise the applicant picks one, defaulting to the
        // three-month single-entry endorsement the permit is expected to carry.
        EndorsementType resolvedEndorsementType =
                EndorsementType.byIdOrNull(payload.getString("lockedEndorsementType"));
        this.lockedEndorsementType = resolvedEndorsementType == null
                ? EndorsementType.THREE_MONTH_SINGLE : resolvedEndorsementType;
        this.lockEndorsementType = payload.getBoolean("lockEndorsementType")
                && resolvedEndorsementType != null;
        this.endorsementChoice = endorsementIndex(lockEndorsementType
                ? lockedEndorsementType : EndorsementType.THREE_MONTH_SINGLE);
        this.accent = device.accentColor();
        readRegistry(payload);
        this.machineNationality = machineNationalityOf(payload);
    }

    /** 26.3 registry snapshot slice carried by the issuer opening payload. */
    private void readRegistry(CompoundTag payload) {
        ListTag countries = payload.getList("registryCountries", Tag.TAG_COMPOUND);
        for (int i = 0; i < countries.size(); i++) {
            CredentialRegistryData.Country country =
                    CredentialRegistryData.Country.fromTag(countries.getCompound(i));
            if (country.valid()) {
                registryCountries.add(country);
            }
        }
        ListTag templates = payload.getList("registryTemplates", Tag.TAG_COMPOUND);
        for (int i = 0; i < templates.size(); i++) {
            CredentialRegistryData.Template template =
                    CredentialRegistryData.Template.fromTag(templates.getCompound(i)).normalized();
            if (template.valid() && template.kind() == kind) {
                registryTemplates.add(template);
            }
        }
    }

    private static DocStyle resolveStyle(String id, DocumentKind kind) {
        if (id == null) {
            return null;
        }
        String key = id.trim();
        for (DocStyle style : DocStyle.forKind(kind)) {
            if (style.id().equals(key)) {
                return style;
            }
        }
        return null;
    }

    private static VisaType resolveVisaType(String id) {
        if (id == null) {
            return null;
        }
        String key = id.trim();
        if (key.isEmpty()) {
            return null;
        }
        for (VisaType type : VisaType.values()) {
            if (type.id().equalsIgnoreCase(key) || type.name().equalsIgnoreCase(key)) {
                return type;
            }
        }
        return null;
    }

    /** Index of an endorsement type in {@link #endorsementChoices}. */
    private int endorsementIndex(EndorsementType type) {
        if (type != null) {
            for (int i = 0; i < endorsementChoices.length; i++) {
                if (endorsementChoices[i].equals(type.id())) {
                    return i;
                }
            }
        }
        return DEFAULT_ENDORSEMENT_CHOICE;
    }

    /**
     * The attached-visa country follows {@code lockVisaCountry}; when that lock
     * is off the server still honours the legacy {@code lockPlace}, so the form
     * mirrors exactly that precedence instead of offering an edit that would be
     * overridden on issue.
     */
    private boolean visaCountryLocked() {
        return lockVisaCountry || lockPlace;
    }

    private String lockedVisaCountryValue() {
        return lockVisaCountry ? lockedVisaCountry : lockedPlace;
    }

    // ------------------------------------------------------------------- build
    @Override
    protected void build() {
        if (formCache == null) {
            formCache = defaults();
        }
        applyLockedValues();
        if (kind != DocumentKind.PASSPORT && tab == TAB_VISA) {
            tab = TAB_APPLICANT;
        }
        int x = left + 24;
        tabs = TabStrip.at(x, top + TAB_STRIP_Y, 472).size(20, 3);
        tabs.add("applicant", Component.translatable("tab.veriborder.issuer.applicant"));
        tabs.add("document", Component.translatable("tab.veriborder.issuer.document"));
        if (kind == DocumentKind.PASSPORT) {
            tabs.add("visa", Component.translatable("tab.veriborder.issuer.visa_panel"));
        }
        tabs.add("presets", Component.translatable("tab.veriborder.issuer.presets"));
        tabs.setSelected(stripIndex(tab));

        switch (tab) {
            case TAB_DOCUMENT -> buildDocumentTab();
            case TAB_VISA -> buildVisaTab();
            case TAB_PRESETS -> buildPresetsTab();
            default -> buildApplicantTab();
        }

        addRenderableWidget(new BetterButton(left + 24, top + FOOTER_Y, 130, 22,
                Component.translatable("button.veriborder.issue"), button -> issue(), Ui.GREEN));
        if (kind == DocumentKind.PERMIT) {
            // 26.4: "initial endorsement" is a dropdown (none + 3 types) instead
            // of a cycle button; the stored wire value is still the type id.
            // 26.2: the terminal may LOCK the type; the control then shows the
            // forced value read-only (the server enforces it either way).
            List<Dropdown.Option> endorsements = new ArrayList<>();
            for (int i = 0; i < endorsementChoices.length; i++) {
                String id = endorsementChoices[i];
                String label = "none".equals(id)
                        ? Component.translatable("screen.veriborder.issuer.none").getString()
                        : Component.translatable(EndorsementType.byId(id).translationKey()).getString();
                endorsements.add(new Dropdown.Option(String.valueOf(i), label));
            }
            if (lockEndorsementType) {
                addDropdown(Dropdown.at(left + 164, top + FOOTER_Y, 190)
                        .height(22)
                        .prefix(Component.translatable("field.veriborder.initial_endorsement_short").getString())
                        .options(List.of(new Dropdown.Option(
                                lockedEndorsementType.id(),
                                Component.translatable(lockedEndorsementType.translationKey()).getString())))
                        .selected(lockedEndorsementType.id())
                        .enabled(false)
                        .tooltip(Component.translatable("hint.veriborder.locked")));
            } else {
                addDropdown(Dropdown.at(left + 164, top + FOOTER_Y, 190)
                        .height(22)
                        .prefix(Component.translatable("field.veriborder.initial_endorsement_short").getString())
                        .options(endorsements)
                        .selected(String.valueOf(endorsementChoice))
                        // The panel opens upward: the control sits in the footer,
                        // so a downward list would hang off the panel.
                        .panelBottom(top + FOOTER_Y - 4)
                        .tooltip(Component.translatable("field.veriborder.initial_endorsement"))
                        .onChange(id -> {
                            try {
                                endorsementChoice = Math.max(0, Math.min(endorsementChoices.length - 1,
                                        Integer.parseInt(id)));
                            } catch (NumberFormatException ignored) {
                                endorsementChoice = DEFAULT_ENDORSEMENT_CHOICE;
                            }
                            rebuildWidgets();
                        }));
            }
        }
        addRenderableWidget(new BetterButton(left + 394, top + FOOTER_Y, 102, 22,
                Component.translatable("button.veriborder.close"), button -> onClose(), Ui.MUTED, true));
    }

    private void selectTab(int next) {
        captureCurrentTab();
        tab = clampTab(next);
        rebuildWidgets();
    }

    private int clampTab(int next) {
        if (kind != DocumentKind.PASSPORT && next == TAB_VISA) {
            return TAB_APPLICANT;
        }
        return Math.max(TAB_APPLICANT, Math.min(next, TAB_PRESETS));
    }

    /** Logical tab -> strip index (skip the visa page on non-passports). */
    private int stripIndex(int logicalTab) {
        if (kind == DocumentKind.PASSPORT) {
            return logicalTab;
        }
        return switch (logicalTab) {
            case TAB_DOCUMENT -> 1;
            case TAB_PRESETS -> 2;
            default -> 0;
        };
    }

    /** Strip index -> logical tab (skip the visa page on non-passports). */
    private int logicalTab(int stripIndex) {
        if (kind == DocumentKind.PASSPORT) {
            return stripIndex;
        }
        return switch (stripIndex) {
            case 1 -> TAB_DOCUMENT;
            case 2 -> TAB_PRESETS;
            default -> TAB_APPLICANT;
        };
    }

    private void buildApplicantTab() {
        // Permanent document name: empty means the official template name.
        displayName = addField(left + 24, top + 84, 300, "field.veriborder.display_name",
                formCache.getString("displayName"),
                lockDocumentName ? "hint.veriborder.locked" : "hint.veriborder.blank_official");
        if (lockDocumentName) {
            displayName.setValue(lockedDocumentName);
            displayName.setEditable(false);
        }
        name = addField(left + 24, top + 116, 214, "field.veriborder.name", formCache.getString("name"), null);
        nameLatin = addField(left + 266, top + 116, 230, "field.veriborder.name_latin",
                formCache.getString("nameLatin"), null);
        number = addField(left + 24, top + 148, 214, "field.veriborder.number", formCache.getString("number"),
                lockNumber ? "hint.veriborder.generated" : "hint.veriborder.auto");
        if (lockNumber) {
            // A locked number is generated at issue time; the field never accepts
            // a typed value (the server ignores one anyway).
            number.setValue("");
            number.setEditable(false);
        }
        // 26.4: sex is a dropdown (M/F/X) instead of a cycle button.
        List<Dropdown.Option> sexes = new ArrayList<>();
        for (String value : new String[]{"M", "F", "X"}) {
            sexes.add(new Dropdown.Option(value,
                    Component.translatable("sex.veriborder." + sexKey(value)).getString()));
        }
        addDropdown(Dropdown.at(left + 266, top + 144, 120)
                .prefix(Component.translatable("field.veriborder.sex").getString() + ": ")
                .options(sexes)
                .selected(sex)
                .onChange(id -> {
                    sex = id;
                    captureApplicant();
                    rebuildWidgets();
                }));
        // 26.2: the birthday is picked from three dropdowns (year / month / day)
        // instead of a free-text field; the written value is unchanged.
        birth = BirthDatePicker.at(left + 24, top + 180, formCache.getString("birthDate"),
                next -> formCache.putString("birthDate", next.value()));
        birth.register(this::addDropdown);
        if (!lockNationality && machineNationality.isBlank() && !registryCountries.isEmpty()) {
            // 26.3: the nationality selector reads the credential registry's
            // country/region list instead of a free-text field. 26.4: dropdown
            // with a search row, because that list can be long.
            List<Dropdown.Option> nationalities = new ArrayList<>();
            for (CredentialRegistryData.Country country : registryCountries) {
                nationalities.add(new Dropdown.Option(countryNationality(country),
                        country.code() + "  " + countryNationality(country)));
            }
            addDropdown(Dropdown.at(left + 266, top + 176, 230)
                    .options(nationalities)
                    .selected(nationalityValue())
                    .placeholder(Component.translatable("hint.veriborder.optional").getString())
                    .tooltip(Component.translatable("field.veriborder.nationality"))
                    .onChange(id -> {
                        if (formCache != null) {
                            formCache.putString("nationality", id);
                        }
                    }));
        } else {
            // 26.3 round 12: the issuing machine's single country selector decides
            // the nationality too, so the applicant only sees the value. A legacy
            // nationality lock still shows its stored value read-only.
            String value = lockNationality ? lockedNationality
                    : machineNationality.isBlank() ? formCache.getString("nationality") : machineNationality;
            nationality = addField(left + 266, top + 180, 230, "field.veriborder.nationality", value,
                    lockNationality ? "hint.veriborder.locked"
                            : machineNationality.isBlank() ? null : "hint.veriborder.machine_nationality");
            if (lockNationality || !machineNationality.isBlank()) {
                nationality.setValue(value);
                nationality.setEditable(false);
            }
        }
    }

    /**
     * Nationality forced by the machine's issuing country (26.3 round 12), or ""
     * when the machine has no registered country selected.
     */
    private String machineNationalityOf(CompoundTag payload) {
        String code = com.veriborder.doc.MrzUtil.countryCode(payload.getString("issuerCountry"));
        if (code.isEmpty()) {
            return "";
        }
        for (CredentialRegistryData.Country country : registryCountries) {
            if (country.code().equals(code)) {
                return country.defaultNationality().isBlank() ? country.displayName(true)
                        : country.defaultNationality();
            }
        }
        return "";
    }

    /** Current form nationality (locked value wins). */
    private String nationalityValue() {
        return lockNationality ? lockedNationality : formCache.getString("nationality");
    }

    private static String countryNationality(CredentialRegistryData.Country country) {
        if (country == null) {
            return "";
        }
        return country.defaultNationality().isBlank() ? country.displayName(true) : country.defaultNationality();
    }

    private void buildDocumentTab() {
        // 26.4 fix: the document layout dropdown now merges the official layouts
        // of this credential kind with the registry templates of the same kind,
        // so a template saved in the registry ("注册器") shows up here instead of
        // only in the presets tab.
        List<Dropdown.Option> layouts = new ArrayList<>();
        for (IssuerChoices.Choice choice : IssuerChoices.layoutChoices(kind, registryTemplates,
                value -> Component.translatable(value.displayNameKey()).getString())) {
            layouts.add(new Dropdown.Option(choice.value(), choice.label()));
        }
        addDropdown(Dropdown.at(left + 24, top + 84, 230)
                .options(layouts)
                .selected(selectedLayoutValue())
                .enabled(!lockStyle)
                .tooltip(lockStyle ? Component.translatable("hint.veriborder.locked")
                        : Component.translatable("button.veriborder.change_style"))
                .onChange(this::selectLayout));
        addRenderableWidget(new BetterButton(left + 260, top + 84, 90, 20,
                Component.translatable("button.veriborder.defaults"), button -> {
                    formCache = defaults();
                    sex = "M";
                    includeVisa = lockIncludeVisa ? lockedIncludeVisa : false;
                    if (kind == DocumentKind.PASSPORT && !lockVisaType) {
                        visaType = VisaType.TOURIST;
                    }
                    rebuildWidgets();
                }, Ui.MUTED, true));
        authority = addField(left + 24, top + 116, 214, "field.veriborder.issuer",
                formCache.getString("authority"), lockAuthority ? "hint.veriborder.locked" : null);
        if (lockAuthority) {
            authority.setValue(lockedAuthority);
            authority.setEditable(false);
        }
        place = addField(left + 266, top + 116, 230, "field.veriborder.place",
                formCache.getString("placeOfIssue"), lockPlace ? "hint.veriborder.locked" : null);
        if (lockPlace) {
            place.setValue(lockedPlace);
            place.setEditable(false);
        }
        issue = addField(left + 24, top + 148, 214, "field.veriborder.issue",
                formCache.getString("issueDate"), lockIssueDate ? "hint.veriborder.locked" : "hint.veriborder.date");
        if (lockIssueDate) {
            issue.setValue(lockedIssueValue());
            issue.setEditable(false);
        }
        expiry = addField(left + 266, top + 148, 230, "field.veriborder.expiry",
                formCache.getString("expiryDate"),
                lockValidity ? "hint.veriborder.auto_years" : "hint.veriborder.date");
        if (lockValidity) {
            expiry.setValue(autoExpiry(formCache.getString("issueDate")));
            expiry.setEditable(false);
            // The issue date stays editable unless it has its own lock; the
            // expiry preview follows it while the server stays authoritative.
            issue.setResponder(value -> {
                formCache.putString("issueDate", value);
                if (expiry != null) {
                    expiry.setValue(autoExpiry(value));
                }
            });
        }
        address = addField(left + 24, top + 180, 472, "field.veriborder.address", formCache.getString("address"));
    }

    /**
     * Optional passport-attached visa panel. Locked fields are seeded with the
     * terminal values and become read-only. Issue/expiry stay summary lines
     * because the visa inherits them from the passport, so a locked value is
     * simply shown here (the server applies it authoritatively).
     */
    private void buildVisaTab() {
        includeVisaButton = addRenderableWidget(new BetterButton(left + 24, top + 84, 150, 18,
                includeVisaLabel(), button -> {
                    if (lockIncludeVisa) {
                        return;
                    }
                    includeVisa = !includeVisa;
                    button.setMessage(includeVisaLabel());
                }, Ui.CYAN, true));
        if (lockIncludeVisa) {
            includeVisaButton.active = false;
        }
        String countryValue = formCache.getString("visaCountry");
        if (countryValue.isBlank()) {
            countryValue = style.defaultNationality();
        }
        boolean countryLocked = visaCountryLocked();
        visaCountry = addField(left + 24, top + 116, 230, "field.veriborder.visa_country",
                countryLocked ? lockedVisaCountryValue() : countryValue,
                countryLocked ? "hint.veriborder.locked" : null);
        if (countryLocked) {
            visaCountry.setValue(lockedVisaCountryValue());
            visaCountry.setEditable(false);
        }
        // 26.4: attached visa type is a dropdown (6 options).
        List<Dropdown.Option> visaTypes = new ArrayList<>();
        for (VisaType value : VisaType.values()) {
            visaTypes.add(new Dropdown.Option(value.id(),
                    Component.translatable(value.translationKey()).getString()));
        }
        addDropdown(Dropdown.at(left + 266, top + 112, 206)
                .options(visaTypes)
                .selected(visaType.id())
                .enabled(!lockVisaType)
                .tooltip(lockVisaType
                        ? Component.translatable("screen.veriborder.visa.self_locked",
                                Component.translatable(lockedVisaType.translationKey()))
                        : Component.translatable("field.veriborder.visa_type"))
                .onChange(id -> {
                    if (!lockVisaType) {
                        visaType = VisaType.byId(id);
                    }
                }));
        visaName = addField(left + 24, top + 148, 214, "field.veriborder.visa_name",
                formCache.getString("visaName"),
                lockVisaName ? "hint.veriborder.locked" : "hint.veriborder.visa_name");
        if (lockVisaName) {
            visaName.setValue(lockedVisaName);
            visaName.setEditable(false);
        }
        visaCode = addField(left + 266, top + 148, 230, "field.veriborder.visa_code",
                lockVisaCode ? lockedVisaCode : formCache.getString("visaCode"),
                lockVisaCode ? "hint.veriborder.locked" : "hint.veriborder.code");
        visaCode.setMaxLength(8);
        visaCode.setFilter(ScreenCodes::digitsUpTo8);
        if (lockVisaCode) {
            visaCode.setValue(lockedVisaCode);
            visaCode.setEditable(false);
        }
        String entriesValue = formCache.getString("visaEntries");
        visaEntries = addField(left + 24, top + 180, 100, "field.veriborder.entries",
                entriesValue.isBlank() ? "1" : entriesValue, "hint.veriborder.unlimited");
        visaEntries.setMaxLength(3);
        visaEntries.setFilter(value -> value.length() <= 3
                && value.chars().allMatch(character -> character >= '0' && character <= '9'));
        if (lockVisaEntries) {
            visaEntries.setValue(String.valueOf(lockedVisaEntries));
            visaEntries.setEditable(false);
        }
    }

    /** Preset library: a paged list of stored templates plus apply/save/delete. */
    private void buildPresetsTab() {
        presetName = addField(left + 24, top + 84, 300, "field.veriborder.preset_name",
                currentPresetName(), "hint.veriborder.preset");
        presetList = PagedList.<CompoundTag>at(left + 24, top + 112, 472, 192)
                .rowHeight(28).rowGap(1).accent(accent).emptyText(Component.translatable("hint.veriborder.no_presets"));
        presetList.setItems(presets());
        presetList.setSelectedIndex(presetIndex >= 0 && presetIndex < presets().size() ? presetIndex : -1);
        presetList.setPage(presetIndex >= 0 && presetList.perPage() > 0
                ? presetIndex / Math.max(1, presetList.perPage()) : 0);

        addRenderableWidget(new BetterButton(left + 24, top + 312, 70, 20,
                Component.translatable("button.veriborder.apply"), button -> applyPreset(), Ui.PURPLE, true));
        addRenderableWidget(new BetterButton(left + 100, top + 312, 64, 20,
                Component.translatable("button.veriborder.save"), button -> savePreset(), Ui.CYAN, true));
        addRenderableWidget(new BetterButton(left + 170, top + 312, 72, 20,
                Component.translatable("button.veriborder.delete"), button -> deletePreset(), Ui.RED, true));
    }

    private String currentPresetName() {
        List<CompoundTag> list = presets();
        if (list.isEmpty()) {
            return Component.translatable("screen.veriborder.issuer.default_preset").getString();
        }
        int index = Math.floorMod(presetIndex, list.size());
        return list.get(index).getString("name");
    }

    private Component includeVisaLabel() {
        String state = includeVisa ? "on" : "off";
        return Component.translatable("screen.veriborder.issuer.include_visa."
                + (lockIncludeVisa ? "locked." : "") + state);
    }

    private CompoundTag defaults() {
        CompoundTag tag = new CompoundTag();
        tag.putString("style", style.id());
        tag.putString("displayName", "");
        tag.putString("name", "");
        tag.putString("nameLatin", "");
        tag.putString("number", "");
        tag.putString("sex", sex);
        tag.putString("birthDate", "1990-01-01");
        tag.putString("address", DocumentFactory.defaultAddress(style));
        tag.putString("authority", style.defaultAuthority());
        tag.putString("nationality", style.defaultNationality());
        tag.putString("placeOfIssue", style.defaultPlace());
        tag.putString("issueDate", DateUtil.today());
        tag.putString("expiryDate", style.permanent() ? DateUtil.PERMANENT
                : DateUtil.plusYears(DateUtil.today(), style.validityYears()));
        if (kind == DocumentKind.PASSPORT) {
            tag.putBoolean("includeVisa", lockIncludeVisa ? lockedIncludeVisa : false);
            tag.putString("visaCountry",
                    visaCountryLocked() ? lockedVisaCountryValue() : style.defaultNationality());
            tag.putString("visaType", (lockVisaType ? lockedVisaType : VisaType.TOURIST).id());
            tag.putString("visaCode", lockVisaCode ? lockedVisaCode : "");
            tag.putString("visaEntries", lockVisaEntries ? String.valueOf(lockedVisaEntries) : "1");
            tag.putString("visaName", lockVisaName ? lockedVisaName : "");
        }
        return tag;
    }

    /**
     * Locked values are authoritative on the server; mirror them into the form
     * cache before the widgets are created so presets/style changes cannot
     * display an obsolete value.
     */
    private void applyLockedValues() {
        if (lockDocumentName) {
            formCache.putString("displayName", lockedDocumentName);
        }
        if (lockAuthority) {
            formCache.putString("authority", lockedAuthority);
        }
        if (lockStyle) {
            formCache.putString("style", style.id());
        }
        if (lockPlace) {
            formCache.putString("placeOfIssue", lockedPlace);
        }
        if (lockIssueDate) {
            formCache.putString("issueDate", lockedIssueValue());
        }
        if (lockNationality) {
            formCache.putString("nationality", lockedNationality);
        } else if (!machineNationality.isBlank()) {
            // 26.3 round 12: the machine's country selector decides the nationality.
            formCache.putString("nationality", machineNationality);
        }
        if (lockValidity) {
            formCache.putString("expiryDate", autoExpiry(formCache.getString("issueDate")));
        }
        if (kind == DocumentKind.PASSPORT) {
            if (visaCountryLocked()) {
                formCache.putString("visaCountry", lockedVisaCountryValue());
            }
            if (lockVisaType) {
                formCache.putString("visaType", lockedVisaType.id());
            }
            if (lockVisaCode) {
                formCache.putString("visaCode", lockedVisaCode);
            }
            if (lockIncludeVisa) {
                includeVisa = lockedIncludeVisa;
                formCache.putBoolean("includeVisa", includeVisa);
            }
            if (lockVisaEntries) {
                formCache.putString("visaEntries", String.valueOf(lockedVisaEntries));
            }
            if (lockVisaName) {
                formCache.putString("visaName", lockedVisaName);
            }
            if (formCache.getString("visaEntries").isBlank()) {
                formCache.putString("visaEntries", "1");
            }
        }
    }

    /** Locked issue date, or today when the administrator left the lock value blank. */
    private String lockedIssueValue() {
        return lockedIssueDate == null || lockedIssueDate.isBlank() ? DateUtil.today() : lockedIssueDate;
    }

    private String autoExpiry(String issueDate) {
        return lockedValidityMonths <= 0 ? DateUtil.PERMANENT
                : DateUtil.plusMonths(issueDate, lockedValidityMonths);
    }

    private String visaIssueValue() {
        return lockVisaIssueDate ? lockedVisaIssueDate : issueValue();
    }

    private String visaExpiryValue() {
        if (lockVisaValidity) {
            return lockedVisaValidityMonths <= 0 ? DateUtil.PERMANENT
                    : DateUtil.plusMonths(visaIssueValue(), lockedVisaValidityMonths);
        }
        return expiryValue();
    }

    private String issueValue() {
        String value = issue != null ? issue.getValue() : formCache.getString("issueDate");
        return DateUtil.isValid(value) ? value : DateUtil.today();
    }

    private String expiryValue() {
        return expiry != null ? expiry.getValue() : formCache.getString("expiryDate");
    }

    /** 26.4: applies a layout chosen from the dropdown (same defaults as before). */
    private void selectStyle(String styleId) {
        DocStyle next = DocStyle.byIdOrNull(styleId);
        if (lockStyle || next == null || next.kind() != kind || next == style) {
            return;
        }
        captureCurrentTab();
        selectedTemplateId = "";
        style = next;
        formCache.putString("style", style.id());
        formCache.putString("authority", style.defaultAuthority());
        formCache.putString("nationality", lockNationality ? lockedNationality : style.defaultNationality());
        formCache.putString("placeOfIssue", style.defaultPlace());
        formCache.putString("address", DocumentFactory.defaultAddress(style));
        if (!lockValidity) {
            formCache.putString("expiryDate", style.permanent() ? DateUtil.PERMANENT
                    : DateUtil.plusYears(formCache.getString("issueDate"), style.validityYears()));
        } else {
            formCache.putString("expiryDate", autoExpiry(formCache.getString("issueDate")));
        }
        if (kind == DocumentKind.PASSPORT) {
            if (!visaCountryLocked()) {
                formCache.putString("visaCountry", style.defaultNationality());
            }
            if (!lockVisaType) {
                formCache.putString("visaType", visaType.id());
            }
        }
        rebuildWidgets();
    }

    /** Value of the layout dropdown: the chosen registry template or the layout id. */
    private String selectedLayoutValue() {
        for (CredentialRegistryData.Template template : registryTemplates) {
            if (template.id().equals(selectedTemplateId) && template.style().equals(style.id())) {
                return IssuerChoices.valueOf(template);
            }
        }
        return style.id();
    }

    /** 26.4: a dropdown value is either a layout id or {@code template:<id>}. */
    private void selectLayout(String value) {
        CredentialRegistryData.Template template = IssuerChoices.resolve(value, registryTemplates);
        if (template != null) {
            applyTemplateChoice(template);
            return;
        }
        selectStyle(value);
    }

    /**
     * 26.4 fix: applies a registry template to the form. The template's layout is
     * used as the document layout and its colour / icon / issuing country are put
     * on the form cache, so the issued document is written with exactly the
     * template the operator picked.
     */
    private void applyTemplateChoice(CredentialRegistryData.Template template) {
        DocStyle templateStyle = DocStyle.byIdOrNull(template.style());
        if (templateStyle == null || templateStyle.kind() != kind || formCache == null) {
            return;
        }
        captureCurrentTab();
        selectedTemplateId = template.id();
        if (!lockStyle) {
            style = templateStyle;
        }
        // Template defaults: style + issuing country + cover colour + icon.
        template.applyTo(formCache);
        formCache.putString("style", style.id());
        formCache.putString("templateId", template.id());
        formCache.putString("authority", templateStyle.defaultAuthority());
        formCache.putString("nationality", lockNationality ? lockedNationality : templateStyle.defaultNationality());
        formCache.putString("placeOfIssue", templateStyle.defaultPlace());
        formCache.putString("address", DocumentFactory.defaultAddress(templateStyle));
        if (!lockValidity) {
            formCache.putString("expiryDate", templateStyle.permanent() ? DateUtil.PERMANENT
                    : DateUtil.plusYears(formCache.getString("issueDate"), templateStyle.validityYears()));
        } else {
            formCache.putString("expiryDate", autoExpiry(formCache.getString("issueDate")));
        }
        if (kind == DocumentKind.PASSPORT) {
            if (!visaCountryLocked()) {
                formCache.putString("visaCountry", templateStyle.defaultNationality());
            }
            if (!lockVisaType) {
                formCache.putString("visaType", visaType.id());
            }
        }
        rebuildWidgets();
    }

    // ------------------------------------------------------------- form capture

    /** Snapshots the visible page's widgets so a tab switch/issue loses nothing. */
    private void captureCurrentTab() {
        if (formCache == null) {
            return;
        }
        switch (tab) {
            case TAB_APPLICANT -> captureApplicant();
            case TAB_DOCUMENT -> captureDocument();
            case TAB_VISA -> captureVisa();
            default -> {
            }
        }
    }

    private void captureApplicant() {
        if (name == null || formCache == null) {
            return;
        }
        formCache.putString("style", style.id());
        formCache.putString("displayName", displayName.getValue());
        formCache.putString("name", name.getValue());
        formCache.putString("nameLatin", nameLatin.getValue());
        if (!lockNumber) {
            formCache.putString("number", number.getValue());
        }
        formCache.putString("sex", sex);
        if (birth != null) {
            // Always a valid YYYY-MM-DD: the three dropdowns cannot produce anything else.
            formCache.putString("birthDate", birth.value());
        }
        if (nationality != null) {
            formCache.putString("nationality", nationality.getValue());
        }
    }

    private void captureDocument() {
        if (formCache == null) {
            return;
        }
        formCache.putString("style", style.id());
        if (authority != null) {
            formCache.putString("authority", authority.getValue());
        }
        if (place != null) {
            formCache.putString("placeOfIssue", place.getValue());
        }
        if (issue != null) {
            formCache.putString("issueDate", issue.getValue());
        }
        if (expiry != null) {
            formCache.putString("expiryDate", expiry.getValue());
        }
        if (address != null) {
            formCache.putString("address", address.getValue());
        }
    }

    private void captureVisa() {
        if (formCache == null || kind != DocumentKind.PASSPORT || visaCountry == null) {
            return;
        }
        formCache.putBoolean("includeVisa", lockIncludeVisa ? lockedIncludeVisa : includeVisa);
        formCache.putString("visaCountry", visaCountry.getValue());
        formCache.putString("visaType", visaType.id());
        formCache.putString("visaCode", visaCode == null ? "" : visaCode.getValue());
        formCache.putString("visaEntries", visaEntries == null ? "1" : visaEntries.getValue());
        formCache.putString("visaName", visaName == null ? "" : visaName.getValue());
    }

    /**
     * Preset library = this machine's saved presets followed by the global
     * registry templates of the matching document kind. A registry entry is
     * marked {@code registry=true} so it is read-only here (it is deleted from
     * the registry screen), while applying it works exactly like a local preset.
     */
    private List<CompoundTag> presets() {
        ListTag list = payload.getList("presets", Tag.TAG_COMPOUND);
        List<CompoundTag> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            result.add(list.getCompound(i));
        }
        for (CredentialRegistryData.Template template : registryTemplates) {
            DocStyle templateStyle = DocStyle.byId(template.style());
            if (templateStyle.kind() != kind) {
                continue;
            }
            CompoundTag values = defaults();
            values.putString("style", templateStyle.id());
            values.putString("authority", templateStyle.defaultAuthority());
            values.putString("nationality", templateStyle.defaultNationality());
            values.putString("placeOfIssue", templateStyle.defaultPlace());
            values.putString("address", DocumentFactory.defaultAddress(templateStyle));
            CompoundTag entry = new CompoundTag();
            entry.putString("name", template.name());
            entry.putBoolean("registry", true);
            template.applyTo(values);
            entry.put("values", values);
            result.add(entry);
        }
        return result;
    }

    private void selectPreset(int index) {
        presetIndex = Math.max(0, index);
        if (presetList != null) {
            presetList.setSelectedIndex(presetIndex);
        }
        if (presetName != null) {
            presetName.setValue(currentPresetName());
        }
    }

    private void applyPreset() {
        List<CompoundTag> list = presets();
        if (list.isEmpty()) {
            return;
        }
        CompoundTag entry = list.get(Math.floorMod(presetIndex, list.size()));
        CompoundTag values = entry.getCompound("values");
        DocStyle loaded = DocStyle.byId(values.getString("style"));
        if (!lockStyle && loaded.kind() == kind) {
            style = loaded;
        }
        formCache = values.copy();
        formCache.putString("style", style.id());
        // A saved preset is a plain layout, not the registry template that may
        // have been selected before (26.4).
        selectedTemplateId = "";
        sex = formCache.getString("sex").isBlank() ? "M" : formCache.getString("sex");
        if (kind == DocumentKind.PASSPORT) {
            includeVisa = lockIncludeVisa ? lockedIncludeVisa : formCache.getBoolean("includeVisa");
            if (!lockVisaType) {
                visaType = VisaType.byId(formCache.getString("visaType"));
            }
            if (visaCountryLocked()) {
                formCache.putString("visaCountry", lockedVisaCountryValue());
            }
            if (lockVisaEntries) {
                formCache.putString("visaEntries", String.valueOf(lockedVisaEntries));
            }
            if (lockVisaName) {
                formCache.putString("visaName", lockedVisaName);
            }
            formCache.putBoolean("includeVisa", includeVisa);
            if (formCache.getString("visaEntries").isBlank()) {
                formCache.putString("visaEntries", "1");
            }
        }
        // Show the applied values on the first page instead of leaving the user
        // on a preset list whose fields are not visible.
        tab = TAB_APPLICANT;
        rebuildWidgets();
    }

    private void savePreset() {
        captureCurrentTab();
        CompoundTag data = formCache.copy();
        String name = presetName == null ? currentPresetName() : presetName.getValue();
        data.putString("presetName", name.isBlank()
                ? Component.translatable("screen.veriborder.issuer.default_preset").getString() : name);
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "save_preset", data, -1));
    }

    private void deletePreset() {
        List<CompoundTag> list = presets();
        CompoundTag selected = presetIndex >= 0 && presetIndex < list.size() ? list.get(presetIndex) : null;
        if (selected != null && selected.getBoolean("registry")) {
            // Global templates are removed from the registry screen, not here.
            return;
        }
        CompoundTag data = new CompoundTag();
        String name = presetName == null ? currentPresetName() : presetName.getValue();
        data.putString("presetName", name);
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "delete_preset", data, -1));
    }

    private void issue() {
        captureCurrentTab();
        CompoundTag data = formCache.copy();
        data.putString("style", style.id());
        data.putString("sex", sex);
        // The DOCUMENT name lock is still a server payload flag the form mirrors.
        // Number/MRZ/entry/permanent-code policy is device configuration and is
        // resolved server-side from the block entity, never from this form.
        data.putBoolean("lockDocumentName", lockDocumentName);
        // 26.4: the trusted locked title travels only from the block entity to the
        // factory (DocumentFactory.LOCKED_DISPLAY_NAME). The client must never
        // send it, so a forged payload cannot rename a locked document.
        data.remove("lockedDisplayName");
        if (lockNumber) {
            data.putString("number", "");
        }
        if (kind == DocumentKind.PERMIT) {
            // 26.2: the permit carries a built-in endorsement. The type is always
            // sent - a locked terminal's value here is advisory (the server
            // re-reads the lock from the block entity), and "none" is the only way
            // to ask for no endorsement on an unlocked machine.
            data.putString("initialEndorsementType", lockEndorsementType
                    ? lockedEndorsementType.id() : endorsementChoices[endorsementChoice]);
        }
        if (kind == DocumentKind.PASSPORT) {
            data.putBoolean("includeVisa", lockIncludeVisa ? lockedIncludeVisa : includeVisa);
            data.putString("visaCountry", visaCountry != null ? visaCountry.getValue()
                    : formCache.getString("visaCountry"));
            data.putString("visaType", visaType.id());
            data.putString("visaCode", visaCode != null ? visaCode.getValue().trim()
                    : formCache.getString("visaCode"));
            data.putInt("visaEntries", lockVisaEntries ? lockedVisaEntries
                    : parseEntries(visaEntries != null ? visaEntries.getValue()
                    : formCache.getString("visaEntries")));
            data.putString("visaName", visaName != null ? visaName.getValue().trim()
                    : formCache.getString("visaName"));
        }
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "issue_document", data, -1));
    }

    private static int parseEntries(String value) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (RuntimeException ignored) {
            return 1;
        }
    }

    private static String sexKey(String sex) {
        return switch (sex) {
            case "F" -> "female";
            case "X" -> "x";
            default -> "male";
        };
    }

    // ------------------------------------------------------------------ render
    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, Component.translatable(device.translationKey()).getString(),
                Component.translatable(device.descriptionKey()).getString());
        tabs.draw(graphics, font, accent);
        switch (tab) {
            case TAB_DOCUMENT -> renderDocumentTab(graphics);
            case TAB_VISA -> renderVisaTab(graphics);
            case TAB_PRESETS -> renderPresetsTab(graphics);
            default -> renderApplicantTab(graphics);
        }
        if (lockValidity) {
            String validity = Ui.validityText(lockedValidityMonths);
            String expiryText = tab == TAB_VISA ? visaExpiryValue() : expiryValue();
            Ui.clipped(graphics, Component.translatable("screen.veriborder.issuer.validity_locked",
                    validity, expiryText), left + 24, top + 378, 472, Ui.DIM);
        }
        if (kind != DocumentKind.PERMIT) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.issuer.hint"), left + 164,
                    top + 380, 222, Ui.DIM);
        } else if (lockEndorsementType) {
            // The permit's built-in endorsement is forced by this terminal.
            Ui.clipped(graphics, Component.translatable("screen.veriborder.issuer.endorsement_locked",
                    Component.translatable(lockedEndorsementType.translationKey()).getString()),
                    left + 164, top + 380, 222, Ui.AMBER);
        }
    }

    private void renderApplicantTab(GuiGraphics graphics) {
        DocumentData preview = previewDocument();
        Ui.documentCard(graphics, left + 24, top + 216, 220, 112, preview);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.issuer.hint"), left + 258,
                top + 222, 238, Ui.MUTED);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.name_hint"), left + 258,
                top + 242, 238, Ui.DIM);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.issuer.effective",
                effectiveName()), left + 258, top + 260, 238, Ui.CYAN);
        if (lockDocumentName) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.name_locked_hint"),
                    left + 258, top + 274, 238, Ui.AMBER);
        }
    }

    private void renderDocumentTab(GuiGraphics graphics) {
        graphics.fill(left + 24, top + 208, left + 496, top + 209, Ui.BORDER);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.issuer.effective",
                effectiveName()), left + 24, top + 214, 300, Ui.CYAN);
        DocumentData preview = previewDocument();
        Ui.documentCard(graphics, left + 24, top + 232, 220, 108, preview);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.issuer.hint"), left + 258,
                top + 236, 238, Ui.MUTED);
        if (lockValidity) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.lock.validity_line",
                    Ui.validityText(lockedValidityMonths), expiryValue()),
                    left + 258, top + 256, 238, Ui.AMBER);
        }
        Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.edit_footer"),
                left + 258, top + 276, 238, Ui.DIM);
    }

    private void renderVisaTab(GuiGraphics graphics) {
        DocumentData preview = previewDocument();
        Ui.documentCard(graphics, left + 24, top + 208, 180, 104, preview);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.issuer.visa_issue", visaIssueValue()),
                left + 216, top + 214, 280, lockVisaIssueDate ? Ui.AMBER : Ui.DIM);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.issuer.visa_expiry", visaExpiryValue()),
                left + 216, top + 228, 280, lockVisaValidity ? Ui.AMBER : Ui.DIM);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.issuer.visa_hint"),
                left + 216, top + 244, 280, Ui.DIM);
    }

    private void renderPresetsTab(GuiGraphics graphics) {
        presetList.draw(graphics, font, (g, entry, index, x, y, width, height, selected, hovered) -> {
            CompoundTag values = entry.getCompound("values");
            DocStyle entryStyle = DocStyle.byId(values.getString("style"));
            graphics.fill(x, y, x + width, y + height, selected ? 0xFF243A50 : hovered ? 0xFF1A2A3B
                    : 0x66101A28);
            if (selected) {
                graphics.fill(x, y, x + 3, y + height, accent);
            }
            graphics.drawString(font, Ui.ellipsis(entry.getString("name"), 220), x + 8, y + 3, Ui.TEXT,
                    false);
            graphics.drawString(font, Ui.ellipsis(Component.translatable(entryStyle.displayNameKey())
                    .getString(), 150), x + 8, y + 15, Ui.MUTED, false);
            graphics.drawString(font, Ui.ellipsis(values.getString("name"), width - 250), x + 236,
                    y + 9, Ui.DIM, false);
            if (entry.getBoolean("registry")) {
                String badge = Component.translatable("screen.veriborder.issuer.registry_badge").getString();
                int badgeWidth = font.width(badge) + 8;
                if (width > badgeWidth + 250) {
                    graphics.fill(x + width - badgeWidth - 4, y + 5, x + width - 4, y + 17, 0x552FC6B7);
                    graphics.drawString(font, badge, x + width - badgeWidth, y + 7, Ui.CYAN, false);
                }
            }
        }, mouseUiX, mouseUiY);
    }

    private double mouseUiX = -1;
    private double mouseUiY = -1;

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.mouseUiX = toUiX(mouseX);
        this.mouseUiY = toUiY(mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String effectiveName() {
        String custom = displayName != null ? displayName.getValue() : formCache.getString("displayName");
        if (custom == null || custom.isBlank()) {
            return Component.translatable(style.displayNameKey()).getString();
        }
        return custom.trim();
    }

    /** Preview-only document: never signed or saved, just the live form values. */
    private DocumentData previewDocument() {
        CompoundTag tag = formCache.copy();
        tag.putString("style", style.id());
        tag.putString("kind", kind.name());
        if (displayName != null) {
            tag.putString("displayName", displayName.getValue());
        }
        if (name != null) {
            tag.putString("name", name.getValue());
        }
        if (number != null) {
            tag.putString("number", number.getValue());
        }
        if (issue != null) {
            tag.putString("issueDate", issue.getValue());
        }
        if (expiry != null) {
            tag.putString("expiryDate", expiry.getValue());
        }
        if (sex != null) {
            tag.putString("sex", sex);
        }
        if (nationality != null) {
            tag.putString("nationality", nationality.getValue());
        }
        if (birth != null) {
            tag.putString("birthDate", birth.value());
        }
        if (authority != null) {
            tag.putString("authority", authority.getValue());
        }
        if (place != null) {
            tag.putString("placeOfIssue", place.getValue());
        }
        if (address != null) {
            tag.putString("address", address.getValue());
        }
        return DocumentData.fromTag(tag);
    }

    // ------------------------------------------------------------------- input
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        if (tabs != null) {
            int hit = tabs.hit(uiX, uiY);
            if (hit >= 0) {
                int logical = logicalTab(hit);
                if (logical != tab) {
                    selectTab(logical);
                }
                return true;
            }
        }
        if (tab == TAB_PRESETS && presetList != null && presetList.mouseClicked(uiX, uiY)) {
            if (presetList.selectedIndex() >= 0) {
                selectPreset(presetList.selectedIndex());
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (dropdownScroll(toUiX(mouseX), toUiY(mouseY), delta)) {
            return true;
        }
        if (tab == TAB_PRESETS && presetList != null
                && presetList.mouseScrolled(toUiX(mouseX), toUiY(mouseY), delta)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }
}
