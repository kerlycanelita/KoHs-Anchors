package dev.zymekoh.kohsanchors.glow;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import dev.zymekoh.kohsanchors.skin.AnchorVariant;
import dev.zymekoh.kohsanchors.skin.ColorMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/**
 * The glow per light: the light of every lit pixel of the anchor, spread softly round it in its own
 * colour and kept in one texture. Each face of the block wears its tile as a sheet half a face
 * wider than itself, so the glow follows the texture's own shapes, the top, the veins and the
 * charge lights each where they are, and passes the block's outline where they reach its edge.
 *
 * <p>A tile is the face with a quarter of a face of margin all round, and the light of a pixel is
 * gone exactly that far from it: a sheet never ends in light. The first row of tiles is in the
 * lights' own colours, the second in white, for a glow of one chosen colour. A tile is baked again
 * only when what glows on its texture changes.</p>
 *
 * <p>It is drawn with Vanilla's beacon beam material, the one the settings screen's stage uses: a
 * texture times a colour, blended over what is behind, tested against depth and writing none. The
 * glow is laid over the scene and not added to it, so it keeps its colour in daylight and never
 * burns out to white.</p>
 */
final class GlowSheet {
    /** How far past each edge of a face its sheet reaches, in faces. */
    private static final float BORDER = 0.25F;
    /** Texels across a face, across the margin and across a tile. */
    private static final int FACE = 96;
    private static final int MARGIN = Math.round(FACE * BORDER);
    private static final int TILE = FACE + 2 * MARGIN;
    private static final AnchorVariant[] VARIANTS = AnchorVariant.values();
    private static final int WIDTH = TILE * VARIANTS.length;
    private static final int HEIGHT = TILE * 2;
    /** The sheet stands this far off the face: over the lit pixels' own shine, under nothing. */
    private static final float OFFSET = 0.012F;
    private static final int FULL_BRIGHT = 0xF000F0;
    /**
     * A pixel's light as it spreads, in faces: tight round the pixel, and a soft skirt; how much of
     * the light goes to each.
     */
    private static final float[] REACH = {0.045F, 0.11F};
    private static final float[] SHARE = {0.6F, 0.4F};
    /** How fast the glow fills up with light, and the most it covers what is under it. */
    private static final float GAIN = 4.5F;
    private static final float COVER = 0.92F;
    /** How much of that is left over a lit pixel itself: it shines already, and its texture should show. */
    private static final float OVER_LIGHT = 0.5F;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final float[][] CORNERS = {{0.0F, 0.0F}, {0.0F, 1.0F}, {1.0F, 1.0F}, {1.0F, 0.0F}};
    /** A sheet across its face: its rim, the face's two edges, its other rim; and how wide that is. */
    private static final float[] EDGES = {-BORDER, 0.0F, 1.0F, 1.0F + BORDER};
    private static final float SPAN = 1.0F + 2.0F * BORDER;
    private static final Identifier ID = Identifier.fromNamespaceAndPath(KoHsAnchorsClient.MOD_ID, "glow_sheet");

    private static DynamicTexture texture;
    private static RenderType material;
    /** Per variant: whether its tile is baked, and from which pixels. */
    private static final boolean[] BAKED = new boolean[VARIANTS.length];
    private static final int[] STAMP = new int[VARIANTS.length];

    private GlowSheet() {
    }

    /** The material the sheets are drawn with; null until a tile is ready. */
    static RenderType material() {
        return material;
    }

    /** How much of its glow a sheet shows: all of it from about twice the default settings up. */
    static float strength(float intensity, float bloom) {
        return Math.min(1.0F, 0.7F * intensity * (0.4F + 0.6F * Math.min(3.0F, bloom)));
    }

    /** Whether {@code variant} glows, with its tile baked from the pixels it has now. */
    static boolean ready(AnchorVariant variant) {
        GlowGeometry.FaceGlow face = GlowGeometry.get(variant);
        if (face == null || !face.glows) {
            return false;
        }
        if (texture == null) {
            texture = new DynamicTexture(() -> "KoHs Anchor's glow", WIDTH, HEIGHT, true);
            Minecraft.getInstance().getTextureManager().register(ID, texture);
            material = RenderTypes.beaconBeam(ID, true);
        }
        int column = variant.ordinal();
        if (!BAKED[column] || STAMP[column] != face.stamp) {
            NativeImage pixels = texture.getPixels();
            if (pixels == null) {
                return false;
            }
            bake(pixels, face, column);
            texture.upload();
            BAKED[column] = true;
            STAMP[column] = face.stamp;
        }
        return true;
    }

    /**
     * Spreads the lit pixels of a texture over its tile. Each gives its colour, as much as it is
     * bright; the light is blurred along the rows and then down the columns, once for each reach.
     */
    private static void bake(NativeImage pixels, GlowGeometry.FaceGlow face, int column) {
        int width = face.width;
        int height = face.height;
        // Amount of light, and red, green and blue weighted by it.
        float[][] source = new float[4][width * height];
        for (int index = 0; index < width * height; index++) {
            if (!face.lit[index]) {
                continue;
            }
            float weight = 0.35F + 0.65F * Math.max(face.red[index], Math.max(face.green[index], face.blue[index]));
            source[0][index] = weight;
            source[1][index] = weight * face.red[index];
            source[2][index] = weight * face.green[index];
            source[3][index] = weight * face.blue[index];
        }
        float[][] sum = new float[4][TILE * TILE];
        float[] rows = new float[TILE * height];
        for (int reach = 0; reach < REACH.length; reach++) {
            Spread across = new Spread(width, REACH[reach]);
            Spread down = width == height ? across : new Spread(height, REACH[reach]);
            for (int channel = 0; channel < 4; channel++) {
                float[] from = source[channel];
                for (int y = 0; y < height; y++) {
                    for (int i = 0; i < TILE; i++) {
                        float light = 0.0F;
                        for (int x = across.first[i]; x < across.end[i]; x++) {
                            light += from[y * width + x] * across.weight[i * width + x];
                        }
                        rows[y * TILE + i] = light;
                    }
                }
                float[] into = sum[channel];
                for (int j = 0; j < TILE; j++) {
                    for (int i = 0; i < TILE; i++) {
                        float light = 0.0F;
                        for (int y = down.first[j]; y < down.end[j]; y++) {
                            light += rows[y * TILE + i] * down.weight[j * height + y];
                        }
                        into[j * TILE + i] += light * SHARE[reach];
                    }
                }
            }
        }
        for (int j = 0; j < TILE; j++) {
            for (int i = 0; i < TILE; i++) {
                int texel = j * TILE + i;
                float amount = sum[0][texel];
                float most = Math.max(sum[1][texel], Math.max(sum[2][texel], sum[3][texel]));
                int x = Math.floorDiv((i - MARGIN) * width, FACE);
                int y = Math.floorDiv((j - MARGIN) * height, FACE);
                boolean onLight = x >= 0 && y >= 0 && x < width && y < height && face.lit[y * width + x];
                int alpha = amount <= 1.0E-4F || most <= 0.0F ? 0
                        : Math.round(COVER * (onLight ? OVER_LIGHT : 1.0F) * (1.0F - (float) Math.exp(-GAIN * amount)) * 255.0F);
                // The colour as bright as it goes: how much of it there is, is the alpha's to say.
                int colour = alpha == 0 ? 0xFFFFFF : ColorMath.fromLinear(sum[1][texel] / most) << 16
                        | ColorMath.fromLinear(sum[2][texel] / most) << 8 | ColorMath.fromLinear(sum[3][texel] / most);
                pixels.setPixel(column * TILE + i, j, alpha << 24 | colour);
                pixels.setPixel(column * TILE + i, TILE + j, alpha << 24 | 0xFFFFFF);
            }
        }
    }

    /**
     * How much of each pixel along one axis of a texture reaches each texel along a tile: a bell
     * {@code reach} wide, brought down to nothing {@link #BORDER} away, so the light of a pixel on
     * the face's edge is gone exactly at the tile's. A row lit from end to end adds up to one.
     */
    private static final class Spread {
        final float[] weight;
        /** Per texel, the pixels that reach it: from {@code first} up to {@code end}. */
        final int[] first = new int[TILE];
        final int[] end = new int[TILE];

        Spread(int size, float reach) {
            this.weight = new float[TILE * size];
            double whole = 0.0D;
            for (int step = -500; step < 500; step++) {
                whole += bell((step + 0.5F) * BORDER / 500.0F, reach) * BORDER / 500.0D;
            }
            for (int i = 0; i < TILE; i++) {
                float at = (i + 0.5F) / FACE - BORDER;
                this.first[i] = size;
                for (int x = 0; x < size; x++) {
                    float value = bell(at - (x + 0.5F) / size, reach);
                    if (value <= 0.0F) {
                        continue;
                    }
                    this.weight[i * size + x] = (float) (value / (whole * size));
                    this.first[i] = Math.min(this.first[i], x);
                    this.end[i] = x + 1;
                }
            }
        }

        private static float bell(float distance, float reach) {
            float part = distance / BORDER;
            if (part <= -1.0F || part >= 1.0F) {
                return 0.0F;
            }
            float window = 1.0F - part * part;
            return (float) Math.exp(-distance * distance / (2.0F * reach * reach)) * window * window;
        }
    }

    /**
     * The sheets of one anchor: a quad for each face the camera sees, in the lights' own colours, or
     * all in {@code override} when it is not 0.
     */
    record Draw(int charge, int exposed, float strength, int override, float cameraX, float cameraY, float cameraZ)
            implements SubmitNodeCollector.CustomGeometryRenderer {
        @Override
        public void render(PoseStack.Pose pose, VertexConsumer consumer) {
            int alpha = Math.max(0, Math.min(255, Math.round(this.strength * 255.0F)));
            int red = this.override != 0 ? this.override >> 16 & 255 : 255;
            int green = this.override != 0 ? this.override >> 8 & 255 : 255;
            int blue = this.override != 0 ? this.override & 255 : 255;
            int row = this.override != 0 ? 1 : 0;
            for (Direction direction : DIRECTIONS) {
                if ((this.exposed & 1 << direction.ordinal()) == 0 || !facesCamera(direction)) {
                    continue;
                }
                AnchorVariant variant = direction == Direction.UP ? AnchorVariant.top(this.charge)
                        : direction == Direction.DOWN ? AnchorVariant.BOTTOM : AnchorVariant.side(this.charge);
                int column = variant.ordinal();
                GlowGeometry.FaceGlow face = GlowGeometry.get(variant);
                if (face == null || !face.glows || !BAKED[column]) {
                    continue;
                }
                // How squarely the camera looks at the face. A sheet seen along its own plane is a
                // line of light sticking out of the block, so what is past the face's edge goes out
                // as the face turns away; what is on the face itself stays.
                float[] middle = SkinCubeTexture.facePoint(direction, 0.5F, 0.5F);
                float toX = this.cameraX - middle[0];
                float toY = this.cameraY - middle[1];
                float toZ = this.cameraZ - middle[2];
                float cosine = (toX * direction.getStepX() + toY * direction.getStepY() + toZ * direction.getStepZ())
                        / Math.max(1.0E-3F, (float) Math.sqrt(toX * toX + toY * toY + toZ * toZ));
                float turned = Math.max(0.0F, Math.min(1.0F, (cosine - 0.1F) / 0.4F));
                int past = Math.round(alpha * turned * turned * (3.0F - 2.0F * turned));
                // Nine pieces: the face, and the margin round it.
                for (int piece = 0; piece < 9; piece++) {
                    int shown = piece == 4 ? alpha : past;
                    if (shown == 0) {
                        continue;
                    }
                    for (float[] corner : CORNERS) {
                        float u = EDGES[piece % 3 + (int) corner[0]];
                        float v = EDGES[piece / 3 + (int) corner[1]];
                        float[] point = SkinCubeTexture.facePoint(direction, u, v);
                        // Half a texel in from the tile's edge, so no neighbour's texel is ever read.
                        consumer.addVertex(pose, point[0] + direction.getStepX() * OFFSET,
                                        point[1] + direction.getStepY() * OFFSET, point[2] + direction.getStepZ() * OFFSET)
                                .setColor(red, green, blue, shown)
                                .setUv((column * TILE + 0.5F + (u + BORDER) / SPAN * (TILE - 1)) / WIDTH,
                                        (row * TILE + 0.5F + (v + BORDER) / SPAN * (TILE - 1)) / HEIGHT)
                                .setLight(FULL_BRIGHT)
                                .setNormal(direction.getStepX(), direction.getStepY(), direction.getStepZ());
                    }
                }
            }
        }

        /** Whether the camera is in front of this face of the anchor block (the cube from 0 to 1). */
        private boolean facesCamera(Direction direction) {
            return switch (direction) {
                case UP -> this.cameraY > 1.0F;
                case DOWN -> this.cameraY < 0.0F;
                case NORTH -> this.cameraZ < 0.0F;
                case SOUTH -> this.cameraZ > 1.0F;
                case WEST -> this.cameraX < 0.0F;
                case EAST -> this.cameraX > 1.0F;
            };
        }
    }
}
