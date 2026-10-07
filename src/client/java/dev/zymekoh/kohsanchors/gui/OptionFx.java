package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.bridge.BridgeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * What an Anchors Server option does, played once over the anchor column when it is switched on.
 *
 * <ul>
 *   <li>{@link Kind#CHAIN}, the no-wait chain: anchors around the anchor go off one after another,
 *   a chain of light linking them.</li>
 *   <li>{@link Kind#INSTANT}, the instant detonation click: a bolt strikes the anchor, a short
 *   local flash and two shockwaves.</li>
 *   <li>{@link Kind#ENEMY}, better glow enemy anchors: a red radar sweep finds an enemy's anchor
 *   and brackets it.</li>
 *   <li>{@link Kind#LATENCY}, real latency: a ping runs from the anchor to the server and back,
 *   and the round trip appears.</li>
 *   <li>{@link Kind#DOUBLE}, the instant double anchor: two clicks, the anchor goes off and the next
 *   one drops into its place at once.</li>
 * </ul>
 * <p>Pure decoration, timed in real time; nothing waits for it.</p>
 */
final class OptionFx {
    enum Kind { CHAIN, INSTANT, ENEMY, LATENCY, DOUBLE }

    private static final float LENGTH = 1.35F;

    private final Kind kind;
    private final long startedAt = System.nanoTime();
    private int sounds;

    OptionFx(Kind kind) {
        this.kind = kind;
    }

    boolean done() {
        return elapsed() >= LENGTH;
    }

    private float elapsed() {
        return (System.nanoTime() - this.startedAt) / 1_000_000_000.0F;
    }

    void render(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.Rect area, double seconds) {
        float t = elapsed();
        if (t >= LENGTH || area.width() <= 0) {
            return;
        }
        int centerX = area.centerX();
        int centerY = area.y() + area.height() / 2;
        float size = Math.min(area.width(), area.height()) / 2.4F;
        float out = 1.0F - AnchorsTheme.clamp01((t - (LENGTH - 0.3F)) / 0.3F);
        graphics.enableScissor(area.x(), area.y(), area.right(), area.bottom());
        switch (this.kind) {
            case CHAIN -> chain(graphics, centerX, centerY, size, t, out);
            case INSTANT -> instant(graphics, area, centerX, centerY, size, t, out);
            case ENEMY -> enemy(graphics, centerX, centerY, size, t, out, seconds);
            case LATENCY -> latency(graphics, font, area, centerX, centerY, size, t, out, seconds);
            case DOUBLE -> doubleAnchor(graphics, font, centerX, centerY, size, t, out);
        }
        graphics.disableScissor();
    }

    /** Five anchors on an arc go off one after another, linked by a chain of light. */
    private void chain(GuiGraphicsExtractor graphics, int centerX, int centerY, float size, float t, float out) {
        int count = 5;
        float step = 0.16F;
        for (int index = 0; index < count; index++) {
            double angle = Math.PI * (1.15D + index * 0.175D);
            int x = centerX + (int) Math.round(Math.cos(angle) * size * 1.35D);
            int y = centerY + (int) Math.round(Math.sin(angle) * size * 0.95D) + Math.round(size * 0.55F);
            float at = 0.12F + index * step;
            if (t < at) {
                AnchorsUi.miniAnchor(graphics, x - 4, y - 4, 4.0F, 0.8F * out);
                continue;
            }
            float since = t - at;
            if (index > 0) {
                double before = Math.PI * (1.15D + (index - 1) * 0.175D);
                int px = centerX + (int) Math.round(Math.cos(before) * size * 1.35D);
                int py = centerY + (int) Math.round(Math.sin(before) * size * 0.95D) + Math.round(size * 0.55F);
                AnchorsUi.segment(graphics, px, py, x, y, 1, AnchorsTheme.withAlpha(0xE9D5FF, Math.round(200 * out)));
            }
            if (this.sounds <= index) {
                play(SoundEvents.RESPAWN_ANCHOR_CHARGE, 1.2F + index * 0.12F, 0.45F);
                this.sounds = index + 1;
            }
            GlowstoneGuardWarning.drawBlast(graphics, x, y, Math.max(8, Math.round(size * 0.35F)), Math.round(size * 2.0F),
                    since);
        }
    }

    /** A bolt from the top of the column onto the anchor, a flash and two shockwaves. */
    private void instant(GuiGraphicsExtractor graphics, AnchorsLayout.Rect area, int centerX, int centerY, float size, float t,
            float out) {
        if (this.sounds == 0) {
            play(SoundEvents.LIGHTNING_BOLT_THUNDER, 1.8F, 0.25F);
            play(SoundEvents.GENERIC_EXPLODE.value(), 1.4F, 0.35F);
            this.sounds = 1;
        }
        if (t < 0.22F) {
            AnchorFx.lightning(graphics, centerX + 6, area.y(), centerX, centerY - Math.round(size * 0.6F), (int) (t * 40.0F),
                    0xC084FC, 1.0F - t / 0.22F);
        }
        if (t < 0.12F) {
            AnchorsUi.glowEllipse(graphics, centerX, centerY, Math.round(size * 1.6F), Math.round(size * 1.4F), 0xFFF7FF,
                    0.9F * (1.0F - t / 0.12F));
        }
        for (int wave = 0; wave < 2; wave++) {
            float progress = (t - 0.05F - wave * 0.16F) / 0.7F;
            if (progress > 0.0F && progress < 1.0F) {
                AnchorsUi.ring(graphics, centerX, centerY, Math.round(size * (0.5F + progress * 1.8F)), 2,
                        AnchorsTheme.withAlpha(wave == 0 ? 0xFFF7FF : 0xC084FC, Math.round(230 * (1.0F - progress) * out)));
            }
        }
    }

    /** Two clicks: the anchor goes off, and the next one drops into its place with a bounce. */
    private void doubleAnchor(GuiGraphicsExtractor graphics, Font font, int centerX, int centerY, float size, float t, float out) {
        float blastAt = 0.1F;
        if (t < blastAt) {
            AnchorsUi.miniAnchor(graphics, centerX - 4, centerY - 4, 4.0F, out);
        } else {
            if (this.sounds == 0) {
                play(SoundEvents.GENERIC_EXPLODE.value(), 1.5F, 0.3F);
                this.sounds = 1;
            }
            GlowstoneGuardWarning.drawBlast(graphics, centerX, centerY, Math.max(8, Math.round(size * 0.4F)),
                    Math.round(size * 2.0F), t - blastAt);
        }
        float drop = AnchorsTheme.clamp01((t - 0.16F) / 0.2F);
        if (drop > 0.0F) {
            int y = centerY - Math.round((1.0F - AnchorsTheme.easeOutBack(drop)) * size * 1.4F);
            AnchorsUi.miniAnchor(graphics, centerX - 4, y - 4, 4.0F, out);
            if (drop >= 1.0F && this.sounds == 1) {
                play(SoundEvents.RESPAWN_ANCHOR_CHARGE, 0.7F, 0.45F);
                this.sounds = 2;
            }
        }
        if (t > 0.36F) {
            float in = AnchorsTheme.clamp01((t - 0.36F) / 0.15F);
            AnchorsUi.label(graphics, font, "x2", centerX + Math.round(size * 0.55F), centerY - Math.round(size * 0.75F),
                    AnchorsTheme.withAlpha(0xFFF7FF, Math.round(255 * in * out)), true);
        }
    }

    /** A red sweep finds an enemy anchor to the side and brackets it. */
    private void enemy(GuiGraphicsExtractor graphics, int centerX, int centerY, float size, float t, float out, double seconds) {
        if (this.sounds == 0) {
            play(SoundEvents.WARDEN_HEARTBEAT, 1.6F, 0.6F);
            this.sounds = 1;
        }
        int radius = Math.round(size * 1.35F);
        AnchorFx.radar(graphics, centerX, centerY, radius, seconds * 4.5D, 0xFF315C, 0.9F * out);
        int targetX = centerX + Math.round(size * 0.85F);
        int targetY = centerY - Math.round(size * 0.65F);
        float found = AnchorsTheme.clamp01((t - 0.35F) / 0.25F);
        if (found > 0.0F) {
            if (this.sounds == 1) {
                play(SoundEvents.AMETHYST_BLOCK_CHIME, 0.7F, 0.9F);
                this.sounds = 2;
            }
            int bracket = Math.round(size * (0.55F - 0.2F * AnchorsTheme.easeOutBack(found)));
            int color = AnchorsTheme.withAlpha(0xFF315C, Math.round(255 * found * out));
            for (int corner = 0; corner < 4; corner++) {
                int sx = corner % 2 == 0 ? -1 : 1;
                int sy = corner < 2 ? -1 : 1;
                int cx = targetX + sx * bracket;
                int cy = targetY + sy * bracket;
                graphics.fill(Math.min(cx, cx - sx * 4), cy, Math.max(cx, cx - sx * 4) + 1, cy + 1, color);
                graphics.fill(cx, Math.min(cy, cy - sy * 4), cx + 1, Math.max(cy, cy - sy * 4) + 1, color);
            }
            AnchorsUi.miniAnchor(graphics, targetX - 4, targetY - 4, 4.0F, found * out);
            AnchorsUi.glowEllipse(graphics, targetX, targetY, Math.round(size * 0.4F), Math.round(size * 0.35F), 0xFF315C,
                    0.55F * found * out);
        }
    }

    /** A ping to the server and back; the round trip shows when it returns. */
    private void latency(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.Rect area, int centerX, int centerY, float size,
            float t, float out, double seconds) {
        int serverSize = Math.max(10, Math.round(size * 0.55F));
        int serverX = area.right() - serverSize - 8;
        int serverY = area.y() + 8;
        AnchorFx.server(graphics, serverX, serverY, serverSize, 0xFF000000 | AnchorFx.GREEN, out, seconds);
        int fromX = centerX;
        int fromY = centerY - Math.round(size * 0.5F);
        int toX = serverX + serverSize / 2;
        int toY = serverY + serverSize;
        float go = AnchorsTheme.clamp01(t / 0.45F);
        float back = AnchorsTheme.clamp01((t - 0.45F) / 0.45F);
        if (this.sounds == 0) {
            play(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.6F, 0.5F);
            this.sounds = 1;
        }
        if (go < 1.0F) {
            dot(graphics, fromX + (toX - fromX) * go, fromY + (toY - fromY) * go, 0xC084FC, out);
        } else if (back < 1.0F) {
            dot(graphics, toX + (fromX - toX) * back, toY + (fromY - toY) * back, AnchorFx.GREEN, out);
        } else {
            if (this.sounds == 1) {
                play(SoundEvents.EXPERIENCE_ORB_PICKUP, 2.0F, 0.5F);
                this.sounds = 2;
            }
            float ring = AnchorsTheme.clamp01((t - 0.9F) / 0.4F);
            AnchorsUi.ring(graphics, centerX, centerY, Math.round(size * (0.6F + ring * 1.2F)), 1,
                    AnchorsTheme.withAlpha(AnchorFx.GREEN, Math.round(220 * (1.0F - ring))));
            float roundTrip = BridgeClient.roundTripMillis();
            String text = roundTrip >= 0.0F ? Math.round(roundTrip) + " ms" : "PING";
            AnchorsUi.label(graphics, font, text, centerX - font.width(text) / 2, area.y() + 6,
                    AnchorsTheme.withAlpha(AnchorFx.GREEN_BRIGHT, Math.round(255 * out)), true);
        }
        for (int wave = 0; wave < 3; wave++) {
            float progress = ((float) (seconds * 1.6D) + wave / 3.0F) % 1.0F;
            AnchorsUi.ring(graphics, centerX, centerY, Math.round(size * (0.4F + progress * 1.1F)), 1,
                    AnchorsTheme.withAlpha(0xC084FC, Math.round(110 * (1.0F - progress) * out)));
        }
    }

    private static void dot(GuiGraphicsExtractor graphics, float x, float y, int color, float alpha) {
        int px = Math.round(x);
        int py = Math.round(y);
        AnchorsUi.glowEllipse(graphics, px, py, 6, 6, color, 0.7F * alpha);
        graphics.fill(px - 1, py - 1, px + 2, py + 2, AnchorsTheme.withAlpha(0xFFFFFF, Math.round(255 * alpha)));
    }

    private static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }
}
