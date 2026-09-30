package dev.zymekoh.kohsanchors.gui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.skin.AnchorTextures;
import dev.zymekoh.kohsanchors.skin.AnchorVariant;
import dev.zymekoh.kohsanchors.skin.AtlasSkin;
import dev.zymekoh.kohsanchors.skin.SkinComposer;
import dev.zymekoh.kohsanchors.skin.SkinPaint;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

/**
 * The anchor the workshop edits, drawn face by face from the skinned textures.
 *
 * <p>An orthographic cube is exact to draw in the 2D interface: each visible face is its texture
 * blitted through the affine transform that maps its pixel grid onto the screen. The same
 * transform, inverted, turns the pointer into the pixel under it, and draws the reference grid
 * exactly over the pixels, whatever the resource pack's resolution. The grid squares only the
 * pixels of the layer being edited, one screen pixel thin inside it and two along its border, so
 * what the brush can reach reads at a glance.</p>
 *
 * <p>The texture holds, for the charge shown, three rows of three faces (top, side, bottom): the
 * skinned colours, the frame layer's mask and the glow layer's mask. The masks darken the layer
 * that is not being edited with a heartbeat, and light the one that is.</p>
 */
final class AnchorCube {
    private static final Direction[] DIRECTIONS = Direction.values();
    /** Grid lines are drawn in 1/64ths of a texture pixel, fine enough for one screen pixel. */
    private static final int SUB = 64;
    private static final int KIND_INNER = 0;
    private static final int KIND_MAJOR = 1;
    private static final int KIND_EDGE = 2;

    /** Each cube keeps its own texture, so the workshop's and the enemy preview's never clash. */
    private final Identifier textureId;
    private DynamicTexture texture;
    private int resolution;
    private int builtGeneration = Integer.MIN_VALUE;
    private int builtRevision = Integer.MIN_VALUE;
    private int builtCharge = -1;
    private int builtFrame = -1;

    private float yaw = 225.0F;
    private float pitch = 28.0F;
    private final float[] rotation = new float[9];
    private final Matrix3x2f[] faces = new Matrix3x2f[6];
    private final boolean[] visible = new boolean[6];
    private int charge = 4;

    /** Each texture pixel's layer, per column (top, side, bottom), for the charge shown. */
    private final byte[][] layers = new byte[3][];
    private int layerStamp;
    /** The grid's line runs per column, for {@link #runsLayer} at {@link #runsStamp}. */
    private final int[][] runs = new int[3][];
    private byte runsLayer = -1;
    private int runsStamp = -1;

    AnchorCube() {
        this("anchor");
    }

    AnchorCube(String name) {
        this.textureId = Identifier.fromNamespaceAndPath(KoHsAnchorsClient.MOD_ID, "workshop/" + name);
        for (int index = 0; index < 6; index++) {
            this.faces[index] = new Matrix3x2f();
        }
    }

    int resolution() {
        return this.resolution;
    }

    int charge() {
        return this.charge;
    }

    void setCharge(int charge) {
        this.charge = Math.max(0, Math.min(4, charge));
    }

    void rotate(float deltaYaw, float deltaPitch) {
        this.yaw += deltaYaw;
        this.pitch = Math.max(-80.0F, Math.min(80.0F, this.pitch + deltaPitch));
    }

    void resetView() {
        this.yaw = 225.0F;
        this.pitch = 28.0F;
    }

    float yaw() {
        return this.yaw;
    }

    float pitch() {
        return this.pitch;
    }

    /** Builds or refreshes the texture; false while the anchor textures cannot be read. */
    boolean prepare(boolean motion) {
        SkinComposer composer = AtlasSkin.composer();
        if (composer == null) {
            return false;
        }
        AnchorTextures textures = composer.textures();
        int size = textures.resolution();
        int frames = textures.get(AnchorVariant.TOP).frames;
        int frame = motion && this.charge > 0 && frames > 1 ? (int) ((System.nanoTime() / 50_000_000L) % frames) : 0;
        int generation = AtlasSkin.generation();
        int revision = AnchorsConfig.revision() * 31 + SkinPaint.revision();
        if (this.texture != null && size == this.resolution && generation == this.builtGeneration
                && revision == this.builtRevision && this.charge == this.builtCharge && frame == this.builtFrame) {
            return true;
        }
        if (this.texture == null || size != this.resolution) {
            close();
            this.resolution = size;
            this.texture = new DynamicTexture(() -> "KoHs Anchor's workshop", size * 3, size * 3, true);
            Minecraft.getInstance().getTextureManager().register(this.textureId, this.texture);
        }
        NativeImage pixels = this.texture.getPixels();
        if (pixels == null) {
            return false;
        }
        AnchorVariant[] variants = {AnchorVariant.top(this.charge), AnchorVariant.side(this.charge), AnchorVariant.BOTTOM};
        boolean layersChanged = false;
        for (int column = 0; column < 3; column++) {
            byte[] columnLayers = this.layers[column] != null && this.layers[column].length == size * size
                    ? this.layers[column] : null;
            byte[] built = new byte[size * size];
            AnchorVariant variant = variants[column];
            AnchorTextures.Texture source = textures.get(variant);
            int[] composed = AtlasSkin.composed(variant);
            SkinPaint.Grid grid = source.width == size && source.height == size
                    ? SkinPaint.grid(variant.face(), size) : null;
            int area = source.area();
            int useFrame = variant == AnchorVariant.TOP ? Math.min(frame, source.frames - 1) : 0;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    // Resource packs that mix resolutions are drawn nearest-neighbour into the grid.
                    int sx = Math.min(source.width - 1, x * source.width / size);
                    int sy = Math.min(source.height - 1, y * source.height / size);
                    int index = sy * source.width + sx;
                    int color = composed == null ? 0xFF000000 : composed[useFrame * area + index] | 0xFF000000;
                    byte layer = composer.layerOf(source, grid, index);
                    built[y * size + x] = layer;
                    pixels.setPixel(column * size + x, y, color);
                    pixels.setPixel(column * size + x, size + y, layer == AnchorTextures.FRAME ? 0xFFFFFFFF : 0);
                    pixels.setPixel(column * size + x, size * 2 + y, layer == AnchorTextures.GLOW ? 0xFFFFFFFF : 0);
                }
            }
            if (columnLayers == null || !Arrays.equals(columnLayers, built)) {
                this.layers[column] = built;
                layersChanged = true;
            }
        }
        if (layersChanged) {
            this.layerStamp++;
        }
        this.texture.upload();
        this.builtGeneration = generation;
        this.builtRevision = revision;
        this.builtCharge = this.charge;
        this.builtFrame = frame;
        return true;
    }

    void close() {
        if (this.texture != null) {
            Minecraft.getInstance().getTextureManager().release(this.textureId);
            this.texture = null;
        }
    }

    /**
     * Places the cube: centred on {@code centerX, centerY}, one block {@code scale} pixels wide
     * seen straight on. Works out which faces show and each face's transform.
     */
    void layout(float centerX, float centerY, float scale) {
        double yawRadians = Math.toRadians(this.yaw);
        double pitchRadians = Math.toRadians(this.pitch);
        float cy = (float) Math.cos(yawRadians);
        float sy = (float) Math.sin(yawRadians);
        float cp = (float) Math.cos(pitchRadians);
        float sp = (float) Math.sin(pitchRadians);
        // Turn about Y by the yaw, then tip about X by the pitch; the viewer looks down -Z.
        this.rotation[0] = cy;
        this.rotation[1] = 0.0F;
        this.rotation[2] = sy;
        this.rotation[3] = sp * sy;
        this.rotation[4] = cp;
        this.rotation[5] = -sp * cy;
        this.rotation[6] = -cp * sy;
        this.rotation[7] = sp;
        this.rotation[8] = cp * cy;
        for (Direction direction : DIRECTIONS) {
            int index = direction.ordinal();
            float normalZ = this.rotation[6] * direction.getStepX() + this.rotation[7] * direction.getStepY()
                    + this.rotation[8] * direction.getStepZ();
            this.visible[index] = normalZ > 0.02F;
            float[] origin = facePoint(direction, 0.0F, 0.0F);
            float[] across = facePoint(direction, 1.0F, 0.0F);
            float[] down = facePoint(direction, 0.0F, 1.0F);
            float ox = screenX(origin, centerX, scale);
            float oy = screenY(origin, centerY, scale);
            float ux = screenX(across, centerX, scale) - ox;
            float uy = screenY(across, centerY, scale) - oy;
            float vx = screenX(down, centerX, scale) - ox;
            float vy = screenY(down, centerY, scale) - oy;
            float per = 1.0F / Math.max(1, this.resolution);
            this.faces[index].set(ux * per, uy * per, vx * per, vy * per, ox, oy);
        }
    }

    boolean visible(Direction direction) {
        return this.visible[direction.ordinal()];
    }

    /** The transform from the face's pixel grid to the screen. */
    Matrix3x2f face(Direction direction) {
        return this.faces[direction.ordinal()];
    }

    /** How many screen pixels one texture pixel covers on a face, roughly. */
    float pixelSize(Direction direction) {
        Matrix3x2f matrix = this.faces[direction.ordinal()];
        float across = (float) Math.hypot(matrix.m00(), matrix.m01());
        float down = (float) Math.hypot(matrix.m10(), matrix.m11());
        return Math.min(across, down);
    }

    /**
     * Draws the visible faces. {@code editing} is the layer being edited ({@link AnchorTextures#FRAME}
     * or {@link AnchorTextures#GLOW}), or 0 to draw the anchor plainly; {@code dim} darkens the
     * other layer (0 to 1) and {@code lift} lights the edited one.
     */
    void draw(GuiGraphicsExtractor graphics, float alpha, byte editing, float dim, float lift) {
        draw(graphics, alpha, editing, dim, lift, 0xC084FC);
    }

    /**
     * The same, lighting the edited layer in {@code liftColor} (RGB): the enemy preview lights the
     * glow layer in the enemy colour, as their glow does in the world.
     */
    void draw(GuiGraphicsExtractor graphics, float alpha, byte editing, float dim, float lift, int liftColor) {
        if (this.texture == null || alpha <= 0.01F) {
            return;
        }
        int size = this.resolution;
        int textureSize = size * 3;
        for (Direction direction : DIRECTIONS) {
            if (!this.visible[direction.ordinal()]) {
                continue;
            }
            int column = direction == Direction.UP ? 0 : direction == Direction.DOWN ? 2 : 1;
            float shade = switch (direction) {
                case UP -> 1.0F;
                case DOWN -> 0.5F;
                case NORTH, SOUTH -> 0.82F;
                default -> 0.64F;
            };
            int grey = Math.round(255.0F * shade);
            int tint = Math.round(255.0F * alpha) << 24 | grey << 16 | grey << 8 | grey;
            graphics.pose().pushMatrix();
            graphics.pose().mul(this.faces[direction.ordinal()]);
            graphics.blit(RenderPipelines.GUI_TEXTURED, this.textureId, 0, 0, column * size, 0, size, size, textureSize, textureSize,
                    tint);
            if (editing != 0 && dim > 0.01F) {
                int otherRow = editing == AnchorTextures.FRAME ? 2 : 1;
                int darkness = Math.round(255.0F * Math.min(1.0F, dim) * alpha) << 24 | 0x05020A;
                graphics.blit(RenderPipelines.GUI_TEXTURED, this.textureId, 0, 0, column * size, otherRow * size, size, size,
                        textureSize, textureSize, darkness);
            }
            if (editing != 0 && lift > 0.01F) {
                int row = editing == AnchorTextures.FRAME ? 1 : 2;
                int light = Math.round(255.0F * Math.min(1.0F, lift) * alpha) << 24 | (liftColor & 0xFFFFFF);
                graphics.blit(RenderPipelines.GUI_TEXTURED, this.textureId, 0, 0, column * size, row * size, size, size,
                        textureSize, textureSize, light);
            }
            graphics.pose().popMatrix();
        }
    }

    /**
     * The reference squares over the layer being edited: a thin line between two of its pixels, a
     * little stronger every four (eight from 32x up), and a double line where it meets the other
     * layer or the edge of the face. Skipped on faces too small on screen for squares to read.
     */
    void drawGrid(GuiGraphicsExtractor graphics, float alpha, int color, byte editing) {
        int size = this.resolution;
        if (size <= 0 || alpha <= 0.01F || this.layers[1] == null) {
            return;
        }
        if (this.runsLayer != editing || this.runsStamp != this.layerStamp) {
            for (int column = 0; column < 3; column++) {
                this.runs[column] = buildRuns(this.layers[column], size, editing);
            }
            this.runsLayer = editing;
            this.runsStamp = this.layerStamp;
        }
        float guiScale = (float) Minecraft.getInstance().getWindow().getGuiScale();
        AnchorsUi.isolate(graphics);
        int inner = AnchorsTheme.withAlpha(color, Math.round(60 * alpha));
        int major = AnchorsTheme.withAlpha(color, Math.round(105 * alpha));
        int edge = AnchorsTheme.withAlpha(color, Math.round(235 * alpha));
        for (Direction direction : DIRECTIONS) {
            if (!this.visible[direction.ordinal()]) {
                continue;
            }
            float physical = pixelSize(direction) * guiScale;
            if (physical < 4.0F) {
                // Squares a few screen pixels wide would wash the face out.
                continue;
            }
            int[] columnRuns = this.runs[direction == Direction.UP ? 0 : direction == Direction.DOWN ? 2 : 1];
            if (columnRuns == null) {
                continue;
            }
            int thin = Math.max(1, Math.round(SUB / physical));
            int thick = Math.max(thin + 1, Math.round(2.0F * SUB / physical));
            graphics.pose().pushMatrix();
            graphics.pose().mul(this.faces[direction.ordinal()]);
            graphics.pose().scale(1.0F / SUB, 1.0F / SUB);
            // Inside lines first, the border over them.
            for (int pass = 0; pass < 2; pass++) {
                for (int index = 0; index < columnRuns.length; index += 5) {
                    int kind = columnRuns[index + 4];
                    if ((kind == KIND_EDGE) != (pass == 1)) {
                        continue;
                    }
                    int width = kind == KIND_EDGE ? thick : thin;
                    int lineColor = kind == KIND_EDGE ? edge : kind == KIND_MAJOR ? major : inner;
                    int across = columnRuns[index + 1] * SUB - width / 2;
                    int from = columnRuns[index + 2] * SUB;
                    int to = columnRuns[index + 3] * SUB;
                    if (kind == KIND_EDGE) {
                        // Borders overlap at the corners so they close.
                        from -= width / 2;
                        to += width - width / 2;
                    }
                    if (columnRuns[index] == 0) {
                        graphics.fill(from, across, to, across + width, lineColor);
                    } else {
                        graphics.fill(across, from, across + width, to, lineColor);
                    }
                }
            }
            graphics.pose().popMatrix();
        }
        AnchorsUi.isolate(graphics);
    }

    /**
     * The grid of one face as runs of {orientation, line, from, to, kind}: orientation 0 runs
     * along a row border (line = v), 1 along a column border (line = u).
     */
    private static int[] buildRuns(byte[] layer, int size, byte editing) {
        if (layer == null || layer.length != size * size) {
            return null;
        }
        int majorEvery = size >= 32 ? 8 : 4;
        IntArrayList out = new IntArrayList();
        for (int orientation = 0; orientation < 2; orientation++) {
            for (int line = 0; line <= size; line++) {
                int runKind = -1;
                int runStart = 0;
                for (int along = 0; along <= size; along++) {
                    int kind = -1;
                    if (along < size) {
                        boolean before;
                        boolean after;
                        if (orientation == 0) {
                            before = line > 0 && layer[(line - 1) * size + along] == editing;
                            after = line < size && layer[line * size + along] == editing;
                        } else {
                            before = line > 0 && layer[along * size + line - 1] == editing;
                            after = line < size && layer[along * size + line] == editing;
                        }
                        if (before && after) {
                            kind = line % majorEvery == 0 ? KIND_MAJOR : KIND_INNER;
                        } else if (before || after) {
                            kind = KIND_EDGE;
                        }
                    }
                    if (kind != runKind) {
                        if (runKind >= 0) {
                            out.add(orientation);
                            out.add(line);
                            out.add(runStart);
                            out.add(along);
                            out.add(runKind);
                        }
                        runKind = kind;
                        runStart = along;
                    }
                }
            }
        }
        return out.toIntArray();
    }

    /** The layer of one pixel of a face, for the charge shown; 0 when unknown. */
    byte layerAt(Direction direction, int u, int v) {
        byte[] layer = this.layers[direction == Direction.UP ? 0 : direction == Direction.DOWN ? 2 : 1];
        int size = this.resolution;
        if (layer == null || u < 0 || v < 0 || u >= size || v >= size || layer.length != size * size) {
            return 0;
        }
        return layer[v * size + u];
    }

    /** Outlines one pixel of one face, two screen pixels wide, over a wash of {@code fill}. */
    void drawPixelOutline(GuiGraphicsExtractor graphics, Direction direction, int u, int v, int color, int fill) {
        float physical = Math.max(0.5F, pixelSize(direction) * (float) Minecraft.getInstance().getWindow().getGuiScale());
        int width = Math.max(2, Math.round(2.0F * SUB / physical));
        graphics.pose().pushMatrix();
        graphics.pose().mul(this.faces[direction.ordinal()]);
        graphics.pose().scale(1.0F / SUB, 1.0F / SUB);
        int x0 = u * SUB;
        int y0 = v * SUB;
        graphics.fill(x0, y0, x0 + SUB, y0 + SUB, fill);
        graphics.fill(x0 - width, y0 - width, x0 + SUB + width, y0, color);
        graphics.fill(x0 - width, y0 + SUB, x0 + SUB + width, y0 + SUB + width, color);
        graphics.fill(x0 - width, y0, x0, y0 + SUB, color);
        graphics.fill(x0 + SUB, y0, x0 + SUB + width, y0 + SUB, color);
        graphics.pose().popMatrix();
    }

    /**
     * The pixel under a screen point, front faces first: {face ordinal, u, v}, or {@code null}.
     */
    int[] pick(double screenX, double screenY) {
        int size = this.resolution;
        if (size <= 0) {
            return null;
        }
        Matrix3x2f inverse = new Matrix3x2f();
        for (Direction direction : DIRECTIONS) {
            if (!this.visible[direction.ordinal()]) {
                continue;
            }
            this.faces[direction.ordinal()].invert(inverse);
            float u = inverse.m00() * (float) screenX + inverse.m10() * (float) screenY + inverse.m20();
            float v = inverse.m01() * (float) screenX + inverse.m11() * (float) screenY + inverse.m21();
            if (u >= 0.0F && v >= 0.0F && u < size && v < size) {
                return new int[] {direction.ordinal(), (int) u, (int) v};
            }
        }
        return null;
    }

    private float screenX(float[] point, float centerX, float scale) {
        float x = point[0] - 0.5F;
        float y = point[1] - 0.5F;
        float z = point[2] - 0.5F;
        return centerX + (this.rotation[0] * x + this.rotation[1] * y + this.rotation[2] * z) * scale;
    }

    private float screenY(float[] point, float centerY, float scale) {
        float x = point[0] - 0.5F;
        float y = point[1] - 0.5F;
        float z = point[2] - 0.5F;
        return centerY - (this.rotation[3] * x + this.rotation[4] * y + this.rotation[5] * z) * scale;
    }

    /** A point of the face turned to {@code direction}, with its texture mapped as the game maps it. */
    static float[] facePoint(Direction direction, float u, float v) {
        return switch (direction) {
            case UP -> new float[] {u, 1.0F, v};
            case DOWN -> new float[] {u, 0.0F, 1.0F - v};
            case NORTH -> new float[] {1.0F - u, 1.0F - v, 0.0F};
            case SOUTH -> new float[] {u, 1.0F - v, 1.0F};
            case WEST -> new float[] {0.0F, 1.0F - v, u};
            case EAST -> new float[] {1.0F, 1.0F - v, 1.0F - u};
        };
    }

    /** The face a direction's pixels belong to: top, one of the four sides, or bottom. */
    static AnchorVariant.Face faceOf(Direction direction) {
        return direction == Direction.UP ? AnchorVariant.Face.TOP
                : direction == Direction.DOWN ? AnchorVariant.Face.BOTTOM : AnchorVariant.Face.SIDE;
    }
}
