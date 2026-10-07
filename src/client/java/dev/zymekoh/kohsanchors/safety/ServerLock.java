package dev.zymekoh.kohsanchors.safety;

import dev.zymekoh.kohsanchors.bridge.BridgeClient;
import dev.zymekoh.kohsanchors.bridge.BridgeProtocol;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import java.net.InetAddress;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Where the instant detonation click may act. (The no-wait chain it once had beside it is part of
 * the core since 0.5.0, made safe by following the server at the anchor's block.)
 *
 * <p>They change when clicks reach the server, which an anticheat can flag and a server can forbid,
 * so the server decides: they act only where its bridge allows them ({@link BridgeClient}), and in
 * the player's own world. Anywhere else they stay switched on but do nothing. The player cannot
 * allow a server: only its admin can, by installing the bridge.</p>
 */
public final class ServerLock {
    private static ServerData lastServer;
    private static boolean resolved;
    private static String address = "";
    private static boolean local = true;

    private ServerLock() {
    }

    /** The instant detonation click, when it is on and the server's bridge allows it. */
    public static boolean instantDetonation() {
        return AnchorsConfig.settings().instantDetonation && BridgeClient.allows(BridgeProtocol.POLICY_INSTANT_DETONATION);
    }

    /** The double anchor's second click sent at the press too: only with the instant detonation click. */
    public static boolean instantDoubleAnchor() {
        return AnchorsConfig.settings().instantDoubleAnchor && instantDetonation();
    }

    /** Whether any anchor chain option is switched on, allowed here or not. */
    public static boolean anyAdvancedOn() {
        AnchorsConfig.Settings settings = AnchorsConfig.settings();
        return settings.instantDetonation;
    }

    /** Whether an anchor chain option is on but this server does not allow it. */
    public static boolean suspendedHere() {
        AnchorsConfig.Settings settings = AnchorsConfig.settings();
        return settings.instantDetonation && !BridgeClient.allows(BridgeProtocol.POLICY_INSTANT_DETONATION);
    }

    /** The address of the server the player is on, lower case, or an empty string. */
    public static String address() {
        refresh();
        return address;
    }

    public static boolean local() {
        refresh();
        return local;
    }

    private static void refresh() {
        Minecraft minecraft = Minecraft.getInstance();
        ServerData server = minecraft.getCurrentServer();
        if (resolved && server == lastServer) {
            return;
        }
        resolved = true;
        lastServer = server;
        if (server == null) {
            // Singleplayer, or no world at all.
            address = "";
            local = true;
            return;
        }
        address = normalise(server.ip);
        local = server.isLan() || isLocalAddress(address);
    }

    static String normalise(String ip) {
        String value = ip == null ? "" : ip.trim().toLowerCase(Locale.ROOT);
        // "example.net" and "example.net:25565" are the same server.
        if (value.endsWith(":25565") && value.indexOf(':') == value.lastIndexOf(':')) {
            value = value.substring(0, value.length() - ":25565".length());
        }
        return value;
    }

    /** Loopback and private network addresses, written as numbers or as localhost. */
    static boolean isLocalAddress(String value) {
        String host = value;
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            host = end > 0 ? host.substring(1, end) : host;
        } else if (host.indexOf(':') == host.lastIndexOf(':') && host.indexOf(':') > 0) {
            host = host.substring(0, host.indexOf(':'));
        }
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) {
            return true;
        }
        if (!host.matches("[0-9.]+") && !host.contains(":")) {
            // A name: resolving it here would block the game on DNS. Names are never local.
            return false;
        }
        try {
            InetAddress parsed = InetAddress.getByName(host);
            return parsed.isLoopbackAddress() || parsed.isSiteLocalAddress() || parsed.isLinkLocalAddress()
                    || parsed.isAnyLocalAddress();
        } catch (Exception invalid) {
            return false;
        }
    }
}
