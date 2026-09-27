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
    /** Crying-obsidian flecks on the anchor's face, as (column, row, bright) triples. */
    private static final int[][] FLECKS = {
            {2, 4, 0}, {3, 4, 1}, {12, 5, 0}, {13, 5, 0}, {4, 11, 1}, {11, 12, 0},
            {13, 9, 1}, {6, 14, 0}, {2, 9, 0}, {9, 3, 0}, {10, 14, 1}
    };
    private static final int[] PIP_COLUMNS = {2, 5, 9, 12};

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

    /**
     * The respawn anchor, drawn on a 16 x 16 grid of {@code unit}-pixel cells centred on
     * ({@code centerX}, {@code centerY}).
     *
     * @param charge 0 to 4; a fraction lights the next charge light partly
     * @param glow   how strongly the charged anchor shines, 0 to 1
     * @param blast  -1 for no detonation, otherwise its progress from 0 to 1
     * @param alpha  overall opacity
     */
    static void anchor(GuiGraphicsExtractor graphics, int centerX, int centerY, int unit, float charge, float glow,
            float blast, float alpha, double seconds) {
        int size = unit * 16;
        int originX = centerX - size / 2;
        int originY = centerY - size / 2;
        float body = alpha;
        if (blast >= 0.0F) {
            body *= AnchorsTheme.clamp01(1.0F - blast * 2.4F);
            if (blast < 0.3F) {
                // The block jolts before it goes.
                originX += (int) Math.round(Math.sin(seconds * 90.0D) * unit * 0.5D * (1.0F - blast / 0.3F));
            }
        }

        float shine = AnchorsTheme.clamp01(charge / 4.0F) * (0.55F + 0.45F * glow);
        if (shine > 0.01F && body > 0.01F) {
            for (int layer = 1; layer <= 4; layer++) {
                int grow = layer * unit;
                int haloAlpha = Math.round(34.0F * shine * body / layer);
                graphics.fill(originX - grow, originY - grow, originX + size + grow, originY + size + grow,
                        AnchorsTheme.withAlpha(AnchorsTheme.PORTAL, haloAlpha));
            }
        }

        if (body > 0.01F) {
            // Body with a lighter top lip, the way the block reads in the inventory.
            graphics.fillGradient(originX, originY + unit * 2, originX + size, originY + size,
                    AnchorsTheme.fade(AnchorsTheme.OBSIDIAN, body), AnchorsTheme.fade(AnchorsTheme.OBSIDIAN_DARK, body));
            graphics.fill(originX, originY, originX + size, originY + unit * 2,
                    AnchorsTheme.fade(AnchorsTheme.OBSIDIAN_LIGHT, body));
            graphics.fill(originX, originY, originX + size, originY + Math.max(1, unit / 2),
                    AnchorsTheme.fade(AnchorsTheme.OBSIDIAN_EDGE, body));
            outline(graphics, originX, originY, size, size, AnchorsTheme.fade(AnchorsTheme.OBSIDIAN_EDGE, body));

            for (int[] fleck : FLECKS) {
                int color = fleck[2] == 1 ? AnchorsTheme.CRYING_BRIGHT : AnchorsTheme.CRYING;
                float flicker = 0.7F + 0.3F * (float) Math.sin(seconds * 1.7D + fleck[0] * 1.3D + fleck[1]);
                cell(graphics, originX, originY, unit, fleck[0], fleck[1], AnchorsTheme.fade(color, body * flicker));
            }

            // The portal window swirls brighter with every charge.
            for (int row = 4; row < 10; row++) {
                for (int column = 5; column < 11; column++) {
                    float wave = 0.55F + 0.45F * (float) Math.sin(seconds * 3.1D + (column + row) * 0.9D
                            + Math.cos(seconds * 1.3D + column * 0.7D));
                    int color = AnchorsTheme.lerp(AnchorsTheme.OBSIDIAN_DARK, AnchorsTheme.PORTAL, shine * wave);
                    cell(graphics, originX, originY, unit, column, row, AnchorsTheme.fade(color, body));
                }
            }
            outline(graphics, originX + unit * 5 - 1, originY + unit * 4 - 1, unit * 6 + 2, unit * 6 + 2,
                    AnchorsTheme.fade(AnchorsTheme.OBSIDIAN_EDGE, body));

            // The four charge lights.
            for (int pip = 0; pip < 4; pip++) {
                float lit = AnchorsTheme.clamp01(charge - pip);
                int color = AnchorsTheme.lerp(AnchorsTheme.CHARGE_OFF, AnchorsTheme.CHARGE_ON, lit);
                int column = PIP_COLUMNS[pip];
                int left = originX + column * unit;
                int top = originY + 12 * unit;
                if (lit > 0.5F) {
                    graphics.fill(left - 1, top - 1, left + unit * 2 + 1, top + unit * 2 + 1,
                            AnchorsTheme.withAlpha(AnchorsTheme.PORTAL, Math.round(120 * lit * body)));
                }
                graphics.fill(left, top, left + unit * 2, top + unit * 2, AnchorsTheme.fade(color, body));
            }
        }

        if (blast >= 0.0F) {
            float fadeOut = 1.0F - blast;
            if (blast < 0.35F) {
                int flash = Math.round(190.0F * (1.0F - blast / 0.35F) * alpha);
                graphics.fill(originX - unit, originY - unit, originX + size + unit, originY + size + unit,
                        AnchorsTheme.withAlpha(0xFFF4FF, flash));
            }
            int radius = Math.round(size * 0.55F + blast * size * 0.9F);
            ring(graphics, centerX, centerY, radius, Math.max(1, unit / 2 + 1),
                    AnchorsTheme.withAlpha(AnchorsTheme.ACCENT_BRIGHT, Math.round(220 * fadeOut * alpha)));
            sparks(graphics, centerX, centerY, size, unit, blast, alpha);
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

    private static void sparks(GuiGraphicsExtractor graphics, int centerX, int centerY, int size, int unit,
            float blast, float alpha) {
        int count = 16;
        float fadeOut = 1.0F - blast;
        for (int index = 0; index < count; index++) {
            double angle = index * (Math.PI * 2.0D / count) + index * 0.37D;
            double distance = size * 0.35D + AnchorsTheme.easeOutCubic(blast) * size * (0.8D + index % 3 * 0.25D);
            int x = centerX + (int) Math.round(Math.cos(angle) * distance);
            int y = centerY + (int) Math.round(Math.sin(angle) * distance);
            int sparkSize = Math.max(1, index % 4 == 0 ? unit : unit / 2);
            int color = AnchorsTheme.lerp(AnchorsTheme.ACCENT_BRIGHT, AnchorsTheme.PORTAL, blast);
            graphics.fill(x, y, x + sparkSize, y + sparkSize, AnchorsTheme.fade(color, fadeOut * alpha));
        }
    }

    private static void cell(GuiGraphicsExtractor graphics, int originX, int originY, int unit, int column, int row,
            int color) {
        int left = originX + column * unit;
        int top = originY + row * unit;
        graphics.fill(left, top, left + unit, top + unit, color);
    }
}
