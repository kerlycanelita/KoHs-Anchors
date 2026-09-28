package dev.zymekoh.kohsanchors.predict;

import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.input.AnchorStats;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Knows, from the client's side, that an anchor is about to explode, and acts on it without
 * touching the world.
 *
 * <p>Outside the dimensions where anchors set a spawn point, using a charged anchor makes the
 * server explode it; the client only learns this when the server's explosion packet arrives. The
 * client already has everything needed to know it will happen (the charge and the dimension's
 * rule), so:</p>
 *
 * <ul>
 *   <li>the explosion's sound and flash are played on the tick of the press, and not again when
 *   the server's explosion arrives for the same block (its debris and knockback apply as
 *   always);</li>
 *   <li>the anchor is remembered as detonating until the server removes it, so a click aimed at
 *   it in the meantime can wait for the free block instead of landing on the old anchor.</li>
 * </ul>
 *
 * <h2>Why the anchor is not removed on the client</h2>
 *
 * <p>Removing it at once lets the next anchor go down before the server's removal reaches the
 * client. The KoHs Anchor lab measured exactly that on a Grim Anticheat server: Grim models the
 * block from what the client has received, still sees the old anchor's block as it was when the
 * next click arrives, flags {@code AirLiquidPlace} and cancels the click. A Vanilla client cannot
 * produce that sequence, so the block is left to the server and early clicks are held instead.</p>
 */
public final class DetonationPredictor {
    /** Longer than any round trip worth playing on; a later explosion is treated as a new one. */
    private static final long CONFIRM_WINDOW_NANOS = 1_500_000_000L;
    private static final int MAX_PENDING = 16;
    private static final double SAME_CENTER = 1.0E-3;

    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    /** Anchors this client detonated that the server has not removed yet, with when. */
    private static final Map<BlockPos, Long> DETONATING = new HashMap<>();

    /** Whether the explosion packet being handled right now was already shown. */
    private static boolean handlingPredicted;

    private DetonationPredictor() {
    }

    /**
     * Called after Vanilla's client-side {@code useWithoutItem} on a respawn anchor. Vanilla has
     * already decided the use happened; this only reads that decision.
     */
    public static void onAnchorUsed(Level level, BlockPos position, BlockState state, Player player,
            InteractionResult result) {
        if (!(level instanceof ClientLevel clientLevel)) {
            return;
        }
        if (player != Minecraft.getInstance().player || !(result instanceof InteractionResult.Success)) {
            return;
        }
        if (!state.is(Blocks.RESPAWN_ANCHOR) || state.getValue(RespawnAnchorBlock.CHARGE) <= 0) {
            return;
        }
        // The same rule the server applies: where anchors work, a use sets the spawn instead.
        if (Boolean.TRUE.equals(clientLevel.environmentAttributes()
                .getValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, position))) {
            return;
        }

        long now = System.nanoTime();
        DETONATING.put(position.immutable(), now);
        if (!AnchorsConfig.settings().predictDetonation) {
            return;
        }
        expire(now);
        if (PENDING.size() >= MAX_PENDING) {
            PENDING.pollFirst();
        }
        Vec3 center = Vec3.atCenterOf(position);
        PENDING.addLast(new Pending(center, now));

        // The same sound and flash, with the same parameters, that Vanilla plays for the server's
        // explosion packet of an anchor.
        RandomSource random = clientLevel.getRandom();
        clientLevel.playLocalSound(center.x, center.y, center.z, SoundEvents.GENERIC_EXPLODE.value(),
                SoundSource.BLOCKS, 4.0F, (1.0F + (random.nextFloat() - random.nextFloat()) * 0.2F) * 0.7F, false);
        clientLevel.addParticle(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y, center.z, 1.0D, 0.0D, 0.0D);
        AnchorStats.predictedDetonation();
    }

    /**
     * Whether the client detonated the anchor at {@code position} and the server has not removed
     * it yet. A detonation the server never answers stops counting after the confirm window.
     */
    public static boolean isDetonating(BlockPos position) {
        Long since = DETONATING.get(position);
        if (since == null) {
            return false;
        }
        if (System.nanoTime() - since > CONFIRM_WINDOW_NANOS) {
            DETONATING.remove(position);
            return false;
        }
        return true;
    }

    /** Every block state the server sends. Anything but an anchor means the anchor is gone. */
    public static void onServerBlock(BlockPos position, BlockState state) {
        if (!DETONATING.isEmpty() && !state.is(Blocks.RESPAWN_ANCHOR)) {
            DETONATING.remove(position);
        }
    }

    /**
     * The explosion packet's sound, which Vanilla plays first. Decides for the whole packet
     * whether it was already shown.
     *
     * @return whether Vanilla should still play it
     */
    public static boolean shouldPlayServerSound(double x, double y, double z) {
        handlingPredicted = confirm(x, y, z, System.nanoTime());
        if (handlingPredicted) {
            AnchorStats.confirmedDetonation();
        }
        return !handlingPredicted;
    }

    /** The explosion packet's flash, right after its sound. */
    public static boolean shouldDrawServerFlash() {
        return !handlingPredicted;
    }

    /**
     * The explosion packet's block debris. Never predicted; only hidden for anchors when the
     * player turned anchor debris off.
     */
    public static boolean shouldDrawDebris(ClientLevel level, Vec3 center) {
        if (AnchorsConfig.settings().anchorDebris) {
            return true;
        }
        // The server sends the explosion before the block changes, so an anchor explosion still
        // finds its anchor on the client.
        return !handlingPredicted && !level.getBlockState(BlockPos.containing(center)).is(Blocks.RESPAWN_ANCHOR);
    }

    private static boolean confirm(double x, double y, double z, long now) {
        expire(now);
        Iterator<Pending> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            Vec3 center = iterator.next().center();
            if (Math.abs(center.x - x) < SAME_CENTER && Math.abs(center.y - y) < SAME_CENTER
                    && Math.abs(center.z - z) < SAME_CENTER) {
                // One prediction answers exactly one explosion: an anchor placed again on the
                // same block and detonated again is a new prediction and a new explosion.
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private static void expire(long now) {
        while (!PENDING.isEmpty() && now - PENDING.peekFirst().createdAt() > CONFIRM_WINDOW_NANOS) {
            PENDING.pollFirst();
        }
    }

    private record Pending(Vec3 center, long createdAt) {
    }
}
