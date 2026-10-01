package dev.zymekoh.kohsanchors.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Small drawings the bridge's windows share: the padlock that falls on a locked anchor, the server
 * the anchor talks to, a bolt of lightning and a radar sweep. Pixels only, no textures.
 */
final class AnchorFx {
    /** The bridge's green: what answers. */
    static final int GREEN = 0x3BFF8A;
    static final int GREEN_BRIGHT = 0xB8FFD2;

    private AnchorFx() {
    }

    /** A bounce that lands at 1, as a falling thing hitting the floor and settling. */
    static float bounce(float value) {
        float t = AnchorsTheme.clamp01(value);
        if (t < 1.0F / 2.75F) {
            return 7.5625F * t * t;
        }
        if (t < 2.0F / 2.75F) {
            t -= 1.5F / 2.75F;
            return 7.5625F * t * t + 0.75F;
        }
        if (t < 2.5F / 2.75F) {
            t -= 2.25F / 2.75F;
            return 7.5625F * t * t + 0.9375F;
        }
        t -= 2.625F / 2.75F;
        return 7.5625F * t * t + 0.984375F;
    }

    /**
     * A padlock {@code size} pixels wide, its body's top edge at {@code bodyTop}: an iron shackle
     * and a gold body with a keyhole, outlined in near black so it reads on any glow.
     */
    static void padlock(GuiGraphicsExtractor graphics, int centerX, int bodyTop, int size, float alpha) {
        if (alpha <= 0.01F || size < 6) {
            return;
        }
        int thickness = Math.max(2, size / 7);
        int shackleWidth = Math.round(size * 0.64F);
        int shackleHeight = Math.round(size * 0.46F);
        int left = centerX - shackleWidth / 2;
        int right = left + shackleWidth;
        int top = bodyTop - shackleHeight;
        int outline = AnchorsTheme.withAlpha(0x0A0410, Math.round(255 * alpha));
        int iron = AnchorsTheme.withAlpha(0xC9CDD8, Math.round(255 * alpha));
        int ironDark = AnchorsTheme.withAlpha(0x6B7080, Math.round(255 * alpha));
        // The shackle: two posts and the arch between them.
        graphics.fill(left - 1, top - 1, right + 1, top + thickness + 1, outline);
        graphics.fill(left - 1, top - 1, left + thickness + 1, bodyTop, outline);
        graphics.fill(right - thickness - 1, top - 1, right + 1, bodyTop, outline);
        graphics.fill(left + 1, top, right - 1, top + thickness, iron);
        graphics.fill(left, top + 1, left + thickness, bodyTop, iron);
        graphics.fill(right - thickness, top + 1, right, bodyTop, ironDark);
        // The body: gold, lighter at the top, with a rim and a keyhole.
        int bodyHeight = Math.round(size * 0.66F);
        int bodyLeft = centerX - size / 2;
        int bodyRight = bodyLeft + size;
        graphics.fill(bodyLeft - 1, bodyTop - 1, bodyRight + 1, bodyTop + bodyHeight + 1, outline);
        graphics.fillGradient(bodyLeft, bodyTop, bodyRight, bodyTop + bodyHeight,
                AnchorsTheme.withAlpha(0xFFD873, Math.round(255 * alpha)), AnchorsTheme.withAlpha(0xB07818, Math.round(255 * alpha)));
        graphics.fill(bodyLeft, bodyTop, bodyRight, bodyTop + 1, AnchorsTheme.withAlpha(0xFFF4C8, Math.round(255 * alpha)));
        graphics.fill(bodyLeft, bodyTop + bodyHeight - 2, bodyRight, bodyTop + bodyHeight,
                AnchorsTheme.withAlpha(0x7A4E0C, Math.round(255 * alpha)));
        int hole = Math.max(2, size / 6);
        int holeY = bodyTop + bodyHeight / 3;
        graphics.fill(centerX - hole / 2, holeY, centerX - hole / 2 + hole, holeY + hole, outline);
        graphics.fill(centerX - 1, holeY + hole, centerX + 1, holeY + hole + Math.max(2, size / 5), outline);
    }

    /** A small server: a rack of three drives with lights blinking at their own pace. */
    static void server(GuiGraphicsExtractor graphics, int x, int y, int size, int accent, float alpha, double seconds) {
        if (alpha <= 0.01F) {
            return;
        }
        int width = size;
        int height = Math.round(size * 1.15F);
        AnchorsUi.panel(graphics, x, y, width, height, AnchorsTheme.withAlpha(0x1D0D32, Math.round(230 * alpha)),
                AnchorsTheme.withAlpha(0x0B0514, Math.round(230 * alpha)));
        AnchorsUi.outline(graphics, x, y, width, height, AnchorsTheme.withAlpha(accent, Math.round(220 * alpha)));
        int drive = Math.max(3, (height - 4) / 3);
        for (int index = 0; index < 3; index++) {
            int driveY = y + 2 + index * drive;
            graphics.fill(x + 2, driveY + drive - 1, x + width - 2, driveY + drive,
                    AnchorsTheme.withAlpha(accent, Math.round(90 * alpha)));
            boolean lit = (int) (seconds * (3.0D + index * 1.7D)) % 2 == 0;
            graphics.fill(x + width - 5, driveY + 1, x + width - 3, driveY + 3,
                    AnchorsTheme.withAlpha(lit ? accent : 0x2A1440, Math.round(255 * alpha)));
            graphics.fill(x + 3, driveY + 1, x + Math.max(4, width / 2), driveY + 2,
                    AnchorsTheme.withAlpha(0xBDB8CF, Math.round(110 * alpha)));
        }
    }

    /** A bolt from one point to another: a jagged path that changes with {@code seed}. */
    static void lightning(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int seed, int color, float alpha) {
        if (alpha <= 0.01F) {
            return;
        }
        int segments = 7;
        float previousX = x0;
        float previousY = y0;
        for (int index = 1; index <= segments; index++) {
            float t = index / (float) segments;
            float jitter = index == segments ? 0.0F : (float) Math.sin(seed * 12.9898D + index * 78.233D) * 7.0F;
            float x = x0 + (x1 - x0) * t + jitter;
            float y = y0 + (y1 - y0) * t;
            AnchorsUi.segment(graphics, previousX, previousY, x, y, 3, AnchorsTheme.withAlpha(color, Math.round(110 * alpha)));
            AnchorsUi.segment(graphics, previousX, previousY, x, y, 1, AnchorsTheme.withAlpha(0xFFFFFF, Math.round(255 * alpha)));
            previousX = x;
            previousY = y;
        }
    }

    /** A radar sweep: a line turning around the centre with a fading trail behind it. */
    static void radar(GuiGraphicsExtractor graphics, int centerX, int centerY, int radius, double angle, int color, float alpha) {
        if (alpha <= 0.01F) {
            return;
        }
        for (int trail = 0; trail < 12; trail++) {
            double a = angle - trail * 0.09D;
            float fade = (1.0F - trail / 12.0F) * alpha;
            AnchorsUi.segment(graphics, centerX, centerY, (float) (centerX + Math.cos(a) * radius),
                    (float) (centerY + Math.sin(a) * radius), 1, AnchorsTheme.withAlpha(color, Math.round(200 * fade)));
        }
        AnchorsUi.ring(graphics, centerX, centerY, radius, 1, AnchorsTheme.withAlpha(color, Math.round(150 * alpha)));
        AnchorsUi.ring(graphics, centerX, centerY, radius / 2, 1, AnchorsTheme.withAlpha(color, Math.round(90 * alpha)));
    }
}
