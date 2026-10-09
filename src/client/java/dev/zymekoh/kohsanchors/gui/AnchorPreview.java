package dev.zymekoh.kohsanchors.gui;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import dev.zymekoh.kohsanchors.compat.AnchorBlockPreview;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.glow.AnchorGlowRenderer;
import dev.zymekoh.kohsanchors.gui.preview.AnchorStage;
import dev.zymekoh.kohsanchors.predict.AnchorFade;
import dev.zymekoh.kohsanchors.sound.AnchorSounds;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The respawn anchor in the settings screen, in a room of its own ({@link AnchorStage}): an
 * obsidian floor and ceiling, and on them the glow exactly as the world draws it. It answers like
 * an anchor: a right click charges it with glowstone, a left click detonates it (a right click on
 * a full one too, as in the game), and the chosen fade and explosion play. Dragging walks the
 * camera around it, and a little up or down; the wheel comes closer. Left alone, it charges and
 * detonates on its own.
 *
 * <p>The room is one picture drawn by Vanilla's renderer for entities in the interface, in the
 * preview area only; what is drawn over it here is decoration, so nothing can move a hitbox.</p>
 */
final class AnchorPreview {
    private static final Identifier[] EXPLOSION = new Identifier[16];
    private static final float DEFAULT_YAW = 32.0F;
    private static final float DEFAULT_LIFT = 0.3F;
    private static final float DEFAULT_DISTANCE = 3.7F;
    private static final float MIN_DISTANCE = 2.8F;
    private static final float MAX_DISTANCE = 6.0F;
    private static final long BLAST_NANOS = 700_000_000L;
    private static final long RESPAWN_NANOS = 380_000_000L;
    /** How long the anchor waits after the player's last touch before it plays on its own. */
    private static final long IDLE_NANOS = 3_500_000_000L;
    private static final long AUTO_STEP_NANOS = 560_000_000L;

    static {
        for (int frame = 0; frame < EXPLOSION.length; frame++) {
            EXPLOSION[frame] = Identifier.withDefaultNamespace("textures/particle/explosion_" + frame + ".png");
        }
    }

    private final AnchorBlockPreview model = new AnchorBlockPreview();
    private final Quaternionf rotation = new Quaternionf();
    private final Quaternionf noCameraTurn = new Quaternionf();
    private final Vector3f translation = new Vector3f();

    private float yaw = DEFAULT_YAW;
    private float lift = DEFAULT_LIFT;
    private float distance = DEFAULT_DISTANCE;
    private float targetDistance = DEFAULT_DISTANCE;
    /** It opens charged, so the room is lit and the glow shows before anything is clicked. */
    private int charge = 3;
    private float shownCharge = 3.0F;
    private long blastStartedAt = -1L;
    private long respawnedAt = -1L;
    /** The anchor is away because Zymekoh is eating it; when it comes back it grows in again. */
    private boolean eatenAway;
    /** Starts at the opening, so the anchor holds its pose before it plays. */
    private long lastTouch = System.nanoTime();
    private long nextAutoStep;
    private long lastFrame = System.nanoTime();

    private boolean dragging;
    private double dragDistance;
    /** Whether the last picture was an enemy's anchor: its fade takes their colour. */
    private boolean enemyView;

    /** The charge the anchor shows right now, easing between whole charges. */
    float charge() {
        return this.shownCharge;
    }

    /**
     * Where the anchor is in the picture drawn into {@code area}, as a square 2.4 blocks wide: the
     * switch to the enemy's anchors lifts it out of there and sets it back.
     */
    AnchorsLayout.Rect anchorBox(AnchorsLayout.Rect area) {
        float block = AnchorStage.pixelsPerBlock(area.height(), this.distance);
        int size = Math.max(8, Math.round(block * 2.4F));
        int centerY = area.y() + area.height() / 2 + Math.round(block * AnchorStage.anchorDrop());
        return new AnchorsLayout.Rect(area.centerX() - size / 2, centerY - size / 2, size, size);
    }

    /**
     * @param enemy draws an enemy's anchor: their skin and their glow colour, as a fight shows it
     * @param present false while the anchor is out of the room, on its way to or from the other page
     */
    void render(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.Rect area, int mouseX, int mouseY,
            boolean motion, float intro, String hint, boolean enemy, boolean present) {
        long now = System.nanoTime();
        float frameMillis = Mth.clamp((now - this.lastFrame) / 1_000_000.0F, 0.0F, 50.0F);
        this.lastFrame = now;
        double seconds = now / 1_000_000_000.0D;
        this.enemyView = enemy;
        advance(now, frameMillis, motion);

        if (Math.min(area.width(), area.height()) < 24) {
            return;
        }
        int centerX = area.centerX();
        float block = AnchorStage.pixelsPerBlock(area.height(), this.distance);
        int anchorY = area.y() + area.height() / 2 + Math.round(block * AnchorStage.anchorDrop());
        float blast = blastProgress(now);
        AnchorsConfig.Settings settings = AnchorsConfig.settings();
        // The light is the one the anchors in the world give off: the skin's glow, the custom
        // colour or the enemy's. With the glow off, the portal's own violet.
        int light = enemy ? settings.enemyGlow.color & 0xFFFFFF
                : settings.glow.enabled ? AnchorGlowRenderer.ownColor(Math.max(1, Math.round(this.shownCharge))) & 0xFFFFFF
                : AnchorsTheme.PORTAL & 0xFFFFFF;

        // Zymekoh eats the preview's anchor: it is away while she does, and comes back after.
        boolean eaten = present && AnchorMascot.anchorEaten();
        if (eaten) {
            this.eatenAway = true;
        } else if (this.eatenAway) {
            this.eatenAway = false;
            this.respawnedAt = now;
        }
        float size = 0.0F;
        if (present && blast < 0.0F && !eaten) {
            size = AnchorsTheme.easeOutBack(appearProgress(now)) * (0.6F + 0.4F * intro);
        }
        if (present && !this.eatenAway) {
            // Where Zymekoh pulls the anchor from, even while it blows or grows back in.
            AnchorMascot.previewAnchor(0, centerX - block * 0.5F, anchorY - block * 0.5F, block);
        }
        float flash = blast >= 0.0F && blast < 0.45F ? (1.0F - blast / 0.45F) * (1.0F - blast / 0.45F) : 0.0F;
        EntityRenderState picture = this.model.withCharge(0);
        AnchorStage.arm(picture, area.width(), area.height(), this.yaw, this.lift, this.distance, this.charge,
                this.shownCharge, enemy, size, flash, light, intro);
        graphics.entity(picture, 1.0F, this.translation, this.rotation, this.noCameraTurn, area.x(), area.y(), area.right(),
                area.bottom());
        vignette(graphics, area, intro);

        if (motion && size > 0.5F && this.charge > 0) {
            portalMotes(graphics, centerX, anchorY - Math.round(block * 0.5F), Math.round(block * 2.4F), seconds, this.charge,
                    intro, light);
        }
        if (blast >= 0.0F) {
            graphics.enableScissor(area.x() + 1, area.y() + 1, area.right() - 1, area.bottom() - 1);
            drawBlast(graphics, centerX, anchorY, block, (now - this.blastStartedAt) / 1_000_000_000.0F, intro,
                    settings.anchorSmoke);
            graphics.disableScissor();
        }

        // What the clicks do, while the pointer rests on the room; out of the way while it plays.
        if (area.contains(mouseX, mouseY) && !this.dragging && now - this.lastTouch > 900_000_000L) {
            List<FormattedCharSequence> lines = font.split(Component.literal(hint), Math.max(40, area.width() - 8));
            int shown = Math.min(3, lines.size());
            int lineY = area.bottom() - 2 - 10 * shown;
            graphics.fillGradient(area.x(), lineY - 8, area.right(), area.bottom(), 0x0007030C,
                    AnchorsTheme.withAlpha(0x07030C, Math.round(200 * intro)));
            for (int index = 0; index < shown; index++) {
                FormattedCharSequence line = lines.get(index);
                AnchorsUi.line(graphics, font, line, centerX - font.width(line) / 2, lineY + index * 10,
                        AnchorsTheme.fade(AnchorsTheme.TEXT_MUTED, intro));
            }
        }
        DevInspector.node("AnchorStage", enemy ? "enemy anchor" : "3D anchor", area.x(), area.y(), area.width(), area.height(),
                "FallingBlockStageMixin → AnchorStage.submit, one picture in perspective",
                "obsidian floor and ceiling · SkinCubeTexture · AnchorGlowRenderer.submitStage",
                "charge " + this.charge + " · yaw " + Math.round(this.yaw) + " · lift "
                        + String.format(java.util.Locale.ROOT, "%.2f", this.lift));
    }

    /** Charge lights, auto-play, the camera's distance and the idle walk around, all by elapsed time. */
    private void advance(long now, float frameMillis, boolean motion) {
        this.shownCharge += (this.charge - this.shownCharge) * (1.0F - (float) Math.exp(-frameMillis / 90.0F));
        this.distance += (this.targetDistance - this.distance) * (1.0F - (float) Math.exp(-frameMillis / 70.0F));
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
            this.yaw += frameMillis * 0.012F;
            if (now >= this.nextAutoStep && this.blastStartedAt < 0L) {
                if (this.charge < 4) {
                    charge(false);
                } else {
                    detonate(now, false);
                }
                this.nextAutoStep = now + (this.charge == 0 ? AUTO_STEP_NANOS * 2 : AUTO_STEP_NANOS);
            }
        }
    }

    boolean mouseClicked(AnchorsLayout.Rect area, double mouseX, double mouseY, int button) {
        if (!area.contains(mouseX, mouseY)) {
            return false;
        }
        long now = System.nanoTime();
        if (button == Keys.RIGHT_BUTTON) {
            this.lastTouch = now;
            // Glowstone on an anchor: one more charge, and on a full one the detonation, as in the game.
            if (this.charge < 4) {
                charge(true);
            } else {
                detonate(now, true);
            }
            return true;
        }
        if (button != Keys.LEFT_BUTTON) {
            return false;
        }
        this.lastTouch = now;
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
        this.yaw -= (float) dragX * 0.7F;
        this.lift = Mth.clamp(this.lift + (float) dragY * 0.014F, -1.0F, 1.0F);
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
            // A click, not a drag: the hit that sets a charged anchor off.
            detonate(now, true);
        }
        return true;
    }

    boolean mouseScrolled(AnchorsLayout.Rect area, double mouseX, double mouseY, double amount) {
        if (!area.contains(mouseX, mouseY) || amount == 0.0D) {
            return false;
        }
        this.lastTouch = System.nanoTime();
        this.targetDistance = Mth.clamp(this.targetDistance * (amount > 0.0D ? 1.0F / 1.1F : 1.1F), MIN_DISTANCE, MAX_DISTANCE);
        return true;
    }

    /** Detonates the anchor now, full, so the fade just chosen plays at once. */
    void playFade() {
        if (this.blastStartedAt >= 0L) {
            return;
        }
        this.charge = 4;
        this.shownCharge = 4.0F;
        detonate(System.nanoTime(), true);
    }

    /** One glowstone. */
    private void charge(boolean withSound) {
        if (this.blastStartedAt >= 0L || this.charge >= 4) {
            return;
        }
        this.charge++;
        if (withSound) {
            // The player's own charge and explosion sounds, when they chose them.
            AnchorSounds.previewCharge(0.9F + this.charge * 0.05F);
        }
    }

    /** The detonation of a charged anchor; an empty one has nothing to set off. */
    private void detonate(long now, boolean withSound) {
        if (this.blastStartedAt >= 0L || this.charge <= 0) {
            return;
        }
        this.lastTouch = withSound ? now : this.lastTouch;
        this.blastStartedAt = now;
        AnchorsConfig.Settings settings = AnchorsConfig.settings();
        if (settings.anchorFade) {
            AnchorStage.fade(AnchorFade.Style.of(settings.fadeStyle), now, this.enemyView
                    ? settings.enemyGlow.color & 0xFFFFFF : AnchorGlowRenderer.ownColor(Math.max(1, this.charge)) & 0xFFFFFF);
        }
        AnchorMascot.previewBlast();
        if (withSound) {
            AnchorSounds.previewExplosion();
        }
    }

    /** A soft dark edge all round the picture: the room sinks into the panel instead of ending at a cut. */
    private static void vignette(GuiGraphicsExtractor graphics, AnchorsLayout.Rect area, float intro) {
        int depth = Math.max(4, Math.min(area.width(), area.height()) / 8);
        int dark = AnchorsTheme.withAlpha(0x07030C, Math.round(225 * intro));
        graphics.fillGradient(area.x(), area.y(), area.right(), area.y() + depth, dark, 0x0007030C);
        graphics.fillGradient(area.x(), area.bottom() - depth, area.right(), area.bottom(), 0x0007030C, dark);
        // The sides column by column: the interface has no gradient that runs sideways.
        for (int column = 0; column < depth; column++) {
            float t = 1.0F - (column + 0.5F) / depth;
            int color = AnchorsTheme.withAlpha(0x07030C, Math.round(225 * intro * t));
            graphics.fill(area.x() + column, area.y(), area.x() + column + 1, area.bottom(), color);
            graphics.fill(area.right() - column - 1, area.y(), area.right() - column, area.bottom(), color);
        }
    }

    private float blastProgress(long now) {
        return this.blastStartedAt < 0L ? -1.0F : Mth.clamp((now - this.blastStartedAt) / (float) BLAST_NANOS, 0.0F, 1.0F);
    }

    private float appearProgress(long now) {
        return this.respawnedAt < 0L ? 1.0F : Mth.clamp((now - this.respawnedAt) / (float) RESPAWN_NANOS, 0.0F, 1.0F);
    }

    /**
     * The explosion's smoke, the game's own: its sixteen frames of a burst, several of them around
     * where the anchor stood, each a moment after the other. As many as the smoke option leaves in
     * the world: all of them, a few, or none.
     */
    private static void drawBlast(GuiGraphicsExtractor graphics, int centerX, int centerY, float block, float age,
            float intro, int smoke) {
        int bursts = smoke == 0 ? 7 : smoke == 1 ? 3 : 0;
        for (int index = 0; index < bursts; index++) {
            float life = (age - index * 0.028F) / 0.46F;
            if (life < 0.0F || life >= 1.0F) {
                continue;
            }
            float spread = index == 0 ? 0.0F : 0.75F;
            float x = centerX + (noise(index * 3) - 0.5F) * 2.0F * spread * block;
            float y = centerY + (noise(index * 3 + 1) - 0.5F) * 2.0F * spread * block;
            int size = Math.max(8, Math.round(block * (1.2F + 0.7F * noise(index * 3 + 2))));
            int shade = Math.round(255.0F * (0.62F + 0.38F * noise(index + 40)));
            int alpha = Math.round(255.0F * intro * (life < 0.8F ? 1.0F : (1.0F - life) / 0.2F));
            graphics.blit(RenderPipelines.GUI_TEXTURED, EXPLOSION[Math.min(15, (int) (life * 16.0F))], Math.round(x - size / 2.0F),
                    Math.round(y - size / 2.0F), 0.0F, 0.0F, size, size, 32, 32, 32, 32,
                    alpha << 24 | shade << 16 | shade << 8 | shade);
        }
    }

    /** A number from 0 to 1 that is the same for the same index, for the bursts. */
    private static float noise(int index) {
        int mixed = index * 0x27D4EB2D + 0x165667B1;
        mixed = (mixed ^ mixed >>> 15) * 0x85EBCA6B;
        mixed ^= mixed >>> 13;
        return (mixed & 0xFFFF) / 65535.0F;
    }

    /** Motes rising out of a charged anchor, more with every charge. */
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
