package dev.zymekoh.kohsanchors.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * The switch between the player's anchors and the enemy's.
 *
 * <p>To the enemy's: the player's anchor lifts out of its column to the middle of the screen, the
 * background turns to the enemy's colour, and a line of it crosses the anchor slowly from top to
 * bottom: above the line it is already the enemy's (their skin and glow, as the world draws it), below it still the
 * player's. Then the enemy page opens and the anchor settles into its column. Back to the player's
 * the same runs the other way: the red drains away and the enemy's anchor turns back into the
 * player's under a violet line.</p>
 *
 * <p>The screen changes page at {@link #PAGE}, while the anchor is in the middle, and draws its
 * background with {@link #blend()}. A click skips to the end. With interface animations off it is
 * a short fade.</p>
 */
final class EnemySwitch {
    private static final float LIFT_END = 0.5F;
    private static final float VEIL_START = 0.12F;
    private static final float VEIL_END = 0.95F;
    private static final float SWAP_START = 0.72F;
    private static final float SWAP_END = 1.82F;
    static final float PAGE = 1.86F;
    private static final float END = 2.36F;
    private static final float REDUCED_END = 0.45F;

    private final boolean toEnemy;
    private final boolean motion;
    private final AnchorFigure figure;
    private final Runnable changePage;
    private final long startedAt = System.nanoTime();
    private final AnchorsLayout.Rect from;
    private AnchorsLayout.Rect to;
    private boolean pageChanged;
    private boolean done;
    private int sounds;

    /**
     * @param from where the anchor starts (its preview column), or an empty rect for the middle
     * @param changePage called once, when the other page should open behind the anchor
     */
    EnemySwitch(boolean toEnemy, boolean motion, AnchorFigure figure, AnchorsLayout.Rect from, Runnable changePage) {
        this.toEnemy = toEnemy;
        this.motion = motion;
        this.figure = figure;
        this.from = from;
        this.to = from;
        this.changePage = changePage;
        play(toEnemy ? SoundEvents.RESPAWN_ANCHOR_CHARGE : SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), toEnemy ? 0.7F : 1.2F,
                0.6F);
    }

    boolean done() {
        return this.done;
    }

    boolean toEnemy() {
        return this.toEnemy;
    }

    /** Where the anchor lands once the new page is open; the screen tells it after the change. */
    void landAt(AnchorsLayout.Rect target) {
        this.to = target;
    }

    private float elapsed() {
        return (System.nanoTime() - this.startedAt) / 1_000_000_000.0F;
    }

    /** How red the screen's background is: 0 for the player's anchors, 1 for the enemy's. */
    float blend() {
        float t = elapsed();
        float veil = this.motion ? smooth((t - VEIL_START) / (VEIL_END - VEIL_START)) : smooth(t / REDUCED_END);
        return this.toEnemy ? veil : 1.0F - veil;
    }

    /** Skips to the end: the page is changed at once if it was not yet. */
    void skip() {
        if (!this.pageChanged) {
            this.pageChanged = true;
            this.changePage.run();
        }
        this.done = true;
    }

    void render(GuiGraphicsExtractor graphics, int width, int height) {
        float t = elapsed();
        if (!this.motion) {
            if (t >= REDUCED_END / 2.0F && !this.pageChanged) {
                this.pageChanged = true;
                this.changePage.run();
            }
            if (t >= REDUCED_END) {
                this.done = true;
            }
            return;
        }
        if (t >= PAGE && !this.pageChanged) {
            this.pageChanged = true;
            this.changePage.run();
        }
        if (t >= END) {
            this.done = true;
            return;
        }
        cues(t);

        int centerX = width / 2;
        int centerY = height / 2;
        float bigScale = Math.max(24.0F, Math.min(width, height) * 0.2F);
        // The anchor's path: out of its column to the middle, and at the end into the new column.
        float x;
        float y;
        float scale;
        if (t < PAGE) {
            float lift = easeInOut(t / LIFT_END);
            AnchorsLayout.Rect start = this.from;
            float startX = start.width() > 0 ? start.centerX() : centerX;
            float startY = start.width() > 0 ? start.y() + start.height() / 2.0F : centerY;
            float startScale = start.width() > 0 ? Math.min(start.width(), start.height()) / 2.4F : bigScale * 0.4F;
            x = startX + (centerX - startX) * lift;
            y = startY + (centerY - startY) * lift;
            scale = startScale + (bigScale - startScale) * lift;
        } else {
            float settle = easeInOut((t - PAGE) / (END - PAGE));
            AnchorsLayout.Rect end = this.to;
            float endX = end.width() > 0 ? end.centerX() : centerX;
            float endY = end.width() > 0 ? end.y() + end.height() / 2.0F : centerY;
            float endScale = end.width() > 0 ? Math.min(end.width(), end.height()) / 2.4F : bigScale * 0.4F;
            x = centerX + (endX - centerX) * settle;
            y = centerY + (endY - centerY) * settle;
            scale = bigScale + (endScale - bigScale) * settle;
        }

        // The stage: the rest of the screen sinks back while the anchor is in the middle.
        float stage = smooth(t / 0.35F) * (1.0F - smooth((t - PAGE) / (END - PAGE)));
        int crimson = AnchorsTheme.lerp(0xFF7C3AED, AnchorsTheme.enemyTone(0xFFD11F4A), this.toEnemy ? smooth((t - 0.2F) / 0.8F)
                : 1.0F - smooth((t - 0.2F) / 0.8F));
        if (stage > 0.01F) {
            graphics.fill(0, 0, width, height, AnchorsTheme.withAlpha(0x06020A, Math.round(150 * stage)));
            AnchorsUi.glowEllipse(graphics, centerX, centerY, Math.round(bigScale * 3.4F), Math.round(bigScale * 2.4F),
                    crimson & 0xFFFFFF, 0.55F * stage);
            AnchorsUi.sigil(graphics, centerX, centerY + Math.round(bigScale * 0.2F), Math.round(bigScale * 2.1F), t * 1.8D,
                    crimson, 0.8F * stage, this.toEnemy ? 4.0F * blend() : 4.0F * (1.0F - blend()));
        }

        // The anchor: before the swap, the old look; during it, a line crosses it slowly; after, the new.
        float fromLook = this.toEnemy ? 0.0F : 1.0F;
        float toLook = this.toEnemy ? 1.0F : 0.0F;
        float swap = (t - SWAP_START) / (SWAP_END - SWAP_START);
        if (swap <= 0.0F || swap >= 1.0F) {
            drawFigure(graphics, x, y, scale, swap >= 1.0F ? toLook : fromLook, true, width, height, 0, height);
        } else {
            float eased = easeInOut(swap);
            int top = Math.round(y - scale * 1.05F);
            int bottom = Math.round(y + scale * 1.05F);
            int line = Math.round(top + (bottom - top) * eased);
            // Above the line the new look, below it the old one.
            drawFigure(graphics, x, y, scale, toLook, true, width, height, 0, line);
            drawFigure(graphics, x, y, scale, fromLook, false, width, height, line, height);
            int lineColor = this.toEnemy ? AnchorsTheme.enemyTone(0xFF315C) : 0xC084FC;
            int half = Math.round(scale * 1.3F);
            graphics.fillGradient(Math.round(x) - half, line - 10, Math.round(x) + half, line, 0,
                    AnchorsTheme.withAlpha(lineColor, 150));
            graphics.fill(Math.round(x) - half, line, Math.round(x) + half, line + 1, AnchorsTheme.withAlpha(0xFFF7FF, 230));
            for (int spark = 0; spark < 10; spark++) {
                double phase = (t * 3.1D + spark * 0.37D) % 1.0D;
                int sx = Math.round(x) - half + (int) ((spark * 53 + t * 240.0D) % (half * 2));
                int sy = line - (int) Math.round(phase * 14.0D);
                graphics.fill(sx, sy, sx + 2, sy + 2, AnchorsTheme.withAlpha(lineColor, (int) Math.round(220.0D * (1.0D - phase))));
            }
        }
        // A ring leaves the anchor when the new look is complete.
        float ring = (t - SWAP_END) / 0.4F;
        if (ring > 0.0F && ring < 1.0F) {
            AnchorsUi.ring(graphics, Math.round(x), Math.round(y), Math.round(scale * (0.6F + ring * 1.6F)), 2,
                    AnchorsTheme.withAlpha(this.toEnemy ? AnchorsTheme.enemyTone(0xFF315C) : 0xC084FC,
                            Math.round(220 * (1.0F - ring))));
        }
    }

    private void drawFigure(GuiGraphicsExtractor graphics, float x, float y, float scale, float look, boolean turn, int width,
            int height, int clipTop, int clipBottom) {
        if (clipBottom <= clipTop) {
            return;
        }
        graphics.enableScissor(0, Math.max(0, clipTop), width, Math.min(height, clipBottom));
        this.figure.draw(graphics, x, y, scale, 1.0F, look, true, true, turn);
        graphics.disableScissor();
    }

    private void cues(float t) {
        if (this.sounds == 0 && t >= SWAP_START) {
            play(SoundEvents.RESPAWN_ANCHOR_AMBIENT, this.toEnemy ? 0.8F : 1.4F, 0.9F);
            this.sounds = 1;
        }
        if (this.sounds == 1 && t >= SWAP_END) {
            play(this.toEnemy ? SoundEvents.RESPAWN_ANCHOR_CHARGE : SoundEvents.RESPAWN_ANCHOR_SET_SPAWN,
                    this.toEnemy ? 0.6F : 1.3F, 0.8F);
            this.sounds = 2;
        }
    }

    private static void play(net.minecraft.sounds.SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    private static float smooth(float value) {
        float t = AnchorsTheme.clamp01(value);
        return t * t * (3.0F - 2.0F * t);
    }

    private static float easeInOut(float value) {
        return AnchorsTheme.easeInOutSine(value);
    }
}
