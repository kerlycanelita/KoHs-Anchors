package dev.zymekoh.kohsanchors.integration;

import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;

/**
 * Reads and cycles Herzium's hotbar order when Herzium is installed.
 *
 * <p>Herzium decides which slot wins when several hotbar keys land in one tick. KoHs Anchor's
 * applies an anchor burst in the order it was pressed and leaves every other press to that
 * choice. From Herzium 1.10.6 the two agree under every order: Herzium defers a key pressed after
 * a click to the next pass, and an anchor burst is resolved entirely by KoHs Anchor's before
 * Herzium's pass runs, so neither ever applies a key the other already applied. Nothing needs to
 * adapt at runtime; what this gives the player is the order in view and a way to change it from
 * here, through Herzium's own methods so it saves and resets itself.</p>
 *
 * <p>Reflection, never a compile-time dependency: KoHs Anchor's loads without Herzium, and a
 * Herzium that renamed its config simply turns the card off.</p>
 */
public final class HerziumBridge {
    public static final String MOD_ID = "herzium";
    /** The first Herzium that defers a key pressed after a click instead of applying it before. */
    public static final String AGREEING_VERSION = "1.10.6";
    private static final String CONFIG_CLASS = "dev.zymekoh.herzium.config.HerziumConfig";

    private static volatile boolean broken;
    private static Method getMethod;
    private static Method orderMethod;
    private static Method cycleMethod;

    private HerziumBridge() {
    }

    public static boolean installed() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    /** Herzium's version, or an empty string. */
    public static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("");
    }

    /** Whether the installed Herzium already agrees with KoHs Anchor's under every order. */
    public static boolean agrees() {
        return FabricLoader.getInstance().getModContainer(MOD_ID).map(container -> {
            try {
                return container.getMetadata().getVersion().compareTo(Version.parse(AGREEING_VERSION)) >= 0;
            } catch (VersionParsingException invalid) {
                return true;
            }
        }).orElse(true);
    }

    public static boolean orderAvailable() {
        return installed() && !broken && resolve();
    }

    /** The order's enum name ({@code VANILLA}, {@code HERZIUM}, {@code VANILLA_REVERSED}), or empty. */
    public static String hotbarOrder() {
        if (!orderAvailable()) {
            return "";
        }
        try {
            Object order = orderMethod.invoke(getMethod.invoke(null));
            return order instanceof Enum<?> value ? value.name() : String.valueOf(order);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            fail(failure);
            return "";
        }
    }

    /** Moves Herzium to its next order, through Herzium's own method. */
    public static void cycleHotbarOrder() {
        if (!orderAvailable()) {
            return;
        }
        try {
            cycleMethod.invoke(getMethod.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            fail(failure);
        }
    }

    /** Where the Herzium integration reaches, for the developer view. */
    public static String reflectionTarget() {
        return CONFIG_CLASS + ".get().hotbarOrder() / cycleHotbarOrder()";
    }

    private static synchronized boolean resolve() {
        if (getMethod != null) {
            return true;
        }
        try {
            Class<?> config = Class.forName(CONFIG_CLASS);
            Method get = config.getMethod("get");
            orderMethod = config.getMethod("hotbarOrder");
            cycleMethod = config.getMethod("cycleHotbarOrder");
            getMethod = get;
            return true;
        } catch (ReflectiveOperationException | LinkageError failure) {
            fail(failure);
            return false;
        }
    }

    private static void fail(Throwable failure) {
        if (!broken) {
            broken = true;
            KoHsAnchorsClient.LOGGER.warn("Herzium is installed but its hotbar order could not be reached; "
                    + "the Herzium card is disabled", failure);
        }
    }
}
