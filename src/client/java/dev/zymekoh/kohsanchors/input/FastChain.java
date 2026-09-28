package dev.zymekoh.kohsanchors.input;

import dev.zymekoh.kohsanchors.predict.AnchorVeil;
import dev.zymekoh.kohsanchors.predict.DetonationPredictor;
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
 * The advanced, not secure chain: clicks on an anchor that is still exploding are not held until
 * the server removes it, and what each click will do is drawn at once.
 *
 * <p>Every click is still one press of the player, sent by Vanilla's own {@code startUseItem} to
 * the block Vanilla's raycast hits, the same packet a Vanilla player clicking the still visible
 * anchor sends. What changes is only that nothing waits: the server receives "detonate, place,
 * charge, detonate" as fast as they were pressed. That is why it is not secure: an anticheat such
 * as Grim can flag a placement against a block the client has not yet seen removed
 * ({@code AirLiquidPlace}), and servers may forbid it.</p>
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
final class FastChain {
    enum Outcome { RUN, RUN_PREDICTED, CATCH_UP, DROP }

    private static BlockPos catchUpTarget;

    private FastChain() {
    }

    static Outcome decide(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || !(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            return Outcome.RUN;
        }
        BlockPos target = hit.getBlockPos();
        if (DetonationPredictor.anchorsWork(level, target)) {
            return Outcome.RUN;
        }
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        if (player.isSecondaryUseActive() && (!main.isEmpty() || !off.isEmpty())) {
            // Sneaking with something in hand uses the item, not the block.
            return Outcome.RUN;
        }

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
            if (main.is(Items.RESPAWN_ANCHOR) && drawn != null) {
                // The world is empty there too: Vanilla places the anchor into the free spot,
                // and it is drawn at once instead of the air still shown there.
                AnchorVeil.predict(level, crossed, AnchorVeil.anchor(0), AnchorVeil.anchor(0));
                AnchorStats.chainedClick();
                return Outcome.RUN_PREDICTED;
            }
            return Outcome.RUN;
        }

        BlockState drawn = AnchorVeil.predicted(target);
        if (drawn == null) {
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
        return Outcome.RUN_PREDICTED;
    }

    static BlockPos catchUpTarget() {
        return catchUpTarget;
    }

    /** Whether the world now has the anchor the player saw, or nothing is drawn there any more. */
    static boolean caughtUp(ClientLevel level, BlockPos position) {
        return level == null || !AnchorVeil.isVeiled(position) || level.getBlockState(position).is(Blocks.RESPAWN_ANCHOR);
    }
}
