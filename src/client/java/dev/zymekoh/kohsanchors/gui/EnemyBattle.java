package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.glow.AnchorGlowRenderer;
import dev.zymekoh.kohsanchors.skin.AnchorTextures;
import dev.zymekoh.kohsanchors.sound.AnchorSounds;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;

/**
 * The enemy anchors' Advanced tab while its options are being made: "Coming soon" over a battle
 * that never ends. The player's anchors hold the left and the enemy's the right, each as the world
 * draws it (the player's skin, lit in their own glow or in the enemy colour), and they throw
 * glowstone at each other. Every hit charges the anchor it lands on and the fifth sets it off, as
 * glowstone does on a full anchor in the Overworld; a moment later the anchor is placed again.
 *
 * <p>The battlefield is layered like a stage: a general on each side in front and two lines of
 * smaller anchors behind it at two depths. Each depth only throws at its own depth, is hazier the
 * farther back it is, and follows the pointer a little less. A click throws a glowstone from the
 * player's general, with the charge and explosion sounds the player chose.</p>
 *
 * <p>Every glow, ring and puff is one tinted blit of a small mask ({@code textures/gui/battle}),
 * the anchors are {@link AnchorCube}s shared by charge, and a glowstone is a turning cube of the
 * resource pack's own glowstone texture. The fight is a small simulation stepped with the real time
 * between frames, so it runs at the same pace at any frame rate; with interface animations off the
 * tab shows it as a still picture.</p>
 */
final class EnemyBattle {
    private static final Identifier SOFT = texture("glow");
    private static final Identifier SHOCK = texture("shock");
    private static final Identifier FLARE = texture("flare");
    private static final Identifier RAMP = texture("ramp");
    private static final Identifier PUFF = texture("puff");
    private static final Identifier RUNES = texture("runes");
    private static final Identifier GLOWSTONE = Identifier.withDefaultNamespace("textures/block/glowstone.png");
    private static final Direction[] DIRECTIONS = Direction.values();

    /** Depths, back to front. Positions are in stage heights, so a burst is round at any size. */
    private static final int FAR = 0;
    private static final int MID = 1;
    private static final int FRONT = 2;
    /** An anchor's size at each depth: a share of the stage's height, capped by its width. */
    private static final float[] SIZE = {0.052F, 0.088F, 0.19F};
    private static final float[] SIZE_BY_WIDTH = {0.03F, 0.05F, 0.105F};
    private static final float[] GROUND = {0.63F, 0.755F, 0.935F};
    private static final float[] PARALLAX = {0.25F, 0.55F, 1.0F};
    private static final float[] LIGHT = {0.55F, 0.78F, 1.0F};
    private static final float[] FLIGHT = {1.15F, 0.95F, 0.8F};
    private static final float[] ARC = {0.12F, 0.17F, 0.25F};
    private static final float[] INTERVAL = {1.4F, 1.0F, 0.75F};
    private static final float[] INTERVAL_SPREAD = {1.1F, 0.8F, 0.5F};
    private static final float WINDUP = 0.16F;
    private static final float HOP = 0.32F;
    /** How long a detonated anchor stays gone, and how long placing it again takes. */
    private static final float DOWN = 1.05F;
    private static final float PLACE = 0.35F;
    private static final int MAX_SHOTS = 32;

    private static final int GOLD = 0xFFC860;
    private static final int GOLD_LIGHT = 0xFFE9A8;
    private static final int[] DEBRIS = {0x1A0F26, 0x2B1840, 0x3E1D5E, 0x8A3FE0};
    private static final int[] FIRE = {0xFFB347, 0xFFE08A, 0xFF7A3D};
    private static final int[] TITLE_COLORS = {0xFFFF315C, 0xFFFF4F8A, 0xFFFF4FC8, 0xFFC084FC, 0xFFFF6A86};

    private static final byte FLASH = 0;
    private static final byte BLOOM = 1;
    private static final byte RING = 2;
    private static final byte SMOKE = 3;
    private static final byte DUST = 4;
    private static final byte GROUND_RING = 5;
    private static final int MAX_BURSTS = 64;

    private static final byte SPARK_GOLD = 0;
    private static final byte SPARK_DEBRIS = 1;
    private static final byte SPARK_FIRE = 2;
    private static final int MAX_SPARKS = 256;

    private final boolean motion;
    private final Random random = new Random(0x4B6F4853L);
    private final List<Fighter> fighters = new ArrayList<>();
    private final List<Shot> shots = new ArrayList<>();
    /** One cube per charge for each side: the player's skin, then the enemy's. */
    private final AnchorCube[] cubes = new AnchorCube[10];
    private final Matrix3x2f face = new Matrix3x2f();
    private final float[] rotation = new float[9];
    private final int[] score = new int[2];
    private final float[] scoredAt = {-9.0F, -9.0F};
    private final Fighter ownGeneral;
    private final Fighter enemyGeneral;
    private long lastFrame = System.nanoTime();
    private float time;
    private float aspect = 2.4F;
    private float shake;
    private float cameraX;
    private float cameraY;
    private float hover;

    // Where the stage is this frame, and how far each depth is moved.
    private int stageX;
    private int stageY;
    private int stageWidth;
    private int stageHeight;
    private final float[] shiftX = new float[3];
    private final float[] shiftY = new float[3];
    private float projectX;
    private float projectY;

    private final float[] burstX = new float[MAX_BURSTS];
    private final float[] burstY = new float[MAX_BURSTS];
    private final float[] burstAge = new float[MAX_BURSTS];
    private final float[] burstLife = new float[MAX_BURSTS];
    private final float[] burstFrom = new float[MAX_BURSTS];
    private final float[] burstTo = new float[MAX_BURSTS];
    private final float[] burstRise = new float[MAX_BURSTS];
    private final float[] burstTurn = new float[MAX_BURSTS];
    private final int[] burstColor = new int[MAX_BURSTS];
    private final byte[] burstType = new byte[MAX_BURSTS];
    private final byte[] burstDepth = new byte[MAX_BURSTS];
    private int nextBurst;

    private final float[] sparkX = new float[MAX_SPARKS];
    private final float[] sparkY = new float[MAX_SPARKS];
    private final float[] sparkSpeedX = new float[MAX_SPARKS];
    private final float[] sparkSpeedY = new float[MAX_SPARKS];
    private final float[] sparkAge = new float[MAX_SPARKS];
    private final float[] sparkLife = new float[MAX_SPARKS];
    private final int[] sparkColor = new int[MAX_SPARKS];
    private final byte[] sparkKind = new byte[MAX_SPARKS];
    private final byte[] sparkDepth = new byte[MAX_SPARKS];
    private final byte[] sparkSize = new byte[MAX_SPARKS];
    private int nextSpark;

    /** One anchor of the battle. */
    private static final class Fighter {
        final boolean enemy;
        final int depth;
        /** Across the stage, 0 (left) to 1 (right). */
        final float slot;
        final float yaw;
        final float phase;
        int charge;
        float cooldown;
        /** Seconds into the wind-up of a throw, or -1. */
        float windup = -1.0F;
        float sinceThrow = 9.0F;
        float hit;
        float shake;
        /** Seconds since it went off, or -1 while it stands. */
        float down = -1.0F;
        /** Seconds since it was placed; below 0 while it waits for its turn to be placed. */
        float placed = 9.0F;
        boolean playerThrow;
        Fighter target;

        Fighter(boolean enemy, int depth, float slot, float yaw, float phase) {
            this.enemy = enemy;
            this.depth = depth;
            this.slot = slot;
            this.yaw = yaw;
            this.phase = phase;
        }

        boolean standing() {
            return this.down < 0.0F && this.placed >= PLACE;
        }
    }

    /** A glowstone in flight from one anchor to another at the same depth. */
    private static final class Shot {
        final Fighter from;
        final Fighter to;
        final float flight;
        final float arc;
        final float yaw;
        final float pitch;
        final float turnYaw;
        final float turnPitch;
        final boolean player;
        float age;

        Shot(Fighter from, Fighter to, float flight, float arc, float yaw, float pitch, float turnYaw, float turnPitch,
                boolean player) {
            this.from = from;
            this.to = to;
            this.flight = flight;
            this.arc = arc;
            this.yaw = yaw;
            this.pitch = pitch;
            this.turnYaw = turnYaw;
            this.turnPitch = turnPitch;
            this.player = player;
        }
    }

    EnemyBattle(boolean motion) {
        this.motion = motion;
        float[][] slots = {{0.05F, 0.145F, 0.24F, 0.335F}, {0.085F, 0.3F}, {0.19F}};
        Fighter own = null;
        Fighter enemy = null;
        for (int depth = FAR; depth <= FRONT; depth++) {
            for (float slot : slots[depth]) {
                for (int side = 0; side < 2; side++) {
                    boolean isEnemy = side == 1;
                    // Each turned a little towards the other side, and each a little differently.
                    float yaw = (isEnemy ? 243.0F : 207.0F) + (this.random.nextFloat() - 0.5F) * 10.0F;
                    Fighter fighter = new Fighter(isEnemy, depth, isEnemy ? 1.0F - slot : slot, yaw,
                            this.random.nextFloat() * 6.2832F);
                    this.fighters.add(fighter);
                    if (depth == FRONT) {
                        if (isEnemy) {
                            enemy = fighter;
                        } else {
                            own = fighter;
                        }
                    }
                }
            }
        }
        this.ownGeneral = own;
        this.enemyGeneral = enemy;
        enter();
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath(KoHsAnchorsClient.MOD_ID, "textures/gui/battle/" + name + ".png");
    }

    /** The tab was opened: the anchors are placed again, back rows first, and the fight starts over. */
    void enter() {
        this.lastFrame = System.nanoTime();
        this.time = 0.0F;
        this.shots.clear();
        java.util.Arrays.fill(this.burstLife, 0.0F);
        java.util.Arrays.fill(this.sparkLife, 0.0F);
        this.score[0] = 0;
        this.score[1] = 0;
        this.scoredAt[0] = -9.0F;
        this.scoredAt[1] = -9.0F;
        this.shake = 0.0F;
        for (Fighter fighter : this.fighters) {
            fighter.charge = 0;
            fighter.down = -1.0F;
            fighter.windup = -1.0F;
            fighter.hit = 0.0F;
            fighter.shake = 0.0F;
            fighter.sinceThrow = 9.0F;
            fighter.playerThrow = false;
            fighter.placed = -(0.08F + fighter.depth * 0.2F + this.random.nextFloat() * 0.12F);
            fighter.cooldown = 0.9F + fighter.depth * 0.1F + this.random.nextFloat() * INTERVAL_SPREAD[fighter.depth];
        }
        if (!this.motion) {
            still();
        }
    }

    /** With interface animations off: one moment of the fight, standing still. */
    private void still() {
        int[] charges = {1, 3, 4, 2, 0, 2, 3, 1, 2, 4, 1, 3, 3, 2};
        for (int index = 0; index < this.fighters.size(); index++) {
            Fighter fighter = this.fighters.get(index);
            fighter.placed = 9.0F;
            fighter.charge = charges[index % charges.length];
        }
        this.ownGeneral.charge = 3;
        this.enemyGeneral.charge = 2;
        freeze(this.ownGeneral, this.enemyGeneral, 0.55F);
        freeze(this.enemyGeneral, this.ownGeneral, 0.18F);
        freeze(this.fighters.get(8), this.fighters.get(11), 0.42F);
        freeze(this.fighters.get(3), this.fighters.get(0), 0.7F);
    }

    private void freeze(Fighter from, Fighter to, float progress) {
        Shot shot = new Shot(from, to, FLIGHT[from.depth], ARC[from.depth], 30.0F, 20.0F, 0.0F, 0.0F, false);
        shot.age = shot.flight * progress;
        this.shots.add(shot);
    }

    void close() {
        for (int index = 0; index < this.cubes.length; index++) {
            if (this.cubes[index] != null) {
                this.cubes[index].close();
                this.cubes[index] = null;
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // The fight
    // ------------------------------------------------------------------------------------------

    private void update(float delta) {
        this.time += delta;
        this.shake = Math.max(0.0F, this.shake - delta * 2.8F);
        for (Fighter fighter : this.fighters) {
            fighter.hit = Math.max(0.0F, fighter.hit - delta * 3.2F);
            fighter.shake = Math.max(0.0F, fighter.shake - delta * 4.0F);
            fighter.sinceThrow += delta;
            if (fighter.down >= 0.0F) {
                fighter.down += delta;
                if (fighter.down >= DOWN) {
                    // Placed again, uncharged.
                    fighter.down = -1.0F;
                    fighter.charge = 0;
                    fighter.placed = 0.0F;
                    fighter.windup = -1.0F;
                }
                continue;
            }
            float before = fighter.placed;
            fighter.placed += delta;
            if (before < PLACE && fighter.placed >= PLACE) {
                landed(fighter);
            }
            if (!fighter.standing()) {
                continue;
            }
            if (fighter.windup >= 0.0F) {
                fighter.windup += delta;
                if (fighter.windup >= WINDUP) {
                    release(fighter);
                }
                continue;
            }
            fighter.cooldown -= delta;
            if (fighter.cooldown <= 0.0F) {
                Fighter target = target(fighter);
                if (target == null) {
                    fighter.cooldown = 0.3F;
                } else {
                    fighter.target = target;
                    fighter.windup = 0.0F;
                }
            }
        }
        for (int index = this.shots.size() - 1; index >= 0; index--) {
            Shot shot = this.shots.get(index);
            shot.age += delta;
            if (shot.age >= shot.flight) {
                this.shots.remove(index);
                impact(shot);
            }
        }
        for (int index = 0; index < MAX_BURSTS; index++) {
            if (this.burstAge[index] < this.burstLife[index]) {
                this.burstAge[index] += delta;
            }
        }
        for (int index = 0; index < MAX_SPARKS; index++) {
            if (this.sparkAge[index] >= this.sparkLife[index]) {
                continue;
            }
            this.sparkAge[index] += delta;
            int depth = this.sparkDepth[index];
            float size = size(depth);
            switch (this.sparkKind[index]) {
                case SPARK_DEBRIS -> this.sparkSpeedY[index] += 7.5F * size * delta;
                case SPARK_FIRE -> {
                    float drag = 1.0F - Math.min(1.0F, 1.8F * delta);
                    this.sparkSpeedX[index] *= drag;
                    this.sparkSpeedY[index] = this.sparkSpeedY[index] * drag - 0.9F * size * delta;
                }
                default -> this.sparkSpeedY[index] += 4.5F * size * delta;
            }
            this.sparkX[index] += this.sparkSpeedX[index] * delta;
            this.sparkY[index] += this.sparkSpeedY[index] * delta;
            if (this.sparkKind[index] != SPARK_FIRE && this.sparkY[index] > GROUND[depth]) {
                // Debris and sparks come to rest on their own ground.
                this.sparkY[index] = GROUND[depth];
                this.sparkSpeedY[index] *= -0.3F;
                this.sparkSpeedX[index] *= 0.55F;
            }
        }
    }

    /** A standing anchor of the other side at the same depth, chosen at random. */
    private Fighter target(Fighter from) {
        Fighter chosen = null;
        int seen = 0;
        for (Fighter other : this.fighters) {
            if (other.enemy != from.enemy && other.depth == from.depth && other.standing()) {
                seen++;
                if (this.random.nextInt(seen) == 0) {
                    chosen = other;
                }
            }
        }
        return chosen;
    }

    private void release(Fighter from) {
        boolean player = from.playerThrow;
        from.playerThrow = false;
        from.windup = -1.0F;
        from.sinceThrow = 0.0F;
        from.cooldown = INTERVAL[from.depth] + this.random.nextFloat() * INTERVAL_SPREAD[from.depth];
        Fighter target = from.target;
        if (target == null || this.shots.size() >= MAX_SHOTS) {
            return;
        }
        int depth = from.depth;
        float turnYaw = (this.random.nextBoolean() ? 1.0F : -1.0F) * (220.0F + this.random.nextFloat() * 260.0F);
        float turnPitch = (this.random.nextBoolean() ? 1.0F : -1.0F) * (120.0F + this.random.nextFloat() * 200.0F);
        this.shots.add(new Shot(from, target, FLIGHT[depth] * (0.92F + this.random.nextFloat() * 0.16F),
                ARC[depth] * (0.85F + this.random.nextFloat() * 0.3F), this.random.nextFloat() * 360.0F,
                this.random.nextFloat() * 360.0F, turnYaw, turnPitch, player));
        float size = size(depth);
        burst(BLOOM, depth, x(from), top(from), 0.8F * size, 1.4F * size, 0.2F, 0.0F, GOLD, 0.0F);
    }

    private void impact(Shot shot) {
        Fighter target = shot.to;
        int depth = target.depth;
        float size = size(depth);
        float x = x(target);
        if (!target.standing()) {
            // Nothing left to hit: it breaks on the ground where the anchor stood.
            float ground = GROUND[depth];
            sparks(depth, SPARK_GOLD, x, ground - 0.05F * size, 9, size, 1.1F, GOLD, GOLD_LIGHT);
            burst(DUST, depth, x, ground, 0.5F * size, 1.6F * size, 0.55F, 0.0F, 0x8A7050, 0.0F);
            return;
        }
        float y = center(target);
        target.hit = 1.0F;
        target.shake = 1.0F;
        int color = glow(target);
        if (target.charge < 4) {
            target.charge++;
            burst(FLASH, depth, x, y, 1.6F * size, 0.6F * size, 0.26F, 0.0F, GOLD_LIGHT, 0.0F);
            burst(RING, depth, x, y, 0.4F * size, 1.8F * size, 0.36F, 0.0F, color, 0.0F);
            sparks(depth, SPARK_GOLD, x, y, 10, size, 1.3F, GOLD, color);
            if (shot.player) {
                AnchorSounds.previewCharge(0.9F + target.charge * 0.05F);
            }
            return;
        }
        detonate(target, x, y, size, color);
        int side = shot.from.enemy ? 1 : 0;
        this.score[side]++;
        this.scoredAt[side] = this.time;
        if (shot.player) {
            AnchorSounds.previewExplosion();
        }
    }

    /** The fifth glowstone: the anchor goes off in a flash, a shockwave, smoke and debris. */
    private void detonate(Fighter target, float x, float y, float size, int color) {
        int depth = target.depth;
        target.down = 0.0F;
        target.charge = 0;
        target.windup = -1.0F;
        target.hit = 0.0F;
        burst(FLASH, depth, x, y, 3.6F * size, 1.2F * size, 0.5F, 0.0F, 0xFFF4E6, 0.0F);
        burst(BLOOM, depth, x, y, 2.2F * size, 4.6F * size, 0.75F, 0.0F, color, 0.0F);
        burst(RING, depth, x, y, 0.5F * size, 4.2F * size, 0.55F, 0.0F, 0xFFF7FF, 0.0F);
        burst(RING, depth, x, y, 0.4F * size, 3.2F * size, 0.6F, -0.09F, color, 0.0F);
        burst(GROUND_RING, depth, x, GROUND[depth], 0.6F * size, 5.2F * size, 0.7F, -0.04F, color, 0.0F);
        for (int puff = 0; puff < 5; puff++) {
            burst(SMOKE, depth, x + (this.random.nextFloat() - 0.5F) * 0.9F * size,
                    y + (this.random.nextFloat() - 0.5F) * 0.6F * size, (0.9F + this.random.nextFloat() * 0.5F) * size,
                    (2.2F + this.random.nextFloat()) * size, 1.3F + this.random.nextFloat() * 0.6F,
                    -this.random.nextFloat() * 0.15F, 0x6A5E78, (0.35F + this.random.nextFloat() * 0.3F) * size);
        }
        sparks(depth, SPARK_DEBRIS, x, y, 18, size, 1.7F, DEBRIS[0], DEBRIS[3]);
        sparks(depth, SPARK_FIRE, x, y, 16, size, 2.2F, FIRE[0], FIRE[1]);
        if (depth == FRONT) {
            this.shake = 1.0F;
        } else if (depth == MID) {
            this.shake = Math.max(this.shake, 0.35F);
        }
    }

    /** Placed on the ground: a puff of dust and a ring along the ground. */
    private void landed(Fighter fighter) {
        int depth = fighter.depth;
        float size = size(depth);
        float x = x(fighter);
        burst(DUST, depth, x, GROUND[depth], 0.6F * size, 2.2F * size, 0.6F, 0.0F, 0x9A8AA8, 0.0F);
        burst(GROUND_RING, depth, x, GROUND[depth], 0.5F * size, 2.6F * size, 0.45F, 0.0F, glow(fighter), 0.0F);
    }

    private void burst(byte type, int depth, float x, float y, float from, float to, float life, float delay, int color,
            float rise) {
        int index = this.nextBurst;
        this.nextBurst = (index + 1) % MAX_BURSTS;
        this.burstType[index] = type;
        this.burstDepth[index] = (byte) depth;
        this.burstX[index] = x;
        this.burstY[index] = y;
        this.burstFrom[index] = from;
        this.burstTo[index] = to;
        this.burstLife[index] = life;
        this.burstAge[index] = delay;
        this.burstColor[index] = color;
        this.burstRise[index] = rise;
        this.burstTurn[index] = this.random.nextFloat() * 6.2832F;
    }

    private void sparks(int depth, byte kind, float x, float y, int count, float size, float speed, int colorA, int colorB) {
        for (int spark = 0; spark < count; spark++) {
            int index = this.nextSpark;
            this.nextSpark = (index + 1) % MAX_SPARKS;
            float angle = this.random.nextFloat() * 6.2832F;
            float velocity = (0.4F + this.random.nextFloat()) * speed * size;
            this.sparkX[index] = x;
            this.sparkY[index] = y;
            this.sparkSpeedX[index] = (float) Math.cos(angle) * velocity;
            this.sparkSpeedY[index] = (float) Math.sin(angle) * velocity - (kind == SPARK_FIRE ? 0.5F : 1.4F) * size * speed;
            this.sparkAge[index] = 0.0F;
            this.sparkLife[index] = (kind == SPARK_DEBRIS ? 0.9F : kind == SPARK_FIRE ? 0.55F : 0.5F)
                    + this.random.nextFloat() * 0.5F;
            this.sparkColor[index] = kind == SPARK_DEBRIS ? DEBRIS[this.random.nextInt(DEBRIS.length)]
                    : kind == SPARK_FIRE ? FIRE[this.random.nextInt(FIRE.length)]
                    : this.random.nextBoolean() ? colorA : colorB;
            this.sparkKind[index] = kind;
            this.sparkDepth[index] = (byte) depth;
            this.sparkSize[index] = (byte) (depth == FRONT ? (kind == SPARK_DEBRIS ? 2 + this.random.nextInt(2) : 2)
                    : depth == MID ? 1 + this.random.nextInt(2) : 1);
        }
    }

    // Geometry in stage heights: x from the stage's left edge, y from its top.

    private float size(int depth) {
        return Math.min(SIZE[depth], SIZE_BY_WIDTH[depth] * this.aspect);
    }

    private float x(Fighter fighter) {
        return fighter.slot * this.aspect;
    }

    private float center(Fighter fighter) {
        return GROUND[fighter.depth] - 0.62F * size(fighter.depth);
    }

    private float top(Fighter fighter) {
        return center(fighter) - 0.72F * size(fighter.depth);
    }

    private int glow(Fighter fighter) {
        return fighter.enemy ? AnchorFigure.enemyColor() : AnchorGlowRenderer.ownColor(Math.max(1, fighter.charge)) & 0xFFFFFF;
    }

    private float screenX(float x, int depth) {
        return this.stageX + x * this.stageHeight + this.shiftX[depth];
    }

    private float screenY(float y, int depth) {
        return this.stageY + y * this.stageHeight + this.shiftY[depth];
    }

    // ------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------

    /** A click on the battlefield: the player's general throws a glowstone at the enemy's. */
    boolean mouseClicked(AnchorsLayout.Rect body, double mouseX, double mouseY, int button) {
        if (button != Keys.LEFT_BUTTON || !body.contains(mouseX, mouseY)) {
            return false;
        }
        Fighter general = this.ownGeneral;
        if (this.motion && general.standing() && general.windup < 0.0F) {
            general.target = this.enemyGeneral;
            general.windup = 0.0F;
            general.playerThrow = true;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.SNOWBALL_THROW,
                    0.7F + this.random.nextFloat() * 0.2F, 0.45F));
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------

    void render(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.Rect body, int mouseX, int mouseY, float intro) {
        long now = System.nanoTime();
        float delta = Math.min(0.05F, (now - this.lastFrame) / 1_000_000_000.0F);
        this.lastFrame = now;
        if (body.width() <= 0 || body.height() <= 0) {
            return;
        }
        this.stageX = body.x();
        this.stageY = body.y();
        this.stageWidth = body.width();
        this.stageHeight = body.height();
        this.aspect = body.width() / (float) body.height();
        if (this.motion) {
            update(delta);
        }

        // The pointer moves the layers a little, the front most; without it they drift slowly.
        boolean over = body.contains(mouseX, mouseY);
        float response = 1.0F - (float) Math.exp(-delta / 0.25F);
        this.hover += ((over ? 1.0F : 0.0F) - this.hover) * (1.0F - (float) Math.exp(-delta / 0.12F));
        float targetX = 0.0F;
        float targetY = 0.0F;
        if (this.motion) {
            if (over) {
                targetX = AnchorsTheme.clamp01((mouseX - body.x()) / (float) body.width()) * 2.0F - 1.0F;
                targetY = AnchorsTheme.clamp01((mouseY - body.y()) / (float) body.height()) * 2.0F - 1.0F;
            } else {
                targetX = 0.35F * (float) Math.sin(this.time * 0.23F);
                targetY = 0.2F * (float) Math.sin(this.time * 0.17F);
            }
        }
        this.cameraX += (targetX - this.cameraX) * response;
        this.cameraY += (targetY - this.cameraY) * response;
        float reach = Math.min(10.0F, body.width() * 0.02F);
        float quake = this.shake * this.shake;
        for (int depth = FAR; depth <= FRONT; depth++) {
            this.shiftX[depth] = (-this.cameraX * reach + quake * 3.0F * (float) Math.sin(this.time * 71.0F)) * PARALLAX[depth];
            this.shiftY[depth] = (-this.cameraY * reach * 0.4F + quake * 2.0F * (float) Math.cos(this.time * 53.0F))
                    * PARALLAX[depth];
        }

        int own = AnchorGlowRenderer.ownColor(Math.max(1, this.ownGeneral.charge)) & 0xFFFFFF;
        int enemy = AnchorFigure.enemyColor();
        AnchorsUi.isolate(graphics);
        graphics.enableScissor(body.x(), body.y(), body.right(), body.bottom());
        drawBackdrop(graphics, font, own, enemy);
        for (int depth = FAR; depth <= FRONT; depth++) {
            drawDepth(graphics, depth);
            AnchorsUi.isolate(graphics);
            if (depth == FAR) {
                // Haze over the back row: farther away, less light.
                int top = Math.round(this.stageY + 0.5F * this.stageHeight);
                int middle = Math.round(this.stageY + 0.655F * this.stageHeight);
                int bottom = Math.round(this.stageY + 0.72F * this.stageHeight);
                graphics.fillGradient(body.x(), top, body.right(), middle, 0x000A0512, 0x780A0512);
                graphics.fillGradient(body.x(), middle, body.right(), bottom, 0x780A0512, 0x000A0512);
                groundLine(graphics, GROUND[MID], MID, own, enemy, 0.22F);
            } else if (depth == MID) {
                int top = Math.round(this.stageY + 0.66F * this.stageHeight);
                int middle = Math.round(this.stageY + 0.78F * this.stageHeight);
                graphics.fillGradient(body.x(), top, body.right(), middle, 0x000A0512, 0x400A0512);
                graphics.fillGradient(body.x(), middle, body.right(), body.bottom(), 0x400A0512, 0xA0080410);
                groundLine(graphics, GROUND[FRONT], FRONT, own, enemy, 0.5F);
            }
        }
        drawForeground(graphics, font, own, enemy, intro);
        graphics.disableScissor();
        AnchorsUi.isolate(graphics);
        AnchorsUi.roundedOutline(graphics, body.x(), body.y(), body.width(), body.height(), 0xA0FF315C);
        AnchorsUi.bladeCorners(graphics, body.x(), body.y(), body.width(), body.height(), 7, 0xE0FF6A86);
        DevInspector.node("EnemyBattle", "coming soon", body.x(), body.y(), body.width(), body.height(),
                "fighters " + this.fighters.size() + " · shots " + this.shots.size() + " · score " + this.score[0] + "–"
                        + this.score[1], "AnchorCube by charge · glowstone.png from the resource pack",
                "click: the player's general throws");
    }

    /** The sky: the two sides' colours from their edges, the sigils behind the generals, the divide. */
    private void drawBackdrop(GuiGraphicsExtractor graphics, Font font, int own, int enemy) {
        int x = this.stageX;
        int y = this.stageY;
        int width = this.stageWidth;
        int height = this.stageHeight;
        graphics.fillGradient(x, y, x + width, y + height, 0xE00A0512, 0xF0120814);
        float half = width * 0.55F;
        sprite(graphics, RAMP, 64, 8, x + half / 2.0F + this.shiftX[FAR], y + height / 2.0F, half, height, 0.0F,
                argb(own, 0.2F));
        sprite(graphics, RAMP, 64, 8, x + width - half / 2.0F + this.shiftX[FAR], y + height / 2.0F, -half, height, 0.0F,
                argb(enemy, 0.2F));
        // A slow scan of light down the screen.
        if (this.motion) {
            float band = (this.time * 0.16F) % 1.3F - 0.15F;
            sprite(graphics, SOFT, 64, 64, x + width / 2.0F, y + band * height, width * 1.3F, height * 0.12F, 0.0F,
                    argb(0xE9D5FF, 0.05F));
        }
        // The horizon glows in both colours.
        float horizon = screenY(GROUND[FAR], FAR);
        sprite(graphics, SOFT, 64, 64, x + width * 0.28F + this.shiftX[FAR], horizon, width * 0.75F, height * 0.14F, 0.0F,
                argb(own, 0.28F));
        sprite(graphics, SOFT, 64, 64, x + width * 0.72F + this.shiftX[FAR], horizon, width * 0.75F, height * 0.14F, 0.0F,
                argb(enemy, 0.28F));
        groundLine(graphics, GROUND[FAR], FAR, own, enemy, 0.3F);

        // A rune ring turning behind each general.
        for (int side = 0; side < 2; side++) {
            Fighter general = side == 0 ? this.ownGeneral : this.enemyGeneral;
            float size = size(FRONT) * height;
            float centerX = screenX(x(general), MID);
            float centerY = screenY(center(general), MID) - size * 0.2F;
            int color = general.enemy ? enemy : own;
            float turn = (general.enemy ? -1.0F : 1.0F) * this.time * 0.25F;
            float lit = 0.14F + 0.05F * general.charge + 0.15F * general.hit;
            sprite(graphics, RUNES, 256, 256, centerX, centerY, size * 3.4F, size * 3.4F, turn, argb(color, lit));
            sprite(graphics, RUNES, 256, 256, centerX, centerY, size * 2.3F, size * 2.3F, -turn * 1.6F, argb(color, lit * 0.6F));
        }
        AnchorsUi.isolate(graphics);

        // The divide: a slanted blade down the middle, a glint running down it, and "VS".
        float middle = x + width / 2.0F + this.shiftX[MID];
        float top = y + 0.47F * height;
        float bottom = y + 0.97F * height;
        float lean = 0.03F * width;
        AnchorsUi.segment(graphics, middle + lean, top, middle - lean, bottom, 1, argb(0xFFE4EA, 0.24F));
        if (this.motion) {
            float run = (this.time % 3.4F) / 0.8F;
            if (run < 1.0F) {
                float eased = AnchorsTheme.easeInOutSine(run);
                sprite(graphics, SOFT, 64, 64, middle + lean - 2.0F * lean * eased, top + (bottom - top) * eased, 14.0F, 14.0F,
                        0.0F, argb(0xFFF7FF, 0.85F * (1.0F - run * 0.4F)));
            }
        }
        int vsY = Math.round(y + 0.72F * height + this.shiftY[MID]);
        int vsX = Math.round(middle - lean * 0.0F);
        AnchorsUi.diamond(graphics, vsX, vsY, 11, argb(0xFF315C, 0.35F));
        AnchorsUi.diamond(graphics, vsX, vsY, 10, 0xE014060E);
        int vWidth = font.width("V");
        int total = vWidth + font.width("S");
        AnchorsUi.label(graphics, font, "V", vsX - total / 2, vsY - 4, 0xFF000000 | own, true);
        AnchorsUi.label(graphics, font, "S", vsX - total / 2 + vWidth, vsY - 4, 0xFF000000 | enemy, true);
        AnchorsUi.isolate(graphics);
    }

    /** A line of light along a ground, from each side's edge in its colour, fading out before the middle. */
    private void groundLine(GuiGraphicsExtractor graphics, float ground, int depth, int own, int enemy, float strength) {
        float y = screenY(ground, depth);
        float half = this.stageWidth * 0.5F;
        sprite(graphics, RAMP, 64, 8, this.stageX + half / 2.0F, y, half, 2.0F, 0.0F, argb(own, strength));
        sprite(graphics, RAMP, 64, 8, this.stageX + this.stageWidth - half / 2.0F, y, -half, 2.0F, 0.0F, argb(enemy, strength));
    }

    /** One depth: what lies on its ground, its anchors, its glowstone in flight, then its bursts and sparks. */
    private void drawDepth(GuiGraphicsExtractor graphics, int depth) {
        for (int index = 0; index < MAX_BURSTS; index++) {
            if (this.burstDepth[index] == depth && (this.burstType[index] == DUST || this.burstType[index] == GROUND_RING)) {
                drawBurst(graphics, index);
            }
        }
        for (Fighter fighter : this.fighters) {
            if (fighter.depth == depth) {
                drawGlow(graphics, fighter);
            }
        }
        for (Fighter fighter : this.fighters) {
            if (fighter.depth == depth) {
                drawAnchor(graphics, fighter);
            }
        }
        for (Shot shot : this.shots) {
            if (shot.from.depth == depth) {
                drawShot(graphics, shot);
            }
        }
        AnchorsUi.isolate(graphics);
        for (int index = 0; index < MAX_BURSTS; index++) {
            if (this.burstDepth[index] == depth && this.burstType[index] != DUST && this.burstType[index] != GROUND_RING) {
                drawBurst(graphics, index);
            }
        }
        int drawn = 0;
        for (int index = 0; index < MAX_SPARKS; index++) {
            if (this.sparkDepth[index] != depth || this.sparkAge[index] >= this.sparkLife[index]) {
                continue;
            }
            if (++drawn % 48 == 0) {
                AnchorsUi.isolate(graphics);
            }
            float life = this.sparkAge[index] / this.sparkLife[index];
            float alpha = (float) Math.pow(1.0F - life, 1.2F);
            if (this.sparkKind[index] == SPARK_FIRE) {
                alpha *= 0.7F + 0.3F * (float) Math.sin(this.sparkAge[index] * 40.0F + index);
            }
            int x = Math.round(screenX(this.sparkX[index], depth));
            int y = Math.round(screenY(this.sparkY[index], depth));
            int size = this.sparkSize[index];
            if (this.sparkKind[index] != SPARK_DEBRIS) {
                graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, argb(this.sparkColor[index], alpha * 0.25F));
            }
            graphics.fill(x, y, x + size, y + size, argb(this.sparkColor[index], alpha));
        }
    }

    /** The light an anchor gives off: on the ground under it and around it, with its charge. */
    private void drawGlow(GuiGraphicsExtractor graphics, Fighter fighter) {
        if (fighter.placed < 0.0F || fighter.down >= 0.0F) {
            return;
        }
        int depth = fighter.depth;
        float scale = size(depth) * this.stageHeight;
        float x = screenX(x(fighter), depth);
        float ground = screenY(GROUND[depth], depth);
        float drop = fighter.placed < PLACE ? 1.0F - AnchorsTheme.easeInCubic(fighter.placed / PLACE) : 0.0F;
        float appear = AnchorsTheme.clamp01(fighter.placed / (PLACE * 0.6F));
        float shrink = 1.0F - 0.5F * drop;
        sprite(graphics, SOFT, 64, 64, x, ground, 1.5F * scale * shrink, 0.36F * scale * shrink, 0.0F,
                argb(0x05020A, 0.8F * appear));
        float charge = fighter.charge / 4.0F;
        float light = (0.5F * charge + 0.3F * fighter.hit) * LIGHT[depth] * appear;
        if (light > 0.01F) {
            int color = glow(fighter);
            float centerY = ground - 0.62F * scale - drop * 1.8F * scale;
            sprite(graphics, SOFT, 64, 64, x, ground, 2.8F * scale, 0.62F * scale, 0.0F, argb(color, light));
            sprite(graphics, SOFT, 64, 64, x, centerY, 2.9F * scale, 2.9F * scale, 0.0F, argb(color, light * 0.85F));
        }
    }

    /**
     * The anchor itself: dropped into place when it is placed, squashed as it winds up a throw,
     * hopping as it lets go, shaken when it is hit.
     */
    private void drawAnchor(GuiGraphicsExtractor graphics, Fighter fighter) {
        if (fighter.placed < 0.0F || fighter.down >= 0.0F) {
            return;
        }
        AnchorCube cube = cube(fighter.charge, fighter.enemy);
        boolean animateTop = this.motion && (cube.resolution() == 0 || cube.resolution() <= 64);
        if (!cube.prepare(animateTop)) {
            return;
        }
        int depth = fighter.depth;
        float scale = size(depth) * this.stageHeight;
        float ground = screenY(GROUND[depth], depth);
        float drop = fighter.placed < PLACE ? 1.0F - AnchorsTheme.easeInCubic(fighter.placed / PLACE) : 0.0F;
        float appear = AnchorsTheme.clamp01(fighter.placed / (PLACE * 0.6F));
        float squash = 0.0F;
        float hop = 0.0F;
        if (fighter.windup >= 0.0F) {
            squash = 0.1F * AnchorsTheme.easeOutCubic(fighter.windup / WINDUP);
        } else if (fighter.sinceThrow < HOP) {
            float arc = (float) Math.sin(Math.PI * fighter.sinceThrow / HOP);
            squash = -0.07F * arc;
            hop = 0.13F * arc * scale;
        }
        if (this.motion) {
            squash += 0.012F * (float) Math.sin(this.time * 2.2F + fighter.phase);
        }
        float x = screenX(x(fighter), depth)
                + fighter.shake * fighter.shake * 0.07F * scale * (float) Math.sin(this.time * 95.0F + fighter.phase);
        float y = ground - 0.62F * scale - drop * 1.8F * scale - hop;
        float sway = this.motion ? 5.0F * (float) Math.sin(this.time * 0.5F + fighter.phase) : 0.0F;
        cube.setView(fighter.yaw + sway, 24.0F);
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, ground);
        graphics.pose().scale(1.0F + squash * 0.6F, 1.0F - squash);
        graphics.pose().translate(-x, -ground);
        cube.layout(x, y, scale);
        float lift = Math.min(1.0F, fighter.charge / 4.0F * (fighter.enemy ? 0.72F : 0.45F) + fighter.hit * 0.35F);
        cube.draw(graphics, appear, AnchorTextures.GLOW, 0.0F, lift, glow(fighter));
        graphics.pose().popMatrix();
    }

    private AnchorCube cube(int charge, boolean enemy) {
        int clamped = Math.max(0, Math.min(4, charge));
        int index = clamped + (enemy ? 5 : 0);
        if (this.cubes[index] == null) {
            this.cubes[index] = new AnchorCube((enemy ? "battle_enemy_" : "battle_") + clamped, enemy);
            this.cubes[index].setCharge(clamped);
        }
        return this.cubes[index];
    }

    /** A glowstone in flight: its trail, its light on the ground below, its glow and the turning block. */
    private void drawShot(GuiGraphicsExtractor graphics, Shot shot) {
        int depth = shot.from.depth;
        float progress = AnchorsTheme.clamp01(shot.age / shot.flight);
        float block = 0.36F * size(depth) * this.stageHeight;
        float light = LIGHT[depth];
        for (int step = 7; step >= 1; step--) {
            float earlier = progress - step * 0.03F;
            if (earlier < 0.0F) {
                continue;
            }
            float fade = 1.0F - step / 8.0F;
            sprite(graphics, SOFT, 64, 64, screenX(shotX(shot, earlier), depth), screenY(shotY(shot, earlier), depth),
                    block * (0.4F + 1.6F * fade), block * (0.4F + 1.6F * fade), 0.0F, argb(GOLD, 0.45F * fade * light));
        }
        float x = screenX(shotX(shot, progress), depth);
        float y = screenY(shotY(shot, progress), depth);
        float ground = screenY(GROUND[depth], depth);
        float height = AnchorsTheme.clamp01((ground - y) / Math.max(1.0F, this.stageHeight * 0.5F));
        sprite(graphics, SOFT, 64, 64, x, ground, block * 2.4F, block * 0.6F, 0.0F, argb(GOLD, 0.4F * light * (1.0F - height)));
        sprite(graphics, SOFT, 64, 64, x, y, block * 3.2F, block * 3.2F, 0.0F, argb(GOLD, 0.75F * light));
        drawBlock(graphics, x, y, block, shot.yaw + shot.turnYaw * shot.age, shot.pitch + shot.turnPitch * shot.age,
                0.75F + 0.25F * light);
    }

    private float shotX(Shot shot, float progress) {
        return x(shot.from) + (x(shot.to) - x(shot.from)) * progress;
    }

    private float shotY(Shot shot, float progress) {
        float from = top(shot.from);
        return from + (center(shot.to) - from) * progress - shot.arc * 4.0F * progress * (1.0F - progress);
    }

    /**
     * A cube of glowstone {@code size} pixels wide, turned by yaw and pitch: each visible face is the
     * resource pack's glowstone texture blitted through the transform that maps it to the screen.
     */
    private void drawBlock(GuiGraphicsExtractor graphics, float centerX, float centerY, float size, float yawDegrees,
            float pitchDegrees, float light) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        float cy = (float) Math.cos(yaw);
        float sy = (float) Math.sin(yaw);
        float cp = (float) Math.cos(pitch);
        float sp = (float) Math.sin(pitch);
        float[] r = this.rotation;
        r[0] = cy;
        r[1] = 0.0F;
        r[2] = sy;
        r[3] = sp * sy;
        r[4] = cp;
        r[5] = -sp * cy;
        r[6] = -cp * sy;
        r[7] = sp;
        r[8] = cp * cy;
        for (Direction direction : DIRECTIONS) {
            float normal = r[6] * direction.getStepX() + r[7] * direction.getStepY() + r[8] * direction.getStepZ();
            if (normal <= 0.02F) {
                continue;
            }
            project(direction, 0.0F, 0.0F, centerX, centerY, size);
            float originX = this.projectX;
            float originY = this.projectY;
            project(direction, 1.0F, 0.0F, centerX, centerY, size);
            float acrossX = this.projectX - originX;
            float acrossY = this.projectY - originY;
            project(direction, 0.0F, 1.0F, centerX, centerY, size);
            float downX = this.projectX - originX;
            float downY = this.projectY - originY;
            this.face.set(acrossX / 16.0F, acrossY / 16.0F, downX / 16.0F, downY / 16.0F, originX, originY);
            float shade = switch (direction) {
                case UP -> 1.0F;
                case DOWN -> 0.62F;
                case NORTH, SOUTH -> 0.88F;
                default -> 0.76F;
            };
            int grey = Math.round(255.0F * shade * light);
            graphics.pose().pushMatrix();
            graphics.pose().mul(this.face);
            graphics.blit(RenderPipelines.GUI_TEXTURED, GLOWSTONE, 0, 0, 0.0F, 0.0F, 16, 16, 16, 16, 16, 16,
                    0xFF000000 | grey << 16 | grey << 8 | grey);
            graphics.pose().popMatrix();
        }
    }

    /** A point of a face of the block, as the game maps its texture, onto the screen. */
    private void project(Direction direction, float u, float v, float centerX, float centerY, float size) {
        float x;
        float y;
        float z;
        switch (direction) {
            case UP -> {
                x = u;
                y = 1.0F;
                z = v;
            }
            case DOWN -> {
                x = u;
                y = 0.0F;
                z = 1.0F - v;
            }
            case NORTH -> {
                x = 1.0F - u;
                y = 1.0F - v;
                z = 0.0F;
            }
            case SOUTH -> {
                x = u;
                y = 1.0F - v;
                z = 1.0F;
            }
            case WEST -> {
                x = 0.0F;
                y = 1.0F - v;
                z = u;
            }
            default -> {
                x = 1.0F;
                y = 1.0F - v;
                z = 1.0F - u;
            }
        }
        x -= 0.5F;
        y -= 0.5F;
        z -= 0.5F;
        float[] r = this.rotation;
        this.projectX = centerX + (r[0] * x + r[1] * y + r[2] * z) * size;
        this.projectY = centerY - (r[3] * x + r[4] * y + r[5] * z) * size;
    }

    private void drawBurst(GuiGraphicsExtractor graphics, int index) {
        float age = this.burstAge[index];
        float life = this.burstLife[index];
        if (life <= 0.0F || age < 0.0F || age >= life) {
            return;
        }
        int depth = this.burstDepth[index];
        float t = age / life;
        float size = (this.burstFrom[index] + (this.burstTo[index] - this.burstFrom[index]) * AnchorsTheme.easeOutCubic(t))
                * this.stageHeight;
        float x = screenX(this.burstX[index], depth);
        float y = screenY(this.burstY[index], depth);
        int color = this.burstColor[index];
        float light = LIGHT[depth];
        switch (this.burstType[index]) {
            case FLASH -> sprite(graphics, FLARE, 64, 64, x, y, size, size, 0.0F, argb(color, 0.95F * (1.0F - t) * (1.0F - t)));
            case BLOOM -> sprite(graphics, SOFT, 64, 64, x, y, size, size, 0.0F,
                    argb(color, 0.8F * light * (float) Math.pow(1.0F - t, 1.5F)));
            case RING -> sprite(graphics, SHOCK, 128, 128, x, y, size, size, 0.0F, argb(color, 0.85F * light * (1.0F - t)));
            case SMOKE -> {
                float rise = this.burstRise[index] * age * this.stageHeight;
                float fadeIn = AnchorsTheme.clamp01(t / 0.12F);
                sprite(graphics, PUFF, 64, 64, x, y - rise, size, size, this.burstTurn[index] + age * 0.6F,
                        argb(color, 0.4F * fadeIn * (1.0F - t)));
            }
            case DUST -> sprite(graphics, PUFF, 64, 64, x, y, size, size * 0.38F, 0.0F, argb(color, 0.45F * light * (1.0F - t)));
            default -> sprite(graphics, SHOCK, 128, 128, x, y, size, size * 0.3F, 0.0F, argb(color, 0.8F * light * (1.0F - t)));
        }
    }

    /** The title, the score, a few embers in front, the scan lines of a screen and the hint. */
    private void drawForeground(GuiGraphicsExtractor graphics, Font font, int own, int enemy, float intro) {
        int x = this.stageX;
        int y = this.stageY;
        int width = this.stageWidth;
        int height = this.stageHeight;
        // Embers drifting up in front of everything, moved the most by the pointer.
        if (this.motion) {
            for (int ember = 0; ember < 14; ember++) {
                double speed = 0.05D + ember % 5 * 0.018D;
                double phase = ember * 0.61803398875D;
                float ex = x + (float) (((ember * 0.137D + 0.05D * Math.sin(this.time * 0.6D + phase * 6.0D)) % 1.0D) * width)
                        + this.shiftX[FRONT] * 1.3F;
                float ey = y + height - (float) (((this.time * speed + phase) % 1.0D) * height * 1.1D);
                int color = ember % 3 == 0 ? GOLD_LIGHT : ember % 2 == 0 ? own : enemy;
                float twinkle = 0.55F + 0.45F * (float) Math.sin(this.time * 3.1F + ember);
                int size = ember % 4 == 0 ? 2 : 1;
                graphics.fill(Math.round(ex), Math.round(ey), Math.round(ex) + size, Math.round(ey) + size,
                        argb(color, 0.6F * twinkle));
            }
        }
        AnchorsUi.isolate(graphics);
        // A screen's scan lines, faint.
        if (height >= 120) {
            for (int line = y + 1; line < y + height; line += 3) {
                graphics.fill(x, line, x + width, line + 1, 0x10000000);
            }
        }
        // The edges darken like a lens.
        graphics.fillGradient(x, y, x + width, y + Math.round(height * 0.18F), 0x900A0512, 0x000A0512);
        sprite(graphics, RAMP, 64, 8, x + width * 0.06F, y + height / 2.0F, width * 0.12F, height, 0.0F, 0x70050208);
        sprite(graphics, RAMP, 64, 8, x + width * 0.94F, y + height / 2.0F, -width * 0.12F, height, 0.0F, 0x70050208);
        AnchorsUi.isolate(graphics);
        drawHud(graphics, font, own, enemy, intro);
        drawTitle(graphics, font, intro);
        if (this.hover > 0.02F && this.motion) {
            String hint = Component.translatable("kohs_anchors.enemy.battle.hint").getString();
            AnchorsUi.label(graphics, font, hint, x + (width - font.width(hint)) / 2, y + height - 11,
                    AnchorsTheme.fade(AnchorsTheme.TEXT_MUTED, this.hover * intro), false);
        }
    }

    /** Each side's name, its general's charge and how many anchors it has set off, in the top corners. */
    private void drawHud(GuiGraphicsExtractor graphics, Font font, int own, int enemy, float intro) {
        if (this.stageWidth < 380 || this.stageHeight < 150) {
            return;
        }
        for (int side = 0; side < 2; side++) {
            boolean isEnemy = side == 1;
            Fighter general = isEnemy ? this.enemyGeneral : this.ownGeneral;
            String name = Component.translatable(isEnemy ? "kohs_anchors.enemy.intro.enemy" : "kohs_anchors.enemy.intro.yours")
                    .getString().toUpperCase(Locale.ROOT);
            int color = AnchorsTheme.lerp(0xFF000000 | (isEnemy ? enemy : own), 0xFFFFFFFF, 0.35F);
            String number = Integer.toString(this.score[side]);
            int nameWidth = font.width(name);
            int blockWidth = Math.max(nameWidth, 30);
            int top = this.stageY + 9;
            int left = isEnemy ? this.stageX + this.stageWidth - 10 - blockWidth : this.stageX + 10;
            AnchorsUi.label(graphics, font, name, isEnemy ? left + blockWidth - nameWidth : left, top,
                    AnchorsTheme.fade(color, intro), true);
            // The general's charge: gold pips, and red once the next glowstone sets it off.
            boolean full = general.charge >= 4 && general.standing();
            float danger = full && this.motion ? 0.5F + 0.5F * (float) Math.sin(this.time * 9.0F) : full ? 1.0F : 0.0F;
            for (int pip = 0; pip < 4; pip++) {
                int pipX = isEnemy ? left + blockWidth - 3 - pip * 8 : left + 3 + pip * 8;
                boolean lit = general.standing() && pip < general.charge;
                int pipColor = lit ? AnchorsTheme.lerp(0xFFFFC860, 0xFFFF315C, danger) : 0xFF3A2A4A;
                AnchorsUi.diamond(graphics, pipX, top + 15, 2, AnchorsTheme.fade(pipColor, intro));
            }
            float pop = 1.0F + 0.5F * Math.max(0.0F, 1.0F - (this.time - this.scoredAt[side]) / 0.35F);
            float scale = 2.0F * pop;
            float numberWidth = font.width(number) * scale;
            float numberX = isEnemy ? left - 8 - numberWidth : left + blockWidth + 8;
            graphics.pose().pushMatrix();
            graphics.pose().translate(numberX, top + 9 - 9 * scale / 2.0F);
            graphics.pose().scale(scale, scale);
            AnchorsUi.label(graphics, font, number, 0, 0, AnchorsTheme.fade(AnchorsTheme.TITLE, intro), true);
            graphics.pose().popMatrix();
        }
    }

    /** "Coming soon": each letter slams in, then the word shimmers from crimson to violet. */
    private void drawTitle(GuiGraphicsExtractor graphics, Font font, float intro) {
        int width = this.stageWidth;
        int height = this.stageHeight;
        boolean hud = width >= 380 && height >= 150;
        String eyebrow = "◆ " + Component.translatable("kohs_anchors.enemy.soon.eyebrow").getString().toUpperCase(Locale.ROOT);
        String title = Component.translatable("kohs_anchors.enemy.soon.title").getString().toUpperCase(Locale.ROOT);
        int room = width - (hud ? 200 : 24);
        float scale = Math.min(height >= 200 ? 3.0F : height >= 140 ? 2.2F : 1.6F,
                room / (float) Math.max(1, spacedWidth(font, title)));
        scale = Math.max(1.0F, scale);
        int centerX = this.stageX + width / 2;
        int y = this.stageY + 8;
        float since = this.motion ? this.time : 9.0F;
        float eyebrowIn = AnchorsTheme.easeOutCubic(AnchorsTheme.clamp01((since - 0.05F) / 0.3F));
        if (font.width(eyebrow) <= room) {
            AnchorsUi.label(graphics, font, eyebrow, centerX - font.width(eyebrow) / 2, y + Math.round((1.0F - eyebrowIn) * 4),
                    AnchorsTheme.fade(0xFFFF8AA8, intro * eyebrowIn), false);
            y += 13;
        }
        float total = spacedWidth(font, title) * scale;
        float letterX = centerX - total / 2.0F;
        float titleY = y;
        AnchorsUi.isolate(graphics);
        for (int index = 0; index < title.length(); index++) {
            String letter = String.valueOf(title.charAt(index));
            float advance = (font.width(letter) + 1) * scale;
            float in = AnchorsTheme.clamp01((since - 0.15F - index * 0.045F) / 0.28F);
            if (!letter.equals(" ") && in > 0.0F) {
                float grow = 1.0F + 0.9F * (1.0F - AnchorsTheme.easeOutCubic(in));
                float shimmer = (float) ((this.time * 0.3D + index / (double) title.length()) % 1.0D);
                int color = gradient(this.motion ? shimmer : index / (float) title.length());
                float letterScale = scale * grow;
                float letterWidth = font.width(letter) * letterScale;
                float drawX = letterX + font.width(letter) * scale / 2.0F - letterWidth / 2.0F;
                float drawY = titleY + 9 * scale / 2.0F - 9 * letterScale / 2.0F;
                drawLetter(graphics, font, letter, drawX + 1.0F, drawY + 1.0F, letterScale, AnchorsTheme.fade(0xFF3A0614, intro * in));
                drawLetter(graphics, font, letter, drawX, drawY, letterScale, AnchorsTheme.fade(color, intro * in));
            }
            letterX += advance;
        }
        float settled = AnchorsTheme.clamp01((since - 0.15F - title.length() * 0.045F - 0.2F) / 0.3F);
        if (settled > 0.0F && this.motion) {
            graphics.pose().pushMatrix();
            graphics.pose().translate(centerX - total / 2.0F, titleY);
            graphics.pose().scale(scale, scale);
            glint(graphics, font, title);
            graphics.pose().popMatrix();
        }
        AnchorsUi.isolate(graphics);
        y = Math.round(titleY + 9 * scale + 4);
        int lineHalf = Math.round(total / 2.0F + 12);
        AnchorsUi.energyLine(graphics, centerX - Math.round(lineHalf * settled), centerX + Math.round(lineHalf * settled), y,
                AnchorsTheme.CRIMSON_BRIGHT, this.motion ? this.time : 0.0D, intro * Math.max(settled, this.motion ? 0.0F : 1.0F));
        y += 6;
        int lines = height >= 200 ? 2 : height >= 150 ? 1 : 0;
        if (lines > 0) {
            int wrap = Math.max(80, Math.min(room, 380));
            List<FormattedCharSequence> text = font.split(Component.translatable("kohs_anchors.enemy.soon.text"), wrap);
            if (text.size() > lines) {
                // Said shorter before any of it is cut.
                text = font.split(Component.translatable("kohs_anchors.enemy.soon.text_short"), wrap);
            }
            float textIn = AnchorsTheme.easeOutCubic(AnchorsTheme.clamp01((since - 0.7F) / 0.4F));
            for (int index = 0; index < Math.min(lines, text.size()); index++) {
                FormattedCharSequence line = text.get(index);
                AnchorsUi.line(graphics, font, line, centerX - font.width(line) / 2, y + index * 10,
                        AnchorsTheme.fade(0xFFE8C5CE, intro * textIn));
            }
        }
    }

    /** The glint of {@link AnchorsUi#glint}, over letters one pixel apart. */
    private void glint(GuiGraphicsExtractor graphics, Font font, String text) {
        double period = 4.5D;
        double sweep = 0.85D;
        double phase = this.time % period;
        int width = spacedWidth(font, text);
        if (phase > sweep || width <= 0) {
            return;
        }
        int band = 9;
        int center = -band + (int) Math.round((width + band * 2) * (phase / sweep));
        int left = Math.max(0, center - band);
        int right = Math.min(width, center + band);
        if (left >= right) {
            return;
        }
        graphics.enableScissor(left, -1, right, 9);
        float x = 0.0F;
        for (int index = 0; index < text.length(); index++) {
            String letter = String.valueOf(text.charAt(index));
            drawLetter(graphics, font, letter, x, 0.0F, 1.0F, 0xC8FFFFFF);
            x += font.width(letter) + 1;
        }
        graphics.disableScissor();
    }

    private static int spacedWidth(Font font, String text) {
        int width = 0;
        for (int index = 0; index < text.length(); index++) {
            width += font.width(String.valueOf(text.charAt(index))) + 1;
        }
        return Math.max(0, width - 1);
    }

    private static void drawLetter(GuiGraphicsExtractor graphics, Font font, String letter, float x, float y, float scale,
            int color) {
        if (((color >>> 24) & 255) < 8) {
            return;
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
        graphics.text(font, letter, 0, 0, color, false);
        graphics.pose().popMatrix();
    }

    private static int gradient(float position) {
        float scaled = position * TITLE_COLORS.length;
        int from = (int) Math.floor(scaled) % TITLE_COLORS.length;
        int to = (from + 1) % TITLE_COLORS.length;
        return AnchorsTheme.lerp(TITLE_COLORS[from], TITLE_COLORS[to], scaled - (float) Math.floor(scaled));
    }

    /**
     * {@code texture} ({@code textureWidth} by {@code textureHeight}) centred on a point, stretched to
     * {@code width} by {@code height} (a negative width mirrors it), turned by {@code angle}, tinted.
     */
    private static void sprite(GuiGraphicsExtractor graphics, Identifier texture, int textureWidth, int textureHeight,
            float centerX, float centerY, float width, float height, float angle, int argb) {
        if (((argb >>> 24) & 255) < 3 || Math.abs(width) < 0.5F || height < 0.5F) {
            return;
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(centerX, centerY);
        if (angle != 0.0F) {
            graphics.pose().rotate(angle);
        }
        graphics.pose().scale(width / textureWidth, height / textureHeight);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, -textureWidth / 2, -textureHeight / 2, 0.0F, 0.0F, textureWidth,
                textureHeight, textureWidth, textureHeight, textureWidth, textureHeight, argb);
        graphics.pose().popMatrix();
    }

    private static int argb(int rgb, float alpha) {
        return Math.round(Math.max(0.0F, Math.min(1.0F, alpha)) * 255.0F) << 24 | (rgb & 0xFFFFFF);
    }
}
