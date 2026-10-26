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
import com.veriborder.resource.GateModelAudit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
                        .executes(BorderDiagnoseCommand::diagnose)
                        .then(Commands.literal("models")
                                .requires(source -> source.hasPermission(2))
                                .executes(BorderDiagnoseCommand::models))));
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

    // ------------------------------------------------------- diagnose models

    /**
     * {@code /veriborder diagnose models} (26.2.1): "why does a gate render as a
     * magenta/black cube?"
     *
     * <p>A missing model is a <b>resource</b> problem, not a code problem, so the
     * whole chain is resolved from the live {@link ResourceManager} instead of
     * needing a client: blockstate JSON, then each variant's model file, then each
     * model's texture PNGs. The block's property set is read from its real
     * {@code StateDefinition}, which is what decides whether vanilla accepts or
     * silently drops a variant key.</p>
     *
     * <p>Prints one line per variant (96 for the three gates) so a single command
     * pins the broken link: a variant that is not covered, a model that is not in
     * the jar, or a texture PNG that is not in the jar.</p>
     */
    private static int models(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source != null ? source.getServer() : null;
        GateModelAudit.Probe probe = resourceProbe(server);

        Map<DeviceType, GateModelAudit.Report> reports = new LinkedHashMap<>();
        int variantCount = 0;
        int expectedCount = 0;
        int modelCount = 0;
        int textureCount = 0;
        int missingModels = 0;
        int missingTextures = 0;
        int problems = 0;

        for (DeviceType type : DeviceType.values()) {
            if (!type.isGate()) {
                continue;
            }
            Block block = ModBlocks.block(type);
            GateModelAudit.Report report = GateModelAudit.audit(probe, type.id(),
                    block.getClass().getSimpleName(), GateModelAudit.domainsOf(block));
            reports.put(type, report);
            variantCount += report.variants().size();
            expectedCount += report.expectedVariants();
            modelCount += report.models().size();
            textureCount += report.textureCount();
            missingModels += report.missingModels();
            missingTextures += report.missingTextures();
            problems += report.problems().size();
        }

        line(source, "VeriBorder diagnose models — gate blockstate -> model -> texture");
        line(source, "  summary : gates=" + reports.size()
                + "  variants=" + variantCount + "/" + expectedCount
                + "  models=" + modelCount
                + "  textures=" + textureCount
                + "  missingModels=" + missingModels
                + "  missingTextures=" + missingTextures
                + "  problems=" + problems);

        for (Map.Entry<DeviceType, GateModelAudit.Report> entry : reports.entrySet()) {
            DeviceType type = entry.getKey();
            GateModelAudit.Report report = entry.getValue();
            line(source, "  " + pad(type.id(), 11)
                    + " block=" + report.blockClass()
                    + "  props=" + report.propertySummary()
                    + "  variants=" + report.variants().size() + "/" + report.expectedVariants()
                    + "  models=" + report.models().size()
                    + "  textures=" + report.textureCount()
                    + (report.ok() ? "  [OK]" : "  [FAIL]"));

            Map<String, GateModelAudit.ModelReport> byLocation = new LinkedHashMap<>();
            for (GateModelAudit.ModelReport model : report.models()) {
                byLocation.put(model.location(), model);
            }
            for (GateModelAudit.Variant variant : report.variants()) {
                String path = GateModelAudit.stripNamespace(variant.model());
                GateModelAudit.ModelReport model = byLocation.get(
                        GateModelAudit.NAMESPACE + ":models/" + path + ".json");
                String textures;
                if (model == null) {
                    textures = "textures=?";
                } else if (model.missingTextures().isEmpty()) {
                    textures = "textures=OK";
                } else {
                    textures = "textures=MISSING " + model.missingTextures();
                }
                line(source, "    " + variant.key() + " -> " + variant.model()
                        + "  model=" + (variant.modelPresent() ? "OK" : "MISSING")
                        + "  " + textures);
            }
            for (String problem : report.problems()) {
                line(source, "    ! " + problem);
            }
        }

        if (problems == 0) {
            line(source, "  [OK] every gate variant resolves to a model whose textures are all in the jar");
            return 1;
        }
        line(source, "  [FAIL] " + problems + " problem(s): a state that does not resolve to a model"
                + " renders vanilla's magenta/black missing model");
        return 0;
    }

    /**
     * Reads the mod's own resources on either side. The server's
     * {@link ResourceManager} also carries the mod file, so the command works in
     * single player and on a dedicated server; the client model baker is never
     * touched.
     */
    private static GateModelAudit.Probe resourceProbe(@Nullable MinecraftServer server) {
        ResourceManager manager = server == null ? null : server.getResourceManager();
        return new GateModelAudit.Probe() {
            @Override
            public boolean exists(String location) {
                return manager != null && manager.getResource(new ResourceLocation(location)).isPresent();
            }

            @Override
            public String read(String location) {
                if (manager == null) {
                    return null;
                }
                Optional<Resource> resource = manager.getResource(new ResourceLocation(location));
                if (resource.isEmpty()) {
                    return null;
                }
                try (InputStream stream = resource.get().open()) {
                    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException exception) {
                    return null;
                }
            }
        };
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
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
