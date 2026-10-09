package dev.zymekoh.kohsanchors.predict;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.zymekoh.kohsanchors.compat.AnchorBlockPreview;
import dev.zymekoh.kohsanchors.compat.Mc;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.glow.AnchorGlowRenderer;
import dev.zymekoh.kohsanchors.glow.AnchorTracker;
import dev.zymekoh.kohsanchors.glow.EnemySkinRenderer;
import dev.zymekoh.kohsanchors.glow.SkinCubeTexture;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Anchor fade: a detonated anchor goes away with an animation instead of vanishing in one frame.
 *
 * <ul>
 *   <li><b>Ghost:</b> it rises a little and turns to light, the same size all the way, gone in a
 *   third of a second.</li>
 *   <li><b>Sink:</b> it shrinks, turns and sinks into the ground.</li>
 *   <li><b>Shatter:</b> it breaks into eight pieces that fly out, spin and fall.</li>
 *   <li><b>Disintegrate:</b> a cut of light runs down it and the part above burns away into
 *   embers.</li>
 *   <li><b>Glitch:</b> it tears into shifted slices and split colours, then shuts off like an old
 *   screen.</li>
 * </ul>
 *
 * <p>It is only a drawing of the anchor: the world, the crosshair, the collision and the next anchor
 * going down in its place are untouched. Styles that stay inside the block stop the moment an anchor
 * is drawn there again; the ghost and the shards leave the block and go on. At most eight at once;
 * with none fading it costs one empty-list check per frame.</p>
 */
public final class AnchorFade {
    /** The styles, in the order the option cycles through them. */
    public enum Style {
        GHOST(330L), SINK(550L), SHATTER(680L), DISINTEGRATE(500L), GLITCH(440L);

        final long nanos;

        Style(long millis) {
            this.nanos = millis * 1_000_000L;
        }

        /** How long it lasts, for the settings preview. */
        public long millis() {
            return this.nanos / 1_000_000L;
        }

        public static Style of(int index) {
            Style[] styles = values();
            return styles[Math.floorMod(index, styles.length)];
        }

        /** Whether it stays inside the block, so a new anchor there ends it. */
        boolean inside() {
            return this == SINK || this == DISINTEGRATE || this == GLITCH;
        }
    }

    private static final int MAX_FADES = 8;
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final float[][] CORNERS = {{0.0F, 0.0F}, {0.0F, 1.0F}, {1.0F, 1.0F}, {1.0F, 0.0F}};
    private static final List<Fade> FADES = new ArrayList<>();

    private AnchorFade() {
    }

    /** The anchor drawn at {@code position} as {@code state} has just been detonated. */
    public static void start(ClientLevel level, BlockPos position, BlockState state) {
        if (!AnchorsConfig.settings().anchorFade || state == null || !state.is(Blocks.RESPAWN_ANCHOR)) {
            return;
        }
        if (FADES.size() >= MAX_FADES) {
            FADES.remove(0);
        }
        Style style = Style.of(AnchorsConfig.settings().fadeStyle);
        int charge = state.getValue(RespawnAnchorBlock.CHARGE);
        // An enemy's anchor fades in their skin, as it was drawn.
        boolean enemy = AnchorTracker.anchors().get(position.asLong()) == AnchorTracker.ENEMY && EnemySkinRenderer.active();
        BlockPos above = position.above();
        int light = Mc.packLight(level.getBrightness(LightLayer.BLOCK, above), level.getBrightness(LightLayer.SKY, above));
        int glow = enemy ? AnchorsConfig.settings().enemyGlow.color : AnchorGlowRenderer.ownColor(Math.max(1, charge));
        // Each sink keeps its own block state: the collector holds it until the frame is drawn.
        Object block = style == Style.SINK ? AnchorBlockPreview.worldBlock(level, position, state) : null;
        long now = System.nanoTime();
        FADES.add(new Fade(level, position.immutable(), block, now, enemy, charge, style, light, 0xFF000000 | glow,
                position.asLong() * 0x9E3779B97F4A7C15L ^ now, null));
    }

    /**
     * The fade of the settings screen's stage: the world's own drawing, for an anchor at the block
     * of {@code poses} that was detonated at {@code since}. The stage lights its anchor itself, so
     * the pieces take {@code shade}, the brightness of each face (by direction), instead of the
     * world's light. Returns false once the fade is over.
     */
    public static boolean submitStage(PoseStack poses, OrderedSubmitNodeCollector collector, Style style, boolean enemy,
            int charge, int glow, long since, long seed, float[] shade) {
        long now = System.nanoTime();
        SkinCubeTexture skin = SkinCubeTexture.of(enemy);
        if (now - since > style.nanos || !skin.prepare()) {
            return false;
        }
        Fade fade = new Fade(null, BlockPos.ZERO, null, since, enemy, charge, style, FULL_BRIGHT, 0xFF000000 | glow, seed,
                shade);
        float progress = Math.min(1.0F, (now - since) / (float) style.nanos);
        float seconds = (now - since) / 1_000_000_000.0F;
        int frame = skin.frame(charge);
        if (style == Style.SINK) {
            poses.pushPose();
            sinkPose(poses, progress);
            collector.submitCustomGeometry(poses, skin.unlit(), (pose, consumer) -> {
                Box box = new Box(skin, charge, frame);
                box.shade = shade;
                box.draw(pose, consumer, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0xFFFFFFFF, FULL_BRIGHT, 0.0F);
            });
            poses.popPose();
            return true;
        }
        collector.submitCustomGeometry(poses, skin.unlitTranslucent(),
                (pose, consumer) -> textured(pose, consumer, skin, frame, fade, progress, seconds));
        if (style == Style.DISINTEGRATE || style == Style.GLITCH) {
            collector.submitCustomGeometry(poses, RenderTypes.lightning(),
                    (pose, consumer) -> light(pose, consumer, fade, progress, seconds));
        }
        return true;
    }

    /** With the frame's block entities, relative to the camera, like the glow. */
    public static void submit(PoseStack poses, SubmitNodeCollector collector, Vec3 camera) {
        if (FADES.isEmpty()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        long now = System.nanoTime();
        FADES.removeIf(fade -> fade.level != level || now - fade.since > fade.style.nanos
                || fade.style.inside() && anchorAgain(level, fade.position));
        for (Fade fade : FADES) {
            float progress = Math.min(1.0F, (now - fade.since) / (float) fade.style.nanos);
            BlockPos position = fade.position;
            poses.pushPose();
            poses.translate(position.getX() - camera.x, position.getY() - camera.y, position.getZ() - camera.z);
            if (fade.style == Style.SINK) {
                sink(poses, collector, fade, progress);
            } else {
                SkinCubeTexture skin = SkinCubeTexture.of(fade.enemy);
                if (skin.prepare()) {
                    int frame = skin.frame(fade.charge);
                    float seconds = (now - fade.since) / 1_000_000_000.0F;
                    collector.submitCustomGeometry(poses, skin.translucent(),
                            (pose, consumer) -> textured(pose, consumer, skin, frame, fade, progress, seconds));
                    if (fade.style == Style.DISINTEGRATE || fade.style == Style.GLITCH) {
                        collector.submitCustomGeometry(poses, RenderTypes.lightning(),
                                (pose, consumer) -> light(pose, consumer, fade, progress, seconds));
                    }
                }
            }
            poses.popPose();
        }
    }

    /** Slow at first, as if it held on, then gone: what is left of it sinks and turns. */
    private static void sink(PoseStack poses, SubmitNodeCollector collector, Fade fade, float progress) {
        sinkPose(poses, progress);
        if (fade.enemy) {
            EnemySkinRenderer.submitCube(poses, collector, fade.level, fade.position, fade.charge, false);
        } else {
            AnchorBlockPreview.submitWorldBlock(poses, collector, fade.block);
        }
    }

    /** The sink's shrinking, turning and sinking, about the middle of the block's base. */
    private static void sinkPose(PoseStack poses, float progress) {
        float scale = 1.0F - progress * progress;
        poses.translate(0.5D, -0.2D * progress, 0.5D);
        // Turned through the pose's own matrices: PoseStack's rotation helpers differ between versions.
        float turn = (float) Math.toRadians(40.0F * progress * progress);
        poses.last().pose().rotateY(turn);
        poses.last().normal().rotateY(turn);
        poses.scale(scale, scale, scale);
        poses.translate(-0.5D, 0.0D, -0.5D);
    }

    /** The skinned part of every style but the sink. */
    private static void textured(PoseStack.Pose pose, VertexConsumer consumer, SkinCubeTexture skin, int frame, Fade fade,
            float progress, float seconds) {
        Box box = new Box(skin, fade.charge, frame);
        box.shade = fade.shade;
        switch (fade.style) {
            case GHOST -> {
                // Light, not size: it fades and rises, the same block all the way.
                float alpha = (1.0F - progress) * (1.0F - progress);
                float rise = 0.35F * easeOut(progress);
                int tint = mix(0xFFFFFF, 0xD9CCFF, progress);
                box.offset.set(0.0F, rise, 0.0F);
                box.draw(pose, consumer, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, argb(alpha * 0.9F, tint), FULL_BRIGHT, 0.004F);
                box.draw(pose, consumer, -0.04F, -0.04F, -0.04F, 1.04F, 1.04F, 1.04F,
                        argb(alpha * 0.3F, fade.glow & 0xFFFFFF), FULL_BRIGHT, 0.0F);
            }
            case SHATTER -> {
                float alpha = progress < 0.55F ? 1.0F : 1.0F - (progress - 0.55F) / 0.45F;
                for (int piece = 0; piece < 8; piece++) {
                    float x0 = (piece & 1) * 0.5F;
                    float y0 = (piece >> 1 & 1) * 0.5F;
                    float z0 = (piece >> 2 & 1) * 0.5F;
                    float dx = x0 - 0.25F + rand(fade.seed, piece * 7) * 0.3F - 0.15F;
                    float dz = z0 - 0.25F + rand(fade.seed, piece * 7 + 1) * 0.3F - 0.15F;
                    float speed = 4.5F + rand(fade.seed, piece * 7 + 2) * 2.5F;
                    float up = 3.2F + rand(fade.seed, piece * 7 + 3) * 2.0F + (y0 > 0.0F ? 1.2F : 0.0F);
                    box.offset.set(dx * speed * seconds, up * seconds - 7.0F * seconds * seconds, dz * speed * seconds);
                    box.center.set(x0 + 0.25F, y0 + 0.25F, z0 + 0.25F);
                    box.spin.identity().rotateAxis((5.0F + 7.0F * rand(fade.seed, piece * 7 + 4)) * seconds,
                            rand(fade.seed, piece * 7 + 5) - 0.5F, 0.6F, rand(fade.seed, piece * 7 + 6) - 0.5F);
                    box.draw(pose, consumer, x0, y0, z0, x0 + 0.5F, y0 + 0.5F, z0 + 0.5F, argb(alpha, 0xFFFFFF), fade.light,
                            0.0F);
                }
            }
            case DISINTEGRATE -> {
                float cut = cut(progress);
                if (cut > 0.01F) {
                    box.draw(pose, consumer, 0.0F, 0.0F, 0.0F, 1.0F, cut, 1.0F, 0xFFFFFFFF, fade.light, 0.003F);
                }
            }
            case GLITCH -> {
                if (progress >= 0.75F) {
                    return;
                }
                int step = (int) (seconds / 0.04F);
                long seed = fade.seed + step * 0x632BE59BD9B4E019L;
                for (int slice = 0; slice < 4; slice++) {
                    if (rand(seed, slice) > 0.85F - 0.6F * progress) {
                        continue;
                    }
                    float shift = rand(seed, slice + 8) < 0.5F ? 0.0F : (rand(seed, slice + 16) - 0.5F) * 0.3F;
                    boolean alongX = rand(seed, slice + 24) < 0.5F;
                    box.offset.set(alongX ? shift : 0.0F, 0.0F, alongX ? 0.0F : shift);
                    box.draw(pose, consumer, 0.0F, slice * 0.25F, 0.0F, 1.0F, slice * 0.25F + 0.25F, 1.0F, 0xFFFFFFFF,
                            FULL_BRIGHT, 0.003F);
                }
                if (step % 2 == 0) {
                    // Split colours: a red and a cyan copy pulled apart.
                    float split = 0.05F + 0.08F * rand(seed, 40);
                    box.offset.set(split, 0.0F, -split * 0.5F);
                    box.draw(pose, consumer, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0x70FF2244, FULL_BRIGHT, 0.006F);
                    box.offset.set(-split, 0.0F, split * 0.5F);
                    box.draw(pose, consumer, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0x7022E6FF, FULL_BRIGHT, 0.006F);
                }
            }
            default -> {
            }
        }
    }

    /** The light of the disintegrate's cut and embers, and the glitch's last line. */
    private static void light(PoseStack.Pose pose, VertexConsumer consumer, Fade fade, float progress, float seconds) {
        int glow = fade.glow & 0xFFFFFF;
        if (fade.style == Style.GLITCH) {
            if (progress < 0.75F) {
                return;
            }
            // Shut off like an old screen: squeezed into a bright line that widens and goes out.
            float off = (progress - 0.75F) / 0.25F;
            float half = 0.5F * (1.0F - off) * (1.0F - off) * (1.0F - off) + 0.01F;
            float wide = 0.2F * off;
            coloredBox(pose, consumer, -wide, 0.5F - half, -wide, 1.0F + wide, 0.5F + half, 1.0F + wide,
                    argb(1.0F - off, mix(0xFFFFFF, glow, off)));
            return;
        }
        float cut = cut(progress);
        float band = 0.035F;
        if (cut > 0.0F && progress < 1.0F) {
            int rim = argb(0.95F * (1.0F - progress * 0.5F), mix(0xFFFFFF, glow, 0.4F));
            coloredBox(pose, consumer, -0.012F, cut - band, -0.012F, 1.012F, cut + band, 1.012F, rim);
        }
        // Embers: each leaves the side of the block when the cut passes its height, and rises.
        for (int ember = 0; ember < 18; ember++) {
            float height = rand(fade.seed, 100 + ember);
            float released = uncut(height);
            float age = progress - released;
            if (age < 0.0F) {
                continue;
            }
            float life = age * fade.style.nanos / 1_000_000_000.0F / 0.32F;
            if (life >= 1.0F) {
                continue;
            }
            int side = ember % 4;
            float along = rand(fade.seed, 140 + ember);
            float x = side == 0 ? -0.02F : side == 1 ? 1.02F : along;
            float z = side == 2 ? -0.02F : side == 3 ? 1.02F : along;
            float out = 0.35F * life;
            x += side == 0 ? -out : side == 1 ? out : 0.0F;
            z += side == 2 ? -out : side == 3 ? out : 0.0F;
            float y = height + 0.55F * life;
            float size = 0.045F * (1.0F - 0.6F * life);
            coloredBox(pose, consumer, x - size, y - size, z - size, x + size, y + size, z + size,
                    argb(1.0F - life, mix(glow, 0x2A0E3F, life)));
        }
    }

    /** The disintegrate's cut: the height that is left, from 1 down to 0. */
    private static float cut(float progress) {
        float eased = progress < 0.5F ? 2.0F * progress * progress : 1.0F - (float) Math.pow(-2.0F * progress + 2.0F, 2) / 2.0F;
        return 1.0F - eased;
    }

    /** The progress at which the cut reaches {@code height}: the inverse of {@link #cut}. */
    private static float uncut(float height) {
        float eased = 1.0F - height;
        return eased < 0.5F ? (float) Math.sqrt(eased / 2.0F) : 1.0F - (float) Math.sqrt((1.0F - eased) * 2.0F) / 2.0F;
    }

    /** A box of plain light, for the render type that takes only position and colour. */
    private static void coloredBox(PoseStack.Pose pose, VertexConsumer consumer, float x0, float y0, float z0, float x1,
            float y1, float z1, int color) {
        for (Direction direction : DIRECTIONS) {
            for (float[] corner : CORNERS) {
                float[] point = SkinCubeTexture.facePoint(direction, corner[0], corner[1]);
                consumer.addVertex(pose, x0 + point[0] * (x1 - x0), y0 + point[1] * (y1 - y0), z0 + point[2] * (z1 - z0))
                        .setColor(color);
            }
        }
    }

    /** An anchor is drawn there again (the next one, the server kept it): the fade is over. */
    private static boolean anchorAgain(ClientLevel level, BlockPos position) {
        BlockState shown = AnchorVeil.predicted(position);
        return shown != null ? shown.is(Blocks.RESPAWN_ANCHOR)
                : level != null && level.getBlockState(position).is(Blocks.RESPAWN_ANCHOR);
    }

    /** Anchors fading right now, for developer mode. */
    public static int active() {
        return FADES.size();
    }

    private static float easeOut(float value) {
        float inverse = 1.0F - value;
        return 1.0F - inverse * inverse * inverse;
    }

    private static int argb(float alpha, int rgb) {
        return Math.round(Math.max(0.0F, Math.min(1.0F, alpha)) * 255.0F) << 24 | rgb & 0xFFFFFF;
    }

    private static int mix(int from, int to, float amount) {
        float a = Math.max(0.0F, Math.min(1.0F, amount));
        int r = Math.round((from >> 16 & 0xFF) + ((to >> 16 & 0xFF) - (from >> 16 & 0xFF)) * a);
        int g = Math.round((from >> 8 & 0xFF) + ((to >> 8 & 0xFF) - (from >> 8 & 0xFF)) * a);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * a);
        return r << 16 | g << 8 | b;
    }

    /** A number from 0 to 1 that is always the same for the same fade and index. */
    private static float rand(long seed, int index) {
        long mixed = seed + index * 0x9E3779B97F4A7C15L;
        mixed = (mixed ^ mixed >>> 30) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ mixed >>> 27) * 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return (mixed >>> 40) / (float) (1L << 24);
    }

    /**
     * Part of the anchor's cube, its texture taken from where the part sits on the whole block, then
     * turned about {@link #center} and moved by {@link #offset}.
     */
    private static final class Box {
        final SkinCubeTexture skin;
        final int charge;
        final int frame;
        final Vector3f offset = new Vector3f();
        final Vector3f center = new Vector3f(0.5F, 0.5F, 0.5F);
        final Quaternionf spin = new Quaternionf();
        private final Vector3f point = new Vector3f();
        private final Vector3f normal = new Vector3f();
        /** Each face's brightness by direction, where the material takes no light; null in the world. */
        float[] shade;

        Box(SkinCubeTexture skin, int charge, int frame) {
            this.skin = skin;
            this.charge = charge;
            this.frame = frame;
        }

        /** The box from {@code x0, y0, z0} to {@code x1, y1, z1} of the block, faces pushed out by {@code grow}. */
        void draw(PoseStack.Pose pose, VertexConsumer consumer, float x0, float y0, float z0, float x1, float y1, float z1,
                int color, int light, float grow) {
            for (Direction direction : DIRECTIONS) {
                this.normal.set(direction.getStepX(), direction.getStepY(), direction.getStepZ()).rotate(this.spin);
                pose.transformNormal(this.normal.x, this.normal.y, this.normal.z, this.normal);
                // A face inside the whole block is a fresh break: drawn darker, as the inside.
                boolean inner = switch (direction) {
                    case DOWN -> y0 > 0.0F;
                    case UP -> y1 < 1.0F && this.charge >= 0 && y1 < 0.99F;
                    case NORTH -> z0 > 0.0F;
                    case SOUTH -> z1 < 1.0F;
                    case WEST -> x0 > 0.0F;
                    case EAST -> x1 < 1.0F;
                };
                int faceColor = inner && direction != Direction.UP ? darker(color) : color;
                if (this.shade != null) {
                    faceColor = shaded(faceColor, this.shade[direction.ordinal()]);
                }
                for (float[] corner : CORNERS) {
                    float[] unit = SkinCubeTexture.facePoint(direction, corner[0], corner[1]);
                    float x = x0 + unit[0] * (x1 - x0);
                    float y = y0 + unit[1] * (y1 - y0);
                    float z = z0 + unit[2] * (z1 - z0);
                    float u = SkinCubeTexture.faceU(direction, x, y, z);
                    float v = SkinCubeTexture.faceV(direction, x, y, z);
                    this.point.set(x + direction.getStepX() * grow, y + direction.getStepY() * grow,
                            z + direction.getStepZ() * grow).sub(this.center).rotate(this.spin).add(this.center).add(this.offset);
                    consumer.addVertex(pose, this.point.x, this.point.y, this.point.z).setColor(faceColor)
                            .setUv(this.skin.u(direction, this.charge, this.frame, u), this.skin.v(direction, this.charge, v))
                            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                            .setNormal(this.normal.x, this.normal.y, this.normal.z);
                }
            }
        }

        private static int shaded(int color, float shade) {
            float factor = Math.max(0.0F, Math.min(1.0F, shade));
            return color & 0xFF000000 | Math.round((color >> 16 & 0xFF) * factor) << 16
                    | Math.round((color >> 8 & 0xFF) * factor) << 8 | Math.round((color & 0xFF) * factor);
        }

        private static int darker(int color) {
            return color & 0xFF000000 | (color >> 16 & 0xFF) * 45 / 100 << 16 | (color >> 8 & 0xFF) * 45 / 100 << 8
                    | (color & 0xFF) * 45 / 100;
        }
    }

    private record Fade(ClientLevel level, BlockPos position, Object block, long since, boolean enemy, int charge, Style style,
            int light, int glow, long seed, float[] shade) {
    }
}
