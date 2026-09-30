package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import dev.zymekoh.kohsanchors.integration.HerziumBridge;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

/**
 * Herzium's window, in two forms.
 *
 * <p>{@link Kind#MISSING}, from the dimmed tab when Herzium is not installed: what it adds to
 * anchors, Modrinth's logo turning the way Modrinth's own animated logo does (the outer ring one
 * way, the inner ring the other, the wrench still), and a link to Herzium's page there, opened
 * through Vanilla's link confirmation.</p>
 *
 * <p>{@link Kind#GUIDE}, when the tab opens with Herzium installed: how the two mods work together
 * and Herzium's three orders, last input first and marked as the one recommended for anchors. The
 * order in use is Herzium's own; a click on another changes it through Herzium, which saves it in
 * its file. "Don't show again" is a switch before "Continue".</p>
 *
 * <p>Geometry comes from {@link AnchorsLayout#infoModal}; what goes inside is arranged by
 * {@link #parts}, which drawing and clicks share, and drops the art before the text.</p>
 */
final class HerziumWindow {
    enum Kind { MISSING, GUIDE }

    private enum Phase { OPEN, CLOSING, DONE }

    private static final long OPEN_NANOS = 340_000_000L;
    private static final long CLOSE_NANOS = 180_000_000L;
    private static final long FLASH_NANOS = 420_000_000L;
    private static final int MODRINTH_GREEN = 0x1BD96A;
    private static final int TEXTURE = 256;
    private static final Identifier OUTER = texture("modrinth_outer");
    private static final Identifier INNER = texture("modrinth_inner");
    private static final Identifier MARK = texture("modrinth_mark");
    private static final Identifier HERZIUM_ICON = texture("herzium");
    /** The cards, in the order they are shown: the recommended one first. */
    private static final String[] CARD_ORDERS = {"HERZIUM", "VANILLA", "VANILLA_REVERSED"};

    private final Kind kind;
    private final boolean motion;
    private final Screen screen;
    private final long openedAt = System.nanoTime();
    private Phase phase = Phase.OPEN;
    private long closedAt;
    private boolean continued;
    private boolean hide;
    private float cancelHover;
    private float confirmHover;
    private float linkHover;
    private final float[] cardHover = new float[CARD_ORDERS.length];
    private int flashCard = -1;
    private long flashAt;
    private long lastFrame = this.openedAt;

    HerziumWindow(Kind kind, boolean motion, Screen screen) {
        this.kind = kind;
        this.motion = motion;
        this.screen = screen;
        if (kind == Kind.MISSING) {
            play(SoundEvents.AMETHYST_BLOCK_CHIME, 1.25F, 0.8F);
        } else {
            play(SoundEvents.TRIDENT_RIPTIDE_1.value(), 1.7F, 0.25F);
        }
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath(KoHsAnchorsClient.MOD_ID, "textures/gui/" + name + ".png");
    }

    Kind kind() {
        return this.kind;
    }

    boolean done() {
        return this.phase == Phase.DONE;
    }

    /** Whether the player ticked "Don't show again" and continued. */
    boolean hideFromNowOn() {
        return this.continued && this.hide;
    }

    // ------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------

    boolean mouseClicked(Font font, int width, int height, double mouseX, double mouseY, int button) {
        if (this.phase != Phase.OPEN || button != Keys.LEFT_BUTTON) {
            return true;
        }
        AnchorsLayout.InfoModal modal = AnchorsLayout.infoModal(width, height);
        Parts parts = parts(font, modal);
        if (this.kind == Kind.MISSING) {
            if (modal.confirm().contains(mouseX, mouseY) || parts.link().contains(mouseX, mouseY)
                    || parts.art().contains(mouseX, mouseY)) {
                openModrinth();
            } else if (modal.cancel().contains(mouseX, mouseY)) {
                click(1.0F);
                close();
            }
            return true;
        }
        if (modal.cancel().contains(mouseX, mouseY)) {
            this.hide = !this.hide;
            click(this.hide ? 1.2F : 0.9F);
        } else if (modal.confirm().contains(mouseX, mouseY)) {
            proceed();
        } else if (HerziumBridge.orderAvailable()) {
            for (int index = 0; index < parts.cards().length; index++) {
                if (parts.cards()[index].contains(mouseX, mouseY)) {
                    choose(index);
                    break;
                }
            }
        }
        return true;
    }

    boolean keyPressed(int key) {
        if (this.phase != Phase.OPEN) {
            return true;
        }
        if (key == Keys.ESCAPE) {
            close();
        } else if (Keys.confirms(key)) {
            if (this.kind == Kind.MISSING) {
                openModrinth();
            } else {
                proceed();
            }
        } else if (key == Keys.SPACE && this.kind == Kind.GUIDE) {
            this.hide = !this.hide;
        }
        return true;
    }

    private void choose(int index) {
        String order = CARD_ORDERS[index];
        this.flashCard = index;
        this.flashAt = System.nanoTime();
        if (!order.equals(HerziumBridge.hotbarOrder())) {
            HerziumBridge.selectHotbarOrder(order);
            play(SoundEvents.TRIDENT_RIPTIDE_1.value(), order.equals(HerziumBridge.RECOMMENDED) ? 1.9F : 1.4F, 0.2F);
        }
        click(1.1F);
    }

    private void openModrinth() {
        click(1.0F);
        ConfirmLinkScreen.confirmLinkNow(this.screen, URI.create(HerziumBridge.MODRINTH_URL));
    }

    private void proceed() {
        this.continued = true;
        click(1.0F);
        close();
    }

    private void close() {
        this.phase = Phase.CLOSING;
        this.closedAt = System.nanoTime();
    }

    private static void click(float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, pitch));
    }

    private static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    // ------------------------------------------------------------------------------------------
    // Arrangement
    // ------------------------------------------------------------------------------------------

    /**
     * Where everything goes inside the content area. The missing form: the logo, the link and the
     * text, side by side when there is width, stacked otherwise. The guide: Herzium's icon and the
     * text, then the three cards and the line on where the order lives.
     */
    private record Parts(AnchorsLayout.Rect art, AnchorsLayout.Rect text, List<FormattedCharSequence> lines,
            int linkLines, AnchorsLayout.Rect link, AnchorsLayout.Rect[] cards, boolean cardText,
            AnchorsLayout.Rect source) {
    }

    private Parts parts(Font font, AnchorsLayout.InfoModal modal) {
        return this.kind == Kind.MISSING ? missingParts(font, modal) : guideParts(font, modal);
    }

    private static Parts missingParts(Font font, AnchorsLayout.InfoModal modal) {
        AnchorsLayout.Rect content = modal.content();
        String requires = text("kohs_anchors.herzium.window.requires");
        String link = text("kohs_anchors.herzium.window.link");
        Component body = Component.translatable("kohs_anchors.herzium.window.missing_body");
        boolean side = content.width() >= 330 && content.height() >= 80;
        // The logo's sigil reaches past it by about a sixth on every side: room is kept for it.
        int artSize = side ? Math.min(112, Math.min(content.height() - 24, Math.round(content.width() * 0.24F))) : 0;
        if (side && artSize < 44) {
            side = false;
            artSize = 0;
        }
        int reach = Math.round(artSize * 0.18F);
        int textX = side ? content.x() + reach * 2 + artSize + 12 : content.x();
        int textWidth = content.right() - textX;
        int linkLines = font.width(requires + " " + link) <= textWidth ? 1 : 2;
        List<FormattedCharSequence> lines = font.split(body, Math.max(40, textWidth));
        int linkHeight = linkLines * 10 + 5;
        if (!side) {
            // Stacked: the logo takes what the text leaves, and goes when that is too little.
            int textHeight = linkHeight + lines.size() * 10;
            artSize = Math.min(Math.min(84, Math.round(content.width() * 0.4F)), content.height() - textHeight - 6);
            if (artSize < 36) {
                artSize = 0;
            }
        }
        int roomForLines = (content.height() - (side ? 0 : artSize > 0 ? artSize + 6 : 0) - linkHeight) / 10;
        if (lines.size() > roomForLines) {
            lines = lines.subList(0, Math.max(0, roomForLines));
        }
        int textHeight = linkHeight + lines.size() * 10;
        AnchorsLayout.Rect art;
        int textY;
        if (side) {
            art = new AnchorsLayout.Rect(content.x() + reach, content.y() + (content.height() - artSize) / 2, artSize,
                    artSize);
            textY = content.y() + Math.max(0, (content.height() - textHeight) / 2);
        } else {
            int group = (artSize > 0 ? artSize + 6 : 0) + textHeight;
            int top = content.y() + Math.max(0, (content.height() - group) / 2);
            art = artSize > 0 ? new AnchorsLayout.Rect(content.centerX() - artSize / 2, top, artSize, artSize)
                    : AnchorsLayout.Rect.EMPTY;
            textY = top + (artSize > 0 ? artSize + 6 : 0);
        }
        // The link: after "Requires Herzium installed;" on its line, or alone on the next.
        int linkWidth = font.width(link);
        int linkX;
        int linkY;
        if (linkLines == 1) {
            int lineWidth = font.width(requires + " " + link);
            int start = side ? textX : content.centerX() - lineWidth / 2;
            linkX = start + font.width(requires + " ");
            linkY = textY;
        } else {
            linkX = side ? textX : content.centerX() - linkWidth / 2;
            linkY = textY + 10;
        }
        AnchorsLayout.Rect linkRect = new AnchorsLayout.Rect(linkX - 2, linkY - 2, linkWidth + 4, 12);
        return new Parts(art, new AnchorsLayout.Rect(textX, textY, textWidth, textHeight), lines, linkLines, linkRect,
                new AnchorsLayout.Rect[0], false, AnchorsLayout.Rect.EMPTY);
    }

    private static Parts guideParts(Font font, AnchorsLayout.InfoModal modal) {
        AnchorsLayout.Rect content = modal.content();
        Component intro = Component.translatable("kohs_anchors.herzium.window.body");
        int gap = 3;
        int cardHeight = modal.small() ? 20 : 24;
        boolean cardText = true;
        boolean source = true;
        int artSize = content.width() >= 330 ? Math.min(56, Math.round(content.width() * 0.14F)) : 0;
        List<FormattedCharSequence> lines;
        int introHeight;
        while (true) {
            int textWidth = content.width() - (artSize > 0 ? artSize + 12 : 0);
            lines = font.split(intro, Math.max(40, textWidth));
            introHeight = Math.max(artSize, lines.size() * 10);
            int cards = CARD_ORDERS.length * cardHeight + (CARD_ORDERS.length - 1) * gap;
            int needed = introHeight + 6 + cards + (source ? 14 : 0);
            if (needed <= content.height()) {
                break;
            }
            // Short screens: the source line goes first, then the icon, then the cards' second lines,
            // then the text is cut.
            if (source) {
                source = false;
            } else if (artSize > 0) {
                artSize = 0;
            } else if (cardText) {
                cardText = false;
                cardHeight = 14;
            } else {
                int room = Math.max(10, content.height() - 6 - cards);
                lines = lines.subList(0, Math.min(lines.size(), Math.max(1, room / 10)));
                introHeight = lines.size() * 10;
                break;
            }
        }
        int cards = CARD_ORDERS.length * cardHeight + (CARD_ORDERS.length - 1) * gap;
        int total = introHeight + 6 + cards + (source ? 14 : 0);
        int y = content.y() + Math.max(0, (content.height() - total) / 2);
        AnchorsLayout.Rect art = artSize > 0
                ? new AnchorsLayout.Rect(content.x(), y + (introHeight - artSize) / 2, artSize, artSize)
                : AnchorsLayout.Rect.EMPTY;
        int textX = content.x() + (artSize > 0 ? artSize + 12 : 0);
        AnchorsLayout.Rect text = new AnchorsLayout.Rect(textX, y + (introHeight - lines.size() * 10) / 2,
                content.right() - textX, lines.size() * 10);
        y += introHeight + 6;
        AnchorsLayout.Rect[] rects = new AnchorsLayout.Rect[CARD_ORDERS.length];
        for (int index = 0; index < rects.length; index++) {
            rects[index] = new AnchorsLayout.Rect(content.x(), y, content.width(), cardHeight);
            y += cardHeight + gap;
        }
        AnchorsLayout.Rect sourceRect = source ? new AnchorsLayout.Rect(content.x(), y + 3, content.width(), 9)
                : AnchorsLayout.Rect.EMPTY;
        return new Parts(art, text, lines, 0, AnchorsLayout.Rect.EMPTY, rects, cardText, sourceRect);
    }

    // ------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------

    void render(GuiGraphicsExtractor graphics, Font font, int width, int height, int mouseX, int mouseY) {
        long now = System.nanoTime();
        float frameMillis = Math.min(50.0F, (now - this.lastFrame) / 1_000_000.0F);
        this.lastFrame = now;
        float response = 1.0F - (float) Math.exp(-frameMillis / 60.0F);
        double seconds = (now - this.openedAt) / 1_000_000_000.0D;
        float open = this.motion ? AnchorsTheme.easeOutCubic((now - this.openedAt) / (float) OPEN_NANOS) : 1.0F;
        if (this.phase == Phase.CLOSING) {
            float t = Math.min(1.0F, (now - this.closedAt) / (float) CLOSE_NANOS);
            open = Math.min(open, 1.0F - t);
            if (t >= 1.0F) {
                this.phase = Phase.DONE;
                return;
            }
        }
        AnchorsUi.isolate(graphics);
        graphics.fill(0, 0, width, height, AnchorsTheme.withAlpha(0x06020A, Math.round(190 * open)));
        if (this.motion) {
            speedStreaks(graphics, width, height, seconds, open);
        }

        AnchorsLayout.InfoModal modal = AnchorsLayout.infoModal(width, height);
        AnchorsLayout.Rect box = modal.box();
        float grow = 0.93F + 0.07F * open;
        int boxWidth = Math.round(box.width() * grow);
        int boxHeight = Math.round(box.height() * grow);
        int boxX = box.centerX() - boxWidth / 2;
        int boxY = box.centerY() - boxHeight / 2;
        float breathe = this.motion ? 0.75F + 0.25F * AnchorsTheme.pulse(seconds, 2.6D) : 1.0F;
        AnchorsUi.halo(graphics, boxX, boxY, boxWidth, boxHeight, AnchorsTheme.ACCENT, 6, 0.75F * open * breathe);
        AnchorsUi.panel(graphics, boxX, boxY, boxWidth, boxHeight, AnchorsTheme.fade(0xF4160B27, open),
                AnchorsTheme.fade(0xF20B0514, open));
        graphics.fillGradient(boxX + 1, boxY + 1, boxX + boxWidth - 1, boxY + 4, AnchorsTheme.fade(0xC0C084FC, open),
                AnchorsTheme.fade(0x00A855F7, open));
        AnchorsUi.roundedOutline(graphics, boxX, boxY, boxWidth, boxHeight, AnchorsTheme.fade(AnchorsTheme.PANEL_BORDER, open));
        AnchorsUi.bladeCorners(graphics, boxX, boxY, boxWidth, boxHeight, 8, AnchorsTheme.fade(0xE0E9D5FF, open));
        if (open < 0.95F || this.phase != Phase.OPEN) {
            return;
        }
        if (this.motion) {
            AnchorsUi.comets(graphics, box.x(), box.y(), box.width(), box.height(), seconds, 0xE8CCFF);
        }

        drawTitle(graphics, font, modal, seconds);
        Parts parts = parts(font, modal);
        float content = this.motion ? AnchorsTheme.easeOutCubic((float) ((seconds - 0.12D) / 0.3D)) : 1.0F;
        if (this.kind == Kind.MISSING) {
            drawMissing(graphics, font, parts, mouseX, mouseY, seconds, content, response);
        } else {
            drawGuide(graphics, font, parts, mouseX, mouseY, seconds, content, response, now);
        }
        drawButtons(graphics, font, modal, mouseX, mouseY, response);
        DevInspector.node("HerziumWindow", this.kind.name().toLowerCase(Locale.ROOT), box.x(), box.y(), box.width(),
                box.height(), this.kind == Kind.MISSING ? "ConfirmLinkScreen → " + HerziumBridge.MODRINTH_URL
                        : "HerziumBridge.selectHotbarOrder: Herzium's own cycleHotbarOrder()",
                "order: " + HerziumBridge.hotbarOrder() + " · " + HerziumBridge.configFile().getFileName());
    }

    private void drawTitle(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.InfoModal modal, double seconds) {
        AnchorsLayout.Rect title = modal.title();
        String eyebrow = "◆ HERZIUM";
        String text = text(this.kind == Kind.MISSING ? "kohs_anchors.herzium.window.missing_title"
                : "kohs_anchors.herzium.window.title").toUpperCase(Locale.ROOT);
        float scale = Math.max(1.0F, Math.min(modal.small() ? 1.25F : 1.8F, title.width() / (float) Math.max(1, font.width(text))));
        int textHeight = Math.round(9 * scale);
        boolean withEyebrow = !modal.small();
        int top = title.y() + Math.max(0, (title.height() - textHeight - (withEyebrow ? 10 : 0)) / 2);
        if (withEyebrow) {
            AnchorsUi.label(graphics, font, eyebrow, title.centerX() - font.width(eyebrow) / 2, top,
                    AnchorsTheme.ACCENT_BRIGHT, false);
            top += 10;
        }
        AnchorsUi.bigText(graphics, font, text, title.centerX() + 1, top + 1, scale, 0xFF3B0A78, false);
        AnchorsUi.bigText(graphics, font, text, title.centerX(), top, scale, AnchorsTheme.TITLE, false);
        // A frequency trace under the title: Herzium is about how fast the hotbar answers.
        frequency(graphics, title.x() + 4, title.right() - 4, title.bottom() + 1, modal.small() ? 1.5F : 2.5F, seconds);
    }

    private void drawMissing(GuiGraphicsExtractor graphics, Font font, Parts parts, int mouseX, int mouseY, double seconds,
            float appear, float response) {
        boolean hovered = parts.link().contains(mouseX, mouseY) || parts.art().contains(mouseX, mouseY);
        this.linkHover += ((hovered ? 1.0F : 0.0F) - this.linkHover) * response;
        AnchorsLayout.Rect art = parts.art();
        if (art.width() > 0) {
            float pop = this.motion ? AnchorsTheme.easeOutBack((float) (seconds / 0.5D)) : 1.0F;
            modrinthLogo(graphics, art.centerX(), art.y() + art.height() / 2.0F, art.width() * pop, seconds, appear);
        }
        String requires = text("kohs_anchors.herzium.window.requires");
        String link = text("kohs_anchors.herzium.window.link");
        AnchorsLayout.Rect linkRect = parts.link();
        int linkX = linkRect.x() + 2;
        int linkY = linkRect.y() + 2;
        if (parts.linkLines() == 1) {
            AnchorsUi.label(graphics, font, requires, linkX - font.width(requires + " "), linkY,
                    AnchorsTheme.fade(AnchorsTheme.TITLE, appear), true);
        } else {
            AnchorsLayout.Rect text = parts.text();
            int requiresX = text.x() + (art.width() > 0 && art.x() < text.x() ? 0 : (text.width() - font.width(requires)) / 2);
            AnchorsUi.label(graphics, font, requires, requiresX, linkY - 10, AnchorsTheme.fade(AnchorsTheme.TITLE, appear), true);
        }
        int green = AnchorsTheme.lerp(0xFF000000 | MODRINTH_GREEN, 0xFFB9FFD6, this.linkHover);
        if (this.linkHover > 0.05F) {
            AnchorsUi.halo(graphics, linkRect.x(), linkRect.y(), linkRect.width(), linkRect.height(),
                    0xFF000000 | MODRINTH_GREEN, 2, this.linkHover * appear);
        }
        AnchorsUi.label(graphics, font, link, linkX, linkY, AnchorsTheme.fade(green, appear), true);
        graphics.fill(linkX, linkY + 9, linkX + font.width(link), linkY + 10, AnchorsTheme.fade(green, appear * (0.5F
                + 0.5F * this.linkHover)));
        AnchorsLayout.Rect text = parts.text();
        int y = text.y() + parts.linkLines() * 10 + 5;
        boolean centred = art.width() == 0 || art.y() + art.height() <= text.y();
        for (FormattedCharSequence line : parts.lines()) {
            int x = centred ? text.x() + (text.width() - font.width(line)) / 2 : text.x();
            AnchorsUi.line(graphics, font, line, x, y, AnchorsTheme.fade(AnchorsTheme.TEXT_MUTED, appear));
            y += 10;
        }
    }

    /** Modrinth's logo: the outer ring turning one way, the inner ring the other, the wrench still. */
    private void modrinthLogo(GuiGraphicsExtractor graphics, float centerX, float centerY, float size, double seconds,
            float alpha) {
        if (size < 8.0F) {
            return;
        }
        float hover = this.linkHover;
        AnchorsUi.glowEllipse(graphics, Math.round(centerX), Math.round(centerY), Math.round(size * 0.62F),
                Math.round(size * 0.62F), MODRINTH_GREEN, (0.28F + 0.22F * hover) * alpha);
        AnchorsUi.sigil(graphics, Math.round(centerX), Math.round(centerY), Math.round(size * 0.66F),
                this.motion ? seconds * 1.4D : 0.0D, AnchorsTheme.ACCENT, 0.55F * alpha, 4.0F);
        int tint = AnchorsTheme.fade(AnchorsTheme.lerp(0xFF000000 | MODRINTH_GREEN, 0xFFC8FFE0, hover * 0.35F), alpha);
        double turn = this.motion ? seconds : 0.0D;
        layer(graphics, OUTER, centerX, centerY, size, (float) (turn / 4.0D * Math.PI * 2.0D), tint);
        layer(graphics, INNER, centerX, centerY, size, (float) (-turn / 6.0D * Math.PI * 2.0D), tint);
        layer(graphics, MARK, centerX, centerY, size, 0.0F, tint);
    }

    private static void layer(GuiGraphicsExtractor graphics, Identifier texture, float centerX, float centerY, float size,
            float angle, int tint) {
        int drawn = Math.max(1, Math.round(size));
        graphics.pose().pushMatrix();
        graphics.pose().translate(centerX, centerY);
        if (angle != 0.0F) {
            graphics.pose().rotate(angle);
        }
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, -drawn / 2, -drawn / 2, 0.0F, 0.0F, drawn, drawn, TEXTURE,
                TEXTURE, TEXTURE, TEXTURE, tint);
        graphics.pose().popMatrix();
    }

    private void drawGuide(GuiGraphicsExtractor graphics, Font font, Parts parts, int mouseX, int mouseY, double seconds,
            float appear, float response, long now) {
        AnchorsLayout.Rect art = parts.art();
        if (art.width() > 0) {
            herziumIcon(graphics, art, seconds, appear);
        }
        AnchorsLayout.Rect text = parts.text();
        int y = text.y();
        for (FormattedCharSequence line : parts.lines()) {
            AnchorsUi.line(graphics, font, line, text.x(), y, AnchorsTheme.fade(AnchorsTheme.TEXT, appear));
            y += 10;
        }
        String current = HerziumBridge.hotbarOrder();
        boolean changeable = HerziumBridge.orderAvailable();
        AnchorsLayout.Rect[] cards = parts.cards();
        for (int index = 0; index < cards.length; index++) {
            AnchorsLayout.Rect card = cards[index];
            boolean hovered = changeable && card.contains(mouseX, mouseY);
            this.cardHover[index] += ((hovered ? 1.0F : 0.0F) - this.cardHover[index]) * response;
            float stagger = this.motion ? AnchorsTheme.easeOutCubic((float) ((seconds - 0.18D - index * 0.07D) / 0.28D))
                    : 1.0F;
            drawCard(graphics, font, card, index, CARD_ORDERS[index].equals(current), parts.cardText(), seconds, stagger,
                    now);
        }
        AnchorsLayout.Rect source = parts.source();
        if (source.width() > 0) {
            String line = AnchorsUi.ellipsis(font, Component.translatable("kohs_anchors.herzium.window.source",
                    HerziumBridge.configFile().getFileName().toString()).getString(), source.width());
            AnchorsUi.label(graphics, font, line, source.centerX() - font.width(line) / 2, source.y(),
                    AnchorsTheme.fade(AnchorsTheme.TEXT_DIM, appear), false);
        }
    }

    private void drawCard(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.Rect card, int index, boolean inUse,
            boolean withText, double seconds, float appear, long now) {
        if (appear <= 0.02F) {
            return;
        }
        float hover = this.cardHover[index];
        boolean recommended = CARD_ORDERS[index].equals(HerziumBridge.RECOMMENDED);
        int slide = Math.round((1.0F - appear) * 10.0F);
        int x = card.x() + slide;
        int top = inUse ? AnchorsTheme.lerp(0xC0401A78, 0xD0552290, hover) : AnchorsTheme.lerp(0x801D0D32, 0xB02A1248, hover);
        int bottom = inUse ? 0xC0200C3C : AnchorsTheme.lerp(0x7012091F, 0x901D0D32, hover);
        AnchorsUi.panel(graphics, x, card.y(), card.width() - slide, card.height(), AnchorsTheme.fade(top, appear),
                AnchorsTheme.fade(bottom, appear));
        int border = inUse ? AnchorsTheme.ACCENT_BRIGHT : AnchorsTheme.lerp(AnchorsTheme.CARD_BORDER, AnchorsTheme.CARD_BORDER_HOVER,
                hover);
        AnchorsUi.roundedOutline(graphics, x, card.y(), card.width() - slide, card.height(), AnchorsTheme.fade(border, appear));
        if (inUse) {
            // The order in use: an accent bar on its left edge.
            graphics.fill(x + 1, card.y() + 3, x + 3, card.bottom() - 3, AnchorsTheme.fade(AnchorsTheme.ACCENT_BRIGHT, appear));
        }
        long sinceFlash = now - this.flashAt;
        if (this.flashCard == index && sinceFlash < FLASH_NANOS) {
            float flash = 1.0F - sinceFlash / (float) FLASH_NANOS;
            graphics.fill(x + 1, card.y() + 1, card.right() - 1, card.bottom() - 1,
                    AnchorsTheme.withAlpha(0xF5DCFF, Math.round(110 * flash * flash)));
        }
        int nameY = withText ? card.y() + 3 : card.y() + (card.height() - 8) / 2;
        String name = text(orderKey(CARD_ORDERS[index]));
        // Tags on the name's line, on the right: in use, and recommended. The name stops before
        // them; the description below has the whole width.
        int chipY = nameY;
        int right = card.right() - 5;
        if (inUse) {
            right = chip(graphics, font, text("kohs_anchors.herzium.window.in_use").toUpperCase(Locale.ROOT), right, chipY,
                    AnchorsTheme.ACCENT_BRIGHT, 0xC0401A78, appear) - 4;
        }
        String recommendedTag = "★ " + text("kohs_anchors.herzium.window.recommended").toUpperCase(Locale.ROOT);
        if (recommended && right - x > font.width(name) + font.width(recommendedTag) + 24) {
            float pulse = this.motion ? 0.6F + 0.4F * AnchorsTheme.pulse(seconds, 1.8D) : 1.0F;
            right = chip(graphics, font, recommendedTag, right, chipY, AnchorsTheme.lerp(0xFFFF8AD8, 0xFFFFD6F2, pulse),
                    0xC0501040, appear) - 4;
        }
        int room = right - (x + 8) - 4;
        AnchorsUi.label(graphics, font, AnchorsUi.ellipsis(font, name, room), x + 8, nameY,
                AnchorsTheme.fade(inUse ? AnchorsTheme.TITLE : AnchorsTheme.lerp(AnchorsTheme.TEXT, 0xFFFFFFFF, hover), appear),
                true);
        if (withText) {
            String description = AnchorsUi.ellipsis(font, text(orderKey(CARD_ORDERS[index]) + ".description"),
                    card.width() - slide - 16);
            AnchorsUi.label(graphics, font, description, x + 8, card.y() + 13,
                    AnchorsTheme.fade(AnchorsTheme.TEXT_MUTED, appear), false);
        }
    }

    /** A small tag ending at {@code right}; returns where it starts. */
    private static int chip(GuiGraphicsExtractor graphics, Font font, String text, int right, int textY, int color,
            int fill, float alpha) {
        int width = font.width(text) + 8;
        int x = right - width;
        AnchorsUi.panel(graphics, x, textY - 2, width, 12, AnchorsTheme.fade(fill, alpha),
                AnchorsTheme.fade(0xC00C0514, alpha));
        AnchorsUi.roundedOutline(graphics, x, textY - 2, width, 12, AnchorsTheme.fade(color, alpha * 0.8F));
        AnchorsUi.label(graphics, font, text, x + 4, textY, AnchorsTheme.fade(color, alpha), false);
        return x;
    }

    /** Herzium's own icon, with speed lines streaming off its left side. */
    private void herziumIcon(GuiGraphicsExtractor graphics, AnchorsLayout.Rect art, double seconds, float alpha) {
        int size = art.width();
        AnchorsUi.glowEllipse(graphics, art.centerX(), art.centerY(), Math.round(size * 0.7F), Math.round(size * 0.7F),
                0x9B4DFF, 0.45F * alpha);
        if (this.motion) {
            for (int streak = 0; streak < 5; streak++) {
                double phase = (seconds * (1.3D + streak * 0.17D) + streak * 0.29D) % 1.0D;
                int length = 6 + (streak * 5) % 11;
                int y = art.y() + Math.round(size * (0.28F + streak * 0.11F));
                int x = art.x() + 2 - (int) Math.round(phase * size * 0.55D);
                int fade = Math.round(200 * (1.0F - (float) phase) * alpha);
                graphics.fill(x - length, y, x, y + 1, AnchorsTheme.withAlpha(0xE9D5FF, fade / 3));
                graphics.fill(x - length / 3, y, x, y + 1, AnchorsTheme.withAlpha(0xFFFFFF, fade));
            }
        }
        float bob = this.motion ? (float) Math.sin(seconds * 2.2D) * 1.2F : 0.0F;
        graphics.pose().pushMatrix();
        graphics.pose().translate(art.x(), art.y() + bob);
        graphics.blit(RenderPipelines.GUI_TEXTURED, HERZIUM_ICON, 0, 0, 0.0F, 0.0F, size, size, TEXTURE, TEXTURE, TEXTURE,
                TEXTURE, AnchorsTheme.fade(0xFFFFFFFF, alpha));
        graphics.pose().popMatrix();
    }

    private void drawButtons(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.InfoModal modal, int mouseX, int mouseY,
            float response) {
        AnchorsLayout.Rect cancel = modal.cancel();
        AnchorsLayout.Rect confirm = modal.confirm();
        this.cancelHover += ((cancel.contains(mouseX, mouseY) ? 1.0F : 0.0F) - this.cancelHover) * response;
        this.confirmHover += ((confirm.contains(mouseX, mouseY) ? 1.0F : 0.0F) - this.confirmHover) * response;
        if (this.kind == Kind.MISSING) {
            AnchorsButton.draw(graphics, cancel.x(), cancel.y(), cancel.width(), cancel.height(),
                    text("kohs_anchors.herzium.window.close"), false, false, this.cancelHover, 0.0F, 1.0F, -1.0F);
            AnchorsButton.draw(graphics, confirm.x(), confirm.y(), confirm.width(), confirm.height(),
                    text("kohs_anchors.herzium.window.open"), true, false, this.confirmHover, 0.0F, 1.0F, -1.0F);
            return;
        }
        // "Don't show again" is a switch: a box that fills with a tick.
        AnchorsButton.draw(graphics, cancel.x(), cancel.y(), cancel.width(), cancel.height(), "", false, false,
                this.cancelHover, 0.0F, 1.0F, -1.0F);
        int boxSize = Math.min(9, cancel.height() - 5);
        int tickX = cancel.x() + 5;
        int tickY = cancel.y() + (cancel.height() - boxSize) / 2;
        AnchorsUi.outline(graphics, tickX, tickY, boxSize, boxSize, this.hide ? AnchorsTheme.ACCENT_BRIGHT : AnchorsTheme.SILVER);
        if (this.hide) {
            graphics.fill(tickX + 2, tickY + 2, tickX + boxSize - 2, tickY + boxSize - 2, AnchorsTheme.ACCENT_BRIGHT);
        }
        String hideText = AnchorsUi.fit(font, text("kohs_anchors.herzium.window.hide"), cancel.width() - boxSize - 12);
        AnchorsUi.label(graphics, font, hideText, tickX + boxSize + 4, cancel.y() + (cancel.height() - 8) / 2,
                AnchorsTheme.TEXT, true);
        AnchorsButton.draw(graphics, confirm.x(), confirm.y(), confirm.width(), confirm.height(),
                text("kohs_anchors.herzium.window.continue"), true, false, this.confirmHover, 0.0F, 1.0F, -1.0F);
    }

    /** Streaks of light crossing the dimmed screen from right to left, fast: Herzium's speed. */
    private static void speedStreaks(GuiGraphicsExtractor graphics, int width, int height, double seconds, float strength) {
        if (width <= 0 || height <= 0 || strength <= 0.05F) {
            return;
        }
        int count = Math.max(10, Math.min(22, height / 18));
        for (int index = 0; index < count; index++) {
            int length = 24 + (index * 37) % 70;
            double speed = 260.0D + (index * 53) % 220;
            int span = width + length * 2;
            int head = width + length - (int) ((seconds * speed + index * 131.0D) % span);
            int y = Math.floorMod(index * 47 + 13, Math.max(1, height));
            int alpha = Math.round((26 + (index % 4) * 10) * strength);
            int color = index % 5 == 0 ? 0x52F2FF : 0xC084FC;
            graphics.fill(head, y, head + length, y + 1, AnchorsTheme.withAlpha(color, alpha / 2));
            graphics.fill(head, y, head + Math.max(3, length / 4), y + 1, AnchorsTheme.withAlpha(0xF5E8FF, alpha));
        }
    }

    /** A thin oscilloscope trace, tapered at both ends. */
    private void frequency(GuiGraphicsExtractor graphics, int left, int right, int y, float amplitude, double seconds) {
        int span = right - left;
        if (span < 12) {
            return;
        }
        AnchorsUi.isolate(graphics);
        double time = this.motion ? seconds : 0.0D;
        for (int x = left; x < right; x += 2) {
            double t = (x - left) / (double) span;
            double envelope = Math.sin(Math.PI * t);
            int offset = (int) Math.round(Math.sin(x * 0.21D + time * 7.0D) * Math.sin(x * 0.047D - time * 1.3D)
                    * amplitude * envelope);
            int alpha = (int) Math.round(70 + 150 * envelope);
            graphics.fill(x, y + offset, x + 2, y + offset + 1, AnchorsTheme.withAlpha(0xC084FC, alpha));
        }
        AnchorsUi.isolate(graphics);
    }

    private static String orderKey(String order) {
        return switch (order) {
            case "VANILLA" -> "kohs_anchors.herzium.order.vanilla";
            case "VANILLA_REVERSED" -> "kohs_anchors.herzium.order.reversed";
            default -> "kohs_anchors.herzium.order.herzium";
        };
    }

    private static String text(String key) {
        return Component.translatable(key).getString();
    }
}
