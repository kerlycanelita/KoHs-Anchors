package dev.zymekoh.kohsanchors.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.zymekoh.kohsanchors.input.AnchorInput;
import net.minecraft.client.Minecraft;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The three moments of {@code handleKeybinds} that anchor input needs. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void kohsAnchors$beginPass(CallbackInfo callback) {
        AnchorInput.beginPass((Minecraft) (Object) this);
    }

    /**
     * The hotbar loop reads {@code keyHotbarSlots} once per slot; the first read is the moment
     * before Vanilla consumes any number key. {@link AnchorInput#atHotbarPoint} runs only once.
     */
    @Inject(method = "handleKeybinds", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/Options;keyHotbarSlots:[Lnet/minecraft/client/KeyMapping;",
            opcode = Opcodes.GETFIELD))
    private void kohsAnchors$sequenceBurst(CallbackInfo callback) {
        AnchorInput.atHotbarPoint((Minecraft) (Object) this);
    }

    /**
     * Ordinal 0 is the call inside Vanilla's {@code while (keyUse.consumeClick())} loop: the target
     * is read again if needed, and a click aimed at an anchor that is still detonating is held.
     * Ordinal 1, the repeat for a held key, is deliberately left alone.
     */
    @WrapWithCondition(method = "handleKeybinds", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;startUseItem()V", ordinal = 0))
    private boolean kohsAnchors$admitUse(Minecraft minecraft) {
        return AnchorInput.admitQueuedUse(minecraft);
    }
}
