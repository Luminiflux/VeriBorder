package com.veriborder.border;

import com.mojang.brigadier.context.CommandContext;
import com.veriborder.VeriBorder;
import com.veriborder.block.DeviceSchema;
import com.veriborder.block.DeviceType;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentKind;
import com.veriborder.doc.DocumentSchema;
import com.veriborder.doc.DocumentTemplateCache;
import com.veriborder.network.ModNetwork;
import com.veriborder.network.ProtocolVersion;
import com.veriborder.registry.ModBlockEntities;
import com.veriborder.registry.ModBlocks;
import com.veriborder.registry.ModCreativeTabs;
import com.veriborder.registry.ModItems;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@code /veriborder diagnose} (26.3 round 11): one page that answers "which build,
 * which protocol, which schema, which registries, how much live state".
 *
 * <p>Registered from its own {@link RegisterCommandsEvent} listener instead of
 * being nested in {@link BorderCommands}: Brigadier merges child literals under an
 * existing root, so the {@code /veriborder} tree stays exactly as documented while
 * this diagnostic lives next to the code it reports on.</p>
 *
 * <p>OP level 2, same as every other operator command; it only reads state.</p>
 */
@Mod.EventBusSubscriber(modid = VeriBorder.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BorderDiagnoseCommand {
    private BorderDiagnoseCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("veriborder")
                // Repeated on the sub-tree so the OP requirement survives
                // Brigadier merging another mod's "/veriborder" root.
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("diagnose")
                        .requires(source -> source.hasPermission(2))
                        .executes(BorderDiagnoseCommand::diagnose)));
    }

    private static int diagnose(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        line(source, "VeriBorder diagnose");
        line(source, "  build      : " + buildVersion());
        line(source, "  protocol   : " + ModNetwork.PROTOCOL
                + "  packets=" + ModNetwork.packetCount()
                + "  accepted=" + acceptedVersions());
        for (String row : ProtocolVersion.matrix()) {
            line(source, "    " + row);
        }
        line(source, "  schemas    : document=" + DocumentSchema.CURRENT
                + "  border=" + BorderTravelData.SCHEMA
                + "  device=" + DeviceSchema.CURRENT
                + "  dataFile=" + BorderTravelData.DATA_NAME);
        line(source, "  registry   : blocks=" + ModBlocks.BLOCKS.getEntries().size()
                + "  items=" + ModItems.ITEMS.getEntries().size()
                + "  blockEntities=" + ModBlockEntities.BLOCK_ENTITIES.getEntries().size()
                + "  creativeTabs=" + ModCreativeTabs.TABS.getEntries().size()
                + "  deviceTypes=" + DeviceType.values().length);
        line(source, "  templates  : layouts=" + DocStyle.values().length
                + "  kinds=" + DocumentKind.values().length
                + "  cached=" + DocumentTemplateCache.size()
                + "  cacheKey=" + DocumentTemplateCache.key(DocStyle.CN_PASSPORT));
        if (server == null) {
            line(source, "  state      : no server instance (run this in-game)");
            return 0;
        }
        BorderTravelData data = BorderTravelData.get(server);
        line(source, "  entries    : active=" + data.entryCount()
                + "  logs=" + data.logCount()
                + "  unwritten=" + data.hasPendingWrites()
                + "  flushEvery=" + BorderTravelData.FLUSH_INTERVAL_TICKS + "t");
        return 1;
    }

    private static String buildVersion() {
        try {
            return ModList.get().getModContainerById(VeriBorder.MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("dev");
        } catch (RuntimeException exception) {
            // ModList is unavailable outside a loaded game (unit tests).
            return "dev";
        }
    }

    private static String acceptedVersions() {
        List<String> accepted = new java.util.ArrayList<>();
        accepted.add(ProtocolVersion.CURRENT);
        accepted.addAll(ProtocolVersion.LEGACY);
        return accepted.toString();
    }

    private static void line(@Nullable CommandSourceStack source, String text) {
        if (source != null) {
            source.sendSystemMessage(Component.literal(text));
        }
    }
}
