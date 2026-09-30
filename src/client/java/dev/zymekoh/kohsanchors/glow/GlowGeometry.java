package dev.zymekoh.kohsanchors.glow;

import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.skin.AnchorTextures;
import dev.zymekoh.kohsanchors.skin.AnchorVariant;
import dev.zymekoh.kohsanchors.skin.AtlasSkin;
import dev.zymekoh.kohsanchors.skin.ColorMath;
import dev.zymekoh.kohsanchors.skin.SkinComposer;
import dev.zymekoh.kohsanchors.skin.SkinPaint;
import java.util.EnumMap;
import java.util.Map;

/**
 * What glows on each anchor texture, measured once per skin change: the lit pixels, merged into
 * runs, and a soft light map around them.
 *
 * <p>The light map is the bloom. The lit pixels' light is gathered into a coarse grid over the
 * face and spread with a Gaussian that reaches past the face's edges, so the light follows the
 * texture's own shapes: four separate charge lights bloom as four, the portal blooms as a square.
 * This is what makes the glow look lit rather than haloed.</p>
 */
final class GlowGeometry {
    /** Cells across a face in the light map. */
    static final int CELLS = 8;
    /** Cells the light map reaches past each edge of the face. */
    static final int BORDER = 2;
    /** Vertices across the light map. */
    static final int GRID = CELLS + BORDER * 2 + 1;
    private static final float SIGMA = 1.15F;

    private static final Map<AnchorVariant, FaceGlow> CACHE = new EnumMap<>(AnchorVariant.class);
    private static int cachedGeneration = Integer.MIN_VALUE;
    private static int cachedRevision = Integer.MIN_VALUE;

    private GlowGeometry() {
    }

    /** The glow of one texture, or {@code null} while the textures cannot be read. */
    static FaceGlow get(AnchorVariant variant) {
        int generation = AtlasSkin.generation();
        int revision = AnchorsConfig.revision() * 31 + SkinPaint.revision();
        if (generation != cachedGeneration || revision != cachedRevision) {
            CACHE.clear();
            cachedGeneration = generation;
            cachedRevision = revision;
        }
        FaceGlow glow = CACHE.get(variant);
        if (glow == null) {
            glow = build(variant);
            if (glow == null) {
                return null;
            }
            CACHE.put(variant, glow);
        }
        return glow;
    }

    private static FaceGlow build(AnchorVariant variant) {
        SkinComposer composer = AtlasSkin.composer();
        int[] pixels = AtlasSkin.composed(variant);
        if (composer == null || pixels == null) {
            return null;
        }
        AnchorTextures.Texture texture = composer.textures().get(variant);
        AnchorsConfig.Skin skin = AnchorsConfig.settings().skin;
        SkinPaint.Grid grid = skin.enabled && texture.width == texture.height
                ? SkinPaint.grid(variant.face(), texture.width) : null;
        int width = texture.width;
        int height = texture.height;
        int area = texture.area();

        // Each pixel's colour, averaged over the animation, and whether it glows.
        float[] red = new float[area];
        float[] green = new float[area];
        float[] blue = new float[area];
        boolean[] lit = new boolean[area];
        for (int index = 0; index < area; index++) {
            if (composer.layerOf(texture, grid, index) != AnchorTextures.GLOW) {
                continue;
            }
            float r = 0.0F;
            float g = 0.0F;
            float b = 0.0F;
            for (int frame = 0; frame < texture.frames; frame++) {
                int pixel = pixels[frame * area + index];
                r += ColorMath.toLinear(pixel >> 16);
                g += ColorMath.toLinear(pixel >> 8);
                b += ColorMath.toLinear(pixel);
            }
            red[index] = r / texture.frames;
            green[index] = g / texture.frames;
            blue[index] = b / texture.frames;
            lit[index] = true;
        }

        // Runs of lit pixels along each row, for the emissive layer.
        float[] runs = new float[area * 8];
        int runCount = 0;
        for (int y = 0; y < height; y++) {
            int x = 0;
            while (x < width) {
                int index = y * width + x;
                if (!lit[index]) {
                    x++;
                    continue;
                }
                int start = x;
                float r = 0.0F;
                float g = 0.0F;
                float b = 0.0F;
                int count = 0;
                int first = packed(red[index], green[index], blue[index]);
                while (x < width && lit[y * width + x]
                        && !ColorMath.differs(first, packed(red[y * width + x], green[y * width + x], blue[y * width + x]))) {
                    r += red[y * width + x];
                    g += green[y * width + x];
                    b += blue[y * width + x];
                    count++;
                    x++;
                }
                int offset = runCount * 8;
                runs[offset] = start / (float) width;
                runs[offset + 1] = y / (float) height;
                runs[offset + 2] = x / (float) width;
                runs[offset + 3] = (y + 1) / (float) height;
                runs[offset + 4] = ColorMath.fromLinear(r / count) / 255.0F;
                runs[offset + 5] = ColorMath.fromLinear(g / count) / 255.0F;
                runs[offset + 6] = ColorMath.fromLinear(b / count) / 255.0F;
                runs[offset + 7] = (0.2126F * r + 0.7152F * g + 0.0722F * b) / count;
                runCount++;
            }
        }

        // Light gathered per cell, then spread over the grid of vertices.
        float[] cellR = new float[CELLS * CELLS];
        float[] cellG = new float[CELLS * CELLS];
        float[] cellB = new float[CELLS * CELLS];
        for (int y = 0; y < height; y++) {
            int cellY = Math.min(CELLS - 1, y * CELLS / height);
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (!lit[index]) {
                    continue;
                }
                int cell = cellY * CELLS + Math.min(CELLS - 1, x * CELLS / width);
                cellR[cell] += red[index];
                cellG[cell] += green[index];
                cellB[cell] += blue[index];
            }
        }
        float pixelsPerCell = (width / (float) CELLS) * (height / (float) CELLS);
        float[] bloom = new float[GRID * GRID * 4];
        float peak = 0.0F;
        float norm = 1.0F / (float) (2.0D * Math.PI * SIGMA * SIGMA);
        for (int gy = 0; gy < GRID; gy++) {
            for (int gx = 0; gx < GRID; gx++) {
                // The vertex, in cells from the face's corner.
                float vx = gx - BORDER;
                float vy = gy - BORDER;
                float r = 0.0F;
                float g = 0.0F;
                float b = 0.0F;
                for (int cy = 0; cy < CELLS; cy++) {
                    float dy = vy - (cy + 0.5F);
                    if (Math.abs(dy) > 3.5F) {
                        continue;
                    }
                    for (int cx = 0; cx < CELLS; cx++) {
                        float dx = vx - (cx + 0.5F);
                        if (Math.abs(dx) > 3.5F) {
                            continue;
                        }
                        int cell = cy * CELLS + cx;
                        if (cellR[cell] + cellG[cell] + cellB[cell] <= 0.0F) {
                            continue;
                        }
                        float weight = norm * (float) Math.exp(-(dx * dx + dy * dy) / (2.0F * SIGMA * SIGMA));
                        r += cellR[cell] / pixelsPerCell * weight;
                        g += cellG[cell] / pixelsPerCell * weight;
                        b += cellB[cell] / pixelsPerCell * weight;
                    }
                }
                int offset = (gy * GRID + gx) * 4;
                bloom[offset] = r;
                bloom[offset + 1] = g;
                bloom[offset + 2] = b;
                bloom[offset + 3] = 0.2126F * r + 0.7152F * g + 0.0722F * b;
                peak = Math.max(peak, bloom[offset + 3]);
            }
        }
        // A face whose brightest light is dim is lifted so the bloom reads on every pack.
        float gain = peak > 0.0F ? Math.min(6.0F, 0.9F / peak) : 0.0F;
        for (int index = 0; index < bloom.length; index++) {
            bloom[index] *= gain;
        }
        return new FaceGlow(runs, runCount, bloom, peak > 0.0F);
    }

    private static int packed(float r, float g, float b) {
        return 0xFF000000 | ColorMath.fromLinear(r) << 16 | ColorMath.fromLinear(g) << 8 | ColorMath.fromLinear(b);
    }

    /** One texture's glow. */
    static final class FaceGlow {
        /** Runs of lit pixels: u0, v0, u1, v1 (0 to 1 on the face), r, g, b (0 to 1), luminance. */
        final float[] runs;
        final int runCount;
        /** The light map, {@link #GRID} by {@link #GRID} vertices of linear r, g, b and luminance. */
        final float[] bloom;
        final boolean glows;

        /** Bloom cell colours per stride, built on first use: no colour maths per frame. */
        private final int[][] colours = new int[GRID][];

        FaceGlow(float[] runs, int runCount, float[] bloom, boolean glows) {
            this.runs = runs;
            this.runCount = runCount;
            this.bloom = bloom;
            this.glows = glows;
        }

        /** The colour of the bloom cell {@code stride} points wide whose first corner is (gx, gy). */
        int bloomColour(int stride, int gx, int gy) {
            int[] table = this.colours[stride];
            if (table == null) {
                table = new int[GRID * GRID];
                for (int y = 0; y + stride < GRID; y++) {
                    for (int x = 0; x + stride < GRID; x++) {
                        int a = (y * GRID + x) * 4;
                        int b = (y * GRID + x + stride) * 4;
                        int c = ((y + stride) * GRID + x) * 4;
                        int d = ((y + stride) * GRID + x + stride) * 4;
                        float r = this.bloom[a] + this.bloom[b] + this.bloom[c] + this.bloom[d];
                        float g = this.bloom[a + 1] + this.bloom[b + 1] + this.bloom[c + 1] + this.bloom[d + 1];
                        float bl = this.bloom[a + 2] + this.bloom[b + 2] + this.bloom[c + 2] + this.bloom[d + 2];
                        float max = Math.max(r, Math.max(g, bl));
                        table[y * GRID + x] = max <= 0.0F ? 0xFFFFFFFF : 0xFF000000 | ColorMath.fromLinear(r / max) << 16
                                | ColorMath.fromLinear(g / max) << 8 | ColorMath.fromLinear(bl / max);
                    }
                }
                this.colours[stride] = table;
            }
            return table[gy * GRID + gx];
        }
    }
}
