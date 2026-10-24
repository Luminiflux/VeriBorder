package com.veriborder.client.screen;

import com.veriborder.block.DeviceBlockEntity;
import com.veriborder.block.DeviceType;
import com.veriborder.client.BetterButton;
import com.veriborder.client.ClientRegistryCache;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.client.ui.CodeListBox;
import com.veriborder.client.ui.CodeListModel;
import com.veriborder.client.ui.CountryPicker;
import com.veriborder.client.ui.Dropdown;
import com.veriborder.client.ui.FlowLayout;
import com.veriborder.client.ui.PagedList;
import com.veriborder.client.ui.TabStrip;
import com.veriborder.doc.DateUtil;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentKind;
import com.veriborder.doc.EndorsementType;
import com.veriborder.doc.MrzUtil;
import com.veriborder.doc.VisaType;
import com.veriborder.network.DeviceActionPacket;
import com.veriborder.network.ModNetwork;
import com.veriborder.registry.CredentialRegistryData;
import com.veriborder.registry.IssuerChoices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Brush-only terminal configuration. Reached exclusively through
 * {@code DeviceService.openConfig} ("config" packet), which requires the
 * Device Brush in either hand.
 *
 * <p>The lock section is laid out as a compact grid: each item is a small
 * on/off toggle with its locked value underneath. Passport issuers now carry
 * twelve locks (six document locks plus six attached-visa locks), so that
 * device uses a three-column grid; every other issuer keeps two columns. All
 * layouts stay inside the 480px design height, and {@link VeriScreen} scales
 * the whole panel down automatically when the GUI scale leaves less room.</p>
 */
public class DeviceConfigScreen extends VeriScreen {
    // 26.3 removed the "all" gate mode: an entry gate now always requires a
    // visa/endorsement (or permanent) credential. Legacy devices that still
    // store "all" are normalised to "both" on read and never offered again.
    private static final String[] GATE_MODES = {
            "visa", "endorsement", "both"
    };

    // 26.3 tabs: Device always exists; issuers add Locks (and document issuers
    // a Policy page). Heights are now uniform per device class because the tab
    // strip removed the need for the old very tall single page.
    private static final int TAB_DEVICE = 0;
    private static final int TAB_LOCKS = 1;
    private static final int TAB_POLICY = 2;
    private static final int ISSUER_HEIGHT = 430;
    private static final int BINDING_GATE_HEIGHT = 560;
    private static final int SIMPLE_HEIGHT = 300;
    /** Exit gate: dedicated exit-code list + advanced permanent codes. */
    private static final int EXIT_GATE_HEIGHT = 560;

    private static final int LOCK_ITEM_PITCH = 44;
    private static final int LOCK_VALUE_OFFSET = 28;
    private static final int LOCK_COLUMN_WIDTH = 190;
    private static final int PASSPORT_COLUMN_WIDTH = 126;
    /** Height of the label chip drawn above an EditBox (see {@code VeriScreen}). */
    private static final int LABEL_HEIGHT = 12;

    /** 26.2 issuer-policy sub-page (its own compact permanent-code row list). */
    private static final int POLICY_CODE_ROW_HEIGHT = 16;

    private final DeviceType device;
    private final BlockPos pos;
    private final int slot;
    private final CompoundTag payload;
    private final boolean bindingGate;
    private final boolean issuer;
    private final boolean selfKiosk;
    private final boolean permitIssuer;
    private final boolean documentIssuer;
    private final boolean visaIssuer;
    private final boolean endorsementIssuer;
    private final boolean passportIssuer;
    private final DocumentKind documentKind;
    private final List<DocStyle> documentStyles;
    private final int firstFieldY;
    private int lockGridStartY;
    /** Entry-stage recorded gate codes (list + scroll window + validation). */
    private final CodeListModel gateCodes = new CodeListModel(3);
    /** Exit gate: dedicated exit codes (empty = fall back to gateCodes). */
    private final CodeListModel exitCodes = new CodeListModel(3);
    /** 26.3 Advanced: allowed issuing-country codes for the entry stage. */
    private final List<String> entryCountryCodes = new ArrayList<>();
    /** 26.3 Advanced: allowed issuing-country codes for the exit stage. */
    private final List<String> exitCountryCodes = new ArrayList<>();
    /** Registered countries/regions offered by the selector (payload snapshot). */
    private final List<CredentialRegistryData.Country> registryCountries = new ArrayList<>();
    /** 26.4: registry templates of this machine's kind, merged into the layout lock. */
    private final List<CredentialRegistryData.Template> registryTemplates = new ArrayList<>();
    private CodeListBox gateCodeBox;
    private CodeListBox exitCodeBox;
    /** Floating whitelist pickers of an entry/exit gate (26.4 repair). */
    private CountryPicker entryPicker;
    private CountryPicker exitPicker;
    private EditBox exitCodeField;
    /** Gate page row tops, filled by {@link #buildGatePage(int)}. */
    private int gateHintY;
    private int gateHint2Y;
    private int gateModeRowY;
    private int gateCodeTitleY;
    private int gateInputY;
    private int gateListY;
    private int gateButtonsY;
    private int gateEntryPickerY;
    private int gateExitPickerY;
    private int gateBehaviorY;
    private int gateBehaviorHintY;
    private int gateIdentityY;

    private EditBox port;
    private EditBox country;
    private EditBox officer;
    private EditBox airportCountry;
    private EditBox gateCode;
    private EditBox lockedName;
    private EditBox lockedAuthorityField;
    private EditBox lockedNationalityField;
    private EditBox issuedCodeField;
    private EditBox lockedEndorsementCodeField;
    private EditBox lockedPlaceField;
    private EditBox lockedIssueDateField;
    private EditBox lockedValidityMonthsField;
    private EditBox lockedVisaCodeField;
    private EditBox lockedVisaEntriesField;
    private EditBox lockedVisaCountryField;
    private EditBox lockedVisaIssueDateField;
    private EditBox lockedVisaValidityMonthsField;
    private EditBox lockedVisaNameField;
    private EditBox overstayDaysField;
    private EditBox issuerCountryField;
    /** 26.3 round 12: the single issuing-country selector (nationality + MRZ code). */
    private Dropdown issuerChoice;
    private BetterButton autoStampButton;

    // ---- 26.2 issuer policy (administrator-only; applicant screen has none) ----
    /** 26.3: Device / Locks / Policy pages of the configuration screen. */
    private int tab = TAB_DEVICE;
    private TabStrip tabs;
    private PagedList<String> policyCodeList;
    private boolean policyLockNumber = true;
    private boolean policyIssueEntry = true;
    private String policyRegion = "";
    private String policySequence = "";
    private final List<String> issueCodes = new ArrayList<>();
    private int policyCodesSelected = -1;
    private String policyCodeError = "";
    private EditBox policyCodeInput;
    private EditBox policyRegionField;
    private EditBox policySequenceField;
    /** 26.3: permanent issue codes live in a collapsed Advanced section. */
    private boolean policyAdvancedOpen;
    /**
     * Snapshot of the editable text fields, captured before switching to the
     * policy sub-page so unsaved main-page edits survive the rebuild.
     */
    private CompoundTag draft;
    /** Whether the region/sequence policy fields are meaningful (mainland ID). */
    private final boolean regionPolicy;
    /** Permanent codes and the issue-entry state belong to passport/permit issuers. */
    private final boolean policyCodesVisible;
    private final boolean policyEntryVisible;

    private String gateMode = "both";
    /** 26.3 registry: issuing-country code of an issuer machine (3 letters). */
    private String issuerCountry = "";
    private boolean lockDocumentName;
    private boolean lockAuthority;
    /** 26.2: force the holder nationality on every document this issuer creates. */
    private boolean lockNationality;
    private boolean lockEndorsementCode;
    private boolean lockStyle;
    private boolean lockPlace;
    private boolean lockIssueDate;
    private boolean lockValidity;
    private boolean lockVisaType;
    private boolean lockVisaCode;
    private boolean lockEndorsementType;
    private boolean lockIncludeVisa;
    private boolean includeVisaLocked;
    private boolean lockVisaEntries;
    private boolean lockVisaCountry;
    private boolean lockVisaIssueDate;
    private boolean lockVisaValidity;
    private boolean lockVisaName;
    private boolean autoStamp;
    private DocStyle lockedStyle;
    /** 26.4: dropdown value of the layout lock (a layout id or template:&lt;id&gt;). */
    private String lockedStyleChoice = "";
    /** 26.4: registry template behind the locked layout ("" for a plain layout). */
    private String lockedStyleTemplate = "";
    private VisaType lockedVisaType = VisaType.TOURIST;
    private EndorsementType lockedEndorsementType = EndorsementType.ONE_YEAR_MULTI;
    /** 26.2 month precision: locked document validity in months (0 = permanent). */
    private int lockedValidityMonths;
    private int lockedVisaEntries = 1;
    /** 26.2 month precision: locked attached-visa validity in months (0 = permanent). */
    private int lockedVisaValidityMonths = 12;
    private int overstayDays = DeviceBlockEntity.DEFAULT_OVERSTAY_DAYS;

    /**
     * Opens the configuration GUI for a device. A dual-clearance gate has no
     * generic configuration page: it opens the shared dual-gate editor in device
     * mode, so the two-stage rules (exit codes, entry codes, advanced permanent
     * exemptions) have exactly one implementation shared with the brush.
     */
    public static void open(DeviceType device, BlockPos pos, CompoundTag payload, int slot) {
        if (device == DeviceType.DUAL_GATE) {
            Minecraft.getInstance().setScreen(new DualGateBrushScreen(device, pos, payload, slot));
            return;
        }
        Minecraft.getInstance().setScreen(new DeviceConfigScreen(device, pos, payload, slot));
    }

    public DeviceConfigScreen(DeviceType device, BlockPos pos, CompoundTag payload, int slot) {
        super(Component.translatable("screen.veriborder.config"), 440, heightFor(device));
        this.device = device;
        this.pos = pos;
        this.slot = slot;
        this.payload = payload;
        this.accent = device.accentColor();
        // Exit gates intentionally have no visa/endorsement binding.
        this.bindingGate = device.isGate() && device != DeviceType.EXIT_GATE;
        this.issuer = device.isIssuer();
        this.selfKiosk = device == DeviceType.SELF_VISA || device == DeviceType.SELF_ENDORSEMENT;
        this.permitIssuer = device == DeviceType.PERMIT_ISSUER;
        this.passportIssuer = device == DeviceType.PASSPORT_ISSUER;
        this.documentKind = switch (device) {
            case ID_ISSUER -> DocumentKind.ID_CARD;
            case PASSPORT_ISSUER -> DocumentKind.PASSPORT;
            case PERMIT_ISSUER -> DocumentKind.PERMIT;
            default -> null;
        };
        this.documentIssuer = documentKind != null;
        this.visaIssuer = device == DeviceType.VISA_ISSUER || device == DeviceType.SELF_VISA;
        this.endorsementIssuer = device == DeviceType.ENDORSEMENT_MACHINE
                || device == DeviceType.SELF_ENDORSEMENT;
        this.documentStyles = documentIssuer ? DocStyle.forKind(documentKind) : List.<DocStyle>of();
        this.gateMode = normalizeMode(payload.getString("gateMode"));
        this.autoStamp = !payload.contains("autoStamp") || payload.getBoolean("autoStamp");
        this.overstayDays = payload.contains("overstayDays")
                ? clampOverstay(payload.getInt("overstayDays")) : DeviceBlockEntity.DEFAULT_OVERSTAY_DAYS;
        this.lockDocumentName = payload.getBoolean("lockDocumentName");
        this.lockAuthority = payload.getBoolean("lockAuthority");
        // A blank locked nationality has no effect on the server, so the toggle
        // only reads as ON when a usable value is stored (same rule as lockPlace).
        String lockedNationality = payload.getString("lockedNationality").trim();
        this.lockNationality = payload.getBoolean("lockNationality") && !lockedNationality.isBlank();
        this.lockEndorsementCode = payload.getBoolean("lockEndorsementCode");
        readGateCodes();
        exitCodes.seed(readCodes("exitCodes"));
        readCodeList(entryCountryCodes, "entryCountryCodes");
        readCodeList(exitCountryCodes, "exitCountryCodes");
        readRegistryCountries(payload);
        // 26.4: the layout lock also offers this kind's registry templates.
        readRegistryTemplates(payload);

        this.lockedStyle = resolveDocumentStyle(payload.getString("lockedStyle"));
        this.lockStyle = payload.getBoolean("lockStyle") && lockedStyle != null;
        this.lockedStyleTemplate = payload.getString("lockedStyleTemplate").trim();
        // 26.4: reopen the dropdown on the template the layout lock came from, not
        // just on its base layout, so saving again keeps the same template.
        this.lockedStyleChoice = lockedStyleChoice(lockedStyle, lockedStyleTemplate);
        String place = payload.getString("lockedPlace").trim();
        this.lockPlace = payload.getBoolean("lockPlace") && !place.isBlank();
        // A blank locked issue date is meaningful: the terminal then issues on
        // "today", so the lock itself is what matters, not the stored value.
        this.lockIssueDate = payload.getBoolean("lockIssueDate");
        this.lockValidity = payload.getBoolean("lockValidity");
        // 26.2 month precision: the stored month key wins; a legacy year value
        // (older NBT) is migrated x12, and an unset lock starts from the layout's
        // own validity (all layouts declare years).
        this.lockedValidityMonths = payload.contains("lockedValidityMonths")
                ? Math.max(0, payload.getInt("lockedValidityMonths"))
                : payload.contains("lockedValidityYears")
                ? Math.max(0, payload.getInt("lockedValidityYears") * 12)
                : defaultValidityMonths();
        VisaType resolvedVisaType = resolveVisaType(payload.getString("lockedVisaType"));
        this.lockedVisaType = resolvedVisaType == null ? VisaType.TOURIST : resolvedVisaType;
        this.lockVisaType = payload.getBoolean("lockVisaType") && resolvedVisaType != null;
        String visaCode = payload.getString("lockedVisaCode").trim();
        this.lockVisaCode = payload.getBoolean("lockVisaCode")
                && ScreenCodes.isValid(visaCode);
        EndorsementType resolvedEndorsementType =
                resolveEndorsementType(payload.getString("lockedEndorsementType"));
        // 26.2: a permit issuer's unset initial-endorsement type is the built-in
        // three-month single-entry endorsement, so the dropdown shows the value
        // the machine actually issues.
        this.lockedEndorsementType = resolvedEndorsementType == null
                ? (permitIssuer ? EndorsementType.THREE_MONTH_SINGLE : EndorsementType.ONE_YEAR_MULTI)
                : resolvedEndorsementType;
        this.lockEndorsementType = payload.getBoolean("lockEndorsementType")
                && resolvedEndorsementType != null;

        // 26.1 attached-visa locks (passport issuer) plus the visa-label lock.
        this.lockIncludeVisa = payload.getBoolean("lockIncludeVisa");
        this.includeVisaLocked = payload.getBoolean("lockedIncludeVisa");
        this.lockVisaEntries = payload.getBoolean("lockVisaEntries");
        this.lockedVisaEntries = Math.max(0, payload.contains("lockedVisaEntries")
                ? payload.getInt("lockedVisaEntries") : 1);
        String visaCountry = payload.getString("lockedVisaCountry").trim();
        this.lockVisaCountry = payload.getBoolean("lockVisaCountry") && !visaCountry.isBlank();
        String visaIssue = payload.getString("lockedVisaIssueDate").trim();
        this.lockVisaIssueDate = payload.getBoolean("lockVisaIssueDate") && DateUtil.isValid(visaIssue);
        this.lockVisaValidity = payload.getBoolean("lockVisaValidity");
        this.lockedVisaValidityMonths = payload.contains("lockedVisaValidityMonths")
                ? Math.max(0, payload.getInt("lockedVisaValidityMonths"))
                : payload.contains("lockedVisaValidityYears")
                ? Math.max(0, payload.getInt("lockedVisaValidityYears") * 12)
                : 12;
        this.lockVisaName = payload.getBoolean("lockVisaName");

        // 26.2 issuer policy. These fields are ignored by non-issuer screens, so
        // they are simply seeded from the payload and only edited on the policy
        // sub-page of the three document issuers.
        this.policyLockNumber = !payload.contains("lockNumber") || payload.getBoolean("lockNumber");
        this.policyIssueEntry = !payload.contains("issueEntryState") || payload.getBoolean("issueEntryState");
        this.policyRegion = payload.getString("numberRegion").trim();
        this.policySequence = payload.getString("numberSequence").trim();
        this.issuerCountry = MrzUtil.countryCode(payload.getString("issuerCountry"));
        // Region/sequence are the mainland CN_ID number fields; show them for the
        // identity-card issuer (they are ignored by every other layout).
        this.regionPolicy = documentKind == DocumentKind.ID_CARD;
        // 26.2 per-machine policy trimming: an ID card is domestic (no MRZ, no
        // permanent codes, no entry state); a permit has no MRZ; only passports
        // use the MRZ issuing country.
        this.policyCodesVisible = documentKind == DocumentKind.PASSPORT
                || documentKind == DocumentKind.PERMIT;
        this.policyEntryVisible = policyCodesVisible;
        readIssueCodes();

        this.firstFieldY = 84;
        this.lockGridStartY = 210;
    }

    /** Tabbed heights: issuers keep a lock grid, binding gates a code list. */
    private static int heightFor(DeviceType device) {
        if (device.isIssuer()) {
            return ISSUER_HEIGHT;
        }
        if (device == DeviceType.EXIT_GATE) {
            return EXIT_GATE_HEIGHT;
        }
        if (device.isGate()) {
            return BINDING_GATE_HEIGHT;
        }
        return SIMPLE_HEIGHT;
    }

    @Override
    protected void build() {
        int x = left + 24;
        int footerY = top + imageHeight - 38;
        // 26.3 round 12: a configuration screen opened before the registry
        // snapshot arrived asks for it (gate whitelists and the issuing-country
        // selector both read the registry).
        ClientRegistryCache.requestIfEmpty();
        if (isPolicyPage()) {
            buildTabs(x);
            buildPolicyPage();
            addFooterButtons(x, footerY);
            return;
        }
        buildTabs(x);
        if (tab == TAB_LOCKS && issuer) {
            buildLocksTab(x);
        } else {
            buildDeviceTab(x);
        }
        addFooterButtons(x, footerY);
    }

    /** Shared 26.3 tab strip: Device, plus Locks/Policy on the relevant issuers. */
    private void buildTabs(int x) {
        tabs = TabStrip.at(x, top + 44, 392).size(20, 3);
        tabs.add("device", Component.translatable("tab.veriborder.config.device"));
        if (issuer) {
            tabs.add("locks", Component.translatable("tab.veriborder.config.locks"));
        }
        if (documentIssuer) {
            tabs.add("policy", Component.translatable("tab.veriborder.config.policy_tab"));
        }
        if (tab >= tabs.tabCount()) {
            tab = TAB_DEVICE;
        }
        tabs.setSelected(tab);
    }

    private void addFooterButtons(int x, int footerY) {
        addRenderableWidget(new BetterButton(x, footerY, 120, 20,
                Component.translatable("button.veriborder.save"), button -> save(), accent));
        addRenderableWidget(new BetterButton(x + 130, footerY, 100, 20,
                Component.translatable("button.veriborder.cancel"), button -> onClose(), Ui.MUTED, true));
    }

    private boolean isPolicyPage() {
        return issuer && documentIssuer && tab == TAB_POLICY;
    }

    private void buildDeviceTab(int x) {
        if (device.isGate()) {
            buildGatePage(x);
            return;
        }
        if (issuer) {
            // 26.3 round 12: ONE country selector per issuing machine. The selected
            // registry country decides both the document nationality and the
            // passport MRZ issuing country, so the old duplicate nationality /
            // country text fields are gone. Port/checkpoint, officer and airport
            // destination are gate/reader settings and no longer appear on an
            // issuing machine; their legacy NBT is kept but unused.
            ClientRegistryCache.requestIfEmpty();
            List<Dropdown.Option> options = new java.util.ArrayList<>();
            for (CredentialRegistryData.Country entry : registryCountries) {
                options.add(new Dropdown.Option(entry.code(),
                        entry.code() + "  " + entry.displayName(false)));
            }
            // 26.4: migrated from ChoiceField to the shared dropdown (searchable
            // once the registry has more than eight entries).
            issuerChoice = addDropdown(Dropdown.at(x, top + firstFieldY + 12, 392)
                    .labelKey("field.veriborder.issuer_country")
                    .panelBottom(top + imageHeight - 40)
                    .placeholder(Ui.labelText("screen.veriborder.registry.empty_countries",
                            "No countries/regions registered yet."))
                    .tooltip("field.veriborder.issuer_country")
                    .options(options)
                    .selected(issuerCountry)
                    .onChange(id -> issuerCountry = id));
            return;
        }
        int fieldStep = 36;
        port = addField(x, top + firstFieldY, 392, "field.veriborder.port",
                valueFor("port", payload.getString("port")));
        country = addField(x, top + firstFieldY + fieldStep, 392, "field.veriborder.country",
                valueFor("country", payload.getString("country")));
        officer = addField(x, top + firstFieldY + fieldStep * 2, 392, "field.veriborder.officer",
                valueFor("officer", payload.getString("officer")));
        airportCountry = addField(x, top + firstFieldY + fieldStep * 3, 392, "field.veriborder.airport_country",
                valueFor("airportCountry", payload.getString("airportCountry")), "hint.veriborder.airport");
    }

    /** Derived nationality of the selected issuing country ("" when none). */
    private String issuerNationality() {
        String code = issuerChoice == null ? issuerCountry : issuerChoice.selectedId();
        for (CredentialRegistryData.Country entry : registryCountries) {
            if (entry.code().equals(code)) {
                return entry.defaultNationality().isBlank() ? entry.displayName(true)
                        : entry.defaultNationality();
            }
        }
        return "";
    }

    /**
     * Gate page (EGATE / EXIT_GATE), rebuilt as a sequential flow in 26.4.
     *
     * <p>Rows are handed out by {@link FlowLayout}, so the inspection mode, the
     * recorded code list and the two whitelist pickers can never overlap; the
     * conditional mode row only exists on an entry gate. Both directions are
     * editable on every gate ({@code entryCountryCodes} and
     * {@code exitCountryCodes} travel in the same payload keys as the
     * dual-clearance editor), which is what the single-gate MRZ country setting
     * was missing.</p>
     */
    private void buildGatePage(int x) {
        boolean exitGate = device == DeviceType.EXIT_GATE;
        int width = 392;
        FlowLayout layout = FlowLayout.at(x, top + 44, width, 6);
        gateHintY = layout.row(10);
        gateHint2Y = layout.row(10);
        if (!exitGate) {
            gateModeRowY = layout.row(LABEL_HEIGHT + 18, 8);
            List<Dropdown.Option> modes = new ArrayList<>();
            for (String mode : GATE_MODES) {
                modes.add(new Dropdown.Option(mode,
                        Component.translatable("screen.veriborder.config.gate_mode." + mode).getString()));
            }
            addDropdown(Dropdown.at(x, gateModeRowY + LABEL_HEIGHT, width)
                    .labelKey("field.veriborder.gate_mode")
                    .options(modes)
                    .selected(gateMode)
                    .panelBottom(top + imageHeight - 40)
                    .tooltip("screen.veriborder.config.gate_hint")
                    .onChange(id -> gateMode = normalizeMode(id)));
        }
        gateCodeTitleY = layout.row(10);
        gateInputY = layout.row(LABEL_HEIGHT + 14);
        if (exitGate) {
            // 26.3 exit gate restore: the dedicated exit-code list is what the
            // gate enforces on departure; blank falls back to the gate codes.
            exitCodeField = addCodeField(x, gateInputY + LABEL_HEIGHT, 300, "field.veriborder.exit_codes");
            addRenderableWidget(new BetterButton(x + 308, gateInputY + LABEL_HEIGHT, 84, 18,
                    Component.translatable("button.veriborder.add"), button -> addExitCode(), Ui.GREEN, true));
        } else {
            gateCode = addCodeField(x, gateInputY + LABEL_HEIGHT, 300, "field.veriborder.gate_code");
            addRenderableWidget(new BetterButton(x + 308, gateInputY + LABEL_HEIGHT, 84, 18,
                    Component.translatable("button.veriborder.add"), button -> addGateCode(), Ui.GREEN, true));
        }
        CodeListModel codes = exitGate ? exitCodes : gateCodes;
        gateListY = layout.row(codes.height());
        if (exitGate) {
            exitCodeBox = new CodeListBox(x, gateListY, width, exitCodes,
                    Component.translatable("screen.veriborder.config.exit_codes_none"));
        } else {
            gateCodeBox = new CodeListBox(x, gateListY, width, gateCodes,
                    Component.translatable("screen.veriborder.config.gate_codes_none"));
        }
        gateButtonsY = layout.row(18);
        addRenderableWidget(new BetterButton(x, gateButtonsY, 120, 18,
                Component.translatable("button.veriborder.remove_last"), button -> {
                    if (exitGate) {
                        exitCodes.removeLast();
                    } else {
                        gateCodes.removeLast();
                    }
                }, Ui.AMBER, true));
        addRenderableWidget(new BetterButton(x + 130, gateButtonsY, 100, 18,
                Component.translatable("button.veriborder.clear_all"), button -> {
                    if (exitGate) {
                        exitCodes.clear();
                    } else {
                        gateCodes.clear();
                    }
                }, Ui.RED, true));
        // 26.4 repair: a gate only owns its own direction. The entry gate checks
        // who may come in, the exit gate checks who may leave; showing both
        // lists on one machine was confusing and let an admin configure a rule
        // that the machine would never read.
        if (!exitGate) {
            gateEntryPickerY = layout.row(CountryPicker.BUTTON_HEIGHT);
            entryPicker = CountryPicker.at(font, x, gateEntryPickerY, width, entryCountryCodes,
                            "checklist.veriborder.entry_whitelist")
                    .countries(registryCountries)
                    .bounds(top + 44, top + imageHeight - 40);
        } else {
            gateExitPickerY = layout.row(CountryPicker.BUTTON_HEIGHT);
            exitPicker = CountryPicker.at(font, x, gateExitPickerY, width, exitCountryCodes,
                            "checklist.veriborder.exit_whitelist")
                    .countries(registryCountries)
                    .bounds(top + 44, top + imageHeight - 40);
        }
        gateBehaviorY = layout.row(LABEL_HEIGHT + 18);
        autoStampButton = addRenderableWidget(new BetterButton(x, gateBehaviorY + LABEL_HEIGHT, 190, 18,
                autoStampLabel(), button -> {
                    autoStamp = !autoStamp;
                    button.setMessage(autoStampLabel());
                }, accent, true));
        overstayDaysField = addField(x + 202, gateBehaviorY + LABEL_HEIGHT, 190,
                "field.veriborder.overstay_days",
                valueFor("overstayDays", String.valueOf(overstayDays)),
                "hint.veriborder.days");
        overstayDaysField.setMaxLength(4);
        overstayDaysField.setFilter(value -> value.length() <= 4
                && value.chars().allMatch(character -> character >= '0' && character <= '9'));
        gateBehaviorHintY = layout.row(10);
        // Checkpoint identity: two compact columns instead of four stacked rows,
        // so the whole gate page fits without scrolling.
        gateIdentityY = layout.row(LABEL_HEIGHT + 14);
        int columnWidth = (width - 12) / 2;
        port = addField(x, gateIdentityY + LABEL_HEIGHT, columnWidth, "field.veriborder.port",
                valueFor("port", payload.getString("port")));
        country = addField(x + columnWidth + 6, gateIdentityY + LABEL_HEIGHT, columnWidth,
                "field.veriborder.country", valueFor("country", payload.getString("country")));
        int identityRowTwo = layout.row(LABEL_HEIGHT + 14);
        officer = addField(x, identityRowTwo + LABEL_HEIGHT, columnWidth, "field.veriborder.officer",
                valueFor("officer", payload.getString("officer")));
        airportCountry = addField(x + columnWidth + 6, identityRowTwo + LABEL_HEIGHT, columnWidth,
                "field.veriborder.airport_country",
                valueFor("airportCountry", payload.getString("airportCountry")), "hint.veriborder.airport");
    }

    /** True when the exit gate has no exit-code list (empty = unrestricted). */
    private String exitCodeModeKey() {
        return exitCodes.isEmpty()
                ? "screen.veriborder.config.exit_codes_unrestricted"
                : "screen.veriborder.config.exit_codes_hint";
    }

    /** Reads the registry country snapshot carried by the config payload. */
    private void readRegistryCountries(CompoundTag payload) {
        ListTag list = payload.getList("registryCountries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CredentialRegistryData.Country country =
                    CredentialRegistryData.Country.fromTag(list.getCompound(i));
            if (country.valid()) {
                registryCountries.add(country);
            }
        }
    }

    /**
     * 26.4: registry templates of this machine's credential kind, so a template
     * saved in the registry is selectable as the locked document layout too.
     */
    private void readRegistryTemplates(CompoundTag payload) {
        if (documentKind == null) {
            return;
        }
        ListTag list = payload.getList("registryTemplates", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CredentialRegistryData.Template template =
                    CredentialRegistryData.Template.fromTag(list.getCompound(i)).normalized();
            if (template.valid() && template.kind() == documentKind) {
                registryTemplates.add(template);
            }
        }
    }

    /**
     * Lock page: name/authority (and the 26.2 nationality) locks sit above the
     * compact grid; the grid keeps three columns for passport issuers (ten lock
     * cells need four rows) and two columns otherwise.
     */
    private void buildLocksTab(int x) {
        addRenderableWidget(new BetterButton(x, top + 84, 190, 18, lockNameLabel(), button -> {
            lockDocumentName = !lockDocumentName;
            button.setMessage(lockNameLabel());
        }, Ui.AMBER, true));
        addRenderableWidget(new BetterButton(x + 202, top + 84, 190, 18, lockAuthorityLabel(), button -> {
            lockAuthority = !lockAuthority;
            button.setMessage(lockAuthorityLabel());
        }, Ui.AMBER, true));
        lockedName = addField(x, top + 112, documentIssuer ? 190 : 392, "field.veriborder.locked_name",
                valueFor("lockedDocumentName", payload.getString("lockedDocumentName")),
                "hint.veriborder.lock_value");
        lockedAuthorityField = addField(x + (documentIssuer ? 202 : 0), top + (documentIssuer ? 112 : 144),
                documentIssuer ? 190 : 392, "field.veriborder.locked_authority",
                valueFor("lockedAuthority", payload.getString("lockedAuthority")),
                "hint.veriborder.lock_value");
        if (documentIssuer) {
            // 26.3 round 12: there is no separate "nationality lock" any more. The
            // single country selector on the Device tab decides both the holder
            // nationality and the MRZ issuing code; the legacy NBT keys are kept
            // but no longer displayed or applied.
            lockGridStartY = selfKiosk ? 178 : 180;
        } else {
            lockGridStartY = selfKiosk ? 200 : 180;
        }
        if (selfKiosk) {
            issuedCodeField = addField(x, top + 176, 392, "field.veriborder.issued_code",
                    valueFor("issuedCode", payload.getString("issuedCode")), "hint.veriborder.machine_code");
            issuedCodeField.setMaxLength(8);
            issuedCodeField.setFilter(ScreenCodes::digitsUpTo8);
        }

        int index = 0;
        if (documentIssuer) {
            index = addDocumentLocks(index);
            if (passportIssuer) {
                index = addPassportVisaLocks(index);
            }
        } else if (visaIssuer) {
            index = addVisaIssuerLocks(index);
        } else if (endorsementIssuer) {
            index = addEndorsementIssuerLocks(index);
        }

        if (permitIssuer) {
            // 26.2: the permit's built-in endorsement. The administrator picks the
            // type from the endorsement table and may lock it, so this machine
            // always issues permits carrying that exact endorsement.
            int ix = lockColumnX(index);
            int iy = lockRowY(index);
            index++;
            addLockToggle(ix, iy, this::lockEndorsementTypeLabel,
                    () -> lockEndorsementType = !lockEndorsementType);
            addDropdown(Dropdown.at(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth())
                    .height(16)
                    .options(endorsementTypeOptions())
                    .selected(lockedEndorsementType.id())
                    .tooltip(lockEndorsementTypeLabel())
                    .onChange(id -> lockedEndorsementType = EndorsementType.byId(id)));

            ix = lockColumnX(index);
            iy = lockRowY(index);
            index++;
            addLockToggle(ix, iy, this::lockEndorsementCodeLabel, () -> lockEndorsementCode = !lockEndorsementCode);
            lockedEndorsementCodeField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                    "field.veriborder.locked_endorsement_code",
                    valueFor("lockedEndorsementCode", payload.getString("lockedEndorsementCode")),
                    "hint.veriborder.code_8");
            lockedEndorsementCodeField.setMaxLength(8);
            lockedEndorsementCodeField.setFilter(ScreenCodes::digitsUpTo8);
        }
    }

    // -------------------------------------------------------- 26.2 issuer policy
    //
    // Number lock / region / sequence, the passport MRZ country, the issue-entry
    // state and the permanent issue codes are administrator configuration. 26.3
    // turns the old sub-page into a real tab next to Device/Locks; every page
    // captures its fields into the same draft, so Save stores all tabs at once.

    private Component policyLockNumberLabel() {
        return Component.translatable("screen.veriborder.config.lock_number."
                + (policyLockNumber ? "on" : "off"));
    }

    private Component policyEntryLabel() {
        return Component.translatable("screen.veriborder.config.entry_state."
                + (policyIssueEntry ? "on" : "off"));
    }

    /** Switches configuration tabs while keeping every edit made so far. */
    private void selectTab(int next) {
        captureFields();
        if (next == TAB_LOCKS && !issuer) {
            next = TAB_DEVICE;
        }
        if (next == TAB_POLICY && !documentIssuer) {
            next = TAB_DEVICE;
        }
        tab = Math.max(TAB_DEVICE, Math.min(next, TAB_POLICY));
        policyCodeError = "";
        rebuildWidgets();
    }

    /** Snapshots every editable text field so a page switch never loses input. */
    private void captureFields() {
        if (draft == null) {
            draft = new CompoundTag();
        }
        putValue(draft, "port", port);
        putValue(draft, "country", country);
        putValue(draft, "officer", officer);
        putValue(draft, "airportCountry", airportCountry);
        putValue(draft, "lockedDocumentName", lockedName);
        putValue(draft, "lockedAuthority", lockedAuthorityField);
        putValue(draft, "lockedNationality", lockedNationalityField);
        putValue(draft, "issuedCode", issuedCodeField);
        putValue(draft, "lockedEndorsementCode", lockedEndorsementCodeField);
        putValue(draft, "lockedPlace", lockedPlaceField);
        putValue(draft, "lockedIssueDate", lockedIssueDateField);
        putValue(draft, "lockedValidityMonths", lockedValidityMonthsField);
        putValue(draft, "lockedVisaCode", lockedVisaCodeField);
        putValue(draft, "lockedVisaEntries", lockedVisaEntriesField);
        putValue(draft, "lockedVisaCountry", lockedVisaCountryField);
        putValue(draft, "lockedVisaIssueDate", lockedVisaIssueDateField);
        putValue(draft, "lockedVisaValidityMonths", lockedVisaValidityMonthsField);
        putValue(draft, "lockedVisaName", lockedVisaNameField);
        putValue(draft, "overstayDays", overstayDaysField);
        capturePolicyFields();
    }

    private static void putValue(CompoundTag tag, String key, EditBox box) {
        if (box != null) {
            tag.putString(key, box.getValue());
        }
    }

    /** Initial widget value: the page-switch draft wins over the server payload. */
    private String valueFor(String key, String fallback) {
        return draft != null && draft.contains(key) ? draft.getString(key) : fallback;
    }

    /** Saved value: the latest captured draft wins over the server payload. */
    private String value(String key) {
        return draft != null && draft.contains(key) ? draft.getString(key) : payload.getString(key);
    }

    private void capturePolicyFields() {
        if (policyRegionField != null) {
            policyRegion = policyRegionField.getValue().trim();
        }
        if (policySequenceField != null) {
            policySequence = policySequenceField.getValue().trim();
        }
    }

    private void buildPolicyPage() {
        int x = left + 24;
        addRenderableWidget(new BetterButton(x, top + 84, 190, 18, policyLockNumberLabel(), button -> {
            policyLockNumber = !policyLockNumber;
            button.setMessage(policyLockNumberLabel());
        }, Ui.AMBER, true));
        if (policyEntryVisible) {
            addRenderableWidget(new BetterButton(x + 202, top + 84, 190, 18, policyEntryLabel(), button -> {
                policyIssueEntry = !policyIssueEntry;
                button.setMessage(policyEntryLabel());
            }, Ui.GREEN, true));
        }

        int fieldsY = top + 120;
        if (regionPolicy) {
            policyRegionField = addField(x, fieldsY, 190, "field.veriborder.region_code",
                    policyRegion, "hint.veriborder.region_6");
            policyRegionField.setMaxLength(6);
            policyRegionField.setFilter(value -> digitsOnly(value, 6));
            policySequenceField = addField(x + 202, fieldsY, 190, "field.veriborder.sequence",
                    policySequence, "hint.veriborder.sequence_2");
            policySequenceField.setMaxLength(2);
            policySequenceField.setFilter(value -> digitsOnly(value, 2));
        }
        if (passportIssuer) {
            // 26.3: there is no separate MRZ country control any more. The
            // issuing country/region selector on the Device tab writes the single
            // issuerCountry field, which the passport MRZ reads.
        }
        if (policyCodesVisible) {
            // 26.3: permanent issue codes are an advanced, case-by-case option.
            // They stay collapsed by default so the page leads with the number /
            // MRZ / entry-state policy instead of the exemption list.
            addRenderableWidget(new BetterButton(x, top + 150, 392, 18, policyAdvancedLabel(), button -> {
                policyAdvancedOpen = !policyAdvancedOpen;
                rebuildWidgets();
            }, Ui.PURPLE, true));
            if (policyAdvancedOpen) {
                buildPolicyCodes(x);
            }
        }
    }

    private Component policyAdvancedLabel() {
        return Component.translatable(policyAdvancedOpen
                ? "screen.veriborder.config.advanced.open" : "screen.veriborder.config.advanced.closed");
    }

    private void buildPolicyCodes(int x) {
        policyCodeInput = addField(x, top + 196, 150, "field.veriborder.exempt_codes", "",
                "hint.veriborder.code_8");
        policyCodeInput.setMaxLength(8);
        policyCodeInput.setFilter(ScreenCodes::digitsUpTo8);
        addRenderableWidget(new BetterButton(x + 156, top + 192, 70, 20,
                Component.translatable("button.veriborder.add"), button -> addPolicyCode(), Ui.GREEN, true));
        addRenderableWidget(new BetterButton(x + 232, top + 192, 76, 20,
                Component.translatable("button.veriborder.delete"), button -> deletePolicyCode(), Ui.AMBER, true));
        addRenderableWidget(new BetterButton(x + 314, top + 192, 78, 20,
                Component.translatable("button.veriborder.clear_all"), button -> clearPolicyCodes(), Ui.RED, true));
        policyCodeList = PagedList.<String>at(x, top + 222, 392, imageHeight - 222 - 50)
                .rowHeight(POLICY_CODE_ROW_HEIGHT).rowGap(1).accent(Ui.PURPLE)
                .emptyText(Component.translatable("screen.veriborder.config.policy_codes_empty"));
        policyCodeList.setItems(issueCodes);
        policyCodeList.setSelectedIndex(policyCodesSelected);
    }

    private static boolean digitsOnly(String value, int maxLength) {
        if (value == null || value.length() > maxLength) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private void readIssueCodes() {
        ListTag list = payload.getList("issueCodes", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            String value = list.getString(i).trim();
            if (ScreenCodes.isValid(value) && !issueCodes.contains(value)) {
                issueCodes.add(value);
            }
        }
    }

    private ListTag issueCodesTag() {
        ListTag list = new ListTag();
        for (String code : issueCodes) {
            list.add(StringTag.valueOf(code));
        }
        return list;
    }

    private void addPolicyCode() {
        String value = policyCodeInput == null ? "" : policyCodeInput.getValue().trim();
        if (!ScreenCodes.isValid(value)) {
            policyCodeError = "message.veriborder.code.invalid";
            return;
        }
        if (issueCodes.contains(value)) {
            policyCodeError = "message.veriborder.code.duplicate";
            return;
        }
        issueCodes.add(value);
        policyCodesSelected = issueCodes.size() - 1;
        policyCodeError = "";
        policyCodeInput.setValue("");
        rebuildWidgets();
    }

    private void deletePolicyCode() {
        if (issueCodes.isEmpty()) {
            return;
        }
        int index = policyCodesSelected >= 0 && policyCodesSelected < issueCodes.size()
                ? policyCodesSelected : issueCodes.size() - 1;
        issueCodes.remove(index);
        int remaining = issueCodes.size();
        policyCodesSelected = remaining <= 0 ? -1 : Math.min(index, remaining - 1);
        policyCodeError = "";
        rebuildWidgets();
    }

    private void clearPolicyCodes() {
        issueCodes.clear();
        policyCodesSelected = -1;
        policyCodeError = "";
        rebuildWidgets();
    }

    private void renderPolicyPage(GuiGraphics graphics) {
        header(graphics, Component.translatable("screen.veriborder.config.policy_title").getString(),
                Component.translatable(device.translationKey()).getString());
        tabs.draw(graphics, font, accent);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.config.policy_hint"),
                left + 24, top + 70, 392, Ui.MUTED);
        if (policyCodesVisible) {
            if (!policyAdvancedOpen) {
                Ui.clipped(graphics, Component.translatable("screen.veriborder.config.advanced_hint"),
                        left + 24, top + 174, 392, Ui.DIM);
            } else if (policyCodeError.isBlank()) {
                Ui.clipped(graphics, Component.translatable("screen.veriborder.config.exempt_codes",
                        issueCodes.size(), policyCodeTail()), left + 24, top + 174, 392, Ui.TEXT);
            } else {
                Ui.clipped(graphics, Component.translatable(policyCodeError), left + 24, top + 174, 392, Ui.RED);
            }
            if (policyAdvancedOpen) {
                int listTop = top + 222;
                int listHeight = imageHeight - 222 - 50;
                Ui.inset(graphics, left + 24, listTop, 392, listHeight);
                if (policyCodeList != null) {
                    policyCodeList.draw(graphics, font, (g, code, index, x, y, width, height, selected, hovered) -> {
                        graphics.fill(x, y, x + width, y + height, selected ? 0xFF243A50 : hovered
                                ? 0xFF1A2A3B : 0x33101A24);
                        if (selected) {
                            graphics.fill(x, y, x + 2, y + height, Ui.GOLD);
                        }
                        graphics.drawString(font, code, x + 6, y + 3, selected ? Ui.TEXT : Ui.MUTED, false);
                    }, mouseUiX, mouseUiY);
                }
            }
        } else {
            // ID cards are domestic: the page deliberately exposes only the number
            // policy, so explain why the other sections are absent.
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.policy_id_hint"),
                    left + 24, top + 140, 392, Ui.MUTED);
        }
    }

    private String policyCodeTail() {
        if (issueCodes.isEmpty()) {
            return Component.translatable("screen.veriborder.config.policy_codes_empty").getString();
        }
        return String.join(" · ", issueCodes.subList(Math.max(0, issueCodes.size() - 4), issueCodes.size()));
    }

    private int addDocumentLocks(int index) {
        int ix = lockColumnX(index);
        int iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockStyleLabel, () -> lockStyle = !lockStyle);
        // 26.4: the locked document layout is a dropdown over this machine's
        // layouts (five for a passport), not a "next layout" cycle button.
        addDropdown(Dropdown.at(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth())
                .height(16)
                .options(styleOptions())
                .selected(lockedStyleChoice)
                .tooltip(lockStyleLabel())
                .onChange(id -> {
                    // 26.4: a registry template resolves to its own base layout.
                    // The raw dropdown value is always kept: saving resolves it
                    // again, so a template the client no longer knows about can
                    // never leave the stored layout at an unrelated old value.
                    lockedStyleChoice = id;
                    DocStyle next = DocStyle.byIdOrNull(IssuerChoices.styleId(id, registryTemplates));
                    if (next != null && next.kind() == documentKind) {
                        lockedStyle = next;
                    }
                }));

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockPlaceLabel, () -> lockPlace = !lockPlace);
        lockedPlaceField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                "field.veriborder.locked_place", valueFor("lockedPlace", payload.getString("lockedPlace")),
                "hint.veriborder.when_locked");

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockIssueLabel, () -> lockIssueDate = !lockIssueDate);
        lockedIssueDateField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                "field.veriborder.locked_issue",
                valueFor("lockedIssueDate", payload.getString("lockedIssueDate")),
                "hint.veriborder.blank_today");

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockValidityLabel, () -> lockValidity = !lockValidity);
        lockedValidityMonthsField = addValidityMonthsField(ix, iy + LOCK_VALUE_OFFSET);

        if (passportIssuer) {
            ix = lockColumnX(index);
            iy = lockRowY(index);
            index++;
            addLockToggle(ix, iy, this::lockVisaTypeLabel, () -> lockVisaType = !lockVisaType);
            // 26.4: visa type is a dropdown (6 options) instead of a cycle.
            addDropdown(Dropdown.at(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth())
                    .height(16)
                    .options(visaTypeOptions())
                    .selected(lockedVisaType.id())
                    .tooltip(lockVisaTypeLabel())
                    .onChange(id -> lockedVisaType = VisaType.byId(id)));

            ix = lockColumnX(index);
            iy = lockRowY(index);
            index++;
            addLockToggle(ix, iy, this::lockVisaCodeLabel, () -> lockVisaCode = !lockVisaCode);
            lockedVisaCodeField = addLockedVisaCodeField(ix, iy + LOCK_VALUE_OFFSET);
        }
        return index;
    }

    /** 26.1 passport-attached visa locks: include, entries, country, issue, validity, name. */
    private int addPassportVisaLocks(int index) {
        int ix = lockColumnX(index);
        int iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockIncludeVisaLabel, () -> lockIncludeVisa = !lockIncludeVisa);
        addValueButton(ix, iy + LOCK_VALUE_OFFSET, this::includeVisaValueLabel,
                () -> includeVisaLocked = !includeVisaLocked, accent);

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockVisaEntriesLabel, () -> lockVisaEntries = !lockVisaEntries);
        lockedVisaEntriesField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                "field.veriborder.locked_visa_entries",
                valueFor("lockedVisaEntries", String.valueOf(lockedVisaEntries)),
                "hint.veriborder.unlimited");
        lockedVisaEntriesField.setMaxLength(3);
        lockedVisaEntriesField.setFilter(value -> value.length() <= 3
                && value.chars().allMatch(character -> character >= '0' && character <= '9'));

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockVisaCountryLabel, () -> lockVisaCountry = !lockVisaCountry);
        lockedVisaCountryField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                "field.veriborder.locked_visa_country",
                valueFor("lockedVisaCountry", payload.getString("lockedVisaCountry")),
                "hint.veriborder.when_locked");

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockVisaIssueLabel, () -> lockVisaIssueDate = !lockVisaIssueDate);
        lockedVisaIssueDateField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                "field.veriborder.locked_visa_issue",
                valueFor("lockedVisaIssueDate", defaultIssueDate(payload.getString("lockedVisaIssueDate"))),
                "hint.veriborder.date");

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockVisaValidityLabel, () -> lockVisaValidity = !lockVisaValidity);
        lockedVisaValidityMonthsField = addVisaValidityMonthsField(ix, iy + LOCK_VALUE_OFFSET);

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockVisaNameLabel, () -> lockVisaName = !lockVisaName);
        lockedVisaNameField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                "field.veriborder.locked_visa_name",
                valueFor("lockedVisaName", payload.getString("lockedVisaName")),
                "hint.veriborder.visa_name");
        return index;
    }

    private int addVisaIssuerLocks(int index) {
        int ix = lockColumnX(index);
        int iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockVisaTypeLabel, () -> lockVisaType = !lockVisaType);
        // 26.4: visa type is a dropdown (6 options) instead of a cycle.
        addDropdown(Dropdown.at(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth())
                .height(16)
                .options(visaTypeOptions())
                .selected(lockedVisaType.id())
                .tooltip(lockVisaTypeLabel())
                .onChange(id -> lockedVisaType = VisaType.byId(id)));

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockVisaCodeLabel, () -> lockVisaCode = !lockVisaCode);
        lockedVisaCodeField = addLockedVisaCodeField(ix, iy + LOCK_VALUE_OFFSET);

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockPlaceLabel, () -> lockPlace = !lockPlace);
        lockedPlaceField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                "field.veriborder.locked_place", valueFor("lockedPlace", payload.getString("lockedPlace")),
                "hint.veriborder.when_locked");

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockIssueLabel, () -> lockIssueDate = !lockIssueDate);
        lockedIssueDateField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                "field.veriborder.locked_issue",
                valueFor("lockedIssueDate", payload.getString("lockedIssueDate")),
                "hint.veriborder.blank_today");

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockValidityLabel, () -> lockValidity = !lockValidity);
        lockedValidityMonthsField = addValidityMonthsField(ix, iy + LOCK_VALUE_OFFSET);

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockVisaNameLabel, () -> lockVisaName = !lockVisaName);
        lockedVisaNameField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                "field.veriborder.locked_visa_name",
                valueFor("lockedVisaName", payload.getString("lockedVisaName")),
                "hint.veriborder.visa_name");
        return index;
    }

    private int addEndorsementIssuerLocks(int index) {
        int ix = lockColumnX(index);
        int iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockEndorsementTypeLabel,
                () -> lockEndorsementType = !lockEndorsementType);
        // 26.4: endorsement type is a dropdown (3 options) instead of a cycle.
        addDropdown(Dropdown.at(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth())
                .height(16)
                .options(endorsementTypeOptions())
                .selected(lockedEndorsementType.id())
                .tooltip(lockEndorsementTypeLabel())
                .onChange(id -> lockedEndorsementType = EndorsementType.byId(id)));

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockIssueLabel, () -> lockIssueDate = !lockIssueDate);
        lockedIssueDateField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                "field.veriborder.locked_issue",
                valueFor("lockedIssueDate", payload.getString("lockedIssueDate")),
                "hint.veriborder.blank_today");

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockValidityLabel, () -> lockValidity = !lockValidity);
        lockedValidityMonthsField = addValidityMonthsField(ix, iy + LOCK_VALUE_OFFSET);
        return index;
    }

    private BetterButton addLockToggle(int x, int y, Supplier<Component> label, Runnable toggle) {
        return addRenderableWidget(new BetterButton(x, y, lockColumnWidth(), 16, label.get(), button -> {
            toggle.run();
            button.setMessage(label.get());
        }, Ui.AMBER, true));
    }

    private BetterButton addValueButton(int x, int y, Supplier<Component> label, Runnable toggle, int color) {
        return addRenderableWidget(new BetterButton(x, y, lockColumnWidth(), 16, label.get(), button -> {
            toggle.run();
            button.setMessage(label.get());
        }, color, true));
    }

    /**
     * 26.4: layouts offered by the document issuer's dropdown, merged with the
     * registry templates of this machine's credential kind (a template entry
     * resolves to its base layout when saved).
     */
    private List<Dropdown.Option> styleOptions() {
        List<Dropdown.Option> options = new ArrayList<>();
        for (IssuerChoices.Choice choice : IssuerChoices.layoutChoices(documentKind, registryTemplates,
                style -> Component.translatable(style.shortNameKey()).getString())) {
            options.add(new Dropdown.Option(choice.value(), choice.label()));
        }
        return options;
    }

    /** 26.4: visa types, in enum order. */
    private static List<Dropdown.Option> visaTypeOptions() {
        List<Dropdown.Option> options = new ArrayList<>();
        for (VisaType type : VisaType.values()) {
            options.add(new Dropdown.Option(type.id(),
                    Component.translatable(type.translationKey()).getString()));
        }
        return options;
    }

    /** 26.4: endorsement types, in enum order. */
    private static List<Dropdown.Option> endorsementTypeOptions() {
        List<Dropdown.Option> options = new ArrayList<>();
        for (EndorsementType type : EndorsementType.values()) {
            options.add(new Dropdown.Option(type.id(),
                    Component.translatable(type.translationKey()).getString()));
        }
        return options;
    }

    /**
     * 26.2 month precision: validity is entered in MONTHS (0 = permanent). The
     * live "= 1 year 3 months" equivalent is painted by {@link #renderLocksTab}.
     */
    private EditBox addValidityMonthsField(int x, int y) {
        EditBox field = addField(x, y, lockColumnWidth(), "field.veriborder.locked_validity",
                valueFor("lockedValidityMonths", String.valueOf(lockedValidityMonths)),
                "hint.veriborder.zero_permanent");
        field.setMaxLength(4);
        field.setFilter(value -> value.isEmpty()
                || (value.length() <= 4 && value.chars().allMatch(c -> c >= '0' && c <= '9')));
        return field;
    }

    private EditBox addVisaValidityMonthsField(int x, int y) {
        EditBox field = addField(x, y, lockColumnWidth(), "field.veriborder.locked_visa_validity",
                valueFor("lockedVisaValidityMonths", String.valueOf(lockedVisaValidityMonths)),
                "hint.veriborder.zero_permanent");
        field.setMaxLength(4);
        field.setFilter(value -> value.isEmpty()
                || (value.length() <= 4 && value.chars().allMatch(c -> c >= '0' && c <= '9')));
        return field;
    }

    private EditBox addLockedVisaCodeField(int x, int y) {
        EditBox field = addField(x, y, lockColumnWidth(), "field.veriborder.locked_visa_code",
                valueFor("lockedVisaCode", payload.getString("lockedVisaCode")), "hint.veriborder.code_8");
        field.setMaxLength(8);
        field.setFilter(ScreenCodes::digitsUpTo8);
        return field;
    }

    private int lockColumns() {
        return passportIssuer ? 3 : 2;
    }

    private int lockColumnWidth() {
        return passportIssuer ? PASSPORT_COLUMN_WIDTH : LOCK_COLUMN_WIDTH;
    }

    private int lockColumnX(int index) {
        int columns = lockColumns();
        int width = lockColumnWidth();
        int gap = columns == 3 ? 7 : 12;
        return left + 24 + (index % columns) * (width + gap);
    }

    private int lockRowY(int index) {
        return top + lockGridStartY + (index / lockColumns()) * LOCK_ITEM_PITCH;
    }

    private int lockItemCount() {
        if (documentIssuer) {
            return 4 + (passportIssuer ? 2 : 0) + (permitIssuer ? 2 : 0)
                    + (passportIssuer ? 6 : 0);
        }
        if (visaIssuer) {
            return 6;
        }
        if (endorsementIssuer) {
            return 3;
        }
        return 0;
    }

    /** Default locked validity in months: the layout's own validity (declared in years). */
    private int defaultValidityMonths() {
        if (documentIssuer && lockedStyle != null) {
            return lockedStyle.validityYears() * 12;
        }
        return visaIssuer || endorsementIssuer ? 12 : 0;
    }

    private static String defaultIssueDate(String value) {
        return DateUtil.isValid(value) ? value.trim() : DateUtil.today();
    }

    private DocStyle resolveDocumentStyle(String id) {
        if (id != null) {
            String key = id.trim();
            for (DocStyle style : documentStyles) {
                if (style.id().equals(key)) {
                    return style;
                }
            }
        }
        return documentStyles.isEmpty() ? null : documentStyles.get(0);
    }

    /**
     * 26.4: dropdown value of the stored layout lock — the registry template when
     * it still exists and still points at the stored layout, otherwise the plain
     * official layout id.
     */
    private String lockedStyleChoice(@Nullable DocStyle style, String templateId) {
        if (style == null) {
            return "";
        }
        if (templateId != null && !templateId.isBlank()) {
            for (CredentialRegistryData.Template template : registryTemplates) {
                if (template.id().equals(templateId) && template.style().equals(style.id())) {
                    return IssuerChoices.valueOf(template);
                }
            }
        }
        return style.id();
    }

    private static VisaType resolveVisaType(String id) {
        if (id != null) {
            String key = id.trim();
            if (!key.isEmpty()) {
                for (VisaType type : VisaType.values()) {
                    if (type.id().equalsIgnoreCase(key) || type.name().equalsIgnoreCase(key)) {
                        return type;
                    }
                }
            }
        }
        return null;
    }

    private static EndorsementType resolveEndorsementType(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return EndorsementType.byIdOrNull(id);
    }

    /** Batch gate-code field: digits plus the three accepted separators. */
    private static boolean codeBatchInput(String value) {
        if (value == null || value.length() > 512) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if ((character < '0' || character > '9') && character != ','
                    && character != ';' && character != ' ') {
                return false;
            }
        }
        return true;
    }

    /** Batch add into the entry code list; the list reports its own error. */
    private void addGateCode() {
        if (gateCodes.addBatch(gateCode == null ? "" : gateCode.getValue()) > 0 && gateCode != null) {
            gateCode.setValue("");
        }
    }

    private void removeLastCode() {
        gateCodes.removeLast();
    }

    private void readGateCodes() {
        gateCodes.seed(readCodes("gateCodes"));
    }

    /** Valid, deduplicated eight-digit codes stored under one payload key. */
    private List<String> readCodes(String key) {
        List<String> result = new ArrayList<>();
        ListTag list = payload.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            String value = list.getString(i).trim();
            if (ScreenCodes.isValid(value) && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private ListTag gateCodesTag() {
        ListTag list = new ListTag();
        for (String code : gateCodes.codes()) {
            list.add(StringTag.valueOf(code));
        }
        return list;
    }

    private static ListTag codesTag(List<String> codes) {
        ListTag list = new ListTag();
        for (String code : codes) {
            list.add(StringTag.valueOf(code));
        }
        return list;
    }

    /** Shared batch code field: digits plus comma/semicolon/space separators. */
    private EditBox addCodeField(int x, int y, int width, String labelKey) {
        EditBox field = addField(x, y, width, labelKey, "", "hint.veriborder.code_batch");
        field.setMaxLength(512);
        field.setFilter(DeviceConfigScreen::codeBatchInput);
        return field;
    }

    private void addExitCode() {
        if (exitCodes.addBatch(exitCodeField == null ? "" : exitCodeField.getValue()) > 0
                && exitCodeField != null) {
            exitCodeField.setValue("");
        }
    }

    private void removeLastExitCode() {
        exitCodes.removeLast();
    }

    /** Reads one normalised eight-digit code list from the payload. */
    private void readCodeList(List<String> target, String key) {
        ListTag list = payload.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            String value = list.getString(i).trim();
            if (ScreenCodes.isValid(value) && !target.contains(value)) {
                target.add(value);
            }
        }
    }

    private Component autoStampLabel() {
        return Component.translatable("screen.veriborder.config.auto_stamp."
                + (autoStamp ? "on" : "off"));
    }

    private Component lockNameLabel() {
        return Component.translatable("screen.veriborder.config.lock_name."
                + (lockDocumentName ? "on" : "off"));
    }

    private Component lockAuthorityLabel() {
        return Component.translatable("screen.veriborder.config.lock_authority."
                + (lockAuthority ? "on" : "off"));
    }

    private Component lockNationalityLabel() {
        return Component.translatable("screen.veriborder.config.lock_nationality."
                + (lockNationality ? "on" : "off"));
    }

    private Component lockEndorsementCodeLabel() {
        return Component.translatable("screen.veriborder.config.lock_endorsement_code."
                + (lockEndorsementCode ? "on" : "off"));
    }

    private Component lockStyleLabel() {
        return Component.translatable("screen.veriborder.config.lock_style."
                + (lockStyle ? "on" : "off"));
    }

    private Component lockPlaceLabel() {
        return Component.translatable("screen.veriborder.config.lock_place."
                + (lockPlace ? "on" : "off"));
    }

    private Component lockIssueLabel() {
        return Component.translatable("screen.veriborder.config.lock_issue."
                + (lockIssueDate ? "on" : "off"));
    }

    private Component lockValidityLabel() {
        return Component.translatable("screen.veriborder.config.lock_validity."
                + (lockValidity ? "on" : "off"));
    }

    private Component lockVisaTypeLabel() {
        return Component.translatable("screen.veriborder.config.lock_visa_type."
                + (lockVisaType ? "on" : "off"));
    }

    private Component lockVisaCodeLabel() {
        return Component.translatable("screen.veriborder.config.lock_visa_code."
                + (lockVisaCode ? "on" : "off"));
    }

    private Component lockEndorsementTypeLabel() {
        return Component.translatable("screen.veriborder.config.lock_endorsement_type."
                + (lockEndorsementType ? "on" : "off"));
    }

    private Component lockIncludeVisaLabel() {
        return Component.translatable("screen.veriborder.config.lock_include_visa."
                + (lockIncludeVisa ? "on" : "off"));
    }

    private Component includeVisaValueLabel() {
        return Component.translatable("screen.veriborder.config.include_visa_value."
                + (includeVisaLocked ? "on" : "off"));
    }

    private Component lockVisaEntriesLabel() {
        return Component.translatable("screen.veriborder.config.lock_visa_entries."
                + (lockVisaEntries ? "on" : "off"));
    }

    private Component lockVisaCountryLabel() {
        return Component.translatable("screen.veriborder.config.lock_visa_country."
                + (lockVisaCountry ? "on" : "off"));
    }

    private Component lockVisaIssueLabel() {
        return Component.translatable("screen.veriborder.config.lock_visa_issue."
                + (lockVisaIssueDate ? "on" : "off"));
    }

    private Component lockVisaValidityLabel() {
        return Component.translatable("screen.veriborder.config.lock_visa_validity."
                + (lockVisaValidity ? "on" : "off"));
    }

    private Component lockVisaNameLabel() {
        return Component.translatable("screen.veriborder.config.lock_visa_name."
                + (lockVisaName ? "on" : "off"));
    }

    /**
     * Saves every tab at once. Values are read from the page-switch draft first,
     * so a field edited on Locks/Policy is still sent when Save is pressed from
     * Device (and vice versa); fields never visited fall back to the server
     * payload, exactly like the old single-page form.
     */
    private void save() {
        captureFields();
        CompoundTag data = new CompoundTag();
        data.putString("port", value("port"));
        data.putString("country", value("country"));
        data.putString("officer", value("officer"));
        data.putString("airportCountry", value("airportCountry"));
        if (device.isGate()) {
            if (bindingGate) {
                data.putString("gateMode", gateMode);
                data.put("gateCodes", gateCodesTag());
                // Legacy visa/endorsement type binding is no longer used.
                data.putString("requiredVisaType", "");
                data.putString("requiredEndorsementType", "");
            } else {
                // Exit gate: keep the stored gate-wide fallback codes (the key is
                // omitted so the server preserves them) and clear any stale
                // visa/endorsement binding.
                data.putString("gateMode", "both");
                data.putString("requiredVisaType", "");
                data.putString("requiredEndorsementType", "");
            }
            if (device == DeviceType.EXIT_GATE) {
                data.put("exitCodes", codesTag(exitCodes.codes()));
            }
            // 26.4 repair: every gate sends BOTH directional whitelists with the
            // same payload keys as the dual-clearance editor. The server applies
            // each key only when it is present, so this cannot clear an unrelated
            // stage; it just stops the single-gate screen from losing the other
            // direction's whitelist every time the gate is saved.
            data.put("entryCountryCodes", codesTag(entryCountryCodes));
            data.put("exitCountryCodes", codesTag(exitCountryCodes));
            // 26.1 gate behaviour. Sent by every gate, including the exit gate.
            data.putBoolean("autoStamp", autoStamp);
            data.putInt("overstayDays", overstayDays());
        }
        if (issuer) {
            data.putBoolean("lockDocumentName", lockDocumentName);
            data.putString("lockedDocumentName", value("lockedDocumentName").trim());
            data.putBoolean("lockAuthority", lockAuthority);
            data.putString("lockedAuthority", value("lockedAuthority").trim());
            if (selfKiosk) {
                data.putString("issuedCode", value("issuedCode").trim());
            }
            data.putString("issuerCountry", issuerChoice == null ? issuerCountry : issuerChoice.selectedId());
            if (documentIssuer) {
                // 26.4 fix: the dropdown value may be "template:<id>". Resolve it
                // to the template's base official layout before saving; a deleted
                // template (or an unusable value) falls back to the first official
                // layout of this machine's kind and the server is told to say so,
                // instead of rejecting or silently saving a bogus lockedStyle.
                IssuerChoices.LockedStyle lockedLayout =
                        IssuerChoices.resolveLockedStyle(lockedStyleChoice, documentKind, registryTemplates);
                this.lockedStyle = DocStyle.byIdOrNull(lockedLayout.styleId());
                this.lockedStyleTemplate = lockedLayout.templateId();
                data.putBoolean("lockStyle", lockStyle);
                data.putString("lockedStyle", lockedLayout.styleId());
                data.putString("lockedStyleTemplate", lockedLayout.templateId());
                if (lockedLayout.fellBack()) {
                    data.putBoolean("lockedStyleFallback", true);
                }
                // 26.3 round 12: the nationality lock is superseded by the country
                // selector above, so its keys are no longer sent (the stored values
                // stay in the block entity NBT, unused).
                // 26.2 issuer policy (Policy tab). Only the keys this machine type
                // actually displays are sent; an omitted key keeps the device's
                // stored value (ID cards never touch MRZ/codes/entry state, permits
                // never touch MRZ, and none of them touch the others' keys).
                data.putBoolean("lockNumber", policyLockNumber);
                if (regionPolicy) {
                    data.putString("numberRegion", policyRegion);
                    data.putString("numberSequence", policySequence);
                }
                if (policyCodesVisible) {
                    data.put("issueCodes", issueCodesTag());
                }
                if (policyEntryVisible) {
                    data.putBoolean("issueEntryState", policyIssueEntry);
                }
            }
            if (documentIssuer || visaIssuer) {
                data.putBoolean("lockPlace", lockPlace);
                data.putString("lockedPlace", value("lockedPlace").trim());
            }
            data.putBoolean("lockIssueDate", lockIssueDate);
            data.putString("lockedIssueDate", value("lockedIssueDate").trim());
            data.putBoolean("lockValidity", lockValidity);
            // 26.2 month precision: months are the stored contract; the legacy
            // year key (floor) keeps an older reader usable and never rounds up.
            int validityMonths = validityMonths();
            data.putInt("lockedValidityMonths", validityMonths);
            data.putInt("lockedValidityYears", validityMonths / 12);
            if (visaIssuer || passportIssuer) {
                data.putBoolean("lockVisaType", lockVisaType);
                data.putString("lockedVisaType", lockedVisaType.id());
                data.putBoolean("lockVisaCode", lockVisaCode);
                data.putString("lockedVisaCode", ScreenCodes.validOrBlank(value("lockedVisaCode")));
            }
            if (endorsementIssuer || permitIssuer) {
                // 26.2: a permit issuer uses the same fields for the type of the
                // built-in endorsement it attaches to every new permit.
                data.putBoolean("lockEndorsementType", lockEndorsementType);
                data.putString("lockedEndorsementType", lockedEndorsementType.id());
            }
            if (permitIssuer) {
                data.putBoolean("lockEndorsementCode", lockEndorsementCode);
                data.putString("lockedEndorsementCode", ScreenCodes.validOrBlank(
                        value("lockedEndorsementCode")));
            }
            if (passportIssuer) {
                // 26.1 attached-visa locks. Keys follow the DeviceBlockEntity
                // contract exactly (lockedVisaValidityMonths for lockVisaValidity).
                data.putBoolean("lockIncludeVisa", lockIncludeVisa);
                data.putBoolean("lockedIncludeVisa", includeVisaLocked);
                data.putBoolean("lockVisaEntries", lockVisaEntries);
                data.putInt("lockedVisaEntries", lockedVisaEntries());
                data.putBoolean("lockVisaCountry", lockVisaCountry);
                data.putString("lockedVisaCountry", value("lockedVisaCountry").trim());
                data.putBoolean("lockVisaIssueDate", lockVisaIssueDate);
                data.putString("lockedVisaIssueDate", value("lockedVisaIssueDate").trim());
                data.putBoolean("lockVisaValidity", lockVisaValidity);
                int visaValidityMonths = visaValidityMonths();
                data.putInt("lockedVisaValidityMonths", visaValidityMonths);
                data.putInt("lockedVisaValidityYears", visaValidityMonths / 12);
                data.putBoolean("lockVisaName", lockVisaName);
                data.putString("lockedVisaName", value("lockedVisaName").trim());
            } else if (visaIssuer) {
                data.putBoolean("lockVisaName", lockVisaName);
                data.putString("lockedVisaName", value("lockedVisaName").trim());
            }
        }
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "save_config", data, slot));
        onClose();
    }

    /** Locked document validity in months (0 = permanent), clamped like the device. */
    private int validityMonths() {
        return DeviceBlockEntity.clampValidityMonths(parseInt(value("lockedValidityMonths"),
                lockedValidityMonths));
    }

    /** Locked attached-visa validity in months (0 = permanent). */
    private int visaValidityMonths() {
        return DeviceBlockEntity.clampValidityMonths(parseInt(
                value("lockedVisaValidityMonths"), lockedVisaValidityMonths));
    }

    private int lockedVisaEntries() {
        return Math.max(0, parseInt(value("lockedVisaEntries"), lockedVisaEntries));
    }

    private int overstayDays() {
        return clampOverstay(parseInt(value("overstayDays"), overstayDays));
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int clampOverstay(int value) {
        return Math.max(0, Math.min(DeviceBlockEntity.MAX_OVERSTAY_DAYS, value));
    }

    private double mouseUiX = -1;
    private double mouseUiY = -1;

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.mouseUiX = toUiX(mouseX);
        this.mouseUiY = toUiY(mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        boolean anyPickerOpen = (entryPicker != null && entryPicker.isOpen())
                || (exitPicker != null && exitPicker.isOpen());
        if (anyPickerOpen) {
            // Floating whitelists are painted after every widget, so they are
            // always the topmost layer and no control below can cover them.
            overlay(graphics, canvas -> {
                if (entryPicker != null && entryPicker.isOpen()) {
                    entryPicker.drawPanel(canvas, accent, mouseUiX, mouseUiY);
                }
                if (exitPicker != null && exitPicker.isOpen()) {
                    exitPicker.drawPanel(canvas, accent, mouseUiX, mouseUiY);
                }
            });
        }
    }

    @Override
    public void onClose() {
        if (isPolicyPage()) {
            // Escape returns to the Device tab instead of discarding the screen.
            selectTab(TAB_DEVICE);
            return;
        }
        super.onClose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        // 26.4: dropdowns (gate mode, issuer country, lock values) own the click.
        if (dropdownClick(uiX, uiY)) {
            return true;
        }
        // 26.4 repair: the whitelist popups are the topmost layer, then the code
        // lists (per-row delete buttons). A click outside an open popup closes it
        // and falls through to the control underneath, so the popup never swallows
        // another widget's click.
        if (handlePicker(entryPicker, uiX, uiY, button)) {
            return true;
        }
        if (handlePicker(exitPicker, uiX, uiY, button)) {
            return true;
        }
        if (gateCodeBox != null && gateCodeBox.mouseClicked(uiX, uiY)) {
            return true;
        }
        if (exitCodeBox != null && exitCodeBox.mouseClicked(uiX, uiY)) {
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (tabs != null && tabs.tabCount() > 1) {
            int hit = tabs.hit(uiX, uiY);
            if (hit >= 0) {
                if (hit != tab) {
                    selectTab(hit);
                }
                return true;
            }
        }
        if (isPolicyPage() && policyAdvancedOpen && policyCodeList != null
                && policyCodeList.mouseClicked(uiX, uiY)) {
            if (policyCodeList.selectedIndex() >= 0) {
                policyCodesSelected = policyCodeList.selectedIndex();
            }
            return true;
        }
        return false;
    }

    /** Whitelist popup: opening one dismisses the other and any open dropdown. */
    private boolean handlePicker(CountryPicker picker, double uiX, double uiY, int button) {
        if (picker == null) {
            return false;
        }
        boolean wasOpen = picker.isOpen();
        if (!picker.mouseClicked(uiX, uiY, button)) {
            return false;
        }
        if (!wasOpen && picker.isOpen()) {
            closeDropdownsExcept(null);
            closePickersExcept(picker);
        }
        return true;
    }

    private void closePickersExcept(CountryPicker keep) {
        if (entryPicker != null && entryPicker != keep) {
            entryPicker.close();
        }
        if (exitPicker != null && exitPicker != keep) {
            exitPicker.close();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (entryPicker != null && entryPicker.isOpen()) {
            return entryPicker.keyPressed(keyCode, scanCode, modifiers);
        }
        if (exitPicker != null && exitPicker.isOpen()) {
            return exitPicker.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (entryPicker != null && entryPicker.isOpen()) {
            return entryPicker.charTyped(codePoint, modifiers);
        }
        if (exitPicker != null && exitPicker.isOpen()) {
            return exitPicker.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        if (dropdownScroll(uiX, uiY, delta)) {
            return true;
        }
        if (isPolicyPage() && policyAdvancedOpen && policyCodeList != null
                && policyCodeList.mouseScrolled(uiX, uiY, delta)) {
            return true;
        }
        if (tab == TAB_DEVICE) {
            if (entryPicker != null && entryPicker.mouseScrolled(uiX, uiY, delta)) {
                return true;
            }
            if (exitPicker != null && exitPicker.mouseScrolled(uiX, uiY, delta)) {
                return true;
            }
            if (gateCodeBox != null && gateCodeBox.mouseScrolled(uiX, uiY, delta)) {
                return true;
            }
            if (exitCodeBox != null && exitCodeBox.mouseScrolled(uiX, uiY, delta)) {
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        if (isPolicyPage()) {
            renderPolicyPage(graphics);
            return;
        }
        header(graphics, Component.translatable(device.translationKey()).getString(),
                Component.translatable(device.descriptionKey()).getString());
        boolean multiTab = tabs != null && tabs.tabCount() > 1;
        if (multiTab) {
            tabs.draw(graphics, font, accent);
        }
        int hintY = multiTab ? top + 70 : top + 44;
        if (tab == TAB_LOCKS) {
            renderLocksTab(graphics, hintY);
            return;
        }
        if (device.isGate()) {
            renderGatePage(graphics);
        } else if (issuer) {
            // 26.3 round 12: one country selector; the nationality and the MRZ
            // issuing code are both derived from it (read-only preview lines).
            String nationality = issuerNationality();
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.issuer_binding",
                    issuerCountry.isBlank() ? "—" : issuerCountry,
                    nationality.isBlank() ? "—" : nationality),
                    left + 24, top + 150, 392, Ui.CYAN);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.issuer_hint"),
                    left + 24, top + 164, 392, Ui.DIM);
        } else {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.hint"),
                    left + 24, hintY, 392, Ui.MUTED);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.airport_hint"),
                    left + 24, top + 202, 392, Ui.DIM);
        }
        if (device.isGate()) {
            // 26.3 round 12 item 18: the exit-tolerance policy is written into the
            // gate configuration itself, so an operator never has to guess whether
            // an expired credential traps a traveller.
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.exit_tolerance"),
                    left + 24, top + imageHeight - 58, 392, Ui.AMBER);
        }
        if (multiTab) {
            Ui.clipped(graphics, Component.translatable("hint.veriborder.config_tabs"), left + 280,
                    top + imageHeight - 14, 136, Ui.DIM);
        }
    }

    /**
     * Gate page chrome: hints, recorded-code header, both code lists, both
     * whitelist buttons and the behaviour hint. Everything is drawn at the row
     * tops the flow layout handed out in {@link #buildGatePage(int)}, so no row
     * can be painted over another one.
     */
    private void renderGatePage(GuiGraphics graphics) {
        boolean exitGate = device == DeviceType.EXIT_GATE;
        int x = left + 24;
        int width = 392;
        Ui.clipped(graphics, Component.translatable(exitGate
                        ? "screen.veriborder.config.exit_gate_hint"
                        : "screen.veriborder.config.gate_hint"),
                x, gateHintY, width, exitGate ? Ui.AMBER : Ui.DIM);
        Ui.clipped(graphics, Component.translatable(exitGate
                        ? exitCodeModeKey()
                        : "screen.veriborder.config.gate_codes_hint"),
                x, gateHint2Y, width, Ui.DIM);
        CodeListModel codes = exitGate ? exitCodes : gateCodes;
        String count = Component.translatable(exitGate
                        ? "screen.veriborder.config.exit_codes"
                        : "screen.veriborder.config.gate_codes", codes.size()).getString();
        if (codes.errorKey().isBlank()) {
            Ui.clipped(graphics, count, x, gateCodeTitleY, width, Ui.TEXT);
        } else {
            String error = Component.translatable(codes.errorKey()).getString();
            int errorWidth = Math.min(font.width(error), width / 2);
            Ui.clipped(graphics, count, x, gateCodeTitleY, width - errorWidth - 8, Ui.TEXT);
            graphics.drawString(font, Ui.ellipsis(error, errorWidth),
                    x + width - errorWidth, gateCodeTitleY, Ui.RED, false);
        }
        if (gateCodeBox != null) {
            gateCodeBox.draw(graphics, font, accent, mouseUiX, mouseUiY);
        }
        if (exitCodeBox != null) {
            exitCodeBox.draw(graphics, font, accent, mouseUiX, mouseUiY);
        }
        if (entryPicker != null) {
            entryPicker.drawButton(graphics, accent, mouseUiX, mouseUiY);
        }
        if (exitPicker != null) {
            exitPicker.drawButton(graphics, accent, mouseUiX, mouseUiY);
        }
        Ui.clipped(graphics, Component.translatable("screen.veriborder.config.gate_behavior_hint"),
                x, gateBehaviorHintY, width, Ui.DIM);
    }

    private void renderLocksTab(GuiGraphics graphics, int hintY) {
        Ui.clipped(graphics, Component.translatable("screen.veriborder.config.lock_hint"),
                left + 24, hintY, 392, Ui.DIM);
        // 26.2 month precision: the months field is authoritative, but the
        // operator sees the human equivalent live while typing ("= 1 year 3 months").
        int y = top + imageHeight - 70;
        if (lockedValidityMonthsField != null) {
            int months = liveMonths(lockedValidityMonthsField, validityMonths());
            Ui.clipped(graphics, validityEquivalent(months), left + 24, y, 392, Ui.CYAN);
            y += 14;
        }
        if (lockedVisaValidityMonthsField != null) {
            int months = liveMonths(lockedVisaValidityMonthsField, visaValidityMonths());
            Ui.clipped(graphics, validityEquivalent(months), left + 24, y, 392, Ui.CYAN);
        }
    }

    /**
     * Month count plus its collapsed year form ("15 months = 1 year 3 months");
     * below a year (and for "permanent") the plain text is enough.
     */
    private static Component validityEquivalent(int months) {
        if (months < 12) {
            return Component.literal(Ui.validityText(months));
        }
        return Component.translatable("hint.veriborder.months_equivalent",
                months, Ui.validityText(months));
    }

    /** Live month count of a validity field (the text being typed, clamped). */
    private static int liveMonths(EditBox field, int fallback) {
        return DeviceBlockEntity.clampValidityMonths(parseInt(
                field == null ? "" : field.getValue(), fallback));
    }

    private static String normalizeMode(String value) {
        if (value != null) {
            switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "visa", "passport" -> {
                    return "visa";
                }
                case "endorsement", "permit" -> {
                    return "endorsement";
                }
                case "both" -> {
                    return "both";
                }
                default -> {
                    // "all" and every unknown legacy value now mean both.
                    return "both";
                }
            }
        }
        return "both";
    }
}
