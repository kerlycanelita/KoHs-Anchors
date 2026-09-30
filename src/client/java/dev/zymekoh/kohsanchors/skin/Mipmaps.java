package dev.zymekoh.kohsanchors.skin;

/**
 * Mip levels for the skinned sprites: each level averages 2x2 pixels of the one above in linear
 * light, the way Vanilla's own generator blends an opaque block texture, so a skinned anchor fades
 * into the distance like any other block.
 */
final class Mipmaps {
    private Mipmaps() {
    }

    /**
     * Level 0 is {@code width * height} pixels of {@code source} from {@code offset}; every further
     * level halves both sides, until {@code levels} levels or a side reaches one pixel.
     */
    static int[][] generate(int[] source, int offset, int width, int height, int levels) {
        int count = Math.max(1, levels);
        int[][] result = new int[count][];
        int[] first = new int[width * height];
        System.arraycopy(source, offset, first, 0, first.length);
        result[0] = first;
        int levelWidth = width;
        int levelHeight = height;
        for (int level = 1; level < count; level++) {
            int nextWidth = Math.max(1, levelWidth >> 1);
            int nextHeight = Math.max(1, levelHeight >> 1);
            int[] previous = result[level - 1];
            int[] next = new int[nextWidth * nextHeight];
            for (int y = 0; y < nextHeight; y++) {
                for (int x = 0; x < nextWidth; x++) {
                    int x0 = Math.min(levelWidth - 1, x * 2);
                    int x1 = Math.min(levelWidth - 1, x * 2 + 1);
                    int y0 = Math.min(levelHeight - 1, y * 2);
                    int y1 = Math.min(levelHeight - 1, y * 2 + 1);
                    next[y * nextWidth + x] = average(previous[y0 * levelWidth + x0], previous[y0 * levelWidth + x1],
                            previous[y1 * levelWidth + x0], previous[y1 * levelWidth + x1]);
                }
            }
            result[level] = next;
            levelWidth = nextWidth;
            levelHeight = nextHeight;
        }
        return result;
    }

    private static int average(int a, int b, int c, int d) {
        int alpha = (((a >>> 24) & 255) + ((b >>> 24) & 255) + ((c >>> 24) & 255) + ((d >>> 24) & 255) + 2) / 4;
        float red = (ColorMath.toLinear(a >> 16) + ColorMath.toLinear(b >> 16) + ColorMath.toLinear(c >> 16)
                + ColorMath.toLinear(d >> 16)) / 4.0F;
        float green = (ColorMath.toLinear(a >> 8) + ColorMath.toLinear(b >> 8) + ColorMath.toLinear(c >> 8)
                + ColorMath.toLinear(d >> 8)) / 4.0F;
        float blue = (ColorMath.toLinear(a) + ColorMath.toLinear(b) + ColorMath.toLinear(c) + ColorMath.toLinear(d))
                / 4.0F;
        return alpha << 24 | ColorMath.fromLinear(red) << 16 | ColorMath.fromLinear(green) << 8 | ColorMath.fromLinear(blue);
    }
}
