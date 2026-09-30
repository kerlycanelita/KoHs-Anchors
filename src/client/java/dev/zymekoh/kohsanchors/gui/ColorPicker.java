package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.skin.ColorMath;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A colour picker drawn from gradients: a saturation and brightness square, a hue bar, the hex
 * code (click to type one), the Zymekoh presets and the last colours used. Every change applies at
 * once, so the anchor on screen and in the world follows the cursor.
 */
final class ColorPicker {
    static final int[] PRESETS = {
            0xFF7C3AED, 0xFFA855F7, 0xFFC084FC, 0xFFE83EAF, 0xFFFF4FC8, 0xFFD11F4A,
            0xFFFF315C, 0xFFFF8A3D, 0xFFFFD54F, 0xFF52F2FF, 0xFF35D8FF, 0xFF4ADE80,
            0xFFF4EEFA, 0xFFBDB8CF, 0xFF3A1A5E, 0xFF12091F};
    private static final List<Integer> RECENT = new ArrayList<>();
    /** The hex column's least width: room for "#RRGGBB" and its padding. */
    private static final int HEX_MIN = 50;

    private final IntSupplier color;
    private final IntConsumer set;
    private final float[] hsv = new float[3];
    private int lastColor;
    private AnchorsLayout.Rect area = AnchorsLayout.Rect.EMPTY;
    private int squareX;
    private int squareY;
    private int squareSize;
    private int hueX;
    private int swatchesY;
    private int swatchColumns;
    private int swatch = 10;
    private int hexX;
    private int hexY;
    private int hexWidth;
    private boolean draggingSquare;
    private boolean draggingHue;
    private boolean editingHex;
    private String hexText = "";

    ColorPicker(IntSupplier color, IntConsumer set) {
        this.color = color;
        this.set = set;
        this.lastColor = color.getAsInt();
        ColorMath.toHsv(this.lastColor, this.hsv);
    }

    void setArea(AnchorsLayout.Rect area) {
        this.area = area;
        this.squareSize = Math.max(24, Math.min(area.height() - 30, area.width() - 21 - HEX_MIN));
        this.squareX = area.x();
        this.squareY = area.y();
        this.hueX = this.squareX + this.squareSize + 5;
        this.hexX = this.hueX + 16;
        this.hexY = this.squareY;
        this.hexWidth = Math.max(40, Math.min(84, area.right() - this.hexX));
        this.swatchesY = this.squareY + this.squareSize + 5;
        // The presets in one full row when they fit at a readable size, else in two even rows: a
        // lone swatch wrapped onto a row of its own reads as a mistake.
        int oneRow = (area.width() + 2) / PRESETS.length - 2;
        if (oneRow >= 7) {
            this.swatchColumns = PRESETS.length;
            this.swatch = Math.min(12, oneRow);
        } else {
            this.swatchColumns = (PRESETS.length + 1) / 2;
            this.swatch = Math.max(5, Math.min(12, (area.width() + 2) / this.swatchColumns - 2));
        }
    }

    AnchorsLayout.Rect area() {
        return this.area;
    }

    /** Minimum room the picker needs. */
    static int minWidth() {
        return 120;
    }

    static int minHeight() {
        return 74;
    }

    private void apply(int argb) {
        this.lastColor = argb | 0xFF000000;
        this.set.accept(this.lastColor);
    }

    static void remember(int argb) {
        Integer value = argb | 0xFF000000;
        RECENT.remove(value);
        RECENT.add(0, value);
        while (RECENT.size() > 8) {
            RECENT.remove(RECENT.size() - 1);
        }
    }

    void render(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, float fade) {
        if (this.area.width() <= 0 || fade <= 0.01F) {
            return;
        }
        int current = this.color.getAsInt();
        if (current != this.lastColor) {
            // Changed from outside (a preset, an undo, the Crystal colours).
            this.lastColor = current;
            ColorMath.toHsv(current, this.hsv);
        }
        int size = this.squareSize;
        // Saturation across, brightness down: one column per pixel, in a stratum of its own.
        AnchorsUi.isolate(graphics);
        for (int column = 0; column < size; column++) {
            float saturation = column / (float) Math.max(1, size - 1);
            int top = ColorMath.fromHsv(this.hsv[0], saturation, 1.0F);
            graphics.fillGradient(this.squareX + column, this.squareY, this.squareX + column + 1, this.squareY + size,
                    AnchorsTheme.fade(top, fade), AnchorsTheme.fade(0xFF000000, fade));
        }
        AnchorsUi.isolate(graphics);
        AnchorsUi.outline(graphics, this.squareX - 1, this.squareY - 1, size + 2, size + 2,
                AnchorsTheme.fade(AnchorsTheme.CARD_BORDER_HOVER, fade));
        int markerX = this.squareX + Math.round(this.hsv[1] * (size - 1));
        int markerY = this.squareY + Math.round((1.0F - this.hsv[2]) * (size - 1));
        AnchorsUi.ring(graphics, markerX, markerY, 4, 1, AnchorsTheme.fade(0xFF000000, fade));
        AnchorsUi.ring(graphics, markerX, markerY, 3, 1, AnchorsTheme.fade(0xFFFFFFFF, fade));

        // The hue bar, six gradients.
        for (int segment = 0; segment < 6; segment++) {
            int y0 = this.squareY + segment * size / 6;
            int y1 = this.squareY + (segment + 1) * size / 6;
            graphics.fillGradient(this.hueX, y0, this.hueX + 10, y1,
                    AnchorsTheme.fade(ColorMath.fromHsv(segment / 6.0F, 1.0F, 1.0F), fade),
                    AnchorsTheme.fade(ColorMath.fromHsv((segment + 1) / 6.0F, 1.0F, 1.0F), fade));
        }
        AnchorsUi.outline(graphics, this.hueX - 1, this.squareY - 1, 12, size + 2,
                AnchorsTheme.fade(AnchorsTheme.CARD_BORDER_HOVER, fade));
        int hueY = this.squareY + Math.round(this.hsv[0] * (size - 1));
        graphics.fill(this.hueX - 2, hueY - 1, this.hueX + 12, hueY + 1, AnchorsTheme.fade(0xFFFFFFFF, fade));
        graphics.fill(this.hueX - 3, hueY, this.hueX - 1, hueY + 1, AnchorsTheme.fade(0xFF000000, fade));

        // The colour and its code.
        if (this.hexWidth >= 40) {
            AnchorsUi.swatch(graphics, this.hexX, this.hexY, Math.min(this.hexWidth, 40), 16, current, fade, 0.0F);
            int fieldY = this.hexY + 20;
            boolean over = mouseX >= this.hexX && mouseX < this.hexX + this.hexWidth && mouseY >= fieldY && mouseY < fieldY + 13;
            AnchorsUi.panel(graphics, this.hexX, fieldY, this.hexWidth, 13, AnchorsTheme.fade(0xC0100818, fade),
                    AnchorsTheme.fade(0xC0080410, fade));
            AnchorsUi.roundedOutline(graphics, this.hexX, fieldY, this.hexWidth, 13, AnchorsTheme.fade(this.editingHex
                    ? AnchorsTheme.ACCENT_BRIGHT : over ? AnchorsTheme.CARD_BORDER_HOVER : AnchorsTheme.CARD_BORDER, fade));
            String text = this.editingHex ? this.hexText + ((System.nanoTime() / 400_000_000L) % 2 == 0 ? "_" : "")
                    : ColorMath.hex(current);
            AnchorsUi.label(graphics, font, AnchorsUi.fit(font, text, this.hexWidth - 6), this.hexX + 4, fieldY + 3,
                    AnchorsTheme.fade(this.editingHex ? AnchorsTheme.TITLE : AnchorsTheme.TEXT, fade), false);
        }

        // Presets, then the recent colours.
        int x = this.area.x();
        int y = this.swatchesY;
        int column = 0;
        for (int preset : PRESETS) {
            if (y + this.swatch > this.area.bottom()) {
                break;
            }
            drawSwatch(graphics, x + column * (this.swatch + 2), y, preset, current, mouseX, mouseY, fade);
            if (++column >= this.swatchColumns) {
                column = 0;
                y += this.swatch + 2;
            }
        }
        if (column != 0) {
            column = 0;
            y += this.swatch + 2;
        }
        for (int recent : RECENT) {
            if (y + this.swatch > this.area.bottom() || column >= this.swatchColumns) {
                break;
            }
            drawSwatch(graphics, x + column * (this.swatch + 2), y, recent, current, mouseX, mouseY, fade);
            column++;
        }
    }

    private void drawSwatch(GuiGraphicsExtractor graphics, int x, int y, int color, int current, int mouseX, int mouseY,
            float fade) {
        boolean over = mouseX >= x && mouseX < x + this.swatch && mouseY >= y && mouseY < y + this.swatch;
        AnchorsUi.swatch(graphics, x, y, this.swatch, this.swatch, color, fade, over ? 1.0F : (color | 0xFF000000) == current ? 0.6F : 0.0F);
    }

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != Keys.LEFT_BUTTON || !this.area.contains(mouseX, mouseY) && !inHue(mouseX, mouseY)) {
            if (this.editingHex) {
                commitHex();
            }
            return false;
        }
        if (inSquare(mouseX, mouseY)) {
            this.draggingSquare = true;
            dragSquare(mouseX, mouseY);
            return true;
        }
        if (inHue(mouseX, mouseY)) {
            this.draggingHue = true;
            dragHue(mouseY);
            return true;
        }
        int fieldY = this.hexY + 20;
        if (mouseX >= this.hexX && mouseX < this.hexX + this.hexWidth && mouseY >= fieldY && mouseY < fieldY + 13) {
            this.editingHex = true;
            this.hexText = ColorMath.hex(this.color.getAsInt());
            return true;
        }
        int swatch = swatchAt(mouseX, mouseY);
        if (swatch != 0) {
            ColorMath.toHsv(swatch, this.hsv);
            apply(swatch);
            remember(swatch);
            return true;
        }
        return this.area.contains(mouseX, mouseY);
    }

    boolean mouseDragged(double mouseX, double mouseY) {
        if (this.draggingSquare) {
            dragSquare(mouseX, mouseY);
            return true;
        }
        if (this.draggingHue) {
            dragHue(mouseY);
            return true;
        }
        return false;
    }

    boolean mouseReleased() {
        if (this.draggingSquare || this.draggingHue) {
            this.draggingSquare = false;
            this.draggingHue = false;
            remember(this.color.getAsInt());
            return true;
        }
        return false;
    }

    boolean editing() {
        return this.editingHex;
    }

    /** Keys while the hex code is being typed. */
    boolean keyPressed(int key) {
        if (!this.editingHex) {
            return false;
        }
        if (Keys.confirms(key)) {
            commitHex();
        } else if (key == Keys.ESCAPE) {
            this.editingHex = false;
        } else if (key == Keys.BACKSPACE && !this.hexText.isEmpty()) {
            this.hexText = this.hexText.substring(0, this.hexText.length() - 1);
        }
        return true;
    }

    boolean charTyped(char character) {
        if (!this.editingHex) {
            return false;
        }
        if ((Character.digit(character, 16) >= 0 || character == '#') && this.hexText.length() < 7) {
            this.hexText += Character.toUpperCase(character);
        }
        return true;
    }

    private void commitHex() {
        this.editingHex = false;
        int parsed = ColorMath.parseHex(this.hexText, -1);
        if (parsed != -1) {
            ColorMath.toHsv(parsed, this.hsv);
            apply(parsed);
            remember(parsed);
        }
    }

    private boolean inSquare(double mouseX, double mouseY) {
        return mouseX >= this.squareX - 2 && mouseX <= this.squareX + this.squareSize + 1 && mouseY >= this.squareY - 2
                && mouseY <= this.squareY + this.squareSize + 1;
    }

    private boolean inHue(double mouseX, double mouseY) {
        return mouseX >= this.hueX - 3 && mouseX <= this.hueX + 13 && mouseY >= this.squareY - 2
                && mouseY <= this.squareY + this.squareSize + 1;
    }

    private void dragSquare(double mouseX, double mouseY) {
        this.hsv[1] = (float) Math.max(0.0D, Math.min(1.0D, (mouseX - this.squareX) / Math.max(1, this.squareSize - 1)));
        this.hsv[2] = (float) Math.max(0.0D, Math.min(1.0D, 1.0D - (mouseY - this.squareY) / Math.max(1, this.squareSize - 1)));
        apply(ColorMath.fromHsv(this.hsv[0], this.hsv[1], this.hsv[2]));
    }

    private void dragHue(double mouseY) {
        this.hsv[0] = (float) Math.max(0.0D, Math.min(0.999D, (mouseY - this.squareY) / Math.max(1, this.squareSize - 1)));
        apply(ColorMath.fromHsv(this.hsv[0], this.hsv[1], this.hsv[2]));
    }

    private int swatchAt(double mouseX, double mouseY) {
        int x = this.area.x();
        int y = this.swatchesY;
        int column = 0;
        for (int preset : PRESETS) {
            int sx = x + column * (this.swatch + 2);
            if (mouseX >= sx && mouseX < sx + this.swatch && mouseY >= y && mouseY < y + this.swatch) {
                return preset;
            }
            if (++column >= this.swatchColumns) {
                column = 0;
                y += this.swatch + 2;
            }
        }
        if (column != 0) {
            column = 0;
            y += this.swatch + 2;
        }
        for (int recent : RECENT) {
            int sx = x + column * (this.swatch + 2);
            if (mouseX >= sx && mouseX < sx + this.swatch && mouseY >= y && mouseY < y + this.swatch) {
                return recent;
            }
            column++;
        }
        return 0;
    }
}
