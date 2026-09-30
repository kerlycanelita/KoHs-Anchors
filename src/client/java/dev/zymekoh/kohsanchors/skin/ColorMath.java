package dev.zymekoh.kohsanchors.skin;

/**
 * Colour arithmetic for the skin, the glow and the colour picker, in OKLab: a space where equal
 * steps look equally different, so a recoloured texture keeps the texture's contrast.
 *
 * <p>Colours travel as ARGB ints. Every method is allocation-free except where it returns an
 * array the caller asked for.</p>
 */
public final class ColorMath {
    private static final float[] TO_LINEAR = new float[256];

    static {
        for (int value = 0; value < 256; value++) {
            float c = value / 255.0F;
            TO_LINEAR[value] = c <= 0.04045F ? c / 12.92F : (float) Math.pow((c + 0.055F) / 1.055F, 2.4F);
        }
    }

    private ColorMath() {
    }

    public static float toLinear(int channel) {
        return TO_LINEAR[channel & 255];
    }

    public static int fromLinear(float linear) {
        float c = linear <= 0.0031308F ? linear * 12.92F : 1.055F * (float) Math.pow(Math.max(0.0F, linear), 1.0F / 2.4F) - 0.055F;
        return Math.max(0, Math.min(255, Math.round(c * 255.0F)));
    }

    /** OKLab of {@code argb} into {@code out} as L, a, b. */
    public static void toOklab(int argb, float[] out) {
        float r = TO_LINEAR[(argb >> 16) & 255];
        float g = TO_LINEAR[(argb >> 8) & 255];
        float b = TO_LINEAR[argb & 255];
        float l = (float) Math.cbrt(0.4122214708F * r + 0.5363325363F * g + 0.0514459929F * b);
        float m = (float) Math.cbrt(0.2119034982F * r + 0.6806995451F * g + 0.1073969566F * b);
        float s = (float) Math.cbrt(0.0883024619F * r + 0.2817188376F * g + 0.6299787005F * b);
        out[0] = 0.2104542553F * l + 0.7936177850F * m - 0.0040720468F * s;
        out[1] = 1.9779984951F * l - 2.4285922050F * m + 0.4505937099F * s;
        out[2] = 0.0259040371F * l + 0.7827717662F * m - 0.8086757660F * s;
    }

    /**
     * The colour at OKLab {@code L, a, b} with {@code alpha}. Outside the sRGB gamut the chroma is
     * reduced until it fits, so a hue never flips into another.
     */
    public static int fromOklab(float lightness, float a, float b, int alpha) {
        float scale = 1.0F;
        for (int attempt = 0; attempt < 12; attempt++) {
            float[] linear = oklabToLinear(lightness, a * scale, b * scale);
            if (inGamut(linear)) {
                return (alpha & 255) << 24 | fromLinear(linear[0]) << 16 | fromLinear(linear[1]) << 8
                        | fromLinear(linear[2]);
            }
            scale *= 0.8F;
        }
        float[] linear = oklabToLinear(lightness, 0.0F, 0.0F);
        return (alpha & 255) << 24 | fromLinear(linear[0]) << 16 | fromLinear(linear[1]) << 8 | fromLinear(linear[2]);
    }

    private static float[] oklabToLinear(float lightness, float a, float b) {
        float l = lightness + 0.3963377774F * a + 0.2158037573F * b;
        float m = lightness - 0.1055613458F * a - 0.0638541728F * b;
        float s = lightness - 0.0894841775F * a - 1.2914855480F * b;
        l = l * l * l;
        m = m * m * m;
        s = s * s * s;
        return new float[] {
                4.0767416621F * l - 3.3077115913F * m + 0.2309699292F * s,
                -1.2684380046F * l + 2.6097574011F * m - 0.3413193965F * s,
                -0.0041960863F * l - 0.7034186147F * m + 1.7076147010F * s};
    }

    private static boolean inGamut(float[] linear) {
        float tolerance = 0.002F;
        for (float channel : linear) {
            if (channel < -tolerance || channel > 1.0F + tolerance) {
                return false;
            }
        }
        return true;
    }

    /** Mixes two colours by {@code t} in linear light, alpha included. */
    public static int mixLinear(int from, int to, float t) {
        if (t <= 0.0F) {
            return from;
        }
        if (t >= 1.0F) {
            return to;
        }
        int alpha = Math.round(((from >>> 24) & 255) + (((to >>> 24) & 255) - ((from >>> 24) & 255)) * t);
        int r = fromLinear(TO_LINEAR[(from >> 16) & 255] + (TO_LINEAR[(to >> 16) & 255] - TO_LINEAR[(from >> 16) & 255]) * t);
        int g = fromLinear(TO_LINEAR[(from >> 8) & 255] + (TO_LINEAR[(to >> 8) & 255] - TO_LINEAR[(from >> 8) & 255]) * t);
        int b = fromLinear(TO_LINEAR[from & 255] + (TO_LINEAR[to & 255] - TO_LINEAR[from & 255]) * t);
        return alpha << 24 | r << 16 | g << 8 | b;
    }

    /** Relative luminance, 0 to 1. */
    public static float luminance(int argb) {
        return 0.2126F * TO_LINEAR[(argb >> 16) & 255] + 0.7152F * TO_LINEAR[(argb >> 8) & 255]
                + 0.0722F * TO_LINEAR[argb & 255];
    }

    /** HSV saturation and value, 0 to 1, into {@code out[0]} and {@code out[1]}. */
    public static void saturationValue(int argb, float[] out) {
        int r = (argb >> 16) & 255;
        int g = (argb >> 8) & 255;
        int b = argb & 255;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        out[0] = max == 0 ? 0.0F : (max - min) / (float) max;
        out[1] = max / 255.0F;
    }

    /** Whether two colours differ enough to be different pixels, not compression noise. */
    public static boolean differs(int first, int second) {
        int dr = ((first >> 16) & 255) - ((second >> 16) & 255);
        int dg = ((first >> 8) & 255) - ((second >> 8) & 255);
        int db = (first & 255) - (second & 255);
        int da = ((first >>> 24) & 255) - ((second >>> 24) & 255);
        return dr * dr + dg * dg + db * db + da * da > 12 * 12;
    }

    /** HSV to RGB, each 0 to 1, as an opaque ARGB colour. */
    public static int fromHsv(float hue, float saturation, float value) {
        float h = ((hue % 1.0F) + 1.0F) % 1.0F * 6.0F;
        int sector = (int) Math.floor(h);
        float f = h - sector;
        float p = value * (1.0F - saturation);
        float q = value * (1.0F - saturation * f);
        float t = value * (1.0F - saturation * (1.0F - f));
        float r;
        float g;
        float b;
        switch (sector) {
            case 0 -> { r = value; g = t; b = p; }
            case 1 -> { r = q; g = value; b = p; }
            case 2 -> { r = p; g = value; b = t; }
            case 3 -> { r = p; g = q; b = value; }
            case 4 -> { r = t; g = p; b = value; }
            default -> { r = value; g = p; b = q; }
        }
        return 0xFF000000 | Math.round(r * 255.0F) << 16 | Math.round(g * 255.0F) << 8 | Math.round(b * 255.0F);
    }

    /** RGB to HSV into {@code out} as hue, saturation, value, each 0 to 1. */
    public static void toHsv(int argb, float[] out) {
        float r = ((argb >> 16) & 255) / 255.0F;
        float g = ((argb >> 8) & 255) / 255.0F;
        float b = (argb & 255) / 255.0F;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float hue;
        if (delta < 1.0E-5F) {
            hue = 0.0F;
        } else if (max == r) {
            hue = ((g - b) / delta) / 6.0F;
        } else if (max == g) {
            hue = ((b - r) / delta + 2.0F) / 6.0F;
        } else {
            hue = ((r - g) / delta + 4.0F) / 6.0F;
        }
        out[0] = (hue + 1.0F) % 1.0F;
        out[1] = max <= 0.0F ? 0.0F : delta / max;
        out[2] = max;
    }

    /** "#RRGGBB". */
    public static String hex(int argb) {
        return String.format(java.util.Locale.ROOT, "#%06X", argb & 0xFFFFFF);
    }

    /** Reads "#RRGGBB", "RRGGBB" or "#RGB"; {@code fallback} when it is not a colour. */
    public static int parseHex(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        String value = text.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.length() == 3) {
            value = "" + value.charAt(0) + value.charAt(0) + value.charAt(1) + value.charAt(1) + value.charAt(2)
                    + value.charAt(2);
        }
        if (value.length() != 6) {
            return fallback;
        }
        try {
            return 0xFF000000 | Integer.parseInt(value, 16);
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}
