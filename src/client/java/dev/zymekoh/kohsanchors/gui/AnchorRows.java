package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.skin.ColorMath;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** The smaller cards: a button, a colour, a choice that cycles, and a note that only informs. */
final class AnchorRows {
    private AnchorRows() {
    }

    /** A card whose control is a small glass button, such as "Open" or "Forget". */
    static final class Button extends AnchorRow {
        private final Supplier<String> buttonText;
        private final Runnable action;
        private final boolean primary;

        Button(int x, int y, int width, Component label, Component description, Font font, Supplier<String> buttonText,
                Runnable action, boolean primary, boolean danger, String inspectKey, String... details) {
            super(x, y, width, label, description, font, Math.max(56, font.width(buttonText.get()) + 16), 0, danger, null,
                    inspectKey, details);
            this.buttonText = buttonText;
            this.action = action;
            this.primary = primary;
        }

        @Override
        protected void activate() {
            this.action.run();
        }

        @Override
        protected int drawControl(GuiGraphicsExtractor graphics, Font font, int right, int labelY, float fade) {
            String text = this.buttonText.get();
            int width = Math.max(56, font.width(text) + 16);
            int x = right - width;
            AnchorsButton.draw(graphics, x, labelY - 3, width, 14, text, this.primary, this.danger, this.hover, 0.0F, fade,
                    -1.0F);
            return x;
        }
    }

    /** A card with a colour swatch and its hex code; a press opens the colour picker. */
    static final class Color extends AnchorRow {
        private final IntSupplier color;
        private final Runnable open;
        private final BooleanSupplier enabled;

        Color(int x, int y, int width, Component label, Component description, Font font, IntSupplier color, Runnable open,
                BooleanSupplier enabled, String inspectKey, String... details) {
            super(x, y, width, label, description, font, 78, 0, false, null, inspectKey, details);
            this.color = color;
            this.open = open;
            this.enabled = enabled;
        }

        @Override
        protected void activate() {
            if (this.enabled.getAsBoolean()) {
                this.open.run();
            }
        }

        @Override
        protected float lit() {
            return this.enabled.getAsBoolean() ? 1.0F : 0.0F;
        }

        @Override
        protected int drawControl(GuiGraphicsExtractor graphics, Font font, int right, int labelY, float fade) {
            boolean on = this.enabled.getAsBoolean();
            float alpha = fade * (on ? 1.0F : 0.4F);
            int swatch = 20;
            int x = right - swatch;
            int color = this.color.getAsInt();
            AnchorsUi.swatch(graphics, x, labelY - 3, swatch, 14, color, alpha, this.hover);
            String hex = ColorMath.hex(color);
            int textX = x - 5 - font.width(hex);
            AnchorsUi.label(graphics, font, hex, textX, labelY, AnchorsTheme.fade(AnchorsTheme.TEXT_MUTED, alpha), false);
            return textX;
        }
    }

    /** A card whose value steps through a few choices on each press. */
    static final class Cycle extends AnchorRow {
        private final Supplier<String> value;
        private final Runnable next;

        Cycle(int x, int y, int width, Component label, Component description, Font font, Supplier<String> value,
                Runnable next, String inspectKey, String... details) {
            super(x, y, width, label, description, font, 96, 0, false, null, inspectKey, details);
            this.value = value;
            this.next = next;
        }

        @Override
        protected void activate() {
            this.next.run();
        }

        @Override
        protected float lit() {
            return 1.0F;
        }

        @Override
        protected int drawControl(GuiGraphicsExtractor graphics, Font font, int right, int labelY, float fade) {
            String text = this.value.get();
            int width = font.width(text) + 22;
            int x = right - width;
            AnchorsUi.panel(graphics, x, labelY - 3, width, 14, AnchorsTheme.fade(0xC04A1C80, fade),
                    AnchorsTheme.fade(0xC0251240, fade));
            AnchorsUi.roundedOutline(graphics, x, labelY - 3, width, 14, AnchorsTheme.fade(
                    AnchorsTheme.lerp(AnchorsTheme.CARD_BORDER_HOVER, AnchorsTheme.ACCENT_BRIGHT, this.hover), fade));
            AnchorsUi.label(graphics, font, "‹", x + 4, labelY, AnchorsTheme.fade(AnchorsTheme.ACCENT, fade), false);
            AnchorsUi.label(graphics, font, text, x + 11, labelY, AnchorsTheme.fade(AnchorsTheme.ACCENT_BRIGHT, fade), false);
            AnchorsUi.label(graphics, font, "›", x + width - 8, labelY, AnchorsTheme.fade(AnchorsTheme.ACCENT, fade), false);
            return x;
        }

        @Override
        protected Component narrationState() {
            return Component.literal(this.value.get());
        }
    }

    /** A card that only informs: a status, a list of what is always on. It cannot be pressed. */
    static final class Note extends AnchorRow {
        private final Supplier<String> badge;
        private final int badgeColor;

        Note(int x, int y, int width, Component label, Component description, Font font, Supplier<String> badge,
                int badgeColor, String inspectKey, String... details) {
            super(x, y, width, label, description, font, badge == null ? 0 : 80, 0, false, null, inspectKey, details);
            this.badge = badge;
            this.badgeColor = badgeColor;
            this.active = false;
        }

        @Override
        protected void activate() {
        }

        @Override
        protected int drawControl(GuiGraphicsExtractor graphics, Font font, int right, int labelY, float fade) {
            if (this.badge == null) {
                return right;
            }
            String text = this.badge.get();
            int width = font.width(text) + 10;
            int x = right - width;
            AnchorsUi.panel(graphics, x, labelY - 2, width, 12, AnchorsTheme.fade(0x90180A28, fade),
                    AnchorsTheme.fade(0x900C0514, fade));
            AnchorsUi.roundedOutline(graphics, x, labelY - 2, width, 12, AnchorsTheme.fade(this.badgeColor, fade));
            AnchorsUi.label(graphics, font, text, x + 5, labelY, AnchorsTheme.fade(this.badgeColor, fade), false);
            return x;
        }
    }
}
