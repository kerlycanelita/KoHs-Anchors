package dev.zymekoh.kohsanchors.predict;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.zymekoh.kohsanchors.compat.AnchorBlockPreview;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.glow.AnchorTracker;
import dev.zymekoh.kohsanchors.glow.EnemySkinRenderer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Anchor fade: a detonated anchor shrinks, turns and sinks away over half a second instead of
 * vanishing in one frame. It is only a drawing of the anchor, submitted the way a falling block is:
 * the world, the crosshair, the collision and the next anchor going down in its place are
 * untouched, and the fade stops the moment an anchor is drawn there again. At most eight at once;
 * with none fading it costs one empty-list check per frame.
 */
public final class AnchorFade {
    private static final long FADE_NANOS = 550_000_000L;
    private static final int MAX_FADES = 8;
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
        // Each fade keeps its own state: the collector holds it until the frame is drawn.
        Object block = AnchorBlockPreview.worldBlock(level, position, state);
        // An enemy's anchor fades in their skin, as it was drawn.
        boolean enemy = AnchorTracker.anchors().get(position.asLong()) == AnchorTracker.ENEMY;
        FADES.add(new Fade(level, position.immutable(), block, System.nanoTime(), enemy, state.getValue(RespawnAnchorBlock.CHARGE)));
    }

    /** With the frame's block entities, relative to the camera, like the glow. */
    public static void submit(PoseStack poses, SubmitNodeCollector collector, Vec3 camera) {
        if (FADES.isEmpty()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        long now = System.nanoTime();
        FADES.removeIf(fade -> fade.level != level || now - fade.since > FADE_NANOS || anchorAgain(level, fade.position));
        for (Fade fade : FADES) {
            float progress = (now - fade.since) / (float) FADE_NANOS;
            // Slow at first, as if it held on, then gone: what is left of it sinks and turns.
            float scale = 1.0F - progress * progress;
            BlockPos position = fade.position;
            poses.pushPose();
            poses.translate(position.getX() - camera.x + 0.5D, position.getY() - camera.y - 0.2D * progress,
                    position.getZ() - camera.z + 0.5D);
            // Turned through the pose's own matrices: PoseStack's rotation helpers differ between versions.
            float turn = (float) Math.toRadians(40.0F * progress * progress);
            poses.last().pose().rotateY(turn);
            poses.last().normal().rotateY(turn);
            poses.scale(scale, scale, scale);
            poses.translate(-0.5D, 0.0D, -0.5D);
            if (fade.enemy && EnemySkinRenderer.active()) {
                EnemySkinRenderer.submitCube(poses, collector, fade.level, position, fade.charge, false);
            } else {
                AnchorBlockPreview.submitWorldBlock(poses, collector, fade.block);
            }
            poses.popPose();
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

    private record Fade(ClientLevel level, BlockPos position, Object block, long since, boolean enemy, int charge) {
    }
}
