package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.glow.AnchorGlowRenderer;
import dev.zymekoh.kohsanchors.skin.AnchorTextures;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A charged anchor drawn as the world draws it, the player's own or an enemy's, for the windows
 * that show one flat: the skinned block from {@link AnchorCube}, its lit pixels lit in the glow's
 * colour, the glow around it and the light it throws on the ground.
 *
 * <p>{@code enemy} goes from 0 (the player's look) to 1 (the enemy's). The player's look is always
 * the player's own skin. The enemy's is their skin while the player gave them one, and otherwise
 * the player's skin with its lit pixels turned to the enemy's colour, which is what a fight shows
 * in each case. In between, for the switch between the two pages, the glow's colours blend.</p>
 */
final class AnchorFigure {
    private final String name;
    private final AnchorCube own;
    /** The enemy's skin, made the first time their look is drawn with their skin on. */
    private AnchorCube theirs;
    private long lastFrame = System.nanoTime();

    AnchorFigure(String name) {
        this.name = name;
        this.own = new AnchorCube(name);
        this.own.setCharge(4);
    }

    int charge() {
        return this.own.charge();
    }

    void setCharge(int charge) {
        this.own.setCharge(charge);
    }

    /** The colour the player's own anchors glow in at this charge. */
    int ownColor() {
        return AnchorGlowRenderer.ownColor(Math.max(1, this.own.charge())) & 0xFFFFFF;
    }

    static int enemyColor() {
        return AnchorsConfig.settings().enemyGlow.color & 0xFFFFFF;
    }

    /** The glow colour of a look between the player's (0) and the enemy's (1). */
    int color(float enemy) {
        boolean enemyOn = AnchorsConfig.settings().enemyGlow.enabled;
        return AnchorsTheme.lerp(0xFF000000 | ownColor(), 0xFF000000 | (enemyOn ? enemyColor() : ownColor()), enemy)
                & 0xFFFFFF;
    }

    /** Whether a look this far towards the enemy's is drawn with the skin the player gave them. */
    private static boolean wearsEnemySkin(float enemy) {
        return enemy >= 0.5F && AnchorsConfig.settings().enemySkin.enabled;
    }

    /** The cube of a look, turned and charged like the player's own. */
    private AnchorCube cube(float enemy) {
        if (!wearsEnemySkin(enemy)) {
            return this.own;
        }
        if (this.theirs == null) {
            this.theirs = new AnchorCube(this.name + "_theirs", true);
        }
        this.theirs.setCharge(this.own.charge());
        this.theirs.setView(this.own.yaw(), this.own.pitch());
        return this.theirs;
    }

    /**
     * Draws the anchor centred on the point, {@code scale} pixels to a block. Returns false while the
     * anchor textures cannot be read.
     */
    boolean draw(GuiGraphicsExtractor graphics, float centerX, float centerY, float scale, float alpha, float enemy,
            boolean motion, boolean surroundings) {
        return draw(graphics, centerX, centerY, scale, alpha, enemy, motion, surroundings, true);
    }

    /** The same; {@code turn} false keeps it still, as when two looks of it are drawn one frame. */
    boolean draw(GuiGraphicsExtractor graphics, float centerX, float centerY, float scale, float alpha, float enemy,
            boolean motion, boolean surroundings, boolean turn) {
        return draw(graphics, centerX, centerY, scale, alpha, enemy, motion, surroundings, turn, 0, 0.0F);
    }

    /**
     * The same, its glow pulled towards {@code tint} by {@code tintAmount}: the bridge's anchor turns
     * green when the server answers, and the enemy's keeps their colour while their glow is off.
     */
    boolean draw(GuiGraphicsExtractor graphics, float centerX, float centerY, float scale, float alpha, float enemy,
            boolean motion, boolean surroundings, boolean turn, int tint, float tintAmount) {
        long now = System.nanoTime();
        float frameMillis = Math.min(50.0F, (now - this.lastFrame) / 1_000_000.0F);
        this.lastFrame = now;
        if (turn && motion) {
            this.own.rotate(frameMillis * 0.02F, 0.0F);
        }
        AnchorCube cube = cube(enemy);
        if (!cube.prepare(motion) || alpha <= 0.01F) {
            return alpha <= 0.01F;
        }
        AnchorsConfig.Glow glow = AnchorsConfig.settings().glow;
        int color = tintAmount > 0.0F ? AnchorsTheme.lerp(0xFF000000 | color(enemy), 0xFF000000 | tint, tintAmount) & 0xFFFFFF
                : color(enemy);
        float charge = cube.charge() / 4.0F;
        float power = Math.min(1.4F, glow.power / 100.0F * (glow.chargeScaling ? 0.4F + 0.6F * charge : 1.0F));
        boolean glowing = glow.enabled && power > 0.0F;
        int x = Math.round(centerX);
        int y = Math.round(centerY);
        if (glowing && surroundings) {
            // The light on the ground under it, and the bloom around it.
            AnchorsUi.glowEllipse(graphics, x, y + Math.round(scale * 0.62F),
                    Math.round(scale * 0.95F * (0.6F + 0.4F * glow.spill / 100.0F)), Math.max(3, Math.round(scale * 0.2F)),
                    color, Math.min(1.0F, power * glow.spill / 100.0F) * alpha);
            AnchorsUi.glowEllipse(graphics, x, y, Math.round(scale * (0.9F + 0.3F * glow.bloom / 100.0F)),
                    Math.round(scale * (0.8F + 0.3F * glow.bloom / 100.0F)), color, Math.min(1.0F, 0.55F * power) * alpha);
        }
        AnchorsUi.ellipse(graphics, x, y + Math.round(scale * 0.62F), Math.round(scale * 0.5F), Math.max(2, Math.round(scale * 0.09F)),
                AnchorsTheme.withAlpha(0x0A0412, Math.round(140 * alpha)));
        cube.layout(centerX, centerY, scale);
        // The lit pixels shine in the glow's colour: faintly where the skin keeps its own colours
        // (the player's anchors, and an enemy's in their skin), fully where the world recolours the
        // player's skin for an enemy.
        float recolour = cube == this.theirs ? 0.0F : Math.max(enemy, tintAmount);
        float lift = glowing ? (0.18F + 0.47F * recolour) * Math.min(1.0F, power) : 0.0F;
        cube.draw(graphics, alpha, AnchorTextures.GLOW, 0.0F, lift, color);
        return true;
    }

    void close() {
        this.own.close();
        if (this.theirs != null) {
            this.theirs.close();
        }
    }
}
