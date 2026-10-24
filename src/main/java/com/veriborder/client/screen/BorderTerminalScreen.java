package com.veriborder.client.screen;

import com.veriborder.border.BorderTerminalService;
import com.veriborder.client.BetterButton;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.client.ui.Dropdown;
import com.veriborder.client.ui.PagedList;
import com.veriborder.client.ui.TabStrip;
import com.veriborder.doc.CredentialState;
import com.veriborder.doc.DateUtil;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentKind;
import com.veriborder.doc.DocumentStatus;
import com.veriborder.doc.DocumentTitles;
import com.veriborder.doc.EndorsementType;
import com.veriborder.doc.MrzUtil;
import com.veriborder.doc.VisaType;
import com.veriborder.network.BorderTerminalActionPacket;
import com.veriborder.network.ModNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;


/**
 * Admin border terminal.
 *
 * <p>Layout follows the rest of the mod (see {@link OfficerScreen}): a left
 * online-player list with [IN]/[OUT] state colours, a right panel that switches
 * between the selected traveller's document cards and their recent border logs,
 * and a role-gated action strip. OP level 2 gets the full edit surface
 * (document core fields, permanent codes, visas/endorsements, stamps, status and
 * forced entry/exit); everyone else gets a read-only console.</p>
 *
 * <p>The screen performs no permission logic of its own: every mutation is
 * re-validated by {@code BorderTerminalService}, and the server re-sends the
 * live snapshot after each action. The packet protocol is unchanged: action
 * strings + a payload {@link CompoundTag} on the way in, a snapshot on the way
 * out.</p>
 */
public class BorderTerminalScreen extends VeriScreen {
    // ------------------------------------------------------------- geometry
    private static final int PANEL_Y = 78;
    private static final int PANEL_H = 270;
    private static final int LEFT_X = 14;
    private static final int LEFT_W = 170;
    private static final int RIGHT_X = 190;
    private static final int RIGHT_W = 436;
    private static final int PLAYER_ROWS_TOP = 32;
    private static final int PLAYER_ROW_H = 22;
    private static final int PLAYER_ROWS = 10;
    private static final int DOC_ROWS_TOP = 30;
    private static final int DOC_CARD_H = 70;
    private static final int DOC_ROWS = 3;
    private static final int LOG_ROW_H = 25;
    private static final int LOG_ROWS = 9;

    private static final int ACTION_Y1 = 356;
    private static final int ACTION_Y2 = 380;
    private static final int ACTION_Y3 = 414;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final String UNKNOWN = "—";

    /** Remember the selected document while the server re-opens the screen. */
    private static String memoryUuid = "";
    private static int memorySlot = -1;

    private enum Page { MAIN, DOC, VISA, ENDORSEMENT, STAMP }

    // --------------------------------------------------------------- records
    private record PlayerRow(String uuid, String name, boolean session, String document, String port,
                             String gate, String dimension, long entryTime, boolean overstay,
                             int overstayDays) {
    }

    private record VisaRow(String id, VisaType type, String country, String code, boolean active,
                           String status, String entries, int entriesTotal, int usedEntries,
                           String issueDate, String expiry, String label, CredentialState state) {
    }

    private record EndorseRow(String id, EndorsementType type, String code, boolean active, String status,
                              String entries, int entriesTotal, int usedEntries, String issueDate,
                              String expiry, CredentialState state) {
    }

    private record StampRow(String id, String direction, String port, String country, String date,
                            String officer) {
    }

    private record LogRow(long time, String result, String direction, String document, String kind,
                          String gate, String detail) {
    }

    private record DocRow(int slot, String style, DocumentKind kind, boolean custom, boolean nameLocked,
                          String name, String nameLatin, String number, DocumentStatus status,
                          String birthDate, String sex, String nationality, String authority, String place,
                          String issueDate, String expiry, boolean permanent, String mrzCountry, String mrz1,
                          String mrz2, boolean integrity, String displayName, String coverName,
                          List<String> codes, List<VisaRow> visas, List<EndorseRow> endorsements,
                          List<StampRow> stamps) {
    }

    // ----------------------------------------------------------------- state
    private final CompoundTag data;
    private final List<PlayerRow> players;
    private final List<DocRow> docs;
    private final List<LogRow> logs;
    private final boolean admin;
    private String selectedUuid;
    private String selectedName;

    private int selectedSlot = -1;
    private int selectedCode = -1;
    private int selectedRecord = -1;
    private int selectedStamp = -1;

    private Page page = Page.MAIN;
    private int docsTab; // 0 documents, 1 logs
    private String playerFilter = "";
    private String docFilter = "";
    private String editorError = "";
    private String sexValue = "M";

    // 26.3: every unbounded list is a shared paged list; pages replace the old
    // pixel scroll offsets so row hit boxes stay stable at any GUI scale.
    private final TabStrip rightTabs = TabStrip.at(RIGHT_X + RIGHT_W - 224, PANEL_Y + 4, 220).size(16, 3);
    private final PagedList<PlayerRow> playerList = PagedList.<PlayerRow>at(LEFT_X + 1,
            PANEL_Y + PLAYER_ROWS_TOP, LEFT_W - 2, 234).rowHeight(PLAYER_ROW_H).rowGap(0).accent(Ui.CYAN);
    private final PagedList<DocRow> docList = PagedList.<DocRow>at(RIGHT_X + 1, PANEL_Y + DOC_ROWS_TOP,
            RIGHT_W - 2, 236).rowHeight(DOC_CARD_H).rowGap(0).accent(Ui.GOLD);
    private final PagedList<LogRow> logList = PagedList.<LogRow>at(RIGHT_X + 1, PANEL_Y + DOC_ROWS_TOP,
            RIGHT_W - 2, 236).rowHeight(LOG_ROW_H).rowGap(0).accent(Ui.GOLD);
    private final PagedList<VisaRow> visaList = PagedList.<VisaRow>at(LEFT_X + 1, 90, 611, 134)
            .rowHeight(30).rowGap(0).accent(Ui.PURPLE);
    private final PagedList<EndorseRow> endorseList = PagedList.<EndorseRow>at(LEFT_X + 1, 90, 611, 134)
            .rowHeight(30).rowGap(0).accent(Ui.PURPLE);
    private final PagedList<StampRow> stampList = PagedList.<StampRow>at(LEFT_X + 1, 90, 611, 294)
            .rowHeight(28).rowGap(0).accent(Ui.GOLD);
    /** Raw snapshot entries by inventory slot, kept for the full document view. */
    private final Map<Integer, CompoundTag> docEntries = new HashMap<>();
    private int hoverX = -1;
    private int hoverY = -1;

    // widgets (main)
    private EditBox playerSearch;
    private EditBox docSearch;
    private EditBox codeInput;
    private EditBox forcePort;
    private BetterButton editButton;
    private BetterButton visaButton;
    private BetterButton endorseButton;
    private BetterButton stampsButton;
    private BetterButton resetButton;
    private BetterButton viewButton;
    private Dropdown statusDropdown;
    private BetterButton forceEntryButton;
    private BetterButton deleteCodeButton;

    // widgets (document editor)
    private EditBox docName;
    private EditBox docNameLatin;
    private EditBox docNumber;
    private EditBox docBirth;
    private EditBox docNationality;
    private EditBox docAuthority;
    private EditBox docPlace;
    private EditBox docIssue;
    private EditBox docExpiry;
    private EditBox docMrz;
    private EditBox docDisplayName;
    // widgets (visa / endorsement add form)
    private EditBox visaCountry;
    private EditBox visaEntries;
    private EditBox visaCode;
    private EditBox visaIssue;
    private EditBox visaExpiry;
    private EditBox visaLabel;
    private EditBox visaAuthority;
    private VisaType visaType = VisaType.TOURIST;
    private BetterButton recordToggleButton;
    private BetterButton recordDeleteButton;

    private EditBox endorseEntries;
    private EditBox endorseCode;
    private EditBox endorseIssue;
    private EditBox endorseExpiry;
    private EditBox endorseAuthority;
    private EndorsementType endorseType = EndorsementType.ONE_YEAR_MULTI;

    private BetterButton stampDeleteButton;

    public BorderTerminalScreen(CompoundTag payload) {
        super(Component.translatable("screen.veriborder.terminal.title"), 640, 470);
        this.data = payload == null ? new CompoundTag() : payload;
        this.accent = Ui.CYAN;
        this.admin = data.getBoolean("admin");
        this.selectedUuid = data.getString("selected");
        this.selectedName = data.getString("targetName");
        this.players = parsePlayers(data);
        this.docs = parseDocs(data);
        this.logs = parseLogs(data);
        ListTag docTags = data.getList("docs", Tag.TAG_COMPOUND);
        for (int i = 0; i < docTags.size(); i++) {
            CompoundTag entry = docTags.getCompound(i);
            docEntries.put(entry.getInt("slot"), entry.copy());
        }
        rightTabs.add("docs", Component.translatable("screen.veriborder.terminal.tab.docs"));
        rightTabs.add("logs", Component.translatable("screen.veriborder.terminal.tab.logs"));
        rightTabs.setSelected(docsTab);
        if (selectedSlot < 0 && !docs.isEmpty()) {
            selectedSlot = memoryUuid.equals(selectedUuid) && hasSlot(docs, memorySlot)
                    ? memorySlot : docs.get(0).slot();
            memoryUuid = selectedUuid;
            memorySlot = selectedSlot;
        }
    }

    @Override
    protected void build() {
        switch (page) {
            case MAIN -> buildMain();
            case DOC -> buildDocEditor();
            case VISA -> buildVisaPage();
            case ENDORSEMENT -> buildEndorsementPage();
            case STAMP -> buildStampPage();
        }
    }

    // ------------------------------------------------------------------ main

    private void buildMain() {
        playerSearch = addField(LEFT_X, 56, LEFT_W, "screen.veriborder.terminal.search_players",
                playerFilter, "hint.veriborder.player_filter");
        playerSearch.setMaxLength(32);
        playerSearch.setResponder(value -> playerFilter = value);
        docSearch = addField(RIGHT_X, 56, 210, "screen.veriborder.terminal.search_docs",
                docFilter, "hint.veriborder.number_filter");
        docSearch.setMaxLength(32);
        docSearch.setResponder(value -> docFilter = value);
        docSearch.setEditable(docsTab == 0);

        // 26.3: the documents/logs switch is a shared TabStrip (drawn in
        // drawRightPanel); only its selection has to be mirrored here.
        rightTabs.setSelected(docsTab);

        if (admin) {
            editButton = addRenderableWidget(new BetterButton(LEFT_X, ACTION_Y1, 84, 20,
                    Component.translatable("screen.veriborder.terminal.edit"), button -> openPage(Page.DOC),
                    Ui.CYAN, true));
            visaButton = addRenderableWidget(new BetterButton(LEFT_X + 90, ACTION_Y1, 84, 20,
                    Component.translatable("screen.veriborder.terminal.visas"), button -> openPage(Page.VISA),
                    Ui.PURPLE, true));
            endorseButton = addRenderableWidget(new BetterButton(LEFT_X + 180, ACTION_Y1, 96, 20,
                    Component.translatable("screen.veriborder.terminal.endorsements"),
                    button -> openPage(Page.ENDORSEMENT), Ui.PURPLE, true));
            stampsButton = addRenderableWidget(new BetterButton(LEFT_X + 282, ACTION_Y1, 84, 20,
                    Component.translatable("screen.veriborder.terminal.stamps"), button -> openPage(Page.STAMP),
                    Ui.GOLD, true));
            resetButton = addRenderableWidget(new BetterButton(LEFT_X + 372, ACTION_Y1, 96, 20,
                    Component.translatable("screen.veriborder.terminal.reset"), button -> resetDocument(),
                    Ui.GREEN, true));
            viewButton = addRenderableWidget(new BetterButton(LEFT_X + 474, ACTION_Y1, 76, 20,
                    Component.translatable("screen.veriborder.terminal.view"), button -> openDocView(),
                    Ui.CYAN, true));

            // 26.4: document status is a dropdown (4 values) instead of a cycle.
            List<Dropdown.Option> statuses = new ArrayList<>();
            for (DocumentStatus value : DocumentStatus.values()) {
                statuses.add(new Dropdown.Option(value.id(),
                        Component.translatable(value.translationKey()).getString()));
            }
            statusDropdown = addDropdown(Dropdown.at(LEFT_X, ACTION_Y2, 120)
                    .height(20)
                    .prefix(Component.translatable("field.veriborder.status").getString() + ": ")
                    .options(statuses)
                    .selected("")
                    .enabled(false)
                    .tooltip("field.veriborder.status")
                    .onChange(this::setStatus));
            forceEntryButton = addRenderableWidget(new BetterButton(LEFT_X + 126, ACTION_Y2, 112, 20,
                    Component.translatable("screen.veriborder.terminal.force_entry"), button -> forceEntry(),
                    Ui.GREEN, true));
            addRenderableWidget(new BetterButton(LEFT_X + 244, ACTION_Y2, 112, 20,
                    Component.translatable("screen.veriborder.terminal.force_exit"), button -> forceExit(),
                    Ui.RED, true));

            codeInput = addField(LEFT_X, 418, 150, "screen.veriborder.terminal.code", "",
                    "hint.veriborder.code_8");
            codeInput.setMaxLength(8);
            codeInput.setFilter(ScreenCodes::digitsUpTo8);
            addRenderableWidget(new BetterButton(LEFT_X + 156, ACTION_Y3, 70, 20,
                    Component.translatable("button.veriborder.add"), button -> addCode(), Ui.GREEN, true));
            deleteCodeButton = addRenderableWidget(new BetterButton(LEFT_X + 232, ACTION_Y3, 90, 20,
                    Component.translatable("screen.veriborder.terminal.delete_code"), button -> deleteCode(),
                    Ui.AMBER, true));
            addRenderableWidget(new BetterButton(LEFT_X + 328, ACTION_Y3, 80, 20,
                    Component.translatable("button.veriborder.clear_all"), button -> clearCodes(), Ui.RED, true));
            forcePort = addField(LEFT_X + 416, 418, 196, "screen.veriborder.terminal.force_port",
                    sessionPort(), "hint.veriborder.port_override");
            forcePort.setMaxLength(48);
        }

        addRenderableWidget(new BetterButton(LEFT_X + (admin ? 362 : 0), ACTION_Y2 + (admin ? 0 : 0),
                90, 20, Component.translatable("button.veriborder.refresh"), button -> refresh(), Ui.CYAN, true));
        addRenderableWidget(new BetterButton(LEFT_X + (admin ? 458 : 96), ACTION_Y2, 100, 20,
                Component.translatable("button.veriborder.close"), button -> onClose(), Ui.MUTED, true));
        if (!admin) {
            // Read-only staff can still open the full paged document viewer.
            viewButton = addRenderableWidget(new BetterButton(LEFT_X + 202, ACTION_Y2, 108, 20,
                    Component.translatable("screen.veriborder.terminal.view"), button -> openDocView(),
                    Ui.CYAN, true));
        }
        updateButtons();
    }

    private void updateButtons() {
        DocRow doc = selectedDoc();
        if (editButton != null) {
            editButton.active = doc != null;
        }
        if (visaButton != null) {
            visaButton.active = doc != null && doc.kind() == DocumentKind.PASSPORT;
        }
        if (endorseButton != null) {
            endorseButton.active = doc != null && doc.kind() == DocumentKind.PERMIT;
        }
        if (stampsButton != null) {
            stampsButton.active = doc != null;
        }
        if (resetButton != null) {
            resetButton.active = doc != null;
        }
        if (viewButton != null) {
            viewButton.active = doc != null && docEntries.containsKey(doc.slot());
        }
        if (statusDropdown != null) {
            statusDropdown.enabled(doc != null);
            statusDropdown.selected(doc == null ? "" : doc.status().id());
        }
        if (forceEntryButton != null) {
            forceEntryButton.active = doc != null;
        }
        if (deleteCodeButton != null) {
            deleteCodeButton.active = doc != null && selectedCode >= 0
                    && selectedCode < doc.codes().size();
        }
        if (recordToggleButton != null) {
            recordToggleButton.active = selectedRecord >= 0;
        }
        if (recordDeleteButton != null) {
            recordDeleteButton.active = selectedRecord >= 0;
        }
        if (stampDeleteButton != null) {
            stampDeleteButton.active = selectedStamp >= 0;
        }
    }

    // ------------------------------------------------------------ main render

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.hoverX = (int) toUiX(mouseX);
        this.hoverY = (int) toUiY(mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        Component[] tooltip = tooltipFor(hoverX, hoverY);
        if (tooltip.length > 0) {
            Ui.tooltip(graphics, mouseX, mouseY, tooltip);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        if (page != Page.MAIN) {
            renderPage(graphics);
            return;
        }
        header(graphics, Component.translatable("screen.veriborder.terminal.title").getString(),
                Component.translatable("screen.veriborder.terminal.subtitle", players.size(),
                        selectedName.isBlank()
                                ? Component.translatable("screen.veriborder.terminal.none").getString()
                                : selectedName).getString());
        drawPlayers(graphics);
        drawRightPanel(graphics);
        drawActionStrip(graphics);
    }

    private void drawPlayers(GuiGraphics graphics) {
        int x = LEFT_X;
        int y = PANEL_Y;
        Ui.inset(graphics, x, y, LEFT_W, PANEL_H);
        List<PlayerRow> rows = visiblePlayers();
        playerList.setItems(rows);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.players", rows.size()),
                x + 8, y + 6, LEFT_W - 16, Ui.CYAN);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.players.hint"),
                x + 8, y + 18, LEFT_W - 16, Ui.DIM);
        int rowTop = y + PLAYER_ROWS_TOP;
        if (rows.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.veriborder.terminal.players.empty"),
                    x + LEFT_W / 2, rowTop + 26, Ui.MUTED);
            return;
        }
        playerList.draw(graphics, font, (g, row, index, rowX, rowY, width, height, selected, hovered) ->
                drawPlayerRow(g, row, rowX, rowY, selected, hovered), hoverX, hoverY);
    }

    private void drawPlayerRow(GuiGraphics graphics, PlayerRow row, int x, int rowY, boolean active,
                               boolean hovered) {
        int stateColor = row.session() ? Ui.GREEN : Ui.AMBER;
        graphics.fill(x + 1, rowY, x + LEFT_W - 1, rowY + PLAYER_ROW_H, Ui.rowColor(hovered, active));
        graphics.fill(x + 1, rowY, x + 3, rowY + PLAYER_ROW_H, stateColor);
        graphics.fill(x + 6, rowY + PLAYER_ROW_H - 1, x + LEFT_W - 6, rowY + PLAYER_ROW_H, 0x332A3B50);

        String state = Component.translatable(row.session()
                ? "screen.veriborder.terminal.in" : "screen.veriborder.terminal.out").getString();
        int stateWidth = font.width(state) + 8;
        int stateX = x + LEFT_W - 8 - stateWidth;
        graphics.fill(stateX, rowY + 4, stateX + stateWidth, rowY + 14,
                (stateColor & 0x00FFFFFF) | 0x33000000);
        graphics.renderOutline(stateX, rowY + 4, stateWidth, 10, stateColor);
        graphics.drawString(font, state, stateX + 4, rowY + 5, stateColor, false);
        Ui.clipped(graphics, traveller(row.name(), row.uuid()), x + 8, rowY + 4,
                Math.max(20, stateX - x - 12), active ? Ui.TEXT : (row.session() ? Ui.TEXT : Ui.MUTED));
        Ui.clipped(graphics, row.session() ? dash(row.document()) : dash(""), x + 8, rowY + 14,
                LEFT_W - 16, Ui.DIM);
        if (row.overstay()) {
            graphics.fill(x + LEFT_W - 8, rowY + 16, x + LEFT_W - 4, rowY + 20, Ui.RED);
        }
    }

    private void drawRightPanel(GuiGraphics graphics) {
        int x = RIGHT_X;
        int y = PANEL_Y;
        Ui.inset(graphics, x, y, RIGHT_W, PANEL_H);
        // 26.3 shared tab strip: documents / logs.
        rightTabs.setSelected(docsTab);
        rightTabs.draw(graphics, font, docsTab == 0 ? Ui.CYAN : Ui.GOLD);
        if (docsTab == 0) {
            drawDocs(graphics, x, y);
        } else {
            drawLogs(graphics, x, y);
        }
    }

    private void drawDocs(GuiGraphics graphics, int x, int y) {
        List<DocRow> rows = visibleDocs();
        docList.setItems(rows);
        int labelWidth = Math.max(80, RIGHT_W - 244);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.docs", rows.size()),
                x + 8, y + 6, labelWidth, Ui.GOLD);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.docs.hint"),
                x + 8, y + 18, labelWidth, Ui.DIM);
        int rowTop = y + DOC_ROWS_TOP;
        if (selectedUuid.isBlank()) {
            graphics.drawCenteredString(font, Component.translatable("screen.veriborder.terminal.no_selection"),
                    x + RIGHT_W / 2, rowTop + 40, Ui.MUTED);
            return;
        }
        if (rows.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.veriborder.terminal.docs.empty"),
                    x + RIGHT_W / 2, rowTop + 40, Ui.MUTED);
            return;
        }
        docList.draw(graphics, font, (g, doc, index, rowX, rowY, width, height, selected, hovered) ->
                drawDocCard(g, doc, rowX, rowY, selected, hovered), hoverX, hoverY);
    }

    private void drawDocCard(GuiGraphics graphics, DocRow doc, int x, int cardY, boolean active,
                             boolean hovered) {
        int statusColor = doc.status().color();
        graphics.fill(x + 1, cardY, x + RIGHT_W - 1, cardY + DOC_CARD_H - 2,
                active ? 0xFF1B2C3D : hovered ? 0xFF172636 : 0x99101A24);
        graphics.fill(x + 1, cardY, x + 4, cardY + DOC_CARD_H - 2, statusColor);
        graphics.renderOutline(x + 1, cardY, RIGHT_W - 2, DOC_CARD_H - 2,
                active ? Ui.GOLD : 0x332A3B50);

        int inner = x + 9;
        int line = cardY + 5;
        Ui.clipped(graphics, Component.literal(kindText(doc.kind()) + " · " + docTitle(doc)),
                inner, line, 260, Ui.TEXT);
        if (doc.custom()) {
            Ui.badge(graphics, inner + 266, line - 1, Component.translatable("document.veriborder.custom"), Ui.CYAN);
        }

        // Status badge, right aligned.
        Component status = Component.translatable(doc.status().translationKey());
        int statusW = font.width(status) + 10;
        int statusX = x + RIGHT_W - 10 - statusW;
        graphics.fill(statusX, line - 1, statusX + statusW, line + 11,
                (statusColor & 0x00FFFFFF) | 0x33000000);
        graphics.renderOutline(statusX, line - 1, statusW, 12, statusColor);
        graphics.drawString(font, status, statusX + 5, line, statusColor, false);

        line = cardY + 17;
        Ui.clipped(graphics, dash(doc.name()), inner, line, 220, Ui.TEXT);
        Ui.clipped(graphics, dash(doc.number()), inner + 236, line, RIGHT_W - 250, Ui.GOLD);
        line = cardY + 28;
        String expiry = doc.permanent()
                ? Component.translatable("date.veriborder.permanent").getString()
                : dash(doc.expiry());
        Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.expiry", expiry),
                inner, line, 190, Ui.MUTED);
        Ui.clipped(graphics, Component.translatable("document.veriborder.integrity",
                        Component.translatable(doc.integrity()
                                ? "document.veriborder.integrity.ok"
                                : "document.veriborder.integrity.bad")),
                inner + 210, line, RIGHT_W - 224, doc.integrity() ? Ui.GREEN : Ui.RED);

        line = cardY + 40;
        drawCodeChips(graphics, doc, inner, line);

        line = cardY + 51;
        Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.records",
                        doc.visas().size(), doc.endorsements().size()),
                inner, line, RIGHT_W - 20, Ui.DIM);
        line = cardY + 61;
        String mrz = doc.kind() == DocumentKind.PASSPORT && !doc.mrz1().isBlank()
                ? doc.mrz1() : dash(doc.authority());
        Ui.clipped(graphics, mrz, inner, line, RIGHT_W - 20, Ui.DIM);
    }

    private void drawLogs(GuiGraphics graphics, int x, int y) {
        logList.setItems(logs);
        int labelWidth = Math.max(80, RIGHT_W - 244);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.logs", logs.size()),
                x + 8, y + 6, labelWidth, Ui.GOLD);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.logs.hint"),
                x + 8, y + 18, labelWidth, Ui.DIM);
        int rowTop = y + DOC_ROWS_TOP;
        if (logs.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.veriborder.terminal.logs.empty"),
                    x + RIGHT_W / 2, rowTop + 40, Ui.MUTED);
            return;
        }
        logList.draw(graphics, font, (g, row, index, rowX, rowY, width, height, selected, hovered) ->
                drawLogRow(g, row, rowX, rowY), hoverX, hoverY);
    }

    private void drawLogRow(GuiGraphics graphics, LogRow row, int x, int rowY) {
        int color = resultColor(row.result());
        graphics.fill(x + 1, rowY, x + RIGHT_W - 1, rowY + LOG_ROW_H - 2, 0x99101A24);
        graphics.fill(x + 1, rowY, x + 3, rowY + LOG_ROW_H - 2, color);
        graphics.fill(x + 6, rowY + LOG_ROW_H - 3, x + RIGHT_W - 6, rowY + LOG_ROW_H - 2, 0x332A3B50);
        int inner = x + 9;
        Ui.clipped(graphics, formatTime(row.time()), inner, rowY + 3, 96, Ui.DIM);
        Ui.clipped(graphics, translatedOr("screen.veriborder.terminal.log." + lower(row.result()),
                row.result()), inner + 100, rowY + 3, 100, color);
        Ui.clipped(graphics, translatedOr("screen.veriborder.officer.direction." + lower(row.direction()),
                row.direction()), inner + 204, rowY + 3, 60, directionColor(row.direction()));
        Ui.clipped(graphics, dash(row.document()), inner + 268, rowY + 3, 90, Ui.MUTED);
        Ui.clipped(graphics, text(row.detail()), inner + 362, rowY + 3, RIGHT_W - 376, Ui.DIM);
        Ui.clipped(graphics, dash(shortDimension(row.gate())), inner, rowY + 13, 300, Ui.DIM);
        Ui.clipped(graphics, translatedOr("screen.veriborder.officer.kind." + lower(row.kind()),
                row.kind()), inner + 306, rowY + 13, 100, Ui.MUTED);
    }

    private void drawCodeChips(GuiGraphics graphics, DocRow doc, int x, int y) {
        if (doc.codes().isEmpty()) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.codes_empty"),
                    x, y, 140, Ui.DIM);
            return;
        }
        int chipX = x;
        int limit = x + RIGHT_W - 24;
        int shown = 0;
        for (int i = 0; i < doc.codes().size(); i++) {
            String code = doc.codes().get(i);
            int width = font.width(code) + 9;
            if (chipX + width > limit) {
                break;
            }
            boolean active = doc.slot() == selectedSlot && i == selectedCode;
            int color = active ? Ui.GOLD : Ui.MUTED;
            graphics.fill(chipX, y - 1, chipX + width, y + 9, active ? 0x552F4A62 : 0x33101A24);
            graphics.renderOutline(chipX, y - 1, width, 10, active ? Ui.GOLD : 0x332A3B50);
            graphics.drawString(font, code, chipX + 4, y, color, false);
            chipX += width + 4;
            shown++;
        }
        if (shown < doc.codes().size()) {
            graphics.drawString(font, "+" + (doc.codes().size() - shown), chipX, y, Ui.DIM, false);
        }
    }

    private void drawActionStrip(GuiGraphics graphics) {
        Ui.divider(graphics, LEFT_X, PANEL_Y + PANEL_H + 2, 612);
        if (!admin) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.read_only"),
                    LEFT_X, ACTION_Y1, 612, Ui.AMBER);
            Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.read_only_hint"),
                    LEFT_X, ACTION_Y1 + 12, 612, Ui.DIM);
            return;
        }
        if (!editorError.isBlank()) {
            Ui.clipped(graphics, Component.translatable(editorError), LEFT_X, 440, 612, Ui.RED);
        }
    }

    // ------------------------------------------------------- document editor

    private void buildDocEditor() {
        DocRow doc = selectedDoc();
        if (doc == null) {
            page = Page.MAIN;
            rebuildWidgets();
            return;
        }
        sexValue = doc.sex();
        int x1 = 24;
        int x2 = 340;
        int w = 280;
        docName = addField(x1, 82, w, "field.veriborder.name", doc.name());
        docNameLatin = addField(x2, 82, w, "field.veriborder.name_latin", doc.nameLatin());
        docNumber = addField(x1, 124, w, "field.veriborder.number", doc.number());
        docBirth = addField(x2, 124, w, "field.veriborder.birth", doc.birthDate(), "YYYY-MM-DD");
        // 26.4: sex is a dropdown (M/F/X) instead of a cycle button.
        List<Dropdown.Option> sexes = new ArrayList<>();
        for (String value : new String[]{"M", "F", "X"}) {
            sexes.add(new Dropdown.Option(value,
                    Component.translatable("sex.veriborder." + sexKey(value)).getString()));
        }
        addDropdown(Dropdown.at(x1, 170, w)
                .height(16)
                .prefix(Component.translatable("field.veriborder.sex").getString() + ": ")
                .options(sexes)
                .selected(sexValue)
                .onChange(id -> sexValue = id));
        docNationality = addField(x2, 166, w, "field.veriborder.nationality", doc.nationality());
        docAuthority = addField(x1, 208, w, "field.veriborder.authority", doc.authority());
        docPlace = addField(x2, 208, w, "field.veriborder.place", doc.place());
        docIssue = addField(x1, 250, w, "field.veriborder.issue", doc.issueDate(), "YYYY-MM-DD");
        docExpiry = addField(x2, 250, w, "field.veriborder.expiry", doc.expiry(), "YYYY-MM-DD");
        docMrz = addField(x1, 292, w, "field.veriborder.mrz_country", doc.mrzCountry(), "hint.veriborder.mrz_country");
        docMrz.setMaxLength(3);
        docMrz.setEditable(doc.kind() == DocumentKind.PASSPORT);
        docDisplayName = addField(x2, 292, w, "field.veriborder.display_name", doc.displayName());
        docDisplayName.setEditable(!doc.nameLocked());

        addRenderableWidget(new BetterButton(24, 396, 120, 20,
                Component.translatable("button.veriborder.save"), button -> saveDocument(), Ui.GREEN));
        addRenderableWidget(new BetterButton(150, 396, 100, 20,
                Component.translatable("button.veriborder.cancel"), button -> backToMain(), Ui.MUTED, true));
        addRenderableWidget(new BetterButton(LEFT_X + imageWidth - 114, 396, 100, 20,
                Component.translatable("button.veriborder.refresh"), button -> refresh(), Ui.CYAN, true));
    }

    private void renderDocEditor(GuiGraphics graphics) {
        DocRow doc = selectedDoc();
        header(graphics, Component.translatable("screen.veriborder.terminal.edit").getString(),
                doc == null ? Component.translatable("screen.veriborder.terminal.none").getString()
                        : doc.name() + " · " + doc.number());
        Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.edit_hint"),
                24, 58, 596, Ui.DIM);
        Ui.clipped(graphics, Component.translatable("field.veriborder.sex"), 24, 158, 280, Ui.DIM);
        if (doc != null && doc.nameLocked()) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.name_locked_hint"),
                    340, 316, 280, Ui.AMBER);
        } else {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.name_hint"),
                    340, 316, 280, Ui.DIM);
        }
        Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.edit_footer"),
                24, 372, 596, Ui.DIM);
        if (!editorError.isBlank()) {
            Ui.clipped(graphics, Component.translatable(editorError), 24, 346, 596, Ui.RED);
        }
    }

    /** Sends only the fields that actually changed. */
    private void saveDocument() {
        DocRow doc = selectedDoc();
        if (doc == null) {
            backToMain();
            return;
        }
        String name = docName.getValue().trim();
        String nameLatin = docNameLatin.getValue().trim();
        String number = docNumber.getValue().trim();
        String birth = docBirth.getValue().trim();
        String nationality = docNationality.getValue().trim();
        String authority = docAuthority.getValue().trim();
        String place = docPlace.getValue().trim();
        String issue = docIssue.getValue().trim();
        String expiry = docExpiry.getValue().trim();
        String mrz = docMrz.getValue().trim().toUpperCase(Locale.ROOT);
        String displayName = docDisplayName.getValue().trim();
        if (name.isEmpty()) {
            editorError = "message.veriborder.terminal.name_required";
            return;
        }
        if (number.isEmpty()) {
            editorError = "message.veriborder.terminal.number_required";
            return;
        }
        if (!DateUtil.isValid(birth) || !DateUtil.isValid(issue)
                || (!DateUtil.isValid(expiry) && !DateUtil.isPermanent(expiry))) {
            editorError = "message.veriborder.terminal.date_invalid";
            return;
        }
        if (doc.kind() == DocumentKind.PASSPORT && !mrz.isEmpty()
                && MrzUtil.countryCode(mrz).isEmpty()) {
            editorError = "message.veriborder.terminal.mrz_invalid";
            return;
        }
        CompoundTag payload = new CompoundTag();
        boolean changed = false;
        if (!name.equals(doc.name())) {
            payload.putString("name", name);
            changed = true;
        }
        if (!nameLatin.equals(doc.nameLatin())) {
            payload.putString("nameLatin", nameLatin);
            changed = true;
        }
        if (!number.equals(doc.number())) {
            payload.putString("number", number);
            changed = true;
        }
        if (!birth.equals(doc.birthDate())) {
            payload.putString("birthDate", birth);
            changed = true;
        }
        if (!sexValue.equalsIgnoreCase(doc.sex())) {
            payload.putString("sex", sexValue);
            changed = true;
        }
        if (!nationality.equals(doc.nationality())) {
            payload.putString("nationality", nationality);
            changed = true;
        }
        if (!authority.equals(doc.authority())) {
            payload.putString("authority", authority);
            changed = true;
        }
        if (!place.equals(doc.place())) {
            payload.putString("place", place);
            changed = true;
        }
        if (!issue.equals(doc.issueDate())) {
            payload.putString("issueDate", issue);
            changed = true;
        }
        if (!expiry.equals(doc.expiry())) {
            payload.putString("expiryDate", expiry);
            changed = true;
        }
        if (doc.kind() == DocumentKind.PASSPORT && !mrz.equalsIgnoreCase(doc.mrzCountry())) {
            payload.putString("mrzCountry", mrz);
            changed = true;
        }
        if (!doc.nameLocked() && !displayName.equals(doc.displayName().trim())) {
            payload.putString("displayName", displayName);
            changed = true;
        }
        if (!changed) {
            editorError = "";
            backToMain();
            return;
        }
        editorError = "";
        send(BorderTerminalService.ACTION_EDIT_DOC, selectedSlot, payload);
    }

    // ------------------------------------------------------------ visa page

    private void buildVisaPage() {
        DocRow doc = selectedDoc();
        if (doc == null || doc.kind() != DocumentKind.PASSPORT) {
            page = Page.MAIN;
            rebuildWidgets();
            return;
        }
        visaCountry = addField(24, 258, 220, "field.veriborder.visa_country", "", "hint.veriborder.visa_country");
        // 26.4: visa type is a dropdown (6 options) instead of a cycle button.
        List<Dropdown.Option> visaTypes = new ArrayList<>();
        for (VisaType value : VisaType.values()) {
            visaTypes.add(new Dropdown.Option(value.id(),
                    Component.translatable(value.translationKey()).getString()));
        }
        addDropdown(Dropdown.at(260, 262, 140)
                .height(16)
                .options(visaTypes)
                .selected(visaType.id())
                .tooltip("field.veriborder.visa_type")
                .onChange(id -> visaType = VisaType.byId(id)));
        visaEntries = addField(414, 258, 80, "field.veriborder.entries",
                String.valueOf(visaType.defaultEntries()), "hint.veriborder.unlimited");
        visaEntries.setMaxLength(3);
        visaEntries.setFilter(value -> value.isEmpty() || value.chars().allMatch(c -> c >= '0' && c <= '9'));
        visaCode = addField(508, 258, 110, "field.veriborder.visa_code", "", "hint.veriborder.code");

        visaIssue = addField(24, 300, 140, "field.veriborder.issue", DateUtil.today(), "YYYY-MM-DD");
        visaExpiry = addField(176, 300, 140, "field.veriborder.expiry", DateUtil.plusYears(DateUtil.today(), 1),
                "YYYY-MM-DD");
        visaLabel = addField(330, 300, 140, "field.veriborder.visa_name", "", "hint.veriborder.optional");
        visaAuthority = addField(482, 300, 140, "field.veriborder.authority", doc.authority());

        addRenderableWidget(new BetterButton(24, 396, 110, 20,
                Component.translatable("screen.veriborder.terminal.visa_add"), button -> addVisa(), Ui.GREEN));
        recordToggleButton = addRenderableWidget(new BetterButton(140, 396, 110, 20,
                Component.translatable("screen.veriborder.terminal.toggle_record"), button -> recordOperation(
                        BorderTerminalService.ACTION_VISA, "toggle"), Ui.AMBER, true));
        recordDeleteButton = addRenderableWidget(new BetterButton(256, 396, 110, 20,
                Component.translatable("button.veriborder.delete"), button -> recordOperation(
                        BorderTerminalService.ACTION_VISA, "remove"), Ui.RED, true));
        addRenderableWidget(new BetterButton(LEFT_X + imageWidth - 114, 396, 100, 20,
                Component.translatable("button.veriborder.back"), button -> backToMain(), Ui.CYAN, true));
        updateButtons();
    }

    private void renderVisaPage(GuiGraphics graphics) {
        DocRow doc = selectedDoc();
        header(graphics, Component.translatable("screen.veriborder.terminal.visas").getString(),
                doc == null ? "" : doc.name() + " · " + doc.number());
        int x = LEFT_X;
        int y = 64;
        Ui.inset(graphics, x, y, 612, 160);
        List<VisaRow> visas = doc == null ? List.of() : doc.visas();
        Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.visas_count", visas.size()),
                x + 8, y + 6, 560, Ui.PURPLE);
        int rowTop = y + 26;
        visaList.setItems(visas);
        if (visas.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.veriborder.terminal.visas_empty"),
                    x + 10, rowTop + 12, Ui.MUTED, false);
        } else {
            visaList.draw(graphics, font, (g, visa, index, rowX, rowY, width, height, selected, hovered) ->
                    drawVisaRow(g, visa, index, rowX, rowY), hoverX, hoverY);
        }
        Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.visa_form_hint"),
                24, 238, 600, Ui.DIM);
        if (!editorError.isBlank()) {
            Ui.clipped(graphics, Component.translatable(editorError), 24, 374, 600, Ui.RED);
        }
    }

    private void drawVisaRow(GuiGraphics graphics, VisaRow visa, int index, int x, int rowY) {
        boolean active = index == selectedRecord;
        // 26.3 round 12: colour + label are the derived state, not the raw flag.
        int color = visa.state().color();
        graphics.fill(x + 1, rowY, x + 611, rowY + 28, active ? 0xFF1B2C3D : 0x88101A24);
        graphics.fill(x + 1, rowY, x + 3, rowY + 28, color);
        Ui.clipped(graphics, Component.translatable(visa.type().translationKey()), x + 8, rowY + 4, 100, Ui.TEXT);
        Ui.clipped(graphics, dash(visa.country()), x + 112, rowY + 4, 130, Ui.TEXT);
        Ui.clipped(graphics, dash(visa.code()), x + 246, rowY + 4, 70, Ui.GOLD);
        Ui.clipped(graphics, dash(visa.entries()), x + 320, rowY + 4, 54, Ui.MUTED);
        Ui.clipped(graphics, dash(visa.expiry()), x + 378, rowY + 4, 90, Ui.MUTED);
        Ui.clipped(graphics, Component.translatable(visa.state().translationKey()), x + 472, rowY + 4, 70,
                color);
        Ui.clipped(graphics, visa.active() ? Component.translatable("screen.veriborder.terminal.active")
                : Component.translatable("screen.veriborder.terminal.inactive"), x + 546, rowY + 4, 60, color);
        Ui.clipped(graphics, visa.label().isBlank() ? Component.translatable("screen.veriborder.terminal.visa_issue",
                        visa.issueDate()) : Component.literal(visa.label() + " · " + visa.issueDate()),
                x + 8, rowY + 15, 596, Ui.DIM);
    }

    private void addVisa() {
        String country = visaCountry.getValue().trim();
        if (country.isEmpty()) {
            editorError = "message.veriborder.terminal.visa_country_required";
            return;
        }
        String issue = visaIssue.getValue().trim();
        String expiry = visaExpiry.getValue().trim();
        if (!DateUtil.isValid(issue) || !DateUtil.isValid(expiry)) {
            editorError = "message.veriborder.terminal.date_invalid";
            return;
        }
        CompoundTag payload = new CompoundTag();
        payload.putString("country", country);
        payload.putString("type", visaType.id());
        payload.putInt("entries", parseInt(visaEntries.getValue(), 1));
        payload.putString("issueDate", issue);
        payload.putString("expiryDate", expiry);
        payload.putString("authority", visaAuthority.getValue().trim());
        payload.putString("code", visaCode.getValue().trim());
        payload.putString("label", visaLabel.getValue().trim());
        editorError = "";
        send(BorderTerminalService.ACTION_ADD_VISA, selectedSlot, payload);
    }

    // ----------------------------------------------------- endorsement page

    private void buildEndorsementPage() {
        DocRow doc = selectedDoc();
        if (doc == null || doc.kind() != DocumentKind.PERMIT) {
            page = Page.MAIN;
            rebuildWidgets();
            return;
        }
        // 26.4: endorsement type is a dropdown (3 options) instead of a cycle.
        List<Dropdown.Option> endorsementTypes = new ArrayList<>();
        for (EndorsementType value : EndorsementType.values()) {
            endorsementTypes.add(new Dropdown.Option(value.id(),
                    Component.translatable(value.translationKey()).getString()));
        }
        addDropdown(Dropdown.at(24, 262, 220)
                .height(16)
                .options(endorsementTypes)
                .selected(endorseType.id())
                .tooltip("field.veriborder.endorsement_type")
                .onChange(id -> endorseType = EndorsementType.byId(id)));
        endorseEntries = addField(260, 258, 90, "field.veriborder.entries",
                String.valueOf(endorseType.defaultEntries()), "hint.veriborder.unlimited");
        endorseEntries.setMaxLength(3);
        endorseEntries.setFilter(value -> value.isEmpty() || value.chars().allMatch(c -> c >= '0' && c <= '9'));
        endorseCode = addField(360, 258, 120, "field.veriborder.code", "", "hint.veriborder.code");
        endorseAuthority = addField(490, 258, 130, "field.veriborder.authority", doc.authority());

        endorseIssue = addField(24, 300, 150, "field.veriborder.issue", DateUtil.today(), "YYYY-MM-DD");
        endorseExpiry = addField(184, 300, 150, "field.veriborder.expiry",
                DateUtil.plusYears(DateUtil.today(), 1), "YYYY-MM-DD");

        addRenderableWidget(new BetterButton(24, 396, 110, 20,
                Component.translatable("screen.veriborder.terminal.endorsement_add"), button -> addEndorsement(),
                Ui.GREEN));
        recordToggleButton = addRenderableWidget(new BetterButton(140, 396, 110, 20,
                Component.translatable("screen.veriborder.terminal.toggle_record"), button -> recordOperation(
                        BorderTerminalService.ACTION_ENDORSEMENT, "toggle"), Ui.AMBER, true));
        recordDeleteButton = addRenderableWidget(new BetterButton(256, 396, 110, 20,
                Component.translatable("button.veriborder.delete"), button -> recordOperation(
                        BorderTerminalService.ACTION_ENDORSEMENT, "remove"), Ui.RED, true));
        addRenderableWidget(new BetterButton(LEFT_X + imageWidth - 114, 396, 100, 20,
                Component.translatable("button.veriborder.back"), button -> backToMain(), Ui.CYAN, true));
        updateButtons();
    }

    private void renderEndorsementPage(GuiGraphics graphics) {
        DocRow doc = selectedDoc();
        header(graphics, Component.translatable("screen.veriborder.terminal.endorsements").getString(),
                doc == null ? "" : doc.name() + " · " + doc.number());
        int x = LEFT_X;
        int y = 64;
        Ui.inset(graphics, x, y, 612, 160);
        List<EndorseRow> rows = doc == null ? List.of() : doc.endorsements();
        Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.endorsements_count", rows.size()),
                x + 8, y + 6, 560, Ui.PURPLE);
        int rowTop = y + 26;
        endorseList.setItems(rows);
        if (rows.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.veriborder.terminal.endorsements_empty"),
                    x + 10, rowTop + 12, Ui.MUTED, false);
        } else {
            endorseList.draw(graphics, font, (g, row, index, rowX, rowY, width, height, selected, hovered) ->
                    drawEndorseRow(g, row, index, rowX, rowY), hoverX, hoverY);
        }
        Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.endorsement_form_hint"),
                24, 238, 600, Ui.DIM);
        if (!editorError.isBlank()) {
            Ui.clipped(graphics, Component.translatable(editorError), 24, 374, 600, Ui.RED);
        }
    }

    private void drawEndorseRow(GuiGraphics graphics, EndorseRow row, int index, int x, int rowY) {
        boolean active = index == selectedRecord;
        int color = row.state().color();
        graphics.fill(x + 1, rowY, x + 611, rowY + 28, active ? 0xFF1B2C3D : 0x88101A24);
        graphics.fill(x + 1, rowY, x + 3, rowY + 28, color);
        Ui.clipped(graphics, Component.translatable(row.type().translationKey()), x + 8, rowY + 4, 160, Ui.TEXT);
        Ui.clipped(graphics, dash(row.code()), x + 174, rowY + 4, 80, Ui.GOLD);
        Ui.clipped(graphics, dash(row.entries()), x + 260, rowY + 4, 60, Ui.MUTED);
        Ui.clipped(graphics, dash(row.expiry()), x + 326, rowY + 4, 100, Ui.MUTED);
        Ui.clipped(graphics, Component.translatable(row.state().translationKey()), x + 432, rowY + 4, 80,
                color);
        Ui.clipped(graphics, row.active() ? Component.translatable("screen.veriborder.terminal.active")
                : Component.translatable("screen.veriborder.terminal.inactive"), x + 518, rowY + 4, 80, color);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.visa_issue", row.issueDate()),
                x + 8, rowY + 15, 596, Ui.DIM);
    }

    private void addEndorsement() {
        String issue = endorseIssue.getValue().trim();
        String expiry = endorseExpiry.getValue().trim();
        if (!DateUtil.isValid(issue) || !DateUtil.isValid(expiry)) {
            editorError = "message.veriborder.terminal.date_invalid";
            return;
        }
        CompoundTag payload = new CompoundTag();
        payload.putString("type", endorseType.id());
        payload.putInt("entries", parseInt(endorseEntries.getValue(), endorseType.defaultEntries()));
        payload.putString("issueDate", issue);
        payload.putString("expiryDate", expiry);
        payload.putString("authority", endorseAuthority.getValue().trim());
        payload.putString("code", endorseCode.getValue().trim());
        editorError = "";
        send(BorderTerminalService.ACTION_ADD_ENDORSEMENT, selectedSlot, payload);
    }

    private void recordOperation(String action, String op) {
        if (selectedRecord < 0) {
            return;
        }
        CompoundTag payload = new CompoundTag();
        payload.putString("op", op);
        payload.putInt("index", selectedRecord);
        send(action, selectedSlot, payload);
    }

    // ------------------------------------------------------------ stamp page

    private void buildStampPage() {
        if (selectedDoc() == null) {
            page = Page.MAIN;
            rebuildWidgets();
            return;
        }
        stampDeleteButton = addRenderableWidget(new BetterButton(24, 396, 110, 20,
                Component.translatable("button.veriborder.delete"), button -> stampOperation("remove"), Ui.RED, true));
        addRenderableWidget(new BetterButton(140, 396, 120, 20,
                Component.translatable("screen.veriborder.terminal.clear_stamps"), button -> stampOperation("clear"),
                Ui.AMBER, true));
        addRenderableWidget(new BetterButton(LEFT_X + imageWidth - 114, 396, 100, 20,
                Component.translatable("button.veriborder.back"), button -> backToMain(), Ui.CYAN, true));
        updateButtons();
    }

    private void renderStampPage(GuiGraphics graphics) {
        DocRow doc = selectedDoc();
        header(graphics, Component.translatable("screen.veriborder.terminal.stamps").getString(),
                doc == null ? "" : doc.name() + " · " + doc.number());
        int x = LEFT_X;
        int y = 64;
        Ui.inset(graphics, x, y, 612, 320);
        List<StampRow> stamps = doc == null ? List.of() : doc.stamps();
        Ui.clipped(graphics, Component.translatable("screen.veriborder.terminal.stamps_count", stamps.size()),
                x + 8, y + 6, 560, Ui.GOLD);
        int rowTop = y + 26;
        stampList.setItems(stamps);
        if (stamps.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.veriborder.terminal.stamps_empty"),
                    x + 10, rowTop + 12, Ui.MUTED, false);
        } else {
            stampList.draw(graphics, font, (g, row, index, rowX, rowY, width, height, selected, hovered) ->
                    drawStampRow(g, row, index, rowX, rowY), hoverX, hoverY);
        }
    }

    private void drawStampRow(GuiGraphics graphics, StampRow row, int index, int x, int rowY) {
        boolean active = index == selectedStamp;
        boolean inbound = "IN".equalsIgnoreCase(row.direction());
        int color = inbound ? Ui.GREEN : Ui.AMBER;
        graphics.fill(x + 1, rowY, x + 611, rowY + 26, active ? 0xFF1B2C3D : 0x88101A24);
        graphics.fill(x + 1, rowY, x + 3, rowY + 26, color);
        Ui.clipped(graphics, translatedOr("stamp.veriborder." + lower(row.direction()), row.direction()),
                x + 8, rowY + 4, 60, color);
        Ui.clipped(graphics, dash(row.date()), x + 74, rowY + 4, 90, Ui.MUTED);
        Ui.clipped(graphics, dash(row.port()), x + 170, rowY + 4, 190, Ui.TEXT);
        Ui.clipped(graphics, dash(row.country()), x + 366, rowY + 4, 120, Ui.MUTED);
        Ui.clipped(graphics, dash(row.officer()), x + 492, rowY + 4, 112, Ui.DIM);
    }

    private void stampOperation(String op) {
        if (!"clear".equals(op) && selectedStamp < 0) {
            return;
        }
        CompoundTag payload = new CompoundTag();
        payload.putString("op", op);
        payload.putInt("index", selectedStamp);
        send(BorderTerminalService.ACTION_STAMP, selectedSlot, payload);
    }

    // -------------------------------------------------------------- actions

    /** 26.4: applies the status chosen in the dropdown to the selected document. */
    private void setStatus(String id) {
        DocRow doc = selectedDoc();
        if (doc == null) {
            return;
        }
        CompoundTag payload = new CompoundTag();
        payload.putString("status", DocumentStatus.byId(id).id());
        send(BorderTerminalService.ACTION_SET_STATUS, selectedSlot, payload);
    }

    private void resetDocument() {
        if (selectedDoc() == null) {
            return;
        }
        send(BorderTerminalService.ACTION_RESET_DOC, selectedSlot, new CompoundTag());
    }

    private void addCode() {
        String code = codeInput.getValue().trim();
        if (!ScreenCodes.isValid(code)) {
            editorError = "message.veriborder.terminal.code_invalid";
            return;
        }
        CompoundTag payload = new CompoundTag();
        payload.putString("addCode", code);
        editorError = "";
        send(BorderTerminalService.ACTION_EDIT_DOC, selectedSlot, payload);
    }

    private void deleteCode() {
        DocRow doc = selectedDoc();
        if (doc == null || selectedCode < 0 || selectedCode >= doc.codes().size()) {
            return;
        }
        CompoundTag payload = new CompoundTag();
        payload.putString("removeCode", doc.codes().get(selectedCode));
        send(BorderTerminalService.ACTION_EDIT_DOC, selectedSlot, payload);
    }

    private void clearCodes() {
        CompoundTag payload = new CompoundTag();
        payload.putBoolean("clearCodes", true);
        send(BorderTerminalService.ACTION_EDIT_DOC, selectedSlot, payload);
    }

    private void forceEntry() {
        if (selectedDoc() == null) {
            return;
        }
        CompoundTag payload = new CompoundTag();
        if (forcePort != null && !forcePort.getValue().isBlank()) {
            payload.putString("port", forcePort.getValue().trim());
        }
        send(BorderTerminalService.ACTION_FORCE_ENTRY, selectedSlot, payload);
    }

    private void forceExit() {
        send(BorderTerminalService.ACTION_FORCE_EXIT, -1, new CompoundTag());
    }

    private void refresh() {
        ModNetwork.CHANNEL.sendToServer(new BorderTerminalActionPacket(
                BorderTerminalService.ACTION_REFRESH, selectedUuid, -1, new CompoundTag()));
    }

    private void selectPlayer(String uuid) {
        selectedUuid = uuid;
        selectedSlot = -1;
        memoryUuid = uuid;
        memorySlot = -1;
        ModNetwork.CHANNEL.sendToServer(new BorderTerminalActionPacket(
                BorderTerminalService.ACTION_SELECT, uuid, -1, new CompoundTag()));
    }

    private void openPage(Page next) {
        page = next;
        selectedRecord = -1;
        selectedStamp = -1;
        visaList.setPage(0);
        endorseList.setPage(0);
        stampList.setPage(0);
        editorError = "";
        rebuildWidgets();
    }

    private void backToMain() {
        page = Page.MAIN;
        editorError = "";
        rebuildWidgets();
    }

    private void send(String action, int slot, CompoundTag payload) {
        ModNetwork.CHANNEL.sendToServer(new BorderTerminalActionPacket(
                action, selectedUuid, slot, payload));
    }

    // ---------------------------------------------------------------- render

    private void renderPage(GuiGraphics graphics) {
        switch (page) {
            case DOC -> renderDocEditor(graphics);
            case VISA -> renderVisaPage(graphics);
            case ENDORSEMENT -> renderEndorsementPage(graphics);
            case STAMP -> renderStampPage(graphics);
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        if (page == Page.MAIN) {
            int tab = rightTabs.hit(uiX, uiY);
            if (tab >= 0) {
                docsTab = tab;
                rightTabs.setSelected(tab);
                if (docSearch != null) {
                    docSearch.setEditable(tab == 0);
                }
                return true;
            }
            int player = playerRowAt(uiX, uiY);
            if (player >= 0) {
                selectPlayer(visiblePlayers().get(player).uuid());
                return true;
            }
            if (docsTab == 0) {
                int docIndex = docCardAt(uiX, uiY);
                if (docIndex >= 0) {
                    DocRow doc = visibleDocs().get(docIndex);
                    int code = codeChipAt(uiX, uiY, doc, docIndex);
                    selectedSlot = doc.slot();
                    selectedCode = code;
                    memoryUuid = selectedUuid;
                    memorySlot = selectedSlot;
                    updateButtons();
                    return true;
                }
            }
            return false;
        }
        if (page == Page.VISA) {
            int row = recordRowAt(uiX, uiY, visibleVisaCount());
            if (row >= 0) {
                selectedRecord = row;
                updateButtons();
                return true;
            }
            return false;
        }
        if (page == Page.ENDORSEMENT) {
            int row = recordRowAt(uiX, uiY, visibleEndorseCount());
            if (row >= 0) {
                selectedRecord = row;
                updateButtons();
                return true;
            }
            return false;
        }
        if (page == Page.STAMP) {
            int row = stampRowAt(uiX, uiY);
            if (row >= 0) {
                selectedStamp = row;
                updateButtons();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        if (dropdownScroll(uiX, uiY, delta)) {
            return true;
        }
        if (page == Page.MAIN) {
            if (playerList.mouseScrolled(uiX, uiY, delta)) {
                return true;
            }
            if (docsTab == 0 ? docList.mouseScrolled(uiX, uiY, delta)
                    : logList.mouseScrolled(uiX, uiY, delta)) {
                return true;
            }
        } else if (page == Page.VISA) {
            if (visaList.mouseScrolled(uiX, uiY, delta)) {
                return true;
            }
        } else if (page == Page.ENDORSEMENT) {
            if (endorseList.mouseScrolled(uiX, uiY, delta)) {
                return true;
            }
        } else if (page == Page.STAMP) {
            if (stampList.mouseScrolled(uiX, uiY, delta)) {
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private int playerRowAt(double uiX, double uiY) {
        int index = playerList.indexAt(uiX, uiY);
        return index >= 0 && index < visiblePlayers().size() ? index : -1;
    }

    private int docCardAt(double uiX, double uiY) {
        int index = docList.indexAt(uiX, uiY);
        return index >= 0 && index < visibleDocs().size() ? index : -1;
    }

    /** Code chip hit box inside a visible document card, or -1. */
    private int codeChipAt(double uiX, double uiY, DocRow doc, int docIndex) {
        int rowTop = docList.rowTop(docIndex);
        if (rowTop < 0) {
            return -1;
        }
        int chipY = rowTop + 40;
        if (uiY < chipY - 2 || uiY >= chipY + 11) {
            return -1;
        }
        int x = docList.x() + 9;
        int limit = RIGHT_X + RIGHT_W - 24;
        for (int i = 0; i < doc.codes().size(); i++) {
            int width = font.width(doc.codes().get(i)) + 9;
            if (x + width > limit) {
                break;
            }
            if (uiX >= x && uiX < x + width) {
                return i;
            }
            x += width + 4;
        }
        return -1;
    }

    private int recordRowAt(double uiX, double uiY, int total) {
        PagedList<?> list = page == Page.VISA ? visaList : endorseList;
        int index = list.indexAt(uiX, uiY);
        return index >= 0 && index < total ? index : -1;
    }

    private int stampRowAt(double uiX, double uiY) {
        int index = stampList.indexAt(uiX, uiY);
        return index >= 0 && index < stampList.size() ? index : -1;
    }

    // --------------------------------------------------------------- tooltip

    private Component[] tooltipFor(int uiX, int uiY) {
        if (page == Page.MAIN) {
            int player = playerRowAt(uiX, uiY);
            if (player >= 0) {
                PlayerRow row = visiblePlayers().get(player);
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal(row.name().isBlank() ? UNKNOWN : row.name()));
                lines.add(Component.literal(row.uuid().isBlank() ? UNKNOWN : row.uuid()));
                lines.add(row.session()
                        ? Component.translatable("screen.veriborder.terminal.tip.inside",
                        dash(row.port()), formatTime(row.entryTime()))
                        : Component.translatable("screen.veriborder.terminal.tip.outside"));
                if (row.session()) {
                    lines.add(Component.translatable("screen.veriborder.terminal.tip.document",
                            dash(row.document())));
                    lines.add(Component.literal(gateText(row)));
                }
                if (row.overstay()) {
                    lines.add(Component.translatable("screen.veriborder.officer.overstay_days",
                            row.overstayDays()));
                }
                return lines.toArray(new Component[0]);
            }
            if (docsTab == 0) {
                int docIndex = docCardAt(uiX, uiY);
                if (docIndex >= 0) {
                    DocRow doc = visibleDocs().get(docIndex);
                    int code = codeChipAt(uiX, uiY, doc, docIndex);
                    if (code >= 0) {
                        return new Component[]{Component.literal(doc.codes().get(code))};
                    }
                    List<Component> lines = new ArrayList<>();
                    lines.add(Component.literal(kindText(doc.kind()) + " · " + docTitle(doc)));
                    lines.add(Component.literal(doc.name() + " · " + doc.number()));
                    lines.add(Component.literal(Component.translatable(doc.status().translationKey())
                            .getString() + " · " + Component.translatable(doc.integrity()
                            ? "screen.veriborder.terminal.integrity_ok"
                            : "screen.veriborder.terminal.integrity_bad").getString()));
                    lines.add(Component.translatable("screen.veriborder.terminal.mrz_line", dash(doc.mrz1())));
                    lines.add(Component.translatable("screen.veriborder.terminal.mrz_line", dash(doc.mrz2())));
                    for (VisaRow visa : doc.visas()) {
                        lines.add(Component.literal(Component.translatable(visa.type().translationKey())
                                .getString() + " " + visa.country() + " " + visa.code() + " "
                                + visa.entries()
                                + (visa.active() ? "" : " ✕")));
                    }
                    for (EndorseRow endorsement : doc.endorsements()) {
                        lines.add(Component.literal(Component.translatable(
                                        endorsement.type().translationKey()).getString() + " "
                                + endorsement.code() + " " + endorsement.entries()
                                + (endorsement.active() ? "" : " ✕")));
                    }
                    lines.add(Component.translatable("screen.veriborder.terminal.codes_line",
                            doc.codes().isEmpty() ? UNKNOWN : String.join(", ", doc.codes())));
                    lines.add(Component.translatable("screen.veriborder.terminal.stamps_line",
                            doc.stamps().size()));
                    return lines.toArray(new Component[0]);
                }
            }
        }
        return new Component[0];
    }

    // --------------------------------------------------------------- full view

    /**
     * 26.3 entry point: opens the shared paged document viewer for the selected
     * snapshot document. The terminal entry carries no signed NBT of its own, so
     * the server-reported integrity flag is passed through, and the server-side
     * border logs become the viewer's History page.
     */
    private void openDocView() {
        DocRow row = selectedDoc();
        CompoundTag entry = selectedSlot >= 0 ? docEntries.get(selectedSlot) : null;
        if (row == null || entry == null) {
            return;
        }
        DocumentData view = DocumentData.fromTag(documentTag(entry));
        List<DocumentViewScreen.HistoryLine> history = new ArrayList<>();
        for (LogRow log : logs) {
            if (!log.document().isBlank() && !log.document().equalsIgnoreCase(row.number())) {
                continue;
            }
            String direction = translatedOr("screen.veriborder.officer.direction." + lower(log.direction()),
                    log.direction()).getString();
            String result = translatedOr("screen.veriborder.terminal.log." + lower(log.result()),
                    log.result()).getString();
            history.add(new DocumentViewScreen.HistoryLine(formatTime(log.time()), result,
                    direction + " \u00B7 " + text(log.detail()).getString(),
                    resultColor(log.result()), "in".equals(lower(log.direction()))));
        }
        Minecraft.getInstance().setScreen(new DocumentViewScreen(ItemStack.EMPTY, view, null,
                row.integrity(), history,
                () -> Minecraft.getInstance().setScreen(new BorderTerminalScreen(data.copy())))
                .withHeader(row.name() + " \u00B7 " + row.number(),
                        kindText(row.kind()) + " \u00B7 " + docTitle(row))
                .withPrint(false));
    }

    /**
     * Rebuilds a document-shaped tag from a terminal snapshot entry so the
     * shared viewer (and its MRZ / record pages) can read it through the regular
     * {@link DocumentData} API. Record keys are remapped because the snapshot is
     * a flat display summary (entriesTotal, expiry instead of expiryDate).
     */
    private static CompoundTag documentTag(CompoundTag entry) {
        CompoundTag tag = new CompoundTag();
        tag.putString("style", entry.getString("style"));
        tag.putString("kind", entry.getString("kind"));
        tag.putString("name", entry.getString("name"));
        tag.putString("nameLatin", entry.getString("nameLatin"));
        tag.putString("number", entry.getString("number"));
        tag.putString("status", entry.getString("status"));
        tag.putString("birthDate", entry.getString("birthDate"));
        tag.putString("sex", entry.getString("sex"));
        tag.putString("nationality", entry.getString("nationality"));
        tag.putString("authority", entry.getString("authority"));
        tag.putString("placeOfIssue", entry.getString("place"));
        tag.putString("issueDate", entry.getString("issueDate"));
        tag.putString("expiryDate", entry.getString("expiry"));
        tag.putString("mrz1", entry.getString("mrz1"));
        tag.putString("mrz2", entry.getString("mrz2"));
        tag.putString("mrzCountry", entry.getString("mrzCountry"));
        String displayName = entry.getString("displayName").trim();
        // Only a custom title travels into the reconstructed document; an official
        // one would override the layout's short cover name with its long form.
        // Snapshots from before 26.4 carried no flag and are trusted as before.
        boolean customTitle = !entry.contains("customTitle") || entry.getBoolean("customTitle");
        if (!displayName.isBlank() && customTitle) {
            tag.putString("displayName", displayName);
        }
        if (entry.getBoolean("custom")) {
            tag.putBoolean("customStyle", true);
        }
        tag.put("visaFreeCodes", entry.getList("codes", Tag.TAG_STRING).copy());
        ListTag visas = new ListTag();
        ListTag rawVisas = entry.getList("visas", Tag.TAG_COMPOUND);
        for (int i = 0; i < rawVisas.size(); i++) {
            CompoundTag raw = rawVisas.getCompound(i);
            CompoundTag visa = new CompoundTag();
            visa.putString("id", raw.getString("id"));
            visa.putString("country", raw.getString("country"));
            visa.putString("type", raw.getString("type"));
            visa.putInt("entries", raw.getInt("entriesTotal"));
            visa.putInt("usedEntries", raw.getInt("usedEntries"));
            visa.putString("issueDate", raw.getString("issueDate"));
            visa.putString("expiryDate", raw.getString("expiry"));
            visa.putString("authority", "");
            visa.putString("status", raw.getString("status"));
            visa.putString("code", raw.getString("code"));
            visa.putString("label", raw.getString("label"));
            visas.add(visa);
        }
        tag.put("visas", visas);
        ListTag endorsements = new ListTag();
        ListTag rawEndorsements = entry.getList("endorsements", Tag.TAG_COMPOUND);
        for (int i = 0; i < rawEndorsements.size(); i++) {
            CompoundTag raw = rawEndorsements.getCompound(i);
            CompoundTag endorsement = new CompoundTag();
            endorsement.putString("id", raw.getString("id"));
            endorsement.putString("type", raw.getString("type"));
            endorsement.putInt("entries", raw.getInt("entriesTotal"));
            endorsement.putInt("usedEntries", raw.getInt("usedEntries"));
            endorsement.putString("issueDate", raw.getString("issueDate"));
            endorsement.putString("expiryDate", raw.getString("expiry"));
            endorsement.putString("authority", "");
            endorsement.putString("status", raw.getString("status"));
            endorsement.putString("code", raw.getString("code"));
            endorsements.add(endorsement);
        }
        tag.put("endorsements", endorsements);
        tag.put("stamps", entry.getList("stamps", Tag.TAG_COMPOUND).copy());
        return tag;
    }

    // -------------------------------------------------------------- parsing

    private static List<PlayerRow> parsePlayers(CompoundTag data) {
        List<PlayerRow> rows = new ArrayList<>();
        ListTag list = data.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            try {
                rows.add(new PlayerRow(
                        entry.getString("uuid"),
                        entry.getString("name"),
                        entry.getBoolean("session"),
                        entry.getString("document"),
                        entry.getString("port"),
                        entry.getString("gate"),
                        entry.getString("dimension"),
                        entry.getLong("entryTime"),
                        entry.getBoolean("overstay"),
                        entry.getInt("overstayDays")));
            } catch (RuntimeException ignored) {
                // Malformed entry: drop it, keep the terminal usable.
            }
        }
        return rows;
    }

    private static List<DocRow> parseDocs(CompoundTag data) {
        List<DocRow> rows = new ArrayList<>();
        ListTag list = data.getList("docs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            try {
                rows.add(new DocRow(
                        entry.getInt("slot"),
                        entry.getString("style"),
                        kind(entry.getString("kind")),
                        entry.getBoolean("custom"),
                        entry.getBoolean("nameLocked"),
                        entry.getString("name"),
                        entry.getString("nameLatin"),
                        entry.getString("number"),
                        DocumentStatus.byId(entry.getString("status")),
                        entry.getString("birthDate"),
                        entry.getString("sex"),
                        entry.getString("nationality"),
                        entry.getString("authority"),
                        entry.getString("place"),
                        entry.getString("issueDate"),
                        entry.getString("expiry"),
                        entry.getBoolean("permanent"),
                        entry.getString("mrzCountry"),
                        entry.getString("mrz1"),
                        entry.getString("mrz2"),
                        entry.getBoolean("integrity"),
                        entry.getString("displayName"),
                        coverName(entry),
                        strings(entry.getList("codes", Tag.TAG_STRING)),
                        parseVisas(entry.getList("visas", Tag.TAG_COMPOUND)),
                        parseEndorsements(entry.getList("endorsements", Tag.TAG_COMPOUND)),
                        parseStamps(entry.getList("stamps", Tag.TAG_COMPOUND))));
            } catch (RuntimeException ignored) {
                // Malformed document: drop it, keep the terminal usable.
            }
        }
        return rows;
    }

    private static List<VisaRow> parseVisas(ListTag list) {
        List<VisaRow> rows = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            rows.add(new VisaRow(
                    tag.getString("id"),
                    VisaType.byId(tag.getString("type")),
                    tag.getString("country"),
                    tag.getString("code"),
                    tag.getBoolean("active"),
                    tag.getString("status"),
                    tag.getString("entries"),
                    tag.getInt("entriesTotal"),
                    tag.getInt("usedEntries"),
                    tag.getString("issueDate"),
                    tag.getString("expiry"),
                    tag.getString("label"),
                    credentialState(tag)));
        }
        return rows;
    }

    private static List<EndorseRow> parseEndorsements(ListTag list) {
        List<EndorseRow> rows = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            rows.add(new EndorseRow(
                    tag.getString("id"),
                    EndorsementType.byId(tag.getString("type")),
                    tag.getString("code"),
                    tag.getBoolean("active"),
                    tag.getString("status"),
                    tag.getString("entries"),
                    tag.getInt("entriesTotal"),
                    tag.getInt("usedEntries"),
                    tag.getString("issueDate"),
                    tag.getString("expiry"),
                    credentialState(tag)));
        }
        return rows;
    }

    /**
     * Derived state of a server record. The payload carries it as {@code state}
     * (26.3 round 12); a payload from before the change falls back to the stored
     * {@code active} flag so the colour still means something.
     */
    private static CredentialState credentialState(CompoundTag tag) {
        if (tag.contains("state")) {
            return CredentialState.byId(tag.getString("state"));
        }
        return tag.getBoolean("active") ? CredentialState.VALID : CredentialState.EXPIRED;
    }

    private static List<StampRow> parseStamps(ListTag list) {
        List<StampRow> rows = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            rows.add(new StampRow(
                    tag.getString("id"),
                    tag.getString("direction"),
                    tag.getString("port"),
                    tag.getString("country"),
                    tag.getString("date"),
                    tag.getString("officer")));
        }
        return rows;
    }

    private static List<LogRow> parseLogs(CompoundTag data) {
        List<LogRow> rows = new ArrayList<>();
        ListTag list = data.getList("logs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            try {
                rows.add(new LogRow(
                        entry.getLong("time"),
                        entry.getString("result"),
                        entry.getString("direction"),
                        entry.getString("document"),
                        entry.getString("kind"),
                        entry.getString("gate"),
                        entry.getString("detail")));
            } catch (RuntimeException ignored) {
                // Malformed log: drop it.
            }
        }
        return rows;
    }

    private static List<String> strings(ListTag list) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            values.add(list.getString(i));
        }
        return values;
    }

    private static DocumentKind kind(String raw) {
        try {
            return DocumentKind.valueOf(raw);
        } catch (RuntimeException exception) {
            return DocumentKind.ID_CARD;
        }
    }

    // -------------------------------------------------------------- filtering

    private List<PlayerRow> visiblePlayers() {
        if (playerFilter.isBlank()) {
            return players;
        }
        String needle = playerFilter.toLowerCase(Locale.ROOT).trim();
        List<PlayerRow> rows = new ArrayList<>();
        for (PlayerRow row : players) {
            if (row.name().toLowerCase(Locale.ROOT).contains(needle)
                    || row.uuid().toLowerCase(Locale.ROOT).contains(needle)) {
                rows.add(row);
            }
        }
        return rows;
    }

    private List<DocRow> visibleDocs() {
        if (docFilter.isBlank()) {
            return docs;
        }
        String needle = docFilter.toLowerCase(Locale.ROOT).trim();
        List<DocRow> rows = new ArrayList<>();
        for (DocRow doc : docs) {
            if (doc.number().toLowerCase(Locale.ROOT).contains(needle)
                    || doc.name().toLowerCase(Locale.ROOT).contains(needle)) {
                rows.add(doc);
            }
        }
        return rows;
    }

    private int visibleVisaCount() {
        return selectedDoc() == null ? 0 : selectedDoc().visas().size();
    }

    private int visibleEndorseCount() {
        return selectedDoc() == null ? 0 : selectedDoc().endorsements().size();
    }

    private DocRow selectedDoc() {
        for (DocRow doc : docs) {
            if (doc.slot() == selectedSlot) {
                return doc;
            }
        }
        return null;
    }

    private static boolean hasSlot(List<DocRow> rows, int slot) {
        for (DocRow doc : rows) {
            if (doc.slot() == slot) {
                return true;
            }
        }
        return false;
    }

    private String sessionPort() {
        for (PlayerRow row : players) {
            if (row.uuid().equals(selectedUuid)) {
                return row.session() ? row.port() : "";
            }
        }
        return "";
    }

    // ------------------------------------------------------------- formatting

    private static String sexKey(String sex) {
        return switch (sex == null ? "M" : sex.toUpperCase(Locale.ROOT)) {
            case "F" -> "female";
            case "X" -> "x";
            default -> "male";
        };
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static Component styleLabel(String styleId) {
        for (DocStyle style : DocStyle.values()) {
            if (style.id().equals(styleId)) {
                return Component.translatable(style.shortNameKey());
            }
        }
        return Component.literal(styleId == null || styleId.isBlank() ? UNKNOWN : styleId);
    }

    private static String styleText(String styleId) {
        return styleLabel(styleId).getString();
    }

    /**
     * 26.4 display fix: the document title shown on a terminal card and in its
     * tooltip — the issuer-locked / custom name when the snapshot carries one,
     * the layout short name otherwise (pre-26.4 look). The holder name is a
     * separate card row.
     */
    private static String docTitle(DocRow doc) {
        return DocumentTitles.coverLabel(doc.coverName(), styleText(doc.style()));
    }

    /**
     * Snapshot cover label, tolerant of older snapshots that only sent the
     * resolved {@code displayName}: fall back to that, then to the layout name.
     */
    private static String coverName(CompoundTag entry) {
        String cover = entry.getString("coverName").trim();
        if (!cover.isBlank()) {
            return cover;
        }
        String title = entry.getString("displayName").trim();
        return title.isBlank() ? styleText(entry.getString("style")) : title;
    }

    private static String kindText(DocumentKind kind) {
        return kind == null ? UNKNOWN : Component.translatable(kind.translationKey()).getString();
    }

    private static String traveller(String name, String uuid) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        if (uuid != null && !uuid.isBlank()) {
            return uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
        }
        return UNKNOWN;
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value.trim();
    }

    private static String shortDimension(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.startsWith("minecraft:") ? trimmed.substring("minecraft:".length()) : trimmed;
    }

    private static String gateText(PlayerRow row) {
        String gate = row.gate() == null || row.gate().isBlank() ? shortDimension(row.dimension()) : row.gate();
        return gate.isBlank() ? UNKNOWN : gate;
    }

    private static String formatTime(long epochMillis) {
        if (epochMillis <= 0L) {
            return UNKNOWN;
        }
        long millis = epochMillis < 100_000_000_000L ? epochMillis * 1000L : epochMillis;
        try {
            return TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
        } catch (RuntimeException exception) {
            return UNKNOWN;
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static int directionColor(String direction) {
        return switch (lower(direction)) {
            case "in", "entry", "enter" -> Ui.GREEN;
            case "out", "exit", "leave" -> Ui.AMBER;
            case "officer" -> Ui.PURPLE;
            default -> Ui.MUTED;
        };
    }

    private static int resultColor(String result) {
        String value = lower(result).toUpperCase(Locale.ROOT);
        if (value.contains("DENY") || value.contains("REJECT") || value.contains("FAIL")) {
            return Ui.RED;
        }
        if (value.contains("OVERSTAY")) {
            return Ui.AMBER;
        }
        if (value.contains("OFFICER")) {
            return Ui.PURPLE;
        }
        if (value.contains("ALLOW") || value.contains("PASS") || value.contains("GRANT")
                || value.equals("OK")) {
            return Ui.GREEN;
        }
        return Ui.MUTED;
    }

    private static Component text(String raw) {
        if (raw == null || raw.isBlank()) {
            return Component.literal(UNKNOWN);
        }
        String value = raw.trim();
        if (value.indexOf('.') > 0 && !value.contains(" ") && I18n.exists(value)) {
            return Component.translatable(value);
        }
        return Component.literal(value);
    }

    private static Component translatedOr(String key, String fallback) {
        return I18n.exists(key) ? Component.translatable(key) : Component.literal(fallback);
    }

    // --------------------------------------------------------------- helpers

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
