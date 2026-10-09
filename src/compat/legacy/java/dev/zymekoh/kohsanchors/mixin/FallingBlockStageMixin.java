package dev.zymekoh.kohsanchors.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.zymekoh.kohsanchors.gui.preview.AnchorStage;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.FallingBlockRenderer;
import net.minecraft.client.renderer.entity.state.FallingBlockRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.11: the stage is drawn in place of the falling block the screen asks Vanilla's
 * picture-in-picture renderer for: only that one render state, by identity, so every falling block
 * of the world passes untouched. The camera state lives one package up here.
 */
@Mixin(FallingBlockRenderer.class)
abstract class FallingBlockStageMixin {
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/FallingBlockRenderState;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At("HEAD"), cancellable = true)
    private void kohsAnchors$stage(FallingBlockRenderState state, PoseStack poses, SubmitNodeCollector collector,
            CameraRenderState camera, CallbackInfo callback) {
        if (AnchorStage.submit(state, poses, collector)) {
            callback.cancel();
        }
    }
}
