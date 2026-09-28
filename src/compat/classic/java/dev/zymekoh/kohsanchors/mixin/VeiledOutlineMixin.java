package dev.zymekoh.kohsanchors.mixin;

import dev.zymekoh.kohsanchors.predict.AnchorVeil;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
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
 * world has there, so the outline is dropped from the frame, not from the raycast. 26.1.x extracts
 * it in LevelRenderer.
 */
@Mixin(LevelRenderer.class)
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
