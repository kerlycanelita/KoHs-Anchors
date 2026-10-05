package dev.zymekoh.kohsanchors.glow;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import dev.zymekoh.kohsanchors.compat.Mc;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.predict.AnchorVeil;
import dev.zymekoh.kohsanchors.skin.AnchorTextures;
import dev.zymekoh.kohsanchors.skin.AnchorVariant;
import dev.zymekoh.kohsanchors.skin.AtlasSkin;
import dev.zymekoh.kohsanchors.skin.SkinComposer;
import dev.zymekoh.kohsanchors.skin.SkinPaint;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The enemy's skin in the world. The player's skin lives in the block atlas, which every anchor
 * shares, so the enemy's cannot go there: each anchor the tracker marks as the enemy's is drawn
 * over with a cube a hair larger than the block, textured with the enemy's colours and paint from
 * a small texture of their faces (the five side charges, the dark and the lit top with its frames,
 * the bottom). Faces against a full block are left out, as the terrain leaves them out; each face
 * takes the light of the block in front of it.
 *
 * <p>Only with enemy anchors and the enemy skin on, only for the enemy's anchors within 96 blocks,
 * and nothing where the anchor is drawn as gone. The texture is rebuilt only when the skin, the
 * paint or the resource pack changes.</p>
 */
public final class EnemySkinRenderer {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(KoHsAnchorsClient.MOD_ID, "enemy_skin");
    private static final double MAX_DISTANCE_SQR = 96.0D * 96.0D;
    /** How far each face stands out of the block, so it is drawn over the terrain's face. */
    private static final float GROW = 0.0015F;
    private static final Direction[] DIRECTIONS = Direction.values();
    /** Columns of the first row: the five sides, the dark top, the bottom. */
    private static final int STATIC_TILES = 7;
    private static final int TILE_TOP_OFF = 5;
    private static final int TILE_BOTTOM = 6;

    private static DynamicTexture texture;
    private static RenderType renderType;
    private static int resolution;
    private static int topFrames = 1;
    private static int width;
    private static int height;
    private static int builtStamp = Integer.MIN_VALUE;
    private static int drawnLastFrame;
    private static int drawnThisFrame;

    private EnemySkinRenderer() {
    }

    /** Whether the enemy's anchors are drawn with their own skin right now. */
    public static boolean active() {
        AnchorsConfig.Settings settings = AnchorsConfig.settings();
        return settings.enemyGlow.enabled && settings.enemySkin.enabled;
    }

    /** Anchors drawn with the enemy's skin in the last frame, for developer mode. */
    public static int drawn() {
        return drawnLastFrame;
    }

    /** With the frame's block entities, relative to the camera, like the glow. */
    public static void submit(PoseStack poses, SubmitNodeCollector collector, Vec3 camera) {
        drawnLastFrame = drawnThisFrame;
        drawnThisFrame = 0;
        ClientLevel level = Minecraft.getInstance().level;
        if (!active() || level == null || AnchorTracker.enemyCount() == 0 || !prepare()) {
            return;
        }
        for (Long2ByteMap.Entry entry : AnchorTracker.anchors().long2ByteEntrySet()) {
            if (entry.getByteValue() != AnchorTracker.ENEMY) {
                continue;
            }
            BlockPos position = BlockPos.of(entry.getLongKey());
            if (position.distToCenterSqr(camera) > MAX_DISTANCE_SQR) {
                continue;
            }
            BlockState drawn = AnchorVeil.predicted(position);
            BlockState state = drawn != null ? drawn : level.getBlockState(position);
            if (!state.is(Blocks.RESPAWN_ANCHOR)) {
                continue;
            }
            poses.pushPose();
            poses.translate(position.getX() - camera.x, position.getY() - camera.y, position.getZ() - camera.z);
            submitCube(poses, collector, level, position, state.getValue(RespawnAnchorBlock.CHARGE), true);
            poses.popPose();
            drawnThisFrame++;
        }
    }

    /**
     * One enemy anchor with their skin, in the block's own space (0 to 1) of {@code poses}: for the
     * world, and for the fade, which shrinks it. {@code cull} leaves out the faces against full blocks.
     */
    public static void submitCube(PoseStack poses, SubmitNodeCollector collector, ClientLevel level, BlockPos position,
            int charge, boolean cull) {
        if (!prepare()) {
            return;
        }
        int[] light = new int[6];
        int faces = 0;
        for (Direction direction : DIRECTIONS) {
            BlockPos front = position.relative(direction);
            if (cull && level.getBlockState(front).isSolidRender()) {
                continue;
            }
            faces |= 1 << direction.ordinal();
            light[direction.ordinal()] = Mc.packLight(level.getBrightness(LightLayer.BLOCK, front),
                    level.getBrightness(LightLayer.SKY, front));
        }
        if (faces == 0) {
            return;
        }
        int frame = charge > 0 && topFrames > 1 ? (int) (System.nanoTime() / 50_000_000L % topFrames) : 0;
        collector.submitCustomGeometry(poses, renderType, new Cube(faces, light, charge, frame));
    }

    /** Builds or refreshes the texture of the enemy's faces; false while the anchor textures cannot be read. */
    private static boolean prepare() {
        SkinComposer composer = AtlasSkin.composer();
        if (composer == null) {
            return false;
        }
        AnchorTextures textures = composer.textures();
        int size = textures.resolution();
        int frames = Math.max(1, textures.get(AnchorVariant.TOP).frames);
        int stamp = AtlasSkin.generation() * 31 + AnchorsConfig.revision() * 7 + SkinPaint.revision();
        if (texture != null && size == resolution && frames == topFrames && stamp == builtStamp) {
            return true;
        }
        if (texture == null || size != resolution || frames != topFrames) {
            if (texture != null) {
                Minecraft.getInstance().getTextureManager().release(TEXTURE);
            }
            resolution = size;
            topFrames = frames;
            width = size * Math.max(STATIC_TILES, frames);
            height = size * 2;
            texture = new DynamicTexture(() -> "KoHs Anchor's enemy skin", width, height, true);
            Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
            renderType = RenderTypes.entityCutout(TEXTURE);
        }
        NativeImage pixels = texture.getPixels();
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
        texture.upload();
        builtStamp = stamp;
        return true;
    }

    /** One frame of {@code variant} with the enemy's skin, into the tile at {@code column, row}. */
    private static void copy(NativeImage pixels, AnchorTextures textures, AnchorVariant variant, int frame, int column,
            int row) {
        AnchorTextures.Texture source = textures.get(variant);
        int[] composed = AtlasSkin.composedEnemy(variant);
        int useFrame = Math.min(frame, source.frames - 1);
        int area = source.area();
        for (int y = 0; y < resolution; y++) {
            for (int x = 0; x < resolution; x++) {
                // Resource packs that mix resolutions are drawn nearest-neighbour into the tile.
                int sx = Math.min(source.width - 1, x * source.width / resolution);
                int sy = Math.min(source.height - 1, y * source.height / resolution);
                int index = sy * source.width + sx;
                int color = composed == null ? source.pixels[useFrame * area + index] : composed[useFrame * area + index];
                pixels.setPixel(column * resolution + x, row * resolution + y, color);
            }
        }
    }

    /** The texture's corner of a face's tile, in 0 to 1. */
    private static float tileU(Direction direction, int charge, int frame) {
        int column = switch (direction) {
            case UP -> charge > 0 ? frame : TILE_TOP_OFF;
            case DOWN -> TILE_BOTTOM;
            default -> Math.max(0, Math.min(4, charge));
        };
        return column * resolution / (float) width;
    }

    private static float tileV(Direction direction, int charge) {
        return direction == Direction.UP && charge > 0 ? 0.5F : 0.0F;
    }

    /**
     * Where the texture point {@code u, v} of a face lies on the block, 0 to 1: the mapping the
     * workshop paints with, which is Vanilla's for the anchor's cube.
     */
    private static float[] facePoint(Direction direction, float u, float v) {
        return switch (direction) {
            case UP -> new float[] {u, 1.0F, v};
            case DOWN -> new float[] {u, 0.0F, 1.0F - v};
            case NORTH -> new float[] {1.0F - u, 1.0F - v, 0.0F};
            case SOUTH -> new float[] {u, 1.0F - v, 1.0F};
            case WEST -> new float[] {0.0F, 1.0F - v, u};
            case EAST -> new float[] {1.0F, 1.0F - v, 1.0F - u};
        };
    }

    private record Cube(int faces, int[] light, int charge, int frame) implements SubmitNodeCollector.CustomGeometryRenderer {
        private static final float[][] CORNERS = {{0.0F, 0.0F}, {0.0F, 1.0F}, {1.0F, 1.0F}, {1.0F, 0.0F}};

        @Override
        public void render(PoseStack.Pose pose, VertexConsumer consumer) {
            float tileWidth = resolution / (float) width;
            float tileHeight = resolution / (float) height;
            Vector3f normal = new Vector3f();
            for (Direction direction : DIRECTIONS) {
                if ((this.faces & 1 << direction.ordinal()) == 0) {
                    continue;
                }
                float u0 = tileU(direction, this.charge, this.frame);
                float v0 = tileV(direction, this.charge);
                pose.transformNormal(direction.getStepX(), direction.getStepY(), direction.getStepZ(), normal);
                int light = this.light[direction.ordinal()];
                for (float[] corner : CORNERS) {
                    float[] point = facePoint(direction, corner[0], corner[1]);
                    float x = 0.5F + (point[0] - 0.5F) * (1.0F + 2.0F * GROW);
                    float y = 0.5F + (point[1] - 0.5F) * (1.0F + 2.0F * GROW);
                    float z = 0.5F + (point[2] - 0.5F) * (1.0F + 2.0F * GROW);
                    consumer.addVertex(pose, x, y, z).setColor(0xFFFFFFFF)
                            .setUv(u0 + corner[0] * tileWidth, v0 + corner[1] * tileHeight)
                            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                            .setNormal(normal.x(), normal.y(), normal.z());
                }
            }
        }
    }
}
