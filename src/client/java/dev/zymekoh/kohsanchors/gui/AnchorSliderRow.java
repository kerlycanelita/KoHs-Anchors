package dev.zymekoh.kohsanchors.gui;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * A card with a slider under its description. The slider moves between fixed stops, spaced evenly
 * along the track, so a range as wide as one millisecond to ten seconds still has fine control at
 * its short end. Drag it, click anywhere on it, or use the arrow keys when the card is focused.
 */
final class AnchorSliderRow extends AnchorRow {
    private static final int TRACK_HEIGHT = 12;

    private final IntSupplier value;
    private final IntConsumer set;
    private final int[] stops;
    private final IntFunction<String> format;
    private final Runnable onRelease;
    private int trackX;
    private int trackY;
    private int trackWidth;
    private float shown = -1.0F;
    private boolean dragging;

    AnchorSliderRow(int x, int y, int width, Component label, Component description, Font font, IntSupplier value,
            IntConsumer set, int[] stops, IntFunction<String> format, Runnable onRelease, String inspectKey,
            String... inspectDetails) {
        super(x, y, width, label, description, font, 64, TRACK_HEIGHT + 4, false, null, inspectKey, inspectDetails);
        this.value = value;
        this.set = set;
        this.stops = stops;
        this.format = format;
        this.onRelease = onRelease;
    }

    /** Evenly spaced stops from {@code min} to {@code max}. */
    static int[] linear(int min, int max, int step) {
        int count = (max - min) / step + 1;
        int[] stops = new int[count];
        for (int index = 0; index < count; index++) {
            stops[index] = min + index * step;
        }
        return stops;
    }

    int index() {
        int current = this.value.getAsInt();
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 0; index < this.stops.length; index++) {
            int distance = Math.abs(this.stops[index] - current);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }
        return best;
    }

    private void select(int index) {
        int clamped = Math.max(0, Math.min(this.stops.length - 1, index));
        if (this.stops[clamped] != this.value.getAsInt()) {
            this.set.accept(this.stops[clamped]);
        }
    }

    @Override
    protected void activate() {
        // A click on the card outside the track does nothing; the track is handled by the screen.
    }

    /** Whether {@code mouseX, mouseY} is on the track, where a click grabs the knob. */
    boolean trackContains(double mouseX, double mouseY) {
        return this.trackWidth > 0 && mouseX >= this.trackX - 4 && mouseX <= this.trackX + this.trackWidth + 4
                && mouseY >= this.trackY - 3 && mouseY <= this.trackY + TRACK_HEIGHT + 2 && clip().contains(mouseX, mouseY);
    }

    void beginDrag(double mouseX) {
        this.dragging = true;
        dragTo(mouseX);
    }

    void dragTo(double mouseX) {
        if (this.trackWidth <= 0) {
            return;
        }
        float fraction = (float) ((mouseX - this.trackX) / this.trackWidth);
        select(Math.round(Math.max(0.0F, Math.min(1.0F, fraction)) * (this.stops.length - 1)));
    }

    boolean dragging() {
        return this.dragging;
    }

    void endDrag() {
        if (this.dragging) {
            this.dragging = false;
            if (this.onRelease != null) {
                this.onRelease.run();
            }
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == Keys.LEFT || key == Keys.DOWN) {
            select(index() - 1);
            release();
            return true;
        }
        if (key == Keys.RIGHT || key == Keys.UP) {
            select(index() + 1);
            release();
            return true;
        }
        if (key == Keys.HOME) {
            select(0);
            release();
            return true;
        }
        if (key == Keys.END) {
            select(this.stops.length - 1);
            release();
            return true;
        }
        return super.keyPressed(event);
    }

    private void release() {
        if (this.onRelease != null) {
            this.onRelease.run();
        }
    }

    @Override
    protected void animate(float response) {
        float target = this.stops.length <= 1 ? 0.0F : index() / (float) (this.stops.length - 1);
        if (this.shown < 0.0F || this.dragging) {
            this.shown = target;
        }
        this.shown += (target - this.shown) * Math.min(1.0F, response * 1.8F);
    }

    @Override
    protected float lit() {
        return this.value.getAsInt() != this.stops[0] ? 1.0F : 0.0F;
    }

    @Override
    protected int drawControl(GuiGraphicsExtractor graphics, Font font, int right, int labelY, float fade) {
        String text = this.format.apply(this.value.getAsInt());
        int width = font.width(text) + 10;
        int x = right - width;
        boolean off = this.value.getAsInt() == this.stops[0];
        AnchorsUi.panel(graphics, x, labelY - 2, width, 12, AnchorsTheme.fade(off ? 0x80251240 : 0xC04A1C80, fade),
                AnchorsTheme.fade(off ? 0x80120920 : 0xC0251240, fade));
        AnchorsUi.roundedOutline(graphics, x, labelY - 2, width, 12,
                AnchorsTheme.fade(off ? AnchorsTheme.CARD_BORDER : AnchorsTheme.CARD_BORDER_HOVER, fade));
        AnchorsUi.label(graphics, font, text, x + 5, labelY, AnchorsTheme.fade(off ? AnchorsTheme.TEXT_MUTED
                : AnchorsTheme.ACCENT_BRIGHT, fade), false);
        return x;
    }

    @Override
    protected void drawExtra(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, float fade) {
        this.trackX = x + 2;
        this.trackY = y + 2;
        this.trackWidth = Math.max(10, width - 4);
        int middle = this.trackY + TRACK_HEIGHT / 2;
        graphics.fill(this.trackX, middle - 1, this.trackX + this.trackWidth, middle + 1, AnchorsTheme.fade(0xFF2A1845, fade));
        int knobX = this.trackX + Math.round(this.trackWidth * Math.max(0.0F, Math.min(1.0F, this.shown)));
        graphics.fillGradient(this.trackX, middle - 1, knobX, middle + 1, AnchorsTheme.fade(AnchorsTheme.ACCENT_DEEP, fade),
                AnchorsTheme.fade(AnchorsTheme.ACCENT_BRIGHT, fade));
        // Ticks at the stops, sparser when there are many.
        int every = Math.max(1, this.stops.length / 12);
        for (int index = 0; index < this.stops.length; index += every) {
            int tickX = this.trackX + Math.round(this.trackWidth * index / (float) Math.max(1, this.stops.length - 1));
            graphics.fill(tickX, middle + 2, tickX + 1, middle + 4, AnchorsTheme.fade(0x806E4AA0, fade));
        }
        float glow = this.dragging ? 1.0F : this.hover;
        if (glow > 0.05F) {
            AnchorsUi.glowEllipse(graphics, knobX, middle, 7, 7, 0xC084FC, glow * fade);
        }
        AnchorsUi.diamond(graphics, knobX, middle, 4, AnchorsTheme.fade(AnchorsTheme.ACCENT_BRIGHT, fade));
        AnchorsUi.diamond(graphics, knobX, middle, 2, AnchorsTheme.fade(AnchorsTheme.ACCENT_DEEP, fade));
    }

    @Override
    protected Component narrationState() {
        return Component.literal(this.format.apply(this.value.getAsInt()));
    }
}
