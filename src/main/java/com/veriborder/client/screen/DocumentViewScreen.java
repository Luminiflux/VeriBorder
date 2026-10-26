package com.veriborder.client.screen;

import com.veriborder.client.BetterButton;
import com.veriborder.client.TemplateDisplay;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.client.ui.CardPanel;
import com.veriborder.client.ui.DocumentArt;
import com.veriborder.client.ui.PagedList;
import com.veriborder.client.ui.PortraitArt;
import com.veriborder.client.ui.TabStrip;
import com.veriborder.doc.CredentialCode;
import com.veriborder.doc.CredentialState;
import com.veriborder.doc.DateUtil;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentKind;
import com.veriborder.doc.DocumentTitles;
import com.veriborder.doc.EndorsementRecord;
import com.veriborder.doc.MrzUtil;
import com.veriborder.doc.StampRecord;
import com.veriborder.doc.VerificationResult;
import com.veriborder.doc.VisaRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 26.3 paged document viewer.
 *
 * <p>Replaces the single scrolling pane of the old viewer with seven pages:
 * Identity, MRZ, Visas, Endorsements, Stamps, History and Integrity. The left
 * portrait card stays visible on every page so the holder, number, status and
 * credential codes are always one glance away; the right card hosts the page
 * content, lists being rendered by the shared {@link PagedList} component.</p>
 *
 * <p>Pages are shared by every entry point: the held document (right click),
 * reader terminals, the universal modifier preview and the admin border
 * terminal. A terminal snapshot has no signature of its own, so the caller can
 * pass the server-reported integrity flag instead of recomputing it, and a
 * server-side border history to fill the History page.</p>
 */
public class DocumentViewScreen extends VeriScreen {
    public static final int TAB_IDENTITY = 0;
    public static final int TAB_MRZ = 1;
    public static final int TAB_VISAS = 2;
    public static final int TAB_ENDORSEMENTS = 3;
    public static final int TAB_STAMPS = 4;
    public static final int TAB_HISTORY = 5;
    public static final int TAB_INTEGRITY = 6;

    /** Integrity row states. */
    private static final int STATE_FAIL = 0;
    private static final int STATE_PASS = 1;
    private static final int STATE_WARN = 2;

    /** One History row: server border log or a synthesised document event. */
    public record HistoryLine(String time, String title, String detail, int color, boolean inbound) {
    }

    /** A stamp plus its optional 26.3 time-of-day field. */
    public record StampView(StampRecord record, String time) {
    }

    /** One integrity checklist row. */
    public record IntegrityCheck(Component label, Component detail, int state) {
    }

    // ------------------------------------------------------------------ layout
    private static final int STRIP_X = 14;
    private static final int STRIP_Y = 44;
    private static final int STRIP_W = 532;
    private static final int PANEL_Y = 70;
    private static final int PANEL_H = 272;
    private static final int LEFT_X = 14;
    private static final int LEFT_W = 184;
    private static final int RIGHT_X = 206;
    private static final int RIGHT_W = 340;
    private static final int FOOTER_Y = 348;

    private static final int VISA_ROW_H = 58;
    private static final int ENDORSE_ROW_H = 46;
    private static final int STAMP_ROW_H = 58;
    private static final int HISTORY_ROW_H = 26;
    private static final int CHECK_ROW_H = 21;

    // ------------------------------------------------------------------- state
    private final ItemStack stack;
    private final DocumentData doc;
    private final VerificationResult check;
    private final Boolean integrityOverride;
    private final List<HistoryLine> serverHistory;
    private Runnable onBack;

    private String headerTitleOverride;
    private String headerSubtitleOverride;
    private boolean printButton = true;
    private int tab = TAB_IDENTITY;
    /**
     * 26.3: permanent codes ("永久编码（个案豁免）") are an advanced option and are
     * no longer part of the portrait card. The Integrity page shows them only
     * when this fold is expanded (collapsed by default).
     */
    private boolean advancedOpen;

    private final int[] tabPages = new int[7];

    private TabStrip tabs;
    private PagedList<VisaRecord> visaList;
    private PagedList<EndorsementRecord> endorseList;
    private PagedList<StampView> stampList;
    private PagedList<HistoryLine> historyList;
    private PagedList<IntegrityCheck> integrityList;

    public DocumentViewScreen(ItemStack stack, DocumentData doc) {
        this(stack, doc, null, null, List.of(), null);
    }

    public DocumentViewScreen(ItemStack stack, DocumentData doc, VerificationResult check) {
        this(stack, doc, check, null, List.of(), null);
    }

    public DocumentViewScreen(ItemStack stack, DocumentData doc, VerificationResult check,
                              Boolean integrityOverride, List<HistoryLine> history, Runnable onBack) {
        super(Component.translatable("screen.veriborder.document"), 560, 376);
        this.stack = stack == null ? ItemStack.EMPTY : stack;
        this.doc = doc;
        this.check = check;
        this.integrityOverride = integrityOverride;
        this.serverHistory = history == null ? List.of() : List.copyOf(history);
        this.onBack = onBack;
        this.accent = doc == null ? Ui.CYAN : TemplateDisplay.documentTint(doc) | 0xFF000000;
    }

    /** Header text for screens that host the viewer (reader, terminal, modifier). */
    public DocumentViewScreen withHeader(String title, String subtitle) {
        this.headerTitleOverride = title;
        this.headerSubtitleOverride = subtitle;
        return this;
    }

    public DocumentViewScreen withPrint(boolean value) {
        this.printButton = value;
        return this;
    }

    public DocumentViewScreen withBack(Runnable action) {
        this.onBack = action;
        return this;
    }

    public DocumentViewScreen initialTab(int index) {
        this.tab = Math.max(TAB_IDENTITY, Math.min(index, TAB_INTEGRITY));
        return this;
    }

    public int currentTab() {
        return tab;
    }

    // ------------------------------------------------------------------- build
    @Override
    protected void build() {
        accent = doc == null ? Ui.CYAN : TemplateDisplay.documentTint(doc) | 0xFF000000;
        tabs = TabStrip.at(STRIP_X, STRIP_Y, STRIP_W).size(20, 3);
        tabs.add("identity", Component.translatable("tab.veriborder.identity"));
        tabs.add("mrz", Component.translatable("tab.veriborder.mrz"));
        tabs.add("visas", countLabel("tab.veriborder.visas", visaCount()));
        tabs.add("endorsements", countLabel("tab.veriborder.endorsements", endorsementCount()));
        tabs.add("stamps", countLabel("tab.veriborder.stamps", stampCount()));
        tabs.add("history", Component.translatable("tab.veriborder.history"));
        tabs.add("integrity", Component.translatable("tab.veriborder.integrity"));
        tabs.setSelected(tab);

        if (doc != null) {
            CardPanel.Content content = rightContent();
            visaList = PagedList.<VisaRecord>at(content.x(), content.y(), content.width(), content.height())
                    .rowHeight(VISA_ROW_H).accent(accent).emptyText(noRecords())
                    .setItems(doc.visas());
            visaList.setPage(tabPages[TAB_VISAS]);
            endorseList = PagedList.<EndorsementRecord>at(content.x(), content.y(), content.width(),
                            content.height())
                    .rowHeight(ENDORSE_ROW_H).accent(accent).emptyText(noRecords())
                    .setItems(doc.endorsements());
            endorseList.setPage(tabPages[TAB_ENDORSEMENTS]);
            stampList = PagedList.<StampView>at(content.x(), content.y(), content.width(), content.height())
                    .rowHeight(STAMP_ROW_H).accent(accent).emptyText(noRecords())
                    .setItems(stampViews());
            stampList.setPage(tabPages[TAB_STAMPS]);
            historyList = PagedList.<HistoryLine>at(content.x(), content.y(), content.width(),
                            content.height())
                    .rowHeight(HISTORY_ROW_H).accent(accent).emptyText(noRecords())
                    .setItems(historyRows());
            historyList.setPage(tabPages[TAB_HISTORY]);
            integrityList = PagedList.<IntegrityCheck>at(content.x(), content.y(), content.width(),
                            content.height())
                    .rowHeight(CHECK_ROW_H).accent(accent).emptyText(noRecords())
                    .setItems(integrityRows());
            integrityList.setPage(tabPages[TAB_INTEGRITY]);
        }

        if (doc != null && printButton) {
            addRenderableWidget(new BetterButton(left + 386, top + FOOTER_Y, 76, 20,
                    Component.translatable("screen.veriborder.print"), button ->
                    ClientScreens.openPrint(doc), Ui.GOLD, true));
        }
        if (doc != null && tab == TAB_INTEGRITY) {
            addRenderableWidget(new BetterButton(left + 300, top + FOOTER_Y, 80, 20,
                    Component.translatable(advancedOpen
                            ? "screen.veriborder.config.advanced.open"
                            : "screen.veriborder.config.advanced.closed"), button -> {
                        advancedOpen = !advancedOpen;
                        rebuildWidgets();
                    }, Ui.PURPLE, true));
        }
        addRenderableWidget(new BetterButton(left + 470, top + FOOTER_Y, 76, 20,
                Component.translatable("button.veriborder.close"), button -> onClose(), Ui.MUTED, true));
    }

    private static CardPanel.Content rightContent() {
        // content() of the right card: 4px inset padding + 24px header.
        return CardPanel.at(RIGHT_X, PANEL_Y, RIGHT_W, PANEL_H).content();
    }

    private static Component countLabel(String key, int count) {
        return Component.translatable(key, count);
    }

    private static Component noRecords() {
        return Component.translatable("document.veriborder.no_records");
    }

    private int visaCount() {
        return doc == null ? 0 : doc.visas().size();
    }

    private int endorsementCount() {
        return doc == null ? 0 : doc.endorsements().size();
    }

    private int stampCount() {
        return doc == null ? 0 : doc.stamps().size();
    }

    // ------------------------------------------------------------------ render
    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, titleText(), subtitleText());
        drawPortraitCard(graphics);
        tabs.draw(graphics, font, accent);
        if (doc == null) {
            CardPanel.at(RIGHT_X, PANEL_Y, RIGHT_W, PANEL_H).accent(accent)
                    .title(tabTitle()).draw(graphics, font);
            graphics.drawCenteredString(font, Component.translatable("document.veriborder.empty"),
                    RIGHT_X + RIGHT_W / 2, PANEL_Y + PANEL_H / 2, Ui.MUTED);
            return;
        }
        CardPanel card = CardPanel.at(RIGHT_X, PANEL_Y, RIGHT_W, PANEL_H).accent(accent)
                .title(tabTitle()).hint(tabHint());
        CardPanel.Content content = card.draw(graphics, font);
        switch (tab) {
            case TAB_MRZ -> drawMrzPage(graphics, content);
            case TAB_VISAS -> visaList.draw(graphics, font, this::drawVisaRow, mouseUiX, mouseUiY);
            case TAB_ENDORSEMENTS -> endorseList.draw(graphics, font, this::drawEndorsementRow,
                    mouseUiX, mouseUiY);
            case TAB_STAMPS -> stampList.draw(graphics, font, this::drawStampRow, mouseUiX, mouseUiY);
            case TAB_HISTORY -> historyList.draw(graphics, font, this::drawHistoryRow,
                    mouseUiX, mouseUiY);
            case TAB_INTEGRITY -> integrityList.draw(graphics, font, this::drawIntegrityRow,
                    mouseUiX, mouseUiY);
            default -> drawIdentityPage(graphics, content);
        }
        drawFooter(graphics);
    }

    private double mouseUiX = -1;
    private double mouseUiY = -1;

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.mouseUiX = toUiX(mouseX);
        this.mouseUiY = toUiY(mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String titleText() {
        if (headerTitleOverride != null && !headerTitleOverride.isBlank()) {
            return headerTitleOverride;
        }
        if (doc == null) {
            return Component.translatable("screen.veriborder.document").getString();
        }
        // 26.4: the header title is the document title, so a locked/custom name
        // reaches it too; the cover name is only a last-resort fallback.
        // 26.4.1: the issuing template's own title wins over everything else.
        String templateTitle = TemplateDisplay.templateTitle(TemplateDisplay.documentTemplate(doc));
        if (!templateTitle.isBlank()) {
            return templateTitle;
        }
        return DocumentTitles.coverLabel(doc.displayName(), doc.coverName());
    }

    private String subtitleText() {
        if (headerSubtitleOverride != null && !headerSubtitleOverride.isBlank()) {
            return headerSubtitleOverride;
        }
        if (doc == null) {
            return Component.translatable("screen.veriborder.document.subtitle").getString();
        }
        return doc.documentType() + " \u00B7 " + doc.number() + " \u00B7 "
                + Component.translatable(doc.status().translationKey()).getString();
    }

    private Component tabTitle() {
        return switch (tab) {
            case TAB_MRZ -> Component.translatable("tab.veriborder.mrz");
            case TAB_VISAS -> Component.translatable("tab.veriborder.visas", visaCount());
            case TAB_ENDORSEMENTS -> Component.translatable("tab.veriborder.endorsements", endorsementCount());
            case TAB_STAMPS -> Component.translatable("tab.veriborder.stamps", stampCount());
            case TAB_HISTORY -> Component.translatable("tab.veriborder.history");
            case TAB_INTEGRITY -> Component.translatable("tab.veriborder.integrity");
            default -> Component.translatable("tab.veriborder.identity");
        };
    }

    private Component tabHint() {
        return switch (tab) {
            case TAB_MRZ -> Component.translatable("hint.veriborder.viewer.mrz");
            case TAB_VISAS -> Component.translatable("hint.veriborder.viewer.stickers");
            case TAB_ENDORSEMENTS -> Component.translatable("hint.veriborder.viewer.endorsements");
            case TAB_STAMPS -> Component.translatable("hint.veriborder.viewer.ink");
            case TAB_HISTORY -> Component.translatable("hint.veriborder.viewer.history");
            case TAB_INTEGRITY -> Component.translatable("hint.veriborder.viewer.integrity");
            default -> Component.translatable("hint.veriborder.viewer.identity");
        };
    }

    // ------------------------------------------------------------ portrait card
    private void drawPortraitCard(GuiGraphics graphics) {
        CardPanel card = CardPanel.at(LEFT_X, PANEL_Y, LEFT_W, PANEL_H).accent(accent)
                .title(doc == null ? Component.translatable("screen.veriborder.document")
                        : Ui.translatedOr(doc.kind().translationKey(), doc.kind().name()));
        CardPanel.Content content = card.draw(graphics, font);
        if (doc == null) {
            graphics.drawString(font, Component.translatable("document.veriborder.empty").getString(),
                    content.x(), content.y() + 4, Ui.MUTED, false);
            return;
        }
        int x = content.x();
        int width = content.width();
        boolean passport = doc.kind() == DocumentKind.PASSPORT;
        int photoHeight = passport ? 82 : 96;
        // 26.5 portrait: every credential kind prints the holder's real photo
        // (player skin / shipped preset / uploaded PNG) in exactly this box, so the
        // viewer shows the same face the border post and the issuing machine do.
        // A passport used to be excluded here, which is why an issued passport
        // showed no portrait at all; its cover now keeps the emblem AND reserves a
        // photo window, so the two no longer exclude each other.
        String emblem = TemplateDisplay.documentEmblem(doc);
        String coverTitle = TemplateDisplay.documentTitle(doc);
        if (passport) {
            DocumentArt.Slot slot = DocumentArt.passportCover(graphics, font, x, content.y(), width,
                    photoHeight, accent, emblem, coverTitle, true);
            if (slot != null) {
                if (!PortraitArt.draw(graphics, slot.x(), slot.y(), slot.width(), slot.height(), doc,
                        DocumentArt.coverTone(accent))) {
                    // No resolvable portrait (unknown preset, an upload the snapshot
                    // did not carry): keep the window and its geometry, print the
                    // generic photo placeholder instead of leaving a hole.
                    DocumentArt.photoPlaceholder(graphics, slot.x(), slot.y(), slot.width(),
                            slot.height(), DocumentArt.coverTone(accent));
                }
            }
        } else if (!PortraitArt.draw(graphics, x, content.y(), width, photoHeight, doc,
                DocumentArt.coverTone(accent))) {
            // 26.4 display fix: the cover plate carries the DOCUMENT title — the
            // issuer-locked / moderator custom name when there is one, otherwise the
            // layout name. The holder name is a separate field and is printed on the
            // row below (doc.name()), never on the cover.
            // 26.5: the plate is painted with the document's own colour (the template
            // tint), never with a fixed slate-blue plate.
            DocumentArt.portrait(graphics, font, x, content.y(), width, photoHeight, accent, false,
                    coverTitle, emblem);
        }
        // Barcode + MRZ plate are pinned to the card bottom first. Every text row
        // above is then laid out inside the remaining band, so a long number,
        // nationality or authority can never collide with them (the previous
        // permanent-code chips overflowed into this area on ID cards).
        int barcodeY = content.y() + content.height() - 26;
        int textBottom = barcodeY - 14;
        int y = content.y() + photoHeight + 6;
        graphics.drawString(font, Ui.ellipsis(doc.name().isBlank() ? "\u2014" : doc.name(), width), x, y,
                Ui.TEXT, false);
        y += 12;
        graphics.drawString(font, Ui.ellipsis(doc.number(), width), x, y, Ui.GOLD, false);
        y += 13;
        int statusColor = doc.status().color();
        int pill = CardPanel.statusPill(graphics, font, x, y,
                Component.translatable(doc.status().translationKey()), statusColor);
        if (doc.customStyle()) {
            CardPanel.statusPill(graphics, font, x + pill + 4, y,
                    Component.translatable("document.veriborder.custom"), Ui.CYAN);
        }
        y += 16;
        if (y + 6 < textBottom) {
            graphics.fill(x, y, x + width, y + 1, 0x552B4056);
            y += 4;
        }
        // Core holder facts only. Permanent codes moved to the Integrity page's
        // Advanced fold (26.3: they are an advanced option, not card content).
        y = drawCardRow(graphics, x, y, width, "field.veriborder.nationality",
                "Nationality", doc.nationality(), Ui.MUTED, textBottom);
        y = drawCardRow(graphics, x, y, width, "field.veriborder.birth", "Date of Birth",
                doc.birthDate(), Ui.MUTED, textBottom);
        String expiry = doc.isPermanent()
                ? Component.translatable("date.veriborder.permanent").getString() : doc.expiryDate();
        drawCardRow(graphics, x, y, width, "field.veriborder.expiry", "Valid Until",
                expiry, doc.isExpired() ? Ui.RED : Ui.MUTED, textBottom);
        // Barcode placeholder pinned to the card bottom.
        DocumentArt.barcode(graphics, x, barcodeY, width, 18, doc.number(), 0xFF9FB3C6);
        graphics.drawString(font, Ui.ellipsis(doc.mrz1().isBlank() ? doc.number() : doc.mrz1(), width),
                x, barcodeY - 10, Ui.DIM, false);
    }

    /**
     * One portrait-card row, skipped when the band above the barcode is full so
     * rows never overlap the pinned barcode/MRZ plate.
     */
    private int drawCardRow(GuiGraphics graphics, int x, int y, int width, String labelKey,
                            String labelFallback, String value, int color, int bottom) {
        if (y + 10 > bottom) {
            return y;
        }
        return CardPanel.keyValue(graphics, font, x, y, width, labelKey, labelFallback,
                Ui.ellipsis(value == null ? "" : value, width), color);
    }

    // --------------------------------------------------------------- identity
    private void drawIdentityPage(GuiGraphics graphics, CardPanel.Content content) {
        int columnWidth = (content.width() - 12) / 2;
        int leftY = content.y();
        int rightY = content.y();
        int leftX = content.x();
        int rightX = content.x() + columnWidth + 12;
        leftY = CardPanel.keyValue(graphics, font, leftX, leftY, columnWidth, "field.veriborder.name",
                "Name", doc.name(), Ui.TEXT);
        leftY = CardPanel.keyValue(graphics, font, leftX, leftY, columnWidth, "field.veriborder.name_latin",
                "Latin Name", doc.nameLatin(), Ui.TEXT);
        leftY = CardPanel.keyValue(graphics, font, leftX, leftY, columnWidth, "field.veriborder.number",
                "Document No.", doc.number(), Ui.GOLD);
        leftY = CardPanel.keyValue(graphics, font, leftX, leftY, columnWidth, "field.veriborder.nationality",
                "Nationality", doc.nationality(), Ui.TEXT);
        leftY = CardPanel.keyValue(graphics, font, leftX, leftY, columnWidth, "field.veriborder.birth",
                "Date of Birth", doc.birthDate(), Ui.TEXT);
        leftY = CardPanel.keyValue(graphics, font, leftX, leftY, columnWidth, "field.veriborder.sex", "Sex",
                Component.translatable(doc.sexKey()).getString(), Ui.TEXT);
        leftY = CardPanel.keyValue(graphics, font, leftX, leftY, columnWidth, "field.veriborder.issuer",
                "Issuing Authority", doc.authority(), Ui.TEXT);

        rightY = CardPanel.keyValue(graphics, font, rightX, rightY, columnWidth, "field.veriborder.place",
                "Place of Issue", doc.placeOfIssue(), Ui.TEXT);
        rightY = CardPanel.keyValue(graphics, font, rightX, rightY, columnWidth, "field.veriborder.issue",
                "Issue Date", doc.issueDate(), Ui.TEXT);
        rightY = CardPanel.keyValue(graphics, font, rightX, rightY, columnWidth, "field.veriborder.expiry",
                "Valid Until", doc.isPermanent()
                        ? Component.translatable("date.veriborder.permanent").getString() : doc.expiryDate(),
                doc.isExpired() ? Ui.RED : Ui.TEXT);
        rightY = CardPanel.keyValue(graphics, font, rightX, rightY, columnWidth, "field.veriborder.address",
                "Address", doc.address(), Ui.TEXT);
        rightY = CardPanel.keyValue(graphics, font, rightX, rightY, columnWidth, "field.veriborder.issuer_country",
                "Issuer Country", doc.mrzCountry(), Ui.TEXT);
        // 26.5 layout row: a template-issued document says so — "自定义版式 · <模板名>"
        // with the official layout it is built on in the small print underneath, so an
        // officer can tell a custom credential from the layout it resembles.
        rightY = CardPanel.keyValue(graphics, font, rightX, rightY, columnWidth,
                Component.translatable("field.veriborder.model"), TemplateDisplay.layoutRowValue(doc),
                Ui.MUTED);
        String layoutNote = TemplateDisplay.layoutRowNote(doc);
        if (!layoutNote.isBlank()) {
            graphics.drawString(font, Ui.ellipsis(layoutNote, columnWidth), rightX, rightY, Ui.DIM, false);
            rightY += 10;
        }
        rightY = CardPanel.keyValue(graphics, font, rightX, rightY, columnWidth,
                Component.translatable("field.veriborder.schema"), String.valueOf(doc.copyTag().getInt("schema")),
                Ui.MUTED);

        int dividerY = content.y() + content.height() - 62;
        graphics.fill(leftX, dividerY, leftX + content.width(), dividerY + 1, 0x332B4056);
        boolean signed = effectiveIntegrity();
        String integrity = Component.translatable("document.veriborder.integrity",
                Component.translatable(signed ? "document.veriborder.integrity.ok"
                        : "document.veriborder.integrity.bad")).getString();
        graphics.drawString(font, integrity, leftX, dividerY + 6, signed ? Ui.GREEN : Ui.RED, false);
        graphics.drawString(font, Component.translatable("document.veriborder.records", visaCount(),
                endorsementCount(), stampCount()).getString(), leftX, dividerY + 19, Ui.MUTED, false);
        if (check != null) {
            Ui.drawCheck(graphics, check, leftX, dividerY + 32, content.width(), 18);
        }
    }

    // -------------------------------------------------------------------- MRZ
    private void drawMrzPage(GuiGraphics graphics, CardPanel.Content content) {
        if (doc.kind() != DocumentKind.PASSPORT) {
            graphics.drawString(font, Component.translatable("hint.veriborder.viewer.mrz_none").getString(),
                    content.x(), content.y() + 6, Ui.MUTED, false);
            int y = content.y() + 28;
            y = CardPanel.keyValue(graphics, font, content.x(), y, content.width(),
                    "field.veriborder.name_latin", "Latin Name", doc.nameLatin(), Ui.TEXT);
            y = CardPanel.keyValue(graphics, font, content.x(), y, content.width(),
                    "field.veriborder.birth", "Date of Birth", doc.birthDate(), Ui.TEXT);
            y = CardPanel.keyValue(graphics, font, content.x(), y, content.width(),
                    "field.veriborder.sex", "Sex", Component.translatable(doc.sexKey()).getString(), Ui.TEXT);
            CardPanel.keyValue(graphics, font, content.x(), y, content.width(),
                    "field.veriborder.expiry", "Valid Until", doc.expiryDate(), Ui.TEXT);
            return;
        }
        String first = doc.mrz1();
        String second = doc.mrz2();
        int y = content.y() + 8;
        graphics.drawString(font, Component.translatable("screen.veriborder.print.mrz").getString(),
                content.x(), y, accent, false);
        y += 12;
        int stripWidth = Math.min(content.width() - 6, Math.max(DocumentArt.mrzWidth(content.width() - 6, first),
                DocumentArt.mrzWidth(content.width() - 6, second)));
        DocumentArt.mrzStrip(graphics, font, content.x() + 2, y, stripWidth, first, 0xFFE9F3FF);
        y += 16;
        DocumentArt.mrzStrip(graphics, font, content.x() + 2, y, stripWidth, second, 0xFFE9F3FF);
        y += 22;
        graphics.fill(content.x(), y, content.x() + content.width(), y + 1, 0x332B4056);
        y += 6;
        int column = (content.width() - 12) / 2;
        int leftY = y;
        int rightY = y;
        leftY = mrzField(graphics, content.x(), leftY, column, "field.veriborder.mrz_type", first.length() >= 2
                ? first.substring(0, 2) : "\u2014");
        leftY = mrzField(graphics, content.x(), leftY, column, "field.veriborder.mrz_issuer",
                first.length() >= 5 ? first.substring(2, 5) : "\u2014");
        leftY = mrzField(graphics, content.x(), leftY, column, "field.veriborder.number",
                second.length() >= 9 ? second.substring(0, 9) : doc.number());
        leftY = mrzField(graphics, content.x(), leftY, column, "field.veriborder.nationality",
                second.length() >= 13 ? second.substring(10, 13) : doc.mrzCountry());
        leftY = mrzField(graphics, content.x(), leftY, column, "field.veriborder.birth",
                second.length() >= 19 ? second.substring(13, 19) : DateUtil.mrzDate(doc.birthDate()));
        rightY = mrzField(graphics, content.x() + column + 12, rightY, column, "field.veriborder.sex",
                second.length() >= 21 ? second.substring(20, 21) : doc.sex());
        rightY = mrzField(graphics, content.x() + column + 12, rightY, column, "field.veriborder.expiry",
                second.length() >= 27 ? second.substring(21, 27) : DateUtil.mrzDate(doc.expiryDate()));
        rightY = mrzField(graphics, content.x() + column + 12, rightY, column,
                "field.veriborder.mrz_personal",
                second.length() >= 43 ? second.substring(28, 42) : "\u2014");
        rightY = mrzField(graphics, content.x() + column + 12, rightY, column,
                "field.veriborder.mrz_columns", first.length() + " + " + second.length());
        int bottom = Math.max(leftY, rightY);
        boolean digitsOk = checkDigits(first, second);
        graphics.drawString(font, Component.translatable("hint.veriborder.viewer.checkdigits",
                        Component.translatable(digitsOk ? "verify.veriborder.pass"
                                : "verify.veriborder.fail")).getString(),
                content.x(), bottom + 2, digitsOk ? Ui.GREEN : Ui.AMBER, false);
    }

    private int mrzField(GuiGraphics graphics, int x, int y, int width, String labelKey, String value) {
        return CardPanel.keyValue(graphics, font, x, y, width,
                Ui.label(labelKey, labelKey), value == null || value.isBlank() ? "\u2014"
                        : value.replace('<', ' ').trim(), Ui.TEXT);
    }

    /** Validates the three MRZ check digits (document number, birth, expiry). */
    private static boolean checkDigits(String first, String second) {
        if (second == null || second.length() < 28) {
            return false;
        }
        return checkDigit(second.substring(0, 9)) == digit(second.charAt(9))
                && checkDigit(second.substring(13, 19)) == digit(second.charAt(19))
                && checkDigit(second.substring(21, 27)) == digit(second.charAt(27));
    }

    private static int digit(char character) {
        return character >= '0' && character <= '9' ? character - '0' : -1;
    }

    /** Same weighting as {@code MrzUtil}, kept local to verify printed values. */
    private static int checkDigit(String value) {
        int[] weights = {7, 3, 1};
        int sum = 0;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            int weight;
            if (character >= '0' && character <= '9') {
                weight = character - '0';
            } else if (character >= 'A' && character <= 'Z') {
                weight = character - 'A' + 10;
            } else {
                weight = 0;
            }
            sum += weight * weights[i % 3];
        }
        return sum % 10;
    }

    // ---------------------------------------------------------------- visa rows
    private void drawVisaRow(GuiGraphics graphics, VisaRecord visa, int index, int x, int y, int width,
                             int height, boolean selected, boolean hovered) {
        // 26.3 round 12: colour and label come from the derived state
        // (valid / used up / expired / revoked), never from the stored flag alone.
        CredentialState state = visa.state();
        int stateColor = state.color();
        int fill = selected ? 0xFF1B2C3D : hovered ? 0xFF172636 : 0x66101A24;
        graphics.fill(x, y, x + width, y + height, fill);
        graphics.renderOutline(x, y, width, height, selected ? accent : 0x332A3B50);
        graphics.fill(x, y, x + 4, y + height, stateColor);
        // Sticker perforation: a dotted left edge reads as a glued-in visa.
        for (int dot = y + 4; dot < y + height - 3; dot += 6) {
            graphics.fill(x + 6, dot, x + 7, dot + 2, 0x66FFFFFF);
        }
        int inner = x + 12;
        graphics.drawString(font, Ui.ellipsis(DocumentData.visaDisplayLabel(visa), width - 100),
                inner, y + 4, Ui.TEXT, false);
        Component type = Component.translatable(visa.type().translationKey());
        graphics.drawString(font, Ui.ellipsis(type.getString(), width - 100), inner, y + 15, Ui.MUTED,
                false);
        CardPanel.statusPill(graphics, font, x + width - 76, y + 4,
                Component.translatable(state.translationKey()), stateColor);
        CardPanel.chip(graphics, font, inner, y + 28, visa.code(), Ui.GOLD, false);
        graphics.drawString(font, visa.entriesText(), inner + 74, y + 28, Ui.MUTED, false);
        graphics.drawString(font, visa.issueDate() + " \u2192 " + visa.expiryDate(), inner + 108, y + 28,
                Ui.DIM, false);
        String authority = visa.authority().isBlank() ? "\u2014" : visa.authority();
        graphics.drawString(font, Ui.ellipsis(authority, width - 24), inner, y + 41, Ui.DIM, false);
    }

    // --------------------------------------------------------- endorsement rows
    private void drawEndorsementRow(GuiGraphics graphics, EndorsementRecord record, int index, int x, int y,
                                    int width, int height, boolean selected, boolean hovered) {
        CredentialState state = record.state();
        int stateColor = state.color();
        graphics.fill(x, y, x + width, y + height, selected ? 0xFF1B2C3D : hovered ? 0xFF172636
                : 0x66101A24);
        graphics.renderOutline(x, y, width, height, selected ? accent : 0x332A3B50);
        graphics.fill(x, y, x + 4, y + height, stateColor);
        int inner = x + 12;
        graphics.drawString(font, Ui.ellipsis(Component.translatable(record.type().translationKey())
                .getString(), width - 100), inner, y + 4, Ui.TEXT, false);
        CardPanel.statusPill(graphics, font, x + width - 76, y + 4,
                Component.translatable(state.translationKey()), stateColor);
        CardPanel.chip(graphics, font, inner, y + 17, record.code(), Ui.GOLD, false);
        graphics.drawString(font, record.entriesText(), inner + 74, y + 17, Ui.MUTED, false);
        graphics.drawString(font, record.issueDate() + " \u2192 " + record.expiryDate(), inner + 108,
                y + 17, Ui.DIM, false);
        graphics.drawString(font, record.id(), inner, y + 30, Ui.DIM, false);
    }

    // ---------------------------------------------------------------- stamp row
    private void drawStampRow(GuiGraphics graphics, StampView view, int index, int x, int y, int width,
                              int height, boolean selected, boolean hovered) {
        StampRecord stamp = view.record();
        int color = stamp.inbound() ? Ui.GREEN : Ui.AMBER;
        graphics.fill(x, y, x + width, y + height, selected ? 0xFF1B2C3D : hovered ? 0xFF172636
                : 0x66101A24);
        graphics.renderOutline(x, y, width, height, selected ? accent : 0x332A3B50);
        graphics.fill(x, y, x + 3, y + height, color);
        DocumentArt.stampInk(graphics, font, x + 42, y + height / 2 - 1, 34, 21,
                Component.translatable(stamp.directionKey()).getString(), stamp.port(), stamp.date(),
                view.time(), stamp.officer(), color, index % 2 == 0 ? -6.0F : 5.0F);
        DocumentArt.inkSpecks(graphics, x + 42, y + height / 2 - 1, 34, 21, color, index * 31 + 7);
        int inner = x + 86;
        graphics.drawString(font, Component.translatable(stamp.directionKey()).getString(), inner,
                y + 6, color, false);
        graphics.drawString(font, Ui.ellipsis(stamp.port() + " / " + stamp.country(), width - 96),
                inner, y + 18, Ui.TEXT, false);
        String when = stamp.date() + "  " + (view.time().isBlank() ? "--:--" : view.time());
        graphics.drawString(font, when, inner, y + 30, Ui.MUTED, false);
        graphics.drawString(font, Ui.ellipsis(stamp.officer(), width - 96), inner, y + 42, Ui.DIM, false);
    }

    // -------------------------------------------------------------- history row
    private void drawHistoryRow(GuiGraphics graphics, HistoryLine line, int index, int x, int y, int width,
                                int height, boolean selected, boolean hovered) {
        graphics.fill(x, y, x + width, y + height - 1, selected ? 0xFF1B2C3D : hovered ? 0xFF172636
                : 0x44101A24);
        if (index % 2 == 0) {
            graphics.fill(x, y, x + width, y + height - 1, 0x221E3346);
        }
        graphics.fill(x + 2, y + 2, x + 4, y + height - 3, line.color());
        if (index < 40) {
            graphics.fill(x + 2, y + height - 3, x + 2 + Math.min(width - 4, 3), y + height - 2,
                    line.color());
        }
        graphics.drawString(font, Ui.ellipsis(line.time(), 84), x + 8, y + 4, Ui.DIM, false);
        graphics.drawString(font, Ui.ellipsis(line.title(), 110), x + 94, y + 4, line.color(), false);
        graphics.drawString(font, Ui.ellipsis(line.detail(), width - 212), x + 206, y + 4, Ui.MUTED,
                false);
        graphics.drawString(font, line.inbound() ? "\u2190" : "\u2192", x + width - 14, y + 4,
                line.color(), false);
        graphics.fill(x + 8, y + height - 1, x + width - 4, y + height, 0x332A3B50);
    }

    // ------------------------------------------------------------ integrity row
    private void drawIntegrityRow(GuiGraphics graphics, IntegrityCheck row, int index, int x, int y,
                                  int width, int height, boolean selected, boolean hovered) {
        int color = switch (row.state()) {
            case STATE_PASS -> Ui.GREEN;
            case STATE_WARN -> Ui.AMBER;
            default -> Ui.RED;
        };
        String symbol = row.state() == STATE_PASS ? "\u2713" : row.state() == STATE_WARN ? "!" : "\u2715";
        graphics.fill(x, y, x + width, y + height - 1, selected ? 0xFF1B2C3D : hovered ? 0xFF172636
                : 0x33101A24);
        graphics.fill(x, y, x + 2, y + height - 1, color);
        graphics.drawString(font, symbol, x + 7, y + 4, color, false);
        graphics.drawString(font, Ui.ellipsis(row.label().getString(), 132), x + 20, y + 4, Ui.TEXT,
                false);
        graphics.drawString(font, Ui.ellipsis(row.detail().getString(), width - 166), x + 156, y + 4,
                color, false);
    }

    // --------------------------------------------------------------- integrity
    private boolean effectiveIntegrity() {
        return integrityOverride != null ? integrityOverride : doc != null && doc.integrityValid();
    }

    private List<IntegrityCheck> integrityRows() {
        List<IntegrityCheck> rows = new ArrayList<>();
        if (doc == null) {
            return rows;
        }
        boolean signed = effectiveIntegrity();
        rows.add(new IntegrityCheck(Component.translatable("integrity.veriborder.signature"),
                Ui.label(signed ? "integrity.veriborder.signature.ok" : "integrity.veriborder.signature.bad",
                        signed ? "verified" : "tampered"),
                signed ? STATE_PASS : STATE_FAIL));
        int schema = doc.copyTag().getInt("schema");
        rows.add(new IntegrityCheck(Component.translatable("integrity.veriborder.schema"),
                Component.translatable("integrity.veriborder.schema.value", schema),
                schema >= DocumentData.SCHEMA ? STATE_PASS : STATE_WARN));
        // The signed style itself: "template:<id>" for a custom layout, the official
        // layout id otherwise. `styleKnown()` verifies the layout underneath, so the
        // row never reads as a failure just because the style names a template.
        String signedStyle = doc.copyTag().getString("style").trim();
        rows.add(new IntegrityCheck(Component.translatable("integrity.veriborder.style"),
                Component.literal(signedStyle.isBlank() ? doc.baseStyleId() : signedStyle),
                doc.styleKnown() ? STATE_PASS : STATE_FAIL));
        rows.add(new IntegrityCheck(Component.translatable("integrity.veriborder.status"),
                Component.literal(doc.status().id()), doc.statusKnown() ? STATE_PASS : STATE_WARN));
        boolean coreFields = !doc.name().isBlank() && !doc.number().isBlank();
        rows.add(new IntegrityCheck(Component.translatable("integrity.veriborder.fields"),
                Component.translatable(coreFields ? "integrity.veriborder.fields.ok"
                        : "integrity.veriborder.fields.missing"),
                coreFields ? STATE_PASS : STATE_FAIL));
        boolean dates = DateUtil.isValid(doc.birthDate()) && DateUtil.isValid(doc.issueDate())
                && (DateUtil.isValid(doc.expiryDate()) || DateUtil.isPermanent(doc.expiryDate()));
        rows.add(new IntegrityCheck(Component.translatable("integrity.veriborder.dates"),
                Component.translatable(dates ? "integrity.veriborder.dates.ok" : "integrity.veriborder.dates.bad"),
                dates ? STATE_PASS : STATE_FAIL));
        List<String> codes = doc.allCredentialCodes();
        int invalid = 0;
        for (String code : codes) {
            if (!CredentialCode.isValid(code)) {
                invalid++;
            }
        }
        boolean unique = codes.size() == codes.stream().distinct().count();
        StringBuilder codeDetail = new StringBuilder(Component.translatable(
                "integrity.veriborder.codes.value", codes.size()).getString());
        if (invalid > 0) {
            codeDetail.append(Component.translatable("integrity.veriborder.invalid_suffix", invalid).getString());
        }
        if (!unique) {
            codeDetail.append(Component.translatable("integrity.veriborder.duplicates_suffix").getString());
        }
        rows.add(new IntegrityCheck(Component.translatable("integrity.veriborder.codes"),
                Component.literal(codeDetail.toString()),
                invalid == 0 && unique ? STATE_PASS : STATE_WARN));
        if (doc.kind() == DocumentKind.PASSPORT) {
            boolean mrz = doc.mrz1().length() == 44 && doc.mrz2().length() == 44
                    && !MrzUtil.countryCode(doc.mrzCountry()).isEmpty();
            rows.add(new IntegrityCheck(Component.translatable("integrity.veriborder.mrz"),
                    Component.translatable(mrz ? "integrity.veriborder.mrz.ok"
                            : "integrity.veriborder.mrz.bad"),
                    mrz ? STATE_PASS : STATE_FAIL));
            boolean digits = checkDigits(doc.mrz1(), doc.mrz2());
            rows.add(new IntegrityCheck(Component.translatable("integrity.veriborder.checkdigits"),
                    Component.translatable(digits ? "integrity.veriborder.checkdigits.ok"
                            : "integrity.veriborder.checkdigits.bad"),
                    digits ? STATE_PASS : STATE_WARN));
        }
        int badRecords = 0;
        for (VisaRecord visa : doc.visas()) {
            if (!CredentialCode.isValid(visa.code()) || DateUtil.parseOrNull(visa.expiryDate()) == null) {
                badRecords++;
            }
        }
        for (EndorsementRecord endorsement : doc.endorsements()) {
            if (!CredentialCode.isValid(endorsement.code())
                    || DateUtil.parseOrNull(endorsement.expiryDate()) == null) {
                badRecords++;
            }
        }
        String recordsDetail = Component.translatable("integrity.veriborder.records.value",
                visaCount() + endorsementCount()).getString()
                + (badRecords > 0
                ? Component.translatable("integrity.veriborder.invalid_suffix", badRecords).getString() : "");
        rows.add(new IntegrityCheck(Component.translatable("integrity.veriborder.records"),
                Component.literal(recordsDetail), badRecords == 0 ? STATE_PASS : STATE_WARN));
        int badStamps = 0;
        for (StampRecord stamp : doc.stamps()) {
            if (DateUtil.parseOrNull(stamp.date()) == null) {
                badStamps++;
            }
        }
        String stampsDetail = Component.translatable("integrity.veriborder.stamps.value", stampCount()).getString()
                + (badStamps > 0
                ? Component.translatable("integrity.veriborder.invalid_suffix", badStamps).getString() : "");
        rows.add(new IntegrityCheck(Component.translatable("integrity.veriborder.stamps"),
                Component.literal(stampsDetail), badStamps == 0 ? STATE_PASS : STATE_WARN));
        rows.add(new IntegrityCheck(Component.translatable("integrity.veriborder.expiry"),
                Ui.label(doc.isExpired() ? "integrity.veriborder.expiry.expired"
                        : "integrity.veriborder.expiry.ok", doc.isExpired() ? "expired" : "in force"),
                doc.isExpired() ? STATE_WARN : STATE_PASS));
        if (advancedOpen) {
            rows.addAll(permanentCodeRows());
        }
        return rows;
    }

    /**
     * Advanced fold content: the document's permanent codes
     * ("永久编码（个案豁免）"). Shown on the Integrity page only, never on the
     * portrait card, so the card keeps its core identity fields.
     */
    private List<IntegrityCheck> permanentCodeRows() {
        List<IntegrityCheck> rows = new ArrayList<>();
        List<DocumentData.PermanentCode> codes = doc.permanentCodes();
        rows.add(new IntegrityCheck(Component.translatable("integrity.veriborder.exempt_title"),
                Component.translatable("integrity.veriborder.exempt_count", codes.size()),
                codes.isEmpty() ? STATE_PASS : STATE_PASS));
        if (codes.isEmpty()) {
            rows.add(new IntegrityCheck(Component.translatable("integrity.veriborder.exempt_code"),
                    Component.translatable("integrity.veriborder.exempt_empty"), STATE_PASS));
            return rows;
        }
        for (int i = 0; i < codes.size(); i++) {
            DocumentData.PermanentCode code = codes.get(i);
            String purpose = Component.translatable("integrity.veriborder.purpose." + code.purpose().id())
                    .getString();
            String country = code.hasIssuerCountry()
                    ? code.issuerCountry()
                    : Component.translatable("integrity.veriborder.no_country").getString();
            String expiry = code.hasExpiry() ? code.expiry()
                    : Component.translatable("date.veriborder.permanent").getString();
            String detail = Component.translatable("integrity.veriborder.exempt_detail", purpose, country, expiry)
                    .getString();
            rows.add(new IntegrityCheck(Component.literal(code.code()), Component.literal(detail),
                    code.expired() ? STATE_WARN : STATE_PASS));
        }
        return rows;
    }

    // ----------------------------------------------------------------- history
    private List<StampView> stampViews() {
        List<StampView> views = new ArrayList<>();
        if (doc == null) {
            return views;
        }
        List<StampRecord> stamps = doc.stamps();
        List<CompoundTag> tags = doc.stampTags();
        for (int i = 0; i < stamps.size(); i++) {
            String time = i < tags.size() ? tags.get(i).getString("time").trim() : "";
            views.add(new StampView(stamps.get(i), time));
        }
        return views;
    }

    private List<HistoryLine> historyRows() {
        if (!serverHistory.isEmpty()) {
            return serverHistory;
        }
        List<TimedLine> timed = new ArrayList<>();
        if (doc != null) {
            List<StampView> stamps = stampViews();
            for (StampView view : stamps) {
                StampRecord stamp = view.record();
                String key = stamp.date() + " " + (view.time().isBlank() ? "00:00" : view.time());
                timed.add(new TimedLine(key, new HistoryLine(key, stamp.inbound()
                        ? Component.translatable("stamp.veriborder.in").getString()
                        : Component.translatable("stamp.veriborder.out").getString(),
                        stamp.port() + " / " + stamp.country() + " \u00B7 " + stamp.officer(),
                        stamp.inbound() ? Ui.GREEN : Ui.AMBER, stamp.inbound())));
            }
            for (VisaRecord visa : doc.visas()) {
                timed.add(new TimedLine(visa.issueDate() + " 00:00", new HistoryLine(visa.issueDate(),
                        Component.translatable("history.veriborder.visa").getString(),
                        DocumentData.visaDisplayLabel(visa) + " \u00B7 " + visa.code(),
                        visa.active() ? Ui.PURPLE : Ui.RED, true)));
            }
            for (EndorsementRecord endorsement : doc.endorsements()) {
                timed.add(new TimedLine(endorsement.issueDate() + " 00:00", new HistoryLine(
                        endorsement.issueDate(),
                        Component.translatable("history.veriborder.endorsement").getString(),
                        Component.translatable(endorsement.type().translationKey()).getString()
                                + " \u00B7 " + endorsement.code(),
                        endorsement.active() ? Ui.CYAN : Ui.RED, true)));
            }
        }
        timed.sort(Comparator.comparing(TimedLine::key).reversed());
        List<HistoryLine> rows = new ArrayList<>();
        for (TimedLine line : timed) {
            rows.add(line.line());
        }
        return rows;
    }

    private record TimedLine(String key, HistoryLine line) {
    }

    // ------------------------------------------------------------------ footer
    private void drawFooter(GuiGraphics graphics) {
        if (check != null) {
            // Leave room for the Advanced fold button shown on the Integrity tab.
            int width = tab == TAB_INTEGRITY && doc != null ? 278 : 356;
            Ui.drawCheck(graphics, check, LEFT_X, FOOTER_Y + 2, width, 16);
            return;
        }
        if (doc == null) {
            return;
        }
        String rights = Component.translatable("document.veriborder.integrity",
                Component.translatable(effectiveIntegrity() ? "document.veriborder.integrity.ok"
                        : "document.veriborder.integrity.bad")).getString()
                + "  \u00B7  " + Component.translatable("document.veriborder.records", visaCount(),
                endorsementCount(), stampCount()).getString();
        graphics.drawString(font, Ui.ellipsis(rights, 356), LEFT_X, FOOTER_Y + 6,
                effectiveIntegrity() ? Ui.DIM : Ui.RED, false);
    }

    // ------------------------------------------------------------------- input
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        int hit = tabs.hit(uiX, uiY);
        if (hit >= 0) {
            rememberPage();
            tabs.setSelected(hit);
            tab = hit;
            rebuildWidgets();
            return true;
        }
        if (doc == null) {
            return false;
        }
        PagedList<?> active = activeList();
        return active != null && active.mouseClicked(uiX, uiY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (doc != null) {
            PagedList<?> active = activeList();
            if (active != null && active.mouseScrolled(toUiX(mouseX), toUiY(mouseY), delta)) {
                tabPages[tab] = active.page();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private PagedList<?> activeList() {
        return switch (tab) {
            case TAB_VISAS -> visaList;
            case TAB_ENDORSEMENTS -> endorseList;
            case TAB_STAMPS -> stampList;
            case TAB_HISTORY -> historyList;
            case TAB_INTEGRITY -> integrityList;
            default -> null;
        };
    }

    private void rememberPage() {
        PagedList<?> active = activeList();
        if (active != null) {
            tabPages[tab] = active.page();
        }
    }

    @Override
    public void onClose() {
        if (onBack != null) {
            Runnable action = onBack;
            onBack = null;
            action.run();
            return;
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Unused stack accessor kept so callers can later request item tooltips. */
    protected ItemStack documentStack() {
        return stack;
    }

    /** Opens the viewer for a held document stack, closing to the game. */
    public static void openFor(Minecraft minecraft, ItemStack stack, DocumentData doc) {
        minecraft.setScreen(new DocumentViewScreen(stack, doc));
    }
}
