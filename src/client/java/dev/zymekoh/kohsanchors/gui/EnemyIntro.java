package dev.zymekoh.kohsanchors.gui;

import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

/**
 * What enemy anchors are, the first time the player opens their page: the player's anchor beside
 * an enemy's, each in its own glow, and a line on what the page changes. "Don't show again" is a
 * switch the player can tick before "Continue"; Escape leaves the player's page as it was.
 */
final class EnemyIntro {
    private static final long OPEN_NANOS = 260_000_000L;
    private static final long CLOSE_NANOS = 180_000_000L;

    private enum Phase { OPEN, CLOSING, DONE }

    private final boolean motion;
    private final AnchorFigure figure;
    private final Runnable onContinue;
    private final long openedAt = System.nanoTime();
    private Phase phase = Phase.OPEN;
    private long closedAt;
    private boolean continued;
    private boolean hide;
    private float hideHover;
    private float continueHover;
    private long lastFrame = this.openedAt;

    EnemyIntro(boolean motion, AnchorFigure figure, Runnable onContinue) {
        this.motion = motion;
        this.figure = figure;
        this.onContinue = onContinue;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.RESPAWN_ANCHOR_AMBIENT, 1.2F, 0.7F));
    }

    boolean done() {
        return this.phase == Phase.DONE;
    }

    /** Whether the player ticked "Don't show again" and continued. */
    boolean hideFromNowOn() {
        return this.continued && this.hide;
    }

    boolean mouseClicked(int width, int height, double mouseX, double mouseY, int button) {
        if (this.phase != Phase.OPEN || button != Keys.LEFT_BUTTON) {
            return true;
        }
        AnchorsLayout.Modal modal = layout(width, height);
        if (modal.cancel().contains(mouseX, mouseY)) {
            this.hide = !this.hide;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, this.hide ? 1.2F : 0.9F));
        } else if (modal.confirm().contains(mouseX, mouseY)) {
            proceed();
        }
        return true;
    }

    /**
     * The window at this size. The "Don't show again" switch widens for a translation longer than
     * the usual button: its box, the gaps and the text.
     */
    private static AnchorsLayout.Modal layout(int width, int height) {
        Font font = Minecraft.getInstance().font;
        return AnchorsLayout.modal(width, height,
                font.width(Component.translatable("kohs_anchors.enemy.intro.hide").getString()) + 21);
    }

    boolean keyPressed(int key) {
        if (this.phase != Phase.OPEN) {
            return true;
        }
        if (key == Keys.ESCAPE) {
            close();
        } else if (Keys.confirms(key)) {
            proceed();
        } else if (key == Keys.SPACE) {
            this.hide = !this.hide;
        }
        return true;
    }

    private void proceed() {
        this.continued = true;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        close();
    }

    private void close() {
        this.phase = Phase.CLOSING;
        this.closedAt = System.nanoTime();
    }

    void render(GuiGraphicsExtractor graphics, Font font, int width, int height, int mouseX, int mouseY) {
        long now = System.nanoTime();
        float frameMillis = Math.min(50.0F, (now - this.lastFrame) / 1_000_000.0F);
        this.lastFrame = now;
        float response = 1.0F - (float) Math.exp(-frameMillis / 60.0F);
        double seconds = now / 1_000_000_000.0D;
        float open = this.motion ? AnchorsTheme.easeOutCubic((now - this.openedAt) / (float) OPEN_NANOS) : 1.0F;
        if (this.phase == Phase.CLOSING) {
            float t = Math.min(1.0F, (now - this.closedAt) / (float) CLOSE_NANOS);
            open = 1.0F - t;
            if (t >= 1.0F) {
                this.phase = Phase.DONE;
                if (this.continued) {
                    this.onContinue.run();
                }
                return;
            }
        }
        graphics.fill(0, 0, width, height, AnchorsTheme.withAlpha(0x06020A, Math.round(190 * open)));
        AnchorsLayout.Modal modal = layout(width, height);
        AnchorsLayout.Rect box = modal.box();
        float grow = 0.93F + 0.07F * open;
        int boxWidth = Math.round(box.width() * grow);
        int boxHeight = Math.round(box.height() * grow);
        int boxX = box.centerX() - boxWidth / 2;
        int boxY = box.centerY() - boxHeight / 2;
        // Violet on the player's side, crimson on the enemy's.
        AnchorsUi.halo(graphics, boxX, boxY, boxWidth / 2, boxHeight, AnchorsTheme.ACCENT, 5, 0.7F * open);
        AnchorsUi.halo(graphics, boxX + boxWidth / 2, boxY, boxWidth - boxWidth / 2, boxHeight, AnchorsTheme.CRIMSON_BRIGHT, 5,
                0.7F * open);
        AnchorsUi.panel(graphics, boxX, boxY, boxWidth, boxHeight, AnchorsTheme.fade(0xF2160B27, open),
                AnchorsTheme.fade(0xF01A0710, open));
        graphics.fillGradient(boxX + 1, boxY + 1, boxX + boxWidth - 1, boxY + 3, AnchorsTheme.fade(0xC0A855F7, open),
                AnchorsTheme.fade(0x00A855F7, open));
        AnchorsUi.roundedOutline(graphics, boxX, boxY, boxWidth, boxHeight, AnchorsTheme.fade(0xE0B8243F, open));
        AnchorsUi.bladeCorners(graphics, boxX, boxY, boxWidth, boxHeight, 7, AnchorsTheme.fade(0xE0FFD6DE, open));
        if (open < 0.95F || this.phase != Phase.OPEN) {
            return;
        }
        if (this.motion) {
            AnchorsUi.comets(graphics, box.x(), box.y(), box.width(), box.height(), seconds, 0xFF9AB0);
        }

        int padding = modal.padding();
        int innerWidth = box.width() - padding * 2;
        int y = box.y() + padding;
        int bottomLimit = modal.cancel().y() - 6;
        String title = Component.translatable("kohs_anchors.enemy.intro.title").getString().toUpperCase(Locale.ROOT);
        float scale = Math.max(1.0F, Math.min(2.0F, innerWidth / (float) Math.max(1, font.width(title))));
        AnchorsUi.bigText(graphics, font, title, box.centerX() + 1, y + 1, scale, 0xFF5A0514, false);
        AnchorsUi.bigText(graphics, font, title, box.centerX(), y, scale, AnchorsTheme.TITLE, false);
        y += Math.round(9 * scale) + 3;
        AnchorsUi.energyLine(graphics, box.x() + padding, box.right() - padding, y, AnchorsTheme.CRIMSON_BRIGHT, seconds, 1.0F);
        y += 5;

        List<FormattedCharSequence> lines = font.split(Component.translatable("kohs_anchors.enemy.intro.body"),
                Math.max(40, innerWidth));
        int textHeight = lines.size() * 10;
        // The two anchors, when there is room for them above the text.
        int stage = bottomLimit - y - textHeight - 4;
        if (stage >= 44) {
            float anchorScale = Math.min(stage * 0.34F, innerWidth * 0.13F);
            int stageCenter = y + stage / 2 - 3;
            int leftX = box.centerX() - Math.round(innerWidth * 0.22F);
            int rightX = box.centerX() + Math.round(innerWidth * 0.22F);
            this.figure.draw(graphics, leftX, stageCenter, anchorScale, 1.0F, 0.0F, this.motion, true);
            this.figure.draw(graphics, rightX, stageCenter, anchorScale, 1.0F, 1.0F, this.motion, true, false);
            String yours = Component.translatable("kohs_anchors.enemy.intro.yours").getString().toUpperCase(Locale.ROOT);
            String enemy = Component.translatable("kohs_anchors.enemy.intro.enemy").getString().toUpperCase(Locale.ROOT);
            int labelY = stageCenter + Math.round(anchorScale * 0.95F);
            if (labelY + 9 <= y + stage) {
                AnchorsUi.label(graphics, font, yours, leftX - font.width(yours) / 2, labelY, AnchorsTheme.ACCENT_BRIGHT, true);
                AnchorsUi.label(graphics, font, enemy, rightX - font.width(enemy) / 2, labelY, 0xFFFF6A86, true);
            }
            // Crossed blades between them.
            int markX = box.centerX();
            int markY = stageCenter - 4;
            for (int step = 0; step < 9; step++) {
                graphics.fill(markX - 4 + step, markY + step, markX - 3 + step, markY + step + 1, 0xFFE9A0C0);
                graphics.fill(markX + 4 - step, markY + step, markX + 5 - step, markY + step + 1, 0xFFE9A0C0);
            }
            y += stage + 4;
        }
        for (FormattedCharSequence line : lines) {
            if (y + 9 > bottomLimit) {
                break;
            }
            AnchorsUi.line(graphics, font, line, box.centerX() - font.width(line) / 2, y, AnchorsTheme.TEXT);
            y += 10;
        }

        AnchorsLayout.Rect hide = modal.cancel();
        AnchorsLayout.Rect proceed = modal.confirm();
        this.hideHover += ((hide.contains(mouseX, mouseY) ? 1.0F : 0.0F) - this.hideHover) * response;
        this.continueHover += ((proceed.contains(mouseX, mouseY) ? 1.0F : 0.0F) - this.continueHover) * response;
        // "Don't show again" is a switch: a box that fills with a tick.
        AnchorsButton.draw(graphics, hide.x(), hide.y(), hide.width(), hide.height(), "", false, false, this.hideHover, 0.0F,
                1.0F, -1.0F);
        int boxSize = Math.min(9, hide.height() - 5);
        int tickX = hide.x() + 5;
        int tickY = hide.y() + (hide.height() - boxSize) / 2;
        AnchorsUi.outline(graphics, tickX, tickY, boxSize, boxSize, this.hide ? AnchorsTheme.ACCENT_BRIGHT : AnchorsTheme.SILVER);
        if (this.hide) {
            graphics.fill(tickX + 2, tickY + 2, tickX + boxSize - 2, tickY + boxSize - 2, AnchorsTheme.ACCENT_BRIGHT);
        }
        String hideText = AnchorsUi.fit(font, Component.translatable("kohs_anchors.enemy.intro.hide").getString(),
                hide.width() - boxSize - 12);
        AnchorsUi.label(graphics, font, hideText, tickX + boxSize + 4, hide.y() + (hide.height() - 8) / 2, AnchorsTheme.TEXT,
                true);
        AnchorsButton.draw(graphics, proceed.x(), proceed.y(), proceed.width(), proceed.height(),
                Component.translatable("kohs_anchors.enemy.intro.continue").getString(), true, true, this.continueHover, 0.0F,
                1.0F, -1.0F);
        DevInspector.node("EnemyIntro", "first time", box.x(), box.y(), box.width(), box.height(),
                "Settings.enemyIntro", "AnchorFigure: yours and the enemy's");
    }
}
