package net.errantwanderer.omnipackmod.network;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> Server request to perform a slot click.
 * We only send the slot index, the mouse/button index and an ordinal for click type.
 * The server will call player.containerMenu.clicked(...) and respond with a small ack.
 */
public record SlotActionRequest(int slotIndex, int button, int clickTypeOrdinal) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SlotActionRequest> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("omnipackmod", "slot_action_request"));

    public static final StreamCodec<io.netty.buffer.ByteBuf, SlotActionRequest> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.INT, SlotActionRequest::slotIndex,
                    ByteBufCodecs.INT, SlotActionRequest::button,
                    ByteBufCodecs.INT, SlotActionRequest::clickTypeOrdinal,
                    SlotActionRequest::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}