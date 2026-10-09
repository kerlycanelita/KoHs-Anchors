package dev.zymekoh.kohsanchors.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** The footer buttons: dark glass that brightens on hover, compresses and flashes when pressed. */
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
        long sincePress = now - this.pressedAt;
        float press = this.pressedOnce && sincePress >= 0L && sincePress < PRESS_FLASH_NANOS
                ? 1.0F - sincePress / (float) PRESS_FLASH_NANOS : 0.0F;
        draw(graphics, getX(), getY(), getWidth(), getHeight(), getMessage().getString(), this.primary, false,
                this.hover, press, Mth.clamp(this.alpha, 0.0F, 1.0F), -1.0F);
    }

    /**
     * One glass button, shared with the warning's own buttons. {@code progress} below zero draws
     * no bar; from 0 to 1 it fills the button from the left, for a button that is not ready yet.
     */
    static void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height, String text, boolean primary,
            boolean danger, float hover, float press, float fade, float progress) {
        draw(graphics, x, y, width, height, text, primary, danger, false, hover, press, fade, progress);
    }

    /** The button of the enemy's own windows: the danger button's glass in the colour of their anchors. */
    static void drawEnemy(GuiGraphicsExtractor graphics, int x, int y, int width, int height, String text, float hover) {
        draw(graphics, x, y, width, height, text, true, true, true, hover, 0.0F, 1.0F, -1.0F);
    }

    private static int toned(boolean enemy, int crimson) {
        return enemy ? AnchorsTheme.enemyTone(crimson) : crimson;
    }

    private static void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height, String text, boolean primary,
            boolean danger, boolean enemy, float hover, float press, float fade, float progress) {
        if (fade <= 0.01F || width <= 0 || height <= 0) {
            return;
        }
        // A pressed button sinks by a pixel, the way a key does.
        int sink = press > 0.6F ? 1 : 0;
        int drawY = y + sink;
        float lift = primary ? 0.35F + hover * 0.65F : hover;
        int top;
        int bottom;
        int border;
        int glow;
        if (danger) {
            top = toned(enemy, AnchorsTheme.lerp(0xE0561024, 0xF08C1636, lift));
            bottom = toned(enemy, AnchorsTheme.lerp(0xE62A0612, 0xF0480A1C, lift));
            border = toned(enemy, AnchorsTheme.lerp(0xE0B8243F, 0xFFFF6A86, hover));
            glow = toned(enemy, AnchorsTheme.CRIMSON_BRIGHT);
        } else {
            top = AnchorsTheme.lerp(AnchorsTheme.BUTTON_TOP, AnchorsTheme.BUTTON_HOVER_TOP, lift);
            bottom = AnchorsTheme.lerp(AnchorsTheme.BUTTON_BOTTOM, AnchorsTheme.BUTTON_HOVER_BOTTOM, lift);
            border = AnchorsTheme.lerp(AnchorsTheme.CARD_BORDER_HOVER, AnchorsTheme.ACCENT_BRIGHT, hover);
            glow = AnchorsTheme.ACCENT;
        }
        boolean ready = progress < 0.0F || progress >= 1.0F;
        if (!ready) {
            top = AnchorsTheme.lerp(top, 0xE0180A20, 0.6F);
            bottom = AnchorsTheme.lerp(bottom, 0xE00C0510, 0.6F);
        }
        if (hover > 0.05F && ready) {
            AnchorsUi.halo(graphics, x, drawY, width, height, glow, 3, hover * fade);
        }
        AnchorsUi.panel(graphics, x, drawY, width, height, AnchorsTheme.fade(top, fade), AnchorsTheme.fade(bottom, fade));
        if (!ready) {
            int filled = Math.round((width - 2) * AnchorsTheme.clamp01(progress));
            graphics.fill(x + 1, drawY + height - 3, x + 1 + filled, drawY + height - 1,
                    AnchorsTheme.fade(danger ? AnchorsTheme.CRIMSON_BRIGHT : AnchorsTheme.ACCENT_BRIGHT, fade));
        }
        AnchorsUi.roundedOutline(graphics, x, drawY, width, height, AnchorsTheme.fade(border, fade));
        if (primary && ready) {
            AnchorsUi.bladeCorners(graphics, x, drawY, width, height, 4, AnchorsTheme.fade(border, 0.8F * fade));
        }
        if (press > 0.0F) {
            graphics.fill(x + 1, drawY + 1, x + width - 1, drawY + height - 1,
                    AnchorsTheme.withAlpha(danger ? 0xFFB3C2 : 0xF5DCFF, Math.round(130.0F * press * press * fade)));
        }

        Font font = Minecraft.getInstance().font;
        String label = AnchorsUi.fit(font, text, width - 8);
        int textColor = ready ? AnchorsTheme.lerp(AnchorsTheme.TEXT, 0xFFFFFFFF, hover) : AnchorsTheme.TEXT_DIM;
        AnchorsUi.label(graphics, font, label, x + (width - font.width(label)) / 2, drawY + (height - 8) / 2,
                AnchorsTheme.fade(textColor, fade), true);
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
