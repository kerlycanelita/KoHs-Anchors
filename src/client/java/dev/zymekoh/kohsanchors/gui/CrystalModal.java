package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.compat.AnchorBlockPreview;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.integration.CrystalPalette;
import java.net.URI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * "Crystal colours": the anchor takes its palette from KoHs Crystal Tweaks.
 *
 * <p>It opens on a loading dance, an anchor hopping with a glowstone that charges it on every
 * landing, while the mod and its settings file are looked for. Without either, it says that KoHs
 * Crystal Tweaks is needed and links to its Modrinth page. With them, it shows the crystal's
 * colours beside what the anchor takes from them, layer by layer and charge by charge, and applies
 * them on request, keeping them in step from then on.</p>
 */
final class CrystalModal {
    private static final long OPEN_NANOS = 240_000_000L;
    private static final long LOADING_NANOS = 1_900_000_000L;
    private static final long CLOSE_NANOS = 180_000_000L;

    private enum Phase { LOADING, MISSING, RESULT, CLOSING, DONE }

    private final Screen parent;
    private final boolean motion;
    private final AnchorBlockPreview anchor = new AnchorBlockPreview();
    private final AnchorBlockPreview glowstone = new AnchorBlockPreview();
    private final Quaternionf rotation = new Quaternionf();
    private final Vector3f translation = new Vector3f();
    private final long openedAt = System.nanoTime();
    private Phase phase = Phase.LOADING;
    private long phaseStartedAt = System.nanoTime();
    private CrystalPalette.Colors colors;
    private CrystalPalette.Mapping mapping;
    private float cancelHover;
    private float confirmHover;
    private int lastHop = -1;
    private int linkX;
    private int linkY;
    private int linkWidth;

    CrystalModal(Screen parent, boolean motion) {
        this.parent = parent;
        this.motion = motion;
    }

    boolean done() {
        return this.phase == Phase.DONE;
    }

    private void settle(long now) {
        this.colors = CrystalPalette.read();
        if (this.colors == null) {
            this.phase = Phase.MISSING;
        } else {
            this.mapping = CrystalPalette.map(this.colors);
            this.phase = Phase.RESULT;
        }
        this.phaseStartedAt = now;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
                this.phase == Phase.RESULT ? SoundEvents.AMETHYST_BLOCK_CHIME : SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(),
                1.2F, 0.7F));
    }

    boolean mouseClicked(int width, int height, double mouseX, double mouseY, int button) {
        long now = System.nanoTime();
        if (button != Keys.LEFT_BUTTON) {
            return true;
        }
        if (this.phase == Phase.LOADING) {
            // A click hurries the dance.
            settle(now);
            return true;
        }
        AnchorsLayout.Modal modal = AnchorsLayout.modal(width, height);
        if (this.phase == Phase.MISSING && this.linkWidth > 0 && mouseX >= this.linkX && mouseX < this.linkX + this.linkWidth
                && mouseY >= this.linkY - 1 && mouseY < this.linkY + 10) {
            ConfirmLinkScreen.confirmLinkNow(this.parent, URI.create(CrystalPalette.MODRINTH));
        } else if (modal.cancel().contains(mouseX, mouseY)) {
            close(now);
        } else if (modal.confirm().contains(mouseX, mouseY)) {
            if (this.phase == Phase.MISSING) {
                ConfirmLinkScreen.confirmLinkNow(this.parent, URI.create(CrystalPalette.MODRINTH));
            } else if (this.phase == Phase.RESULT) {
                AnchorsConfig.Settings settings = AnchorsConfig.settings();
                if (settings.crystalColors) {
                    settings.crystalColors = false;
                    AnchorsConfig.save();
                } else {
                    settings.crystalColors = true;
                    CrystalPalette.apply(this.mapping, this.colors.modified());
                    Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.RESPAWN_ANCHOR_SET_SPAWN,
                            1.3F, 0.7F));
                }
                close(now);
            }
        }
        return true;
    }

    boolean keyPressed(int key) {
        if (key == Keys.ESCAPE) {
            close(System.nanoTime());
        }
        return true;
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
        float open = this.motion ? AnchorsTheme.easeOutCubic((now - this.openedAt) / (float) OPEN_NANOS) : 1.0F;
        if (this.phase == Phase.CLOSING) {
            float t = Math.min(1.0F, (now - this.phaseStartedAt) / (float) CLOSE_NANOS);
            open = 1.0F - t;
            if (t >= 1.0F) {
                this.phase = Phase.DONE;
                return;
            }
        }
        if (this.phase == Phase.LOADING && now - this.phaseStartedAt >= (this.motion ? LOADING_NANOS : LOADING_NANOS / 3)) {
            settle(now);
        }
        graphics.fill(0, 0, width, height, AnchorsTheme.withAlpha(0x06020A, Math.round(190 * open)));
        AnchorsLayout.Modal modal = AnchorsLayout.modal(width, height);
        AnchorsLayout.Rect box = modal.box();
        int accent = this.phase == Phase.MISSING ? AnchorsTheme.MAGENTA : AnchorsTheme.ACCENT;
        AnchorsUi.halo(graphics, box.x(), box.y(), box.width(), box.height(), accent, 6, open);
        AnchorsUi.panel(graphics, box.x(), box.y(), box.width(), box.height(), AnchorsTheme.fade(AnchorsTheme.PANEL_TOP, open),
                AnchorsTheme.fade(AnchorsTheme.PANEL_BOTTOM, open));
        AnchorsUi.roundedOutline(graphics, box.x(), box.y(), box.width(), box.height(), AnchorsTheme.fade(accent, open));
        AnchorsUi.bladeCorners(graphics, box.x(), box.y(), box.width(), box.height(), 7, AnchorsTheme.fade(0xE0E9D5FF, open));
        if (open < 0.95F || this.phase == Phase.CLOSING) {
            return;
        }
        if (this.motion) {
            AnchorsUi.comets(graphics, box.x(), box.y(), box.width(), box.height(), seconds, 0xE8CCFF);
        }
        int padding = modal.padding();
        int innerWidth = box.width() - padding * 2;
        int stageHeight = Math.max(40, Math.min(90, (modal.cancel().y() - box.y()) / 2 - 6));
        int stageY = box.y() + padding;
        drawDance(graphics, box.centerX(), stageY + stageHeight / 2, stageHeight, seconds, now);
        int y = stageY + stageHeight + 4;
        int bottomLimit = modal.cancel().y() - 6;
        String titleKey = switch (this.phase) {
            case LOADING -> CrystalPalette.available() ? "kohs_anchors.crystal.analyzing" : "kohs_anchors.crystal.searching";
            case MISSING -> "kohs_anchors.crystal.missing.title";
            default -> "kohs_anchors.crystal.result.title";
        };
        String title = Component.translatable(titleKey).getString();
        AnchorsUi.label(graphics, font, AnchorsUi.fit(font, title, innerWidth), box.centerX() - Math.min(innerWidth,
                font.width(title)) / 2, y, AnchorsTheme.TITLE, true);
        y += 12;
        AnchorsUi.energyLine(graphics, box.x() + padding, box.right() - padding, y - 2, accent, seconds, 1.0F);
        y += 5;
        if (this.phase == Phase.LOADING) {
            int dots = (int) (seconds * 3.0D) % 4;
            String wait = Component.translatable("kohs_anchors.crystal.wait").getString() + ".".repeat(dots);
            AnchorsUi.label(graphics, font, wait, box.centerX() - font.width(wait) / 2, y + 4, AnchorsTheme.TEXT_MUTED, false);
            return;
        }
        if (this.phase == Phase.MISSING) {
            for (FormattedCharSequence line : font.split(Component.translatable("kohs_anchors.crystal.missing.body"),
                    Math.max(40, innerWidth))) {
                if (y + 9 > bottomLimit) {
                    break;
                }
                AnchorsUi.line(graphics, font, line, box.centerX() - font.width(line) / 2, y, AnchorsTheme.TEXT);
                y += 10;
            }
            // The page itself, written out and clickable, so it can also be typed on another device.
            String link = CrystalPalette.MODRINTH.replaceFirst("^https://", "");
            this.linkWidth = 0;
            if (y + 14 <= bottomLimit) {
                String shown = AnchorsUi.fit(font, link, innerWidth);
                this.linkWidth = font.width(shown);
                this.linkX = box.centerX() - this.linkWidth / 2;
                this.linkY = y + 4;
                boolean over = mouseX >= this.linkX && mouseX < this.linkX + this.linkWidth && mouseY >= this.linkY - 1
                        && mouseY < this.linkY + 10;
                int color = over ? 0xFFB8F8FF : AnchorsTheme.CYAN;
                AnchorsUi.label(graphics, font, shown, this.linkX, this.linkY, color, false);
                graphics.fill(this.linkX, this.linkY + 9, this.linkX + this.linkWidth, this.linkY + 10,
                        AnchorsTheme.withAlpha(color & 0xFFFFFF, over ? 255 : 150));
                DevInspector.node("Link", "Modrinth", this.linkX, this.linkY, this.linkWidth, 10,
                        "CrystalPalette.MODRINTH", "ConfirmLinkScreen.confirmLinkNow");
            }
        } else if (this.phase == Phase.RESULT && this.mapping != null) {
            y = drawMapping(graphics, font, box.x() + padding, y, innerWidth, bottomLimit);
        }
        buttons(graphics, modal, mouseX, mouseY);
    }

    private int drawMapping(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int bottomLimit) {
        String from = Component.translatable("kohs_anchors.crystal.from").getString();
        String to = Component.translatable("kohs_anchors.crystal.to").getString();
        int half = width / 2;
        AnchorsUi.label(graphics, font, AnchorsUi.fit(font, from, half - 6), x, y, AnchorsTheme.TEXT_DIM, false);
        AnchorsUi.label(graphics, font, AnchorsUi.fit(font, to, half - 6), x + half + 4, y, AnchorsTheme.TEXT_DIM, false);
        y += 11;
        int swatch = Math.max(8, Math.min(14, (half - 10) / 5 - 2));
        int[] source = {this.colors.outer(), this.colors.inner(), this.colors.core(), this.colors.glow(), this.colors.enemyGlow()};
        for (int index = 0; index < source.length; index++) {
            // Crystal Tweaks' glow colour only counts when it is set as custom.
            float alpha = index == 3 && !this.colors.customGlow() ? 0.3F : 1.0F;
            AnchorsUi.swatch(graphics, x + index * (swatch + 3), y, swatch, swatch, source[index], alpha, 0.0F);
        }
        int arrowX = x + half - 6;
        AnchorsUi.label(graphics, font, "▶", arrowX, y + swatch / 2 - 4, AnchorsTheme.ACCENT_BRIGHT, false);
        int[] target = {this.mapping.frame(), this.mapping.glow(), this.mapping.charges()[0], this.mapping.charges()[1],
                this.mapping.charges()[2], this.mapping.charges()[3], this.mapping.light(), this.mapping.enemy()};
        int columns = Math.max(4, (half - 4) / (swatch + 3));
        for (int index = 0; index < target.length; index++) {
            int column = index % columns;
            int row = index / columns;
            AnchorsUi.swatch(graphics, x + half + 4 + column * (swatch + 3), y + row * (swatch + 3), swatch, swatch,
                    target[index], 1.0F, 0.0F);
        }
        y += swatch * ((target.length + columns - 1) / columns) + (target.length > columns ? 3 : 0) + 6;
        for (FormattedCharSequence line : font.split(Component.translatable(AnchorsConfig.settings().crystalColors
                ? "kohs_anchors.crystal.result.synced" : "kohs_anchors.crystal.result.body"), Math.max(40, width))) {
            if (y + 9 > bottomLimit) {
                break;
            }
            AnchorsUi.line(graphics, font, line, x, y, AnchorsTheme.TEXT_MUTED);
            y += 10;
        }
        return y;
    }

    private void buttons(GuiGraphicsExtractor graphics, AnchorsLayout.Modal modal, int mouseX, int mouseY) {
        AnchorsLayout.Rect cancel = modal.cancel();
        AnchorsLayout.Rect confirm = modal.confirm();
        this.cancelHover += ((cancel.contains(mouseX, mouseY) ? 1.0F : 0.0F) - this.cancelHover) * 0.3F;
        this.confirmHover += ((confirm.contains(mouseX, mouseY) ? 1.0F : 0.0F) - this.confirmHover) * 0.3F;
        AnchorsButton.draw(graphics, cancel.x(), cancel.y(), cancel.width(), cancel.height(),
                Component.translatable("kohs_anchors.crystal.close").getString(), false, false, this.cancelHover, 0.0F, 1.0F,
                -1.0F);
        String key = this.phase == Phase.MISSING ? "kohs_anchors.crystal.modrinth"
                : AnchorsConfig.settings().crystalColors ? "kohs_anchors.crystal.stop" : "kohs_anchors.crystal.apply";
        AnchorsButton.draw(graphics, confirm.x(), confirm.y(), confirm.width(), confirm.height(),
                Component.translatable(key).getString(), true, false, this.confirmHover, 0.0F, 1.0F, -1.0F);
    }

    /**
     * The loading dance: the anchor hops and turns, a glowstone circles it and drops onto it at
     * every landing, lighting one more charge, with sparks between them.
     */
    private void drawDance(GuiGraphicsExtractor graphics, int centerX, int centerY, int size, double seconds, long now) {
        double beat = this.motion ? seconds * 1.6D : 0.0D;
        int hop = (int) Math.floor(beat);
        double phase = beat - hop;
        int charge = Math.floorMod(hop, 5);
        if (this.motion && hop != this.lastHop && this.phase == Phase.LOADING) {
            this.lastHop = hop;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.RESPAWN_ANCHOR_CHARGE,
                    0.9F + charge * 0.12F, 0.25F));
        }
        float jump = (float) Math.sin(phase * Math.PI);
        float squash = phase < 0.12D ? (float) (1.0D - phase / 0.12D) * 0.18F : 0.0F;
        int anchorY = centerY - Math.round(jump * size * 0.18F);
        AnchorsUi.glowEllipse(graphics, centerX, centerY, size, Math.round(size * 0.7F), 0x9B4DFF, 0.25F + 0.1F * charge);
        AnchorsUi.ellipse(graphics, centerX, centerY + Math.round(size * 0.42F), Math.round(size * (0.32F - jump * 0.06F)),
                Math.max(2, size / 14), AnchorsTheme.withAlpha(0x0A0412, 150));
        drawBlock(graphics, this.anchor.withCharge(charge), centerX, anchorY, size * 0.62F * (1.0F + squash * 0.4F),
                (float) (seconds * 70.0D) + 225.0F, -24.0F, 1.0F - squash);
        // The glowstone circles and falls onto the anchor as it lands.
        double orbit = beat * Math.PI * 0.8D;
        float radius = size * (0.85F - 0.55F * (float) Math.pow(Math.max(0.0D, 1.0D - phase * 2.2D), 3.0D));
        int stoneX = centerX + (int) Math.round(Math.cos(orbit) * radius);
        int stoneY = anchorY - Math.round(size * 0.12F) - Math.round((float) Math.abs(Math.sin(beat * Math.PI * 2.0D)) * size * 0.25F);
        drawBlock(graphics, this.glowstone.withState(Blocks.GLOWSTONE.defaultBlockState()), stoneX, stoneY, size * 0.3F,
                (float) (seconds * -140.0D), -30.0F, 1.0F);
        // Sparks from the glowstone to the anchor.
        for (int spark = 0; spark < 8; spark++) {
            double t = ((seconds * 1.7D + spark * 0.125D) % 1.0D);
            int sx = (int) Math.round(stoneX + (centerX - stoneX) * t + Math.sin(spark + seconds * 5.0D) * 3.0D);
            int sy = (int) Math.round(stoneY + (anchorY - stoneY) * t - Math.sin(t * Math.PI) * 6.0D);
            int color = spark % 2 == 0 ? 0xFFFFC46B : 0xFFE9CCFF;
            graphics.fill(sx, sy, sx + 2, sy + 2, AnchorsTheme.withAlpha(color, Math.round((float) Math.sin(t * Math.PI) * 220)));
        }
        if (charge == RespawnAnchorBlock.MAX_CHARGES && phase < 0.25D && this.motion) {
            AnchorsUi.ring(graphics, centerX, centerY, Math.round(size * (0.3F + (float) phase * 2.2F)), 1,
                    AnchorsTheme.withAlpha(0xC084FC, Math.round((1.0F - (float) phase * 4.0F) * 220)));
        }
    }

    private void drawBlock(GuiGraphicsExtractor graphics, net.minecraft.client.renderer.entity.state.EntityRenderState state,
            int centerX, int centerY, float size, float yaw, float pitch, float stretch) {
        this.rotation.identity().rotateZ((float) Math.PI).rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw));
        this.rotation.transform(this.translation.set(0.0F, 0.5F, 0.0F)).negate();
        int half = Math.round(size);
        graphics.entity(state, size / 1.6F, this.translation, this.rotation, new Quaternionf(),
                centerX - half, centerY - half, centerX + half, centerY + half);
    }
}
