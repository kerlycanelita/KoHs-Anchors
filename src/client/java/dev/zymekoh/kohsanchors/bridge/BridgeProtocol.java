package dev.zymekoh.kohsanchors.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The language KoHs Anchor's and its server bridge (the KoHs Anchor's Bridge plugin) speak on the
 * {@value #CHANNEL} plugin channel.
 *
 * <p>Every message is one plugin message: a type byte and then its fields, written as Java's
 * {@link DataOutputStream} writes them (big-endian integers, modified UTF-8 strings), so a Bukkit,
 * Velocity or BungeeCord plugin reads it with a plain {@link DataInputStream}. The plugin keeps a
 * copy of these numbers; {@link #VERSION} changes when either side would misread the other.</p>
 *
 * <ul>
 *   <li>{@link #HELLO} (client to server), once the server registered the channel: protocol,
 *   mod version, Minecraft version.</li>
 *   <li>{@link #WELCOME} (server to client): protocol, bridge version, platform, and the policy
 *   the server's admin chose, as {@code POLICY_*} bits.</li>
 *   <li>{@link #SETTINGS} (client to server): which bridge features this player uses, as the same
 *   bits, so the server only works for what is in use.</li>
 *   <li>{@link #OWNER} (server to client): who placed the respawn anchor at a block: this player
 *   or someone else. Sent the moment the server accepts the placement.</li>
 *   <li>{@link #PING} and {@link #PONG}: a number the server sends straight back, for the real
 *   round trip.</li>
 *   <li>{@link #POLICY} (server to client): the policy changed (the admin reloaded it).</li>
 * </ul>
 */
public final class BridgeProtocol {
    public static final String NAMESPACE = "kohs_anchors";
    public static final String PATH = "bridge";
    public static final String CHANNEL = NAMESPACE + ":" + PATH;
    public static final int VERSION = 1;

    public static final byte HELLO = 1;
    public static final byte WELCOME = 2;
    public static final byte OWNER = 3;
    public static final byte PING = 4;
    public static final byte PONG = 5;
    public static final byte SETTINGS = 7;
    public static final byte POLICY = 8;

    /** The no-wait chain may act here. */
    public static final int POLICY_FAST_CHAIN = 1;
    /** The instant detonation click may act here. */
    public static final int POLICY_INSTANT_DETONATION = 1 << 1;
    /** The server says who placed each anchor. */
    public static final int POLICY_OWNERSHIP = 1 << 2;
    /** The server answers pings, for the real round trip. */
    public static final int POLICY_LATENCY = 1 << 3;
    /** The server's anticheat was told about the anchor chain (Grim, through its API). */
    public static final int POLICY_ANTICHEAT = 1 << 4;

    public static final byte OWNER_SELF = 1;
    public static final byte OWNER_OTHER = 2;

    private BridgeProtocol() {
    }

    /** Writes one message: its type and whatever {@code body} writes after it. */
    public static byte[] message(byte type, Body body) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(type);
            body.write(out);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return bytes.toByteArray();
    }

    /** A reader positioned after the type byte, which {@link #type} returns. */
    public static DataInputStream reader(byte[] data) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        try {
            in.skipBytes(1);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return in;
    }

    public static byte type(byte[] data) {
        return data.length == 0 ? 0 : data[0];
    }

    @FunctionalInterface
    public interface Body {
        void write(DataOutputStream out) throws IOException;
    }
}
