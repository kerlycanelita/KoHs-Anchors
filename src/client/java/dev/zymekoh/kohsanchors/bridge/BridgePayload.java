package dev.zymekoh.kohsanchors.bridge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * One plugin message on the bridge's channel, carried as its raw bytes: the bridge reads and writes
 * them itself ({@link BridgeProtocol}), so the payload is the same for every Minecraft version.
 */
record BridgePayload(byte[] data) implements CustomPacketPayload {
    static final Type<BridgePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(BridgeProtocol.NAMESPACE,
            BridgeProtocol.PATH));
    static final StreamCodec<FriendlyByteBuf, BridgePayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBytes(payload.data()),
            buf -> {
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                return new BridgePayload(data);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
