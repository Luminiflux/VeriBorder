package com.veriborder.client.screen;

import com.veriborder.client.BetterButton;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
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
    private static final int[] PALETTE = {
            0x1F6FB2, 0x7C3AED, 0xB91C1C, 0x0F766E,
            0xC2410C, 0x374151, 0xA16207, 0x111827
    };

    private DocumentKind kind = DocumentKind.ID_CARD;
    private DocStyle style = DocStyle.CN_ID;
    private boolean custom;
    private String sex = "M";
    private int paletteIndex;
    private CompoundTag cache;

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
    private BetterButton kindButton;
    private BetterButton templateButton;
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
        tag.putString("customName", style == DocStyle.CN_ID ? "Custom Identity Card" : "Custom Document");
        tag.putString("customShortName", "CUSTOM");
        tag.putString("customType", Component.translatable(kind.translationKey()).getString());
        tag.putInt("customColor", style.accentColor());
        return tag;
    }

    @Override
    protected void build() {
        if (cache == null) {
            cache = defaults();
        }
        kindButton = addRenderableWidget(new BetterButton(left + 24, top + 46, 130, 20,
                Component.translatable(kind.translationKey()), button -> {
                    capture();
                    DocumentKind[] kinds = DocumentKind.values();
                    kind = kinds[(kind.ordinal() + 1) % kinds.length];
                    List<DocStyle> styles = DocStyle.forKind(kind);
                    style = styles.get(0);
                    cache = defaults();
                    rebuildWidgets();
                }, accent));
        templateButton = addRenderableWidget(new BetterButton(left + 160, top + 46, 210, 20,
                Component.translatable(style.displayNameKey()), button -> {
                    capture();
                    style = DocStyle.nextForKind(kind, style);
                    cache.putString("style", style.id());
                    cache.putString("authority", style.defaultAuthority());
                    cache.putString("nationality", style.defaultNationality());
                    cache.putString("placeOfIssue", style.defaultPlace());
                    cache.putString("address", DocumentFactory.defaultAddress(style));
                    cache.putString("expiryDate", style.permanent() ? DateUtil.PERMANENT
                            : DateUtil.plusYears(cache.getString("issueDate"), style.validityYears()));
                    rebuildWidgets();
                }, accent, true));
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
        customColor = addField(left + 306, top + 78, 110, "field.veriborder.custom_color",
                String.format("#%06X", cache.getInt("customColor") & 0xFFFFFF), "#RRGGBB");
        addRenderableWidget(new BetterButton(left + 422, top + 74, 74, 20,
                Component.translatable("button.veriborder.palette"), button -> {
                    paletteIndex = (paletteIndex + 1) % PALETTE.length;
                    customColor.setValue(String.format("#%06X", PALETTE[paletteIndex]));
                }, Ui.PURPLE, true));

        customName = addField(left + 24, top + 108, 150, "field.veriborder.custom_name", cache.getString("customName"),
                "hint.veriborder.cover");
        customShort = addField(left + 180, top + 108, 116, "field.veriborder.custom_short", cache.getString("customShortName"));
        customType = addField(left + 302, top + 108, 194, "field.veriborder.custom_type", cache.getString("customType"));

        name = addField(left + 24, top + 138, 214, "field.veriborder.name", cache.getString("name"));
        nameLatin = addField(left + 266, top + 138, 230, "field.veriborder.name_latin", cache.getString("nameLatin"));
        number = addField(left + 24, top + 168, 214, "field.veriborder.number", cache.getString("number"),
                "hint.veriborder.auto");
        sexButton = addRenderableWidget(new BetterButton(left + 266, top + 164, 70, 20,
                Component.translatable("sex.veriborder." + sexKey(sex)), button -> {
                    capture();
                    sex = switch (sex) {
                        case "M" -> "F";
                        case "F" -> "X";
                        default -> "M";
                    };
                    rebuildWidgets();
                }, Ui.CYAN, true));
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
        cache.putInt("customColor", parseColor(customColor.getValue(), style.accentColor()));
    }

    private void generate() {
        capture();
        CompoundTag data = cache.copy();
        data.putString("kind", kind.name());
        data.putString("style", style.id());
        data.putBoolean("customStyle", custom);
        data.putString("sex", sex);
        if (custom && data.getString("customName").isBlank()) {
            data.putString("customName", style == DocStyle.CN_ID ? "Custom Identity Card" : "Custom Document");
        }
        ModNetwork.CHANNEL.sendToServer(new ModifierGeneratePacket(data));
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
        int color = parseColor(customColor == null ? "" : customColor.getValue(), style.accentColor());
        graphics.fill(left + 292, top + 80, left + 302, top + 94, 0xFF000000 | color);
        graphics.renderOutline(left + 292, top + 80, 10, 14, Ui.BORDER);
    }
}
