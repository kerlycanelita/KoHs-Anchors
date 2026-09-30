package dev.zymekoh.kohsanchors.glow;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.zymekoh.kohsanchors.compat.GlowMaterial;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.predict.AnchorVeil;
import dev.zymekoh.kohsanchors.skin.AnchorVariant;
import dev.zymekoh.kohsanchors.skin.AtlasSkin;
import dev.zymekoh.kohsanchors.skin.ColorMath;
import dev.zymekoh.kohsanchors.skin.SkinComposer;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/**
 * The anchor glow: light that behaves like light, not a halo.
 *
 * <p>Three layers, all additive, all tested against depth so walls hide them:</p>
 * <ul>
 *   <li><b>Emissive pixels.</b> The anchor's own lit pixels shine at full strength, in their own
 *   colours, even in the dark: the portal, the veins and each charge light.</li>
 *   <li><b>Bloom.</b> Light spreads from those pixels across the face and past its edges, shaped by
 *   the pixels themselves ({@link GlowGeometry}).</li>
 *   <li><b>Bounce light.</b> The floor, the walls and the ceiling around the anchor are lit in the
 *   glow's colour, falling off with distance and with the angle each surface turns to the anchor,
 *   and only where the anchor can see them.</li>
 * </ul>
 *
 * <p>The glow grows with the charge, breathes slowly, and fades out between 48 and 64 blocks.
 * Anchors another player placed glow in the enemy colour. Everything is built from caches: per
 * frame this only multiplies vertices.</p>
 *
 * <p>Vertices are the whole cost (writing them is where the frame goes), so as few as possible are
 * written: anchors outside the view are skipped, and so is every face, bloom and bounce light that
 * turns its back to the camera; the nearest anchors get the full glow and the rest a coarser bloom
 * that looks the same from where they are; only the nearest few light the blocks around them; and
 * past a budget, the rest of the frame's anchors take the lightest glow. Measured with 64 charged
 * anchors in view: from 353 thousand vertices a frame to a fraction of that.</p>
 */
public final class AnchorGlowRenderer {
    private static final double FADE_START = 48.0D;
    private static final double MAX_DISTANCE = 64.0D;
    private static final float FACE_OFFSET = 0.0025F;
    private static final float BLOOM_OFFSET = 0.018F;
    private static final float SPILL_OFFSET = 0.005F;
    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * Where the glow steps down, in blocks, per quality (performance, balanced, quality): full,
     * half, a third and a quarter of the bloom's cells.
     */
    private static final double[][] LOD_DISTANCE = {{4.0D, 12.0D, 24.0D}, {8.0D, 20.0D, 36.0D}, {16.0D, 32.0D, 48.0D}};
    private static final int[] LOD_STRIDE = {1, 2, 3, 4};
    /** Roughly the vertices one anchor costs at each level, for the frame budget. */
    private static final int[] LOD_VERTICES = {3400, 1100, 420, 240};
    /** Past this many vertices in a frame, the remaining anchors take the lightest glow. */
    private static final int[] VERTEX_BUDGET = {40_000, 90_000, 200_000};
    /** Only the nearest anchors light the blocks around them, and only this close. */
    private static final int[] SPILL_ANCHORS = {3, 8, 16};
    private static final double[] SPILL_DISTANCE = {10.0D, 16.0D, 24.0D};
    /** Bounce light built at most this many times a frame: never a burst of rebuilds in one frame. */
    private static final int SPILL_BUILDS_PER_FRAME = 2;
    private static final int MAX_CANDIDATES = 256;

    private static final long[] CANDIDATE_KEYS = new long[MAX_CANDIDATES];
    private static final double[] CANDIDATE_DISTANCE = new double[MAX_CANDIDATES];
    private static final int[] CANDIDATE_ORDER = new int[MAX_CANDIDATES];
    private static final byte[] CANDIDATE_OWNER = new byte[MAX_CANDIDATES];
    private static final int[] CANDIDATE_CHARGE = new int[MAX_CANDIDATES];
    private static int spillBuildsThisFrame;

    private static final Long2ObjectOpenHashMap<SpillLight> SPILL = new Long2ObjectOpenHashMap<>();
    private static final BlockPos.MutableBlockPos CURSOR = new BlockPos.MutableBlockPos();
    private static final BlockPos.MutableBlockPos NEIGHBOR = new BlockPos.MutableBlockPos();
    private static ClientLevel spillLevel;
    private static int textureColorGeneration = Integer.MIN_VALUE;
    /** Anchors submitted in the last frame, and faces and quads drawn: for the developer view. */
    private static int submittedAnchors;
    private static int drawnFaces;
    private static int drawnQuads;
    private static int lastSubmitted;
    private static int lastFaces;
    private static int lastQuads;
    private static final int[] TEXTURE_COLORS = new int[5];

    private AnchorGlowRenderer() {
    }

    /** Called where the frame's block entities are submitted, with the camera's position and turn. */
    public static void submit(PoseStack poses, SubmitNodeCollector collector, Vec3 camera, Quaternionfc orientation) {
        AnchorsConfig.Glow glow = AnchorsConfig.settings().glow;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        lastSubmitted = submittedAnchors;
        lastFaces = drawnFaces;
        lastQuads = drawnQuads;
        submittedAnchors = 0;
        drawnFaces = 0;
        drawnQuads = 0;
        if (!glow.enabled || level == null || AnchorTracker.count() == 0 || glow.power <= 0) {
            return;
        }
        if (level != spillLevel) {
            SPILL.clear();
            spillLevel = level;
        }
        AnchorsConfig.EnemyGlow enemy = AnchorsConfig.settings().enemyGlow;
        long now = System.nanoTime();
        double seconds = now / 1_000_000_000.0D;
        spillBuildsThisFrame = 0;

        // The view: a cone around where the camera looks, as wide as the screen's diagonal with
        // room for sprinting and zoom mods; an anchor whose light cannot reach it is not drawn.
        Vector3f forward = orientation.transform(new Vector3f(0.0F, 0.0F, -1.0F));
        double forwardX = forward.x();
        double forwardY = forward.y();
        double forwardZ = forward.z();
        double halfVertical = Math.toRadians(Math.min(160.0D, minecraft.options.fov().get() * 1.3D)) / 2.0D;
        double aspect = minecraft.getWindow().getWidth() / (double) Math.max(1, minecraft.getWindow().getHeight());
        double halfAngle = Math.atan(Math.tan(halfVertical) * Math.sqrt(1.0D + aspect * aspect));
        int quality = glow.quality;
        double[] lod = LOD_DISTANCE[quality];
        int budget = VERTEX_BUDGET[quality];
        int spillAnchors = SPILL_ANCHORS[quality];
        double spillDistance = SPILL_DISTANCE[quality];

        int count = 0;
        for (Long2ByteMap.Entry entry : AnchorTracker.anchors().long2ByteEntrySet()) {
            if (count == MAX_CANDIDATES) {
                break;
            }
            long key = entry.getLongKey();
            double cx = BlockPos.getX(key) + 0.5D - camera.x;
            double cy = BlockPos.getY(key) + 0.5D - camera.y;
            double cz = BlockPos.getZ(key) + 0.5D - camera.z;
            double distance = Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (distance > MAX_DISTANCE) {
                continue;
            }
            // Near anchors light the blocks up to four blocks away, so their sphere is wider.
            double radius = distance < spillDistance ? 4.0D : 1.6D;
            if (distance > radius) {
                double cosine = (cx * forwardX + cy * forwardY + cz * forwardZ) / distance;
                double angle = Math.acos(Math.max(-1.0D, Math.min(1.0D, cosine)));
                if (angle > halfAngle + Math.asin(radius / distance)) {
                    continue;
                }
            }
            CURSOR.set(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
            // What is drawn there: a detonated anchor the veil hides gives no light, a charge the
            // chain drew ahead of the server gives the light of that charge.
            BlockState shown = AnchorVeil.predicted(CURSOR);
            BlockState state = shown != null ? shown : level.getBlockState(CURSOR);
            if (!state.is(Blocks.RESPAWN_ANCHOR)) {
                continue;
            }
            int charge = state.getValue(RespawnAnchorBlock.CHARGE);
            if (charge <= 0) {
                continue;
            }
            CANDIDATE_KEYS[count] = key;
            CANDIDATE_DISTANCE[count] = distance;
            CANDIDATE_OWNER[count] = entry.getByteValue();
            CANDIDATE_CHARGE[count] = charge;
            CANDIDATE_ORDER[count] = count;
            count++;
        }
        IntArrays.quickSort(CANDIDATE_ORDER, 0, count,
                (a, b) -> Double.compare(CANDIDATE_DISTANCE[a], CANDIDATE_DISTANCE[b]));

        int vertices = 0;
        int spillers = 0;
        for (int rank = 0; rank < count; rank++) {
            int index = CANDIDATE_ORDER[rank];
            long key = CANDIDATE_KEYS[index];
            double distance = CANDIDATE_DISTANCE[index];
            int charge = CANDIDATE_CHARGE[index];
            CURSOR.set(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
            double dx = CURSOR.getX() - camera.x;
            double dy = CURSOR.getY() - camera.y;
            double dz = CURSOR.getZ() - camera.z;
            int detail = distance < lod[0] ? 0 : distance < lod[1] ? 1 : distance < lod[2] ? 2 : 3;
            if (vertices > budget) {
                detail = 3;
            } else if (vertices > budget / 2) {
                detail = Math.max(detail, 2);
            }
            float intensity = glow.power / 100.0F;
            if (glow.chargeScaling) {
                intensity *= 0.4F + 0.6F * charge / 4.0F;
            }
            if (glow.pulse) {
                // Each anchor breathes on its own phase, so a row of them does not pulse in step.
                intensity *= 0.86F + 0.14F * (float) Math.sin(seconds * 2.3D + (key * 0.37D) % 6.283D);
            }
            if (distance > FADE_START) {
                intensity *= (float) ((MAX_DISTANCE - distance) / (MAX_DISTANCE - FADE_START));
            }
            boolean isEnemy = CANDIDATE_OWNER[index] == AnchorTracker.ENEMY && enemy.enabled;
            int override = isEnemy ? enemy.color : glow.source == AnchorsConfig.Glow.SOURCE_CUSTOM ? glow.color : 0;
            int spillColor = override != 0 ? override : textureColor(charge);
            int exposed = exposure(level, CURSOR);
            boolean lights = glow.spill > 0 && detail <= 1 && spillers < spillAnchors && distance < spillDistance;
            SpillLight spill = lights ? spill(level, key, now) : null;
            if (spill != null) {
                spillers++;
                // About half of the bounce light turns its back to the camera and is skipped.
                vertices += spill.quads * 3;
            }
            vertices += LOD_VERTICES[detail];
            submittedAnchors++;
            poses.pushPose();
            poses.translate(dx, dy, dz);
            collector.submitCustomGeometry(poses, GlowMaterial.glow(), new Draw(charge, exposed, intensity, override,
                    glow.emissive, glow.bloom / 100.0F, glow.spill / 100.0F, spillColor, spill, detail,
                    (float) -dx, (float) -dy, (float) -dz));
            poses.popPose();
        }
    }

    /** "anchors, faces, quads" of the last frame, for the developer view. */
    public static String frameStats() {
        return lastSubmitted + " anchors, " + lastFaces + " faces, " + lastQuads + " vertices";
    }

    /**
     * A block changed in the client world (a server update, an explosion, the player's own
     * prediction). Bounce light is cached per anchor, so every cache that could reach the block is
     * dropped and built again on its anchor's next frame: light never stays on the faces of blocks
     * an explosion destroyed, and a face the change uncovered is lit at once. Only a change of
     * shape counts: full block or not, anchor or not.
     */
    public static void blockChanged(BlockPos position, BlockState before, BlockState after) {
        if (SPILL.isEmpty() || before == after
                || before.isSolidRender() == after.isSolidRender()
                && before.is(Blocks.RESPAWN_ANCHOR) == after.is(Blocks.RESPAWN_ANCHOR)) {
            return;
        }
        int x = position.getX();
        int y = position.getY();
        int z = position.getZ();
        int reach = SpillLight.RADIUS + 1;
        ObjectIterator<Long2ObjectMap.Entry<SpillLight>> iterator = SPILL.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            long key = iterator.next().getLongKey();
            if (Math.abs(BlockPos.getX(key) - x) <= reach && Math.abs(BlockPos.getY(key) - y) <= reach
                    && Math.abs(BlockPos.getZ(key) - z) <= reach) {
                iterator.remove();
            }
        }
    }

    /** Once in a while, bounce light that no anchor uses any more is forgotten. */
    public static void tick(long gameTime) {
        if (gameTime % 40L == 0L && !SPILL.isEmpty()) {
            long now = System.nanoTime();
            SPILL.long2ObjectEntrySet().removeIf(entry -> now - entry.getValue().builtAt > 4_000_000_000L);
        }
    }

    /**
     * The colour the player's own anchors glow in at a charge, as the world draws it: the custom
     * colour, or the one their lit pixels average to. The settings screen's previews use it.
     */
    public static int ownColor(int charge) {
        AnchorsConfig.Glow glow = AnchorsConfig.settings().glow;
        return glow.source == AnchorsConfig.Glow.SOURCE_CUSTOM ? glow.color : textureColor(charge);
    }

    /** The glow's colour for the player's own anchors with the texture as its source. */
    private static int textureColor(int charge) {
        int generation = AtlasSkin.generation();
        if (generation != textureColorGeneration) {
            textureColorGeneration = generation;
            SkinComposer composer = AtlasSkin.composer();
            for (int index = 0; index <= 4; index++) {
                TEXTURE_COLORS[index] = composer == null ? 0xFFB14DFF
                        : composer.averageGlowColor(AnchorsConfig.settings().skin, index);
            }
        }
        return TEXTURE_COLORS[Math.max(0, Math.min(4, charge))];
    }

    /** One bit per direction whose face no full block covers. */
    private static int exposure(ClientLevel level, BlockPos position) {
        int mask = 0;
        for (Direction direction : DIRECTIONS) {
            NEIGHBOR.setWithOffset(position, direction);
            if (!level.getBlockState(NEIGHBOR).isSolidRender()) {
                mask |= 1 << direction.ordinal();
            }
        }
        return mask;
    }

    private static SpillLight spill(ClientLevel level, long key, long now) {
        SpillLight light = SPILL.get(key);
        // Block changes drop the cache at once (blockChanged); the time limit only catches what
        // arrives without one, such as a chunk sent again, and differs per anchor so the refreshes
        // do not all land on one frame. A few builds a frame at most: an anchor waiting for its
        // first one draws without bounce light meanwhile, never with a stale one after a change.
        long lifetime = 1_000_000_000L + Math.floorMod(key * 0x9E3779B97F4A7C15L, 400L) * 1_000_000L;
        if ((light == null || now - light.builtAt > lifetime) && spillBuildsThisFrame < SPILL_BUILDS_PER_FRAME) {
            spillBuildsThisFrame++;
            light = SpillLight.build(level, BlockPos.of(key), now);
            SPILL.put(key, light);
        }
        return light;
    }

    /** One anchor's glow, drawn later in the frame from what was captured now. */
    private record Draw(int charge, int exposed, float intensity, int override, boolean emissive, float bloom,
            float spill, int spillColor, SpillLight spillLight, int detail, float cameraX, float cameraY, float cameraZ)
            implements SubmitNodeCollector.CustomGeometryRenderer {
        @Override
        public void render(PoseStack.Pose pose, VertexConsumer consumer) {
            Matrix4f matrix = pose.pose();
            for (Direction direction : DIRECTIONS) {
                if ((this.exposed & 1 << direction.ordinal()) == 0 || !facesCamera(direction)) {
                    continue;
                }
                AnchorVariant variant = direction == Direction.UP ? AnchorVariant.top(this.charge)
                        : direction == Direction.DOWN ? AnchorVariant.BOTTOM : AnchorVariant.side(this.charge);
                GlowGeometry.FaceGlow face = GlowGeometry.get(variant);
                if (face == null || !face.glows) {
                    continue;
                }
                drawnFaces++;
                // The lit pixels' own shine only reads from close; farther out the bloom carries it.
                if (this.emissive && this.detail <= 1) {
                    emitRuns(matrix, consumer, direction, face);
                }
                if (this.bloom > 0.0F) {
                    emitBloom(matrix, consumer, direction, face);
                }
            }
            if (this.spillLight != null && this.spill > 0.0F) {
                emitSpill(matrix, consumer);
            }
        }

        private void emitRuns(Matrix4f matrix, VertexConsumer consumer, Direction direction, GlowGeometry.FaceGlow face) {
            float strength = this.intensity * 0.5F;
            float[] runs = face.runs;
            for (int run = 0; run < face.runCount; run++) {
                int offset = run * 8;
                int color = runColor(runs, offset);
                quad(matrix, consumer, direction, runs[offset], runs[offset + 1], runs[offset + 2], runs[offset + 3],
                        FACE_OFFSET, color, strength, strength, strength, strength);
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

        private int runColor(float[] runs, int offset) {
            if (this.override != 0) {
                // A chosen colour keeps the pixels' own brightness pattern.
                float light = Math.min(1.0F, 0.35F + runs[offset + 7] * 1.6F);
                return scale(this.override, light);
            }
            return 0xFF000000 | Math.round(runs[offset + 4] * 255.0F) << 16 | Math.round(runs[offset + 5] * 255.0F) << 8
                    | Math.round(runs[offset + 6] * 255.0F);
        }

        private void emitBloom(Matrix4f matrix, VertexConsumer consumer, Direction direction, GlowGeometry.FaceGlow face) {
            float[] map = face.bloom;
            int grid = GlowGeometry.GRID;
            // Farther anchors merge cells: the light map is smooth, so a coarser grid of the same
            // map looks the same from there for a fraction of the vertices.
            int stride = LOD_STRIDE[this.detail];
            float cell = 1.0F / GlowGeometry.CELLS;
            float size = cell * stride;
            float origin = -GlowGeometry.BORDER * cell;
            float strength = this.intensity * this.bloom * 0.7F;
            for (int gy = 0; gy + stride < grid; gy += stride) {
                for (int gx = 0; gx + stride < grid; gx += stride) {
                    int i00 = (gy * grid + gx) * 4;
                    int i10 = (gy * grid + gx + stride) * 4;
                    int i01 = ((gy + stride) * grid + gx) * 4;
                    int i11 = ((gy + stride) * grid + gx + stride) * 4;
                    float l00 = map[i00 + 3];
                    float l10 = map[i10 + 3];
                    float l01 = map[i01 + 3];
                    float l11 = map[i11 + 3];
                    if (l00 + l10 + l01 + l11 < 0.02F) {
                        continue;
                    }
                    float u0 = origin + gx * cell;
                    float v0 = origin + gy * cell;
                    int color = this.override != 0 ? this.override : face.bloomColour(stride, gx, gy);
                    quad(matrix, consumer, direction, u0, v0, u0 + size, v0 + size, BLOOM_OFFSET, color,
                            fade(direction, v0, l00) * strength, fade(direction, v0, l10) * strength,
                            fade(direction, v0 + size, l01) * strength, fade(direction, v0 + size, l11) * strength);
                }
            }
        }

        /**
         * A vertex's light, faded where a side face's bloom reaches below the anchor's base, so it
         * meets the ground already dark instead of being cut off by it.
         */
        private static float fade(Direction direction, float v, float light) {
            float value = Math.min(1.0F, light);
            if (direction.getAxis().isHorizontal() && v > 1.0F) {
                float t = Math.max(0.0F, 1.0F - (v - 1.0F) / 0.22F);
                value *= t * t;
            }
            return value;
        }

        private void emitSpill(Matrix4f matrix, VertexConsumer consumer) {
            float[] vertices = this.spillLight.vertices;
            float strength = this.intensity * this.spill * 1.25F;
            int color = this.spillColor;
            int r = (color >> 16) & 255;
            int g = (color >> 8) & 255;
            int b = color & 255;
            byte[] faces = this.spillLight.faces;
            float[] planes = this.spillLight.planes;
            for (int quad = 0; quad < this.spillLight.quads; quad++) {
                // A lit face the camera sees from behind is inside a block: skipped.
                if (!inFront(faces[quad], planes[quad])) {
                    continue;
                }
                int offset = quad * 16;
                float w0 = vertices[offset + 3] * strength;
                float w1 = vertices[offset + 7] * strength;
                float w2 = vertices[offset + 11] * strength;
                float w3 = vertices[offset + 15] * strength;
                if (w0 + w1 + w2 + w3 < 0.01F) {
                    continue;
                }
                vertex(matrix, consumer, vertices, offset, r, g, b, w0);
                vertex(matrix, consumer, vertices, offset + 4, r, g, b, w1);
                vertex(matrix, consumer, vertices, offset + 8, r, g, b, w2);
                vertex(matrix, consumer, vertices, offset, r, g, b, w0);
                vertex(matrix, consumer, vertices, offset + 8, r, g, b, w2);
                vertex(matrix, consumer, vertices, offset + 12, r, g, b, w3);
            }
        }

        /** Whether the camera is on the side a surround face of this direction, at this plane, turns to. */
        private boolean inFront(byte face, float plane) {
            return switch (face) {
                case 1 -> this.cameraY > plane;
                case 0 -> this.cameraY < plane;
                case 2 -> this.cameraZ < plane;
                case 3 -> this.cameraZ > plane;
                case 4 -> this.cameraX < plane;
                default -> this.cameraX > plane;
            };
        }

        private static void vertex(Matrix4f matrix, VertexConsumer consumer, float[] vertices, int offset, int r, int g,
                int b, float light) {
            int alpha = Math.max(0, Math.min(255, Math.round(light * 255.0F)));
            consumer.addVertex(matrix, vertices[offset], vertices[offset + 1], vertices[offset + 2]).setColor(r, g, b, alpha);
        }
    }

    /**
     * A rectangle on a face of the anchor, from face coordinates (u to the right, v down, 0 to 1
     * across the face as its texture is mapped) to block coordinates, as two triangles wound
     * counter-clockwise seen from outside, the side the material does not cull.
     */
    private static void quad(Matrix4f matrix, VertexConsumer consumer, Direction direction, float u0, float v0, float u1,
            float v1, float offset, int color, float a00, float a10, float a01, float a11) {
        corner(matrix, consumer, direction, u0, v0, offset, color, a00);
        corner(matrix, consumer, direction, u0, v1, offset, color, a01);
        corner(matrix, consumer, direction, u1, v1, offset, color, a11);
        corner(matrix, consumer, direction, u0, v0, offset, color, a00);
        corner(matrix, consumer, direction, u1, v1, offset, color, a11);
        corner(matrix, consumer, direction, u1, v0, offset, color, a10);
    }

    /** One vertex at face coordinates {@code u, v}, pushed {@code offset} out of the face. */
    private static void corner(Matrix4f matrix, VertexConsumer consumer, Direction direction, float u, float v,
            float offset, int color, float light) {
        drawnQuads++;
        float x;
        float y;
        float z;
        switch (direction) {
            case UP -> { x = u; y = 1.0F + offset; z = v; }
            case DOWN -> { x = u; y = -offset; z = 1.0F - v; }
            case NORTH -> { x = 1.0F - u; y = 1.0F - v; z = -offset; }
            case SOUTH -> { x = u; y = 1.0F - v; z = 1.0F + offset; }
            case WEST -> { x = -offset; y = 1.0F - v; z = u; }
            default -> { x = 1.0F + offset; y = 1.0F - v; z = 1.0F - u; }
        }
        int alpha = Math.round(Math.max(0.0F, Math.min(1.0F, light)) * 255.0F);
        float over = Math.max(1.0F, light);
        int r = Math.min(255, Math.round(((color >> 16) & 255) * over));
        int g = Math.min(255, Math.round(((color >> 8) & 255) * over));
        int b = Math.min(255, Math.round((color & 255) * over));
        consumer.addVertex(matrix, x, y, z).setColor(r, g, b, alpha);
    }

    private static int scale(int color, float factor) {
        int r = Math.min(255, Math.round(((color >> 16) & 255) * factor));
        int g = Math.min(255, Math.round(((color >> 8) & 255) * factor));
        int b = Math.min(255, Math.round((color & 255) * factor));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /**
     * The light an anchor throws on the surfaces around it: every exposed face of a full block
     * within three blocks that turns towards the anchor and that the anchor can see, split in four
     * so the falloff is smooth, with the light at each corner.
     */
    private static final class SpillLight {
        private static final int RADIUS = 3;
        /** The light reaches zero at this distance, squared, so it has no edge. */
        private static final float REACH_SQUARED = 13.0F;
        private static final int PARTS = 3;
        private static final int MAX_QUADS = 480;

        final long builtAt;
        final float[] vertices;
        final int quads;
        /** Each quad's direction (Direction ordinal) and plane, in the anchor block's coordinates. */
        final byte[] faces;
        final float[] planes;

        private SpillLight(long builtAt, float[] vertices, int quads, byte[] faces, float[] planes) {
            this.builtAt = builtAt;
            this.vertices = vertices;
            this.quads = quads;
            this.faces = faces;
            this.planes = planes;
        }

        static SpillLight build(ClientLevel level, BlockPos anchor, long now) {
            float[] vertices = new float[MAX_QUADS * 16];
            byte[] faces = new byte[MAX_QUADS];
            float[] planes = new float[MAX_QUADS];
            int quads = 0;
            BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
            BlockPos.MutableBlockPos beside = new BlockPos.MutableBlockPos();
            outer:
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                    for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        block.set(anchor.getX() + dx, anchor.getY() + dy, anchor.getZ() + dz);
                        BlockState receiver = level.getBlockState(block);
                        // Other anchors take no bounce light: added flat over their dark obsidian it
                        // reads as a lavender fog on the block, not as light; theirs is their own glow.
                        if (!receiver.isSolidRender() || receiver.is(Blocks.RESPAWN_ANCHOR)) {
                            continue;
                        }
                        for (Direction direction : DIRECTIONS) {
                            beside.setWithOffset(block, direction);
                            if (beside.equals(anchor) || level.getBlockState(beside).isSolidRender()) {
                                continue;
                            }
                            // The face's centre, relative to the anchor's centre.
                            float cx = dx + 0.5F * direction.getStepX();
                            float cy = dy + 0.5F * direction.getStepY();
                            float cz = dz + 0.5F * direction.getStepZ();
                            if (cx * direction.getStepX() + cy * direction.getStepY() + cz * direction.getStepZ() >= 0.0F) {
                                continue;
                            }
                            if (cx * cx + cy * cy + cz * cz > REACH_SQUARED || !visible(level, anchor, cx, cy, cz, block)) {
                                continue;
                            }
                            float plane = switch (direction) {
                                case UP -> dy + 1.0F;
                                case DOWN -> dy;
                                case SOUTH -> dz + 1.0F;
                                case NORTH -> dz;
                                case EAST -> dx + 1.0F;
                                case WEST -> dx;
                            };
                            for (int part = 0; part < PARTS * PARTS && quads < MAX_QUADS; part++) {
                                faces[quads] = (byte) direction.ordinal();
                                planes[quads] = plane;
                                addQuad(vertices, quads++, direction, dx, dy, dz, part);
                            }
                            if (quads >= MAX_QUADS) {
                                break outer;
                            }
                        }
                    }
                }
            }
            return new SpillLight(now, vertices, quads, faces, planes);
        }

        /** Whether the straight line from the anchor to the face crosses no other full block. */
        private static boolean visible(ClientLevel level, BlockPos anchor, float x, float y, float z, BlockPos target) {
            BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
            int steps = 12;
            for (int step = 2; step < steps; step++) {
                float t = step / (float) steps;
                probe.set(anchor.getX() + (int) Math.floor(0.5F + x * t), anchor.getY() + (int) Math.floor(0.5F + y * t),
                        anchor.getZ() + (int) Math.floor(0.5F + z * t));
                if (probe.equals(anchor) || probe.equals(target)) {
                    continue;
                }
                if (level.getBlockState(probe).isSolidRender()) {
                    return false;
                }
            }
            return true;
        }

        /** One of the {@link #PARTS} by {@link #PARTS} pieces of a face, in coordinates of the anchor block. */
        private static void addQuad(float[] vertices, int index, Direction direction, int dx, int dy, int dz, int part) {
            float size = 1.0F / PARTS;
            float u0 = (part % PARTS) * size;
            float v0 = (part / PARTS) * size;
            float[][] corners = {
                    facePoint(direction, u0, v0), facePoint(direction, u0 + size, v0),
                    facePoint(direction, u0 + size, v0 + size), facePoint(direction, u0, v0 + size)};
            // Counter-clockwise seen from the side the face turns to, or the material culls it.
            float ax = corners[1][0] - corners[0][0];
            float ay = corners[1][1] - corners[0][1];
            float az = corners[1][2] - corners[0][2];
            float bx = corners[2][0] - corners[0][0];
            float by = corners[2][1] - corners[0][1];
            float bz = corners[2][2] - corners[0][2];
            float facing = (ay * bz - az * by) * direction.getStepX() + (az * bx - ax * bz) * direction.getStepY()
                    + (ax * by - ay * bx) * direction.getStepZ();
            if (facing < 0.0F) {
                float[] swap = corners[1];
                corners[1] = corners[3];
                corners[3] = swap;
            }
            int offset = index * 16;
            for (int corner = 0; corner < 4; corner++) {
                float x = dx + corners[corner][0] + direction.getStepX() * SPILL_OFFSET;
                float y = dy + corners[corner][1] + direction.getStepY() * SPILL_OFFSET;
                float z = dz + corners[corner][2] + direction.getStepZ() * SPILL_OFFSET;
                // Light from the anchor's centre: Lambert's cosine, a soft inverse square, and a
                // window that takes it smoothly to nothing at the edge of its reach.
                float lx = 0.5F - x;
                float ly = 0.5F - y;
                float lz = 0.5F - z;
                float distanceSquared = lx * lx + ly * ly + lz * lz;
                float length = (float) Math.sqrt(distanceSquared);
                float cosine = (lx * direction.getStepX() + ly * direction.getStepY() + lz * direction.getStepZ())
                        / Math.max(1.0E-3F, length);
                float window = Math.max(0.0F, 1.0F - distanceSquared / REACH_SQUARED);
                float light = Math.max(0.0F, cosine) * window * window / (1.0F + 0.35F * distanceSquared);
                vertices[offset + corner * 4] = x;
                vertices[offset + corner * 4 + 1] = y;
                vertices[offset + corner * 4 + 2] = z;
                vertices[offset + corner * 4 + 3] = light;
            }
        }

        /** A point on the face of a unit block that faces {@code direction}, from face coordinates. */
        private static float[] facePoint(Direction direction, float u, float v) {
            return switch (direction) {
                case UP -> new float[] {u, 1.0F, v};
                case DOWN -> new float[] {u, 0.0F, v};
                case NORTH -> new float[] {u, v, 0.0F};
                case SOUTH -> new float[] {u, v, 1.0F};
                case WEST -> new float[] {0.0F, v, u};
                case EAST -> new float[] {1.0F, v, u};
            };
        }
    }
}
