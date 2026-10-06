package dev.zymekoh.kohsanchors.gui;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import dev.zymekoh.kohsanchors.compat.AnchorBlockPreview;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.glow.AnchorGlowRenderer;
import dev.zymekoh.kohsanchors.predict.AnchorFade;
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
    private static final Identifier AMETHYST = Identifier.withDefaultNamespace("textures/block/amethyst_block.png");
    /** Tiles of the wall the blast breaks, from the anchor out, with a ragged edge. */
    private static final float CRATER_RADIUS = 4.1F;
    /** The crater stays open two seconds, then the wall retracts back into place. */
    private static final float CRATER_OPEN = 2.0F;
    private static final float CRATER_CLOSE = 0.35F;
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
    /** The anchor as the chosen fade takes it away, drawn face by face so it can go transparent. */
    private final AnchorCube fadeCube = new AnchorCube("preview_fade");
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
    /** When the last blast broke the amethyst wall behind the anchor, or -1 while it is whole. */
    private long craterAt = -1L;
    private long respawnedAt = -1L;
    /** The anchor is away because Zymekoh is eating it; when it comes back it grows in again. */
    private boolean eatenAway;
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

        drawWall(graphics, area, centerX, centerY, size, now, intro);
        if (!this.eatenAway) {
            // Where Zymekoh pulls the anchor from, even while it blows or grows back in.
            float shown = size / 1.9F * this.zoom;
            AnchorMascot.previewAnchor(0, centerX - shown * 0.5F, centerY - shown * 0.5F, shown);
        }

        // Light behind the block that grows with every charge, and the shadow it stands on.
        float breathe = motion ? 0.85F + 0.15F * (float) Math.sin(seconds * 3.2D) : 1.0F;
        AnchorsUi.glowEllipse(graphics, centerX, centerY, Math.round(size * 0.47F), Math.round(size * 0.43F),
                light, (0.45F + glow * 0.55F) * breathe * intro * (glowing ? 1.0F : 0.7F));
        AnchorsUi.ellipse(graphics, centerX, centerY + Math.round(size * 0.34F), Math.round(size * 0.30F),
                Math.max(2, Math.round(size * 0.06F)), AnchorsTheme.withAlpha(0x0A0412, Math.round(150 * intro)));

        if (blast < 0.0F) {
            float grow = AnchorsTheme.easeOutBack(appear) * (0.6F + 0.4F * intro);
            float scale = size / 1.9F * this.zoom * grow;
            // Zymekoh eats the preview's anchor: it is away while she does, and comes back after.
            boolean eaten = AnchorMascot.anchorEaten();
            if (eaten) {
                this.eatenAway = true;
            } else if (this.eatenAway) {
                this.eatenAway = false;
                this.respawnedAt = now;
            }
            if (scale > 0.5F && !eaten) {
                this.rotation.identity().rotateZ((float) Math.PI)
                        .rotateX((float) Math.toRadians(this.pitch))
                        .rotateY((float) Math.toRadians(this.yaw));
                // The renderer puts the block's bottom centre at the origin; this moves its middle
                // there instead, so the block turns about its own centre.
                this.rotation.transform(this.translation.set(0.0F, 0.5F, 0.0F)).negate();
                graphics.entity(this.model.withCharge(this.charge), scale, this.translation, this.rotation,
                        new Quaternionf(), area.x(), area.y(), area.right(), area.bottom());
            }
            if (motion && this.charge > 0 && !eaten) {
                portalMotes(graphics, centerX, centerY - Math.round(size * 0.18F * this.zoom), size, seconds,
                        this.charge, intro, light);
            }
        } else {
            drawFade(graphics, area, centerX, centerY, size / 1.9F * this.zoom, now, light);
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
        if (!area.contains(mouseX, mouseY) || button != Keys.LEFT_BUTTON) {
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

    /** Detonates the anchor now, full, so the fade just chosen plays at once. */
    void playFade() {
        long now = System.nanoTime();
        if (this.blastStartedAt >= 0L) {
            return;
        }
        this.lastTouch = now;
        this.charge = 4;
        this.shownCharge = 4.0F;
        this.blastStartedAt = now;
        this.craterAt = now;
        AnchorSounds.previewExplosion();
    }

    /**
     * The anchor fade chosen in General, as the world draws it: the anchor goes away during the blast
     * instead of vanishing with the flash.
     */
    private void drawFade(GuiGraphicsExtractor graphics, AnchorsLayout.Rect area, int centerX, int centerY, float scale,
            long now, int light) {
        AnchorsConfig.Settings settings = AnchorsConfig.settings();
        if (!settings.anchorFade || scale < 2.0F || !this.fadeCube.prepare(false)) {
            return;
        }
        AnchorFade.Style style = AnchorFade.Style.of(settings.fadeStyle);
        float seconds = (now - this.blastStartedAt) / 1_000_000_000.0F;
        float progress = seconds / (style.millis() / 1000.0F);
        if (progress >= 1.0F) {
            return;
        }
        this.fadeCube.setCharge(4);
        this.fadeCube.setView(this.yaw, -this.pitch);
        int glow = 0xFF000000 | light;
        graphics.enableScissor(area.x() + 1, area.y() + 1, area.right() - 1, area.bottom() - 1);
        switch (style) {
            case GHOST -> {
                float alpha = (1.0F - progress) * (1.0F - progress);
                float rise = 0.35F * scale * (1.0F - (1.0F - progress) * (1.0F - progress) * (1.0F - progress));
                AnchorsUi.glowEllipse(graphics, centerX, Math.round(centerY - rise), Math.round(scale * 0.9F),
                        Math.round(scale * 0.9F), light, alpha * 0.6F);
                this.fadeCube.layout(centerX, centerY - rise, scale);
                this.fadeCube.drawTinted(graphics, alpha * 0.95F, AnchorsTheme.lerp(0xFFFFFFFF, 0xFFD9CCFF, progress));
            }
            case SINK -> {
                float shrink = 1.0F - progress * progress;
                this.fadeCube.setView(this.yaw + 40.0F * progress * progress, -this.pitch);
                this.fadeCube.layout(centerX, centerY + 0.2F * scale * progress, scale * shrink);
                this.fadeCube.drawTinted(graphics, 1.0F, 0xFFFFFF);
            }
            case SHATTER -> {
                float alpha = progress < 0.55F ? 1.0F : 1.0F - (progress - 0.55F) / 0.45F;
                for (int piece = 0; piece < 8; piece++) {
                    double angle = Math.toRadians(piece * 45.0D + 22.5D + noise(piece) * 20.0D);
                    float speed = scale * (2.0F + noise(piece + 8) * 1.4F);
                    float x = centerX + (float) Math.cos(angle) * (scale * 0.25F + speed * seconds);
                    float y = centerY + (float) Math.sin(angle) * scale * 0.25F - scale * (2.6F + noise(piece + 16)) * seconds
                            + scale * 7.0F * seconds * seconds;
                    graphics.pose().pushMatrix();
                    graphics.pose().translate(x, y);
                    graphics.pose().rotate((float) ((noise(piece + 24) - 0.5D) * 14.0D * seconds));
                    this.fadeCube.drawShard(graphics, piece >= 4, piece % 4, scale * 0.48F, alpha);
                    graphics.pose().popMatrix();
                }
            }
            case DISINTEGRATE -> {
                float eased = progress < 0.5F ? 2.0F * progress * progress
                        : 1.0F - (float) Math.pow(-2.0F * progress + 2.0F, 2) / 2.0F;
                int top = Math.round(centerY - scale * 0.9F);
                int bottom = Math.round(centerY + scale * 0.9F);
                int cut = Math.round(top + (bottom - top) * eased);
                int left = Math.round(centerX - scale);
                int right = Math.round(centerX + scale);
                graphics.enableScissor(left, cut, right, bottom + 2);
                this.fadeCube.layout(centerX, centerY, scale);
                this.fadeCube.drawTinted(graphics, 1.0F, 0xFFFFFF);
                graphics.disableScissor();
                graphics.fill(left + 2, cut - 1, right - 2, cut + 1, AnchorsTheme.lerp(0xFFFFFFFF, glow, 0.4F));
                for (int ember = 0; ember < 14; ember++) {
                    float released = noise(ember + 40);
                    float age = eased - released;
                    if (age < 0.0F || age > 0.45F) {
                        continue;
                    }
                    float life = age / 0.45F;
                    int ex = Math.round(left + (right - left) * noise(ember + 60));
                    int ey = Math.round(top + (bottom - top) * released - life * scale * 0.7F);
                    int dot = Math.max(1, Math.round(scale * 0.06F * (1.0F - life)));
                    graphics.fill(ex, ey, ex + dot, ey + dot, AnchorsTheme.fade(AnchorsTheme.lerp(glow, 0xFF2A0E3F, life), 1.0F - life));
                }
            }
            case GLITCH -> {
                if (progress < 0.75F) {
                    int step = (int) (seconds / 0.04F);
                    int top = Math.round(centerY - scale * 0.9F);
                    int band = Math.max(1, Math.round(scale * 0.45F));
                    int left = Math.round(centerX - scale * 1.3F);
                    int right = Math.round(centerX + scale * 1.3F);
                    if (step % 2 == 0) {
                        float split = scale * (0.05F + 0.08F * noise(step));
                        this.fadeCube.layout(centerX + split, centerY, scale);
                        this.fadeCube.drawTinted(graphics, 0.45F, 0xFF4060);
                        this.fadeCube.layout(centerX - split, centerY, scale);
                        this.fadeCube.drawTinted(graphics, 0.45F, 0x30E6FF);
                    }
                    for (int slice = 0; slice < 4; slice++) {
                        if (noise(step * 4 + slice + 100) > 0.85F - 0.6F * progress) {
                            continue;
                        }
                        float shift = noise(step * 4 + slice + 200) < 0.5F ? 0.0F
                                : (noise(step * 4 + slice + 300) - 0.5F) * scale * 0.3F;
                        graphics.enableScissor(left, top + slice * band, right, top + (slice + 1) * band);
                        this.fadeCube.layout(centerX + shift, centerY, scale);
                        this.fadeCube.drawTinted(graphics, 1.0F, 0xFFFFFF);
                        graphics.disableScissor();
                    }
                } else {
                    float off = (progress - 0.75F) / 0.25F;
                    int half = Math.max(1, Math.round(scale * 0.9F * (1.0F - off) * (1.0F - off) * (1.0F - off)));
                    int wide = Math.round(scale * (1.0F + 0.4F * off));
                    graphics.fill(centerX - wide, centerY - half, centerX + wide, centerY + half,
                            AnchorsTheme.fade(AnchorsTheme.lerp(0xFFFFFFFF, glow, off), 1.0F - off));
                }
            }
        }
        graphics.disableScissor();
    }

    /** A number from 0 to 1 that is the same for the same index, for the shards and embers. */
    private static float noise(int index) {
        int mixed = index * 0x27D4EB2D + 0x165667B1;
        mixed = (mixed ^ mixed >>> 15) * 0x85EBCA6B;
        mixed ^= mixed >>> 13;
        return (mixed & 0xFFFF) / 65535.0F;
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
            this.craterAt = now;
            AnchorMascot.previewBlast();
            if (withSound) {
                AnchorSounds.previewExplosion();
            }
        }
    }

    /**
     * The amethyst wall behind the anchor, one block per tile, edge to edge like a wall in the world. A
     * detonation shatters the tiles around the anchor into the ragged crater an anchor blast
     * leaves, shards flying out; two seconds later the broken blocks retract back into place, the
     * rim first. Plain textured quads, a few dozen a frame: no block models, no extra pass.
     */
    private void drawWall(GuiGraphicsExtractor graphics, AnchorsLayout.Rect area, int centerX, int centerY, int size,
            long now, float intro) {
        int tile = Math.max(6, Math.round(size / 9.0F));
        float crater = -1.0F;
        if (this.craterAt >= 0L) {
            float age = (now - this.craterAt) / 1_000_000_000.0F;
            if (age > CRATER_OPEN + CRATER_CLOSE + 0.4F) {
                this.craterAt = -1L;
            } else {
                crater = age;
            }
        }
        int columns = area.width() / tile + 3;
        int rows = area.height() / tile + 3;
        int originX = centerX - tile / 2 - columns / 2 * tile;
        int originY = centerY - tile / 2 - rows / 2 * tile;
        float reach = Math.max(1.0F, Math.max(columns, rows) / 2.0F);
        int alpha = Math.round(255 * intro);
        graphics.enableScissor(area.x() + 1, area.y() + 1, area.right() - 1, area.bottom() - 1);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int x = originX + column * tile;
                int y = originY + row * tile;
                float dx = (x + tile / 2.0F - centerX) / tile;
                float dy = (y + tile / 2.0F - centerY) / tile;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                int hash = column * 73856093 ^ row * 19349663;
                float noise = (hash >>> 8 & 255) / 255.0F;
                float whole = 1.0F;
                if (crater >= 0.0F && distance + noise * 1.3F < CRATER_RADIUS) {
                    // The rim closes first, the centre last: the wall pulls itself back in.
                    float closeAt = CRATER_OPEN + 0.08F * Math.max(0.0F, CRATER_RADIUS - distance);
                    if (crater < closeAt) {
                        whole = 1.0F - Mth.clamp(crater / 0.16F, 0.0F, 1.0F);
                        if (crater < 0.55F && distance > 0.01F) {
                            drawShard(graphics, x, y, tile, dx / distance, dy / distance, crater, noise, alpha);
                        }
                    } else {
                        whole = AnchorsTheme.easeOutBack(Mth.clamp((crater - closeAt) / CRATER_CLOSE, 0.0F, 1.0F));
                    }
                }
                if (whole <= 0.03F) {
                    continue;
                }
                // Darker towards the edges and block by block, so the anchor stands out in front.
                float shade = (0.42F + 0.4F * (1.0F - Mth.clamp(distance / reach, 0.0F, 1.0F))) * (0.95F + 0.05F * noise);
                int channel = Math.round(255 * Mth.clamp(shade, 0.0F, 1.0F));
                int color = alpha << 24 | channel << 16 | Math.round(channel * 0.86F) << 8 | channel;
                // Whole blocks touch: one wall, no seams. Only the breaking and returning ones shrink.
                int drawn = whole >= 0.999F && whole <= 1.001F ? tile : Math.max(1, Math.round(tile * Math.min(whole, 1.08F)));
                int offset = (tile - drawn) / 2;
                graphics.blit(RenderPipelines.GUI_TEXTURED, AMETHYST, x + offset, y + offset, 0.0F, 0.0F, drawn, drawn,
                        16, 16, 16, 16, color);
            }
        }
        if (crater >= 0.0F && crater < CRATER_OPEN + CRATER_CLOSE) {
            // The scorched hollow, glowing hot at first and cooling while it stays open.
            float cool = Mth.clamp(crater / CRATER_OPEN, 0.0F, 1.0F);
            float open = 1.0F - Mth.clamp((crater - CRATER_OPEN) / CRATER_CLOSE, 0.0F, 1.0F);
            int radius = Math.round(tile * (CRATER_RADIUS - 0.4F));
            // A scorched hollow behind the wall, its rim glowing hot at first and cooling while it stays open.
            AnchorsUi.ellipse(graphics, centerX, centerY, radius, Math.round(radius * 0.92F),
                    AnchorsTheme.withAlpha(0x07030C, Math.round(170 * open * intro)));
            AnchorsUi.glowEllipse(graphics, centerX, centerY, radius, Math.round(radius * 0.92F),
                    AnchorsTheme.lerp(0xFF8A3D, 0x5A1A8A, cool) & 0xFFFFFF, 0.35F * open * intro);
        }
        graphics.disableScissor();
        DevInspector.node("Wall", "amethyst wall", area.x(), area.y(), area.width(), area.height(),
                "AnchorPreview.drawWall: " + columns + "×" + rows + " tiles of " + tile + " px, edge to edge",
                crater < 0.0F ? "whole" : "crater " + String.format(java.util.Locale.ROOT, "%.2f", crater) + " s · open "
                        + CRATER_OPEN + " s, retracts in " + CRATER_CLOSE + " s",
                "blit textures/block/amethyst_block.png");
    }

    /** A shard of a broken block, thrown out of the crater and fading. */
    private static void drawShard(GuiGraphicsExtractor graphics, int x, int y, int tile, float dirX, float dirY, float age,
            float noise, int alpha) {
        float travel = tile * (0.6F + noise) * Mth.clamp(age / 0.55F, 0.0F, 1.0F);
        int shard = Math.max(2, tile / 3);
        int sx = Math.round(x + tile / 2.0F + dirX * travel - shard / 2.0F);
        int sy = Math.round(y + tile / 2.0F + dirY * travel + travel * travel / (tile * 3.0F) - shard / 2.0F);
        int fade = Math.round(alpha * (1.0F - Mth.clamp(age / 0.55F, 0.0F, 1.0F)));
        graphics.blit(RenderPipelines.GUI_TEXTURED, AMETHYST, sx, sy, 4.0F, 4.0F, shard, shard, 6, 6, 16, 16,
                fade << 24 | 0xFFFFFF);
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
