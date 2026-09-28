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
        for (int row = -radiusY; row <= radiusY; row++) {
            double t = row / (double) radiusY;
            int span = (int) Math.round(radiusX * Math.sqrt(Math.max(0.0D, 1.0D - t * t)));
            graphics.fill(centerX - span, centerY + row, centerX + span + 1, centerY + row + 1, color);
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

    /** A pixel ring from horizontal spans. */
    static void ring(GuiGraphicsExtractor graphics, int centerX, int centerY, int radius, int thickness, int color) {
        if (radius <= 0 || ((color >>> 24) & 255) < 4) {
            return;
        }
        int inner = Math.max(0, radius - thickness);
        for (int dy = -radius; dy <= radius; dy++) {
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
    }
}
