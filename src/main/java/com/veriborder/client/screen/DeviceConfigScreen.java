package com.veriborder.client.screen;

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

/**
 * Brush-only terminal configuration. Reached exclusively through
 * {@code DeviceService.openConfig} ("config" packet), which requires the
 * Device Brush in either hand.
 *
 * <p>Round 5 adds the universal issuer lock controls. The lock section is laid
 * out as a two-column grid: each item is a small on/off toggle with its locked
 * value underneath, so even six locks (passport issuer: layout, place, issue,
 * validity, attached-visa type and code) fit above the save row at GUI scale
 * 2 (480px design height = exactly 240px on a 854x480 screen).</p>
 */
public class DeviceConfigScreen extends VeriScreen {
    private static final String[] GATE_MODES = {
            "all", "visa", "endorsement", "both"
    };

    /** Tallest design height that still fits a 854x480 viewport at GUI scale 2. */
    private static final int ISSUER_HEIGHT = 480;
    private static final int LOCK_ITEM_PITCH = 46;
    private static final int LOCK_VALUE_OFFSET = 28;
    private static final int LOCK_COLUMN_WIDTH = 190;

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
    private DocStyle lockedStyle;
    private VisaType lockedVisaType = VisaType.TOURIST;
    private EndorsementType lockedEndorsementType = EndorsementType.ONE_YEAR_MULTI;
    private int lockedValidityYears;

    public DeviceConfigScreen(DeviceType device, BlockPos pos, CompoundTag payload, int slot) {
        super(Component.translatable("screen.veriborder.config"), 440,
                device.isIssuer() ? ISSUER_HEIGHT
                        : device.isGate() && device != DeviceType.EXIT_GATE ? 378 : 268);
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

        this.firstFieldY = bindingGate ? 204 : 76;
        if (bindingGate) {
            this.lockGridStartY = 0;
            this.saveY = 350;
        } else if (issuer) {
            this.lockGridStartY = selfKiosk ? 308 : 292;
            int rows = (lockItemCount() + 1) / 2;
            this.saveY = lockGridStartY + (rows - 1) * LOCK_ITEM_PITCH + 42 + 14;
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
                    "hint.veriborder.code_8");
            gateCode.setMaxLength(8);
            gateCode.setFilter(ScreenCodes::digitsUpTo8);
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
     * round-5 locks follow in a compact two-column grid.
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
            int ix = lockColumnX(index);
            int iy = lockRowY(index);
            index++;
            addRenderableWidget(new BetterButton(ix, iy, LOCK_COLUMN_WIDTH, 16, lockStyleLabel(), button -> {
                lockStyle = !lockStyle;
                button.setMessage(lockStyleLabel());
            }, Ui.AMBER, true));
            addRenderableWidget(new BetterButton(ix, iy + LOCK_VALUE_OFFSET, LOCK_COLUMN_WIDTH, 16,
                    Component.translatable(lockedStyle.shortNameKey()), button -> {
                        lockedStyle = DocStyle.nextForKind(documentKind, lockedStyle);
                        button.setMessage(Component.translatable(lockedStyle.shortNameKey()));
                    }, accent, true));

            ix = lockColumnX(index);
            iy = lockRowY(index);
            index++;
            addRenderableWidget(new BetterButton(ix, iy, LOCK_COLUMN_WIDTH, 16, lockPlaceLabel(), button -> {
                lockPlace = !lockPlace;
                button.setMessage(lockPlaceLabel());
            }, Ui.AMBER, true));
            lockedPlaceField = addField(ix, iy + LOCK_VALUE_OFFSET, LOCK_COLUMN_WIDTH,
                    "field.veriborder.locked_place", payload.getString("lockedPlace"),
                    "hint.veriborder.when_locked");

            ix = lockColumnX(index);
            iy = lockRowY(index);
            index++;
            addRenderableWidget(new BetterButton(ix, iy, LOCK_COLUMN_WIDTH, 16, lockIssueLabel(), button -> {
                lockIssueDate = !lockIssueDate;
                button.setMessage(lockIssueLabel());
            }, Ui.AMBER, true));
            lockedIssueDateField = addField(ix, iy + LOCK_VALUE_OFFSET, LOCK_COLUMN_WIDTH,
                    "field.veriborder.locked_issue", defaultIssueDate(payload.getString("lockedIssueDate")),
                    "hint.veriborder.date");

            ix = lockColumnX(index);
            iy = lockRowY(index);
            index++;
            addRenderableWidget(new BetterButton(ix, iy, LOCK_COLUMN_WIDTH, 16, lockValidityLabel(), button -> {
                lockValidity = !lockValidity;
                button.setMessage(lockValidityLabel());
            }, Ui.AMBER, true));
            lockedValidityYearsField = addValidityYearsField(ix, iy + LOCK_VALUE_OFFSET);

            if (device == DeviceType.PASSPORT_ISSUER) {
                ix = lockColumnX(index);
                iy = lockRowY(index);
                index++;
                addRenderableWidget(new BetterButton(ix, iy, LOCK_COLUMN_WIDTH, 16, lockVisaTypeLabel(), button -> {
                    lockVisaType = !lockVisaType;
                    button.setMessage(lockVisaTypeLabel());
                }, Ui.AMBER, true));
                addRenderableWidget(new BetterButton(ix, iy + LOCK_VALUE_OFFSET, LOCK_COLUMN_WIDTH, 16,
                        Component.translatable(lockedVisaType.translationKey()), button -> {
                            lockedVisaType = lockedVisaType.next();
                            button.setMessage(Component.translatable(lockedVisaType.translationKey()));
                        }, Ui.PURPLE, true));

                ix = lockColumnX(index);
                iy = lockRowY(index);
                index++;
                addRenderableWidget(new BetterButton(ix, iy, LOCK_COLUMN_WIDTH, 16, lockVisaCodeLabel(), button -> {
                    lockVisaCode = !lockVisaCode;
                    button.setMessage(lockVisaCodeLabel());
                }, Ui.AMBER, true));
                lockedVisaCodeField = addLockedVisaCodeField(ix, iy + LOCK_VALUE_OFFSET);
            }
        } else if (visaIssuer) {
            int ix = lockColumnX(index);
            int iy = lockRowY(index);
            index++;
            addRenderableWidget(new BetterButton(ix, iy, LOCK_COLUMN_WIDTH, 16, lockVisaTypeLabel(), button -> {
                lockVisaType = !lockVisaType;
                button.setMessage(lockVisaTypeLabel());
            }, Ui.AMBER, true));
            addRenderableWidget(new BetterButton(ix, iy + LOCK_VALUE_OFFSET, LOCK_COLUMN_WIDTH, 16,
                    Component.translatable(lockedVisaType.translationKey()), button -> {
                        lockedVisaType = lockedVisaType.next();
                        button.setMessage(Component.translatable(lockedVisaType.translationKey()));
                    }, Ui.PURPLE, true));

            ix = lockColumnX(index);
            iy = lockRowY(index);
            index++;
            addRenderableWidget(new BetterButton(ix, iy, LOCK_COLUMN_WIDTH, 16, lockVisaCodeLabel(), button -> {
                lockVisaCode = !lockVisaCode;
                button.setMessage(lockVisaCodeLabel());
            }, Ui.AMBER, true));
            lockedVisaCodeField = addLockedVisaCodeField(ix, iy + LOCK_VALUE_OFFSET);

            ix = lockColumnX(index);
            iy = lockRowY(index);
            index++;
            addRenderableWidget(new BetterButton(ix, iy, LOCK_COLUMN_WIDTH, 16, lockPlaceLabel(), button -> {
                lockPlace = !lockPlace;
                button.setMessage(lockPlaceLabel());
            }, Ui.AMBER, true));
            lockedPlaceField = addField(ix, iy + LOCK_VALUE_OFFSET, LOCK_COLUMN_WIDTH,
                    "field.veriborder.locked_place", payload.getString("lockedPlace"),
                    "hint.veriborder.when_locked");

            ix = lockColumnX(index);
            iy = lockRowY(index);
            index++;
            addRenderableWidget(new BetterButton(ix, iy, LOCK_COLUMN_WIDTH, 16, lockIssueLabel(), button -> {
                lockIssueDate = !lockIssueDate;
                button.setMessage(lockIssueLabel());
            }, Ui.AMBER, true));
            lockedIssueDateField = addField(ix, iy + LOCK_VALUE_OFFSET, LOCK_COLUMN_WIDTH,
                    "field.veriborder.locked_issue", defaultIssueDate(payload.getString("lockedIssueDate")),
                    "hint.veriborder.date");

            ix = lockColumnX(index);
            iy = lockRowY(index);
            index++;
            addRenderableWidget(new BetterButton(ix, iy, LOCK_COLUMN_WIDTH, 16, lockValidityLabel(), button -> {
                lockValidity = !lockValidity;
                button.setMessage(lockValidityLabel());
            }, Ui.AMBER, true));
            lockedValidityYearsField = addValidityYearsField(ix, iy + LOCK_VALUE_OFFSET);
        } else if (endorsementIssuer) {
            int ix = lockColumnX(index);
            int iy = lockRowY(index);
            index++;
            addRenderableWidget(new BetterButton(ix, iy, LOCK_COLUMN_WIDTH, 16, lockEndorsementTypeLabel(), button -> {
                lockEndorsementType = !lockEndorsementType;
                button.setMessage(lockEndorsementTypeLabel());
            }, Ui.AMBER, true));
            addRenderableWidget(new BetterButton(ix, iy + LOCK_VALUE_OFFSET, LOCK_COLUMN_WIDTH, 16,
                    Component.translatable(lockedEndorsementType.translationKey()), button -> {
                        lockedEndorsementType = lockedEndorsementType.next();
                        button.setMessage(Component.translatable(lockedEndorsementType.translationKey()));
                    }, Ui.PURPLE, true));

            ix = lockColumnX(index);
            iy = lockRowY(index);
            index++;
            addRenderableWidget(new BetterButton(ix, iy, LOCK_COLUMN_WIDTH, 16, lockIssueLabel(), button -> {
                lockIssueDate = !lockIssueDate;
                button.setMessage(lockIssueLabel());
            }, Ui.AMBER, true));
            lockedIssueDateField = addField(ix, iy + LOCK_VALUE_OFFSET, LOCK_COLUMN_WIDTH,
                    "field.veriborder.locked_issue", defaultIssueDate(payload.getString("lockedIssueDate")),
                    "hint.veriborder.date");

            ix = lockColumnX(index);
            iy = lockRowY(index);
            index++;
            addRenderableWidget(new BetterButton(ix, iy, LOCK_COLUMN_WIDTH, 16, lockValidityLabel(), button -> {
                lockValidity = !lockValidity;
                button.setMessage(lockValidityLabel());
            }, Ui.AMBER, true));
            lockedValidityYearsField = addValidityYearsField(ix, iy + LOCK_VALUE_OFFSET);
        }

        if (permitIssuer) {
            int ix = lockColumnX(index);
            int iy = lockRowY(index);
            index++;
            addRenderableWidget(new BetterButton(ix, iy, LOCK_COLUMN_WIDTH, 16,
                    lockEndorsementCodeLabel(), button -> {
                        lockEndorsementCode = !lockEndorsementCode;
                        button.setMessage(lockEndorsementCodeLabel());
                    }, Ui.AMBER, true));
            lockedEndorsementCodeField = addField(ix, iy + LOCK_VALUE_OFFSET, LOCK_COLUMN_WIDTH,
                    "field.veriborder.locked_endorsement_code",
                    payload.getString("lockedEndorsementCode"), "hint.veriborder.code_8");
            lockedEndorsementCodeField.setMaxLength(8);
            lockedEndorsementCodeField.setFilter(ScreenCodes::digitsUpTo8);
        }
    }

    private EditBox addValidityYearsField(int x, int y) {
        EditBox field = addField(x, y, LOCK_COLUMN_WIDTH, "field.veriborder.locked_validity",
                String.valueOf(lockedValidityYears), "hint.veriborder.zero_permanent");
        field.setMaxLength(3);
        field.setFilter(value -> value.isEmpty()
                || (value.length() <= 3 && value.chars().allMatch(c -> c >= '0' && c <= '9')));
        return field;
    }

    private EditBox addLockedVisaCodeField(int x, int y) {
        EditBox field = addField(x, y, LOCK_COLUMN_WIDTH, "field.veriborder.locked_visa_code",
                payload.getString("lockedVisaCode"), "hint.veriborder.code_8");
        field.setMaxLength(8);
        field.setFilter(ScreenCodes::digitsUpTo8);
        return field;
    }

    private int lockColumnX(int index) {
        return left + 24 + (index % 2) * 202;
    }

    private int lockRowY(int index) {
        return top + lockGridStartY + (index / 2) * LOCK_ITEM_PITCH;
    }

    private int lockItemCount() {
        if (documentIssuer) {
            return 4 + (device == DeviceType.PASSPORT_ISSUER ? 2 : 0) + (permitIssuer ? 1 : 0);
        }
        if (visaIssuer) {
            return 5;
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

    private void addGateCode() {
        String value = gateCode == null ? "" : gateCode.getValue().trim();
        if (!ScreenCodes.isValid(value)) {
            codeError = "message.veriborder.code.invalid";
            return;
        }
        if (gateCodes.contains(value)) {
            codeError = "message.veriborder.code.duplicate";
            return;
        }
        gateCodes.add(value);
        gateCode.setValue("");
        codeError = "";
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
            if (visaIssuer || device == DeviceType.PASSPORT_ISSUER) {
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
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.airport_hint"),
                    left + 24, top + 332, 392, Ui.DIM);
        } else if (device.isGate()) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.exit_gate_hint"),
                    left + 24, top + 58, 392, Ui.AMBER);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.config.airport_hint"),
                    left + 24, top + 202, 392, Ui.DIM);
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
