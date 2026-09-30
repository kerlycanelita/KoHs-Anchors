package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

/**
 * The KoHs tab: Zymery Dria, who makes KoHs Anchor's, as the KoHs Mod Suite site draws her (a
 * hooded figure with glowing eyes and a code sigil), and below her the name, the aka, what she
 * does, links to Discord, the site and Modrinth, and "KoHs on top" to close.
 *
 * <p>The illustration is the site's own drawing, taken apart into layers so each can move as it
 * does there and a little more: the sigil's ring and its runes turn opposite ways, the eyes blink
 * and flare, a glint runs down the blade of light, the shards drift, and the whole figure answers
 * the pointer with a slight parallax. A click on her lights everything up at once.</p>
 *
 * <p>Everything is timed with {@code System.nanoTime}. With interface animations off the drawing
 * stands still and only fades in. Geometry comes from {@link #parts}, which drawing and clicks
 * share; the drawing goes first when the body is too short.</p>
 */
final class KohsPage {
    static final String DISCORD_URL = "https://discord.gg/9t2VxEF7UU";
    static final String SITE_URL = "https://kerlycanelita.github.io/KoHs-Mod-Suite/";
    static final String MODRINTH_URL = "https://modrinth.com/user/zymery_dria";

    /** The drawing's own units: 400 wide; shown from y 10 to 460, where its fade ends. */
    private static final float ART_WIDTH = 400.0F;
    private static final float ART_TOP = 10.0F;
    private static final float ART_HEIGHT = 450.0F;
    private static final long ENTER_NANOS = 1_100_000_000L;
    private static final long FLARE_NANOS = 900_000_000L;

    /** One layer of the drawing: its texture, its size in pixels, and where it sits in units. */
    private record Layer(Identifier texture, int textureWidth, int textureHeight, float x, float y, float width,
            float height) {
        float centerX() {
            return this.x + this.width / 2.0F;
        }

        float centerY() {
            return this.y + this.height / 2.0F;
        }
    }

    private static final Layer AURA = layer("aura", 192, 192, 10, 6, 380, 380);
    private static final Layer SIGIL_RING = layer("sigil_ring", 448, 448, 25, 21, 350, 350);
    private static final Layer SIGIL_RUNES = layer("sigil_runes", 448, 448, 25, 21, 350, 350);
    private static final Layer SLASH = layer("slash", 496, 563, 12, 10, 388, 440);
    private static final Layer FIGURE = layer("figure", 512, 563, 0, 20, 400, 440);
    private static final Layer EYES = layer("eyes", 179, 76, 130, 160, 140, 60);
    private static final Layer EMBLEM = layer("emblem", 128, 128, 150, 332, 100, 100);
    private static final Layer[] SHARDS = {
            layer("shard0", 71, 71, 31, 91, 56, 56), layer("shard1", 71, 71, 316, 71, 56, 56),
            layer("shard2", 71, 71, 305, 233, 56, 56), layer("shard3", 71, 71, 41, 235, 56, 56),
            layer("shard4", 71, 71, 77, 31, 56, 56), layer("shard5", 71, 71, 277, 20, 56, 56)};
    /** The site's timing for each shard: its period and how far into it it starts, in seconds. */
    private static final float[] SHARD_PERIOD = {7.0F, 8.0F, 7.0F, 9.0F, 7.0F, 6.0F};
    private static final float[] SHARD_DELAY = {0.0F, 1.2F, 2.4F, 3.1F, 4.3F, 5.5F};
    private static final Identifier DISCORD_ICON = texture("discord");
    private static final Identifier KOHS_MARK = texture("kohs_mark");
    private static final Identifier MODRINTH_MARK = Identifier.fromNamespaceAndPath(KoHsAnchorsClient.MOD_ID,
            "textures/gui/modrinth_mark.png");
    private static final Identifier MODRINTH_OUTER = Identifier.fromNamespaceAndPath(KoHsAnchorsClient.MOD_ID,
            "textures/gui/modrinth_outer.png");
    private static final Identifier MODRINTH_INNER = Identifier.fromNamespaceAndPath(KoHsAnchorsClient.MOD_ID,
            "textures/gui/modrinth_inner.png");
    /** The slash's bright edge, in units: from its top right to its bottom left. */
    private static final float SLASH_X0 = 387.5F;
    private static final float SLASH_Y0 = 23.5F;
    private static final float SLASH_X1 = 28.5F;
    private static final float SLASH_Y1 = 437.5F;
    private static final String FINALE = "KOHS ON TOP";
    private static final int[] FINALE_COLORS = {0xFFFF4FB8, 0xFFE83EAF, 0xFFC084FC, 0xFFA855F7, 0xFFD8B4FE};

    private final boolean motion;
    private final Screen screen;
    private long enteredAt = System.nanoTime();
    private long flareAt = -1L;
    private long lastFrame = this.enteredAt;
    private final float[] buttonHover = new float[3];
    private final float[] chipHover = new float[6];
    private float artHover;
    private float parallaxX;
    private float parallaxY;

    KohsPage(boolean motion, Screen screen) {
        this.motion = motion;
        this.screen = screen;
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath(KoHsAnchorsClient.MOD_ID, "textures/gui/zymery/" + name + ".png");
    }

    private static Layer layer(String name, int textureWidth, int textureHeight, float x, float y, float width,
            float height) {
        return new Layer(texture(name), textureWidth, textureHeight, x, y, width, height);
    }

    /** The tab was opened: the entrance plays again. */
    void enter() {
        this.enteredAt = System.nanoTime();
        this.flareAt = -1L;
        play(SoundEvents.AMETHYST_BLOCK_RESONATE, 1.3F, 0.5F);
    }

    // ------------------------------------------------------------------------------------------
    // Arrangement
    // ------------------------------------------------------------------------------------------

    /**
     * Top to bottom: the drawing, the eyebrow, the name, the aka, what she does, the three links
     * and the closing words. The specialties stand on both sides of the drawing when there is room.
     */
    private record Parts(AnchorsLayout.Rect art, float scale, boolean compact, int eyebrowY, int nameY, float nameScale,
            int akaY, List<FormattedCharSequence> role, int roleY, AnchorsLayout.Rect[] buttons, int finaleY,
            float finaleScale, AnchorsLayout.Rect[] chips) {
    }

    private Parts parts(Font font, AnchorsLayout.Rect body) {
        boolean compact = body.height() < 200 || body.width() < 330;
        boolean tall = body.height() >= 250;
        int eyebrow = compact ? 0 : 12;
        String name = text("kohs_anchors.kohs.name");
        float nameScale = Math.min(compact ? 1.6F : tall ? 2.2F : 2.0F, (body.width() - 12) / (float) Math.max(1, font.width(name)));
        int nameHeight = Math.round(9 * nameScale) + 3;
        int aka = 12;
        int textWidth = Math.min(body.width() - 16, 470);
        List<FormattedCharSequence> role = font.split(Component.translatable("kohs_anchors.kohs.role"), Math.max(60, textWidth));
        int buttonHeight = compact ? 14 : 16;
        float finaleScale = Math.min(compact ? 1.4F : 2.0F, (body.width() - 12) / (float) Math.max(1, finaleWidth(font, 1.0F)));
        int finaleHeight = Math.round(9 * finaleScale) + 2;
        int maxRole = tall ? 3 : 2;
        // The eyebrow sits on the drawing's faded foot, so the drawing gets those pixels back.
        int overlap = eyebrow > 0 ? 6 : 0;
        int artHeight;
        while (true) {
            int roleLines = Math.min(maxRole, role.size());
            int text = eyebrow + nameHeight + aka + roleLines * 10 + 5 + buttonHeight + 6 + finaleHeight;
            artHeight = Math.min(compact ? 110 : 190, body.height() - text - 6 + overlap);
            if (artHeight >= 56 || maxRole <= 1) {
                break;
            }
            maxRole--;
        }
        if (artHeight < 56) {
            artHeight = 0;
        }
        if (role.size() > maxRole) {
            // What she does, said shorter, before any of it is cut.
            List<FormattedCharSequence> brief = font.split(Component.translatable("kohs_anchors.kohs.role_short"),
                    Math.max(60, textWidth));
            role = brief.size() <= maxRole ? brief : brief.subList(0, maxRole);
        }
        int artWidth = Math.round(artHeight * ART_WIDTH / ART_HEIGHT);
        int artSpace = artHeight > 0 ? artHeight + 4 - overlap : 0;
        int total = artSpace + eyebrow + nameHeight + aka + role.size() * 10 + 5 + buttonHeight + 6 + finaleHeight;
        int y = body.y() + Math.max(0, (body.height() - total) / 2);
        AnchorsLayout.Rect art = artHeight > 0
                ? new AnchorsLayout.Rect(body.centerX() - artWidth / 2, y, artWidth, artHeight) : AnchorsLayout.Rect.EMPTY;
        y += artSpace;
        int eyebrowY = y;
        y += eyebrow;
        int nameY = y;
        y += nameHeight;
        int akaY = y;
        y += aka;
        int roleY = y;
        y += role.size() * 10 + 5;
        // The three links, as wide as their names need, centred together.
        String[] labels = {text("kohs_anchors.kohs.discord"), text("kohs_anchors.kohs.site"), text("kohs_anchors.kohs.modrinth")};
        int[] widths = new int[3];
        int buttonsWidth = 0;
        for (int index = 0; index < 3; index++) {
            widths[index] = font.width(labels[index]) + buttonHeight + 14;
            buttonsWidth += widths[index];
        }
        int gap = 6;
        buttonsWidth += gap * 2;
        if (buttonsWidth > body.width() - 8) {
            // Narrow: three equal buttons across the body.
            int each = (body.width() - 8 - gap * 2) / 3;
            java.util.Arrays.fill(widths, each);
            buttonsWidth = each * 3 + gap * 2;
        }
        AnchorsLayout.Rect[] buttons = new AnchorsLayout.Rect[3];
        int x = body.centerX() - buttonsWidth / 2;
        for (int index = 0; index < 3; index++) {
            buttons[index] = new AnchorsLayout.Rect(x, y, widths[index], buttonHeight);
            x += widths[index] + gap;
        }
        y += buttonHeight + 6;
        int finaleY = y;
        // The specialties, three on each side of the drawing, when both sides have room.
        AnchorsLayout.Rect[] chips = new AnchorsLayout.Rect[0];
        int side = (body.width() - artWidth) / 2 - 18;
        if (artHeight >= 80 && side >= 118) {
            chips = new AnchorsLayout.Rect[6];
            int chipWidth = Math.min(side, 150);
            int chipHeight = 16;
            int chipGap = Math.min(10, Math.max(4, (artHeight - chipHeight * 3) / 4));
            int top = art.y() + (artHeight - chipHeight * 3 - chipGap * 2) / 2;
            for (int index = 0; index < 3; index++) {
                int chipY = top + index * (chipHeight + chipGap);
                chips[index] = new AnchorsLayout.Rect(art.x() - 14 - chipWidth, chipY, chipWidth, chipHeight);
                chips[index + 3] = new AnchorsLayout.Rect(art.right() + 14, chipY, chipWidth, chipHeight);
            }
        }
        return new Parts(art, artHeight / ART_HEIGHT, compact, eyebrowY, nameY, nameScale, akaY, role, roleY, buttons, finaleY,
                finaleScale, chips);
    }

    // ------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------

    boolean mouseClicked(Font font, AnchorsLayout.Rect body, double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        Parts parts = parts(font, body);
        String[] urls = {DISCORD_URL, SITE_URL, MODRINTH_URL};
        for (int index = 0; index < 3; index++) {
            if (parts.buttons()[index].contains(mouseX, mouseY)) {
                play(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 1.0F);
                ConfirmLinkScreen.confirmLinkNow(this.screen, URI.create(urls[index]));
                return true;
            }
        }
        if (parts.art().contains(mouseX, mouseY)) {
            // Her eyes flare, the blade lights up and the sigil sends out a ring.
            this.flareAt = System.nanoTime();
            play(SoundEvents.AMETHYST_BLOCK_CHIME, 0.8F, 0.9F);
            play(SoundEvents.RESPAWN_ANCHOR_CHARGE, 1.6F, 0.35F);
            return true;
        }
        return false;
    }

    private static void play(net.minecraft.sounds.SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    // ------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------

    void render(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.Rect body, int mouseX, int mouseY, float intro) {
        long now = System.nanoTime();
        float frameMillis = Math.min(50.0F, (now - this.lastFrame) / 1_000_000.0F);
        this.lastFrame = now;
        float response = 1.0F - (float) Math.exp(-frameMillis / 70.0F);
        double seconds = now / 1_000_000_000.0D;
        float since = (now - this.enteredAt) / 1_000_000_000.0F;
        float enter = this.motion ? AnchorsTheme.clamp01(since / (ENTER_NANOS / 1_000_000_000.0F)) : 1.0F;
        Parts parts = parts(font, body);
        AnchorsLayout.Rect art = parts.art();

        // The pointer tilts the drawing a little: the far layers less than the figure.
        boolean overArt = art.contains(mouseX, mouseY);
        this.artHover += ((overArt ? 1.0F : 0.0F) - this.artHover) * response;
        float targetX = 0.0F;
        float targetY = 0.0F;
        if (this.motion && art.width() > 0 && mouseX >= 0) {
            targetX = AnchorsTheme.clamp01((mouseX - body.x()) / (float) Math.max(1, body.width())) * 2.0F - 1.0F;
            targetY = AnchorsTheme.clamp01((mouseY - body.y()) / (float) Math.max(1, body.height())) * 2.0F - 1.0F;
        }
        this.parallaxX += (targetX - this.parallaxX) * response * 0.5F;
        this.parallaxY += (targetY - this.parallaxY) * response * 0.5F;

        if (art.width() > 0) {
            drawArt(graphics, parts, seconds, since, enter, intro, now);
        }
        drawChips(graphics, font, parts, mouseX, mouseY, since, intro, response, seconds);
        drawText(graphics, font, body, parts, since, intro, seconds);
        drawButtons(graphics, font, parts, mouseX, mouseY, since, intro, response, seconds);
        drawFinale(graphics, font, body, parts, since, intro, seconds);
        DevInspector.node("KohsPage", "Zymery Dria", body.x(), body.y(), body.width(), body.height(),
                "the KoHs Mod Suite site's drawing, in " + (7 + SHARDS.length) + " layers",
                "Discord · site · Modrinth through ConfirmLinkScreen");
    }

    /** How far into its own entrance an element is, from its start time in seconds. */
    private float appear(float since, float start, float length) {
        return this.motion ? AnchorsTheme.easeOutCubic(AnchorsTheme.clamp01((since - start) / length)) : 1.0F;
    }

    private void drawArt(GuiGraphicsExtractor graphics, Parts parts, double seconds, float since, float enter, float intro,
            long now) {
        AnchorsLayout.Rect art = parts.art();
        float scale = parts.scale();
        float originX = art.x();
        float originY = art.y() - ART_TOP * scale;
        double time = this.motion ? seconds : 0.0D;
        float flare = this.flareAt < 0L ? 0.0F : 1.0F - AnchorsTheme.clamp01((now - this.flareAt) / (float) FLARE_NANOS);
        float glow = Math.max(flare, 0.35F * this.artHover);

        AnchorsUi.isolate(graphics);
        graphics.enableScissor(art.x() - 2, art.y() - 2, art.right() + 2, art.bottom());
        // The aura breathes.
        float auraIn = appear(since, 0.0F, 0.6F);
        float breathe = this.motion ? 0.84F + 0.16F * (float) Math.sin(time * Math.PI * 2.0D / 4.0D) : 1.0F;
        float auraScale = 0.86F + 0.14F * auraIn + (this.motion ? 0.02F * (float) Math.sin(time * 1.3D) : 0.0F);
        drawLayer(graphics, AURA, originX, originY, scale, 0.6F, 0.0F, auraScale,
                AnchorsTheme.fade(0xFFFFFFFF, intro * auraIn * Math.min(1.0F, breathe + glow * 0.3F)));
        // The sigil: the ring one way, the runes the other; a burst of speed as it appears.
        float sigilIn = appear(since, 0.08F, 0.8F);
        double burst = (1.0D - sigilIn) * Math.PI * 0.9D;
        float sigilScale = 1.14F - 0.14F * sigilIn;
        drawLayer(graphics, SIGIL_RING, originX, originY, scale, 1.2F, (float) (time * Math.PI * 2.0D / 80.0D + burst),
                sigilScale, AnchorsTheme.fade(0xFFFFFFFF, intro * sigilIn));
        drawLayer(graphics, SIGIL_RUNES, originX, originY, scale, 1.6F, (float) (-time * Math.PI * 2.0D / 120.0D - burst),
                sigilScale, AnchorsTheme.fade(0xFFFFFFFF, intro * sigilIn * (0.8F + 0.2F * glow)));
        if (flare > 0.0F) {
            // The flare's ring leaves the sigil.
            float ring = 1.0F - flare;
            AnchorsUi.ring(graphics, Math.round(originX + 200 * scale), Math.round(originY + 196 * scale),
                    Math.round((150 + 60 * ring) * scale), 2, AnchorsTheme.withAlpha(0xFF4FB8, Math.round(200 * flare)));
        }
        // The blade of light, pulsing as on the site, with a glint running down it now and then.
        float slashIn = appear(since, 0.25F, 0.35F);
        float slashPulse = this.motion ? 0.65F + 0.2F * (float) Math.sin(time * Math.PI * 2.0D / 6.0D) : 0.85F;
        drawLayer(graphics, SLASH, originX, originY, scale, 2.2F, 0.0F, 1.0F,
                AnchorsTheme.fade(0xFFFFFFFF, intro * slashIn * Math.min(1.0F, slashPulse + glow)));
        // The figure, rising into place and breathing.
        float figureIn = appear(since, 0.12F, 0.7F);
        float bob = this.motion ? (float) Math.sin(time * Math.PI * 2.0D / 5.0D) * 1.2F : 0.0F;
        drawLayer(graphics, FIGURE, originX, originY + ((1.0F - figureIn) * 14.0F + bob) * scale, scale, 3.0F, 0.0F, 1.0F,
                AnchorsTheme.fade(0xFFFFFFFF, intro * figureIn));
        // Her eyes light up last; then they blink every five seconds, and flare on a click.
        float eyesIn = appear(since, 0.55F, 0.25F);
        float blink = 1.0F;
        if (this.motion) {
            double phase = (time % 5.0D) / 5.0D;
            if (phase > 0.46D && phase < 0.5D) {
                blink = phase < 0.48D ? (float) (1.0D - (phase - 0.46D) / 0.02D * 0.85D) : (float) (0.15D + (phase - 0.48D) / 0.02D * 0.85D);
            }
        }
        float eyes = intro * eyesIn * blink;
        drawLayer(graphics, EYES, originX, originY + ((1.0F - figureIn) * 14.0F + bob) * scale, scale, 3.0F, 0.0F, 1.0F,
                AnchorsTheme.fade(0xFFFFFFFF, eyes));
        if (glow > 0.02F || (this.motion && since > 0.55F && since < 0.9F)) {
            // A second pass brightens them: the ignition, a hover, a flare.
            float ignite = this.motion ? Math.max(0.0F, 1.0F - Math.abs(since - 0.7F) / 0.2F) : 0.0F;
            drawLayer(graphics, EYES, originX, originY + ((1.0F - figureIn) * 14.0F + bob) * scale, scale, 3.0F, 0.0F,
                    1.0F + 0.08F * Math.max(glow, ignite), AnchorsTheme.fade(0xFFFFFFFF, eyes * Math.max(glow, ignite)));
        }
        // The code emblem pulses.
        float emblemPulse = this.motion ? 0.86F + 0.14F * (float) Math.sin(time * Math.PI * 2.0D / 3.2D) : 1.0F;
        drawLayer(graphics, EMBLEM, originX, originY + ((1.0F - figureIn) * 14.0F + bob) * scale, scale, 3.0F, 0.0F,
                0.98F + 0.04F * emblemPulse, AnchorsTheme.fade(0xFFFFFFFF, intro * figureIn * emblemPulse));
        // The shards drift up and turn, each on its own clock.
        for (int index = 0; index < SHARDS.length; index++) {
            float shardIn = appear(since, 0.3F + index * 0.06F, 0.4F);
            float drift = 0.0F;
            if (this.motion) {
                double phase = (time + SHARD_DELAY[index]) / SHARD_PERIOD[index];
                drift = (float) (0.5D - 0.5D * Math.cos(phase * Math.PI * 2.0D));
            }
            Layer shard = SHARDS[index];
            drawLayer(graphics, shard, originX, originY - drift * 10.0F * scale, scale, 2.6F,
                    (float) Math.toRadians(14.0D * drift), 1.0F + 0.3F * flare, AnchorsTheme.fade(0xFFFFFFFF, intro * shardIn));
        }
        graphics.disableScissor();
        AnchorsUi.isolate(graphics);
        if (this.motion) {
            glint(graphics, originX, originY, scale, time, flare, intro * slashIn);
            sparks(graphics, art, time, intro * auraIn);
        }
    }

    /**
     * One layer at its place. {@code depth} is how far the pointer moves it, in units; {@code angle}
     * and {@code grow} turn and scale it around its own centre.
     */
    private void drawLayer(GuiGraphicsExtractor graphics, Layer layer, float originX, float originY, float scale, float depth,
            float angle, float grow, int color) {
        if (((color >>> 24) & 255) < 4) {
            return;
        }
        float centerX = originX + (layer.centerX() + this.parallaxX * depth) * scale;
        float centerY = originY + (layer.centerY() + this.parallaxY * depth * 0.6F) * scale;
        graphics.pose().pushMatrix();
        graphics.pose().translate(centerX, centerY);
        if (angle != 0.0F) {
            graphics.pose().rotate(angle);
        }
        graphics.pose().scale(layer.width() * scale * grow / layer.textureWidth(), layer.height() * scale * grow / layer.textureHeight());
        graphics.blit(RenderPipelines.GUI_TEXTURED, layer.texture(), -layer.textureWidth() / 2, -layer.textureHeight() / 2, 0.0F,
                0.0F, layer.textureWidth(), layer.textureHeight(), layer.textureWidth(), layer.textureHeight(),
                layer.textureWidth(), layer.textureHeight(), color);
        graphics.pose().popMatrix();
    }

    /** A point of light running down the blade every few seconds, or at once on a flare. */
    private void glint(GuiGraphicsExtractor graphics, float originX, float originY, float scale, double time, float flare,
            float alpha) {
        double period = 4.5D;
        double run = 0.55D;
        double phase = time % period;
        float t = flare > 0.0F ? 1.0F - flare : (float) (phase / run);
        if (t < 0.0F || t > 1.0F || alpha <= 0.05F) {
            return;
        }
        float eased = AnchorsTheme.easeInOutSine(t);
        for (int step = 0; step < 10; step++) {
            float s = eased - step * 0.012F;
            if (s < 0.0F) {
                break;
            }
            float ux = SLASH_X0 + (SLASH_X1 - SLASH_X0) * s + this.parallaxX * 2.2F;
            float uy = SLASH_Y0 + (SLASH_Y1 - SLASH_Y0) * s + this.parallaxY * 1.3F;
            int x = Math.round(originX + ux * scale);
            int y = Math.round(originY + uy * scale);
            int size = step == 0 ? 3 : step < 4 ? 2 : 1;
            int tone = AnchorsTheme.withAlpha(step == 0 ? 0xFFFFFF : 0xFFD1EC, Math.round(255 * alpha * (1.0F - step / 10.0F)));
            graphics.fill(x - size / 2, y - size / 2, x - size / 2 + size, y - size / 2 + size, tone);
        }
    }

    /** Pink and violet sparks rising around her. */
    private static void sparks(GuiGraphicsExtractor graphics, AnchorsLayout.Rect art, double time, float alpha) {
        if (alpha <= 0.05F) {
            return;
        }
        int span = art.height() + 10;
        for (int index = 0; index < 14; index++) {
            double speed = 9.0D + index % 5 * 3.0D;
            double phase = index * 0.618D;
            int x = art.x() + Math.floorMod(index * 53 + 17, Math.max(1, art.width()))
                    + (int) Math.round(Math.sin(time * 0.9D + phase * 5.0D) * 3.0D);
            int y = art.bottom() - (int) ((time * speed + phase * span) % span);
            float life = 1.0F - (art.bottom() - y) / (float) span;
            int color = index % 3 == 0 ? 0xFF4FB8 : index % 3 == 1 ? 0xC084FC : 0xF5D0FE;
            int size = index % 4 == 0 ? 2 : 1;
            graphics.fill(x, y, x + size, y + size, AnchorsTheme.withAlpha(color, Math.round(200 * alpha * life)));
        }
    }

    private void drawChips(GuiGraphicsExtractor graphics, Font font, Parts parts, int mouseX, int mouseY, float since,
            float intro, float response, double seconds) {
        AnchorsLayout.Rect[] chips = parts.chips();
        for (int index = 0; index < chips.length; index++) {
            AnchorsLayout.Rect chip = chips[index];
            boolean left = index < 3;
            float in = appear(since, 0.35F + (index % 3) * 0.08F, 0.45F);
            if (in <= 0.02F) {
                continue;
            }
            this.chipHover[index] += ((chip.contains(mouseX, mouseY) ? 1.0F : 0.0F) - this.chipHover[index]) * response;
            float hover = this.chipHover[index];
            int slide = Math.round((1.0F - in) * 18.0F) * (left ? -1 : 1);
            int x = chip.x() + slide;
            float alpha = intro * in;
            AnchorsUi.panel(graphics, x, chip.y(), chip.width(), chip.height(),
                    AnchorsTheme.fade(AnchorsTheme.lerp(0x901D0D32, 0xC02A1248, hover), alpha),
                    AnchorsTheme.fade(0x8012091F, alpha));
            AnchorsUi.roundedOutline(graphics, x, chip.y(), chip.width(), chip.height(),
                    AnchorsTheme.fade(AnchorsTheme.lerp(0x9A6A2A9A, 0xE0FF8AD8, hover), alpha));
            // A slow scan of light along each chip, one after the other.
            if (this.motion) {
                double scan = ((seconds * 0.35D) + index / 6.0D) % 1.0D;
                int scanX = x + (int) Math.round(scan * (chip.width() + 20)) - 10;
                graphics.enableScissor(x + 1, chip.y() + 1, x + chip.width() - 1, chip.bottom() - 1);
                graphics.fill(scanX, chip.y() + 1, scanX + 2, chip.bottom() - 1, AnchorsTheme.withAlpha(0xF5D0FE, Math.round(30 * alpha)));
                graphics.disableScissor();
            }
            int diamondX = left ? x + chip.width() - 8 : x + 7;
            AnchorsUi.diamond(graphics, diamondX, chip.centerY(), 2, AnchorsTheme.fade(0xFFFF4FB8, alpha));
            String label = AnchorsUi.ellipsis(font, text("kohs_anchors.kohs.skill." + (index + 1)), chip.width() - 20);
            int textX = left ? x + chip.width() - 14 - font.width(label) : x + 14;
            AnchorsUi.label(graphics, font, label, textX, chip.y() + (chip.height() - 8) / 2,
                    AnchorsTheme.fade(AnchorsTheme.lerp(AnchorsTheme.TEXT_MUTED, AnchorsTheme.TITLE, hover), alpha), false);
        }
    }

    private void drawText(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.Rect body, Parts parts, float since,
            float intro, double seconds) {
        int centerX = body.centerX();
        if (!parts.compact()) {
            float in = appear(since, 0.3F, 0.35F);
            String eyebrow = "◆ " + text("kohs_anchors.kohs.eyebrow").toUpperCase(Locale.ROOT);
            AnchorsUi.label(graphics, font, eyebrow, centerX - font.width(eyebrow) / 2, parts.eyebrowY() + Math.round((1.0F - in) * 4),
                    AnchorsTheme.fade(0xFFFF8AD8, intro * in), false);
        }
        // The name decodes itself: letters run through signs until each settles.
        String name = text("kohs_anchors.kohs.name").toUpperCase(Locale.ROOT);
        float decode = appear(since, 0.35F, 0.55F);
        String shown = name;
        if (decode < 1.0F) {
            String signs = "<>/\\#*+=ZYMEKOHS";
            StringBuilder scrambled = new StringBuilder(name.length());
            long tick = (long) (since * 30.0F);
            for (int index = 0; index < name.length(); index++) {
                char real = name.charAt(index);
                boolean settled = real == ' ' || decode > (index + 1) / (float) (name.length() + 1);
                scrambled.append(settled ? real : signs.charAt((int) Math.floorMod(tick * 31 + index * 17L, signs.length())));
            }
            shown = scrambled.toString();
        }
        float nameIn = appear(since, 0.35F, 0.3F);
        AnchorsUi.bigText(graphics, font, shown, centerX + 1, parts.nameY() + 1, parts.nameScale(),
                AnchorsTheme.fade(0xFF6B0F5A, intro * nameIn), false);
        AnchorsUi.bigText(graphics, font, shown, centerX, parts.nameY(), parts.nameScale(),
                AnchorsTheme.fade(AnchorsTheme.TITLE, intro * nameIn), false);
        if (decode >= 1.0F && this.motion) {
            float width = font.width(name) * parts.nameScale();
            graphics.pose().pushMatrix();
            graphics.pose().translate(centerX - width / 2.0F, parts.nameY());
            graphics.pose().scale(parts.nameScale(), parts.nameScale());
            AnchorsUi.glint(graphics, font, name, 0, 0, seconds);
            graphics.pose().popMatrix();
        }
        // a.k.a. zymekoh · kohzemyora: the names in pink.
        float akaIn = appear(since, 0.55F, 0.35F);
        String prefix = text("kohs_anchors.kohs.aka") + " ";
        String first = "zymekoh";
        String dot = " · ";
        String second = "kohzemyora";
        int width = font.width(prefix + first + dot + second);
        int x = centerX - width / 2;
        int y = parts.akaY() + Math.round((1.0F - akaIn) * 4);
        AnchorsUi.label(graphics, font, prefix, x, y, AnchorsTheme.fade(AnchorsTheme.TEXT_DIM, intro * akaIn), false);
        x += font.width(prefix);
        AnchorsUi.label(graphics, font, first, x, y, AnchorsTheme.fade(0xFFFF8AD8, intro * akaIn), false);
        x += font.width(first);
        AnchorsUi.label(graphics, font, dot, x, y, AnchorsTheme.fade(AnchorsTheme.TEXT_DIM, intro * akaIn), false);
        x += font.width(dot);
        AnchorsUi.label(graphics, font, second, x, y, AnchorsTheme.fade(0xFFC084FC, intro * akaIn), false);
        // What she does.
        int lineY = parts.roleY();
        for (int index = 0; index < parts.role().size(); index++) {
            float in = appear(since, 0.65F + index * 0.07F, 0.35F);
            FormattedCharSequence line = parts.role().get(index);
            AnchorsUi.line(graphics, font, line, centerX - font.width(line) / 2, lineY + Math.round((1.0F - in) * 4),
                    AnchorsTheme.fade(AnchorsTheme.TEXT_MUTED, intro * in));
            lineY += 10;
        }
    }

    private void drawButtons(GuiGraphicsExtractor graphics, Font font, Parts parts, int mouseX, int mouseY, float since,
            float intro, float response, double seconds) {
        String[] labels = {text("kohs_anchors.kohs.discord"), text("kohs_anchors.kohs.site"), text("kohs_anchors.kohs.modrinth")};
        int[] glows = {0xFF5865F2, 0xFFFF4FB8, 0xFF1BD96A};
        for (int index = 0; index < 3; index++) {
            AnchorsLayout.Rect button = parts.buttons()[index];
            float in = appear(since, 0.8F + index * 0.07F, 0.35F);
            if (in <= 0.02F) {
                continue;
            }
            this.buttonHover[index] += ((button.contains(mouseX, mouseY) ? 1.0F : 0.0F) - this.buttonHover[index]) * response;
            float hover = this.buttonHover[index];
            float alpha = intro * in;
            if (hover > 0.05F) {
                AnchorsUi.halo(graphics, button.x(), button.y(), button.width(), button.height(), glows[index], 3, hover * alpha);
            }
            AnchorsButton.draw(graphics, button.x(), button.y(), button.width(), button.height(), "", index == 1, false, hover,
                    0.0F, alpha, -1.0F);
            int icon = button.height() - 6;
            String label = AnchorsUi.fit(font, labels[index], button.width() - icon - 12);
            int contentWidth = icon + 4 + font.width(label);
            int x = button.x() + (button.width() - contentWidth) / 2;
            int iconY = button.y() + 3;
            switch (index) {
                case 0 -> icon(graphics, DISCORD_ICON, 64, x, iconY, icon, 0.0F,
                        AnchorsTheme.fade(AnchorsTheme.lerp(0xFFB4BBFF, 0xFFFFFFFF, hover), alpha));
                case 1 -> icon(graphics, KOHS_MARK, 96, x, iconY, icon, 0.0F, AnchorsTheme.fade(0xFFFFFFFF, alpha));
                default -> {
                    int green = AnchorsTheme.fade(AnchorsTheme.lerp(0xFF1BD96A, 0xFF9CFFC6, hover), alpha);
                    float turn = this.motion ? (float) (seconds / 4.0D * Math.PI * 2.0D) * (0.3F + hover) : 0.0F;
                    icon(graphics, MODRINTH_OUTER, 256, x, iconY, icon, turn, green);
                    icon(graphics, MODRINTH_INNER, 256, x, iconY, icon, -turn * 0.66F, green);
                    icon(graphics, MODRINTH_MARK, 256, x, iconY, icon, 0.0F, green);
                }
            }
            AnchorsUi.label(graphics, font, label, x + icon + 4, button.y() + (button.height() - 8) / 2,
                    AnchorsTheme.fade(AnchorsTheme.lerp(AnchorsTheme.TEXT, 0xFFFFFFFF, hover), alpha), true);
        }
    }

    private static void icon(GuiGraphicsExtractor graphics, Identifier texture, int textureSize, int x, int y, int size,
            float angle, int color) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x + size / 2.0F, y + size / 2.0F);
        if (angle != 0.0F) {
            graphics.pose().rotate(angle);
        }
        graphics.pose().scale(size / (float) textureSize, size / (float) textureSize);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, -textureSize / 2, -textureSize / 2, 0.0F, 0.0F, textureSize,
                textureSize, textureSize, textureSize, textureSize, textureSize, color);
        graphics.pose().popMatrix();
    }

    /** The width of "KOHS ON TOP" at {@code scale}, letter by letter with a pixel between. */
    private static int finaleWidth(Font font, float scale) {
        int width = 0;
        for (int index = 0; index < FINALE.length(); index++) {
            width += font.width(String.valueOf(FINALE.charAt(index))) + 1;
        }
        return Math.round((width - 1) * scale);
    }

    /**
     * "KOHS ON TOP": the letters rise in one by one, then ride a slow wave in a gradient from
     * magenta to lilac; every few seconds the line glitches for a moment, as the site's title does.
     */
    private void drawFinale(GuiGraphicsExtractor graphics, Font font, AnchorsLayout.Rect body, Parts parts, float since,
            float intro, double seconds) {
        float scale = parts.finaleScale();
        int total = finaleWidth(font, scale);
        float x = body.centerX() - total / 2.0F;
        int y = parts.finaleY();
        double time = this.motion ? seconds : 0.0D;
        double glitchPhase = time % 3.4D;
        boolean glitch = this.motion && glitchPhase < 0.14D && since > 1.6F;
        float jitter = glitch ? (float) Math.sin(time * 90.0D) * 1.5F : 0.0F;
        AnchorsUi.isolate(graphics);
        for (int index = 0; index < FINALE.length(); index++) {
            String letter = String.valueOf(FINALE.charAt(index));
            float in = appear(since, 0.95F + index * 0.045F, 0.3F);
            if (letter.equals(" ") || in <= 0.02F) {
                x += (font.width(letter) + 1) * scale;
                continue;
            }
            float wave = this.motion ? (float) Math.sin(time * 3.2D - index * 0.55D) * 1.4F : 0.0F;
            float rise = (1.0F - in) * 8.0F;
            float shift = (float) ((time * 0.25D + index / (double) FINALE.length()) % 1.0D);
            int color = gradient(shift);
            float alpha = intro * in;
            float letterY = y + wave + rise;
            if (glitch) {
                // Two torn copies, magenta and cyan, either side.
                drawLetter(graphics, font, letter, x - 1.5F + jitter, letterY, scale, AnchorsTheme.fade(0xB0FF4FB8, alpha));
                drawLetter(graphics, font, letter, x + 1.5F - jitter, letterY, scale, AnchorsTheme.fade(0xB052F2FF, alpha));
            }
            drawLetter(graphics, font, letter, x + 1.0F, letterY + 1.0F, scale, AnchorsTheme.fade(0xFF3B0A5A, alpha));
            drawLetter(graphics, font, letter, x, letterY, scale, AnchorsTheme.fade(color, alpha));
            x += (font.width(letter) + 1) * scale;
        }
        AnchorsUi.isolate(graphics);
    }

    private static void drawLetter(GuiGraphicsExtractor graphics, Font font, String letter, float x, float y, float scale,
            int color) {
        if (((color >>> 24) & 255) < 8) {
            return;
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
        graphics.text(font, letter, 0, 0, color, false);
        graphics.pose().popMatrix();
    }

    /** A colour along the finale's gradient, looping. */
    private static int gradient(float position) {
        float scaled = position * FINALE_COLORS.length;
        int from = (int) Math.floor(scaled) % FINALE_COLORS.length;
        int to = (from + 1) % FINALE_COLORS.length;
        return AnchorsTheme.lerp(FINALE_COLORS[from], FINALE_COLORS[to], scaled - (float) Math.floor(scaled));
    }

    private static String text(String key) {
        return Component.translatable(key).getString();
    }
}
