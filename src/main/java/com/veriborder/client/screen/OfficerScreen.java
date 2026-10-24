package com.veriborder.client.screen;

import com.veriborder.client.BetterButton;
import com.veriborder.client.Ui;
import com.veriborder.client.VeriScreen;
import com.veriborder.network.DeviceActionPacket;
import com.veriborder.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Operator console for a {@code MANUAL_TERMINAL}.
 *
 * <p>Renders the active border sessions and the recent border log that the
 * server sends in the officer payload ({@code sessions} / {@code logs}), lets
 * the operator select a session and request {@code officer_clear} for it, and
 * requests a fresh payload with {@code officer_refresh}. The screen performs no
 * permission logic of its own: the server decides whether the player may open
 * the console or clear a session.</p>
 *
 * <p>Every list is parsed defensively. A missing list, a wrong element type or
 * a malformed compound is skipped instead of throwing, so the console still
 * opens on an empty or partially populated payload.</p>
 */
public class OfficerScreen extends VeriScreen {
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final String UNKNOWN = "—";

    // Design-space geometry (the panel origin is always 0,0 for VeriScreen).
    private static final int PANEL_X = 14;
    private static final int PANEL_W = 492;
    private static final int SESSIONS_Y = 48;
    private static final int SESSIONS_H = 166;
    private static final int LOGS_Y = 220;
    private static final int LOGS_H = 134;
    private static final int ROWS_TOP = 30;
    private static final int SESSION_ROW_H = 26;
    private static final int SESSION_ROWS = 5;
    private static final int LOG_ROW_H = 24;
    private static final int LOG_ROWS = 4;
    private static final int BUTTON_Y = 362;
    private static final int HINT_Y = 390;

    private final BlockPos pos;
    private final List<SessionRow> sessions;
    private final List<LogRow> logs;

    private int selected = -1;
    private int sessionScroll;
    private int logScroll;
    private int hoverX = -1;
    private int hoverY = -1;
    private BetterButton clearButton;

    /** One active border session as delivered by the server. */
    private record SessionRow(String player, String name, String document, String kind, String code,
                              String gate, String dimension, String port, String entryTime,
                              boolean overstay, int overstayDays) {
    }

    /** One border log record as delivered by the server. */
    private record LogRow(String time, String player, String name, String direction, String result,
                          String document, String code, String kind, String gate, String detail) {
    }

    public OfficerScreen(BlockPos pos, CompoundTag payload) {
        super(Component.translatable("screen.veriborder.officer"), 520, 420);
        this.pos = pos == null ? BlockPos.ZERO : pos;
        CompoundTag data = payload == null ? new CompoundTag() : payload;
        this.accent = Ui.CYAN;
        this.sessions = parseSessions(data);
        this.logs = parseLogs(data);
    }

    @Override
    protected void build() {
        clearButton = addRenderableWidget(new BetterButton(left + PANEL_X, top + BUTTON_Y, 122, 20,
                Component.translatable("button.veriborder.clear_entry"), button -> clearSelected(), Ui.RED));
        addRenderableWidget(new BetterButton(left + PANEL_X + 128, top + BUTTON_Y, 96, 20,
                Component.translatable("button.veriborder.refresh"), button -> refresh(), Ui.CYAN, true));
        addRenderableWidget(new BetterButton(left + imageWidth - 110, top + BUTTON_Y, 96, 20,
                Component.translatable("button.veriborder.close"), button -> onClose(), Ui.MUTED, true));
        updateClearButton();
    }

    private void updateClearButton() {
        if (clearButton == null) {
            return;
        }
        clearButton.active = selected >= 0 && selected < sessions.size()
                && !sessions.get(selected).player().isBlank();
    }

    /** Clears the active session of the selected traveller; the server re-sends the payload. */
    private void clearSelected() {
        if (selected < 0 || selected >= sessions.size()) {
            return;
        }
        SessionRow row = sessions.get(selected);
        if (row.player().isBlank()) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putString("target", row.player());
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "officer_clear", data, -1));
    }

    /** Asks the server for a fresh officer payload; it reopens this screen. */
    private void refresh() {
        ModNetwork.CHANNEL.sendToServer(new DeviceActionPacket(pos, "officer_refresh", new CompoundTag(), -1));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // renderBackground has no mouse parameters; capture the design-space
        // cursor so row hover highlighting matches the pose-transformed layout.
        this.hoverX = (int) toUiX(mouseX);
        this.hoverY = (int) toUiY(mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        header(graphics, Component.translatable("screen.veriborder.officer").getString(),
                Component.translatable("screen.veriborder.officer.subtitle", sessions.size(), logs.size())
                        .getString());
        drawSessions(graphics);
        drawLogs(graphics);
        drawHint(graphics);
    }

    private void drawSessions(GuiGraphics graphics) {
        int x = left + PANEL_X;
        int y = top + SESSIONS_Y;
        Ui.inset(graphics, x, y, PANEL_W, SESSIONS_H);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.officer.sessions", sessions.size()),
                x + 8, y + 6, PANEL_W - 16, Ui.CYAN);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.officer.sessions.hint"),
                x + 8, y + 17, PANEL_W - 16, Ui.DIM);

        int rowTop = y + ROWS_TOP;
        int maxScroll = Math.max(0, sessions.size() - SESSION_ROWS);
        sessionScroll = Math.min(Math.max(0, sessionScroll), maxScroll);
        if (sessions.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.veriborder.officer.sessions.empty"),
                    x + PANEL_W / 2, rowTop + 26, Ui.MUTED);
            return;
        }
        Ui.scissor(graphics, x + 1, rowTop, x + PANEL_W - 1, rowTop + SESSION_ROWS * SESSION_ROW_H);
        for (int i = 0; i < SESSION_ROWS && sessionScroll + i < sessions.size(); i++) {
            drawSessionRow(graphics, sessions.get(sessionScroll + i), sessionScroll + i, x,
                    rowTop + i * SESSION_ROW_H);
        }
        graphics.disableScissor();
        drawScrollbar(graphics, x + PANEL_W - 5, rowTop, SESSION_ROWS * SESSION_ROW_H,
                sessions.size(), SESSION_ROWS, sessionScroll, Ui.CYAN);
    }

    private void drawSessionRow(GuiGraphics graphics, SessionRow row, int index, int x, int rowY) {
        boolean hovered = hoverX >= x && hoverX <= x + PANEL_W && hoverY >= rowY && hoverY < rowY + SESSION_ROW_H;
        boolean active = index == selected;
        graphics.fill(x + 1, rowY, x + PANEL_W - 1, rowY + SESSION_ROW_H, Ui.rowColor(hovered, active));
        graphics.fill(x + 1, rowY, x + 3, rowY + SESSION_ROW_H, active ? Ui.CYAN : kindColor(row.kind()));
        graphics.fill(x + 6, rowY + SESSION_ROW_H - 1, x + PANEL_W - 6, rowY + SESSION_ROW_H, 0x332A3B50);

        int innerX = x + 8;
        int line1 = rowY + 5;
        Ui.clipped(graphics, traveller(row.name(), row.player()), innerX, line1, 118, Ui.TEXT);
        Ui.clipped(graphics, dash(row.document()), innerX + 124, line1, 76, Ui.MUTED);
        Ui.clipped(graphics, kindText(row.kind()), innerX + 206, line1, 64, kindColor(row.kind()));
        Ui.clipped(graphics, dash(row.code()), innerX + 276, line1, 52, Ui.GOLD);
        if (row.overstay()) {
            Component badge = Component.translatable("screen.veriborder.officer.overstay");
            int badgeWidth = font.width(badge) + 10;
            Ui.badge(graphics, x + PANEL_W - 10 - badgeWidth, rowY + 3, badge, Ui.RED);
        }

        int line2 = rowY + 16;
        Ui.clipped(graphics, row.gate().isBlank() ? UNKNOWN : row.gate(), innerX, line2, 88, Ui.DIM);
        Ui.clipped(graphics, location(row.port(), row.dimension()), innerX + 94, line2, 180, Ui.DIM);
        Ui.clipped(graphics, row.entryTime(), innerX + 280, line2, 116, Ui.MUTED);
        if (row.overstayDays() > 0) {
            Ui.clipped(graphics, Component.translatable("screen.veriborder.officer.overstay_days",
                    row.overstayDays()), innerX + 402, line2, 70, Ui.DIM);
        }
    }

    private void drawLogs(GuiGraphics graphics) {
        int x = left + PANEL_X;
        int y = top + LOGS_Y;
        Ui.inset(graphics, x, y, PANEL_W, LOGS_H);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.officer.logs", logs.size()),
                x + 8, y + 6, PANEL_W - 16, Ui.GOLD);
        Ui.clipped(graphics, Component.translatable("screen.veriborder.officer.logs.hint"),
                x + 8, y + 17, PANEL_W - 16, Ui.DIM);

        int rowTop = y + ROWS_TOP;
        int maxScroll = Math.max(0, logs.size() - LOG_ROWS);
        logScroll = Math.min(Math.max(0, logScroll), maxScroll);
        if (logs.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.veriborder.officer.logs.empty"),
                    x + PANEL_W / 2, rowTop + 20, Ui.MUTED);
            return;
        }
        Ui.scissor(graphics, x + 1, rowTop, x + PANEL_W - 1, rowTop + LOG_ROWS * LOG_ROW_H);
        for (int i = 0; i < LOG_ROWS && logScroll + i < logs.size(); i++) {
            drawLogRow(graphics, logs.get(logScroll + i), x, rowTop + i * LOG_ROW_H);
        }
        graphics.disableScissor();
        drawScrollbar(graphics, x + PANEL_W - 5, rowTop, LOG_ROWS * LOG_ROW_H,
                logs.size(), LOG_ROWS, logScroll, Ui.GOLD);
    }

    private void drawLogRow(GuiGraphics graphics, LogRow row, int x, int rowY) {
        int color = resultColor(row.result());
        graphics.fill(x + 1, rowY, x + PANEL_W - 1, rowY + LOG_ROW_H, 0x33111C28);
        graphics.fill(x + 1, rowY, x + 3, rowY + LOG_ROW_H, color);
        graphics.fill(x + 6, rowY + LOG_ROW_H - 1, x + PANEL_W - 6, rowY + LOG_ROW_H, 0x332A3B50);

        int innerX = x + 8;
        int line1 = rowY + 4;
        Ui.clipped(graphics, row.time(), innerX, line1, 100, Ui.DIM);
        Ui.clipped(graphics, traveller(row.name(), row.player()), innerX + 104, line1, 100, Ui.TEXT);
        Ui.clipped(graphics, directionText(row.direction()), innerX + 210, line1, 54,
                directionColor(row.direction()));
        Ui.clipped(graphics, resultText(row.result()), innerX + 270, line1, 78, color);
        Ui.clipped(graphics, dash(row.document()), innerX + 352, line1, 70, Ui.MUTED);
        Ui.clipped(graphics, dash(row.code()), innerX + 426, line1, 50, Ui.GOLD);

        int line2 = rowY + 14;
        Ui.clipped(graphics, dash(shortDimension(row.gate())), innerX, line2, 150, Ui.DIM);
        Ui.clipped(graphics, kindText(row.kind()), innerX + 154, line2, 60, kindColor(row.kind()));
        Ui.clipped(graphics, detail(row.detail()), innerX + 218, line2, 258, Ui.MUTED);
    }

    /**
     * Officer-side log detail (26.3 round 12): the readable sentence <em>plus</em>
     * the administrator reason code, so the two-track rejection reason keeps its
     * machine-readable code on the officer console.
     */
    private static Component detail(String raw) {
        if (raw == null || raw.isBlank()) {
            return Component.literal(UNKNOWN);
        }
        String value = raw.trim();
        if (value.indexOf('.') > 0 && !value.contains(" ") && I18n.exists(value)) {
            return Component.translatable(value).append(Component.literal(" · " + value));
        }
        return Component.literal(value);
    }

    private void drawHint(GuiGraphics graphics) {
        graphics.drawCenteredString(font, Ui.ellipsis(
                Component.translatable("screen.veriborder.officer.hint").getString(), imageWidth - 40),
                left + imageWidth / 2, top + HINT_Y, Ui.DIM);
    }

    private void drawScrollbar(GuiGraphics graphics, int barX, int barTop, int height, int total, int visible,
                               int scroll, int color) {
        if (total <= visible) {
            return;
        }
        int barHeight = Math.max(10, height * visible / total);
        int maxScroll = Math.max(1, total - visible);
        int barY = barTop + (height - barHeight) * Math.min(scroll, maxScroll) / maxScroll;
        graphics.fill(barX, barTop, barX + 2, barTop + height, 0x332A3B50);
        graphics.fill(barX, barY, barX + 2, barY + barHeight, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        int row = sessionRowAt(toUiX(mouseX), toUiY(mouseY));
        if (row < 0) {
            return false;
        }
        selected = row;
        updateClearButton();
        return true;
    }

    /** Row index under the cursor, or -1. Rows outside the visible list are not selectable. */
    private int sessionRowAt(double uiX, double uiY) {
        int x = left + PANEL_X;
        int rowTop = top + SESSIONS_Y + ROWS_TOP;
        if (uiX < x || uiX > x + PANEL_W || uiY < rowTop || uiY >= rowTop + SESSION_ROWS * SESSION_ROW_H) {
            return -1;
        }
        int index = sessionScroll + (int) ((uiY - rowTop) / SESSION_ROW_H);
        return index >= 0 && index < sessions.size() ? index : -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        int step = (int) Math.signum(delta);
        if (inside(uiX, uiY, left + PANEL_X, top + SESSIONS_Y, PANEL_W, SESSIONS_H)) {
            sessionScroll = clampScroll(sessionScroll - step, sessions.size(), SESSION_ROWS);
            return true;
        }
        if (inside(uiX, uiY, left + PANEL_X, top + LOGS_Y, PANEL_W, LOGS_H)) {
            logScroll = clampScroll(logScroll - step, logs.size(), LOG_ROWS);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private static boolean inside(double uiX, double uiY, int x, int y, int width, int height) {
        return uiX >= x && uiX <= x + width && uiY >= y && uiY <= y + height;
    }

    private static int clampScroll(int value, int total, int visible) {
        return Math.max(0, Math.min(value, Math.max(0, total - visible)));
    }

    // ------------------------------------------------------------------
    // Payload parsing — tolerant of absent, mistyped and malformed entries.
    // ------------------------------------------------------------------

    private static List<SessionRow> parseSessions(CompoundTag payload) {
        List<SessionRow> rows = new ArrayList<>();
        ListTag list = payload.getList("sessions", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Tag raw = list.get(i);
            if (!(raw instanceof CompoundTag entry)) {
                continue;
            }
            try {
                rows.add(new SessionRow(
                        uuid(entry, "player", "uuid"),
                        str(entry, "playerName", "name"),
                        str(entry, "document", "documentNumber"),
                        str(entry, "kind"),
                        str(entry, "code"),
                        gate(entry),
                        str(entry, "dimension"),
                        str(entry, "port"),
                        timeText(entry, "entryTime", "entryEpochMillis", "enteredAt"),
                        flag(entry, "overstay"),
                        (int) number(entry, "overstayDays")));
            } catch (RuntimeException ignored) {
                // Malformed entry: drop it, keep the console usable.
            }
        }
        return rows;
    }

    private static List<LogRow> parseLogs(CompoundTag payload) {
        List<LogRow> rows = new ArrayList<>();
        ListTag list = payload.getList("logs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Tag raw = list.get(i);
            if (!(raw instanceof CompoundTag entry)) {
                continue;
            }
            try {
                rows.add(new LogRow(
                        timeText(entry, "time", "epochMillis", "timestamp"),
                        uuid(entry, "player", "uuid"),
                        str(entry, "playerName", "name"),
                        str(entry, "direction"),
                        str(entry, "result"),
                        str(entry, "document", "documentNumber"),
                        str(entry, "code"),
                        str(entry, "kind"),
                        str(entry, "gate"),
                        str(entry, "detail")));
            } catch (RuntimeException ignored) {
                // Malformed entry: drop it, keep the console usable.
            }
        }
        return rows;
    }

    private static String str(CompoundTag tag, String... keys) {
        for (String key : keys) {
            if (tag.contains(key, Tag.TAG_STRING)) {
                String value = tag.getString(key);
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return "";
    }

    /**
     * Player UUID as a string. {@code BorderSession.toTag}/{@code BorderLog.toTag}
     * store it with {@code putUUID} (a TAG_INT_ARRAY), so an ordinary string read
     * would silently return an empty id and disable Clear Entry.
     */
    private static String uuid(CompoundTag tag, String... keys) {
        for (String key : keys) {
            if (tag.hasUUID(key)) {
                UUID value = tag.getUUID(key);
                return value == null ? "" : value.toString();
            }
            if (tag.contains(key, Tag.TAG_STRING)) {
                String value = tag.getString(key).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return "";
    }

    private static long number(CompoundTag tag, String... keys) {
        for (String key : keys) {
            if (tag.contains(key, Tag.TAG_LONG)) {
                return tag.getLong(key);
            }
            if (tag.contains(key, Tag.TAG_INT)) {
                return tag.getInt(key);
            }
        }
        return 0L;
    }

    private static boolean flag(CompoundTag tag, String key) {
        if (tag.contains(key, Tag.TAG_BYTE)) {
            return tag.getBoolean(key);
        }
        if (tag.contains(key, Tag.TAG_INT)) {
            return tag.getInt(key) != 0;
        }
        return false;
    }

    private static String gate(CompoundTag tag) {
        if (!tag.contains("gateX") && !tag.contains("gateY") && !tag.contains("gateZ")) {
            return "";
        }
        return tag.getInt("gateX") + ", " + tag.getInt("gateY") + ", " + tag.getInt("gateZ");
    }

    /** Epoch millis (or seconds) when numeric, otherwise the raw string the server sent. */
    private static String timeText(CompoundTag tag, String... keys) {
        long millis = number(tag, keys);
        return millis > 0L ? formatTime(millis) : dash(str(tag, keys));
    }

    // ------------------------------------------------------------------
    // Formatting helpers.
    // ------------------------------------------------------------------

    private static String traveller(String name, String player) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        if (player != null && !player.isBlank()) {
            String id = player.trim();
            return id.length() > 8 ? id.substring(0, 8) : id;
        }
        return UNKNOWN;
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value.trim();
    }

    /** Checkpoint plus dimension, e.g. {@code Hong Kong Port · overworld}. */
    private static String location(String port, String dimension) {
        String place = port == null ? "" : port.trim();
        String dim = shortDimension(dimension);
        if (place.isEmpty()) {
            return dash(dim);
        }
        return dim.isEmpty() ? dash(place) : place + " · " + dim;
    }

    /** {@code minecraft:overworld@1,64,2} -> {@code overworld@1,64,2}, saving scarce row width. */
    private static String shortDimension(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.startsWith("minecraft:") ? trimmed.substring("minecraft:".length()) : trimmed;
    }

    private static String formatTime(long epochMillis) {
        if (epochMillis <= 0L) {
            return UNKNOWN;
        }
        // BorderLog stores epoch millis; tolerate a payload that sent epoch seconds.
        long millis = epochMillis < 100_000_000_000L ? epochMillis * 1000L : epochMillis;
        try {
            return TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
        } catch (RuntimeException exception) {
            return UNKNOWN;
        }
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static int kindColor(String kind) {
        return switch (upper(kind)) {
            case "VISA" -> Ui.CYAN;
            case "VISA_FREE", "VISAFREE", "VISA-FREE" -> Ui.GREEN;
            case "ENDORSEMENT" -> Ui.PURPLE;
            case "ALL" -> Ui.AMBER;
            default -> Ui.MUTED;
        };
    }

    private static int directionColor(String direction) {
        return switch (upper(direction)) {
            case "IN", "ENTRY", "ENTER", "ENTERING" -> Ui.GREEN;
            case "OUT", "EXIT", "LEAVE", "LEAVING" -> Ui.AMBER;
            case "OFFICER" -> Ui.PURPLE;
            default -> Ui.MUTED;
        };
    }

    private static int resultColor(String result) {
        String value = upper(result);
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

    private static Component kindText(String kind) {
        if (kind == null || kind.isBlank()) {
            return Component.literal(UNKNOWN);
        }
        String value = kind.trim();
        return translatedOr("screen.veriborder.officer.kind." + value.toLowerCase(Locale.ROOT).replace(' ', '_'),
                value);
    }

    private static Component directionText(String direction) {
        if (direction == null || direction.isBlank()) {
            return Component.literal(UNKNOWN);
        }
        String value = direction.trim();
        return translatedOr("screen.veriborder.officer.direction." + value.toLowerCase(Locale.ROOT).replace(' ', '_'),
                value);
    }

    private static Component resultText(String result) {
        if (result == null || result.isBlank()) {
            return Component.literal(UNKNOWN);
        }
        String value = result.trim();
        return translatedOr("screen.veriborder.officer.result." + value.toLowerCase(Locale.ROOT).replace(' ', '_'),
                value);
    }

    /** Server detail may be a literal sentence or a translation key; use it as-is when unknown. */
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

    /** Uses {@code key} only when the language files define it, otherwise the raw server value. */
    private static Component translatedOr(String key, String fallback) {
        return I18n.exists(key) ? Component.translatable(key) : Component.literal(fallback);
    }
}
