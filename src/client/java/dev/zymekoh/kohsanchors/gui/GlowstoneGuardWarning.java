package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.compat.AnchorBlockPreview;
import dev.zymekoh.kohsanchors.video.LoopingClip;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.Blocks;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The warning before the glowstone guard is switched on: it rules out the safe anchor, so the
 * player sees one first.
 *
 * <p>Purple, not crimson: nothing here risks a ban, it only takes a move away. In the middle plays
 * a five-second clip of safe anchors from a real fight, at its own 60 frames a second
 * ({@link ClipView}); near the end of every loop a crimson line strikes its "safe anchor" label
 * through, which is what the option will do. "Turn it on" fills for {@link #READ_NANOS} before it
 * answers.</p>
 *
 * <p>After it: a short charge. The clip switches off like an old screen, an anchor runs across a
 * streaking track with a glowing glowstone gaining on it while the bar fills; the glowstone
 * catches it and charges it four times, the anchor explodes, and "activated" slams in, with the
 * respawn anchor's own sounds. A click or Escape skips it. With interface animations off it is a
 * bar, four charges and the word.</p>
 */
final class GlowstoneGuardWarning {
    private static final long OPEN_NANOS = 300_000_000L;
    private static final long READ_NANOS = 1_500_000_000L;
    private static final long CLOSE_NANOS = 220_000_000L;
    /** Frames in one loop of the clip: five seconds at 60 frames a second. */
    private static final int LOOP_FRAMES = 300;
    private static final long SCAN_NANOS = 380_000_000L;
    /** Denser than the settings' glass: the clip should not show the options through the box. */
    private static final int GLASS_TOP = 0xF2160B27;
    private static final int GLASS_BOTTOM = 0xF00B0514;

    // The charge, in seconds after "Turn it on".
    private static final float COLLAPSE_END = 0.28F;
    private static final float CHASE_END = 1.72F;
    private static final float CATCH_END = CHASE_END + 0.10F;
    private static final float CHARGE_STEP = 0.13F;
    private static final float CHARGED = CATCH_END + 4.0F * CHARGE_STEP;
    private static final float BLAST = CHARGED + 0.14F;
    private static final float ACTIVATED = BLAST + 0.34F;
    private static final float END = ACTIVATED + 1.40F;
    // The same without animations.
    private static final float REDUCED_BAR_END = 0.45F;
    private static final float REDUCED_STEP = 0.11F;
    private static final float REDUCED_END = REDUCED_BAR_END + 4.0F * REDUCED_STEP + 1.1F;

    private enum Phase { WARNING, CHARGE, CLOSING, DONE }

    private final boolean motion;
    private final Runnable onConfirm;
    private final String eyebrow;
    private final String title;
    private final String badge;
    private final ClipView clip = new ClipView(LoopingClip.SAFE_ANCHOR);
    private final AnchorBlockPreview anchor = new AnchorBlockPreview();
    private final AnchorBlockPreview glowstone = new AnchorBlockPreview();
    private final Quaternionf rotation = new Quaternionf();
    private final Quaternionf camera = new Quaternionf();
    private final Vector3f translation = new Vector3f();
    private final long openedAt = System.nanoTime();

    private Phase phase = Phase.WARNING;
    private long phaseStartedAt = this.openedAt;
    private boolean confirmed;
    private float cancelHover;
    private float confirmHover;
    private long lastFrame = this.openedAt;
    private int sounds;
    private long shownLoop = -1L;
    private long loopStartedAt = this.openedAt;

    // The text wrapped for the last screen size.
    private int laidWidth = -1;
    private int laidHeight = -1;
    private AnchorsLayout.GuardModal layout;
    private List<FormattedCharSequence> body = List.of();

    GlowstoneGuardWarning(boolean motion, Runnable onConfirm) {
        this.motion = motion;
        this.onConfirm = onConfirm;
        this.eyebrow = Component.translatable("kohs_anchors.option.glowstone_guard").getString().toUpperCase(Locale.ROOT);
        this.title = Component.translatable("kohs_anchors.glowstone_warning.title").getString().toUpperCase(Locale.ROOT);
        this.badge = Component.translatable("kohs_anchors.glowstone_warning.clip").getString().toUpperCase(Locale.ROOT);
        this.clip.start();
    }

    boolean done() {
        return this.phase == Phase.DONE;
    }

    /** Frees the clip; the screen calls it when it closes under the warning. */
    void close() {
        this.clip.close();
        this.phase = Phase.DONE;
    }

    private boolean ready(long now) {
        return this.phase == Phase.WARNING && now - this.openedAt >= READ_NANOS;
    }

    private AnchorsLayout.GuardModal layout(Font font, int width, int height) {
        if (width != this.laidWidth || height != this.laidHeight || this.layout == null) {
            this.laidWidth = width;
            this.laidHeight = height;
            // The text's width depends only on the box, so a first fit gives the line count.
            float aspect = this.clip.aspect();
            AnchorsLayout.GuardModal first = AnchorsLayout.guardModal(width, height, 3, aspect);
            this.body = font.split(Component.translatable("kohs_anchors.glowstone_warning.body"),
                    Math.max(40, first.body().width()));
            this.layout = AnchorsLayout.guardModal(width, height, this.body.size(), aspect);
        }
        return this.layout;
    }

    // ------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------

    boolean mouseClicked(Font font, int width, int height, double mouseX, double mouseY, int button) {
        long now = System.nanoTime();
        if (this.phase == Phase.CHARGE) {
            // A click only hurries the charge to its end; the guard is already on.
            close(now);
            return true;
        }
        if (this.phase != Phase.WARNING || button != 0) {
            return true;
        }
        AnchorsLayout.GuardModal modal = layout(font, width, height);
        if (modal.cancel().contains(mouseX, mouseY)) {
            cancel(now);
        } else if (modal.confirm().contains(mouseX, mouseY) && ready(now)) {
            confirm(now);
        }
        return true;
    }

    /** Escape cancels (or skips the charge); Enter turns it on once the warning has been read. */
    boolean keyPressed(int key) {
        long now = System.nanoTime();
        if (key == 256) {
            if (this.phase == Phase.WARNING) {
                cancel(now);
            } else if (this.phase == Phase.CHARGE) {
                close(now);
            }
        } else if ((key == 257 || key == 335) && ready(now)) {
            confirm(now);
        }
        return true;
    }

    private void cancel(long now) {
        this.phase = Phase.CLOSING;
        this.phaseStartedAt = now;
        this.clip.stop();
        play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 0.8F));
    }

    private void confirm(long now) {
        this.confirmed = true;
        this.onConfirm.run();
        this.phase = Phase.CHARGE;
        this.phaseStartedAt = now;
        this.sounds = 0;
        play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.1F));
    }

    private void close(long now) {
        if (this.phase != Phase.CLOSING && this.phase != Phase.DONE) {
            this.phase = Phase.CLOSING;
            this.phaseStartedAt = now;
            this.clip.stop();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------

    void render(GuiGraphicsExtractor graphics, Font font, int width, int height, int mouseX, int mouseY) {
        long now = System.nanoTime();
        float frameMillis = Math.min(50.0F, (now - this.lastFrame) / 1_000_000.0F);
        this.lastFrame = now;
        float response = 1.0F - (float) Math.exp(-frameMillis / 60.0F);
        double seconds = now / 1_000_000_000.0D;
        AnchorsLayout.GuardModal modal = layout(font, width, height);

        switch (this.phase) {
            case WARNING -> {
                this.clip.update(now);
                float open = this.motion ? AnchorsTheme.easeOutCubic((now - this.openedAt) / (float) OPEN_NANOS) : 1.0F;
                drawBackdrop(graphics, width, height, open, seconds);
                drawBox(graphics, modal, open, seconds, 0.0F);
                if (open >= 0.98F) {
                    drawWarning(graphics, font, modal, mouseX, mouseY, now, seconds, response, 1.0F);
                }
            }
            case CHARGE -> {
                float t = (now - this.phaseStartedAt) / 1_000_000_000.0F;
                drawBackdrop(graphics, width, height, 1.0F, seconds);
                if (this.motion) {
                    drawCharge(graphics, font, modal, t, now, seconds);
                } else {
                    drawReducedCharge(graphics, font, modal, t);
                }
                if (t >= (this.motion ? END : REDUCED_END)) {
                    close(now);
                }
            }
            case CLOSING -> {
                float t = Math.min(1.0F, (now - this.phaseStartedAt) / (float) CLOSE_NANOS);
                float fade = 1.0F - AnchorsTheme.easeInCubic(t);
                drawBackdrop(graphics, width, height, fade, seconds);
                drawBox(graphics, modal, fade, seconds, 0.0F);
                if (t >= 1.0F) {
                    this.clip.close();
                    this.phase = Phase.DONE;
                }
            }
            case DONE -> {
            }
        }
    }

    /** The veil under the warning: dark, a slow violet tide from the edges, drifting motes. */
    private void drawBackdrop(GuiGraphicsExtractor graphics, int width, int height, float strength, double seconds) {
        if (strength <= 0.01F) {
            return;
        }
        graphics.fill(0, 0, width, height, AnchorsTheme.withAlpha(0x06020A, Math.round(196 * strength)));
        float tide = this.motion ? 0.55F + 0.45F * AnchorsTheme.pulse(seconds, 2.6D) : 0.7F;
        int depth = Math.max(8, height / 4);
        graphics.fillGradient(0, 0, width, depth, AnchorsTheme.withAlpha(0x7C3AED, Math.round(105 * tide * strength)), 0);
        graphics.fillGradient(0, height - depth, width, height, 0,
                AnchorsTheme.withAlpha(0x9333EA, Math.round(115 * tide * strength)));
        if (this.motion) {
            AnchorsUi.motes(graphics, width, height, seconds, 0.6F * strength);
        }
    }

    /**
     * The glass box, grown in by {@code open}; a sigil turns behind it, faster and brighter as
     * {@code charge} (0 to 4) rises.
     */
    private void drawBox(GuiGraphicsExtractor graphics, AnchorsLayout.GuardModal modal, float open, double seconds,
            float charge) {
        AnchorsLayout.Rect box = modal.box();
        if (open <= 0.01F) {
            return;
        }
        if (this.motion) {
            AnchorsUi.sigil(graphics, box.centerX(), box.centerY(), Math.round(Math.max(box.width(), box.height()) * 0.62F),
                    seconds * (0.8D + charge * 0.5D), AnchorsTheme.ACCENT_DEEP, (0.45F + charge * 0.1F) * open, charge);
            if (open < 1.0F) {
                // An energy ring bursting out of the box as it opens.
                int radius = Math.round(Math.max(box.width(), box.height()) * (0.3F + open * 0.55F));
                AnchorsUi.ring(graphics, box.centerX(), box.centerY(), radius, 2,
                        AnchorsTheme.withAlpha(0xC084FC, Math.round(180 * (1.0F - open))));
            }
        }
        float grow = 0.92F + 0.08F * open;
        int width = Math.round(box.width() * grow);
        int height = Math.round(box.height() * grow);
        int x = box.centerX() - width / 2;
        int y = box.centerY() - height / 2;
        AnchorsUi.halo(graphics, x, y, width, height, AnchorsTheme.ACCENT, 6, 0.85F * open);
        AnchorsUi.panel(graphics, x, y, width, height, AnchorsTheme.fade(GLASS_TOP, open), AnchorsTheme.fade(GLASS_BOTTOM, open));
        AnchorsUi.roundedOutline(graphics, x, y, width, height, AnchorsTheme.fade(AnchorsTheme.PANEL_BORDER, open));
        AnchorsUi.bladeCorners(graphics, x, y, width, height, 7, AnchorsTheme.fade(0xE0E9D5FF, open));
        if (this.motion && open >= 0.98F) {
            AnchorsUi.comets(graphics, box.x(), box.y(), box.width(), box.height(), seconds, 0xE8CCFF);
        }
        DevInspector.node("GlowstoneGuardWarning", "box", box.x(), box.y(), box.width(), box.height(),
                "AnchorsLayout.guardModal", "phase " + this.phase);
    }

    private void drawWarning(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.GuardModal modal, int mouseX,
            int mouseY, long now, double seconds, float response, float alpha) {
        drawTitle(graphics, font, modal, seconds, alpha);
        drawClip(graphics, font, modal.clip(), now, seconds, alpha, 1.0F);
        drawBody(graphics, font, modal, alpha);

        long elapsed = now - this.openedAt;
        boolean ready = elapsed >= READ_NANOS;
        AnchorsLayout.Rect cancel = modal.cancel();
        AnchorsLayout.Rect confirm = modal.confirm();
        this.cancelHover += ((cancel.contains(mouseX, mouseY) ? 1.0F : 0.0F) - this.cancelHover) * response;
        this.confirmHover += ((confirm.contains(mouseX, mouseY) && ready ? 1.0F : 0.0F) - this.confirmHover) * response;
        AnchorsButton.draw(graphics, cancel.x(), cancel.y(), cancel.width(), cancel.height(),
                Component.translatable("kohs_anchors.glowstone_warning.cancel").getString(), false, false,
                this.cancelHover, 0.0F, alpha, -1.0F);
        String confirmText = ready ? Component.translatable("kohs_anchors.glowstone_warning.accept").getString()
                : Component.translatable("kohs_anchors.warning.wait",
                        (int) Math.ceil((READ_NANOS - elapsed) / 1_000_000_000.0D)).getString();
        AnchorsButton.draw(graphics, confirm.x(), confirm.y(), confirm.width(), confirm.height(), confirmText, true, false,
                this.confirmHover, 0.0F, alpha, ready ? -1.0F : elapsed / (float) READ_NANOS);
        DevInspector.node("AnchorsButton", "keep safe anchor", cancel.x(), cancel.y(), cancel.width(), cancel.height(),
                "Escape");
        DevInspector.node("AnchorsButton", "turn it on", confirm.x(), confirm.y(), confirm.width(), confirm.height(),
                "Settings.glowstoneGuard = true", "fills for " + READ_NANOS / 1_000_000 + " ms");
    }

    /** "◆ GLOWSTONE GUARD" over the title, which is as large as the room allows. */
    private void drawTitle(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.GuardModal modal, double seconds,
            float alpha) {
        AnchorsLayout.Rect area = modal.title();
        int centerX = area.centerX();
        String eyebrow = AnchorsUi.fit(font, this.eyebrow, area.width() - 12);
        int eyebrowWidth = font.width(eyebrow) + 8;
        int eyebrowX = centerX - eyebrowWidth / 2;
        AnchorsUi.diamond(graphics, eyebrowX + 2, area.y() + 3, 2, AnchorsTheme.fade(AnchorsTheme.ACCENT, alpha));
        AnchorsUi.label(graphics, font, eyebrow, eyebrowX + 8, area.y(), AnchorsTheme.fade(AnchorsTheme.SECTION, alpha), false);
        int titleY = area.y() + 11;
        float scale = Math.min(2.0F, Math.max(1.0F, (area.height() - 11) / 9.0F));
        scale = Math.min(scale, Math.max(0.75F, area.width() / (float) Math.max(1, font.width(this.title))));
        AnchorsUi.bigText(graphics, font, this.title, centerX + 1, titleY + 1, scale, AnchorsTheme.fade(0xFF5B1FB0, alpha),
                false);
        AnchorsUi.bigText(graphics, font, this.title, centerX, titleY, scale, AnchorsTheme.fade(AnchorsTheme.TITLE, alpha),
                false);
        if (this.motion && alpha >= 0.98F && scale == Math.round(scale)) {
            int textWidth = Math.round(font.width(this.title) * scale);
            graphics.pose().pushMatrix();
            graphics.pose().translate(centerX - textWidth / 2.0F, titleY);
            graphics.pose().scale(scale, scale);
            AnchorsUi.glint(graphics, font, this.title, 0, 0, seconds);
            graphics.pose().popMatrix();
        }
        DevInspector.node("Title", this.title, area.x(), area.y(), area.width(), area.height(),
                "kohs_anchors.glowstone_warning.title");
    }

    private void drawBody(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.GuardModal modal, float alpha) {
        AnchorsLayout.Rect area = modal.body();
        int y = area.y();
        for (FormattedCharSequence line : this.body) {
            if (y + 9 > area.bottom()) {
                break;
            }
            AnchorsUi.line(graphics, font, line, area.centerX() - font.width(line) / 2, y,
                    AnchorsTheme.fade(AnchorsTheme.TEXT, alpha));
            y += 10;
        }
        DevInspector.node("Text", "body", area.x(), area.y(), area.width(), area.height(),
                "kohs_anchors.glowstone_warning.body", this.body.size() + " lines");
    }

    /**
     * The clip in its frame: violet edge and clasps, a dark vignette, a scan sweeping down each
     * time it starts over, and the "safe anchor" label that a crimson line strikes through near the
     * end of every loop. {@code squash} below 1 closes it like an old screen switching off.
     */
    private void drawClip(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.Rect area, long now, double seconds,
            float alpha, float squash) {
        if (area.width() <= 0 || alpha <= 0.01F) {
            return;
        }
        int height = Math.max(1, Math.round(area.height() * squash));
        int x = area.x();
        int y = area.centerY() - height / 2;
        int width = area.width();
        if (squash < 1.0F) {
            // The picture folds into a bright line that then fades.
            float line = AnchorsTheme.clamp01(1.0F - squash * 3.0F);
            if (height > 2) {
                this.clip.draw(graphics, x, y, width, height, alpha, 0xFFFFFF);
                graphics.fill(x, y, x + width, y + height, AnchorsTheme.withAlpha(0xE9D5FF, Math.round(150 * (1.0F - squash) * alpha)));
            }
            int glow = Math.round(255 * alpha * (0.4F + 0.6F * (1.0F - line)));
            int shrink = Math.round(width * 0.5F * line);
            graphics.fill(x + shrink, area.centerY() - 1, x + width - shrink, area.centerY() + 1,
                    AnchorsTheme.withAlpha(0xFFF7FF, glow));
            AnchorsUi.halo(graphics, x + shrink, area.centerY() - 1, Math.max(1, width - shrink * 2), 2, AnchorsTheme.ACCENT, 3,
                    alpha * (1.0F - line));
            return;
        }

        AnchorsUi.halo(graphics, x - 1, y - 1, width + 2, height + 2, AnchorsTheme.ACCENT, 3, 0.7F * alpha);
        graphics.fill(x, y, x + width, y + height, AnchorsTheme.fade(0xFF0B0514, alpha));
        if (this.clip.hasPicture()) {
            this.clip.draw(graphics, x, y, width, height, alpha, 0xFFFFFF);
            long loop = this.clip.loops(LOOP_FRAMES);
            if (loop != this.shownLoop) {
                this.shownLoop = loop;
                this.loopStartedAt = now;
            }
        } else if (this.clip.failed()) {
            String text = AnchorsUi.fit(font, Component.translatable("kohs_anchors.glowstone_warning.no_video").getString(),
                    width - 8);
            AnchorsUi.label(graphics, font, text, area.centerX() - font.width(text) / 2, area.centerY() - 4,
                    AnchorsTheme.fade(AnchorsTheme.TEXT_DIM, alpha), false);
        } else {
            spinner(graphics, area.centerX(), area.centerY(), Math.max(6, Math.min(14, height / 6)), seconds, alpha);
        }
        // A soft vignette at the top and the bottom, so the picture sinks into the glass.
        int band = Math.max(4, height / 7);
        graphics.fillGradient(x, y, x + width, y + band, AnchorsTheme.withAlpha(0x0B0514, Math.round(150 * alpha)), 0);
        graphics.fillGradient(x, y + height - band, x + width, y + height, 0,
                AnchorsTheme.withAlpha(0x0B0514, Math.round(170 * alpha)));
        if (this.motion && this.clip.hasPicture()) {
            float scan = (now - this.loopStartedAt) / (float) SCAN_NANOS;
            if (scan >= 0.0F && scan < 1.0F) {
                int scanY = y + Math.round(height * AnchorsTheme.easeOutCubic(scan));
                int fade = Math.round(150 * (1.0F - scan) * alpha);
                graphics.fillGradient(x, Math.max(y, scanY - 14), x + width, scanY, 0, AnchorsTheme.withAlpha(0xC084FC, fade));
                graphics.fill(x, scanY, x + width, Math.min(y + height, scanY + 1), AnchorsTheme.withAlpha(0xFFF7FF, fade));
            }
        }
        AnchorsUi.outline(graphics, x - 1, y - 1, width + 2, height + 2, AnchorsTheme.fade(0xC0A855F7, alpha));
        AnchorsUi.bladeCorners(graphics, x - 1, y - 1, width + 2, height + 2, 6, AnchorsTheme.fade(0xF0E9D5FF, alpha));
        drawBadge(graphics, font, x + 5, y + 5, width - 10, alpha);
        if (width >= 200) {
            String rate = this.clip.hasPicture() ? LoopingClip.SAFE_ANCHOR.frameRate() + " FPS" : "";
            AnchorsUi.label(graphics, font, rate, x + width - 5 - font.width(rate), y + height - 12,
                    AnchorsTheme.fade(0xB0BDB8CF, alpha), false);
        }
        DevInspector.node("ClipView", "safe anchor clip", x, y, width, height, "LoopingClip.SAFE_ANCHOR (JCodec, H.264)",
                "frame " + (this.clip.hasPicture() ? Math.round(this.clip.loopProgress(LOOP_FRAMES) * LOOP_FRAMES) : -1)
                        + " / " + LOOP_FRAMES, "VideoTexture, linear filter");
    }

    /** "▶ SAFE ANCHOR", struck through in crimson over the last fifth of every loop. */
    private void drawBadge(GuiGraphicsExtractor graphics, Font font, int x, int y, int room, float alpha) {
        String text = AnchorsUi.fit(font, this.badge, Math.max(0, room - 18));
        if (text.isEmpty() || room < 60) {
            return;
        }
        int textWidth = font.width(text);
        int width = textWidth + 17;
        float progress = this.clip.loopProgress(LOOP_FRAMES);
        float strike = this.clip.hasPicture() ? AnchorsTheme.easeOutCubic((progress - 0.78F) / 0.1F) : 0.0F;
        AnchorsUi.panel(graphics, x, y, width, 13, AnchorsTheme.fade(0xC0180A28, alpha), AnchorsTheme.fade(0xC00C0514, alpha));
        AnchorsUi.roundedOutline(graphics, x, y, width, 13,
                AnchorsTheme.fade(AnchorsTheme.lerp(0xE0C084FC, AnchorsTheme.CRIMSON_BRIGHT, strike), alpha));
        // A small play triangle.
        int glyph = AnchorsTheme.fade(AnchorsTheme.lerp(AnchorsTheme.ACCENT_BRIGHT, 0xFFFF6A86, strike), alpha);
        for (int row = 0; row < 5; row++) {
            int span = row < 3 ? row : 4 - row;
            graphics.fill(x + 5, y + 4 + row, x + 6 + span, y + 5 + row, glyph);
        }
        AnchorsUi.label(graphics, font, text, x + 11, y + 3,
                AnchorsTheme.fade(AnchorsTheme.lerp(AnchorsTheme.TEXT, 0xFFFFD6DE, strike), alpha), false);
        if (strike > 0.0F) {
            int length = Math.round((textWidth + 4) * strike);
            graphics.fill(x + 9, y + 6, x + 9 + length, y + 8, AnchorsTheme.fade(AnchorsTheme.CRIMSON_BRIGHT, alpha));
        }
    }

    /** Twelve dots around a circle, the brightest one turning: the clip is loading. */
    private void spinner(GuiGraphicsExtractor graphics, int centerX, int centerY, int radius, double seconds, float alpha) {
        int dots = 12;
        double head = this.motion ? seconds * 1.4D % 1.0D : 0.0D;
        for (int dot = 0; dot < dots; dot++) {
            double angle = dot * Math.PI * 2.0D / dots - Math.PI / 2.0D;
            double behind = (head * dots - dot + dots) % dots / dots;
            int px = centerX + (int) Math.round(Math.cos(angle) * radius);
            int py = centerY + (int) Math.round(Math.sin(angle) * radius);
            int color = AnchorsTheme.withAlpha(0xC084FC, Math.round(255 * alpha * (0.2F + 0.8F * (float) (1.0D - behind))));
            graphics.fill(px - 1, py - 1, px + 1, py + 1, color);
        }
    }

    // ------------------------------------------------------------------------------------------
    // The charge
    // ------------------------------------------------------------------------------------------

    private void drawCharge(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.GuardModal modal, float t, long now,
            double seconds) {
        int charges = t < CATCH_END ? 0 : Math.min(4, (int) ((t - CATCH_END) / CHARGE_STEP) + 1);
        boolean exploded = t >= BLAST;
        cues(t);
        // The detonation jolts the whole box for a moment.
        float jolt = exploded && t < BLAST + 0.18F ? 1.0F - (t - BLAST) / 0.18F : 0.0F;
        int shakeX = Math.round((float) Math.sin(now / 6_500_000.0D) * 5.0F * jolt);
        int shakeY = Math.round((float) Math.cos(now / 8_000_000.0D) * 3.0F * jolt);
        graphics.pose().pushMatrix();
        graphics.pose().translate(shakeX, shakeY);
        drawStage(graphics, font, modal, t, now, seconds, charges, exploded);
        graphics.pose().popMatrix();
    }

    private void drawStage(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.GuardModal modal, float t, long now,
            double seconds, int charges, boolean exploded) {
        drawBox(graphics, modal, 1.0F, seconds, exploded ? 0.0F : charges);

        AnchorsLayout.Rect box = modal.box();
        int padding = modal.padding();
        // The stage: everything between the title and the bar.
        int stageX = box.x() + padding;
        int stageWidth = box.width() - padding * 2;
        int stageY = modal.title().y();
        int stageBottom = modal.cancel().y() - 4;
        int stageHeight = Math.max(20, stageBottom - stageY);

        // The warning folds away first.
        if (t < COLLAPSE_END) {
            float fold = t / COLLAPSE_END;
            float alpha = 1.0F - AnchorsTheme.easeOutCubic(fold);
            drawTitle(graphics, font, modal, seconds, alpha);
            drawBody(graphics, font, modal, alpha);
            if (modal.clip().width() > 0) {
                this.clip.update(now);
                drawClip(graphics, font, modal.clip(), now, seconds, 1.0F, 1.0F - AnchorsTheme.easeInCubic(fold));
            }
        } else {
            this.clip.stop();
        }

        float chase = AnchorsTheme.clamp01((t - COLLAPSE_END) / (CHASE_END - COLLAPSE_END));
        float appear = AnchorsTheme.clamp01((t - COLLAPSE_END * 0.6F) / 0.2F);
        int floorY = stageY + Math.round(stageHeight * 0.74F);
        int size = Math.max(16, Math.min(96, Math.round(Math.min(stageHeight * 0.36F, stageWidth * 0.16F))));
        graphics.enableScissor(stageX, stageY, stageX + stageWidth, stageBottom);
        if (appear > 0.0F && t < ACTIVATED + 0.2F) {
            float speed = t < CHASE_END ? 1.0F : Math.max(0.0F, 1.0F - (t - CHASE_END) / 0.35F);
            drawTrack(graphics, stageX, stageWidth, stageY, floorY, seconds, appear * (exploded ? 0.5F : 1.0F), speed,
                    charges);
        }

        // The anchor runs until the glowstone catches it, then stands and shakes as it fills.
        int anchorX = stageX + Math.round(stageWidth * 0.66F);
        int anchorY = floorY - Math.round(size * 0.62F);
        if (t < CHASE_END) {
            anchorX += Math.round((float) Math.sin(seconds * 11.0D) * 1.5F);
            anchorY -= Math.round(Math.abs((float) Math.sin(seconds * 13.0D)) * size * 0.1F);
        } else if (!exploded) {
            float tremble = Math.min(1.0F, (t - CHASE_END) / (BLAST - CHASE_END));
            anchorX += Math.round((float) Math.sin(now / 9_000_000.0D) * tremble * 2.5F);
        }
        // The glowstone gains on it, faster and faster, and catches it.
        float gain = AnchorsTheme.easeInCubic(chase);
        int startX = stageX + Math.round(stageWidth * 0.12F);
        int caughtX = anchorX - Math.round(size * 0.82F);
        int stoneX = Math.round(startX + (caughtX - startX) * gain);
        int stoneY = floorY - Math.round(size * 0.52F) - Math.round(Math.abs((float) Math.sin(seconds * 10.0D)) * size * 0.16F);
        float stoneSize = size * 0.5F * (t < CATCH_END ? 1.0F : Math.max(0.0F, 1.0F - (t - CATCH_END) / (CHARGED - CATCH_END)));

        if (!exploded && appear > 0.0F) {
            if (t < CHASE_END) {
                speedTrail(graphics, anchorX - Math.round(size * 0.6F), anchorY, size, seconds, appear, 0xC084FC);
                speedTrail(graphics, stoneX - Math.round(size * 0.45F), stoneY, Math.round(size * 0.7F), seconds + 0.37D,
                        appear, 0xFFC46B);
            }
            if (charges > 0) {
                AnchorsUi.sigil(graphics, anchorX, anchorY + size / 5, Math.round(size * 1.15F), seconds * 2.5D,
                        AnchorsTheme.ACCENT, 0.85F * appear, charges);
            }
            float light = 0.42F + 0.12F * charges;
            AnchorsUi.glowEllipse(graphics, anchorX, anchorY, Math.round(size * 1.05F), Math.round(size * 0.95F), 0x9B4DFF,
                    light * appear);
            drawBlock(graphics, this.anchor.withCharge(charges), anchorX, anchorY, size * 0.74F,
                    225.0F + (float) (seconds * (t < CHASE_END ? 160.0D : 40.0D)), -24.0F);
            if (stoneSize > 1.5F) {
                AnchorsUi.glowEllipse(graphics, stoneX, stoneY, Math.round(stoneSize * 1.9F), Math.round(stoneSize * 1.7F),
                        0xFFC46B, (0.55F + 0.15F * (float) Math.sin(seconds * 9.0D)) * appear);
                drawBlock(graphics, this.glowstone.withState(Blocks.GLOWSTONE.defaultBlockState()), stoneX, stoneY, stoneSize,
                        (float) (seconds * -300.0D), -30.0F);
            }
            // Each charge sends a ring out of the anchor.
            for (int charge = 1; charge <= charges; charge++) {
                float ring = (t - (CATCH_END + (charge - 1) * CHARGE_STEP)) / 0.3F;
                if (ring >= 0.0F && ring < 1.0F) {
                    AnchorsUi.ring(graphics, anchorX, anchorY, Math.round(size * (0.4F + ring * 0.9F)), 1,
                            AnchorsTheme.withAlpha(charge == 4 ? 0xFFF7FF : 0xC084FC, Math.round(220 * (1.0F - ring))));
                }
            }
            if (t >= CHASE_END && t < CATCH_END + 0.25F) {
                // The catch: sparks where the two meet.
                float burst = (t - CHASE_END) / 0.35F;
                sparks(graphics, anchorX - Math.round(size * 0.45F), anchorY, size, burst, 0xFFC46B, 12);
            }
        }
        if (exploded) {
            drawBlast(graphics, anchorX, anchorY, size, stageWidth, t - BLAST);
        }
        graphics.disableScissor();

        if (t >= ACTIVATED) {
            drawActivated(graphics, font, box, (t - ACTIVATED), END - ACTIVATED);
        } else if (appear > 0.0F) {
            drawBar(graphics, font, modal, t < CHASE_END ? chase : 1.0F, appear, seconds);
        }
    }

    /**
     * The track: a ritual circle turning behind everything, obsidian pillars drifting past slowly,
     * lanes of light streaming past fast, and the floor line with runes running under it. Its
     * speed eases to nothing once the glowstone catches the anchor.
     */
    private static void drawTrack(GuiGraphicsExtractor graphics, int x, int width, int top, int floorY, double seconds,
            float alpha, float speed, int charges) {
        double travel = seconds * 420.0D * speed;
        int centerX = x + width / 2;
        int radius = Math.round(Math.min(width * 0.36F, (floorY - top) * 1.1F));
        AnchorsUi.sigil(graphics, centerX, floorY - radius / 3, radius, seconds * 0.6D, AnchorsTheme.ACCENT_DEEP,
                0.4F * alpha, charges);

        // Far pillars, a third of the lanes' speed: depth.
        int span = floorY - top;
        for (int pillar = 0; pillar < 6; pillar++) {
            int pillarWidth = 10 + pillar * 7 % 12;
            int pillarHeight = Math.round(span * (0.28F + (pillar * 37 % 45) / 100.0F));
            int pillarX = x + width - (int) ((travel * 0.3D + pillar * 211.0D) % (width + pillarWidth * 2)) + pillarWidth;
            int pillarTop = floorY - pillarHeight;
            graphics.fillGradient(pillarX, pillarTop, pillarX + pillarWidth, floorY,
                    AnchorsTheme.withAlpha(0x1D0D32, Math.round(170 * alpha)), AnchorsTheme.withAlpha(0x2A1248, Math.round(200 * alpha)));
            graphics.fill(pillarX, pillarTop, pillarX + pillarWidth, pillarTop + 1,
                    AnchorsTheme.withAlpha(0xA855F7, Math.round(120 * alpha)));
            graphics.fill(pillarX + pillarWidth - 1, pillarTop, pillarX + pillarWidth, floorY,
                    AnchorsTheme.withAlpha(0x7C3AED, Math.round(70 * alpha)));
        }

        // Lanes of light: thin ones, and now and then a thick bright one.
        for (int lane = 0; lane < 26; lane++) {
            int laneY = top + 6 + (floorY - top - 12) * lane / 26 + (lane * 7) % 5;
            int length = 14 + lane * 37 % 56;
            double laneSpeed = 0.55D + (lane * 13 % 7) * 0.14D;
            int head = x + width - (int) ((travel * laneSpeed + lane * 97.0D) % (width + length));
            boolean bold = lane % 9 == 4;
            int color = bold ? 0xFFF7FF : lane % 3 == 0 ? 0xE9D5FF : lane % 3 == 1 ? 0xC084FC : 0x7C3AED;
            int strength = Math.round((bold ? 120 : 45 + lane % 4 * 20) * alpha * Math.max(0.15F, speed));
            graphics.fill(head, laneY, head + length, laneY + (bold ? 2 : 1), AnchorsTheme.withAlpha(color, strength));
        }

        // The floor: a violet glow under the line and runes running by.
        graphics.fillGradient(x, floorY + 1, x + width, floorY + 22, AnchorsTheme.withAlpha(0x7C3AED, Math.round(60 * alpha)), 0);
        AnchorsUi.energyLine(graphics, x, x + width, floorY, AnchorsTheme.ACCENT_BRIGHT, seconds, alpha);
        int tick = 22;
        int offset = (int) (travel % tick);
        for (int runeX = x - offset + tick; runeX < x + width; runeX += tick) {
            graphics.fill(runeX, floorY + 3, runeX + 5, floorY + 4, AnchorsTheme.withAlpha(0xA855F7, Math.round(120 * alpha)));
            graphics.fill(runeX + 2, floorY + 5, runeX + 3, floorY + 7, AnchorsTheme.withAlpha(0xA855F7, Math.round(90 * alpha)));
        }
    }

    /** Streaks trailing behind something moving to the right. */
    private static void speedTrail(GuiGraphicsExtractor graphics, int tailX, int centerY, int size, double seconds, float alpha,
            int color) {
        for (int streak = 0; streak < 5; streak++) {
            int streakY = centerY - size / 3 + streak * size / 6;
            int length = Math.round(size * (0.9F + 0.6F * (float) Math.abs(Math.sin(seconds * 7.0D + streak * 1.7D))));
            for (int step = 0; step < 4; step++) {
                int from = tailX - length + length * step / 4;
                int to = tailX - length + length * (step + 1) / 4;
                graphics.fill(from, streakY, to, streakY + 1, AnchorsTheme.withAlpha(color, Math.round(alpha * 45 * (step + 1))));
            }
        }
    }

    private static void sparks(GuiGraphicsExtractor graphics, int centerX, int centerY, int size, float progress, int color,
            int count) {
        if (progress < 0.0F || progress >= 1.0F) {
            return;
        }
        float spread = AnchorsTheme.easeOutCubic(progress);
        for (int spark = 0; spark < count; spark++) {
            double angle = spark * (Math.PI * 2.0D / count) + spark * 0.53D;
            double distance = size * (0.15D + spread * (0.55D + spark % 3 * 0.15D));
            int sx = centerX + (int) Math.round(Math.cos(angle) * distance);
            int sy = centerY + (int) Math.round(Math.sin(angle) * distance * 0.8D);
            int dot = spark % 4 == 0 ? 3 : 2;
            graphics.fill(sx, sy, sx + dot, sy + dot, AnchorsTheme.withAlpha(color, Math.round(235 * (1.0F - progress))));
        }
    }

    /** The detonation: a short local flash, three shockwaves, sparks and falling debris. */
    private static void drawBlast(GuiGraphicsExtractor graphics, int centerX, int centerY, int size, int stageWidth,
            float since) {
        float wave = AnchorsTheme.clamp01(since / 0.55F);
        if (wave >= 1.0F) {
            return;
        }
        float fadeOut = 1.0F - wave;
        if (since < 0.09F) {
            AnchorsUi.glowEllipse(graphics, centerX, centerY, Math.round(size * 1.7F), Math.round(size * 1.3F), 0xFFF7FF,
                    (1.0F - since / 0.09F) * 0.95F);
        }
        int reach = Math.max(size * 2, stageWidth / 3);
        AnchorsUi.ring(graphics, centerX, centerY, Math.round(size * 0.3F + wave * reach), 2,
                AnchorsTheme.withAlpha(0xFF315C, Math.round(210 * fadeOut)));
        AnchorsUi.ring(graphics, centerX, centerY, Math.round(size * 0.2F + wave * reach * 0.72F), 2,
                AnchorsTheme.withAlpha(0xC084FC, Math.round(220 * fadeOut)));
        AnchorsUi.ring(graphics, centerX, centerY, Math.round(size * 0.1F + wave * reach * 0.45F), 1,
                AnchorsTheme.withAlpha(0xFFF7FF, Math.round(200 * fadeOut)));
        AnchorsUi.glowEllipse(graphics, centerX, centerY, Math.round(size * 1.2F), Math.round(size), 0x9B4DFF, 0.6F * fadeOut);
        for (int spark = 0; spark < 30; spark++) {
            double angle = spark * (Math.PI * 2.0D / 30.0D) + spark * 0.37D;
            double distance = size * (0.2D + AnchorsTheme.easeOutCubic(wave) * (1.1D + spark % 4 * 0.2D));
            int sx = centerX + (int) Math.round(Math.cos(angle) * distance);
            int sy = centerY + (int) Math.round(Math.sin(angle) * distance * 0.75D + wave * wave * size * 0.8D);
            int color = spark % 3 == 0 ? 0xFF315C : spark % 3 == 1 ? 0xC084FC : 0xFFF7FF;
            int dot = spark % 5 == 0 ? 3 : 2;
            graphics.fill(sx, sy, sx + dot, sy + dot, AnchorsTheme.withAlpha(color, Math.round(235 * fadeOut)));
        }
        // Obsidian chips falling out of the blast.
        for (int chip = 0; chip < 9; chip++) {
            double angle = -Math.PI * (0.15D + chip * 0.08D);
            double speed = size * (1.2D + chip % 3 * 0.35D);
            int cx = centerX + (int) Math.round(Math.cos(angle) * speed * wave * (chip % 2 == 0 ? 1 : -1));
            int cy = centerY + (int) Math.round(Math.sin(angle) * speed * wave + wave * wave * size * 2.2D);
            graphics.fill(cx, cy, cx + 3, cy + 3, AnchorsTheme.withAlpha(0x2A1440, Math.round(255 * fadeOut)));
            graphics.fill(cx, cy, cx + 1, cy + 1, AnchorsTheme.withAlpha(0x9B4DFF, Math.round(255 * fadeOut)));
        }
    }

    /** "GLOWSTONE GUARD ACTIVATED" slams in over a violet glow, with a check beside it. */
    private void drawActivated(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.Rect box, float since, float length) {
        float in = AnchorsTheme.clamp01(since / 0.22F);
        float out = AnchorsTheme.clamp01((since - (length - 0.3F)) / 0.3F);
        float alpha = in * (1.0F - out);
        if (alpha <= 0.01F) {
            return;
        }
        String text = Component.translatable("kohs_anchors.glowstone_warning.activated").getString().toUpperCase(Locale.ROOT);
        float fit = Math.max(1.0F, Math.min(2.4F, (box.width() - 30) / (float) Math.max(1, font.width(text))));
        float scale = fit * (1.3F - 0.3F * AnchorsTheme.easeOutCubic(in));
        int centerX = box.centerX();
        int centerY = box.centerY();
        AnchorsUi.glowEllipse(graphics, centerX, centerY, Math.round(box.width() * 0.42F), Math.round(box.height() * 0.28F),
                0x9B4DFF, 0.7F * alpha);
        int textY = centerY - Math.round(9 * scale / 2.0F);
        AnchorsUi.bigText(graphics, font, text, centerX + 1, textY + 1, scale, AnchorsTheme.fade(0xFF5B1FB0, alpha), false);
        AnchorsUi.bigText(graphics, font, text, centerX, textY, scale, AnchorsTheme.fade(0xFFFFF7FF, alpha), false);
        // The check: two strokes, drawn in as the text lands.
        int checkX = centerX - 8;
        int checkY = textY - 18;
        float draw = AnchorsTheme.clamp01((since - 0.1F) / 0.25F);
        int check = AnchorsTheme.fade(0xFFE9D5FF, alpha);
        if (draw > 0.0F && checkY > box.y() + 4) {
            AnchorsUi.segment(graphics, checkX, checkY + 6, checkX + 5 * Math.min(1.0F, draw * 2.0F),
                    checkY + 6 + 5 * Math.min(1.0F, draw * 2.0F), 2, check);
            if (draw > 0.5F) {
                float second = (draw - 0.5F) * 2.0F;
                AnchorsUi.segment(graphics, checkX + 5, checkY + 11, checkX + 5 + 11 * second, checkY + 11 - 11 * second, 2,
                        check);
            }
        }
        AnchorsUi.energyLine(graphics, box.x() + 20, box.right() - 20, textY + Math.round(9 * scale) + 6,
                AnchorsTheme.ACCENT, since * 2.0D, alpha);
    }

    /** "Charging…" and the bar under the stage. */
    private void drawBar(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.GuardModal modal, float progress,
            float alpha, double seconds) {
        AnchorsLayout.Rect cancel = modal.cancel();
        AnchorsLayout.Rect confirm = modal.confirm();
        int x = cancel.x();
        int width = confirm.right() - cancel.x();
        int barY = cancel.bottom() - 5;
        String label = Component.translatable("kohs_anchors.glowstone_warning.charging").getString();
        String percent = Math.round(progress * 100.0F) + "%";
        AnchorsUi.label(graphics, font, label, x, barY - 11, AnchorsTheme.fade(AnchorsTheme.SECTION, alpha), false);
        AnchorsUi.label(graphics, font, percent, x + width - font.width(percent), barY - 11,
                AnchorsTheme.fade(AnchorsTheme.ACCENT_BRIGHT, alpha), false);
        AnchorsUi.panel(graphics, x, barY, width, 5, AnchorsTheme.fade(0xC0251240, alpha), AnchorsTheme.fade(0xC0180A28, alpha));
        int filled = Math.round((width - 2) * progress);
        if (filled > 0) {
            graphics.fillGradient(x + 1, barY + 1, x + 1 + filled, barY + 4, AnchorsTheme.fade(0xFFE9D5FF, alpha),
                    AnchorsTheme.fade(0xFF9333EA, alpha));
            // A bright glint running along the filled part.
            int glint = x + 1 + (int) ((seconds * 160.0D) % Math.max(1, filled));
            graphics.fill(glint, barY + 1, Math.min(x + 1 + filled, glint + 6), barY + 4, AnchorsTheme.fade(0xFFFFFFFF, alpha * 0.8F));
            AnchorsUi.halo(graphics, x + 1, barY + 1, filled, 3, AnchorsTheme.ACCENT, 2, alpha);
        }
    }

    /** Without animations: the bar, four charges with their sounds, and the word. */
    private void drawReducedCharge(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.GuardModal modal, float t) {
        AnchorsLayout.Rect box = modal.box();
        int charges = t < REDUCED_BAR_END ? 0 : Math.min(4, (int) ((t - REDUCED_BAR_END) / REDUCED_STEP) + 1);
        drawBox(graphics, modal, 1.0F, 0.0D, charges);
        while (this.sounds < charges) {
            this.sounds++;
            play(SimpleSoundInstance.forUI(SoundEvents.RESPAWN_ANCHOR_CHARGE, 0.85F + this.sounds * 0.12F, 0.9F));
        }
        float settled = REDUCED_BAR_END + 4.0F * REDUCED_STEP;
        if (t < settled + 0.1F) {
            int size = Math.max(14, Math.min(50, box.height() / 4));
            drawBlock(graphics, this.anchor.withCharge(charges), box.centerX(), box.centerY() - size / 3, size * 0.62F, 225.0F,
                    -24.0F);
            drawBar(graphics, font, modal, AnchorsTheme.clamp01(t / REDUCED_BAR_END), 1.0F, 0.0D);
        } else {
            if (this.sounds == 4) {
                this.sounds++;
                play(SimpleSoundInstance.forUI(SoundEvents.RESPAWN_ANCHOR_SET_SPAWN, 1.2F, 0.9F));
            }
            drawActivated(graphics, font, box, t - settled, REDUCED_END - settled);
        }
    }

    /** The charge's sounds, each once, at its moment. */
    private void cues(float t) {
        if (this.sounds == 0 && t >= COLLAPSE_END * 0.5F) {
            play(SimpleSoundInstance.forUI(SoundEvents.RESPAWN_ANCHOR_AMBIENT, 1.6F, 0.9F));
            this.sounds = 1;
        }
        while (this.sounds >= 1 && this.sounds <= 4 && t >= CATCH_END + (this.sounds - 1) * CHARGE_STEP) {
            play(SimpleSoundInstance.forUI(SoundEvents.RESPAWN_ANCHOR_CHARGE, 0.85F + this.sounds * 0.12F, 1.0F));
            this.sounds++;
        }
        if (this.sounds == 5 && t >= BLAST) {
            play(SimpleSoundInstance.forUI(SoundEvents.GENERIC_EXPLODE.value(), 0.95F, 0.8F));
            this.sounds = 6;
        }
        if (this.sounds == 6 && t >= ACTIVATED) {
            play(SimpleSoundInstance.forUI(SoundEvents.RESPAWN_ANCHOR_SET_SPAWN, 1.2F, 0.9F));
            this.sounds = 7;
        }
    }

    /** A block drawn by the game's own renderer, centred on the point. */
    private void drawBlock(GuiGraphicsExtractor graphics, EntityRenderState state, int centerX, int centerY, float size, float yaw,
            float pitch) {
        if (size < 1.5F) {
            return;
        }
        this.rotation.identity().rotateZ((float) Math.PI).rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw));
        this.rotation.transform(this.translation.set(0.0F, 0.5F, 0.0F)).negate();
        int half = Math.round(size);
        graphics.entity(state, size / 1.6F, this.translation, this.rotation, this.camera.identity(), centerX - half,
                centerY - half, centerX + half, centerY + half);
    }

    private static void play(SimpleSoundInstance sound) {
        Minecraft.getInstance().getSoundManager().play(sound);
    }
}
