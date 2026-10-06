package dev.zymekoh.kohsanchors.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;

/**
 * Zymekoh, the cat that lives at the bottom of the KoHs Anchor's settings, brought over from KoHs Inventory
 * Tweaks.
 *
 * <p>It is drawn from the Zymekoh skin itself ({@link MascotSkin}): the face and the
 * hood, the body and its white belly, the slim arms, the paws and the tail painted
 * on the back, each moved pixel by pixel. It breathes, blinks, slow-blinks at the
 * pointer, wags its tail, wanders along its floor and reacts to what the window
 * tells it. Pet it with a click; carry it and it dangles and swings; drop it and it
 * falls; throw it and it tumbles, bounces off the edges of the window or, thrown
 * hard, splats against them. Its menu feeds it: it eats only Totems of Undying,
 * pulled by magic out of the inventory preview, and five of them make it fat and
 * heavy until it digests them ten minutes later. Eaten totems only fade in the
 * preview; the real inventory is never touched.</p>
 *
 * <p>Standing, it keeps to floor no control occupies and takes input only on its
 * own pixels. Its animations run on their own clock and sprites, so no graphics,
 * particle or motion setting changes them. Its preferences live in their own file,
 * so a window saving its working copy of the main configuration never undoes them;
 * what it ate lives only in memory.</p>
 */
public final class AnchorMascot {
    public enum Mood { IDLE, HAPPY, MEH, ALERT, SURPRISED, PET, LOVE, WAVE, ZOOM, DIZZY, SLEEP, HURT, DISGUST }

    /** What the mascot remembers between sessions. */
    public static final class Prefs {
        public boolean enabled = true;
        /** Screen pixels per skin pixel are this times the GUI scale: 1, 2 or 3. */
        public int size = 2;
        public boolean playful = true;
        public boolean sounds = true;
        /** Where it stands, as a fraction of the window width. */
        public double place = 0.86;
    }

    private enum Eyes { OPEN, HALF, CLOSED, HAPPY, WIDE, LOVE, DIZZY, SQUEEZE }
    private enum Ears { UP, PERK, DOWN }
    private enum Body { STANDING, CARRIED, FLYING, SPLAT, HOP }
    private enum Feed { NONE, CAST, FLY, EAT, DONE }

    private static final int W = 20;
    private static final int H = 20;
    private static final int OX = 6;
    private static final int OY = 3;
    /** Bottom row of the paws, the row that stands on the floor. */
    private static final int FLOOR_ROW = 17;
    private static final int[] CANVAS = new int[W * H];
    private static final int[] SHOWN = new int[W * H];
    private static final int OUTLINE = 0x6EC084FC;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int TAIL = 0xFF583478;
    private static final int TAIL_TIP = 0xFFE5E5E5;
    private static final int PURPLE = MascotSkin.HOOD[2];
    private static final int NAVY = MascotSkin.HOOD[2 * 8 + 2];
    private static final int PINK = MascotSkin.HOOD[8 + 1];
    private static final int BRIM = MascotSkin.HOOD[4 * 8 + 1];
    private static final int FACE = MascotSkin.HEAD[5 * 8 + 3];
    private static final int CHEEK = MascotSkin.HEAD[6 * 8 + 3];
    private static final int BODY_EDGE = MascotSkin.BODY[3 * 8];
    // The eyes are drawn on the head after the hood, whose brim covers their top row:
    // big violet gems with a catchlight, the way the skin's own purple glint suggests.
    private static final int CATCHLIGHT = 0xFFFFF8FF;
    private static final int EYE_TOP = 0xFF2A1450;
    private static final int EYE_MID = 0xFF6A35C2;
    private static final int EYE_SHINE = 0xFFC4A2FF;
    private static final int LOVE_TOP = 0xFF8E2463;
    private static final int LOVE_MID = 0xFFE0559E;
    private static final int LOVE_SHINE = 0xFFFFB8DD;
    private static final int ARC = 0xFFD9C2FF;
    private static final int LID = 0xFF8C5BE0;
    private static final int BLUSH = 0xFFF28CC8;
    private static final int BLUSH_SOFT = 0xFF9C5683;
    private static final int MOUTH = 0xFF6E2240;
    private static final int MOUTH_OPEN = 0xFFD2506E;
    private static final int TONGUE = 0xFFFF8FB0;
    private static final int TOTEM_GREEN = 0xFF7C3AED;
    private static final int TOTEM_GOLD = 0xFFC46BFF;
    private static final int[][] DIZZY_RING = {{0, 4}, {1, 4}, {1, 5}, {1, 6}, {0, 6}, {0, 5}};
    /** The tail from the body's corner; the last two pixels hook it into a happy question mark. */
    private static final int[][] TAIL_PATH = {{7, 14}, {8, 15}, {9, 15}, {10, 14}, {10, 13}, {11, 12}, {11, 11}, {11, 10}, {10, 9}};
    private static final float TAU = (float) (Math.PI * 2.0);
    private static final int[] BODY_ROWS = {2, 3, 4, 6, 9};
    private static final int[] ARM_ROWS = {0, 3, 6, 9};
    private static final long SECOND = 1_000_000_000L;
    private static final long SLEEP_AFTER = 30 * SECOND;
    private static final long DROWSY_AFTER = 15 * SECOND;
    /** GUI pixels per second squared, and the speeds that tell a drop, a throw and a splat apart. */
    private static final float GRAVITY = 1500.0F;
    private static final float THROW_SPEED = 380.0F;
    private static final float SPLAT_SPEED = 720.0F;
    /** Five totems make it fat; ten minutes after its last meal it has digested them all. */
    private static final int FAT_AT = 5;
    private static final long DIGEST = 10 * 60 * SECOND;
    // The meal, from the cast: the pull starts, the totem lands in its paws, the last bite, the end.
    private static final long FLY_AT = SECOND * 40 / 100;
    private static final long EAT_AT = SECOND * 130 / 100;
    private static final long DONE_AT = SECOND * 310 / 100;
    private static final long END_AT = SECOND * 440 / 100;
    private static final long[] BITES = {SECOND * 35 / 100, SECOND * 85 / 100, SECOND * 135 / 100};
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("kohs_anchors_mascot.json");
    private static final String HOME = "dev.zymekoh.kohsanchors.gui";
    private static final int MENU_ROW = 14;
    private static final int MENU_ROWS = 6;

    private static final Identifier ANCHOR_OFF = Identifier.withDefaultNamespace("textures/block/respawn_anchor_side0.png");
    private static final Identifier ANCHOR_ON = Identifier.withDefaultNamespace("textures/block/respawn_anchor_side4.png");
    /** How long the preview goes without its anchor once she starts eating it. */
    private static final long MEAL_AWAY = SECOND * 4;

    /** Her own anchor game: one drops beside her, she charges it, it blows. */
    private enum Play { NONE, DROP, CHARGE, WAIT, BOOM }

    private static Play play = Play.NONE;
    private static long playStart;
    private static float playX;
    private static long nextPlayAt;
    private static long anchorGoneUntil;

    private static boolean installed;
    private static @Nullable Prefs prefs;
    private static Mood mood = Mood.IDLE;
    private static long moodStart;
    private static long moodUntil;
    private static @Nullable Screen screen;
    private static int screenWidth = 1;
    private static long lastInput;
    private static int lastMouseX = Integer.MIN_VALUE;
    private static int lastMouseY = Integer.MIN_VALUE;
    private static long blinkAt;
    private static long blinkUntil;
    private static long slowBlinkAt;
    private static long twitchAt;
    private static long twitchUntil;
    private static boolean twitchLeft;
    private static long nextWalkAt;
    /** Where it wanders to, as an offset from its place. */
    private static float wander;
    private static boolean walking;
    /** The skin's own side: tail to the right, turned toward the left. */
    private static boolean facingLeft = true;
    private static long lastUpdate;
    private static long lastZ;
    private static long lastTear;
    private static long lastMeow;
    private static int petStreak;
    private static long lastPetAt;
    private static int scrollBurst;
    private static long scrollWindow;
    // The body, in GUI coordinates: the middle of its feet.
    private static Body body = Body.STANDING;
    private static float bodyX = Float.NaN;
    private static float bodyY;
    private static float velX;
    private static float velY;
    private static float tilt;
    private static boolean thrown;
    private static int bounces;
    private static int splatSide;
    private static long splatUntil;
    private static float hopFrom;
    private static float hopTo;
    private static long hopStart;
    private static float squashX = 1.0F;
    private static float squashY = 1.0F;
    private static long squashStart;
    private static long squashLength;
    // Carrying: the pointer, and its last positions for the speed of a throw.
    private static boolean pressed;
    private static int pressButton;
    private static boolean carrying;
    private static double pressX;
    private static double pressY;
    private static double grabX;
    private static double grabY;
    private static final double[][] TRAIL = new double[8][3];
    private static int trailHead;
    private static int trailCount;
    // Layout of the last frame.
    private static boolean visible;
    private static int left;
    private static int top;
    private static int unit = 2;
    private static int zoneMin;
    private static int zoneMax;
    private static int floorY;
    private static boolean hovered;
    private static long hoveredSince;
    private static boolean menuOpen;
    private static int menuX;
    private static int menuY;
    private static int menuWidth;
    // The meal.
    private static Feed feed = Feed.NONE;
    private static long feedStart;
    private static int bitesTaken;
    private static float sourceX;
    private static float sourceY;
    private static float sourceSize;
    private static int eatenTotems;
    private static long lastMealAt;
    private static @Nullable String tipKey;
    private static long tipStart;
    private static long tipUntil;
    // The totems the inventory preview drew, in slot order: centre and size, this frame and the last.
    private static final float[] PREVIEW = new float[46 * 3];
    private static final float[] PREVIEW_SHOWN = new float[46 * 3];
    private static int previewCount;
    private static int previewShownCount;
    private static long previewShownAt;
    private static final List<Particle> PARTICLES = new ArrayList<>();

    private static final class Particle {
        final String kind;
        float x;
        float y;
        float vx;
        float vy;
        final float gravity;
        final long born;
        final long life;
        final int color;

        Particle(final String kind, final float x, final float y, final float vx, final float vy, final float gravity, final long born, final long life, final int color) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.gravity = gravity;
            this.born = born;
            this.life = life;
            this.color = color;
        }
    }

    private AnchorMascot() {
    }

    /** Marks the mascot as living in the settings screen, which draws it and passes it its input. */
    public static void install() {
        installed = true;
    }

    // ---- preferences -------------------------------------------------------

    public static Prefs prefs() {
        if (prefs == null) {
            Prefs loaded = null;
            if (Files.isRegularFile(FILE)) {
                try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                    loaded = GSON.fromJson(reader, Prefs.class);
                } catch (Exception exception) {
                    KoHsAnchorsClient.LOGGER.warn("Could not read {}", FILE, exception);
                }
            }
            prefs = loaded == null ? new Prefs() : loaded;
            prefs.size = Math.max(1, Math.min(3, prefs.size));
            prefs.place = Double.isFinite(prefs.place) ? Math.max(0.0, Math.min(1.0, prefs.place)) : 0.86;
        }
        return prefs;
    }

    public static void setEnabled(final boolean enabled) {
        prefs().enabled = enabled;
        save();
        if (enabled) {
            react(Mood.WAVE);
        }
    }

    static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(prefs(), writer);
            }
        } catch (Exception exception) {
            KoHsAnchorsClient.LOGGER.warn("Could not write {}", FILE, exception);
        }
    }

    // ---- what the windows tell it -------------------------------------------

    /** Something happened in the window; the mascot reacts. A held mood is re-armed by calling it again. */
    public static void react(final Mood next) {
        long now = System.nanoTime();
        lastInput = now;
        if (next == Mood.ALERT && mood == Mood.ALERT) {
            moodUntil = now + SECOND * 12 / 10;
            return;
        }
        mood = next;
        moodStart = now;
        moodUntil = now + switch (next) {
            case HAPPY, SURPRISED -> SECOND * 11 / 10;
            case MEH, ZOOM -> SECOND;
            case ALERT -> SECOND * 12 / 10;
            case PET, WAVE -> SECOND * 14 / 10;
            case LOVE, DISGUST -> SECOND * 2;
            case DIZZY, HURT -> SECOND * 15 / 10;
            default -> 0L;
        };
        if (prefs().enabled && visible) {
            burst(next, now);
        }
    }

    /** Every scroll counts toward getting dizzy; it never consumes the scroll. */
    public static void scrolled() {
        long now = System.nanoTime();
        lastInput = now;
        if (now - scrollWindow > SECOND * 6 / 10) {
            scrollWindow = now;
            scrollBurst = 0;
        }
        if (++scrollBurst >= 14 && mood != Mood.DIZZY && prefs().playful) {
            react(Mood.DIZZY);
        }
    }

    /** The inventory preview reports each totem it draws, in slot order: where, and how large on screen. */
    public static void previewAnchor(final int ordinal, final float x, final float y, final float size) {
        if (ordinal >= 0 && ordinal < PREVIEW.length / 3) {
            PREVIEW[ordinal * 3] = x + size * 0.5F;
            PREVIEW[ordinal * 3 + 1] = y + size * 0.5F;
            PREVIEW[ordinal * 3 + 2] = size;
            previewCount = Math.max(previewCount, ordinal + 1);
        }
    }

    /** While it is carried, where its head is, for windows that react to it; otherwise null. */
    public static float @Nullable [] carriedHead() {
        if (body != Body.CARRIED || !visible) {
            return null;
        }
        return new float[] {bodyX, top + 8.0F * unit};
    }

    /** Whether the settings preview leaves its anchor out: she is eating it, or it is still coming back. */
    public static boolean anchorEaten() {
        return prefs().enabled && System.nanoTime() < anchorGoneUntil;
    }

    /** The preview's anchor just blew up: she jumps. */
    public static void previewBlast() {
        react(Mood.SURPRISED);
        if (prefs().sounds && visible) {
            sound("entity.cat.ambient", 1.6F, 0.18F);
        }
    }

    // ---- drawing -----------------------------------------------------------

    /** Called for every screen after it is drawn; only this mod's windows have the mascot. */
    public static void extract(final Screen owner, final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY) {
        if (installed && owner.getClass().getPackageName().equals(HOME)) {
            draw(graphics, owner, mouseX, mouseY);
        }
        // What the preview drew this frame is what a feed started before the next one sees.
        System.arraycopy(PREVIEW, 0, PREVIEW_SHOWN, 0, previewCount * 3);
        previewShownCount = previewCount;
        previewShownAt = System.nanoTime();
        previewCount = 0;
    }

    private static void draw(final GuiGraphicsExtractor graphics, final Screen owner, final int mouseX, final int mouseY) {
        Prefs settings = prefs();
        if (!settings.enabled) {
            visible = false;
            menuOpen = false;
            return;
        }
        long now = System.nanoTime();
        if (owner != screen) {
            screen = owner;
            carrying = false;
            pressed = false;
            menuOpen = false;
            wander = 0.0F;
            body = Body.STANDING;
            bodyX = Float.NaN;
            tilt = 0.0F;
            feed = Feed.NONE;
            play = Play.NONE;
            nextPlayAt = now + SECOND * (9 + ThreadLocalRandom.current().nextInt(8));
            PARTICLES.clear();
            lastInput = now;
            if (mood == Mood.SLEEP || mood == Mood.IDLE) {
                react(Mood.WAVE);
                if (now - lastMeow > 25 * SECOND) {
                    lastMeow = now;
                    sound("entity.cat.ambient", 1.15F, 0.28F);
                }
            }
        }
        if (mouseX != lastMouseX || mouseY != lastMouseY) {
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            if (mood == Mood.SLEEP) {
                react(Mood.SURPRISED);
            }
            lastInput = now;
        }
        digest(now);
        unit = settings.size;
        screenWidth = owner.width;
        visible = findFloor(owner, 16 * unit, 20 * unit);
        if (!visible) {
            menuOpen = false;
            return;
        }
        if (Float.isNaN(bodyX)) {
            bodyX = standX(owner);
            bodyY = floorY;
        }
        float dt = lastUpdate == 0L ? 0.0F : Math.min(0.05F, (now - lastUpdate) / (float) SECOND);
        lastUpdate = now;
        update(now, dt, settings, owner);

        left = Math.round(bodyX) - W * unit / 2;
        top = Math.round(bodyY) - (FLOOR_ROW + 1) * unit - (body == Body.STANDING ? hop(now) * unit : 0);
        look(mouseX);
        compose(now);
        // Its own layer: the window's composited previews never cover it.
        graphics.nextStratum();
        if (feed == Feed.CAST || feed == Feed.FLY) {
            magicCircle(graphics, now);
        }
        paintBody(graphics, now);
        drawMeal(graphics, now);
        drawPlay(graphics, now);
        drawParticles(graphics, now);
        if (mood == Mood.ZOOM && body == Body.STANDING) {
            speedLines(graphics, now);
        }
        drawTip(graphics, owner, now);

        hovered = body == Body.STANDING && !carrying && !menuOpen && contains(mouseX, mouseY);
        if (hovered) {
            if (hoveredSince == 0L) {
                hoveredSince = now;
                // A cat's slow blink: it trusts you.
                if (mood == Mood.IDLE) {
                    slowBlinkAt = now;
                }
            }
            if (now - hoveredSince > SECOND * 7 / 10 && feed == Feed.NONE) {
                Font font = Minecraft.getInstance().font;
                List<FormattedCharSequence> lines = new ArrayList<>();
                lines.add(Component.translatable("kohs_anchors.mascot.name").getVisualOrderText());
                lines.addAll(font.split(Component.translatable("kohs_anchors.mascot.hint"), 200));
                graphics.setTooltipForNextFrame(lines, mouseX, mouseY);
            }
        } else {
            hoveredSince = 0L;
        }
        if (menuOpen) {
            drawMenu(graphics, mouseX, mouseY);
        }
    }

    /** The head alone, for the option card that turns the mascot on and off. */
    public static void drawPortrait(final GuiGraphicsExtractor graphics, final int x, final int y, final int size, final float energy) {
        long now = System.nanoTime();
        boolean blink = (now / (SECOND / 10)) % 37 == 0;
        boolean happy = energy > 0.5F;
        Arrays.fill(CANVAS, 0);
        // Hovered it smiles, switched off it dozes.
        head(happy ? Eyes.HAPPY : energy <= 0.0F ? Eyes.HALF : blink ? Eyes.CLOSED : Eyes.OPEN,
            happy ? Ears.PERK : Ears.UP, 0, happy, happy ? 1 : 0, false, false, 0, 0, now);
        outline();
        int scale = Math.max(1, size / 12);
        // Rows 0-10 hold the ears and the head; centre them in the square.
        int drawX = x + (size - W * scale) / 2;
        int drawY = y + (size - 11 * scale) / 2 - scale;
        paintRows(graphics, drawX, drawY, scale, 0, 11, false);
    }

    // ---- input -------------------------------------------------------------

    public static boolean mouseClicked(final Screen owner, final MouseButtonEvent event) {
        if (!prefs().enabled || owner != screen || !visible) {
            return false;
        }
        long now = System.nanoTime();
        lastInput = now;
        if (menuOpen) {
            int row = menuRowAt(event.x(), event.y());
            menuOpen = false;
            if (row >= 0) {
                menuAction(row);
                return true;
            }
            return contains(event.x(), event.y());
        }
        if (!contains(event.x(), event.y())) {
            return false;
        }
        // The menu opens on the use key's mouse button, as remapped, or on the right button.
        Options options = Minecraft.getInstance().options;
        boolean menu = options.keyUse.matchesMouse(event) || event.button() == 1 && !options.keyAttack.matchesMouse(event);
        if (menu) {
            if (body == Body.STANDING && feed == Feed.NONE) {
                openMenu(owner);
            }
            return true;
        }
        if (options.keyAttack.matchesMouse(event) || event.button() == 0) {
            pressed = true;
            pressButton = event.button();
            pressX = event.x();
            pressY = event.y();
            // Caught in mid-air.
            if (body == Body.FLYING || body == Body.SPLAT) {
                startCarry(event.x(), event.y(), now);
            }
            return true;
        }
        return false;
    }

    public static boolean mouseDragged(final MouseButtonEvent event) {
        if (!pressed) {
            return false;
        }
        long now = System.nanoTime();
        if (!carrying && feed == Feed.NONE && Math.hypot(event.x() - pressX, event.y() - pressY) > 3.0) {
            startCarry(event.x(), event.y(), now);
        }
        if (carrying) {
            grabX = event.x();
            grabY = event.y();
            record(now, grabX, grabY);
        }
        return true;
    }

    public static boolean mouseReleased(final MouseButtonEvent event) {
        if (!pressed || event.button() != pressButton) {
            return false;
        }
        pressed = false;
        if (carrying) {
            release(System.nanoTime());
            return true;
        }
        pet();
        return true;
    }

    private static void startCarry(final double x, final double y, final long now) {
        carrying = true;
        menuOpen = false;
        body = Body.CARRIED;
        grabX = x;
        grabY = y;
        trailCount = 0;
        record(now, x, y);
        sound("entity.cat.ambient", 1.5F, 0.26F);
    }

    private static void record(final long now, final double x, final double y) {
        TRAIL[trailHead][0] = now;
        TRAIL[trailHead][1] = x;
        TRAIL[trailHead][2] = y;
        trailHead = (trailHead + 1) % TRAIL.length;
        trailCount = Math.min(TRAIL.length, trailCount + 1);
    }

    /** Let go: a still hand drops it, a quick one throws it with the hand's own speed. */
    private static void release(final long now) {
        carrying = false;
        record(now, grabX, grabY);
        double vx = 0.0;
        double vy = 0.0;
        for (int back = 1; back < trailCount; back++) {
            double[] sample = TRAIL[Math.floorMod(trailHead - 1 - back, TRAIL.length)];
            double age = (now - sample[0]) / (double) SECOND;
            if (age >= 0.06 || back == trailCount - 1) {
                if (age > 0.0) {
                    vx = (grabX - sample[1]) / age;
                    vy = (grabY - sample[2]) / age;
                }
                break;
            }
        }
        // A fresh press that never moved has no speed.
        if ((now - TRAIL[Math.floorMod(trailHead - 2, TRAIL.length)][0]) / (double) SECOND > 0.12) {
            vx = 0.0;
            vy = 0.0;
        }
        float weight = fat() ? 0.55F : 1.0F;
        body = Body.FLYING;
        bounces = 0;
        thrown = Math.hypot(vx, vy) > THROW_SPEED;
        if (thrown) {
            velX = clamp((float) vx * weight, -2600.0F, 2600.0F);
            velY = clamp((float) vy * weight, -2600.0F, 2600.0F);
            sound("entity.player.attack.sweep", 1.5F, 0.22F);
        } else {
            velX *= 0.2F;
            velY = Math.min(velY, 0.0F) * 0.2F;
        }
    }

    // ---- behaviour ---------------------------------------------------------

    private static void update(final long now, final float dt, final Prefs settings, final Screen owner) {
        if (mood != Mood.IDLE && mood != Mood.SLEEP && now > moodUntil) {
            mood = mood == Mood.HURT ? Mood.DIZZY : Mood.IDLE;
            if (mood == Mood.DIZZY) {
                moodStart = now;
                moodUntil = now + SECOND;
            }
        }
        if (mood == Mood.IDLE && feed == Feed.NONE && body == Body.STANDING && now - lastInput > SLEEP_AFTER) {
            mood = Mood.SLEEP;
            moodStart = now;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (now > blinkAt) {
            // One blink in four is slow and soft.
            if (random.nextInt(4) == 0) {
                slowBlinkAt = now;
            } else {
                blinkUntil = now + SECOND * 13 / 100;
            }
            blinkAt = now + SECOND * (25 + random.nextInt(35)) / 10;
        }
        if (now > twitchAt) {
            twitchUntil = now + SECOND * 18 / 100;
            twitchLeft = random.nextBoolean();
            twitchAt = now + SECOND * (settings.playful ? 5 + random.nextInt(7) : 12 + random.nextInt(10));
        }
        boolean still = !settings.playful || hovered || menuOpen || mood != Mood.IDLE || feed != Feed.NONE || play != Play.NONE
            || body != Body.STANDING;
        if (!still && now > nextWalkAt) {
            float range = Math.max(0.0F, (zoneMax - zoneMin) * 0.5F - W * unit * 0.5F);
            wander = (random.nextFloat() * 2.0F - 1.0F) * Math.min(range, 26.0F * unit);
            nextWalkAt = now + SECOND * (7 + random.nextInt(8));
        }
        physics(now, dt, owner);
        feedStep(now);
        if (play == Play.NONE && settings.playful && mood == Mood.IDLE && feed == Feed.NONE && body == Body.STANDING
            && !hovered && !menuOpen && nextPlayAt > 0L && now > nextPlayAt) {
            playAction(now);
        }
        playStep(now);
        if (mood == Mood.SLEEP && now - lastZ > SECOND * 12 / 10) {
            lastZ = now;
            spawn("z", left + (OX + 8) * unit, top + (OY - 1) * unit, 6.0F, -10.0F, 0.0F, now, SECOND * 2, ZTheme.LILAC_PALE);
        }
        if ((mood == Mood.HURT || body == Body.SPLAT) && now - lastTear > SECOND / 4) {
            lastTear = now;
            for (int side = 0; side < 2; side++) {
                spawn("splash_" + random.nextInt(4), pointX(side == 0 ? OX + 1.5F : OX + 6.5F), top + (OY + 7) * unit,
                    (side == 0 ? -1 : 1) * 14.0F, -10.0F, 220.0F, now, SECOND * 7 / 10, 0xFFA8D8FF);
            }
        }
        if (mood == Mood.LOVE && body == Body.STANDING) {
            long beat = (now - moodStart) / (SECOND / 6);
            facingLeft = beat % 2 == 1 && beat < 8;
        }
        PARTICLES.removeIf(particle -> now - particle.born > particle.life);
        for (Particle particle : PARTICLES) {
            particle.vy += particle.gravity * dt;
            particle.x += particle.vx * dt;
            particle.y += particle.vy * dt;
        }
    }

    /** Where it means to stand: its place plus its wandering, inside the free floor. */
    private static float standX(final Screen owner) {
        float half = W * unit * 0.5F;
        float wanted = (float) (prefs().place * owner.width) + wander;
        return clamp(wanted, zoneMin + half, Math.max(zoneMin + half, zoneMax - half));
    }

    private static void physics(final long now, final float dt, final Screen owner) {
        float half = W * unit * 0.5F;
        float height = (FLOOR_ROW + 1) * unit;
        walking = false;
        switch (body) {
            case STANDING -> {
                bodyY = floorY;
                float target = standX(owner);
                if (bodyX < zoneMin + half - 1.0F || bodyX > zoneMax - half + 1.0F) {
                    // Landed on a control: a hop back to free floor.
                    startHop(bodyX, target, now);
                    return;
                }
                float step = (fat() ? 9.0F : 16.0F) * unit * dt;
                if (Math.abs(target - bodyX) > step) {
                    facingLeft = target < bodyX;
                    bodyX += Math.signum(target - bodyX) * step;
                    walking = true;
                } else {
                    bodyX = target;
                }
            }
            case CARRIED -> {
                // A spring to the hand, by the scruff: it lags and swings with speed, more when fat.
                float hang = 16.0F * unit;
                float stiffness = fat() ? 55.0F : 170.0F;
                float damping = fat() ? 8.0F : 18.0F;
                float sag = fat() ? 260.0F : 0.0F;
                int steps = Math.max(1, (int) Math.ceil(dt / 0.008F));
                float h = dt / steps;
                for (int index = 0; index < steps; index++) {
                    float ax = stiffness * ((float) grabX - bodyX) - damping * velX;
                    float ay = stiffness * ((float) grabY + hang - bodyY) - damping * velY + sag;
                    velX += ax * h;
                    velY += ay * h;
                    bodyX += velX * h;
                    bodyY += velY * h;
                }
                tilt = approach(tilt, clamp(((float) grabX - bodyX) * 0.025F, -0.8F, 0.8F), 12.0F, dt);
                if (Math.abs(velX) > 30.0F) {
                    facingLeft = velX < 0.0F;
                }
            }
            case FLYING -> {
                velY += GRAVITY * dt;
                velX *= (float) Math.exp(-0.6 * dt);
                bodyX += velX * dt;
                bodyY += velY * dt;
                tilt = thrown ? tilt + velX * 0.006F * dt : approach(tilt, 0.0F, 6.0F, dt);
                if (bodyX - half < 0.0F && velX < 0.0F) {
                    hitWall(-1, now, half, height);
                } else if (bodyX + half > owner.width && velX > 0.0F) {
                    hitWall(1, now, half, height);
                }
                if (bodyY - height < 0.0F && velY < 0.0F) {
                    hitWall(0, now, half, height);
                }
                if (body == Body.FLYING && bodyY >= floorY && velY > 0.0F) {
                    land(now, owner);
                }
            }
            case SPLAT -> {
                if (now > splatUntil) {
                    // It peels off and slides down.
                    body = Body.FLYING;
                    thrown = false;
                    velX = -splatSide * 60.0F;
                    velY = splatSide == 0 ? 60.0F : 0.0F;
                }
            }
            case HOP -> {
                float progress = (now - hopStart) / (float) (SECOND * 42 / 100);
                if (progress >= 1.0F) {
                    bodyX = hopTo;
                    bodyY = floorY;
                    body = Body.STANDING;
                    squash(1.12F, 0.88F, SECOND / 10, now);
                } else {
                    bodyX = hopFrom + (hopTo - hopFrom) * progress;
                    bodyY = floorY - (float) Math.sin(progress * Math.PI) * (fat() ? 6.0F : 12.0F) * unit;
                }
            }
        }
    }

    private static void startHop(final float from, final float to, final long now) {
        body = Body.HOP;
        hopFrom = from;
        hopTo = to;
        hopStart = now;
        facingLeft = to < from;
        sound("block.wool.fall", 1.6F, 0.25F);
    }

    /** An edge of the window: -1 left, 1 right, 0 the top. Hard enough, it sticks there for a moment. */
    private static void hitWall(final int side, final long now, final float half, final float height) {
        float speed = side == 0 ? Math.abs(velY) : Math.abs(velX);
        float impactX = side < 0 ? 0.0F : side > 0 ? screenWidth : bodyX;
        float impactY = side == 0 ? 0.0F : bodyY - height * 0.5F;
        if (side < 0) {
            bodyX = half;
        } else if (side > 0) {
            bodyX = screenWidth - half;
        } else {
            bodyY = height;
        }
        if (speed > SPLAT_SPEED) {
            body = Body.SPLAT;
            splatSide = side;
            splatUntil = now + SECOND * 8 / 10;
            velX = 0.0F;
            velY = 0.0F;
            tilt = 0.0F;
            react(Mood.HURT);
            spawn("flash", impactX, impactY, 0.0F, 0.0F, 0.0F, now, SECOND / 4, 0xFFFFFFFF);
            for (int index = 0; index < 4; index++) {
                float spread = (index - 1.5F) * 9.0F * unit;
                spawn("critical_hit", impactX - side * 4.0F * unit, impactY + (side == 0 ? 0.0F : spread),
                    -side * 40.0F + (side == 0 ? spread * 2.0F : 0.0F), side == 0 ? 50.0F : spread, 0.0F, now, SECOND * 7 / 10, 0xFFFFE680);
            }
            spawn("damage", bodyX, bodyY - height, 0.0F, -26.0F, 0.0F, now, SECOND, 0xFFFFFFFF);
            sound("entity.slime.squish_small", 0.8F, 0.55F);
            sound("entity.cat.hurt", 1.2F, 0.32F);
        } else {
            if (side == 0) {
                velY = -velY * 0.45F;
            } else {
                velX = -velX * 0.45F;
                velY *= 0.8F;
            }
            if (speed > 120.0F) {
                spawn("critical_hit", impactX, impactY, -side * 30.0F, 0.0F, 0.0F, now, SECOND / 2, 0xFFFFE680);
                squash(side == 0 ? 1.15F : 0.82F, side == 0 ? 0.82F : 1.15F, SECOND * 12 / 100, now);
                sound("block.wool.fall", 1.3F, 0.45F);
            }
        }
    }

    private static void land(final long now, final Screen owner) {
        float impact = velY;
        bodyY = floorY;
        tilt = 0.0F;
        boolean heavy = fat();
        if (impact > 900.0F || heavy && impact > 200.0F) {
            squash(1.35F, 0.6F, SECOND * 22 / 100, now);
            poof(6, now);
            sound(heavy ? "entity.player.big_fall" : "entity.player.small_fall", heavy ? 0.7F : 1.2F, 0.5F);
            if (impact > 1400.0F && mood != Mood.HURT) {
                react(Mood.DIZZY);
            }
        } else if (impact > 250.0F) {
            squash(1.18F, 0.82F, SECOND * 14 / 100, now);
            poof(3, now);
            sound("block.wool.fall", 1.2F, 0.5F);
        }
        if (impact > 520.0F && bounces < 1 && !heavy) {
            bounces++;
            velY = -impact * 0.28F;
            bodyY = floorY - 1.0F;
            return;
        }
        body = Body.STANDING;
        velX = 0.0F;
        velY = 0.0F;
        bounces = 0;
        wander = 0.0F;
        if (owner.width > 0) {
            prefs().place = clamp(bodyX / owner.width, 0.0F, 1.0F);
            save();
        }
    }

    private static void squash(final float sx, final float sy, final long length, final long now) {
        squashX = sx;
        squashY = sy;
        squashStart = now;
        squashLength = length;
    }

    private static void poof(final int count, final long now) {
        for (int index = 0; index < count; index++) {
            float spread = index - (count - 1) * 0.5F;
            spawn("poof", bodyX + spread * 3.0F * unit, floorY - unit, spread * 9.0F, -8.0F, 0.0F, now, SECOND / 2, 0xFFDDD6E8);
        }
    }

    private static void pet() {
        long now = System.nanoTime();
        if (body != Body.STANDING) {
            return;
        }
        sound("entity.cat.purr", 1.0F + ThreadLocalRandom.current().nextFloat() * 0.3F, 0.4F);
        // In love, more petting keeps it there, a heart at a time.
        if (mood == Mood.LOVE && now < moodUntil) {
            moodUntil = now + SECOND * 2;
            lastInput = now;
            spawn("heart", left + (OX + 4) * unit, top + (OY - 1) * unit, 0.0F, -18.0F, 0.0F, now, SECOND * 13 / 10, WHITE);
            return;
        }
        petStreak = now - lastPetAt < SECOND * 3 ? petStreak + 1 : 1;
        lastPetAt = now;
        boolean love = petStreak >= 4 && prefs().playful;
        react(love ? Mood.LOVE : Mood.PET);
        if (love) {
            petStreak = 0;
            sound("entity.cat.purreow", 1.1F, 0.38F);
        }
    }

    /** Particles that come with a reaction. */
    private static void burst(final Mood next, final long now) {
        float headX = left + (OX + 4) * unit;
        float headY = top + OY * unit;
        boolean lively = prefs().playful;
        switch (next) {
            case HAPPY -> {
                for (int index = 0; index < (lively ? 4 : 2); index++) {
                    spawn("glitter", headX + (index - 1.5F) * 5 * unit, headY, (index - 1.5F) * 5.0F, -14.0F - index * 3, 0.0F, now, SECOND * 9 / 10, ZTheme.VIOLET_BRIGHT);
                }
            }
            case PET, LOVE -> {
                int hearts = next == Mood.LOVE ? 6 : 2;
                for (int index = 0; index < hearts; index++) {
                    spawn("heart", headX + (index - hearts / 2.0F) * 4 * unit, headY - unit, (index - hearts / 2.0F) * 4.0F, -16.0F - index * 2, 0.0F, now, SECOND * 13 / 10, WHITE);
                }
                spawn("note", headX + 6 * unit, headY + 2 * unit, 10.0F, -12.0F, 0.0F, now, SECOND, 0xFFFF9AD5);
            }
            case ALERT -> spawn("!", headX, headY - 3 * unit, 0.0F, -4.0F, 0.0F, now, SECOND * 12 / 10, ZTheme.CRIMSON_BRIGHT);
            case SURPRISED -> spawn("?", headX + 5 * unit, headY - 2 * unit, 2.0F, -6.0F, 0.0F, now, SECOND, ZTheme.TEXT);
            case WAVE -> spawn("note", headX + 6 * unit, headY, 8.0F, -12.0F, 0.0F, now, SECOND, ZTheme.LILAC);
            case DIZZY -> {
                for (int index = 0; index < 3; index++) {
                    spawn("critical_hit", headX + (index - 1) * 6 * unit, headY - 2 * unit, (index - 1) * 6.0F, -3.0F, 0.0F, now, SECOND * 15 / 10, 0xFFFFE680);
                }
            }
            case DISGUST -> {
                // Yuck: two green puffs from its mouth.
                for (int index = 0; index < 2; index++) {
                    spawn("poof", headX + (index == 0 ? -2 : 3) * unit, top + (OY + 8) * unit, (index == 0 ? -10 : 10), -12.0F, 0.0F, now, SECOND * 8 / 10, 0xFF9CCB6B);
                }
            }
            default -> {
            }
        }
    }

    private static void spawn(final String kind, final float x, final float y, final float vx, final float vy, final float gravity, final long now, final long life, final int color) {
        if (PARTICLES.size() >= 90) {
            PARTICLES.remove(0);
        }
        PARTICLES.add(new Particle(kind, x, y, vx, vy, gravity, now, life, color));
    }

    private static int hop(final long now) {
        if (mood == Mood.IDLE || mood == Mood.SLEEP || mood == Mood.MEH || mood == Mood.ALERT || mood == Mood.HURT
            || mood == Mood.DISGUST || feed != Feed.NONE) {
            return 0;
        }
        float progress = (now - moodStart) / (float) (SECOND * 35 / 100);
        if (mood == Mood.LOVE || mood == Mood.HAPPY && prefs().playful) {
            progress %= 1.0F;
        }
        if (progress >= 1.0F) {
            return 0;
        }
        return Math.round((float) Math.sin(progress * Math.PI) * (mood == Mood.SURPRISED ? 2 : fat() ? 1 : 3));
    }

    /** Idle and standing still, it turns to face the pointer. */
    private static void look(final int mouseX) {
        if (body != Body.STANDING || walking || mood != Mood.IDLE || feed != Feed.NONE) {
            return;
        }
        int center = left + W * unit / 2;
        if (mouseX < center - 16 * unit) {
            facingLeft = true;
        } else if (mouseX > center + 16 * unit) {
            facingLeft = false;
        }
    }

    /** The eyes of a slow blink: half shut, shut, half shut, over two thirds of a second. */
    private static @Nullable Eyes slowBlink(final long now) {
        long elapsed = now - slowBlinkAt;
        if (slowBlinkAt == 0L || elapsed < 0L || elapsed > SECOND * 68 / 100) {
            return null;
        }
        return elapsed < SECOND * 12 / 100 || elapsed > SECOND * 52 / 100 ? Eyes.HALF : Eyes.CLOSED;
    }

    // ---- its meal -----------------------------------------------------------

    public static boolean fat() {
        return eatenTotems >= FAT_AT;
    }

    /** The anchor the settings preview shows right now: one, or none while it is away or not drawn. */
    private static int totemsCarried() {
        return previewShownCount > 0 && System.nanoTime() - previewShownAt < SECOND / 4 ? 1 : 0;
    }

    /** She eats only anchors: the one the settings preview shows, pulled out by magic; it comes back after. */
    private static void feedAction(final long now) {
        if (body != Body.STANDING || feed != Feed.NONE || play != Play.NONE) {
            return;
        }
        if (now < anchorGoneUntil) {
            react(Mood.HAPPY);
            tip("kohs_anchors.mascot.tip.all_eaten", now);
            sound("entity.player.burp", 1.2F, 0.25F);
            return;
        }
        if (totemsCarried() == 0) {
            react(Mood.DISGUST);
            tip("kohs_anchors.mascot.tip.only_anchors", now);
            sound("entity.cat.hiss", 1.25F, 0.22F);
            return;
        }
        sourceX = PREVIEW_SHOWN[0];
        sourceY = PREVIEW_SHOWN[1];
        sourceSize = PREVIEW_SHOWN[2];
        // From this moment the preview shows it as gone, until it is made again.
        anchorGoneUntil = now + MEAL_AWAY;
        eatenTotems++;
        lastMealAt = now;
        lastInput = now;
        feed = Feed.CAST;
        feedStart = now;
        bitesTaken = 0;
        mood = Mood.IDLE;
        facingLeft = sourceX < bodyX;
        sound("block.enchantment_table.use", 1.3F, 0.4F);
    }

    /** One of her own anchors goes down beside her; she charges it and backs off before it blows. */
    private static void playAction(final long now) {
        if (body != Body.STANDING || feed != Feed.NONE || play != Play.NONE) {
            return;
        }
        float side = facingLeft ? -1.0F : 1.0F;
        float room = 12.0F * unit;
        playX = bodyX + side * room;
        if (playX < zoneMin + 5.0F * unit || playX > zoneMax - 5.0F * unit) {
            playX = bodyX - side * room;
        }
        facingLeft = playX < bodyX;
        play = Play.DROP;
        playStart = now;
        lastInput = now;
        mood = Mood.IDLE;
        sound("block.stone.place", 0.9F, 0.35F);
    }

    private static void playStep(final long now) {
        if (play == Play.NONE) {
            return;
        }
        long elapsed = now - playStart;
        switch (play) {
            case DROP -> {
                if (elapsed > SECOND * 35 / 100) {
                    play = Play.CHARGE;
                    playStart = now;
                    sound("block.respawn_anchor.charge", 1.0F, 0.4F);
                }
            }
            case CHARGE -> {
                if (elapsed > SECOND * 55 / 100) {
                    play = Play.WAIT;
                    playStart = now;
                    // She knows what comes next: a quick step back.
                    react(Mood.ZOOM);
                    wander += (playX < bodyX ? 9.0F : -9.0F) * unit;
                }
            }
            case WAIT -> {
                if (elapsed > SECOND * 55 / 100) {
                    play = Play.BOOM;
                    playStart = now;
                    sound("entity.generic.explode", 1.45F, 0.16F);
                    poof(4, now);
                    if (Math.abs(playX - bodyX) < 13.0F * unit) {
                        squash(1.2F, 0.8F, SECOND / 5, now);
                        react(Mood.SURPRISED);
                    }
                }
            }
            case BOOM -> {
                if (elapsed > SECOND * 6 / 10) {
                    play = Play.NONE;
                    react(Mood.HAPPY);
                    nextPlayAt = now + SECOND * (25 + ThreadLocalRandom.current().nextInt(20));
                }
            }
            default -> {
            }
        }
    }

    /** Her anchor: dropping, charged with a stream of glowstone, glowing, then the blast. */
    private static void drawPlay(final GuiGraphicsExtractor graphics, final long now) {
        if (play == Play.NONE) {
            return;
        }
        long elapsed = now - playStart;
        float size = 10.0F * unit;
        float rest = floorY - size * 0.5F;
        switch (play) {
            case DROP -> {
                float fall = clamp(elapsed / (float) (SECOND * 35 / 100), 0.0F, 1.0F);
                float from = top + (OY + 3.0F) * unit;
                drawAnchorBlock(graphics, playX, from + (rest - from) * fall * fall, size, false);
            }
            case CHARGE -> {
                float flow = clamp(elapsed / (float) (SECOND * 55 / 100), 0.0F, 1.0F);
                float handX = pointX(OX + 4.0F);
                float handY = top + (OY - 3.0F) * unit;
                int dot = Math.max(1, unit);
                for (int bead = 0; bead < 7; bead++) {
                    float along = clamp(flow * 1.5F - bead * 0.07F, 0.0F, 1.0F);
                    if (along <= 0.0F || along >= 1.0F) {
                        continue;
                    }
                    float x = handX + (playX - handX) * along;
                    float y = handY + (rest - handY) * along - (float) Math.sin(along * Math.PI) * 9.0F * unit;
                    graphics.fill(Math.round(x), Math.round(y), Math.round(x) + dot, Math.round(y) + dot, 0xFFFFD86B);
                }
                drawAnchorBlock(graphics, playX, rest, size, flow > 0.85F);
            }
            case WAIT -> {
                float pulse = 0.5F + 0.5F * (float) Math.sin(elapsed / (float) SECOND * 28.0F);
                ZScene.sprite(graphics, "glow", playX, rest, size * (1.7F + 0.5F * pulse), 8, ZTheme.fade(TOTEM_GOLD, 0.45F + 0.35F * pulse));
                drawAnchorBlock(graphics, playX, rest, size, true);
            }
            case BOOM -> {
                float blast = clamp(elapsed / (float) (SECOND * 6 / 10), 0.0F, 1.0F);
                int frame = Math.min(15, (int) (blast * 16.0F));
                ZScene.sprite(graphics, "glow", playX, rest, size * (2.0F + 3.0F * blast), 8, ZTheme.fade(TOTEM_GOLD, 0.6F * (1.0F - blast)));
                ZScene.sprite(graphics, "explosion_" + frame, playX, rest - size * 0.4F, size * 3.2F, 16, 0xFFFFFFFF);
            }
            default -> {
            }
        }
    }

    /** A small respawn anchor, its side: dark, or lit and glowing. */
    private static void drawAnchorBlock(final GuiGraphicsExtractor graphics, final float cx, final float cy, final float size, final boolean charged) {
        if (charged) {
            ZScene.sprite(graphics, "glow", cx, cy, size * 1.6F, 8, ZTheme.fade(TOTEM_GOLD, 0.4F));
        }
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(cx - size * 0.5F, cy - size * 0.5F);
        pose.scale(size / 16.0F, size / 16.0F);
        graphics.blit(RenderPipelines.GUI_TEXTURED, charged ? ANCHOR_ON : ANCHOR_OFF, 0, 0, 0.0F, 0.0F, 16, 16, 16, 16, 16, 16, 0xFFFFFFFF);
        pose.popMatrix();
    }

    /**
     * The cat standing, {@code scale} screen pixels to a skin pixel, centred on {@code centerX} with
     * its feet on {@code footY}: for the scenes of other windows, as the safe anchor note's player.
     */
    public static void drawFigure(final GuiGraphicsExtractor graphics, final float centerX, final float footY, final int scale, final boolean faceLeft) {
        Mood savedMood = mood;
        Body savedBody = body;
        Feed savedFeed = feed;
        boolean savedFacing = facingLeft;
        int savedUnit = unit;
        int savedLeft = left;
        int savedTop = top;
        float savedX = bodyX;
        float savedTilt = tilt;
        long savedSquash = squashLength;
        try {
            mood = Mood.IDLE;
            body = Body.STANDING;
            feed = Feed.NONE;
            facingLeft = faceLeft;
            unit = Math.max(1, scale);
            bodyX = centerX;
            tilt = 0.0F;
            squashLength = 0L;
            left = Math.round(centerX) - W * unit / 2;
            top = Math.round(footY) - (FLOOR_ROW + 1) * unit;
            long now = System.nanoTime();
            compose(now);
            paintBody(graphics, now);
        } finally {
            mood = savedMood;
            body = savedBody;
            feed = savedFeed;
            facingLeft = savedFacing;
            unit = savedUnit;
            left = savedLeft;
            top = savedTop;
            bodyX = savedX;
            tilt = savedTilt;
            squashLength = savedSquash;
        }
    }

    private static void feedStep(final long now) {
        if (feed == Feed.NONE) {
            return;
        }
        long elapsed = now - feedStart;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (feed == Feed.CAST && elapsed > FLY_AT) {
            feed = Feed.FLY;
            sound("block.amethyst_block.chime", 1.4F, 0.6F);
        }
        if (feed == Feed.CAST || feed == Feed.FLY) {
            // Glyphs of the enchanting table rise from its circle.
            if (random.nextInt(3) == 0) {
                double angle = random.nextDouble() * TAU;
                spawn("sga_" + (char) ('a' + random.nextInt(26)), bodyX + (float) Math.cos(angle) * 11.0F * unit,
                    bodyY + unit + (float) Math.sin(angle) * 3.5F * unit, 0.0F, -22.0F * unit / 2.0F, 0.0F, now, SECOND * 9 / 10, ZTheme.LILAC);
            }
        }
        if (feed == Feed.FLY) {
            float[] at = flightPoint(now);
            spawn("glitter", at[0] + (random.nextFloat() - 0.5F) * 4.0F, at[1] + (random.nextFloat() - 0.5F) * 4.0F,
                0.0F, 6.0F, 0.0F, now, SECOND / 2, random.nextBoolean() ? TOTEM_GOLD : TOTEM_GREEN);
            if (elapsed > EAT_AT) {
                feed = Feed.EAT;
                sound("entity.item.pickup", 1.2F, 0.5F);
            }
        }
        if (feed == Feed.EAT) {
            long eating = elapsed - EAT_AT;
            while (bitesTaken < BITES.length && eating > BITES[bitesTaken]) {
                bitesTaken++;
                sound("entity.generic.eat", 0.9F + random.nextFloat() * 0.3F, 0.45F);
                float[] mouth = mouthPoint();
                for (int index = 0; index < 5; index++) {
                    spawn("crumb", mouth[0], mouth[1] + 2.0F * unit, (random.nextFloat() - 0.5F) * 60.0F, -30.0F - random.nextFloat() * 30.0F,
                        260.0F, now, SECOND * 7 / 10, random.nextBoolean() ? TOTEM_GOLD : TOTEM_GREEN);
                }
            }
            if (elapsed > DONE_AT) {
                feed = Feed.DONE;
                sound("entity.player.burp", 1.0F, 0.4F);
                sound("item.totem.use", 1.6F, 0.12F);
                float[] center = {bodyX, top + 9.0F * unit};
                for (int index = 0; index < 14; index++) {
                    double angle = index * TAU / 14.0;
                    spawn("glitter", center[0], center[1], (float) Math.cos(angle) * 70.0F, (float) Math.sin(angle) * 70.0F - 20.0F,
                        40.0F, now, SECOND * 9 / 10, index % 2 == 0 ? TOTEM_GOLD : TOTEM_GREEN);
                }
                for (int index = 0; index < 3; index++) {
                    spawn("goldheart_" + index, center[0] + (index - 1) * 6.0F * unit, top + 2.0F * unit, (index - 1) * 6.0F, -20.0F, 0.0F,
                        now, SECOND * 14 / 10, WHITE);
                }
            }
        }
        if (feed == Feed.DONE && elapsed > END_AT) {
            feed = Feed.NONE;
            if (eatenTotems == FAT_AT) {
                // Five: it grows round, all at once.
                squash(1.3F, 0.8F, SECOND * 3 / 10, now);
                poof(6, now);
                sound("entity.slime.squish_small", 0.6F, 0.6F);
                tip("kohs_anchors.mascot.tip.fat", now);
                react(Mood.SURPRISED);
            } else {
                react(Mood.LOVE);
                sound("entity.cat.purreow", 1.0F, 0.35F);
            }
        }
    }

    /** After ten minutes without a meal it has digested everything: slim again, and the preview's totems back. */
    private static void digest(final long now) {
        if (eatenTotems > 0 && feed == Feed.NONE && now - lastMealAt > DIGEST) {
            boolean wasFat = fat();
            eatenTotems = 0;
            if (wasFat && visible) {
                squash(0.85F, 1.15F, SECOND / 4, now);
                poof(5, now);
                react(Mood.HAPPY);
            }
        }
    }

    private static void tip(final String key, final long now) {
        tipKey = key;
        tipStart = now;
        tipUntil = now + SECOND * 32 / 10;
    }

    /** A point of the skin canvas on screen, minding which way it faces. */
    private static float pointX(final float column) {
        return left + (facingLeft ? column : W - column) * unit;
    }

    private static float[] mouthPoint() {
        return new float[] {pointX(OX + 4.0F), top + (OY + 7.5F) * unit};
    }

    /** The totem in flight: a rising arc from its slot to the raised paws, eased. */
    private static float[] flightPoint(final long now) {
        float progress = clamp((now - feedStart - FLY_AT) / (float) (EAT_AT - FLY_AT), 0.0F, 1.0F);
        float eased = progress * progress * (3.0F - 2.0F * progress);
        float endX = pointX(OX + 4.0F);
        float endY = top + (OY - 3.0F) * unit;
        float midX = (sourceX + endX) * 0.5F;
        float midY = Math.min(sourceY, endY) - 30.0F * unit / 2.0F;
        float a = 1.0F - eased;
        return new float[] {
            a * a * sourceX + 2.0F * a * eased * midX + eased * eased * endX,
            a * a * sourceY + 2.0F * a * eased * midY + eased * eased * endY,
            progress
        };
    }

    private static void drawMeal(final GuiGraphicsExtractor graphics, final long now) {
        if (feed == Feed.NONE) {
            return;
        }
        long elapsed = now - feedStart;
        float held = 7.0F * unit;
        if (feed == Feed.CAST) {
            // The totem shines in its slot before it lifts.
            float glow = elapsed / (float) FLY_AT;
            float size = sourceSize > 0.0F ? sourceSize : held;
            ZScene.sprite(graphics, "glow", sourceX, sourceY, size * (1.2F + 0.6F * glow), 8, ZTheme.fade(TOTEM_GOLD, 0.6F * glow));
            if (sourceSize == 0.0F) {
                drawAnchorIcon(graphics, sourceX, sourceY, held * glow, 0.0F);
            }
        } else if (feed == Feed.FLY) {
            float[] at = flightPoint(now);
            float from = sourceSize > 0.0F ? sourceSize : held;
            float size = from + (held - from) * at[2];
            float spin = (float) Math.sin(at[2] * Math.PI * 3.0) * 0.5F;
            // A beam of little lights from its paws to the totem.
            float handX = pointX(OX + 4.0F);
            float handY = top + (OY - 3.0F) * unit;
            float march = (now % (SECOND / 2)) / (float) (SECOND / 2);
            for (int dot = 0; dot < 12; dot++) {
                float f = (dot + march) / 12.0F;
                int px = Math.round(handX + (at[0] - handX) * f);
                int py = Math.round(handY + (at[1] - handY) * f);
                graphics.fill(px, py, px + Math.max(1, unit / 2), py + Math.max(1, unit / 2), ZTheme.fade(ZTheme.LILAC_PALE, 0.35F + 0.5F * f));
            }
            ZScene.sprite(graphics, "glow", at[0], at[1], size * 1.6F, 8, ZTheme.fade(TOTEM_GOLD, 0.55F));
            drawAnchorIcon(graphics, at[0], at[1], size, spin);
        } else if (feed == Feed.EAT) {
            // Held to its mouth, smaller with every bite.
            float left3 = 1.0F - bitesTaken / (float) BITES.length;
            if (left3 > 0.0F) {
                float[] mouth = mouthPoint();
                float nibble = (now / (SECOND / 8)) % 2 == 0 ? 0.0F : unit * 0.5F;
                drawAnchorIcon(graphics, mouth[0], mouth[1] + nibble, held * (0.45F + 0.55F * left3), 0.0F);
            }
        }
    }

    /** A violet circle under its feet while it casts, two rings turning against each other. */
    private static void magicCircle(final GuiGraphicsExtractor graphics, final long now) {
        float seconds = (now % (3600 * SECOND)) / (float) SECOND;
        float appear = clamp((now - feedStart) / (float) (SECOND / 4), 0.0F, 1.0F);
        float cx = bodyX;
        float cy = bodyY + unit;
        int dot = Math.max(1, unit / 2);
        for (int ring = 0; ring < 2; ring++) {
            float rx = (ring == 0 ? 11.0F : 7.0F) * unit * appear;
            float ry = (ring == 0 ? 3.5F : 2.2F) * unit * appear;
            int points = ring == 0 ? 30 : 20;
            float turn = seconds * (ring == 0 ? 1.6F : -2.4F);
            for (int index = 0; index < points; index++) {
                double angle = turn + index * TAU / points;
                int px = Math.round(cx + (float) Math.cos(angle) * rx);
                int py = Math.round(cy + (float) Math.sin(angle) * ry);
                int color = index % 5 == 0 ? ZTheme.LILAC_PALE : ZTheme.fade(ZTheme.VIOLET_BRIGHT, 0.8F);
                graphics.fill(px, py, px + dot, py + dot, color);
            }
        }
        ZScene.sprite(graphics, "glow", cx, cy - unit, 18.0F * unit * appear, 8, ZTheme.fade(ZTheme.VIOLET, 0.35F));
    }

    /** The anchor she eats, as an icon: its lit side, turned by {@code angle}. */
    private static void drawAnchorIcon(final GuiGraphicsExtractor graphics, final float cx, final float cy, final float size, final float angle) {
        if (size < 1.0F) {
            return;
        }
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(cx, cy);
        if (angle != 0.0F) {
            pose.rotate(angle);
        }
        pose.scale(size / 16.0F, size / 16.0F);
        graphics.blit(RenderPipelines.GUI_TEXTURED, ANCHOR_ON, -8, -8, 0.0F, 0.0F, 16, 16, 16, 16, 16, 16, 0xFFFFFFFF);
        pose.popMatrix();
    }

    private static void drawTip(final GuiGraphicsExtractor graphics, final Screen owner, final long now) {
        if (tipKey == null || now > tipUntil) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        List<FormattedCharSequence> lines = font.split(Component.translatable(tipKey), 150);
        int width = 0;
        for (FormattedCharSequence line : lines) {
            width = Math.max(width, font.width(line));
        }
        width += 12;
        int height = lines.size() * 10 + 8;
        float rise = clamp((now - tipStart) / (float) (SECOND / 6), 0.0F, 1.0F);
        int x = Math.max(4, Math.min(owner.width - width - 4, Math.round(bodyX) - width / 2));
        int y = Math.max(4, top - height - 4 - Math.round(4 * rise));
        AnchorsUi.panel(graphics, x, y, width, height, ZTheme.SURFACE, ZTheme.SURFACE);
        AnchorsUi.roundedOutline(graphics, x, y, width, height, ZTheme.BORDER);
        int tail = Math.max(x + 6, Math.min(x + width - 8, Math.round(bodyX)));
        for (int row = 0; row < 3; row++) {
            graphics.fill(tail - 2 + row, y + height + row, tail + 3 - row, y + height + row + 1, ZTheme.BORDER);
        }
        for (int index = 0; index < lines.size(); index++) {
            graphics.text(font, lines.get(index), x + 6, y + 5 + index * 10, ZTheme.TEXT, false);
        }
    }

    private static void sound(final String id, final float pitch, final float volume) {
        if (!prefs().sounds) {
            return;
        }
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
            SoundEvent.createVariableRangeEvent(Identifier.withDefaultNamespace(id)), pitch, volume));
    }

    // ---- the pixels ----------------------------------------------------------

    private static void compose(final long now) {
        Arrays.fill(CANVAS, 0);
        float seconds = (now % (3600 * SECOND)) / (float) SECOND;
        boolean standing = body == Body.STANDING;
        boolean fat = fat();
        int bob = standing && (mood == Mood.SLEEP ? (seconds % 2.6F) > 1.3F : (seconds % 1.6F) > 1.0F) ? 1 : 0;
        int step = 0;
        if (body == Body.STANDING && (walking || mood == Mood.ZOOM)) {
            step = ((now / (mood == Mood.ZOOM ? SECOND / 14 : fat ? SECOND / 5 : SECOND / 7)) % 2 == 0) ? 1 : -1;
        }
        Eyes eyes;
        Ears ears;
        boolean blush = false;
        int mouth = 0;
        boolean tongue = false;
        boolean tailUp = false;
        // Seconds per sway of the tail; 0 holds it still at hold. A happy cat carries
        // its tail up and sways it slowly; only an upset one lashes it.
        float wag = 2.6F;
        int hold = 0;
        int arm = 0;
        int shake = 0;
        switch (mood) {
            case HAPPY -> { eyes = Eyes.HAPPY; ears = Ears.PERK; blush = true; mouth = 1; tailUp = true; wag = 1.4F; }
            case MEH -> { eyes = Eyes.HALF; ears = Ears.DOWN; wag = 0.0F; }
            case ALERT -> { eyes = Eyes.WIDE; ears = Ears.PERK; tailUp = true; wag = 0.0F; hold = 1; }
            case SURPRISED -> { eyes = Eyes.WIDE; ears = Ears.PERK; mouth = 2; tailUp = true; wag = 0.0F; }
            case PET -> { eyes = Eyes.HAPPY; ears = Ears.UP; blush = true; mouth = 1; tailUp = true; wag = 1.8F; }
            case LOVE -> { eyes = Eyes.LOVE; ears = Ears.PERK; blush = true; mouth = 1; tailUp = true; wag = 1.2F; arm = 2; }
            case WAVE -> {
                eyes = Eyes.HAPPY;
                ears = Ears.PERK;
                blush = true;
                tailUp = true;
                wag = 1.4F;
                arm = (now / (SECOND / 5)) % 2 == 0 ? 1 : 3;
            }
            case ZOOM -> { eyes = Eyes.OPEN; ears = Ears.DOWN; wag = 0.0F; hold = -1; }
            case DIZZY -> { eyes = Eyes.DIZZY; ears = Ears.DOWN; wag = 0.7F; }
            case SLEEP -> { eyes = Eyes.CLOSED; ears = Ears.DOWN; wag = 0.0F; }
            case HURT -> { eyes = Eyes.SQUEEZE; ears = Ears.DOWN; mouth = 2; wag = 0.0F; }
            case DISGUST -> {
                eyes = Eyes.SQUEEZE;
                ears = Ears.DOWN;
                mouth = 1;
                tongue = true;
                wag = 0.0F;
                hold = -1;
                // It shakes its head, fast, for the first moment.
                if (now - moodStart < SECOND * 9 / 10) {
                    shake = (now / (SECOND / 11)) % 2 == 0 ? -1 : 1;
                }
            }
            default -> {
                Eyes slow = slowBlink(now);
                eyes = slow != null ? slow : now < blinkUntil ? Eyes.CLOSED
                    : now - lastInput > DROWSY_AFTER ? Eyes.HALF : Eyes.OPEN;
                ears = hovered ? Ears.PERK : Ears.UP;
                if (hovered) {
                    blush = true;
                    tailUp = true;
                    wag = 2.0F;
                }
            }
        }
        switch (body) {
            case CARRIED -> {
                boolean fast = Math.hypot(velX, velY) > 700.0;
                eyes = fast ? Eyes.SQUEEZE : Eyes.WIDE;
                ears = fast ? Ears.DOWN : Ears.PERK;
                mouth = fast ? 2 : 0;
                arm = 2;
                tailUp = false;
                wag = 0.0F;
                hold = 0;
            }
            case FLYING, HOP -> {
                eyes = body == Body.HOP ? Eyes.HAPPY : thrown ? Eyes.SQUEEZE : Eyes.WIDE;
                ears = Ears.PERK;
                mouth = body == Body.HOP ? 1 : 2;
                arm = 2;
                tailUp = true;
                wag = 0.0F;
            }
            case SPLAT -> {
                eyes = Eyes.SQUEEZE;
                ears = Ears.DOWN;
                mouth = 2;
                arm = 2;
                wag = 0.0F;
            }
            default -> {
            }
        }
        switch (feed) {
            case CAST, FLY -> { eyes = Eyes.WIDE; ears = Ears.PERK; arm = 2; blush = true; tailUp = true; wag = 1.2F; }
            case EAT -> {
                eyes = Eyes.HAPPY;
                ears = Ears.UP;
                arm = 5;
                blush = true;
                tailUp = true;
                wag = 1.6F;
                mouth = (now / (SECOND / 8)) % 2 == 0 ? 2 : 1;
            }
            case DONE -> {
                long done = now - feedStart - DONE_AT;
                eyes = done < SECOND * 4 / 10 ? Eyes.HAPPY : Eyes.LOVE;
                ears = Ears.PERK;
                blush = true;
                tailUp = true;
                wag = 1.2F;
                mouth = done < SECOND * 35 / 100 ? 2 : 1;
            }
            default -> {
            }
        }
        int sway = wag <= 0.0F ? hold : Math.round((float) Math.sin(seconds * TAU / wag));
        int twitch = now < twitchUntil && mood == Mood.IDLE ? (twitchLeft ? -1 : 1) : 0;
        int wide = fat ? 1 : 0;
        // Tail first: it is behind the body.
        int tailLength = tailUp ? TAIL_PATH.length : TAIL_PATH.length - 2;
        for (int index = 0; index < tailLength; index++) {
            int x = OX + TAIL_PATH[index][0] + (index >= 4 ? sway : 0) + wide;
            put(x, TAIL_PATH[index][1] + bob, index >= tailLength - 2 ? TAIL_TIP : TAIL);
        }
        // Paws stay on the floor, one lifted while walking; they hang when carried, spread when fat.
        for (int j = 0; j < 3; j++) {
            int liftLeft = step > 0 ? 1 : 0;
            int liftRight = step < 0 ? 1 : 0;
            int hang = body == Body.CARRIED ? 1 : 0;
            put(OX + 1 + j - hang - wide, 16 - liftLeft + hang, MascotSkin.LEG[9 * 4 + j]);
            put(OX + 1 + j - hang - wide, 17 - liftLeft + hang, MascotSkin.LEG[10 * 4 + j]);
            put(OX + 4 + j + hang + wide, 16 - liftRight + hang, MascotSkin.LEG[9 * 4 + j]);
            put(OX + 4 + j + hang + wide, 17 - liftRight + hang, MascotSkin.LEG[10 * 4 + j]);
        }
        // Body: six of its eight columns, or all eight and a round belly when fat.
        for (int i = 0; i < BODY_ROWS.length; i++) {
            for (int x = 1 - wide; x < 7 + wide; x++) {
                put(OX + x, 11 + i + bob, MascotSkin.BODY[BODY_ROWS[i] * 8 + x]);
            }
            if (fat && i >= 1 && i <= 3) {
                // A round belly that shows past its arms.
                put(OX - 1, 11 + i + bob, BODY_EDGE);
                put(OX + 8, 11 + i + bob, BODY_EDGE);
                if (i == 2) {
                    put(OX - 2, 11 + i + bob, BODY_EDGE);
                    put(OX + 9, 11 + i + bob, BODY_EDGE);
                }
            }
        }
        // Arms, one pixel wide, a purple sleeve ending in a white paw.
        for (int i = 0; i < ARM_ROWS.length; i++) {
            int color = MascotSkin.ARM[ARM_ROWS[i] * 3 + 1];
            if (arm == 2) {
                put(OX - 1 - wide, 10 - i + bob, color);
            } else if (arm != 5) {
                // Hanging arms stay on the body's sides, so a fat belly rounds out past them.
                put(OX, 11 + i + bob, color);
            }
            if (arm == 0) {
                put(OX + 7, 11 + i + bob, color);
            }
        }
        head(eyes, ears, twitch, blush || fat, mouth, tongue, fat || feed == Feed.EAT, bob, shake, now);
        // Raised arms and the arms that hold its food go over the head, paws on top.
        for (int i = 0; i < ARM_ROWS.length; i++) {
            int color = MascotSkin.ARM[ARM_ROWS[i] * 3 + 1];
            if (arm == 1 || arm == 2) {
                put(OX + 8 + wide + (i >= 2 ? 1 : 0), 10 - i + bob, color);
            } else if (arm == 3) {
                put(OX + 8 + wide + i, 10 - i / 2 + bob, color);
            }
        }
        if (arm == 5) {
            int sleeve = MascotSkin.ARM[1];
            int paw = MascotSkin.ARM[9 * 3 + 1];
            put(OX - wide, 12 + bob, sleeve);
            put(OX + 1, 11 + bob, sleeve);
            put(OX + 2, 10 + bob, paw);
            put(OX + 7 + wide, 12 + bob, sleeve);
            put(OX + 6, 11 + bob, sleeve);
            put(OX + 5, 10 + bob, paw);
        }
        outline();
    }

    /** Ears, then the face under the hood, then the expression on top of both. */
    private static void head(final Eyes eyes, final Ears ears, final int twitch, final boolean blush, final int mouth, final boolean tongue,
        final boolean puffed, final int bob, final int shake, final long now) {
        int hx = OX + shake;
        for (int side = 0; side < 2; side++) {
            int col = side == 0 ? 1 : 6;
            int outward = side == 0 ? -1 : 1;
            int x = hx + col;
            int base = OY - 1 + bob;
            boolean down = ears == Ears.DOWN || twitch == (side == 0 ? -1 : 1);
            if (down) {
                put(x - outward, base, PURPLE);
                put(x, base, PINK);
                put(x + outward, base, PURPLE);
                put(x + 2 * outward, base + 1, PURPLE);
            } else {
                put(x - 1, base, PURPLE);
                put(x, base, PINK);
                put(x + 1, base, PURPLE);
                put(x, base - 1, PURPLE);
                if (ears == Ears.PERK) {
                    put(x, base - 2, PURPLE);
                    put(x, base - 1, PINK);
                }
            }
        }
        int[] face = new int[64];
        for (int index = 0; index < 64; index++) {
            face[index] = MascotSkin.HOOD[index] != 0 ? MascotSkin.HOOD[index] : MascotSkin.HEAD[index];
        }
        // The hood's pink went up into the ears; the bridge of the nose catches a little light.
        face[8 + 1] = NAVY;
        face[8 + 6] = NAVY;
        face[5 * 8 + 3] = CHEEK;
        face[5 * 8 + 4] = CHEEK;
        for (int side = 0; side < 2; side++) {
            int column = side == 0 ? 1 : 5;
            switch (eyes) {
                case OPEN, WIDE, LOVE -> {
                    boolean love = eyes == Eyes.LOVE;
                    int top = love ? LOVE_TOP : EYE_TOP;
                    int mid = love ? LOVE_MID : EYE_MID;
                    eye(face, column, CATCHLIGHT, top, eyes == Eyes.WIDE ? CATCHLIGHT : top, mid, mid, love ? LOVE_SHINE : EYE_SHINE);
                }
                case HALF -> eye(face, column, BRIM, BRIM, EYE_TOP, EYE_TOP, EYE_MID, EYE_SHINE);
                case CLOSED -> eye(face, column, BRIM, BRIM, FACE, FACE, LID, LID);
                case HAPPY -> {
                    // ^ ^, high on the face over raised cheeks, so the blush never touches them.
                    eye(face, column, BRIM, BRIM, CHEEK, CHEEK, CHEEK, CHEEK);
                    int peak = side == 0 ? column : column + 1;
                    face[4 * 8 + peak] = ARC;
                    face[5 * 8 + peak - 1] = ARC;
                    face[5 * 8 + peak + 1] = ARC;
                }
                case SQUEEZE -> {
                    // > <, shut tight.
                    eye(face, column, BRIM, BRIM, CHEEK, CHEEK, CHEEK, CHEEK);
                    int outer = side == 0 ? column : column + 1;
                    int inner = side == 0 ? column + 1 : column;
                    face[4 * 8 + outer] = LID;
                    face[5 * 8 + inner] = LID;
                    face[6 * 8 + outer] = LID;
                }
                case DIZZY -> {
                    eye(face, column, EYE_TOP, EYE_TOP, EYE_TOP, EYE_TOP, EYE_MID, EYE_MID);
                    int step = (int) ((now / (SECOND / 12)) % DIZZY_RING.length);
                    int[] spot = DIZZY_RING[side == 0 ? step : DIZZY_RING.length - 1 - step];
                    face[spot[1] * 8 + column + spot[0]] = EYE_SHINE;
                }
            }
        }
        face[7 * 8 + 1] = blush ? BLUSH : BLUSH_SOFT;
        face[7 * 8 + 6] = blush ? BLUSH : BLUSH_SOFT;
        if (puffed) {
            face[7 * 8] = BLUSH;
            face[7 * 8 + 7] = BLUSH;
        }
        if (mouth > 0) {
            face[7 * 8 + 3] = mouth == 2 ? MOUTH_OPEN : MOUTH;
            face[7 * 8 + 4] = mouth == 2 ? MOUTH_OPEN : MOUTH;
        }
        if (tongue) {
            face[7 * 8 + 4] = TONGUE;
        }
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                put(hx + x, OY + y + bob, face[y * 8 + x]);
            }
        }
        if (tongue) {
            put(hx + 4, OY + 8 + bob, TONGUE);
        }
    }

    /** One eye: two columns from column, rows 4 to 6, top row first. */
    private static void eye(final int[] face, final int column, final int a, final int b, final int c, final int d, final int e, final int f) {
        face[4 * 8 + column] = a;
        face[4 * 8 + column + 1] = b;
        face[5 * 8 + column] = c;
        face[5 * 8 + column + 1] = d;
        face[6 * 8 + column] = e;
        face[6 * 8 + column + 1] = f;
    }

    private static void put(final int x, final int y, final int color) {
        if ((color >>> 24) != 0 && x >= 0 && x < W && y >= 0 && y < H) {
            CANVAS[y * W + x] = color;
        }
    }

    /** A soft lilac rim around the silhouette, so the dark hood reads on dark glass. */
    private static void outline() {
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int color = CANVAS[y * W + x];
                if (color == 0 && (opaque(x - 1, y) || opaque(x + 1, y) || opaque(x, y - 1) || opaque(x, y + 1))) {
                    color = OUTLINE;
                }
                SHOWN[y * W + x] = color;
            }
        }
    }

    private static boolean opaque(final int x, final int y) {
        return x >= 0 && x < W && y >= 0 && y < H && CANVAS[y * W + x] != 0;
    }

    /** The body with its swing, spin and squash, each around the point it happens at. */
    private static void paintBody(final GuiGraphicsExtractor graphics, final long now) {
        float sx;
        float sy;
        if (body == Body.SPLAT) {
            sx = splatSide == 0 ? 1.25F : 0.55F;
            sy = splatSide == 0 ? 0.55F : 1.25F;
        } else {
            float progress = squashLength == 0L ? 1.0F : clamp((now - squashStart) / (float) squashLength, 0.0F, 1.0F);
            // Back to shape with a little overshoot.
            float settle = 1.0F - (float) (Math.exp(-5.0 * progress) * Math.cos(progress * Math.PI * 2.5));
            settle = progress >= 1.0F ? 1.0F : settle;
            sx = squashX + (1.0F - squashX) * settle;
            sy = squashY + (1.0F - squashY) * settle;
        }
        float pivotX = bodyX;
        float pivotY = top + (FLOOR_ROW + 1) * unit;
        switch (body) {
            case CARRIED -> pivotY = top + 2.0F * unit;
            case FLYING -> pivotY = top + H * unit * 0.5F;
            case SPLAT -> {
                pivotX = splatSide < 0 ? left : splatSide > 0 ? left + W * unit : bodyX;
                pivotY = splatSide == 0 ? top : top + H * unit * 0.5F;
            }
            default -> {
            }
        }
        boolean transformed = tilt != 0.0F || sx != 1.0F || sy != 1.0F;
        Matrix3x2fStack pose = graphics.pose();
        if (transformed) {
            pose.pushMatrix();
            pose.translate(pivotX, pivotY);
            pose.rotate(tilt);
            pose.scale(sx, sy);
            pose.translate(-pivotX, -pivotY);
        }
        paintRows(graphics, left, top, unit, 0, H, !facingLeft);
        if (transformed) {
            pose.popMatrix();
        }
    }

    /** One fill per run of equal pixels in a row. */
    private static void paintRows(final GuiGraphicsExtractor graphics, final int x, final int y, final int scale, final int firstRow, final int endRow, final boolean mirror) {
        for (int row = firstRow; row < endRow; row++) {
            int column = 0;
            while (column < W) {
                int color = SHOWN[row * W + (mirror ? W - 1 - column : column)];
                int end = column + 1;
                while (end < W && SHOWN[row * W + (mirror ? W - 1 - end : end)] == color) {
                    end++;
                }
                if (color != 0) {
                    graphics.fill(x + column * scale, y + (row - firstRow) * scale, x + end * scale, y + (row - firstRow + 1) * scale, color);
                }
                column = end;
            }
        }
    }

    private static void drawParticles(final GuiGraphicsExtractor graphics, final long now) {
        Font font = Minecraft.getInstance().font;
        for (Particle particle : PARTICLES) {
            float life = (now - particle.born) / (float) particle.life;
            float fade = life < 0.15F ? life / 0.15F : 1.0F - Math.max(0.0F, (life - 0.6F) / 0.4F);
            int color = ZTheme.fade(particle.color, clamp(fade, 0.0F, 1.0F));
            switch (particle.kind) {
                case "z", "!", "?" -> {
                    if ((color >>> 24) > 8) {
                        graphics.text(font, particle.kind, Math.round(particle.x), Math.round(particle.y), color, true);
                    }
                }
                case "crumb" -> {
                    int size = Math.max(1, unit / 2 + 1);
                    int px = Math.round(particle.x);
                    int py = Math.round(particle.y);
                    graphics.fill(px, py, px + size, py + size, color);
                }
                case "glitter", "poof" -> {
                    int frame = Math.min(7, (int) (life * 8));
                    String name = particle.kind.equals("poof") ? "generic_" + (7 - frame) : "glitter_" + frame;
                    ZScene.sprite(graphics, name, particle.x, particle.y, 4.0F * unit, 8, color);
                }
                case "flash" -> ZScene.sprite(graphics, "flash", particle.x, particle.y, 26.0F * unit * (0.6F + life), 32, color);
                default -> ZScene.sprite(graphics, particle.kind, particle.x, particle.y, 4.0F * unit, 8, color);
            }
        }
    }

    private static void speedLines(final GuiGraphicsExtractor graphics, final long now) {
        float seconds = (now % (3600 * SECOND)) / (float) SECOND;
        int width = W * unit;
        for (int lane = 0; lane < 3; lane++) {
            float phase = (seconds * 3.0F + lane * 0.33F) % 1.0F;
            int length = (6 + lane * 3) * unit / 2;
            int x = (facingLeft ? left + width + Math.round(phase * 12 * unit) : left - Math.round(phase * 12 * unit) - length);
            int y = top + (10 + lane * 3) * unit;
            graphics.fill(x, y, x + length, y + Math.max(1, unit / 2), ZTheme.fade(ZTheme.CYAN, 1.0F - phase));
        }
    }

    private static float clamp(final float value, final float minimum, final float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float approach(final float current, final float target, final float rate, final float dt) {
        return current + (target - current) * (1.0F - (float) Math.exp(-rate * dt));
    }

    // ---- where it stands -----------------------------------------------------

    private static final int[] FREE = new int[64];

    /**
     * Finds the floor: the bottom of the lowest row of controls, and along it the
     * stretch of free width nearest to where the mascot wants to stand.
     */
    private static boolean findFloor(final Screen owner, final int spriteWidth, final int spriteHeight) {
        int floor = -1;
        for (GuiEventListener child : owner.children()) {
            if (child instanceof AbstractWidget widget && widget.visible) {
                int bottom = widget.getY() + widget.getHeight();
                if (bottom > owner.height * 0.7 && bottom <= owner.height) {
                    floor = Math.max(floor, bottom);
                }
            }
        }
        if (floor < 0) {
            floor = owner.height - 6;
        }
        int bandTop = floor - spriteHeight;
        int free = 0;
        FREE[free++] = 6;
        FREE[free++] = owner.width - 6;
        for (GuiEventListener child : owner.children()) {
            if (!(child instanceof AbstractWidget widget) || !widget.visible) {
                continue;
            }
            int wy0 = widget.getY();
            int wy1 = wy0 + widget.getHeight();
            if (wy1 <= bandTop || wy0 >= floor) {
                continue;
            }
            int wx0 = widget.getX() - 4;
            int wx1 = widget.getX() + widget.getWidth() + 4;
            free = subtract(free, wx0, wx1);
        }
        float wanted = (float) (prefs().place * owner.width);
        int bestA = 0;
        int bestB = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int index = 0; index + 1 < free; index += 2) {
            int a = FREE[index];
            int b = FREE[index + 1];
            if (b - a < spriteWidth + 4) {
                continue;
            }
            float distance = wanted < a ? a - wanted : wanted > b ? wanted - b : 0.0F;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestA = a;
                bestB = b;
            }
        }
        if (bestDistance == Float.MAX_VALUE) {
            return false;
        }
        zoneMin = bestA;
        zoneMax = bestB;
        floorY = floor;
        return true;
    }

    /** Removes {@code [a, b)} from the free intervals in {@link #FREE}; returns the new length. */
    private static int subtract(final int length, final int a, final int b) {
        int[] next = new int[FREE.length];
        int count = 0;
        for (int index = 0; index + 1 < length && count + 4 <= next.length; index += 2) {
            int x0 = FREE[index];
            int x1 = FREE[index + 1];
            if (b <= x0 || a >= x1) {
                next[count++] = x0;
                next[count++] = x1;
                continue;
            }
            if (a > x0) {
                next[count++] = x0;
                next[count++] = a;
            }
            if (b < x1) {
                next[count++] = b;
                next[count++] = x1;
            }
        }
        System.arraycopy(next, 0, FREE, 0, count);
        return count;
    }

    /** Whether {@code x, y} is on the mascot: on its own pixels while it stands, anywhere on it in the air. */
    private static boolean contains(final double x, final double y) {
        if (!visible) {
            return false;
        }
        int column = (int) Math.floor((x - left) / unit);
        int row = (int) Math.floor((y - top) / unit);
        if (column < 0 || column >= W || row < 0 || row >= H) {
            return false;
        }
        if (body != Body.STANDING) {
            return true;
        }
        if (!facingLeft) {
            column = W - 1 - column;
        }
        return SHOWN[row * W + column] != 0;
    }

    // ---- its little menu -----------------------------------------------------

    private static Component[] menuRows() {
        Prefs settings = prefs();
        return new Component[] {
            Component.translatable("kohs_anchors.mascot.feed"),
            Component.translatable("kohs_anchors.mascot.play"),
            Component.translatable("kohs_anchors.mascot.size",
                Component.translatable("kohs_anchors.mascot.size." + settings.size)),
            Component.translatable(settings.playful
                ? "kohs_anchors.mascot.playful" : "kohs_anchors.mascot.calm"),
            Component.translatable(settings.sounds
                ? "kohs_anchors.mascot.sounds.on" : "kohs_anchors.mascot.sounds.off"),
            Component.translatable("kohs_anchors.mascot.hide"),
        };
    }

    private static void openMenu(final Screen owner) {
        Font font = Minecraft.getInstance().font;
        menuWidth = 0;
        for (Component row : menuRows()) {
            menuWidth = Math.max(menuWidth, font.width(row) + 30);
        }
        int height = MENU_ROW * MENU_ROWS + 6;
        menuX = Math.max(4, Math.min(owner.width - menuWidth - 4, left + W * unit / 2 - menuWidth / 2));
        menuY = Math.max(4, top - height - 2);
        menuOpen = true;
        react(Mood.SURPRISED);
    }

    private static int menuRowAt(final double x, final double y) {
        if (x < menuX || x >= menuX + menuWidth || y < menuY + 3 || y >= menuY + 3 + MENU_ROW * MENU_ROWS) {
            return -1;
        }
        return (int) ((y - menuY - 3) / MENU_ROW);
    }

    private static void menuAction(final int row) {
        Prefs settings = prefs();
        long now = System.nanoTime();
        switch (row) {
            case 0 -> {
                feedAction(now);
                return;
            }
            case 1 -> {
                playAction(now);
                return;
            }
            case 2 -> settings.size = settings.size % 3 + 1;
            case 3 -> settings.playful = !settings.playful;
            case 4 -> settings.sounds = !settings.sounds;
            default -> settings.enabled = false;
        }
        save();
        if (settings.enabled) {
            react(row == 3 && !settings.playful ? Mood.MEH : Mood.HAPPY);
            sound("entity.cat.ambient", 1.3F, 0.25F);
        }
    }

    private static void drawMenu(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY) {
        Component[] rows = menuRows();
        int height = MENU_ROW * rows.length + 6;
        AnchorsUi.halo(graphics, menuX, menuY, menuWidth, height, ZTheme.VIOLET, 4, 0.6F);
        AnchorsUi.panel(graphics, menuX, menuY, menuWidth, height, ZTheme.SURFACE, ZTheme.SURFACE);
        AnchorsUi.roundedOutline(graphics, menuX, menuY, menuWidth, height, ZTheme.BORDER);
        int hoveredRow = menuRowAt(mouseX, mouseY);
        Font font = Minecraft.getInstance().font;
        for (int index = 0; index < rows.length; index++) {
            int y = menuY + 3 + index * MENU_ROW;
            if (index == hoveredRow) {
                graphics.fill(menuX + 2, y, menuX + menuWidth - 2, y + MENU_ROW, ZTheme.SURFACE_HOVER);
            }
            if (index == 0) {
                // The only food she takes.
                drawAnchorIcon(graphics, menuX + 11, y + MENU_ROW * 0.5F, 10.0F, 0.0F);
            } else if (index == 1) {
                ZScene.sprite(graphics, "explosion_4", menuX + 11, y + MENU_ROW * 0.5F, 11.0F, 16, 0xFFFFFFFF);
            }
            int color = index == rows.length - 1 ? ZTheme.CRIMSON_BRIGHT : index == hoveredRow ? ZTheme.TEXT : ZTheme.TEXT_MUTED;
            graphics.text(font, rows[index], menuX + 20, y + 3, color, false);
        }
    }
}
