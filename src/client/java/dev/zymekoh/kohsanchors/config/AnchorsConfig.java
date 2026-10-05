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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The player's settings, kept in {@code config/kohs_anchors.json}; the painted skin lives beside it
 * in {@code config/kohs_anchors/}.
 *
 * <p>Reads happen on every input pass and every frame, so they go to a plain object in memory; the
 * file is only touched when the game starts and when the settings screen closes.</p>
 */
public final class AnchorsConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = KoHsAnchorsClient.MOD_ID + ".json";

    /** The longest debounce the sliders offer: ten seconds. */
    public static final int MAX_DEBOUNCE_MILLIS = 10_000;

    private static Settings settings = new Settings();
    private static int revision;

    private AnchorsConfig() {
    }

    public static Settings settings() {
        return settings;
    }

    /**
     * Grows every time a setting that changes how anchors look is edited, so the skin and the glow
     * rebuild what they cache only when something they read changed.
     */
    public static int revision() {
        return revision;
    }

    public static void changed() {
        revision++;
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
        settings.sanitize();
        changed();
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
        changed();
    }

    /** {@code config/kohs_anchors/}, for the files that do not fit in the JSON. */
    public static Path directory() {
        return FabricLoader.getInstance().getConfigDir().resolve(KoHsAnchorsClient.MOD_ID);
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /** One field per option. Gson fills in the defaults for anything a file does not mention. */
    public static final class Settings {
        // ------------------------------------------------------------------------------------
        // The core. Always on and never saved: switching them off only ever made anchors land
        // worse, so they are no longer options. They stay fields so the KoHs Anchor lab can turn
        // them off at runtime to measure Vanilla against them.
        // ------------------------------------------------------------------------------------

        /** Replays a burst of hotbar and use presses in the order they were pressed. */
        public transient boolean inputOrder = true;

        /** Aims each extra use in a tick at what the crosshair hits after the previous one. */
        public transient boolean freshTarget = true;

        /** Holds a click aimed at an anchor this client just detonated until the server removes it. */
        public transient boolean holdEarlyClicks = true;

        /** Joins a second use with the same item in the same tick to the first, in anchor play. */
        public transient boolean noStacking = true;

        /** Shows and plays a detonation the moment the anchor is used. */
        public transient boolean predictDetonation = true;

        /**
         * Draws a detonated anchor as gone the moment it is used, and what clicks held for the
         * server will do; the world is not changed. Core since 0.5.0: it is what makes the clicks
         * feel immediate, and the server's answer always corrects it.
         */
        public transient boolean hideDetonating = true;

        // ------------------------------------------------------------------------------------
        // General.
        // ------------------------------------------------------------------------------------

        /** A detonated anchor shrinks away over half a second instead of vanishing; drawing only. */
        public boolean anchorFade = true;

        /** Keeps Vanilla's block debris particles for anchor explosions. */
        public boolean anchorDebris = true;

        /** The smoke of anchor explosions: 0 Vanilla, 1 one light puff, 2 none. */
        public int anchorSmoke = 0;

        /**
         * In anchor fights, glowstone only charges anchors; a click that would place it as a block is
         * dropped. Off by default: it rules out the safe anchor, which puts glowstone down on purpose.
         */
        public boolean glowstoneGuard = false;

        /** Animated particles and transitions on the settings screen. */
        public boolean interfaceMotion = true;

        /**
         * A second anchor placement with the same slot within this many milliseconds of the last
         * one is refused. 0 is Vanilla: no window.
         */
        public int anchorDebounceMillis = 0;

        /** The same for glowstone: a charge or a placement. 0 is Vanilla. */
        public int glowstoneDebounceMillis = 0;

        /** Shows Herzium's hotbar order in the settings and lets it be changed from here. */
        public boolean herziumIntegration = true;

        /**
         * Better communication with Herzium: the hotbar presses an anchor burst applies are
         * reported to Herzium, and its hotbar preview is dropped when the burst goes on next tick
         * with another item. Only does anything with Herzium installed.
         */
        public boolean herziumSync = true;

        /** Explains Herzium and its orders when its tab opens; "Don't show again" turns it off. */
        public boolean herziumIntro = true;

        // ------------------------------------------------------------------------------------
        // Anchors Server. The anchor chain options are off by default and only act where a
        // server's bridge allows them (or in singleplayer, where the world is the player's).
        // ------------------------------------------------------------------------------------

        /**
         * Clicks on an exploding anchor are sent at once instead of waiting for the server's
         * removal, and what they will do is drawn at once.
         */
        public boolean fastChain = false;

        /**
         * A click that detonates an anchor is sent the moment it is pressed, between client ticks,
         * instead of on the next tick.
         */
        public boolean instantDetonation = false;

        /**
         * Better glow enemy anchors: with a server's bridge, the server says who placed each
         * anchor, so a placement of the player's never shows as an enemy's and the other way
         * round.
         */
        public boolean betterEnemyGlow = true;

        /**
         * The real round trip, measured by pinging the server's bridge once a second, for every wait
         * that depends on the connection, instead of the player list's figure.
         */
        public boolean bridgeLatency = true;

        /** The player accepted the window on what the mod is and what its bridge adds. */
        public boolean entryAccepted = false;

        /** Development view: internal names, the credit line and the screen inspector. */
        public boolean devMode = false;

        // ------------------------------------------------------------------------------------
        // Looks.
        // ------------------------------------------------------------------------------------

        public Skin skin = new Skin();

        /** The enemy's anchors' own skin: colours and paint, drawn over the anchors marked theirs. */
        public Skin enemySkin = Skin.enemy();
        public Glow glow = new Glow();
        public EnemyGlow enemyGlow = new EnemyGlow();
        public AnchorSound chargeSound = AnchorSound.charge();
        public AnchorSound explosionSound = AnchorSound.explosion();

        /** Keeps the skin and the glow in step with KoHs Crystal Tweaks' crystal colours. */
        public boolean crystalColors = false;

        /** Says what enemy anchors are before their page opens; "Don't show again" turns it off. */
        public boolean enemyIntro = true;

        void sanitize() {
            this.anchorDebounceMillis = clamp(this.anchorDebounceMillis, 0, MAX_DEBOUNCE_MILLIS);
            this.glowstoneDebounceMillis = clamp(this.glowstoneDebounceMillis, 0, MAX_DEBOUNCE_MILLIS);
            this.anchorSmoke = clamp(this.anchorSmoke, 0, 2);
            if (this.skin == null) {
                this.skin = new Skin();
            }
            this.skin.sanitize();
            if (this.enemySkin == null) {
                this.enemySkin = Skin.enemy();
            }
            this.enemySkin.sanitize();
            if (this.glow == null) {
                this.glow = new Glow();
            }
            this.glow.sanitize();
            if (this.enemyGlow == null) {
                this.enemyGlow = new EnemyGlow();
            }
            if (this.chargeSound == null) {
                this.chargeSound = AnchorSound.charge();
            }
            if (this.explosionSound == null) {
                this.explosionSound = AnchorSound.explosion();
            }
            this.chargeSound.sanitize(AnchorSound.CHARGE_ID);
            this.explosionSound.sanitize(AnchorSound.EXPLOSION_ID);
        }
    }

    /**
     * How the anchor's two layers are coloured. The frame is the obsidian body; the glow is
     * everything that lights up: the portal on top, the crying veins and the charge lights.
     */
    public static final class Skin {
        /** The whole skin: off draws the resource pack's anchor untouched. */
        public boolean enabled = false;

        /** The frame's colour, ARGB, and how much of it covers the original, 0 to 100. */
        public int frameColor = 0xFF2A1450;
        public int frameStrength = 0;

        /** The glow's colour and strength. */
        public int glowColor = 0xFFB14DFF;
        public int glowStrength = 0;

        /** Each charge light in its own colour instead of the glow's. */
        public boolean chargeColors = false;

        /** The four charge lights' colours, first to fourth. */
        public int[] charge = {0xFFFFB347, 0xFFFF8A3D, 0xFFFF5A5F, 0xFFE83EAF};

        /** The enemy's starting colours: a crimson frame and a red glow, off until switched on. */
        public static Skin enemy() {
            Skin skin = new Skin();
            skin.frameColor = 0xFF3A0A14;
            skin.glowColor = 0xFFFF3B4E;
            skin.charge = new int[] {0xFFFF6A3D, 0xFFFF4A3D, 0xFFFF2E4E, 0xFFD11F4A};
            return skin;
        }

        void sanitize() {
            this.frameStrength = clamp(this.frameStrength, 0, 100);
            this.glowStrength = clamp(this.glowStrength, 0, 100);
            if (this.charge == null || this.charge.length != 4) {
                this.charge = new int[] {0xFFFFB347, 0xFFFF8A3D, 0xFFFF5A5F, 0xFFE83EAF};
            }
            for (int index = 0; index < 4; index++) {
                this.charge[index] |= 0xFF000000;
            }
            this.frameColor |= 0xFF000000;
            this.glowColor |= 0xFF000000;
        }
    }

    /** The light a charged anchor gives off. */
    public static final class Glow {
        public static final int SOURCE_TEXTURE = 0;
        public static final int SOURCE_CUSTOM = 1;

        public static final int QUALITY_PERFORMANCE = 0;
        public static final int QUALITY_BALANCED = 1;
        public static final int QUALITY_HIGH = 2;

        public boolean enabled = true;

        /** How much of the glow each anchor gets: performance, balanced or quality. */
        public int quality = QUALITY_BALANCED;

        /** Where the colour comes from: the anchor's own glow pixels, or {@link #color}. */
        public int source = SOURCE_TEXTURE;
        public int color = 0xFFB14DFF;

        /** Overall strength, 0 to 300 percent. */
        public int power = 100;

        /** The soft light around the lit pixels, 0 to 300 percent. */
        public int bloom = 100;

        /** The light cast on the blocks around the anchor, 0 to 300 percent. */
        public int spill = 100;

        /** The lit pixels themselves shine at full brightness, as if they gave off light. */
        public boolean emissive = true;

        /** A slow breathing of the light. */
        public boolean pulse = true;

        /** More light with every charge, as Vanilla's light level does. */
        public boolean chargeScaling = true;

        void sanitize() {
            this.source = this.source == SOURCE_CUSTOM ? SOURCE_CUSTOM : SOURCE_TEXTURE;
            this.quality = clamp(this.quality, QUALITY_PERFORMANCE, QUALITY_HIGH);
            this.power = clamp(this.power, 0, 300);
            this.bloom = clamp(this.bloom, 0, 300);
            this.spill = clamp(this.spill, 0, 300);
            this.color |= 0xFF000000;
        }
    }

    /** Anchors other players placed: only their glow colour is theirs. */
    public static final class EnemyGlow {
        /** Enemy anchors: off by default, so every anchor looks like the player's own. */
        public boolean enabled = false;
        public int color = 0xFFFF3B4E;
    }

    /** One replaced anchor sound. */
    public static final class AnchorSound {
        public static final String CHARGE_ID = "minecraft:block.respawn_anchor.charge";
        public static final String EXPLOSION_ID = "minecraft:entity.generic.explode";

        /** Off plays Vanilla's sound, untouched. */
        public boolean custom = false;
        public String sound = CHARGE_ID;
        /** Percent of the original volume, 0 to 100. */
        public int volume = 100;
        /** Percent of the original pitch, 50 to 200. */
        public int pitch = 100;

        static AnchorSound charge() {
            AnchorSound sound = new AnchorSound();
            sound.sound = CHARGE_ID;
            return sound;
        }

        static AnchorSound explosion() {
            AnchorSound sound = new AnchorSound();
            sound.sound = EXPLOSION_ID;
            return sound;
        }

        void sanitize(String fallback) {
            if (this.sound == null || this.sound.isBlank()) {
                this.sound = fallback;
            }
            this.sound = this.sound.trim().toLowerCase(Locale.ROOT);
            this.volume = clamp(this.volume, 0, 100);
            this.pitch = clamp(this.pitch, 50, 200);
        }
    }
}
