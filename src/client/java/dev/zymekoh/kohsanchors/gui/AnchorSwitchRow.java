package dev.zymekoh.kohsanchors.gui;

import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/**
 * One option: its name, what it does, and a switch whose knob slides to the new state. The whole
 * card is the button, and it only answers inside the scrolling area it is drawn in, so the part of
 * a card scrolled out of view cannot be clicked.
 *
 * <p>A danger row (the advanced tab) is drawn in crimson and carries a "not secure" tag, so its
 * risk reads from the shape and the words as well as the colour.</p>
 */
final class AnchorSwitchRow extends AbstractButton {
    static final int PAD_X = 8;
    static final int PAD_Y = 6;
    static final int SWITCH_WIDTH = 22;
    static final int SWITCH_HEIGHT = 10;
    private static final int LINE_HEIGHT = 10;
    private static final long PRESS_FLASH_NANOS = 260_000_000L;
    private static final Component ON = Component.translatable("kohs_anchors.state.on");
    private static final Component OFF = Component.translatable("kohs_anchors.state.off");

    private final BooleanSupplier state;
    private final Runnable toggle;
    private final boolean danger;
    private final String tag;
    private final List<FormattedCharSequence> descriptionLines;
    private AnchorsLayout.Rect clip = AnchorsLayout.Rect.EMPTY;
    private float appear = 1.0F;
    private float hover;
    private float knob = -1.0F;
    private long lastFrame = System.nanoTime();
    private long pressedAt;
    private boolean pressedOnce;

    AnchorSwitchRow(int x, int y, int width, Component label, Component description, Font font,
            BooleanSupplier state, Runnable toggle, boolean danger, Component tag) {
        super(x, y, width, heightFor(font, description, width), label);
        this.state = state;
        this.toggle = toggle;
        this.danger = danger;
        this.tag = tag == null ? "" : tag.getString();
        this.descriptionLines = AnchorsUi.wrap(font, description, textWidth(font, width));
    }

    /** The height a row needs for its description wrapped to {@code width}. */
    static int heightFor(Font font, Component description, int width) {
        int lines = AnchorsUi.wrap(font, description, textWidth(font, width)).size();
        return PAD_Y + 9 + (lines > 0 ? 3 + lines * LINE_HEIGHT - 1 : 0) + PAD_Y;
    }

    private static int textWidth(Font font, int width) {
        int stateWidth = Math.max(font.width(ON), font.width(OFF));
        return width - PAD_X * 2 - SWITCH_WIDTH - stateWidth - 10;
    }

    void setClip(AnchorsLayout.Rect clip) {
        this.clip = clip;
    }

    void setAppear(float appear) {
        this.appear = appear;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return super.isMouseOver(mouseX, mouseY) && this.clip.contains(mouseX, mouseY);
    }

    @Override
    public void onPress(InputWithModifiers input) {
        this.pressedAt = System.nanoTime();
        this.pressedOnce = true;
        this.toggle.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        float elapsed = Mth.clamp((now - this.lastFrame) / 1_000_000.0F, 0.0F, 50.0F);
        this.lastFrame = now;
        float response = 1.0F - (float) Math.exp(-elapsed / 70.0F);
        boolean on = this.state.getAsBoolean();
        if (this.knob < 0.0F) {
            this.knob = on ? 1.0F : 0.0F;
        }
        this.knob += ((on ? 1.0F : 0.0F) - this.knob) * Math.min(1.0F, response * 1.5F);
        this.hover += ((isHoveredOrFocused() ? 1.0F : 0.0F) - this.hover) * response;
        float fade = Mth.clamp(this.alpha, 0.0F, 1.0F) * this.appear;
        if (fade <= 0.01F) {
            return;
        }

        int x = getX();
        int y = getY();
        int width = getWidth();
        int height = getHeight();
        int accent = this.danger ? AnchorsTheme.CRIMSON : AnchorsTheme.ACCENT;
        int top = this.danger ? AnchorsTheme.lerp(0x5A2A0A18, 0x7A3D0E22, this.hover)
                : AnchorsTheme.lerp(AnchorsTheme.CARD, AnchorsTheme.CARD_HOVER, this.hover);
        int bottom = this.danger ? AnchorsTheme.lerp(0x4A140510, 0x6A220816, this.hover)
                : AnchorsTheme.lerp(AnchorsTheme.CARD_BOTTOM, AnchorsTheme.CARD_BOTTOM_HOVER, this.hover);
        AnchorsUi.panel(graphics, x, y, width, height, AnchorsTheme.fade(top, fade), AnchorsTheme.fade(bottom, fade));
        int border = this.danger ? AnchorsTheme.lerp(0x8A6A1030, 0xF0FF315C, this.hover)
                : AnchorsTheme.lerp(AnchorsTheme.CARD_BORDER, AnchorsTheme.CARD_BORDER_HOVER, this.hover);
        AnchorsUi.roundedOutline(graphics, x, y, width, height, AnchorsTheme.fade(border, fade));
        if (this.hover > 0.05F) {
            AnchorsUi.bladeCorners(graphics, x, y, width, height, 5,
                    AnchorsTheme.fade(this.danger ? AnchorsTheme.CRIMSON_BRIGHT : AnchorsTheme.ACCENT_BRIGHT,
                            this.hover * fade));
        }
        // An edge on the left follows the switch, so the state also reads from a distance.
        graphics.fill(x + 2, y + 3, x + 4, y + height - 3,
                AnchorsTheme.withAlpha(accent, Math.round(220.0F * this.knob * fade)));

        long sincePress = now - this.pressedAt;
        if (this.pressedOnce && sincePress >= 0L && sincePress < PRESS_FLASH_NANOS) {
            float t = sincePress / (float) PRESS_FLASH_NANOS;
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, AnchorsTheme.withAlpha(
                    this.danger ? 0xFF9AB0 : 0xE9D5FF, Math.round(90.0F * (1.0F - t) * (1.0F - t) * fade)));
        }

        Font font = Minecraft.getInstance().font;
        int labelY = y + PAD_Y;
        int switchX = x + width - PAD_X - SWITCH_WIDTH;
        int switchY = labelY - 1;
        String stateText = (on ? ON : OFF).getString();
        int stateX = switchX - 5 - font.width(stateText);
        int textX = x + PAD_X + 2;

        int tagWidth = this.tag.isEmpty() ? 0 : font.width(this.tag) + 8;
        String label = AnchorsUi.fit(font, getMessage().getString(), stateX - 6 - textX - (tagWidth > 0 ? tagWidth + 4 : 0));
        AnchorsUi.label(graphics, font, label, textX, labelY,
                AnchorsTheme.fade(AnchorsTheme.lerp(AnchorsTheme.TEXT, 0xFFFFFFFF, this.hover), fade), false);
        if (tagWidth > 0) {
            int tagX = textX + font.width(label) + 5;
            if (tagX + tagWidth < stateX - 4) {
                AnchorsUi.panel(graphics, tagX, labelY - 2, tagWidth, 12, AnchorsTheme.fade(0xC0500A1E, fade),
                        AnchorsTheme.fade(0xC02A0510, fade));
                AnchorsUi.roundedOutline(graphics, tagX, labelY - 2, tagWidth, 12,
                        AnchorsTheme.fade(AnchorsTheme.CRIMSON_BRIGHT, fade));
                AnchorsUi.label(graphics, font, this.tag, tagX + 4, labelY, AnchorsTheme.fade(0xFFFFD6DE, fade), false);
            }
        }
        int lineY = labelY + 13;
        for (FormattedCharSequence line : this.descriptionLines) {
            AnchorsUi.line(graphics, font, line, textX, lineY, AnchorsTheme.fade(AnchorsTheme.TEXT_MUTED, fade));
            lineY += LINE_HEIGHT;
        }

        int onColor = this.danger ? 0xFFFFC2CE : AnchorsTheme.STATE_ON;
        AnchorsUi.label(graphics, font, stateText, stateX, labelY,
                AnchorsTheme.fade(AnchorsTheme.lerp(AnchorsTheme.STATE_OFF, onColor, this.knob), fade), false);
        drawSwitch(graphics, switchX, switchY, fade);
    }

    private void drawSwitch(GuiGraphicsExtractor graphics, int x, int y, float fade) {
        int onTrack = this.danger ? AnchorsTheme.SWITCH_DANGER_ON : AnchorsTheme.SWITCH_ON;
        int track = AnchorsTheme.lerp(AnchorsTheme.SWITCH_OFF, onTrack, this.knob);
        if (this.knob > 0.02F) {
            AnchorsUi.halo(graphics, x, y, SWITCH_WIDTH, SWITCH_HEIGHT, this.danger ? AnchorsTheme.CRIMSON_BRIGHT
                    : AnchorsTheme.ACCENT, 2, this.knob * fade);
        }
        graphics.fill(x + 1, y, x + SWITCH_WIDTH - 1, y + SWITCH_HEIGHT, AnchorsTheme.fade(track, fade));
        graphics.fill(x, y + 1, x + SWITCH_WIDTH, y + SWITCH_HEIGHT - 1, AnchorsTheme.fade(track, fade));
        int knobSize = SWITCH_HEIGHT - 2;
        int knobX = x + 1 + Math.round((SWITCH_WIDTH - knobSize - 2) * this.knob);
        graphics.fill(knobX, y + 1, knobX + knobSize, y + 1 + knobSize,
                AnchorsTheme.fade(AnchorsTheme.lerp(AnchorsTheme.KNOB_OFF, AnchorsTheme.KNOB_ON, this.knob), fade));
        // A notch in the knob, like the groove of a blade.
        graphics.fill(knobX + knobSize / 2, y + 3, knobX + knobSize / 2 + 1, y + SWITCH_HEIGHT - 3,
                AnchorsTheme.fade(AnchorsTheme.lerp(0xFF6E6982, this.danger ? AnchorsTheme.CRIMSON : AnchorsTheme.ACCENT_DEEP,
                        this.knob), fade));
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        MutableComponent message = super.createNarrationMessage();
        if (!this.tag.isEmpty()) {
            message = message.append(CommonComponents.NARRATION_SEPARATOR).append(Component.literal(this.tag));
        }
        return message.append(CommonComponents.NARRATION_SEPARATOR).append(this.state.getAsBoolean() ? ON : OFF);
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
