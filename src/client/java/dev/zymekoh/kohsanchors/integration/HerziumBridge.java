package dev.zymekoh.kohsanchors.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.minecraft.world.entity.player.Inventory;

/**
 * Herzium, when it is installed: its hotbar order, and what KoHs Anchor's tells it.
 *
 * <p>Herzium decides which slot wins when several hotbar keys land in one tick. KoHs Anchor's
 * applies an anchor burst in the order it was pressed and leaves every other press to that
 * choice. From Herzium 1.10.6 the two agree under every order: Herzium defers a key pressed after
 * a click to the next pass, and an anchor burst is resolved entirely by KoHs Anchor's before
 * Herzium's pass runs, so neither ever applies a key the other already applied.</p>
 *
 * <p>The order is Herzium's. It is read from Herzium's own config object, which Herzium loads from
 * {@code config/herzium.json}, or from that file if the object cannot be reached; a change goes
 * through Herzium's own method, so Herzium saves it and resets itself. KoHs Anchor's never writes
 * Herzium's file and keeps no copy of the order.</p>
 *
 * <p>With "better communication" on, two more things reach Herzium, both through its public
 * methods: each hotbar press KoHs Anchor's applies itself is reported as consumed
 * ({@code HotbarOrderController.hotbarClickConsumed}), and when a burst goes on in the next tick
 * with another item than the one Herzium's hotbar preview shows, that preview is dropped
 * ({@code ImmediateHotbarInput.clearPreview}), so the hotbar shows the item the burst is using.
 * Never reported: {@code noteCallSiteSelection}. Herzium keeps that for Vanilla's own hotbar pass
 * and takes a difference there as its own mistake, switching its preview off for the rest of the
 * world; a slot KoHs Anchor's chose in pressed order is not one.</p>
 *
 * <p>Reflection, never a compile-time dependency: KoHs Anchor's loads without Herzium, and a
 * Herzium that renamed its classes simply turns the part it reached off.</p>
 */
public final class HerziumBridge {
    public static final String MOD_ID = "herzium";
    /** The first Herzium that defers a key pressed after a click instead of applying it before. */
    public static final String AGREEING_VERSION = "1.10.6";
    /** Herzium on Modrinth. */
    public static final String MODRINTH_URL = "https://modrinth.com/mod/herzium";
    /** Herzium's orders, in the order its own button cycles them. */
    public static final String[] ORDERS = {"VANILLA", "HERZIUM", "VANILLA_REVERSED"};
    /** The order KoHs Anchor's recommends for anchors: the last key pressed wins. */
    public static final String RECOMMENDED = "HERZIUM";

    private static final String CONFIG_CLASS = "dev.zymekoh.herzium.config.HerziumConfig";
    private static final String CONTROLLER_CLASS = "dev.zymekoh.herzium.input.HotbarOrderController";
    private static final String PREVIEW_CLASS = "dev.zymekoh.herzium.input.ImmediateHotbarInput";
    private static final long FILE_CHECK_NANOS = 500_000_000L;

    private static volatile boolean broken;
    private static Method getMethod;
    private static Method orderMethod;
    private static Method cycleMethod;

    private static volatile boolean talkBroken;
    private static boolean talkResolved;
    private static Method consumedMethod;
    private static Method clearPreviewMethod;
    private static Method visualSlotMethod;

    private static String fileOrder = "";
    private static long fileCheckedAt;
    private static long fileModified = Long.MIN_VALUE;

    private static int reportedPresses;
    private static int droppedPreviews;

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

    /** Whether the order can be changed from here: Herzium's own methods were found. */
    public static boolean orderAvailable() {
        return installed() && !broken && resolve();
    }

    /** Herzium's config file, where its order is saved. */
    public static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");
    }

    /**
     * The order's enum name ({@code VANILLA}, {@code HERZIUM}, {@code VANILLA_REVERSED}): Herzium's
     * live value, or what its file says when that cannot be reached; empty without Herzium.
     */
    public static String hotbarOrder() {
        if (!installed()) {
            return "";
        }
        if (orderAvailable()) {
            try {
                Object order = orderMethod.invoke(getMethod.invoke(null));
                return order instanceof Enum<?> value ? value.name() : String.valueOf(order);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                fail(failure);
            }
        }
        return orderFromFile();
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

    /** Moves Herzium to {@code order} with its own method, one step at a time, as its button does. */
    public static void selectHotbarOrder(String order) {
        for (int step = 0; step < ORDERS.length && orderAvailable() && !order.equals(hotbarOrder()); step++) {
            cycleHotbarOrder();
        }
    }

    /** Where the Herzium integration reaches, for the developer view. */
    public static String reflectionTarget() {
        return CONFIG_CLASS + ".get().hotbarOrder() / cycleHotbarOrder()";
    }

    /** Hotbar presses of anchor bursts reported to Herzium this session. */
    public static int reportedPresses() {
        return reportedPresses;
    }

    /** Herzium hotbar previews dropped this session because a burst was about to use another item. */
    public static int droppedPreviews() {
        return droppedPreviews;
    }

    /** How many presses and previews better communication handed to Herzium this session. */
    public static String talkStats() {
        return "presses reported " + reportedPresses + " · previews dropped " + droppedPreviews
                + (talkBroken ? " · unreachable" : "");
    }

    // ------------------------------------------------------------------------------------------
    // Better communication
    // ------------------------------------------------------------------------------------------

    private static boolean talking() {
        return AnchorsConfig.settings().herziumSync && installed() && !talkBroken && resolveTalk();
    }

    /**
     * KoHs Anchor's took one press of hotbar slot {@code slot} from Vanilla's counter, which now
     * holds {@code remaining}: Herzium's record of pending presses follows at once.
     */
    public static void pressConsumed(int slot, int remaining) {
        if (!talking()) {
            return;
        }
        try {
            consumedMethod.invoke(null, slot, remaining);
            reportedPresses++;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failTalk(failure);
        }
    }

    /**
     * An anchor burst goes on next tick and will select {@code nextSlot} first (or keep
     * {@code current} when it is -1): if Herzium's hotbar preview shows another slot, it is dropped,
     * so the hotbar shows the slot actually in use until the burst selects the next one.
     */
    public static void burstContinues(Inventory inventory, int current, int nextSlot) {
        if (!talking()) {
            return;
        }
        try {
            int shown = (int) visualSlotMethod.invoke(null, inventory, current);
            int expected = nextSlot >= 0 ? nextSlot : current;
            if (shown != current && shown != expected) {
                clearPreviewMethod.invoke(null);
                droppedPreviews++;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failTalk(failure);
        }
    }

    // ------------------------------------------------------------------------------------------

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

    private static synchronized boolean resolveTalk() {
        if (talkResolved) {
            return true;
        }
        try {
            Class<?> controller = Class.forName(CONTROLLER_CLASS);
            Class<?> preview = Class.forName(PREVIEW_CLASS);
            consumedMethod = controller.getMethod("hotbarClickConsumed", int.class, int.class);
            clearPreviewMethod = preview.getMethod("clearPreview");
            visualSlotMethod = preview.getMethod("visualSelectedSlot", Inventory.class, int.class);
            talkResolved = true;
            return true;
        } catch (ReflectiveOperationException | LinkageError failure) {
            failTalk(failure);
            return false;
        }
    }

    /** {@code hotbarOrder} as Herzium's file has it, read again at most twice a second. */
    private static synchronized String orderFromFile() {
        long now = System.nanoTime();
        if (now - fileCheckedAt < FILE_CHECK_NANOS && fileCheckedAt != 0L) {
            return fileOrder;
        }
        fileCheckedAt = now;
        Path file = configFile();
        try {
            long modified = Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1L;
            if (modified == fileModified) {
                return fileOrder;
            }
            fileModified = modified;
            // No file yet: Herzium starts a fresh install on its last-input order.
            String order = RECOMMENDED;
            if (modified >= 0L) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    JsonElement root = JsonParser.parseReader(reader);
                    if (root.isJsonObject()) {
                        JsonObject object = root.getAsJsonObject();
                        if (object.has("hotbarOrder") && object.get("hotbarOrder").isJsonPrimitive()) {
                            order = object.get("hotbarOrder").getAsString();
                        }
                    }
                }
            }
            fileOrder = order;
        } catch (IOException | RuntimeException failure) {
            fileOrder = "";
        }
        return fileOrder;
    }

    private static void fail(Throwable failure) {
        if (!broken) {
            broken = true;
            KoHsAnchorsClient.LOGGER.warn("Herzium is installed but its hotbar order could not be reached; "
                    + "the order is read from its file and cannot be changed from here", failure);
        }
    }

    private static void failTalk(Throwable failure) {
        if (!talkBroken) {
            talkBroken = true;
            KoHsAnchorsClient.LOGGER.warn("Herzium is installed but its input methods could not be reached; "
                    + "better communication with it is off", failure);
        }
    }
}
