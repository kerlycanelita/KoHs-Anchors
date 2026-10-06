package dev.zymekoh.kohsanchors.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The server states Vanilla keeps aside, block by block, while the player's own predictions there
 * wait for their acknowledgement. Removing a block's entry makes Vanilla show the server's state
 * for it at once, as for any block the player did not predict.
 */
@Mixin(BlockStatePredictionHandler.class)
public interface BlockStatePredictionAccessor {
    @Accessor("serverVerifiedStates")
    Long2ObjectOpenHashMap<?> kohsAnchors$serverVerifiedStates();
}
