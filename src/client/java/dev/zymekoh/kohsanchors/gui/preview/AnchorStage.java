package dev.zymekoh.kohsanchors.gui.preview;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.zymekoh.kohsanchors.compat.Mc;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.glow.AnchorGlowRenderer;
import dev.zymekoh.kohsanchors.glow.SkinCubeTexture;
import dev.zymekoh.kohsanchors.predict.AnchorFade;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.joml.Matrix4f;

/**
 * The settings screen's stage: a real little room for the anchor. An obsidian floor, an obsidian
 * ceiling two blocks above it, the anchor between them with the player's skin (or the enemy's),
 * and on all of it the mod's own glow, the very geometry the world draws: the lit pixels, the bloom
 * and the light thrown on the floor and the ceiling. What a setting does here it does in a fight.
 *
 * <p>The room is lit as the game lights a dark place: almost nothing from the surroundings, and
 * the block light a respawn anchor gives, a quarter of full light per charge, fading with every
 * block. So a charge visibly lights the room, and the glow's colour lies over that light.</p>
 *
 * <p>It is drawn in one pass, in perspective, in place of the falling block Vanilla's own
 * picture-in-picture renderer for entities is asked to draw (the hook is in the falling block's
 * renderer): the preview arms the stage with what to show, then asks for that picture. One stage
 * a frame, as there is one preview.</p>
 */
public final class AnchorStage {
    /** The floor's blocks, and the ceiling's: the anchor stands at the origin, two blocks of room over the floor. */
    private static final int FLOOR_Y = -1;
    private static final int CEILING_Y = 2;
    /** Tiles drawn on each side of the anchor, and where they fade out: no edge is ever seen. */
    private static final int REACH = 8;
    private static final float FADE_FROM = 4.4F;
    private static final float FADE_TO = 7.4F;
    private static final float FOV = 42.0F;
    /** The height the camera looks at: the middle of the room. */
    private static final float TARGET_Y = 0.92F;
    /** How far above or below that height the camera may go: it stays inside the room. */
    private static final float EYE_RANGE = 0.62F;
    private static final int FULL_BRIGHT = 0xF000F0;
    /** A dark room: what the surroundings give with no light at all, a little towards violet. */
    private static final float AMBIENT_R = 0.46F;
    private static final float AMBIENT_G = 0.41F;
    private static final float AMBIENT_B = 0.62F;
    private static final float ANCHOR_AMBIENT = 0.6F;
    /** Vanilla's shade of a block's faces, by direction: down, up, north, south, west, east. */
    private static final float[] SHADE = {0.5F, 1.0F, 0.8F, 0.8F, 0.6F, 0.6F};
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final float[][] CORNERS = {{0.0F, 0.0F}, {0.0F, 1.0F}, {1.0F, 1.0F}, {1.0F, 0.0F}};
    private static final Identifier OBSIDIAN = Identifier.withDefaultNamespace("textures/block/obsidian.png");

    /** The room as the glow reads it: where its light can go and which faces take it. */
    private static final BlockGetter SCENE = new BlockGetter() {
        @Override
        public BlockEntity getBlockEntity(BlockPos position) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos position) {
            int y = position.getY();
            if (y == FLOOR_Y || y == CEILING_Y) {
                return Blocks.OBSIDIAN.defaultBlockState();
            }
            return y == 0 && position.getX() == 0 && position.getZ() == 0 ? Blocks.RESPAWN_ANCHOR.defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos position) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 16;
        }

        @Override
        public int getMinY() {
            return -8;
        }
    };

    private static final Matrix4f VIEW = new Matrix4f();
    private static final float[] LIGHT = new float[4];
    private static final float[] FADE_SHADE = new float[6];
    private static final SubmitNodeCollector.CustomGeometryRenderer SOLID_TILES = (pose, consumer) -> tiles(pose, consumer, true);
    private static final SubmitNodeCollector.CustomGeometryRenderer SOFT_TILES = (pose, consumer) -> tiles(pose, consumer, false);
    private static final SubmitNodeCollector.CustomGeometryRenderer ANCHOR = AnchorStage::anchor;

    private static RenderType solid;
    private static RenderType soft;

    // What the preview armed for this frame.
    private static Object carrier;
    private static float width = 1.0F;
    private static float height = 1.0F;
    private static float eyeX;
    private static float eyeY;
    private static float eyeZ;
    private static int charge;
    private static float shownCharge;
    private static boolean enemy;
    private static boolean enemySkin;
    private static float anchorSize;
    private static float flash;
    private static int flashColor;
    private static float shown = 1.0F;
    private static AnchorFade.Style fadeStyle;
    private static long fadeSince;
    private static long fadeSeed;
    private static int fadeGlow;

    private AnchorStage() {
    }

    /**
     * What the stage shows the next time {@code state} is drawn as a picture.
     *
     * @param yaw where the camera stands around the anchor, in degrees
     * @param lift how high the camera is, -1 (near the floor) to 1 (near the ceiling)
     * @param distance blocks from the camera to the middle of the room
     * @param size the anchor's size, 0 (away) to 1; it grows from its base
     * @param blastFlash a detonation's light on the room, 0 to 1, in {@code blastColor}
     * @param presence the whole stage's presence, 0 to 1: it fades in with the screen
     */
    public static void arm(Object state, float pictureWidth, float pictureHeight, float yaw, float lift, float distance,
            int anchorCharge, float chargeShown, boolean enemyAnchor, float size, float blastFlash, int blastColor,
            float presence) {
        carrier = state;
        width = Math.max(1.0F, pictureWidth);
        height = Math.max(1.0F, pictureHeight);
        float rise = Math.max(-1.0F, Math.min(1.0F, lift)) * EYE_RANGE;
        float flat = (float) Math.sqrt(Math.max(0.25F, distance * distance - rise * rise));
        double turn = Math.toRadians(yaw);
        eyeX = 0.5F + (float) Math.sin(turn) * flat;
        eyeY = TARGET_Y + rise;
        eyeZ = 0.5F + (float) Math.cos(turn) * flat;
        charge = Math.max(0, Math.min(4, anchorCharge));
        shownCharge = Math.max(0.0F, Math.min(4.0F, chargeShown));
        enemy = enemyAnchor;
        // An enemy's anchor wears their skin only while the player gave them one, as in a fight.
        enemySkin = enemyAnchor && AnchorsConfig.settings().enemySkin.enabled;
        anchorSize = Math.max(0.0F, size);
        flash = Math.max(0.0F, Math.min(1.0F, blastFlash));
        flashColor = blastColor;
        shown = Math.max(0.0F, Math.min(1.0F, presence));
    }

    /** The anchor was just detonated: the chosen fade plays where it stood. */
    public static void fade(AnchorFade.Style style, long since, int glow) {
        fadeStyle = style;
        fadeSince = since;
        fadeSeed = since * 0x9E3779B97F4A7C15L;
        fadeGlow = glow;
    }

    /**
     * How many pixels a block of the anchor takes in a picture {@code pictureHeight} pixels tall,
     * seen from {@code distance}: for the effects the screen draws over the picture.
     */
    public static float pixelsPerBlock(float pictureHeight, float distance) {
        return pictureHeight / (2.0F * (float) Math.tan(Math.toRadians(FOV) / 2.0D) * Math.max(0.5F, distance));
    }

    /** How far below the picture's middle the anchor's own middle is, in blocks. */
    public static float anchorDrop() {
        return TARGET_Y - 0.5F;
    }

    /**
     * From the falling block's renderer: draws the stage when {@code state} is the one the preview
     * armed it with, and says so; any other falling block is none of its business.
     */
    public static boolean submit(Object state, PoseStack poses, SubmitNodeCollector collector) {
        if (state != carrier || state == null) {
            return false;
        }
        if (shown <= 0.01F) {
            return true;
        }
        Mc.perspective(FOV, width, height, 0.05F, 48.0F);
        poses.pushPose();
        poses.setIdentity();
        poses.mulPose(VIEW.setLookAt(eyeX, eyeY, eyeZ, 0.5F, TARGET_Y, 0.5F, 0.0F, 1.0F, 0.0F));
        if (solid == null) {
            solid = RenderTypes.beaconBeam(OBSIDIAN, false);
            soft = RenderTypes.beaconBeam(OBSIDIAN, true);
        }
        collector.submitCustomGeometry(poses, solid, SOLID_TILES);
        collector.submitCustomGeometry(poses, soft, SOFT_TILES);

        SkinCubeTexture skin = SkinCubeTexture.of(enemySkin);
        if (anchorSize > 0.01F && skin.prepare()) {
            poses.pushPose();
            poses.translate(0.5F, 0.0F, 0.5F);
            poses.scale(anchorSize, anchorSize, anchorSize);
            poses.translate(-0.5F, 0.0F, -0.5F);
            collector.submitCustomGeometry(poses, skin.unlit(), ANCHOR);
            poses.popPose();
            // The glow belongs to a whole anchor: it comes up as the anchor finishes growing.
            float grown = Math.min(1.0F, anchorSize);
            AnchorGlowRenderer.submitStage(poses, collector.order(2), SCENE, charge, enemy, grown * grown * grown * shown, eyeX,
                    eyeY, eyeZ);
        }
        if (flash > 0.01F) {
            AnchorGlowRenderer.submitStageFlash(poses, collector.order(2), SCENE, flashColor, flash * 1.7F * shown, eyeX, eyeY,
                    eyeZ);
        }
        if (fadeStyle != null) {
            float light = anchorLight(4.0F);
            for (int index = 0; index < 6; index++) {
                FADE_SHADE[index] = SHADE[index] * light;
            }
            if (!AnchorFade.submitStage(poses, collector.order(1), fadeStyle, enemySkin, 4, fadeGlow, fadeSince, fadeSeed,
                    FADE_SHADE)) {
                fadeStyle = null;
            }
        }
        poses.popPose();
        return true;
    }

    /**
     * The floor's top and the ceiling's underside, a tile to a block. Whole tiles go to the solid
     * material, which writes depth as the world's blocks do; the ones the fade touches go to the
     * translucent one.
     */
    private static void tiles(PoseStack.Pose pose, VertexConsumer consumer, boolean whole) {
        boolean entering = shown < 0.999F;
        for (int z = -REACH; z <= REACH; z++) {
            for (int x = -REACH; x <= REACH; x++) {
                // The farthest corner decides: a tile is whole when the fade has not reached any of it.
                float farX = Math.max(Math.abs(x - 0.5F), Math.abs(x + 0.5F));
                float farZ = Math.max(Math.abs(z - 0.5F), Math.abs(z + 0.5F));
                float far = (float) Math.sqrt(farX * farX + farZ * farZ);
                float nearX = Math.max(0.0F, Math.abs(x) - 0.5F);
                float nearZ = Math.max(0.0F, Math.abs(z) - 0.5F);
                if (nearX * nearX + nearZ * nearZ >= FADE_TO * FADE_TO) {
                    continue;
                }
                if ((far <= FADE_FROM && !entering) != whole) {
                    continue;
                }
                tile(pose, consumer, x, z, 0.0F, Direction.UP);
                tile(pose, consumer, x, z, CEILING_Y, Direction.DOWN);
            }
        }
    }

    private static void tile(PoseStack.Pose pose, VertexConsumer consumer, int x, int z, float y, Direction face) {
        for (float[] corner : CORNERS) {
            float[] point = SkinCubeTexture.facePoint(face, corner[0], corner[1]);
            float px = x + point[0];
            float pz = z + point[2];
            light(px, y, pz);
            consumer.addVertex(pose, px, y, pz)
                    .setColor(channel(LIGHT[0]), channel(LIGHT[1]), channel(LIGHT[2]), channel(LIGHT[3]))
                    .setUv(corner[0], corner[1]).setLight(FULL_BRIGHT).setNormal(0.0F, face.getStepY(), 0.0F);
        }
    }

    /**
     * The light of a point of the room into {@link #LIGHT}: red, green, blue and how solid it is.
     * The game's own curve of block light and its warm tint, from the light level the anchor's
     * charge gives there. Obsidian takes little of it, as in the game; what shows on it is the
     * glow's light, which is added on top.
     */
    private static void light(float x, float y, float z) {
        float dx = x - 0.5F;
        float dy = y - 0.5F;
        float dz = z - 0.5F;
        float flat = (float) Math.sqrt(dx * dx + dz * dz);
        float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float level = Math.max(0.0F, Math.min(15.0F, shownCharge * 3.75F - 0.4F - distance));
        float part = level / 15.0F;
        float curve = part / (4.0F - 3.0F * part);
        // Farther tiles sink into the dark before they fade out.
        float dim = 1.0F - 0.55F * smooth((flat - 2.0F) / (FADE_TO - 2.0F));
        float r = AMBIENT_R * dim + (1.0F - AMBIENT_R * dim) * curve;
        float g = AMBIENT_G * dim + (1.0F - AMBIENT_G * dim) * curve * ((curve * 0.6F + 0.4F) * 0.6F + 0.4F);
        float b = AMBIENT_B * dim + (1.0F - AMBIENT_B * dim) * curve * (curve * curve * 0.6F + 0.4F);
        LIGHT[0] = r;
        LIGHT[1] = g;
        LIGHT[2] = b;
        LIGHT[3] = shown * (1.0F - smooth((flat - FADE_FROM) / (FADE_TO - FADE_FROM)));
    }

    /** The anchor: its six faces with the skin, shaded by direction, lit by its own charge. */
    private static void anchor(PoseStack.Pose pose, VertexConsumer consumer) {
        SkinCubeTexture skin = SkinCubeTexture.of(enemySkin);
        int frame = skin.frame(charge);
        float light = anchorLight(shownCharge);
        for (Direction direction : DIRECTIONS) {
            if (direction == Direction.DOWN) {
                // It stands on the floor.
                continue;
            }
            int value = channel(SHADE[direction.ordinal()] * light);
            for (float[] corner : CORNERS) {
                float[] point = SkinCubeTexture.facePoint(direction, corner[0], corner[1]);
                consumer.addVertex(pose, point[0], point[1], point[2]).setColor(value, value, value, 255)
                        .setUv(skin.u(direction, charge, frame, corner[0]), skin.v(direction, charge, corner[1]))
                        .setLight(FULL_BRIGHT).setNormal(direction.getStepX(), direction.getStepY(), direction.getStepZ());
            }
        }
    }

    /** How bright the anchor's own faces are with {@code charges} in it: its light on itself. */
    private static float anchorLight(float charges) {
        float part = Math.max(0.0F, Math.min(15.0F, charges * 3.75F - 0.5F)) / 15.0F;
        return ANCHOR_AMBIENT + (1.0F - ANCHOR_AMBIENT) * part / (4.0F - 3.0F * part);
    }

    private static float smooth(float value) {
        float t = Math.max(0.0F, Math.min(1.0F, value));
        return t * t * (3.0F - 2.0F * t);
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0F)));
    }
}
