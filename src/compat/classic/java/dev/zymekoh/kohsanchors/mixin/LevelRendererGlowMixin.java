package dev.zymekoh.kohsanchors.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.zymekoh.kohsanchors.glow.AnchorGlowRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 26.1.x: block entities are submitted into the storage itself. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererGlowMixin {
    @Inject(method = "submitBlockEntities", at = @At("TAIL"))
    private void kohsAnchors$glow(PoseStack poses, LevelRenderState state, SubmitNodeStorage storage,
            CallbackInfo callback) {
        dev.zymekoh.kohsanchors.glow.EnemySkinRenderer.submit(poses, storage, state.cameraRenderState.pos);
        AnchorGlowRenderer.submit(poses, storage, state.cameraRenderState.pos, state.cameraRenderState.orientation);
        dev.zymekoh.kohsanchors.predict.AnchorFade.submit(poses, storage, state.cameraRenderState.pos);
        dev.zymekoh.kohsanchors.glow.SafeAnchorView.submit(poses, storage, state.cameraRenderState.pos);
    }
}
