package dev.zymekoh.kohsanchors.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.zymekoh.kohsanchors.glow.AnchorGlowRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The anchor glow is submitted with the frame's block entities, into the same collector and
 * relative to the same camera position, so it is drawn in the main pass after the terrain it is
 * tested against. 26.1.x and 1.21.11 pass the storage and their state class under other names and
 * replace this file.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererGlowMixin {
    @Inject(method = "submitBlockEntities", at = @At("TAIL"))
    private void kohsAnchors$glow(PoseStack poses, LevelRenderState state, SubmitNodeCollector collector,
            CallbackInfo callback) {
        AnchorGlowRenderer.submit(poses, collector, state.cameraRenderState.pos, state.cameraRenderState.orientation);
    }
}
