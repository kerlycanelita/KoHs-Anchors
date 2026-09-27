package dev.zymekoh.kohsanchors.gui;

/**
 * Colours of the KoHs Anchor's screen, in one place: KoHs dark purple glass over a transparent
 * purple veil, lit by the violet of a charged anchor. Everything is ARGB.
 */
final class AnchorsTheme {
    /** The veil over the world: transparent enough that the world stays visible behind it. */
    static final int VEIL_TOP = 0x5A160628;
    static final int VEIL_BOTTOM = 0x8A08020F;

    static final int PANEL_TOP = 0xC61D0A30;
    static final int PANEL_BOTTOM = 0xBC0B0415;
    static final int PANEL_BORDER = 0xD2B46AF7;
    static final int HEADER_LINE = 0x5AB46AF7;

    static final int CARD = 0x2A2C1042;
    static final int CARD_HOVER = 0x46421A63;
    static final int CARD_BORDER = 0x5E4A2272;
    static final int CARD_BORDER_HOVER = 0xC8C88CFF;

    static final int SECTION = 0xFFD8BAFF;
    static final int TEXT = 0xFFF8EEFF;
    static final int TEXT_MUTED = 0xFFC4B0D8;
    static final int TEXT_DIM = 0xFF9D89B2;
    static final int TITLE = 0xFFEBD6FF;

    static final int ACCENT = 0xFFC77DFF;
    static final int ACCENT_BRIGHT = 0xFFF1DBFF;
    static final int ACCENT_DEEP = 0xFF6A2BC2;

    static final int SWITCH_OFF = 0xFF2B1640;
    static final int SWITCH_ON = 0xFF8E45E6;
    static final int KNOB_OFF = 0xFFB5A1CB;
    static final int KNOB_ON = 0xFFFFFFFF;
    static final int STATE_ON = 0xFFE6C9FF;
    static final int STATE_OFF = 0xFF9D89B2;

    static final int SCROLL_TRACK = 0x662B1236;
    static final int SCROLL_THUMB = 0xE6C07BFF;

    static final int BUTTON_TOP = 0xDC4A1C6E;
    static final int BUTTON_BOTTOM = 0xE6250C3A;
    static final int BUTTON_HOVER_TOP = 0xF07A34B8;
    static final int BUTTON_HOVER_BOTTOM = 0xF03C1463;

    // The anchor drawing.
    static final int OBSIDIAN_LIGHT = 0xFF2A1440;
    static final int OBSIDIAN = 0xFF180A26;
    static final int OBSIDIAN_DARK = 0xFF0C0514;
    static final int OBSIDIAN_EDGE = 0xFF3E1D5E;
    static final int CRYING = 0xFF7B30D8;
    static final int CRYING_BRIGHT = 0xFFB46CFF;
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

    static float easeInOutSine(float value) {
        return (float) (0.5D - 0.5D * Math.cos(Math.PI * clamp01(value)));
    }
}
