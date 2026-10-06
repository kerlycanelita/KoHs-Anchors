package dev.zymekoh.kohsanchors.glow;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.zymekoh.kohsanchors.compat.Mc;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.predict.AnchorVeil;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
    private static final double MAX_DISTANCE_SQR = 96.0D * 96.0D;
    /** How far each face stands out of the block, so it is drawn over the terrain's face. */
    private static final float GROW = 0.0015F;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final SkinCubeTexture SKIN = SkinCubeTexture.ENEMY;

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
        if (!active() || level == null || AnchorTracker.enemyCount() == 0 || !SKIN.prepare()) {
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
        if (!SKIN.prepare()) {
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
        collector.submitCustomGeometry(poses, SKIN.cutout(), new Cube(faces, light, charge, SKIN.frame(charge)));
    }

    private record Cube(int faces, int[] light, int charge, int frame) implements SubmitNodeCollector.CustomGeometryRenderer {
        private static final float[][] CORNERS = {{0.0F, 0.0F}, {0.0F, 1.0F}, {1.0F, 1.0F}, {1.0F, 0.0F}};

        @Override
        public void render(PoseStack.Pose pose, VertexConsumer consumer) {
            Vector3f normal = new Vector3f();
            for (Direction direction : DIRECTIONS) {
                if ((this.faces & 1 << direction.ordinal()) == 0) {
                    continue;
                }
                pose.transformNormal(direction.getStepX(), direction.getStepY(), direction.getStepZ(), normal);
                int light = this.light[direction.ordinal()];
                for (float[] corner : CORNERS) {
                    float[] point = SkinCubeTexture.facePoint(direction, corner[0], corner[1]);
                    float x = 0.5F + (point[0] - 0.5F) * (1.0F + 2.0F * GROW);
                    float y = 0.5F + (point[1] - 0.5F) * (1.0F + 2.0F * GROW);
                    float z = 0.5F + (point[2] - 0.5F) * (1.0F + 2.0F * GROW);
                    consumer.addVertex(pose, x, y, z).setColor(0xFFFFFFFF)
                            .setUv(SKIN.u(direction, this.charge, this.frame, corner[0]), SKIN.v(direction, this.charge, corner[1]))
                            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                            .setNormal(normal.x(), normal.y(), normal.z());
                }
            }
        }
    }
}
