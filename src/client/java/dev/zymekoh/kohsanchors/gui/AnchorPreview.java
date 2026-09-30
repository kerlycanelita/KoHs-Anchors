package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.compat.AnchorBlockPreview;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.glow.AnchorGlowRenderer;
import dev.zymekoh.kohsanchors.sound.AnchorSounds;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The respawn anchor in the settings screen: Vanilla's own block model and textures, drawn in 3D.
 * Drag to turn it, scroll to zoom, click to charge it with glowstone, and click a full anchor to
 * detonate it. Left alone, it charges and detonates on its own.
 *
 * <p>The model is drawn by Vanilla's GUI entity renderer into the preview area only; every
 * effect around it is decoration, so nothing here can move a hitbox.</p>
 */
final class AnchorPreview {
    private static final float DEFAULT_YAW = 225.0F;
    private static final float DEFAULT_PITCH = -30.0F;
    private static final float MIN_ZOOM = 0.6F;
    private static final float MAX_ZOOM = 1.7F;
    private static final long BLAST_NANOS = 700_000_000L;
    private static final long RESPAWN_NANOS = 380_000_000L;
    /** How long the anchor waits after the player's last touch before it plays on its own. */
    private static final long IDLE_NANOS = 3_500_000_000L;
    private static final long AUTO_STEP_NANOS = 560_000_000L;

    private final AnchorBlockPreview model = new AnchorBlockPreview();
    private final Quaternionf rotation = new Quaternionf();
    private final Vector3f translation = new Vector3f();

    private float yaw = DEFAULT_YAW;
    private float pitch = DEFAULT_PITCH;
    private float zoom = 1.0F;
    private float targetZoom = 1.0F;
    private int charge;
    private float shownCharge;
    /** A light colour that replaces the anchor's own, RGB; 0 for none. */
    private int lightOverride;
    private long blastStartedAt = -1L;
    private long respawnedAt = -1L;
    /** Starts at the opening, so the anchor holds its three-quarter pose before it plays. */
    private long lastTouch = System.nanoTime();
    private long nextAutoStep;
    private long lastFrame = System.nanoTime();

    private boolean dragging;
    private double dragDistance;

    /** Lights the preview in {@code argb} instead of the anchor's own glow; 0 goes back to it. */
    void lightOverride(int argb) {
        this.lightOverride = argb & 0xFFFFFF;
    }

    /** The charge the anchor shows right now, easing between whole charges. */
    float charge() {
        return this.shownCharge;
    }

    void render(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.Rect area, int mouseX, int mouseY,
            boolean motion, float intro, String hint) {
        long now = System.nanoTime();
        float frameMillis = Mth.clamp((now - this.lastFrame) / 1_000_000.0F, 0.0F, 50.0F);
        this.lastFrame = now;
        double seconds = now / 1_000_000_000.0D;
        advance(now, frameMillis, motion);

        int size = Math.min(area.width(), area.height());
        if (size < 24) {
            return;
        }
        int centerX = area.centerX();
        int centerY = area.y() + area.height() / 2;
        float blast = blastProgress(now);
        float appear = appearProgress(now);
        float glow = this.shownCharge / 4.0F;
        // The light is the one the anchors in the world give off: the skin's glow or the custom
        // colour. With the glow off, the portal's own violet, softer.
        boolean glowing = AnchorsConfig.settings().glow.enabled;
        int light = this.lightOverride != 0 ? this.lightOverride
                : glowing ? AnchorGlowRenderer.ownColor(Math.max(1, Math.round(this.shownCharge))) & 0xFFFFFF
                : AnchorsTheme.PORTAL;

        // Light behind the block that grows with every charge, and the shadow it stands on.
        float breathe = motion ? 0.85F + 0.15F * (float) Math.sin(seconds * 3.2D) : 1.0F;
        AnchorsUi.glowEllipse(graphics, centerX, centerY, Math.round(size * 0.47F), Math.round(size * 0.43F),
                light, (0.45F + glow * 0.55F) * breathe * intro * (glowing ? 1.0F : 0.7F));
        AnchorsUi.ellipse(graphics, centerX, centerY + Math.round(size * 0.34F), Math.round(size * 0.30F),
                Math.max(2, Math.round(size * 0.06F)), AnchorsTheme.withAlpha(0x0A0412, Math.round(150 * intro)));

        if (blast < 0.0F) {
            float grow = AnchorsTheme.easeOutBack(appear) * (0.6F + 0.4F * intro);
            float scale = size / 1.9F * this.zoom * grow;
            if (scale > 0.5F) {
                this.rotation.identity().rotateZ((float) Math.PI)
                        .rotateX((float) Math.toRadians(this.pitch))
                        .rotateY((float) Math.toRadians(this.yaw));
                // The renderer puts the block's bottom centre at the origin; this moves its middle
                // there instead, so the block turns about its own centre.
                this.rotation.transform(this.translation.set(0.0F, 0.5F, 0.0F)).negate();
                graphics.entity(this.model.withCharge(this.charge), scale, this.translation, this.rotation,
                        new Quaternionf(), area.x(), area.y(), area.right(), area.bottom());
            }
            if (motion && this.charge > 0) {
                portalMotes(graphics, centerX, centerY - Math.round(size * 0.18F * this.zoom), size, seconds,
                        this.charge, intro, light);
            }
        } else {
            drawBlast(graphics, centerX, centerY, size, blast, intro, light);
        }

        boolean hovered = area.contains(mouseX, mouseY);
        if (hovered || now - this.lastTouch < 2_500_000_000L) {
            List<FormattedCharSequence> lines = font.split(Component.literal(hint), Math.max(40, area.width() - 6));
            int lineY = area.bottom() - 10 * Math.min(2, lines.size());
            for (int index = 0; index < Math.min(2, lines.size()); index++) {
                FormattedCharSequence line = lines.get(index);
                AnchorsUi.line(graphics, font, line, centerX - font.width(line) / 2, lineY + index * 10,
                        AnchorsTheme.fade(AnchorsTheme.TEXT_DIM, intro));
            }
        }
    }

    /** Charge lights, auto-play, zoom and the idle turn, all by elapsed time. */
    private void advance(long now, float frameMillis, boolean motion) {
        this.shownCharge += (this.charge - this.shownCharge) * (1.0F - (float) Math.exp(-frameMillis / 90.0F));
        this.zoom += (this.targetZoom - this.zoom) * (1.0F - (float) Math.exp(-frameMillis / 70.0F));
        if (this.blastStartedAt >= 0L && now - this.blastStartedAt >= BLAST_NANOS) {
            this.blastStartedAt = -1L;
            this.respawnedAt = now;
            this.charge = 0;
            this.shownCharge = 0.0F;
        }
        if (!motion) {
            return;
        }
        if (!this.dragging && now - this.lastTouch > IDLE_NANOS) {
            this.yaw += frameMillis * 0.018F;
            if (now >= this.nextAutoStep && this.blastStartedAt < 0L) {
                step(now, false);
                this.nextAutoStep = now + (this.charge == 0 ? AUTO_STEP_NANOS * 2 : AUTO_STEP_NANOS);
            }
        }
    }

    boolean mouseClicked(AnchorsLayout.Rect area, double mouseX, double mouseY, int button, boolean doubleClick) {
        if (!area.contains(mouseX, mouseY) || button != 0) {
            return false;
        }
        long now = System.nanoTime();
        this.lastTouch = now;
        if (doubleClick) {
            this.yaw = DEFAULT_YAW;
            this.pitch = DEFAULT_PITCH;
            this.targetZoom = 1.0F;
            this.dragging = false;
            return true;
        }
        this.dragging = true;
        this.dragDistance = 0.0D;
        return true;
    }

    boolean mouseDragged(double dragX, double dragY) {
        if (!this.dragging) {
            return false;
        }
        this.lastTouch = System.nanoTime();
        this.dragDistance += Math.abs(dragX) + Math.abs(dragY);
        this.yaw += (float) dragX * 0.9F;
        this.pitch = Mth.clamp(this.pitch - (float) dragY * 0.9F, -85.0F, 85.0F);
        return true;
    }

    boolean mouseReleased() {
        if (!this.dragging) {
            return false;
        }
        this.dragging = false;
        long now = System.nanoTime();
        this.lastTouch = now;
        if (this.dragDistance < 3.0D) {
            step(now, true);
        }
        return true;
    }

    boolean mouseScrolled(AnchorsLayout.Rect area, double mouseX, double mouseY, double amount) {
        if (!area.contains(mouseX, mouseY) || amount == 0.0D) {
            return false;
        }
        this.lastTouch = System.nanoTime();
        this.targetZoom = Mth.clamp(this.targetZoom * (amount > 0.0D ? 1.1F : 1.0F / 1.1F), MIN_ZOOM, MAX_ZOOM);
        return true;
    }

    /** One glowstone, or the detonation of a full anchor. */
    private void step(long now, boolean withSound) {
        if (this.blastStartedAt >= 0L) {
            return;
        }
        if (this.charge < 4) {
            this.charge++;
            if (withSound) {
                // The player's own charge and explosion sounds, when they chose them.
                AnchorSounds.previewCharge(0.9F + this.charge * 0.05F);
            }
        } else {
            this.blastStartedAt = now;
            if (withSound) {
                AnchorSounds.previewExplosion();
            }
        }
    }

    private float blastProgress(long now) {
        return this.blastStartedAt < 0L ? -1.0F : Mth.clamp((now - this.blastStartedAt) / (float) BLAST_NANOS, 0.0F, 1.0F);
    }

    private float appearProgress(long now) {
        return this.respawnedAt < 0L ? 1.0F : Mth.clamp((now - this.respawnedAt) / (float) RESPAWN_NANOS, 0.0F, 1.0F);
    }

    private static void drawBlast(GuiGraphicsExtractor graphics, int centerX, int centerY, int size, float blast,
            float intro, int light) {
        float fadeOut = 1.0F - blast;
        if (blast < 0.3F) {
            int flash = Math.round(200.0F * (1.0F - blast / 0.3F) * intro);
            AnchorsUi.glowEllipse(graphics, centerX, centerY, Math.round(size * 0.46F), Math.round(size * 0.42F),
                    0xFFF4FF, flash / 255.0F);
        }
        int radius = Math.round(size * 0.12F + AnchorsTheme.easeOutCubic(blast) * size * 0.40F);
        AnchorsUi.ring(graphics, centerX, centerY, radius, Math.max(1, size / 40),
                AnchorsTheme.withAlpha(AnchorsTheme.ACCENT_BRIGHT, Math.round(220 * fadeOut * intro)));
        int sparks = 18;
        for (int index = 0; index < sparks; index++) {
            double angle = index * (Math.PI * 2.0D / sparks) + index * 0.41D;
            double distance = size * (0.1D + AnchorsTheme.easeOutCubic(blast) * (0.36D + index % 3 * 0.06D));
            int x = centerX + (int) Math.round(Math.cos(angle) * distance);
            int y = centerY + (int) Math.round(Math.sin(angle) * distance * 0.9D);
            int sparkSize = index % 4 == 0 ? 3 : 2;
            graphics.fill(x, y, x + sparkSize, y + sparkSize,
                    AnchorsTheme.fade(AnchorsTheme.lerp(AnchorsTheme.ACCENT_BRIGHT, 0xFF000000 | light, blast),
                            fadeOut * intro));
        }
    }

    /** Violet motes rising out of a charged anchor, more with every charge. */
    private static void portalMotes(GuiGraphicsExtractor graphics, int centerX, int topY, int size, double seconds,
            int charge, float intro, int light) {
        int count = 3 + charge * 3;
        int spread = Math.max(4, Math.round(size * 0.22F));
        int rise = Math.max(8, Math.round(size * 0.36F));
        for (int index = 0; index < count; index++) {
            double phase = index * 0.618D;
            double life = (seconds * (0.7D + index % 3 * 0.25D) + phase) % 1.0D;
            int x = centerX + (int) Math.round(Math.sin(phase * 9.0D + seconds * 0.8D) * spread * (0.4D + life * 0.6D));
            int y = topY - (int) Math.round(life * rise);
            int alpha = Math.round((float) Math.sin(life * Math.PI) * 190.0F * intro);
            int color = index % 3 == 0 ? AnchorsTheme.lerp(light, 0xFFFFFF, 0.7F) & 0xFFFFFF : light;
            graphics.fill(x, y, x + (index % 5 == 0 ? 2 : 1), y + (index % 5 == 0 ? 2 : 1),
                    AnchorsTheme.withAlpha(color, alpha));
        }
    }
}
