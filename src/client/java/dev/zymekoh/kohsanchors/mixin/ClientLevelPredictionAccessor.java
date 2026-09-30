package dev.zymekoh.kohsanchors.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The sequence numbers the client stamps on every block interaction it sends. The server answers
 * each one with an acknowledgement after it has handled the click and sent the block's new state.
 */
@Mixin(ClientLevel.class)
public interface ClientLevelPredictionAccessor {
    @Accessor("blockStatePredictionHandler")
    BlockStatePredictionHandler kohsAnchors$predictionHandler();
}
