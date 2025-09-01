package net.errantwanderer.omnipackmod.network;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> Client lightweight acknowledgement payload.
 */
public record SlotActionResponse(boolean accepted, int slotIndex) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SlotActionResponse> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("omnipackmod", "slot_action_response"));

    public static final StreamCodec<io.netty.buffer.ByteBuf, SlotActionResponse> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, SlotActionResponse::accepted,
                    ByteBufCodecs.INT, SlotActionResponse::slotIndex,
                    SlotActionResponse::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}