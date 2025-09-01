package net.errantwanderer.omnipackmod.network;

import net.errantwanderer.omnipackmod.OmniPackMod;
import net.errantwanderer.omnipackmod.SharedInventoryManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.inventory.ClickType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.api.distmarker.Dist;

/**
 * Server-side payload registration: register the client -> server request here.
 */
@EventBusSubscriber(modid = OmniPackMod.MOD_ID, value = Dist.DEDICATED_SERVER)
public class NetworkRegistrationServer {

    static {
        OmniPackMod.LOGGER.info("[OmniPackMod] NetworkRegistrationServer loaded");
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        OmniPackMod.LOGGER.info("[OmniPackMod] Registering serverbound payloads (server)");

        var registrar = event.registrar("1"); // keep version string stable

        // Register client -> server request (SlotActionRequest) as serverbound
        registrar.playToServer(
                SlotActionRequest.TYPE,
                SlotActionRequest.STREAM_CODEC,
                (payload, ctx) -> {
                    OmniPackMod.LOGGER.info("[OmniPackMod] Received SlotActionRequest (server) from player={}",
                            ctx.player() != null ? ctx.player().getName().getString() : "UNKNOWN");

                    // schedule on main thread; enqueueWork returns a CompletableFuture
                    ctx.enqueueWork(() -> {
                        var p = ctx.player();
                        if (!(p instanceof ServerPlayer player)) {
                            OmniPackMod.LOGGER.warn("[OmniPackMod] payload context player is not ServerPlayer");
                            return;
                        }

                        // Defensive mapping ordinal -> ClickType
                        ClickType clickType = ClickType.PICKUP;
                        try {
                            ClickType[] ct = ClickType.values();
                            int idx = Math.max(0, Math.min(ct.length - 1, payload.clickTypeOrdinal()));
                            clickType = ct[idx];
                        } catch (Throwable ignored) {
                            OmniPackMod.LOGGER.warn("[OmniPackMod] invalid clickTypeOrdinal {}, defaulting to PICKUP", payload.clickTypeOrdinal());
                        }

                        try {
                            // Call vanilla server-side click handler (mutates inventories)
                            player.containerMenu.clicked(payload.slotIndex(), payload.button(), clickType, player);
                            OmniPackMod.LOGGER.info("[OmniPackMod] Applied click for {} slot={}", player.getName().getString(), payload.slotIndex());
                        } catch (Throwable t) {
                            OmniPackMod.LOGGER.warn("[OmniPackMod] Error invoking container click: {}", t.getMessage());
                        }

                        // Update shared inventory manager immediately
                        MinecraftServer server = player.getServer();
                        SharedInventoryManager manager = OmniPackMod.getSharedInventoryManager();
                        if (manager != null && server != null) {
                            manager.handleImmediatePlayerAction(player, server);
                            OmniPackMod.LOGGER.info("[OmniPackMod] SharedInventoryManager processed request from {}", player.getName().getString());
                        }

                        // Optionally reply ack to the client
                        ctx.reply(new SlotActionResponse(true, payload.slotIndex()));
                    }).exceptionally(e -> {
                        OmniPackMod.LOGGER.warn("[OmniPackMod] exception while handling SlotActionRequest: {}", e.getMessage());
                        return null;
                    });
                }
        );

        OmniPackMod.LOGGER.info("[OmniPackMod] Registered SlotActionRequest as serverbound");
    }
}