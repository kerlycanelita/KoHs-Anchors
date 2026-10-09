package dev.zymekoh.kohsanchors.gui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The header's mark: a small Nether portal turning on itself.
 *
 * <p>The disc is drawn pixel by pixel into a tiny texture twenty times a second, a game tick per
 * frame like the game's own animated blocks: two arms of light revolve around a bright eye, a finer
 * pair turns faster for the portal's shimmer, and the result is dithered into the portal's six
 * purples (crimson on the enemy's side) so it stays pixel art at every size. A ring around it
 * carries the four charge lights of the header's anchor cycle, and motes drift into it as particles
 * drift into a portal.</p>
 *
 * <p>With interface animations off it is one still frame. The texture is a few hundred pixels,
 * built only when the frame or the colours change.</p>
 */
final class HeaderPortal {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(KoHsAnchorsClient.MOD_ID, "header/portal");
    private static final Identifier GLOW = Identifier.fromNamespaceAndPath(KoHsAnchorsClient.MOD_ID,
            "textures/gui/battle/glow.png");
    /** The portal's purples, darkest to brightest, and the enemy's crimsons. */
    private static final int[] OWN = {0xFF14052A, 0xFF300C66, 0xFF5A18B0, 0xFF8E45F0, 0xFFC99BFF, 0xFFF3E6FF};
    private static final int[] ENEMY = {0xFF22040C, 0xFF560A1E, 0xFF9C1834, 0xFFE0385A, 0xFFFF93A8, 0xFFFFE6EC};
    /** An ordered 2x2 dither, so the light steps between two purples in a pixel pattern. */
    private static final float[] DITHER = {-0.375F, 0.125F, 0.375F, -0.125F};
    private static final long FRAME_NANOS = 50_000_000L;
    private static final double TURN_SECONDS = 2.2D;

    private DynamicTexture texture;
    private int size;
    private long builtFrame = Long.MIN_VALUE;
    private float builtEnemy = -1.0F;
    /** The enemy's colour the disc was last built for: their side of it takes that hue. */
    private int builtTone;
    private final int[] enemyPalette = new int[ENEMY.length];

    /**
     * Draws the portal centred on the point, {@code radius} pixels across its disc, with its ring
     * when {@code ring}. {@code charge} (0 to 4) lights the ring's nodes; {@code enemy} (0 to 1)
     * turns it crimson.
     */
    void render(GuiGraphicsExtractor graphics, int centerX, int centerY, int radius, boolean ring, float charge, float enemy,
            boolean motion, float alpha) {
        if (radius < 3 || alpha <= 0.01F) {
            return;
        }
        long now = System.nanoTime();
        long frame = motion ? now / FRAME_NANOS : 12L;
        double seconds = motion ? now / 1_000_000_000.0D : 0.0D;
        int accent = AnchorsTheme.lerp(0xFFA855F7, AnchorsTheme.enemyTone(0xFFFF315C), enemy);
        int bright = AnchorsTheme.lerp(OWN[4], AnchorsTheme.enemyTone(ENEMY[4]), enemy);

        // Its light on the header behind it, breathing.
        float breathe = motion ? 0.8F + 0.2F * (float) Math.sin(seconds * 2.4D) : 1.0F;
        glow(graphics, centerX, centerY, radius * 3.4F, AnchorsTheme.withAlpha(accent, Math.round(95 * alpha * breathe)));

        int diameter = radius * 2;
        if (build(diameter, frame, enemy)) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, centerX - radius, centerY - radius, 0.0F, 0.0F, diameter,
                    diameter, diameter, diameter, AnchorsTheme.fade(0xFFFFFFFF, alpha));
        }

        if (ring) {
            int ringRadius = radius + 2;
            AnchorsUi.ring(graphics, centerX, centerY, ringRadius, 1, AnchorsTheme.withAlpha(accent, Math.round(130 * alpha)));
            // The four charge lights ride the ring, one way round.
            double turn = -seconds * 0.9D;
            for (int node = 0; node < 4; node++) {
                double angle = turn + node * Math.PI / 2.0D;
                int x = centerX + (int) Math.round(Math.cos(angle) * ringRadius);
                int y = centerY + (int) Math.round(Math.sin(angle) * ringRadius);
                float lit = AnchorsTheme.clamp01(charge - node);
                int color = AnchorsTheme.fade(AnchorsTheme.lerp(0xFF2E1846, bright, lit), alpha);
                graphics.fill(x - 1, y, x + 2, y + 1, color);
                graphics.fill(x, y - 1, x + 1, y + 2, color);
            }
        }

        if (motion) {
            // Motes drift in, turning, and fade into the disc.
            int from = radius + (ring ? 4 : 3);
            for (int mote = 0; mote < 5; mote++) {
                double progress = (seconds * 0.55D + mote / 5.0D) % 1.0D;
                double distance = from - progress * (from - radius * 0.35D);
                double angle = mote * Math.PI * 2.0D / 5.0D + seconds * 1.4D + progress * 2.5D;
                int x = centerX + (int) Math.round(Math.cos(angle) * distance);
                int y = centerY + (int) Math.round(Math.sin(angle) * distance);
                float fade = (float) Math.min(progress / 0.2D, 1.0D - progress);
                graphics.fill(x, y, x + 1, y + 1, AnchorsTheme.withAlpha(bright, Math.round(230 * alpha * fade)));
            }
        }
    }

    /** Builds the disc for this frame and colour; false while there is no texture to draw. */
    private boolean build(int diameter, long frame, float enemy) {
        if (this.texture == null || this.size != diameter) {
            close();
            this.size = diameter;
            this.texture = new DynamicTexture(() -> "KoHs Anchor's header portal", diameter, diameter, true);
            Minecraft.getInstance().getTextureManager().register(TEXTURE, this.texture);
            this.builtFrame = Long.MIN_VALUE;
        }
        int tone = dev.zymekoh.kohsanchors.config.AnchorsConfig.settings().enemyGlow.color;
        if (frame == this.builtFrame && Math.abs(enemy - this.builtEnemy) < 0.004F && tone == this.builtTone) {
            return true;
        }
        for (int level = 0; level < ENEMY.length; level++) {
            this.enemyPalette[level] = AnchorsTheme.enemyTone(ENEMY[level]);
        }
        NativeImage pixels = this.texture.getPixels();
        if (pixels == null) {
            return false;
        }
        double time = frame * (FRAME_NANOS / 1_000_000_000.0D);
        double spin = time * Math.PI * 2.0D / TURN_SECONDS;
        float edge = 1.0F - 2.2F / diameter;
        for (int y = 0; y < diameter; y++) {
            for (int x = 0; x < diameter; x++) {
                double px = (x + 0.5D) / diameter * 2.0D - 1.0D;
                double py = (y + 0.5D) / diameter * 2.0D - 1.0D;
                double r = Math.sqrt(px * px + py * py);
                if (r > 1.0D) {
                    pixels.setPixel(x, y, 0);
                    continue;
                }
                int level;
                if (r > edge) {
                    // A dark rim closes the circle.
                    level = 0;
                } else {
                    double theta = Math.atan2(py, px);
                    double arms = 0.5D + 0.5D * Math.sin(2.0D * theta + 5.0D * r - spin);
                    double shimmer = 0.5D + 0.5D * Math.sin(4.0D * theta - 7.0D * r - spin * 1.7D);
                    double light = 0.74D * arms + 0.26D * shimmer + 0.5D * Math.pow(1.0D - r, 3.0D)
                            - 0.3D * Math.max(0.0D, r - 0.7D) / 0.3D;
                    level = (int) Math.floor(light * 5.0D + 0.5D + DITHER[(x & 1) + 2 * (y & 1)]);
                    level = Math.max(1, Math.min(5, level));
                }
                pixels.setPixel(x, y, AnchorsTheme.lerp(OWN[level], this.enemyPalette[level], enemy));
            }
        }
        this.texture.upload();
        this.builtFrame = frame;
        this.builtEnemy = enemy;
        this.builtTone = tone;
        return true;
    }

    private static void glow(GuiGraphicsExtractor graphics, int centerX, int centerY, float size, int argb) {
        if (((argb >>> 24) & 255) < 3) {
            return;
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(centerX, centerY);
        graphics.pose().scale(size / 64.0F, size / 64.0F);
        graphics.blit(RenderPipelines.GUI_TEXTURED, GLOW, -32, -32, 0.0F, 0.0F, 64, 64, 64, 64, 64, 64, argb);
        graphics.pose().popMatrix();
    }

    void close() {
        if (this.texture != null) {
            Minecraft.getInstance().getTextureManager().release(TEXTURE);
            this.texture = null;
        }
    }
}
