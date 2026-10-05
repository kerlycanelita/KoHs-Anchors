package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import dev.zymekoh.kohsanchors.bridge.BridgeClient;
import dev.zymekoh.kohsanchors.bridge.BridgeProtocol;
import dev.zymekoh.kohsanchors.compat.Mc;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.glow.AnchorGlowRenderer;
import dev.zymekoh.kohsanchors.glow.AnchorTracker;
import dev.zymekoh.kohsanchors.input.AnchorDebounce;
import dev.zymekoh.kohsanchors.input.AnchorStats;
import dev.zymekoh.kohsanchors.integration.CrystalPalette;
import dev.zymekoh.kohsanchors.integration.HerziumBridge;
import dev.zymekoh.kohsanchors.safety.ServerLock;
import dev.zymekoh.kohsanchors.skin.ColorMath;
import dev.zymekoh.kohsanchors.skin.SkinPaint;
import dev.zymekoh.kohsanchors.sound.AnchorSounds;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/**
 * The KoHs Anchor's settings, in the Zymekoh style: black-purple glass over a transparent veil,
 * an anchor sigil turning slowly behind it, eight tabs, and the 3D anchor inside its ritual circle
 * with this session's numbers.
 *
 * <ul>
 *   <li><b>General</b>: the anchor and glowstone debounce, the detonation, and what is always
 *   on.</li>
 *   <li><b>Anchor</b>: the workshop, where the anchor's two layers are coloured or painted pixel by
 *   pixel ({@link AnchorWorkshop}).</li>
 *   <li><b>Glow</b>: the light a charged anchor gives off, and the enemy anchors' colour on a page
 *   of its own.</li>
 *   <li><b>Sounds</b>: the charge and explosion sounds.</li>
 *   <li><b>Anchors Server</b>: the server's bridge. Entering it checks the server with a falling
 *   anchor ({@link ServerCheckWindow}): green into the column when a bridge answers, red under a
 *   padlock when none does. Then the anchor chain options the bridge allows and what it adds, each
 *   played once when switched on ({@link OptionFx}).</li>
 *   <li><b>Advanced</b>: developer mode, in a deeper purple.</li>
 *   <li><b>Herzium</b>: Herzium's hotbar order and how the two mods talk ({@link HerziumWindow}
 *   explains both). Without Herzium the tab is dimmed and opens a window with where to get it.</li>
 *   <li><b>KoHs</b>: who makes the mod, drawn as on the KoHs Mod Suite site, with links to
 *   Discord, the site and Modrinth ({@link KohsPage}).</li>
 * </ul>
 *
 * <p>The header's switch turns the whole screen to the enemy's anchors, in crimson. While enemy
 * anchors are off the page is only their switch and their red anchor under it; switched on, the
 * anchor flies into its column ({@link EnemyReveal}) and three tabs of their own are dealt in:
 * <b>Glow</b>, <b>Colours</b> (their colour) and <b>Advanced</b>, which is still being made and
 * shows a battle between the two sides ({@link EnemyBattle}). The switch back returns to the tab the
 * player left.</p>
 *
 * <p>The first time the screen opens, an anchor charges and explodes into anchors and glowstone,
 * and two windows say what the mod is and what its server bridge adds ({@link EntrySequence}).</p>
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
    private static final long RESET_ARM_NANOS = 2_500_000_000L;
    private static final int SECTION_HEIGHT = 14;
    private static final int SECTION_GAP = 6;
    private static final int ROW_GAP = 4;
    private static final int STAT_LINE = 11;
    private static final int STATS_HEIGHT = 13 + STAT_LINE * 5;
    private static final double CYCLE_SECONDS = 4.8D;

    static final int GENERAL = 0;
    static final int ANCHOR = 1;
    static final int GLOW = 2;
    static final int SOUNDS = 3;
    static final int SERVER = 4;
    static final int ADVANCED = 5;
    static final int HERZIUM = 6;
    static final int KOHS = 7;
    static final int ENEMY_GLOW = 8;
    static final int ENEMY_COLOURS = 9;
    static final int ENEMY_ADVANCED = 10;
    private static final String[] TAB_KEYS = {"general", "anchor", "glow", "sounds", "server", "advanced", "herzium", "kohs",
            "enemy_glow", "enemy_colours", "enemy_advanced"};
    /** The tab bar of each side: the player's eight tabs, the enemy anchors' three, none while theirs are off. */
    private static final int[] OWN_TABS = {GENERAL, ANCHOR, GLOW, SOUNDS, SERVER, ADVANCED, HERZIUM, KOHS};
    private static final int[] ENEMY_TABS = {ENEMY_GLOW, ENEMY_COLOURS, ENEMY_ADVANCED};
    private static final int[] NO_TABS = {};
    /** The connection the server check last ran for: it runs again on another server. */
    private static Object checkedFor = new Object();
    private static final long TAB_DEAL_NANOS = 300_000_000L;
    private static final long TAB_DEAL_STAGGER_NANOS = 55_000_000L;

    /** Debounce stops: Vanilla, then one millisecond to ten seconds, finer at the short end. */
    static final int[] DEBOUNCE_STOPS = {0, 1, 2, 3, 5, 8, 10, 15, 20, 25, 30, 40, 50, 60, 75, 80, 100, 120, 150, 175,
            200, 250, 300, 350, 400, 500, 600, 750, 1000, 1250, 1500, 2000, 2500, 3000, 4000, 5000, 6000, 7500, 10000};

    /** The tab the screen opens on: the last one used this session. */
    private static int lastTab;
    /** Whether the screen shows the enemy anchors' three tabs instead of the player's. */
    private static boolean enemyPage;
    /** The player's tab to return to from the enemy's, and the enemy tab to return to. */
    private static int ownTab = GLOW;
    private static int enemyTab = ENEMY_GLOW;

    private final Screen parent;
    private final String versionLabel;
    private final List<AnchorRow> rows = new ArrayList<>();
    private final List<Integer> rowOffsets = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    private final AnchorPreview anchorPreview = new AnchorPreview();
    /** The header's mark: a small Nether portal turning on itself. */
    private final HeaderPortal headerPortal = new HeaderPortal();
    private final float[] tabHover = new float[TAB_KEYS.length];

    private AnchorsLayout layout;
    private AnchorsLayout.Rect previewArt = AnchorsLayout.Rect.EMPTY;
    private int tab = lastTab;
    private long tabChangedAt = -1L;
    /** When the tab bar last changed sides: its tabs are dealt in one by one. */
    private long tabsDealtAt = -1L;
    private float enemySwitchHover;
    private int enemySwitchX;
    private int enemySwitchY;
    private int enemySwitchWidth;
    private int statsOffset = -1;
    private int contentHeight;
    private int maxScroll;
    private float scroll;
    private float scrollTarget;
    private long openedAt;
    private long lastFrame;
    private boolean saved;
    private GuiEventListener lastFocused;
    private float anchorCharge;
    private long resetArmedAt = -1L;
    private float previousPreviewX = -1.0F;
    private float previousPreviewY = -1.0F;

    private AnchorWorkshop workshop;
    private ServerCheckWindow serverCheck;
    private EntrySequence entry;
    private boolean entryStarted;
    private EnemyReveal enemyReveal;
    private OptionFx optionFx;
    /** The bridge's anchor in the Anchors Server tab: green with a bridge, red and locked without. */
    private final AnchorFigure bridgeFigure = new AnchorFigure("bridge");
    private BridgeClient.State shownBridgeState;
    /** When "Switch to enemy Anchor's" started calling attention to itself, after the first entry. */
    private long switchHighlightAt = -1L;
    private boolean highlightSwitch;
    private float enemyOffHover;
    private GlowstoneGuardWarning guardWarning;
    private HerziumWindow herziumWindow;
    private KohsPage kohsPage;
    private EnemyBattle battle;
    private EnemyIntro enemyIntro;
    private EnemySwitch enemySwitch;
    /** The enemy's anchor, as the world draws it: on the enemy page and in the switch. */
    private final AnchorFigure enemyFigure = new AnchorFigure("enemy");
    private boolean figureDragging;
    private double figureDragDistance;
    private DevModeWarning devWarning;
    private CrystalModal crystalModal;
    private SoundPicker soundPicker;
    private ColorPicker enemyPicker;
    private AnchorSliderRow draggingSlider;

    /**
     * Textures let go of while a frame is being drawn, closed when the next one starts: the frame
     * that is being drawn may still use them (the page changes in the middle of the enemy switch).
     */
    private final List<Runnable> releases = new ArrayList<>();

    private String subtitle = "";
    private String previewHint = "";
    private String statsTitle = "";
    private String[] tabLabels = new String[0];
    private String[] tabShortLabels = new String[0];

    public AnchorsScreen(Screen parent) {
        super(Component.translatable("kohs_anchors.screen.title"));
        this.parent = parent;
        this.versionLabel = FabricLoader.getInstance().getModContainer(KoHsAnchorsClient.MOD_ID)
                .map(container -> "v" + container.getMetadata().getVersion().getFriendlyString())
                .orElse("");
        CrystalPalette.syncIfChanged();
    }

    private static AnchorsConfig.Settings settings() {
        return AnchorsConfig.settings();
    }

    private static boolean dev() {
        return AnchorsConfig.settings().devMode;
    }

    @Override
    protected void init() {
        long now = System.nanoTime();
        if (this.openedAt == 0L) {
            this.openedAt = now;
        }
        if (!this.entryStarted) {
            this.entryStarted = true;
            AnchorsConfig.Settings startup = settings();
            if (startup.interfaceMotion || !startup.entryAccepted) {
                this.highlightSwitch = !startup.entryAccepted;
                this.entry = new EntrySequence(startup.interfaceMotion, startup.entryAccepted, this::openLink, () -> {
                    settings().entryAccepted = true;
                    AnchorsConfig.save();
                });
            }
        }
        this.lastFrame = now;
        this.saved = false;
        this.lastFocused = null;
        this.layout = AnchorsLayout.fit(this.width, this.height);
        this.subtitle = Component.translatable("kohs_anchors.screen.subtitle").getString();
        this.previewHint = Component.translatable("kohs_anchors.preview.hint").getString();
        this.statsTitle = Component.translatable("kohs_anchors.stats.title").getString();
        if (enemyPage != isEnemyTab(this.tab)) {
            // The tab and the side always agree: an enemy tab only while the enemy's side shows.
            this.tab = enemyPage ? enemyTab : ownTab;
            lastTab = this.tab;
        }
        this.tabLabels = new String[TAB_KEYS.length];
        this.tabShortLabels = new String[TAB_KEYS.length];
        for (int index = 0; index < TAB_KEYS.length; index++) {
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
        this.enemyPicker = null;
        if (this.tab == ANCHOR) {
            if (this.workshop == null) {
                this.workshop = new AnchorWorkshop(this);
                AnchorsLayout.Rect body = this.layout.body();
                float fromX = this.previousPreviewX >= 0.0F ? this.previousPreviewX : body.centerX();
                float fromY = this.previousPreviewY >= 0.0F ? this.previousPreviewY : body.centerY();
                this.workshop.enter(fromX, fromY);
            }
            this.workshop.layout(this.layout.body());
            this.contentHeight = 0;
        } else if (this.tab == KOHS) {
            if (this.kohsPage == null) {
                this.kohsPage = new KohsPage(settings().interfaceMotion, this);
                this.kohsPage.enter();
            }
            this.contentHeight = 0;
        } else if (this.tab == ENEMY_ADVANCED) {
            if (this.battle == null) {
                this.battle = new EnemyBattle(settings().interfaceMotion);
            }
            this.contentHeight = 0;
        } else if (enemyOff()) {
            // Enemy anchors off: their page is only the switch and their anchor, drawn by the screen.
            this.contentHeight = 0;
        } else {
            buildContent();
        }
        this.shownBridgeState = BridgeClient.state();

        this.maxScroll = Math.max(0, this.contentHeight - this.layout.options.height());
        this.scrollTarget = Mth.clamp(this.scrollTarget, 0.0F, this.maxScroll);
        this.scroll = Mth.clamp(this.scroll, 0.0F, this.maxScroll);
        applyScroll();

        addRenderableWidget(new AnchorsButton(this.layout.resetButton, Component.translatable("kohs_anchors.button.reset"),
                false, this::resetTab));
        addRenderableWidget(new AnchorsButton(this.layout.doneButton, Component.translatable("kohs_anchors.button.done"),
                true, this::onClose));
    }

    // ------------------------------------------------------------------------------------------
    // Content
    // ------------------------------------------------------------------------------------------

    private void buildContent() {
        int offset = 0;
        switch (this.tab) {
            case GENERAL -> offset = buildGeneral(offset);
            case GLOW -> offset = buildGlow(offset);
            case SOUNDS -> offset = buildSounds(offset);
            case HERZIUM -> offset = buildHerzium(offset);
            case SERVER -> offset = buildServer(offset);
            case ENEMY_GLOW -> offset = buildEnemyGlow(offset);
            case ENEMY_COLOURS -> offset = buildEnemyColours(offset);
            default -> offset = buildAdvanced(offset);
        }
        this.statsOffset = -1;
        if (!this.layout.showsPreview() && this.tab != ENEMY_COLOURS) {
            // Without the anchor column the session numbers move to the end of the list.
            offset = section(offset, "kohs_anchors.stats.title");
            this.statsOffset = offset - SECTION_HEIGHT;
            offset += STAT_LINE * 5;
        }
        if (this.tab == ENEMY_COLOURS) {
            // The colours tab ends in a full colour picker, as tall as the tab has room for.
            int pickerHeight = Math.max(ColorPicker.minHeight() + 20, Math.min(170, this.layout.options.height() - offset - 6));
            this.enemyPicker = new ColorPicker(() -> settings().enemyGlow.color, color -> {
                settings().enemyGlow.color = color | 0xFF000000;
                AnchorsConfig.changed();
            });
            this.enemyPickerOffset = offset + 4;
            offset += pickerHeight + 8;
            this.enemyPickerHeight = pickerHeight;
        }
        this.contentHeight = offset + 2;
    }

    private int enemyPickerOffset;
    private int enemyPickerHeight;

    private int buildGeneral(int offset) {
        AnchorsConfig.Settings settings = settings();
        offset = section(offset, "kohs_anchors.section.clicks");
        offset = slider(offset, "anchor_debounce", () -> settings.anchorDebounceMillis,
                value -> settings.anchorDebounceMillis = value, DEBOUNCE_STOPS, AnchorsScreen::formatDebounce,
                AnchorDebounce::settingsChanged, "AnchorDebounce.refuses ← MultiPlayerGameModeMixin @ useItemOn HEAD",
                "FAIL before any prediction or packet", "config anchorDebounceMillis");
        offset = slider(offset, "glowstone_debounce", () -> settings.glowstoneDebounceMillis,
                value -> settings.glowstoneDebounceMillis = value, DEBOUNCE_STOPS, AnchorsScreen::formatDebounce,
                AnchorDebounce::settingsChanged, "AnchorDebounce.refuses (charges and glowstone blocks)",
                "never a detonation · slot change ends the window", "config glowstoneDebounceMillis");
        // Switching the guard off is immediate; switching it on shows what it takes away first.
        offset = addRow(offset, new AnchorSwitchRow(x(), y(offset), this.layout.rowWidth(), label("glowstone_guard"),
                description("glowstone_guard"), this.font, () -> settings.glowstoneGuard, () -> {
                    if (settings.glowstoneGuard) {
                        settings.glowstoneGuard = false;
                        AnchorsConfig.changed();
                        AnchorsConfig.save();
                        return;
                    }
                    this.guardWarning = new GlowstoneGuardWarning(settings.interfaceMotion, () -> {
                        settings.glowstoneGuard = true;
                        AnchorsConfig.changed();
                        AnchorsConfig.save();
                    });
                }, false, null, "glowstone_guard",
                "AnchorDebounce.refuses: glowstone that would go down as a block, within 4 s of an anchor action",
                "FAIL before any prediction or packet · counted: " + AnchorStats.glowstoneGuarded(),
                "GlowstoneGuardWarning: LoopingClip.SAFE_ANCHOR, then the charge"));
        offset = section(offset, "kohs_anchors.section.detonation");
        offset = toggle(offset, "anchor_fade", () -> settings.anchorFade, value -> settings.anchorFade = value,
                "AnchorFade.start ← DetonationPredictor.detonate", "AnchorFade.submit ← LevelRendererGlowMixin",
                "submitMovingBlock: a shrinking copy, no block, no collision");
        offset = toggle(offset, "anchor_debris", () -> settings.anchorDebris, value -> settings.anchorDebris = value,
                "ClientPacketListenerMixin @ handleExplosion trackExplosionEffects",
                "DetonationPredictor.shouldDrawDebris");
        offset = addRow(offset, new AnchorRows.Cycle(x(), y(offset), this.layout.rowWidth(), label("anchor_smoke"),
                description("anchor_smoke"), this.font, () -> Component.translatable(switch (settings.anchorSmoke) {
                    case 1 -> "kohs_anchors.smoke.light";
                    case 2 -> "kohs_anchors.smoke.none";
                    default -> "kohs_anchors.smoke.vanilla";
                }).getString(), () -> {
                    settings.anchorSmoke = (settings.anchorSmoke + 1) % 3;
                    rebuildWidgets();
                }, "anchor_smoke", "AnchorSmoke.flash ← ClientPacketListenerMixin @ handleExplosion addParticle",
                "and DetonationPredictor.detonate · config anchorSmoke"));
        offset = section(offset, "kohs_anchors.section.core");
        offset = note(offset, "core", () -> Component.translatable("kohs_anchors.core.badge").getString(),
                AnchorsTheme.ACCENT_BRIGHT, "AnchorInput: replay, refreshTargetBeforeUse, hold + drawHeld, mergesRepeat",
                "DetonationPredictor.showAtInput / onAnchorUsed", "Settings fields transient: not saved");
        offset = section(offset, "kohs_anchors.section.interface");
        offset = toggle(offset, "interface_motion", () -> settings.interfaceMotion, value -> settings.interfaceMotion = value,
                "AnchorsScreen motion: sigil, motes, slashes, entrances");
        return offset;
    }

    private int buildGlow(int offset) {
        AnchorsConfig.Glow glow = settings().glow;
        offset = section(offset, "kohs_anchors.section.glow");
        offset = toggle(offset, "glow_enabled", () -> glow.enabled, value -> glow.enabled = value,
                "AnchorGlowRenderer.submit ← LevelRendererGlowMixin @ submitBlockEntities TAIL",
                "AnchorTracker: event-driven + palette scan", "GlowMaterial: additive, depth-tested, no depth write");
        offset = addRow(offset, new AnchorRows.Cycle(x(), y(offset), this.layout.rowWidth(), label("glow_quality"),
                description("glow_quality"), this.font, () -> Component.translatable(switch (glow.quality) {
                    case AnchorsConfig.Glow.QUALITY_PERFORMANCE -> "kohs_anchors.glow_quality.performance";
                    case AnchorsConfig.Glow.QUALITY_HIGH -> "kohs_anchors.glow_quality.quality";
                    default -> "kohs_anchors.glow_quality.balanced";
                }).getString(), () -> {
                    glow.quality = (glow.quality + 1) % 3;
                    rebuildWidgets();
                }, "glow_quality", "AnchorGlowRenderer: view cone, back faces, LOD by distance, vertex budget",
                "frame: " + AnchorGlowRenderer.frameStats()));
        offset = addRow(offset, new AnchorRows.Cycle(x(), y(offset), this.layout.rowWidth(), label("glow_source"),
                description("glow_source"), this.font, () -> Component.translatable(glow.source == AnchorsConfig.Glow.SOURCE_CUSTOM
                        ? "kohs_anchors.glow.source.custom" : "kohs_anchors.glow.source.texture").getString(),
                () -> {
                    glow.source = glow.source == AnchorsConfig.Glow.SOURCE_CUSTOM ? AnchorsConfig.Glow.SOURCE_TEXTURE
                            : AnchorsConfig.Glow.SOURCE_CUSTOM;
                    AnchorsConfig.changed();
                    rebuildWidgets();
                }, "glow_source", "SkinComposer.averageGlowColor(skin, charge) or Glow.color"));
        offset = addRow(offset, new AnchorRows.Color(x(), y(offset), this.layout.rowWidth(), label("glow_color"),
                description("glow_color"), this.font, () -> glow.color, () -> openColorPopover("glow_color",
                        () -> glow.color, color -> glow.color = color), () -> glow.source == AnchorsConfig.Glow.SOURCE_CUSTOM,
                "glow_color", "Glow.color = " + ColorMath.hex(glow.color)));
        int[] percent = AnchorSliderRow.linear(0, 300, 10);
        offset = slider(offset, "glow_power", () -> glow.power, value -> glow.power = value, percent,
                value -> value + "%", AnchorsConfig::changed, "intensity = power × charge × pulse × distance fade");
        offset = slider(offset, "glow_bloom", () -> glow.bloom, value -> glow.bloom = value, percent,
                value -> value + "%", AnchorsConfig::changed, "GlowGeometry: light map 8×8 cells, Gaussian σ 1.15",
                "reaches 2 cells past each edge, faded at the base");
        offset = slider(offset, "glow_spill", () -> glow.spill, value -> glow.spill = value, percent,
                value -> value + "%", AnchorsConfig::changed, "SpillLight: spread through the air, 26 neighbours, reach 4.6",
                "faces lit from the air in front, smooth corners × (0.4 + 0.6 cos)", "rebuilt every 1 to 1.4 s or on a block change");
        offset = toggle(offset, "glow_emissive", () -> glow.emissive, value -> glow.emissive = value,
                "GlowGeometry runs: lit pixels merged per row");
        offset = toggle(offset, "glow_pulse", () -> glow.pulse, value -> glow.pulse = value,
                "0.86 + 0.14 sin(2.3 t + phase per anchor)");
        offset = toggle(offset, "glow_charge_scaling", () -> glow.chargeScaling, value -> glow.chargeScaling = value,
                "0.4 + 0.6 × charge / 4");
        offset = section(offset, "kohs_anchors.section.crystal");
        offset = addRow(offset, new AnchorRows.Button(x(), y(offset), this.layout.rowWidth(), label("crystal_colors"),
                description("crystal_colors"), this.font, () -> Component.translatable(settings().crystalColors
                        ? "kohs_anchors.crystal.synced" : "kohs_anchors.button.open").getString(), this::openCrystalColors,
                settings().crystalColors, false, "crystal_colors", "CrystalPalette.read() ← config/crystal_tweaks.json",
                "never writes Crystal Tweaks' file"));
        return offset;
    }

    /**
     * The enemy's glow: what makes an anchor an enemy's, whether theirs glow in their own colour,
     * and what their glow shares with the player's.
     */
    private int buildEnemyGlow(int offset) {
        AnchorsConfig.EnemyGlow enemy = settings().enemyGlow;
        // The switch comes first: off, the page goes back to it alone.
        offset = addRow(offset, new AnchorSwitchRow(x(), y(offset), this.layout.rowWidth(), label("enemy_enabled"),
                description("enemy_enabled"), this.font, () -> enemy.enabled, () -> setEnemyAnchors(!enemy.enabled), false, null,
                "enemy_enabled", "EnemyGlow.enabled · off: the page is only this switch (EnemyReveal)",
                "tracked " + AnchorTracker.count() + " · enemies " + AnchorTracker.enemyCount()));
        offset = note(offset, "enemy_about", () -> Component.translatable("kohs_anchors.enemy.badge").getString()
                        + " " + AnchorTracker.enemyCount(), AnchorsTheme.CRIMSON_BRIGHT,
                "AnchorTracker.ENEMY: a new anchor where no own placement is in flight",
                "ownPlacement ← MultiPlayerGameModeMixin @ useItemOn RETURN (sequence stamped)",
                "with a bridge: BridgeClient OWNER → AnchorTracker.serverOwner");
        offset = section(offset, "kohs_anchors.section.enemy_glow");
        offset = note(offset, "enemy_follows", () -> {
            AnchorsConfig.Glow glow = settings().glow;
            return Component.translatable(!glow.enabled ? "kohs_anchors.state.off" : switch (glow.quality) {
                case AnchorsConfig.Glow.QUALITY_PERFORMANCE -> "kohs_anchors.glow_quality.performance";
                case AnchorsConfig.Glow.QUALITY_HIGH -> "kohs_anchors.glow_quality.quality";
                default -> "kohs_anchors.glow_quality.balanced";
            }).getString().toUpperCase(Locale.ROOT);
        }, settings().glow.enabled ? AnchorsTheme.ACCENT_BRIGHT : AnchorsTheme.TEXT_DIM,
                "AnchorGlowRenderer: one Glow for every anchor", "Draw.override = EnemyGlow.color: the colour only");
        return offset;
    }

    /** The enemy's colour: a full picker, and a word when their own colour is off. */
    private int buildEnemyColours(int offset) {
        AnchorsConfig.EnemyGlow enemy = settings().enemyGlow;
        offset = section(offset, "kohs_anchors.section.enemy_colour");
        if (!enemy.enabled) {
            offset = note(offset, "enemy_colour_off", () -> Component.translatable("kohs_anchors.state.off").getString(),
                    AnchorsTheme.TEXT_DIM, "EnemyGlow.enabled = false: enemy anchors take the player's colour");
        }
        return offset;
    }

    private int buildSounds(int offset) {
        offset = soundSection(offset, "charge", settings().chargeSound, AnchorsConfig.AnchorSound.CHARGE_ID,
                () -> AnchorSounds.previewCharge(1.0F), "ClientPacketListenerMixin @ handleSoundEvent playSeededSound");
        offset = soundSection(offset, "explosion", settings().explosionSound, AnchorsConfig.AnchorSound.EXPLOSION_ID,
                AnchorSounds::previewExplosion, "ClientPacketListenerMixin @ handleExplosion playLocalSound",
                "DetonationPredictor.detonate → AnchorSounds.playExplosion");
        return offset;
    }

    private int soundSection(int offset, String kind, AnchorsConfig.AnchorSound sound, String vanilla, Runnable preview,
            String... details) {
        offset = section(offset, "kohs_anchors.section.sound." + kind);
        offset = toggle(offset, "sound_" + kind + "_custom", () -> sound.custom, value -> sound.custom = value, details);
        offset = addRow(offset, new AnchorRows.Button(x(), y(offset), this.layout.rowWidth(), label("sound_" + kind + "_pick"),
                description("sound_" + kind + "_pick"), this.font,
                () -> AnchorsUi.ellipsis(this.font, AnchorSounds.label(sound.sound), 150),
                () -> this.soundPicker = new SoundPicker(Component.translatable("kohs_anchors.option.sound_" + kind + "_pick")
                        .getString(), sound.sound, sound.volume, sound.pitch, id -> {
                            sound.sound = id;
                            sound.custom = true;
                        }), false, false, "sound_" + kind + "_pick", "sound id " + sound.sound, "vanilla " + vanilla));
        int[] volume = AnchorSliderRow.linear(0, 100, 5);
        int[] pitch = AnchorSliderRow.linear(50, 200, 5);
        offset = slider(offset, "sound_volume", () -> sound.volume, value -> sound.volume = value, volume, value -> value + "%",
                null, "volume × " + sound.volume + "%");
        offset = slider(offset, "sound_pitch", () -> sound.pitch, value -> sound.pitch = value, pitch, value -> value + "%",
                null, "pitch × " + sound.pitch + "%");
        offset = addRow(offset, new AnchorRows.Button(x(), y(offset), this.layout.rowWidth(), label("sound_preview"),
                description("sound_preview"), this.font, () -> Component.translatable("kohs_anchors.button.play").getString(),
                preview, true, false, "sound_preview", "SimpleSoundInstance.forUI"));
        return offset;
    }

    /**
     * Herzium's order, which is Herzium's own (read from it, changed through it), the recommended
     * order when another is in use, and how the two mods talk. Only reached with Herzium installed.
     */
    private int buildHerzium(int offset) {
        AnchorsConfig.Settings settings = settings();
        offset = section(offset, "kohs_anchors.section.herzium_order");
        String version = HerziumBridge.version();
        boolean changeable = HerziumBridge.orderAvailable();
        offset = addRow(offset, new AnchorRows.Cycle(x(), y(offset), this.layout.rowWidth(),
                Component.translatable("kohs_anchors.option.herzium", version),
                description(HerziumBridge.agrees() ? "herzium" : "herzium_old"), this.font,
                () -> orderLabel(HerziumBridge.hotbarOrder()), () -> {
                    HerziumBridge.cycleHotbarOrder();
                    rebuildWidgets();
                }, "herzium", "HerziumBridge (reflection)", HerziumBridge.reflectionTarget(),
                "agrees from " + HerziumBridge.AGREEING_VERSION + ": " + HerziumBridge.agrees(),
                changeable ? "changed through Herzium, which saves it" : "read from " + HerziumBridge.configFile().getFileName()
                        + " only"));
        if (!HerziumBridge.RECOMMENDED.equals(HerziumBridge.hotbarOrder()) && changeable) {
            offset = addRow(offset, new AnchorRows.Button(x(), y(offset), this.layout.rowWidth(), label("herzium_recommend"),
                    description("herzium_recommend"), this.font,
                    () -> Component.translatable("kohs_anchors.herzium.use").getString(), () -> {
                        HerziumBridge.selectHotbarOrder(HerziumBridge.RECOMMENDED);
                        rebuildWidgets();
                    }, true, false, "herzium_recommend", "HerziumBridge.selectHotbarOrder(HERZIUM)",
                    "cycleHotbarOrder() until it is reached: Herzium saves it"));
        } else {
            offset = note(offset, "herzium_recommend", () -> Component.translatable("kohs_anchors.herzium.window.in_use")
                    .getString().toUpperCase(Locale.ROOT), AnchorsTheme.ACCENT_BRIGHT, "HotbarOrder.HERZIUM in use");
        }
        offset = note(offset, "herzium_source", () -> HerziumBridge.configFile().getFileName().toString(),
                AnchorsTheme.TEXT_MUTED, "HerziumConfig.get().hotbarOrder(), else " + HerziumBridge.configFile(),
                "never written by KoHs Anchor's");
        offset = section(offset, "kohs_anchors.section.herzium_link");
        offset = toggle(offset, "herzium_sync", () -> settings.herziumSync, value -> settings.herziumSync = value,
                "AnchorInput.replay → HotbarOrderController.hotbarClickConsumed(slot, remaining)",
                "AnchorInput.endTick → ImmediateHotbarInput.visualSelectedSlot / clearPreview",
                "never noteCallSiteSelection · " + HerziumBridge.talkStats());
        offset = addRow(offset, new AnchorRows.Button(x(), y(offset), this.layout.rowWidth(), label("herzium_guide"),
                description("herzium_guide"), this.font, () -> Component.translatable("kohs_anchors.button.open").getString(),
                () -> this.herziumWindow = new HerziumWindow(HerziumWindow.Kind.GUIDE, settings.interfaceMotion, this),
                false, false, "herzium_guide", "HerziumWindow(GUIDE)", "Settings.herziumIntro = " + settings.herziumIntro));
        return offset;
    }

    private int buildAdvanced(int offset) {
        AnchorsConfig.Settings settings = settings();
        offset = section(offset, "kohs_anchors.section.advanced");
        offset = note(offset, "chain_moved", () -> Component.translatable("kohs_anchors.tab.server").getString()
                .toUpperCase(Locale.ROOT), AnchorsTheme.INTENSE_BRIGHT, "the anchor chain options live on Anchors Server",
                "ServerLock: only where a bridge allows them");
        offset = section(offset, "kohs_anchors.section.dev");
        offset = addRow(offset, new AnchorSwitchRow(x(), y(offset), this.layout.rowWidth(), label("dev_mode"),
                description("dev_mode"), this.font, () -> settings.devMode, () -> {
                    if (settings.devMode) {
                        settings.devMode = false;
                        AnchorsConfig.save();
                        rebuildWidgets();
                        return;
                    }
                    this.devWarning = new DevModeWarning(settings.interfaceMotion, () -> {
                        settings.devMode = true;
                        AnchorsConfig.save();
                        rebuildWidgets();
                    });
                }, false, null, "dev_mode", "DevInspector: nodes recorded while drawing", "DevModeWarning"));
        if (settings.devMode) {
            offset = section(offset, "kohs_anchors.section.mixins");
            for (String[] mixin : MIXINS) {
                offset = addRow(offset, new AnchorRows.Note(x(), y(offset), this.layout.rowWidth(), Component.literal(mixin[0]),
                        Component.literal(mixin[1] + "\n" + mixin[2]), this.font, () -> mixin[3], AnchorsTheme.DEV_BLUE_BRIGHT,
                        mixin[0], mixin[1], mixin[2]));
            }
        }
        return offset;
    }

    /** Every mixin of the mod: name, target, what it does, and the kind of injection. */
    private static final String[][] MIXINS = {
            {"KeyMappingMixin", "KeyMapping.click HEAD / TAIL", "Journals each press; shows a certain detonation at the press.", "INJECT"},
            {"MinecraftMixin", "Minecraft.tick HEAD · handleKeybinds", "Ticks the mod; replays anchor bursts; admits each use.", "INJECT+WRAP"},
            {"MultiPlayerGameModeMixin", "MultiPlayerGameMode.useItemOn HEAD / RETURN", "Debounce refuses with FAIL; notes own anchors.", "INJECT"},
            {"RespawnAnchorBlockMixin", "RespawnAnchorBlock.useWithoutItem RETURN", "Reads Vanilla's decision that an anchor was used.", "INJECT"},
            {"ClientLevelServerStateMixin", "ClientLevel.setServerVerifiedBlockState / handleBlockChangedAck", "Server states and acks: veil, detonations, enemy anchors.", "INJECT"},
            {"ClientPacketListenerMixin", "handleExplosion · handleSoundEvent", "Predicted sound and flash, custom sounds, anchor debris.", "WRAP"},
            {"RenderSectionRegionMixin", "RenderSectionRegion.getBlockState HEAD", "Draws a veiled anchor as the veil says.", "INJECT"},
            {"SodiumLevelSliceMixin", "@Pseudo LevelSlice.getBlockState", "The same veil for Sodium's meshes.", "INJECT"},
            {"VeiledOutlineMixin", "LevelExtractor.extractBlockOutline TAIL", "No outline around a veiled anchor.", "INJECT"},
            {"LevelRendererGlowMixin", "LevelRenderer.submitBlockEntities TAIL", "Submits the anchor glow with the block entities.", "INJECT"},
            {"TextureAtlasMixin", "TextureAtlas.upload TAIL", "A new block atlas: the skin is written again.", "INJECT"},
            {"SpriteContentsMixin", "SpriteContents.createAnimationState RETURN", "Keeps the animated top's frames for the skin.", "INJECT"},
            {"AnimationStateAccessor", "SpriteContents.AnimationState.frameTexturesByIndex", "Each frame's own texture.", "ACCESSOR"},
            {"TextureAtlasSpriteAccessor", "TextureAtlasSprite.padding", "The sprite's border in the atlas.", "ACCESSOR"},
            {"ClientLevelPredictionAccessor", "ClientLevel.blockStatePredictionHandler", "The click's sequence number.", "ACCESSOR"},
            {"KeyMappingAccessor", "KeyMapping.key / clickCount", "The key and its pending presses.", "ACCESSOR"},
            {"MinecraftUseInvoker", "Minecraft.startUseItem", "Vanilla's own use, for a held click.", "INVOKER"},
            {"MinecraftPickInvoker", "Minecraft.pick", "Vanilla's crosshair raycast (26.x).", "INVOKER"}};

    // ------------------------------------------------------------------------------------------
    // Anchors Server
    // ------------------------------------------------------------------------------------------

    /**
     * The bridge: its state and a new check, the anchor chain options it allows (improved by the
     * server's side), what it adds to the anchors, and the plugin's page.
     */
    private int buildServer(int offset) {
        AnchorsConfig.Settings settings = settings();
        BridgeClient.State state = BridgeClient.state();
        offset = section(offset, "kohs_anchors.section.bridge");
        offset = note(offset, "bridge_status", this::bridgeBadge, bridgeColor(), "BridgeClient.state() = " + state,
                "channel " + BridgeProtocol.CHANNEL + " · protocol " + BridgeProtocol.VERSION,
                "policy " + Integer.toBinaryString(BridgeClient.policy()) + " · " + BridgeClient.platform() + " "
                        + BridgeClient.bridgeVersion());
        if (state != BridgeClient.State.LOCAL && state != BridgeClient.State.OFFLINE) {
            offset = addRow(offset, new AnchorRows.Button(x(), y(offset), this.layout.rowWidth(), label("bridge_check"),
                    description("bridge_check"), this.font, () -> Component.translatable("kohs_anchors.button.check").getString(),
                    () -> {
                        BridgeClient.recheck();
                        startServerCheck(false);
                    }, false, false, "bridge_check", "BridgeClient.recheck() · ServerCheckWindow"));
        }
        offset = section(offset, "kohs_anchors.section.anchor_chain");
        offset = serverToggle(offset, "fast_chain", BridgeProtocol.POLICY_FAST_CHAIN, () -> settings.fastChain,
                value -> settings.fastChain = value, OptionFx.Kind.CHAIN, "FastChain.decide ← AnchorInput.decide",
                "predictions drawn by AnchorVeil", "ServerLock.fastChain(): the bridge's POLICY_FAST_CHAIN");
        offset = serverToggle(offset, "instant_detonation", BridgeProtocol.POLICY_INSTANT_DETONATION,
                () -> settings.instantDetonation, value -> settings.instantDetonation = value, OptionFx.Kind.INSTANT,
                "AnchorInput.afterKeyClicked ← KeyMappingMixin @ click TAIL", "startUseItem between client ticks",
                "ServerLock.instantDetonation(): POLICY_INSTANT_DETONATION");
        offset = section(offset, "kohs_anchors.section.server_optimization");
        offset = serverToggle(offset, "better_enemy_glow", BridgeProtocol.POLICY_OWNERSHIP, () -> settings.betterEnemyGlow,
                value -> settings.betterEnemyGlow = value, OptionFx.Kind.ENEMY, "BridgeClient OWNER → AnchorTracker.serverOwner",
                "owners told this connection: " + BridgeClient.ownersReceived());
        offset = serverToggle(offset, "bridge_latency", BridgeProtocol.POLICY_LATENCY, () -> settings.bridgeLatency,
                value -> settings.bridgeLatency = value, OptionFx.Kind.LATENCY, "BridgeClient PING / PONG once a second",
                "Latency.millis() → AnchorVeil and the early clicks' waits");
        offset = section(offset, "kohs_anchors.section.bridge_plugin");
        offset = addRow(offset, new AnchorRows.Button(x(), y(offset), this.layout.rowWidth(), label("bridge_plugin"),
                description("bridge_plugin"), this.font, () -> Component.translatable("kohs_anchors.button.open").getString(),
                () -> openLink(ServerCheckWindow.PLUGIN_URL), false, false, "bridge_plugin", ServerCheckWindow.PLUGIN_URL));
        return offset;
    }

    /**
     * An option of the bridge's. Without a bridge it is locked: a click shows why, with the padlock.
     * Switched on, it plays what it does over the anchor column.
     */
    private int serverToggle(int offset, String option, int policyBit, BooleanSupplier state, Consumer<Boolean> set,
            OptionFx.Kind fx, String... details) {
        boolean available = BridgeClient.available();
        Component tag = Component.translatable(!available ? "kohs_anchors.tag.locked"
                : BridgeClient.allows(policyBit) ? "kohs_anchors.tag.bridge" : "kohs_anchors.tag.off_here");
        return addRow(offset, new AnchorSwitchRow(x(), y(offset), this.layout.rowWidth(), label(option), description(option),
                this.font, state, () -> {
                    if (!BridgeClient.available()) {
                        startServerCheck(true);
                        return;
                    }
                    boolean next = !state.getAsBoolean();
                    set.accept(next);
                    AnchorsConfig.changed();
                    AnchorsConfig.save();
                    BridgeClient.settingsChanged();
                    if (next) {
                        this.optionFx = new OptionFx(fx);
                    }
                }, !BridgeClient.allows(policyBit), tag, option, details));
    }

    private String bridgeBadge() {
        return Component.translatable("kohs_anchors.bridge.badge." + BridgeClient.state().name().toLowerCase(Locale.ROOT))
                .getString().toUpperCase(Locale.ROOT);
    }

    private static int bridgeColor() {
        return switch (BridgeClient.state()) {
            case CONNECTED, LOCAL -> 0xFF000000 | AnchorFx.GREEN_BRIGHT;
            case MISSING, NO_API -> AnchorsTheme.CRIMSON_BRIGHT;
            default -> AnchorsTheme.ACCENT_BRIGHT;
        };
    }

    /** Whether the Anchors Server tab is locked: a server without a bridge, or no way to talk to one. */
    private static boolean serverLocked() {
        BridgeClient.State state = BridgeClient.state();
        return state == BridgeClient.State.MISSING || state == BridgeClient.State.NO_API;
    }

    /** Runs the check window, or shows its answer at once ({@code direct}) for a locked option. */
    private void startServerCheck(boolean direct) {
        checkedFor = this.minecraft.getConnection();
        this.serverCheck = new ServerCheckWindow(settings().interfaceMotion, this.bridgeFigure,
                () -> previewShown() ? this.previewArt : AnchorsLayout.Rect.EMPTY, this::openLink, direct);
    }

    /** Whether this connection has not been checked yet on the Anchors Server tab. */
    private boolean needsServerCheck() {
        Object current = this.minecraft.getConnection();
        return current != checkedFor && this.entry == null;
    }

    /** Opens a page in the browser, after the game's own "open this link?". */
    void openLink(String url) {
        ConfirmLinkScreen.confirmLinkNow(this, URI.create(url));
    }

    /** Whether the enemy's page is open with enemy anchors off: only their switch and anchor show. */
    private static boolean enemyOff() {
        return enemyPage && !AnchorsConfig.settings().enemyGlow.enabled;
    }

    /** The Advanced tab's deeper purple. */
    private boolean intense() {
        return this.tab == ADVANCED && !enemyPage;
    }

    /**
     * Enemy anchors on or off from their page: on, the tabs and the options come in behind the anchor
     * flying into its column; off, the anchor goes back under the switch.
     */
    private void setEnemyAnchors(boolean on) {
        AnchorsConfig.Settings settings = settings();
        if (settings.enemyGlow.enabled == on) {
            return;
        }
        boolean motion = settings.interfaceMotion;
        if (on) {
            AnchorsLayout.Rect from = enemyOffStage();
            settings.enemyGlow.enabled = true;
            AnchorsConfig.changed();
            AnchorsConfig.save();
            this.tab = ENEMY_GLOW;
            lastTab = ENEMY_GLOW;
            enemyTab = ENEMY_GLOW;
            long now = System.nanoTime();
            // The tabs and the rows wait for the anchor to take off.
            this.tabsDealtAt = motion ? now + 280_000_000L : now;
            this.tabChangedAt = motion ? now + 380_000_000L : now;
            this.scroll = 0.0F;
            this.scrollTarget = 0.0F;
            rebuildWidgets();
            this.enemyReveal = new EnemyReveal(true, motion, this.enemyFigure, () -> from, () -> this.previewArt);
        } else {
            AnchorsLayout.Rect from = previewShown() ? this.previewArt : enemyOffStage();
            settings.enemyGlow.enabled = false;
            AnchorsConfig.changed();
            AnchorsConfig.save();
            this.tab = ENEMY_GLOW;
            lastTab = ENEMY_GLOW;
            enemyTab = ENEMY_GLOW;
            rebuildWidgets();
            this.enemyReveal = new EnemyReveal(false, motion, this.enemyFigure, () -> from, this::enemyOffStage);
        }
    }

    /** The enemy page's switch while their anchors are off: in the middle of the body, near its top. */
    private AnchorsLayout.Rect enemyOffSwitch() {
        AnchorsLayout.Rect body = this.layout.body();
        int width = Math.min(240, Math.max(110, body.width() - 40));
        return new AnchorsLayout.Rect(body.centerX() - width / 2, body.y() + Math.max(6, body.height() / 10), width, 20);
    }

    /** Where their anchor stands under that switch. */
    private AnchorsLayout.Rect enemyOffStage() {
        AnchorsLayout.Rect body = this.layout.body();
        AnchorsLayout.Rect pill = enemyOffSwitch();
        int top = pill.bottom() + 32;
        int bottom = body.bottom() - 6;
        int size = Math.max(24, Math.min(bottom - top, 150));
        return new AnchorsLayout.Rect(body.centerX() - size / 2, top + Math.max(0, (bottom - top - size) / 2), size, size);
    }

    private int x() {
        return this.layout.options.x();
    }

    private int y(int offset) {
        return this.layout.options.y() + offset;
    }

    /** An option's name: in developer mode, what it is in the code. */
    private static Component label(String option) {
        return Component.translatable((dev() ? "kohs_anchors.dev." : "kohs_anchors.option.") + option);
    }

    private static Component description(String option) {
        return Component.translatable((dev() ? "kohs_anchors.dev." : "kohs_anchors.option.") + option + ".description");
    }

    /** Herzium's order, shortly: the row's value has little room beside its name. */
    private static String orderLabel(String order) {
        String key = switch (order) {
            case "HERZIUM" -> "kohs_anchors.herzium.order.herzium.short";
            case "VANILLA" -> "kohs_anchors.herzium.order.vanilla.short";
            case "VANILLA_REVERSED" -> "kohs_anchors.herzium.order.reversed.short";
            default -> "";
        };
        return key.isEmpty() ? order : Component.translatable(key).getString();
    }

    static String formatDebounce(int millis) {
        if (millis <= 0) {
            return Component.translatable("kohs_anchors.debounce.vanilla").getString();
        }
        if (millis < 1000) {
            return millis + " ms";
        }
        float seconds = millis / 1000.0F;
        return (seconds == Math.round(seconds) ? Integer.toString(Math.round(seconds))
                : String.format(Locale.ROOT, "%.2f", seconds).replaceAll("0+$", "")) + " s";
    }

    private int section(int offset, String key) {
        int start = offset == 0 ? 0 : offset + SECTION_GAP;
        this.sections.add(new Section(Component.translatable(key).getString().toUpperCase(Locale.ROOT), start, key));
        return start + SECTION_HEIGHT;
    }

    private int toggle(int offset, String option, BooleanSupplier state, Consumer<Boolean> set, String... details) {
        return addRow(offset, new AnchorSwitchRow(x(), y(offset), this.layout.rowWidth(), label(option), description(option),
                this.font, state, () -> {
                    set.accept(!state.getAsBoolean());
                    AnchorsConfig.changed();
                }, false, null, option, details));
    }

    private int slider(int offset, String option, IntSupplier value, IntConsumer set, int[] stops,
            java.util.function.IntFunction<String> format, Runnable onRelease, String... details) {
        return addRow(offset, new AnchorSliderRow(x(), y(offset), this.layout.rowWidth(), label(option), description(option),
                this.font, value, v -> {
                    set.accept(v);
                    AnchorsConfig.changed();
                }, stops, format, () -> {
                    if (onRelease != null) {
                        onRelease.run();
                    }
                    AnchorsConfig.save();
                }, option, details));
    }

    private int note(int offset, String option, java.util.function.Supplier<String> badge, int badgeColor, String... details) {
        return addRow(offset, new AnchorRows.Note(x(), y(offset), this.layout.rowWidth(), label(option), description(option),
                this.font, badge, badgeColor, option, details));
    }

    private int addRow(int offset, AnchorRow row) {
        addWidget(row);
        this.rows.add(row);
        this.rowOffsets.add(offset);
        return offset + row.getHeight() + ROW_GAP;
    }

    private void applyScroll() {
        AnchorsLayout.Rect viewport = this.layout.options;
        int offset = Math.round(this.scroll);
        for (int index = 0; index < this.rows.size(); index++) {
            AnchorRow row = this.rows.get(index);
            row.setY(viewport.y() + this.rowOffsets.get(index) - offset);
            row.setClip(viewport);
        }
    }

    private static boolean isEnemyTab(int tab) {
        return tab >= ENEMY_GLOW;
    }

    /** The tabs the bar shows: the player's, the enemy anchors' three, or none while theirs are off. */
    private static int[] visibleTabs() {
        return enemyPage ? (AnchorsConfig.settings().enemyGlow.enabled ? ENEMY_TABS : NO_TABS) : OWN_TABS;
    }

    /** Whether the tab in view has the anchor column beside its options. */
    private boolean previewShown() {
        return this.layout.showsPreview() && this.tab != ANCHOR && this.tab != KOHS && this.tab != ENEMY_ADVANCED
                && !enemyOff();
    }

    private void selectTab(int index) {
        if (index < 0 || index >= TAB_KEYS.length || isEnemyTab(index) != enemyPage) {
            return;
        }
        if (index == HERZIUM && !HerziumBridge.installed()) {
            // Without Herzium its tab has nothing to change: it says where to get it instead.
            this.herziumWindow = new HerziumWindow(HerziumWindow.Kind.MISSING, settings().interfaceMotion, this);
            return;
        }
        if (index == HERZIUM && index != this.tab && settings().herziumIntro) {
            this.herziumWindow = new HerziumWindow(HerziumWindow.Kind.GUIDE, settings().interfaceMotion, this);
        }
        if (index == this.tab) {
            rebuildWidgets();
            return;
        }
        leaveTab();
        this.tab = index;
        lastTab = index;
        this.tabChangedAt = System.nanoTime();
        this.scroll = 0.0F;
        this.scrollTarget = 0.0F;
        this.resetArmedAt = -1L;
        rebuildWidgets();
    }

    /** Lets go of what the tab being left draws: the workshop, the KoHs page, the battle. */
    private void leaveTab() {
        if (previewShown()) {
            this.previousPreviewX = this.previewArt.centerX();
            this.previousPreviewY = this.previewArt.y() + this.previewArt.height() / 2.0F;
        } else {
            this.previousPreviewX = -1.0F;
            this.previousPreviewY = -1.0F;
        }
        if (this.workshop != null) {
            this.releases.add(this.workshop::close);
            this.workshop = null;
        }
        // The KoHs page and the battle start over each time their tab opens.
        this.kohsPage = null;
        if (this.battle != null) {
            this.releases.add(this.battle::close);
            this.battle = null;
        }
    }

    /** Closes what earlier frames let go of: nothing drawn from now on uses it. */
    private void release() {
        for (Runnable release : this.releases) {
            release.run();
        }
        this.releases.clear();
    }

    /**
     * The header switch: the whole screen turns to the enemy's anchors (their glow colour), in
     * crimson, or back to the player's own ({@link EnemySwitch}). The first time, a word on what
     * enemy anchors are comes first ({@link EnemyIntro}).
     */
    private void toggleEnemyAnchors() {
        if (this.enemySwitch != null || this.enemyIntro != null) {
            return;
        }
        boolean toEnemy = !enemyPage;
        if (toEnemy && settings().enemyIntro) {
            this.enemyIntro = new EnemyIntro(settings().interfaceMotion, this.enemyFigure, () -> startEnemySwitch(true));
            return;
        }
        startEnemySwitch(toEnemy);
    }

    private void startEnemySwitch(boolean toEnemy) {
        AnchorsLayout.Rect from = previewShown() ? this.previewArt : AnchorsLayout.Rect.EMPTY;
        this.enemySwitch = new EnemySwitch(toEnemy, settings().interfaceMotion, this.enemyFigure, from, () -> {
            switchPage(toEnemy);
            if (this.enemySwitch != null) {
                this.enemySwitch.landAt(previewShown() ? this.previewArt : AnchorsLayout.Rect.EMPTY);
            }
        });
    }

    /**
     * Turns the screen to the enemy anchors' three tabs, on the one last used there, or back to the
     * player's tabs, on the one the player left.
     */
    private void switchPage(boolean toEnemy) {
        if (toEnemy == enemyPage) {
            return;
        }
        leaveTab();
        this.previousPreviewX = -1.0F;
        this.previousPreviewY = -1.0F;
        if (toEnemy) {
            ownTab = this.tab;
        } else {
            enemyTab = this.tab;
        }
        enemyPage = toEnemy;
        int next = toEnemy ? enemyTab : ownTab;
        if (isEnemyTab(next) != toEnemy || next == HERZIUM && !HerziumBridge.installed()) {
            next = toEnemy ? ENEMY_GLOW : GLOW;
        }
        this.tab = next;
        lastTab = next;
        long now = System.nanoTime();
        this.tabChangedAt = now;
        this.tabsDealtAt = now;
        this.scroll = 0.0F;
        this.scrollTarget = 0.0F;
        this.resetArmedAt = -1L;
        rebuildWidgets();
    }

    /** The screen's danger colours: the enemy's anchors, and Anchors Server on a server without a bridge. */
    private boolean crimson() {
        return enemyPage || this.tab == SERVER && serverLocked();
    }

    /** How red the background is: 1 on the enemy's page, in between while switching. */
    private float enemyBlend() {
        return this.enemySwitch != null ? this.enemySwitch.blend() : enemyPage ? 1.0F : 0.0F;
    }

    /** From the workshop: Crystal Tweaks' colours. */
    void openCrystalColors() {
        this.crystalModal = new CrystalModal(this, settings().interfaceMotion);
    }

    private ColorPicker popover;
    private AnchorsLayout.Rect popoverBox = AnchorsLayout.Rect.EMPTY;
    private String popoverTitle = "";

    private void openColorPopover(String option, IntSupplier get, IntConsumer set) {
        this.popoverTitle = Component.translatable("kohs_anchors.option." + option).getString();
        this.popover = new ColorPicker(get, color -> {
            set.accept(color | 0xFF000000);
            AnchorsConfig.changed();
        });
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
        // On the enemy's page the veil turns red; during the switch it turns by degrees.
        float red = enemyBlend();
        graphics.fillGradient(0, 0, this.width, this.height, AnchorsTheme.lerp(AnchorsTheme.VEIL_TOP, 0x86300818, red),
                AnchorsTheme.lerp(AnchorsTheme.VEIL_BOTTOM, 0xB0180308, red));
        Mc.extractDeferredSubtitles(this.minecraft);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        release();
        if (this.entry != null) {
            this.entry.render(graphics, this.font, this.width, this.height, mouseX, mouseY);
            if (!this.entry.done()) {
                return;
            }
            this.entry = null;
            // The menu's own entrance plays now, and the enemy switch calls attention to itself once.
            this.openedAt = System.nanoTime();
            if (this.highlightSwitch) {
                this.switchHighlightAt = this.openedAt;
            }
        }
        if (this.tab == SERVER && this.shownBridgeState != BridgeClient.state()) {
            // The bridge answered while the tab was open: its rows follow.
            rebuildWidgets();
        }
        long now = System.nanoTime();
        float frameMillis = Mth.clamp((now - this.lastFrame) / 1_000_000.0F, 0.0F, 50.0F);
        this.lastFrame = now;
        boolean motion = settings().interfaceMotion;
        double seconds = now / 1_000_000_000.0D;
        float intro = motion ? AnchorsTheme.easeOutCubic(progress(now - this.openedAt, INTRO_NANOS)) : 1.0F;
        boolean modal = modalOpen();
        // Under a modal nothing is hovered: the modal owns the pointer.
        int pointerX = modal ? -1 : mouseX;
        int pointerY = modal ? -1 : mouseY;
        DevInspector.begin(dev());

        followFocus();
        updateScroll(frameMillis, motion);
        updateAnchorCycle(seconds, motion);

        boolean advanced = crimson();
        if (motion) {
            AnchorsLayout.Rect panel = this.layout.panel;
            AnchorsUi.sigil(graphics, panel.centerX(), panel.centerY(),
                    Math.round(Math.min(panel.width(), panel.height()) * 0.62F), seconds,
                    advanced ? AnchorsTheme.CRIMSON : intense() ? AnchorsTheme.INTENSE_DEEP : AnchorsTheme.ACCENT_DEEP,
                    0.55F * intro, this.anchorCharge);
            AnchorsUi.motes(graphics, this.width, this.height, seconds, intro);
        }
        drawPanel(graphics, intro, seconds, motion, advanced);
        this.enemySwitchHover += ((this.enemySwitchWidth > 0 && pointerX >= this.enemySwitchX
                && pointerX < this.enemySwitchX + this.enemySwitchWidth && pointerY >= this.enemySwitchY
                && pointerY < this.enemySwitchY + 13 ? 1.0F : 0.0F) - this.enemySwitchHover)
                * (1.0F - (float) Math.exp(-frameMillis / 70.0F));
        drawHeader(graphics, intro, seconds);
        drawTabs(graphics, pointerX, pointerY, intro, seconds, frameMillis, motion);
        if (this.tab == ANCHOR && this.workshop != null) {
            this.workshop.render(graphics, this.font, pointerX, pointerY, motion, seconds, intro);
        } else if (this.tab == KOHS && this.kohsPage != null) {
            this.kohsPage.render(graphics, this.font, this.layout.body(), pointerX, pointerY, intro);
        } else if (this.tab == ENEMY_ADVANCED && this.battle != null) {
            this.battle.render(graphics, this.font, this.layout.body(), pointerX, pointerY, intro);
            if (motion && this.tabChangedAt >= 0L) {
                AnchorsUi.slash(graphics, this.layout.body(), progress(now - this.tabChangedAt, TAB_SLASH_NANOS),
                        AnchorsTheme.CRIMSON_BRIGHT);
            }
        } else if (enemyOff()) {
            drawEnemyOff(graphics, pointerX, pointerY, intro, motion, seconds);
        } else {
            drawOptions(graphics, pointerX, pointerY, partialTick, now, motion, seconds);
            if (this.layout.showsPreview()) {
                if (this.tab == SERVER) {
                    drawServerPreview(graphics, intro, motion, seconds);
                } else {
                    drawPreview(graphics, pointerX, pointerY, intro, motion, seconds);
                }
            } else if (this.tab == SERVER && this.optionFx != null) {
                // No anchor column: the option's animation plays over the options.
                this.optionFx.render(graphics, this.font, this.layout.options, seconds);
                if (this.optionFx.done()) {
                    this.optionFx = null;
                }
            }
        }
        drawFooterNote(graphics, intro);
        super.extractRenderState(graphics, pointerX, pointerY, partialTick);
        if (this.enemySwitch != null) {
            this.enemySwitch.render(graphics, this.width, this.height);
            if (this.enemySwitch.done()) {
                this.enemySwitch = null;
            }
        }
        if (this.enemyReveal != null) {
            this.enemyReveal.render(graphics, this.width, this.height);
            if (this.enemyReveal.done()) {
                this.enemyReveal = null;
            }
        }
        if (this.tab == SERVER && this.serverCheck == null && needsServerCheck()) {
            startServerCheck(false);
        }
        AnchorsLayout.Rect reset = this.layout.resetButton;
        DevInspector.node("AnchorsButton", "reset tab", reset.x(), reset.y(), reset.width(), reset.height(),
                "per-tab defaults, second click confirms");
        AnchorsLayout.Rect done = this.layout.doneButton;
        DevInspector.node("AnchorsButton", "done", done.x(), done.y(), done.width(), done.height(),
                "AnchorsConfig.save() · SkinPaint.saveAll()");

        if (this.popover != null) {
            drawPopover(graphics, mouseX, mouseY, motion);
        }
        if (this.soundPicker != null) {
            this.soundPicker.render(graphics, this.font, this.width, this.height, mouseX, mouseY, motion);
            if (this.soundPicker.closed()) {
                this.soundPicker = null;
                AnchorsConfig.changed();
                AnchorsConfig.save();
                rebuildWidgets();
            }
        }
        if (this.serverCheck != null) {
            this.serverCheck.render(graphics, this.font, this.width, this.height, mouseX, mouseY);
            if (this.serverCheck.done()) {
                this.serverCheck = null;
                rebuildWidgets();
            }
        }
        if (this.devWarning != null) {
            this.devWarning.render(graphics, this.font, this.width, this.height, mouseX, mouseY);
            if (this.devWarning.done()) {
                this.devWarning = null;
            }
        }
        if (this.crystalModal != null) {
            this.crystalModal.render(graphics, this.font, this.width, this.height, mouseX, mouseY);
            if (this.crystalModal.done()) {
                this.crystalModal = null;
                rebuildWidgets();
            }
        }
        if (this.guardWarning != null) {
            this.guardWarning.render(graphics, this.font, this.width, this.height, mouseX, mouseY);
            if (this.guardWarning.done()) {
                this.guardWarning = null;
            }
        }
        if (this.enemyIntro != null) {
            this.enemyIntro.render(graphics, this.font, this.width, this.height, mouseX, mouseY);
            if (this.enemyIntro.done()) {
                if (this.enemyIntro.hideFromNowOn()) {
                    settings().enemyIntro = false;
                    AnchorsConfig.save();
                }
                this.enemyIntro = null;
            }
        }
        if (this.herziumWindow != null) {
            this.herziumWindow.render(graphics, this.font, this.width, this.height, mouseX, mouseY);
            if (this.herziumWindow.done()) {
                if (this.herziumWindow.hideFromNowOn()) {
                    settings().herziumIntro = false;
                    AnchorsConfig.save();
                }
                boolean guide = this.herziumWindow.kind() == HerziumWindow.Kind.GUIDE;
                this.herziumWindow = null;
                if (guide && this.tab == HERZIUM) {
                    // The order may have changed in the window: the rows follow it.
                    rebuildWidgets();
                }
            }
        }
        if (this.devWarning == null) {
            DevInspector.render(graphics, this.font, this.width, this.height, mouseX, mouseY,
                    "AnchorsScreen › " + (enemyPage ? "enemy › " : "") + TAB_KEYS[this.tab],
                    this.layout.tight ? "TIGHT" : this.layout.compact ? "COMPACT" : "STANDARD");
        }
    }

    private void drawPopover(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean motion) {
        graphics.fill(0, 0, this.width, this.height, 0xA006020A);
        int width = Math.min(this.width - 16, 220);
        int height = Math.min(this.height - 16, 170);
        this.popoverBox = new AnchorsLayout.Rect((this.width - width) / 2, (this.height - height) / 2, width, height);
        AnchorsLayout.Rect box = this.popoverBox;
        AnchorsUi.halo(graphics, box.x(), box.y(), width, height, AnchorsTheme.ACCENT, 5, 1.0F);
        AnchorsUi.panel(graphics, box.x(), box.y(), width, height, AnchorsTheme.PANEL_TOP, AnchorsTheme.PANEL_BOTTOM);
        AnchorsUi.roundedOutline(graphics, box.x(), box.y(), width, height, AnchorsTheme.PANEL_BORDER);
        AnchorsUi.bladeCorners(graphics, box.x(), box.y(), width, height, 6, 0xE0E9D5FF);
        AnchorsUi.label(graphics, this.font, AnchorsUi.fit(this.font, this.popoverTitle.toUpperCase(Locale.ROOT), width - 16),
                box.x() + 8, box.y() + 7, AnchorsTheme.TITLE, true);
        this.popover.setArea(new AnchorsLayout.Rect(box.x() + 8, box.y() + 22, width - 16, height - 48));
        this.popover.render(graphics, this.font, mouseX, mouseY, 1.0F);
        AnchorsLayout.Rect done = popoverDone();
        AnchorsButton.draw(graphics, done.x(), done.y(), done.width(), done.height(),
                Component.translatable("kohs_anchors.button.done").getString(), true, false,
                done.contains(mouseX, mouseY) ? 1.0F : 0.0F, 0.0F, 1.0F, -1.0F);
        DevInspector.node("ColorPopover", this.popoverTitle, box.x(), box.y(), width, height, "ColorPicker in a modal");
    }

    private AnchorsLayout.Rect popoverDone() {
        AnchorsLayout.Rect box = this.popoverBox;
        return new AnchorsLayout.Rect(box.right() - 8 - 70, box.bottom() - 8 - 16, 70, 16);
    }

    private void drawPanel(GuiGraphicsExtractor graphics, float intro, double seconds, boolean motion, boolean advanced) {
        AnchorsLayout.Rect panel = this.layout.panel;
        float grow = 0.94F + intro * 0.06F;
        int width = Math.max(1, Math.round(panel.width() * grow));
        int height = Math.max(1, Math.round(panel.height() * grow));
        int x = panel.x() + (panel.width() - width) / 2;
        int y = panel.y() + (panel.height() - height) / 2;
        int accent = dev() ? AnchorsTheme.DEV_BLUE : advanced ? AnchorsTheme.CRIMSON : intense() ? AnchorsTheme.INTENSE
                : AnchorsTheme.ACCENT;
        AnchorsUi.halo(graphics, x, y, width, height, accent, 6, 0.6F * intro);
        AnchorsUi.panel(graphics, x, y, width, height,
                AnchorsTheme.fade(AnchorsTheme.PANEL_TOP, 0.3F + intro * 0.7F),
                AnchorsTheme.fade(AnchorsTheme.PANEL_BOTTOM, 0.3F + intro * 0.7F));
        AnchorsUi.roundedOutline(graphics, x, y, width, height, AnchorsTheme.fade(
                advanced ? 0xE0FF315C : intense() ? 0xE0C55BFF : AnchorsTheme.PANEL_BORDER, intro));
        DevInspector.node("Panel", "AnchorsScreen", panel.x(), panel.y(), panel.width(), panel.height(),
                "AnchorsLayout.fit(" + this.width + ", " + this.height + ")", "glass " + Integer.toHexString(AnchorsTheme.PANEL_TOP));
        if (intro < 0.98F) {
            return;
        }
        AnchorsUi.bladeCorners(graphics, panel.x(), panel.y(), panel.width(), panel.height(), 9,
                advanced ? 0xE0FF6A86 : intense() ? 0xE0F0C8FF : 0xE0E9D5FF);
        if (motion) {
            AnchorsUi.comets(graphics, panel.x(), panel.y(), panel.width(), panel.height(), seconds,
                    advanced ? 0xFF9AB0 : intense() ? 0xF0C8FF : 0xE8CCFF);
        }
        int headerLine = this.layout.header.bottom() - 1;
        AnchorsUi.energyLine(graphics, panel.x() + 8, panel.right() - 8, headerLine,
                advanced ? AnchorsTheme.CRIMSON_BRIGHT : intense() ? AnchorsTheme.INTENSE : AnchorsTheme.ACCENT,
                motion ? seconds : 0.0D, 1.0F);
        int footerLine = this.layout.footer.y();
        graphics.fill(panel.x() + 8, footerLine, panel.right() - 8, footerLine + 1, AnchorsTheme.HEADER_LINE);
    }

    private void drawHeader(GuiGraphicsExtractor graphics, float intro, double seconds) {
        AnchorsLayout.Rect header = this.layout.header;
        int iconX = header.x() + this.layout.padding + 2;
        int iconY = header.y() + (header.height() - 12) / 2;
        boolean ringed = header.height() >= 30;
        this.headerPortal.render(graphics, iconX + 6, iconY + 6, ringed ? 9 : 6, ringed, this.anchorCharge, enemyBlend(),
                settings().interfaceMotion, intro);
        DevInspector.node("HeaderPortal", "portal", iconX - 5, iconY - 5, 23, 23, "a Nether portal turning, 20 frames a second",
                "ring: the header's anchor cycle, charge " + Math.round(this.anchorCharge));

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
        AnchorsUi.label(graphics, this.font, title, 1, 1, AnchorsTheme.fade(enemyPage ? 0xFF8A1030 : 0xFF5B1FB0, intro),
                false);
        AnchorsUi.label(graphics, this.font, title, 0, 0, AnchorsTheme.fade(AnchorsTheme.TITLE, intro), false);
        if (intro >= 0.98F) {
            AnchorsUi.glint(graphics, this.font, title, 0, 0, seconds);
        }
        graphics.pose().popMatrix();
        DevInspector.node("Title", title, titleX, titleY, Math.round(this.font.width(title) * scale), titleHeight,
                "Component kohs_anchors.screen.title", "scale " + scale + " · glint every 4.5 s");
        if (showSubtitle) {
            AnchorsUi.label(graphics, this.font, subtitleShown(), titleX, titleY + titleHeight + 2,
                    AnchorsTheme.fade(enemyPage ? 0xFFFF9AB0 : AnchorsTheme.TEXT_MUTED, intro), false);
        }

        String chip = dev() ? this.versionLabel + " · DEV" : this.versionLabel;
        int titleRight = titleX + Math.round(this.font.width(title) * scale);
        int textRight = Math.max(titleRight, showSubtitle ? titleX + this.font.width(subtitleShown()) : titleRight);
        int chipWidth = chip.isEmpty() ? 0 : this.font.width(chip) + 10;
        int chipX = header.right() - this.layout.padding - chipWidth;
        boolean chipFits = !chip.isEmpty() && chipX > textRight + 10;
        drawEnemySwitchButton(graphics, header, chipFits ? chipX - 6 : header.right() - this.layout.padding, textRight,
                intro, seconds);
        if (!chip.isEmpty()) {
            int chipY = header.y() + (header.height() - 13) / 2;
            if (chipFits) {
                AnchorsUi.panel(graphics, chipX, chipY, chipWidth, 13,
                        AnchorsTheme.fade(dev() ? 0x80203070 : 0x803A1560, intro), AnchorsTheme.fade(0x80200A36, intro));
                AnchorsUi.roundedOutline(graphics, chipX, chipY, chipWidth, 13,
                        AnchorsTheme.fade(dev() ? AnchorsTheme.DEV_BLUE : AnchorsTheme.CARD_BORDER_HOVER, intro * 0.8F));
                AnchorsUi.label(graphics, this.font, chip, chipX + 5, chipY + 3,
                        AnchorsTheme.fade(dev() ? AnchorsTheme.DEV_BLUE_BRIGHT : AnchorsTheme.ACCENT_BRIGHT, intro), false);
                DevInspector.node("Chip", "version", chipX, chipY, chipWidth, 13, "FabricLoader mod metadata " + this.versionLabel);
            }
        }
    }

    private String subtitleShown() {
        return enemyPage ? Component.translatable("kohs_anchors.enemy.subtitle").getString() : this.subtitle;
    }

    /**
     * "Switch to enemy Anchor's", ending at {@code right}; its short form, or nothing, when the
     * header has no room beside the title.
     */
    private void drawEnemySwitchButton(GuiGraphicsExtractor graphics, AnchorsLayout.Rect header, int right, int textRight,
            float intro, double seconds) {
        String full = Component.translatable(enemyPage ? "kohs_anchors.enemy.switch_back" : "kohs_anchors.enemy.switch")
                .getString();
        String brief = Component.translatable(enemyPage ? "kohs_anchors.enemy.switch_back_short"
                : "kohs_anchors.enemy.switch_short").getString();
        String text = full;
        int width = this.font.width(text) + 22;
        if (right - width < textRight + 10) {
            text = brief;
            width = this.font.width(text) + 22;
        }
        if (right - width < textRight + 10) {
            this.enemySwitchWidth = 0;
            return;
        }
        int height = 13;
        int x = right - width;
        int y = header.y() + (header.height() - height) / 2;
        this.enemySwitchX = x;
        this.enemySwitchY = y;
        this.enemySwitchWidth = width;
        float pulse = enemyPage ? 0.55F + 0.45F * AnchorsTheme.pulse(seconds, 1.4D) : 0.0F;
        float hover = this.enemySwitchHover;
        int top = enemyPage ? AnchorsTheme.lerp(0xC0521028, 0xE0701A38, hover) : AnchorsTheme.lerp(0x803A1560, 0xB0521A80, hover);
        int bottom = enemyPage ? 0xC02A0612 : 0x80200A36;
        if (enemyPage) {
            AnchorsUi.halo(graphics, x, y, width, height, AnchorsTheme.CRIMSON_BRIGHT, 3, intro * (0.4F + 0.6F * pulse));
        }
        float highlight = this.switchHighlightAt < 0L || enemyPage ? 0.0F
                : 1.0F - AnchorsTheme.clamp01((System.nanoTime() - this.switchHighlightAt) / 7_000_000_000.0F);
        if (highlight > 0.0F) {
            // After the first entry: a beating halo, and an arrow bobbing up at it.
            float beat = AnchorsTheme.pulse(seconds, 0.9D);
            AnchorsUi.halo(graphics, x, y, width, height, AnchorsTheme.CRIMSON_BRIGHT, 4, intro * highlight * (0.45F + 0.55F * beat));
            int arrowX = x + width / 2;
            int arrowY = y + height + 3 + Math.round((1.0F - beat) * 3.0F);
            int arrow = AnchorsTheme.withAlpha(0xFF6A86, Math.round(255 * highlight * intro));
            for (int row = 0; row < 4; row++) {
                graphics.fill(arrowX - row, arrowY + row, arrowX + row + 1, arrowY + row + 1, arrow);
            }
        }
        AnchorsUi.panel(graphics, x, y, width, height, AnchorsTheme.fade(top, intro), AnchorsTheme.fade(bottom, intro));
        AnchorsUi.roundedOutline(graphics, x, y, width, height, AnchorsTheme.fade(enemyPage
                ? AnchorsTheme.lerp(0xFFB8243F, 0xFFFF6A86, Math.max(hover, pulse))
                : AnchorsTheme.lerp(0xFF7A2E6A, 0xFFFF6A86, hover), intro));
        // A small crossed-swords mark: two strokes crossing.
        int markX = x + 6;
        int markY = y + 3;
        int mark = AnchorsTheme.fade(enemyPage ? 0xFFFF6A86 : 0xFFE9A0C0, intro);
        for (int step = 0; step < 7; step++) {
            graphics.fill(markX + step, markY + step, markX + step + 1, markY + step + 1, mark);
            graphics.fill(markX + 6 - step, markY + step, markX + 7 - step, markY + step + 1, mark);
        }
        AnchorsUi.label(graphics, this.font, text, x + 16, y + 3,
                AnchorsTheme.fade(enemyPage ? 0xFFFFE4EA : AnchorsTheme.ACCENT_BRIGHT, intro), false);
        DevInspector.node("Button", "enemy anchors switch", x, y, width, height,
                "AnchorsScreen.toggleEnemyAnchors() · enemyPage = " + enemyPage,
                "EnemyGlow: the anchors AnchorTracker marks ENEMY");
    }

    private void drawTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float intro, double seconds,
            float frameMillis, boolean motion) {
        float response = 1.0F - (float) Math.exp(-frameMillis / 70.0F);
        boolean advancedOn = ServerLock.anyAdvancedOn();
        boolean suspended = ServerLock.suspendedHere();
        boolean herziumMissing = !HerziumBridge.installed();
        int[] tabs = visibleTabs();
        long now = System.nanoTime();
        for (int slot = 0; slot < tabs.length; slot++) {
            int index = tabs[slot];
            AnchorsLayout.Rect rect = this.layout.tab(slot, tabs.length);
            boolean selected = index == this.tab;
            boolean danger = enemyPage || index == SERVER && serverLocked();
            boolean intense = index == ADVANCED && !enemyPage;
            boolean hovered = rect.contains(mouseX, mouseY);
            this.tabHover[index] += ((hovered ? 1.0F : 0.0F) - this.tabHover[index]) * response;
            float hover = this.tabHover[index];
            // After a change of side the tabs are dealt in one by one from their middles; the
            // hitboxes are where the tabs end, from the first frame.
            float deal = motion && this.tabsDealtAt >= 0L
                    ? progress(now - this.tabsDealtAt - slot * TAB_DEAL_STAGGER_NANOS, TAB_DEAL_NANOS) : 1.0F;
            if (deal <= 0.0F) {
                continue;
            }
            float tabAlpha = intro * Math.min(1.0F, deal * 1.6F);
            AnchorsLayout.Rect full = rect;
            if (deal < 1.0F) {
                int dealt = Math.max(2, Math.round(rect.width() * AnchorsTheme.easeOutBack(deal)));
                rect = new AnchorsLayout.Rect(rect.centerX() - dealt / 2, rect.y(), dealt, rect.height());
            }
            if (index == HERZIUM && herziumMissing) {
                drawMissingTab(graphics, rect, hover, tabAlpha, seconds, motion);
                continue;
            }
            float tabIntro = tabAlpha;
            float contentAlpha = tabIntro * AnchorsTheme.clamp01((deal - 0.45F) / 0.55F);

            int accent = danger ? AnchorsTheme.CRIMSON_BRIGHT : intense ? AnchorsTheme.INTENSE : AnchorsTheme.ACCENT;
            int top = selected ? (danger ? 0xE0521028 : intense ? AnchorsTheme.INTENSE_GLASS_TOP : 0xE03A1668)
                    : AnchorsTheme.lerp(intense ? 0x8A2A0E52 : 0x801D0D32, intense ? 0xB83C1270 : 0xB02A1248, hover);
            int bottom = selected ? (danger ? 0xE02A0612 : intense ? AnchorsTheme.INTENSE_GLASS_BOTTOM : 0xE01D0D32)
                    : AnchorsTheme.lerp(0x7012091F, 0x901D0D32, hover);
            AnchorsUi.panel(graphics, rect.x(), rect.y(), rect.width(), rect.height(), AnchorsTheme.fade(top, tabIntro),
                    AnchorsTheme.fade(bottom, tabIntro));
            int border = selected ? AnchorsTheme.fade(accent, tabIntro)
                    : AnchorsTheme.fade(AnchorsTheme.lerp(danger ? 0x8A6A1030 : intense ? 0x9A6A1AB0 : AnchorsTheme.CARD_BORDER,
                            danger ? 0xE0FF6A86 : intense ? AnchorsTheme.INTENSE_BRIGHT : AnchorsTheme.CARD_BORDER_HOVER, hover),
                            tabIntro);
            AnchorsUi.roundedOutline(graphics, rect.x(), rect.y(), rect.width(), rect.height(), border);
            if (selected) {
                float breathe = motion ? 0.7F + 0.3F * AnchorsTheme.pulse(seconds, 2.4D) : 1.0F;
                graphics.fill(rect.x() + 3, rect.bottom() - 2, rect.right() - 3, rect.bottom() - 1,
                        AnchorsTheme.fade(accent, tabIntro * breathe));
                AnchorsUi.halo(graphics, rect.x(), rect.y(), rect.width(), rect.height(), accent, 2, 0.5F * tabIntro * breathe);
            }

            String label = this.tabLabels[index];
            if (this.font.width(label) + 21 > full.width()) {
                label = this.tabShortLabels[index];
            }
            boolean iconOnly = this.font.width(label) + 21 > full.width();
            int iconSize = 12;
            int contentWidth = iconSize + (iconOnly ? 0 : 4 + this.font.width(label));
            int startX = full.x() + (full.width() - contentWidth) / 2;
            int iconY = full.y() + (full.height() - iconSize) / 2;
            int iconColor = AnchorsTheme.fade(selected || hover > 0.5F
                    ? (danger ? 0xFFFF6A86 : intense ? AnchorsTheme.INTENSE_BRIGHT : AnchorsTheme.ACCENT_BRIGHT)
                    : (danger ? 0xFFB8243F : intense ? 0xFFC77DFF : AnchorsTheme.SILVER), contentAlpha);
            drawTabIcon(graphics, index, startX, iconY, iconSize, iconColor);
            if (!iconOnly) {
                int textColor = selected ? AnchorsTheme.TITLE : AnchorsTheme.lerp(AnchorsTheme.TEXT_MUTED, AnchorsTheme.TEXT, hover);
                if (danger && !selected) {
                    textColor = AnchorsTheme.lerp(0xFFE08A9E, 0xFFFFD6DE, hover);
                } else if (intense && !selected) {
                    textColor = AnchorsTheme.lerp(0xFFD9A6FF, 0xFFF6E6FF, hover);
                }
                AnchorsUi.label(graphics, this.font, label, startX + iconSize + 4, full.y() + (full.height() - 8) / 2,
                        AnchorsTheme.fade(textColor, contentAlpha), false);
            }
            if (index == SERVER && advancedOn) {
                // A live anchor chain option: a green ember where the bridge allows it, a dim red one where not.
                float ember = motion ? 0.6F + 0.4F * AnchorsTheme.pulse(seconds, 1.4D) : 1.0F;
                int emberColor = AnchorsTheme.withAlpha(suspended ? 0x8A6A1030 : AnchorFx.GREEN,
                        Math.round(255 * ember * contentAlpha));
                AnchorsUi.diamond(graphics, rect.right() - 5, rect.y() + 4, 2, emberColor);
            }
            DevInspector.node("Tab", TAB_KEYS[index], full.x(), full.y(), full.width(), full.height(),
                    "AnchorsLayout.tab(" + slot + ", " + tabs.length + ")", selected ? "selected" : "", iconOnly ? "icon only" : "");
        }
    }

    /**
     * The Herzium tab without Herzium: dimmed glass, a grey label and a small green arrow (get it
     * on Modrinth). Hovering lifts it a little, since a click still opens the window.
     */
    private void drawMissingTab(GuiGraphicsExtractor graphics, AnchorsLayout.Rect rect, float hover, float intro,
            double seconds, boolean motion) {
        float dim = intro * (0.5F + 0.3F * hover);
        AnchorsUi.panel(graphics, rect.x(), rect.y(), rect.width(), rect.height(), AnchorsTheme.fade(0x70140A20, dim),
                AnchorsTheme.fade(0x600B0514, dim));
        AnchorsUi.roundedOutline(graphics, rect.x(), rect.y(), rect.width(), rect.height(),
                AnchorsTheme.fade(AnchorsTheme.lerp(0x80463A56, 0xC08E8AA0, hover), intro));
        String label = this.tabLabels[HERZIUM];
        if (this.font.width(label) + 21 > rect.width()) {
            label = this.tabShortLabels[HERZIUM];
        }
        boolean iconOnly = this.font.width(label) + 21 > rect.width();
        int iconSize = 12;
        int contentWidth = iconSize + (iconOnly ? 0 : 4 + this.font.width(label));
        int startX = rect.x() + (rect.width() - contentWidth) / 2;
        int iconY = rect.y() + (rect.height() - iconSize) / 2;
        drawTabIcon(graphics, HERZIUM, startX, iconY, iconSize, AnchorsTheme.fade(AnchorsTheme.TEXT_DIM, intro));
        if (!iconOnly) {
            AnchorsUi.label(graphics, this.font, label, startX + iconSize + 4, rect.y() + (rect.height() - 8) / 2,
                    AnchorsTheme.fade(AnchorsTheme.lerp(0xFF6E6880, AnchorsTheme.TEXT_MUTED, hover), intro), false);
        }
        // A download arrow in Modrinth's green, breathing slowly.
        float breathe = motion ? 0.55F + 0.45F * AnchorsTheme.pulse(seconds, 2.2D) : 1.0F;
        int arrow = AnchorsTheme.withAlpha(0x1BD96A, Math.round(255 * intro * (0.5F + 0.5F * Math.max(hover, breathe))));
        int ax = rect.right() - 6;
        int ay = rect.y() + 2;
        graphics.fill(ax, ay, ax + 1, ay + 3, arrow);
        graphics.fill(ax - 1, ay + 2, ax + 2, ay + 3, arrow);
        graphics.fill(ax - 2, ay + 4, ax + 3, ay + 5, arrow);
        DevInspector.node("Tab", "herzium (not installed)", rect.x(), rect.y(), rect.width(), rect.height(),
                "FabricLoader.isModLoaded(\"herzium\") = false", "click: HerziumWindow(MISSING)");
    }

    /**
     * The tab's block: the anchor, crying obsidian for its skin, glowstone, an amethyst cluster for
     * the sounds, a lodestone for the server, netherite, nether gold for Herzium, amethyst for
     * KoHs; for the enemy's tabs shroomlight, crimson nylium and gilded blackstone. Drawn by the
     * block's own texture, scaled to the tab.
     */
    private static void drawTabIcon(GuiGraphicsExtractor graphics, int index, int x, int y, int size, int color) {
        if (index < 0 || index >= TAB_BLOCKS.length) {
            return;
        }
        // The block's own texture, flat: item stacks cannot be made before a world binds their
        // components, and this screen opens from the title screen too.
        graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, TAB_BLOCKS[index], x, y, 0.0F, 0.0F,
                size, size, 16, 16, 16, 16);
        int dim = 255 - (color >>> 24);
        if (dim > 40) {
            // A faded tab (the intro, Herzium missing): its block fades with it.
            graphics.fill(x, y, x + size, y + size, AnchorsTheme.withAlpha(0x0A0412, Math.min(200, dim)));
        }
    }

    private static final net.minecraft.resources.Identifier[] TAB_BLOCKS = {
            net.minecraft.resources.Identifier.withDefaultNamespace("textures/block/respawn_anchor_side4.png"),
            net.minecraft.resources.Identifier.withDefaultNamespace("textures/block/crying_obsidian.png"),
            net.minecraft.resources.Identifier.withDefaultNamespace("textures/block/glowstone.png"),
            net.minecraft.resources.Identifier.withDefaultNamespace("textures/block/amethyst_cluster.png"),
            net.minecraft.resources.Identifier.withDefaultNamespace("textures/block/lodestone_top.png"),
            net.minecraft.resources.Identifier.withDefaultNamespace("textures/block/netherite_block.png"),
            net.minecraft.resources.Identifier.withDefaultNamespace("textures/block/nether_gold_ore.png"),
            net.minecraft.resources.Identifier.withDefaultNamespace("textures/block/amethyst_block.png"),
            net.minecraft.resources.Identifier.withDefaultNamespace("textures/block/shroomlight.png"),
            net.minecraft.resources.Identifier.withDefaultNamespace("textures/block/crimson_nylium.png"),
            net.minecraft.resources.Identifier.withDefaultNamespace("textures/block/gilded_blackstone.png")};

    private void drawOptions(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, long now,
            boolean motion, double seconds) {
        AnchorsLayout.Rect viewport = this.layout.options;
        if (viewport.width() <= 0 || viewport.height() <= 0) {
            return;
        }
        boolean advanced = crimson();
        long since = this.tabChangedAt < 0L ? now - this.openedAt - INTRO_NANOS / 3 : now - this.tabChangedAt;
        int offset = Math.round(this.scroll);
        int lineRight = viewport.x() + this.layout.rowWidth();
        graphics.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
        for (Section section : this.sections) {
            int y = viewport.y() + section.offset() + 2 - offset;
            if (y + SECTION_HEIGHT < viewport.y() || y > viewport.bottom()) {
                continue;
            }
            int sectionColor = advanced ? 0xFFFF9AB0 : intense() ? 0xFFE7B5FF : AnchorsTheme.SECTION;
            AnchorsUi.diamond(graphics, viewport.x() + 3, y + 4, 2, advanced ? AnchorsTheme.CRIMSON_BRIGHT
                    : intense() ? AnchorsTheme.INTENSE : AnchorsTheme.ACCENT);
            AnchorsUi.label(graphics, this.font, section.title(), viewport.x() + 9, y, sectionColor, false);
            int lineX = viewport.x() + 15 + this.font.width(section.title());
            if (lineX < lineRight) {
                AnchorsUi.energyLine(graphics, lineX, lineRight, y + 4,
                        advanced ? AnchorsTheme.CRIMSON_BRIGHT : intense() ? AnchorsTheme.INTENSE : AnchorsTheme.ACCENT,
                        motion ? seconds : 0.0D, 0.8F);
            }
            DevInspector.node("Section", section.title(), viewport.x(), y, this.layout.rowWidth(), SECTION_HEIGHT - 2,
                    "Component " + section.key());
        }
        for (int index = 0; index < this.rows.size(); index++) {
            AnchorRow row = this.rows.get(index);
            if (row.getY() >= viewport.bottom() || row.getY() + row.getHeight() <= viewport.y()) {
                continue;
            }
            float appear = motion
                    ? AnchorsTheme.easeOutCubic(progress(since - index * ROW_STAGGER_NANOS, ROW_FADE_NANOS))
                    : 1.0F;
            row.setAppear(appear);
            row.extractRenderState(graphics, mouseX, mouseY, partialTick);
            DevInspector.node(row.getClass().getSimpleName(), row.inspectKey, row.getX(), Math.max(row.getY(), viewport.y()),
                    row.getWidth(), Math.min(row.getY() + row.getHeight(), viewport.bottom()) - Math.max(row.getY(), viewport.y()),
                    row.inspectDetails);
        }
        if (this.enemyPicker != null) {
            int pickerY = viewport.y() + this.enemyPickerOffset - offset;
            AnchorsLayout.Rect area = new AnchorsLayout.Rect(viewport.x() + 4, pickerY, this.layout.rowWidth() - 8,
                    this.enemyPickerHeight);
            this.enemyPicker.setArea(area);
            this.enemyPicker.render(graphics, this.font, mouseX, mouseY, 1.0F);
            DevInspector.node("ColorPicker", "enemy glow", area.x(), area.y(), area.width(), area.height(),
                    "EnemyGlow.color = " + ColorMath.hex(settings().enemyGlow.color));
        }
        if (this.statsOffset >= 0) {
            drawStats(graphics, viewport.x() + 2, viewport.y() + this.statsOffset - offset,
                    this.layout.rowWidth() - 4, 1.0F, false);
        }
        graphics.disableScissor();

        // A blade crosses the options when the tab changes.
        if (motion && this.tabChangedAt >= 0L) {
            float slash = progress(now - this.tabChangedAt, TAB_SLASH_NANOS);
            AnchorsUi.slash(graphics, viewport, slash, advanced ? AnchorsTheme.CRIMSON_BRIGHT
                    : intense() ? AnchorsTheme.INTENSE_BRIGHT : AnchorsTheme.ACCENT_BRIGHT);
        }

        if (this.maxScroll > 0) {
            int trackX = viewport.right() - 3;
            graphics.fill(trackX, viewport.y(), trackX + 2, viewport.bottom(), AnchorsTheme.SCROLL_TRACK);
            int thumbHeight = Math.max(12, viewport.height() * viewport.height() / Math.max(1, this.contentHeight));
            int thumbY = viewport.y() + Math.round((viewport.height() - thumbHeight) * (this.scroll / this.maxScroll));
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight,
                    advanced ? 0xE6FF6A86 : intense() ? 0xE6D27BFF : AnchorsTheme.SCROLL_THUMB);
            if (this.scroll > 0.5F) {
                graphics.fillGradient(viewport.x(), viewport.y(), trackX - 1, viewport.y() + 8, 0x800B0514, 0x000B0514);
            }
            if (this.scroll < this.maxScroll - 0.5F) {
                graphics.fillGradient(viewport.x(), viewport.bottom() - 8, trackX - 1, viewport.bottom(), 0x000B0514,
                        0x800B0514);
            }
        }
    }

    private void drawPreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float intro, boolean motion,
            double seconds) {
        AnchorsLayout.Rect preview = this.layout.preview;
        boolean advanced = crimson();
        AnchorsUi.panel(graphics, preview.x(), preview.y(), preview.width(), preview.height(),
                AnchorsTheme.fade(0x6A12091F, intro), AnchorsTheme.fade(0x5A08050D, intro));
        boolean hovered = this.previewArt.contains(mouseX, mouseY);
        AnchorsUi.roundedOutline(graphics, preview.x(), preview.y(), preview.width(), preview.height(),
                AnchorsTheme.fade(hovered ? AnchorsTheme.CARD_BORDER_HOVER
                        : advanced ? 0x8A6A1030 : AnchorsTheme.CARD_BORDER, intro));
        AnchorsUi.bladeCorners(graphics, preview.x(), preview.y(), preview.width(), preview.height(), 6,
                AnchorsTheme.fade(advanced ? 0xC0FF6A86 : 0xC0C084FC, intro));

        // The ritual circle behind the anchor lights a node for every charge the anchor holds.
        boolean enemyView = enemyPage;
        int radius = Math.round(Math.min(this.previewArt.width(), this.previewArt.height()) * 0.46F);
        if (radius > 16) {
            AnchorsUi.sigil(graphics, this.previewArt.centerX(), this.previewArt.centerY() + radius / 6, radius,
                    motion ? seconds * 1.6D : 0.0D, advanced || enemyView ? AnchorsTheme.CRIMSON_BRIGHT : AnchorsTheme.ACCENT,
                    0.8F * intro, enemyView ? this.enemyFigure.charge() : this.anchorPreview.charge());
        }
        if (this.enemySwitch != null || this.enemyReveal != null) {
            // The anchor is on its way: the switch or the reveal draws it.
        } else if (enemyView) {
            // The enemy's anchor exactly as a fight shows it: the same block, lit in their colour.
            float scale = Math.min(this.previewArt.width(), this.previewArt.height()) / 2.4F;
            this.enemyFigure.draw(graphics, this.previewArt.centerX(), this.previewArt.y() + this.previewArt.height() / 2.0F,
                    scale, intro, 1.0F, motion, true);
            if (this.previewArt.contains(mouseX, mouseY)) {
                List<FormattedCharSequence> hint = this.font.split(Component.translatable("kohs_anchors.enemy.preview.hint"),
                        Math.max(40, this.previewArt.width() - 6));
                int lineY = this.previewArt.bottom() - 10 * Math.min(2, hint.size());
                for (int index = 0; index < Math.min(2, hint.size()); index++) {
                    FormattedCharSequence line = hint.get(index);
                    AnchorsUi.line(graphics, this.font, line, this.previewArt.centerX() - this.font.width(line) / 2,
                            lineY + index * 10, AnchorsTheme.fade(AnchorsTheme.TEXT_DIM, intro));
                }
            }
            DevInspector.node("AnchorFigure", "enemy anchor", this.previewArt.x(), this.previewArt.y(), this.previewArt.width(),
                    this.previewArt.height(), "AnchorCube(\"enemy\"): glow layer lit in EnemyGlow.color",
                    "EnemyGlow.color = " + ColorMath.hex(settings().enemyGlow.color), "drag: turn · click: charge");
        } else if (this.tab == GLOW) {
            // The glow as it will look: its colour and strength around the anchor, and on the ground.
            AnchorsConfig.Glow glow = settings().glow;
            int color = AnchorGlowRenderer.ownColor(Math.max(1, Math.round(this.anchorPreview.charge())));
            boolean on = glow.enabled;
            if (on) {
                float charge = this.anchorPreview.charge() / 4.0F;
                float power = glow.power / 100.0F * (glow.chargeScaling ? 0.4F + 0.6F * charge : 1.0F);
                float breathe = glow.pulse && motion ? 0.86F + 0.14F * (float) Math.sin(seconds * 2.3D) : 1.0F;
                int size = Math.min(this.previewArt.width(), this.previewArt.height());
                AnchorsUi.glowEllipse(graphics, this.previewArt.centerX(), this.previewArt.y() + this.previewArt.height() / 2,
                        Math.round(size * (0.35F + 0.15F * glow.bloom / 100.0F)), Math.round(size * (0.33F + 0.15F * glow.bloom / 100.0F)),
                        color & 0xFFFFFF, Math.min(1.0F, power * breathe));
                AnchorsUi.glowEllipse(graphics, this.previewArt.centerX(),
                        this.previewArt.y() + this.previewArt.height() / 2 + Math.round(size * 0.34F),
                        Math.round(size * 0.45F * (0.5F + glow.spill / 200.0F)), Math.max(3, Math.round(size * 0.09F)),
                        color & 0xFFFFFF, Math.min(1.0F, power * glow.spill / 100.0F * breathe));
            }
        }
        if (this.enemySwitch == null && !enemyView) {
            this.anchorPreview.lightOverride(0);
            this.anchorPreview.render(graphics, this.font, this.previewArt, mouseX, mouseY, motion, intro, this.previewHint);
            DevInspector.node("AnchorPreview", "3D anchor", this.previewArt.x(), this.previewArt.y(), this.previewArt.width(),
                    this.previewArt.height(), "FallingBlockRenderState → GuiGraphics.entity", "block atlas: the skin shows here",
                    "FullBrightLightEngine");
        }

        int statsTop = preview.bottom() - STATS_HEIGHT - 8;
        AnchorsUi.energyLine(graphics, preview.x() + 8, preview.right() - 8, statsTop - 1,
                advanced ? AnchorsTheme.CRIMSON_BRIGHT : AnchorsTheme.ACCENT, motion ? seconds : 0.0D, intro);
        drawStats(graphics, preview.x() + 8, statsTop + 4, preview.width() - 16, intro, true);
    }

    /** Anchors Server's column: the bridge's anchor, green with a bridge, red under a padlock without. */
    private void drawServerPreview(GuiGraphicsExtractor graphics, float intro, boolean motion, double seconds) {
        AnchorsLayout.Rect preview = this.layout.preview;
        boolean locked = serverLocked();
        boolean ok = BridgeClient.available();
        int accent = ok ? 0xFF000000 | AnchorFx.GREEN : locked ? AnchorsTheme.CRIMSON_BRIGHT : AnchorsTheme.ACCENT;
        AnchorsUi.panel(graphics, preview.x(), preview.y(), preview.width(), preview.height(),
                AnchorsTheme.fade(locked ? 0x6A2A0A18 : 0x6A12091F, intro), AnchorsTheme.fade(0x5A08050D, intro));
        AnchorsUi.roundedOutline(graphics, preview.x(), preview.y(), preview.width(), preview.height(),
                AnchorsTheme.fade(locked ? 0x8A6A1030 : ok ? 0x8A1F7A48 : AnchorsTheme.CARD_BORDER, intro));
        AnchorsUi.bladeCorners(graphics, preview.x(), preview.y(), preview.width(), preview.height(), 6,
                AnchorsTheme.fade(locked ? 0xC0FF6A86 : ok ? 0xC08CFFB8 : 0xC0C084FC, intro));
        AnchorsLayout.Rect art = this.previewArt;
        float scale = Math.min(art.width(), art.height()) / 2.4F;
        int centerY = art.y() + art.height() / 2;
        int radius = Math.round(Math.min(art.width(), art.height()) * 0.46F);
        if (radius > 16) {
            AnchorsUi.sigil(graphics, art.centerX(), centerY + radius / 6, radius, motion ? seconds * 1.6D : 0.0D, accent,
                    0.8F * intro, ok ? 4.0F : 0.0F);
        }
        if (BridgeClient.state() == BridgeClient.State.CHECKING && motion) {
            AnchorFx.radar(graphics, art.centerX(), centerY, Math.round(scale * 1.3F), seconds * 3.4D, 0xE9D5FF, 0.7F * intro);
        }
        boolean flying = this.serverCheck != null && this.serverCheck.flying();
        if (!flying && art.width() > 0) {
            this.bridgeFigure.draw(graphics, art.centerX(), centerY, scale, intro, 0.0F, motion, true, true,
                    ok ? AnchorFx.GREEN : 0xFF315C, ok || locked ? 1.0F : 0.0F);
            if (locked) {
                int size = Math.max(10, Math.round(scale * 0.95F));
                AnchorsUi.glowEllipse(graphics, art.centerX(), centerY, Math.round(size * 1.1F), Math.round(size * 0.9F), 0xFF315C,
                        0.3F * intro);
                AnchorFx.padlock(graphics, art.centerX(), centerY - Math.round(size * 0.2F), size, intro);
            }
        }
        String status = bridgeBadge();
        AnchorsUi.label(graphics, this.font, AnchorsUi.ellipsis(this.font, status, art.width() - 4),
                art.centerX() - Math.min(this.font.width(status), art.width() - 4) / 2, art.bottom() - 10,
                AnchorsTheme.fade(bridgeColor(), intro), true);
        if (this.optionFx != null) {
            this.optionFx.render(graphics, this.font, art, seconds);
            if (this.optionFx.done()) {
                this.optionFx = null;
            }
        }
        DevInspector.node("BridgeFigure", "bridge anchor", art.x(), art.y(), art.width(), art.height(),
                "BridgeClient.state() = " + BridgeClient.state(), "AnchorFigure(\"bridge\") tinted",
                "OptionFx when an option is switched on");
        int statsTop = preview.bottom() - STATS_HEIGHT - 8;
        AnchorsUi.energyLine(graphics, preview.x() + 8, preview.right() - 8, statsTop - 1, accent, motion ? seconds : 0.0D, intro);
        drawStats(graphics, preview.x() + 8, statsTop + 4, preview.width() - 16, intro, true);
    }

    /** Enemy anchors off: their switch in the middle, their red anchor under it, and a word on both. */
    private void drawEnemyOff(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float intro, boolean motion,
            double seconds) {
        AnchorsLayout.Rect pill = enemyOffSwitch();
        this.enemyOffHover += ((pill.contains(mouseX, mouseY) ? 1.0F : 0.0F) - this.enemyOffHover) * 0.25F;
        float hover = this.enemyOffHover;
        float pulse = motion ? AnchorsTheme.pulse(seconds, 1.6D) : 0.5F;
        AnchorsUi.halo(graphics, pill.x(), pill.y(), pill.width(), pill.height(), AnchorsTheme.CRIMSON_BRIGHT, 4,
                intro * (0.35F + 0.4F * pulse + 0.25F * hover));
        AnchorsUi.panel(graphics, pill.x(), pill.y(), pill.width(), pill.height(),
                AnchorsTheme.fade(AnchorsTheme.lerp(0xD03A0A1A, 0xE0521028, hover), intro), AnchorsTheme.fade(0xD0180410, intro));
        AnchorsUi.roundedOutline(graphics, pill.x(), pill.y(), pill.width(), pill.height(),
                AnchorsTheme.fade(AnchorsTheme.lerp(0xFFB8243F, 0xFFFF6A86, Math.max(hover, pulse * 0.5F)), intro));
        String label = Component.translatable("kohs_anchors.option.enemy_enabled").getString().toUpperCase(Locale.ROOT);
        String state = Component.translatable("kohs_anchors.state.off").getString().toUpperCase(Locale.ROOT);
        int trackX = pill.right() - 30;
        int trackY = pill.y() + 5;
        AnchorsUi.label(graphics, this.font, AnchorsUi.ellipsis(this.font, label, trackX - this.font.width(state) - 14 - pill.x()),
                pill.x() + 8, pill.y() + 6, AnchorsTheme.fade(0xFFFFE4EA, intro), true);
        AnchorsUi.label(graphics, this.font, state, trackX - 4 - this.font.width(state), pill.y() + 6,
                AnchorsTheme.fade(AnchorsTheme.STATE_OFF, intro), false);
        AnchorsUi.panel(graphics, trackX, trackY, 22, 10, AnchorsTheme.fade(AnchorsTheme.SWITCH_OFF, intro),
                AnchorsTheme.fade(0xFF12091F, intro));
        AnchorsUi.roundedOutline(graphics, trackX, trackY, 22, 10, AnchorsTheme.fade(0xFF6A1030, intro));
        graphics.fill(trackX + 2, trackY + 2, trackX + 8, trackY + 8, AnchorsTheme.fade(AnchorsTheme.KNOB_OFF, intro));

        AnchorsLayout.Rect body = this.layout.body();
        List<FormattedCharSequence> lines = this.font.split(Component.translatable("kohs_anchors.enemy.off.body"),
                Math.max(60, Math.min(body.width() - 20, 320)));
        int lineY = pill.bottom() + 6;
        for (int index = 0; index < Math.min(2, lines.size()); index++) {
            FormattedCharSequence line = lines.get(index);
            AnchorsUi.line(graphics, this.font, line, body.centerX() - this.font.width(line) / 2, lineY,
                    AnchorsTheme.fade(AnchorsTheme.TEXT_MUTED, intro));
            lineY += 10;
        }
        AnchorsLayout.Rect stage = enemyOffStage();
        float scale = Math.min(stage.width(), stage.height()) / 2.4F;
        float centerY = stage.y() + stage.height() / 2.0F;
        AnchorsUi.sigil(graphics, stage.centerX(), Math.round(centerY + scale * 0.2F), Math.round(scale * 2.0F),
                motion ? seconds * 0.8D : 0.0D, AnchorsTheme.CRIMSON, 0.6F * intro, 0.0F);
        if (this.enemyReveal == null && this.enemySwitch == null) {
            this.enemyFigure.draw(graphics, stage.centerX(), centerY, scale, intro * 0.92F, 1.0F, motion, true, true,
                    AnchorFigure.enemyColor(), 1.0F);
        }
        DevInspector.node("EnemyOff", "enemy anchors off", pill.x(), pill.y(), pill.width(), pill.height(),
                "EnemyGlow.enabled = false: no tabs", "click: setEnemyAnchors(true) · EnemyReveal");
    }

    private static String bridgeShort() {
        return Component.translatable("kohs_anchors.bridge.short." + BridgeClient.state().name().toLowerCase(Locale.ROOT))
                .getString();
    }

    private void drawStats(GuiGraphicsExtractor graphics, int x, int y, int width, float alpha, boolean withTitle) {
        int lineY = y;
        boolean advanced = crimson();
        if (withTitle) {
            AnchorsUi.label(graphics, this.font, this.statsTitle.toUpperCase(Locale.ROOT), x, lineY,
                    AnchorsTheme.fade(advanced ? 0xFFFF9AB0 : AnchorsTheme.SECTION, alpha), false);
            lineY += 13;
        }
        String[] keys;
        String[] values;
        switch (this.tab) {
            case GLOW, ENEMY_GLOW, ENEMY_COLOURS -> {
                keys = new String[] {"tracked", "enemies", "predicted", "confirmed", "kept"};
                values = numbers(AnchorTracker.count(), AnchorTracker.enemyCount(), AnchorStats.predictedDetonations(),
                        AnchorStats.confirmedDetonations(), AnchorStats.keptDetonations());
            }
            case SOUNDS -> {
                keys = new String[] {"predicted", "confirmed", "held", "merged", "kept"};
                values = numbers(AnchorStats.predictedDetonations(), AnchorStats.confirmedDetonations(),
                        AnchorStats.heldClicks(), AnchorStats.mergedClicks(), AnchorStats.keptDetonations());
            }
            case SERVER -> {
                keys = new String[] {"bridge", "ping", "owners", "chained", "instant"};
                float roundTrip = BridgeClient.roundTripMillis();
                values = new String[] {bridgeShort(), roundTrip >= 0.0F ? Math.round(roundTrip) + " ms" : "—",
                        Integer.toString(BridgeClient.ownersReceived()), Integer.toString(AnchorStats.chainedClicks()),
                        Integer.toString(AnchorStats.instantDetonations())};
            }
            case HERZIUM -> {
                keys = new String[] {"ordered", "next_tick", "reported", "previews", "merged"};
                values = numbers(AnchorStats.orderedBursts(), AnchorStats.nextTickPresses(), HerziumBridge.reportedPresses(),
                        HerziumBridge.droppedPreviews(), AnchorStats.mergedClicks());
            }
            default -> {
                keys = new String[] {"cycle", "ordered", "held", "merged", "debounced"};
                values = numbers(0, AnchorStats.orderedBursts(), AnchorStats.heldClicks(), AnchorStats.mergedClicks(),
                        AnchorStats.debouncedAnchors() + AnchorStats.debouncedGlowstone() + AnchorStats.glowstoneGuarded());
                long last = AnchorStats.lastCycleMillis();
                values[0] = last < 0L ? "—" : last + " · " + AnchorStats.bestCycleMillis() + " ms";
            }
        }
        for (int index = 0; index < values.length; index++) {
            String value = values[index];
            int valueWidth = this.font.width(value);
            String name = AnchorsUi.ellipsis(this.font, Component.translatable("kohs_anchors.stats." + keys[index]).getString(),
                    width - valueWidth - 6);
            AnchorsUi.label(graphics, this.font, name, x, lineY, AnchorsTheme.fade(AnchorsTheme.TEXT_MUTED, alpha), false);
            AnchorsUi.label(graphics, this.font, value, x + width - valueWidth, lineY,
                    AnchorsTheme.fade(advanced ? 0xFFFFC2CE : AnchorsTheme.ACCENT_BRIGHT, alpha), false);
            DevInspector.node("Stat", keys[index], x, lineY, width, 9, "AnchorStats." + keys[index] + " = " + value,
                    "counted where it happens, never estimated");
            lineY += STAT_LINE;
        }
    }

    private static String[] numbers(int... values) {
        String[] text = new String[values.length];
        for (int index = 0; index < values.length; index++) {
            text[index] = Integer.toString(values[index]);
        }
        return text;
    }

    private void drawFooterNote(GuiGraphicsExtractor graphics, float intro) {
        boolean advanced = crimson();
        AnchorsLayout.Rect footer = this.layout.footer;
        int left = this.layout.resetButton.right() + 8;
        int right = this.layout.doneButton.x() - 8;
        boolean armed = this.resetArmedAt >= 0L && System.nanoTime() - this.resetArmedAt < RESET_ARM_NANOS;
        String note = Component.translatable(armed ? "kohs_anchors.footer.note.reset"
                : this.tab == ENEMY_ADVANCED ? "kohs_anchors.footer.note.enemy_advanced"
                : enemyPage ? "kohs_anchors.footer.note.enemy"
                : this.tab == SERVER ? "kohs_anchors.footer.note.server"
                : this.tab == ADVANCED ? "kohs_anchors.footer.note.advanced" : this.tab == ANCHOR ? "kohs_anchors.footer.note.anchor"
                : this.tab == KOHS ? "kohs_anchors.footer.note.kohs" : "kohs_anchors.footer.note").getString();
        int noteWidth = this.font.width(note);
        if (right - left < noteWidth) {
            return;
        }
        AnchorsUi.label(graphics, this.font, note, left + (right - left - noteWidth) / 2,
                footer.y() + (footer.height() - 8) / 2 + 1,
                AnchorsTheme.fade(armed ? 0xFFFFC2CE : advanced ? 0xFFE08A9E : AnchorsTheme.TEXT_DIM, intro), false);
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
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (this.entry != null) {
            return this.entry.mouseClicked(this.width, this.height, mouseX, mouseY, button);
        }
        if (this.serverCheck != null) {
            return this.serverCheck.mouseClicked(this.width, this.height, mouseX, mouseY, button);
        }
        if (this.enemyReveal != null) {
            // A click hurries the reveal to its end.
            this.enemyReveal.skip();
            this.enemyReveal = null;
            return true;
        }
        if (this.guardWarning != null) {
            return this.guardWarning.mouseClicked(this.font, this.width, this.height, mouseX, mouseY, button);
        }
        if (this.herziumWindow != null) {
            return this.herziumWindow.mouseClicked(this.font, this.width, this.height, mouseX, mouseY, button);
        }
        if (this.enemyIntro != null) {
            return this.enemyIntro.mouseClicked(this.width, this.height, mouseX, mouseY, button);
        }
        if (this.enemySwitch != null) {
            // A click hurries the switch to its end.
            this.enemySwitch.skip();
            this.enemySwitch = null;
            return true;
        }
        if (this.crystalModal != null) {
            return this.crystalModal.mouseClicked(this.width, this.height, mouseX, mouseY, button);
        }
        if (this.devWarning != null) {
            return this.devWarning.mouseClicked(this.width, this.height, mouseX, mouseY, button);
        }
        if (this.soundPicker != null) {
            return this.soundPicker.mouseClicked(mouseX, mouseY, button);
        }
        if (this.popover != null) {
            if (popoverDone().contains(mouseX, mouseY) || !this.popoverBox.contains(mouseX, mouseY)) {
                this.popover = null;
                AnchorsConfig.save();
                return true;
            }
            this.popover.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        if (button == Keys.LEFT_BUTTON && this.enemySwitchWidth > 0 && mouseX >= this.enemySwitchX
                && mouseX < this.enemySwitchX + this.enemySwitchWidth && mouseY >= this.enemySwitchY
                && mouseY < this.enemySwitchY + 13) {
            toggleEnemyAnchors();
            return true;
        }
        if (enemyOff() && button == Keys.LEFT_BUTTON && enemyOffSwitch().contains(mouseX, mouseY)) {
            setEnemyAnchors(true);
            return true;
        }
        int[] tabs = visibleTabs();
        for (int slot = 0; slot < tabs.length; slot++) {
            if (button == Keys.LEFT_BUTTON && this.layout.tab(slot, tabs.length).contains(mouseX, mouseY)) {
                selectTab(tabs[slot]);
                return true;
            }
        }
        if (this.tab == ANCHOR && this.workshop != null) {
            if (this.workshop.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        } else if (this.tab == KOHS && this.kohsPage != null) {
            if (this.kohsPage.mouseClicked(this.font, this.layout.body(), mouseX, mouseY, button)) {
                return true;
            }
        } else if (this.tab == ENEMY_ADVANCED && this.battle != null) {
            if (this.battle.mouseClicked(this.layout.body(), mouseX, mouseY, button)) {
                return true;
            }
        } else {
            if (this.enemyPicker != null && this.enemyPicker.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            if (button == Keys.LEFT_BUTTON) {
                for (AnchorRow row : this.rows) {
                    if (row instanceof AnchorSliderRow slider && slider.trackContains(mouseX, mouseY)) {
                        this.draggingSlider = slider;
                        slider.beginDrag(mouseX);
                        return true;
                    }
                }
            }
            if (enemyPage) {
                if (button == Keys.LEFT_BUTTON && this.previewArt.contains(mouseX, mouseY)) {
                    this.figureDragging = true;
                    this.figureDragDistance = 0.0D;
                    return true;
                }
            } else if (this.anchorPreview.mouseClicked(this.previewArt, mouseX, mouseY, button, doubleClick)) {
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.entry != null || this.serverCheck != null || this.enemyReveal != null || this.devWarning != null
                || this.crystalModal != null || this.soundPicker != null || this.guardWarning != null || this.enemyIntro != null
                || this.enemySwitch != null || this.herziumWindow != null) {
            return true;
        }
        if (this.figureDragging) {
            this.enemyFigure.drag(dragX, dragY);
            this.figureDragDistance += Math.abs(dragX) + Math.abs(dragY);
            return true;
        }
        if (this.popover != null) {
            this.popover.mouseDragged(event.x(), event.y());
            return true;
        }
        if (this.draggingSlider != null) {
            this.draggingSlider.dragTo(event.x());
            return true;
        }
        if (this.tab == ANCHOR && this.workshop != null) {
            if (this.workshop.mouseDragged(event.x(), event.y(), dragX, dragY)) {
                return true;
            }
        } else if (this.enemyPicker != null && this.enemyPicker.mouseDragged(event.x(), event.y())) {
            return true;
        }
        if (this.anchorPreview.mouseDragged(dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.entry != null || this.serverCheck != null || this.enemyReveal != null || this.devWarning != null
                || this.crystalModal != null || this.soundPicker != null || this.guardWarning != null
                || this.enemyIntro != null || this.enemySwitch != null || this.herziumWindow != null) {
            return true;
        }
        if (this.figureDragging) {
            this.figureDragging = false;
            if (this.figureDragDistance < 3.0D) {
                // A click, not a drag: one more charge, with the charge sound the player chose.
                this.enemyFigure.cycleCharge();
                AnchorSounds.previewCharge(0.9F + this.enemyFigure.charge() * 0.05F);
            }
            return true;
        }
        if (this.popover != null) {
            this.popover.mouseReleased();
            return true;
        }
        if (this.draggingSlider != null) {
            this.draggingSlider.endDrag();
            this.draggingSlider = null;
            return true;
        }
        if (this.tab == ANCHOR && this.workshop != null) {
            if (this.workshop.mouseReleased()) {
                return true;
            }
        } else if (this.enemyPicker != null && this.enemyPicker.mouseReleased()) {
            AnchorsConfig.save();
            return true;
        }
        if (this.anchorPreview.mouseReleased()) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.entry != null || this.serverCheck != null || this.enemyReveal != null || this.devWarning != null
                || this.crystalModal != null || this.popover != null || this.guardWarning != null || this.enemyIntro != null
                || this.enemySwitch != null || this.herziumWindow != null) {
            return true;
        }
        if (this.soundPicker != null) {
            return this.soundPicker.mouseScrolled(verticalAmount);
        }
        if (this.tab == KOHS || this.tab == ENEMY_ADVANCED) {
            // Nothing on the KoHs page or the battle scrolls.
            return true;
        }
        if (this.tab == ANCHOR && this.workshop != null) {
            return this.workshop.mouseScrolled(mouseX, mouseY, verticalAmount);
        }
        if (!enemyPage && this.anchorPreview.mouseScrolled(this.previewArt, mouseX, mouseY, verticalAmount)) {
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
        int key = event.key();
        if (this.entry != null) {
            return this.entry.keyPressed(key);
        }
        if (this.serverCheck != null) {
            return this.serverCheck.keyPressed(key);
        }
        if (this.enemyReveal != null) {
            this.enemyReveal.skip();
            this.enemyReveal = null;
            return true;
        }
        if (this.guardWarning != null) {
            return this.guardWarning.keyPressed(key);
        }
        if (this.herziumWindow != null) {
            return this.herziumWindow.keyPressed(key);
        }
        if (this.enemyIntro != null) {
            return this.enemyIntro.keyPressed(key);
        }
        if (this.enemySwitch != null) {
            if (key == Keys.ESCAPE) {
                this.enemySwitch.skip();
                this.enemySwitch = null;
            }
            return true;
        }
        if (this.crystalModal != null) {
            return this.crystalModal.keyPressed(key);
        }
        if (this.devWarning != null) {
            return this.devWarning.keyPressed(key);
        }
        if (this.soundPicker != null) {
            return this.soundPicker.keyPressed(key);
        }
        if (this.popover != null) {
            if (!this.popover.keyPressed(key) && (key == Keys.ESCAPE || Keys.confirms(key))) {
                this.popover = null;
                AnchorsConfig.save();
            }
            return true;
        }
        if (this.enemyPicker != null && this.enemyPicker.keyPressed(key)) {
            return true;
        }
        if (this.tab == ANCHOR && this.workshop != null
                && this.workshop.keyPressed(key, (event.modifiers() & Keys.CONTROL) != 0)) {
            return true;
        }
        if (key == Keys.F12 && dev()) {
            // F12, as in a browser, shows and hides the inspector while developer mode is on.
            DevInspector.toggle();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        char character = (char) event.codepoint();
        if (this.soundPicker != null) {
            return this.soundPicker.charTyped(character);
        }
        if (this.popover != null) {
            return this.popover.charTyped(character);
        }
        if (this.enemyPicker != null && this.enemyPicker.charTyped(character)) {
            return true;
        }
        if (this.tab == ANCHOR && this.workshop != null && this.workshop.charTyped(character)) {
            return true;
        }
        return super.charTyped(event);
    }

    /** Keyboard focus on a row out of view scrolls it into view. */
    private void followFocus() {
        GuiEventListener focused = getFocused();
        if (focused == this.lastFocused) {
            return;
        }
        this.lastFocused = focused;
        int index = focused instanceof AnchorRow row ? this.rows.indexOf(row) : -1;
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

    /** Defaults for the tab in view; the first press arms it, the second within 2.5 s applies it. */
    private void resetTab() {
        if (this.tab == KOHS || this.tab == ENEMY_ADVANCED) {
            // Nothing on the KoHs tab or the battle is a setting.
            return;
        }
        long now = System.nanoTime();
        if (this.resetArmedAt < 0L || now - this.resetArmedAt > RESET_ARM_NANOS) {
            this.resetArmedAt = now;
            return;
        }
        this.resetArmedAt = -1L;
        AnchorsConfig.Settings settings = settings();
        AnchorsConfig.Settings defaults = new AnchorsConfig.Settings();
        switch (this.tab) {
            case GENERAL -> {
                settings.hideDetonating = defaults.hideDetonating;
                settings.anchorDebris = defaults.anchorDebris;
                settings.interfaceMotion = defaults.interfaceMotion;
                settings.anchorDebounceMillis = defaults.anchorDebounceMillis;
                settings.glowstoneDebounceMillis = defaults.glowstoneDebounceMillis;
                settings.glowstoneGuard = defaults.glowstoneGuard;
                settings.anchorSmoke = defaults.anchorSmoke;
                AnchorDebounce.settingsChanged();
            }
            case ANCHOR -> settings.skin = defaults.skin;
            case GLOW -> settings.glow = defaults.glow;
            case ENEMY_GLOW -> settings.enemyGlow.enabled = defaults.enemyGlow.enabled;
            case ENEMY_COLOURS -> settings.enemyGlow.color = defaults.enemyGlow.color;
            case SOUNDS -> {
                settings.chargeSound = defaults.chargeSound;
                settings.explosionSound = defaults.explosionSound;
            }
            case HERZIUM -> {
                // Herzium's order is Herzium's: only this mod's side goes back to its defaults.
                settings.herziumSync = defaults.herziumSync;
                settings.herziumIntro = defaults.herziumIntro;
            }
            case SERVER -> {
                settings.fastChain = false;
                settings.instantDetonation = false;
                settings.betterEnemyGlow = defaults.betterEnemyGlow;
                settings.bridgeLatency = defaults.bridgeLatency;
                BridgeClient.settingsChanged();
            }
            default -> settings.devMode = false;
        }
        AnchorsConfig.changed();
        AnchorsConfig.save();
        rebuildWidgets();
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

    @Override
    public boolean shouldCloseOnEsc() {
        return !modalOpen() && (this.workshop == null || !this.workshop.editingText());
    }

    /** Whether a warning, picker or popover owns the pointer and the keyboard. */
    private boolean modalOpen() {
        return this.entry != null || this.serverCheck != null || this.enemyReveal != null || this.devWarning != null
                || this.crystalModal != null || this.soundPicker != null || this.popover != null || this.guardWarning != null
                || this.enemyIntro != null || this.enemySwitch != null || this.herziumWindow != null;
    }

    private void saveSettings() {
        if (this.guardWarning != null) {
            // Leaving under the warning: its clip stops and lets go of its texture.
            this.guardWarning.close();
            this.guardWarning = null;
        }
        if (this.enemySwitch != null) {
            this.enemySwitch.skip();
            this.enemySwitch = null;
        }
        this.enemyIntro = null;
        this.enemyReveal = null;
        this.serverCheck = null;
        this.enemyFigure.close();
        this.bridgeFigure.close();
        this.headerPortal.close();
        if (this.battle != null) {
            // Its anchors' textures go with it; the tab starts it again if the screen comes back.
            this.battle.close();
            this.battle = null;
        }
        release();
        if (this.saved) {
            return;
        }
        if (this.workshop != null) {
            this.workshop.close();
            this.workshop = null;
        }
        SkinPaint.saveAll();
        AnchorsConfig.save();
        this.saved = true;
    }

    private static float progress(long elapsed, long duration) {
        return Mth.clamp(elapsed / (float) duration, 0.0F, 1.0F);
    }

    private record Section(String title, int offset, String key) {
    }
}
