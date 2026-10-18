package com.veriborder.item;

import com.veriborder.client.ClientHooks;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentFactory;
import com.veriborder.doc.DocumentKind;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.StampRecord;
import com.veriborder.doc.VisaRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class DocumentItem extends Item {
    private final DocumentKind kind;

    public DocumentItem(DocumentKind kind, Properties properties) {
        super(properties.stacksTo(1).rarity(Rarity.UNCOMMON));
        this.kind = kind;
    }

    public DocumentKind kind() {
        return kind;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.openDocument(stack));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public Component getName(ItemStack stack) {
        DocumentData data = getDocument(stack);
        if (data != null) {
            return data.displayNameComponent();
        }
        return Component.translatable("item.veriborder." + kind.itemId());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        DocumentData data = getDocument(stack);
        if (data == null) {
            tooltip.add(Component.translatable("tooltip.veriborder.document.blank").withStyle(ChatFormatting.RED));
            tooltip.add(Component.translatable("tooltip.veriborder.document.use_modifier").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (data.customStyle()) {
            tooltip.add(Component.translatable("tooltip.veriborder.document.type", data.documentType())
                    .withStyle(ChatFormatting.AQUA));
        }
        tooltip.add(Component.translatable("tooltip.veriborder.document.number", data.number()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.veriborder.document.holder", data.name()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.veriborder.document.status",
                Component.translatable(data.status().translationKey()).withStyle(ChatFormatting.WHITE))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.veriborder.document.expiry", displayExpiry(data))
                .withStyle(data.isExpired() ? ChatFormatting.RED : ChatFormatting.GRAY));
        if (data.kind() == DocumentKind.PASSPORT) {
            List<VisaRecord> visas = data.visas();
            long active = visas.stream().filter(VisaRecord::active).count();
            tooltip.add(Component.translatable("tooltip.veriborder.document.visas", active, visas.size())
                    .withStyle(ChatFormatting.DARK_AQUA));
            List<StampRecord> stamps = data.stamps();
            long in = stamps.stream().filter(StampRecord::inbound).count();
            tooltip.add(Component.translatable("tooltip.veriborder.document.stamps", in, stamps.size() - in)
                    .withStyle(ChatFormatting.DARK_AQUA));
        }
        if (data.kind() == DocumentKind.PERMIT) {
            tooltip.add(Component.translatable("tooltip.veriborder.document.endorsements",
                    data.endorsements().stream().filter(record -> record.active()).count(),
                    data.endorsements().size()).withStyle(ChatFormatting.DARK_AQUA));
        }
        tooltip.add(Component.translatable("tooltip.veriborder.document.open").withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
    }

    private Component displayExpiry(DocumentData data) {
        return data.isPermanent() ? Component.translatable("date.veriborder.permanent") : Component.literal(data.expiryDate());
    }

    @Nullable
    public static DocumentData getDocument(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof DocumentItem)) {
            return null;
        }
        if (!stack.hasTag() || !stack.getTag().contains(DocumentData.ROOT)) {
            return null;
        }
        return DocumentData.fromTag(stack.getTag().getCompound(DocumentData.ROOT));
    }

    public static void writeDocument(ItemStack stack, DocumentData data) {
        stack.getOrCreateTag().put(DocumentData.ROOT, data.tag());
        stack.getOrCreateTag().putInt("CustomModelData", data.itemModelData());
    }

    public static int tintColor(ItemStack stack) {
        DocumentData data = getDocument(stack);
        if (data != null && data.customStyle()) {
            return 0xFF000000 | data.accentColor();
        }
        return -1;
    }

    public ItemStack sampleStack(DocStyle style) {
        if (style.kind() != kind) {
            throw new IllegalArgumentException("Style does not match item kind");
        }
        ItemStack stack = new ItemStack(this);
        writeDocument(stack, DocumentFactory.sample(style));
        return stack;
    }
}
