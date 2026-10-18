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
    private BetterButton styleButton;
    private BetterButton sexButton;
    private String sex = "M";
    private final String[] endorsementChoices = {
            "none", EndorsementType.ONE_YEAR_MULTI.id(), EndorsementType.THREE_MONTH_SINGLE.id(),
            EndorsementType.OTHER.id()
    };
    private int endorsementChoice = 1;

    public IssuerScreen(DeviceType device, BlockPos pos, CompoundTag payload) {
        super(Component.translatable(device.translationKey()), 520, 360);
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
        this.styles = DocStyle.forKind(kind);
        this.style = styles.get(0);
        this.accent = device.accentColor();
    }

    @Override
    protected void build() {
        if (formCache == null) {
            formCache = defaults();
        }
        applyLockedValues();
        styleButton = addRenderableWidget(new BetterButton(left + 24, top + 46, 210, 20,
                Component.literal(Component.translatable(style.displayNameKey()).getString()), button -> cycleStyle(), accent));
        addRenderableWidget(new BetterButton(left + 242, top + 46, 120, 20,
                Component.translatable("button.veriborder.defaults"), button -> {
                    sex = "M";
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
        place = addField(left + 266, top + 204, 230, "field.veriborder.place", formCache.getString("placeOfIssue"));
        issue = addField(left + 24, top + 234, 214, "field.veriborder.issue", formCache.getString("issueDate"),
                "hint.veriborder.date");
        expiry = addField(left + 266, top + 234, 230, "field.veriborder.expiry", formCache.getString("expiryDate"),
                "hint.veriborder.date");
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

        addRenderableWidget(new BetterButton(left + 24, top + 330, 130, 22,
                Component.translatable("button.veriborder.issue"), button -> issue(), Ui.GREEN));
        if (kind == DocumentKind.PERMIT) {
            addRenderableWidget(new BetterButton(left + 164, top + 330, 190, 22,
                    Component.translatable("screen.veriborder.issuer.initial", endorsementLabel()),
                    button -> {
                        endorsementChoice = (endorsementChoice + 1) % endorsementChoices.length;
                        rebuildWidgets();
                    }, Ui.PURPLE, true));
        }
        addRenderableWidget(new BetterButton(left + 394, top + 330, 102, 22,
                Component.translatable("button.veriborder.close"), button -> onClose(), Ui.MUTED, true));
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
    }

    private void cycleStyle() {
        captureFields();
        style = DocStyle.nextForKind(kind, style);
        formCache.putString("style", style.id());
        formCache.putString("authority", style.defaultAuthority());
        formCache.putString("nationality", style.defaultNationality());
        formCache.putString("placeOfIssue", style.defaultPlace());
        formCache.putString("address", DocumentFactory.defaultAddress(style));
        formCache.putString("expiryDate", style.permanent() ? DateUtil.PERMANENT
                : DateUtil.plusYears(formCache.getString("issueDate"), style.validityYears()));
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
        if (loaded.kind() == kind) {
            style = loaded;
        }
        formCache = values.copy();
        formCache.putString("style", style.id());
        sex = formCache.getString("sex").isBlank() ? "M" : formCache.getString("sex");
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
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "issue_document", data, -1));
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
        if (kind != DocumentKind.PERMIT) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.issuer.hint"), left + 164,
                    top + 338, 222, Ui.DIM);
        }
    }
}
