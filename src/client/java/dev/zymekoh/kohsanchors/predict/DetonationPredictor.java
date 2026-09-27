package dev.zymekoh.kohsanchors.predict;

import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.input.AnchorStats;
import java.util.ArrayDeque;
import java.util.Iterator;
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
 * Shows a charged anchor's detonation the moment the player uses it, instead of one round trip
 * later.
 *
 * <p>Outside the dimensions where anchors set a spawn point, using a charged anchor makes the
 * server explode it; the client only learns this when the server's explosion packet arrives. The
 * client already has everything needed to know it will happen (the charge and the dimension's
 * rule), so the explosion's sound and flash are played locally on the tick of the press.</p>
 *
 * <p>Nothing else is predicted: no block is removed, nobody takes damage or knockback, and the
 * world is not touched. When the server's explosion arrives for the same block, its sound and
 * flash are skipped because they already played; its debris, knockback and block changes apply
 * as always. A prediction the server never confirms simply expires.</p>
 */
public final class DetonationPredictor {
    /** Longer than any round trip worth playing on; a later explosion is treated as a new one. */
    private static final long CONFIRM_WINDOW_NANOS = 1_500_000_000L;
    private static final int MAX_PENDING = 16;
    private static final double SAME_CENTER = 1.0E-3;

    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();

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
        if (!AnchorsConfig.settings().predictDetonation || !(level instanceof ClientLevel clientLevel)) {
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
