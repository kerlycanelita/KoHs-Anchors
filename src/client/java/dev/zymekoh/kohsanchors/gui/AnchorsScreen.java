package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import dev.zymekoh.kohsanchors.compat.Mc;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.input.AnchorStats;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The KoHs Anchor's settings: a purple glass panel over a transparent purple veil, the options on
 * the left and a charging, detonating anchor with this session's numbers on the right.
 *
 * <p>Geometry comes from {@link AnchorsLayout}. Every animation is timed in real time and moves
 * only decoration: rows fade in but are always where their hitboxes are, and the scrolling area
 * clips both drawing and clicks.</p>
 */
public final class AnchorsScreen extends Screen {
    private static final long INTRO_NANOS = 420_000_000L;
    private static final long ROW_FADE_NANOS = 240_000_000L;
    private static final long ROW_STAGGER_NANOS = 45_000_000L;
    private static final int SECTION_HEIGHT = 14;
    private static final int SECTION_GAP = 6;
    private static final int ROW_GAP = 4;
    private static final int STAT_LINE = 11;
    private static final int STATS_HEIGHT = 13 + STAT_LINE * 5;
    private static final double CYCLE_SECONDS = 4.8D;

    private final Screen parent;
    private final String versionLabel;
    private final List<AnchorSwitchRow> rows = new ArrayList<>();
    private final List<Integer> rowOffsets = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    private final AnchorPreview anchorPreview = new AnchorPreview();

    private AnchorsLayout layout;
    private AnchorsLayout.Rect previewArt = AnchorsLayout.Rect.EMPTY;
    private int statsOffset = -1;
    private int contentHeight;
    private int maxScroll;
    private float scroll;
    private float scrollTarget;
    private long openedAt;
    private long lastFrame;
    private boolean saved;
    private GuiEventListener lastFocused;

    private String subtitle = "";
    private String previewHint = "";
    private String footerNote = "";
    private String statsTitle = "";
    private String[] statLabels = new String[0];

    private float anchorCharge;

    public AnchorsScreen(Screen parent) {
        super(Component.translatable("kohs_anchors.screen.title"));
        this.parent = parent;
        this.versionLabel = FabricLoader.getInstance().getModContainer(KoHsAnchorsClient.MOD_ID)
                .map(container -> "v" + container.getMetadata().getVersion().getFriendlyString())
                .orElse("");
    }

    @Override
    protected void init() {
        long now = System.nanoTime();
        if (this.openedAt == 0L) {
            this.openedAt = now;
        }
        this.lastFrame = now;
        this.saved = false;
        this.lastFocused = null;
        this.layout = AnchorsLayout.fit(this.width, this.height);
        this.subtitle = Component.translatable("kohs_anchors.screen.subtitle").getString();
        this.previewHint = Component.translatable("kohs_anchors.preview.hint").getString();
        this.footerNote = Component.translatable("kohs_anchors.footer.note").getString();
        this.statsTitle = Component.translatable("kohs_anchors.stats.title").getString();
        this.statLabels = new String[] {
                Component.translatable("kohs_anchors.stats.ordered").getString(),
                Component.translatable("kohs_anchors.stats.retargeted").getString(),
                Component.translatable("kohs_anchors.stats.held").getString(),
                Component.translatable("kohs_anchors.stats.predicted").getString(),
                Component.translatable("kohs_anchors.stats.confirmed").getString()
        };

        AnchorsLayout.Rect preview = this.layout.preview;
        this.previewArt = this.layout.showsPreview()
                ? new AnchorsLayout.Rect(preview.x() + 4, preview.y() + 4, preview.width() - 8,
                        Math.max(0, preview.height() - STATS_HEIGHT - 20))
                : AnchorsLayout.Rect.EMPTY;

        this.rows.clear();
        this.rowOffsets.clear();
        this.sections.clear();
        buildContent();

        // A resize keeps the reader where they were, clamped to the new length.
        this.maxScroll = Math.max(0, this.contentHeight - this.layout.options.height());
        this.scrollTarget = Mth.clamp(this.scrollTarget, 0.0F, this.maxScroll);
        this.scroll = Mth.clamp(this.scroll, 0.0F, this.maxScroll);
        applyScroll();

        addRenderableWidget(new AnchorsButton(this.layout.resetButton,
                Component.translatable("kohs_anchors.button.reset"), false, this::resetSettings));
        addRenderableWidget(new AnchorsButton(this.layout.doneButton,
                Component.translatable("kohs_anchors.button.done"), true, this::onClose));
    }

    private void buildContent() {
        int offset = 0;
        offset = section(offset, "kohs_anchors.section.input");
        offset = row(offset, "input_order", () -> AnchorsConfig.settings().inputOrder, () -> {
            AnchorsConfig.Settings settings = AnchorsConfig.settings();
            settings.inputOrder = !settings.inputOrder;
        });
        offset = row(offset, "fresh_target", () -> AnchorsConfig.settings().freshTarget, () -> {
            AnchorsConfig.Settings settings = AnchorsConfig.settings();
            settings.freshTarget = !settings.freshTarget;
        });
        offset = row(offset, "hold_early_clicks", () -> AnchorsConfig.settings().holdEarlyClicks, () -> {
            AnchorsConfig.Settings settings = AnchorsConfig.settings();
            settings.holdEarlyClicks = !settings.holdEarlyClicks;
        });
        offset = row(offset, "no_stacking", () -> AnchorsConfig.settings().noStacking, () -> {
            AnchorsConfig.Settings settings = AnchorsConfig.settings();
            settings.noStacking = !settings.noStacking;
        });
        offset = section(offset, "kohs_anchors.section.feedback");
        offset = row(offset, "predict_detonation", () -> AnchorsConfig.settings().predictDetonation, () -> {
            AnchorsConfig.Settings settings = AnchorsConfig.settings();
            settings.predictDetonation = !settings.predictDetonation;
        });
        offset = row(offset, "anchor_debris", () -> AnchorsConfig.settings().anchorDebris, () -> {
            AnchorsConfig.Settings settings = AnchorsConfig.settings();
            settings.anchorDebris = !settings.anchorDebris;
        });
        offset = section(offset, "kohs_anchors.section.interface");
        offset = row(offset, "interface_motion", () -> AnchorsConfig.settings().interfaceMotion, () -> {
            AnchorsConfig.Settings settings = AnchorsConfig.settings();
            settings.interfaceMotion = !settings.interfaceMotion;
        });
        this.statsOffset = -1;
        if (!this.layout.showsPreview()) {
            // Without the anchor column the session numbers move to the end of the list.
            offset = section(offset, "kohs_anchors.stats.title");
            this.statsOffset = offset - SECTION_HEIGHT;
            offset += STAT_LINE * 5;
        }
        this.contentHeight = offset + 2;
    }

    private int section(int offset, String key) {
        int start = offset == 0 ? 0 : offset + SECTION_GAP;
        this.sections.add(new Section(Component.translatable(key).getString(), start));
        return start + SECTION_HEIGHT;
    }

    private int row(int offset, String option, BooleanSupplier state, Runnable toggle) {
        AnchorSwitchRow row = new AnchorSwitchRow(this.layout.options.x(), this.layout.options.y() + offset,
                this.layout.rowWidth(),
                Component.translatable("kohs_anchors.option." + option),
                Component.translatable("kohs_anchors.option." + option + ".description"),
                this.font, state, toggle);
        addWidget(row);
        this.rows.add(row);
        this.rowOffsets.add(offset);
        return offset + row.getHeight() + ROW_GAP;
    }

    private void applyScroll() {
        AnchorsLayout.Rect viewport = this.layout.options;
        int offset = Math.round(this.scroll);
        for (int index = 0; index < this.rows.size(); index++) {
            AnchorSwitchRow row = this.rows.get(index);
            row.setY(viewport.y() + this.rowOffsets.get(index) - offset);
            row.setClip(viewport);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // The screen owns its background: no blur and no menu darkening, only a purple veil the
        // world (or, from the title screen, the panorama) shows through.
        if (this.minecraft.level == null) {
            this.extractPanorama(graphics, partialTick);
        }
        graphics.fillGradient(0, 0, this.width, this.height, AnchorsTheme.VEIL_TOP, AnchorsTheme.VEIL_BOTTOM);
        Mc.extractDeferredSubtitles(this.minecraft);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        float frameMillis = Mth.clamp((now - this.lastFrame) / 1_000_000.0F, 0.0F, 50.0F);
        this.lastFrame = now;
        boolean motion = AnchorsConfig.settings().interfaceMotion;
        double seconds = now / 1_000_000_000.0D;
        float intro = motion ? AnchorsTheme.easeOutCubic(progress(now - this.openedAt, INTRO_NANOS)) : 1.0F;

        followFocus();
        updateScroll(frameMillis, motion);
        updateAnchorCycle(seconds, motion);

        if (motion) {
            AnchorsUi.motes(graphics, this.width, this.height, seconds, intro);
        }
        drawPanel(graphics, intro, seconds, motion);
        drawHeader(graphics, intro, seconds);
        drawOptions(graphics, mouseX, mouseY, partialTick, now, motion);
        if (this.layout.showsPreview()) {
            drawPreview(graphics, mouseX, mouseY, intro, motion);
        }
        drawFooterNote(graphics, intro);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawPanel(GuiGraphicsExtractor graphics, float intro, double seconds, boolean motion) {
        AnchorsLayout.Rect panel = this.layout.panel;
        float grow = 0.94F + intro * 0.06F;
        int width = Math.max(1, Math.round(panel.width() * grow));
        int height = Math.max(1, Math.round(panel.height() * grow));
        int x = panel.x() + (panel.width() - width) / 2;
        int y = panel.y() + (panel.height() - height) / 2;
        AnchorsUi.halo(graphics, x, y, width, height, AnchorsTheme.ACCENT, 5, 0.55F * intro);
        AnchorsUi.panel(graphics, x, y, width, height,
                AnchorsTheme.fade(AnchorsTheme.PANEL_TOP, 0.3F + intro * 0.7F),
                AnchorsTheme.fade(AnchorsTheme.PANEL_BOTTOM, 0.3F + intro * 0.7F));
        AnchorsUi.roundedOutline(graphics, x, y, width, height, AnchorsTheme.fade(AnchorsTheme.PANEL_BORDER, intro));
        if (intro < 0.98F) {
            return;
        }
        if (motion) {
            AnchorsUi.comets(graphics, panel.x(), panel.y(), panel.width(), panel.height(), seconds, 0xE8CCFF);
        }
        int headerLine = this.layout.header.bottom() - 1;
        graphics.fill(panel.x() + 8, headerLine, panel.right() - 8, headerLine + 1, AnchorsTheme.HEADER_LINE);
        int footerLine = this.layout.footer.y();
        graphics.fill(panel.x() + 8, footerLine, panel.right() - 8, footerLine + 1, AnchorsTheme.HEADER_LINE);
    }

    private void drawHeader(GuiGraphicsExtractor graphics, float intro, double seconds) {
        AnchorsLayout.Rect header = this.layout.header;
        int iconX = header.x() + this.layout.padding + 2;
        AnchorsUi.miniAnchor(graphics, iconX, header.y() + (header.height() - 12) / 2, this.anchorCharge, intro);

        String title = getTitle().getString();
        int titleX = iconX + 18;
        boolean large = !this.layout.compact && header.height() >= 30;
        float scale = large ? 1.5F : 1.0F;
        int titleHeight = Math.round(9 * scale);
        boolean showSubtitle = large;
        int blockHeight = titleHeight + (showSubtitle ? 11 : 0);
        int titleY = header.y() + (header.height() - blockHeight) / 2 + 1;
        int titleColor = AnchorsTheme.fade(AnchorsTheme.TITLE, intro);

        graphics.pose().pushMatrix();
        graphics.pose().translate(titleX, titleY);
        graphics.pose().scale(scale, scale);
        AnchorsUi.label(graphics, this.font, title, 0, 0, titleColor, true);
        if (intro >= 0.98F) {
            AnchorsUi.glint(graphics, this.font, title, 0, 0, seconds);
        }
        graphics.pose().popMatrix();
        if (showSubtitle) {
            AnchorsUi.label(graphics, this.font, this.subtitle, titleX, titleY + titleHeight + 2,
                    AnchorsTheme.fade(AnchorsTheme.TEXT_MUTED, intro), false);
        }

        // The version sits in a chip on the right, when the title leaves room for it.
        if (!this.versionLabel.isEmpty()) {
            int chipWidth = this.font.width(this.versionLabel) + 10;
            int chipX = header.right() - this.layout.padding - chipWidth;
            int chipY = header.y() + (header.height() - 13) / 2;
            int titleRight = titleX + Math.round(this.font.width(title) * scale);
            if (chipX > titleRight + 10 && (!showSubtitle || chipX > titleX + this.font.width(this.subtitle) + 10)) {
                AnchorsUi.panel(graphics, chipX, chipY, chipWidth, 13,
                        AnchorsTheme.fade(0x803A1560, intro), AnchorsTheme.fade(0x80200A36, intro));
                AnchorsUi.roundedOutline(graphics, chipX, chipY, chipWidth, 13,
                        AnchorsTheme.fade(AnchorsTheme.CARD_BORDER_HOVER, intro * 0.8F));
                AnchorsUi.label(graphics, this.font, this.versionLabel, chipX + 5, chipY + 3,
                        AnchorsTheme.fade(AnchorsTheme.ACCENT_BRIGHT, intro), false);
            }
        }
    }

    private void drawOptions(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, long now,
            boolean motion) {
        AnchorsLayout.Rect viewport = this.layout.options;
        if (viewport.width() <= 0 || viewport.height() <= 0) {
            return;
        }
        int offset = Math.round(this.scroll);
        int lineRight = viewport.x() + this.layout.rowWidth();
        graphics.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
        for (Section section : this.sections) {
            int y = viewport.y() + section.offset() + 2 - offset;
            if (y + SECTION_HEIGHT < viewport.y() || y > viewport.bottom()) {
                continue;
            }
            AnchorsUi.label(graphics, this.font, section.title(), viewport.x() + 2, y, AnchorsTheme.SECTION, false);
            int lineX = viewport.x() + 8 + this.font.width(section.title());
            if (lineX < lineRight) {
                graphics.fill(lineX, y + 4, lineRight, y + 5, AnchorsTheme.withAlpha(AnchorsTheme.ACCENT, 70));
            }
        }
        for (int index = 0; index < this.rows.size(); index++) {
            AnchorSwitchRow row = this.rows.get(index);
            if (row.getY() >= viewport.bottom() || row.getY() + row.getHeight() <= viewport.y()) {
                continue;
            }
            float appear = motion
                    ? AnchorsTheme.easeOutCubic(progress(now - this.openedAt - INTRO_NANOS / 3 - index * ROW_STAGGER_NANOS,
                            ROW_FADE_NANOS))
                    : 1.0F;
            row.setAppear(appear);
            row.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
        if (this.statsOffset >= 0) {
            drawStats(graphics, viewport.x() + 2, viewport.y() + this.statsOffset - offset,
                    this.layout.rowWidth() - 4, 1.0F, false);
        }
        graphics.disableScissor();

        if (this.maxScroll > 0) {
            int trackX = viewport.right() - 3;
            graphics.fill(trackX, viewport.y(), trackX + 2, viewport.bottom(), AnchorsTheme.SCROLL_TRACK);
            int thumbHeight = Math.max(12, viewport.height() * viewport.height() / Math.max(1, this.contentHeight));
            int thumbY = viewport.y() + Math.round((viewport.height() - thumbHeight) * (this.scroll / this.maxScroll));
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, AnchorsTheme.SCROLL_THUMB);
        }
    }

    private void drawPreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float intro, boolean motion) {
        AnchorsLayout.Rect preview = this.layout.preview;
        AnchorsUi.panel(graphics, preview.x(), preview.y(), preview.width(), preview.height(),
                AnchorsTheme.fade(AnchorsTheme.CARD, intro), AnchorsTheme.fade(0x1C12061E, intro));
        boolean hovered = this.previewArt.contains(mouseX, mouseY);
        AnchorsUi.roundedOutline(graphics, preview.x(), preview.y(), preview.width(), preview.height(),
                AnchorsTheme.fade(hovered ? AnchorsTheme.CARD_BORDER_HOVER : AnchorsTheme.CARD_BORDER, intro));

        this.anchorPreview.render(graphics, this.font, this.previewArt, mouseX, mouseY, motion, intro, this.previewHint);

        int statsTop = preview.bottom() - STATS_HEIGHT - 8;
        graphics.fill(preview.x() + 8, statsTop - 1, preview.right() - 8, statsTop,
                AnchorsTheme.withAlpha(AnchorsTheme.ACCENT, Math.round(60 * intro)));
        drawStats(graphics, preview.x() + 8, statsTop + 4, preview.width() - 16, intro, true);
    }

    private void drawStats(GuiGraphicsExtractor graphics, int x, int y, int width, float alpha, boolean withTitle) {
        int lineY = y;
        if (withTitle) {
            AnchorsUi.label(graphics, this.font, this.statsTitle, x, lineY, AnchorsTheme.fade(AnchorsTheme.SECTION, alpha),
                    false);
            lineY += 13;
        }
        int[] values = {
                AnchorStats.orderedBursts(),
                AnchorStats.retargetedUses(),
                AnchorStats.heldClicks(),
                AnchorStats.predictedDetonations(),
                AnchorStats.confirmedDetonations()
        };
        for (int index = 0; index < values.length; index++) {
            String value = Integer.toString(values[index]);
            int valueWidth = this.font.width(value);
            String name = AnchorsUi.fit(this.font, this.statLabels[index], width - valueWidth - 6);
            AnchorsUi.label(graphics, this.font, name, x, lineY, AnchorsTheme.fade(AnchorsTheme.TEXT_MUTED, alpha), false);
            AnchorsUi.label(graphics, this.font, value, x + width - valueWidth, lineY,
                    AnchorsTheme.fade(AnchorsTheme.ACCENT_BRIGHT, alpha), false);
            lineY += STAT_LINE;
        }
    }

    private void drawFooterNote(GuiGraphicsExtractor graphics, float intro) {
        AnchorsLayout.Rect footer = this.layout.footer;
        int left = this.layout.resetButton.right() + 8;
        int right = this.layout.doneButton.x() - 8;
        int noteWidth = this.font.width(this.footerNote);
        if (right - left < noteWidth) {
            return;
        }
        AnchorsUi.label(graphics, this.font, this.footerNote, left + (right - left - noteWidth) / 2,
                footer.y() + (footer.height() - 8) / 2 + 1, AnchorsTheme.fade(AnchorsTheme.TEXT_DIM, intro), false);
    }

    /** The header's small anchor charges one light at a time and starts over. */
    private void updateAnchorCycle(double seconds, boolean motion) {
        if (!motion) {
            this.anchorCharge = 4.0F;
            return;
        }
        double time = seconds % CYCLE_SECONDS;
        this.anchorCharge = time < 0.6D ? 0.0F : (float) Math.min(4.0D, (time - 0.6D) / 0.5D);
    }

    // ------------------------------------------------------------------------------------------
    // Input and lifecycle
    // ------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (this.anchorPreview.mouseClicked(this.previewArt, event.x(), event.y(), event.button(), doubleClick)) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.anchorPreview.mouseDragged(dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.anchorPreview.mouseReleased()) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.anchorPreview.mouseScrolled(this.previewArt, mouseX, mouseY, verticalAmount)) {
            return true;
        }
        if (this.maxScroll > 0 && verticalAmount != 0.0D && this.layout.options.contains(mouseX, mouseY)) {
            this.scrollTarget = Mth.clamp(this.scrollTarget - (float) verticalAmount * 22.0F, 0.0F, this.maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    /** Keyboard focus on a row out of view scrolls it into view. */
    private void followFocus() {
        GuiEventListener focused = getFocused();
        if (focused == this.lastFocused) {
            return;
        }
        this.lastFocused = focused;
        int index = focused instanceof AnchorSwitchRow row ? this.rows.indexOf(row) : -1;
        if (index < 0) {
            return;
        }
        int top = this.rowOffsets.get(index);
        int bottom = top + this.rows.get(index).getHeight();
        int viewHeight = this.layout.options.height();
        if (top < this.scrollTarget) {
            this.scrollTarget = Math.max(0, top - 2);
        } else if (bottom > this.scrollTarget + viewHeight) {
            this.scrollTarget = Math.min(this.maxScroll, bottom - viewHeight + 2);
        }
    }

    private void updateScroll(float frameMillis, boolean motion) {
        if (Math.abs(this.scrollTarget - this.scroll) < 0.01F) {
            return;
        }
        float response = motion ? 1.0F - (float) Math.exp(-frameMillis / 55.0F) : 1.0F;
        this.scroll += (this.scrollTarget - this.scroll) * response;
        if (Math.abs(this.scrollTarget - this.scroll) < 0.5F) {
            this.scroll = this.scrollTarget;
        }
        applyScroll();
    }

    private void resetSettings() {
        AnchorsConfig.reset();
    }

    @Override
    public void onClose() {
        saveSettings();
        Mc.setScreen(this.minecraft, this.parent);
    }

    @Override
    public void removed() {
        saveSettings();
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    private void saveSettings() {
        if (this.saved) {
            return;
        }
        AnchorsConfig.save();
        this.saved = true;
    }

    private static float progress(long elapsed, long duration) {
        return Mth.clamp(elapsed / (float) duration, 0.0F, 1.0F);
    }

    private record Section(String title, int offset) {
    }
}
