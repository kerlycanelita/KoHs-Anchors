package dev.zymekoh.kohsanchors.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** The footer buttons: purple glass that brightens on hover and flashes when pressed. */
final class AnchorsButton extends AbstractButton {
    private static final long PRESS_FLASH_NANOS = 240_000_000L;

    private final Runnable action;
    private final boolean primary;
    private float hover;
    private long lastFrame = System.nanoTime();
    private long pressedAt;
    private boolean pressedOnce;

    AnchorsButton(AnchorsLayout.Rect rect, Component message, boolean primary, Runnable action) {
        super(rect.x(), rect.y(), rect.width(), rect.height(), message);
        this.primary = primary;
        this.action = action;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        this.pressedAt = System.nanoTime();
        this.pressedOnce = true;
        this.action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        float elapsed = Mth.clamp((now - this.lastFrame) / 1_000_000.0F, 0.0F, 50.0F);
        this.lastFrame = now;
        float response = 1.0F - (float) Math.exp(-elapsed / 60.0F);
        this.hover += ((isHoveredOrFocused() && this.active ? 1.0F : 0.0F) - this.hover) * response;
        float fade = Mth.clamp(this.alpha, 0.0F, 1.0F);

        int x = getX();
        int y = getY();
        int width = getWidth();
        int height = getHeight();
        float lift = this.primary ? 0.35F + this.hover * 0.65F : this.hover;
        int top = AnchorsTheme.lerp(AnchorsTheme.BUTTON_TOP, AnchorsTheme.BUTTON_HOVER_TOP, lift);
        int bottom = AnchorsTheme.lerp(AnchorsTheme.BUTTON_BOTTOM, AnchorsTheme.BUTTON_HOVER_BOTTOM, lift);
        if (this.hover > 0.05F) {
            AnchorsUi.halo(graphics, x, y, width, height, AnchorsTheme.ACCENT, 3, this.hover * fade);
        }
        AnchorsUi.panel(graphics, x, y, width, height, AnchorsTheme.fade(top, fade), AnchorsTheme.fade(bottom, fade));
        AnchorsUi.roundedOutline(graphics, x, y, width, height, AnchorsTheme.fade(
                AnchorsTheme.lerp(AnchorsTheme.CARD_BORDER_HOVER, AnchorsTheme.ACCENT_BRIGHT, this.hover), fade));

        long sincePress = now - this.pressedAt;
        if (this.pressedOnce && sincePress >= 0L && sincePress < PRESS_FLASH_NANOS) {
            float t = sincePress / (float) PRESS_FLASH_NANOS;
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1,
                    AnchorsTheme.withAlpha(0xF5DCFF, Math.round(130.0F * (1.0F - t) * (1.0F - t) * fade)));
        }

        Font font = Minecraft.getInstance().font;
        String label = AnchorsUi.fit(font, getMessage().getString(), width - 8);
        int color = AnchorsTheme.fade(AnchorsTheme.lerp(AnchorsTheme.TEXT, 0xFFFFFFFF, this.hover), fade);
        AnchorsUi.label(graphics, font, label, x + (width - font.width(label)) / 2, y + (height - 8) / 2, color, true);
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
