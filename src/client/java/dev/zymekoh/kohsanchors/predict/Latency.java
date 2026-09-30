package dev.zymekoh.kohsanchors.predict;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * How long the server takes to answer, for the waits that depend on it. The player list's latency
 * is what the server itself measured for this player; singleplayer and a fresh connection read 0.
 */
public final class Latency {
    private Latency() {
    }

    /** The round trip the server last measured for this player, in milliseconds. */
    public static int millis() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null || minecraft.player == null) {
            return 0;
        }
        PlayerInfo info = connection.getPlayerInfo(minecraft.player.getUUID());
        return info == null ? 0 : Math.max(0, info.getLatency());
    }

    /**
     * How long to wait for an answer to a click before assuming it will not come: three round
     * trips and a quarter second for the server's tick, between {@code min} and {@code max}.
     */
    public static long answerWindowNanos(long min, long max) {
        long wait = (3L * millis() + 250L) * 1_000_000L;
        return Math.max(min, Math.min(max, wait));
    }
}
