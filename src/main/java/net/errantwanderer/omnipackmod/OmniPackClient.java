package net.errantwanderer.omnipackmod;

import net.errantwanderer.omnipackmod.network.NetworkRegistrationClient;
import net.errantwanderer.omnipackmod.network.SlotActionRequest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Client-side interception: do not locally mutate the inventory for shared slots.
 * Instead send a SlotActionRequest to the server and let the server respond/authoritatively update.
 */
@EventBusSubscriber(modid = OmniPackMod.MOD_ID, value = Dist.CLIENT)
public class OmniPackClient {
    private static final int SHARED_SLOT_COUNT = SharedInventoryManager.SHARED_SLOT_COUNT;

    // Intercept mouse button presses on inventory screens
    @SubscribeEvent
    public static void onScreenMouseClick(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen s = event.getScreen();
        if (!(s instanceof AbstractContainerScreen<?> acs)) {
            return;
        }

        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();
        int button = event.getButton();

        // compute gui offsets
        int guiLeft = acs.getGuiLeft();
        int guiTop = acs.getGuiTop();

        AbstractContainerMenu menu = acs.getMenu();
        Slot clickedSlot = null;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            int sx = guiLeft + slot.x;
            int sy = guiTop + slot.y;
            if (mouseX >= sx && mouseX < sx + 16 && mouseY >= sy && mouseY < sy + 16) {
                clickedSlot = slot;
                break;
            }
        }

        if (clickedSlot == null) return;

        int slotIndex = clickedSlot.index;

        // only intercept if the slot is part of the shared inventory (0..40)
        if (slotIndex < 0 || slotIndex >= SHARED_SLOT_COUNT) return;

        // Cancel vanilla local handling so we don't apply client-side optimistic changes
        event.setCanceled(true);

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        // We send slotIndex + button + a clickType ordinal (use PICKUP as default; server will map properly)
        int clickTypeOrdinal = ClickType.PICKUP.ordinal();

        // Send request to server via NeoForge PacketDistributor
        try {
            SlotActionRequest payload = new SlotActionRequest(slotIndex, button, clickTypeOrdinal);
            // previously: build pkt & send directly
            NetworkRegistrationClient.sendOrQueue(payload);
        } catch (Throwable t) {
            OmniPackMod.LOGGER.warn("Failed to send slot action request (enqueue): {}", t.getMessage());
        }
    }
}