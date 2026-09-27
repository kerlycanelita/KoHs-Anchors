package dev.zymekoh.kohsanchors.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.zymekoh.kohsanchors.input.AnchorInput;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes the order of presses. {@code click} is where the keyboard and mouse handlers count a
 * press for every mapping bound to that key; this only watches it and changes nothing.
 */
@Mixin(KeyMapping.class)
abstract class KeyMappingMixin {
    @Inject(method = "click", at = @At("HEAD"))
    private static void kohsAnchors$notePress(InputConstants.Key key, CallbackInfo callback) {
        AnchorInput.onKeyClicked(key);
    }
}
