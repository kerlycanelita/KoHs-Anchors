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
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
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
 *   glow's colour. The light spreads through the air as the game's own does, round corners and
 *   down past the block the anchor stands on, fading with the way it travels and brighter on the
 *   surfaces that turn towards the anchor ({@link SpillLight}).</li>
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
    /** The bounce light of the settings screen's stage, built once for its scene. */
    private static BlockGetter stageScene;
    private static SpillLight stageSpill;
    /** The same room lit from where the anchor stood, once it is gone: a detonation's flash. */
    private static SpillLight stageFlash;
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
            // Near anchors light the blocks up to five blocks away, so their sphere is wider.
            double radius = distance < spillDistance ? 5.0D : 1.6D;
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

    /**
     * The glow of one anchor outside any world: the settings screen's stage, a scene of its own with
     * the anchor at its origin. The same three layers from the same settings, at full detail, so
     * what the stage shows is what the world will. {@code enemy} glows in the enemy colour, as an
     * enemy's anchor does in a fight; {@code strength} scales it while the anchor comes and goes.
     *
     * @param cameraX where the camera is in the anchor block's coordinates, for the faces turned away
     */
    public static void submitStage(PoseStack poses, OrderedSubmitNodeCollector collector, BlockGetter scene, int charge,
            boolean enemy, float strength, float cameraX, float cameraY, float cameraZ) {
        AnchorsConfig.Glow glow = AnchorsConfig.settings().glow;
        if (!glow.enabled || glow.power <= 0 || charge <= 0 || strength <= 0.0F) {
            return;
        }
        float intensity = glow.power / 100.0F * strength;
        if (glow.chargeScaling) {
            intensity *= 0.4F + 0.6F * charge / 4.0F;
        }
        if (glow.pulse) {
            intensity *= 0.86F + 0.14F * (float) Math.sin(System.nanoTime() / 1_000_000_000.0D * 2.3D);
        }
        int override = enemy ? AnchorsConfig.settings().enemyGlow.color
                : glow.source == AnchorsConfig.Glow.SOURCE_CUSTOM ? glow.color : 0;
        stageLights(scene);
        collector.submitCustomGeometry(poses, GlowMaterial.glow(), new Draw(charge, exposure(scene, BlockPos.ZERO), intensity,
                override, glow.emissive, glow.bloom / 100.0F, glow.spill / 100.0F,
                override != 0 ? override : textureColor(charge), glow.spill > 0 ? stageSpill : null, 0, cameraX, cameraY,
                cameraZ));
    }

    /**
     * A burst of light on the stage's room, with no anchor in it: the bounce light alone, in
     * {@code color}, as a detonation throws it for a moment. It reaches where the glow's light does.
     */
    public static void submitStageFlash(PoseStack poses, OrderedSubmitNodeCollector collector, BlockGetter scene, int color,
            float strength, float cameraX, float cameraY, float cameraZ) {
        if (strength <= 0.0F) {
            return;
        }
        stageLights(scene);
        collector.submitCustomGeometry(poses, GlowMaterial.glow(), new Draw(4, 0, strength, 0xFF000000 | color, false, 0.0F,
                1.0F, 0xFF000000 | color, stageFlash, 0, cameraX, cameraY, cameraZ));
    }

    /** The stage's bounce light, built once for its scene: with the anchor in it, and without. */
    private static void stageLights(BlockGetter scene) {
        if (scene != stageScene) {
            stageScene = scene;
            long now = System.nanoTime();
            stageSpill = SpillLight.build(scene, BlockPos.ZERO, now, false);
            stageFlash = SpillLight.build(scene, BlockPos.ZERO, now, true);
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
    private static int exposure(BlockGetter level, BlockPos position) {
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
            light = SpillLight.build(level, BlockPos.of(key), now, false);
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
            float strength = this.intensity * this.spill * 0.72F;
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
     * The light an anchor throws on the surfaces around it, spread through the air the way the
     * game's own light spreads: out of the anchor into every open cell around it, round corners and
     * down past the edges of the block it stands on, fading with the distance it travels. Each face
     * of a full block that touches lit air takes the light of the air in front of it, averaged at
     * its corners as the game's smooth lighting does, and a little more when it turns towards the
     * anchor.
     *
     * <p>Measured from the anchor's centre along straight lines, as before, the block an anchor
     * stands on hid the ground all around it: on a pillar, a hard shadow lay under the anchor.
     * Through the air, the pillar's sides and the ground at its foot are lit, two and three blocks
     * down, and nothing is lit through a wall.</p>
     */
    private static final class SpillLight {
        /** Cells examined on each side of the anchor. */
        static final int RADIUS = 5;
        private static final int SIZE = RADIUS * 2 + 1;
        private static final int CELLS = SIZE * SIZE * SIZE;
        private static final int CENTER = (RADIUS * SIZE + RADIUS) * SIZE + RADIUS;
        /** How far the light travels through the air, in blocks from the anchor's centre. */
        private static final float REACH = 4.6F;
        private static final int PARTS = 2;
        private static final int MAX_FACES = 260;
        private static final int MAX_QUADS = MAX_FACES * PARTS * PARTS;
        /** The 26 neighbours of a cell: offsets, index steps and lengths. */
        private static final int[][] STEPS = new int[26][];
        private static final int[] STEP_INDEX = new int[26];
        private static final float[] STEP_LENGTH = new float[26];
        private static final Direction[] AROUND = Direction.values();

        // Scratch space, reused by every build (the render thread builds one at a time).
        private static final boolean[] OPEN = new boolean[CELLS];
        private static final boolean[] RECEIVES = new boolean[CELLS];
        private static final float[] DISTANCE = new float[CELLS];
        private static final float[] LIGHT = new float[CELLS];
        private static final int[] SETTLED = new int[CELLS];
        private static final int[] HEAP = new int[CELLS * 27];
        private static final float[] HEAP_KEY = new float[CELLS * 27];

        static {
            int step = 0;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        STEPS[step] = new int[] {dx, dy, dz};
                        STEP_INDEX[step] = (dy * SIZE + dz) * SIZE + dx;
                        STEP_LENGTH[step] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                        step++;
                    }
                }
            }
        }

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

        private static int index(int x, int y, int z) {
            return ((y + RADIUS) * SIZE + z + RADIUS) * SIZE + x + RADIUS;
        }

        private static boolean inside(int x, int y, int z) {
            return x >= -RADIUS && x <= RADIUS && y >= -RADIUS && y <= RADIUS && z >= -RADIUS && z <= RADIUS;
        }

        /**
         * @param hollow the light comes from an empty block: the faces that touched the anchor are
         *     lit as well, as when it has just been blown away
         */
        static SpillLight build(BlockGetter level, BlockPos anchor, long now, boolean hollow) {
            BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
            for (int y = -RADIUS; y <= RADIUS; y++) {
                for (int z = -RADIUS; z <= RADIUS; z++) {
                    for (int x = -RADIUS; x <= RADIUS; x++) {
                        int cell = index(x, y, z);
                        block.set(anchor.getX() + x, anchor.getY() + y, anchor.getZ() + z);
                        BlockState state = level.getBlockState(block);
                        boolean solid = state.isSolidRender();
                        OPEN[cell] = !solid;
                        // Other anchors take no bounce light: added flat over their dark obsidian it
                        // reads as a lavender fog on the block, not as light; theirs is their own glow.
                        RECEIVES[cell] = solid && !state.is(Blocks.RESPAWN_ANCHOR);
                    }
                }
            }
            OPEN[CENTER] = false;
            int settled = spread();
            if (hollow) {
                OPEN[CENTER] = true;
                LIGHT[CENTER] = 1.0F;
                SETTLED[settled++] = CENTER;
            }

            float[] vertices = new float[MAX_QUADS * 16];
            byte[] faces = new byte[MAX_QUADS];
            float[] planes = new float[MAX_QUADS];
            float[] corners = new float[4];
            int quads = 0;
            int found = 0;
            // Nearest air first, so the face limit, if it is ever reached, drops the farthest light.
            outer:
            for (int order = 0; order < settled; order++) {
                int front = SETTLED[order];
                if (LIGHT[front] <= 0.002F) {
                    continue;
                }
                int fx = front % SIZE - RADIUS;
                int fz = front / SIZE % SIZE - RADIUS;
                int fy = front / (SIZE * SIZE) - RADIUS;
                for (Direction toward : AROUND) {
                    int bx = fx + toward.getStepX();
                    int by = fy + toward.getStepY();
                    int bz = fz + toward.getStepZ();
                    if (!inside(bx, by, bz) || !RECEIVES[index(bx, by, bz)]) {
                        continue;
                    }
                    // The lit face is the side of that block turned back to this air.
                    Direction direction = toward.getOpposite();
                    cornerLight(direction, fx, fy, fz, bx, by, bz, corners);
                    if (corners[0] + corners[1] + corners[2] + corners[3] < 0.008F) {
                        continue;
                    }
                    float plane = switch (direction) {
                        case UP -> by + 1.0F;
                        case DOWN -> by;
                        case SOUTH -> bz + 1.0F;
                        case NORTH -> bz;
                        case EAST -> bx + 1.0F;
                        case WEST -> bx;
                    };
                    for (int part = 0; part < PARTS * PARTS; part++) {
                        faces[quads] = (byte) direction.ordinal();
                        planes[quads] = plane;
                        addQuad(vertices, quads++, direction, bx, by, bz, part, corners);
                    }
                    if (++found >= MAX_FACES) {
                        break outer;
                    }
                }
            }
            return new SpillLight(now, java.util.Arrays.copyOf(vertices, quads * 16), quads,
                    java.util.Arrays.copyOf(faces, quads), java.util.Arrays.copyOf(planes, quads));
        }

        /**
         * Spreads the light out of the anchor through the open cells, nearest first (Dijkstra over
         * the 26 neighbours, each step as long as it really is), and fills {@link #LIGHT}. A step
         * across an edge or a corner needs open air beside it, so light never slips through a
         * crack between two blocks that only touch at an edge. Returns how many cells it reached,
         * listed in {@link #SETTLED} in the order they were reached.
         */
        private static int spread() {
            java.util.Arrays.fill(DISTANCE, Float.MAX_VALUE);
            java.util.Arrays.fill(LIGHT, 0.0F);
            DISTANCE[CENTER] = 0.0F;
            int heapSize = 0;
            HEAP[heapSize] = CENTER;
            HEAP_KEY[heapSize++] = 0.0F;
            int settled = 0;
            while (heapSize > 0) {
                int cell = HEAP[0];
                float key = HEAP_KEY[0];
                heapSize = pop(heapSize);
                if (key > DISTANCE[cell]) {
                    continue;
                }
                if (cell != CENTER) {
                    SETTLED[settled++] = cell;
                    LIGHT[cell] = lightAt(key);
                }
                int x = cell % SIZE - RADIUS;
                int z = cell / SIZE % SIZE - RADIUS;
                int y = cell / (SIZE * SIZE) - RADIUS;
                for (int step = 0; step < 26; step++) {
                    int[] offset = STEPS[step];
                    int nx = x + offset[0];
                    int ny = y + offset[1];
                    int nz = z + offset[2];
                    if (!inside(nx, ny, nz)) {
                        continue;
                    }
                    int next = cell + STEP_INDEX[step];
                    float distance = key + STEP_LENGTH[step];
                    if (!OPEN[next] || distance >= REACH || distance >= DISTANCE[next] || !passes(x, y, z, offset)) {
                        continue;
                    }
                    DISTANCE[next] = distance;
                    heapSize = push(heapSize, next, distance);
                }
            }
            return settled;
        }

        /** Whether light can take a step from a cell: straight always; across an edge or a corner, beside open air. */
        private static boolean passes(int x, int y, int z, int[] offset) {
            int axes = (offset[0] != 0 ? 1 : 0) + (offset[1] != 0 ? 1 : 0) + (offset[2] != 0 ? 1 : 0);
            if (axes == 1) {
                return true;
            }
            boolean single = offset[0] != 0 && OPEN[index(x + offset[0], y, z)]
                    || offset[1] != 0 && OPEN[index(x, y + offset[1], z)]
                    || offset[2] != 0 && OPEN[index(x, y, z + offset[2])];
            if (axes == 2 || !single) {
                return single;
            }
            return OPEN[index(x + offset[0], y + offset[1], z)] || OPEN[index(x + offset[0], y, z + offset[2])]
                    || OPEN[index(x, y + offset[1], z + offset[2])];
        }

        /** The light of air this far from the anchor's centre: gentle, and nothing at the reach, with no edge. */
        private static float lightAt(float distance) {
            float reach = distance / REACH;
            float window = Math.max(0.0F, 1.0F - reach * reach);
            float travelled = Math.max(0.0F, distance - 0.5F);
            return window * window / (1.0F + 0.24F * travelled * travelled);
        }

        private static int push(int size, int cell, float key) {
            int at = size++;
            while (at > 0) {
                int parent = (at - 1) >> 1;
                if (HEAP_KEY[parent] <= key) {
                    break;
                }
                HEAP[at] = HEAP[parent];
                HEAP_KEY[at] = HEAP_KEY[parent];
                at = parent;
            }
            HEAP[at] = cell;
            HEAP_KEY[at] = key;
            return size;
        }

        private static int pop(int size) {
            size--;
            int cell = HEAP[size];
            float key = HEAP_KEY[size];
            int at = 0;
            while (true) {
                int child = at * 2 + 1;
                if (child >= size) {
                    break;
                }
                if (child + 1 < size && HEAP_KEY[child + 1] < HEAP_KEY[child]) {
                    child++;
                }
                if (HEAP_KEY[child] >= key) {
                    break;
                }
                HEAP[at] = HEAP[child];
                HEAP_KEY[at] = HEAP_KEY[child];
                at = child;
            }
            HEAP[at] = cell;
            HEAP_KEY[at] = key;
            return size;
        }

        /**
         * The light at the four corners of the face of block {@code b} that looks into the air cell
         * {@code f}: at each corner the average of the open cells around it on the lit side, as
         * smooth lighting does, times how much the corner turns towards the anchor. Corners in the
         * order of {@link #facePoint}: (0,0), (1,0), (0,1), (1,1).
         */
        private static void cornerLight(Direction direction, int fx, int fy, int fz, int bx, int by, int bz,
                float[] corners) {
            // The face's two axes, the same ones facePoint maps u and v to.
            int ux = 0;
            int uy = 0;
            int uz = 0;
            int vx = 0;
            int vy = 0;
            int vz = 0;
            switch (direction.getAxis()) {
                case Y -> {
                    ux = 1;
                    vz = 1;
                }
                case Z -> {
                    ux = 1;
                    vy = 1;
                }
                default -> {
                    uz = 1;
                    vy = 1;
                }
            }
            int front = index(fx, fy, fz);
            for (int corner = 0; corner < 4; corner++) {
                int su = (corner & 1) == 0 ? -1 : 1;
                int sv = (corner & 2) == 0 ? -1 : 1;
                float sum = LIGHT[front];
                int count = 1;
                boolean alongU = open(fx + su * ux, fy + su * uy, fz + su * uz);
                boolean alongV = open(fx + sv * vx, fy + sv * vy, fz + sv * vz);
                if (alongU) {
                    sum += LIGHT[index(fx + su * ux, fy + su * uy, fz + su * uz)];
                    count++;
                }
                if (alongV) {
                    sum += LIGHT[index(fx + sv * vx, fy + sv * vy, fz + sv * vz)];
                    count++;
                }
                int dx = fx + su * ux + sv * vx;
                int dy = fy + su * uy + sv * vy;
                int dz = fz + su * uz + sv * vz;
                if ((alongU || alongV) && open(dx, dy, dz)) {
                    sum += LIGHT[index(dx, dy, dz)];
                    count++;
                }
                // The corner itself, relative to the anchor's centre, and how it faces the anchor.
                float[] point = facePoint(direction, (corner & 1) == 0 ? 0.0F : 1.0F, (corner & 2) == 0 ? 0.0F : 1.0F);
                float px = bx + point[0] - 0.5F;
                float py = by + point[1] - 0.5F;
                float pz = bz + point[2] - 0.5F;
                float length = (float) Math.sqrt(px * px + py * py + pz * pz);
                float cosine = -(px * direction.getStepX() + py * direction.getStepY() + pz * direction.getStepZ())
                        / Math.max(1.0E-3F, length);
                corners[corner] = sum / count * (0.4F + 0.6F * Math.max(0.0F, cosine));
            }
        }

        private static boolean open(int x, int y, int z) {
            return inside(x, y, z) && OPEN[index(x, y, z)];
        }

        /**
         * One of the {@link #PARTS} by {@link #PARTS} pieces of a face, in coordinates of the anchor
         * block, lit between its four corners.
         */
        private static void addQuad(float[] vertices, int index, Direction direction, int dx, int dy, int dz, int part,
                float[] light) {
            float size = 1.0F / PARTS;
            float u0 = (part % PARTS) * size;
            float v0 = (part / PARTS) * size;
            float[] us = {u0, u0 + size, u0 + size, u0};
            float[] vs = {v0, v0, v0 + size, v0 + size};
            float[][] corners = new float[4][];
            float[] values = new float[4];
            for (int corner = 0; corner < 4; corner++) {
                corners[corner] = facePoint(direction, us[corner], vs[corner]);
                // Bilinear between the face's corner lights.
                float top = light[0] + (light[1] - light[0]) * us[corner];
                float bottom = light[2] + (light[3] - light[2]) * us[corner];
                values[corner] = top + (bottom - top) * vs[corner];
            }
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
                float value = values[1];
                values[1] = values[3];
                values[3] = value;
            }
            int offset = index * 16;
            for (int corner = 0; corner < 4; corner++) {
                vertices[offset + corner * 4] = dx + corners[corner][0] + direction.getStepX() * SPILL_OFFSET;
                vertices[offset + corner * 4 + 1] = dy + corners[corner][1] + direction.getStepY() * SPILL_OFFSET;
                vertices[offset + corner * 4 + 2] = dz + corners[corner][2] + direction.getStepZ() * SPILL_OFFSET;
                vertices[offset + corner * 4 + 3] = values[corner];
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
