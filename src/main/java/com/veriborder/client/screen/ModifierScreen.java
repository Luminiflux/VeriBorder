package com.veriborder.client.screen;

import com.veriborder.client.BetterButton;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.doc.CredentialCode;
import com.veriborder.doc.DateUtil;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentKind;
import com.veriborder.doc.DocumentStatus;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.EndorsementType;
import com.veriborder.doc.VisaType;
import com.veriborder.network.ModNetwork;
import com.veriborder.network.ModifierSavePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class ModifierScreen extends VeriScreen {
    private record Entry(int slot, CompoundTag tag) {
    }

    private static final int TAB_PROFILE = 0;
    private static final int TAB_STYLE = 1;
    private static final int TAB_VISAS = 2;
    private static final int TAB_ENDORSEMENTS = 3;
    private static final int TAB_STAMPS = 4;

    private final List<Entry> entries = new ArrayList<>();
    private int selected;
    private int tab = TAB_PROFILE;
    private int listScroll;
    private int recordScroll;
    private CompoundTag working;
    private DocStyle style;
    private String sex = "M";
    private String status = "valid";

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
    private EditBox customName;
    private EditBox customShort;
    private EditBox customType;
    private EditBox customColor;
    private BetterButton sexButton;
    private BetterButton statusButton;
    private BetterButton styleButton;

    private boolean editingVisa;
    private boolean editingEndorsement;
    private int editIndex = -1;
    private int visaSelected = -1;
    private int endorsementSelected = -1;
    private int stampSelected = -1;
    private VisaType visaType = VisaType.TOURIST;
    private EndorsementType endorsementType = EndorsementType.ONE_YEAR_MULTI;
    private String recordStatus = "ACTIVE";
    private EditBox recCountry;
    private EditBox recEntries;
    private EditBox recIssue;
    private EditBox recExpiry;
    private EditBox recAuthority;
    private EditBox recCode;
    private BetterButton recTypeButton;
    private BetterButton recStatusButton;
    private EditBox visaFreeCode;
    private String visaFreeError = "";

    public ModifierScreen(CompoundTag payload) {
        super(Component.translatable("screen.veriborder.modifier"), 520, 360);
        this.accent = Ui.GOLD;
        ListTag docs = payload.getList("docs", Tag.TAG_COMPOUND);
        for (int i = 0; i < docs.size(); i++) {
            CompoundTag entry = docs.getCompound(i);
            entries.add(new Entry(entry.getInt("slot"), entry.getCompound("doc").copy()));
        }
        if (!entries.isEmpty()) {
            load(0);
        }
    }

    private void load(int index) {
        selected = index;
        working = entries.get(index).tag().copy();
        style = DocStyle.byId(working.getString("style"));
        sex = working.getString("sex").isBlank() ? "M" : working.getString("sex");
        status = working.getString("status").isBlank() ? "valid" : working.getString("status");
        visaSelected = -1;
        endorsementSelected = -1;
        stampSelected = -1;
        editingVisa = false;
        editingEndorsement = false;
        editIndex = -1;
        recordScroll = 0;
        visaFreeError = "";
    }

    @Override
    protected void build() {
        if (working == null) {
            addRenderableWidget(new BetterButton(left + 20, top + 300, 140, 22,
                    Component.translatable("button.veriborder.generate"), button -> openGenerator(), Ui.GREEN));
            addRenderableWidget(new BetterButton(left + 170, top + 300, 120, 22,
                    Component.translatable("button.veriborder.close"), button -> onClose(), Ui.MUTED, true));
            return;
        }
        accent = DocumentData.fromTag(working).accentColor();
        int tabX = left + 170;
        int tabY = top + 48;
        int tabWidth = 64;
        addTab(tabX, tabY, tabWidth, TAB_PROFILE, "tab.veriborder.profile");
        addTab(tabX + 67, tabY, tabWidth, TAB_STYLE, "tab.veriborder.style");
        addTab(tabX + 134, tabY, tabWidth, TAB_VISAS, "tab.veriborder.visas");
        addTab(tabX + 201, tabY, tabWidth, TAB_ENDORSEMENTS, "tab.veriborder.endorsements");
        addTab(tabX + 268, tabY, tabWidth, TAB_STAMPS, "tab.veriborder.stamps");

        if (tab == TAB_PROFILE) {
            buildProfile();
        } else if (tab == TAB_STYLE) {
            buildStyle();
        } else if (tab == TAB_VISAS) {
            buildVisas();
        } else if (tab == TAB_ENDORSEMENTS) {
            buildEndorsements();
        } else {
            buildStamps();
        }
        addRenderableWidget(new BetterButton(left + 440, top + 330, 70, 22,
                Component.translatable("button.veriborder.save"), button -> save(), Ui.GREEN));
        addRenderableWidget(new BetterButton(left + 102, top + 330, 100, 22,
                Component.translatable("button.veriborder.generate"), button -> openGenerator(), Ui.GOLD, true));
        addRenderableWidget(new BetterButton(left + 20, top + 330, 76, 22,
                Component.translatable("button.veriborder.close"), button -> onClose(), Ui.MUTED, true));
    }

    private void openGenerator() {
        net.minecraft.client.Minecraft.getInstance().setScreen(new GeneratorScreen());
    }

    private void addTab(int x, int y, int width, int index, String key) {
        BetterButton button = new BetterButton(x, y, width, 20, Component.translatable(key),
                pressed -> {
                    if (tab == TAB_STYLE) {
                        commitCustomStyle();
                    }
                    captureProfile();
                    tab = index;
                    editingVisa = false;
                    editingEndorsement = false;
                    recordScroll = 0;
                    visaFreeError = "";
                    rebuildWidgets();
                }, accent, tab != index);
        if (tab == index) {
            button.setFocused(false);
        }
        addRenderableWidget(button);
    }

    private void buildProfile() {
        int x1 = left + 174;
        int x2 = left + 344;
        int y = top + 82;
        // Independent display name (overrides custom cover name and official name).
        displayName = addField(x1, y, 320, "field.veriborder.display_name",
                working.getString("displayName"), "hint.veriborder.blank_official");
        name = addField(x1, y + 32, 150, "field.veriborder.name", working.getString("name"));
        nameLatin = addField(x2, y + 32, 150, "field.veriborder.name_latin", working.getString("nameLatin"));
        number = addField(x1, y + 64, 150, "field.veriborder.number", working.getString("number"), "hint.veriborder.auto");
        sexButton = addRenderableWidget(new BetterButton(x2, y + 60, 74, 20,
                Component.translatable("sex.veriborder." + sexKey(sex)), button -> {
                    captureProfile();
                    sex = switch (sex) {
                        case "M" -> "F";
                        case "F" -> "X";
                        default -> "M";
                    };
                    rebuildWidgets();
                }, Ui.CYAN, true));
        statusButton = addRenderableWidget(new BetterButton(x2 + 78, y + 60, 72, 20,
                Component.translatable("status.veriborder." + status), button -> {
                    captureProfile();
                    status = DocumentStatus.byId(status).next().id();
                    rebuildWidgets();
                }, DocumentStatus.byId(status).color(), true));
        birth = addField(x1, y + 96, 150, "field.veriborder.birth", working.getString("birthDate"), "hint.veriborder.date");
        nationality = addField(x2, y + 96, 150, "field.veriborder.nationality", working.getString("nationality"));
        authority = addField(x1, y + 128, 150, "field.veriborder.issuer", working.getString("authority"));
        place = addField(x2, y + 128, 150, "field.veriborder.place", working.getString("placeOfIssue"));
        issue = addField(x1, y + 160, 150, "field.veriborder.issue", working.getString("issueDate"), "hint.veriborder.date");
        expiry = addField(x2, y + 160, 150, "field.veriborder.expiry", working.getString("expiryDate"), "hint.veriborder.date");
        address = addField(x1, y + 192, 320, "field.veriborder.address", working.getString("address"));
    }

    private void buildStyle() {
        addRenderableWidget(new BetterButton(left + 404, top + 82, 100, 20,
                Component.translatable("button.veriborder.change_style"), button -> {
                    captureProfile();
                    style = DocStyle.nextForKind(style.kind(), style);
                    working.putString("style", style.id());
                    working.putString("kind", style.kind().name());
                    working.remove("customStyle");
                    working.remove("customName");
                    working.remove("customShortName");
                    working.remove("customType");
                    working.remove("customColor");
                    DocumentData.fromTag(working).sign();
                    rebuildWidgets();
                }, accent, true));
        DocumentData data = DocumentData.fromTag(working);
        customName = addField(left + 174, top + 244, 150, "field.veriborder.custom_name",
                data.customStyle() ? working.getString("customName")
                        : Component.translatable(style.displayNameKey()).getString(), "hint.veriborder.cover");
        customShort = addField(left + 334, top + 244, 168, "field.veriborder.custom_short",
                data.customStyle() ? working.getString("customShortName") : data.shortName());
        customType = addField(left + 174, top + 276, 150, "field.veriborder.custom_type",
                data.customStyle() ? working.getString("customType") : data.documentType());
        customColor = addField(left + 334, top + 276, 100, "field.veriborder.custom_color",
                String.format("#%06X", data.accentColor() & 0xFFFFFF));
        addRenderableWidget(new BetterButton(left + 174, top + 306, 100, 20,
                Component.translatable("button.veriborder.apply_custom"), button -> applyCustomStyle(), Ui.PURPLE, true));
        addRenderableWidget(new BetterButton(left + 280, top + 306, 96, 20,
                Component.translatable("button.veriborder.clear_custom"), button -> clearCustomStyle(), Ui.MUTED, true));
    }

    private void applyCustomStyle() {
        if (commitCustomStyle()) {
            rebuildWidgets();
        }
    }

    private boolean commitCustomStyle() {
        if (customName == null || working == null) {
            return false;
        }
        int color = parseColor(customColor.getValue(), style.accentColor());
        String display = customName.getValue().isBlank() ? Component.translatable(style.displayNameKey()).getString()
                : customName.getValue();
        String shortName = customShort.getValue().isBlank() ? Component.translatable(style.shortNameKey()).getString()
                : customShort.getValue();
        String type = customType.getValue().isBlank() ? Component.translatable(style.kind().translationKey()).getString()
                : customType.getValue();
        DocumentData.fromTag(working).applyCustomStyle(display, shortName, type, color);
        return true;
    }

    private void clearCustomStyle() {
        if (working == null) {
            return;
        }
        DocumentData.fromTag(working).clearCustomStyle();
        rebuildWidgets();
    }

    private static int parseColor(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        String cleaned = value.trim().replace("#", "").replace("0x", "");
        try {
            return Integer.parseInt(cleaned, 16) & 0x00FFFFFF;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private void buildVisas() {
        addRenderableWidget(new BetterButton(left + 174, top + 286, 52, 20,
                Component.translatable("button.veriborder.add"), button -> beginVisa(-1), Ui.GREEN, true));
        addRenderableWidget(new BetterButton(left + 230, top + 286, 52, 20,
                Component.translatable("button.veriborder.edit"), button -> beginVisa(visaSelected), Ui.CYAN, true));
        addRenderableWidget(new BetterButton(left + 286, top + 286, 56, 20,
                Component.translatable("button.veriborder.delete"), button -> deleteVisa(), Ui.RED, true));
        if (editingVisa) {
            buildRecordEditor(true);
        } else if (DocumentData.fromTag(working).kind() == DocumentKind.PASSPORT) {
            buildVisaFreePanel();
        }
    }

    /**
     * Passport visa-free codes are managed in the free right-hand panel of the
     * visa tab. They are stored as the {@code visaFreeCodes} string list and are
     * one of the credential sources a visa gate accepts.
     */
    private void buildVisaFreePanel() {
        visaFreeCode = addField(left + 358, top + 168, 128, "field.veriborder.visa_free_code", "", null);
        visaFreeCode.setMaxLength(8);
        visaFreeCode.setFilter(ScreenCodes::digitsUpTo8);
        addRenderableWidget(new BetterButton(left + 358, top + 190, 62, 18,
                Component.translatable("button.veriborder.add"), button -> addVisaFreeCode(), Ui.GREEN, true));
        addRenderableWidget(new BetterButton(left + 424, top + 190, 62, 18,
                Component.translatable("button.veriborder.remove_last"), button -> removeLastVisaFreeCode(), Ui.AMBER, true));
        addRenderableWidget(new BetterButton(left + 358, top + 212, 128, 18,
                Component.translatable("button.veriborder.clear_all"), button -> clearVisaFreeCodes(), Ui.RED, true));
    }

    private void addVisaFreeCode() {
        String value = visaFreeCode == null ? "" : visaFreeCode.getValue().trim();
        if (!ScreenCodes.isValid(value)) {
            visaFreeError = "message.veriborder.code.invalid";
            return;
        }
        List<String> codes = visaFreeCodes();
        if (codes.contains(value)) {
            visaFreeError = "message.veriborder.code.duplicate";
            return;
        }
        DocumentData.fromTag(working).addVisaFreeCode(value);
        visaFreeCode.setValue("");
        visaFreeError = "";
    }

    private void removeLastVisaFreeCode() {
        List<String> codes = visaFreeCodes();
        if (!codes.isEmpty()) {
            DocumentData.fromTag(working).removeVisaFreeCode(codes.get(codes.size() - 1));
            visaFreeError = "";
        }
    }

    private void clearVisaFreeCodes() {
        DocumentData.fromTag(working).clearVisaFreeCodes();
        visaFreeError = "";
    }

    private List<String> visaFreeCodes() {
        return DocumentData.fromTag(working).visaFreeCodes();
    }

    private void buildEndorsements() {
        addRenderableWidget(new BetterButton(left + 174, top + 286, 52, 20,
                Component.translatable("button.veriborder.add"), button -> beginEndorsement(-1), Ui.GREEN, true));
        addRenderableWidget(new BetterButton(left + 230, top + 286, 52, 20,
                Component.translatable("button.veriborder.edit"), button -> beginEndorsement(endorsementSelected), Ui.CYAN, true));
        addRenderableWidget(new BetterButton(left + 286, top + 286, 56, 20,
                Component.translatable("button.veriborder.delete"), button -> deleteEndorsement(), Ui.RED, true));
        if (editingEndorsement) {
            buildRecordEditor(false);
        }
    }

    private void buildStamps() {
        addRenderableWidget(new BetterButton(left + 174, top + 286, 70, 20,
                Component.translatable("button.veriborder.delete"), button -> deleteStamp(), Ui.RED, true));
        addRenderableWidget(new BetterButton(left + 250, top + 286, 92, 20,
                Component.translatable("button.veriborder.clear_stamps"), button -> {
                    working.put("stamps", new ListTag());
                    stampSelected = -1;
                    rebuildWidgets();
                }, Ui.AMBER, true));
    }

    private void buildRecordEditor(boolean visa) {
        int x = left + 350;
        int y = top + 80;
        if (visa) {
            recCountry = addField(x, y + 22, 152, "field.veriborder.visa_country", "");
        }
        recTypeButton = addRenderableWidget(new BetterButton(x, y + 44, 152, 20,
                Component.translatable(visa ? visaType.translationKey() : endorsementType.translationKey()),
                button -> {
                    if (visa) {
                        visaType = visaType.next();
                    } else {
                        endorsementType = endorsementType.next();
                    }
                    recTypeButton.setMessage(Component.translatable(visa ? visaType.translationKey()
                            : endorsementType.translationKey()));
                }, Ui.PURPLE, true));
        recEntries = addField(x, y + 76, 152, "field.veriborder.entries", "1", "hint.veriborder.unlimited");
        recIssue = addField(x, y + 104, 152, "field.veriborder.issue", DateUtil.today(), "hint.veriborder.date");
        recExpiry = addField(x, y + 132, 152, "field.veriborder.expiry", DateUtil.plusYears(DateUtil.today(), 1),
                "hint.veriborder.date");
        recAuthority = addField(x, y + 160, 152, "field.veriborder.authority", "Authority");
        recCode = addField(x, y + 188, 152, "field.veriborder.code", "", "hint.veriborder.code_auto");
        recCode.setMaxLength(8);
        recCode.setFilter(ScreenCodes::digitsUpTo8);
        recStatusButton = addRenderableWidget(new BetterButton(x, y + 212, 152, 20,
                Component.translatable("record.veriborder.status." + recordStatus.toLowerCase(Locale.ROOT)),
                button -> {
                    recordStatus = switch (recordStatus) {
                        case "ACTIVE" -> "REVOKED";
                        case "REVOKED" -> "EXPIRED";
                        default -> "ACTIVE";
                    };
                    recStatusButton.setMessage(Component.translatable("record.veriborder.status."
                            + recordStatus.toLowerCase(Locale.ROOT)));
                }, recordStatusColor(), true));
        addRenderableWidget(new BetterButton(x, y + 232, 74, 20,
                Component.translatable("button.veriborder.save_record"), button -> saveRecord(visa), Ui.GREEN, true));
        addRenderableWidget(new BetterButton(x + 80, y + 232, 72, 20,
                Component.translatable("button.veriborder.cancel"), button -> {
                    editingVisa = false;
                    editingEndorsement = false;
                    rebuildWidgets();
                }, Ui.MUTED, true));
        // Fill the editor from the selected record (or defaults for a new one).
        CompoundTag record = selectedRecord(visa);
        if (record != null) {
            if (visa) {
                recCountry.setValue(record.getString("country"));
                visaType = VisaType.byId(record.getString("type"));
            } else {
                endorsementType = EndorsementType.byId(record.getString("type"));
            }
            recEntries.setValue(String.valueOf(record.getInt("entries")));
            recIssue.setValue(record.getString("issueDate"));
            recExpiry.setValue(record.getString("expiryDate"));
            recAuthority.setValue(record.getString("authority"));
            recCode.setValue(record.getString("code"));
            recordStatus = record.getString("status").isBlank() ? "ACTIVE" : record.getString("status");
            recTypeButton.setMessage(Component.translatable(visa ? visaType.translationKey() : endorsementType.translationKey()));
            recStatusButton.setMessage(Component.translatable("record.veriborder.status." + recordStatus.toLowerCase(Locale.ROOT)));
            if (visa) {
                recCountry.setValue(record.getString("country"));
            }
        }
    }

    private int recordStatusColor() {
        return switch (recordStatus) {
            case "ACTIVE" -> Ui.GREEN;
            case "REVOKED" -> Ui.RED;
            default -> Ui.AMBER;
        };
    }

    private CompoundTag selectedRecord(boolean visa) {
        if (editIndex < 0) {
            CompoundTag tag = new CompoundTag();
            tag.putString("country", "");
            tag.putString("type", visa ? visaType.id() : endorsementType.id());
            tag.putInt("entries", visa ? 1 : endorsementType.defaultEntries());
            tag.putInt("usedEntries", 0);
            tag.putString("issueDate", DateUtil.today());
            tag.putString("expiryDate", DateUtil.plusYears(DateUtil.today(), 1));
            tag.putString("authority", "");
            tag.putString("status", "ACTIVE");
            return tag;
        }
        List<CompoundTag> list = visa ? visaTags() : endorsementTags();
        if (editIndex >= list.size()) {
            return null;
        }
        return list.get(editIndex);
    }

    private List<CompoundTag> visaTags() {
        ListTag list = working.getList("visas", Tag.TAG_COMPOUND);
        List<CompoundTag> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            result.add(list.getCompound(i));
        }
        return result;
    }

    private List<CompoundTag> endorsementTags() {
        ListTag list = working.getList("endorsements", Tag.TAG_COMPOUND);
        List<CompoundTag> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            result.add(list.getCompound(i));
        }
        return result;
    }

    private List<CompoundTag> stampTags() {
        ListTag list = working.getList("stamps", Tag.TAG_COMPOUND);
        List<CompoundTag> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            result.add(list.getCompound(i));
        }
        return result;
    }

    private void beginVisa(int index) {
        captureProfile();
        editingVisa = true;
        editingEndorsement = false;
        editIndex = index;
        CompoundTag record = index >= 0 && index < visaTags().size() ? visaTags().get(index) : null;
        if (record != null) {
            visaType = VisaType.byId(record.getString("type"));
            recordStatus = record.getString("status").isBlank() ? "ACTIVE" : record.getString("status");
        } else {
            recordStatus = "ACTIVE";
        }
        rebuildWidgets();
    }

    private void beginEndorsement(int index) {
        captureProfile();
        editingVisa = false;
        editingEndorsement = true;
        editIndex = index;
        CompoundTag record = index >= 0 && index < endorsementTags().size() ? endorsementTags().get(index) : null;
        if (record != null) {
            endorsementType = EndorsementType.byId(record.getString("type"));
            recordStatus = record.getString("status").isBlank() ? "ACTIVE" : record.getString("status");
        } else {
            recordStatus = "ACTIVE";
        }
        rebuildWidgets();
    }

    private void saveRecord(boolean visa) {
        List<CompoundTag> list = visa ? visaTags() : endorsementTags();
        CompoundTag record;
        if (editIndex >= 0 && editIndex < list.size()) {
            record = list.get(editIndex).copy();
        } else {
            record = new CompoundTag();
            record.putString("id", (visa ? "V-" : "E-")
                    + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            record.putInt("usedEntries", 0);
        }
        if (visa) {
            record.putString("country", recCountry.getValue());
            record.putString("type", visaType.id());
        } else {
            record.putString("type", endorsementType.id());
        }
        try {
            record.putInt("entries", Integer.parseInt(recEntries.getValue().trim()));
        } catch (NumberFormatException ignored) {
            record.putInt("entries", visa ? 1 : endorsementType.defaultEntries());
        }
        record.putString("issueDate", recIssue.getValue());
        record.putString("expiryDate", recExpiry.getValue());
        record.putString("authority", recAuthority.getValue());
        String typedCode = ScreenCodes.validOrBlank(recCode.getValue());
        if (!typedCode.isEmpty()) {
            record.putString("code", typedCode);
        } else if (record.getString("code").isBlank()) {
            // Blank keeps an existing code; a new/legacy record gets an unused one.
            record.putString("code", nextUniqueCode());
        }
        record.putString("status", recordStatus);
        if (editIndex >= 0 && editIndex < list.size()) {
            list.set(editIndex, record);
        } else {
            list.add(record);
        }
        ListTag tag = new ListTag();
        list.forEach(tag::add);
        working.put(visa ? "visas" : "endorsements", tag);
        editingVisa = false;
        editingEndorsement = false;
        editIndex = -1;
        rebuildWidgets();
    }

    private String nextUniqueCode() {
        return CredentialCode.generateUnique(DocumentData.fromTag(working).allCredentialCodes());
    }

    private void deleteVisa() {
        List<CompoundTag> list = visaTags();
        if (visaSelected >= 0 && visaSelected < list.size()) {
            list.remove(visaSelected);
            ListTag tag = new ListTag();
            list.forEach(tag::add);
            working.put("visas", tag);
            visaSelected = -1;
            rebuildWidgets();
        }
    }

    private void deleteEndorsement() {
        List<CompoundTag> list = endorsementTags();
        if (endorsementSelected >= 0 && endorsementSelected < list.size()) {
            list.remove(endorsementSelected);
            ListTag tag = new ListTag();
            list.forEach(tag::add);
            working.put("endorsements", tag);
            endorsementSelected = -1;
            rebuildWidgets();
        }
    }

    private void deleteStamp() {
        List<CompoundTag> list = stampTags();
        if (stampSelected >= 0 && stampSelected < list.size()) {
            list.remove(stampSelected);
            ListTag tag = new ListTag();
            list.forEach(tag::add);
            working.put("stamps", tag);
            stampSelected = -1;
            rebuildWidgets();
        }
    }

    private void captureProfile() {
        if (tab != TAB_PROFILE || name == null || working == null) {
            return;
        }
        working.putString("name", name.getValue());
        working.putString("displayName", displayName.getValue());
        working.putString("nameLatin", nameLatin.getValue());
        working.putString("number", number.getValue());
        working.putString("birthDate", birth.getValue());
        working.putString("nationality", nationality.getValue());
        working.putString("authority", authority.getValue());
        working.putString("placeOfIssue", place.getValue());
        working.putString("issueDate", issue.getValue());
        working.putString("expiryDate", expiry.getValue());
        working.putString("address", address.getValue());
        working.putString("sex", sex);
        working.putString("status", status);
        working.putString("style", style.id());
        working.putString("kind", style.kind().name());
        DocumentData.fromTag(working).sign();
    }

    private void save() {
        captureProfile();
        if (tab == TAB_STYLE) {
            commitCustomStyle();
        }
        if (working == null) {
            onClose();
            return;
        }
        working.putString("sex", sex);
        working.putString("status", status);
        working.putString("style", style.id());
        working.putString("kind", style.kind().name());
        DocumentData.fromTag(working).sign();
        ModNetwork.CHANNEL.sendToServer(new ModifierSavePacket(entries.get(selected).slot(), working.copy()));
        onClose();
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, Component.translatable("screen.veriborder.modifier").getString(),
                Component.translatable("screen.veriborder.modifier.subtitle").getString());
        if (working == null) {
            graphics.drawCenteredString(font, Component.translatable("screen.veriborder.modifier.no_docs"),
                    left + imageWidth / 2, top + 150, Ui.RED);
            return;
        }
        drawDocumentList(graphics);
        if (tab == TAB_PROFILE) {
            drawProfileSummary(graphics);
        } else if (tab == TAB_STYLE) {
            drawStyleTab(graphics);
        } else if (tab == TAB_VISAS) {
            drawVisaTab(graphics);
        } else if (tab == TAB_ENDORSEMENTS) {
            drawEndorsementTab(graphics);
        } else {
            drawStampTab(graphics);
        }
    }

    private void drawDocumentList(GuiGraphics graphics) {
        int x = left + 10;
        int y = top + 50;
        int width = 150;
        int height = 266;
        Ui.inset(graphics, x, y, width, height);
        graphics.drawString(font, Component.translatable("screen.veriborder.modifier.inventory"), x + 6, y + 6,
                Ui.DIM, false);
        int rowHeight = 32;
        int visible = 7;
        listScroll = Math.min(listScroll, Math.max(0, entries.size() - visible));
        Ui.scissor(graphics, x + 2, y + 17, x + width - 2, y + height - 2);
        for (int i = 0; i < visible && listScroll + i < entries.size(); i++) {
            Entry entry = entries.get(listScroll + i);
            int rowY = y + 18 + i * rowHeight;
            boolean selectedRow = listScroll + i == selected;
            int fill = selectedRow ? 0xFF243A50 : 0xFF111C28;
            graphics.fill(x + 3, rowY, x + width - 3, rowY + rowHeight - 2, fill);
            if (selectedRow) {
                graphics.fill(x + 3, rowY, x + 5, rowY + rowHeight - 2, accent);
            }
            DocumentData data = DocumentData.fromTag(entry.tag());
            graphics.drawString(font, "#" + entry.slot() + "  " + Ui.ellipsis(data.displayName(), 96),
                    x + 9, rowY + 4, Ui.TEXT, false);
            graphics.drawString(font, Ui.ellipsis(data.number(), 126), x + 9, rowY + 16, Ui.MUTED, false);
        }
        graphics.disableScissor();
        if (entries.size() > visible) {
            graphics.drawString(font, "↕", x + width - 14, y + height - 12, Ui.DIM, false);
        }
    }

    private void drawProfileSummary(GuiGraphics graphics) {
        DocumentData data = DocumentData.fromTag(working);
        Ui.inset(graphics, left + 174, top + 294, 320, 26);
        graphics.drawString(font, Ui.ellipsis(Component.translatable("screen.veriborder.modifier.effective",
                data.displayName()).getString(), 306), left + 182, top + 298, Ui.TEXT, false);
        graphics.drawString(font, Component.translatable("screen.veriborder.modifier.security",
                Component.translatable(data.integrityValid() ? "document.veriborder.integrity.ok"
                        : "document.veriborder.integrity.bad")), left + 182, top + 309,
                data.integrityValid() ? Ui.GREEN : Ui.RED, false);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.modifier.record_counts",
                data.visas().size(), data.endorsements().size(), data.stamps().size()), left + 322, top + 309,
                174, Ui.DIM);
    }

    private void drawStyleTab(GuiGraphics graphics) {
        DocumentData data = DocumentData.fromTag(working);
        Ui.documentCard(graphics, left + 174, top + 80, 220, 140, data);
        graphics.drawString(font, Ui.ellipsis(Component.translatable("screen.veriborder.modifier.style_hint",
                Component.translatable(style.displayNameKey()).getString()).getString(), 340), left + 174, top + 224,
                Ui.MUTED, false);
        graphics.fill(left + 444, top + 276, left + 460, top + 292,
                data.accentColor() | 0xFF000000);
        graphics.renderOutline(left + 444, top + 276, 16, 16, Ui.BORDER);
    }

    private void drawVisaTab(GuiGraphics graphics) {
        drawRecordList(graphics, visaTags(), visaSelected, true);
        if (editingVisa) {
            drawRecordEditorFrame(graphics, true);
        } else if (DocumentData.fromTag(working).kind() == DocumentKind.PASSPORT) {
            drawVisaFreePanel(graphics);
        } else {
            Ui.inset(graphics, left + 350, top + 80, 152, 200);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.modifier.select_visa"),
                    left + 362, top + 94, 130, Ui.MUTED);
            if (visaSelected >= 0 && visaSelected < visaTags().size()) {
                CompoundTag visa = visaTags().get(visaSelected);
                Ui.clipped(graphics, visa.getString("country"), left + 362, top + 114, 132, Ui.TEXT);
                Ui.clipped(graphics, Component.translatable("record.veriborder.status."
                        + visa.getString("status").toLowerCase(Locale.ROOT)), left + 362, top + 128, 132, Ui.DIM);
            }
        }
    }

    private void drawVisaFreePanel(GuiGraphics graphics) {
        // Only the header/list strip gets an inset; the code field below keeps
        // the standard field chrome drawn by VeriScreen.
        Ui.inset(graphics, left + 350, top + 80, 152, 64);
        graphics.drawString(font, Component.translatable("screen.veriborder.modifier.visa_free"),
                left + 358, top + 86, accent, false);
        List<String> codes = visaFreeCodes();
        if (codes.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.veriborder.modifier.visa_free_empty"),
                    left + 358, top + 101, Ui.DIM, false);
        } else {
            int start = Math.max(0, codes.size() - 4);
            for (int i = start; i < codes.size(); i++) {
                graphics.drawString(font, codes.get(i), left + 358, top + 101 + (i - start) * 11,
                        Ui.TEXT, false);
            }
        }
        if (!visaFreeError.isBlank()) {
            Ui.clipped(graphics, Component.translatable(visaFreeError), left + 358, top + 146, 136, Ui.RED);
        } else {
            Ui.clipped(graphics, Component.translatable("hint.veriborder.code_8"),
                    left + 358, top + 146, 136, Ui.DIM);
        }
    }

    private void drawEndorsementTab(GuiGraphics graphics) {
        drawRecordList(graphics, endorsementTags(), endorsementSelected, false);
        if (editingEndorsement) {
            drawRecordEditorFrame(graphics, false);
        } else {
            Ui.inset(graphics, left + 350, top + 80, 152, 200);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.modifier.select_endorsement"),
                    left + 362, top + 94, 130, Ui.MUTED);
        }
    }

    private void drawRecordList(GuiGraphics graphics, List<CompoundTag> records, int selectedRecord, boolean visa) {
        int x = left + 174;
        int y = top + 80;
        int width = 168;
        int height = 200;
        Ui.inset(graphics, x, y, width, height);
        int rowHeight = 26;
        int visible = 7;
        recordScroll = Math.min(recordScroll, Math.max(0, records.size() - visible));
        Ui.scissor(graphics, x + 2, y + 2, x + width - 2, y + height - 2);
        for (int i = 0; i < visible && recordScroll + i < records.size(); i++) {
            CompoundTag record = records.get(recordScroll + i);
            int rowY = y + 3 + i * rowHeight;
            boolean current = recordScroll + i == selectedRecord;
            graphics.fill(x + 3, rowY, x + width - 3, rowY + rowHeight - 2, current ? 0xFF243A50 : 0xFF111C28);
            if (current) {
                graphics.fill(x + 3, rowY, x + 5, rowY + rowHeight - 2, accent);
            }
            String title = visa ? record.getString("country") : Component.translatable(
                    EndorsementType.byId(record.getString("type")).translationKey()).getString();
            String type = visa ? Component.translatable(VisaType.byId(record.getString("type")).translationKey()).getString()
                    : record.getString("id");
            String code = record.getString("code").trim();
            String detail = type + (code.isEmpty() ? "" : " · " + code) + " · " + record.getString("expiryDate");
            graphics.drawString(font, Ui.ellipsis(title, 150), x + 9, rowY + 3, Ui.TEXT, false);
            graphics.drawString(font, Ui.ellipsis(detail, 150), x + 9, rowY + 14, Ui.MUTED, false);
        }
        graphics.disableScissor();
        if (records.isEmpty()) {
            graphics.drawString(font, Component.translatable("document.veriborder.no_records"), x + 8, y + 8,
                    Ui.DIM, false);
        }
    }

    private void drawRecordEditorFrame(GuiGraphics graphics, boolean visa) {
        // Header strip + outline only: the fields below keep their own chrome.
        graphics.fill(left + 348, top + 76, left + 506, top + 88, 0xFF0B1621);
        graphics.renderOutline(left + 348, top + 76, 158, 266, 0xFF2A4157);
        graphics.drawString(font, Component.translatable(visa ? "screen.veriborder.modifier.edit_visa"
                : "screen.veriborder.modifier.edit_endorsement"), left + 356, top + 78, accent, false);
    }

    private void drawStampTab(GuiGraphics graphics) {
        List<CompoundTag> stamps = stampTags();
        int x = left + 174;
        int y = top + 80;
        int width = 326;
        int height = 200;
        Ui.inset(graphics, x, y, width, height);
        int rowHeight = 24;
        int visible = 8;
        recordScroll = Math.min(recordScroll, Math.max(0, stamps.size() - visible));
        Ui.scissor(graphics, x + 2, y + 2, x + width - 2, y + height - 2);
        for (int i = 0; i < visible && recordScroll + i < stamps.size(); i++) {
            CompoundTag stamp = stamps.get(recordScroll + i);
            int rowY = y + 3 + i * rowHeight;
            boolean current = recordScroll + i == stampSelected;
            graphics.fill(x + 3, rowY, x + width - 3, rowY + rowHeight - 2, current ? 0xFF243A50 : 0xFF111C28);
            boolean inbound = "IN".equalsIgnoreCase(stamp.getString("direction"));
            graphics.fill(x + 3, rowY, x + 5, rowY + rowHeight - 2, inbound ? Ui.GREEN : Ui.AMBER);
            graphics.drawString(font, Component.translatable(inbound ? "stamp.veriborder.in"
                    : "stamp.veriborder.out").getString(), x + 10, rowY + 3, inbound ? Ui.GREEN : Ui.AMBER, false);
            graphics.drawString(font, Ui.ellipsis(stamp.getString("port") + " / " + stamp.getString("country"),
                    width - 100), x + 54, rowY + 3, Ui.TEXT, false);
            graphics.drawString(font, stamp.getString("date"), x + width - 78, rowY + 3, Ui.MUTED, false);
            graphics.drawString(font, Ui.ellipsis(stamp.getString("officer"), width - 100), x + 54, rowY + 13,
                    Ui.DIM, false);
        }
        graphics.disableScissor();
        if (stamps.isEmpty()) {
            graphics.drawString(font, Component.translatable("document.veriborder.no_records"), x + 8, y + 8,
                    Ui.DIM, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (working == null) {
            return false;
        }
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        int listX = left + 10;
        int listY = top + 50;
        if (uiX >= listX && uiX <= listX + 150 && uiY >= listY + 18 && uiY <= listY + 266) {
            int row = (int) ((uiY - (listY + 18)) / 32) + listScroll;
            if (row >= 0 && row < entries.size()) {
                if (tab == TAB_STYLE) {
                    commitCustomStyle();
                }
                captureProfile();
                load(row);
                tab = TAB_PROFILE;
                rebuildWidgets();
                return true;
            }
        }
        if (tab == TAB_VISAS || tab == TAB_ENDORSEMENTS) {
            int row = recordRowAt(uiX, uiY);
            List<CompoundTag> records = tab == TAB_VISAS ? visaTags() : endorsementTags();
            if (row >= 0 && row < records.size()) {
                if (tab == TAB_VISAS) {
                    visaSelected = row;
                } else {
                    endorsementSelected = row;
                }
                return true;
            }
        } else if (tab == TAB_STAMPS) {
            int row = stampRowAt(uiX, uiY);
            if (row >= 0 && row < stampTags().size()) {
                stampSelected = row;
                return true;
            }
        }
        return false;
    }

    private int recordRowAt(double mouseX, double mouseY) {
        int x = left + 174;
        int y = top + 80;
        if (mouseX < x || mouseX > x + 168 || mouseY < y + 3 || mouseY > y + 200) {
            return -1;
        }
        int row = (int) ((mouseY - (y + 3)) / 26) + recordScroll;
        return row >= 0 && row < 7 ? row : -1;
    }

    private int stampRowAt(double mouseX, double mouseY) {
        int x = left + 174;
        int y = top + 80;
        if (mouseX < x || mouseX > x + 326 || mouseY < y + 3 || mouseY > y + 200) {
            return -1;
        }
        int row = (int) ((mouseY - (y + 3)) / 24) + recordScroll;
        return row >= 0 && row < 8 ? row : -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        if (uiX >= left + 10 && uiX <= left + 160 && uiY >= top + 68 && uiY <= top + 316) {
            listScroll = Math.max(0, listScroll - (int) Math.signum(delta));
            return true;
        }
        if (uiX >= left + 174 && uiX <= left + 500 && uiY >= top + 80 && uiY <= top + 280) {
            recordScroll = Math.max(0, recordScroll - (int) Math.signum(delta));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private static String sexKey(String sex) {
        return switch (sex) {
            case "F" -> "female";
            case "X" -> "x";
            default -> "male";
        };
    }
}
