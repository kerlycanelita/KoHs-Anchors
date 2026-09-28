package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import dev.zymekoh.kohsanchors.compat.Mc;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.input.AnchorStats;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/**
 * The KoHs Anchor's settings, in the Zymekoh style: black-purple glass over a transparent veil,
 * an anchor sigil turning slowly behind it, four tabs, and the 3D anchor inside its ritual circle
 * with this session's numbers.
 *
 * <p>The fourth tab holds the advanced, not secure options. It is crimson wherever it appears,
 * each of its options says "not secure", and switching one on opens {@link AnchorsWarning}
 * first.</p>
 *
 * <p>Geometry comes from {@link AnchorsLayout}. Every animation is timed in real time and moves
 * only decoration: rows fade in but are always where their hitboxes are, and the scrolling area
 * clips both drawing and clicks.</p>
 */
public final class AnchorsScreen extends Screen {
    private static final long INTRO_NANOS = 420_000_000L;
    private static final long ROW_FADE_NANOS = 240_000_000L;
    private static final long ROW_STAGGER_NANOS = 45_000_000L;
    private static final long TAB_SLASH_NANOS = 220_000_000L;
    private static final int SECTION_HEIGHT = 14;
    private static final int SECTION_GAP = 6;
    private static final int ROW_GAP = 4;
    private static final int STAT_LINE = 11;
    private static final int STATS_HEIGHT = 13 + STAT_LINE * 5;
    private static final int ADVANCED = 3;
    private static final double CYCLE_SECONDS = 4.8D;
    private static final String[] TAB_KEYS = {"precision", "effects", "interface", "advanced"};

    /** The tab the screen opens on: the last one used this session. */
    private static int lastTab;

    private final Screen parent;
    private final String versionLabel;
    private final List<AnchorSwitchRow> rows = new ArrayList<>();
    private final List<Integer> rowOffsets = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    private final AnchorPreview anchorPreview = new AnchorPreview();
    private final float[] tabHover = new float[AnchorsLayout.TAB_COUNT];

    private AnchorsLayout layout;
    private AnchorsLayout.Rect previewArt = AnchorsLayout.Rect.EMPTY;
    private int tab = lastTab;
    private long tabChangedAt = -1L;
    private int statsOffset = -1;
    private int bannerOffset = -1;
    private int bannerHeight;
    private int contentHeight;
    private int maxScroll;
    private float scroll;
    private float scrollTarget;
    private long openedAt;
    private long lastFrame;
    private boolean saved;
    private GuiEventListener lastFocused;
    private AnchorsWarning warning;

    private String subtitle = "";
    private String previewHint = "";
    private String footerNote = "";
    private String statsTitle = "";
    private String[] tabLabels = new String[0];
    private String[] tabShortLabels = new String[0];

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
        this.footerNote = Component.translatable(this.tab == ADVANCED ? "kohs_anchors.footer.note.advanced"
                : "kohs_anchors.footer.note").getString();
        this.statsTitle = Component.translatable("kohs_anchors.stats.title").getString();
        this.tabLabels = new String[AnchorsLayout.TAB_COUNT];
        this.tabShortLabels = new String[AnchorsLayout.TAB_COUNT];
        for (int index = 0; index < AnchorsLayout.TAB_COUNT; index++) {
            this.tabLabels[index] = Component.translatable("kohs_anchors.tab." + TAB_KEYS[index]).getString()
                    .toUpperCase(Locale.ROOT);
            this.tabShortLabels[index] = Component.translatable("kohs_anchors.tab." + TAB_KEYS[index] + ".short")
                    .getString().toUpperCase(Locale.ROOT);
        }

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
        this.bannerOffset = -1;
        switch (this.tab) {
            case 0 -> {
                offset = section(offset, "kohs_anchors.section.input");
                offset = row(offset, "input_order", () -> AnchorsConfig.settings().inputOrder,
                        value -> AnchorsConfig.settings().inputOrder = value);
                offset = row(offset, "fresh_target", () -> AnchorsConfig.settings().freshTarget,
                        value -> AnchorsConfig.settings().freshTarget = value);
                offset = row(offset, "hold_early_clicks", () -> AnchorsConfig.settings().holdEarlyClicks,
                        value -> AnchorsConfig.settings().holdEarlyClicks = value);
                offset = row(offset, "no_stacking", () -> AnchorsConfig.settings().noStacking,
                        value -> AnchorsConfig.settings().noStacking = value);
            }
            case 1 -> {
                offset = section(offset, "kohs_anchors.section.feedback");
                offset = row(offset, "predict_detonation", () -> AnchorsConfig.settings().predictDetonation,
                        value -> AnchorsConfig.settings().predictDetonation = value);
                offset = row(offset, "hide_detonating", () -> AnchorsConfig.settings().hideDetonating,
                        value -> AnchorsConfig.settings().hideDetonating = value);
                offset = row(offset, "anchor_debris", () -> AnchorsConfig.settings().anchorDebris,
                        value -> AnchorsConfig.settings().anchorDebris = value);
            }
            case 2 -> {
                offset = section(offset, "kohs_anchors.section.interface");
                offset = row(offset, "interface_motion", () -> AnchorsConfig.settings().interfaceMotion,
                        value -> AnchorsConfig.settings().interfaceMotion = value);
            }
            default -> {
                this.bannerOffset = offset;
                this.bannerHeight = bannerHeightFor(this.layout.rowWidth());
                offset += this.bannerHeight + SECTION_GAP;
                offset = section(offset, "kohs_anchors.section.advanced");
                offset = dangerRow(offset, "fast_chain", () -> AnchorsConfig.settings().fastChain,
                        value -> AnchorsConfig.settings().fastChain = value);
                offset = dangerRow(offset, "instant_detonation", () -> AnchorsConfig.settings().instantDetonation,
                        value -> AnchorsConfig.settings().instantDetonation = value);
            }
        }
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
        this.sections.add(new Section(Component.translatable(key).getString().toUpperCase(Locale.ROOT), start));
        return start + SECTION_HEIGHT;
    }

    private int row(int offset, String option, BooleanSupplier state, Consumer<Boolean> set) {
        return addRow(offset, option, state, () -> set.accept(!state.getAsBoolean()), false);
    }

    /** An advanced option: switching it off is immediate, switching it on asks first. */
    private int dangerRow(int offset, String option, BooleanSupplier state, Consumer<Boolean> set) {
        return addRow(offset, option, state, () -> {
            if (state.getAsBoolean()) {
                set.accept(false);
                AnchorsConfig.save();
                return;
            }
            this.warning = new AnchorsWarning(Component.translatable("kohs_anchors.option." + option),
                    AnchorsConfig.settings().interfaceMotion, () -> {
                        set.accept(true);
                        AnchorsConfig.save();
                    });
        }, true);
    }

    private int addRow(int offset, String option, BooleanSupplier state, Runnable toggle, boolean danger) {
        AnchorSwitchRow row = new AnchorSwitchRow(this.layout.options.x(), this.layout.options.y() + offset,
                this.layout.rowWidth(),
                Component.translatable("kohs_anchors.option." + option),
                Component.translatable("kohs_anchors.option." + option + ".description"),
                this.font, state, toggle, danger,
                danger ? Component.translatable("kohs_anchors.tag.not_secure") : null);
        addWidget(row);
        this.rows.add(row);
        this.rowOffsets.add(offset);
        return offset + row.getHeight() + ROW_GAP;
    }

    private int bannerHeightFor(int width) {
        List<FormattedCharSequence> lines = this.font.split(Component.translatable("kohs_anchors.advanced.banner.text"),
                Math.max(40, width - 34));
        return 8 + 11 + lines.size() * 10 + 6;
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

    private void selectTab(int index) {
        if (index == this.tab || index < 0 || index >= AnchorsLayout.TAB_COUNT) {
            return;
        }
        this.tab = index;
        lastTab = index;
        this.tabChangedAt = System.nanoTime();
        this.scroll = 0.0F;
        this.scrollTarget = 0.0F;
        rebuildWidgets();
    }

    // ------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // The screen owns its background: no blur and no menu darkening, only a veil the world
        // (or, from the title screen, the panorama) shows through.
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
        // Under the warning nothing is hovered: the modal owns the pointer.
        int pointerX = this.warning == null ? mouseX : -1;
        int pointerY = this.warning == null ? mouseY : -1;

        followFocus();
        updateScroll(frameMillis, motion);
        updateAnchorCycle(seconds, motion);

        boolean advanced = this.tab == ADVANCED;
        if (motion) {
            AnchorsLayout.Rect panel = this.layout.panel;
            AnchorsUi.sigil(graphics, panel.centerX(), panel.centerY(),
                    Math.round(Math.min(panel.width(), panel.height()) * 0.62F), seconds,
                    advanced ? AnchorsTheme.CRIMSON : AnchorsTheme.ACCENT_DEEP, 0.55F * intro, this.anchorCharge);
            AnchorsUi.motes(graphics, this.width, this.height, seconds, intro);
        }
        drawPanel(graphics, intro, seconds, motion, advanced);
        drawHeader(graphics, intro, seconds);
        drawTabs(graphics, pointerX, pointerY, intro, seconds, frameMillis, motion);
        drawOptions(graphics, pointerX, pointerY, partialTick, now, motion, seconds);
        if (this.layout.showsPreview()) {
            drawPreview(graphics, pointerX, pointerY, intro, motion, seconds);
        }
        drawFooterNote(graphics, intro, advanced);
        super.extractRenderState(graphics, pointerX, pointerY, partialTick);

        if (this.warning != null) {
            this.warning.render(graphics, this.font, this.width, this.height, mouseX, mouseY);
            if (this.warning.done()) {
                this.warning = null;
            }
        }
    }

    private void drawPanel(GuiGraphicsExtractor graphics, float intro, double seconds, boolean motion, boolean advanced) {
        AnchorsLayout.Rect panel = this.layout.panel;
        float grow = 0.94F + intro * 0.06F;
        int width = Math.max(1, Math.round(panel.width() * grow));
        int height = Math.max(1, Math.round(panel.height() * grow));
        int x = panel.x() + (panel.width() - width) / 2;
        int y = panel.y() + (panel.height() - height) / 2;
        AnchorsUi.halo(graphics, x, y, width, height, advanced ? AnchorsTheme.CRIMSON : AnchorsTheme.ACCENT, 6, 0.6F * intro);
        AnchorsUi.panel(graphics, x, y, width, height,
                AnchorsTheme.fade(AnchorsTheme.PANEL_TOP, 0.3F + intro * 0.7F),
                AnchorsTheme.fade(AnchorsTheme.PANEL_BOTTOM, 0.3F + intro * 0.7F));
        AnchorsUi.roundedOutline(graphics, x, y, width, height, AnchorsTheme.fade(
                advanced ? 0xE0FF315C : AnchorsTheme.PANEL_BORDER, intro));
        if (intro < 0.98F) {
            return;
        }
        AnchorsUi.bladeCorners(graphics, panel.x(), panel.y(), panel.width(), panel.height(), 9,
                advanced ? 0xE0FF6A86 : 0xE0E9D5FF);
        if (motion) {
            AnchorsUi.comets(graphics, panel.x(), panel.y(), panel.width(), panel.height(), seconds,
                    advanced ? 0xFF9AB0 : 0xE8CCFF);
        }
        int headerLine = this.layout.header.bottom() - 1;
        AnchorsUi.energyLine(graphics, panel.x() + 8, panel.right() - 8, headerLine,
                advanced ? AnchorsTheme.CRIMSON_BRIGHT : AnchorsTheme.ACCENT, motion ? seconds : 0.0D, 1.0F);
        int footerLine = this.layout.footer.y();
        graphics.fill(panel.x() + 8, footerLine, panel.right() - 8, footerLine + 1, AnchorsTheme.HEADER_LINE);
    }

    private void drawHeader(GuiGraphicsExtractor graphics, float intro, double seconds) {
        AnchorsLayout.Rect header = this.layout.header;
        int iconX = header.x() + this.layout.padding + 2;
        int iconY = header.y() + (header.height() - 12) / 2;
        if (header.height() >= 30) {
            AnchorsUi.ring(graphics, iconX + 6, iconY + 6, 11, 1,
                    AnchorsTheme.withAlpha(AnchorsTheme.ACCENT, Math.round(120 * intro)));
        }
        AnchorsUi.miniAnchor(graphics, iconX, iconY, this.anchorCharge, intro);

        String title = getTitle().getString().toUpperCase(Locale.ROOT);
        int titleX = iconX + 20;
        boolean large = !this.layout.compact && header.height() >= 30;
        float scale = large ? 1.6F : 1.0F;
        int titleHeight = Math.round(9 * scale);
        boolean showSubtitle = large;
        int blockHeight = titleHeight + (showSubtitle ? 11 : 0);
        int titleY = header.y() + (header.height() - blockHeight) / 2 + 1;

        graphics.pose().pushMatrix();
        graphics.pose().translate(titleX, titleY);
        graphics.pose().scale(scale, scale);
        AnchorsUi.label(graphics, this.font, title, 1, 1, AnchorsTheme.fade(0xFF5B1FB0, intro), false);
        AnchorsUi.label(graphics, this.font, title, 0, 0, AnchorsTheme.fade(AnchorsTheme.TITLE, intro), false);
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

    private void drawTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float intro, double seconds,
            float frameMillis, boolean motion) {
        float response = 1.0F - (float) Math.exp(-frameMillis / 70.0F);
        boolean advancedOn = AnchorsConfig.settings().fastChain || AnchorsConfig.settings().instantDetonation;
        for (int index = 0; index < AnchorsLayout.TAB_COUNT; index++) {
            AnchorsLayout.Rect rect = this.layout.tab(index);
            boolean selected = index == this.tab;
            boolean danger = index == ADVANCED;
            boolean hovered = rect.contains(mouseX, mouseY);
            this.tabHover[index] += ((hovered ? 1.0F : 0.0F) - this.tabHover[index]) * response;
            float hover = this.tabHover[index];

            int accent = danger ? AnchorsTheme.CRIMSON_BRIGHT : AnchorsTheme.ACCENT;
            int top = selected ? (danger ? 0xE0521028 : 0xE03A1668) : AnchorsTheme.lerp(0x801D0D32, 0xB02A1248, hover);
            int bottom = selected ? (danger ? 0xE02A0612 : 0xE01D0D32) : AnchorsTheme.lerp(0x7012091F, 0x901D0D32, hover);
            AnchorsUi.panel(graphics, rect.x(), rect.y(), rect.width(), rect.height(), AnchorsTheme.fade(top, intro),
                    AnchorsTheme.fade(bottom, intro));
            int border = selected ? AnchorsTheme.fade(accent, intro)
                    : AnchorsTheme.fade(AnchorsTheme.lerp(danger ? 0x8A6A1030 : AnchorsTheme.CARD_BORDER,
                            danger ? 0xE0FF6A86 : AnchorsTheme.CARD_BORDER_HOVER, hover), intro);
            AnchorsUi.roundedOutline(graphics, rect.x(), rect.y(), rect.width(), rect.height(), border);
            if (selected) {
                float breathe = motion ? 0.7F + 0.3F * AnchorsTheme.pulse(seconds, 2.4D) : 1.0F;
                graphics.fill(rect.x() + 3, rect.bottom() - 2, rect.right() - 3, rect.bottom() - 1,
                        AnchorsTheme.fade(accent, intro * breathe));
                AnchorsUi.halo(graphics, rect.x(), rect.y(), rect.width(), rect.height(), accent, 2, 0.5F * intro * breathe);
            }

            String label = this.tabLabels[index];
            if (this.font.width(label) + 18 > rect.width()) {
                label = this.tabShortLabels[index];
            }
            boolean iconOnly = this.font.width(label) + 18 > rect.width();
            int iconSize = 9;
            int contentWidth = iconSize + (iconOnly ? 0 : 4 + this.font.width(label));
            int startX = rect.x() + (rect.width() - contentWidth) / 2;
            int iconY = rect.y() + (rect.height() - iconSize) / 2;
            int iconColor = AnchorsTheme.fade(selected || hover > 0.5F ? (danger ? 0xFFFF6A86 : AnchorsTheme.ACCENT_BRIGHT)
                    : (danger ? 0xFFB8243F : AnchorsTheme.SILVER), intro);
            drawTabIcon(graphics, index, startX, iconY, iconSize, iconColor);
            if (!iconOnly) {
                int textColor = selected ? AnchorsTheme.TITLE : AnchorsTheme.lerp(AnchorsTheme.TEXT_MUTED, AnchorsTheme.TEXT, hover);
                if (danger && !selected) {
                    textColor = AnchorsTheme.lerp(0xFFE08A9E, 0xFFFFD6DE, hover);
                }
                AnchorsUi.label(graphics, this.font, label, startX + iconSize + 4, rect.y() + (rect.height() - 8) / 2,
                        AnchorsTheme.fade(textColor, intro), false);
            }
            if (danger && advancedOn) {
                // A live advanced option: a crimson ember in the tab's corner.
                float ember = motion ? 0.6F + 0.4F * AnchorsTheme.pulse(seconds, 1.4D) : 1.0F;
                AnchorsUi.diamond(graphics, rect.right() - 5, rect.y() + 4, 2,
                        AnchorsTheme.withAlpha(0xFF315C, Math.round(255 * ember * intro)));
            }
        }
    }

    /** Small icons drawn from pixels: a crosshair, a burst, sliders, and the warning sign. */
    private static void drawTabIcon(GuiGraphicsExtractor graphics, int index, int x, int y, int size, int color) {
        int center = size / 2;
        switch (index) {
            case 0 -> {
                AnchorsUi.ring(graphics, x + center, y + center, center, 1, color);
                graphics.fill(x + center, y - 1, x + center + 1, y + 2, color);
                graphics.fill(x + center, y + size - 2, x + center + 1, y + size + 1, color);
                graphics.fill(x - 1, y + center, x + 2, y + center + 1, color);
                graphics.fill(x + size - 2, y + center, x + size + 1, y + center + 1, color);
                graphics.fill(x + center, y + center, x + center + 1, y + center + 1, color);
            }
            case 1 -> {
                AnchorsUi.diamond(graphics, x + center, y + center, 2, color);
                graphics.fill(x + center, y, x + center + 1, y + 2, color);
                graphics.fill(x + center, y + size - 2, x + center + 1, y + size, color);
                graphics.fill(x, y + center, x + 2, y + center + 1, color);
                graphics.fill(x + size - 2, y + center, x + size, y + center + 1, color);
                graphics.fill(x + 1, y + 1, x + 2, y + 2, color);
                graphics.fill(x + size - 2, y + 1, x + size - 1, y + 2, color);
                graphics.fill(x + 1, y + size - 2, x + 2, y + size - 1, color);
                graphics.fill(x + size - 2, y + size - 2, x + size - 1, y + size - 1, color);
            }
            case 2 -> {
                for (int line = 0; line < 3; line++) {
                    int ly = y + 1 + line * 3;
                    graphics.fill(x, ly, x + size, ly + 1, AnchorsTheme.fade(color, 0.55F));
                    int knob = x + (line == 1 ? size - 3 : line * 2 + 1);
                    graphics.fill(knob, ly - 1, knob + 2, ly + 2, color);
                }
            }
            default -> AnchorsUi.warningGlyph(graphics, x + center, y, size, color, 0xFF1A0308);
        }
    }

    private void drawOptions(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, long now,
            boolean motion, double seconds) {
        AnchorsLayout.Rect viewport = this.layout.options;
        if (viewport.width() <= 0 || viewport.height() <= 0) {
            return;
        }
        boolean advanced = this.tab == ADVANCED;
        long since = this.tabChangedAt < 0L ? now - this.openedAt - INTRO_NANOS / 3 : now - this.tabChangedAt;
        int offset = Math.round(this.scroll);
        int lineRight = viewport.x() + this.layout.rowWidth();
        graphics.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
        if (this.bannerOffset >= 0) {
            drawBanner(graphics, viewport.x(), viewport.y() + this.bannerOffset - offset, this.layout.rowWidth(), motion,
                    seconds, motion ? AnchorsTheme.easeOutCubic(progress(since, ROW_FADE_NANOS)) : 1.0F);
        }
        for (Section section : this.sections) {
            int y = viewport.y() + section.offset() + 2 - offset;
            if (y + SECTION_HEIGHT < viewport.y() || y > viewport.bottom()) {
                continue;
            }
            int sectionColor = advanced ? 0xFFFF9AB0 : AnchorsTheme.SECTION;
            AnchorsUi.diamond(graphics, viewport.x() + 3, y + 4, 2, advanced ? AnchorsTheme.CRIMSON_BRIGHT : AnchorsTheme.ACCENT);
            AnchorsUi.label(graphics, this.font, section.title(), viewport.x() + 9, y, sectionColor, false);
            int lineX = viewport.x() + 15 + this.font.width(section.title());
            if (lineX < lineRight) {
                AnchorsUi.energyLine(graphics, lineX, lineRight, y + 4,
                        advanced ? AnchorsTheme.CRIMSON_BRIGHT : AnchorsTheme.ACCENT, motion ? seconds : 0.0D, 0.8F);
            }
        }
        for (int index = 0; index < this.rows.size(); index++) {
            AnchorSwitchRow row = this.rows.get(index);
            if (row.getY() >= viewport.bottom() || row.getY() + row.getHeight() <= viewport.y()) {
                continue;
            }
            float appear = motion
                    ? AnchorsTheme.easeOutCubic(progress(since - index * ROW_STAGGER_NANOS, ROW_FADE_NANOS))
                    : 1.0F;
            row.setAppear(appear);
            row.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
        if (this.statsOffset >= 0) {
            drawStats(graphics, viewport.x() + 2, viewport.y() + this.statsOffset - offset,
                    this.layout.rowWidth() - 4, 1.0F, false);
        }
        graphics.disableScissor();

        // A blade crosses the options when the tab changes.
        if (motion && this.tabChangedAt >= 0L) {
            float slash = progress(now - this.tabChangedAt, TAB_SLASH_NANOS);
            AnchorsUi.slash(graphics, viewport, slash, advanced ? AnchorsTheme.CRIMSON_BRIGHT : AnchorsTheme.ACCENT_BRIGHT);
        }

        if (this.maxScroll > 0) {
            int trackX = viewport.right() - 3;
            graphics.fill(trackX, viewport.y(), trackX + 2, viewport.bottom(), AnchorsTheme.SCROLL_TRACK);
            int thumbHeight = Math.max(12, viewport.height() * viewport.height() / Math.max(1, this.contentHeight));
            int thumbY = viewport.y() + Math.round((viewport.height() - thumbHeight) * (this.scroll / this.maxScroll));
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight,
                    advanced ? 0xE6FF6A86 : AnchorsTheme.SCROLL_THUMB);
            // Soft fades where the list continues beyond the view.
            if (this.scroll > 0.5F) {
                graphics.fillGradient(viewport.x(), viewport.y(), trackX - 1, viewport.y() + 8, 0x800B0514, 0x000B0514);
            }
            if (this.scroll < this.maxScroll - 0.5F) {
                graphics.fillGradient(viewport.x(), viewport.bottom() - 8, trackX - 1, viewport.bottom(), 0x000B0514,
                        0x800B0514);
            }
        }
    }

    /** The advanced tab's warning banner: crimson, with the sign and a slow pulse. */
    private void drawBanner(GuiGraphicsExtractor graphics, int x, int y, int width, boolean motion, double seconds,
            float appear) {
        float pulse = motion ? 0.55F + 0.45F * AnchorsTheme.pulse(seconds, 2.2D) : 0.8F;
        AnchorsUi.panel(graphics, x, y, width, this.bannerHeight, AnchorsTheme.fade(0xD03A0A1A, appear),
                AnchorsTheme.fade(0xD0180410, appear));
        AnchorsUi.roundedOutline(graphics, x, y, width, this.bannerHeight,
                AnchorsTheme.withAlpha(0xFF315C, Math.round((120 + 120 * pulse) * appear)));
        AnchorsUi.bladeCorners(graphics, x, y, width, this.bannerHeight, 5, AnchorsTheme.fade(0xFFFF6A86, appear));
        AnchorsUi.warningGlyph(graphics, x + 13, y + 7, 13, AnchorsTheme.fade(0xFFFF315C, appear), 0xFF1A0308);
        String title = Component.translatable("kohs_anchors.advanced.banner.title").getString().toUpperCase(Locale.ROOT);
        AnchorsUi.label(graphics, this.font, title, x + 26, y + 8, AnchorsTheme.fade(0xFFFFE4EA, appear), true);
        int lineY = y + 8 + 11;
        for (FormattedCharSequence line : this.font.split(Component.translatable("kohs_anchors.advanced.banner.text"),
                Math.max(40, width - 34))) {
            AnchorsUi.line(graphics, this.font, line, x + 26, lineY, AnchorsTheme.fade(0xFFE8C5CE, appear));
            lineY += 10;
        }
    }

    private void drawPreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float intro, boolean motion,
            double seconds) {
        AnchorsLayout.Rect preview = this.layout.preview;
        boolean advanced = this.tab == ADVANCED;
        AnchorsUi.panel(graphics, preview.x(), preview.y(), preview.width(), preview.height(),
                AnchorsTheme.fade(0x6A12091F, intro), AnchorsTheme.fade(0x5A08050D, intro));
        boolean hovered = this.previewArt.contains(mouseX, mouseY);
        AnchorsUi.roundedOutline(graphics, preview.x(), preview.y(), preview.width(), preview.height(),
                AnchorsTheme.fade(hovered ? AnchorsTheme.CARD_BORDER_HOVER
                        : advanced ? 0x8A6A1030 : AnchorsTheme.CARD_BORDER, intro));
        AnchorsUi.bladeCorners(graphics, preview.x(), preview.y(), preview.width(), preview.height(), 6,
                AnchorsTheme.fade(advanced ? 0xC0FF6A86 : 0xC0C084FC, intro));

        // The ritual circle behind the anchor lights a node for every charge the anchor holds.
        int radius = Math.round(Math.min(this.previewArt.width(), this.previewArt.height()) * 0.46F);
        if (radius > 16) {
            AnchorsUi.sigil(graphics, this.previewArt.centerX(), this.previewArt.centerY() + radius / 6, radius,
                    motion ? seconds * 1.6D : 0.0D, advanced ? AnchorsTheme.CRIMSON_BRIGHT : AnchorsTheme.ACCENT,
                    0.8F * intro, this.anchorPreview.charge());
        }
        this.anchorPreview.render(graphics, this.font, this.previewArt, mouseX, mouseY, motion, intro, this.previewHint);

        int statsTop = preview.bottom() - STATS_HEIGHT - 8;
        AnchorsUi.energyLine(graphics, preview.x() + 8, preview.right() - 8, statsTop - 1,
                advanced ? AnchorsTheme.CRIMSON_BRIGHT : AnchorsTheme.ACCENT, motion ? seconds : 0.0D, intro);
        drawStats(graphics, preview.x() + 8, statsTop + 4, preview.width() - 16, intro, true);
    }

    private void drawStats(GuiGraphicsExtractor graphics, int x, int y, int width, float alpha, boolean withTitle) {
        int lineY = y;
        boolean advanced = this.tab == ADVANCED;
        if (withTitle) {
            AnchorsUi.label(graphics, this.font, this.statsTitle.toUpperCase(Locale.ROOT), x, lineY,
                    AnchorsTheme.fade(advanced ? 0xFFFF9AB0 : AnchorsTheme.SECTION, alpha), false);
            lineY += 13;
        }
        String[] keys = advanced
                ? new String[] {"chained", "instant", "merged", "dropped", "held"}
                : new String[] {"ordered", "retargeted", "held", "predicted", "confirmed"};
        int[] values = advanced
                ? new int[] {AnchorStats.chainedClicks(), AnchorStats.instantDetonations(), AnchorStats.mergedClicks(),
                        AnchorStats.droppedClicks(), AnchorStats.heldClicks()}
                : new int[] {AnchorStats.orderedBursts(), AnchorStats.retargetedUses(), AnchorStats.heldClicks(),
                        AnchorStats.predictedDetonations(), AnchorStats.confirmedDetonations()};
        for (int index = 0; index < values.length; index++) {
            String value = Integer.toString(values[index]);
            int valueWidth = this.font.width(value);
            String name = AnchorsUi.fit(this.font, Component.translatable("kohs_anchors.stats." + keys[index]).getString(),
                    width - valueWidth - 6);
            AnchorsUi.label(graphics, this.font, name, x, lineY, AnchorsTheme.fade(AnchorsTheme.TEXT_MUTED, alpha), false);
            AnchorsUi.label(graphics, this.font, value, x + width - valueWidth, lineY,
                    AnchorsTheme.fade(advanced ? 0xFFFFC2CE : AnchorsTheme.ACCENT_BRIGHT, alpha), false);
            lineY += STAT_LINE;
        }
    }

    private void drawFooterNote(GuiGraphicsExtractor graphics, float intro, boolean advanced) {
        AnchorsLayout.Rect footer = this.layout.footer;
        int left = this.layout.resetButton.right() + 8;
        int right = this.layout.doneButton.x() - 8;
        int noteWidth = this.font.width(this.footerNote);
        if (right - left < noteWidth) {
            return;
        }
        AnchorsUi.label(graphics, this.font, this.footerNote, left + (right - left - noteWidth) / 2,
                footer.y() + (footer.height() - 8) / 2 + 1,
                AnchorsTheme.fade(advanced ? 0xFFE08A9E : AnchorsTheme.TEXT_DIM, intro), false);
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
        if (this.warning != null) {
            return this.warning.mouseClicked(this.width, this.height, event.x(), event.y(), event.button());
        }
        for (int index = 0; index < AnchorsLayout.TAB_COUNT; index++) {
            if (event.button() == 0 && this.layout.tab(index).contains(event.x(), event.y())) {
                selectTab(index);
                return true;
            }
        }
        if (this.anchorPreview.mouseClicked(this.previewArt, event.x(), event.y(), event.button(), doubleClick)) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.warning != null) {
            return true;
        }
        if (this.anchorPreview.mouseDragged(dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.warning != null) {
            return true;
        }
        if (this.anchorPreview.mouseReleased()) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.warning != null) {
            return true;
        }
        if (this.anchorPreview.mouseScrolled(this.previewArt, mouseX, mouseY, verticalAmount)) {
            return true;
        }
        if (this.maxScroll > 0 && verticalAmount != 0.0D && this.layout.options.contains(mouseX, mouseY)) {
            this.scrollTarget = Mth.clamp(this.scrollTarget - (float) verticalAmount * 22.0F, 0.0F, this.maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.warning != null) {
            return this.warning.keyPressed(event.key());
        }
        return super.keyPressed(event);
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
