package dev.zymekoh.kohsanchors.mixin;

import dev.zymekoh.kohsanchors.predict.AnchorVeil;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No outline around an anchor that is drawn as gone. The crosshair still points at the block the
 * world has there, so the outline is dropped from the frame, not from the raycast. 26.2 and 26.3
 * extract it in LevelExtractor; the older eras replace this file.
 */
@Mixin(LevelExtractor.class)
abstract class VeiledOutlineMixin {
    @Inject(method = "extractBlockOutline", at = @At("TAIL"))
    private void kohsAnchors$hideVeiledOutline(Camera camera, LevelRenderState state, CallbackInfo callback) {
        if (state.blockOutlineRenderState == null
                || !(Minecraft.getInstance().hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockState shown = AnchorVeil.predicted(hit.getBlockPos());
        if (shown != null && shown.isAir()) {
            state.blockOutlineRenderState = null;
        }
    }
}
