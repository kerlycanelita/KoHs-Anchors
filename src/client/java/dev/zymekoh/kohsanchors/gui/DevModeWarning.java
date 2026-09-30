package dev.zymekoh.kohsanchors.gui;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

/**
 * The warning before developer mode, in blue-violet instead of crimson: it risks nothing on a
 * server, it only changes the screen into a developer's view. After "Enable", a short boot: a scan
 * runs down the screen while the mod's own classes scroll past.
 */
final class DevModeWarning {
    private static final long OPEN_NANOS = 240_000_000L;
    private static final long READ_NANOS = 1_200_000_000L;
    private static final long BOOT_NANOS = 1_300_000_000L;
    private static final long CLOSE_NANOS = 180_000_000L;
    private static final String[] CLASSES = {
            "dev.zymekoh.kohsanchors.input.AnchorInput", "dev.zymekoh.kohsanchors.input.AnchorDebounce",
            "dev.zymekoh.kohsanchors.input.FastChain", "dev.zymekoh.kohsanchors.predict.DetonationPredictor",
            "dev.zymekoh.kohsanchors.predict.AnchorVeil", "dev.zymekoh.kohsanchors.skin.AtlasSkin",
            "dev.zymekoh.kohsanchors.skin.SkinComposer", "dev.zymekoh.kohsanchors.glow.AnchorGlowRenderer",
            "dev.zymekoh.kohsanchors.glow.AnchorTracker", "dev.zymekoh.kohsanchors.sound.AnchorSounds",
            "dev.zymekoh.kohsanchors.safety.ServerLock", "mixin.KeyMappingMixin @ KeyMapping.click",
            "mixin.MinecraftMixin @ Minecraft.handleKeybinds", "mixin.MultiPlayerGameModeMixin @ useItemOn",
            "mixin.ClientLevelServerStateMixin @ setServerVerifiedBlockState",
            "mixin.LevelRendererGlowMixin @ submitBlockEntities", "mixin.TextureAtlasMixin @ upload",
            "mixin.RenderSectionRegionMixin @ getBlockState", "mixin.ClientPacketListenerMixin @ handleExplosion"};

    private enum Phase { WARNING, BOOT, CLOSING, DONE }

    private final boolean motion;
    private final Runnable onConfirm;
    private final long openedAt = System.nanoTime();
    private Phase phase = Phase.WARNING;
    private long phaseStartedAt = System.nanoTime();
    private boolean confirmed;
    private float cancelHover;
    private float confirmHover;

    DevModeWarning(boolean motion, Runnable onConfirm) {
        this.motion = motion;
        this.onConfirm = onConfirm;
    }

    boolean done() {
        return this.phase == Phase.DONE;
    }

    boolean mouseClicked(int width, int height, double mouseX, double mouseY, int button) {
        long now = System.nanoTime();
        if (this.phase == Phase.BOOT) {
            close(now);
            return true;
        }
        if (this.phase != Phase.WARNING || button != 0) {
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
        if (key == 256) {
            close(now);
        } else if ((key == 257 || key == 335) && this.phase == Phase.WARNING && now - this.openedAt >= READ_NANOS) {
            confirm(now);
        }
        return true;
    }

    private void confirm(long now) {
        this.confirmed = true;
        this.onConfirm.run();
        this.phase = Phase.BOOT;
        this.phaseStartedAt = now;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BEACON_ACTIVATE, 1.7F, 0.45F));
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
            case BOOT -> {
                float t = Math.min(1.0F, (now - this.phaseStartedAt) / (float) (this.motion ? BOOT_NANOS : BOOT_NANOS / 3));
                dim(graphics, width, height, 1.0F - Math.max(0.0F, t - 0.8F) * 5.0F, seconds);
                drawBoot(graphics, font, width, height, t);
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
        graphics.fill(0, 0, width, height, AnchorsTheme.withAlpha(0x04061A, Math.round(210 * strength)));
        float pulse = this.motion ? 0.55F + 0.45F * AnchorsTheme.pulse(seconds, 2.4D) : 0.7F;
        int depth = Math.max(8, height / 5);
        graphics.fillGradient(0, 0, width, depth, AnchorsTheme.withAlpha(0x3B4BFF, Math.round(110 * pulse * strength)), 0);
        graphics.fillGradient(0, height - depth, width, height, 0,
                AnchorsTheme.withAlpha(0x6A3BFF, Math.round(110 * pulse * strength)));
        if (this.motion) {
            // A faint debugger grid.
            int grid = 16;
            int color = AnchorsTheme.withAlpha(0x5B6CFF, Math.round(18 * strength));
            for (int x = (int) (seconds * 6.0D) % grid; x < width; x += grid) {
                graphics.fill(x, 0, x + 1, height, color);
            }
            for (int y = (int) (seconds * 6.0D) % grid; y < height; y += grid) {
                graphics.fill(0, y, width, y + 1, color);
            }
        }
    }

    private void drawWarning(GuiGraphicsExtractor graphics, Font font, int width, int height, int mouseX, int mouseY,
            float open, long now, double seconds) {
        AnchorsLayout.Modal modal = AnchorsLayout.modal(width, height);
        AnchorsLayout.Rect box = modal.box();
        AnchorsUi.halo(graphics, box.x(), box.y(), box.width(), box.height(), AnchorsTheme.DEV_BLUE, 6, open);
        AnchorsUi.panel(graphics, box.x(), box.y(), box.width(), box.height(), AnchorsTheme.fade(AnchorsTheme.DEV_GLASS_TOP, open),
                AnchorsTheme.fade(AnchorsTheme.DEV_GLASS_BOTTOM, open));
        AnchorsUi.roundedOutline(graphics, box.x(), box.y(), box.width(), box.height(), AnchorsTheme.fade(AnchorsTheme.DEV_BLUE, open));
        AnchorsUi.bladeCorners(graphics, box.x(), box.y(), box.width(), box.height(), 7, AnchorsTheme.fade(0xFFDDE4FF, open));
        if (open < 0.98F) {
            return;
        }
        if (this.motion) {
            AnchorsUi.comets(graphics, box.x(), box.y(), box.width(), box.height(), seconds, 0x9DB0FF);
        }
        int padding = modal.padding();
        int innerWidth = box.width() - padding * 2;
        int y = box.y() + padding;
        int bottomLimit = modal.cancel().y() - 6;
        // A bracket glyph: "</>".
        String glyph = "</>";
        AnchorsUi.bigText(graphics, font, glyph, box.centerX(), y, 1.6F, AnchorsTheme.DEV_BLUE_BRIGHT, false);
        y += 18;
        String title = Component.translatable("kohs_anchors.dev.warning.title").getString();
        float scale = Math.max(1.0F, Math.min(2.6F, innerWidth / (float) Math.max(1, font.width(title))));
        scale = Math.min(scale, Math.max(1.0F, (bottomLimit - y - 30) / 9.0F));
        AnchorsUi.bigText(graphics, font, title, box.centerX() + 1, y + 1, scale, 0xFF1A1F6B, false);
        AnchorsUi.bigText(graphics, font, title, box.centerX(), y, scale, 0xFFF4F6FF, false);
        y += Math.round(9 * scale) + 4;
        String subtitle = Component.translatable("kohs_anchors.dev.warning.subtitle").getString();
        AnchorsUi.label(graphics, font, AnchorsUi.fit(font, subtitle, innerWidth), box.centerX() - Math.min(innerWidth,
                font.width(subtitle)) / 2, y, AnchorsTheme.DEV_BLUE_BRIGHT, true);
        y += 13;
        AnchorsUi.energyLine(graphics, box.x() + padding, box.right() - padding, y - 3, AnchorsTheme.DEV_BLUE, seconds, 1.0F);
        List<FormattedCharSequence> lines = font.split(Component.translatable("kohs_anchors.dev.warning.body"),
                Math.max(40, innerWidth));
        for (FormattedCharSequence line : lines) {
            if (y + 9 > bottomLimit) {
                break;
            }
            AnchorsUi.line(graphics, font, line, box.centerX() - font.width(line) / 2, y, AnchorsTheme.DEV_TEXT);
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
        String text = Component.translatable(ready ? "kohs_anchors.dev.warning.confirm" : "kohs_anchors.dev.warning.wait")
                .getString();
        AnchorsButton.draw(graphics, confirm.x(), confirm.y(), confirm.width(), confirm.height(), text, true, false,
                this.confirmHover, 0.0F, 1.0F, ready ? -1.0F : elapsed / (float) READ_NANOS);
    }

    /** The boot: class names scrolling up, a scan line coming down, then "developer mode online". */
    private void drawBoot(GuiGraphicsExtractor graphics, Font font, int width, int height, float t) {
        int lineHeight = 10;
        int visible = Math.max(4, height / lineHeight);
        int shift = Math.round(t * CLASSES.length * 1.6F);
        for (int index = 0; index < visible; index++) {
            int entry = (index + shift) % CLASSES.length;
            float alpha = 0.15F + 0.35F * (index / (float) visible);
            String text = "> " + CLASSES[entry];
            AnchorsUi.label(graphics, font, text, 12, index * lineHeight + 4, AnchorsTheme.fade(0xFF9DB0FF, alpha), false);
        }
        int scanY = Math.round(height * Math.min(1.0F, t * 1.25F));
        graphics.fillGradient(0, scanY - 24, width, scanY, 0x005B6CFF, 0x805B6CFF);
        graphics.fill(0, scanY, width, scanY + 1, 0xFF9DB0FF);
        float text = AnchorsTheme.clamp01((t - 0.35F) / 0.2F);
        if (text > 0.01F) {
            String online = Component.translatable("kohs_anchors.dev.online").getString();
            AnchorsUi.bigText(graphics, font, online, width / 2 + 1, height / 2 - 9, 2.2F, AnchorsTheme.fade(0xFF1A1F6B, text),
                    false);
            AnchorsUi.bigText(graphics, font, online, width / 2, height / 2 - 10, 2.2F, AnchorsTheme.fade(0xFFF4F6FF, text),
                    false);
            String credit = Component.translatable("kohs_anchors.dev.credit").getString();
            AnchorsUi.label(graphics, font, credit, width / 2 - font.width(credit) / 2, height / 2 + 14,
                    AnchorsTheme.fade(0xFFA855F7, text), true);
        }
    }
}
