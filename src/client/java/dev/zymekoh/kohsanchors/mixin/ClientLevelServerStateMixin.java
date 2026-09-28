package dev.zymekoh.kohsanchors.mixin;

import dev.zymekoh.kohsanchors.predict.DetonationPredictor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Watches the block states the server sends: a detonated anchor is gone, a veiled one caught up. */
@Mixin(ClientLevel.class)
abstract class ClientLevelServerStateMixin {
    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
    private void kohsAnchors$serverState(BlockPos position, BlockState state, int flags, CallbackInfo callback) {
        DetonationPredictor.onServerBlock((ClientLevel) (Object) this, position, state);
    }
}
