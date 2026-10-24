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
 * /veriborder border status &lt;person&gt;
 * /veriborder border clear &lt;person&gt;
 * /veriborder border log [count]
 * </pre>
 *
 * <p>{@code <person>} accepts an online/offline player name, a player UUID, the
 * person key or the document number of the active entry, so an entry can be
 * inspected and cleared without knowing which booklet was presented. Registered
 * through {@link RegisterCommandsEvent}.</p>
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
                                .then(Commands.argument("person", StringArgumentType.word())
                                        .executes(BorderCommands::status)))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("person", StringArgumentType.word())
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
        String query = StringArgumentType.getString(context, "person");
        BorderEntry entry = find(source.getServer(), query);
        if (entry == null) {
            source.sendSystemMessage(Component.literal("No active border entry for \"" + query + "\"."));
            return 0;
        }
        long now = System.currentTimeMillis();
        boolean overstay = entry.overstayed(now);
        source.sendSystemMessage(Component.literal("Border entry of " + entry.holder()
                + (entry.playerName().isBlank() ? "" : " (" + entry.playerName() + ")")
                + "  key: " + entry.personKey()));
        source.sendSystemMessage(Component.literal("  document: " + blank(entry.documentNumber())
                + "  kind: " + entry.kind()
                + (entry.code().isBlank() ? "" : "  code: " + entry.code())));
        source.sendSystemMessage(Component.literal("  entered: " + formatTime(entry.entryEpochMillis())
                + "  at: " + BorderService.gate(entry.gatePos(), entry.dimension())
                + (entry.port().isBlank() ? "" : "  port: " + entry.port())));
        source.sendSystemMessage(Component.literal("  allowance: " + entry.overstayDays() + " day(s)"
                + (overstay
                        ? "  OVERSTAY by " + entry.daysOverstayed(now) + " day(s)"
                        : "  on time")));
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        String query = StringArgumentType.getString(context, "person");
        BorderEntry entry = find(server, query);
        if (entry == null || server == null) {
            source.sendSystemMessage(Component.literal("No active border entry for \"" + query + "\"."));
            return 0;
        }
        BorderService.clearEntry(server, entry.personKey(), source.getTextName());
        source.sendSystemMessage(Component.literal("Cleared the border entry of " + entry.holder() + "."));
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

    /**
     * Resolves an active entry by player name, player UUID, document number or
     * person key (in that order). Entries are listed newest first, so a name that
     * occurs more than once resolves to the most recent entry.
     */
    @Nullable
    private static BorderEntry find(@Nullable MinecraftServer server, @Nullable String query) {
        if (server == null || query == null || query.isBlank()) {
            return null;
        }
        String trimmed = query.trim();
        BorderTravelData data = BorderTravelData.get(server);
        for (BorderEntry entry : data.entries()) {
            if (entry.playerName().equalsIgnoreCase(trimmed)) {
                return entry;
            }
        }
        try {
            BorderEntry byPlayer = data.entryForPlayer(UUID.fromString(trimmed));
            if (byPlayer != null) {
                return byPlayer;
            }
        } catch (IllegalArgumentException ignored) {
            // Not a UUID: fall through to the document/person lookups.
        }
        BorderEntry byDocument = data.entryByDocument(trimmed);
        return byDocument != null ? byDocument : data.entry(trimmed);
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
