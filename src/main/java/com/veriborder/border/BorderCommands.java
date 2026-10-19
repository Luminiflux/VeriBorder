package com.veriborder.border;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.veriborder.VeriBorder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Operator commands for the border state machine (all OP level 2):
 *
 * <pre>
 * /veriborder border status &lt;player&gt;
 * /veriborder border clear &lt;player&gt;
 * /veriborder border log [count]
 * </pre>
 *
 * <p>{@code <player>} accepts an online/offline name from the active session
 * list or a UUID, so a traveller who logged out can still be inspected and
 * cleared. Registered through {@link RegisterCommandsEvent}.</p>
 */
@Mod.EventBusSubscriber(modid = VeriBorder.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BorderCommands {
    private static final DateTimeFormatter FULL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter SHORT_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private BorderCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("veriborder")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("border")
                        // Repeated on the sub-tree so the OP requirement survives
                        // Brigadier merging another mod's "/veriborder" root.
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(BorderCommands::status)))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(BorderCommands::clear)))
                        .then(Commands.literal("log")
                                .executes(context -> log(context, 20))
                                .then(Commands.argument("count",
                                                IntegerArgumentType.integer(1, BorderTravelData.MAX_LOGS))
                                        .executes(context -> log(context,
                                                IntegerArgumentType.getInteger(context, "count")))))));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String query = StringArgumentType.getString(context, "player");
        BorderSession session = find(source.getServer(), query);
        if (session == null) {
            source.sendSystemMessage(Component.literal("No active border session for \"" + query + "\"."));
            return 0;
        }
        long now = System.currentTimeMillis();
        boolean overstay = session.overstayed(now);
        source.sendSystemMessage(Component.literal("Border session of " + session.playerName()
                + " (" + session.player() + ")"));
        source.sendSystemMessage(Component.literal("  document: " + blank(session.documentNumber())
                + "  kind: " + session.kind()
                + (session.code().isBlank() ? "" : "  code: " + session.code())));
        source.sendSystemMessage(Component.literal("  entered: " + formatTime(session.entryEpochMillis())
                + "  at: " + BorderService.gate(session.gatePos(), session.dimension())
                + (session.port().isBlank() ? "" : "  port: " + session.port())));
        source.sendSystemMessage(Component.literal("  allowance: " + session.overstayDays() + " day(s)"
                + (overstay
                        ? "  OVERSTAY by " + session.daysOverstayed(now) + " day(s)"
                        : "  on time")));
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        String query = StringArgumentType.getString(context, "player");
        BorderSession session = find(server, query);
        if (session == null || server == null) {
            source.sendSystemMessage(Component.literal("No active border session for \"" + query + "\"."));
            return 0;
        }
        BorderService.clearSession(server, session.player(), source.getTextName());
        source.sendSystemMessage(Component.literal("Cleared the border session of "
                + session.playerName() + "."));
        return 1;
    }

    private static int log(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return 0;
        }
        List<BorderLog> entries = BorderTravelData.get(server).recentLogs(count);
        source.sendSystemMessage(Component.literal("Last " + entries.size()
                + " border log entries (newest first):"));
        for (BorderLog entry : entries) {
            source.sendSystemMessage(Component.literal(format(entry)));
        }
        return entries.size();
    }

    /** Resolves a session by exact player name first, then by UUID. */
    @Nullable
    private static BorderSession find(@Nullable MinecraftServer server, @Nullable String query) {
        if (server == null || query == null || query.isBlank()) {
            return null;
        }
        String trimmed = query.trim();
        BorderTravelData data = BorderTravelData.get(server);
        for (BorderSession session : data.sessions()) {
            if (session.playerName().equalsIgnoreCase(trimmed)) {
                return session;
            }
        }
        try {
            return data.session(UUID.fromString(trimmed));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String format(BorderLog entry) {
        StringBuilder builder = new StringBuilder();
        builder.append('[').append(SHORT_TIME.format(localTime(entry.epochMillis()))).append("] ")
                .append(entry.direction()).append(' ').append(entry.result());
        if (!entry.playerName().isBlank()) {
            builder.append(' ').append(entry.playerName());
        }
        if (!entry.documentNumber().isBlank()) {
            builder.append(" doc=").append(entry.documentNumber());
        }
        if (!entry.kind().isBlank()) {
            builder.append(" kind=").append(entry.kind());
        }
        if (!entry.code().isBlank()) {
            builder.append(" code=").append(entry.code());
        }
        if (!entry.gate().isBlank()) {
            builder.append(" @").append(entry.gate());
        }
        if (!entry.detail().isBlank()) {
            builder.append(" - ").append(entry.detail());
        }
        return builder.toString();
    }

    private static LocalDateTime localTime(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    private static String formatTime(long epochMillis) {
        return FULL_TIME.format(localTime(epochMillis));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
