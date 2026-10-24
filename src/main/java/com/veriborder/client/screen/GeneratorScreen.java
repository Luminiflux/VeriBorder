package com.veriborder.client.screen;

import com.veriborder.client.BetterButton;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.client.ui.ColorPalette;
import com.veriborder.client.ui.Dropdown;
import com.veriborder.doc.DateUtil;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentFactory;
import com.veriborder.doc.DocumentKind;
import com.veriborder.network.ModNetwork;
import com.veriborder.network.ModifierGeneratePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Opens directly from the Universal Document Modifier. It can create a document
 * from an official layout or from a fully custom cover/layout identity.
 */
public class GeneratorScreen extends VeriScreen {
    private DocumentKind kind = DocumentKind.ID_CARD;
    private DocStyle style = DocStyle.CN_ID;
    private boolean custom;
    private String sex = "M";
    private CompoundTag cache;
    /** 26.3 round 12: the shared palette picker replaces the old hex text box. */
    private ColorPalette colorPicker;
    private EditBox colorHex;

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
    private BetterButton customButton;

    public GeneratorScreen() {
        super(Component.translatable("screen.veriborder.generator"), 520, 360);
        this.accent = Ui.GOLD;
        this.cache = defaults();
    }

    private CompoundTag defaults() {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", kind.name());
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
        tag.putString("customName", style == DocStyle.CN_ID
                ? Component.translatable("screen.veriborder.generator.custom_id_name").getString()
                : Component.translatable("screen.veriborder.generator.custom_doc_name").getString());
        tag.putString("customShortName", Component.translatable("screen.veriborder.generator.custom_short").getString());
        tag.putString("customType", Component.translatable(kind.translationKey()).getString());
        tag.putInt("customColor", style.accentColor());
        return tag;
    }

    @Override
    protected void build() {
        if (cache == null) {
            cache = defaults();
        }
        // 26.4: credential type, layout and sex are real dropdowns instead of
        // three cycle buttons (passports alone offer five layouts).
        List<Dropdown.Option> kinds = new java.util.ArrayList<>();
        for (DocumentKind value : DocumentKind.values()) {
            kinds.add(new Dropdown.Option(value.id(),
                    Component.translatable(value.translationKey()).getString()));
        }
        addDropdown(Dropdown.at(left + 24, top + 46, 130)
                .options(kinds)
                .selected(kind.id())
                .onChange(id -> {
                    if (kind.id().equals(id)) {
                        return;
                    }
                    capture();
                    DocumentKind next = DocumentKind.byIdOrNull(id);
                    if (next == null) {
                        return;
                    }
                    kind = next;
                    style = DocStyle.forKind(kind).get(0);
                    cache = defaults();
                    rebuildWidgets();
                }));
        List<Dropdown.Option> layouts = new java.util.ArrayList<>();
        for (DocStyle value : DocStyle.forKind(kind)) {
            layouts.add(new Dropdown.Option(value.id(),
                    Component.translatable(value.displayNameKey()).getString()));
        }
        addDropdown(Dropdown.at(left + 160, top + 46, 210)
                .options(layouts)
                .selected(style.id())
                .tooltip("button.veriborder.change_style")
                .onChange(id -> {
                    DocStyle next = DocStyle.byIdOrNull(id);
                    if (next == null || next.kind() != kind || next == style) {
                        return;
                    }
                    capture();
                    style = next;
                    cache.putString("style", style.id());
                    cache.putString("authority", style.defaultAuthority());
                    cache.putString("nationality", style.defaultNationality());
                    cache.putString("placeOfIssue", style.defaultPlace());
                    cache.putString("address", DocumentFactory.defaultAddress(style));
                    cache.putString("expiryDate", style.permanent() ? DateUtil.PERMANENT
                            : DateUtil.plusYears(cache.getString("issueDate"), style.validityYears()));
                    rebuildWidgets();
                }));
        customButton = addRenderableWidget(new BetterButton(left + 376, top + 46, 120, 20,
                Component.translatable(custom ? "screen.veriborder.generator.custom.on"
                        : "screen.veriborder.generator.custom.off"), button -> {
                    capture();
                    custom = !custom;
                    rebuildWidgets();
                }, Ui.PURPLE, true));

        // Permanent document name (independent from the custom cover name).
        displayName = addField(left + 24, top + 78, 150, "field.veriborder.display_name",
                cache.getString("displayName"), "hint.veriborder.blank_official");
        colorPicker = ColorPalette.at(left + 306, top + 74, 186, cache.getInt("customColor"));
        colorHex = new EditBox(this.font, colorPicker.hexFieldX(), colorPicker.hexFieldY(),
                colorPicker.hexFieldWidth(), 12, Component.empty());
        colorHex.setBordered(false);
        colorHex.setTextColor(0xFFE9F3FF);
        colorHex.setMaxLength(7);
        colorHex.setValue(ColorPalette.hexValue(cache.getInt("customColor")));
        colorHex.setVisible(false);
        addRenderableWidget(colorHex);
        colorPicker.hex(colorHex);

        customName = addField(left + 24, top + 108, 150, "field.veriborder.custom_name", cache.getString("customName"),
                "hint.veriborder.cover");
        customShort = addField(left + 180, top + 108, 116, "field.veriborder.custom_short", cache.getString("customShortName"));
        customType = addField(left + 302, top + 108, 194, "field.veriborder.custom_type", cache.getString("customType"));

        name = addField(left + 24, top + 138, 214, "field.veriborder.name", cache.getString("name"));
        nameLatin = addField(left + 266, top + 138, 230, "field.veriborder.name_latin", cache.getString("nameLatin"));
        number = addField(left + 24, top + 168, 214, "field.veriborder.number", cache.getString("number"),
                "hint.veriborder.auto");
        List<Dropdown.Option> sexes = new java.util.ArrayList<>();
        for (String value : new String[]{"M", "F", "X"}) {
            sexes.add(new Dropdown.Option(value,
                    Component.translatable("sex.veriborder." + sexKey(value)).getString()));
        }
        addDropdown(Dropdown.at(left + 266, top + 164, 120)
                .prefix(Component.translatable("field.veriborder.sex").getString() + ": ")
                .options(sexes)
                .selected(sex)
                .onChange(id -> {
                    sex = id;
                    capture();
                    rebuildWidgets();
                }));
        birth = addField(left + 24, top + 198, 214, "field.veriborder.birth", cache.getString("birthDate"),
                "hint.veriborder.date");
        nationality = addField(left + 266, top + 198, 230, "field.veriborder.nationality", cache.getString("nationality"));
        authority = addField(left + 24, top + 228, 214, "field.veriborder.issuer", cache.getString("authority"));
        place = addField(left + 266, top + 228, 230, "field.veriborder.place", cache.getString("placeOfIssue"));
        issue = addField(left + 24, top + 258, 214, "field.veriborder.issue", cache.getString("issueDate"),
                "hint.veriborder.date");
        expiry = addField(left + 266, top + 258, 230, "field.veriborder.expiry", cache.getString("expiryDate"),
                "hint.veriborder.date");
        address = addField(left + 24, top + 288, 472, "field.veriborder.address", cache.getString("address"));

        addRenderableWidget(new BetterButton(left + 24, top + 328, 150, 22,
                Component.translatable("button.veriborder.generate"), button -> generate(), Ui.GREEN));
        addRenderableWidget(new BetterButton(left + 394, top + 328, 102, 22,
                Component.translatable("button.veriborder.close"), button -> onClose(), Ui.MUTED, true));
    }

    private void capture() {
        if (name == null) {
            return;
        }
        cache.putString("kind", kind.name());
        cache.putString("style", style.id());
        cache.putString("displayName", displayName.getValue());
        cache.putString("name", name.getValue());
        cache.putString("nameLatin", nameLatin.getValue());
        cache.putString("number", number.getValue());
        cache.putString("sex", sex);
        cache.putString("birthDate", birth.getValue());
        cache.putString("nationality", nationality.getValue());
        cache.putString("authority", authority.getValue());
        cache.putString("placeOfIssue", place.getValue());
        cache.putString("issueDate", issue.getValue());
        cache.putString("expiryDate", expiry.getValue());
        cache.putString("address", address.getValue());
        cache.putString("customName", customName.getValue());
        cache.putString("customShortName", customShort.getValue());
        cache.putString("customType", customType.getValue());
        cache.putInt("customColor", colorPicker == null
                ? style.accentColor() : colorPicker.commit());
    }

    private void generate() {
        capture();
        CompoundTag data = cache.copy();
        data.putString("kind", kind.name());
        data.putString("style", style.id());
        data.putBoolean("customStyle", custom);
        data.putString("sex", sex);
        if (custom && data.getString("customName").isBlank()) {
            data.putString("customName", style == DocStyle.CN_ID
                ? Component.translatable("screen.veriborder.generator.custom_id_name").getString()
                : Component.translatable("screen.veriborder.generator.custom_doc_name").getString());
        }
        ModNetwork.CHANNEL.sendToServer(new ModifierGeneratePacket(data));
    }

    private static String sexKey(String sex) {
        return switch (sex) {
            case "F" -> "female";
            case "X" -> "x";
            default -> "male";
        };
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The RGB input only exists (and accepts input) while the palette is open.
        if (colorHex != null) {
            colorHex.setVisible(colorPicker != null && colorPicker.open());
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        // An open palette paints above the form rows that follow it; the RGB input
        // is re-rendered on top of the panel so it stays usable.
        overlay(graphics, g -> {
            if (colorPicker != null && colorPicker.open()) {
                colorPicker.draw(g, font, Ui.PURPLE);
                if (colorHex != null) {
                    colorHex.render(g, (int) toUiX(mouseX), (int) toUiY(mouseY), partialTick);
                }
            }
        });
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        // 26.4: an open dropdown owns the click (including dismissal).
        if (dropdownClick(uiX, uiY)) {
            return true;
        }
        // An open palette owns the click even where a form field sits underneath -
        // except for its own RGB input, which is a real widget.
        if (colorPicker != null && colorPicker.open()) {
            if (colorPicker.overHexField(uiX, uiY)) {
                return super.mouseClicked(mouseX, mouseY, button);
            }
            if (colorPicker.mouseClicked(uiX, uiY)) {
                if (!colorPicker.open()) {
                    capture();
                }
                return true;
            }
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return colorPicker != null && colorPicker.mouseClicked(uiX, uiY);
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, Component.translatable("screen.veriborder.generator.title").getString(),
                Component.translatable("screen.veriborder.generator.subtitle").getString());
        // Live preview of the effective title: explicit document name, custom
        // cover name, or the official template name, in that order.
        String typedName = displayName == null ? cache.getString("displayName") : displayName.getValue().trim();
        String coverName = customName == null ? cache.getString("customName") : customName.getValue().trim();
        String effective = !typedName.isBlank() ? typedName
                : custom && !coverName.isBlank() ? coverName
                : Component.translatable(style.displayNameKey()).getString();
        graphics.drawString(font, Ui.ellipsis(Component.translatable("screen.veriborder.generator.effective",
                effective).getString(), 104), left + 180, top + 81,
                typedName.isBlank() ? Ui.DIM : Ui.CYAN, false);
        // Shared palette picker: swatches + recent colours + RGB input.
        if (colorPicker != null && !colorPicker.open()) {
            colorPicker.draw(graphics, font, Ui.PURPLE);
        }
    }
}
