package dev.zymekoh.kohsanchors.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.skin.ColorMath;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Takes the anchor's colours from KoHs Crystal Tweaks, so crystals and anchors wear one palette.
 *
 * <p>Crystal Tweaks keeps its colours in {@code config/crystal_tweaks.json}: the crystal's outer
 * frame, inner frame and core, the glow's colour and power, and the enemy crystals' glow. They are
 * read, never written, and translated for the anchor rather than copied, because a crystal is
 * bright glass and an anchor is dark stone:</p>
 *
 * <ul>
 *   <li><b>Frame</b>: the outer frame's hue, darkened to obsidian depth with a little of its
 *   chroma, so the body is tinted stone and not a painted block.</li>
 *   <li><b>Glow</b>: the most saturated of core, inner frame and glow colour, lifted to a bright,
 *   vivid lightness for the portal and the veins.</li>
 *   <li><b>Charge lights</b>: a ramp from the inner frame's hue at the first charge to the glow at
 *   the fourth, brighter with every charge, so the charge still reads at a glance.</li>
 *   <li><b>Light</b>: the crystal glow's colour and power; enemy anchors take the enemy crystals'
 *   glow.</li>
 * </ul>
 *
 * <p>Neutral crystal colours (Crystal Tweaks' white default) are ignored in favour of the next
 * colour; a palette that is all neutral follows the glow colour.</p>
 */
public final class CrystalPalette {
    public static final String MOD_ID = "crystal_tweaks";
    public static final String MODRINTH = "https://modrinth.com/mod/kohs-crystal-tweaks";
    private static final int NEUTRAL = 0xFFFFFFFF;
    private static final int ENEMY_RED = 0xFFFF3B3B;

    private static long appliedStamp = Long.MIN_VALUE;

    private CrystalPalette() {
    }

    public static boolean installed() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("crystal_tweaks.json");
    }

    public static boolean configPresent() {
        return Files.isRegularFile(file());
    }

    /** Whether there is anything to take colours from: the mod or at least its settings file. */
    public static boolean available() {
        return installed() || configPresent();
    }

    /**
     * Crystal Tweaks' colours, or {@code null} when its file is missing or unreadable. Its defaults
     * when the mod is installed but never saved.
     */
    public static Colors read() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return installed() ? Colors.DEFAULTS : null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject visuals = section(root, "visuals");
            JsonObject glow = section(root, "glow");
            JsonObject enemy = section(root, "enemyVisuals");
            // The enemy crystals' glow is their own colour only when Crystal Tweaks says it is custom
            // (files from before that switch have just the colour); otherwise it comes from their
            // crystal colours, as Crystal Tweaks draws it.
            boolean enemyCustom = enemy.has("glowColor")
                    && (!enemy.has("customGlowColor") || enemy.get("customGlowColor").getAsBoolean());
            int enemyGlow = enemyCustom ? colorOf(enemy.get("glowColor"), ENEMY_RED)
                    : vivid(ENEMY_RED, colorOf(enemy.get("coreColor"), NEUTRAL), colorOf(enemy.get("innerColor"), NEUTRAL),
                            colorOf(enemy.get("outerColor"), NEUTRAL));
            return new Colors(
                    colorOf(visuals.get("outerColor"), NEUTRAL),
                    colorOf(visuals.get("innerColor"), NEUTRAL),
                    colorOf(visuals.get("coreColor"), NEUTRAL),
                    colorOf(glow.get("color"), 0xFFC880FF),
                    !glow.has("customColor") || glow.get("customColor").getAsBoolean(),
                    glow.has("powerPercent") ? glow.get("powerPercent").getAsInt() : 55,
                    !glow.has("enabled") || glow.get("enabled").getAsBoolean(),
                    enemyGlow,
                    !enemy.has("enabled") || enemy.get("enabled").getAsBoolean(),
                    Files.getLastModifiedTime(file).toMillis());
        } catch (Exception exception) {
            KoHsAnchorsClient.LOGGER.warn("Could not read {}", file, exception);
            return null;
        }
    }

    /** The anchor palette for {@code colors}, without applying it. */
    public static Mapping map(Colors colors) {
        int outer = colors.outer();
        int inner = colors.inner();
        int core = colors.core();
        // Crystal Tweaks' glow colour counts only when the player set one; otherwise its glow is
        // the crystal's own most vivid colour. White and black tint nothing and carry no hue.
        int crystalHue = vivid(0, core, inner, outer);
        int halo = colors.customGlow() || crystalHue == 0 ? colors.glow() : crystalHue;

        int bodySource = hasHue(outer) ? outer : hasHue(inner) ? inner : halo;
        int frame = reshape(bodySource, 0.23F, 0.055F, 0.09F);
        int glowSource = hasHue(core) ? core : hasHue(inner) ? inner : hasHue(outer) ? outer : halo;
        int glow = reshape(glowSource, 0.66F, 0.15F, 0.26F);
        int rampStart = hasHue(inner) ? inner : glowSource;
        int[] charges = new int[4];
        for (int index = 0; index < 4; index++) {
            float t = index / 3.0F;
            int mixed = ColorMath.mixLinear(rampStart, glowSource, t);
            charges[index] = reshape(mixed, 0.62F + 0.24F * t, 0.12F, 0.24F);
        }
        int light = reshape(halo, 0.72F, 0.14F, 0.30F);
        int power = Math.max(20, Math.min(300, Math.round(colors.glowPower() * 1.25F)));
        return new Mapping(frame, glow, charges, light, power, colors.glowEnabled(),
                reshape(colors.enemyGlow(), 0.66F, 0.16F, 0.30F), colors.enemyEnabled());
    }

    /** Writes the mapping into the settings: skin on, both layers, the charge ramp and the light. */
    public static void apply(Mapping mapping, long stamp) {
        AnchorsConfig.Settings settings = AnchorsConfig.settings();
        AnchorsConfig.Skin skin = settings.skin;
        skin.enabled = true;
        skin.frameColor = mapping.frame();
        skin.frameStrength = 90;
        skin.glowColor = mapping.glow();
        skin.glowStrength = 100;
        skin.chargeColors = true;
        System.arraycopy(mapping.charges(), 0, skin.charge, 0, 4);
        settings.glow.enabled = mapping.glowEnabled();
        settings.glow.source = AnchorsConfig.Glow.SOURCE_CUSTOM;
        settings.glow.color = mapping.light();
        settings.glow.power = mapping.power();
        settings.enemyGlow.color = mapping.enemy();
        settings.enemyGlow.enabled = mapping.enemyEnabled();
        appliedStamp = stamp;
        AnchorsConfig.changed();
        AnchorsConfig.save();
    }

    /**
     * With the integration on, takes Crystal Tweaks' colours again when its file changed since
     * they were last taken. Cheap: one file time check.
     */
    public static void syncIfChanged() {
        if (!AnchorsConfig.settings().crystalColors) {
            return;
        }
        Path file = file();
        try {
            long stamp = Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
            if (stamp == appliedStamp) {
                return;
            }
            Colors colors = read();
            if (colors != null) {
                apply(map(colors), stamp);
            } else {
                appliedStamp = stamp;
            }
        } catch (Exception exception) {
            KoHsAnchorsClient.LOGGER.warn("Could not check {}", file, exception);
        }
    }

    private static JsonObject section(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonObject() ? root.getAsJsonObject(key) : new JsonObject();
    }

    /** A colour written as "#RRGGBB" or as a number. */
    private static int colorOf(JsonElement element, int fallback) {
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            if (element.getAsJsonPrimitive().isNumber()) {
                return element.getAsInt() | 0xFF000000;
            }
            return ColorMath.parseHex(element.getAsString(), fallback);
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }

    /** Whether {@code color} tints with a hue: not white, black or a grey. */
    private static boolean hasHue(int color) {
        float[] lab = new float[3];
        ColorMath.toOklab(color, lab);
        return Math.hypot(lab[1], lab[2]) >= 0.04D;
    }

    /** The most colourful of {@code colors} that has a hue, or {@code fallback} when none has. */
    private static int vivid(int fallback, int... colors) {
        int best = fallback;
        double bestChroma = 0.04D;
        float[] lab = new float[3];
        for (int color : colors) {
            ColorMath.toOklab(color, lab);
            double chroma = Math.hypot(lab[1], lab[2]);
            if (chroma >= bestChroma) {
                bestChroma = chroma;
                best = color;
            }
        }
        return best;
    }

    /** {@code color}'s hue at OKLab lightness {@code lightness}, with its chroma kept within bounds. */
    private static int reshape(int color, float lightness, float minChroma, float maxChroma) {
        float[] lab = new float[3];
        ColorMath.toOklab(color, lab);
        float chroma = (float) Math.hypot(lab[1], lab[2]);
        float hue = (float) Math.atan2(lab[2], lab[1]);
        float target = chroma < 0.02F ? 0.0F : Math.max(minChroma, Math.min(maxChroma, chroma));
        return ColorMath.fromOklab(lightness, target * (float) Math.cos(hue), target * (float) Math.sin(hue), 255);
    }

    /** What Crystal Tweaks' file says. */
    public record Colors(int outer, int inner, int core, int glow, boolean customGlow, int glowPower,
            boolean glowEnabled, int enemyGlow, boolean enemyEnabled, long modified) {
        static final Colors DEFAULTS = new Colors(NEUTRAL, NEUTRAL, NEUTRAL, 0xFFC880FF, true, 55, true, ENEMY_RED,
                true, 0L);
    }

    /** The anchor palette taken from it. */
    public record Mapping(int frame, int glow, int[] charges, int light, int power, boolean glowEnabled, int enemy,
            boolean enemyEnabled) {
    }
}
