package dev.zymekoh.kohsanchors.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.zymekoh.kohsanchors.predict.AnchorVeil;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.BlockLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A block the veil draws differently gives the light of what is drawn, on the client's light
 * engine only. A detonated anchor drawn as air at the click stops lighting the blocks around it at
 * the same moment, instead of lighting them for a round trip from a block that is no longer shown;
 * if the server keeps the anchor, the veil lifts and its light comes back.
 *
 * <p>The world is untouched: this is the light the client draws with, recomputed by
 * {@code AnchorVeil} for the veiled block only. The integrated server's engines, which light the
 * real world, are never affected: they read a server chunk cache. While nothing is veiled the check
 * is one volatile read.</p>
 */
@Mixin(BlockLightEngine.class)
abstract class BlockLightEngineMixin {
    @ModifyExpressionValue(method = "getEmission(JLnet/minecraft/world/level/block/state/BlockState;)I",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getLightEmission()I"))
    private int kohsAnchors$drawnEmission(int emission, @Local(argsOnly = true) long node) {
        BlockState shown = AnchorVeil.shown(node);
        if (shown == null || !(((LightEngineAccessor) (Object) this).kohsAnchors$chunkSource() instanceof ClientChunkCache)) {
            return emission;
        }
        return shown.getLightEmission();
    }
}
