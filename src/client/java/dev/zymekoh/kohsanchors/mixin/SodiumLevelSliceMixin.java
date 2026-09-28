package dev.zymekoh.kohsanchors.mixin;

import dev.zymekoh.kohsanchors.predict.AnchorVeil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium meshes chunks from its own copy of the world, so the veil is applied there too. Nothing
 * happens without Sodium ({@link Pseudo}), and a Sodium whose copy is shaped differently only
 * loses the veil ({@code require = 0}), never the game.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.world.LevelSlice")
abstract class SodiumLevelSliceMixin {
    @Inject(method = "getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"),
            cancellable = true, require = 0)
    private void kohsAnchors$veil(int x, int y, int z, CallbackInfoReturnable<BlockState> callback) {
        BlockState shown = AnchorVeil.shown(BlockPos.asLong(x, y, z));
        if (shown != null) {
            callback.setReturnValue(shown);
        }
    }

    @Inject(method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void kohsAnchors$veilAt(BlockPos position, CallbackInfoReturnable<BlockState> callback) {
        BlockState shown = AnchorVeil.shown(position.asLong());
        if (shown != null) {
            callback.setReturnValue(shown);
        }
    }
}
