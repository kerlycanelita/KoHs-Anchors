package dev.zymekoh.kohsanchors.bridge;

import java.lang.reflect.Method;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * The bridge's channel through Fabric API's networking, which also tells the server which channels
 * this client listens on (Bukkit only sends plugin messages on channels a client registered).
 *
 * <p>Only loaded when Fabric API's networking is installed. The registries of play payloads are
 * named {@code serverboundPlay}/{@code clientboundPlay} on 26.x and {@code playC2S}/{@code playS2C}
 * on 1.21.11, so they are looked up by name; everything else is the same on every version.</p>
 */
final class BridgeNet {
    private BridgeNet() {
    }

    /** Registers the payload both ways and the receiver: once, while the game starts. */
    static void register() {
        registry("serverboundPlay", "playC2S").register(BridgePayload.TYPE, BridgePayload.CODEC);
        registry("clientboundPlay", "playS2C").register(BridgePayload.TYPE, BridgePayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(BridgePayload.TYPE,
                (payload, context) -> BridgeClient.receive(payload.data()));
    }

    /** Whether the server registered the bridge's channel: a bridge is listening there. */
    static boolean serverListens() {
        try {
            return ClientPlayNetworking.canSend(BridgePayload.TYPE);
        } catch (RuntimeException notConnected) {
            return false;
        }
    }

    static void send(byte[] data) {
        try {
            ClientPlayNetworking.send(new BridgePayload(data));
        } catch (RuntimeException notConnected) {
            // The connection closed between the check and the send: nothing to answer.
        }
    }

    @SuppressWarnings("unchecked")
    private static PayloadTypeRegistry<RegistryFriendlyByteBuf> registry(String name, String olderName) {
        for (String candidate : new String[] {name, olderName}) {
            try {
                Method method = PayloadTypeRegistry.class.getMethod(candidate);
                return (PayloadTypeRegistry<RegistryFriendlyByteBuf>) method.invoke(null);
            } catch (ReflectiveOperationException absent) {
                // The other name, on this version.
            }
        }
        throw new IllegalStateException("Fabric API has no play payload registry named " + name + " or " + olderName);
    }
}
