package dev.zymekoh.kohsanchors.glow;

import com.mojang.blaze3d.platform.NativeImage;
import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.skin.AnchorTextures;
import dev.zymekoh.kohsanchors.skin.AnchorVariant;
import dev.zymekoh.kohsanchors.skin.AtlasSkin;
import dev.zymekoh.kohsanchors.skin.SkinComposer;
import dev.zymekoh.kohsanchors.skin.SkinPaint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/**
 * An anchor's faces in one small texture, with a skin: the player's own or the enemy's. The block
 * atlas holds only the player's, and only opaque; this one is drawn over the enemy's anchors in the
 * world and, with any transparency, by the anchor fade.
 *
 * <p>Row 0 holds the five side charges, the dark top and the bottom; row 1 the frames of the lit
 * top. It is rebuilt only when the skin, the paint or the resource pack changes.</p>
 */
public final class SkinCubeTexture {
    public static final SkinCubeTexture OWN = new SkinCubeTexture("own_skin", false);
    public static final SkinCubeTexture ENEMY = new SkinCubeTexture("enemy_skin", true);

    /** Columns of the first row: the five sides, the dark top, the bottom. */
    private static final int STATIC_TILES = 7;
    private static final int TILE_TOP_OFF = 5;
    private static final int TILE_BOTTOM = 6;

    private final Identifier id;
    private final boolean enemy;
    private DynamicTexture texture;
    private RenderType cutout;
    private RenderType translucent;
    private RenderType unlit;
    private RenderType unlitTranslucent;
    private int resolution;
    private int topFrames = 1;
    private int width;
    private int height;
    private int builtStamp = Integer.MIN_VALUE;

    private SkinCubeTexture(String name, boolean enemy) {
        this.id = Identifier.fromNamespaceAndPath(KoHsAnchorsClient.MOD_ID, name);
        this.enemy = enemy;
    }

    public static SkinCubeTexture of(boolean enemy) {
        return enemy ? ENEMY : OWN;
    }

    public RenderType cutout() {
        return this.cutout;
    }

    public RenderType translucent() {
        return this.translucent;
    }

    /**
     * The same faces with no light of the scene on them, only the colour each vertex gives: the
     * settings screen's stage lights its anchor itself. Vanilla's beacon beam material is exactly a
     * texture times a colour.
     */
    public RenderType unlit() {
        return this.unlit;
    }

    public RenderType unlitTranslucent() {
        return this.unlitTranslucent;
    }

    /** The lit top's frame to draw now, from the game's own 50 ms frame step. */
    public int frame(int charge) {
        return charge > 0 && this.topFrames > 1 ? (int) (System.nanoTime() / 50_000_000L % this.topFrames) : 0;
    }

    /** Builds or refreshes the texture; false while the anchor textures cannot be read. */
    public boolean prepare() {
        SkinComposer composer = AtlasSkin.composer();
        if (composer == null) {
            return false;
        }
        AnchorTextures textures = composer.textures();
        int size = textures.resolution();
        int frames = Math.max(1, textures.get(AnchorVariant.TOP).frames);
        int stamp = AtlasSkin.generation() * 31 + AnchorsConfig.revision() * 7 + SkinPaint.revision();
        if (this.texture != null && size == this.resolution && frames == this.topFrames && stamp == this.builtStamp) {
            return true;
        }
        if (this.texture == null || size != this.resolution || frames != this.topFrames) {
            if (this.texture != null) {
                Minecraft.getInstance().getTextureManager().release(this.id);
            }
            this.resolution = size;
            this.topFrames = frames;
            this.width = size * Math.max(STATIC_TILES, frames);
            this.height = size * 2;
            this.texture = new DynamicTexture(() -> "KoHs Anchor's " + this.id.getPath(), this.width, this.height, true);
            Minecraft.getInstance().getTextureManager().register(this.id, this.texture);
            this.cutout = RenderTypes.entityCutout(this.id);
            this.translucent = RenderTypes.entityTranslucent(this.id);
            this.unlit = RenderTypes.beaconBeam(this.id, false);
            this.unlitTranslucent = RenderTypes.beaconBeam(this.id, true);
        }
        NativeImage pixels = this.texture.getPixels();
        if (pixels == null) {
            return false;
        }
        for (int charge = 0; charge <= 4; charge++) {
            copy(pixels, textures, AnchorVariant.side(charge), 0, charge, 0);
        }
        copy(pixels, textures, AnchorVariant.TOP_OFF, 0, TILE_TOP_OFF, 0);
        copy(pixels, textures, AnchorVariant.BOTTOM, 0, TILE_BOTTOM, 0);
        for (int frame = 0; frame < frames; frame++) {
            copy(pixels, textures, AnchorVariant.TOP, frame, frame, 1);
        }
        this.texture.upload();
        this.builtStamp = stamp;
        return true;
    }

    /** One frame of {@code variant} with this skin, into the tile at {@code column, row}. */
    private void copy(NativeImage pixels, AnchorTextures textures, AnchorVariant variant, int frame, int column, int row) {
        AnchorTextures.Texture source = textures.get(variant);
        int[] composed = AtlasSkin.composed(variant, this.enemy);
        int useFrame = Math.min(frame, source.frames - 1);
        int area = source.area();
        for (int y = 0; y < this.resolution; y++) {
            for (int x = 0; x < this.resolution; x++) {
                // Resource packs that mix resolutions are drawn nearest-neighbour into the tile.
                int sx = Math.min(source.width - 1, x * source.width / this.resolution);
                int sy = Math.min(source.height - 1, y * source.height / this.resolution);
                int index = sy * source.width + sx;
                int color = composed == null ? source.pixels[useFrame * area + index] : composed[useFrame * area + index];
                pixels.setPixel(column * this.resolution + x, row * this.resolution + y, color);
            }
        }
    }

    /** The texture point of face {@code direction} at {@code u, v} (0 to 1 across the face), 0 to 1. */
    public float u(Direction direction, int charge, int frame, float u) {
        int column = switch (direction) {
            case UP -> charge > 0 ? frame : TILE_TOP_OFF;
            case DOWN -> TILE_BOTTOM;
            default -> Math.max(0, Math.min(4, charge));
        };
        return (column + u) * this.resolution / (float) this.width;
    }

    public float v(Direction direction, int charge, float v) {
        return (direction == Direction.UP && charge > 0 ? 0.5F : 0.0F) + v * this.resolution / (float) this.height;
    }

    /**
     * Where the texture point {@code u, v} of a face lies on the block, 0 to 1: the mapping the
     * workshop paints with, which is Vanilla's for the anchor's cube.
     */
    public static float[] facePoint(Direction direction, float u, float v) {
        return switch (direction) {
            case UP -> new float[] {u, 1.0F, v};
            case DOWN -> new float[] {u, 0.0F, 1.0F - v};
            case NORTH -> new float[] {1.0F - u, 1.0F - v, 0.0F};
            case SOUTH -> new float[] {u, 1.0F - v, 1.0F};
            case WEST -> new float[] {0.0F, 1.0F - v, u};
            case EAST -> new float[] {1.0F, 1.0F - v, 1.0F - u};
        };
    }

    /** The inverse of {@link #facePoint}: the face's texture point under the block point {@code x, y, z}. */
    public static float faceU(Direction direction, float x, float y, float z) {
        return switch (direction) {
            case UP, DOWN, SOUTH -> x;
            case NORTH -> 1.0F - x;
            case WEST -> z;
            case EAST -> 1.0F - z;
        };
    }

    public static float faceV(Direction direction, float x, float y, float z) {
        return switch (direction) {
            case UP -> z;
            case DOWN -> 1.0F - z;
            default -> 1.0F - y;
        };
    }
}
