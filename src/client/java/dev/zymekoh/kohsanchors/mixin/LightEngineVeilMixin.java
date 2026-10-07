package dev.zymekoh.kohsanchors.mixin;

import dev.zymekoh.kohsanchors.predict.AnchorVeil;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The client's light engines read a veiled block as it is drawn. A detonated anchor drawn as air
 * lets light through at once, like the air the server is about to send: the floor under it and
 * the blocks around it are lit as they will be, instead of a black square where the hidden anchor
 * still blocked every light for a round trip.
 *
 * <p>Only the client's engines: a server's read a server chunk cache. While nothing is veiled the
 * check is one volatile read.</p>
 */
@Mixin(LightEngine.class)
abstract class LightEngineVeilMixin {
    @Shadow
    @Final
    protected LightChunkGetter chunkSource;

    @Inject(method = "getState", at = @At("HEAD"), cancellable = true)
    private void kohsAnchors$drawnState(BlockPos position, CallbackInfoReturnable<BlockState> callback) {
        BlockState shown = AnchorVeil.shown(position.asLong());
        if (shown != null && this.chunkSource instanceof ClientChunkCache) {
            callback.setReturnValue(shown);
        }
    }
}
