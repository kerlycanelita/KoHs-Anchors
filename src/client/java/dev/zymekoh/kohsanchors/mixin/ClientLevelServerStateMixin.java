package dev.zymekoh.kohsanchors.mixin;

import dev.zymekoh.kohsanchors.glow.AnchorGlowRenderer;
import dev.zymekoh.kohsanchors.glow.AnchorTracker;
import dev.zymekoh.kohsanchors.predict.DetonationPredictor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watches the block states the server sends and the acknowledgements of the player's clicks: a
 * detonated anchor is gone, a veiled one caught up, an anchor the server kept is shown again, and
 * an anchor someone else placed is noticed. And every block change, for the glow's bounce light.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelServerStateMixin {
    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
    private void kohsAnchors$serverState(BlockPos position, BlockState state, int flags, CallbackInfo callback) {
        ClientLevel level = (ClientLevel) (Object) this;
        AnchorTracker.onServerBlock(level, position, state);
        DetonationPredictor.onServerBlock(level, position, state);
    }

    /**
     * After the acknowledgement of a click: by then the server has handled it and sent the states
     * it left behind, so an anchor still standing after its detonation click was not exploded, and
     * a placement the server did not answer with an anchor is no longer waiting for one.
     */
    @Inject(method = "handleBlockChangedAck", at = @At("TAIL"))
    private void kohsAnchors$acknowledged(int sequence, CallbackInfo callback) {
        ClientLevel level = (ClientLevel) (Object) this;
        DetonationPredictor.onAcknowledged(level, sequence);
        AnchorTracker.onAcknowledged(level, sequence);
    }

    /**
     * Every block change the client draws, whatever sent it: the explosion's, the server's, the
     * player's own predictions. The glow drops the bounce light that could reach it.
     */
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void kohsAnchors$blockChanged(BlockPos position, BlockState before, BlockState after, int flags,
            CallbackInfo callback) {
        AnchorGlowRenderer.blockChanged(position, before, after);
    }
}
