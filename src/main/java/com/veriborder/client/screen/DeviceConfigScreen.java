package com.veriborder.client.screen;

import com.veriborder.block.DeviceBlockEntity;
import com.veriborder.block.DeviceType;
import com.veriborder.client.BetterButton;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.doc.DateUtil;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentKind;
import com.veriborder.doc.EndorsementType;
import com.veriborder.doc.VisaType;
import com.veriborder.network.DeviceActionPacket;
import com.veriborder.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

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
    private static final String[] GATE_MODES = {
            "all", "visa", "endorsement", "both"
    };

    /** Tallest design height that still fits a 854x480 viewport at GUI scale 2. */
    private static final int ISSUER_HEIGHT = 480;
    private static final int GATE_HEIGHT = 420;
    private static final int EXIT_GATE_HEIGHT = 292;
    private static final int LOCK_ITEM_PITCH = 44;
    private static final int LOCK_VALUE_OFFSET = 28;
    private static final int LOCK_COLUMN_WIDTH = 190;
    private static final int PASSPORT_COLUMN_WIDTH = 126;

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
    private final int saveY;
    private final int lockGridStartY;
    private final List<String> gateCodes = new ArrayList<>();

    private EditBox port;
    private EditBox country;
    private EditBox officer;
    private EditBox airportCountry;
    private EditBox gateCode;
    private EditBox lockedName;
    private EditBox lockedAuthorityField;
    private EditBox issuedCodeField;
    private EditBox lockedEndorsementCodeField;
    private EditBox lockedPlaceField;
    private EditBox lockedIssueDateField;
    private EditBox lockedValidityYearsField;
    private EditBox lockedVisaCodeField;
    private EditBox lockedVisaEntriesField;
    private EditBox lockedVisaCountryField;
    private EditBox lockedVisaIssueDateField;
    private EditBox lockedVisaValidityYearsField;
    private EditBox lockedVisaNameField;
    private EditBox overstayDaysField;
    private BetterButton autoStampButton;

    private String gateMode = "all";
    private String codeError = "";
    private boolean lockDocumentName;
    private boolean lockAuthority;
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
    private VisaType lockedVisaType = VisaType.TOURIST;
    private EndorsementType lockedEndorsementType = EndorsementType.ONE_YEAR_MULTI;
    private int lockedValidityYears;
    private int lockedVisaEntries = 1;
    private int lockedVisaValidityYears = 1;
    private int overstayDays = DeviceBlockEntity.DEFAULT_OVERSTAY_DAYS;

    public DeviceConfigScreen(DeviceType device, BlockPos pos, CompoundTag payload, int slot) {
        super(Component.translatable("screen.veriborder.config"), 440,
                device.isIssuer() ? ISSUER_HEIGHT
                        : device.isGate() ? (device != DeviceType.EXIT_GATE ? GATE_HEIGHT : EXIT_GATE_HEIGHT) : 268);
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
        this.lockEndorsementCode = payload.getBoolean("lockEndorsementCode");
        readGateCodes();

        this.lockedStyle = resolveDocumentStyle(payload.getString("lockedStyle"));
        this.lockStyle = payload.getBoolean("lockStyle") && lockedStyle != null;
        String place = payload.getString("lockedPlace").trim();
        this.lockPlace = payload.getBoolean("lockPlace") && !place.isBlank();
        String issue = payload.getString("lockedIssueDate").trim();
        this.lockIssueDate = payload.getBoolean("lockIssueDate") && DateUtil.isValid(issue);
        this.lockValidity = payload.getBoolean("lockValidity");
        this.lockedValidityYears = payload.contains("lockedValidityYears")
                ? payload.getInt("lockedValidityYears") : defaultValidityYears();
        VisaType resolvedVisaType = resolveVisaType(payload.getString("lockedVisaType"));
        this.lockedVisaType = resolvedVisaType == null ? VisaType.TOURIST : resolvedVisaType;
        this.lockVisaType = payload.getBoolean("lockVisaType") && resolvedVisaType != null;
        String visaCode = payload.getString("lockedVisaCode").trim();
        this.lockVisaCode = payload.getBoolean("lockVisaCode")
                && ScreenCodes.isValid(visaCode);
        EndorsementType resolvedEndorsementType =
                resolveEndorsementType(payload.getString("lockedEndorsementType"));
        this.lockedEndorsementType = resolvedEndorsementType == null
                ? EndorsementType.ONE_YEAR_MULTI : resolvedEndorsementType;
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
        this.lockedVisaValidityYears = payload.contains("lockedVisaValidityYears")
                ? payload.getInt("lockedVisaValidityYears") : 1;
        this.lockVisaName = payload.getBoolean("lockVisaName");

        this.firstFieldY = bindingGate ? 250 : 76;
        if (bindingGate) {
            this.lockGridStartY = 0;
            this.saveY = 392;
        } else if (issuer) {
            // Passport issuers need four rows of three columns; the extra row is
            // gained by starting the grid right below the authority value field.
            this.lockGridStartY = passportIssuer ? 276 : selfKiosk ? 308 : 292;
            int columns = lockColumns();
            int rows = (lockItemCount() + columns - 1) / columns;
            this.saveY = lockGridStartY + (rows - 1) * LOCK_ITEM_PITCH + 48;
        } else if (device.isGate()) {
            this.lockGridStartY = 0;
            this.saveY = 264;
        } else {
            this.lockGridStartY = 0;
            this.saveY = firstFieldY + 156;
        }
    }

    @Override
    protected void build() {
        int x = left + 24;
        if (bindingGate) {
            addRenderableWidget(new BetterButton(x, top + 56, 392, 20, gateModeLabel(), button -> {
                gateMode = nextMode(gateMode);
                button.setMessage(gateModeLabel());
            }, accent, true));
            gateCode = addField(x, top + 104, 300, "field.veriborder.gate_code", "",
                    "hint.veriborder.code_batch");
            // The batch field accepts commas/spaces/semicolons, not only digits.
            gateCode.setMaxLength(512);
            gateCode.setFilter(DeviceConfigScreen::codeBatchInput);
            addRenderableWidget(new BetterButton(x + 308, top + 100, 84, 18,
                    Component.translatable("button.veriborder.add"), button -> addGateCode(), Ui.GREEN, true));
            addRenderableWidget(new BetterButton(x, top + 148, 120, 18,
                    Component.translatable("button.veriborder.remove_last"), button -> removeLastCode(), Ui.AMBER, true));
            addRenderableWidget(new BetterButton(x + 130, top + 148, 100, 18,
                    Component.translatable("button.veriborder.clear_all"), button -> {
                        gateCodes.clear();
                        codeError = "";
                    }, Ui.RED, true));
        }
        if (device.isGate()) {
            int behaviorY = bindingGate ? 210 : 224;
            int overstayY = bindingGate ? 222 : 236;
            autoStampButton = addRenderableWidget(new BetterButton(x, top + behaviorY, 190, 18,
                    autoStampLabel(), button -> {
                        autoStamp = !autoStamp;
                        button.setMessage(autoStampLabel());
                    }, accent, true));
            overstayDaysField = addField(x + 202, top + overstayY, 190,
                    "field.veriborder.overstay_days", String.valueOf(overstayDays),
                    "hint.veriborder.days");
            overstayDaysField.setMaxLength(4);
            overstayDaysField.setFilter(value -> value.length() <= 4
                    && value.chars().allMatch(character -> character >= '0' && character <= '9'));
        }
        port = addField(x, top + firstFieldY, 392, "field.veriborder.port", payload.getString("port"));
        int fieldStep = issuer ? 32 : 36;
        country = addField(x, top + firstFieldY + fieldStep, 392, "field.veriborder.country",
                payload.getString("country"));
        officer = addField(x, top + firstFieldY + fieldStep * 2, 392, "field.veriborder.officer",
                payload.getString("officer"));
        airportCountry = addField(x, top + firstFieldY + fieldStep * 3, 392, "field.veriborder.airport_country",
                payload.getString("airportCountry"), "hint.veriborder.airport");
        if (issuer) {
            buildIssuerLocks(x);
        }
        addRenderableWidget(new BetterButton(x, top + saveY, 120, 20,
                Component.translatable("button.veriborder.save"), button -> save(), accent));
        addRenderableWidget(new BetterButton(x + 130, top + saveY, 100, 20,
                Component.translatable("button.veriborder.cancel"), button -> onClose(), Ui.MUTED, true));
    }

    /**
     * Existing name/authority locks stay at the top of the issuer section; the
     * remaining locks follow in a compact grid (three columns for a passport
     * issuer, two columns otherwise).
     */
    private void buildIssuerLocks(int x) {
        addRenderableWidget(new BetterButton(x, top + 200, 190, 18, lockNameLabel(), button -> {
            lockDocumentName = !lockDocumentName;
            button.setMessage(lockNameLabel());
        }, Ui.AMBER, true));
        addRenderableWidget(new BetterButton(x + 202, top + 200, 190, 18, lockAuthorityLabel(), button -> {
            lockAuthority = !lockAuthority;
            button.setMessage(lockAuthorityLabel());
        }, Ui.AMBER, true));
        lockedName = addField(x, top + 230, 392, "field.veriborder.locked_name",
                payload.getString("lockedDocumentName"), "hint.veriborder.lock_value");
        lockedAuthorityField = addField(x, top + 258, 392, "field.veriborder.locked_authority",
                payload.getString("lockedAuthority"), "hint.veriborder.lock_value");
        if (selfKiosk) {
            issuedCodeField = addField(x, top + 286, 392, "field.veriborder.issued_code",
                    payload.getString("issuedCode"), "hint.veriborder.machine_code");
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
            int ix = lockColumnX(index);
            int iy = lockRowY(index);
            index++;
            addLockToggle(ix, iy, this::lockEndorsementCodeLabel, () -> lockEndorsementCode = !lockEndorsementCode);
            lockedEndorsementCodeField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                    "field.veriborder.locked_endorsement_code",
                    payload.getString("lockedEndorsementCode"), "hint.veriborder.code_8");
            lockedEndorsementCodeField.setMaxLength(8);
            lockedEndorsementCodeField.setFilter(ScreenCodes::digitsUpTo8);
        }
    }

    private int addDocumentLocks(int index) {
        int ix = lockColumnX(index);
        int iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockStyleLabel, () -> lockStyle = !lockStyle);
        addValueButton(ix, iy + LOCK_VALUE_OFFSET, () -> Component.translatable(lockedStyle.shortNameKey()),
                () -> lockedStyle = DocStyle.nextForKind(documentKind, lockedStyle), accent);

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockPlaceLabel, () -> lockPlace = !lockPlace);
        lockedPlaceField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                "field.veriborder.locked_place", payload.getString("lockedPlace"),
                "hint.veriborder.when_locked");

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockIssueLabel, () -> lockIssueDate = !lockIssueDate);
        lockedIssueDateField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                "field.veriborder.locked_issue", defaultIssueDate(payload.getString("lockedIssueDate")),
                "hint.veriborder.date");

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockValidityLabel, () -> lockValidity = !lockValidity);
        lockedValidityYearsField = addValidityYearsField(ix, iy + LOCK_VALUE_OFFSET);

        if (passportIssuer) {
            ix = lockColumnX(index);
            iy = lockRowY(index);
            index++;
            addLockToggle(ix, iy, this::lockVisaTypeLabel, () -> lockVisaType = !lockVisaType);
            addValueButton(ix, iy + LOCK_VALUE_OFFSET, () -> Component.translatable(lockedVisaType.translationKey()),
                    () -> lockedVisaType = lockedVisaType.next(), Ui.PURPLE);

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
                "field.veriborder.locked_visa_entries", String.valueOf(lockedVisaEntries),
                "hint.veriborder.unlimited");
        lockedVisaEntriesField.setMaxLength(3);
        lockedVisaEntriesField.setFilter(value -> value.length() <= 3
                && value.chars().allMatch(character -> character >= '0' && character <= '9'));

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockVisaCountryLabel, () -> lockVisaCountry = !lockVisaCountry);
        lockedVisaCountryField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                "field.veriborder.locked_visa_country", payload.getString("lockedVisaCountry"),
                "hint.veriborder.when_locked");

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockVisaIssueLabel, () -> lockVisaIssueDate = !lockVisaIssueDate);
        lockedVisaIssueDateField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                "field.veriborder.locked_visa_issue",
                defaultIssueDate(payload.getString("lockedVisaIssueDate")), "hint.veriborder.date");

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockVisaValidityLabel, () -> lockVisaValidity = !lockVisaValidity);
        lockedVisaValidityYearsField = addVisaValidityYearsField(ix, iy + LOCK_VALUE_OFFSET);

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockVisaNameLabel, () -> lockVisaName = !lockVisaName);
        lockedVisaNameField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                "field.veriborder.locked_visa_name", payload.getString("lockedVisaName"),
                "hint.veriborder.visa_name");
        return index;
    }

    private int addVisaIssuerLocks(int index) {
        int ix = lockColumnX(index);
        int iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockVisaTypeLabel, () -> lockVisaType = !lockVisaType);
        addValueButton(ix, iy + LOCK_VALUE_OFFSET, () -> Component.translatable(lockedVisaType.translationKey()),
                () -> lockedVisaType = lockedVisaType.next(), Ui.PURPLE);

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
                "field.veriborder.locked_place", payload.getString("lockedPlace"),
                "hint.veriborder.when_locked");

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockIssueLabel, () -> lockIssueDate = !lockIssueDate);
        lockedIssueDateField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                "field.veriborder.locked_issue", defaultIssueDate(payload.getString("lockedIssueDate")),
                "hint.veriborder.date");

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockValidityLabel, () -> lockValidity = !lockValidity);
        lockedValidityYearsField = addValidityYearsField(ix, iy + LOCK_VALUE_OFFSET);

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockVisaNameLabel, () -> lockVisaName = !lockVisaName);
        lockedVisaNameField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                "field.veriborder.locked_visa_name", payload.getString("lockedVisaName"),
                "hint.veriborder.visa_name");
        return index;
    }

    private int addEndorsementIssuerLocks(int index) {
        int ix = lockColumnX(index);
        int iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockEndorsementTypeLabel,
                () -> lockEndorsementType = !lockEndorsementType);
        addValueButton(ix, iy + LOCK_VALUE_OFFSET,
                () -> Component.translatable(lockedEndorsementType.translationKey()),
                () -> lockedEndorsementType = lockedEndorsementType.next(), Ui.PURPLE);

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockIssueLabel, () -> lockIssueDate = !lockIssueDate);
        lockedIssueDateField = addField(ix, iy + LOCK_VALUE_OFFSET, lockColumnWidth(),
                "field.veriborder.locked_issue", defaultIssueDate(payload.getString("lockedIssueDate")),
                "hint.veriborder.date");

        ix = lockColumnX(index);
        iy = lockRowY(index);
        index++;
        addLockToggle(ix, iy, this::lockValidityLabel, () -> lockValidity = !lockValidity);
        lockedValidityYearsField = addValidityYearsField(ix, iy + LOCK_VALUE_OFFSET);
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

    private EditBox addValidityYearsField(int x, int y) {
        EditBox field = addField(x, y, lockColumnWidth(), "field.veriborder.locked_validity",
                String.valueOf(lockedValidityYears), "hint.veriborder.zero_permanent");
        field.setMaxLength(3);
        field.setFilter(value -> value.isEmpty()
                || (value.length() <= 3 && value.chars().allMatch(c -> c >= '0' && c <= '9')));
        return field;
    }

    private EditBox addVisaValidityYearsField(int x, int y) {
        EditBox field = addField(x, y, lockColumnWidth(), "field.veriborder.locked_visa_validity",
                String.valueOf(lockedVisaValidityYears), "hint.veriborder.zero_permanent");
        field.setMaxLength(3);
        field.setFilter(value -> value.isEmpty()
                || (value.length() <= 3 && value.chars().allMatch(c -> c >= '0' && c <= '9')));
        return field;
    }

    private EditBox addLockedVisaCodeField(int x, int y) {
        EditBox field = addField(x, y, lockColumnWidth(), "field.veriborder.locked_visa_code",
                payload.getString("lockedVisaCode"), "hint.veriborder.code_8");
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
            return 4 + (passportIssuer ? 2 : 0) + (permitIssuer ? 1 : 0)
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

    private int defaultValidityYears() {
        if (documentIssuer && lockedStyle != null) {
            return lockedStyle.validityYears();
        }
        return visaIssuer || endorsementIssuer ? 1 : 0;
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

    /**
     * Parses the add field as whitespace/comma/semicolon-separated codes. Every
     * valid code is added (deduplicated); invalid tokens are rejected as a
     * group with the usual "8 digits" error and the field is only cleared when
     * at least one code was accepted.
     */
    private void addGateCode() {
        String raw = gateCode == null ? "" : gateCode.getValue().trim();
        if (raw.isEmpty()) {
            codeError = "message.veriborder.code.invalid";
            return;
        }
        String[] tokens = raw.split("[,;\\s]+");
        int added = 0;
        boolean anyToken = false;
        boolean invalid = false;
        boolean duplicate = false;
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            anyToken = true;
            String value = token.trim();
            if (!ScreenCodes.isValid(value)) {
                invalid = true;
                continue;
            }
            if (gateCodes.contains(value)) {
                duplicate = true;
                continue;
            }
            gateCodes.add(value);
            added++;
        }
        if (!anyToken) {
            invalid = true;
        }
        if (added > 0) {
            gateCode.setValue("");
        }
        if (invalid) {
            codeError = "message.veriborder.code.invalid";
        } else if (added == 0 && duplicate) {
            codeError = "message.veriborder.code.duplicate";
        } else {
            codeError = "";
        }
    }

    private void removeLastCode() {
        if (!gateCodes.isEmpty()) {
            gateCodes.remove(gateCodes.size() - 1);
            codeError = "";
        }
    }

    private void readGateCodes() {
        ListTag list = payload.getList("gateCodes", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            String value = list.getString(i).trim();
            if (ScreenCodes.isValid(value) && !gateCodes.contains(value)) {
                gateCodes.add(value);
            }
        }
    }

    private ListTag gateCodesTag() {
        ListTag list = new ListTag();
        for (String code : gateCodes) {
            list.add(StringTag.valueOf(code));
        }
        return list;
    }

    private Component gateModeLabel() {
        return Component.translatable("screen.veriborder.config.gate_mode." + gateMode);
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

    private void save() {
        CompoundTag data = new CompoundTag();
        data.putString("port", port.getValue());
        data.putString("country", country.getValue());
        data.putString("officer", officer.getValue());
        data.putString("airportCountry", airportCountry.getValue());
        if (device.isGate()) {
            if (bindingGate) {
                data.putString("gateMode", gateMode);
                data.put("gateCodes", gateCodesTag());
                // Legacy visa/endorsement type binding is no longer used.
                data.putString("requiredVisaType", "");
                data.putString("requiredEndorsementType", "");
            } else {
                // Exit gate: explicitly clear any stale binding.
                data.putString("gateMode", "all");
                data.put("gateCodes", new ListTag());
                data.putString("requiredVisaType", "");
                data.putString("requiredEndorsementType", "");
            }
            // 26.1 gate behaviour. Sent by every gate, including the exit gate.
            data.putBoolean("autoStamp", autoStamp);
            data.putInt("overstayDays", overstayDays());
        }
        if (issuer) {
            data.putBoolean("lockDocumentName", lockDocumentName);
            data.putString("lockedDocumentName", lockedName == null ? "" : lockedName.getValue().trim());
            data.putBoolean("lockAuthority", lockAuthority);
            data.putString("lockedAuthority",
                    lockedAuthorityField == null ? "" : lockedAuthorityField.getValue().trim());
            if (selfKiosk) {
                data.putString("issuedCode",
                        issuedCodeField == null ? "" : issuedCodeField.getValue().trim());
            }
            if (documentIssuer) {
                data.putBoolean("lockStyle", lockStyle);
                data.putString("lockedStyle", lockedStyle == null ? "" : lockedStyle.id());
            }
            if (documentIssuer || visaIssuer) {
                data.putBoolean("lockPlace", lockPlace);
                data.putString("lockedPlace",
                        lockedPlaceField == null ? "" : lockedPlaceField.getValue().trim());
            }
            data.putBoolean("lockIssueDate", lockIssueDate);
            data.putString("lockedIssueDate",
                    lockedIssueDateField == null ? "" : lockedIssueDateField.getValue().trim());
            data.putBoolean("lockValidity", lockValidity);
            data.putInt("lockedValidityYears", validityYears());
            if (visaIssuer || passportIssuer) {
                data.putBoolean("lockVisaType", lockVisaType);
                data.putString("lockedVisaType", lockedVisaType.id());
                data.putBoolean("lockVisaCode", lockVisaCode);
                data.putString("lockedVisaCode", lockedVisaCodeField == null ? ""
                        : ScreenCodes.validOrBlank(lockedVisaCodeField.getValue()));
            }
            if (endorsementIssuer) {
                data.putBoolean("lockEndorsementType", lockEndorsementType);
                data.putString("lockedEndorsementType", lockedEndorsementType.id());
            }
            if (permitIssuer) {
                data.putBoolean("lockEndorsementCode", lockEndorsementCode);
                data.putString("lockedEndorsementCode",
                        lockedEndorsementCodeField == null ? ""
                                : ScreenCodes.validOrBlank(lockedEndorsementCodeField.getValue()));
            }
            if (passportIssuer) {
                // 26.1 attached-visa locks. Keys follow the DeviceBlockEntity
                // contract exactly (lockedVisaValidityYears for lockVisaValidity).
                data.putBoolean("lockIncludeVisa", lockIncludeVisa);
                data.putBoolean("lockedIncludeVisa", includeVisaLocked);
                data.putBoolean("lockVisaEntries", lockVisaEntries);
                data.putInt("lockedVisaEntries", lockedVisaEntries());
                data.putBoolean("lockVisaCountry", lockVisaCountry);
                data.putString("lockedVisaCountry", lockedVisaCountryField == null ? ""
                        : lockedVisaCountryField.getValue().trim());
                data.putBoolean("lockVisaIssueDate", lockVisaIssueDate);
                data.putString("lockedVisaIssueDate", lockedVisaIssueDateField == null ? ""
                        : lockedVisaIssueDateField.getValue().trim());
                data.putBoolean("lockVisaValidity", lockVisaValidity);
                data.putInt("lockedVisaValidityYears", visaValidityYears());
                data.putBoolean("lockVisaName", lockVisaName);
                data.putString("lockedVisaName", lockedVisaNameField == null ? ""
                        : lockedVisaNameField.getValue().trim());
            } else if (visaIssuer) {
                data.putBoolean("lockVisaName", lockVisaName);
                data.putString("lockedVisaName", lockedVisaNameField == null ? ""
                        : lockedVisaNameField.getValue().trim());
            }
        }
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "save_config", data, slot));
        onClose();
    }

    private int validityYears() {
        if (lockedValidityYearsField == null) {
            return lockedValidityYears;
        }
        try {
            return Math.max(0, Integer.parseInt(lockedValidityYearsField.getValue().trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private int visaValidityYears() {
        if (lockedVisaValidityYearsField == null) {
            return lockedVisaValidityYears;
        }
        try {
            return Math.max(0, Integer.parseInt(lockedVisaValidityYearsField.getValue().trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private int lockedVisaEntries() {
        if (lockedVisaEntriesField == null) {
            return lockedVisaEntries;
        }
        try {
            return Math.max(0, Integer.parseInt(lockedVisaEntriesField.getValue().trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private int overstayDays() {
        if (overstayDaysField == null) {
            return overstayDays;
        }
        try {
            return clampOverstay(Integer.parseInt(overstayDaysField.getValue().trim()));
        } catch (NumberFormatException ignored) {
            return overstayDays;
        }
    }

    private static int clampOverstay(int value) {
        return Math.max(0, Math.min(DeviceBlockEntity.MAX_OVERSTAY_DAYS, value));
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, Component.translatable(device.translationKey()).getString(),
                Component.translatable(device.descriptionKey()).getString());
        Ui.clipped(graphics, Component.translatable("screen.veriborder.config.hint"), left + 24, top + 44,
                392, Ui.MUTED);
        if (bindingGate) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.gate_hint"),
                    left + 24, top + 82, 392, Ui.DIM);
            if (codeError.isBlank()) {
                Ui.clipped(graphics, Component.translatable("screen.veriborder.config.gate_codes",
                        gateCodes.size()).getString(), left + 24, top + 124, 392, Ui.TEXT);
            } else {
                Ui.clipped(graphics, Component.translatable(codeError).getString(),
                        left + 24, top + 124, 392, Ui.RED);
            }
            String summary = gateCodes.isEmpty()
                    ? Component.translatable("screen.veriborder.config.gate_codes_none").getString()
                    : String.join(" · ", gateCodes.subList(Math.max(0, gateCodes.size() - 3), gateCodes.size()));
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.gate_codes_list",
                    summary).getString(), left + 24, top + 136, 392, Ui.MUTED);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.gate_codes_hint"),
                    left + 24, top + 170, 392, Ui.DIM);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.gate_codes_hint2"),
                    left + 24, top + 182, 392, Ui.DIM);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.gate_behavior_hint"),
                    left + 24, top + 196, 392, Ui.DIM);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.airport_hint"),
                    left + 24, top + 378, 392, Ui.DIM);
        } else if (device.isGate()) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.exit_gate_hint"),
                    left + 24, top + 58, 392, Ui.AMBER);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.airport_hint"),
                    left + 24, top + 200, 392, Ui.DIM);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.gate_behavior_hint"),
                    left + 24, top + 210, 392, Ui.DIM);
        } else if (issuer) {
            // Second header line explains the lock section without overlapping
            // the first field label chip (which starts at y=64). The compact
            // 32px field step leaves room for the airport hint above the locks.
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.lock_hint"),
                    left + 24, top + 54, 392, Ui.DIM);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.airport_hint"),
                    left + 24, top + 190, 392, Ui.DIM);
        } else {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.airport_hint"),
                    left + 24, top + 202, 392, Ui.DIM);
        }
    }

    private static String normalizeMode(String value) {
        if (value != null) {
            String key = value.trim().toLowerCase(Locale.ROOT);
            switch (key) {
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
                    return "all";
                }
            }
        }
        return "all";
    }

    private static String nextMode(String value) {
        int index = 0;
        for (int i = 0; i < GATE_MODES.length; i++) {
            if (GATE_MODES[i].equals(value)) {
                index = i;
                break;
            }
        }
        return GATE_MODES[(index + 1) % GATE_MODES.length];
    }
}
