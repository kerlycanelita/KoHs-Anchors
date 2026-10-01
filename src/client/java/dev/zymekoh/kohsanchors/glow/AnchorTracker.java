package dev.zymekoh.kohsanchors.glow;

import dev.zymekoh.kohsanchors.mixin.ClientLevelPredictionAccessor;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * The respawn anchors near the player, and who put each one there.
 *
 * <p>The client never learns who placed a block. It does know its own placements: every anchor
 * the player places goes through {@code useItemOn}, Vanilla predicts the block at once and stamps
 * the click with a sequence number, and the server answers that number with an acknowledgement
 * only after it has sent the states the click left behind. So an anchor the server reports at a
 * block while the player's placement there is still waiting for its acknowledgement is the
 * player's; once acknowledged, the placement is spent, and the next anchor to appear at that block
 * is someone else's even seconds later, as happens when two players keep using the same hole. An
 * anchor that appears from a server update where this client has no placement in flight is an
 * enemy's. Anchors that were already there when their chunk arrived have no known owner and glow
 * like the player's own.</p>
 *
 * <p>Discovery is event-driven for new anchors (server block updates and own placements) and a
 * small periodic scan for the ones that came with a chunk. The scan only walks sections whose block
 * palette may contain an anchor, a few chunks around the player, twice a second.</p>
 *
 * <p>It keeps running while the glow is off: who placed an anchor can only be seen the moment it
 * appears, so a tracker that stopped would call every enemy anchor unknown once the glow came back
 * on. Its cost is one map lookup per block update and the palette check above.</p>
 */
public final class AnchorTracker {
    public static final byte UNKNOWN = 0;
    public static final byte OWN = 1;
    public static final byte ENEMY = 2;

    /** How far around the player anchors are kept, in chunks. */
    private static final int RADIUS_CHUNKS = 3;
    /** How far above and below the player, in sections. */
    private static final int RADIUS_SECTIONS = 2;
    private static final int SCAN_INTERVAL_TICKS = 10;
    /**
     * A placement whose acknowledgement never came (the connection dropped) is forgotten after
     * this; a real acknowledgement takes one round trip.
     */
    private static final long PENDING_LIMIT_NANOS = 5_000_000_000L;
    private static final int MAX_TRACKED = 256;
    private static final int MAX_PENDING = 64;

    private static final Long2ByteOpenHashMap OWNERS = new Long2ByteOpenHashMap();
    /** The player's placements waiting for their acknowledgement: block → the click's sequence number. */
    private static final Long2IntOpenHashMap OWN_PENDING = new Long2IntOpenHashMap();
    private static final Long2LongOpenHashMap OWN_PENDING_AT = new Long2LongOpenHashMap();
    private static final BlockPos.MutableBlockPos CURSOR = new BlockPos.MutableBlockPos();
    private static ClientLevel trackedLevel;
    private static int scanCountdown;

    private AnchorTracker() {
    }

    /** The anchors known now, with their owners. Iterate on the client thread only. */
    public static Long2ByteMap anchors() {
        return OWNERS;
    }

    public static int count() {
        return OWNERS.size();
    }

    public static int enemyCount() {
        int enemies = 0;
        for (byte owner : OWNERS.values()) {
            if (owner == ENEMY) {
                enemies++;
            }
        }
        return enemies;
    }

    /**
     * The player placed an anchor at {@code position}, or at {@code alternative} if Vanilla judged
     * the clicked block replaceable differently; Vanilla has already predicted it and stamped the
     * click with a sequence number, which is still the prediction handler's current one.
     */
    public static void ownPlacement(ClientLevel level, BlockPos position, BlockPos alternative) {
        if (position == null) {
            return;
        }
        ensureLevel(level);
        int sequence = sequence(level);
        long now = System.nanoTime();
        if (OWN_PENDING.size() >= MAX_PENDING) {
            OWN_PENDING.clear();
            OWN_PENDING_AT.clear();
        }
        for (BlockPos candidate : new BlockPos[] {position, alternative}) {
            if (candidate == null) {
                continue;
            }
            long key = candidate.asLong();
            OWN_PENDING.put(key, sequence);
            OWN_PENDING_AT.put(key, now);
            // The prediction already put the anchor there: it is the player's from this frame on.
            if (!OWNERS.containsKey(key) && level.getBlockState(candidate).is(Blocks.RESPAWN_ANCHOR)) {
                track(key, OWN);
            }
        }
    }

    /**
     * The server acknowledged the player's clicks up to {@code sequence}: by now it has sent every
     * state those clicks left, so their placements are spent. An anchor that appears at one of those
     * blocks from now on is someone else's.
     */
    public static void onAcknowledged(ClientLevel level, int sequence) {
        if (level != trackedLevel || OWN_PENDING.isEmpty()) {
            return;
        }
        ObjectIterator<Long2IntMap.Entry> iterator = OWN_PENDING.long2IntEntrySet().iterator();
        while (iterator.hasNext()) {
            Long2IntMap.Entry entry = iterator.next();
            if (entry.getIntValue() <= sequence) {
                OWN_PENDING_AT.remove(entry.getLongKey());
                iterator.remove();
            }
        }
    }

    private static int sequence(ClientLevel level) {
        try {
            return ((ClientLevelPredictionAccessor) level).kohsAnchors$predictionHandler().currentSequence();
        } catch (RuntimeException unavailable) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Every block state the server sends, before it is applied: the world still holds the old one.
     */
    public static void onServerBlock(ClientLevel level, BlockPos position, BlockState state) {
        ensureLevel(level);
        long key = position.asLong();
        if (!state.is(Blocks.RESPAWN_ANCHOR)) {
            OWNERS.remove(key);
            return;
        }
        if (OWNERS.containsKey(key)) {
            // A charge, or the confirmation of an anchor the tracker already knows: the owner does
            // not change. An anchor that exploded was forgotten when its air arrived.
            return;
        }
        if (OWN_PENDING.containsKey(key)) {
            // The player's own placement there has not been acknowledged yet: this is its anchor.
            track(key, OWN);
        } else if (level.getBlockState(position).is(Blocks.RESPAWN_ANCHOR)) {
            track(key, UNKNOWN);
        } else {
            // A new anchor where this client has no placement in flight.
            track(key, ENEMY);
        }
    }

    /**
     * The server's bridge said who placed the anchor at this block, the moment the server accepted
     * the placement: that is final, whatever the acknowledgements suggested. It usually arrives just
     * before the block itself, which then keeps this owner.
     */
    public static void serverOwner(long key, boolean own) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        ensureLevel(level);
        OWNERS.remove(key);
        track(key, own ? OWN : ENEMY);
    }

    /** Once a client tick: forget what left, and now and then look for anchors chunks brought. */
    public static void tick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            if (trackedLevel != null) {
                clear();
            }
            return;
        }
        ensureLevel(level);
        if (--scanCountdown > 0) {
            return;
        }
        scanCountdown = SCAN_INTERVAL_TICKS;
        BlockPos center = minecraft.player.blockPosition();
        prune(level, center);
        scan(level, center);
    }

    public static void clear() {
        OWNERS.clear();
        OWN_PENDING.clear();
        OWN_PENDING_AT.clear();
        trackedLevel = null;
        scanCountdown = 0;
    }

    private static void track(long key, byte owner) {
        if (OWNERS.size() >= MAX_TRACKED && !OWNERS.containsKey(key)) {
            return;
        }
        OWNERS.put(key, owner);
    }

    private static void ensureLevel(ClientLevel level) {
        if (level != trackedLevel) {
            OWNERS.clear();
            OWN_PENDING.clear();
            OWN_PENDING_AT.clear();
            trackedLevel = level;
            scanCountdown = 0;
        }
    }

    private static void prune(ClientLevel level, BlockPos center) {
        int reach = (RADIUS_CHUNKS + 1) * 16;
        ObjectIterator<Long2ByteMap.Entry> iterator = OWNERS.long2ByteEntrySet().iterator();
        while (iterator.hasNext()) {
            long key = iterator.next().getLongKey();
            CURSOR.set(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
            if (Math.abs(CURSOR.getX() - center.getX()) > reach || Math.abs(CURSOR.getZ() - center.getZ()) > reach
                    || Math.abs(CURSOR.getY() - center.getY()) > reach
                    || !level.getBlockState(CURSOR).is(Blocks.RESPAWN_ANCHOR)) {
                iterator.remove();
            }
        }
        long now = System.nanoTime();
        ObjectIterator<Long2LongMap.Entry> pending = OWN_PENDING_AT.long2LongEntrySet().iterator();
        while (pending.hasNext()) {
            Long2LongMap.Entry entry = pending.next();
            if (now - entry.getLongValue() > PENDING_LIMIT_NANOS) {
                OWN_PENDING.remove(entry.getLongKey());
                pending.remove();
            }
        }
    }

    private static void scan(ClientLevel level, BlockPos center) {
        int chunkX = SectionPos.blockToSectionCoord(center.getX());
        int chunkZ = SectionPos.blockToSectionCoord(center.getZ());
        int sectionY = SectionPos.blockToSectionCoord(center.getY());
        int minSection = level.getMinSectionY();
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(chunkX + dx, chunkZ + dz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int sy = sectionY - RADIUS_SECTIONS; sy <= sectionY + RADIUS_SECTIONS; sy++) {
                    int index = sy - minSection;
                    if (index < 0 || index >= sections.length) {
                        continue;
                    }
                    LevelChunkSection section = sections[index];
                    if (section == null || section.hasOnlyAir()
                            || !section.maybeHas(state -> state.is(Blocks.RESPAWN_ANCHOR))) {
                        continue;
                    }
                    scanSection(section, (chunkX + dx) << 4, sy << 4, (chunkZ + dz) << 4);
                }
            }
        }
    }

    private static void scanSection(LevelChunkSection section, int baseX, int baseY, int baseZ) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (section.getBlockState(x, y, z).is(Blocks.RESPAWN_ANCHOR)) {
                        long key = BlockPos.asLong(baseX + x, baseY + y, baseZ + z);
                        if (!OWNERS.containsKey(key)) {
                            track(key, UNKNOWN);
                        }
                    }
                }
            }
        }
    }
}
