package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.compat.AnchorBlockPreview;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The warning shown before an advanced, not secure option is switched on, and the ritual that
 * plays once the player accepts it.
 *
 * <p>The warning cannot be skipped by accident: the confirm button fills up for
 * {@link #READ_NANOS} before it answers, the title says in large letters that the player is
 * warned, and the text says to check whether the server allows it. The motion is strong but
 * restrained: slow crimson pulses, one impact, a rare glitch, nothing that flashes the whole
 * screen.</p>
 *
 * <p>The ritual: an anchor falls from above and slams down with a shockwave, the ritual circle
 * lights its four charges one by one, and the anchor rises away in violet light. With interface
 * animations off it is a short fade.</p>
 */
final class AnchorsWarning {
    private static final long OPEN_NANOS = 260_000_000L;
    private static final long READ_NANOS = 2_200_000_000L;
    private static final long CLOSE_NANOS = 180_000_000L;
    private static final long RITUAL_NANOS = 2_500_000_000L;
    private static final long RITUAL_REDUCED_NANOS = 700_000_000L;
    private static final float FALL_END = 0.22F;
    private static final float CHARGE_START = 0.30F;
    private static final float CHARGE_STEP = 0.075F;
    private static final float RISE_START = 0.66F;
    private static final float RISE_END = 0.90F;

    private enum Phase { WARNING, RITUAL, CLOSING, DONE }

    private final String optionName;
    private final Runnable onConfirm;
    private final boolean motion;
    private final AnchorBlockPreview model = new AnchorBlockPreview();
    private final Quaternionf rotation = new Quaternionf();
    private final Vector3f translation = new Vector3f();

    private final long openedAt = System.nanoTime();
    private Phase phase = Phase.WARNING;
    private long phaseStartedAt = System.nanoTime();
    private boolean confirmed;
    private float cancelHover;
    private float confirmHover;
    private long lastFrame = System.nanoTime();
    private int soundsPlayed;

    AnchorsWarning(Component optionName, boolean motion, Runnable onConfirm) {
        this.optionName = optionName.getString();
        this.motion = motion;
        this.onConfirm = onConfirm;
    }

    boolean done() {
        return this.phase == Phase.DONE;
    }

    /** Whether the player accepted; the option is switched on the moment they do. */
    boolean confirmed() {
        return this.confirmed;
    }

    private boolean readyToConfirm(long now) {
        return this.phase == Phase.WARNING && now - this.openedAt >= READ_NANOS;
    }

    // ------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------

    boolean mouseClicked(int width, int height, double mouseX, double mouseY, int button) {
        if (button != 0) {
            return true;
        }
        long now = System.nanoTime();
        if (this.phase == Phase.RITUAL) {
            // A click during the ritual only hurries it to the end.
            skipRitual(now);
            return true;
        }
        if (this.phase != Phase.WARNING) {
            return true;
        }
        AnchorsLayout.Modal modal = AnchorsLayout.modal(width, height);
        if (modal.cancel().contains(mouseX, mouseY)) {
            cancel(now);
        } else if (modal.confirm().contains(mouseX, mouseY) && readyToConfirm(now)) {
            confirm(now);
        }
        return true;
    }

    /** Escape cancels; Enter confirms once the warning has been read. */
    boolean keyPressed(int key) {
        long now = System.nanoTime();
        if (key == 256) {
            if (this.phase == Phase.WARNING) {
                cancel(now);
            } else if (this.phase == Phase.RITUAL) {
                skipRitual(now);
            }
            return true;
        }
        if ((key == 257 || key == 335) && readyToConfirm(now)) {
            confirm(now);
            return true;
        }
        return true;
    }

    private void cancel(long now) {
        this.phase = Phase.CLOSING;
        this.phaseStartedAt = now;
        play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 0.8F));
    }

    private void confirm(long now) {
        this.confirmed = true;
        this.onConfirm.run();
        this.phase = Phase.RITUAL;
        this.phaseStartedAt = now;
        this.soundsPlayed = 0;
    }

    private void skipRitual(long now) {
        this.phase = Phase.CLOSING;
        this.phaseStartedAt = now;
    }

    // ------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------

    void render(GuiGraphicsExtractor graphics, Font font, int width, int height, int mouseX, int mouseY) {
        long now = System.nanoTime();
        float frameMillis = Math.min(50.0F, (now - this.lastFrame) / 1_000_000.0F);
        this.lastFrame = now;
        double seconds = now / 1_000_000_000.0D;
        float response = 1.0F - (float) Math.exp(-frameMillis / 60.0F);

        switch (this.phase) {
            case WARNING -> {
                float open = this.motion ? AnchorsTheme.easeOutCubic((now - this.openedAt) / (float) OPEN_NANOS) : 1.0F;
                drawDim(graphics, width, height, open, seconds, true);
                drawWarning(graphics, font, width, height, mouseX, mouseY, open, now, seconds, response);
            }
            case RITUAL -> {
                long duration = this.motion ? RITUAL_NANOS : RITUAL_REDUCED_NANOS;
                float t = Math.min(1.0F, (now - this.phaseStartedAt) / (float) duration);
                drawDim(graphics, width, height, 1.0F, seconds, false);
                drawRitual(graphics, font, width, height, t, now, seconds);
                if (t >= 1.0F) {
                    this.phase = Phase.CLOSING;
                    this.phaseStartedAt = now;
                }
            }
            case CLOSING -> {
                float t = Math.min(1.0F, (now - this.phaseStartedAt) / (float) CLOSE_NANOS);
                drawDim(graphics, width, height, 1.0F - t, seconds, !this.confirmed);
                if (t >= 1.0F) {
                    this.phase = Phase.DONE;
                }
            }
            case DONE -> {
            }
        }
    }

    /** The dark veil over the screen, with a slow crimson pulse creeping in from the edges. */
    private void drawDim(GuiGraphicsExtractor graphics, int width, int height, float strength, double seconds,
            boolean danger) {
        if (strength <= 0.01F) {
            return;
        }
        graphics.fill(0, 0, width, height, AnchorsTheme.withAlpha(0x06020A, Math.round(200 * strength)));
        float pulse = this.motion ? 0.55F + 0.45F * AnchorsTheme.pulse(seconds, 1.8D) : 0.7F;
        int edge = danger ? 0xA31234 : 0x7C3AED;
        int depth = Math.max(8, height / 5);
        graphics.fillGradient(0, 0, width, depth, AnchorsTheme.withAlpha(edge, Math.round(120 * pulse * strength)), 0x00000000);
        graphics.fillGradient(0, height - depth, width, height, 0x00000000,
                AnchorsTheme.withAlpha(edge, Math.round(120 * pulse * strength)));
        if (this.motion) {
            // Faint scanlines, the texture of a system screaming quietly.
            for (int y = (int) (seconds * 18.0D) % 4; y < height; y += 4) {
                graphics.fill(0, y, width, y + 1, AnchorsTheme.withAlpha(0x000000, Math.round(34 * strength)));
            }
        }
    }

    private void drawWarning(GuiGraphicsExtractor graphics, Font font, int width, int height, int mouseX, int mouseY,
            float open, long now, double seconds, float response) {
        AnchorsLayout.Modal modal = AnchorsLayout.modal(width, height);
        AnchorsLayout.Rect box = modal.box();
        float grow = 0.92F + 0.08F * open;
        int boxWidth = Math.round(box.width() * grow);
        int boxHeight = Math.round(box.height() * grow);
        int boxX = box.centerX() - boxWidth / 2;
        int boxY = box.centerY() - boxHeight / 2;

        // An energy ring bursting out of the modal as it opens.
        if (this.motion && open < 1.0F) {
            int radius = Math.round(Math.max(box.width(), box.height()) * (0.3F + open * 0.6F));
            AnchorsUi.ring(graphics, box.centerX(), box.centerY(), radius, 2,
                    AnchorsTheme.withAlpha(0xFF315C, Math.round(170 * (1.0F - open))));
        }
        AnchorsUi.halo(graphics, boxX, boxY, boxWidth, boxHeight, AnchorsTheme.CRIMSON, 6, 0.8F * open);
        AnchorsUi.panel(graphics, boxX, boxY, boxWidth, boxHeight, AnchorsTheme.fade(AnchorsTheme.CRIMSON_GLASS_TOP, open),
                AnchorsTheme.fade(AnchorsTheme.CRIMSON_GLASS_BOTTOM, open));
        AnchorsUi.roundedOutline(graphics, boxX, boxY, boxWidth, boxHeight, AnchorsTheme.fade(AnchorsTheme.CRIMSON_BRIGHT, open));
        AnchorsUi.bladeCorners(graphics, boxX, boxY, boxWidth, boxHeight, 7, AnchorsTheme.fade(0xFFFFD6DE, open));
        if (open < 0.98F) {
            return;
        }
        if (this.motion) {
            AnchorsUi.comets(graphics, box.x(), box.y(), box.width(), box.height(), seconds, 0xFF9AB0);
        }

        int padding = modal.padding();
        int innerLeft = box.x() + padding;
        int innerWidth = box.width() - padding * 2;
        int y = box.y() + padding;
        int bottomLimit = modal.cancel().y() - 6;

        // The sigil: a crimson warning glyph inside a turning ring, when there is room for it.
        int sigilSize = Math.min(30, (box.height() - 150) / 2 + 20);
        if (sigilSize >= 18) {
            int centerX = box.centerX();
            int centerY = y + sigilSize / 2;
            AnchorsUi.sigil(graphics, centerX, centerY, sigilSize / 2 + 4, this.motion ? seconds * 2.2D : 0.0D,
                    AnchorsTheme.CRIMSON_BRIGHT, 0.9F, 0.0F);
            AnchorsUi.warningGlyph(graphics, centerX, centerY - sigilSize / 3, Math.round(sigilSize * 0.62F),
                    AnchorsTheme.CRIMSON_BRIGHT, 0xFF1A0308);
            y += sigilSize + 6;
        }

        // The title slams in, shakes for a moment, and now and then splits into colour.
        String title = Component.translatable("kohs_anchors.warning.title").getString();
        float titleScale = Math.max(1.0F, Math.min(3.0F, innerWidth / (float) Math.max(1, font.width(title))));
        titleScale = Math.min(titleScale, Math.max(1.0F, (bottomLimit - y - 34) / 9.0F));
        float slam = this.motion ? AnchorsTheme.easeOutCubic((now - this.openedAt - OPEN_NANOS) / 300_000_000.0F) : 1.0F;
        float scale = titleScale * (1.25F - 0.25F * slam);
        int shake = this.motion && slam < 1.0F ? Math.round((float) Math.sin(now / 9_000_000.0D) * 3.0F * (1.0F - slam)) : 0;
        int titleY = y;
        int titleCenter = box.centerX() + shake;
        if (this.motion && (seconds % 2.9D) < 0.07D) {
            AnchorsUi.bigText(graphics, font, title, titleCenter - 2, titleY, scale, 0x9052F2FF, false);
            AnchorsUi.bigText(graphics, font, title, titleCenter + 2, titleY, scale, 0x90E83EAF, false);
        }
        AnchorsUi.bigText(graphics, font, title, titleCenter + 1, titleY + 1, scale, 0xFFA31234, false);
        AnchorsUi.bigText(graphics, font, title, titleCenter, titleY, scale, 0xFFFFF7FF, false);
        y += Math.round(9 * titleScale) + 5;

        String subtitle = Component.translatable("kohs_anchors.warning.subtitle", this.optionName).getString();
        subtitle = AnchorsUi.fit(font, subtitle, innerWidth);
        AnchorsUi.label(graphics, font, subtitle, box.centerX() - font.width(subtitle) / 2, y, 0xFFFF6A86, true);
        y += 13;
        AnchorsUi.energyLine(graphics, innerLeft, innerLeft + innerWidth, y - 3, AnchorsTheme.CRIMSON_BRIGHT, seconds, 1.0F);

        List<FormattedCharSequence> lines = font.split(Component.translatable("kohs_anchors.warning.body"),
                Math.max(40, innerWidth));
        for (FormattedCharSequence line : lines) {
            if (y + 9 > bottomLimit) {
                break;
            }
            AnchorsUi.line(graphics, font, line, box.centerX() - font.width(line) / 2, y, AnchorsTheme.TEXT);
            y += 10;
        }

        long elapsed = now - this.openedAt;
        boolean ready = elapsed >= READ_NANOS;
        boolean overCancel = modal.cancel().contains(mouseX, mouseY);
        boolean overConfirm = modal.confirm().contains(mouseX, mouseY) && ready;
        this.cancelHover += ((overCancel ? 1.0F : 0.0F) - this.cancelHover) * response;
        this.confirmHover += ((overConfirm ? 1.0F : 0.0F) - this.confirmHover) * response;
        AnchorsLayout.Rect cancel = modal.cancel();
        AnchorsButton.draw(graphics, cancel.x(), cancel.y(), cancel.width(), cancel.height(),
                Component.translatable("kohs_anchors.warning.cancel").getString(), false, false, this.cancelHover, 0.0F,
                1.0F, -1.0F);
        AnchorsLayout.Rect confirm = modal.confirm();
        String confirmText = ready ? Component.translatable("kohs_anchors.warning.confirm").getString()
                : Component.translatable("kohs_anchors.warning.wait",
                        (int) Math.ceil((READ_NANOS - elapsed) / 1_000_000_000.0D)).getString();
        AnchorsButton.draw(graphics, confirm.x(), confirm.y(), confirm.width(), confirm.height(), confirmText, true, true,
                this.confirmHover, 0.0F, 1.0F, ready ? -1.0F : elapsed / (float) READ_NANOS);
    }

    // ------------------------------------------------------------------------------------------
    // The ritual
    // ------------------------------------------------------------------------------------------

    private void drawRitual(GuiGraphicsExtractor graphics, Font font, int width, int height, float t, long now,
            double seconds) {
        int centerX = width / 2;
        int centerY = height / 2 - Math.min(20, height / 12);
        int size = Math.max(40, Math.min(150, Math.min(width, height) / 3));
        if (!this.motion) {
            float alpha = t < 0.5F ? t * 2.0F : 1.0F - (t - 0.5F) * 2.0F;
            AnchorsUi.glowEllipse(graphics, centerX, centerY, size, size, 0x9B4DFF, alpha);
            drawAnchor(graphics, centerX, centerY, size, 4, 225.0F, alpha);
            activatedText(graphics, font, centerX, centerY + size / 2 + 12, alpha, 1.0F);
            return;
        }

        float fall = Math.min(1.0F, t / FALL_END);
        float afterImpact = t - FALL_END;
        int shake = 0;
        if (afterImpact >= 0.0F && afterImpact < 0.12F) {
            float decay = 1.0F - afterImpact / 0.12F;
            shake = Math.round((float) Math.sin(now / 7_000_000.0D) * 5.0F * decay);
        }

        // The ritual circle wakes after the impact and lights one charge after another.
        int charges = 0;
        for (int node = 0; node < 4; node++) {
            if (t >= CHARGE_START + node * CHARGE_STEP) {
                charges = node + 1;
            }
        }
        float circle = AnchorsTheme.clamp01((t - FALL_END) / 0.1F) * (1.0F - AnchorsTheme.clamp01((t - RISE_END) / 0.1F));
        if (circle > 0.01F) {
            AnchorsUi.sigil(graphics, centerX + shake, centerY + size / 3, Math.round(size * 0.95F), seconds * 3.0D,
                    AnchorsTheme.ACCENT, circle, charges);
            AnchorsUi.glowEllipse(graphics, centerX + shake, centerY, Math.round(size * 0.8F), Math.round(size * 0.7F),
                    0x9B4DFF, circle * (0.35F + 0.15F * charges));
        }

        // Sounds at their moments, once each.
        if (this.soundsPlayed == 0 && t >= FALL_END) {
            play(SimpleSoundInstance.forUI(SoundEvents.GENERIC_EXPLODE.value(), 0.7F, 0.9F));
            this.soundsPlayed = 1;
        }
        while (this.soundsPlayed >= 1 && this.soundsPlayed <= 4 && t >= CHARGE_START + (this.soundsPlayed - 1) * CHARGE_STEP) {
            play(SimpleSoundInstance.forUI(SoundEvents.RESPAWN_ANCHOR_CHARGE, 0.8F + this.soundsPlayed * 0.15F, 0.9F));
            this.soundsPlayed++;
        }
        if (this.soundsPlayed == 5 && t >= RISE_START) {
            play(SimpleSoundInstance.forUI(SoundEvents.RESPAWN_ANCHOR_SET_SPAWN, 1.2F, 0.9F));
            this.soundsPlayed = 6;
        }

        int anchorY;
        float anchorAlpha = 1.0F;
        float yaw;
        if (t < FALL_END) {
            // Falling: faster and faster, spinning, with speed lines above it.
            float eased = AnchorsTheme.easeInCubic(fall);
            anchorY = Math.round(-size + (centerY + size) * eased);
            yaw = 225.0F + fall * 720.0F;
            for (int line = 0; line < 7; line++) {
                int lx = centerX - size / 3 + line * size / 9;
                int length = Math.round(size * 0.9F * eased);
                graphics.fillGradient(lx, anchorY - size / 2 - length, lx + 1, anchorY - size / 3,
                        0x00000000, AnchorsTheme.withAlpha(0xC084FC, Math.round(140 * eased)));
            }
        } else if (t < RISE_START) {
            anchorY = centerY;
            yaw = 225.0F + 720.0F + (t - FALL_END) * 120.0F;
        } else {
            // Rising away, leaving a violet trail.
            float rise = AnchorsTheme.easeInOutSine((t - RISE_START) / (RISE_END - RISE_START));
            anchorY = Math.round(centerY - (centerY + size) * rise);
            anchorAlpha = 1.0F - AnchorsTheme.clamp01((t - RISE_START) / (RISE_END - RISE_START) - 0.3F);
            yaw = 225.0F + 720.0F + (RISE_START - FALL_END) * 120.0F + (t - RISE_START) * 900.0F;
            graphics.fillGradient(centerX - size / 6, anchorY + size / 3, centerX + size / 6, centerY + size / 2,
                    AnchorsTheme.withAlpha(0xC084FC, Math.round(170 * anchorAlpha)), 0x00000000);
        }

        if (afterImpact >= 0.0F && afterImpact < 0.35F) {
            float wave = afterImpact / 0.35F;
            float fadeOut = 1.0F - wave;
            int impactY = centerY + size / 3;
            if (afterImpact < 0.05F) {
                AnchorsUi.glowEllipse(graphics, centerX, impactY, size, size / 2, 0xFFF7FF, (1.0F - afterImpact / 0.05F) * 0.9F);
            }
            AnchorsUi.ring(graphics, centerX + shake, impactY, Math.round(size * (0.3F + wave * 1.4F)), 2,
                    AnchorsTheme.withAlpha(0xFF315C, Math.round(220 * fadeOut)));
            AnchorsUi.ring(graphics, centerX + shake, impactY, Math.round(size * (0.2F + wave * 1.0F)), 2,
                    AnchorsTheme.withAlpha(0xC084FC, Math.round(200 * fadeOut)));
            AnchorsUi.ring(graphics, centerX + shake, impactY, Math.round(size * (0.1F + wave * 0.6F)), 1,
                    AnchorsTheme.withAlpha(0xFFF7FF, Math.round(180 * fadeOut)));
            for (int spark = 0; spark < 28; spark++) {
                double angle = Math.PI + spark * (Math.PI / 27.0D);
                double distance = size * (0.2D + wave * (0.8D + spark % 4 * 0.15D));
                int sx = centerX + (int) Math.round(Math.cos(angle) * distance);
                int sy = impactY + (int) Math.round(Math.sin(angle) * distance * 0.6D + wave * wave * size * 0.5D);
                int color = spark % 3 == 0 ? 0xFF315C : spark % 3 == 1 ? 0xC084FC : 0xFFF7FF;
                int sparkSize = spark % 5 == 0 ? 3 : 2;
                graphics.fill(sx, sy, sx + sparkSize, sy + sparkSize, AnchorsTheme.withAlpha(color, Math.round(230 * fadeOut)));
            }
        }

        if (anchorAlpha > 0.02F) {
            drawAnchor(graphics, centerX + shake, anchorY, size, charges, yaw, anchorAlpha);
        }

        float text = AnchorsTheme.clamp01((t - RISE_START) / 0.12F) * (1.0F - AnchorsTheme.clamp01((t - 0.94F) / 0.06F));
        if (text > 0.01F) {
            activatedText(graphics, font, centerX, centerY + size / 2 + 10, text, 0.9F + 0.1F * AnchorsTheme.easeOutCubic(text));
        }
    }

    private void activatedText(GuiGraphicsExtractor graphics, Font font, int centerX, int y, float alpha, float grow) {
        String text = Component.translatable("kohs_anchors.warning.activated").getString();
        float scale = 2.0F * grow;
        AnchorsUi.bigText(graphics, font, text, centerX + 1, y + 1, scale, AnchorsTheme.fade(0xFF7C3AED, alpha), false);
        AnchorsUi.bigText(graphics, font, text, centerX, y, scale, AnchorsTheme.fade(0xFFFFF7FF, alpha), false);
        String sub = Component.translatable("kohs_anchors.warning.activated.sub").getString();
        AnchorsUi.label(graphics, font, sub, centerX - font.width(sub) / 2, y + Math.round(9 * scale) + 4,
                AnchorsTheme.fade(0xFFFF6A86, alpha), true);
    }

    /** The 3D anchor, centred on the given point, drawn by the game's own block renderer. */
    private void drawAnchor(GuiGraphicsExtractor graphics, int centerX, int centerY, int size, int charge, float yaw,
            float alpha) {
        if (alpha < 0.05F) {
            return;
        }
        int half = size;
        this.rotation.identity().rotateZ((float) Math.PI).rotateX((float) Math.toRadians(-28.0F))
                .rotateY((float) Math.toRadians(yaw));
        this.rotation.transform(this.translation.set(0.0F, 0.5F, 0.0F)).negate();
        float scale = size / 1.6F * (0.85F + 0.15F * alpha);
        graphics.entity(this.model.withCharge(charge), scale, this.translation, this.rotation, new Quaternionf(),
                centerX - half, centerY - half, centerX + half, centerY + half);
    }

    private static void play(SimpleSoundInstance sound) {
        Minecraft.getInstance().getSoundManager().play(sound);
    }
}
