package dev.zymekoh.kohsanchors.gui;

/**
 * Colours of the KoHs Anchor's screen, in one place. Zymekoh: black-purple glass over a
 * transparent veil, violet as the identity, crimson only for danger, silver and bone white for
 * text and metal. Everything is ARGB.
 */
final class AnchorsTheme {
    /** The veil over the world: dark, but the world stays visible behind it. */
    static final int VEIL_TOP = 0x78100720;
    static final int VEIL_BOTTOM = 0xA806030D;

    static final int PANEL_TOP = 0xD8160B27;
    static final int PANEL_BOTTOM = 0xD00B0514;
    static final int PANEL_BORDER = 0xE0A855F7;
    static final int HEADER_LINE = 0x6AA855F7;

    static final int CARD = 0x5A1D0D32;
    static final int CARD_HOVER = 0x7A2A1248;
    static final int CARD_BOTTOM = 0x4A12091F;
    static final int CARD_BOTTOM_HOVER = 0x6A1D0D32;
    static final int CARD_BORDER = 0x7A4C2380;
    static final int CARD_BORDER_HOVER = 0xE0C084FC;

    static final int SECTION = 0xFFD8B4FE;
    static final int TEXT = 0xFFF4EEFA;
    static final int TEXT_MUTED = 0xFFBDB8CF;
    static final int TEXT_DIM = 0xFF8E8AA0;
    static final int TITLE = 0xFFFFF7FF;

    static final int ACCENT = 0xFFA855F7;
    static final int ACCENT_BRIGHT = 0xFFE9D5FF;
    static final int ACCENT_DEEP = 0xFF7C3AED;
    static final int MAGENTA = 0xFFE83EAF;
    static final int CYAN = 0xFF52F2FF;
    static final int SILVER = 0xFFBDB8CF;

    /** Danger: the advanced tab, the warning and its ritual. */
    static final int CRIMSON = 0xFFD11F4A;
    static final int CRIMSON_BRIGHT = 0xFFFF315C;
    static final int CRIMSON_DEEP = 0xFFA31234;
    static final int CRIMSON_GLASS_TOP = 0xD82A0A18;
    static final int CRIMSON_GLASS_BOTTOM = 0xD8140510;
    static final int BLOOD_DARK = 0xFF1A0308;

    /** Developer mode: blue-violet, the colour of a debugger, still inside the purple family. */
    static final int DEV_BLUE = 0xFF5B6CFF;
    static final int DEV_BLUE_BRIGHT = 0xFF9DB0FF;
    static final int DEV_INDIGO = 0xFF4F2FD9;
    static final int DEV_GLASS_TOP = 0xE0141A4A;
    static final int DEV_GLASS_BOTTOM = 0xE00A0C26;
    static final int DEV_TEXT = 0xFFDDE4FF;

    static final int SWITCH_OFF = 0xFF25123F;
    static final int SWITCH_ON = 0xFF9333EA;
    static final int SWITCH_DANGER_ON = 0xFFD11F4A;
    static final int KNOB_OFF = 0xFFBDB8CF;
    static final int KNOB_ON = 0xFFFFF7FF;
    static final int STATE_ON = 0xFFE9D5FF;
    static final int STATE_OFF = 0xFF8E8AA0;

    static final int SCROLL_TRACK = 0x66251240;
    static final int SCROLL_THUMB = 0xE6C084FC;

    static final int BUTTON_TOP = 0xE03A1668;
    static final int BUTTON_BOTTOM = 0xE61D0D32;
    static final int BUTTON_HOVER_TOP = 0xF06D28D9;
    static final int BUTTON_HOVER_BOTTOM = 0xF02E1052;

    // The anchor drawing.
    static final int OBSIDIAN_LIGHT = 0xFF2A1440;
    static final int OBSIDIAN = 0xFF180A26;
    static final int OBSIDIAN_DARK = 0xFF0C0514;
    static final int OBSIDIAN_EDGE = 0xFF3E1D5E;
    static final int CHARGE_OFF = 0xFF2E1846;
    static final int CHARGE_ON = 0xFFE9CCFF;
    static final int PORTAL = 0xFF9B4DFF;

    private AnchorsTheme() {
    }

    /** {@code color} with its alpha multiplied by {@code factor}. */
    static int fade(int color, float factor) {
        int alpha = (color >>> 24) & 255;
        return Math.round(alpha * clamp01(factor)) << 24 | (color & 0xFFFFFF);
    }

    static int withAlpha(int color, int alpha) {
        return Math.max(0, Math.min(255, alpha)) << 24 | (color & 0xFFFFFF);
    }

    static int lerp(int from, int to, float amount) {
        float t = clamp01(amount);
        int a = Math.round(((from >>> 24) & 255) + (((to >>> 24) & 255) - ((from >>> 24) & 255)) * t);
        int r = Math.round(((from >> 16) & 255) + (((to >> 16) & 255) - ((from >> 16) & 255)) * t);
        int g = Math.round(((from >> 8) & 255) + (((to >> 8) & 255) - ((from >> 8) & 255)) * t);
        int b = Math.round((from & 255) + ((to & 255) - (from & 255)) * t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    static float clamp01(float value) {
        return value < 0.0F ? 0.0F : Math.min(1.0F, value);
    }

    static float easeOutCubic(float value) {
        float inverse = 1.0F - clamp01(value);
        return 1.0F - inverse * inverse * inverse;
    }

    static float easeInCubic(float value) {
        float t = clamp01(value);
        return t * t * t;
    }

    /** Overshoots a little before settling, for things that pop into place. */
    static float easeOutBack(float value) {
        float shifted = clamp01(value) - 1.0F;
        return 1.0F + 2.70158F * shifted * shifted * shifted + 1.70158F * shifted * shifted;
    }

    static float easeInOutSine(float value) {
        return (float) (0.5D - 0.5D * Math.cos(Math.PI * clamp01(value)));
    }

    /** 0 to 1 and back, once every {@code period} seconds. */
    static float pulse(double seconds, double period) {
        return (float) (0.5D - 0.5D * Math.cos(seconds * Math.PI * 2.0D / period));
    }
}
