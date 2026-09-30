package dev.zymekoh.kohsanchors.skin;

import dev.zymekoh.kohsanchors.config.AnchorsConfig;

/**
 * Turns the resource pack's anchor textures and the player's skin into the pixels that are drawn.
 *
 * <p>The basic colours recolour a whole layer and keep its texture: in OKLab, each pixel takes the
 * chosen hue and chroma, and its lightness is scaled so the layer's average lightness becomes the
 * chosen colour's while every pixel keeps its difference from the others. So a dark obsidian frame
 * painted pink turns into pink stone with the same cracks, not into a flat pink square. The
 * strength mixes the result with the original in linear light. Painted pixels go on top as they
 * are.</p>
 */
public final class SkinComposer {
    /** The three groups the colours apply to; the charge lights are part of the glow layer. */
    private static final int FRAME = 0;
    private static final int GLOW = 1;
    private static final int CHARGE = 2;

    private final AnchorTextures textures;
    private final float[] meanLightness = new float[3];
    private final float[] meanChroma = new float[3];
    private final float[] lab = new float[3];
    private final float[] target = new float[3];

    public SkinComposer(AnchorTextures textures) {
        this.textures = textures;
        measure();
    }

    public AnchorTextures textures() {
        return this.textures;
    }

    /** The layer the player sees for one pixel: what they moved it to, or what the analysis found. */
    public byte layerOf(AnchorTextures.Texture texture, SkinPaint.Grid grid, int index) {
        if (grid != null) {
            byte moved = grid.layer[index];
            if (moved == SkinPaint.TO_FRAME) {
                return AnchorTextures.FRAME;
            }
            if (moved == SkinPaint.TO_GLOW) {
                return AnchorTextures.GLOW;
            }
        }
        return texture.layer[index];
    }

    /**
     * The pixels of {@code variant} with the skin applied, every frame, in the same layout as
     * {@link AnchorTextures.Texture#pixels}. With the skin off, the original pixels.
     */
    public int[] compose(AnchorVariant variant, AnchorsConfig.Skin skin) {
        AnchorTextures.Texture texture = this.textures.get(variant);
        if (!skin.enabled) {
            return texture.pixels;
        }
        SkinPaint.Grid grid = SkinPaint.grid(variant.face(), texture.width);
        if (texture.height != texture.width) {
            grid = null;
        }
        int area = texture.area();
        int[] out = new int[texture.pixels.length];
        for (int index = 0; index < area; index++) {
            byte layer = layerOf(texture, grid, index);
            int group;
            int color;
            int strength;
            if (layer == AnchorTextures.GLOW) {
                boolean light = texture.litChargeLight(index);
                group = light ? CHARGE : GLOW;
                color = light && skin.chargeColors ? skin.charge[texture.chargeLight[index] - 1] : skin.glowColor;
                strength = skin.chargeColors && light ? Math.max(skin.glowStrength, 100) : skin.glowStrength;
            } else {
                group = FRAME;
                color = skin.frameColor;
                strength = skin.frameStrength;
            }
            int painted = grid == null ? 0 : grid.paint(layer)[index];
            for (int frame = 0; frame < texture.frames; frame++) {
                int source = texture.pixels[frame * area + index];
                int result = strength > 0 ? recolour(source, color, group, strength / 100.0F) : source;
                if (painted != 0) {
                    float alpha = ((painted >>> 24) & 255) / 255.0F;
                    result = ColorMath.mixLinear(result, painted | 0xFF000000, alpha)
                            & 0x00FFFFFF | (source & 0xFF000000);
                }
                out[frame * area + index] = result;
            }
        }
        return out;
    }

    /**
     * {@code source} recoloured towards {@code color}, keeping its place among the pixels of its
     * group, mixed with the original by {@code strength}.
     */
    public int recolour(int source, int color, int group, float strength) {
        ColorMath.toOklab(source, this.lab);
        ColorMath.toOklab(color, this.target);
        float reference = Math.max(0.04F, this.meanLightness[group]);
        float lightness = this.lab[0] * (this.target[0] / reference);
        // Values past white roll off instead of clipping, so bright pixels keep some detail.
        if (lightness > 0.85F) {
            lightness = 0.85F + (1.0F - 0.85F) * (1.0F - (float) Math.exp(-(lightness - 0.85F) / 0.15F));
        }
        float sourceChroma = (float) Math.hypot(this.lab[1], this.lab[2]);
        float targetChroma = (float) Math.hypot(this.target[1], this.target[2]);
        float relative = this.meanChroma[group] > 0.01F ? sourceChroma / this.meanChroma[group] : 1.0F;
        float chroma = Math.min(0.37F, targetChroma * (0.65F + 0.35F * Math.min(2.0F, relative)));
        float hue = (float) Math.atan2(this.target[2], this.target[1]);
        int recoloured = ColorMath.fromOklab(Math.max(0.0F, Math.min(1.0F, lightness)),
                chroma * (float) Math.cos(hue), chroma * (float) Math.sin(hue), (source >>> 24) & 255);
        return ColorMath.mixLinear(source, recoloured, strength);
    }

    /** The average OKLab lightness and chroma of each group, over every texture and frame. */
    private void measure() {
        double[] lightness = new double[3];
        double[] chroma = new double[3];
        int[] counts = new int[3];
        for (AnchorVariant variant : AnchorVariant.values()) {
            AnchorTextures.Texture texture = this.textures.get(variant);
            int area = texture.area();
            for (int frame = 0; frame < texture.frames; frame++) {
                for (int index = 0; index < area; index++) {
                    int pixel = texture.pixels[frame * area + index];
                    if (((pixel >>> 24) & 255) < 16) {
                        continue;
                    }
                    int group = texture.layer[index] == AnchorTextures.GLOW
                            ? (texture.litChargeLight(index) ? CHARGE : GLOW) : FRAME;
                    ColorMath.toOklab(pixel, this.lab);
                    lightness[group] += this.lab[0];
                    chroma[group] += Math.hypot(this.lab[1], this.lab[2]);
                    counts[group]++;
                }
            }
        }
        for (int group = 0; group < 3; group++) {
            this.meanLightness[group] = counts[group] == 0 ? 0.5F : (float) (lightness[group] / counts[group]);
            this.meanChroma[group] = counts[group] == 0 ? 0.1F : (float) (chroma[group] / counts[group]);
        }
    }

    /** The average colour of a group after the skin, for the glow's automatic colour. */
    public int averageGlowColor(AnchorsConfig.Skin skin, int charge) {
        AnchorVariant variant = charge > 0 ? AnchorVariant.side(charge) : AnchorVariant.SIDE0;
        double r = 0;
        double g = 0;
        double b = 0;
        double weight = 0;
        for (AnchorVariant source : new AnchorVariant[] {AnchorVariant.TOP, variant}) {
            AnchorTextures.Texture texture = this.textures.get(source);
            int[] pixels = compose(source, skin);
            int area = texture.area();
            SkinPaint.Grid grid = skin.enabled ? SkinPaint.grid(source.face(), texture.width) : null;
            for (int index = 0; index < area; index++) {
                if (layerOf(texture, grid, index) != AnchorTextures.GLOW) {
                    continue;
                }
                int pixel = pixels[index];
                double w = 0.2D + ColorMath.luminance(pixel);
                r += ColorMath.toLinear(pixel >> 16) * w;
                g += ColorMath.toLinear(pixel >> 8) * w;
                b += ColorMath.toLinear(pixel) * w;
                weight += w;
            }
        }
        if (weight <= 0.0D) {
            return 0xFFB14DFF;
        }
        float max = (float) Math.max(r, Math.max(g, b)) / (float) weight;
        float scale = max > 0.0F ? 1.0F / max : 1.0F;
        return 0xFF000000 | ColorMath.fromLinear((float) (r / weight) * scale) << 16
                | ColorMath.fromLinear((float) (g / weight) * scale) << 8
                | ColorMath.fromLinear((float) (b / weight) * scale);
    }
}
