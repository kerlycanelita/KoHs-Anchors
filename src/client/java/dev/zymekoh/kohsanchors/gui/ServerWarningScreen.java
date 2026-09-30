package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.compat.Mc;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.safety.ServerLock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

/**
 * The second warning: on a server that is neither local nor allowed, with a not secure option on.
 *
 * <p>The options stay suspended on this server unless the player holds "This server allows them"
 * for two seconds; a click, Escape or closing the screen keeps them off. The server is named in the
 * warning, and an allowed server is remembered and listed in the advanced tab, where it can be
 * forgotten.</p>
 */
public final class ServerWarningScreen extends Screen {
    private static final long OPEN_NANOS = 260_000_000L;
    private static final long HOLD_NANOS = 2_000_000_000L;
    private static final long CLOSE_NANOS = 180_000_000L;

    private final String address;
    private final long openedAt = System.nanoTime();
    private long holdStartedAt = -1L;
    private long closingAt = -1L;
    private boolean allowed;
    private float keepHover;
    private float allowHover;
    private long lastFrame = System.nanoTime();

    public ServerWarningScreen(String address) {
        super(Component.translatable("kohs_anchors.server.title"));
        this.address = address;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // The world stays visible: this warns about it, it does not hide it.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        float frameMillis = Math.min(50.0F, (now - this.lastFrame) / 1_000_000.0F);
        this.lastFrame = now;
        float response = 1.0F - (float) Math.exp(-frameMillis / 60.0F);
        boolean motion = AnchorsConfig.settings().interfaceMotion;
        double seconds = now / 1_000_000_000.0D;
        float open = motion ? AnchorsTheme.easeOutCubic((now - this.openedAt) / (float) OPEN_NANOS) : 1.0F;
        if (this.closingAt >= 0L) {
            float t = Math.min(1.0F, (now - this.closingAt) / (float) CLOSE_NANOS);
            open = 1.0F - t;
            if (t >= 1.0F) {
                Mc.setScreen(this.minecraft, null);
                return;
            }
        }
        dim(graphics, open, seconds, motion);

        AnchorsLayout.Modal modal = AnchorsLayout.modal(this.width, this.height);
        AnchorsLayout.Rect box = modal.box();
        AnchorsUi.halo(graphics, box.x(), box.y(), box.width(), box.height(), AnchorsTheme.CRIMSON, 6, 0.8F * open);
        AnchorsUi.panel(graphics, box.x(), box.y(), box.width(), box.height(),
                AnchorsTheme.fade(AnchorsTheme.CRIMSON_GLASS_TOP, open), AnchorsTheme.fade(AnchorsTheme.CRIMSON_GLASS_BOTTOM, open));
        AnchorsUi.roundedOutline(graphics, box.x(), box.y(), box.width(), box.height(),
                AnchorsTheme.fade(AnchorsTheme.CRIMSON_BRIGHT, open));
        AnchorsUi.bladeCorners(graphics, box.x(), box.y(), box.width(), box.height(), 7, AnchorsTheme.fade(0xFFFFD6DE, open));
        if (open < 0.98F) {
            return;
        }
        if (motion) {
            AnchorsUi.comets(graphics, box.x(), box.y(), box.width(), box.height(), seconds, 0xFF9AB0);
        }

        int padding = modal.padding();
        int innerLeft = box.x() + padding;
        int innerWidth = box.width() - padding * 2;
        int y = box.y() + padding;
        int bottomLimit = modal.cancel().y() - 6;

        // "2" in a warning glyph: this is the second of the two warnings.
        AnchorsUi.warningGlyph(graphics, box.centerX(), y, 15, AnchorsTheme.CRIMSON_BRIGHT, 0xFF1A0308);
        y += 19;
        String title = Component.translatable("kohs_anchors.server.title").getString();
        float scale = Math.max(1.0F, Math.min(2.4F, innerWidth / (float) Math.max(1, this.font.width(title))));
        if (motion && (seconds % 3.1D) < 0.06D) {
            AnchorsUi.bigText(graphics, this.font, title, box.centerX() - 2, y, scale, 0x9052F2FF, false);
            AnchorsUi.bigText(graphics, this.font, title, box.centerX() + 2, y, scale, 0x90E83EAF, false);
        }
        AnchorsUi.bigText(graphics, this.font, title, box.centerX() + 1, y + 1, scale, 0xFFA31234, false);
        AnchorsUi.bigText(graphics, this.font, title, box.centerX(), y, scale, 0xFFFFF7FF, false);
        y += Math.round(9 * scale) + 5;

        // The server, in a chip.
        String server = AnchorsUi.fit(this.font, this.address.isEmpty() ? "?" : this.address, innerWidth - 12);
        int chipWidth = this.font.width(server) + 12;
        int chipX = box.centerX() - chipWidth / 2;
        AnchorsUi.panel(graphics, chipX, y - 2, chipWidth, 13, 0xC0500A1E, 0xC02A0510);
        AnchorsUi.roundedOutline(graphics, chipX, y - 2, chipWidth, 13, AnchorsTheme.CRIMSON_BRIGHT);
        AnchorsUi.label(graphics, this.font, server, chipX + 6, y + 1, 0xFFFFE4EA, false);
        y += 16;
        AnchorsUi.energyLine(graphics, innerLeft, innerLeft + innerWidth, y - 2, AnchorsTheme.CRIMSON_BRIGHT, seconds, 1.0F);

        List<FormattedCharSequence> lines = new ArrayList<>(this.font.split(
                Component.translatable("kohs_anchors.server.body", activeOptions()), Math.max(40, innerWidth)));
        for (FormattedCharSequence line : lines) {
            if (y + 9 > bottomLimit) {
                break;
            }
            AnchorsUi.line(graphics, this.font, line, box.centerX() - this.font.width(line) / 2, y, AnchorsTheme.TEXT);
            y += 10;
        }

        AnchorsLayout.Rect keep = modal.cancel();
        AnchorsLayout.Rect allow = modal.confirm();
        boolean overKeep = keep.contains(mouseX, mouseY);
        boolean overAllow = allow.contains(mouseX, mouseY);
        this.keepHover += ((overKeep ? 1.0F : 0.0F) - this.keepHover) * response;
        this.allowHover += ((overAllow ? 1.0F : 0.0F) - this.allowHover) * response;
        AnchorsButton.draw(graphics, keep.x(), keep.y(), keep.width(), keep.height(),
                Component.translatable("kohs_anchors.server.keep").getString(), true, false, this.keepHover, 0.0F, 1.0F, -1.0F);
        float hold = this.holdStartedAt < 0L ? 0.0F : Math.min(1.0F, (now - this.holdStartedAt) / (float) HOLD_NANOS);
        if (this.holdStartedAt >= 0L && !overAllow) {
            // Sliding off the button lets go of it.
            this.holdStartedAt = -1L;
            hold = 0.0F;
        }
        String allowText = Component.translatable(hold > 0.0F ? "kohs_anchors.server.holding" : "kohs_anchors.server.allow")
                .getString();
        AnchorsButton.draw(graphics, allow.x(), allow.y(), allow.width(), allow.height(), allowText, false, true,
                this.allowHover, 0.0F, 1.0F, hold > 0.0F ? hold : -1.0F);
        if (hold >= 1.0F && !this.allowed) {
            this.allowed = true;
            ServerLock.allowCurrent();
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.RESPAWN_ANCHOR_SET_SPAWN, 1.2F, 0.7F));
            close();
        }
    }

    /** The names of the not secure options that are switched on. */
    private static String activeOptions() {
        AnchorsConfig.Settings settings = AnchorsConfig.settings();
        List<String> names = new ArrayList<>();
        if (settings.fastChain) {
            names.add(Component.translatable("kohs_anchors.option.fast_chain").getString());
        }
        if (settings.instantDetonation) {
            names.add(Component.translatable("kohs_anchors.option.instant_detonation").getString());
        }
        return String.join(", ", names);
    }

    private void dim(GuiGraphicsExtractor graphics, float strength, double seconds, boolean motion) {
        graphics.fill(0, 0, this.width, this.height, AnchorsTheme.withAlpha(0x06020A, Math.round(170 * strength)));
        float pulse = motion ? 0.55F + 0.45F * AnchorsTheme.pulse(seconds, 1.8D) : 0.7F;
        int depth = Math.max(8, this.height / 5);
        graphics.fillGradient(0, 0, this.width, depth, AnchorsTheme.withAlpha(0xA31234, Math.round(120 * pulse * strength)),
                0x00000000);
        graphics.fillGradient(0, this.height - depth, this.width, this.height, 0x00000000,
                AnchorsTheme.withAlpha(0xA31234, Math.round(120 * pulse * strength)));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != Keys.LEFT_BUTTON || this.closingAt >= 0L) {
            return true;
        }
        AnchorsLayout.Modal modal = AnchorsLayout.modal(this.width, this.height);
        if (modal.cancel().contains(event.x(), event.y())) {
            close();
        } else if (modal.confirm().contains(event.x(), event.y())) {
            this.holdStartedAt = System.nanoTime();
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 0.8F));
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        this.holdStartedAt = -1L;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == Keys.ESCAPE || Keys.confirms(event.key())) {
            // Escape and Enter both keep the options off: allowing takes the deliberate hold.
            close();
            return true;
        }
        return true;
    }

    @Override
    public void onClose() {
        close();
    }

    private void close() {
        if (this.closingAt < 0L) {
            this.closingAt = System.nanoTime();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    /** For the inspector: what this screen is. */
    static String describe(String address) {
        return "ServerWarningScreen(address=" + address.toLowerCase(Locale.ROOT) + ")";
    }
}
