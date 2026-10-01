package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.bridge.BridgeClient;
import dev.zymekoh.kohsanchors.safety.ServerLock;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

/**
 * The Anchors Server tab's check: is there a bridge on this server?
 *
 * <p>An anchor falls into the window and lands with a bounce, and while the bridge is asked
 * ({@link BridgeClient}) rings scan around it and packets run between it and a small server. Then:</p>
 * <ul>
 *   <li><b>A bridge</b> (or the player's own world): the anchor's glow turns green with a ring of
 *   light, it hops twice and flies into the tab's anchor column, and the window lets the options
 *   show.</li>
 *   <li><b>No bridge</b>: everything turns red, an evil sound, a padlock falls on the anchor, and
 *   the window says which server does not allow the anchor chain and why. Its buttons open the
 *   plugin's page or close it.</li>
 *   <li><b>Not on a server</b>: a calm word on where the check works.</li>
 * </ul>
 * <p>A locked option opens it straight on its answer. A click skips the waiting parts; Escape
 * closes it.</p>
 */
final class ServerCheckWindow {
    private static final float FALL_END = 0.8F;
    private static final float MIN_SCAN = 1.75F;
    private static final float GREEN_END = 0.35F;
    private static final float HOPS_END = 1.15F;
    private static final float FLY_END = 1.7F;
    private static final float LOCK_DROP = 0.3F;
    private static final float LOCK_LANDED = 0.85F;
    private static final long CLOSE_NANOS = 200_000_000L;
    static final String PLUGIN_URL = "https://github.com/kerlycanelita/KoHs-Anchors-Bridge";

    private enum Answer { NONE, BRIDGE, MISSING, OFFLINE }

    private final boolean motion;
    private final AnchorFigure figure;
    private final Supplier<AnchorsLayout.Rect> landing;
    private final Consumer<String> openLink;
    private final long openedAt = System.nanoTime();
    private final boolean direct;
    private Answer answer = Answer.NONE;
    private long answeredAt;
    private boolean closing;
    private long closedAt;
    private boolean done;
    private int sounds;
    private float cancelHover;
    private float confirmHover;
    private long lastFrame = this.openedAt;

    /**
     * @param landing where the anchor flies when the bridge answers: the tab's anchor column
     * @param direct a locked option was clicked: the answer at once, without the fall and the scan
     */
    ServerCheckWindow(boolean motion, AnchorFigure figure, Supplier<AnchorsLayout.Rect> landing, Consumer<String> openLink,
            boolean direct) {
        this.motion = motion;
        this.figure = figure;
        this.landing = landing;
        this.openLink = openLink;
        this.direct = direct || !motion;
        if (!this.direct) {
            play(SoundEvents.RESPAWN_ANCHOR_AMBIENT, 1.5F, 0.8F);
        }
    }

    boolean done() {
        return this.done;
    }

    /** Whether the anchor is on its way into the column: the column must not draw it twice. */
    boolean flying() {
        return this.answer == Answer.BRIDGE && !this.done;
    }

    private float elapsed(long now) {
        return (now - this.openedAt) / 1_000_000_000.0F;
    }

    private float sinceAnswer(long now) {
        return (now - this.answeredAt) / 1_000_000_000.0F;
    }

    boolean mouseClicked(int width, int height, double mouseX, double mouseY, int button) {
        if (button != Keys.LEFT_BUTTON || this.closing) {
            return true;
        }
        long now = System.nanoTime();
        if (this.answer == Answer.NONE) {
            // A click while it scans: straight to the answer, if there is one yet.
            if (BridgeClient.state() != BridgeClient.State.CHECKING) {
                decide(now);
            }
            return true;
        }
        if (this.answer == Answer.BRIDGE) {
            this.done = true;
            return true;
        }
        AnchorsLayout.InfoModal modal = AnchorsLayout.infoModal(width, height);
        if (this.answer == Answer.MISSING && modal.cancel().contains(mouseX, mouseY)) {
            this.openLink.accept(PLUGIN_URL);
        } else if (modal.confirm().contains(mouseX, mouseY)) {
            close(now);
        }
        return true;
    }

    boolean keyPressed(int key) {
        long now = System.nanoTime();
        if (key == Keys.ESCAPE) {
            if (this.answer == Answer.BRIDGE) {
                this.done = true;
            } else {
                close(now);
            }
        } else if (Keys.confirms(key) && this.answer != Answer.NONE) {
            if (this.answer == Answer.BRIDGE) {
                this.done = true;
            } else {
                close(now);
            }
        }
        return true;
    }

    private void close(long now) {
        if (!this.closing) {
            this.closing = true;
            this.closedAt = now;
            play(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 0.6F);
        }
    }

    private void decide(long now) {
        BridgeClient.State state = BridgeClient.state();
        this.answer = switch (state) {
            case CONNECTED, LOCAL -> Answer.BRIDGE;
            case OFFLINE -> Answer.OFFLINE;
            default -> Answer.MISSING;
        };
        this.answeredAt = now;
        switch (this.answer) {
            case BRIDGE -> {
                play(SoundEvents.BEACON_ACTIVATE, 1.4F, 0.9F);
                play(SoundEvents.RESPAWN_ANCHOR_SET_SPAWN, 1.3F, 0.7F);
            }
            case MISSING -> {
                // Evil: the guardian's curse and the anchor draining, low.
                play(SoundEvents.ELDER_GUARDIAN_CURSE, 0.7F, 0.45F);
                play(SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), 0.5F, 1.0F);
            }
            default -> play(SoundEvents.BEACON_DEACTIVATE, 1.2F, 0.6F);
        }
    }

    void render(GuiGraphicsExtractor graphics, Font font, int width, int height, int mouseX, int mouseY) {
        long now = System.nanoTime();
        float frameMillis = Math.min(50.0F, (now - this.lastFrame) / 1_000_000.0F);
        this.lastFrame = now;
        float response = 1.0F - (float) Math.exp(-frameMillis / 60.0F);
        double seconds = now / 1_000_000_000.0D;
        float t = elapsed(now);
        if (this.answer == Answer.NONE && (this.direct || t >= MIN_SCAN) && BridgeClient.state() != BridgeClient.State.CHECKING) {
            decide(now);
        }
        float fade = 1.0F;
        if (this.closing) {
            fade = 1.0F - Math.min(1.0F, (now - this.closedAt) / (float) CLOSE_NANOS);
            if (fade <= 0.0F) {
                this.done = true;
                return;
            }
        }
        float since = this.answer == Answer.NONE ? 0.0F : sinceAnswer(now);
        // The bridge's answer flies the anchor out: the window fades as it goes.
        float flight = this.answer == Answer.BRIDGE ? AnchorsTheme.clamp01((since - HOPS_END) / (FLY_END - HOPS_END)) : 0.0F;
        if (this.answer == Answer.BRIDGE && since >= FLY_END) {
            this.done = true;
            return;
        }
        float windowAlpha = fade * (1.0F - AnchorsTheme.easeInCubic(flight));
        float open = this.direct ? 1.0F : AnchorsTheme.easeOutCubic(t / 0.25F);

        // The veil: darker while it checks, red when there is no bridge.
        float red = this.answer == Answer.MISSING ? AnchorsTheme.clamp01(since / 0.4F) : 0.0F;
        int veil = AnchorsTheme.lerp(0xC8060208, 0xC8380612, red);
        graphics.fill(0, 0, width, height, AnchorsTheme.fade(veil, open * windowAlpha));
        if (red > 0.0F && this.motion) {
            float throb = 0.5F + 0.5F * (float) Math.sin(seconds * 3.2D);
            graphics.fillGradient(0, 0, width, height / 3, AnchorsTheme.withAlpha(0xD11F4A, Math.round(60 * red * throb * fade)), 0);
            graphics.fillGradient(0, height * 2 / 3, width, height, 0,
                    AnchorsTheme.withAlpha(0xD11F4A, Math.round(60 * red * throb * fade)));
        }

        AnchorsLayout.InfoModal modal = AnchorsLayout.infoModal(width, height);
        AnchorsLayout.Rect box = modal.box();
        int accent = switch (this.answer) {
            case BRIDGE -> 0xFF000000 | AnchorFx.GREEN;
            case MISSING -> AnchorsTheme.CRIMSON_BRIGHT;
            default -> AnchorsTheme.ACCENT;
        };
        float boxAlpha = open * windowAlpha;
        AnchorsUi.halo(graphics, box.x(), box.y(), box.width(), box.height(), accent, 5, 0.7F * boxAlpha);
        AnchorsUi.panel(graphics, box.x(), box.y(), box.width(), box.height(),
                AnchorsTheme.fade(this.answer == Answer.MISSING ? 0xF22A0A18 : 0xF2160B27, boxAlpha),
                AnchorsTheme.fade(this.answer == Answer.MISSING ? 0xF0140510 : 0xF00B0514, boxAlpha));
        AnchorsUi.roundedOutline(graphics, box.x(), box.y(), box.width(), box.height(), AnchorsTheme.fade(accent, boxAlpha));
        AnchorsUi.bladeCorners(graphics, box.x(), box.y(), box.width(), box.height(), 7, AnchorsTheme.fade(0xE0FFF7FF, boxAlpha));
        if (this.motion && boxAlpha > 0.5F) {
            AnchorsUi.comets(graphics, box.x(), box.y(), box.width(), box.height(), seconds, accent & 0xFFFFFF);
        }

        // The title.
        String titleKey = switch (this.answer) {
            case NONE -> "kohs_anchors.bridge.check.title";
            case BRIDGE -> BridgeClient.state() == BridgeClient.State.LOCAL ? "kohs_anchors.bridge.ok.local" : "kohs_anchors.bridge.ok.title";
            case MISSING -> BridgeClient.state() == BridgeClient.State.NO_API ? "kohs_anchors.bridge.noapi.title"
                    : "kohs_anchors.bridge.fail.title";
            case OFFLINE -> "kohs_anchors.bridge.offline.title";
        };
        String title = Component.translatable(titleKey).getString().toUpperCase(Locale.ROOT);
        AnchorsLayout.Rect titleRect = modal.title();
        float scale = Math.max(1.0F, Math.min(2.0F, titleRect.width() / (float) Math.max(1, font.width(title))));
        int titleY = titleRect.y() + Math.max(0, (titleRect.height() - Math.round(9 * scale)) / 2);
        int shadow = this.answer == Answer.MISSING ? 0xFF5A0514 : this.answer == Answer.BRIDGE ? 0xFF0A4A24 : 0xFF5B1FB0;
        AnchorsUi.bigText(graphics, font, title, box.centerX() + 1, titleY + 1, scale, AnchorsTheme.fade(shadow, boxAlpha), false);
        AnchorsUi.bigText(graphics, font, title, box.centerX(), titleY, scale, AnchorsTheme.fade(AnchorsTheme.TITLE, boxAlpha), false);
        AnchorsUi.energyLine(graphics, box.x() + modal.padding(), box.right() - modal.padding(), titleRect.bottom() + 1,
                accent, this.motion ? seconds : 0.0D, boxAlpha);

        // The stage: the anchor in the upper part of the content, the text under it.
        AnchorsLayout.Rect content = modal.content();
        List<FormattedCharSequence> lines = font.split(bodyText(), Math.max(40, content.width()));
        int textHeight = Math.min(lines.size(), 5) * 10;
        int stageTop = content.y() + 2;
        int stageBottom = content.bottom() - textHeight - 4;
        int stageHeight = Math.max(0, stageBottom - stageTop);
        float anchorScale = Math.max(10.0F, Math.min(stageHeight * 0.3F, content.width() * 0.11F));
        float restX = box.centerX();
        float restY = stageTop + stageHeight * 0.55F;

        // Where the anchor is this frame: falling in, resting, hopping, or flying out.
        float x = restX;
        float y = restY;
        float drawScale = anchorScale;
        if (!this.direct && t < FALL_END) {
            float fall = AnchorFx.bounce(t / FALL_END);
            y = (box.y() - anchorScale * 2.0F) + (restY - (box.y() - anchorScale * 2.0F)) * fall;
        }
        if (!this.direct && this.sounds == 0 && t >= FALL_END * 0.36F) {
            // The first touch of the floor.
            play(SoundEvents.ANVIL_LAND, 0.55F, 0.35F);
            this.sounds = 1;
        }
        float tint = 0.0F;
        int tintColor = AnchorFx.GREEN;
        if (this.answer == Answer.BRIDGE) {
            tint = AnchorsTheme.clamp01(since / GREEN_END);
            float hops = AnchorsTheme.clamp01((since - GREEN_END) / (HOPS_END - GREEN_END));
            if (hops > 0.0F && hops < 1.0F) {
                y -= (float) Math.abs(Math.sin(hops * Math.PI * 2.0D)) * anchorScale * 0.55F * (1.0F - hops * 0.4F);
            }
            if (flight > 0.0F) {
                AnchorsLayout.Rect target = this.landing.get();
                float eased = AnchorsTheme.easeInOutSine(flight);
                float targetX = target.width() > 0 ? target.centerX() : restX;
                float targetY = target.width() > 0 ? target.y() + target.height() / 2.0F : restY;
                float targetScale = target.width() > 0 ? Math.min(target.width(), target.height()) / 2.4F : anchorScale;
                // An arc: up first, then down into the column.
                float lift = (float) Math.sin(eased * Math.PI) * anchorScale * 1.2F;
                x = restX + (targetX - restX) * eased;
                y = restY + (targetY - restY) * eased - lift;
                drawScale = anchorScale + (targetScale - anchorScale) * eased;
            }
        } else if (this.answer == Answer.MISSING) {
            tint = AnchorsTheme.clamp01(since / 0.4F);
            tintColor = 0xFF315C;
            if (since < LOCK_LANDED + 0.2F) {
                x += (float) Math.sin(now / 9_000_000.0D) * 2.0F * (1.0F - AnchorsTheme.clamp01(since / (LOCK_LANDED + 0.2F)));
            }
        }

        if (stageHeight >= 30 || flight > 0.0F) {
            // The floor and what turns around the anchor.
            if (windowAlpha > 0.05F) {
                AnchorsUi.glowEllipse(graphics, Math.round(restX), Math.round(restY + anchorScale * 0.62F),
                        Math.round(anchorScale * 1.4F), Math.max(3, Math.round(anchorScale * 0.22F)), accent & 0xFFFFFF,
                        0.45F * windowAlpha);
            }
            if (this.answer == Answer.NONE && t >= FALL_END * 0.6F) {
                drawScan(graphics, font, Math.round(restX), Math.round(restY), anchorScale, box, seconds, boxAlpha);
            }
            if (!this.direct && t >= FALL_END * 0.36F && t < FALL_END * 0.36F + 0.5F) {
                // The landing's dust ring.
                float dust = (t - FALL_END * 0.36F) / 0.5F;
                AnchorsUi.ring(graphics, Math.round(restX), Math.round(restY + anchorScale * 0.6F),
                        Math.round(anchorScale * (0.6F + dust * 1.4F)), 1, AnchorsTheme.withAlpha(0xE9D5FF, Math.round(200 * (1.0F - dust))));
            }
            if (this.answer == Answer.BRIDGE && since < 0.6F) {
                float ring = since / 0.6F;
                AnchorsUi.ring(graphics, Math.round(restX), Math.round(restY), Math.round(anchorScale * (0.7F + ring * 2.2F)), 2,
                        AnchorsTheme.withAlpha(AnchorFx.GREEN, Math.round(230 * (1.0F - ring))));
                AnchorsUi.glowEllipse(graphics, Math.round(restX), Math.round(restY), Math.round(anchorScale * 1.6F),
                        Math.round(anchorScale * 1.4F), AnchorFx.GREEN, 0.6F * (1.0F - ring));
            }
            this.figure.draw(graphics, x, y, drawScale, Math.max(windowAlpha, flight > 0.0F ? 1.0F : 0.0F), 0.0F, this.motion,
                    true, true, tintColor, tint);
            if (this.answer == Answer.MISSING) {
                drawLock(graphics, Math.round(x), Math.round(y), anchorScale, since, boxAlpha);
            }
        }

        // The text under the stage.
        int lineY = content.bottom() - textHeight;
        int textColor = this.answer == Answer.MISSING ? 0xFFFFE4EA : AnchorsTheme.TEXT;
        for (int index = 0; index < Math.min(lines.size(), 5); index++) {
            FormattedCharSequence line = lines.get(index);
            AnchorsUi.line(graphics, font, line, box.centerX() - font.width(line) / 2, lineY, AnchorsTheme.fade(textColor, boxAlpha));
            lineY += 10;
        }

        // The buttons, once there is an answer that waits for the player.
        if (this.answer == Answer.MISSING || this.answer == Answer.OFFLINE) {
            float buttons = AnchorsTheme.clamp01((since - (this.answer == Answer.MISSING ? LOCK_LANDED : 0.2F)) / 0.25F) * fade;
            AnchorsLayout.Rect cancel = modal.cancel();
            AnchorsLayout.Rect confirm = modal.confirm();
            this.cancelHover += ((cancel.contains(mouseX, mouseY) ? 1.0F : 0.0F) - this.cancelHover) * response;
            this.confirmHover += ((confirm.contains(mouseX, mouseY) ? 1.0F : 0.0F) - this.confirmHover) * response;
            if (this.answer == Answer.MISSING) {
                AnchorsButton.draw(graphics, cancel.x(), cancel.y(), cancel.width(), cancel.height(),
                        Component.translatable("kohs_anchors.bridge.plugin").getString(), false, false, this.cancelHover, 0.0F,
                        buttons, -1.0F);
            }
            AnchorsButton.draw(graphics, confirm.x(), confirm.y(), confirm.width(), confirm.height(),
                    Component.translatable("kohs_anchors.bridge.close").getString(), true, this.answer == Answer.MISSING,
                    this.confirmHover, 0.0F, buttons, -1.0F);
        }
        DevInspector.node("ServerCheckWindow", this.answer.name(), box.x(), box.y(), box.width(), box.height(),
                "BridgeClient.state() = " + BridgeClient.state(), "policy " + Integer.toBinaryString(BridgeClient.policy()),
                "platform " + BridgeClient.platform() + " · bridge " + BridgeClient.bridgeVersion());
    }

    private Component bodyText() {
        String address = ServerLock.address();
        return switch (this.answer) {
            case NONE -> Component.translatable("kohs_anchors.bridge.check.body", address.isEmpty() ? "—" : address);
            case BRIDGE -> BridgeClient.state() == BridgeClient.State.LOCAL
                    ? Component.translatable("kohs_anchors.bridge.ok.local.body")
                    : Component.translatable("kohs_anchors.bridge.ok.body", BridgeClient.platform(), BridgeClient.bridgeVersion());
            case MISSING -> BridgeClient.state() == BridgeClient.State.NO_API
                    ? Component.translatable("kohs_anchors.bridge.noapi.body", address)
                    : Component.translatable("kohs_anchors.bridge.fail.body", address);
            case OFFLINE -> Component.translatable("kohs_anchors.bridge.offline.body");
        };
    }

    /** While the bridge is asked: rings scanning the anchor, and packets to a server and back. */
    private void drawScan(GuiGraphicsExtractor graphics, Font font, int centerX, int centerY, float scale,
            AnchorsLayout.Rect box, double seconds, float alpha) {
        int radius = Math.round(scale * 1.25F);
        for (int ring = 0; ring < 3; ring++) {
            double phase = (seconds * 0.9D + ring / 3.0D) % 1.0D;
            AnchorsUi.ring(graphics, centerX, centerY, Math.round(radius * (0.7F + (float) phase * 0.9F)), 1,
                    AnchorsTheme.withAlpha(0xC084FC, Math.round(200 * (1.0F - (float) phase) * alpha)));
        }
        AnchorFx.radar(graphics, centerX, centerY, radius, seconds * 3.4D, 0xE9D5FF, 0.8F * alpha);
        int serverSize = Math.max(10, Math.round(scale * 0.7F));
        int serverX = Math.min(box.right() - serverSize - 14, centerX + Math.round(scale * 3.2F));
        int serverY = centerY - serverSize / 2;
        AnchorFx.server(graphics, serverX, serverY, serverSize, 0xFFC084FC, alpha, seconds);
        int fromX = centerX + Math.round(scale * 1.1F);
        int toX = serverX - 3;
        if (toX > fromX + 8) {
            for (int dot = fromX; dot < toX; dot += 4) {
                graphics.fill(dot, centerY, dot + 2, centerY + 1, AnchorsTheme.withAlpha(0x7C3AED, Math.round(160 * alpha)));
            }
            for (int packet = 0; packet < 3; packet++) {
                double phase = (seconds * 1.3D + packet / 3.0D) % 1.0D;
                boolean back = packet % 2 == 1;
                int px = (int) Math.round(back ? toX - (toX - fromX) * phase : fromX + (toX - fromX) * phase);
                graphics.fill(px - 1, centerY - 1, px + 2, centerY + 2,
                        AnchorsTheme.withAlpha(back ? 0xE9D5FF : 0xC084FC, Math.round(255 * alpha)));
            }
        }
    }

    /** The padlock falls onto the anchor, lands with a clank and stays, shaking once. */
    private void drawLock(GuiGraphicsExtractor graphics, int centerX, int centerY, float scale, float since, float alpha) {
        if (since < LOCK_DROP) {
            return;
        }
        float drop = AnchorFx.bounce((since - LOCK_DROP) / (LOCK_LANDED - LOCK_DROP));
        int size = Math.max(10, Math.round(scale * 0.95F));
        int restTop = centerY - Math.round(size * 0.2F);
        int startTop = restTop - Math.round(scale * 4.0F);
        int top = Math.round(startTop + (restTop - startTop) * drop);
        if (this.sounds < 2 && since >= LOCK_DROP + (LOCK_LANDED - LOCK_DROP) * 0.36F) {
            play(SoundEvents.ANVIL_LAND, 1.4F, 0.4F);
            play(SoundEvents.IRON_DOOR_CLOSE, 0.6F, 0.7F);
            this.sounds = 2;
        }
        if (since >= LOCK_LANDED) {
            AnchorsUi.glowEllipse(graphics, centerX, top + size / 3, Math.round(size * 1.1F), Math.round(size * 0.9F), 0xFF315C,
                    0.35F * alpha);
        }
        AnchorFx.padlock(graphics, centerX, top, size, alpha);
    }

    private static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }
}
