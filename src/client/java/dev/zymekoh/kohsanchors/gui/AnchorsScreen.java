package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/**
 * The KoHs Anchor's settings, in the Zymekoh style: black-purple glass over a transparent veil,
 * an anchor sigil turning slowly behind it, five tabs, and the 3D anchor inside its ritual circle
 * with this session's numbers.
 *
 * <ul>
 *   <li><b>General</b>: the anchor and glowstone debounce, the detonation, what is always on, and
 *   Herzium.</li>
 *   <li><b>Anchor</b>: the workshop, where the anchor's two layers are coloured or painted pixel by
 *   pixel ({@link AnchorWorkshop}).</li>
 *   <li><b>Glow</b>: the light a charged anchor gives off, and the enemy anchors' colour on a page
 *   of its own.</li>
 *   <li><b>Sounds</b>: the charge and explosion sounds.</li>
 *   <li><b>Advanced</b>: the not secure options, crimson, each behind two warnings, and developer
 *   mode, behind a blue one.</li>
 * </ul>
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
    static final int ADVANCED = 4;
    private static final String[] TAB_KEYS = {"general", "anchor", "glow", "sounds", "advanced"};

    /** Debounce stops: Vanilla, then one millisecond to ten seconds, finer at the short end. */
    static final int[] DEBOUNCE_STOPS = {0, 1, 2, 3, 5, 8, 10, 15, 20, 25, 30, 40, 50, 60, 75, 80, 100, 120, 150, 175,
            200, 250, 300, 350, 400, 500, 600, 750, 1000, 1250, 1500, 2000, 2500, 3000, 4000, 5000, 6000, 7500, 10000};

    /** The tab the screen opens on: the last one used this session. */
    private static int lastTab;
    /** Whether the glow tab shows the enemy anchors' page. */
    private static boolean enemyPage;

    private final Screen parent;
    private final String versionLabel;
    private final List<AnchorRow> rows = new ArrayList<>();
    private final List<Integer> rowOffsets = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    private final AnchorPreview anchorPreview = new AnchorPreview();
    private final float[] tabHover = new float[AnchorsLayout.TAB_COUNT];

    private AnchorsLayout layout;
    private AnchorsLayout.Rect previewArt = AnchorsLayout.Rect.EMPTY;
    private int tab = lastTab;
    private long tabChangedAt = -1L;
    /** When the screen last switched between the player's anchors and the enemy's. */
    private long enemySwitchedAt = -1L;
    private float enemySwitchHover;
    private int enemySwitchX;
    private int enemySwitchY;
    private int enemySwitchWidth;
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
    private float anchorCharge;
    private long resetArmedAt = -1L;
    private float previousPreviewX = -1.0F;
    private float previousPreviewY = -1.0F;

    private AnchorWorkshop workshop;
    private AnchorsWarning warning;
    private GlowstoneGuardWarning guardWarning;
    private DevModeWarning devWarning;
    private CrystalModal crystalModal;
    private SoundPicker soundPicker;
    private ColorPicker enemyPicker;
    private AnchorSliderRow draggingSlider;

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
        this.lastFrame = now;
        this.saved = false;
        this.lastFocused = null;
        this.layout = AnchorsLayout.fit(this.width, this.height);
        this.subtitle = Component.translatable("kohs_anchors.screen.subtitle").getString();
        this.previewHint = Component.translatable("kohs_anchors.preview.hint").getString();
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
        } else {
            buildContent();
        }

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
        this.bannerOffset = -1;
        switch (this.tab) {
            case GENERAL -> offset = buildGeneral(offset);
            case GLOW -> offset = enemyPage ? buildEnemy(offset) : buildGlow(offset);
            case SOUNDS -> offset = buildSounds(offset);
            default -> offset = buildAdvanced(offset);
        }
        this.statsOffset = -1;
        if (!this.layout.showsPreview() && !(this.tab == GLOW && enemyPage)) {
            // Without the anchor column the session numbers move to the end of the list.
            offset = section(offset, "kohs_anchors.stats.title");
            this.statsOffset = offset - SECTION_HEIGHT;
            offset += STAT_LINE * 5;
        }
        if (this.tab == GLOW && enemyPage) {
            // The enemy page ends in a full colour picker.
            int pickerHeight = Math.max(ColorPicker.minHeight() + 20, Math.min(150, this.layout.options.height() - offset - 6));
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
        offset = toggle(offset, "hide_detonating", () -> settings.hideDetonating, value -> settings.hideDetonating = value,
                "AnchorVeil.predict(level, pos, AIR, AIR)", "RenderSectionRegionMixin @ getBlockState HEAD",
                "SodiumLevelSliceMixin (@Pseudo) · VeiledOutlineMixin", "lifted on the server's state or on the ack");
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
                AnchorsTheme.ACCENT_BRIGHT, "AnchorInput: replay, refreshTargetBeforeUse, hold, mergesRepeat",
                "DetonationPredictor.showAtInput / onAnchorUsed", "Settings fields transient: not saved");
        offset = section(offset, "kohs_anchors.section.integrations");
        if (HerziumBridge.installed() && HerziumBridge.orderAvailable()) {
            String version = HerziumBridge.version();
            offset = addRow(offset, new AnchorRows.Cycle(x(), y(offset), this.layout.rowWidth(),
                    Component.translatable("kohs_anchors.option.herzium", version),
                    description(HerziumBridge.agrees() ? "herzium" : "herzium_old"), this.font,
                    () -> orderLabel(HerziumBridge.hotbarOrder()), HerziumBridge::cycleHotbarOrder, "herzium",
                    "HerziumBridge (reflection)", HerziumBridge.reflectionTarget(),
                    "agrees from " + HerziumBridge.AGREEING_VERSION + ": " + HerziumBridge.agrees()));
        } else {
            offset = note(offset, "herzium_missing", () -> Component.translatable("kohs_anchors.herzium.absent").getString(),
                    AnchorsTheme.TEXT_DIM, "FabricLoader.isModLoaded(\"herzium\") = false");
        }
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

    private int buildEnemy(int offset) {
        AnchorsConfig.EnemyGlow enemy = settings().enemyGlow;
        offset = note(offset, "enemy_about", () -> Component.translatable("kohs_anchors.enemy.badge").getString()
                        + " " + AnchorTracker.enemyCount(), AnchorsTheme.CRIMSON_BRIGHT,
                "AnchorTracker.ENEMY: a new anchor this client did not place",
                "ownPlacement ← MultiPlayerGameModeMixin @ useItemOn RETURN");
        offset = section(offset, "kohs_anchors.section.enemy_colour");
        offset = toggle(offset, "enemy_enabled", () -> enemy.enabled, value -> enemy.enabled = value,
                "EnemyGlow.enabled", "tracked " + AnchorTracker.count() + " · enemies " + AnchorTracker.enemyCount());
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

    private int buildAdvanced(int offset) {
        AnchorsConfig.Settings settings = settings();
        this.bannerOffset = offset;
        this.bannerHeight = bannerHeightFor(this.layout.rowWidth());
        offset += this.bannerHeight + SECTION_GAP;
        offset = section(offset, "kohs_anchors.section.advanced");
        offset = dangerRow(offset, "fast_chain", () -> settings.fastChain, value -> settings.fastChain = value,
                "FastChain.decide ← AnchorInput.decide", "predictions drawn by AnchorVeil",
                "gated by ServerLock.fastChain()");
        offset = dangerRow(offset, "instant_detonation", () -> settings.instantDetonation,
                value -> settings.instantDetonation = value, "AnchorInput.afterKeyClicked ← KeyMappingMixin @ click TAIL",
                "startUseItem between client ticks", "gated by ServerLock.instantDetonation()");
        offset = note(offset, "server_lock", () -> Component.translatable(serverStatusKey()).getString(),
                ServerLock.suspendedHere() ? AnchorsTheme.CRIMSON_BRIGHT : AnchorsTheme.ACCENT_BRIGHT,
                "ServerLock: singleplayer, LAN, localhost, servers allowed this session",
                "allowed servers are never saved or shown");
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

    private String serverStatusKey() {
        if (!ServerLock.anyAdvancedOn()) {
            return "kohs_anchors.server.status.off";
        }
        if (ServerLock.local()) {
            return "kohs_anchors.server.status.local";
        }
        return ServerLock.allowedHere() ? "kohs_anchors.server.status.allowed" : "kohs_anchors.server.status.suspended";
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

    private static String orderLabel(String order) {
        String key = switch (order) {
            case "HERZIUM" -> "kohs_anchors.herzium.order.herzium";
            case "VANILLA" -> "kohs_anchors.herzium.order.vanilla";
            case "VANILLA_REVERSED" -> "kohs_anchors.herzium.order.reversed";
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

    /** An advanced option: switching it off is immediate, switching it on takes two warnings. */
    private int dangerRow(int offset, String option, BooleanSupplier state, Consumer<Boolean> set, String... details) {
        return addRow(offset, new AnchorSwitchRow(x(), y(offset), this.layout.rowWidth(), label(option), description(option),
                this.font, state, () -> {
                    if (state.getAsBoolean()) {
                        set.accept(false);
                        AnchorsConfig.save();
                        return;
                    }
                    this.warning = new AnchorsWarning(Component.translatable("kohs_anchors.option." + option),
                            settings().interfaceMotion, () -> {
                                set.accept(true);
                                AnchorsConfig.save();
                            });
                }, true, Component.translatable("kohs_anchors.tag.not_secure"), option, details));
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

    private int bannerHeightFor(int width) {
        List<FormattedCharSequence> lines = this.font.split(Component.translatable("kohs_anchors.advanced.banner.text"),
                Math.max(40, width - 34));
        return 8 + 11 + lines.size() * 10 + 6;
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

    private void selectTab(int index) {
        if (index >= 0 && index < AnchorsLayout.TAB_COUNT && index != GLOW && enemyPage) {
            // The enemy's anchors only have a glow: any other tab is the player's own again.
            enemyPage = false;
            this.enemySwitchedAt = System.nanoTime();
            enemySound(false);
        }
        if (index == this.tab || index < 0 || index >= AnchorsLayout.TAB_COUNT) {
            if (index == this.tab) {
                rebuildWidgets();
            }
            return;
        }
        if (this.layout.showsPreview() && this.tab != ANCHOR) {
            this.previousPreviewX = this.previewArt.centerX();
            this.previousPreviewY = this.previewArt.y() + this.previewArt.height() / 2.0F;
        } else {
            this.previousPreviewX = -1.0F;
            this.previousPreviewY = -1.0F;
        }
        if (this.tab == ANCHOR && this.workshop != null) {
            this.workshop.close();
            this.workshop = null;
        }
        this.tab = index;
        lastTab = index;
        this.tabChangedAt = System.nanoTime();
        this.scroll = 0.0F;
        this.scrollTarget = 0.0F;
        this.resetArmedAt = -1L;
        rebuildWidgets();
    }

    /**
     * The header switch: the whole screen turns to the enemy's anchors (their glow colour), in
     * crimson, or back to the player's own.
     */
    private void toggleEnemyAnchors() {
        long now = System.nanoTime();
        enemyPage = !enemyPage;
        this.enemySwitchedAt = now;
        enemySound(enemyPage);
        if (this.tab != GLOW) {
            if (this.tab == ANCHOR && this.workshop != null) {
                this.workshop.close();
                this.workshop = null;
            }
            this.previousPreviewX = -1.0F;
            this.previousPreviewY = -1.0F;
            this.tab = GLOW;
            lastTab = GLOW;
        }
        this.tabChangedAt = now;
        this.scroll = 0.0F;
        this.scrollTarget = 0.0F;
        this.resetArmedAt = -1L;
        rebuildWidgets();
    }

    private void enemySound(boolean enemy) {
        net.minecraft.client.Minecraft.getInstance().getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(enemy
                        ? net.minecraft.sounds.SoundEvents.RESPAWN_ANCHOR_CHARGE
                        : net.minecraft.sounds.SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), enemy ? 0.75F : 1.25F, 0.6F));
    }

    /** The screen's danger colours: the Advanced tab, and the enemy's anchors. */
    private boolean crimson() {
        return this.tab == ADVANCED || enemyPage;
    }

    /**
     * The switch between the player's anchors and the enemy's: two blades cross the panel, a
     * shockwave leaves the switch and a crimson (or violet) wash fades, in about half a second.
     */
    private void drawEnemySwitch(GuiGraphicsExtractor graphics, boolean motion, long now) {
        if (!motion || this.enemySwitchedAt < 0L) {
            return;
        }
        long since = now - this.enemySwitchedAt;
        if (since > 700_000_000L) {
            return;
        }
        AnchorsLayout.Rect panel = this.layout.panel;
        int color = enemyPage ? AnchorsTheme.CRIMSON_BRIGHT : AnchorsTheme.ACCENT_BRIGHT;
        float wash = 1.0F - progress(since, 360_000_000L);
        if (wash > 0.0F) {
            graphics.fill(panel.x(), panel.y(), panel.right(), panel.bottom(),
                    AnchorsTheme.withAlpha(enemyPage ? 0xA31234 : 0x7C3AED, Math.round(70 * wash * wash)));
        }
        AnchorsUi.slash(graphics, panel, progress(since, 420_000_000L), color);
        AnchorsUi.slash(graphics, panel, progress(since - 110_000_000L, 420_000_000L), AnchorsTheme.withAlpha(color, 170));
        int centerX = this.enemySwitchX + this.enemySwitchWidth / 2;
        int centerY = this.enemySwitchY + 6;
        for (int wave = 0; wave < 2; wave++) {
            float t = progress(since - wave * 120_000_000L, 520_000_000L);
            if (t <= 0.0F || t >= 1.0F) {
                continue;
            }
            int radius = Math.round(8 + AnchorsTheme.easeOutCubic(t) * Math.min(panel.width(), panel.height()) * 0.45F);
            AnchorsUi.ring(graphics, centerX, centerY, radius, 2 - wave, AnchorsTheme.withAlpha(color, Math.round(200 * (1.0F - t))));
        }
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
        graphics.fillGradient(0, 0, this.width, this.height, AnchorsTheme.VEIL_TOP, AnchorsTheme.VEIL_BOTTOM);
        Mc.extractDeferredSubtitles(this.minecraft);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
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
                    advanced ? AnchorsTheme.CRIMSON : AnchorsTheme.ACCENT_DEEP, 0.55F * intro, this.anchorCharge);
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
        } else {
            drawOptions(graphics, pointerX, pointerY, partialTick, now, motion, seconds);
            if (this.layout.showsPreview()) {
                drawPreview(graphics, pointerX, pointerY, intro, motion, seconds);
            }
        }
        drawFooterNote(graphics, intro, this.tab == ADVANCED);
        super.extractRenderState(graphics, pointerX, pointerY, partialTick);
        drawEnemySwitch(graphics, motion, now);
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
        if (this.warning != null) {
            this.warning.render(graphics, this.font, this.width, this.height, mouseX, mouseY);
            if (this.warning.done()) {
                this.warning = null;
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
        if (this.devWarning == null) {
            DevInspector.render(graphics, this.font, this.width, this.height, mouseX, mouseY,
                    "AnchorsScreen › " + TAB_KEYS[this.tab] + (this.tab == GLOW && enemyPage ? " › enemy" : ""),
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
        int accent = dev() ? AnchorsTheme.DEV_BLUE : advanced ? AnchorsTheme.CRIMSON : AnchorsTheme.ACCENT;
        AnchorsUi.halo(graphics, x, y, width, height, accent, 6, 0.6F * intro);
        AnchorsUi.panel(graphics, x, y, width, height,
                AnchorsTheme.fade(AnchorsTheme.PANEL_TOP, 0.3F + intro * 0.7F),
                AnchorsTheme.fade(AnchorsTheme.PANEL_BOTTOM, 0.3F + intro * 0.7F));
        AnchorsUi.roundedOutline(graphics, x, y, width, height, AnchorsTheme.fade(
                advanced ? 0xE0FF315C : AnchorsTheme.PANEL_BORDER, intro));
        DevInspector.node("Panel", "AnchorsScreen", panel.x(), panel.y(), panel.width(), panel.height(),
                "AnchorsLayout.fit(" + this.width + ", " + this.height + ")", "glass " + Integer.toHexString(AnchorsTheme.PANEL_TOP));
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
        for (int index = 0; index < AnchorsLayout.TAB_COUNT; index++) {
            AnchorsLayout.Rect rect = this.layout.tab(index);
            boolean selected = index == this.tab;
            boolean danger = index == ADVANCED || enemyPage && index == GLOW;
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
                // A live advanced option: a crimson ember; hollow while it is suspended on this server.
                float ember = motion ? 0.6F + 0.4F * AnchorsTheme.pulse(seconds, 1.4D) : 1.0F;
                int emberColor = AnchorsTheme.withAlpha(suspended ? 0x8A6A1030 : 0xFF315C, Math.round(255 * ember * intro));
                AnchorsUi.diamond(graphics, rect.right() - 5, rect.y() + 4, 2, emberColor);
            }
            DevInspector.node("Tab", TAB_KEYS[index], rect.x(), rect.y(), rect.width(), rect.height(),
                    "AnchorsLayout.tab(" + index + ")", selected ? "selected" : "", iconOnly ? "icon only" : "");
        }
    }

    /** Small icons drawn from pixels: sliders, the anchor, a flare, a wave, and the warning sign. */
    private static void drawTabIcon(GuiGraphicsExtractor graphics, int index, int x, int y, int size, int color) {
        int center = size / 2;
        switch (index) {
            case GENERAL -> {
                for (int line = 0; line < 3; line++) {
                    int ly = y + 1 + line * 3;
                    graphics.fill(x, ly, x + size, ly + 1, AnchorsTheme.fade(color, 0.55F));
                    int knob = x + (line == 1 ? size - 3 : line * 2 + 1);
                    graphics.fill(knob, ly - 1, knob + 2, ly + 2, color);
                }
            }
            case ANCHOR -> {
                AnchorsUi.outline(graphics, x, y, size, size, color);
                graphics.fill(x + 2, y + 2, x + size - 2, y + 4, AnchorsTheme.fade(color, 0.6F));
                for (int pip = 0; pip < 3; pip++) {
                    graphics.fill(x + 2 + pip * 2, y + size - 3, x + 3 + pip * 2, y + size - 2, color);
                }
            }
            case GLOW -> {
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
            case SOUNDS -> {
                int[] heights = {3, 6, 9, 5, 7};
                for (int bar = 0; bar < heights.length; bar++) {
                    int barX = x + bar * 2;
                    graphics.fill(barX, y + size - heights[bar], barX + 1, y + size, color);
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
            AnchorsUi.slash(graphics, viewport, slash, advanced ? AnchorsTheme.CRIMSON_BRIGHT : AnchorsTheme.ACCENT_BRIGHT);
        }

        if (this.maxScroll > 0) {
            int trackX = viewport.right() - 3;
            graphics.fill(trackX, viewport.y(), trackX + 2, viewport.bottom(), AnchorsTheme.SCROLL_TRACK);
            int thumbHeight = Math.max(12, viewport.height() * viewport.height() / Math.max(1, this.contentHeight));
            int thumbY = viewport.y() + Math.round((viewport.height() - thumbHeight) * (this.scroll / this.maxScroll));
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight,
                    advanced ? 0xE6FF6A86 : AnchorsTheme.SCROLL_THUMB);
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
        DevInspector.node("Banner", "advanced", x, y, width, this.bannerHeight, "ServerLock + AnchorsWarning (two stages)");
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
        if (this.tab == GLOW) {
            // The glow as it will look: its colour and strength around the anchor, and on the ground.
            AnchorsConfig.Glow glow = settings().glow;
            boolean enemy = enemyPage;
            int color = enemy ? settings().enemyGlow.color
                    : AnchorGlowRenderer.ownColor(Math.max(1, Math.round(this.anchorPreview.charge())));
            boolean on = enemy ? settings().enemyGlow.enabled && glow.enabled : glow.enabled;
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
        this.anchorPreview.lightOverride(this.tab == GLOW && enemyPage && settings().enemyGlow.enabled
                ? settings().enemyGlow.color : 0);
        this.anchorPreview.render(graphics, this.font, this.previewArt, mouseX, mouseY, motion, intro, this.previewHint);
        DevInspector.node("AnchorPreview", "3D anchor", this.previewArt.x(), this.previewArt.y(), this.previewArt.width(),
                this.previewArt.height(), "FallingBlockRenderState → GuiGraphics.entity", "block atlas: the skin shows here",
                "FullBrightLightEngine");

        int statsTop = preview.bottom() - STATS_HEIGHT - 8;
        AnchorsUi.energyLine(graphics, preview.x() + 8, preview.right() - 8, statsTop - 1,
                advanced ? AnchorsTheme.CRIMSON_BRIGHT : AnchorsTheme.ACCENT, motion ? seconds : 0.0D, intro);
        drawStats(graphics, preview.x() + 8, statsTop + 4, preview.width() - 16, intro, true);
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
            case GLOW -> {
                keys = new String[] {"tracked", "enemies", "predicted", "confirmed", "kept"};
                values = numbers(AnchorTracker.count(), AnchorTracker.enemyCount(), AnchorStats.predictedDetonations(),
                        AnchorStats.confirmedDetonations(), AnchorStats.keptDetonations());
            }
            case SOUNDS -> {
                keys = new String[] {"predicted", "confirmed", "held", "merged", "kept"};
                values = numbers(AnchorStats.predictedDetonations(), AnchorStats.confirmedDetonations(),
                        AnchorStats.heldClicks(), AnchorStats.mergedClicks(), AnchorStats.keptDetonations());
            }
            case ADVANCED -> {
                keys = new String[] {"chained", "instant", "merged", "dropped", "kept"};
                values = numbers(AnchorStats.chainedClicks(), AnchorStats.instantDetonations(), AnchorStats.mergedClicks(),
                        AnchorStats.droppedClicks(), AnchorStats.keptDetonations());
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
            String name = AnchorsUi.fit(this.font, Component.translatable("kohs_anchors.stats." + keys[index]).getString(),
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

    private void drawFooterNote(GuiGraphicsExtractor graphics, float intro, boolean advanced) {
        AnchorsLayout.Rect footer = this.layout.footer;
        int left = this.layout.resetButton.right() + 8;
        int right = this.layout.doneButton.x() - 8;
        boolean armed = this.resetArmedAt >= 0L && System.nanoTime() - this.resetArmedAt < RESET_ARM_NANOS;
        String note = Component.translatable(armed ? "kohs_anchors.footer.note.reset"
                : advanced ? "kohs_anchors.footer.note.advanced" : this.tab == ANCHOR ? "kohs_anchors.footer.note.anchor"
                : "kohs_anchors.footer.note").getString();
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
        if (this.guardWarning != null) {
            return this.guardWarning.mouseClicked(this.font, this.width, this.height, mouseX, mouseY, button);
        }
        if (this.crystalModal != null) {
            return this.crystalModal.mouseClicked(this.width, this.height, mouseX, mouseY, button);
        }
        if (this.devWarning != null) {
            return this.devWarning.mouseClicked(this.width, this.height, mouseX, mouseY, button);
        }
        if (this.warning != null) {
            return this.warning.mouseClicked(this.width, this.height, mouseX, mouseY, button);
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
        if (button == 0 && this.enemySwitchWidth > 0 && mouseX >= this.enemySwitchX
                && mouseX < this.enemySwitchX + this.enemySwitchWidth && mouseY >= this.enemySwitchY
                && mouseY < this.enemySwitchY + 13) {
            toggleEnemyAnchors();
            return true;
        }
        for (int index = 0; index < AnchorsLayout.TAB_COUNT; index++) {
            if (button == 0 && this.layout.tab(index).contains(mouseX, mouseY)) {
                selectTab(index);
                return true;
            }
        }
        if (this.tab == ANCHOR && this.workshop != null) {
            if (this.workshop.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        } else {
            if (this.enemyPicker != null && this.enemyPicker.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            if (button == 0) {
                for (AnchorRow row : this.rows) {
                    if (row instanceof AnchorSliderRow slider && slider.trackContains(mouseX, mouseY)) {
                        this.draggingSlider = slider;
                        slider.beginDrag(mouseX);
                        return true;
                    }
                }
            }
            if (this.anchorPreview.mouseClicked(this.previewArt, mouseX, mouseY, button, doubleClick)) {
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.warning != null || this.devWarning != null || this.crystalModal != null || this.soundPicker != null
                || this.guardWarning != null) {
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
        if (this.warning != null) {
            return this.warning.mouseReleased();
        }
        if (this.devWarning != null || this.crystalModal != null || this.soundPicker != null || this.guardWarning != null) {
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
        if (this.warning != null || this.devWarning != null || this.crystalModal != null || this.popover != null
                || this.guardWarning != null) {
            return true;
        }
        if (this.soundPicker != null) {
            return this.soundPicker.mouseScrolled(verticalAmount);
        }
        if (this.tab == ANCHOR && this.workshop != null) {
            return this.workshop.mouseScrolled(mouseX, mouseY, verticalAmount);
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
        int key = event.key();
        if (this.guardWarning != null) {
            return this.guardWarning.keyPressed(key);
        }
        if (this.crystalModal != null) {
            return this.crystalModal.keyPressed(key);
        }
        if (this.devWarning != null) {
            return this.devWarning.keyPressed(key);
        }
        if (this.warning != null) {
            return this.warning.keyPressed(key);
        }
        if (this.soundPicker != null) {
            return this.soundPicker.keyPressed(key);
        }
        if (this.popover != null) {
            if (!this.popover.keyPressed(key) && (key == 256 || key == 257 || key == 335)) {
                this.popover = null;
                AnchorsConfig.save();
            }
            return true;
        }
        if (this.enemyPicker != null && this.enemyPicker.keyPressed(key)) {
            return true;
        }
        if (this.tab == ANCHOR && this.workshop != null
                && this.workshop.keyPressed(key, (event.modifiers() & 2) != 0)) {
            return true;
        }
        if (key == 301 && dev()) {
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
            case GLOW -> {
                settings.glow = defaults.glow;
                settings.enemyGlow = defaults.enemyGlow;
            }
            case SOUNDS -> {
                settings.chargeSound = defaults.chargeSound;
                settings.explosionSound = defaults.explosionSound;
            }
            default -> {
                settings.fastChain = false;
                settings.instantDetonation = false;
                settings.devMode = false;
            }
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
        return this.warning != null || this.devWarning != null || this.crystalModal != null || this.soundPicker != null
                || this.popover != null || this.guardWarning != null;
    }

    private void saveSettings() {
        if (this.guardWarning != null) {
            // Leaving under the warning: its clip stops and lets go of its texture.
            this.guardWarning.close();
            this.guardWarning = null;
        }
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
