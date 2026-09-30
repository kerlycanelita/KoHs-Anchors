package dev.zymekoh.kohsanchors.gui;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Drawing primitives for the KoHs Anchor's screen. Decoration only: nothing here reads or moves a
 * widget, so no animation can pull a hitbox away from what is drawn.
 */
final class AnchorsUi {
    private AnchorsUi() {
    }

    /**
     * Starts a new GUI stratum. Minecraft places every GUI element by testing its bounds against
     * the elements already in the stratum, so a shape drawn from hundreds of spans (a ring, a soft
     * glow, a gradient square) made each later element test all of them: quadratic work that cost
     * the settings screen half its frame rate. Shapes like that get a stratum of their own, opened
     * before and after them. What shows is unchanged: a later stratum is drawn over an earlier
     * one, just as a later element is drawn over what it overlaps.
     */
    static void isolate(GuiGraphicsExtractor graphics) {
        graphics.nextStratum();
    }

    /**
     * Rows of a round shape per stratum: the rows of one shape never overlap, so each would still
     * test all the rows before it; a fresh stratum every few rows keeps a large ring linear.
     */
    private static final int ROWS_PER_STRATUM = 24;

    /** A panel with 3px rounded corners and a vertical gradient. */
    static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int top, int bottom) {
        if (width < 6 || height < 6) {
            if (width > 0 && height > 0) {
                graphics.fillGradient(x, y, x + width, y + height, top, bottom);
            }
            return;
        }
        graphics.fillGradient(x + 3, y, x + width - 3, y + height, top, bottom);
        graphics.fillGradient(x + 1, y + 1, x + 3, y + height - 1, top, bottom);
        graphics.fillGradient(x + width - 3, y + 1, x + width - 1, y + height - 1, top, bottom);
        graphics.fillGradient(x, y + 3, x + 1, y + height - 3, top, bottom);
        graphics.fillGradient(x + width - 1, y + 3, x + width, y + height - 3, top, bottom);
    }

    static void roundedOutline(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
        if (width < 6 || height < 6) {
            outline(graphics, x, y, width, height, color);
            return;
        }
        int right = x + width;
        int bottom = y + height;
        graphics.fill(x + 3, y, right - 3, y + 1, color);
        graphics.fill(x + 3, bottom - 1, right - 3, bottom, color);
        graphics.fill(x, y + 3, x + 1, bottom - 3, color);
        graphics.fill(right - 1, y + 3, right, bottom - 3, color);
        graphics.fill(x + 1, y + 1, x + 3, y + 2, color);
        graphics.fill(x + 1, y + 2, x + 2, y + 3, color);
        graphics.fill(right - 3, y + 1, right - 1, y + 2, color);
        graphics.fill(right - 2, y + 2, right - 1, y + 3, color);
        graphics.fill(x + 1, bottom - 2, x + 3, bottom - 1, color);
        graphics.fill(x + 1, bottom - 3, x + 2, bottom - 2, color);
        graphics.fill(right - 3, bottom - 2, right - 1, bottom - 1, color);
        graphics.fill(right - 2, bottom - 3, right - 1, bottom - 2, color);
    }

    static void outline(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    /** A soft halo: outlines that fade as they move away from the rectangle. */
    static void halo(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color, int layers,
            float strength) {
        int baseAlpha = (color >>> 24) & 255;
        for (int layer = 1; layer <= layers; layer++) {
            int alpha = Math.round(baseAlpha * strength * (1.0F - (layer - 1) / (float) layers) * 0.45F);
            if (alpha < 3) {
                break;
            }
            roundedOutline(graphics, x - layer, y - layer, width + layer * 2, height + layer * 2,
                    AnchorsTheme.withAlpha(color, alpha));
        }
    }

    /**
     * Text that honours a faded colour. Minecraft draws a nearly transparent text colour fully
     * opaque, so anything faded below visibility is skipped instead.
     */
    static void label(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color, boolean shadow) {
        if (((color >>> 24) & 255) < 8 || text.isEmpty()) {
            return;
        }
        graphics.text(font, text, x, y, color, shadow);
    }

    static void line(GuiGraphicsExtractor graphics, Font font, FormattedCharSequence text, int x, int y, int color) {
        if (((color >>> 24) & 255) < 8) {
            return;
        }
        graphics.text(font, text, x, y, color, false);
    }

    /** {@code text} cut to {@code room} pixels. */
    static String fit(Font font, String text, int room) {
        return font.width(text) > room ? font.plainSubstrByWidth(text, Math.max(0, room)) : text;
    }

    /** {@code text} cut to {@code room} pixels with an ellipsis where it was cut. */
    static String ellipsis(Font font, String text, int room) {
        if (font.width(text) <= room) {
            return text;
        }
        String dots = "…";
        return font.plainSubstrByWidth(text, Math.max(0, room - font.width(dots))).stripTrailing() + dots;
    }

    static List<FormattedCharSequence> wrap(Font font, Component text, int width) {
        return font.split(text, Math.max(24, width));
    }

    /**
     * A glint running through a line of text every few seconds. Only the letters light up: the
     * text is drawn again, brighter, inside a moving clip.
     */
    static void glint(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, double seconds) {
        double period = 4.5D;
        double sweep = 0.85D;
        double phase = seconds % period;
        int width = font.width(text);
        if (phase > sweep || width <= 0) {
            return;
        }
        int band = 9;
        int center = x - band + (int) Math.round((width + band * 2) * (phase / sweep));
        int left = Math.max(x, center - band);
        int right = Math.min(x + width, center + band);
        if (left >= right) {
            return;
        }
        graphics.enableScissor(left, y - 1, right, y + 9);
        label(graphics, font, text, x, y, 0xC8FFFFFF, false);
        graphics.disableScissor();
    }

    /** Two points of light running around a rectangle, each trailing a fading tail. */
    static void comets(GuiGraphicsExtractor graphics, int x, int y, int width, int height, double seconds, int color) {
        int perimeter = 2 * (width + height);
        if (width < 16 || height < 16) {
            return;
        }
        double speed = perimeter / 8.0D;
        for (int comet = 0; comet < 2; comet++) {
            double head = (seconds * speed + comet * perimeter / 2.0D) % perimeter;
            for (int step = 0; step < 24; step++) {
                double position = head - step * 1.7D;
                if (position < 0) {
                    position += perimeter;
                }
                int p = (int) position;
                int px;
                int py;
                if (p < width) {
                    px = x + p;
                    py = y;
                } else if ((p -= width) < height) {
                    px = x + width - 1;
                    py = y + p;
                } else if ((p -= height) < width) {
                    px = x + width - 1 - p;
                    py = y + height - 1;
                } else {
                    p -= width;
                    px = x;
                    py = y + height - 1 - Math.min(p, height - 1);
                }
                int alpha = Math.round(215.0F * (1.0F - step / 24.0F));
                int size = step < 3 ? 2 : 1;
                graphics.fill(px, py, px + size, py + size, AnchorsTheme.withAlpha(color, alpha));
            }
        }
    }

    /**
     * Motes of violet light drifting up the screen, like the particles over a charged anchor.
     * Their number follows the screen's area; each mote's path comes from its index, so nothing is
     * allocated or remembered between frames.
     */
    static void motes(GuiGraphicsExtractor graphics, int width, int height, double seconds, float strength) {
        if (strength <= 0.02F || width <= 0 || height <= 0) {
            return;
        }
        int span = height + 24;
        int count = Math.max(36, Math.min(96, width * height / 4_500));
        for (int index = 0; index < count; index++) {
            double phase = index * 0.61803398875D;
            double speed = 5.0D + index % 7 * 1.3D;
            int baseX = Math.floorMod(index * 97 + 31, width);
            int x = baseX + (int) Math.round(Math.sin(seconds * 0.7D + phase * 6.0D) * (3 + index % 5));
            int y = height + 10 - (int) ((seconds * speed + phase * span) % span);
            int size = index % 7 == 0 ? 3 : index % 3 == 0 ? 2 : 1;
            float twinkle = 0.65F + 0.35F * (float) Math.sin(seconds * 2.3D + index);
            int alpha = Math.round((70 + index % 5 * 16) * twinkle * strength);
            int core = index % 4 == 0 ? 0xF0DCFF : index % 4 == 1 ? 0xC38BFF : 0xA55CFF;
            graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, AnchorsTheme.withAlpha(0x8A3FE0, alpha / 4));
            graphics.fill(x, y, x + size, y + size, AnchorsTheme.withAlpha(core, alpha));
        }
    }

    /** The small anchor mark in the header: a block with its four charge lights. */
    static void miniAnchor(GuiGraphicsExtractor graphics, int x, int y, float charge, float alpha) {
        graphics.fill(x, y, x + 12, y + 12, AnchorsTheme.fade(AnchorsTheme.OBSIDIAN, alpha));
        graphics.fill(x, y, x + 12, y + 2, AnchorsTheme.fade(AnchorsTheme.OBSIDIAN_LIGHT, alpha));
        outline(graphics, x, y, 12, 12, AnchorsTheme.fade(AnchorsTheme.OBSIDIAN_EDGE, alpha));
        float shine = AnchorsTheme.clamp01(charge / 4.0F);
        graphics.fill(x + 4, y + 3, x + 8, y + 7,
                AnchorsTheme.fade(AnchorsTheme.lerp(AnchorsTheme.OBSIDIAN_DARK, AnchorsTheme.PORTAL, shine), alpha));
        for (int pip = 0; pip < 4; pip++) {
            float lit = AnchorsTheme.clamp01(charge - pip);
            graphics.fill(x + 2 + pip * 2, y + 9, x + 3 + pip * 2, y + 10,
                    AnchorsTheme.fade(AnchorsTheme.lerp(AnchorsTheme.CHARGE_OFF, AnchorsTheme.CHARGE_ON, lit), alpha));
        }
    }

    /** A filled ellipse from horizontal spans. */
    static void ellipse(GuiGraphicsExtractor graphics, int centerX, int centerY, int radiusX, int radiusY, int color) {
        if (radiusX <= 0 || radiusY <= 0 || ((color >>> 24) & 255) < 2) {
            return;
        }
        boolean many = radiusY > 6;
        for (int row = -radiusY; row <= radiusY; row++) {
            if (many && (row + radiusY) % ROWS_PER_STRATUM == 0) {
                isolate(graphics);
            }
            double t = row / (double) radiusY;
            int span = (int) Math.round(radiusX * Math.sqrt(Math.max(0.0D, 1.0D - t * t)));
            graphics.fill(centerX - span, centerY + row, centerX + span + 1, centerY + row + 1, color);
        }
        if (many) {
            isolate(graphics);
        }
    }

    /**
     * A soft round glow: nested ellipses whose light adds up towards the middle, so the edge fades
     * out instead of ending in a line.
     */
    static void glowEllipse(GuiGraphicsExtractor graphics, int centerX, int centerY, int radiusX, int radiusY,
            int rgb, float strength) {
        if (strength <= 0.01F || radiusX <= 0 || radiusY <= 0) {
            return;
        }
        int layers = 7;
        for (int layer = 0; layer < layers; layer++) {
            float t = (layer + 1) / (float) layers;
            int alpha = Math.round(38.0F * strength);
            if (alpha < 1) {
                return;
            }
            ellipse(graphics, centerX, centerY, Math.max(1, Math.round(radiusX * (1.0F - t * 0.85F))),
                    Math.max(1, Math.round(radiusY * (1.0F - t * 0.85F))), AnchorsTheme.withAlpha(rgb, alpha));
        }
    }

    /**
     * Angular marks on the four corners, like the clasps of a blade: a short stroke along each
     * edge and a pixel stepping diagonally inwards.
     */
    static void bladeCorners(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int size, int color) {
        if (width < size * 2 + 4 || height < size * 2 + 4 || ((color >>> 24) & 255) < 4) {
            return;
        }
        int right = x + width;
        int bottom = y + height;
        graphics.fill(x - 1, y - 1, x + size, y, color);
        graphics.fill(x - 1, y, x, y + size, color);
        graphics.fill(x + 1, y + 1, x + 2, y + 2, color);
        graphics.fill(right - size, y - 1, right + 1, y, color);
        graphics.fill(right, y, right + 1, y + size, color);
        graphics.fill(right - 2, y + 1, right - 1, y + 2, color);
        graphics.fill(x - 1, bottom, x + size, bottom + 1, color);
        graphics.fill(x - 1, bottom - size, x, bottom, color);
        graphics.fill(x + 1, bottom - 2, x + 2, bottom - 1, color);
        graphics.fill(right - size, bottom, right + 1, bottom + 1, color);
        graphics.fill(right, bottom - size, right + 1, bottom, color);
        graphics.fill(right - 2, bottom - 2, right - 1, bottom - 1, color);
    }

    /** A thin separator with a bright segment travelling along it. */
    static void energyLine(GuiGraphicsExtractor graphics, int left, int right, int y, int color, double seconds,
            float strength) {
        if (right - left < 8 || strength <= 0.02F) {
            return;
        }
        graphics.fill(left, y, right, y + 1, fade(color, 0.28F * strength));
        int span = right - left;
        int head = left + (int) ((seconds * 90.0D) % (span + 60)) - 30;
        for (int step = 0; step < 30; step++) {
            int px = head - step;
            if (px < left || px >= right) {
                continue;
            }
            float t = 1.0F - step / 30.0F;
            graphics.fill(px, y, px + 1, y + 1, fade(color, t * strength));
        }
    }

    /**
     * A blade slash across {@code rect}: a slanted band of light moving from left to right as
     * {@code progress} goes from 0 to 1. Drawn inside the rect only.
     */
    static void slash(GuiGraphicsExtractor graphics, AnchorsLayout.Rect rect, float progress, int color) {
        if (progress <= 0.0F || progress >= 1.0F || rect.width() <= 0 || rect.height() <= 0) {
            return;
        }
        float eased = AnchorsTheme.easeOutCubic(progress);
        int travel = rect.width() + rect.height();
        int center = rect.x() - rect.height() + Math.round(travel * eased);
        float fadeOut = 1.0F - progress;
        graphics.enableScissor(rect.x(), rect.y(), rect.right(), rect.bottom());
        for (int row = 0; row < rect.height(); row += 2) {
            int offset = center + (rect.height() - row) / 2;
            graphics.fill(offset - 10, rect.y() + row, offset + 10, rect.y() + row + 2, fade(color, 0.16F * fadeOut));
            graphics.fill(offset - 3, rect.y() + row, offset + 3, rect.y() + row + 2, fade(0xFFFFF7FF, 0.55F * fadeOut));
        }
        graphics.disableScissor();
    }

    /**
     * The anchor sigil: an outer ring of rune ticks turning one way, an inner ring turning the
     * other, and four charge nodes that light up with {@code charge}. Slow, and behind everything.
     */
    static void sigil(GuiGraphicsExtractor graphics, int centerX, int centerY, int radius, double seconds,
            int color, float alpha, float charge) {
        if (radius < 10 || alpha <= 0.02F) {
            return;
        }
        ring(graphics, centerX, centerY, radius, 1, fade(color, 0.35F * alpha));
        ring(graphics, centerX, centerY, Math.round(radius * 0.72F), 1, fade(color, 0.22F * alpha));
        int ticks = 36;
        double outer = seconds * 0.12D;
        for (int tick = 0; tick < ticks; tick++) {
            double angle = outer + tick * Math.PI * 2.0D / ticks;
            int length = tick % 3 == 0 ? 4 : 2;
            int px = centerX + (int) Math.round(Math.cos(angle) * (radius - 3));
            int py = centerY + (int) Math.round(Math.sin(angle) * (radius - 3));
            int size = tick % 9 == 0 ? 2 : 1;
            graphics.fill(px, py, px + size, py + length / 2 + size - 1, fade(color, (tick % 3 == 0 ? 0.6F : 0.3F) * alpha));
        }
        double inner = -seconds * 0.2D;
        int innerRadius = Math.round(radius * 0.72F);
        for (int node = 0; node < 4; node++) {
            double angle = inner + node * Math.PI / 2.0D;
            int px = centerX + (int) Math.round(Math.cos(angle) * innerRadius);
            int py = centerY + (int) Math.round(Math.sin(angle) * innerRadius);
            float lit = AnchorsTheme.clamp01(charge - node);
            diamond(graphics, px, py, 3, fade(AnchorsTheme.lerp(0xFF3A1F5C, AnchorsTheme.ACCENT_BRIGHT, lit), alpha));
            if (lit > 0.05F) {
                glowEllipse(graphics, px, py, 7, 7, 0xC084FC, lit * alpha * 0.6F);
            }
        }
    }

    /** A filled diamond, the shape of a charge node or a rune. */
    static void diamond(GuiGraphicsExtractor graphics, int centerX, int centerY, int radius, int color) {
        for (int row = -radius; row <= radius; row++) {
            int span = radius - Math.abs(row);
            graphics.fill(centerX - span, centerY + row, centerX + span + 1, centerY + row + 1, color);
        }
    }

    /** A warning triangle with an exclamation mark, drawn from horizontal spans. */
    static void warningGlyph(GuiGraphicsExtractor graphics, int centerX, int top, int size, int color, int markColor) {
        for (int row = 0; row < size; row++) {
            int span = Math.round(row * 0.58F);
            graphics.fill(centerX - span, top + row, centerX + span + 1, top + row + 1, color);
        }
        int markTop = top + Math.round(size * 0.34F);
        int markBottom = top + Math.round(size * 0.72F);
        int half = Math.max(1, size / 14);
        graphics.fill(centerX - half, markTop, centerX + half + 1, markBottom, markColor);
        graphics.fill(centerX - half, markBottom + half + 1, centerX + half + 1, markBottom + half * 3 + 2, markColor);
    }

    /** {@code text} drawn {@code scale} times larger, centred on {@code centerX}. */
    static void bigText(GuiGraphicsExtractor graphics, Font font, String text, int centerX, int y, float scale,
            int color, boolean shadow) {
        if (((color >>> 24) & 255) < 8 || text.isEmpty()) {
            return;
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(centerX - font.width(text) * scale / 2.0F, y);
        graphics.pose().scale(scale, scale);
        graphics.text(font, text, 0, 0, color, shadow);
        graphics.pose().popMatrix();
    }

    /** A thin progress bar. */
    static void bar(GuiGraphicsExtractor graphics, int x, int y, int width, int height, float progress, int track,
            int fill) {
        if (width <= 0 || height <= 0) {
            return;
        }
        graphics.fill(x, y, x + width, y + height, track);
        int filled = Math.round(width * AnchorsTheme.clamp01(progress));
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + height, fill);
        }
    }

    /**
     * A colour swatch: a checker behind it for translucent colours, the colour, and a frame that
     * lights up with {@code highlight} (0 to 1).
     */
    static void swatch(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color, float alpha,
            float highlight) {
        if (width <= 0 || height <= 0 || alpha <= 0.01F) {
            return;
        }
        int colorAlpha = Math.round(((color >>> 24) & 255) * alpha);
        if (colorAlpha < 250) {
            // The checkerboard only shows through a colour that is not opaque.
            int cell = 3;
            for (int cy = 0; cy < height; cy += cell) {
                for (int cx = 0; cx < width; cx += cell) {
                    boolean light = ((cx / cell) + (cy / cell)) % 2 == 0;
                    graphics.fill(x + cx, y + cy, x + Math.min(width, cx + cell), y + Math.min(height, cy + cell),
                            fade(light ? 0xFF3A3346 : 0xFF221C2C, alpha));
                }
            }
        }
        graphics.fill(x, y, x + width, y + height, withAlphaFast(color, colorAlpha));
        outline(graphics, x - 1, y - 1, width + 2, height + 2, fade(AnchorsTheme.lerp(0xFF4C2380, 0xFFFFF7FF, highlight), alpha));
    }

    private static int withAlphaFast(int color, int alpha) {
        return Math.max(0, Math.min(255, alpha)) << 24 | (color & 0xFFFFFF);
    }

    /** A thin line between two points, drawn as a run of small squares. */
    static void segment(GuiGraphicsExtractor graphics, float x0, float y0, float x1, float y1, int thickness, int color) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        int steps = Math.max(1, Math.round(Math.max(Math.abs(dx), Math.abs(dy))));
        for (int step = 0; step <= steps; step++) {
            int x = Math.round(x0 + dx * step / steps);
            int y = Math.round(y0 + dy * step / steps);
            graphics.fill(x, y, x + thickness, y + thickness, color);
        }
    }

    private static int fade(int color, float factor) {
        return AnchorsTheme.fade(color, factor);
    }

    /** A pixel ring from horizontal spans. */
    static void ring(GuiGraphicsExtractor graphics, int centerX, int centerY, int radius, int thickness, int color) {
        if (radius <= 0 || ((color >>> 24) & 255) < 4) {
            return;
        }
        int inner = Math.max(0, radius - thickness);
        boolean many = radius > 6;
        for (int dy = -radius; dy <= radius; dy++) {
            if (many && (dy + radius) % ROWS_PER_STRATUM == 0) {
                isolate(graphics);
            }
            int outerSpan = (int) Math.round(Math.sqrt((double) radius * radius - (double) dy * dy));
            int innerSpan = Math.abs(dy) >= inner ? -1
                    : (int) Math.round(Math.sqrt((double) inner * inner - (double) dy * dy));
            if (innerSpan < 0) {
                graphics.fill(centerX - outerSpan, centerY + dy, centerX + outerSpan + 1, centerY + dy + 1, color);
            } else {
                graphics.fill(centerX - outerSpan, centerY + dy, centerX - innerSpan, centerY + dy + 1, color);
                graphics.fill(centerX + innerSpan + 1, centerY + dy, centerX + outerSpan + 1, centerY + dy + 1, color);
            }
        }
        if (many) {
            isolate(graphics);
        }
    }
}
