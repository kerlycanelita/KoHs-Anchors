package dev.zymekoh.kohsanchors.gui;

/**
 * Zymekoh // Velocity: the colour system of the KoHs Inventory Tweaks interface.
 *
 * <p>Violet carries the identity and fills most of the screen; every other hue has
 * a job. Icy cyan means speed and precision (the fast opening, the cursor), crimson
 * means danger, magenta marks energy and experimental states, silver is technical
 * detail and bone white is the text. Brightness is kept for what matters, so the
 * few glowing accents stand out against the dark.</p>
 */
public final class ZTheme {
    // Depth: the void behind everything.
    public static final int VOID = 0xFF07040C;
    public static final int BACKDROP_TOP = 0xC6090512;
    public static final int BACKDROP_BOTTOM = 0xEA050309;
    public static final int MODAL_DIM = 0xB8050309;

    // Glass surfaces, from the panel up to a selected card.
    public static final int SURFACE = 0xE0120A20;
    public static final int SURFACE_LIGHT = 0xD21A0E2E;
    public static final int SURFACE_HOVER = 0xE824123F;
    public static final int SURFACE_SELECTED = 0xF0331760;
    public static final int SURFACE_PREVIEW = 0xD40C0616;

    // Violet identity.
    public static final int VIOLET_DEEP = 0xFF4C1D95;
    public static final int VIOLET = 0xFF7C3AED;
    public static final int VIOLET_ELECTRIC = 0xFF9333EA;
    public static final int VIOLET_BRIGHT = 0xFFA855F7;
    public static final int LILAC = 0xFFC084FC;
    public static final int LILAC_PALE = 0xFFE4CCFF;

    // Accents with a purpose.
    public static final int CYAN = 0xFF3FDCFF;
    public static final int CYAN_DEEP = 0xFF1582A8;
    public static final int MAGENTA = 0xFFEC4BB6;
    public static final int CRIMSON = 0xFFC8193F;
    public static final int CRIMSON_BRIGHT = 0xFFFF3D66;
    public static final int SILVER = 0xFFBDB8CF;

    // Type.
    public static final int TEXT = 0xFFF6F0FB;
    public static final int TEXT_MUTED = 0xFFB8A6CC;
    public static final int TEXT_DISABLED = 0xFF6D5E80;

    // Lines and depth cues.
    public static final int BORDER = 0xC08B5CF6;
    public static final int BORDER_SOFT = 0x74503177;
    public static final int BORDER_HOT = 0xFFD2A6FF;
    public static final int SHADOW = 0x70000000;
    public static final int SCROLL_TRACK = 0x6A3B2455;

    private ZTheme() {
    }

    /** {@code color} with its alpha replaced. */
    public static int alpha(final int color, final int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    /** {@code color} with its alpha scaled by {@code factor}. */
    public static int fade(final int color, final float factor) {
        int alpha = Math.round(((color >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, factor)));
        return alpha(color, alpha);
    }

    /** Channel-wise blend of two ARGB colours. */
    public static int mix(final int from, final int to, final float amount) {
        float t = Math.max(0.0F, Math.min(1.0F, amount));
        int a = channel(from, 24, to, t);
        int r = channel(from, 16, to, t);
        int g = channel(from, 8, to, t);
        int b = channel(from, 0, to, t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static int channel(final int from, final int shift, final int to, final float t) {
        int start = (from >>> shift) & 0xFF;
        int end = (to >>> shift) & 0xFF;
        return Math.round(start + (end - start) * t);
    }
}
