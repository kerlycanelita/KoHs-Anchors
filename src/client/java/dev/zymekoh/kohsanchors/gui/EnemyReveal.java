package dev.zymekoh.kohsanchors.gui;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * Enemy anchors switched on or off on their own page.
 *
 * <p>On: the red anchor under the switch charges, a crimson shockwave and blades of light burst out
 * of it, and it flies along an arc into the anchor column while the tabs are dealt in and the
 * options cascade down behind it; embers trail it and a ring marks where it lands. Off: it lifts out
 * of the column back to the middle, under the switch. The screen changes the page at once; this
 * only draws the anchor on its way and the light around it. A click ends it.</p>
 */
final class EnemyReveal {
    private static final float ON_LENGTH = 1.3F;
    private static final float OFF_LENGTH = 0.65F;
    private static final float CHARGE_END = 0.3F;
    private static final float FLIGHT_END = 0.95F;

    private final boolean on;
    private final boolean motion;
    private final AnchorFigure figure;
    private final Supplier<AnchorsLayout.Rect> from;
    private final Supplier<AnchorsLayout.Rect> to;
    private final long startedAt = System.nanoTime();
    private boolean done;
    private int sounds;

    EnemyReveal(boolean on, boolean motion, AnchorFigure figure, Supplier<AnchorsLayout.Rect> from,
            Supplier<AnchorsLayout.Rect> to) {
        this.on = on;
        this.motion = motion;
        this.figure = figure;
        this.from = from;
        this.to = to;
        play(on ? SoundEvents.RESPAWN_ANCHOR_CHARGE : SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), on ? 0.6F : 1.2F, 0.9F);
    }

    boolean done() {
        return this.done;
    }

    void skip() {
        this.done = true;
    }

    private float elapsed() {
        return (System.nanoTime() - this.startedAt) / 1_000_000_000.0F;
    }

    /** How far the options and tabs have come in: the screen deals them in behind the anchor. */
    float reveal() {
        if (!this.on) {
            return 0.0F;
        }
        return AnchorsTheme.clamp01((elapsed() - CHARGE_END) / (FLIGHT_END - CHARGE_END));
    }

    void render(GuiGraphicsExtractor graphics, int width, int height) {
        float t = elapsed();
        float length = this.on ? ON_LENGTH : OFF_LENGTH;
        if (!this.motion || t >= length) {
            this.done = true;
            return;
        }
        AnchorsLayout.Rect start = this.from.get();
        AnchorsLayout.Rect end = this.to.get();
        float startX = start.centerX();
        float startY = start.y() + start.height() / 2.0F;
        float startScale = Math.max(12.0F, Math.min(start.width(), start.height()) / 2.4F);
        float endX = end.centerX();
        float endY = end.y() + end.height() / 2.0F;
        float endScale = Math.max(12.0F, Math.min(end.width(), end.height()) / 2.4F);
        double seconds = System.nanoTime() / 1_000_000_000.0D;

        if (!this.on) {
            float move = AnchorsTheme.easeInOutSine(t / OFF_LENGTH);
            float x = startX + (endX - startX) * move;
            float y = startY + (endY - startY) * move - (float) Math.sin(move * Math.PI) * startScale * 0.8F;
            float scale = startScale + (endScale - startScale) * move;
            this.figure.draw(graphics, x, y, scale, 1.0F, 1.0F, true, true, true, AnchorFigure.enemyColor(), 1.0F);
            return;
        }

        int cx = Math.round(startX);
        int cy = Math.round(startY);
        if (t < CHARGE_END) {
            // It gathers itself: rings pulled in, the glow swelling.
            float gather = t / CHARGE_END;
            for (int ring = 0; ring < 3; ring++) {
                float r = 1.0F - ((gather + ring / 3.0F) % 1.0F);
                AnchorsUi.ring(graphics, cx, cy, Math.round(startScale * (0.6F + r * 2.4F)), 1,
                        AnchorsTheme.withAlpha(0xFF315C, Math.round(220 * (1.0F - r))));
            }
            AnchorsUi.glowEllipse(graphics, cx, cy, Math.round(startScale * (1.2F + gather)), Math.round(startScale * (1.1F + gather)),
                    0xFF315C, 0.5F + 0.4F * gather);
            int shake = Math.round((float) Math.sin(System.nanoTime() / 6_000_000.0D) * 2.0F * gather);
            this.figure.draw(graphics, startX + shake, startY, startScale * (1.0F + 0.12F * gather), 1.0F, 1.0F, true, true, true,
                    AnchorFigure.enemyColor(), 1.0F);
            return;
        }
        if (this.sounds == 0) {
            play(SoundEvents.FIREWORK_ROCKET_BLAST, 0.6F, 0.7F);
            play(SoundEvents.RESPAWN_ANCHOR_SET_SPAWN, 0.8F, 0.8F);
            this.sounds = 1;
        }
        float burst = (t - CHARGE_END) / 0.6F;
        if (burst < 1.0F) {
            // The shockwave and the blades of light from where it stood.
            AnchorsUi.ring(graphics, cx, cy, Math.round(startScale * (0.6F + burst * 5.0F)), 2,
                    AnchorsTheme.withAlpha(0xFF315C, Math.round(230 * (1.0F - burst))));
            AnchorsUi.ring(graphics, cx, cy, Math.round(startScale * (0.4F + burst * 3.4F)), 1,
                    AnchorsTheme.withAlpha(0xFFF7FF, Math.round(200 * (1.0F - burst))));
            for (int blade = 0; blade < 10; blade++) {
                double angle = blade * Math.PI / 5.0D + 0.3D;
                float inner = startScale * (0.8F + burst * 1.5F);
                float outer = startScale * (1.4F + burst * 4.2F);
                AnchorsUi.segment(graphics, (float) (cx + Math.cos(angle) * inner), (float) (cy + Math.sin(angle) * inner),
                        (float) (cx + Math.cos(angle) * outer), (float) (cy + Math.sin(angle) * outer), 2,
                        AnchorsTheme.withAlpha(blade % 2 == 0 ? 0xFF6A86 : 0xFFF7FF, Math.round(210 * (1.0F - burst))));
            }
        }
        float flight = AnchorsTheme.clamp01((t - CHARGE_END) / (FLIGHT_END - CHARGE_END));
        float eased = AnchorsTheme.easeInOutSine(flight);
        float x = startX + (endX - startX) * eased;
        float arc = (float) Math.sin(eased * Math.PI) * Math.max(startScale, endScale) * 1.6F;
        float y = startY + (endY - startY) * eased - arc;
        float scale = startScale + (endScale - startScale) * eased;
        if (flight < 1.0F) {
            // Embers trailing it.
            for (int ember = 0; ember < 14; ember++) {
                float back = ember / 14.0F;
                float trail = Math.max(0.0F, eased - back * 0.25F);
                float ex = startX + (endX - startX) * trail + (float) Math.sin(seconds * 9.0D + ember) * 3.0F;
                float ey = startY + (endY - startY) * trail - (float) Math.sin(trail * Math.PI) * Math.max(startScale, endScale) * 1.6F
                        + ember % 3;
                int size = ember % 4 == 0 ? 3 : 2;
                graphics.fill(Math.round(ex), Math.round(ey), Math.round(ex) + size, Math.round(ey) + size,
                        AnchorsTheme.withAlpha(ember % 2 == 0 ? 0xFF315C : 0xFFC46B, Math.round(220 * (1.0F - back))));
            }
        } else if (this.sounds == 1) {
            play(SoundEvents.ANVIL_LAND, 1.6F, 0.25F);
            play(SoundEvents.RESPAWN_ANCHOR_CHARGE, 1.2F, 0.7F);
            this.sounds = 2;
        }
        float land = (t - FLIGHT_END) / (ON_LENGTH - FLIGHT_END);
        if (land > 0.0F && land < 1.0F) {
            AnchorsUi.ring(graphics, Math.round(endX), Math.round(endY), Math.round(endScale * (0.7F + land * 1.8F)), 2,
                    AnchorsTheme.withAlpha(0xFF315C, Math.round(230 * (1.0F - land))));
            AnchorsUi.glowEllipse(graphics, Math.round(endX), Math.round(endY), Math.round(endScale * 1.6F),
                    Math.round(endScale * 1.4F), 0xFF315C, 0.5F * (1.0F - land));
        }
        this.figure.draw(graphics, x, y, scale, 1.0F, 1.0F, true, true, true, 0xFF3B4E, 0.0F);
    }

    private static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }
}
