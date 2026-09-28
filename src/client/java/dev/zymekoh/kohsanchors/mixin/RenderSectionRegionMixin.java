package dev.zymekoh.kohsanchors.mixin;

import dev.zymekoh.kohsanchors.predict.AnchorVeil;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's chunk mesher reads every block through this region copy. A veiled anchor is drawn as
 * the veil says; the world itself is not changed. Worker threads call this; the veil's lookup is
 * lock-free and costs one volatile read while nothing is veiled.
 */
@Mixin(RenderSectionRegion.class)
abstract class RenderSectionRegionMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void kohsAnchors$veil(BlockPos position, CallbackInfoReturnable<BlockState> callback) {
        BlockState shown = AnchorVeil.shown(position.asLong());
        if (shown != null) {
            callback.setReturnValue(shown);
        }
    }
}
