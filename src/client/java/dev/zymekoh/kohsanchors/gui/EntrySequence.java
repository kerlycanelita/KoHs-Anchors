package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.compat.AnchorBlockPreview;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.Blocks;

/**
 * The way into the settings.
 *
 * <p>An anchor in the middle of a dark screen charges four times, a ring of the ritual circle
 * lighting with each charge, and explodes: a flash, shockwaves, and a burst of small anchors and
 * glowstone flying out and falling. The first time (until it is accepted) two windows follow:</p>
 * <ol>
 *   <li>In capitals: KoHs Anchor's is legitimate, what that means, and that the advanced anchor
 *   options only work through its server plugin; with the legitimacy audit and the plugin's page.</li>
 *   <li>A smaller one: more optimization and more options come with the server's bridge, so talk to
 *   the server's admin or ask for it, it also fixes common errors such as an own anchor counted as an
 *   enemy's; and try "Switch to enemy Anchor's".</li>
 * </ol>
 * <p>Then a short loading ring, and the menu. A click or Escape skips the animated parts; the
 * windows wait for their buttons. Without interface animations only the windows show.</p>
 */
final class EntrySequence {
    static final String AUDIT_URL = "https://github.com/kerlycanelita/KoHs-Anchors/blob/main/docs/audits/legitimacy.md";

    private static final float CHARGE_STEP = 0.2F;
    private static final float CHARGE_START = 0.25F;
    private static final float CHARGED = CHARGE_START + 4.0F * CHARGE_STEP;
    private static final float BLAST = CHARGED + 0.12F;
    private static final float BURST_END = BLAST + 1.0F;
    private static final float LOADING = 0.55F;
    private static final long WINDOW_OPEN_NANOS = 260_000_000L;
    private static final int PIECES = 22;

    private enum Phase { INTRO, LEGIT, MORE, LOADING, DONE }

    private final boolean motion;
    private final boolean accepted;
    private final Consumer<String> openLink;
    private final Runnable onAccepted;
    private final AnchorBlockPreview anchor = new AnchorBlockPreview();
    private final AnchorBlockPreview anchorPiece = new AnchorBlockPreview();
    private final AnchorBlockPreview glowstonePiece = new AnchorBlockPreview();
    private final BlockDraw blocks = new BlockDraw();
    private Phase phase;
    private long phaseAt = System.nanoTime();
    private int sounds;
    private float cancelHover;
    private float confirmHover;
    private float linkHover;
    private long lastFrame = this.phaseAt;
    private AnchorsLayout.Rect linkRect = AnchorsLayout.Rect.EMPTY;

    /**
     * @param accepted the windows were accepted before: only the animation, and only with motion
     * @param onAccepted called when the second window is accepted
     */
    EntrySequence(boolean motion, boolean accepted, Consumer<String> openLink, Runnable onAccepted) {
        this.motion = motion;
        this.accepted = accepted;
        this.openLink = openLink;
        this.onAccepted = onAccepted;
        this.phase = motion ? Phase.INTRO : accepted ? Phase.DONE : Phase.LEGIT;
    }

    boolean done() {
        return this.phase == Phase.DONE;
    }

    /** Whether the menu behind is hidden: everything but the last fade of the loading ring. */
    boolean covers() {
        return this.phase != Phase.DONE;
    }

    private float elapsed(long now) {
        return (now - this.phaseAt) / 1_000_000_000.0F;
    }

    private void enter(Phase next) {
        this.phase = next;
        this.phaseAt = System.nanoTime();
    }

    private void afterIntro() {
        enter(this.accepted ? (this.motion ? Phase.LOADING : Phase.DONE) : Phase.LEGIT);
    }

    boolean mouseClicked(int width, int height, double mouseX, double mouseY, int button) {
        if (button != Keys.LEFT_BUTTON) {
            return true;
        }
        switch (this.phase) {
            case INTRO -> afterIntro();
            case LOADING -> enter(Phase.DONE);
            case LEGIT, MORE -> {
                AnchorsLayout.InfoModal modal = AnchorsLayout.infoModal(width, height);
                if (this.linkRect.contains(mouseX, mouseY)) {
                    this.openLink.accept(ServerCheckWindow.PLUGIN_URL);
                } else if (modal.cancel().contains(mouseX, mouseY)) {
                    this.openLink.accept(this.phase == Phase.LEGIT ? AUDIT_URL : ServerCheckWindow.PLUGIN_URL);
                } else if (modal.confirm().contains(mouseX, mouseY)) {
                    accept();
                }
            }
            default -> {
            }
        }
        return true;
    }

    boolean keyPressed(int key) {
        switch (this.phase) {
            case INTRO -> {
                if (key == Keys.ESCAPE || Keys.confirms(key) || key == Keys.SPACE) {
                    afterIntro();
                }
            }
            case LOADING -> enter(Phase.DONE);
            case LEGIT, MORE -> {
                if (Keys.confirms(key)) {
                    accept();
                }
            }
            default -> {
            }
        }
        return true;
    }

    private void accept() {
        play(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 0.7F);
        if (this.phase == Phase.LEGIT) {
            play(SoundEvents.RESPAWN_ANCHOR_CHARGE, 1.3F, 0.6F);
            enter(Phase.MORE);
        } else {
            this.onAccepted.run();
            play(SoundEvents.RESPAWN_ANCHOR_SET_SPAWN, 1.25F, 0.8F);
            enter(this.motion ? Phase.LOADING : Phase.DONE);
        }
    }

    void render(GuiGraphicsExtractor graphics, Font font, int width, int height, int mouseX, int mouseY) {
        long now = System.nanoTime();
        float frameMillis = Math.min(50.0F, (now - this.lastFrame) / 1_000_000.0F);
        this.lastFrame = now;
        float response = 1.0F - (float) Math.exp(-frameMillis / 60.0F);
        double seconds = now / 1_000_000_000.0D;
        float t = elapsed(now);
        switch (this.phase) {
            case INTRO -> {
                graphics.fill(0, 0, width, height, 0xF0060208);
                drawIntro(graphics, width, height, t, now, seconds);
                if (t >= BURST_END) {
                    afterIntro();
                }
            }
            case LEGIT, MORE -> {
                graphics.fill(0, 0, width, height, 0xE8060208);
                if (this.motion) {
                    AnchorsUi.motes(graphics, width, height, seconds, 0.8F);
                }
                drawWindow(graphics, font, width, height, mouseX, mouseY, t, seconds, response);
            }
            case LOADING -> {
                float out = AnchorsTheme.clamp01((t - LOADING * 0.7F) / (LOADING * 0.3F));
                graphics.fill(0, 0, width, height, AnchorsTheme.fade(0xE8060208, 1.0F - out));
                drawLoading(graphics, font, width / 2, height / 2, t, seconds, 1.0F - out);
                if (t >= LOADING) {
                    enter(Phase.DONE);
                }
            }
            default -> {
            }
        }
    }

    /** The anchor charges and explodes into a burst of anchors and glowstone. */
    private void drawIntro(GuiGraphicsExtractor graphics, int width, int height, float t, long now, double seconds) {
        int centerX = width / 2;
        int centerY = height / 2;
        float size = Math.max(18.0F, Math.min(width, height) * 0.11F);
        int charges = t < CHARGE_START ? 0 : Math.min(4, (int) ((t - CHARGE_START) / CHARGE_STEP) + 1);
        while (this.sounds < charges) {
            this.sounds++;
            play(SoundEvents.RESPAWN_ANCHOR_CHARGE, 0.8F + this.sounds * 0.13F, 0.9F);
        }
        if (this.sounds == 4 && t >= BLAST) {
            play(SoundEvents.GENERIC_EXPLODE.value(), 0.9F, 0.75F);
            play(SoundEvents.FIREWORK_ROCKET_BLAST, 0.8F, 0.6F);
            this.sounds = 5;
        }
        float appear = AnchorsTheme.easeOutCubic(t / 0.25F);
        if (t < BLAST) {
            AnchorsUi.sigil(graphics, centerX, centerY + Math.round(size * 0.2F), Math.round(size * 2.2F), seconds * 1.4D,
                    AnchorsTheme.ACCENT, 0.8F * appear, charges);
            AnchorsUi.glowEllipse(graphics, centerX, centerY, Math.round(size * (1.3F + charges * 0.15F)),
                    Math.round(size * (1.2F + charges * 0.15F)), 0x9B4DFF, (0.35F + charges * 0.12F) * appear);
            float tremble = t > CHARGE_START ? Math.min(1.0F, (t - CHARGE_START) / (BLAST - CHARGE_START)) : 0.0F;
            int shake = Math.round((float) Math.sin(now / 7_000_000.0D) * tremble * 3.0F);
            this.blocks.draw(graphics, this.anchor.withCharge(charges), centerX + shake, centerY, size * appear,
                    225.0F + (float) (seconds * 60.0D), -24.0F);
            for (int charge = 1; charge <= charges; charge++) {
                float ring = (t - (CHARGE_START + (charge - 1) * CHARGE_STEP)) / 0.35F;
                if (ring >= 0.0F && ring < 1.0F) {
                    AnchorsUi.ring(graphics, centerX, centerY, Math.round(size * (0.5F + ring * 1.2F)), 1,
                            AnchorsTheme.withAlpha(charge == 4 ? 0xFFF7FF : 0xC084FC, Math.round(230 * (1.0F - ring))));
                }
            }
            String brand = "KOHS ANCHOR'S";
            AnchorsUi.bigText(graphics, Minecraft.getInstance().font, brand, centerX, centerY + Math.round(size * 1.6F), 1.5F,
                    AnchorsTheme.fade(0xFFE9D5FF, appear * 0.9F), false);
            return;
        }
        float since = t - BLAST;
        // The flash covers the middle only, briefly: no full-screen flashing.
        GlowstoneGuardWarning.drawBlast(graphics, centerX, centerY, Math.round(size), Math.min(width, height), since);
        float spread = AnchorsTheme.easeOutCubic(Math.min(1.0F, since / (BURST_END - BLAST)));
        float fade = 1.0F - AnchorsTheme.clamp01((since - 0.6F) / 0.4F);
        for (int index = 0; index < PIECES; index++) {
            double angle = index * (Math.PI * 2.0D / PIECES) + index * 0.61D;
            double reach = Math.min(width, height) * (0.22D + (index % 5) * 0.06D);
            int px = centerX + (int) Math.round(Math.cos(angle) * reach * spread);
            int py = centerY + (int) Math.round(Math.sin(angle) * reach * spread * 0.8D + since * since * size * 3.2D);
            float pieceSize = size * (index % 3 == 0 ? 0.42F : 0.3F) * fade;
            float spin = (float) (since * (300.0D + index * 37.0D)) * (index % 2 == 0 ? 1.0F : -1.0F);
            if (index % 2 == 0) {
                AnchorsUi.glowEllipse(graphics, px, py, Math.round(pieceSize * 1.6F), Math.round(pieceSize * 1.4F), 0xFFC46B,
                        0.45F * fade);
                this.blocks.draw(graphics, this.glowstonePiece.withState(Blocks.GLOWSTONE.defaultBlockState()), px, py, pieceSize, spin,
                        -20.0F);
            } else {
                AnchorsUi.glowEllipse(graphics, px, py, Math.round(pieceSize * 1.6F), Math.round(pieceSize * 1.4F), 0x9B4DFF,
                        0.45F * fade);
                this.blocks.draw(graphics, this.anchorPiece.withCharge(4), px, py, pieceSize, spin, -20.0F);
            }
        }
    }

    /** The two windows: what the mod is, then what the bridge adds. */
    private void drawWindow(GuiGraphicsExtractor graphics, Font font, int width, int height, int mouseX, int mouseY, float t,
            double seconds, float response) {
        boolean legit = this.phase == Phase.LEGIT;
        float open = this.motion ? AnchorsTheme.easeOutCubic(t / (WINDOW_OPEN_NANOS / 1_000_000_000.0F)) : 1.0F;
        AnchorsLayout.InfoModal modal = AnchorsLayout.infoModal(width, height);
        AnchorsLayout.Rect full = modal.box();
        AnchorsLayout.Rect box = full;
        float grow = 0.92F + 0.08F * open;
        int boxWidth = Math.round(box.width() * grow);
        int boxHeight = Math.round(box.height() * grow);
        int boxX = box.centerX() - boxWidth / 2;
        int boxY = box.centerY() - boxHeight / 2;
        int accent = legit ? AnchorsTheme.ACCENT : 0xFF000000 | AnchorFx.GREEN;
        AnchorsUi.halo(graphics, boxX, boxY, boxWidth, boxHeight, accent, 6, 0.8F * open);
        AnchorsUi.panel(graphics, boxX, boxY, boxWidth, boxHeight, AnchorsTheme.fade(0xF4160B27, open), AnchorsTheme.fade(0xF20B0514, open));
        AnchorsUi.roundedOutline(graphics, boxX, boxY, boxWidth, boxHeight, AnchorsTheme.fade(accent, open));
        AnchorsUi.bladeCorners(graphics, boxX, boxY, boxWidth, boxHeight, 8, AnchorsTheme.fade(0xE0FFF7FF, open));
        if (open < 0.95F) {
            return;
        }
        if (this.motion) {
            AnchorsUi.comets(graphics, box.x(), box.y(), box.width(), box.height(), seconds, accent & 0xFFFFFF);
        }
        int padding = modal.padding();
        AnchorsLayout.Rect titleRect = modal.title();
        String title = Component.translatable(legit ? "kohs_anchors.entry.legit.title" : "kohs_anchors.entry.more.title")
                .getString().toUpperCase(Locale.ROOT);
        float scale = Math.max(1.0F, Math.min(2.0F, titleRect.width() / (float) Math.max(1, font.width(title))));
        int titleY = titleRect.y() + Math.max(0, (titleRect.height() - Math.round(9 * scale)) / 2);
        AnchorsUi.bigText(graphics, font, title, box.centerX() + 1, titleY + 1, scale, legit ? 0xFF5B1FB0 : 0xFF0A4A24, false);
        AnchorsUi.bigText(graphics, font, title, box.centerX(), titleY, scale, AnchorsTheme.TITLE, false);
        AnchorsUi.energyLine(graphics, box.x() + padding, box.right() - padding, titleRect.bottom() + 1, accent,
                this.motion ? seconds : 0.0D, 1.0F);

        AnchorsLayout.Rect content = modal.content();
        int y = content.y() + 3;
        int bottom = content.bottom();
        if (legit) {
            // The body in capitals, as the window is meant to be read: loud.
            Component body = Component.literal(Component.translatable("kohs_anchors.entry.legit.body").getString()
                    .toUpperCase(Locale.ROOT));
            y = paragraph(graphics, font, body, content, y, bottom, AnchorsTheme.TEXT);
            y += 3;
            Component plugin = Component.literal(Component.translatable("kohs_anchors.entry.legit.plugin").getString()
                    .toUpperCase(Locale.ROOT));
            y = paragraph(graphics, font, plugin, content, y, bottom, 0xFFE9D5FF);
            y += 3;
            y = paragraph(graphics, font, Component.translatable("kohs_anchors.entry.legit.rules"), content, y, bottom,
                    AnchorsTheme.TEXT_DIM);
        } else {
            y = paragraph(graphics, font, Component.translatable("kohs_anchors.entry.more.body"), content, y, bottom,
                    AnchorsTheme.TEXT);
            y += 3;
            y = paragraph(graphics, font, Component.translatable("kohs_anchors.entry.more.enemy",
                    Component.translatable("kohs_anchors.enemy.switch")), content, y, bottom, 0xFFFF9AB0);
        }
        // The plugin's page, as a link under the text.
        String link = "› " + Component.translatable("kohs_anchors.entry.plugin_link").getString();
        int linkWidth = font.width(link);
        int linkY = Math.min(bottom - 10, y + 4);
        if (linkY + 9 <= bottom && linkY >= content.y()) {
            this.linkRect = new AnchorsLayout.Rect(box.centerX() - linkWidth / 2 - 2, linkY - 1, linkWidth + 4, 11);
            this.linkHover += ((this.linkRect.contains(mouseX, mouseY) ? 1.0F : 0.0F) - this.linkHover) * response;
            int linkColor = AnchorsTheme.lerp(0xFFC084FC, 0xFFFFF7FF, this.linkHover);
            AnchorsUi.label(graphics, font, link, box.centerX() - linkWidth / 2, linkY, linkColor, true);
            graphics.fill(box.centerX() - linkWidth / 2, linkY + 9, box.centerX() + linkWidth / 2, linkY + 10,
                    AnchorsTheme.fade(linkColor, 0.5F + 0.5F * this.linkHover));
        } else {
            this.linkRect = AnchorsLayout.Rect.EMPTY;
        }

        AnchorsLayout.Rect cancel = modal.cancel();
        AnchorsLayout.Rect confirm = modal.confirm();
        this.cancelHover += ((cancel.contains(mouseX, mouseY) ? 1.0F : 0.0F) - this.cancelHover) * response;
        this.confirmHover += ((confirm.contains(mouseX, mouseY) ? 1.0F : 0.0F) - this.confirmHover) * response;
        AnchorsButton.draw(graphics, cancel.x(), cancel.y(), cancel.width(), cancel.height(),
                Component.translatable(legit ? "kohs_anchors.entry.audit" : "kohs_anchors.bridge.plugin").getString(), false,
                false, this.cancelHover, 0.0F, 1.0F, -1.0F);
        AnchorsButton.draw(graphics, confirm.x(), confirm.y(), confirm.width(), confirm.height(),
                Component.translatable(legit ? "kohs_anchors.entry.accept" : "kohs_anchors.entry.continue").getString(), true,
                false, this.confirmHover, 0.0F, 1.0F, -1.0F);
        DevInspector.node("EntrySequence", this.phase.name(), box.x(), box.y(), box.width(), box.height(),
                "Settings.entryAccepted", "audit " + AUDIT_URL, "plugin " + ServerCheckWindow.PLUGIN_URL);
    }

    private static int paragraph(GuiGraphicsExtractor graphics, Font font, Component text, AnchorsLayout.Rect content, int y,
            int bottom, int color) {
        List<FormattedCharSequence> lines = font.split(text, Math.max(40, content.width()));
        for (FormattedCharSequence line : lines) {
            if (y + 9 > bottom - 12) {
                break;
            }
            AnchorsUi.line(graphics, font, line, content.centerX() - font.width(line) / 2, y, color);
            y += 10;
        }
        return y;
    }

    /** A small ring filling around a charged anchor, and the word. */
    private void drawLoading(GuiGraphicsExtractor graphics, Font font, int centerX, int centerY, float t, double seconds,
            float alpha) {
        float progress = AnchorsTheme.clamp01(t / (LOADING * 0.8F));
        int radius = 18;
        int dots = 24;
        for (int dot = 0; dot < dots; dot++) {
            double angle = dot * Math.PI * 2.0D / dots - Math.PI / 2.0D;
            boolean lit = dot < Math.round(progress * dots);
            int px = centerX + (int) Math.round(Math.cos(angle) * radius);
            int py = centerY + (int) Math.round(Math.sin(angle) * radius);
            graphics.fill(px - 1, py - 1, px + 1, py + 1, AnchorsTheme.withAlpha(lit ? 0xE9D5FF : 0x3A1560, Math.round(255 * alpha)));
        }
        AnchorsUi.miniAnchor(graphics, centerX - 4, centerY - 4, 4.0F * progress, alpha);
        String word = Component.translatable("kohs_anchors.entry.loading").getString().toUpperCase(Locale.ROOT);
        AnchorsUi.label(graphics, font, word, centerX - font.width(word) / 2, centerY + radius + 6,
                AnchorsTheme.withAlpha(0xD8B4FE, Math.round(255 * alpha)), false);
    }

    private static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }
}
