package dev.zymekoh.kohsanchors.gui;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/**
 * The note before the safe anchor view, in the square's own cyan: it is a hint, the player still
 * places the block, and some servers count helpers. A small scene plays the whole idea: the square
 * blinks between the player and their anchor, a block drops into it, the anchor blows and the
 * block takes the blast. After "Show it", the square stamps the screen once.
 */
final class SafeAnchorWarning {
    private static final long OPEN_NANOS = 240_000_000L;
    private static final long READ_NANOS = 1_400_000_000L;
    private static final long STAMP_NANOS = 750_000_000L;
    private static final long CLOSE_NANOS = 180_000_000L;
    private static final long SCENE_NANOS = 2_800_000_000L;
    private static final Identifier STONE = Identifier.withDefaultNamespace("textures/block/stone.png");
    private static final Identifier GLOWSTONE = Identifier.withDefaultNamespace("textures/block/glowstone.png");
    private static final Identifier ANCHOR = Identifier.withDefaultNamespace("textures/block/respawn_anchor_side4.png");

    private enum Phase { WARNING, STAMP, CLOSING, DONE }

    private final boolean motion;
    private final int color;
    private final Runnable onConfirm;
    private final long openedAt = System.nanoTime();
    private Phase phase = Phase.WARNING;
    private long phaseStartedAt = System.nanoTime();
    private boolean confirmed;
    private float cancelHover;
    private float confirmHover;

    SafeAnchorWarning(boolean motion, int color, Runnable onConfirm) {
        this.motion = motion;
        this.color = 0xFF000000 | color;
        this.onConfirm = onConfirm;
    }

    boolean done() {
        return this.phase == Phase.DONE;
    }

    boolean mouseClicked(int width, int height, double mouseX, double mouseY, int button) {
        long now = System.nanoTime();
        if (this.phase == Phase.STAMP) {
            close(now);
            return true;
        }
        if (this.phase != Phase.WARNING || button != Keys.LEFT_BUTTON) {
            return true;
        }
        AnchorsLayout.Modal modal = AnchorsLayout.modal(width, height);
        if (modal.cancel().contains(mouseX, mouseY)) {
            close(now);
        } else if (modal.confirm().contains(mouseX, mouseY) && now - this.openedAt >= READ_NANOS) {
            confirm(now);
        }
        return true;
    }

    boolean keyPressed(int key) {
        long now = System.nanoTime();
        if (key == Keys.ESCAPE) {
            close(now);
        } else if (Keys.confirms(key) && this.phase == Phase.WARNING && now - this.openedAt >= READ_NANOS) {
            confirm(now);
        }
        return true;
    }

    private void confirm(long now) {
        this.confirmed = true;
        this.onConfirm.run();
        this.phase = Phase.STAMP;
        this.phaseStartedAt = now;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F, 0.9F));
    }

    private void close(long now) {
        if (this.phase != Phase.CLOSING && this.phase != Phase.DONE) {
            this.phase = Phase.CLOSING;
            this.phaseStartedAt = now;
        }
    }

    void render(GuiGraphicsExtractor graphics, Font font, int width, int height, int mouseX, int mouseY) {
        long now = System.nanoTime();
        double seconds = now / 1_000_000_000.0D;
        switch (this.phase) {
            case WARNING -> {
                float open = this.motion ? AnchorsTheme.easeOutCubic((now - this.openedAt) / (float) OPEN_NANOS) : 1.0F;
                dim(graphics, width, height, open, seconds);
                drawWarning(graphics, font, width, height, mouseX, mouseY, open, now, seconds);
            }
            case STAMP -> {
                float t = Math.min(1.0F, (now - this.phaseStartedAt) / (float) (this.motion ? STAMP_NANOS : STAMP_NANOS / 3));
                dim(graphics, width, height, 1.0F - t, seconds);
                drawStamp(graphics, font, width, height, t);
                if (t >= 1.0F) {
                    close(now);
                }
            }
            case CLOSING -> {
                float t = Math.min(1.0F, (now - this.phaseStartedAt) / (float) CLOSE_NANOS);
                if (!this.confirmed) {
                    dim(graphics, width, height, 1.0F - t, seconds);
                }
                if (t >= 1.0F) {
                    this.phase = Phase.DONE;
                }
            }
            case DONE -> {
            }
        }
    }

    private void dim(GuiGraphicsExtractor graphics, int width, int height, float strength, double seconds) {
        if (strength <= 0.01F) {
            return;
        }
        graphics.fill(0, 0, width, height, AnchorsTheme.withAlpha(0x03121A, Math.round(205 * strength)));
        float pulse = this.motion ? 0.55F + 0.45F * AnchorsTheme.pulse(seconds, 2.2D) : 0.7F;
        int depth = Math.max(8, height / 5);
        graphics.fillGradient(0, 0, width, depth, AnchorsTheme.withAlpha(this.color, Math.round(80 * pulse * strength)), 0);
        graphics.fillGradient(0, height - depth, width, height, 0, AnchorsTheme.withAlpha(0x7C3AED, Math.round(110 * pulse * strength)));
    }

    private void drawWarning(GuiGraphicsExtractor graphics, Font font, int width, int height, int mouseX, int mouseY,
            float open, long now, double seconds) {
        AnchorsLayout.Modal modal = AnchorsLayout.modal(width, height);
        AnchorsLayout.Rect box = modal.box();
        AnchorsUi.halo(graphics, box.x(), box.y(), box.width(), box.height(), this.color, 6, open);
        AnchorsUi.panel(graphics, box.x(), box.y(), box.width(), box.height(), AnchorsTheme.fade(0xEE0B1522, open),
                AnchorsTheme.fade(0xF2120A20, open));
        AnchorsUi.roundedOutline(graphics, box.x(), box.y(), box.width(), box.height(), AnchorsTheme.fade(this.color, open));
        AnchorsUi.bladeCorners(graphics, box.x(), box.y(), box.width(), box.height(), 7, AnchorsTheme.fade(0xFFE4FBFF, open));
        if (open < 0.98F) {
            return;
        }
        if (this.motion) {
            AnchorsUi.comets(graphics, box.x(), box.y(), box.width(), box.height(), seconds, this.color & 0xFFFFFF);
        }
        int padding = modal.padding();
        int innerWidth = box.width() - padding * 2;
        int y = box.y() + padding;
        int bottomLimit = modal.cancel().y() - 6;
        String title = Component.translatable("kohs_anchors.safe.warning.title").getString();
        float scale = Math.max(1.0F, Math.min(2.4F, innerWidth / (float) Math.max(1, font.width(title))));
        scale = Math.min(scale, Math.max(1.0F, (bottomLimit - y - 30) / 9.0F));
        AnchorsUi.bigText(graphics, font, title, box.centerX() + 1, y + 1, scale, 0xFF06232B, false);
        AnchorsUi.bigText(graphics, font, title, box.centerX(), y, scale, 0xFFF2FDFF, false);
        y += Math.round(9 * scale) + 4;
        String subtitle = Component.translatable("kohs_anchors.safe.warning.subtitle").getString();
        AnchorsUi.label(graphics, font, AnchorsUi.fit(font, subtitle, innerWidth), box.centerX() - Math.min(innerWidth,
                font.width(subtitle)) / 2, y, this.color, true);
        y += 13;
        AnchorsUi.energyLine(graphics, box.x() + padding, box.right() - padding, y - 3, this.color, seconds, 1.0F);
        List<FormattedCharSequence> lines = font.split(Component.translatable("kohs_anchors.safe.warning.body"),
                Math.max(40, innerWidth));
        // The scene takes the room the text leaves, up to a block-sized stage, centred with the text.
        int room = bottomLimit - y - lines.size() * 10 - 6;
        int sceneHeight = Mth.clamp(room, 0, 96);
        if (sceneHeight >= 30) {
            y += Math.max(0, (room - sceneHeight) / 2);
            int sceneWidth = Math.min(innerWidth, sceneHeight * 3);
            drawScene(graphics, box.centerX() - sceneWidth / 2, y, sceneWidth, sceneHeight, now);
            y += sceneHeight + 6;
        }
        for (FormattedCharSequence line : lines) {
            if (y + 9 > bottomLimit) {
                break;
            }
            AnchorsUi.line(graphics, font, line, box.centerX() - font.width(line) / 2, y, 0xFFD6F6FF);
            y += 10;
        }
        AnchorsLayout.Rect cancel = modal.cancel();
        AnchorsLayout.Rect confirm = modal.confirm();
        long elapsed = now - this.openedAt;
        boolean ready = elapsed >= READ_NANOS;
        this.cancelHover += ((cancel.contains(mouseX, mouseY) ? 1.0F : 0.0F) - this.cancelHover) * 0.3F;
        this.confirmHover += ((confirm.contains(mouseX, mouseY) && ready ? 1.0F : 0.0F) - this.confirmHover) * 0.3F;
        AnchorsButton.draw(graphics, cancel.x(), cancel.y(), cancel.width(), cancel.height(),
                Component.translatable("kohs_anchors.warning.cancel").getString(), false, false, this.cancelHover, 0.0F, 1.0F,
                -1.0F);
        String text = Component.translatable(ready ? "kohs_anchors.safe.warning.confirm" : "kohs_anchors.safe.warning.wait")
                .getString();
        AnchorsButton.draw(graphics, confirm.x(), confirm.y(), confirm.width(), confirm.height(), text, true, false,
                this.confirmHover, 0.0F, 1.0F, ready ? -1.0F : elapsed / (float) READ_NANOS);
    }

    /**
     * Side view, looping: the player, the blinking square, the charged anchor; a block drops into the
     * square, the anchor blows, its rays stop at the block.
     */
    private void drawScene(GuiGraphicsExtractor graphics, int x, int y, int width, int height, long now) {
        float t = this.motion ? (now - this.openedAt) % SCENE_NANOS / 1_000_000_000.0F : 1.9F;
        int tile = Math.max(8, Math.min(24, height / 4));
        int ground = y + height - tile;
        for (int column = 0; column * tile < width; column++) {
            int drawn = Math.min(tile, width - column * tile);
            graphics.blit(RenderPipelines.GUI_TEXTURED, STONE, x + column * tile, ground, 0.0F, 0.0F, drawn, tile, 16, 16,
                    16, 16, 0xFFB8B8C8);
        }
        int playerX = x + tile;
        int squareX = x + width / 2 - tile / 2;
        int anchorX = x + width - tile * 2;
        // The anchor, charged and glowing; gone in the flash.
        boolean blown = t >= 1.55F && t < 2.35F;
        if (!blown) {
            AnchorsUi.glowEllipse(graphics, anchorX + tile / 2, ground - tile / 2, tile, tile, 0xA855F7, 0.55F);
            graphics.blit(RenderPipelines.GUI_TEXTURED, ANCHOR, anchorX, ground - tile, 0.0F, 0.0F, tile, tile, 16, 16, 16, 16);
        }
        // The player: a dark figure two blocks tall.
        int body = 0xFF2A1840;
        graphics.fill(playerX + tile / 4, ground - tile * 2, playerX + tile * 3 / 4, ground - tile * 3 / 2, 0xFFE0B48A);
        graphics.fill(playerX + tile / 5, ground - tile * 3 / 2, playerX + tile * 4 / 5, ground, body);
        graphics.fill(playerX + tile / 5, ground - tile * 3 / 2, playerX + tile * 4 / 5, ground - tile * 3 / 2 + 1, this.color);
        // The square: blinking until the block covers it.
        float drop = Mth.clamp((t - 0.9F) / 0.35F, 0.0F, 1.0F);
        if (t < 2.35F) {
            if (drop < 1.0F) {
                float blink = 0.5F + 0.5F * (float) Math.sin(t * Math.PI * 2.0D * 2.2D);
                graphics.fill(squareX, ground - 2, squareX + tile, ground, AnchorsTheme.withAlpha(this.color, Math.round(90 + 150 * blink)));
            }
            if (drop > 0.0F) {
                int top = Math.round(Mth.lerp(drop * drop, y - tile, ground - tile));
                graphics.blit(RenderPipelines.GUI_TEXTURED, GLOWSTONE, squareX, top, 0.0F, 0.0F, tile, tile, 16, 16, 16, 16);
            }
        }
        // The blast: a ring, and rays toward the player that the block stops.
        if (blown) {
            float blast = (t - 1.55F) / 0.8F;
            int centerX = anchorX + tile / 2;
            int centerY = ground - tile / 2;
            int alpha = Math.round(230 * (1.0F - blast));
            AnchorsUi.ring(graphics, centerX, centerY, Math.round(tile * (0.5F + 2.2F * blast)), 2,
                    AnchorsTheme.withAlpha(0xFFE9D5FF, alpha));
            for (int ray = 0; ray < 5; ray++) {
                int targetY = ground - tile * 2 + ray * tile * 2 / 5;
                int stopX = squareX + tile;
                boolean through = targetY < ground - tile;
                int endX = through ? playerX + tile / 2 : stopX;
                float reach = Math.min(1.0F, blast * 3.0F);
                int tipX = Math.round(Mth.lerp(reach, centerX, endX));
                int tipY = Math.round(Mth.lerp(reach * (tipX - centerX) / (float) Math.min(-1, endX - centerX),
                        centerY, targetY));
                dashed(graphics, centerX, centerY, tipX, tipY, AnchorsTheme.withAlpha(through ? 0xFF6A80 : 0xFFE9D5FF, alpha));
            }
        }
    }

    /** A dotted line from one point to another. */
    private static void dashed(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color) {
        int steps = Math.max(1, Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)) / 2);
        for (int step = 0; step <= steps; step += 2) {
            int px = x0 + (x1 - x0) * step / steps;
            int py = y0 + (y1 - y0) * step / steps;
            graphics.fill(px, py, px + 1, py + 1, color);
        }
    }

    /** "On": the square stamps the screen and fades. */
    private void drawStamp(GuiGraphicsExtractor graphics, Font font, int width, int height, float t) {
        int size = Math.round(Math.min(width, height) * (0.18F + 0.5F * AnchorsTheme.easeOutCubic(t)));
        int alpha = Math.round(220 * (1.0F - t));
        int left = width / 2 - size / 2;
        int top = height / 2 - size / 2;
        int edge = Math.max(2, size / 14);
        int frame = AnchorsTheme.withAlpha(this.color, alpha);
        graphics.fill(left, top, left + size, top + edge, frame);
        graphics.fill(left, top + size - edge, left + size, top + size, frame);
        graphics.fill(left, top + edge, left + edge, top + size - edge, frame);
        graphics.fill(left + size - edge, top + edge, left + size, top + size - edge, frame);
        graphics.fill(left + edge, top + edge, left + size - edge, top + size - edge, AnchorsTheme.withAlpha(this.color, alpha / 4));
        String on = Component.translatable("kohs_anchors.safe.on").getString();
        float text = 1.0F - Math.max(0.0F, t - 0.6F) / 0.4F;
        AnchorsUi.bigText(graphics, font, on, width / 2 + 1, height / 2 - 8, 2.0F, AnchorsTheme.fade(0xFF06232B, text), false);
        AnchorsUi.bigText(graphics, font, on, width / 2, height / 2 - 9, 2.0F, AnchorsTheme.fade(0xFFF2FDFF, text), false);
    }
}
