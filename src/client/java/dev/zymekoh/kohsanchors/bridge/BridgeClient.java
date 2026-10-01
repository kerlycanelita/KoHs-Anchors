package dev.zymekoh.kohsanchors.bridge;

import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.glow.AnchorTracker;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import java.io.DataInputStream;
import java.io.IOException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * The client's side of the server bridge: whether the server the player is on runs the KoHs
 * Anchor's Bridge plugin, what its admin allows, and what it says.
 *
 * <p>On every new connection to a server the bridge is {@link State#CHECKING}: the plugin registers
 * the {@value BridgeProtocol#CHANNEL} channel as the player joins, the client then says hello, and
 * the plugin's welcome carries its policy. No channel within {@link #CHANNEL_WAIT_NANOS}, or no
 * welcome within {@link #WELCOME_WAIT_NANOS}, means the server has no bridge ({@link State#MISSING}).
 * In singleplayer, and when hosting a LAN world, the world is the player's own: {@link State#LOCAL}
 * allows everything.</p>
 *
 * <p>The bridge never decides anything for the server: it only lets the mod know what the server
 * itself says, and lets the anchor chain options act only where the server's admin allows them.</p>
 */
public final class BridgeClient {
    public enum State { OFFLINE, NO_API, CHECKING, CONNECTED, LOCAL, MISSING }

    /** A server registers its channels as the player joins: a little slack for a slow join. */
    private static final long CHANNEL_WAIT_NANOS = 3_000_000_000L;
    private static final long WELCOME_WAIT_NANOS = 4_000_000_000L;
    private static final long PING_INTERVAL_NANOS = 1_000_000_000L;
    private static final long RECHECK_INTERVAL_NANOS = 1_000_000_000L;
    private static final int PINGS_KEPT = 16;

    private static boolean api;
    private static State state = State.OFFLINE;
    private static ClientPacketListener connection;
    private static long checkStartedAt;
    private static long helloSentAt;
    private static long lastRecheckAt;
    private static long changedAt = System.nanoTime();
    private static int protocol;
    private static int policy;
    private static String bridgeVersion = "";
    private static String platform = "";
    private static int sentSettings = -1;
    private static final Int2LongOpenHashMap PINGS = new Int2LongOpenHashMap();
    private static int nextPing;
    private static long lastPingAt;
    private static float roundTrip = -1.0F;
    private static int owners;

    private BridgeClient() {
    }

    /** Registers the channel when Fabric API's networking is there: once, while the game starts. */
    public static void init() {
        api = FabricLoader.getInstance().isModLoaded("fabric-networking-api-v1");
        if (!api) {
            return;
        }
        try {
            BridgeNet.register();
        } catch (RuntimeException | LinkageError failed) {
            api = false;
            KoHsAnchorsClient.LOGGER.warn("The server bridge is unavailable: Fabric API's networking could not be used", failed);
        }
    }

    public static State state() {
        return state;
    }

    /** When the state last changed, for the screen's animations. */
    public static long changedAt() {
        return changedAt;
    }

    /** Whether the anchor chain options may be used here at all: a bridge, or the player's own world. */
    public static boolean available() {
        return state == State.CONNECTED || state == State.LOCAL;
    }

    /** Whether the server's policy allows {@code bit} (a {@code POLICY_*} bit); everything in the player's own world. */
    public static boolean allows(int bit) {
        return state == State.LOCAL || state == State.CONNECTED && (policy & bit) != 0;
    }

    public static int policy() {
        return state == State.CONNECTED ? policy : 0;
    }

    public static String bridgeVersion() {
        return bridgeVersion;
    }

    public static String platform() {
        return platform;
    }

    /** Whether the bridge speaks another version of the protocol than this mod. */
    public static boolean otherProtocol() {
        return state == State.CONNECTED && protocol != BridgeProtocol.VERSION;
    }

    /** The real round trip to the server, smoothed, in milliseconds; -1 before the first answer. */
    public static float roundTripMillis() {
        return state == State.CONNECTED ? roundTrip : -1.0F;
    }

    /** Anchors whose owner the server told this connection. */
    public static int ownersReceived() {
        return owners;
    }

    /** Asks again, as on joining: the screen's "Check again". */
    public static void recheck() {
        if (connection == null || state == State.LOCAL || state == State.NO_API) {
            return;
        }
        startCheck(System.nanoTime());
    }

    /** The player changed which bridge features they use: the server hears it on the next tick. */
    public static void settingsChanged() {
        sentSettings = -1;
    }

    /** Once a client tick: follows the connection, asks, waits, and pings. */
    public static void tick(Minecraft minecraft) {
        ClientPacketListener current = minecraft.getConnection();
        if (current != connection) {
            connection = current;
            reset(minecraft);
        }
        if (current == null || minecraft.player == null || !api) {
            return;
        }
        long now = System.nanoTime();
        switch (state) {
            case CHECKING -> {
                if (helloSentAt == 0L) {
                    if (BridgeNet.serverListens()) {
                        sendHello();
                        helloSentAt = now;
                    } else if (now - checkStartedAt > CHANNEL_WAIT_NANOS) {
                        set(State.MISSING);
                    }
                } else if (now - helloSentAt > WELCOME_WAIT_NANOS) {
                    set(State.MISSING);
                }
            }
            case MISSING -> {
                // A bridge can appear later (a proxy moving the player to another server keeps
                // this connection): a cheap look once a second.
                if (now - lastRecheckAt > RECHECK_INTERVAL_NANOS) {
                    lastRecheckAt = now;
                    if (BridgeNet.serverListens()) {
                        startCheck(now);
                    }
                }
            }
            case CONNECTED -> {
                syncSettings();
                ping(now);
            }
            default -> {
            }
        }
    }

    /** One message from the server's bridge, on the client thread. */
    static void receive(byte[] data) {
        try {
            DataInputStream in = BridgeProtocol.reader(data);
            switch (BridgeProtocol.type(data)) {
                case BridgeProtocol.WELCOME -> {
                    protocol = in.readInt();
                    bridgeVersion = in.readUTF();
                    platform = in.readUTF();
                    policy = in.readInt();
                    sentSettings = -1;
                    set(State.CONNECTED);
                }
                case BridgeProtocol.POLICY -> policy = in.readInt();
                case BridgeProtocol.OWNER -> {
                    long position = in.readLong();
                    byte owner = in.readByte();
                    owners++;
                    if (AnchorsConfig.settings().betterEnemyGlow) {
                        AnchorTracker.serverOwner(position, owner == BridgeProtocol.OWNER_SELF);
                    }
                }
                case BridgeProtocol.PONG -> {
                    int id = in.readInt();
                    long sentAt = PINGS.remove(id);
                    if (sentAt != 0L) {
                        float millis = (System.nanoTime() - sentAt) / 1_000_000.0F;
                        roundTrip = roundTrip < 0.0F ? millis : roundTrip * 0.7F + millis * 0.3F;
                    }
                }
                default -> {
                    // A message of a newer bridge: nothing this version understands.
                }
            }
        } catch (IOException malformed) {
            KoHsAnchorsClient.LOGGER.debug("Ignored a malformed bridge message", malformed);
        }
    }

    private static void reset(Minecraft minecraft) {
        protocol = 0;
        policy = 0;
        bridgeVersion = "";
        platform = "";
        sentSettings = -1;
        PINGS.clear();
        roundTrip = -1.0F;
        owners = 0;
        helloSentAt = 0L;
        if (connection == null) {
            set(State.OFFLINE);
        } else if (minecraft.getCurrentServer() == null) {
            // Singleplayer, or hosting a LAN world: the player's own world.
            set(State.LOCAL);
        } else if (!api) {
            set(State.NO_API);
        } else {
            startCheck(System.nanoTime());
        }
    }

    private static void startCheck(long now) {
        checkStartedAt = now;
        helloSentAt = 0L;
        lastRecheckAt = now;
        set(State.CHECKING);
    }

    private static void set(State next) {
        if (state != next) {
            state = next;
            changedAt = System.nanoTime();
        }
    }

    private static void sendHello() {
        String version = FabricLoader.getInstance().getModContainer(KoHsAnchorsClient.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("");
        String minecraftVersion = FabricLoader.getInstance().getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("");
        BridgeNet.send(BridgeProtocol.message(BridgeProtocol.HELLO, out -> {
            out.writeInt(BridgeProtocol.VERSION);
            out.writeUTF(version);
            out.writeUTF(minecraftVersion);
        }));
    }

    /** The bridge features this player uses, as policy bits. */
    private static int wanted() {
        AnchorsConfig.Settings settings = AnchorsConfig.settings();
        int bits = 0;
        if (settings.fastChain) {
            bits |= BridgeProtocol.POLICY_FAST_CHAIN;
        }
        if (settings.instantDetonation) {
            bits |= BridgeProtocol.POLICY_INSTANT_DETONATION;
        }
        if (settings.betterEnemyGlow) {
            bits |= BridgeProtocol.POLICY_OWNERSHIP;
        }
        if (settings.bridgeLatency) {
            bits |= BridgeProtocol.POLICY_LATENCY;
        }
        return bits;
    }

    private static void syncSettings() {
        int bits = wanted();
        if (bits == sentSettings) {
            return;
        }
        sentSettings = bits;
        BridgeNet.send(BridgeProtocol.message(BridgeProtocol.SETTINGS, out -> out.writeInt(bits)));
    }

    private static void ping(long now) {
        if (!AnchorsConfig.settings().bridgeLatency || (policy & BridgeProtocol.POLICY_LATENCY) == 0
                || now - lastPingAt < PING_INTERVAL_NANOS) {
            return;
        }
        lastPingAt = now;
        if (PINGS.size() >= PINGS_KEPT) {
            // Answers that never came (the server is busy): the oldest are forgotten.
            PINGS.clear();
        }
        int id = ++nextPing;
        PINGS.put(id, now);
        BridgeNet.send(BridgeProtocol.message(BridgeProtocol.PING, out -> out.writeInt(id)));
    }
}
