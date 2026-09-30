package dev.zymekoh.kohsanchors.safety;

import dev.zymekoh.kohsanchors.compat.Mc;
import dev.zymekoh.kohsanchors.gui.ServerWarningScreen;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import java.net.InetAddress;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Where the advanced, not secure options may act.
 *
 * <p>They change when clicks reach the server, which anticheats can flag and many servers forbid.
 * So being switched on is not enough: they act in singleplayer, on the local network and on
 * servers the player allowed one by one, after a second warning that names the server. Anywhere
 * else they stay switched on but suspended, and the player is asked once per connection.</p>
 *
 * <p>This is the client protecting its own player from a rule they may not know. It decides nothing
 * for the server and hides nothing from it.</p>
 */
public final class ServerLock {
    private static ServerData lastServer;
    private static boolean resolved;
    private static String address = "";
    private static boolean local = true;
    private static boolean promptedThisConnection;

    private ServerLock() {
    }

    /** The no-wait chain, when it is on and allowed here. */
    public static boolean fastChain() {
        return AnchorsConfig.settings().fastChain && allowedHere();
    }

    /** The instant detonation click, when it is on and allowed here. */
    public static boolean instantDetonation() {
        return AnchorsConfig.settings().instantDetonation && allowedHere();
    }

    /** Whether any advanced option is switched on, allowed here or not. */
    public static boolean anyAdvancedOn() {
        AnchorsConfig.Settings settings = AnchorsConfig.settings();
        return settings.fastChain || settings.instantDetonation;
    }

    /** Whether the advanced options may act in the world the player is in now. */
    public static boolean allowedHere() {
        refresh();
        return local || AnchorsConfig.settings().allowedServers.contains(address);
    }

    /** Whether the player is on a server that is neither local nor allowed. */
    public static boolean suspendedHere() {
        return anyAdvancedOn() && !allowedHere();
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

    /**
     * This server allows the advanced options, until the game closes. Private: the address is kept
     * in memory only, never saved.
     */
    public static void allowCurrent() {
        refresh();
        if (!local && !address.isEmpty() && !AnchorsConfig.settings().allowedServers.contains(address)) {
            AnchorsConfig.settings().allowedServers.add(address);
        }
    }

    public static void forgetAllowedServers() {
        AnchorsConfig.settings().allowedServers.clear();
    }

    /**
     * Once a client tick: on a server that is neither local nor allowed, with an advanced option on,
     * the second warning opens once per connection, as soon as no other screen is open.
     */
    public static void tick(Minecraft minecraft) {
        refresh();
        if (local || !anyAdvancedOn() || promptedThisConnection || allowedHere()) {
            return;
        }
        if (minecraft.player == null || minecraft.level == null || Mc.screen(minecraft) != null
                || Mc.overlay(minecraft) != null) {
            return;
        }
        promptedThisConnection = true;
        Mc.setScreen(minecraft, new ServerWarningScreen(address));
    }

    private static void refresh() {
        Minecraft minecraft = Minecraft.getInstance();
        ServerData server = minecraft.getCurrentServer();
        if (resolved && server == lastServer) {
            return;
        }
        resolved = true;
        lastServer = server;
        promptedThisConnection = false;
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
