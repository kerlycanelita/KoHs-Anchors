package dev.zymekoh.kohsanchors.input;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;

/**
 * The anchor cycle: from the click that placed one of the player's anchors to the server's removal
 * of that anchor by its explosion. Measured on the client, where the player feels it, so it
 * includes the round trips; it is only shown, never used to decide anything.
 */
public final class AnchorCycles {
    /** A placement this old is not the start of the explosion that comes now. */
    private static final long MAX_CYCLE_NANOS = 5_000_000_000L;
    private static final int MAX_TRACKED = 64;

    private static final Long2LongOpenHashMap PLACED_AT = new Long2LongOpenHashMap();

    private AnchorCycles() {
    }

    /** The player placed an anchor at {@code position}. */
    public static void placed(BlockPos position) {
        if (PLACED_AT.size() >= MAX_TRACKED) {
            PLACED_AT.clear();
        }
        PLACED_AT.put(position.asLong(), System.nanoTime());
    }

    /** The server removed the anchor at {@code position} after the player detonated it. */
    public static void exploded(BlockPos position) {
        long placedAt = PLACED_AT.remove(position.asLong());
        if (placedAt == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - placedAt;
        if (elapsed > 0L && elapsed < MAX_CYCLE_NANOS) {
            AnchorStats.anchorCycle(Math.round(elapsed / 1_000_000.0D));
        }
    }

    public static void clear() {
        PLACED_AT.clear();
    }
}
