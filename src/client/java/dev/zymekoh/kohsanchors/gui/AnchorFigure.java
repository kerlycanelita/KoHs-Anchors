package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.glow.AnchorGlowRenderer;
import dev.zymekoh.kohsanchors.skin.AnchorTextures;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A charged anchor drawn as the world draws it, the player's own or an enemy's: the skinned block
 * from {@link AnchorCube}, its lit pixels lit in the glow's colour, the glow around it and the
 * light it throws on the ground. An enemy's anchor looks exactly as it does in a fight: the same
 * block, glowing in the enemy colour.
 *
 * <p>{@code enemy} goes from 0 (the player's look) to 1 (the enemy's); in between the colours
 * blend, for the switch between the two pages.</p>
 */
final class AnchorFigure {
    private final AnchorCube cube;
    private long lastFrame = System.nanoTime();
    private long lastTouch;

    AnchorFigure(String name) {
        this.cube = new AnchorCube(name);
        this.cube.setCharge(4);
    }

    int charge() {
        return this.cube.charge();
    }

    void setCharge(int charge) {
        this.cube.setCharge(charge);
    }

    /** Turns it by a drag; it holds still for a moment before turning on its own again. */
    void drag(double dragX, double dragY) {
        this.cube.rotate((float) dragX * 0.9F, (float) dragY * 0.9F);
        this.lastTouch = System.nanoTime();
    }

    /** One more charge, and back to one after the fourth. */
    void cycleCharge() {
        this.cube.setCharge(this.cube.charge() >= 4 ? 1 : this.cube.charge() + 1);
        this.lastTouch = System.nanoTime();
    }

    /** The colour the player's own anchors glow in at this charge. */
    int ownColor() {
        return AnchorGlowRenderer.ownColor(Math.max(1, this.cube.charge())) & 0xFFFFFF;
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
     * green when the server answers, and the enemy's stays red while their glow is off.
     */
    boolean draw(GuiGraphicsExtractor graphics, float centerX, float centerY, float scale, float alpha, float enemy,
            boolean motion, boolean surroundings, boolean turn, int tint, float tintAmount) {
        long now = System.nanoTime();
        float frameMillis = Math.min(50.0F, (now - this.lastFrame) / 1_000_000.0F);
        this.lastFrame = now;
        if (turn && motion && now - this.lastTouch > 2_500_000_000L) {
            this.cube.rotate(frameMillis * 0.02F, 0.0F);
        }
        if (!this.cube.prepare(motion) || alpha <= 0.01F) {
            return alpha <= 0.01F;
        }
        AnchorsConfig.Glow glow = AnchorsConfig.settings().glow;
        int color = tintAmount > 0.0F ? AnchorsTheme.lerp(0xFF000000 | color(enemy), 0xFF000000 | tint, tintAmount) & 0xFFFFFF
                : color(enemy);
        float charge = this.cube.charge() / 4.0F;
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
        this.cube.layout(centerX, centerY, scale);
        // The lit pixels shine in the glow's colour: faintly for the player's own anchors, whose
        // pixels keep their colours, fully for an enemy's, which the world recolours.
        float lift = glowing ? (0.18F + 0.47F * Math.max(enemy, tintAmount)) * Math.min(1.0F, power) : 0.0F;
        this.cube.draw(graphics, alpha, AnchorTextures.GLOW, 0.0F, lift, color);
        return true;
    }

    void close() {
        this.cube.close();
    }
}
