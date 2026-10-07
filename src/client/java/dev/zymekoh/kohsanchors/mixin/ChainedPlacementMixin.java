package dev.zymekoh.kohsanchors.mixin;

import dev.zymekoh.kohsanchors.predict.DetonationPredictor;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A click the anchor chain sends at an anchor that is still exploding places nothing in the client
 * world: Vanilla would put the block beside the old anchor, or glowstone in the fire it left, where
 * the server will not, and the next clicks would aim at that ghost. The chain draws the anchor or
 * the charge the server will make instead. The same for a click the chain runs as a detonation:
 * the anchor is drawn charged by glowstone the world has not seen yet, so Vanilla, reading the
 * world's uncharged anchor, would put a new anchor on its face, on top of the one exploding. The
 * packet is the same: Vanilla builds it from the crosshair, before and whatever this returns.
 */
@Mixin(BlockItem.class)
abstract class ChainedPlacementMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void kohsAnchors$chained(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> callback) {
        if (context.getLevel().isClientSide() && (DetonationPredictor.chaining() || DetonationPredictor.runningDetonation())) {
            callback.setReturnValue(InteractionResult.SUCCESS);
        }
    }
}
