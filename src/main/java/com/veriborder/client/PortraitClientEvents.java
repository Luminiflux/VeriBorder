package com.veriborder.client;

import com.veriborder.VeriBorder;
import com.veriborder.client.ui.PortraitArt;
import com.veriborder.doc.DocumentData;
import com.veriborder.item.DocumentItem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector2ic;

import java.util.List;

/**
 * 26.4.1 client-only portrait lifecycle.
 *
 * <p>Two jobs that belong to the client, not to a screen:</p>
 * <ul>
 *   <li><b>log-out release</b> — the dynamic texture slots of uploaded portraits
 *       are freed when the player disconnects, so a client never keeps images
 *       resident for a world it left (and a second world always starts clean);</li>
 *   <li><b>item tooltip badge</b> — a carried document shows a 16×16 portrait next
 *       to its tooltip, so a passport can be recognised in the inventory without
 *       opening it. The badge is drawn in the {@code Pre} phase, which runs before
 *       the tooltip frame, so it is placed just outside the frame the positioner
 *       resolved — never underneath it.</li>
 * </ul>
 *
 * <p>Kept in its own FORGE-bus subscriber (instead of {@code ClientEvents}, which
 * is the MOD bus) so nothing here is loaded on a dedicated server.</p>
 */
@Mod.EventBusSubscriber(modid = VeriBorder.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class PortraitClientEvents {
    /** Tooltip badge edge length. */
    public static final int BADGE = 16;
    /** Gap between the tooltip frame and the badge. */
    private static final int GAP = 2;

    private PortraitClientEvents() {
    }

    /** Frees every uploaded-portrait texture when the player leaves the world. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PortraitArt.releaseAll();
    }

    /** Draws the portrait badge of a document item next to its tooltip. */
    @SubscribeEvent
    public static void onTooltip(RenderTooltipEvent.Pre event) {
        if (event == null || event.getGraphics() == null) {
            return;
        }
        DocumentData doc = DocumentItem.getDocument(event.getItemStack());
        if (doc == null) {
            // A blank document (no signed data at all) has no portrait to show.
            return;
        }
        GuiGraphics graphics = event.getGraphics();
        Font font = event.getFont();
        int width = 0;
        int height = 0;
        List<ClientTooltipComponent> components = event.getComponents();
        for (ClientTooltipComponent component : components) {
            width = Math.max(width, component.getWidth(font));
            height += component.getHeight();
        }
        if (width <= 0 || height <= 0) {
            return;
        }
        Vector2ic position = event.getTooltipPositioner().positionTooltip(event.getScreenWidth(),
                event.getScreenHeight(), event.getX(), event.getY(), width, height);
        int badgeX = position.x() + width + GAP;
        if (badgeX + BADGE > event.getScreenWidth()) {
            // No room on the right: flip to the left side of the frame.
            badgeX = position.x() - GAP - BADGE;
        }
        int badgeY = position.y();
        if (badgeX < 0 || badgeY < 0 || badgeY + BADGE > event.getScreenHeight()) {
            return;
        }
        // A dark plate under the badge keeps it readable over any background.
        graphics.fill(badgeX - 1, badgeY - 1, badgeX + BADGE + 1, badgeY + BADGE + 1, 0xE0101826);
        PortraitArt.draw(graphics, badgeX, badgeY, BADGE, BADGE, doc, 0xFF101826);
    }
}
