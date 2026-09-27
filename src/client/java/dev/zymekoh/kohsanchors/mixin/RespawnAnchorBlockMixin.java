package dev.zymekoh.kohsanchors.mixin;

import dev.zymekoh.kohsanchors.predict.DetonationPredictor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Learns, without changing it, that the local player just used a charged anchor. The injection
 * reads Vanilla's result at {@code RETURN}; the server-side call in singleplayer is ignored.
 */
@Mixin(RespawnAnchorBlock.class)
abstract class RespawnAnchorBlockMixin {
    @Inject(method = "useWithoutItem", at = @At("RETURN"))
    private void kohsAnchors$predictDetonation(BlockState state, Level level, BlockPos position, Player player,
            BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> callback) {
        DetonationPredictor.onAnchorUsed(level, position, state, player, callback.getReturnValue());
    }
}
