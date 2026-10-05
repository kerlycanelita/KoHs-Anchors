package dev.zymekoh.kohsanchors.predict;

import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.input.AnchorCycles;
import dev.zymekoh.kohsanchors.input.AnchorDebounce;
import dev.zymekoh.kohsanchors.input.AnchorStats;
import dev.zymekoh.kohsanchors.mixin.KeyMappingAccessor;
import dev.zymekoh.kohsanchors.mixin.ClientLevelPredictionAccessor;
import dev.zymekoh.kohsanchors.safety.ServerLock;
import dev.zymekoh.kohsanchors.sound.AnchorSounds;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.phys.Vec3;

/**
 * Knows, from the client's side, that an anchor is about to explode, and shows it without
 * touching the world.
 *
 * <p>Outside the dimensions where anchors set a spawn point, using a charged anchor makes the
 * server explode it; the client only learns this when the server's explosion packet arrives. The
 * client already has everything needed to know it will happen (the charge, the item and the
 * dimension's rule), so:</p>
 *
 * <ul>
 *   <li>the explosion's sound and flash are played at once, and not again when the server's
 *   explosion arrives for the same block (its debris and knockback apply as always);</li>
 *   <li>the anchor is drawn as gone at once ({@link AnchorVeil}); the block itself stays in the
 *   client world until the server removes it;</li>
 *   <li>the anchor is remembered as detonating until the server removes it, so a click aimed at
 *   it in the meantime can wait for the free block instead of landing on the old anchor.</li>
 * </ul>
 *
 * <p>"At once" is the moment the click is pressed, not the next client tick: a click that will
 * certainly detonate is shown from the key or button handler, and the tick that then runs the use
 * only confirms it. This is drawing and sound only; the use itself still runs in the tick.</p>
 *
 * <h2>When the server keeps the anchor</h2>
 *
 * <p>Every click carries a sequence number, and the server acknowledges it after it has handled
 * the click and sent the block's new state. If the anchor is still in the client world when its
 * detonation click is acknowledged, the server did not explode it (a rejected click, a protected
 * area): it is drawn again at once, instead of staying invisible until a timeout, and the clicks
 * that were waiting for it are dropped rather than landed on it.</p>
 *
 * <h2>Why the anchor is not removed from the client world</h2>
 *
 * <p>Removing it at once lets the next anchor go down before the server's removal reaches the
 * client. The KoHs Anchor lab measured exactly that on a Grim Anticheat server: Grim models the
 * block from what the client has received, still sees the old anchor's block as it was when the
 * next click arrives, flags {@code AirLiquidPlace} and cancels the click. So the block is only
 * hidden from the frame, and early clicks are held instead.</p>
 */
public final class DetonationPredictor {
    /** Longer than any round trip worth playing on; a later explosion is treated as a new one. */
    private static final long CONFIRM_WINDOW_NANOS = 1_500_000_000L;
    /**
     * An input-time prediction the next ticks did not confirm is taken back after this, unless its
     * click is still queued (a tick that already clicked with another item leaves it for the next
     * one); a click queued longer than the hard limit is not coming.
     */
    private static final long INPUT_CONFIRM_NANOS = 250_000_000L;
    private static final long INPUT_CONFIRM_LIMIT_NANOS = 600_000_000L;
    private static final int MAX_PENDING = 16;
    private static final double SAME_CENTER = 1.0E-3;

    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    /** Anchors this client detonated that the server has not removed yet. */
    private static final Map<BlockPos, Detonation> DETONATING = new HashMap<>();
    /** Detonations shown from the input handler, waiting for the tick's use to confirm them. */
    private static final Map<BlockPos, Long> SHOWN_AT_INPUT = new HashMap<>();

    /** Whether the explosion packet being handled right now was already shown. */
    private static boolean handlingPredicted;
    /** Set while a use runs whose outcome the advanced chain already predicted. */
    private static boolean usePredictedElsewhere;

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
        if (!state.is(Blocks.RESPAWN_ANCHOR) || state.getValue(RespawnAnchorBlock.CHARGE) <= 0
                || anchorsWork(clientLevel, position)) {
            return;
        }
        AnchorDebounce.noteAnchorPlay();
        // This runs inside the prediction of the click being sent: its sequence number is the one
        // the server will acknowledge.
        int sequence = currentSequence(clientLevel);
        if (usePredictedElsewhere) {
            stamp(position, sequence);
            return;
        }
        if (SHOWN_AT_INPUT.remove(position) != null) {
            // Shown when the click was pressed; this tick is the use it predicted.
            stamp(position, sequence);
            return;
        }
        if (isDetonating(position) && !(AnchorVeil.predicted(position) instanceof BlockState shown && isCharged(shown))) {
            // Another click on an anchor that is already exploding: the same explosion.
            return;
        }
        detonate(clientLevel, position);
        stamp(position, sequence);
    }

    /**
     * Shows a detonation of the anchor at {@code position}: remembered as detonating, drawn as
     * gone and, when enabled, heard and seen exploding.
     */
    public static void detonate(ClientLevel level, BlockPos position) {
        long now = System.nanoTime();
        BlockPos key = position.immutable();
        DETONATING.put(key, new Detonation(now));
        BlockState shown = AnchorVeil.predicted(key);
        AnchorFade.start(level, key, shown != null ? shown : level.getBlockState(key));
        if (AnchorsConfig.settings().hideDetonating || ServerLock.fastChain()) {
            AnchorVeil.predict(level, key, AnchorVeil.air(), AnchorVeil.air());
        }
        if (!AnchorsConfig.settings().predictDetonation) {
            return;
        }
        expire(now);
        if (PENDING.size() >= MAX_PENDING) {
            PENDING.pollFirst();
        }
        Vec3 center = Vec3.atCenterOf(key);
        PENDING.addLast(new Pending(center, now));

        // The same sound and flash, with the same parameters, that Vanilla plays for the server's
        // explosion packet of an anchor, or the player's own sound when they chose one.
        AnchorSounds.playExplosion(level, center.x, center.y, center.z);
        AnchorSmoke.flash(level, center.x, center.y, center.z);
        AnchorStats.predictedDetonation();
    }

    /**
     * From the key or button handler, the moment "use" is pressed: when the click will certainly
     * detonate the anchor under the crosshair, the detonation is shown now instead of on the next
     * tick. Drawing and sound only; the use itself still runs in the tick, which confirms it.
     *
     * @return whether a detonation was shown
     */
    public static boolean showAtInput(Minecraft minecraft, BlockPos position) {
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || isDetonating(position) || AnchorVeil.isVeiled(position)) {
            return false;
        }
        BlockState state = level.getBlockState(position);
        if (!willDetonate(level, position, state, player)) {
            return false;
        }
        detonate(level, position);
        SHOWN_AT_INPUT.put(position.immutable(), System.nanoTime());
        return true;
    }

    /**
     * Whether a detonation of the anchor at {@code position} was shown when its click was pressed
     * and still waits for the tick's use. That use is the click itself: it must run, never wait
     * for the removal its own detonation will cause.
     */
    public static boolean awaitsUse(BlockPos position) {
        return position != null && SHOWN_AT_INPUT.containsKey(position);
    }

    /**
     * Whether a use by {@code player} on {@code state} at {@code position} makes the server explode
     * the anchor. The same decision {@code RespawnAnchorBlock} makes, from the same inputs.
     */
    public static boolean willDetonate(ClientLevel level, BlockPos position, BlockState state, LocalPlayer player) {
        if (!state.is(Blocks.RESPAWN_ANCHOR) || anchorsWork(level, position)) {
            return false;
        }
        int charge = state.getValue(RespawnAnchorBlock.CHARGE);
        if (charge <= 0) {
            return false;
        }
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        // Sneaking with something in hand uses the item, not the block.
        if (player.isSecondaryUseActive() && (!main.isEmpty() || !off.isEmpty())) {
            return false;
        }
        boolean chargeable = charge < RespawnAnchorBlock.MAX_CHARGES;
        if (main.is(Items.GLOWSTONE) && chargeable) {
            return false;
        }
        // Glowstone in the off hand charges before the main hand's item can detonate.
        return !(off.is(Items.GLOWSTONE) && chargeable);
    }

    /**
     * Whether anchors set the spawn point here, so a use never explodes one.
     *
     * <p>The server decides with the {@code respawn_anchor_works} environment attribute, which is
     * not one the game syncs: a client that builds its dimensions from the server's registry data
     * reads it as false everywhere, and 1.21.11 does, in singleplayer too. Read alone it made a
     * set-spawn use in the Nether look like a detonation. So the Nether's own dimension type, which
     * servers' extra Nether worlds share, says it as well; the attribute still counts where the
     * client has it.</p>
     */
    public static boolean anchorsWork(ClientLevel level, BlockPos position) {
        return level.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER) || level.dimension() == Level.NETHER
                || Boolean.TRUE.equals(level.environmentAttributes().getValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS,
                        position));
    }

    /** Runs {@code use} as a use whose outcome the caller has already shown. */
    public static void runPredicted(Runnable use) {
        usePredictedElsewhere = true;
        try {
            use.run();
        } finally {
            usePredictedElsewhere = false;
        }
    }

    /**
     * Whether the client detonated the anchor at {@code position} and the server has neither
     * removed it nor said it kept it. A detonation the server never answers stops counting after
     * the confirm window.
     */
    public static boolean isDetonating(BlockPos position) {
        Detonation detonation = DETONATING.get(position);
        if (detonation == null || detonation.failed) {
            return false;
        }
        if (System.nanoTime() - detonation.since > CONFIRM_WINDOW_NANOS) {
            DETONATING.remove(position);
            return false;
        }
        return true;
    }

    /** Whether the server acknowledged the detonation click at {@code position} and kept the anchor. */
    public static boolean failed(BlockPos position) {
        Detonation detonation = DETONATING.get(position);
        return detonation != null && detonation.failed;
    }

    /** Forgets a detonation the server kept, once the clicks waiting for it have been dropped. */
    public static void forget(BlockPos position) {
        Detonation detonation = DETONATING.get(position);
        if (detonation != null && detonation.failed) {
            DETONATING.remove(position);
        }
    }

    /**
     * Every block state the server sends, after Vanilla handled it. Anything but an anchor means the
     * anchor is gone. But while one of the player's own predictions there waits for its
     * acknowledgement, Vanilla keeps the server's state aside and goes on drawing, and raycasting,
     * the anchor it predicted, until the acknowledgement applies it (the server sends the
     * acknowledgement at the end of its tick, after the states). Until then the anchor still counts as
     * detonating: clicks aimed at it keep waiting, instead of landing on an anchor that is no longer
     * there or taking it for one the server kept. The server lab caught that window with the glowstone
     * and the sword of one cycle handled in one server tick.
     */
    public static void afterServerBlock(ClientLevel level, BlockPos position, BlockState state) {
        AnchorVeil.afterServerBlock(level, position, state);
        if (DETONATING.isEmpty() || state.is(Blocks.RESPAWN_ANCHOR)) {
            return;
        }
        Detonation detonation = DETONATING.get(position);
        if (detonation == null) {
            return;
        }
        if (level.getBlockState(position).is(Blocks.RESPAWN_ANCHOR)) {
            detonation.removedByServer = true;
            return;
        }
        DETONATING.remove(position);
        AnchorCycles.exploded(position);
    }

    /**
     * The server acknowledged every click up to {@code sequence}, and Vanilla has applied the states
     * it kept aside for them. An anchor the server removed is gone from the world now; a detonation
     * whose anchor is still in the world was not exploded: it is drawn again now.
     */
    public static void onAcknowledged(ClientLevel level, int sequence) {
        AnchorVeil.onAcknowledged(level);
        if (DETONATING.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<BlockPos, Detonation>> iterator = DETONATING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Detonation> entry = iterator.next();
            Detonation detonation = entry.getValue();
            BlockPos position = entry.getKey();
            if (detonation.removedByServer) {
                if (!level.getBlockState(position).is(Blocks.RESPAWN_ANCHOR)) {
                    iterator.remove();
                    AnchorCycles.exploded(position);
                }
                continue;
            }
            if (detonation.failed || detonation.sequence < 0 || detonation.sequence > sequence) {
                continue;
            }
            if (level.getBlockState(position).is(Blocks.RESPAWN_ANCHOR)) {
                detonation.failed = true;
                SHOWN_AT_INPUT.remove(position);
                AnchorVeil.lift(level, position);
                AnchorStats.keptDetonation();
            }
        }
    }

    /** Once a client tick: detonations shown at input that no use confirmed are taken back. */
    public static void tick(Minecraft minecraft) {
        if (SHOWN_AT_INPUT.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        boolean usePending = ((KeyMappingAccessor) minecraft.options.keyUse).kohsAnchors$clickCount() > 0;
        Iterator<Map.Entry<BlockPos, Long>> iterator = SHOWN_AT_INPUT.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Long> entry = iterator.next();
            long age = now - entry.getValue();
            if (age > INPUT_CONFIRM_LIMIT_NANOS || age > INPUT_CONFIRM_NANOS && !usePending) {
                iterator.remove();
                DETONATING.remove(entry.getKey());
                AnchorVeil.lift(minecraft.level, entry.getKey());
            }
        }
    }

    /**
     * The first use of a tick is the click a detonation was shown for when it was pressed (only a
     * lone use press is ever shown). Aimed elsewhere now (the crosshair moved between the press and
     * the tick), that detonation is not going to happen: it is taken back at once, so the anchor
     * is drawn again instead of staying hidden while it still stands.
     */
    public static void firstUseOfTick(ClientLevel level, BlockPos target) {
        if (SHOWN_AT_INPUT.isEmpty() || level == null) {
            return;
        }
        Iterator<Map.Entry<BlockPos, Long>> iterator = SHOWN_AT_INPUT.entrySet().iterator();
        while (iterator.hasNext()) {
            BlockPos shown = iterator.next().getKey();
            if (!shown.equals(target)) {
                iterator.remove();
                DETONATING.remove(shown);
                AnchorVeil.lift(level, shown);
                AnchorStats.withdrawnDetonation();
            }
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
        return !handlingPredicted && !isAnchorExplosion(level, center);
    }

    /** Whether an explosion centred at {@code center} is an anchor's: its anchor is still there. */
    public static boolean isAnchorExplosion(ClientLevel level, Vec3 center) {
        return level.getBlockState(BlockPos.containing(center)).is(Blocks.RESPAWN_ANCHOR);
    }

    static boolean isCharged(BlockState state) {
        return state.is(Blocks.RESPAWN_ANCHOR) && state.getValue(RespawnAnchorBlock.CHARGE) > 0;
    }

    private static void stamp(BlockPos position, int sequence) {
        Detonation detonation = DETONATING.get(position);
        if (detonation != null && sequence >= 0) {
            detonation.sequence = sequence;
        }
    }

    private static int currentSequence(ClientLevel level) {
        try {
            return ((ClientLevelPredictionAccessor) level).kohsAnchors$predictionHandler().currentSequence();
        } catch (RuntimeException unavailable) {
            return -1;
        }
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

    /**
     * One detonation: when it was shown, the click's sequence number, whether it failed, and whether
     * the server removed its anchor while Vanilla still draws it.
     */
    private static final class Detonation {
        final long since;
        int sequence = -1;
        boolean failed;
        boolean removedByServer;

        Detonation(long since) {
            this.since = since;
        }
    }
}
