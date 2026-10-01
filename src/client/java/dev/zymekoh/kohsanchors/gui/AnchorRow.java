package dev.zymekoh.kohsanchors.gui;

import java.util.List;
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
 * One card in an options list: its name, what it does, and a control on the right. The whole card
 * is the button, and it only answers inside the scrolling area it is drawn in, so the part of a
 * card scrolled out of view cannot be clicked.
 *
 * <p>A danger card (the advanced tab) is crimson and carries a "not secure" tag, so its risk reads
 * from the shape and the words as well as the colour. Subclasses draw the control and say what a
 * press does.</p>
 */
abstract class AnchorRow extends AbstractButton {
    static final int PAD_X = 8;
    static final int PAD_Y = 6;
    static final int LINE_HEIGHT = 10;
    private static final long PRESS_FLASH_NANOS = 260_000_000L;

    protected final boolean danger;
    protected final String tag;
    protected final List<FormattedCharSequence> descriptionLines;
    /** What the inspector names this card after, such as {@code hideDetonating}. */
    final String inspectKey;
    /** Extra lines the inspector lists for this card: classes, mixins, the setting it writes. */
    final String[] inspectDetails;
    private final int extraHeight;
    private AnchorsLayout.Rect clip = AnchorsLayout.Rect.EMPTY;
    private float appear = 1.0F;
    protected float hover;
    private long lastFrame = System.nanoTime();
    private long pressedAt;
    private boolean pressedOnce;

    AnchorRow(int x, int y, int width, Component label, Component description, Font font, int controlWidth,
            int extraHeight, boolean danger, Component tag, String inspectKey, String... inspectDetails) {
        super(x, y, width, heightFor(font, description, width, controlWidth) + extraHeight, label);
        this.danger = danger;
        this.tag = tag == null ? "" : tag.getString();
        this.descriptionLines = AnchorsUi.wrap(font, description, textWidth(width, controlWidth));
        this.extraHeight = extraHeight;
        this.inspectKey = inspectKey;
        this.inspectDetails = inspectDetails;
    }

    /** The height a card needs for its description wrapped beside a control this wide. */
    static int heightFor(Font font, Component description, int width, int controlWidth) {
        int lines = AnchorsUi.wrap(font, description, textWidth(width, controlWidth)).size();
        return PAD_Y + 9 + (lines > 0 ? 3 + lines * LINE_HEIGHT - 1 : 0) + PAD_Y;
    }

    static int textWidth(int width, int controlWidth) {
        return Math.max(40, width - PAD_X * 2 - controlWidth - 10);
    }

    void setClip(AnchorsLayout.Rect clip) {
        this.clip = clip;
    }

    AnchorsLayout.Rect clip() {
        return this.clip;
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
        activate();
    }

    /** What a click or Enter on the card does. */
    protected abstract void activate();

    /**
     * Draws the control, right-aligned against {@code right}, level with the name at
     * {@code labelY}; returns its left edge, where the name has to stop.
     */
    protected abstract int drawControl(GuiGraphicsExtractor graphics, Font font, int right, int labelY, float fade);

    /** Anything drawn in the extra height under the description, such as a slider track. */
    protected void drawExtra(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, float fade) {
    }

    /** The height below the description reserved for {@link #drawExtra}. */
    protected int extraHeight() {
        return this.extraHeight;
    }

    /** The accent the card lights up with when its control is on; 0 to 1. */
    protected float lit() {
        return 0.0F;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        float elapsed = Mth.clamp((now - this.lastFrame) / 1_000_000.0F, 0.0F, 50.0F);
        this.lastFrame = now;
        float response = 1.0F - (float) Math.exp(-elapsed / 70.0F);
        animate(response);
        this.hover += ((isHoveredOrFocused() && this.active ? 1.0F : 0.0F) - this.hover) * response;
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
        // An edge on the left follows the control, so the state also reads from a distance.
        float lit = lit();
        if (lit > 0.01F) {
            graphics.fill(x + 2, y + 3, x + 4, y + height - 3, AnchorsTheme.withAlpha(accent, Math.round(220.0F * lit * fade)));
        }
        long sincePress = now - this.pressedAt;
        if (this.pressedOnce && sincePress >= 0L && sincePress < PRESS_FLASH_NANOS) {
            float t = sincePress / (float) PRESS_FLASH_NANOS;
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, AnchorsTheme.withAlpha(
                    this.danger ? 0xFF9AB0 : 0xE9D5FF, Math.round(90.0F * (1.0F - t) * (1.0F - t) * fade)));
        }

        Font font = Minecraft.getInstance().font;
        int labelY = y + PAD_Y;
        int textX = x + PAD_X + 2;
        int controlLeft = drawControl(graphics, font, x + width - PAD_X, labelY, fade);
        int tagWidth = this.tag.isEmpty() ? 0 : font.width(this.tag) + 8;
        String label = AnchorsUi.fit(font, getMessage().getString(), controlLeft - 6 - textX - (tagWidth > 0 ? tagWidth + 4 : 0));
        AnchorsUi.label(graphics, font, label, textX, labelY,
                AnchorsTheme.fade(AnchorsTheme.lerp(AnchorsTheme.TEXT, 0xFFFFFFFF, this.hover), fade), false);
        if (tagWidth > 0) {
            int tagX = textX + font.width(label) + 5;
            if (tagX + tagWidth < controlLeft - 4) {
                // Crimson on a danger card (a locked option); the bridge's green on any other.
                AnchorsUi.panel(graphics, tagX, labelY - 2, tagWidth, 12, AnchorsTheme.fade(this.danger ? 0xC0500A1E : 0xC00A3A20, fade),
                        AnchorsTheme.fade(this.danger ? 0xC02A0510 : 0xC0062414, fade));
                AnchorsUi.roundedOutline(graphics, tagX, labelY - 2, tagWidth, 12,
                        AnchorsTheme.fade(this.danger ? AnchorsTheme.CRIMSON_BRIGHT : 0xFF3BFF8A, fade));
                AnchorsUi.label(graphics, font, this.tag, tagX + 4, labelY,
                        AnchorsTheme.fade(this.danger ? 0xFFFFD6DE : 0xFFD6FFE6, fade), false);
            }
        }
        int lineY = labelY + 13;
        for (FormattedCharSequence line : this.descriptionLines) {
            AnchorsUi.line(graphics, font, line, textX, lineY, AnchorsTheme.fade(AnchorsTheme.TEXT_MUTED, fade));
            lineY += LINE_HEIGHT;
        }
        if (this.extraHeight > 0) {
            drawExtra(graphics, font, textX, y + height - PAD_Y - this.extraHeight, width - (textX - x) - PAD_X, fade);
        }
    }

    /** Per-frame easing of the control; {@code response} is this frame's share of the way. */
    protected void animate(float response) {
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        MutableComponent message = super.createNarrationMessage();
        if (!this.tag.isEmpty()) {
            message = message.append(CommonComponents.NARRATION_SEPARATOR).append(Component.literal(this.tag));
        }
        Component state = narrationState();
        return state == null ? message : message.append(CommonComponents.NARRATION_SEPARATOR).append(state);
    }

    /** The control's state as read aloud, or {@code null}. */
    protected Component narrationState() {
        return null;
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
