package dev.zymekoh.kohsanchors.input;

import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.glow.AnchorTracker;
import dev.zymekoh.kohsanchors.predict.AnchorVeil;
import dev.zymekoh.kohsanchors.predict.DetonationPredictor;
import dev.zymekoh.kohsanchors.predict.Latency;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The anchor chain of the core: a click on an anchor that is still exploding goes out at once, the
 * way Vanilla sends it, and what it will do is drawn at once.
 *
 * <p>Every click is one press of the player, sent by Vanilla's own {@code startUseItem} to the block
 * Vanilla's raycast hits: on an anchor this client detonated, that is the old anchor it still has,
 * the same packet a Vanilla player clicking it sends. The server, which has exploded it by then,
 * puts the new anchor in its place, charges it and detonates it: "detonate, place, charge,
 * detonate" reach it as fast as they were pressed, whatever the latency.</p>
 *
 * <p>Up to 0.5.0 these clicks waited on the client for the server's removal and were then aimed
 * again. On the ground of a real fight, which each explosion breaks, that second aim went over the
 * hole or into it, and the waiting clicks fell out of step with the next ones: 13 % of the anchors
 * exploded at +100 ms in the lab, against 60 % for Vanilla. Not waiting is safe because the client
 * world follows the server at an anchor's block ({@link DetonationPredictor#mirrors}): once the
 * server's removal arrives, no click is aimed at the old anchor any more, so an anticheat never sees
 * a click on a block that is air for the client (Grim's {@code AirLiquidPlace}). Vanilla keeps the
 * old anchor until its acknowledgement and sends exactly those clicks.</p>
 *
 * <p>Each click is judged against what is drawn (the veil's prediction), not against the stale
 * world:</p>
 * <ul>
 *   <li>anchors on an anchor drawn as gone: the next anchor, drawn at once;</li>
 *   <li>glowstone on a drawn anchor: one more charge, drawn at once;</li>
 *   <li>anything else on a drawn charged anchor: its detonation, shown at once;</li>
 *   <li>glowstone where no anchor is drawn, or anchors on a fresh one: dropped, since the server
 *   would place a glowstone block or stack an anchor;</li>
 *   <li>a click whose raycast passes through a drawn anchor the world does not have yet waits
 *   until the world has it, so it lands on the anchor instead of on the floor behind it.</li>
 * </ul>
 */
final class AnchorChain {
    /**
     * {@code RUN_PREDICTED}: an anchor or a charge drawn at once. {@code RUN_DETONATED}: a detonation
     * shown at once, whose click the predictor stamps.
     */
    enum Outcome { RUN, RUN_PREDICTED, RUN_DETONATED, CATCH_UP, DROP }

    /** How long a charge counts as on its way: the server's answer, as the veil waits for it. */
    private static final long CHARGE_WINDOW_NANOS = 400_000_000L;
    private static final long CHARGE_WINDOW_MAX_NANOS = 1_500_000_000L;

    private static BlockPos catchUpTarget;
    /**
     * The anchor glowstone was last sent to, and when. Until the server answers, it may already be
     * charged there although this client still shows it empty: a click with anchors on it is then
     * the detonation the player meant (a double anchor's first click), not an anchor stacked on top.
     */
    private static BlockPos chargeSentTo;
    private static long chargeSentAt;

    private AnchorChain() {
    }

    static Outcome decide(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || !(minecraft.hitResult instanceof BlockHitResult hit)) {
            return Outcome.RUN;
        }
        // A miss still has a ray: through an anchor that is drawn but not in the world yet, it is a
        // click meant for that anchor, and waits for it like any other.
        BlockPos target = hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
        if (DetonationPredictor.anchorsWork(level, target != null ? target : player.blockPosition())) {
            return Outcome.RUN;
        }
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        if (player.isSecondaryUseActive() && (!main.isEmpty() || !off.isEmpty())) {
            // Sneaking with something in hand uses the item, not the block.
            return Outcome.RUN;
        }

        noteCharge(level, target, main, off);
        Vec3 eye = player.getEyePosition();
        BlockPos crossed = AnchorVeil.firstAlong(eye, hit.getLocation(), target);
        if (crossed != null) {
            BlockState drawn = AnchorVeil.predicted(crossed);
            if (drawn != null && drawn.is(Blocks.RESPAWN_ANCHOR)) {
                // The player sees an anchor there; the world is still empty and the raycast went
                // through to the block behind it.
                catchUpTarget = crossed;
                return Outcome.CATCH_UP;
            }
            if (main.is(Items.GLOWSTONE)) {
                // Glowstone meant for an anchor that is not there yet would become a glowstone
                // block in the anchor's place.
                AnchorStats.droppedClicks(1);
                return Outcome.DROP;
            }
            if (main.is(Items.RESPAWN_ANCHOR) && drawn != null && target != null) {
                // The world is empty there too: Vanilla places the anchor into the free spot,
                // and it is drawn at once instead of the air still shown there.
                AnchorVeil.predict(level, crossed, AnchorVeil.anchor(0), AnchorVeil.anchor(0));
                AnchorStats.chainedClick();
                return Outcome.RUN_PREDICTED;
            }
            return Outcome.RUN;
        }
        if (target == null) {
            return Outcome.RUN;
        }

        BlockState drawn = AnchorVeil.predicted(target);
        if (drawn == null) {
            BlockState world = level.getBlockState(target);
            if (main.is(Items.RESPAWN_ANCHOR) && world.is(Blocks.RESPAWN_ANCHOR)
                    && world.getValue(RespawnAnchorBlock.CHARGE) == 0 && chargeInFlight(target)) {
                // Glowstone is on its way to this anchor: the server detonates it with this click.
                DetonationPredictor.detonate(level, target);
                AnchorStats.chainedClick();
                return Outcome.RUN_DETONATED;
            }
            if (main.is(Items.RESPAWN_ANCHOR) && world.is(Blocks.RESPAWN_ANCHOR)
                    && world.getValue(RespawnAnchorBlock.CHARGE) == 0
                    && AnchorTracker.anchors().get(target.asLong()) == AnchorTracker.OWN
                    && AnchorsConfig.settings().noStacking) {
                // The player's own anchor, not charged yet: the next anchor clicked on it would sit
                // on top of it, and every cycle after would aim one block higher. Sneaking still
                // stacks, as it does in Vanilla.
                AnchorStats.droppedClicks(1);
                return Outcome.DROP;
            }
            // The world as it is; a detonation of it is shown by the predictor as always.
            return Outcome.RUN;
        }
        if (!drawn.is(Blocks.RESPAWN_ANCHOR)) {
            if (main.is(Items.RESPAWN_ANCHOR)) {
                AnchorVeil.predict(level, target, AnchorVeil.anchor(0), AnchorVeil.anchor(0));
                AnchorStats.chainedClick();
                return Outcome.RUN_PREDICTED;
            }
            if (main.is(Items.GLOWSTONE) || off.is(Items.GLOWSTONE)) {
                AnchorStats.droppedClicks(1);
                return Outcome.DROP;
            }
            return Outcome.RUN;
        }

        int charge = drawn.getValue(RespawnAnchorBlock.CHARGE);
        boolean chargeable = charge < RespawnAnchorBlock.MAX_CHARGES;
        if (chargeable && (main.is(Items.GLOWSTONE) || off.is(Items.GLOWSTONE))) {
            AnchorVeil.predict(level, target, AnchorVeil.anchor(charge + 1), AnchorVeil.anchor(charge + 1));
            AnchorStats.chainedClick();
            return Outcome.RUN_PREDICTED;
        }
        if (charge == 0 && main.is(Items.RESPAWN_ANCHOR) && chargeInFlight(target)) {
            DetonationPredictor.detonate(level, target);
            AnchorStats.chainedClick();
            return Outcome.RUN_DETONATED;
        }
        if (charge == 0) {
            if (main.is(Items.RESPAWN_ANCHOR)) {
                // A second anchor on the one the previous click placed.
                AnchorStats.droppedClicks(1);
                return Outcome.DROP;
            }
            return Outcome.RUN;
        }
        DetonationPredictor.detonate(level, target);
        AnchorStats.chainedClick();
        return Outcome.RUN_DETONATED;
    }

    /** Remembers glowstone clicked on an anchor that can take a charge. */
    private static void noteCharge(ClientLevel level, BlockPos target, ItemStack main, ItemStack off) {
        if (target == null || !(main.is(Items.GLOWSTONE) || off.is(Items.GLOWSTONE))) {
            return;
        }
        BlockState drawn = AnchorVeil.predicted(target);
        BlockState state = drawn != null ? drawn : level.getBlockState(target);
        if (state.is(Blocks.RESPAWN_ANCHOR) && state.getValue(RespawnAnchorBlock.CHARGE) < RespawnAnchorBlock.MAX_CHARGES) {
            chargeSentTo = target.immutable();
            chargeSentAt = System.nanoTime();
        }
    }

    /** Whether glowstone went to the anchor at {@code target} recently enough that the server may not have answered yet. */
    private static boolean chargeInFlight(BlockPos target) {
        return target.equals(chargeSentTo) && System.nanoTime() - chargeSentAt
                < Latency.answerWindowNanos(CHARGE_WINDOW_NANOS, CHARGE_WINDOW_MAX_NANOS);
    }

    static BlockPos catchUpTarget() {
        return catchUpTarget;
    }

    /** Whether the world now has the anchor the player saw, or nothing is drawn there any more. */
    static boolean caughtUp(ClientLevel level, BlockPos position) {
        return level == null || !AnchorVeil.isVeiled(position) || level.getBlockState(position).is(Blocks.RESPAWN_ANCHOR);
    }
}
