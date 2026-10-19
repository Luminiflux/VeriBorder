package com.veriborder.client.screen;

import com.veriborder.block.DeviceType;
import com.veriborder.client.BetterButton;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.doc.DateUtil;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentFactory;
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
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class IssuerScreen extends VeriScreen {
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
    private final int lockedValidityYears;
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
    private final int lockedVisaValidityYears;
    private final boolean lockVisaName;
    private final String lockedVisaName;
    private final List<DocStyle> styles;
    private DocStyle style;
    private int presetIndex;
    private CompoundTag formCache;
    private EditBox name;
    private EditBox nameLatin;
    private EditBox number;
    private EditBox birth;
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
    private BetterButton styleButton;
    private BetterButton visaTypeButton;
    private BetterButton includeVisaButton;
    private BetterButton sexButton;
    private String sex = "M";
    private VisaType visaType;
    private boolean includeVisa;
    private final String[] endorsementChoices = {
            "none", EndorsementType.ONE_YEAR_MULTI.id(), EndorsementType.THREE_MONTH_SINGLE.id(),
            EndorsementType.OTHER.id()
    };
    private int endorsementChoice = 1;

    public IssuerScreen(DeviceType device, BlockPos pos, CompoundTag payload) {
        super(Component.translatable(device.translationKey()), 520, heightFor(device, payload));
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
        this.lockDocumentName = payload.getBoolean("lockDocumentName") && !lockedDocumentName.isBlank();
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
        this.lockIssueDate = payload.getBoolean("lockIssueDate") && DateUtil.isValid(issueValue);
        this.lockValidity = payload.getBoolean("lockValidity");
        this.lockedValidityYears = payload.getInt("lockedValidityYears");
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
        this.lockedVisaValidityYears = payload.getInt("lockedVisaValidityYears");
        String visaNameValue = payload.getString("lockedVisaName").trim();
        this.lockedVisaName = visaNameValue;
        // A locked blank name is meaningful: it forces the country/type fallback.
        this.lockVisaName = payload.getBoolean("lockVisaName");
        this.visaType = lockVisaType ? lockedVisaType : VisaType.TOURIST;
        this.accent = device.accentColor();
    }

    /**
     * Passports grow to fit the optional attached-visa panel; other issuers only
     * grow when a locked validity adds its auto-calculation summary line.
     */
    private static int heightFor(DeviceType device, CompoundTag payload) {
        if (device == DeviceType.PASSPORT_ISSUER) {
            return 480;
        }
        return payload.getBoolean("lockValidity") ? 376 : 360;
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

    @Override
    protected void build() {
        if (formCache == null) {
            formCache = defaults();
        }
        applyLockedValues();
        styleButton = addRenderableWidget(new BetterButton(left + 24, top + 46, 210, 20,
                Component.literal(Component.translatable(style.displayNameKey()).getString()), button -> cycleStyle(), accent));
        if (lockStyle) {
            styleButton.active = false;
        }
        addRenderableWidget(new BetterButton(left + 242, top + 46, 120, 20,
                Component.translatable("button.veriborder.defaults"), button -> {
                    sex = "M";
                    includeVisa = lockIncludeVisa ? lockedIncludeVisa : false;
                    if (kind == DocumentKind.PASSPORT && !lockVisaType) {
                        visaType = VisaType.TOURIST;
                    }
                    formCache = defaults();
                    rebuildWidgets();
                }, Ui.MUTED, true));

        // Permanent document name: empty means the official template name.
        displayName = addField(left + 24, top + 84, 300, "field.veriborder.display_name",
                formCache.getString("displayName"),
                lockDocumentName ? "hint.veriborder.locked" : "hint.veriborder.blank_official");
        if (lockDocumentName) {
            displayName.setValue(lockedDocumentName);
            displayName.setEditable(false);
        }
        name = addField(left + 24, top + 114, 214, "field.veriborder.name", formCache.getString("name"));
        nameLatin = addField(left + 266, top + 114, 230, "field.veriborder.name_latin", formCache.getString("nameLatin"));
        number = addField(left + 24, top + 144, 214, "field.veriborder.number", formCache.getString("number"),
                "hint.veriborder.auto");
        sexButton = addRenderableWidget(new BetterButton(left + 266, top + 140, 72, 20,
                Component.translatable("sex.veriborder." + sexKey(sex)), button -> {
                    sex = switch (sex) {
                        case "M" -> "F";
                        case "F" -> "X";
                        default -> "M";
                    };
                    rebuildWidgets();
                }, Ui.CYAN, true));
        birth = addField(left + 24, top + 174, 214, "field.veriborder.birth", formCache.getString("birthDate"),
                "hint.veriborder.date");
        nationality = addField(left + 266, top + 174, 230, "field.veriborder.nationality", formCache.getString("nationality"));
        authority = addField(left + 24, top + 204, 214, "field.veriborder.issuer",
                formCache.getString("authority"), lockAuthority ? "hint.veriborder.locked" : null);
        if (lockAuthority) {
            authority.setValue(lockedAuthority);
            authority.setEditable(false);
        }
        place = addField(left + 266, top + 204, 230, "field.veriborder.place",
                formCache.getString("placeOfIssue"), lockPlace ? "hint.veriborder.locked" : null);
        if (lockPlace) {
            place.setValue(lockedPlace);
            place.setEditable(false);
        }
        issue = addField(left + 24, top + 234, 214, "field.veriborder.issue",
                formCache.getString("issueDate"), lockIssueDate ? "hint.veriborder.locked" : "hint.veriborder.date");
        if (lockIssueDate) {
            issue.setValue(lockedIssueDate);
            issue.setEditable(false);
        }
        expiry = addField(left + 266, top + 234, 230, "field.veriborder.expiry",
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
        address = addField(left + 24, top + 264, 472, "field.veriborder.address", formCache.getString("address"));

        presetName = addField(left + 24, top + 296, 190, "field.veriborder.preset_name",
                payload.getList("presets", Tag.TAG_COMPOUND).isEmpty() ? "Standard"
                        : payload.getList("presets", Tag.TAG_COMPOUND).getCompound(0).getString("name"),
                "hint.veriborder.preset");
        addRenderableWidget(new BetterButton(left + 224, top + 292, 30, 20, Component.literal("◀"),
                button -> movePreset(-1), Ui.MUTED, true));
        addRenderableWidget(new BetterButton(left + 258, top + 292, 30, 20, Component.literal("▶"),
                button -> movePreset(1), Ui.MUTED, true));
        addRenderableWidget(new BetterButton(left + 294, top + 292, 66, 20,
                Component.translatable("button.veriborder.apply"), button -> applyPreset(), Ui.CYAN, true));
        addRenderableWidget(new BetterButton(left + 366, top + 292, 60, 20,
                Component.translatable("button.veriborder.save"), button -> savePreset(), Ui.CYAN, true));
        addRenderableWidget(new BetterButton(left + 432, top + 292, 64, 20,
                Component.translatable("button.veriborder.delete"), button -> deletePreset(), Ui.RED, true));

        if (kind == DocumentKind.PASSPORT) {
            buildVisaPanel();
        }

        int actionY = kind == DocumentKind.PASSPORT ? 442 : 330;
        addRenderableWidget(new BetterButton(left + 24, top + actionY, 130, 22,
                Component.translatable("button.veriborder.issue"), button -> issue(), Ui.GREEN));
        if (kind == DocumentKind.PERMIT) {
            addRenderableWidget(new BetterButton(left + 164, top + actionY, 190, 22,
                    Component.translatable("screen.veriborder.issuer.initial", endorsementLabel()),
                    button -> {
                        endorsementChoice = (endorsementChoice + 1) % endorsementChoices.length;
                        rebuildWidgets();
                    }, Ui.PURPLE, true));
        }
        addRenderableWidget(new BetterButton(left + 394, top + actionY, 102, 22,
                Component.translatable("button.veriborder.close"), button -> onClose(), Ui.MUTED, true));
    }

    /**
     * Optional passport-attached visa panel. Locked fields are seeded with the
     * terminal values and become read-only. Issue/expiry stay summary lines
     * because the visa inherits them from the passport, so a locked value is
     * simply shown here (the server applies it authoritatively).
     */
    private void buildVisaPanel() {
        includeVisaButton = addRenderableWidget(new BetterButton(left + 32, top + 334, 150, 18,
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
        visaCountry = addField(left + 200, top + 344, 130, "field.veriborder.visa_country",
                countryLocked ? lockedVisaCountryValue() : countryValue,
                countryLocked ? "hint.veriborder.locked" : null);
        if (countryLocked) {
            visaCountry.setValue(lockedVisaCountryValue());
            visaCountry.setEditable(false);
        }
        visaTypeButton = addRenderableWidget(new BetterButton(left + 340, top + 336, 148, 18,
                Component.translatable(visaType.translationKey()), button -> {
                    if (lockVisaType) {
                        return;
                    }
                    visaType = visaType.next();
                    button.setMessage(Component.translatable(visaType.translationKey()));
                }, Ui.PURPLE, true));
        if (lockVisaType) {
            visaTypeButton.active = false;
        }

        visaName = addField(left + 32, top + 380, 190, "field.veriborder.visa_name",
                formCache.getString("visaName"),
                lockVisaName ? "hint.veriborder.locked" : "hint.veriborder.visa_name");
        if (lockVisaName) {
            visaName.setValue(lockedVisaName);
            visaName.setEditable(false);
        }
        visaCode = addField(left + 232, top + 380, 130, "field.veriborder.visa_code",
                lockVisaCode ? lockedVisaCode : formCache.getString("visaCode"),
                lockVisaCode ? "hint.veriborder.locked" : "hint.veriborder.code");
        visaCode.setMaxLength(8);
        visaCode.setFilter(ScreenCodes::digitsUpTo8);
        if (lockVisaCode) {
            visaCode.setValue(lockedVisaCode);
            visaCode.setEditable(false);
        }
        String entriesValue = formCache.getString("visaEntries");
        visaEntries = addField(left + 372, top + 380, 60, "field.veriborder.entries",
                entriesValue.isBlank() ? "1" : entriesValue, "hint.veriborder.unlimited");
        visaEntries.setMaxLength(3);
        visaEntries.setFilter(value -> value.length() <= 3
                && value.chars().allMatch(character -> character >= '0' && character <= '9'));
        if (lockVisaEntries) {
            visaEntries.setValue(String.valueOf(lockedVisaEntries));
            visaEntries.setEditable(false);
        }
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
            formCache.putString("issueDate", lockedIssueDate);
        }
        if (lockValidity) {
            formCache.putString("expiryDate", autoExpiry(formCache.getString("issueDate")));
        }
        if (kind == DocumentKind.PASSPORT) {
            if (lockPlace) {
                formCache.putString("placeOfIssue", lockedPlace);
            }
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

    private String autoExpiry(String issueDate) {
        return lockedValidityYears <= 0 ? DateUtil.PERMANENT
                : DateUtil.plusYears(issueDate, lockedValidityYears);
    }

    private String visaIssueValue() {
        return lockVisaIssueDate ? lockedVisaIssueDate
                : issue == null ? formCache.getString("issueDate") : issue.getValue();
    }

    private String visaExpiryValue() {
        if (lockVisaValidity) {
            return lockedVisaValidityYears <= 0 ? DateUtil.PERMANENT
                    : DateUtil.plusYears(visaIssueValue(), lockedVisaValidityYears);
        }
        return expiry == null ? formCache.getString("expiryDate") : expiry.getValue();
    }

    private void cycleStyle() {
        if (lockStyle) {
            return;
        }
        captureFields();
        style = DocStyle.nextForKind(kind, style);
        formCache.putString("style", style.id());
        formCache.putString("authority", style.defaultAuthority());
        formCache.putString("nationality", style.defaultNationality());
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

    private void captureFields() {
        if (name == null) {
            return;
        }
        formCache.putString("style", style.id());
        formCache.putString("displayName", displayName.getValue());
        formCache.putString("name", name.getValue());
        formCache.putString("nameLatin", nameLatin.getValue());
        formCache.putString("number", number.getValue());
        formCache.putString("sex", sex);
        formCache.putString("birthDate", birth.getValue());
        formCache.putString("nationality", nationality.getValue());
        formCache.putString("authority", authority.getValue());
        formCache.putString("placeOfIssue", place.getValue());
        formCache.putString("issueDate", issue.getValue());
        formCache.putString("expiryDate", expiry.getValue());
        formCache.putString("address", address.getValue());
        if (kind == DocumentKind.PASSPORT && visaCountry != null) {
            formCache.putBoolean("includeVisa", lockIncludeVisa ? lockedIncludeVisa : includeVisa);
            formCache.putString("visaCountry", visaCountry.getValue());
            formCache.putString("visaType", visaType.id());
            formCache.putString("visaCode", visaCode == null ? "" : visaCode.getValue());
            formCache.putString("visaEntries", visaEntries == null ? "1" : visaEntries.getValue());
            formCache.putString("visaName", visaName == null ? "" : visaName.getValue());
        }
    }

    private List<CompoundTag> presets() {
        ListTag list = payload.getList("presets", Tag.TAG_COMPOUND);
        List<CompoundTag> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            result.add(list.getCompound(i));
        }
        return result;
    }

    private void movePreset(int direction) {
        List<CompoundTag> list = presets();
        if (list.isEmpty()) {
            return;
        }
        presetIndex = (presetIndex + direction + list.size()) % list.size();
        presetName.setValue(list.get(presetIndex).getString("name"));
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
        }
        presetName.setValue(entry.getString("name"));
        rebuildWidgets();
        if (presetName != null) {
            presetName.setValue(entry.getString("name"));
        }
    }

    private void savePreset() {
        captureFields();
        CompoundTag data = formCache.copy();
        data.putString("presetName", presetName.getValue().isBlank() ? "Standard" : presetName.getValue());
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "save_preset", data, -1));
    }

    private void deletePreset() {
        CompoundTag data = new CompoundTag();
        data.putString("presetName", presetName.getValue());
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "delete_preset", data, -1));
    }

    private void issue() {
        captureFields();
        CompoundTag data = formCache.copy();
        data.putString("style", style.id());
        data.putString("sex", sex);
        if (kind == DocumentKind.PERMIT && !"none".equals(endorsementChoices[endorsementChoice])) {
            data.putString("initialEndorsementType", endorsementChoices[endorsementChoice]);
        }
        if (kind == DocumentKind.PASSPORT) {
            data.putBoolean("includeVisa", lockIncludeVisa ? lockedIncludeVisa : includeVisa);
            data.putString("visaCountry", visaCountry == null ? "" : visaCountry.getValue());
            data.putString("visaType", visaType.id());
            data.putString("visaCode", visaCode == null ? "" : visaCode.getValue().trim());
            data.putInt("visaEntries", lockVisaEntries ? lockedVisaEntries
                    : parseEntries(visaEntries == null ? "1" : visaEntries.getValue()));
            data.putString("visaName", visaName == null ? "" : visaName.getValue().trim());
        }
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "issue_document", data, -1));
    }

    private static int parseEntries(String value) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private Component endorsementLabel() {
        String id = endorsementChoices[endorsementChoice];
        return "none".equals(id) ? Component.translatable("screen.veriborder.issuer.none")
                : Component.translatable(EndorsementType.byId(id).translationKey());
    }

    private static String sexKey(String sex) {
        return switch (sex) {
            case "F" -> "female";
            case "X" -> "x";
            default -> "male";
        };
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, Component.translatable(device.translationKey()).getString(),
                Component.translatable(device.descriptionKey()).getString());
        graphics.fill(left + 24, top + 68, left + 496, top + 69, Ui.BORDER);
        String custom = displayName == null ? formCache.getString("displayName") : displayName.getValue();
        String effective = custom == null || custom.isBlank()
                ? Component.translatable(style.displayNameKey()).getString() : custom.trim();
        graphics.drawString(font, Ui.ellipsis(Component.translatable("screen.veriborder.issuer.effective",
                effective).getString(), 160), left + 330, top + 87, custom == null || custom.isBlank()
                ? Ui.DIM : Ui.CYAN, false);
        if (kind == DocumentKind.PASSPORT) {
            renderVisaPanel(graphics);
        }
        if (kind != DocumentKind.PERMIT) {
            int hintY = kind == DocumentKind.PASSPORT ? top + 444 : top + 338;
            Ui.clipped(graphics, Component.translatable("screen.veriborder.issuer.hint"), left + 164,
                    hintY, 222, Ui.DIM);
        }
        if (lockValidity) {
            String years = lockedValidityYears <= 0
                    ? Component.translatable("date.veriborder.permanent").getString()
                    : Component.translatable("screen.veriborder.years", lockedValidityYears).getString();
            String expiryValue = expiry == null ? formCache.getString("expiryDate") : expiry.getValue();
            int y = kind == DocumentKind.PASSPORT ? top + 468 : top + 360;
            Ui.clipped(graphics, Component.translatable("screen.veriborder.issuer.validity_locked",
                    years, expiryValue), left + 24, y, 472, Ui.DIM);
        }
    }

    private void renderVisaPanel(GuiGraphics graphics) {
        // Translucent grouping panel drawn after the field chrome: opaque text
        // wells and label chips stay fully legible underneath.
        graphics.fill(left + 24, top + 318, left + 496, top + 436, 0x181E3247);
        graphics.renderOutline(left + 24, top + 318, 472, 118, Ui.BORDER);
        graphics.drawString(font, Component.translatable("screen.veriborder.issuer.visa_panel").getString(),
                left + 32, top + 322, Ui.GOLD, false);
        graphics.drawString(font, Component.translatable("field.veriborder.visa_type").getString(),
                left + 340, top + 326, Ui.DIM, false);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.issuer.visa_issue", visaIssueValue()),
                left + 32, top + 398, 200, lockVisaIssueDate ? Ui.AMBER : Ui.DIM);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.issuer.visa_expiry", visaExpiryValue()),
                left + 32, top + 409, 200, lockVisaValidity ? Ui.AMBER : Ui.DIM);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.issuer.visa_hint"),
                left + 32, top + 421, 440, Ui.DIM);
    }
}
