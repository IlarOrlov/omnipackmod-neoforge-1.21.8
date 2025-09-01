package net.errantwanderer.omnipackmod.network;

import net.errantwanderer.omnipackmod.OmniPackMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.api.distmarker.Dist;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Client-side payload registration and sender helper.
 *
 * - Marks SlotActionRequest as sendable from client -> server (playToServer).
 * - Registers clientbound SlotActionResponse handler.
 * - Buffers sends attempted before registration completes.
 */
@EventBusSubscriber(modid = OmniPackMod.MOD_ID, value = Dist.CLIENT)
public class NetworkRegistrationClient {
    private static volatile boolean NETWORK_READY = false;
    private static final Queue<SlotActionRequest> PENDING = new ConcurrentLinkedQueue<>();

    static {
        OmniPackMod.LOGGER.info("[OmniPackMod] NetworkRegistrationClient loaded");
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        OmniPackMod.LOGGER.info("[OmniPackMod] RegisterPayloadHandlersEvent (client) fired");

        var registrar = event.registrar("1");

        // Mark the request as sendable from client -> server. Provide a no-op receiver
        // so that this payload is known on the client as "can be sent to server".
        registrar.playToServer(
                SlotActionRequest.TYPE,
                SlotActionRequest.STREAM_CODEC,
                (payload, ctx) -> {
                    // This should not happen: client should never receive the serverbound request.
                    OmniPackMod.LOGGER.warn("[OmniPackMod] Unexpected SlotActionRequest delivered to client");
                }
        );
        OmniPackMod.LOGGER.info("[OmniPackMod] SlotActionRequest marked playToServer on client");

        // Register the server -> client response handler (clientbound)
        registrar.playToClient(
                SlotActionResponse.TYPE,
                SlotActionResponse.STREAM_CODEC,
                (payload, ctx) -> {
                    // schedule on main thread if needed
                    ctx.enqueueWork(() -> {
                        OmniPackMod.LOGGER.info("[OmniPackMod] SlotActionResponse received on client (slot={} accepted={})",
                                payload.slotIndex(), payload.accepted());
                        // no-op: server will push authoritative inventory updates separately
                    });
                }
        );
        OmniPackMod.LOGGER.info("[OmniPackMod] SlotActionResponse registered (clientbound)");

        // finished registering — now allow queued sends
        NETWORK_READY = true;
        OmniPackMod.LOGGER.info("[OmniPackMod] NETWORK_READY = true — flushing pending sends");
        flushPending();
    }

    private static void flushPending() {
        SlotActionRequest req;
        while ((req = PENDING.poll()) != null) {
            sendNow(req);
        }
    }

    /**
     * Public helper for UI code: queue if registry not ready.
     */
    public static void sendOrQueue(SlotActionRequest req) {
        if (NETWORK_READY) {
            sendNow(req);
        } else {
            PENDING.add(req);
            OmniPackMod.LOGGER.info("[OmniPackMod] network not ready, queued SlotActionRequest(slot={})", req.slotIndex());
        }
    }

    /**
     * Use Neoforge helper to send to server — do NOT construct vanilla ServerboundCustomPayloadPacket manually.
     */
    private static void sendNow(SlotActionRequest req) {
        try {
            // ClientPacketDistributor wraps / wraps correctly for you.
            ClientPacketDistributor.sendToServer(req);
        } catch (Throwable t) {
            OmniPackMod.LOGGER.warn("[OmniPackMod] Failed to send slot action request (via ClientPacketDistributor): {}", t.getMessage());
        }
    }
}