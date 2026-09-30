package dev.zymekoh.kohsanchors.predict;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What an anchor block looks like while the server has not caught up with the player's clicks:
 * drawing only, the world is never touched.
 *
 * <p>A detonated anchor is drawn as air the moment the click happens, instead of standing there
 * for a round trip until the server's explosion removes it. With the advanced chain, the anchor
 * that the next click places, and the glowstone that charges it, are drawn at once too.</p>
 *
 * <p>Only the chunk mesh, the crosshair outline and the client's light engine read this: the
 * light a veiled anchor gave goes out with it and comes back with it. The block stays in the client
 * world exactly as the server last said: collision, the crosshair's raycast, movement and every
 * packet are those of Vanilla. The veil lifts when the server's states for that block have arrived in
 * the order the clicks predicted them, or after a wait that follows the connection's latency.</p>
 *
 * <p>Chunk meshes are built on worker threads, so the lookup is lock-free and costs one volatile
 * read while nothing is veiled.</p>
 */
public final class AnchorVeil {
    /**
     * How long a veil waits for the server: three round trips and a quarter second, 0.4 to 1.5 s.
     * An answer that never comes (a click an anticheat dropped without a word) no longer hides the
     * anchor for a second and a half: past this, the world is drawn as it is.
     */
    private static final long MIN_TIMEOUT_NANOS = 400_000_000L;
    private static final long MAX_TIMEOUT_NANOS = 1_500_000_000L;
    private static final int MAX_VEILS = 8;

    private static final Map<Long, Veil> VEILS = new ConcurrentHashMap<>();
    private static volatile boolean active;
    private static ClientLevel veiledLevel;

    private AnchorVeil() {
    }

    /**
     * The state to draw at {@code packedPos} instead of the world's, or {@code null}. Called by the
     * chunk mesher for every block it reads, from worker threads.
     */
    public static BlockState shown(long packedPos) {
        if (!active) {
            return null;
        }
        Veil veil = VEILS.get(packedPos);
        return veil == null ? null : veil.shown;
    }

    public static boolean isVeiled(BlockPos position) {
        return active && VEILS.containsKey(position.asLong());
    }

    /** The predicted state at {@code position}, or {@code null} when nothing is predicted there. */
    public static BlockState predicted(BlockPos position) {
        return shown(position.asLong());
    }

    /**
     * The nearest veiled position the segment from {@code from} to {@code to} passes through, other
     * than {@code exclude}, or {@code null}. For a crosshair that went through a block drawn
     * differently from the world.
     */
    public static BlockPos firstAlong(Vec3 from, Vec3 to, BlockPos exclude) {
        if (!active) {
            return null;
        }
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Long key : VEILS.keySet()) {
            BlockPos position = BlockPos.of(key);
            if (position.equals(exclude)) {
                continue;
            }
            Optional<Vec3> entry = new AABB(position).clip(from, to);
            if (entry.isPresent()) {
                double distance = entry.get().distanceToSqr(from);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = position;
                }
            }
        }
        return nearest;
    }

    /**
     * Draws {@code shown} at {@code position} until the server has sent {@code expected}, in order.
     * A position already veiled keeps its queue: the new prediction follows the ones before it.
     */
    public static void predict(ClientLevel level, BlockPos position, BlockState shown, BlockState expected) {
        if (level != veiledLevel) {
            clear();
            veiledLevel = level;
        }
        long key = position.asLong();
        Veil veil = VEILS.get(key);
        if (veil == null) {
            if (VEILS.size() >= MAX_VEILS) {
                return;
            }
            veil = new Veil();
            VEILS.put(key, veil);
        }
        veil.shown = shown;
        veil.expected.addLast(expected);
        veil.since = System.nanoTime();
        active = true;
        remesh(level, position);
        relight(level, position);
    }

    /**
     * Every block state the server sends. A state that matches one a veil expects consumes it and
     * every state expected before it: when the server handles two clicks in one tick, only the
     * second state reaches the client. When nothing is left to expect, the world is drawn again.
     */
    public static void onServerBlock(ClientLevel level, BlockPos position, BlockState state) {
        if (!active) {
            return;
        }
        Veil veil = VEILS.get(position.asLong());
        if (veil == null) {
            return;
        }
        int index = 0;
        int match = -1;
        for (BlockState expected : veil.expected) {
            if (matches(expected, state)) {
                match = index;
                break;
            }
            index++;
        }
        for (int drop = 0; drop <= match; drop++) {
            veil.expected.pollFirst();
        }
        if (veil.expected.isEmpty()) {
            lift(level, position);
        }
    }

    /** Once a client tick: veils the server never answered are lifted. */
    public static void tick(Minecraft minecraft) {
        if (!active) {
            return;
        }
        ClientLevel level = minecraft.level;
        if (level == null || level != veiledLevel) {
            clear();
            return;
        }
        long now = System.nanoTime();
        long timeout = Latency.answerWindowNanos(MIN_TIMEOUT_NANOS, MAX_TIMEOUT_NANOS);
        Iterator<Map.Entry<Long, Veil>> iterator = VEILS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Veil> entry = iterator.next();
            if (now - entry.getValue().since > timeout) {
                iterator.remove();
                BlockPos position = BlockPos.of(entry.getKey());
                remesh(level, position);
                relight(level, position);
            }
        }
        active = !VEILS.isEmpty();
    }

    /** Draws the world at {@code position} again. */
    public static void lift(ClientLevel level, BlockPos position) {
        if (VEILS.remove(position.asLong()) != null) {
            active = !VEILS.isEmpty();
            if (level != null) {
                remesh(level, position);
                relight(level, position);
            }
        }
    }

    public static void clear() {
        VEILS.clear();
        active = false;
        veiledLevel = null;
    }

    /** Air for a detonated anchor: whatever is left there is not an anchor. */
    public static BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }

    public static BlockState anchor(int charge) {
        return Blocks.RESPAWN_ANCHOR.defaultBlockState()
                .setValue(RespawnAnchorBlock.CHARGE, Math.max(0, Math.min(RespawnAnchorBlock.MAX_CHARGES, charge)));
    }

    /**
     * Whether the server's state is the predicted one. Anything that is not an anchor answers a
     * detonation: the explosion can leave air or fire.
     */
    private static boolean matches(BlockState expected, BlockState actual) {
        if (!expected.is(Blocks.RESPAWN_ANCHOR)) {
            return !actual.is(Blocks.RESPAWN_ANCHOR);
        }
        return actual.is(Blocks.RESPAWN_ANCHOR)
                && actual.getValue(RespawnAnchorBlock.CHARGE).equals(expected.getValue(RespawnAnchorBlock.CHARGE));
    }

    /**
     * Has the client's light engine look at {@code position} again, where the light engine reads
     * the drawn state ({@code BlockLightEngineMixin}): processed with the frame's light updates,
     * which remesh whatever the light changed.
     */
    private static void relight(ClientLevel level, BlockPos position) {
        level.getChunkSource().getLightEngine().checkBlock(position);
    }

    private static void remesh(ClientLevel level, BlockPos position) {
        level.setSectionDirtyWithNeighbors(SectionPos.blockToSectionCoord(position.getX()),
                SectionPos.blockToSectionCoord(position.getY()), SectionPos.blockToSectionCoord(position.getZ()));
    }

    private static final class Veil {
        volatile BlockState shown;
        final ArrayDeque<BlockState> expected = new ArrayDeque<>();
        volatile long since;
    }
}
