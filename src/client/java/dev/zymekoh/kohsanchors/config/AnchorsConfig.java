package dev.zymekoh.kohsanchors.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The player's settings, kept in {@code config/kohs_anchors.json}.
 *
 * <p>Reads happen on every input pass, so they go to a plain object in memory; the file is only
 * touched when the game starts and when the settings screen closes.</p>
 */
public final class AnchorsConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = KoHsAnchorsClient.MOD_ID + ".json";

    private static Settings settings = new Settings();

    private AnchorsConfig() {
    }

    public static Settings settings() {
        return settings;
    }

    public static void load() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            settings = new Settings();
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Settings loaded = GSON.fromJson(reader, Settings.class);
            settings = loaded == null ? new Settings() : loaded;
        } catch (IOException | JsonParseException exception) {
            // A damaged file must not cost the player the game: fall back to the defaults and say
            // why, but leave the file alone so nothing they wrote by hand is overwritten silently.
            KoHsAnchorsClient.LOGGER.warn("Could not read {}, using the default settings", file, exception);
            settings = new Settings();
        }
    }

    public static void save() {
        Path file = file();
        Path temporary = file.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(settings, writer);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            KoHsAnchorsClient.LOGGER.warn("Could not save {}", file, exception);
        }
    }

    /** Puts every option back to its default, in memory; {@link #save()} writes it. */
    public static void reset() {
        settings = new Settings();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** One field per option. Gson fills in the defaults for anything a file does not mention. */
    public static final class Settings {
        /** Replays a burst of hotbar and use presses in the order they were pressed. */
        public boolean inputOrder = true;

        /** Aims each extra use in a tick at what the crosshair hits after the previous one. */
        public boolean freshTarget = true;

        /** Holds a click aimed at an anchor this client just detonated until the server removes it. */
        public boolean holdEarlyClicks = true;

        /** Joins a second use with the same item in the same tick to the first, in anchor play. */
        public boolean noStacking = true;

        /** Shows and plays a detonation the moment the anchor is used. */
        public boolean predictDetonation = true;

        /** Keeps Vanilla's block debris particles for anchor explosions. */
        public boolean anchorDebris = true;

        /** Animated particles and transitions on the settings screen. */
        public boolean interfaceMotion = true;
    }
}
