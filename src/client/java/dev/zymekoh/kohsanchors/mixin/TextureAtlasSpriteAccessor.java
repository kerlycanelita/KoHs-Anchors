package dev.zymekoh.kohsanchors.mixin;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The border of repeated edge pixels around a sprite in the atlas, which mipmaps read. */
@Mixin(TextureAtlasSprite.class)
public interface TextureAtlasSpriteAccessor {
    @Accessor("padding")
    int kohsAnchors$padding();
}
