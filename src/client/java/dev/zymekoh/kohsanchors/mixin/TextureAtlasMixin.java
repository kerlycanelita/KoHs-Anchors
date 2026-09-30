package dev.zymekoh.kohsanchors.mixin;

import dev.zymekoh.kohsanchors.skin.AtlasSkin;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A freshly uploaded block atlas holds the resource pack's anchor again: the skin is written anew. */
@Mixin(TextureAtlas.class)
abstract class TextureAtlasMixin {
    @Inject(method = "upload", at = @At("TAIL"))
    private void kohsAnchors$uploaded(SpriteLoader.Preparations preparations, CallbackInfo callback) {
        AtlasSkin.onAtlasUploaded((TextureAtlas) (Object) this);
    }
}
