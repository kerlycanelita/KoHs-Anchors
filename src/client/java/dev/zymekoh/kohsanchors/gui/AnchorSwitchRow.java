package dev.zymekoh.kohsanchors.gui;

import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** A card with a switch whose knob slides to the new state. */
final class AnchorSwitchRow extends AnchorRow {
    static final int SWITCH_WIDTH = 22;
    static final int SWITCH_HEIGHT = 10;
    private static final Component ON = Component.translatable("kohs_anchors.state.on");
    private static final Component OFF = Component.translatable("kohs_anchors.state.off");

    private final BooleanSupplier state;
    private final Runnable toggle;
    private float knob = -1.0F;

    AnchorSwitchRow(int x, int y, int width, Component label, Component description, Font font, BooleanSupplier state,
            Runnable toggle, boolean danger, Component tag, String inspectKey, String... inspectDetails) {
        super(x, y, width, label, description, font, controlWidth(font), 0, danger, tag, inspectKey, inspectDetails);
        this.state = state;
        this.toggle = toggle;
    }

    static int controlWidth(Font font) {
        return Math.max(font.width(ON), font.width(OFF)) + 5 + SWITCH_WIDTH;
    }

    @Override
    protected void activate() {
        this.toggle.run();
    }

    @Override
    protected void animate(float response) {
        boolean on = this.state.getAsBoolean();
        if (this.knob < 0.0F) {
            this.knob = on ? 1.0F : 0.0F;
        }
        this.knob += ((on ? 1.0F : 0.0F) - this.knob) * Math.min(1.0F, response * 1.5F);
    }

    @Override
    protected float lit() {
        return Math.max(0.0F, this.knob);
    }

    @Override
    protected int drawControl(GuiGraphicsExtractor graphics, Font font, int right, int labelY, float fade) {
        boolean on = this.state.getAsBoolean();
        int switchX = right - SWITCH_WIDTH;
        int switchY = labelY - 1;
        String stateText = (on ? ON : OFF).getString();
        int stateX = switchX - 5 - font.width(stateText);
        int onColor = this.danger ? 0xFFFFC2CE : AnchorsTheme.STATE_ON;
        AnchorsUi.label(graphics, font, stateText, stateX, labelY,
                AnchorsTheme.fade(AnchorsTheme.lerp(AnchorsTheme.STATE_OFF, onColor, this.knob), fade), false);
        drawSwitch(graphics, switchX, switchY, fade, this.knob, this.danger);
        return stateX;
    }

    static void drawSwitch(GuiGraphicsExtractor graphics, int x, int y, float fade, float knob, boolean danger) {
        int onTrack = danger ? AnchorsTheme.SWITCH_DANGER_ON : AnchorsTheme.SWITCH_ON;
        int track = AnchorsTheme.lerp(AnchorsTheme.SWITCH_OFF, onTrack, knob);
        if (knob > 0.02F) {
            AnchorsUi.halo(graphics, x, y, SWITCH_WIDTH, SWITCH_HEIGHT, danger ? AnchorsTheme.CRIMSON_BRIGHT
                    : AnchorsTheme.ACCENT, 2, knob * fade);
        }
        graphics.fill(x + 1, y, x + SWITCH_WIDTH - 1, y + SWITCH_HEIGHT, AnchorsTheme.fade(track, fade));
        graphics.fill(x, y + 1, x + SWITCH_WIDTH, y + SWITCH_HEIGHT - 1, AnchorsTheme.fade(track, fade));
        int knobSize = SWITCH_HEIGHT - 2;
        int knobX = x + 1 + Math.round((SWITCH_WIDTH - knobSize - 2) * knob);
        graphics.fill(knobX, y + 1, knobX + knobSize, y + 1 + knobSize,
                AnchorsTheme.fade(AnchorsTheme.lerp(AnchorsTheme.KNOB_OFF, AnchorsTheme.KNOB_ON, knob), fade));
        // A notch in the knob, like the groove of a blade.
        graphics.fill(knobX + knobSize / 2, y + 3, knobX + knobSize / 2 + 1, y + SWITCH_HEIGHT - 3,
                AnchorsTheme.fade(AnchorsTheme.lerp(0xFF6E6982, danger ? AnchorsTheme.CRIMSON : AnchorsTheme.ACCENT_DEEP, knob),
                        fade));
    }

    @Override
    protected Component narrationState() {
        return this.state.getAsBoolean() ? ON : OFF;
    }
}
