package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.skin.AnchorTextures;
import dev.zymekoh.kohsanchors.skin.AnchorVariant;
import dev.zymekoh.kohsanchors.skin.AtlasSkin;
import dev.zymekoh.kohsanchors.skin.ColorMath;
import dev.zymekoh.kohsanchors.skin.SkinComposer;
import dev.zymekoh.kohsanchors.skin.SkinPaint;
import dev.zymekoh.kohsanchors.sound.AnchorSounds;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * The anchor custom tab: the anchor on a stage in the middle, modes and tools on the left, layers
 * and colour on the right.
 *
 * <p><b>Basic colours</b> recolour a whole layer at once, the frame or the glow, with a strength;
 * the charge lights can take a colour each. <b>Pixel editor</b> makes the anchor bigger, detects
 * the resource pack's resolution (16x, 32x, 64x...) and lays the reference grid over the layer
 * being edited, while the other layer dims with a heartbeat. The brush only paints pixels of the
 * edited layer, so the other one cannot be spoiled by a slip. The five charge states can be shown
 * under the anchor at any time.</p>
 *
 * <p>Every change reaches the anchors in the world on the next tick. Paint is saved when the
 * screen closes.</p>
 */
final class AnchorWorkshop {
    private static final long ENTER_NANOS = 520_000_000L;
    private static final long SCAN_NANOS = 700_000_000L;
    private static final int TARGET_FRAME = 0;
    private static final int TARGET_GLOW = 1;

    enum Mode { COLORS, PIXELS }

    enum Tool { BRUSH, ERASER, PICKER, FILL, LAYER }

    private final AnchorsScreen screen;
    /** The enemy's workshop edits their skin and paint; the player's, their own. */
    private final boolean enemy;
    private final AnchorCube cube;
    private final ColorPicker picker;
    private final Deque<List<Change>> undo = new ArrayDeque<>();
    private final Deque<List<Change>> redo = new ArrayDeque<>();
    private final List<Hit> hits = new ArrayList<>();
    private List<Change> stroke;

    private AnchorsLayout.Workshop geometry;
    private Mode mode = Mode.COLORS;
    private Tool tool = Tool.BRUSH;
    /** 0 frame, 1 glow, 2 to 5 the charge lights. */
    private int target = TARGET_GLOW;
    private int brushColor = 0xFFB14DFF;
    private long enteredAt = System.nanoTime();
    private long modeChangedAt = -1L;
    private float enterFromX;
    private float enterFromY;
    private float modeBlend;
    private float zoom = 1.0F;
    private float shownZoom = 1.0F;
    private int[] hover;
    private boolean painting;
    private boolean rotating;
    private boolean strengthDragging;
    private int lastPaintedFace = -1;
    private int lastPaintedIndex = -1;
    private long lastFrame = System.nanoTime();
    private int strengthX;
    private int strengthY;
    private int strengthWidth;

    AnchorWorkshop(AnchorsScreen screen) {
        this(screen, false);
    }

    AnchorWorkshop(AnchorsScreen screen, boolean enemy) {
        this.screen = screen;
        this.enemy = enemy;
        this.cube = new AnchorCube(enemy ? "workshop_enemy" : "workshop", enemy);
        if (enemy) {
            this.brushColor = 0xFFFF3B4E;
        }
        this.picker = new ColorPicker(this::pickerColor, this::setPickerColor);
    }

    /** Where the anchor flies in from: the preview column of the tab before, or the stage's centre. */
    void enter(float fromX, float fromY) {
        this.enteredAt = System.nanoTime();
        this.enterFromX = fromX;
        this.enterFromY = fromY;
    }

    void layout(AnchorsLayout.Rect body) {
        this.geometry = AnchorsLayout.workshop(body);
    }

    void close() {
        this.cube.close();
        SkinPaint.saveAll();
    }

    // ------------------------------------------------------------------------------------------
    // Colour targets
    // ------------------------------------------------------------------------------------------

    boolean enemy() {
        return this.enemy;
    }

    private AnchorsConfig.Skin skin() {
        return this.enemy ? AnchorsConfig.settings().enemySkin : AnchorsConfig.settings().skin;
    }

    private int pickerColor() {
        if (this.mode == Mode.PIXELS) {
            return this.brushColor;
        }
        AnchorsConfig.Skin skin = skin();
        return switch (this.target) {
            case TARGET_FRAME -> skin.frameColor;
            case TARGET_GLOW -> skin.glowColor;
            default -> skin.charge[this.target - 2];
        };
    }

    private void setPickerColor(int color) {
        if (this.mode == Mode.PIXELS) {
            this.brushColor = color | 0xFF000000;
            return;
        }
        AnchorsConfig.Skin skin = skin();
        switch (this.target) {
            case TARGET_FRAME -> {
                skin.frameColor = color | 0xFF000000;
                if (skin.frameStrength == 0) {
                    skin.frameStrength = 100;
                }
            }
            case TARGET_GLOW -> {
                skin.glowColor = color | 0xFF000000;
                if (skin.glowStrength == 0) {
                    skin.glowStrength = 100;
                }
            }
            default -> {
                skin.charge[this.target - 2] = color | 0xFF000000;
                skin.chargeColors = true;
            }
        }
        skin.enabled = true;
        AnchorsConfig.changed();
    }

    /** The layer the pixel editor works on: the glow for the glow and the charge lights. */
    private byte editingLayer() {
        return this.target == TARGET_FRAME ? AnchorTextures.FRAME : AnchorTextures.GLOW;
    }

    // ------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------

    void render(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, boolean motion, double seconds,
            float intro) {
        if (this.geometry == null) {
            return;
        }
        long now = System.nanoTime();
        float frameMillis = Math.min(50.0F, (now - this.lastFrame) / 1_000_000.0F);
        this.lastFrame = now;
        float response = 1.0F - (float) Math.exp(-frameMillis / 80.0F);
        float enter = motion ? AnchorsTheme.clamp01((now - this.enteredAt) / (float) ENTER_NANOS) : 1.0F;
        float modeTarget = this.mode == Mode.PIXELS ? 1.0F : 0.0F;
        this.modeBlend += (modeTarget - this.modeBlend) * (motion ? response * 1.3F : 1.0F);
        this.shownZoom += (this.zoom - this.shownZoom) * (motion ? response * 1.5F : 1.0F);
        this.hits.clear();

        AnchorsLayout.Rect stage = this.geometry.stage();
        drawStage(graphics, font, mouseX, mouseY, motion, seconds, intro, enter, now);
        drawLeftRail(graphics, font, mouseX, mouseY, enter, intro);
        drawRightRail(graphics, font, mouseX, mouseY, enter, intro, seconds, motion);
        DevInspector.node("AnchorWorkshop", "stage", stage.x(), stage.y(), stage.width(), stage.height(),
                "class AnchorWorkshop · AnchorCube (affine faces)", "mode " + this.mode + " · tool " + this.tool,
                "skin: AtlasSkin → TextureAtlas sprites", "mixins TextureAtlasMixin, SpriteContentsMixin");
    }

    private void drawStage(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, boolean motion, double seconds,
            float intro, float enter, long now) {
        AnchorsLayout.Rect stage = this.geometry.stage();
        AnchorsUi.panel(graphics, stage.x(), stage.y(), stage.width(), stage.height(), AnchorsTheme.fade(0x6A12091F, intro),
                AnchorsTheme.fade(0x5A08050D, intro));
        AnchorsUi.roundedOutline(graphics, stage.x(), stage.y(), stage.width(), stage.height(),
                AnchorsTheme.fade(AnchorsTheme.CARD_BORDER, intro));
        AnchorsUi.bladeCorners(graphics, stage.x(), stage.y(), stage.width(), stage.height(), 6,
                AnchorsTheme.fade(0xC0C084FC, intro));

        int pipsHeight = 18;
        // The pixel editor keeps a band at the top for the resolution badge and the layer.
        float topBand = 17.0F * this.modeBlend;
        float centerX = stage.centerX();
        float centerY = stage.y() + topBand + (stage.height() - pipsHeight - topBand) / 2.0F;
        float moved = AnchorsTheme.easeOutCubic(enter);
        float cubeX = this.enterFromX + (centerX - this.enterFromX) * moved;
        float cubeY = this.enterFromY + (centerY - this.enterFromY) * moved;
        int room = Math.min(stage.width(), stage.height() - pipsHeight);
        // Seen from its resting angle the anchor is about 1.42 blocks wide and 1.55 tall: the
        // pixel editor fits that into the stage, so the whole anchor shows until it is zoomed.
        float fit = Math.min((stage.width() - 10) / 1.45F, (stage.height() - pipsHeight - topBand - 8) / 1.58F);
        float basic = Math.min(room * 0.46F, fit);
        float pixels = Math.max(8.0F, fit) * this.shownZoom;
        float scale = (basic + (pixels - basic) * this.modeBlend) * (0.55F + 0.45F * AnchorsTheme.easeOutBack(enter));

        // The ritual circle and the light, behind the anchor.
        float charge = this.cube.charge();
        if (motion) {
            AnchorsUi.sigil(graphics, Math.round(cubeX), Math.round(cubeY + scale * 0.15F), Math.round(room * 0.47F),
                    seconds * 1.4D, AnchorsTheme.ACCENT, 0.7F * intro * (1.0F - this.modeBlend * 0.6F), charge);
        }
        int glow = skin().enabled ? skin().glowColor
                : this.enemy ? AnchorsConfig.settings().enemyGlow.color : AnchorsTheme.PORTAL;
        AnchorsUi.glowEllipse(graphics, Math.round(cubeX), Math.round(cubeY), Math.round(scale * 0.95F),
                Math.round(scale * 0.85F), glow & 0xFFFFFF, (0.25F + 0.15F * charge) * intro);
        AnchorsUi.ellipse(graphics, Math.round(cubeX), Math.round(cubeY + scale * 0.62F), Math.round(scale * 0.55F),
                Math.max(2, Math.round(scale * 0.1F)), AnchorsTheme.withAlpha(0x0A0412, Math.round(150 * intro)));

        graphics.enableScissor(stage.x() + 1, stage.y() + 1, stage.right() - 1, stage.bottom() - 1);
        if (!this.cube.prepare(motion)) {
            graphics.disableScissor();
            String text = Component.translatable("kohs_anchors.workshop.unavailable").getString();
            AnchorsUi.label(graphics, font, text, stage.centerX() - font.width(text) / 2, stage.centerY(),
                    AnchorsTheme.TEXT_MUTED, false);
            return;
        }
        this.cube.layout(cubeX, cubeY, scale);
        boolean pixelMode = this.mode == Mode.PIXELS;
        float dim = 0.0F;
        float lift = 0.0F;
        if (pixelMode) {
            // A heartbeat: two quick beats, then rest.
            double beat = (seconds % 1.15D);
            float pulse = (float) Math.max(Math.exp(-Math.pow((beat - 0.12D) / 0.07D, 2.0D)),
                    0.7D * Math.exp(-Math.pow((beat - 0.36D) / 0.08D, 2.0D)));
            dim = (0.45F + 0.3F * (motion ? pulse : 0.5F)) * this.modeBlend;
            lift = (0.02F + 0.06F * (motion ? pulse : 0.5F)) * this.modeBlend;
        }
        this.cube.draw(graphics, intro, pixelMode ? editingLayer() : 0, dim, lift);
        if (this.modeBlend > 0.05F) {
            float scan = motion ? AnchorsTheme.clamp01((now - this.modeChangedAt) / (float) SCAN_NANOS) : 1.0F;
            this.cube.drawGrid(graphics, this.modeBlend * intro * scan, this.target == TARGET_FRAME ? 0xE9D5FF : 0xFFD6F0,
                    editingLayer());
            if (pixelMode && scan < 1.0F) {
                // Scanning the resource pack: a band of light sweeps the stage once.
                int bandY = stage.y() + Math.round(stage.height() * scan);
                graphics.fillGradient(stage.x(), bandY - 10, stage.right(), bandY, 0x0052F2FF, 0x6052F2FF);
                graphics.fill(stage.x(), bandY, stage.right(), bandY + 1, 0xE052F2FF);
            }
        }
        this.hover = pixelMode && this.geometry.stage().contains(mouseX, mouseY) ? this.cube.pick(mouseX, mouseY) : null;
        if (this.hover != null) {
            Direction face = Direction.values()[this.hover[0]];
            // The brush, the eraser and the fill only reach the edited layer: elsewhere the cursor dims.
            boolean reachable = this.tool == Tool.PICKER || this.tool == Tool.LAYER
                    || this.cube.layerAt(face, this.hover[1], this.hover[2]) == editingLayer();
            int wash = !reachable ? 0x20FFFFFF : this.tool == Tool.BRUSH || this.tool == Tool.FILL
                    ? AnchorsTheme.withAlpha(this.brushColor & 0xFFFFFF, 170) : 0x40FFFFFF;
            this.cube.drawPixelOutline(graphics, face, this.hover[1], this.hover[2], reachable ? 0xFFFFF7FF : 0xFF6B5A7A,
                    wash);
        }
        graphics.disableScissor();

        // Resolution badge, then the pixel under the pointer.
        if (this.modeBlend > 0.3F) {
            float scan = motion ? AnchorsTheme.clamp01((now - this.modeChangedAt) / (float) SCAN_NANOS) : 1.0F;
            String badge = scan < 1.0F ? Component.translatable("kohs_anchors.workshop.scanning").getString()
                    : Component.translatable("kohs_anchors.workshop.detected", this.cube.resolution() + "x").getString();
            int badgeWidth = font.width(badge) + 10;
            int bx = stage.x() + 5;
            int by = stage.y() + 5;
            AnchorsUi.panel(graphics, bx, by, badgeWidth, 13, 0xD0102040, 0xD0081020);
            AnchorsUi.roundedOutline(graphics, bx, by, badgeWidth, 13, AnchorsTheme.CYAN);
            AnchorsUi.label(graphics, font, badge, bx + 5, by + 3, 0xFFB8F8FF, false);
            DevInspector.node("Badge", "resolution", bx, by, badgeWidth, 13,
                    "AnchorTextures.resolution() = " + this.cube.resolution(), "read from the active resource packs");
            String layer = Component.translatable(this.target == TARGET_FRAME ? "kohs_anchors.workshop.layer.frame"
                    : "kohs_anchors.workshop.layer.glow").getString().toUpperCase(Locale.ROOT);
            String editing = Component.translatable("kohs_anchors.workshop.editing", layer).getString();
            // The layer on the right of the same band, the pixel under the pointer in the bottom corner.
            String shownEditing = AnchorsUi.fit(font, editing, Math.max(20, stage.width() - badgeWidth - 18));
            AnchorsUi.label(graphics, font, shownEditing, stage.right() - 6 - font.width(shownEditing), by + 3,
                    AnchorsTheme.fade(AnchorsTheme.ACCENT_BRIGHT, this.modeBlend), true);
            if (this.hover != null) {
                String where = faceName(Direction.values()[this.hover[0]]) + " " + this.hover[1] + "," + this.hover[2];
                int pipsRight = stage.centerX() + (5 * 11 + 4 * 5) / 2;
                if (stage.right() - 6 - font.width(where) > pipsRight + 4) {
                    AnchorsUi.label(graphics, font, where, stage.right() - 6 - font.width(where),
                            stage.bottom() - pipsHeight + 5, AnchorsTheme.TEXT_MUTED, false);
                }
            }
        }

        // The charge states, 0 to 4, under the anchor.
        int pipY = stage.bottom() - pipsHeight + 3;
        int pipSize = 11;
        int total = 5 * pipSize + 4 * 5;
        int pipX = stage.centerX() - total / 2;
        String chargeLabel = Component.translatable("kohs_anchors.workshop.charge").getString();
        if (stage.width() > total + font.width(chargeLabel) + 14) {
            AnchorsUi.label(graphics, font, chargeLabel, pipX - font.width(chargeLabel) - 6, pipY + 2,
                    AnchorsTheme.fade(AnchorsTheme.TEXT_DIM, intro), false);
        }
        for (int state = 0; state <= 4; state++) {
            int x = pipX + state * (pipSize + 5);
            boolean selected = state == this.cube.charge();
            boolean over = mouseX >= x && mouseX < x + pipSize && mouseY >= pipY && mouseY < pipY + pipSize;
            int fill = selected ? (state == 0 ? 0xFF3A1F5C : 0xFFFFC46B) : over ? 0xFF4C2380 : 0xFF25123F;
            AnchorsUi.diamond(graphics, x + pipSize / 2, pipY + pipSize / 2, pipSize / 2, AnchorsTheme.fade(fill, intro));
            String digit = Integer.toString(state);
            AnchorsUi.label(graphics, font, digit, x + pipSize / 2 - font.width(digit) / 2 + 1, pipY + 2,
                    AnchorsTheme.fade(selected ? 0xFF1A0308 : AnchorsTheme.TEXT_MUTED, intro), false);
            final int chosen = state;
            this.hits.add(new Hit(x, pipY, pipSize, pipSize, () -> {
                this.cube.setCharge(chosen);
                if (chosen > 0) {
                    AnchorSounds.previewCharge(0.8F + chosen * 0.1F);
                }
            }));
            DevInspector.node("ChargePip", "charge " + state, x, pipY, pipSize, pipSize,
                    "AnchorVariant.side(" + state + ") / top(" + state + ")");
        }
    }

    private void drawLeftRail(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, float enter, float intro) {
        AnchorsLayout.Rect rail = this.geometry.left();
        boolean narrow = this.geometry.narrow();
        float slide = AnchorsTheme.easeOutCubic(AnchorsTheme.clamp01(enter * 1.3F - 0.2F));
        int offset = Math.round((1.0F - slide) * -(rail.width() + 12));
        float alpha = intro * slide;
        int y = rail.y();
        int height = narrow ? 18 : 20;
        y = railButton(graphics, font, rail.x() + offset, y, rail.width(), height, "kohs_anchors.workshop.mode.colors", "C",
                this.mode == Mode.COLORS, false, mouseX, mouseY, alpha, () -> setMode(Mode.COLORS), "mode COLORS",
                "Skin.frameColor / glowColor / charge[] + strength");
        y = railButton(graphics, font, rail.x() + offset, y, rail.width(), height, "kohs_anchors.workshop.mode.pixels", "P",
                this.mode == Mode.PIXELS, false, mouseX, mouseY, alpha, () -> setMode(Mode.PIXELS), "mode PIXELS",
                "SkinPaint grids · config/kohs_anchors/skin/*.png");
        y += 4;
        if (this.mode != Mode.PIXELS) {
            String hint = Component.translatable("kohs_anchors.workshop.hint.colors").getString();
            if (!narrow) {
                for (var line : font.split(Component.literal(hint), rail.width() - 4)) {
                    if (y + 10 > rail.bottom()) {
                        break;
                    }
                    AnchorsUi.line(graphics, font, line, rail.x() + offset + 2, y, AnchorsTheme.fade(AnchorsTheme.TEXT_DIM, alpha));
                    y += 10;
                }
            }
            return;
        }
        Tool[] tools = Tool.values();
        String[] keys = {"brush", "eraser", "picker", "fill", "layer"};
        String[] icons = {"B", "E", "I", "F", "L"};
        for (int index = 0; index < tools.length; index++) {
            if (y + height > rail.bottom()) {
                return;
            }
            Tool option = tools[index];
            y = railButton(graphics, font, rail.x() + offset, y, rail.width(), height, "kohs_anchors.workshop.tool." + keys[index],
                    icons[index], this.tool == option, false, mouseX, mouseY, alpha, () -> this.tool = option,
                    "tool " + option, toolDetail(option));
        }
        y += 3;
        if (y + height <= rail.bottom()) {
            y = railButton(graphics, font, rail.x() + offset, y, rail.width(), height, "kohs_anchors.workshop.undo", "↶",
                    false, false, mouseX, mouseY, alpha * (this.undo.isEmpty() ? 0.5F : 1.0F), this::undo, "undo",
                    this.undo.size() + " strokes");
        }
        if (y + height <= rail.bottom()) {
            y = railButton(graphics, font, rail.x() + offset, y, rail.width(), height, "kohs_anchors.workshop.redo", "↷",
                    false, false, mouseX, mouseY, alpha * (this.redo.isEmpty() ? 0.5F : 1.0F), this::redo, "redo",
                    this.redo.size() + " strokes");
        }
        if (y + height <= rail.bottom()) {
            railButton(graphics, font, rail.x() + offset, y, rail.width(), height, "kohs_anchors.workshop.clear", "X", false,
                    true, mouseX, mouseY, alpha, this::clearPaint, "clear", "SkinPaint.clear(this.enemy, resolution)");
        }
    }

    private static String toolDetail(Tool tool) {
        return switch (tool) {
            case BRUSH -> "Grid.setPaint(layer, index, color): edited layer only";
            case ERASER -> "Grid.setPaint(layer, index, 0)";
            case PICKER -> "AtlasSkin.composed(variant, enemy)[index] → brush";
            case FILL -> "every pixel of the edited layer on this face";
            case LAYER -> "Grid.setLayer(index, TO_FRAME / TO_GLOW)";
        };
    }

    private int railButton(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height, String key,
            String icon, boolean selected, boolean danger, int mouseX, int mouseY, float alpha, Runnable action,
            String inspectName, String detail) {
        boolean over = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        String text = this.geometry.narrow() ? icon : Component.translatable(key).getString();
        AnchorsButton.draw(graphics, x, y, width, height, text, selected, danger, over ? 1.0F : selected ? 0.55F : 0.0F,
                0.0F, alpha, -1.0F);
        if (selected) {
            graphics.fill(x + 2, y + 3, x + 4, y + height - 3, AnchorsTheme.fade(AnchorsTheme.ACCENT_BRIGHT, alpha));
        }
        this.hits.add(new Hit(x, y, width, height, action));
        DevInspector.node("RailButton", inspectName, x, y, width, height, detail);
        return y + height + 3;
    }

    private void drawRightRail(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, float enter, float intro,
            double seconds, boolean motion) {
        AnchorsLayout.Rect rail = this.geometry.right();
        float slide = AnchorsTheme.easeOutCubic(AnchorsTheme.clamp01(enter * 1.3F - 0.3F));
        int offset = Math.round((1.0F - slide) * (rail.width() + 12));
        float alpha = intro * slide;
        int x = rail.x() + offset;
        int width = rail.width();
        int y = rail.y();
        AnchorsConfig.Skin skin = skin();

        // The skin's master switch.
        String title = Component.translatable(this.enemy ? "kohs_anchors.workshop.enemy_skin" : "kohs_anchors.workshop.skin")
                .getString().toUpperCase(Locale.ROOT);
        AnchorsUi.label(graphics, font, AnchorsUi.fit(font, title, width - 34), x + 2, y + 2,
                AnchorsTheme.fade(AnchorsTheme.SECTION, alpha), false);
        AnchorSwitchRow.drawSwitch(graphics, x + width - AnchorSwitchRow.SWITCH_WIDTH - 1, y + 1, alpha,
                skin.enabled ? 1.0F : 0.0F, false);
        this.hits.add(new Hit(x, y, width, 12, () -> {
            skin.enabled = !skin.enabled;
            AnchorsConfig.changed();
        }));
        DevInspector.node("Switch", this.enemy ? "enemySkin.enabled" : "skin.enabled", x, y, width, 12,
                (this.enemy ? "AnchorsConfig.enemySkin.enabled = " : "AnchorsConfig.Skin.enabled = ") + skin.enabled,
                this.enemy ? "EnemySkinRenderer: drawn over the ENEMY anchors (textures enemy_skin), paint in skin/enemy/"
                        : "AtlasSkin.tick → AtlasWriter.write / writeFrame");
        y += 16;

        // Layers: frame and glow, then the charge lights.
        int chipWidth = (width - 3) / 2;
        y = chip(graphics, font, x, y, chipWidth, "kohs_anchors.workshop.layer.frame", this.target == TARGET_FRAME, mouseX,
                mouseY, alpha, () -> this.target = TARGET_FRAME, skin.frameColor, "layer FRAME");
        chip(graphics, font, x + chipWidth + 3, y - 15, chipWidth, "kohs_anchors.workshop.layer.glow",
                this.target != TARGET_FRAME, mouseX, mouseY, alpha, () -> this.target = TARGET_GLOW, skin.glowColor,
                "layer GLOW");
        if (this.mode == Mode.COLORS) {
            // The charge lights, one swatch each; the first click on one turns per-charge colours on.
            int swatch = Math.min(18, (width - 30) / 4);
            String label = Component.translatable("kohs_anchors.workshop.charges").getString();
            AnchorsUi.label(graphics, font, AnchorsUi.fit(font, label, width - swatch * 4 - 14), x + 2, y + 3,
                    AnchorsTheme.fade(skin.chargeColors ? AnchorsTheme.TEXT : AnchorsTheme.TEXT_DIM, alpha), false);
            for (int index = 0; index < 4; index++) {
                int sx = x + width - (4 - index) * (swatch + 2);
                boolean selected = this.target == 2 + index;
                boolean over = mouseX >= sx && mouseX < sx + swatch && mouseY >= y && mouseY < y + 12;
                AnchorsUi.swatch(graphics, sx, y + 1, swatch, 10, skin.chargeColors ? skin.charge[index] : 0xFF3A2A4A,
                        alpha, selected ? 1.0F : over ? 0.6F : 0.0F);
                final int charge = index;
                this.hits.add(new Hit(sx, y, swatch, 12, () -> {
                    this.target = 2 + charge;
                    this.cube.setCharge(Math.max(this.cube.charge(), charge + 1));
                }));
                DevInspector.node("Swatch", "charge light " + (index + 1), sx, y + 1, swatch, 10,
                        "Skin.charge[" + index + "] = " + ColorMath.hex(skin.charge[index]),
                        "AnchorTextures.chargeLight: pixels lit from charge " + (index + 1));
            }
            y += 16;
        }

        // Bottom buttons, reserved first so the picker fits between.
        int buttonHeight = 14;
        int bottom = rail.bottom();
        int buttonY = bottom - buttonHeight;
        int half = (width - 3) / 2;
        boolean overCrystal = mouseX >= x && mouseX < x + half && mouseY >= buttonY && mouseY < buttonY + buttonHeight;
        if (this.enemy) {
            // The enemy's workshop starts from the player's colours instead of Crystal Tweaks'.
            AnchorsButton.draw(graphics, x, buttonY, half, buttonHeight,
                    Component.translatable("kohs_anchors.workshop.copy_own").getString(), false,
                    false, overCrystal ? 1.0F : 0.0F, 0.0F, alpha, -1.0F);
            this.hits.add(new Hit(x, buttonY, half, buttonHeight, this::copyOwnColours));
            DevInspector.node("Button", "copy own colours", x, buttonY, half, buttonHeight,
                    "enemySkin ← skin: colours and strengths (paint stays apart)");
        } else {
            AnchorsButton.draw(graphics, x, buttonY, half, buttonHeight,
                    Component.translatable("kohs_anchors.workshop.crystal").getString(), AnchorsConfig.settings().crystalColors,
                    false, overCrystal ? 1.0F : 0.0F, 0.0F, alpha, -1.0F);
            this.hits.add(new Hit(x, buttonY, half, buttonHeight, this.screen::openCrystalColors));
            DevInspector.node("Button", "crystal colours", x, buttonY, half, buttonHeight,
                    "CrystalPalette.read() ← config/crystal_tweaks.json", "CrystalPalette.map → Skin + Glow");
        }
        boolean overReset = mouseX >= x + half + 3 && mouseX < x + width && mouseY >= buttonY && mouseY < buttonY + buttonHeight;
        AnchorsButton.draw(graphics, x + half + 3, buttonY, width - half - 3, buttonHeight,
                Component.translatable("kohs_anchors.workshop.reset").getString(), false, true,
                overReset ? 1.0F : 0.0F, 0.0F, alpha, -1.0F);
        this.hits.add(new Hit(x + half + 3, buttonY, width - half - 3, buttonHeight, this::resetSkin));
        DevInspector.node("Button", "reset skin", x + half + 3, buttonY, width - half - 3, buttonHeight,
                "Skin = new Skin() (paint kept)");
        bottom = buttonY - 4;

        // Strength, for a whole layer.
        if (this.mode == Mode.COLORS && this.target <= TARGET_GLOW) {
            int strength = this.target == TARGET_FRAME ? skin.frameStrength : skin.glowStrength;
            int sliderY = bottom - 12;
            String label = Component.translatable("kohs_anchors.workshop.strength", strength + "%").getString();
            AnchorsUi.label(graphics, font, AnchorsUi.fit(font, label, width), x + 2, sliderY - 10,
                    AnchorsTheme.fade(AnchorsTheme.TEXT_MUTED, alpha), false);
            this.strengthX = x + 2;
            this.strengthY = sliderY;
            this.strengthWidth = width - 6;
            int middle = sliderY + 5;
            graphics.fill(this.strengthX, middle - 1, this.strengthX + this.strengthWidth, middle + 1,
                    AnchorsTheme.fade(0xFF2A1845, alpha));
            int knob = this.strengthX + Math.round(this.strengthWidth * strength / 100.0F);
            graphics.fillGradient(this.strengthX, middle - 1, knob, middle + 1, AnchorsTheme.fade(AnchorsTheme.ACCENT_DEEP, alpha),
                    AnchorsTheme.fade(AnchorsTheme.ACCENT_BRIGHT, alpha));
            AnchorsUi.diamond(graphics, knob, middle, 4, AnchorsTheme.fade(AnchorsTheme.ACCENT_BRIGHT, alpha));
            DevInspector.node("Slider", "strength", this.strengthX, sliderY, this.strengthWidth, 10,
                    (this.target == TARGET_FRAME ? "Skin.frameStrength" : "Skin.glowStrength") + " = " + strength,
                    "SkinComposer.recolour: OKLab, mixed in linear light");
            bottom = sliderY - 14;
        } else {
            this.strengthWidth = 0;
        }

        // The colour picker fills what is left.
        int pickerHeight = Math.max(ColorPicker.minHeight(), bottom - y - 2);
        this.picker.setArea(new AnchorsLayout.Rect(x, y + 2, width, Math.max(0, Math.min(pickerHeight, bottom - y - 2))));
        if (bottom - y >= ColorPicker.minHeight() - 10) {
            this.picker.render(graphics, font, mouseX, mouseY, alpha);
            AnchorsLayout.Rect area = this.picker.area();
            DevInspector.node("ColorPicker", this.mode == Mode.PIXELS ? "brush" : "target " + this.target, area.x(),
                    area.y(), area.width(), area.height(), "HSV square + hue bar + hex + presets",
                    "colour " + ColorMath.hex(pickerColor()));
        }
    }

    private int chip(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, String key, boolean selected,
            int mouseX, int mouseY, float alpha, Runnable action, int color, String inspectName) {
        boolean over = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 12;
        AnchorsUi.panel(graphics, x, y, width, 12, AnchorsTheme.fade(selected ? 0xE03A1668 : 0x801D0D32, alpha),
                AnchorsTheme.fade(selected ? 0xE01D0D32 : 0x7012091F, alpha));
        AnchorsUi.roundedOutline(graphics, x, y, width, 12, AnchorsTheme.fade(selected ? AnchorsTheme.ACCENT_BRIGHT
                : over ? AnchorsTheme.CARD_BORDER_HOVER : AnchorsTheme.CARD_BORDER, alpha));
        AnchorsUi.swatch(graphics, x + 3, y + 3, 6, 6, color, alpha, 0.0F);
        String text = AnchorsUi.fit(font, Component.translatable(key).getString().toUpperCase(Locale.ROOT), width - 14);
        AnchorsUi.label(graphics, font, text, x + 12, y + 2, AnchorsTheme.fade(selected ? AnchorsTheme.TITLE
                : AnchorsTheme.TEXT_MUTED, alpha), false);
        this.hits.add(new Hit(x, y, width, 12, action));
        DevInspector.node("LayerChip", inspectName, x, y, width, 12, "SkinComposer.layerOf(texture, grid, index)");
        return y + 15;
    }

    private static String faceName(Direction direction) {
        return direction == Direction.UP ? "top" : direction == Direction.DOWN ? "bottom" : "side";
    }

    // ------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.geometry == null) {
            return false;
        }
        if (this.picker.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == Keys.LEFT_BUTTON) {
            for (int index = this.hits.size() - 1; index >= 0; index--) {
                Hit hit = this.hits.get(index);
                if (hit.contains(mouseX, mouseY)) {
                    hit.action().run();
                    click();
                    return true;
                }
            }
            if (this.strengthWidth > 0 && mouseX >= this.strengthX - 3 && mouseX <= this.strengthX + this.strengthWidth + 3
                    && mouseY >= this.strengthY - 2 && mouseY <= this.strengthY + 11) {
                this.strengthDragging = true;
                dragStrength(mouseX);
                return true;
            }
        }
        AnchorsLayout.Rect stage = this.geometry.stage();
        if (!stage.contains(mouseX, mouseY)) {
            return false;
        }
        if (button == Keys.LEFT_BUTTON && this.mode == Mode.PIXELS) {
            int[] picked = this.cube.pick(mouseX, mouseY);
            if (picked != null) {
                this.painting = true;
                this.stroke = new ArrayList<>();
                this.lastPaintedFace = -1;
                this.lastPaintedIndex = -1;
                apply(picked);
                return true;
            }
        }
        if (button == Keys.LEFT_BUTTON || button == Keys.RIGHT_BUTTON) {
            this.rotating = true;
            return true;
        }
        return false;
    }

    boolean mouseDragged(double mouseX, double mouseY, double dragX, double dragY) {
        if (this.picker.mouseDragged(mouseX, mouseY)) {
            return true;
        }
        if (this.strengthDragging) {
            dragStrength(mouseX);
            return true;
        }
        if (this.painting) {
            int[] picked = this.cube.pick(mouseX, mouseY);
            if (picked != null && (this.tool == Tool.BRUSH || this.tool == Tool.ERASER)) {
                apply(picked);
            }
            return true;
        }
        if (this.rotating) {
            this.cube.rotate((float) dragX * 0.9F, (float) dragY * 0.9F);
            return true;
        }
        return false;
    }

    boolean mouseReleased() {
        boolean handled = this.picker.mouseReleased() || this.strengthDragging || this.painting || this.rotating;
        this.strengthDragging = false;
        if (this.painting) {
            this.painting = false;
            if (this.stroke != null && !this.stroke.isEmpty()) {
                this.undo.push(this.stroke);
                while (this.undo.size() > 64) {
                    this.undo.removeLast();
                }
                this.redo.clear();
            }
            this.stroke = null;
        }
        this.rotating = false;
        return handled;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (this.geometry == null || !this.geometry.stage().contains(mouseX, mouseY) || amount == 0.0D) {
            return false;
        }
        this.zoom = Math.max(0.7F, Math.min(3.2F, this.zoom * (amount > 0.0D ? 1.12F : 1.0F / 1.12F)));
        return true;
    }

    boolean keyPressed(int key, boolean control) {
        if (this.picker.keyPressed(key)) {
            return true;
        }
        if (control && key == Keys.Z) {
            undo();
            return true;
        }
        if (control && key == Keys.Y) {
            redo();
            return true;
        }
        return false;
    }

    boolean charTyped(char character) {
        return this.picker.charTyped(character);
    }

    boolean editingText() {
        return this.picker.editing();
    }

    private void dragStrength(double mouseX) {
        int value = (int) Math.round(Math.max(0.0D, Math.min(1.0D, (mouseX - this.strengthX) / Math.max(1, this.strengthWidth)))
                * 100.0D);
        AnchorsConfig.Skin skin = skin();
        if (this.target == TARGET_FRAME) {
            skin.frameStrength = value;
        } else {
            skin.glowStrength = value;
        }
        skin.enabled = true;
        AnchorsConfig.changed();
    }

    private void setMode(Mode next) {
        if (this.mode != next) {
            this.mode = next;
            this.modeChangedAt = System.nanoTime();
            if (next == Mode.PIXELS) {
                this.brushColor = this.target == TARGET_FRAME ? skin().frameColor : skin().glowColor;
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BEACON_POWER_SELECT, 1.6F, 0.35F));
            }
        }
    }

    private void apply(int[] picked) {
        Direction direction = Direction.values()[picked[0]];
        SkinComposer composer = AtlasSkin.composer();
        if (composer == null) {
            return;
        }
        int size = this.cube.resolution();
        AnchorVariant variant = direction == Direction.UP ? AnchorVariant.top(this.cube.charge())
                : direction == Direction.DOWN ? AnchorVariant.BOTTOM : AnchorVariant.side(this.cube.charge());
        AnchorTextures.Texture texture = composer.textures().get(variant);
        if (texture.width != size || texture.height != size) {
            return;
        }
        SkinPaint.Grid grid = SkinPaint.grid(this.enemy, variant.face(), size);
        int index = picked[2] * size + picked[1];
        if (picked[0] == this.lastPaintedFace && index == this.lastPaintedIndex) {
            return;
        }
        this.lastPaintedFace = picked[0];
        this.lastPaintedIndex = index;
        byte editing = editingLayer();
        byte layer = composer.layerOf(texture, grid, index);
        switch (this.tool) {
            case BRUSH -> {
                if (layer == editing) {
                    paint(grid, editing, index, this.brushColor | 0xFF000000);
                }
            }
            case ERASER -> {
                if (layer == editing) {
                    paint(grid, editing, index, 0);
                }
            }
            case PICKER -> {
                int[] pixels = AtlasSkin.composed(variant, this.enemy);
                if (pixels != null) {
                    this.brushColor = pixels[index] | 0xFF000000;
                    ColorPicker.remember(this.brushColor);
                    this.tool = Tool.BRUSH;
                }
            }
            case FILL -> {
                for (int pixel = 0; pixel < grid.frame.length; pixel++) {
                    if (composer.layerOf(texture, grid, pixel) == editing) {
                        paint(grid, editing, pixel, this.brushColor | 0xFF000000);
                    }
                }
            }
            case LAYER -> {
                byte before = grid.layer[index];
                byte after = layer == AnchorTextures.FRAME ? SkinPaint.TO_GLOW : SkinPaint.TO_FRAME;
                this.stroke.add(new Change(grid, (byte) -1, index, before, after));
                grid.setLayer(index, after);
            }
        }
        if (this.tool != Tool.PICKER && !skin().enabled) {
            skin().enabled = true;
            AnchorsConfig.changed();
        }
    }

    private void paint(SkinPaint.Grid grid, byte layer, int index, int color) {
        int before = grid.paint(layer)[index];
        if (before == color) {
            return;
        }
        this.stroke.add(new Change(grid, layer, index, before, color));
        grid.setPaint(layer, index, color);
    }

    private void undo() {
        List<Change> changes = this.undo.poll();
        if (changes == null) {
            return;
        }
        for (int index = changes.size() - 1; index >= 0; index--) {
            changes.get(index).revert();
        }
        this.redo.push(changes);
    }

    private void redo() {
        List<Change> changes = this.redo.poll();
        if (changes == null) {
            return;
        }
        for (Change change : changes) {
            change.apply();
        }
        this.undo.push(changes);
    }

    private void clearPaint() {
        List<Change> changes = new ArrayList<>();
        for (AnchorVariant.Face face : AnchorVariant.Face.values()) {
            SkinPaint.Grid grid = SkinPaint.grid(this.enemy, face, this.cube.resolution());
            for (int index = 0; index < grid.frame.length; index++) {
                if (grid.frame[index] != 0) {
                    changes.add(new Change(grid, AnchorTextures.FRAME, index, grid.frame[index], 0));
                }
                if (grid.glow[index] != 0) {
                    changes.add(new Change(grid, AnchorTextures.GLOW, index, grid.glow[index], 0));
                }
                if (grid.layer[index] != SkinPaint.AUTO) {
                    changes.add(new Change(grid, (byte) -1, index, grid.layer[index], SkinPaint.AUTO));
                }
            }
        }
        for (Change change : changes) {
            change.apply();
        }
        if (!changes.isEmpty()) {
            this.undo.push(changes);
            this.redo.clear();
        }
    }

    private void resetSkin() {
        // Back to the resource pack's anchor; what was painted stays, the clear tool removes it.
        if (this.enemy) {
            AnchorsConfig.settings().enemySkin = AnchorsConfig.Skin.enemy();
        } else {
            AnchorsConfig.settings().skin = new AnchorsConfig.Skin();
        }
        AnchorsConfig.changed();
    }

    /** The player's colours, strengths and charge lights, onto the enemy's skin; switched on. */
    private void copyOwnColours() {
        AnchorsConfig.Skin own = AnchorsConfig.settings().skin;
        AnchorsConfig.Skin enemySkin = AnchorsConfig.settings().enemySkin;
        enemySkin.frameColor = own.frameColor;
        enemySkin.frameStrength = own.frameStrength;
        enemySkin.glowColor = own.glowColor;
        enemySkin.glowStrength = own.glowStrength;
        enemySkin.chargeColors = own.chargeColors;
        enemySkin.charge = own.charge.clone();
        enemySkin.enabled = true;
        AnchorsConfig.changed();
        click();
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 0.45F));
    }

    /** One pixel edit, reversible: a paint colour, or with {@code layer} -1 a layer move. */
    private record Change(SkinPaint.Grid grid, byte layer, int index, int before, int after) {
        void apply() {
            if (this.layer < 0) {
                this.grid.setLayer(this.index, (byte) this.after);
            } else {
                this.grid.setPaint(this.layer, this.index, this.after);
            }
        }

        void revert() {
            if (this.layer < 0) {
                this.grid.setLayer(this.index, (byte) this.before);
            } else {
                this.grid.setPaint(this.layer, this.index, this.before);
            }
        }
    }

    private record Hit(int x, int y, int width, int height, Runnable action) {
        boolean contains(double px, double py) {
            return px >= this.x && py >= this.y && px < this.x + this.width && py < this.y + this.height;
        }
    }
}
