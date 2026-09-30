package dev.zymekoh.kohsanchors.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Each frame of an animated sprite lives in its own texture, by frame index. */
@Mixin(SpriteContents.AnimationState.class)
public interface AnimationStateAccessor {
    @Accessor("frameTexturesByIndex")
    Int2ObjectMap<?> kohsAnchors$frames();
}
