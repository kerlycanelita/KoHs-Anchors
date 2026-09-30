package dev.zymekoh.kohsanchors.mixin;

import dev.zymekoh.kohsanchors.skin.AtlasSkin;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The animation Vanilla builds for an animated sprite when the atlas is uploaded. The anchor top's
 * is remembered, because its frames, not the atlas, are what the skin has to rewrite. The handler
 * leaves out the target's arguments, whose GPU types moved package in 26.3.
 */
@Mixin(SpriteContents.class)
abstract class SpriteContentsMixin {
    @Inject(method = "createAnimationState", at = @At("RETURN"))
    private void kohsAnchors$animation(CallbackInfoReturnable<Object> callback) {
        Object state = callback.getReturnValue();
        if (state != null) {
            AtlasSkin.onAnimationState((SpriteContents) (Object) this, state);
        }
    }
}
